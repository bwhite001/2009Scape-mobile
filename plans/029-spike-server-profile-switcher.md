# Plan 029: Spike/design — named server-profile switcher

> **Executor instructions**: This is a DESIGN/SPIKE plan, not a build-everything
> plan. The deliverable is a written design (schema, persistence, migration, UX,
> open questions) plus, ONLY if it stays low-risk, a minimal prototype behind the
> existing settings screen. Do NOT rip out or replace the current single-pair
> `serverIp`/`serverPort` flow. Follow the steps in order; run every verification
> command; if a STOP condition triggers, stop and report — do not improvise past it.
> When done, update this plan's row in `plans/README.md` — unless a reviewer
> dispatched you and told you they maintain the index.
>
> **Drift check (run first)**: `git diff --stat 8ee361ea1..HEAD -- app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/di/PreferencesRepository.kt app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/di/AppContainer.kt`
> If any of these changed since this plan was written, re-read the live file and
> compare against the "Current state" excerpts before proceeding; on a
> substantive mismatch (not just line-number shift), treat it as a STOP condition.
>
> **Known drift already present at write time**: this plan's grounding evidence
> was originally sketched against `SettingsActivity.kt:199-211` / `:98` (an older
> commit). At the actual planned-at commit `8ee361ea1`, the same logic lives at
> different line numbers (see "Current state" below, which reflects the live
> file) — the file was reformatted/extended by the RS2 settings restyle
> (`89d2872d9`) in between. The substance (single hardcoded import URL, import
> overwrites one `serverIp`/`serverPort` pair) is unchanged. This is noted so the
> executor doesn't treat the line-number difference alone as drift requiring a
> STOP — but DOES still re-confirm the excerpts below against the live file.

## Status

- **Priority**: P2
- **Effort**: M (coarse — this is a design spike; a real estimate needs the
  design questions in this plan answered first)
- **Risk**: MED
- **Depends on**: `plans/018-*.md` (ServerConfig normalizer) — recommended to
  land first so Step 4 (routing `patchConfigJson` off the selected profile) has
  a single parse/validate function to call. Not a hard blocker for Steps 1–3
  (schema/migration/UX design) or for writing this plan's design doc; it IS a
  hard blocker for the optional prototype in Step 4 if plan 018 hasn't been
  executed yet — see Step 4.
- **Category**: direction
- **Planned at**: commit `8ee361ea1`, 2026-07-10

## Why this matters

The launcher's server configuration is a single, global `serverIp`/`serverPort`
pair. Players who move between a public server, a friend's LAN server, and
`127.0.0.1` for local testing must re-run "Import config from URL" or "Load
config.json from file" every time, and each import silently overwrites the
previous target with no way to go back without re-importing. The import
plumbing and a Compose settings screen already exist (`SettingsActivity.kt`) —
a named list of remembered profiles the user can tap to switch is the natural
next step and mostly a persistence/UX problem, not a new capability. This plan
produces the design (schema, migration, UX sketch, open questions) and, if it
stays low-risk, a minimal non-shipping prototype — not the finished feature.

## Current state

Files and roles:
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt` —
  the Compose settings screen; owns the single `serverIp`/`serverPort`/
  `serverConfigUrl` string prefs and the two import paths (URL fetch, file pick).
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java` —
  `patchConfigJson` (launch-time) reads the SAME flat prefs and rewrites the
  on-disk `config.json` the RT4-Client actually reads.
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/di/PreferencesRepository.kt`
  — the only persistence abstraction in the launcher shell; a thin wrapper over
  `SharedPreferences`.
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/di/AppContainer.kt` —
  manual service locator; `appContainer.preferencesRepository` is how any
  Activity reaches persisted prefs today.

Confirmed excerpts (re-read at commit `8ee361ea1`, current line numbers — differ
from the grounding evidence's line numbers because of the intervening RS2
settings restyle; substance matches):

`SettingsActivity.kt:103-109` — the single hardcoded default + the three
server-related string prefs:
```kotlin
private const val DEFAULT_CONFIG_URL = "http://192.168.0.243:8080/config.json"

private val SERVER_STRINGS = listOf(
    StringPref("serverIp",   "Server IP address", "127.0.0.1"),
    StringPref("serverPort", "Server port",       "43595", KeyboardType.Number),
    StringPref("serverConfigUrl", "Config import URL", DEFAULT_CONFIG_URL),
)
```

`SettingsActivity.kt:204-217` — `applyConfigJson`, the shared body of both
import paths, writes exactly one `serverIp`/`serverPort` pair and returns:
```kotlin
private fun applyConfigJson(repo: PreferencesRepository, body: String): String {
    val json = org.json.JSONObject(body)
    val ip = json.optString("ip_address", json.optString("ip_management", ""))
    if (ip.isEmpty()) throw IllegalStateException("config has no ip_address")
    val port = when {
        json.has("wl_port")     -> json.getInt("wl_port")
        json.has("js5_port")    -> json.getInt("js5_port")
        json.has("server_port") -> json.getInt("server_port")
        else -> 43595
    }
    repo.putString("serverIp", ip)
    repo.putString("serverPort", port.toString())
    return "$ip:$port"
}
```
Both `importServerConfig` (URL fetch, `SettingsActivity.kt:151-178`) and
`applyConfigFromUri` (file pick, `:185-197`) call this — so ANY design for
multiple profiles must decide what `applyConfigJson` does: append a new named
profile, or keep overwriting a "current" slot and let the user separately name/
save it. This is Open Question 1 below.

`Tools.java:157-172` — `patchConfigJson`, the launch-time consumer, reads the
same flat keys directly off `SharedPreferences` (not through
`PreferencesRepository` — this runs in the `:game` process context, a plain
`Activity`):
```java
public static void patchConfigJson(Activity activity) {
    try {
        android.content.SharedPreferences prefs =
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(activity);
        String ip   = prefs.getString("serverIp",   "127.0.0.1");
        String port = prefs.getString("serverPort",  "43595");
        if (ip == null || ip.isEmpty()) return;
        ...
```
Whatever the profile-list design settles on, `patchConfigJson` must still end
up reading a single resolved `ip`/`port` pair — it is the last-mile function
that writes the on-disk `config.json` (`DIR_DATA/config.json`) the RT4-Client
launch classpath reads via `-DconfigFile=` (`Tools.java:218`). The design's job
is to decide how the "currently selected profile" resolves into that pair
before `patchConfigJson` runs (called from `launchGLJRE`, `Tools.java:206`,
which runs on every launch).

`PreferencesRepository.kt:12-18` (class doc comment) — the critical constraint
for the persistence design:
```kotlin
/**
 * Compose-facing wrapper over the app's default SharedPreferences — the SAME
 * store LauncherPreferences reads. Preserves exact keys/semantics; the :game
 * process keeps reading via LauncherPreferences static fields. Writes go here,
 * and reloadLauncherPreferences() refreshes those statics so the launch path
 * sees the new values. DataStore is intentionally NOT used (cross-process reads).
 */
```
This is a hard constraint on the design: **do not introduce Jetpack DataStore**
for profile storage. The repo deliberately stays on `SharedPreferences`/
`PreferenceManager.getDefaultSharedPreferences` because the launch path reads
it from a different process context via `PreferenceManager`, not via any
in-process singleton/Flow. A profile LIST (not a single string) has no native
`SharedPreferences` type, so the design must pick one of: (a) a single JSON-
array string value under a new key (e.g. `serverProfiles`), parsed with
`org.json` (already used elsewhere in this file, no new dependency), or (b) one
`SharedPreferences` key per profile field with an index (`profile_0_name`,
`profile_0_ip`, …). Recommend (a) for this design — matches the existing
`org.json.JSONObject` usage pattern in `applyConfigJson` and keeps it to one key.

`AppContainer.kt:1-26` — full file, confirms there is no DI framework (manual
locator only) and `preferencesRepository` is the only injectable persistence
surface; a `ServerProfile`/profile-list accessor would live as a new method on
`PreferencesRepository`, matching its existing `getBoolean`/`getInt`/`getString`
+ `putX` pattern (`PreferencesRepository.kt:22-28`).

Compose building blocks already available for the picker sketch (Step 3),
confirmed by import in `SettingsActivity.kt:38-43`: `RsBackButton`, `RsButton`,
`RsHeaderBand`, `RsSectionHeader`, `RsSlider`, `RsToggle` from
`net.kdt.pojavlaunch.ui.rs.*` — a profile-picker list row can reuse
`RsButton`/`RsToggle` rather than introducing new Compose primitives.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Build APK (Docker, no host JDK/SDK) | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` | `BUILD SUCCESSFUL`; APK at `app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk` |
| Unit tests (if any prototype test is added) | append `:app_pojavlauncher:testDebugUnitTest` to the same Docker command | JUnit report under `app_pojavlauncher/build/test-results/testDebugUnitTest/`, all green |
| Find current callers of the flat pair | `grep -rn '"serverIp"\|"serverPort"' app_pojavlauncher/src/main/java` | confirms `SettingsActivity.kt` and `Tools.java` are the only two read/write sites (re-run before removing anything) |

## Scope

**In scope:**
- Writing the design (can live in this plan file's "design doc" section below,
  or in a new `docs/design/server-profiles.md` the executor creates — pick one
  and say which in the PR/commit message).
- OPTIONAL minimal prototype: a `ServerProfile` data model + a
  `PreferencesRepository` extension to read/write a JSON-array profile list,
  and/or a bare-bones Compose list screen — gated behind existing settings
  (e.g. reachable only from a new button, not replacing the current Server
  section) so the current single-pair flow keeps working unmodified.

**Out of scope (do NOT touch beyond reading, this pass):**
- Removing or rewiring `SERVER_STRINGS`, `applyConfigJson`, `patchConfigJson`,
  or the existing import buttons — the current single-pair path must keep
  working exactly as-is until the design is reviewed and a follow-up plan
  executes it.
- Per-profile control layouts, per-profile plugin sets, or any multi-profile
  feature beyond IP/port/name — list them as open questions instead (Step 5).
- `plans/018-*.md`'s own scope (the `ServerConfig` normalizer itself) — depend
  on its output, don't redo it.

## Git workflow

- Branch: `advisor/029-spike-server-profile-switcher`
- Commit per step; message style matches repo, e.g. `docs: design server
  profile switcher schema/migration/UX` for the design commit, `feat: prototype
  ServerProfile persistence (spike, unwired)` for any prototype commit.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Define the `ServerProfile` schema and persistence shape

Write (in the design doc) a concrete schema, e.g.:
```kotlin
data class ServerProfile(
    val id: String,       // stable key, e.g. UUID or slug — decide which
    val name: String,     // user-facing label, e.g. "Home LAN"
    val ip: String,
    val port: Int,
    val configUrl: String? = null, // optional: remember the import source
)
```
Decide and document: storage as one `SharedPreferences` string key holding a
JSON array (recommended, see Current state) vs. indexed keys. Decide the
selected-profile pointer (`selectedProfileId: String?` under its own key, or
`selectedProfileIndex: Int`). Add read/write methods to
`PreferencesRepository` matching its existing style
(`getBoolean`/`putBoolean` etc., `PreferencesRepository.kt:22-28`) — e.g.
`getServerProfiles(): List<ServerProfile>`, `putServerProfiles(List<ServerProfile>)`,
`getSelectedServerProfile(): ServerProfile?`.

**Verify**: design doc contains the schema, the chosen storage shape, and the
concrete `PreferencesRepository` method signatures. No code required for this
step alone.

### Step 2: Define migration from the current single pair

Document the one-time migration: on first read of the new profile-list key (or
behind an `asset_version`-style bump, see `AsyncAssetManager.assetVersionChanged`
pattern at `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/tasks/AsyncAssetManager.java:72-81`
for a precedent of "compare a stored version, migrate once"), if no profile
list exists yet, synthesize a single profile from the current
`serverIp`/`serverPort`/`serverConfigUrl` values and mark it selected. Document
what happens to the legacy keys afterward: (a) leave them in place and treat
them as a derived "currently active" cache that `patchConfigJson` still reads
(safest — no `Tools.java` change required), or (b) have `patchConfigJson` read
through a new resolver. Recommend (a) for the design (lowest risk, keeps
`Tools.java` untouched) and record (b) as a documented alternative + its cost.

**Verify**: design doc states the migration trigger, the synthesized profile's
field values, and which of (a)/(b) is recommended and why.

### Step 3: Sketch the Compose picker UX

Sketch (pseudocode/description, not necessarily working code) a profile list
screen reusing `RsButton`/`RsToggle`/`RsSectionHeader` (already imported in
`SettingsActivity.kt:38-43`): list of saved profiles (name + ip:port), tap to
select, a way to rename/delete, and an "Add" path that either opens the
existing import-from-URL/import-from-file flow and saves the result as a new
named profile, or duplicates the current "Server" section's fields into a
create-profile form. Note where it's reached from (a new button in the
existing "Server" section, `SettingsActivity.kt:250-254`).

**Verify**: design doc has the screen sketch and states the entry point.

### Step 4: Route `patchConfigJson` off the selected profile (prototype-only, optional)

Only attempt this if `plans/018-*.md` (`ServerConfig` normalizer) has already
landed — this step's job is to have `patchConfigJson` call the normalizer with
the SELECTED profile's ip/port instead of the flat `serverIp`/`serverPort`
prefs it reads at `Tools.java:161-162`. If plan 018 has not landed yet, DO NOT
attempt this step — document the intended call shape in the design doc instead
(e.g. "`patchConfigJson` would call `ServerConfig.normalize(profile.ip,
profile.port)` in place of its current `prefs.getString(...)` pair") and move on.

**Verify** (only if attempted): `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` → `BUILD SUCCESSFUL`; manually confirm the existing single-profile flow (Settings → Server → edit IP/port → launch) still patches `config.json` correctly (read the log line at `Tools.java:194-199` after a launch).

### Step 5: Write OPEN QUESTIONS

Record explicitly in the design doc (do not silently resolve these):
- Does each profile need its own control layout / plugin set, or is that
  always global? (Spec explicitly flags this — default to "out of scope,
  global stays global" unless the design doc argues otherwise.)
- What happens on first launch after this feature ships to a user who never
  used import — is there a "default"/"Local (127.0.0.1)" seed profile, or is
  the list empty until the user imports?
- Should profiles be exportable/shareable (e.g. as the same `config.json`
  shape) so a server admin can hand out one file that becomes a named profile
  directly, instead of the user manually naming it after import?
- Does `serverConfigUrl` (the import source) belong per-profile (so
  "re-import/refresh this profile" is possible) or stays a single global
  default URL?
- Is the profile `id` a UUID (stable across renames) or the `name` itself
  (simpler, but renaming becomes "delete + recreate" unless handled)?

**Verify**: the design doc has a distinct "Open Questions" section listing at
least the five above, each with the plan's recommendation or explicitly marked
"unresolved — needs product input."

## Test plan

- No existing unit-test coverage of `SettingsActivity.kt`/`Tools.java` (no test
  harness for these files today — the repo's one JUnit test,
  `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/customcontrols/CameraPanTest.java`,
  covers unrelated pure-logic code).
- If Step 1's `PreferencesRepository` methods are prototyped, add a pure-logic
  unit test for JSON (de)serialization of `ServerProfile` list — model the test
  file after `CameraPanTest.java`'s structure (plain JUnit, no Android
  framework dependency; if serialization needs `org.json.JSONObject`/`JSONArray`
  which requires the Android runtime under Robolectric, note that as a
  limitation and keep the test to a pure Kotlin data-class round-trip via a
  hand-rolled serializer instead, or skip the unit test and rely on the build +
  manual on-device check).
- Verification: the Docker build command above → `BUILD SUCCESSFUL`; if a test
  was added, the `testDebugUnitTest` variant also green.

## Done criteria

Machine-checkable. ALL must hold:

- [ ] A written design exists (in this plan file or a new `docs/design/server-profiles.md`) covering: schema (Step 1), migration (Step 2), UX sketch (Step 3), and the Open Questions list (Step 5)
- [ ] If a prototype was attempted: `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` → `BUILD SUCCESSFUL`
- [ ] The existing single-profile flow is unmodified and still builds/works: `grep -n 'SERVER_STRINGS\|applyConfigJson' app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt` still shows the same functions present
- [ ] No files outside the in-scope list are modified (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:
- The excerpts in "Current state" don't match the live file at a level beyond
  line-number shift (i.e. the logic itself changed, not just its position).
- The design reveals a conflict with how `rt4.jar` (the RT4-Client, external
  repo `gitlab.com/downthecrop/rt4-client`) actually reads `config.json` at
  launch — e.g. if the client caches/validates the config in a way that
  assumes it never changes between launches, multi-profile switching may need
  a client-side change out of scope for this repo. Report this as the primary
  finding rather than guessing at client behavior.
- Step 4's prototype requires touching `Tools.java`'s `launchGLJRE` control
  flow beyond swapping the ip/port source — STOP, that's scope creep into
  plan 018's territory.
- `plans/018-*.md` does not exist yet in `plans/` when you reach Step 4 —
  skip the prototype (documented in Step 4), do not block the whole plan on it.

## Maintenance notes

- Reviewer: check that the design doesn't silently assume DataStore or a new
  DI framework — this repo deliberately uses `SharedPreferences` +
  `PreferenceManager` for cross-process reasons (`PreferencesRepository.kt:12-18`).
- Follow-up (deferred, out of this plan): actually shipping the picker UI,
  wiring delete/rename, and the export/share open question — a future
  build-plan should consume this design doc as its "Current state" input.
- If plan 018 lands after this plan's design is written but before it's
  reviewed, re-check Step 4's call shape against the actual `ServerConfig`
  normalizer API before executing any follow-up build plan.
