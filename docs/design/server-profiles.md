# Design: named server-profile switcher

Status: SPIKE / design only (plan `plans/029-spike-server-profile-switcher.md`).
No prototype code was written for this pass — see "Prototype" note at the
bottom for why.

Grounded against commit `8ee361ea1` (plan's "planned at" commit) plus the six
commits that landed on `batch2-plans-007-031` since then and touch the files
this design depends on (`89d2872d9` RS2 restyle, `22662e386` import-guard
fixes, `029ea8ba3`/`40b0bc736`/`0321d900f` — plan 018's `ServerConfig`
normalizer landing and hardening, `e379ec5d3` zip-bomb cap, `2b321894d`
ktlint). None of this drift invalidates the design below; if anything it
retires part of Step 4 for free (see "Step 4 status"). Details in "Drift notes".

## 1. Schema and persistence shape

```kotlin
data class ServerProfile(
    val id: String,            // stable key — see "id choice" below
    val name: String,          // user-facing label, e.g. "Home LAN"
    val ip: String,
    val port: Int,
    val configUrl: String? = null, // optional: remember the import source URL
)
```

**Storage**: one new `SharedPreferences` string key, `serverProfiles`, holding
a JSON array — not indexed keys. This matches the constraint documented in
`PreferencesRepository.kt:12-18` (no DataStore; must stay
`SharedPreferences`/`PreferenceManager` because the `:game` process reads
prefs via `PreferenceManager.getDefaultSharedPreferences`, not an in-process
singleton) and reuses the `org.json.JSONObject`/`JSONArray` pattern
`applyConfigJson` already uses (`SettingsActivity.kt:261-283`). Indexed keys
(`profile_0_name`, `profile_0_ip`, …) were rejected: they need a separate
count/index key kept in sync, delete/reorder becomes key renumbering, and nothing
in this codebase already does that — one JSON-array key is strictly simpler and
atomic to write.

**Selected-profile pointer**: `selectedProfileId: String?` under its own key,
`selectedServerProfileId`. An index (`Int`) was rejected — deleting profile 1
of 3 would silently reindex profile 2 into slot 1 and change what "selected"
points at without an explicit re-select; an id string is stable across list
mutations.

**`PreferencesRepository` additions** (same style as
`getBoolean`/`putBoolean`, `PreferencesRepository.kt:22-50`):

```kotlin
fun getServerProfiles(): List<ServerProfile>          // parses "serverProfiles", [] if absent/malformed
fun putServerProfiles(profiles: List<ServerProfile>)  // serializes to "serverProfiles"
fun getSelectedServerProfile(): ServerProfile?         // looks up selectedServerProfileId in getServerProfiles()
fun setSelectedServerProfileId(id: String?)            // writes "selectedServerProfileId"
```

`getServerProfiles()` must tolerate a malformed/missing key by returning
`emptyList()` rather than throwing — this key doesn't exist yet on any
installed copy of the app, and a future format change should degrade to "no
saved profiles" rather than crash Settings on open.

**`id` choice**: UUID (`java.util.UUID.randomUUID().toString()`), not the
`name`. Rationale is in Open Questions #5, but the short version: renaming a
profile must not "orphan" the currently-selected pointer, so the pointer needs
a key that renaming doesn't change. `name` is user-editable and not required
to be unique; `id` is assigned once at creation and never edited.

## 2. Migration from the current single pair

**Trigger**: lazy, on first read of `serverProfiles` — no separate
`asset_version`-style counter needed (unlike `AsyncAssetManager`'s
`PREF_ASSET_VERSION` precedent at `AsyncAssetManager.java:64,73-81`, which
exists because asset unpacking needs to re-run on APK upgrades even when
prefs are unchanged). Here the check is simpler: `if (repo.getString(
"serverProfiles", null) == null) { synthesize and write it }`. This runs once
because after synthesis the key is always present (even if empty `"[]"` would
also count as "present" — see below).

**Synthesized profile**, built from the existing flat keys the first time
`getServerProfiles()` (or a dedicated `migrateIfNeeded()` called from the same
place) runs:

```kotlin
ServerProfile(
    id = UUID.randomUUID().toString(),
    name = "Default",                              // no prior name to recover
    ip = repo.getString("serverIp", ServerConfig.DEFAULT_IP)!!,
    port = repo.getString("serverPort", ServerConfig.DEFAULT_PORT.toString())!!.toIntOrNull()
        ?: ServerConfig.DEFAULT_PORT,
    configUrl = repo.getString("serverConfigUrl", null),
)
```
— written as a single-element list, and its `id` immediately becomes
`selectedServerProfileId`. This reuses `ServerConfig.DEFAULT_IP`/
`DEFAULT_PORT` (`ServerConfig.java:22-23`) for the fallback rather than
re-hardcoding `"127.0.0.1"`/`43595`, so the constants stay single-sourced.

**What happens to the legacy keys afterward — recommendation (a)**: leave
`serverIp`/`serverPort`/`serverConfigUrl` in place permanently as a derived
"currently active" cache. Every profile-switch action (tap a profile row)
writes that profile's `ip`/`port`/`configUrl` back into the three legacy keys
*in addition to* updating `selectedServerProfileId`, then calls
`repo.reloadLauncherPreferences()` exactly as `applyConfigJson` does today
(implicitly, via the existing `PreferencesRepository` write path). This means:

- `Tools.patchConfigJson` (`Tools.java:157-176`) needs **zero changes** — it
  keeps reading `serverIp`/`serverPort` exactly as it does now and keeps
  calling `ServerConfig.normalize` on them (already true as of `40b0bc736`,
  see "Step 4 status" below).
- `SettingsActivity`'s existing `StringRow` fields for IP/port
  (`SERVER_STRINGS`, `SettingsActivity.kt:122-126`) keep showing the *active*
  profile's values with no special-casing.
- The only new write site is "select a profile", which is a superset of what
  `applyConfigJson` already does (write `serverIp`/`serverPort`) plus setting
  `selectedServerProfileId`.

Alternative (b) — `patchConfigJson` resolves the selected profile itself
(`PreferencesRepository.getSelectedServerProfile()` inside `Tools.java`) — was
considered and **not recommended**: `Tools.java` runs in the `:game` process
via a plain `Activity` and reads prefs directly off
`PreferenceManager.getDefaultSharedPreferences` (`Tools.java:157-162`), not
through `PreferencesRepository`; wiring it through the repository's JSON
profile-list parsing would put `org.json` array parsing on the launch hot
path and duplicate the "which key resolves to truth" logic that already
exists for the legacy keys. (a) keeps `Tools.java` as a dumb consumer of two
strings, which is the existing, working contract.

## 3. Compose picker UX sketch

**Entry point**: a new `RsButton("Manage server profiles", ...)` added to the
"Server" section in `SettingsScreen` (`SettingsActivity.kt:316-323`), placed
above the existing "Import config from URL" / "Load config.json from file"
buttons. Tapping it launches a new screen/dialog — not a replacement of the
current section, matching the plan's "gated behind existing settings" scope
rule.

**Profile list screen** (pseudocode, reusing existing primitives — no new
Compose primitives needed):

```
RsHeaderBand("Server Profiles")
RsBackButton(onClick = onBack)
LazyColumn {
    items(profiles) { profile ->
        Row {
            // tap the row = select
            Text("${profile.name}")
            Text("${profile.ip}:${profile.port}", muted style)
            if (profile.id == selectedId) RsCheckOrHighlight()  // reuse RsToggle's
                                                                  // "on" visual state,
                                                                  // or a simple bg tint
            RsButton("Rename", onClick = { showRenameDialog(profile) }, small)
            RsButton("Delete", onClick = { confirmDelete(profile) }, small, muted)
        }
    }
    item {
        RsButton("Add profile", onClick = onAddProfile)
    }
}
```

Row tap → `repo.putServerProfiles(...)` unchanged, `repo.setSelectedServerProfileId(profile.id)`,
copy that profile's fields into the legacy `serverIp`/`serverPort`/
`serverConfigUrl` keys (see migration section, recommendation (a)),
`repo.reloadLauncherPreferences()`, then pop back to `SettingsScreen` so the
Server section's `StringRow`s refresh to show the new active values (same
`recreate()`-style refresh `importServerConfig`/`applyConfigFromUri` already
use, `SettingsActivity.kt:212`, `:250`).

**Rename**: an inline text field or small dialog editing `name` only;
`id`/`ip`/`port` untouched — this is exactly why `id` must not be derived from
`name` (Open Question #5).

**Delete**: confirm dialog; if the deleted profile was selected, fall back to
selecting the first remaining profile (or `null`/empty state if the list
becomes empty — see Open Question #2).

**"Add profile"**: two reasonable variants, worth flagging rather than
picking one:
1. Reuse the existing import flow (`importServerConfig`/
   `applyConfigFromUri`) but instead of writing straight into
   `serverIp`/`serverPort`, wrap the result in a new `ServerProfile` (prompt
   for a `name` after successful parse) and append it to the list.
2. A bare "create profile" form duplicating the current Server section's
   three fields (IP, port, config URL) with a `name` field added, saved
   directly with no network fetch.

Recommend supporting both: (1) for "I have a config.json/URL from a server
admin", (2) for "I already know the IP/port and want to type them in and name
them" (e.g. `127.0.0.1` for local testing). Both terminate in the same
`ServerProfile` append + `putServerProfiles` call.

## 4. Step 4 status — `patchConfigJson` routing (prototype gate)

Plan 018 landed (`plans/README.md:42`, `DONE`) **and** its `ServerConfig.normalize`
seam is already wired into both call sites this design cares about, as of
commits after this plan was drafted:

- `SettingsActivity.applyConfigJson` (`SettingsActivity.kt:279`) already calls
  `ServerConfig.normalize(ip, ip, serverPort, wlPort, js5Port)` instead of
  hand-rolling port precedence.
- `Tools.patchConfigJson` (`Tools.java:176`) already calls
  `ServerConfig.normalize(ip, ip, port, null, null)` instead of reading the
  prefs pair raw.

This is good news for a follow-up build plan, not scope for this spike: it
means the "call shape" this design needs to plan for is already real code,
not speculative. Under recommendation (a) above (legacy keys stay the
resolved-active cache), **`Tools.java` requires no changes at all** to support
multiple profiles — `patchConfigJson` keeps calling
`ServerConfig.normalize(prefs.getString("serverIp", ...), ...)` exactly as it
does today; only the profile-select action (in `SettingsActivity`/
`PreferencesRepository`) is new code. This satisfies the plan's Step 4 STOP
condition ("Step 4's prototype requires touching `Tools.java`'s `launchGLJRE`
control flow beyond swapping the ip/port source — STOP") by design: recommendation
(a) means it doesn't touch `Tools.java` at all.

No prototype code was written in this pass (see bottom note) — this section
documents the intended call shape only, per the plan's instruction for when
018 has landed but the executor chooses not to prototype.

## 5. Open Questions

1. **Per-profile control layout / plugin set?** Recommend **no** — out of
   scope, stays global. Nothing in the current settings model
   (`VIDEO_BOOLS`/`CONTROL_BOOLS`/`JAVA_BOOLS`/`MISC_BOOLS` sections,
   `SettingsActivity.kt`) is keyed per-server today, and mixing that in here
   would turn a persistence spike into a full preferences-scoping redesign.
   If a future need arises (e.g. a server with different plugin
   requirements), it should be its own plan.

2. **First-launch-after-ship default profile.** Two sub-cases:
   - **Upgrade from an existing install** (user already has `serverIp`/
     `serverPort` set, possibly via prior import): migration (Section 2)
     synthesizes exactly one profile named `"Default"` from those values and
     selects it. No empty-list state here.
   - **Fresh install, never touched Settings**: `serverIp`/`serverPort` are
     still their compiled-in defaults (`ServerConfig.DEFAULT_IP`/
     `DEFAULT_PORT`, or whatever `SERVER_STRINGS`' declared defaults are —
     currently `"127.0.0.1"`/`"43595"`, `SettingsActivity.kt:124-125`) even
     though the user never explicitly saved them. Migration still runs the
     same way and synthesizes a `"Default"` profile from those compiled-in
     values — there is no meaningful "empty until import" state to design for,
     because the flat keys already have non-null defaults today. Recommend:
     do NOT special-case this — treat "never touched settings" and "used
     defaults" identically; both produce one `"Default"` profile. Marking
     **resolved** under this recommendation; flag for product review only if
     shipping with a pre-seeded *public* server profile (as opposed to
     `127.0.0.1`) is desired — that would be a product decision, not a
     migration-logic one.

3. **Exportable/shareable profiles.** Recommend **yes, for free** — a
   `ServerProfile` already round-trips cleanly to the exact `config.json`
   shape `applyConfigJson` parses (`ip_address`/`wl_port`/`js5_port`/
   `server_port`). A "Share" action on a profile row could serialize
   `{"ip_address": profile.ip, "wl_port": profile.port, "js5_port":
   profile.port, "server_port": profile.port}` (all three ports identical per
   `ServerConfig.java`'s doc comment about the single-port multiplex) via
   Android's share sheet, and the existing "Load config.json from file"/
   "Import from URL" paths already consume that exact shape with no new
   parser needed. This is a nice-to-have, not required for the MVP profile
   list — flagging as **resolved-but-deferred** (build it if time allows,
   don't block the list/switch feature on it).

4. **Does `serverConfigUrl` belong per-profile?** Recommend **yes** — the
   schema in Section 1 already has `configUrl: String?` per profile for
   exactly this reason: a profile created via URL import should remember
   where it came from so a future "refresh this profile" action (re-fetch
   the same URL, re-parse, update ip/port in place) is possible without
   re-typing the URL. The *global* `serverConfigUrl` pref
   (`SettingsActivity.kt:126`) stays as-is for the existing single "Import
   config from URL" button (unchanged, per scope) — it becomes, in effect,
   "the URL for the currently-being-edited/default import," while
   per-profile `configUrl` is a separate, optional memory. Marked
   **resolved** (recommendation given), not unresolved — no product
   ambiguity here, it's additive.

5. **UUID vs. name as `id`.** Recommend **UUID**, decided above in Section 1.
   Rationale restated: `name` is user-editable (rename is an explicit
   feature, Section 3) and is not guaranteed unique (nothing stops a user
   naming two profiles "Home"); using `name` as the stable key means rename
   would require an atomic "remove old key, insert new key, and fix up
   `selectedServerProfileId` if it pointed at the old name" — strictly more
   moving parts than generating a UUID once at creation and never touching it
   again. Marked **resolved**.

## Drift notes (for the reviewer)

The plan's drift check (`git diff --stat 8ee361ea1..HEAD -- ...`) is
non-empty: `SettingsActivity.kt`, `Tools.java`, and
`PreferencesRepository.kt` all changed after this plan was written, because
plan 018 (the `ServerConfig` normalizer, this plan's dependency) executed on
this same branch afterward, plus import-hardening fixes (`22662e386`,
`0321d900f`, `e379ec5d3`) and a ktlint CI wire-up (`2b321894d`). Re-read
against the live files at the current `HEAD`:

- `PreferencesRepository.kt`'s class doc comment (the hard DataStore/
  cross-process constraint this design depends on) is **byte-for-byte
  unchanged** from the plan's excerpt — verified directly.
- `AppContainer.kt` is **unchanged** — verified directly, matches the plan's
  excerpt exactly.
- `SettingsActivity.applyConfigJson` and `Tools.patchConfigJson` changed
  *in substance*, not just line numbers: both now call
  `ServerConfig.normalize(...)` instead of the ad-hoc port-precedence logic
  the plan quoted. This is exactly what plan 018 was for, landing as
  intended. It does not conflict with anything in this design — recommendation
  (a) in Section 2 treats both functions as unchanged black boxes ("read/write
  `serverIp`/`serverPort` strings"), and that contract still holds; only their
  internals changed. Judged **not a STOP-worthy conflict**: the plan's STOP
  condition is about the *excerpted logic* changing in a way that would
  invalidate the design's assumptions, and here the change only makes Step 4
  easier (see Section 4) — it doesn't change what "write serverIp/serverPort"
  means to any caller.
- `DEFAULT_CONFIG_URL` changed from a hardcoded LAN IP
  (`"http://192.168.0.243:8080/config.json"`) to `""` (commit `0321d900f`,
  "drop hardcoded dev-IP config default"). This affects only the *existing*
  single-URL import flow's default value, not this design — profiles created
  via option (2) in Section 3 ("Add profile" bare form) or via option (1)
  (import-then-name) are unaffected either way.

## Prototype

No prototype code was written for this pass. Rationale: plan 018 has landed,
so Step 4 was technically unblocked, but its actual code shape (Sections 1–2)
requires a new `ServerProfile` Kotlin data class, `PreferencesRepository`
JSON-array read/write methods, and touching `SettingsActivity.kt`'s Server
section — all doable, but the plan explicitly marks the prototype **optional**
and gates it on staying low-risk. Given the ktlint gate now in CI
(`2b321894d`) and the Docker build gate required for any source change per
this task's instructions, and that the design above is unambiguous enough to
hand directly to a build-plan executor as its "Current state" input (per this
plan's own "Maintenance notes"), the lower-risk choice for a spike is
doc-only: zero chance of introducing a regression in the working single-profile
flow, and the design is concrete enough (method signatures, storage key names,
migration trigger, UX sketch, open questions with recommendations) that a
follow-up build plan does not need to re-derive any of this.
