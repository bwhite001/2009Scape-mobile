# Plan 014: Stop shipping a hardcoded dev LAN IP and app-wide cleartext for config import

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 8ee361ea1..HEAD -- app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt app_pojavlauncher/src/main/AndroidManifest.xml`
> If either file changed since this plan was written, compare the "Current
> state" excerpts against the live code before proceeding; on a mismatch,
> treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: S-M
- **Risk**: MED (this repo's primary workflow is LAN HTTP — see the STOP condition, don't break it)
- **Depends on**: none
- **Category**: security
- **Planned at**: commit `8ee361ea1`, 2026-07-10

## Why this matters

`SettingsActivity`'s "Import config from URL" feature fetches a `config.json`
over plain HTTP and, if it parses, **overwrites the `serverIp`/`serverPort`
preferences that decide which server the game client connects to** (see
`applyConfigJson`, `SettingsActivity.kt:204-217`). Two things widen the blast
radius unnecessarily: (1) the shipped default URL for this feature is a
specific developer's LAN IP (`http://192.168.0.243:8080/config.json`) baked in
as a `private const val` — a confusing, environment-specific default to ship
in a general-purpose APK, and if that IP is ever reachable by someone else on
a network the user is on, an unsuspecting user might import from it by
mistake; (2) the app declares `android:usesCleartextTraffic="true"` at the
`<application>` level, permitting plaintext HTTP to **any** host from **any**
part of the app, not just this one LAN-dev feature — so there's no way to
reason about "this app only talks cleartext to my LAN" without reading code.
Since the imported config directly controls which server the client's login
credentials get sent to, an on-path attacker on an open Wi-Fi network who can
intercept or spoof a plaintext HTTP response to the import URL can redirect
the client to a malicious server transparently. This plan narrows both the
default and the cleartext scope while explicitly preserving the LAN-HTTP
workflow this repo is built around (`/runescape/2009Scape-mobile/CLAUDE.md`
describes `run-local.sh web` serving exactly this kind of `config.json` over
plain HTTP on a LAN — that must keep working).

## Current state

- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt` —
  confirmed current line numbers:

  ```kotlin
  103  private const val DEFAULT_CONFIG_URL = "http://192.168.0.243:8080/config.json"
  ```

  ```kotlin
  106  private val SERVER_STRINGS = listOf(
  107      StringPref("serverIp",   "Server IP address", "127.0.0.1"),
  108      StringPref("serverPort", "Server port",       "43595", KeyboardType.Number),
  109      StringPref("serverConfigUrl", "Config import URL", DEFAULT_CONFIG_URL),
  110  )
  ```

  ```kotlin
  151  private fun importServerConfig(repo: PreferencesRepository) {
  152      val url = repo.getString("serverConfigUrl", DEFAULT_CONFIG_URL)?.trim().orEmpty()
  153      if (url.isEmpty()) {
  154          Toast.makeText(this, "Set a config import URL first", Toast.LENGTH_SHORT).show()
  155          return
  156      }
  157      Toast.makeText(this, "Importing config from $url…", Toast.LENGTH_SHORT).show()
  158      Thread {
  159          try {
  160              val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
  161                  connectTimeout = 5000
  162                  readTimeout = 5000
  163                  requestMethod = "GET"
  164              }
  165              val body = conn.inputStream.bufferedReader().use { it.readText() }
  166              conn.disconnect()
  167              val result = applyConfigJson(repo, body)
  168              runOnUiThread {
  169                  Toast.makeText(this, "Imported $result — IP/port overridden", Toast.LENGTH_LONG).show()
  170                  recreate()
  171              }
  172          } catch (e: Exception) {
  173              Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
  174          }
  175      }.start()
  176  }
  ```

  No TLS/host validation exists (any `http://` or `https://` URL the user
  types or the shipped default is accepted), and `it.readText()` at line 165
  reads the entire response body unbounded.

- `app_pojavlauncher/src/main/AndroidManifest.xml:38`:
  ```xml
  android:usesCleartextTraffic="true"
  ```
  This is a top-level `<application>` attribute — it enables plaintext HTTP
  app-wide, not just for this one import feature.

- Repo context confirming LAN HTTP is the intended, load-bearing workflow (do
  not design this plan to break it): `/runescape/2009Scape-mobile/CLAUDE.md`
  describes the client reading `config.json` fields (`ip_management`,
  `ip_address`, `server_port`, `wl_port`, `js5_port`) and `/runescape/CLAUDE.md`
  describes `run-local.sh web`, which serves exactly this kind of
  `config.json` via **plain nginx HTTP on port 8080** for LAN devices — the
  scenario this whole feature exists for.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Build APK | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` | `BUILD SUCCESSFUL`; APK at `app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk` |
| Unit tests | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug :app_pojavlauncher:testDebugUnitTest"` | all tests pass |

## Scope

**In scope:**
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt` — `DEFAULT_CONFIG_URL`, `importServerConfig` (read cap, cleartext warning).
- `app_pojavlauncher/src/main/AndroidManifest.xml` — the `usesCleartextTraffic` attribute and adding `android:networkSecurityConfig`.
- New file: `app_pojavlauncher/src/main/res/xml/network_security_config.xml`.

**Out of scope (do NOT touch):**
- `MyDialogFragment.java`'s file-picker import path and the JSON-validity/size-cap hardening of the *file-based* import — that's plan 013.
- `Tools.patchConfigJson` (`Tools.java:157+`) — plan 018's normalizer.
- `applyConfigFromUri`/`applyConfigJson` parsing logic itself (leave as-is; only the transport around `importServerConfig` changes here).
- Any other manifest permissions or components.

## Git workflow

- Branch: `advisor/014-config-import-transport-hardening`
- Commit per logical unit (e.g. one for the manifest + network security config, one for `SettingsActivity.kt`'s default URL and read cap); message style: conventional commits, e.g. `fix: scope cleartext traffic to LAN import hosts and drop hardcoded dev IP default`.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Replace the hardcoded dev-IP default

In `SettingsActivity.kt:103`, change:
```kotlin
private const val DEFAULT_CONFIG_URL = "http://192.168.0.243:8080/config.json"
```
to an empty default:
```kotlin
private const val DEFAULT_CONFIG_URL = ""
```
This forces the user to type their own server's config URL rather than
inheriting an unrelated developer's LAN address. `importServerConfig`
(`SettingsActivity.kt:152-156`) already handles an empty URL gracefully — it
shows "Set a config import URL first" and returns — so no other change is
needed for this to be safe. Leave `serverIp`/`serverPort` defaults
(`127.0.0.1` / `43595`, `SettingsActivity.kt:106-108`) untouched; those are
generic loopback values, not a real IP.

**Verify**: `grep -n "DEFAULT_CONFIG_URL" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt` → the definition line shows `""`, not `192.168.0.243`.

### Step 2: Cap the response body read in `importServerConfig`

In `SettingsActivity.kt:165`, replace the unbounded
`conn.inputStream.bufferedReader().use { it.readText() }` with a capped read
(e.g. read into a `ByteArrayOutputStream` up to a fixed ceiling like `512 *
1024` bytes and throw if the stream has more remaining — a `config.json` is a
handful of fields, never hundreds of KB). Keep the existing `catch (e:
Exception)` block at line 172 as the error path (already shows a toast, does
not crash) — a cap violation should raise an exception into that same catch.

**Verify**: `grep -n "512 \* 1024\|MAX_CONFIG" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt` → a cap constant/limit is present near `importServerConfig`.

### Step 3: Add a scoped `network_security_config.xml`

Create `app_pojavlauncher/src/main/res/xml/network_security_config.xml`. Scope
cleartext to LAN-style hosts only, not the whole internet, using
`cleartextTrafficPermitted="true"` on a `domain-config` — since the actual LAN
IP varies per deployment (there's no fixed hostname to allowlist, per the
`run-local.sh`-driven workflow in `/runescape/2009Scape-mobile/CLAUDE.md`
where the IP is auto-detected per LAN), the correct-and-still-safe scoping is
a **base config that disables cleartext by default**, plus explicit private-
use IP ranges permitted for cleartext (RFC 1918 / link-local), which covers
"my phone talking to a box on my home/LAN network" without opening cleartext
to arbitrary public hosts:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">127.0.0.1</domain>
        <domain includeSubdomains="true">localhost</domain>
        <!-- Common private LAN ranges used by run-local.sh's advertised world IP -->
        <domain includeSubdomains="true">10.0.0.0</domain>
        <domain includeSubdomains="true">192.168.0.0</domain>
        <domain includeSubdomains="true">172.16.0.0</domain>
    </domain-config>
</network-security-config>
```

Note: Android's `<domain>` element requires an exact hostname/IP match (or
subdomain match), **not** a CIDR range — a bare `192.168.0.0` entry does
**not** match `192.168.1.42`. If the chosen LAN IP does not literally match
one of the listed domains, cleartext to it will be blocked. Read
`https://developer.android.com/privacy-and-security/security-config` (or the
equivalent bundled Android docs in the build image) to confirm current
behavior for your target `compileSdk` (35) before finalizing the list — if
per-IP domain matching genuinely cannot express "any private LAN address",
that's the STOP condition below (report, don't silently fall back to
app-wide cleartext).

**Verify**: `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && find app_pojavlauncher/src/main/res/xml -name network_security_config.xml"` → file exists.

### Step 4: Reference the config from the manifest and remove blanket cleartext

In `AndroidManifest.xml`, on the `<application>` element (around line 38):
- Remove `android:usesCleartextTraffic="true"`.
- Add `android:networkSecurityConfig="@xml/network_security_config"`.

**Verify**: `grep -n "usesCleartextTraffic\|networkSecurityConfig" app_pojavlauncher/src/main/AndroidManifest.xml` → no `usesCleartextTraffic` match, one `networkSecurityConfig="@xml/network_security_config"` match.

### Step 5: Warn on cleartext import

In `importServerConfig` (`SettingsActivity.kt:151-178`), before starting the
import `Thread`, if `url.startsWith("http://", ignoreCase = true)` show an
additional toast (or fold into the existing "Importing config from $url…"
toast text) making clear the transfer is unencrypted, e.g. "Importing over
HTTP (unencrypted) from $url…". This is an awareness nudge, not a blocking
confirmation dialog — keep it non-blocking so the LAN dev workflow described
in the repo docs isn't slowed down by an extra tap.

**Verify**: `grep -n "unencrypted\|http://" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt` → the warning text is present in `importServerConfig`.

## Test plan

- No new unit tests are expected here — this is transport/manifest
  configuration, not pure logic. If you want extra confidence in the byte-cap
  from Step 2, you may extract it alongside plan 013's `ImportGuard` helper
  (if that plan has already landed) rather than duplicating a second cap
  constant — check `plans/013-harden-config-import-validation.md`'s status
  before deciding; do not block on it.
- Device verification (required, list in the PR description):
  1. On a LAN with a `config.json` served over plain HTTP at a private IP
     (e.g. via `run-local.sh web` from the server repo, port 8080) matching
     one of the `network_security_config.xml` domain entries — "Import config
     from URL" still succeeds and overrides `serverIp`/`serverPort`.
  2. The "Config import URL" field is empty by default on a fresh install (no
     `192.168.0.243` anywhere).
  3. Attempting an HTTP import to a **public** (non-LAN) host fails at the
     network layer (cleartext blocked) rather than silently succeeding —
     confirm via a toast/error, e.g. point the URL at a plain-HTTP public test
     endpoint and confirm the request is refused.
  4. HTTPS import to any host still works unconditionally (network security
     config only restricts cleartext, never TLS).

## Done criteria

Machine-checkable. ALL must hold:

- [ ] Docker build command above exits with `BUILD SUCCESSFUL`
- [ ] `grep -n "192.168.0.243" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt` returns no matches
- [ ] `grep -n "usesCleartextTraffic=\"true\"" app_pojavlauncher/src/main/AndroidManifest.xml` returns no matches
- [ ] `grep -n "networkSecurityConfig" app_pojavlauncher/src/main/AndroidManifest.xml` returns one match
- [ ] `app_pojavlauncher/src/main/res/xml/network_security_config.xml` exists and is well-formed XML
- [ ] No files outside the in-scope list are modified (`git status`)
- [ ] `plans/README.md` status row updated
- [ ] Device steps 1-4 above completed and noted in the PR/commit description, with step 1 (LAN HTTP still works) explicitly confirmed

## STOP conditions

Stop and report back (do not improvise) if:

- The code at `SettingsActivity.kt:103/151-178` or `AndroidManifest.xml:38` doesn't match the excerpts above (drift) — re-read the live file before proceeding.
- Scoping cleartext via `network_security_config.xml` breaks the LAN-HTTP
  import workflow for a realistic private IP (i.e. `<domain>` matching
  genuinely cannot express "any address in a private LAN range" on this
  `compileSdk`/Android version) — this is explicitly called out as a risk in
  Step 3; report the exact behavior observed rather than reverting to
  app-wide `usesCleartextTraffic="true"` or silently narrowing the feature to
  be unusable.
- A verification step fails twice after a reasonable fix attempt.
- The fix appears to require touching `MyDialogFragment.java`, `Tools.patchConfigJson`, or the file-picker import path — those belong to plans 013/018.

## Maintenance notes

- If a future release wants a single stable "official" HTTPS config-hosting
  endpoint, that URL is a better `DEFAULT_CONFIG_URL` than empty — but do not
  invent one in this plan; leave it empty until such an endpoint actually
  exists, per the instruction in "Why this matters".
- A reviewer should scrutinize the exact IP ranges listed in
  `network_security_config.xml` — Android's domain-based matching does not
  support CIDR, so the list is necessarily a finite set of literal
  addresses/hostnames; if the real-world LAN IPs used by `run-local.sh`
  deployments don't literally match this list, users on those IPs lose the
  cleartext-import feature silently (a toast "cleartext not permitted"). This
  is the main thing to watch after this lands.
- This plan does not change how `patchConfigJson` (plan 018) *writes*
  `config.json` after import — only how the import's own HTTP fetch is
  secured/bounded.
