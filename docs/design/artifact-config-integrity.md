# Design: integrity story for shipped and imported client artifacts

Status: SPIKE / design only (plan `plans/030-spike-artifact-config-integrity.md`).
No prototype code was written for this pass — see "Prototype" at the bottom
for why.

Grounded against commit `8ee361ea1` (plan's "planned at" commit) plus the
commits that landed on `batch2-plans-007-031` since then and touch the files
this design depends on: `f2cc3f5f9`/`b1eb26bbc` (plan 026, tar-slip +
symlink containment in `MultiRTUtils`), and the earlier-in-batch plans 013
(config-import validation via `ImportGuard`), 014 (config-import transport:
`network_security_config.xml`, `MAX_CONFIG_BYTES`, emptied
`DEFAULT_CONFIG_URL`), and 015 (zip-bomb caps in `Tools.ZipTool.unzip`). None
of this drift invalidates the design below — if anything it retires or
strengthens several rows of the inventory the plan wrote against a staler
snapshot of these files (see "Drift notes"). Details inline per boundary.

## 1. Inventory of untrusted-input boundaries

Each row: where external/attacker-influenceable bytes enter the app, and what
check exists **today** (re-verified live, not from the plan's stale excerpts).

| # | Boundary | Entry point (file:line) | What's untrusted | Check today |
|---|---|---|---|---|
| 1 | Shipped assets unpacked at first run (`rt4.jar`, `config.json`, bundled plugin zips, `caciocavallo`/`lwjgl3`/`security` components) | `AsyncAssetManager.java:110-131` (`unpackComponents`), `:93-108` (`unpackSingleFiles`), `:133-169` (`extractAllPlugins`) | Nothing external at rest — these ship inside the signed APK. The "threat" is only supply-chain: a compromised build pipeline or a hand-edited asset before packaging | None. `Tools.copyAssetFile` is a byte copy; no hash of any kind |
| 2 | Asset-version gate (`build_version.txt`) deciding whether to re-copy #1 | `AsyncAssetManager.java:64-90` | Not attacker-controlled at runtime (it's a checked-in file), but it's a bare integer, not a manifest, so it can't detect a partially-corrupted or tampered copy | A `>` comparison against a stored int; no content verification of what it gates |
| 3 | The one existing hash primitive | `Tools.java:658-672` (`compareSHA1`) | N/A — this is the primitive, not a boundary | SHA-1, hardened fail-closed by plan 003, but **has zero live call sites** as of this pass (see Drift notes) — even weaker than the plan assumed |
| 4 | Config import over HTTP (Compose Settings) | `SettingsActivity.kt:169-220` (`importServerConfig`), `:261-283` (`applyConfigJson`) | A response from a URL the user typed into a text field — server identity and content both untrusted | `MAX_CONFIG_BYTES` (512 KB) cap on the streamed body (plan 014); `network_security_config.xml` restricts cleartext `http://` to loopback + three literal RFC1918 network addresses (plan 014); `applyConfigJson` requires a real `org.json.JSONObject` parse plus presence of `ip_address`/`ip_management` and `getInt` type-checks on ports — still no cryptographic verification |
| 5 | Config import via file picker (Compose Settings) | `SettingsActivity.kt:133-136`, `:227-254` (`applyConfigFromUri`) | A `content://` URI the user picked, which may resolve to any app's shared storage | `ImportGuard.isWithinSizeLimit` (5 MB cap) during the streamed read, then the same `applyConfigJson` parse/shape check as #4 |
| 6 | Config import via legacy "Advanced" dialog (raw overwrite) | `MyDialogFragment.java:192-246` (`onActivityResult`, `FILE_SELECT_CODE_JSON`) | Same `content://` picker risk as #5 | `ImportGuard.isWithinSizeLimit` (size cap), `ImportGuard.isValidJsonObject` (structural brace/bracket pre-filter), **plus** a real `org.json.JSONObject` parse added since the plan was written (`:229-233`) before the byte-for-byte write to `config.json`. This closes the gap the plan's "Current state" flagged (no JSON check at all) — the legacy path is now on par with the Compose path for shape validation |
| 7 | Plugin-ZIP import via legacy dialog | `MyDialogFragment.java:247-286` (`onActivityResult`, `FILE_SELECT_CODE_ZIP`), `AsyncAssetManager.extractPluginZip` (`:172-174`) | A `content://` ZIP the user picked; extracted straight into the live `plugins/` dir the running JVM's `-DpluginDir=` points at (`Tools.java:219`) | `ImportGuard.isWithinSizeLimit` on the copy-to-tempfile step, then `Tools.ZipTool.unzip`'s zip-slip guard (plan 001) **and** its zip-bomb caps — `MAX_ENTRY_COUNT` 5000, `MAX_ENTRY_UNCOMPRESSED_BYTES` 50 MB, `MAX_TOTAL_UNCOMPRESSED_BYTES` 200 MB (plan 015, `Tools.java:560-654`). No content/identity check of what's inside the zip (arbitrary `.class`/`.jar` payload for the JVM to load) |
| 8 (new) | JRE runtime tar unpack (`universal.tar.xz`, `bin-<arch>.tar.xz`) | `AsyncAssetManager.java:33-62` (`unpackRuntime`) → `MultiRTUtils.installRuntimeNamedBinpack` | Ships inside the APK today (same supply-chain-only threat as #1), but `MultiRTUtils` also has a **user-facing runtime-import path** (multirt lets a user supply their own JRE tar — confirmed via `grep -rn "tar-slip\|symlink\|canonicalTarget" multirt/MultiRTUtils.java`) which is genuinely untrusted | Tar-slip path-containment check + symlink-target containment (plan 026, `MultiRTUtils.java:245,250-258`) — no hash/signature check of tar contents either way |

The `grep -rn "openInputStream\|HttpURLConnection\|copyAssetFile"` sweep the
plan's Step 1 specifies turned up one boundary beyond the plan's original six
(#8, the runtime-tar import path) and no others; all other
`openInputStream`/`HttpURLConnection` hits are the file-picker/URL-fetch call
sites already covered by #4-#7.

## 2. Trust anchor per boundary

**Boundary 1 (shipped assets) and 2 (version gate) — build-time manifest,
anchored by the APK signature.**
Generate a SHA-256 per shipped asset at build time (a small Gradle task
writing `assets/asset_manifest.json`, e.g.
`{ "rt4.jar": "<sha256>", "config.json": "<sha256>" }`), mirroring the
existing `build_version.txt` precedent (`AsyncAssetManager.java:72-81`, hand-
maintained today per `grep -n "build_version" app_pojavlauncher/build.gradle`
returning no matches — confirmed still true this pass). A new
`Tools.verifySha256` call inside `unpackComponents`/`extractAllPlugins`,
after `Tools.copyAssetFile` and before `saveAssetVersion`, would compare the
copied file against the manifest. This is trustworthy because the manifest
ships inside the same signed APK as the assets it describes — the actual
trust anchor is "the APK signing key," which already exists and is out of
scope to change here. This is the only boundary where SHA-256 buys a real,
non-circular guarantee (detects corruption/tamper of the copy step itself;
does not (and cannot) prove the *shipped* asset itself is what upstream
intended — that's an APK-signing-and-review problem, already solved at the
level this repo can affect).

**Boundary 3 (`compareSHA1`) — not a boundary, a primitive; propose extending
it (see §3), do not fix in place.**

**Boundaries 4 and 5 (Compose config import, URL and file) and 6 (legacy
config import) — shape + host validation only, no cryptographic trust
anchor exists.** A stricter schema check (required keys present, correct
types — already partially done via `getInt`/`has`; extend to also validate
`ip_address`/`ip_management` look like a plausible host string and ports
fall in `1..65535`) is the practical ceiling for these three boundaries.
Hashing the downloaded/picked bytes with SHA-256 would be trivial to add but
provides **zero trust benefit** without something authoritative to compare
against — there is no publisher-signed hash or key for an arbitrary
community server's `config.json` (see Open Questions, §5, first item). The
existing size caps (#4/#5/#6) and the real-parse requirement (#4/#5/#6) are
the actual mitigations in place; a SHA-256 of the body would only be useful
if the app later gains a curated allowlist of known-good server hashes,
which is out of scope here (rejected as "invents a local-only signing
scheme that gives false confidence" per this plan's own STOP condition).

**Boundary 7 (plugin-ZIP import) — explicitly deferred, capped by other
in-flight controls, same "no signer" problem as #4-#6.** Plan 015's
zip-bomb caps and plan 001's zip-slip guard are the appropriate mitigation
for resource/path-traversal risk on this path. Cryptographic verification of
a community plugin has the identical "no authoritative signer" problem as
config import — a plugin author would need a keypair the app trusts and
publishes, which does not exist. The pragmatic near-term mitigation
(cheaper than a signing scheme) is a one-time "install untrusted code?"
confirmation dialog before extraction — see Open Questions §5, fourth item.

**Boundary 8 (runtime tar import) — same category as #1 for the bundled
copies (build-time manifest would apply equally well: hash
`universal.tar.xz`/`bin-<arch>.tar.xz` the same way as `rt4.jar`), but the
user-supplied-runtime-import path (if/when a user points multirt at their
own JRE tar) reduces to the same "no signer for external content" problem as
#4-#7.** The tar-slip/symlink containment already landed (plan 026) is the
correct mitigation for the extraction-safety risk regardless of hashing;
hashing would only add value for the bundled-copy half of this boundary.

## 3. `compareSHA1` → SHA-256 upgrade path (proposal, not executed)

Add a new method alongside (not replacing) `compareSHA1`, same fail-closed
shape as the plan-003-hardened version:

```java
// Tools.java, near compareSHA1 (~line 658)
public static boolean verifySha256(File f, String sourceSHA) {
    try {
        String sha256_dst;
        try (InputStream is = new FileInputStream(f)) {
            sha256_dst = new String(Hex.encodeHex(
                    org.apache.commons.codec.digest.DigestUtils.sha256(is)));
        }
        // A null expected hash means the caller opted out of verification (best-effort).
        if (sourceSHA == null) return true;
        return sha256_dst.equalsIgnoreCase(sourceSHA);
    } catch (IOException e) {
        // Fail closed: an unreadable/corrupt file is NOT verified.
        Log.w("SHA256", "Hash check failed to read file; treating as mismatch", e);
        return false;
    }
}
```

`compareSHA1` is left untouched so the (currently zero, per Drift notes)
`checkLibraries`-gated callers — and any future ones — are undisturbed;
this is purely additive.

**Proposed new call sites (only these, not a repo-wide swap):**
- `AsyncAssetManager.java:121` — guard
  `Tools.copyAssetFile(ctx,"rt4.jar",Tools.DIR_DATA, false)` inside
  `unpackComponents`, checked against a `rt4.jar` entry in the new
  build-time `asset_manifest.json` (§2, Boundary 1).
- `AsyncAssetManager.java:122` — guard the `config.json` copy the same way,
  once the manifest exists.
- (Follow-up, not this pass) `AsyncAssetManager.java:162-166`
  (`extractAllPlugins`'s per-plugin-zip copy) for the bundled plugin zips,
  once the manifest is extended to cover them.

## 4. Prototype

Not attempted this pass. The plan marks the prototype OPTIONAL and
recommends exactly one boundary (Boundary 1, `rt4.jar`). Skipped here
because:
- The spike's primary deliverable is this design doc; the plan's own Done
  Criteria treat the inventory/design/open-questions as the required
  artifact and the prototype as optional.
- A real prototype needs a Gradle task (or checked-in generation script) to
  produce `asset_manifest.json` from the actual asset bytes at build time —
  doing that credibly (not just hand-typing one hash) is itself a small
  build-system change, which risks scope creep into "rolling the SHA-256
  manifest out" that the plan's Scope section explicitly defers to a
  follow-up build plan.
- Nothing in this design depends on having built the prototype to be
  actionable — §2/§3 above give a follow-up plan a concrete method
  signature, exact call sites, and the manifest shape to implement against.

If a follow-up plan does attempt it: keep the manifest to the one `rt4.jar`
entry as this plan's Step 4 specifies, add the `Tools.verifySha256` guard at
`AsyncAssetManager.java:121` only, and on mismatch log-and-skip using the
corrupted copy rather than crashing the unpack thread (matching the existing
`catch (IOException)` pattern in `unpackComponents`).

## 5. Open Questions

1. **Where does an authoritative hash/signature come from for imported
   content (Boundaries 4-7)?** The shipped-asset manifest (§2, Boundary 1)
   only solves trust for content that ships inside this repo's own signed
   APK. A community server's `config.json` or a community plugin has no
   equivalent anchor without either (a) a signing key the server/plugin
   author holds and the app ships a matching public key for, or (b) a
   curated allowlist server the app calls out to at runtime. **Both require
   infrastructure outside this repo** (a server-side signing/publishing
   process, or a maintained allowlist service) — this is the expected
   conclusion the plan anticipated, and it is recorded here rather than
   inventing a local-only signing scheme. **Recommendation: unresolved —
   needs a trust-anchor decision outside this repo** (specifically, whether
   the 2009Scape server project would ever publish signed configs, which is
   not this repo's call to make).
2. **Does Android's cleartext-traffic policy block or allow the `http://`
   fetch in `importServerConfig`?** Resolved this pass, not open: yes, it's
   restricted. `AndroidManifest.xml:38` sets
   `android:networkSecurityConfig="@xml/network_security_config"`, and that
   file (added by plan 014) sets `cleartextTrafficPermitted="false"` at the
   base config, carving out cleartext only for `127.0.0.1`, `localhost`, and
   three literal RFC1918 network addresses (`10.0.0.0`, `192.168.0.0`,
   `172.16.0.0`) — which, per the file's own comment, do **not** literally
   match a real assigned LAN host IP (e.g. `192.168.0.243`) since Android's
   network-security-config has no CIDR/wildcard form for IP literals. **This
   is a known, documented limitation of an already-landed mitigation, not a
   new finding** — flagging it here only so a follow-up plan doesn't
   re-discover it independently.
3. **Is upgrading imported-config transport to `https://` alone (without
   content signing) worth doing, given `DEFAULT_CONFIG_URL` is now empty
   (plan 014 removed the hardcoded LAN IP default) and a real deployment's
   LAN IP will rarely have a valid TLS certificate?** Recommendation:
   marginal value on its own — a self-signed or absent cert on a LAN server
   means most operators would either disable cert validation (defeating the
   purpose) or fail every import. TLS on a LAN-facing config server without
   a private CA the app also trusts doesn't move the actual trust boundary
   (§ Open Question 1 still applies — TLS proves transport confidentiality,
   not content authenticity). Treat as separate from, and lower-value than,
   the boundary-4-7 signing question above.
4. **Should plugin-ZIP import (Boundary 7) require an explicit "install
   untrusted code" confirmation dialog as a stop-gap, independent of
   cryptographic verification?** Recommendation: yes, cheaply — it's a
   one-screen addition to the existing `MyDialogFragment` "Load plugin" flow
   (`MyDialogFragment.java:247-286`), costs no new infrastructure, and
   matches how the size/zip-slip/zip-bomb caps already frame this path as
   "extraction is safe, content trust is not." Scope it as a small follow-up
   UI plan, not part of this spike.

## Drift notes

The plan's "Current state" excerpts were captured at commit `8ee361ea1`
(this plan's own "planned at" commit). Re-reading the live files at this
plan's execution time shows four of the six files/behaviors it excerpted have
since changed, all as a *result* of other in-batch plans landing first
(013/014/015/026), not unrelated drift:
- `MyDialogFragment.java`'s JSON-import path (Boundary 6/plan's Boundary 6)
  now does a real `org.json.JSONObject` parse plus `ImportGuard` size/shape
  checks — the plan's excerpt showed a raw byte-for-byte copy with zero
  validation. This is strictly better than what the plan assumed; no
  regression, so it does not trigger the plan's drift STOP condition (which
  is about *mismatched* excerpts invalidating the design, not about
  discovering the state improved).
- `SettingsActivity.kt`'s `DEFAULT_CONFIG_URL` is now `""` (plan 014), not
  the hardcoded LAN IP the plan's excerpt showed; `MAX_CONFIG_BYTES` and the
  `network_security_config.xml` cleartext restriction now exist.
- `Tools.java` gained the zip-bomb caps in `ZipTool.unzip` (plan 015) beyond
  the zip-slip guard the plan cited.
- `AsyncAssetManager.java` differs by 3 lines from the plan's excerpt
  (progress-repository failure reporting added by an unrelated fix commit,
  `13fcb887c`) — cosmetic, does not change any boundary's shape.
None of this changes the shape of the per-boundary design in §2: the
boundaries needing shape/host validation already have more of it than the
plan assumed, and the boundaries needing a build-time manifest still have
none. The design and open questions above are written against the live
state confirmed in this pass.
