# Plan 030: Spike/design — integrity story for shipped and imported client artifacts

> **Executor instructions**: This is a DESIGN/SPIKE plan, not a build-everything
> plan. The deliverable is an inventory of every unverified untrusted input, a
> per-boundary validation design, and OPTIONALLY a minimal prototype at exactly
> ONE boundary — not full rollout, not signing infrastructure. Follow the steps
> in order; run every verification command; if a STOP condition triggers, stop
> and report — do not improvise past it. When done, update this plan's row in
> `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 8ee361ea1..HEAD -- app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/tasks/AsyncAssetManager.java app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MyDialogFragment.java app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt`
> If any of these changed since this plan was written, re-read the live file
> and compare against the "Current state" excerpts before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: L (coarse — the inventory and design are boundable, but "where
  does an authoritative hash come from for imported content" may reveal a
  server-side dependency that inflates true effort; see STOP conditions)
- **Risk**: MED
- **Depends on**: pairs naturally with `plans/013-*.md`, `plans/014-*.md`,
  `plans/015-*.md` (referenced by number per the advisor's plan set; if they
  don't exist yet in `plans/` when you run this, proceed with this plan's own
  inventory anyway — it duplicates minimal effort at worst) and builds on the
  already-landed `plans/001-zip-slip-plugin-import.md` and
  `plans/003-comparesha1-fake-match.md` (both DONE per `plans/README.md`).
- **Category**: direction (security)
- **Planned at**: commit `8ee361ea1`, 2026-07-10

## Why this matters

The launcher runs an embedded JVM (`rt4.jar`, the RT4-Client) with a classpath
and a `config.json` that decide what code executes and what server it connects
to — a tampered config or plugin silently redirects a player's client or loads
attacker-controlled code. Today there is exactly one integrity primitive in
the codebase (`Tools.compareSHA1`, SHA-1, hardened for its failure mode by
`plans/003-comparesha1-fake-match.md` but not applied to `rt4.jar`, the
plugin zips, or `config.json`), and the two config-import paths added in the
RS2 settings restyle (`SettingsActivity.kt`) fetch over plaintext `http://`
and parse with nothing beyond `org.json.JSONObject` — no shape, host, or
content validation. This plan inventories every such boundary and designs
(without necessarily building) a coherent validation story, so a future
hardening pass has a map instead of ad-hoc patches.

## Current state

Files and roles:
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java` — hosts
  `compareSHA1` (the one hash-check primitive) and `ZipTool.unzip` (extraction,
  already zip-slip-guarded per plan 001).
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/tasks/AsyncAssetManager.java`
  — unpacks `rt4.jar`, `config.json`, and all `plugins/*.zip` from APK assets
  into app-writable storage, with NO hash check anywhere in the unpack path.
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt` —
  the two user-facing config-import paths (URL fetch, file pick).
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MyDialogFragment.java`
  — the legacy "Advanced" dialog's plugin-ZIP import and raw config.json
  file-picker overwrite.

Confirmed excerpts (re-read at commit `8ee361ea1`):

**Boundary 1 — shipped assets, unpacked with no hash check.**
`AsyncAssetManager.java:108-128` (`unpackComponents`):
```java
public static void unpackComponents(Context ctx){
    ProgressLayout.setProgress(ProgressLayout.EXTRACT_COMPONENTS, 0);
    sExecutorService.execute(() -> {
        try {
            boolean overwrite = assetVersionChanged(ctx);
            unpackComponent(ctx, "caciocavallo", false);
            unpackComponent(ctx, "caciocavallo17", false);
            unpackComponent(ctx, "lwjgl3", false);
            unpackComponent(ctx, "security", true);
            Tools.copyAssetFile(ctx,"rt4.jar",Tools.DIR_DATA, false); // Change this to true if you're working on client features.
            Tools.copyAssetFile(ctx,"config.json",Tools.DIR_DATA, overwrite);
            extractAllPlugins(ctx);
            if (overwrite) saveAssetVersion(ctx);
        } catch (IOException e) {
            Log.e("AsyncAssetManager", "Failed o unpack components !",e );
        }
        ProgressLayout.clearProgress(ProgressLayout.EXTRACT_COMPONENTS);
    });
}
```
`Tools.copyAssetFile` is a plain byte copy — no hash comparison anywhere in
this method or its callees. The "already unpacked?" guard is a boolean
overwrite flag, not an integrity check (this is the `false`→`true` toggle
`CLAUDE.md` tells contributors to flip while iterating on client features —
it exists for iteration convenience, not security). `extractAllPlugins`
(`AsyncAssetManager.java:130-166`) unzips each bundled plugin the same way,
also with no hash check, though it IS already zip-slip-guarded transitively
via `Tools.ZipTool.unzip` (`Tools.java:597-599`, from plan 001).

**Boundary 2 — the asset-version gate is a plain integer, not a manifest.**
`AsyncAssetManager.java:63-90`:
```java
private static final String PREF_ASSET_VERSION = "asset_version";
...
private static boolean assetVersionChanged(Context ctx) {
    try (InputStream is = ctx.getAssets().open("build_version.txt")) {
        int bundled = Integer.parseInt(readInputStreamFully(is));
        int stored = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
                .getInt(PREF_ASSET_VERSION, 0);
        return bundled > stored;
    } catch (Exception e) {
        return true;
    }
}
```
`build_version.txt` (`app_pojavlauncher/src/main/assets/build_version.txt`,
currently contains `4`) is a checked-in static file, NOT generated by
`build.gradle` (confirmed: `grep -n "build_version" app_pojavlauncher/build.gradle`
returns no matches) — it's manually bumped. This is the natural hook: a
build-time SHA-256 manifest could be generated and checked in alongside (or
instead of) this counter, and `unpackComponents`/`extractAllPlugins` could
verify each copied file's hash against it before/after copy.

**Boundary 3 — the one existing hash primitive is SHA-1, and is opt-in per
caller (nullable expected-hash parameter).**
`Tools.java:622-636` (already hardened by plan 003 — fails closed on read
error, but still SHA-1 and still requires a caller to pass a real expected
hash; passing `null` opts out entirely):
```java
public static boolean compareSHA1(File f, String sourceSHA) {
    try {
        String sha1_dst;
        try (InputStream is = new FileInputStream(f)) {
            sha1_dst = new String(Hex.encodeHex(org.apache.commons.codec.digest.DigestUtils.sha1(is)));
        }
        // A null expected hash means the caller opted out of verification (best-effort).
        if (sourceSHA == null) return true;
        return sha1_dst.equalsIgnoreCase(sourceSHA);
    } catch (IOException e) {
        // Fail closed: an unreadable/corrupt file is NOT verified.
        Log.w("SHA1", "Hash check failed to read file; treating as mismatch", e);
        return false;
    }
}
```
Confirm current callers before designing where to extend this:
`grep -rn "compareSHA1" app_pojavlauncher/src/main/java` (used today for
downloaded-library checks gated by the `checkLibraries` preference,
`SettingsActivity.kt` `MISC_BOOLS` at `SettingsActivity.kt:96`
`BoolPref("checkLibraries", "Verify library integrity", true)` — NOT applied to
`rt4.jar`/plugins/config).

**Boundary 4 — config import over plaintext HTTP, shape-checked only.**
`SettingsActivity.kt:151-178` (`importServerConfig`):
```kotlin
private fun importServerConfig(repo: PreferencesRepository) {
    val url = repo.getString("serverConfigUrl", DEFAULT_CONFIG_URL)?.trim().orEmpty()
    if (url.isEmpty()) {
        Toast.makeText(this, "Set a config import URL first", Toast.LENGTH_SHORT).show()
        return
    }
    Toast.makeText(this, "Importing config from $url…", Toast.LENGTH_SHORT).show()
    Thread {
        try {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val result = applyConfigJson(repo, body)
            ...
```
`DEFAULT_CONFIG_URL` (`SettingsActivity.kt:103`) is itself `http://` (cleartext,
a hardcoded LAN IP). `applyConfigJson` (`:204-217`) does nothing beyond
`JSONObject(body)` + `optString`/`has`/`getInt` presence checks — no schema
validation (e.g. a config with an unexpected extra field, wrong types on
`wl_port`, or a maliciously large body is not rejected beyond a
`JSONException`/`IllegalStateException` being caught and shown as a toast).
There is no cleartext-traffic restriction visible in this file for this
specific connection (Android's default cleartext policy applies; not verified
in this pass — see Open Questions).

**Boundary 5 — plugin-ZIP import via the legacy dialog, no integrity check.**
`MyDialogFragment.java:214-243` (`onActivityResult`, `FILE_SELECT_CODE_ZIP`
branch) copies a user-picked file to `cacheDir/temp.zip` then calls
`AsyncAssetManager.extractPluginZip(tempFile)` (`AsyncAssetManager.java:169-171`),
which only does the zip-slip-guarded unzip (`Tools.java:597-599`) — no hash,
signature, or source check on the ZIP's contents at all. Any file the user
picks (or is tricked into picking, e.g. via a shared-storage app) that looks
like a ZIP is extracted straight into the live `plugins/` directory that
`-DpluginDir=` (`Tools.java:219`) hands to the running JVM.

**Boundary 6 — the raw config.json file-picker overwrite in the legacy dialog
has even less validation than the Compose path.**
`MyDialogFragment.java:190-213` — a raw byte-for-byte copy over
`DIR_DATA/config.json` with NO JSON parse/shape check at all (unlike
`SettingsActivity.applyConfigJson`, this path doesn't even validate it's valid
JSON before it becomes the file the RT4-Client reads at launch).

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Build APK (Docker, no host JDK/SDK) | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` | `BUILD SUCCESSFUL`; APK at `app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk` |
| Unit tests (if a prototype adds one) | append `:app_pojavlauncher:testDebugUnitTest` | JUnit report under `app_pojavlauncher/build/test-results/testDebugUnitTest/`, all green |
| Confirm compareSHA1 callers | `grep -rn "compareSHA1" app_pojavlauncher/src/main/java` | list of call sites, used to confirm none currently cover `rt4.jar`/plugins/config |
| Confirm build_version.txt is static | `grep -rn "build_version" app_pojavlauncher/build.gradle` | no matches (confirms it's hand-maintained today, not generated) |

## Scope

**In scope:**
- The inventory (Boundaries 1–6 above, extend if the executor finds more).
- The per-boundary design: which get a build-time SHA-256 manifest check,
  which get shape/host validation, which are explicitly deferred (with why).
- OPTIONAL minimal prototype at exactly ONE boundary — recommended: Boundary 1
  (a Gradle-generated SHA-256 manifest for `rt4.jar` + one config asset,
  checked in `unpackComponents` before/after `Tools.copyAssetFile`). Do not
  prototype more than one boundary in this pass.

**Out of scope (do NOT touch beyond reading, this pass):**
- Any signing infrastructure (APK signing scheme, code-signing keys) — that's
  a separate, much larger undertaking; note it only as a rejected/deferred
  option in the design if relevant.
- Rolling the design out to all six boundaries — that's follow-up plans.
- Changing `compareSHA1`'s algorithm in place (upgrading existing callers to
  SHA-256) — propose it in the design, don't execute a repo-wide swap here.
- The rt4-client (external repo) — you cannot change how it consumes
  `config.json` or its own classpath; note any client-side dependency as an
  open question instead.

## Git workflow

- Branch: `advisor/030-spike-artifact-config-integrity`
- Commit per step; message style matches repo, e.g. `docs: inventory untrusted
  input boundaries for client artifact integrity` for the design commit,
  `feat: prototype build-time SHA-256 manifest for rt4.jar (spike)` for any
  prototype commit.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Confirm and extend the inventory

Re-verify Boundaries 1–6 above against the live files (the drift check at the
top of this plan). Search for any additional untrusted-input boundary this
plan's evidence-gathering may have missed:
`grep -rn "openInputStream\|HttpURLConnection\|copyAssetFile" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch`.
Add any new boundary found to the design doc's inventory table with the same
shape as Boundaries 1–6 (file:line, what's untrusted, what check exists today).

**Verify**: the design doc's inventory table has at least 6 rows, each citing
a `file:line`.

### Step 2: Decide the trust anchor per boundary

For each boundary, document one of:
- **Build-time manifest** (Boundaries 1, 2): generate a SHA-256 per shipped
  asset at build time (e.g. a small Gradle task writing
  `assets/asset_manifest.json` — `{ "rt4.jar": "<sha256>", "config.json":
  "<sha256>", ... }`, mirroring the existing `build_version.txt` precedent at
  `AsyncAssetManager.java:72-81`), checked by a new `verifySha256` call inside
  `unpackComponents`/`extractAllPlugins` after `Tools.copyAssetFile` and before
  `saveAssetVersion`. The manifest is trustworthy because it ships inside the
  same signed APK as the assets it describes — the trust anchor is "the APK
  signature," which is out of scope to change but already exists.
- **Shape + host validation, no cryptographic trust anchor** (Boundaries 4, 6):
  imported `config.json` (URL or file) has no pre-existing trusted source —
  design a strict schema check (required keys present, correct types, `ip_address`/
  `ip_management` look like a valid host, ports in valid range) as the
  practical ceiling; document that cryptographic verification of imported
  content requires an authoritative signer, which does not exist today (see
  Open Questions).
- **Explicitly deferred, capped by other in-flight plans** (Boundary 5): note
  that `plans/015-*.md` (zip caps, e.g. max entry count/size) is the
  appropriate mitigation for plugin-ZIP import size/resource limits, and that
  cryptographic verification of a community plugin has the same "no
  authoritative signer" problem as Boundary 4.

**Verify**: the design doc has one paragraph per boundary stating the chosen
trust anchor (or "none available, capped by X instead").

### Step 3: Propose the `compareSHA1` → SHA-256 upgrade path

Document (do not execute broadly): a new `Tools.verifySha256(File, String)`
method with the same fail-closed shape as the hardened `compareSHA1`
(`Tools.java:622-636` — null-hash opts out, IOException fails closed, matching
hash returns true), added alongside (not replacing) `compareSHA1` so existing
`checkLibraries`-gated callers are undisturbed. Document which NEW call sites
would use it first (rt4.jar and config.json unpack, from Step 2's Boundary 1
design).

**Verify**: the design doc states the proposed method signature and lists the
specific `file:line` call sites it would be added to (not "everywhere").

### Step 4: Minimal prototype (optional, ONE boundary only)

If attempting: add a build-time SHA-256 manifest for exactly `rt4.jar` (the
highest-value target — it's the executed code, not just config) generated via
a Gradle task or a checked-in generation script, plus one `Tools.verifySha256`
call in `unpackComponents` (`AsyncAssetManager.java:119`) guarding the
`Tools.copyAssetFile(ctx,"rt4.jar",Tools.DIR_DATA, false)` line — on mismatch,
log and skip using the corrupted copy (do not crash the unpack thread; match
the existing `catch (IOException)` pattern in `unpackComponents`). Keep the
manifest to ONE entry for this spike.

**Verify**: `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` → `BUILD SUCCESSFUL`; `grep -n "verifySha256" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/tasks/AsyncAssetManager.java` shows the new call; manually confirm on-device (or via the device-smoke checklist, `docs/verification/device-smoke-checklist.md`) that a normal fresh install still unpacks `rt4.jar` and launches.

### Step 5: Write OPEN QUESTIONS

Record explicitly (do not silently resolve):
- Where does an authoritative hash/signature come from for **imported**
  content (Boundaries 4–6)? The shipped-asset manifest (Step 2) only solves
  trust for content that ships inside this repo's own signed APK — a
  community server's `config.json` or a community plugin has no equivalent
  anchor without either (a) a signing key the server/plugin author holds and
  the app ships a matching public key for, or (b) a curated allowlist server
  the app calls out to. Both require infrastructure outside this repo.
- Does Android's default cleartext-traffic policy currently block or allow
  the `http://` fetch in `importServerConfig` (`SettingsActivity.kt:160`) on
  the app's `minSdk 21`/`targetSdk`? Not verified in this pass — check
  `AndroidManifest.xml` for `usesCleartextTraffic`/network-security-config
  before assuming it's silently allowed or silently blocked.
- Is upgrading imported-config transport to `https://` alone (without content
  signing) worth doing as a cheap partial mitigation, given `DEFAULT_CONFIG_URL`
  (`SettingsActivity.kt:103`) is itself a LAN IP that will rarely have a valid
  TLS certificate in practice?
- Should the plugin-ZIP import path (Boundary 5) require the user to
  explicitly confirm "install untrusted code" (a warning dialog) as a
  stop-gap, independent of any cryptographic verification?

**Verify**: the design doc has a distinct "Open Questions" section with at
least the four above, each with the plan's recommendation or explicitly marked
"unresolved — needs a trust-anchor decision outside this repo."

## Test plan

- No existing unit-test coverage of any of these boundaries (the repo's one
  JUnit test, `CameraPanTest.java`, is unrelated pure-logic code).
- If Step 4's prototype adds `Tools.verifySha256`, add a unit test modeled
  after the structural pattern of `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/customcontrols/CameraPanTest.java`
  (plain JUnit 4, no Android framework dependency) covering: matching hash
  returns true, mismatched hash returns false, null expected hash opts out
  (matches `compareSHA1`'s existing contract), unreadable file fails closed.
- Verification: the Docker build command above → `BUILD SUCCESSFUL`; if a test
  was added, the `testDebugUnitTest` variant also green.

## Done criteria

Machine-checkable. ALL must hold:

- [ ] Inventory (Step 1) written with at least 6 rows, each with a `file:line`
- [ ] Per-boundary trust-anchor design (Step 2) written for every inventory row
- [ ] `compareSHA1` → SHA-256 upgrade path proposal (Step 3) written with a
      concrete method signature and named call sites
- [ ] Open Questions (Step 5) written, at least 4 items
- [ ] If a prototype was attempted: `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` → `BUILD SUCCESSFUL`
- [ ] No files outside the in-scope list are modified (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:
- The excerpts in "Current state" don't match the live file (drift beyond
  line-number shift).
- Step 5's "where does the trust anchor come from for imported content"
  question cannot be answered without server-side changes (e.g. the 2009Scape
  server repo would need to start publishing signed configs) — this is
  EXPECTED to be the likely outcome; when it happens, record it as the key
  open question, mark the imported-content boundaries (4, 5, 6) as "shape/host
  validation only, no cryptographic trust possible without server-side work,"
  and stop there rather than inventing a local-only signing scheme that gives
  false confidence.
- A prototype attempt (Step 4) requires changes to `Tools.java`'s launch
  sequence (`launchGLJRE`) beyond the single guarded copy — that's scope creep
  into a full rollout; stop and scale the prototype back to the one call site.

## Maintenance notes

- Reviewer: confirm the design doesn't conflate "verified because it shipped
  in our APK" (Boundary 1, legitimately strong — anchored by the APK
  signature) with "verified because we checked its JSON shape" (Boundaries 4/6,
  much weaker — this is validation, not integrity, and the design doc must not
  blur that distinction).
- Follow-up (deferred, out of this plan): actually rolling the SHA-256
  manifest out to all shipped assets/plugins (Boundary 1 fully), and any
  cleartext-transport fix for config import, are separate build plans that
  should cite this design doc as their "Current state" input.
- If `plans/013-*.md`/`014-*.md`/`015-*.md` land with their own findings about
  the plugin-ZIP or download path, reconcile this plan's Boundary 5/inventory
  against their final scope before a follow-up build plan executes.
