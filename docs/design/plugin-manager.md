# Design: in-app plugin manager (enable/disable/import)

Status: DESIGN ONLY (plan `plans/031-design-in-app-plugin-manager.md`). No
prototype code was written for this pass — see "Prototype" at the bottom for
why.

Grounded against commit `8ee361ea1` (plan's "planned at" commit) plus the
commits that landed on `batch2-plans-007-031` since then and touch the files
this design depends on. Drift check
(`git diff --stat 8ee361ea1..HEAD -- MyDialogFragment.java
AsyncAssetManager.java Tools.java SettingsActivity.kt`) shows real changes in
all four files, but none invalidate this plan's premises — see "Drift notes"
below. In particular: **plan 013's crashy-import fix and plan 015's zip-bomb
caps have both already landed**, which this plan's own scope note anticipated
("this plan's design should assume that fix lands separately") — they did,
and this design is written against the now-current (safer) versions of those
code paths.

## Drift notes

- `MyDialogFragment.java` (+113/-diff): `onActivityResult` for both
  `FILE_SELECT_CODE_JSON` and `FILE_SELECT_CODE_ZIP` now null-checks
  `data`/`data.getData()`, streams through `ImportGuard.isWithinSizeLimit`
  (a size cap), and (JSON path only) does a real `org.json.JSONObject`
  parse before writing. This is plan 013 landing. **The one behavior this
  design's Step 3 flagged as still-a-bug is still true after the fix**: the
  `FILE_SELECT_CODE_ZIP` branch (now `MyDialogFragment.java:247-286`) calls
  `AsyncAssetManager.extractPluginZip(tempFile)` on success but still does
  **not** call `addPluginsToList()` afterward, so a freshly imported plugin
  will not appear in the list until the dialog is reopened. Confirmed by
  `grep -n "addPluginsToList" MyDialogFragment.java` returning only the
  `onCreateView` call site (line 66), not inside `onActivityResult`.
- `Tools.java` (+82/-diff): `ZipTool.unzip` (now `Tools.java:556-654`) gained
  the three zip-bomb caps this design assumes are already in place —
  `MAX_ENTRY_COUNT` (5000), `MAX_ENTRY_UNCOMPRESSED_BYTES` (50 MB),
  `MAX_TOTAL_UNCOMPRESSED_BYTES` (200 MB). This is plan 015 landing. The
  `-DpluginDir=` wiring this design's Step 5 discusses is unchanged in
  substance, now at `Tools.java:223` (was cited as `:219` when the plan was
  written — pure line-number shift from unrelated edits earlier in the
  file). The rest of the diff (`patchConfigJson` refactor to use
  `ServerConfig.normalize`) is unrelated to plugins.
- `SettingsActivity.kt` (+369/-diff): unrelated to plugins — this is plan
  029's server-profile-switcher-adjacent work and general restyling. The
  "Advanced" section this design's Step 4 hooks into is unchanged in
  substance: `RsSectionHeader("Advanced")` / `Text("Renderer, runtime,
  plugins, and imports", ...)` / `RsButton("Open advanced settings",
  onClick = onOpenAdvanced, ...)` still live together (now
  `SettingsActivity.kt:338-341`, was `:262-268`), and `onOpenAdvanced` still
  opens `MyDialogFragment` directly (`:147`). The existing
  `registerForActivityResult(ActivityResultContracts.OpenDocument())` pattern
  this design's Step 4 reuses is confirmed present at `:134-136`, currently
  used for config-file import (mime types `application/json`, `text/plain`,
  `*/*`).
- `AsyncAssetManager.java` (+3 lines only): unrelated to plugins in
  substance — a small unrelated fix. `extractAllPlugins` /
  `extractPluginZip` are otherwise as the plan described them.

None of this drift changes any design decision below; it only confirms two of
this design's dependencies (plans 013, 015) already shipped, and updates
cited line numbers.

## 1. Current model (Step 1)

The existing plugin enable/disable mechanism, in full, as of
`MyDialogFragment.java:110-186` (`addPluginsToList` /
`processPluginDirectory`):

- **A plugin is "enabled" iff its extracted directory lives under
  `Tools.DIR_DATA + "/plugins/"`.** It is "disabled" iff that same directory
  instead lives under `Tools.DIR_DATA + "/disabledPlugins/"`.
- **There is no separate descriptor, database row, or `SharedPreferences`
  entry recording plugin identity or state anywhere in this repo.** The
  enumeration itself is just `File.listFiles()` over each of those two
  directories (`processPluginDirectory`, `MyDialogFragment.java:135` area) —
  whatever subdirectories exist ARE the plugin list; the directory name IS
  the plugin's display name; the parent directory IS the enabled/disabled
  state.
- Toggling in the existing UI is a raw `File.renameTo` moving the whole
  extracted directory between the two parents
  (`MyDialogFragment.java:164-177`), with no import-time metadata (version,
  origin, checksum) attached anywhere.
- The five bundled plugins (`GroundItems`, `LoginTimer`,
  `MobileClientBindings`, `RememberMyLogin`, `SlayerTrackerPlugin`) are
  extracted once by `AsyncAssetManager.extractAllPlugins`
  (`AsyncAssetManager.java:130-166`) straight into `plugins/` — i.e. they
  start life "enabled" by construction, with no way today to distinguish
  "bundled" from "user-imported" once extracted (both are just directories
  under `plugins/` or `disabledPlugins/`, indistinguishable by origin).

This is a legitimate, if minimal, design: the filesystem location already
functions as a durable, crash-safe, drift-free state store, and it's the
*exact* input `-DpluginDir=` needs (a directory of enabled plugin
subdirectories). Any redesign should keep this rather than invent a second,
parallel source of truth (see Step 2).

## 2. Plugin descriptor + enable/disable persistence model (Step 2)

```kotlin
data class PluginInfo(
    val directoryName: String,   // matches the extracted dir name, e.g. "GroundItems"
    val displayName: String,     // defaults to directoryName; could later come from
                                  // an in-zip manifest if rt4-client defines one (see §5)
    val enabled: Boolean,        // DERIVED — see below, not stored separately
    val source: PluginSource,    // BUNDLED or IMPORTED
)

enum class PluginSource { BUNDLED, IMPORTED }
```

`PluginInfo` is a **view model built by scanning the two directories at
read time** (`plugins/` → `enabled = true`, `disabledPlugins/` →
`enabled = false`), not a persisted record. `enabled` stays **derived from
directory location, not a separately stored boolean**, for one reason that
dominates every alternative: this repo already has exactly one place that
tells the running JVM where plugins live (`-DpluginDir=`,
`Tools.java:223`), and that place only understands "what's currently in this
one directory," not "what's nominally enabled per some list." A stored
`enabled` flag that could disagree with the actual directory contents (e.g.
if a plugin's directory were deleted, moved, or edited outside the app,
which is possible on Android via any file manager with storage access) would
be a silent-drift bug class for zero benefit — the directory-move mechanism
is already atomic-enough (a single `renameTo`) and needs no journal.

`source` (`BUNDLED` vs `IMPORTED`) is metadata the filesystem genuinely
cannot hold today (a directory name doesn't say where it came from) and
must be tracked separately if the manager is to forbid permanent removal of
bundled plugins (Step 4). Proposed shape: a **single flat file**,
`Tools.DIR_DATA + "/plugin_sources.json"`, a `directoryName -> "BUNDLED" |
"IMPORTED"` map — written to once by `AsyncAssetManager.extractAllPlugins`
for the five bundled names, and appended to by the new import path for each
newly-imported directory name. This is metadata *about* provenance, not
metadata that changes the enabled/disabled semantics above, so it does not
reintroduce a second source of truth for `enabled` — it only answers "is
this the same directory name AsyncAssetManager put there at first run,"
which is a fact that can't drift the same way a boolean toggle could
(nothing else in the app currently renames a bundled plugin's directory to
a different bundled name). If this file is missing or unreadable (e.g. a
pre-existing install upgrading into this feature for the first time), the
manager should default any unrecognized `directoryName` to `IMPORTED` if it
is not one of the five known bundled names hardcoded as a fallback list,
and `BUNDLED` if it is — so the feature degrades gracefully on upgrade
without requiring a migration step.

## 3. Safe import (Step 3)

A real (non-prototype) import path must compose, in this order:

1. **Plan 015's zip caps** — `Tools.ZipTool.unzip` already enforces
   `MAX_ENTRY_COUNT` (5000), `MAX_ENTRY_UNCOMPRESSED_BYTES` (50 MB), and
   `MAX_TOTAL_UNCOMPRESSED_BYTES` (200 MB) (`Tools.java:559-654`, confirmed
   live this pass). Any new import entry point must go through this same
   `unzip` call (or an equally-capped one) — never a raw
   `ZipInputStream` loop that bypasses it.
2. **Plan 030's integrity conclusion for Boundary 7 (plugin-ZIP import)** —
   `docs/design/artifact-config-integrity.md` §2 concludes there is **no
   cryptographic trust anchor available** for a community-authored plugin
   (no publisher keypair the app can verify against), and proposes, as the
   pragmatic mitigation, a **one-time "install untrusted code?" confirmation
   dialog before extraction**. This design adopts that conclusion by
   reference rather than re-deriving it: any new plugin-manager import
   button must show that confirmation (stating plainly that the ZIP will run
   as trusted JVM code alongside the game client) before calling
   `extractPluginZip`, in addition to the existing size cap that already
   happens during the picker-to-tempfile copy
   (`ImportGuard.isWithinSizeLimit`, `MyDialogFragment.java:255-268`).
3. **Refresh the visible list after import.** Confirmed still true this
   pass: `MyDialogFragment.java`'s `FILE_SELECT_CODE_ZIP` branch calls
   `AsyncAssetManager.extractPluginZip(tempFile)` on success but does not
   call `addPluginsToList()` afterward — the imported plugin is invisible in
   the dialog until it's reopened. Fixing that specific line is plan 013's
   scope (already landed for the JSON-config path's crash-safety, not this
   particular UX gap), not this plan's — but any new Compose plugin-manager
   screen must not repeat the omission: its import handler must re-scan
   `plugins/`/`disabledPlugins/` (and update `plugin_sources.json`, per §2)
   immediately after a successful `extractPluginZip` call, before returning
   control to the list UI.

## 4. Compose plugin-manager screen sketch (Step 4)

Entry point: a new `RsButton("Manage plugins", onClick = onOpenPlugins, ...)`
placed next to the existing `RsButton("Open advanced settings", onClick =
onOpenAdvanced, muted = true)` in the "Advanced" section
(`SettingsActivity.kt:338-341`), following the same "settings screen button
launches a dedicated Activity" pattern already used for
`onOpenControls` → `CustomControlsActivity` (`SettingsActivity.kt:145`)
rather than inlining a plugin list into the main `LazyColumn`.

Screen contents (`PluginManagerActivity`, new):

- One row per `PluginInfo` (§2), each showing `displayName` and an
  `RsToggle` mirroring `BoolRow`'s existing pattern
  (`SettingsActivity.kt:369-382`) — `checked = plugin.enabled`,
  `onCheckedChange` triggers the existing directory-move logic (the same
  `renameTo` `MyDialogFragment.processPluginDirectory` already does today,
  relocated/shared rather than duplicated) followed by a list refresh.
- An **"Import plugin"** button reusing the existing file-picker pattern —
  `registerForActivityResult(ActivityResultContracts.OpenDocument())`
  (`SettingsActivity.kt:134-136`'s style), launched with a ZIP mime-type
  array matching `MyDialogFragment.java:300`'s existing list
  (`application/zip`, `application/x-zip-compressed`, `multipart/x-zip`) —
  followed by the confirmation dialog and capped extraction from §3.
- A **remove** action shown only for rows where `source == IMPORTED`.
  Bundled plugins (`source == BUNDLED`) show only the enable/disable toggle,
  never a remove option — a bundled plugin's directory came from the signed
  APK's own assets and re-extracting it (e.g. after an app update) should not
  have to contend with the user having permanently deleted it. This
  distinction is exactly why §2 tracks `source` outside the filesystem-as-
  state model: the directory tree alone can't tell "may this be deleted"
  from "may this only be toggled."

## 5. RT4-Client (external repo) open questions — REQUIRED, unresolved

None of the following can be answered by reading this repo — `rt4.jar` is a
prebuilt artifact of a separate GitLab project
(`gitlab.com/downthecrop/rt4-client`, branch `lwjgl-mobile-callbacks` per
this repo's own `CLAUDE.md`), and plugin loading happens entirely inside
that jar, not in this launcher. This design does **not** guess at any of
these — each is flagged as blocking before any *real* (non-read-only) manager
ships:

1. **Does `rt4.jar` read `-DpluginDir=` (`Tools.java:223`) — or
   `config.json`'s `"pluginsFolder": "plugins"` key
   (`app_pojavlauncher/src/main/assets/config.json:9`) — once at its own
   startup, or does it rescan the directory at some later point (e.g. on a
   client-side "reload plugins" action)?** If once-only (the more likely
   default for a JVM classloader-based plugin system, but **not confirmed**
   from this repo), then toggling a plugin via any manager UI — the existing
   legacy dialog or a new Compose screen — has **zero effect on the running
   client** until the next full game relaunch. Any manager UI's copy must
   say so explicitly ("takes effect next launch") rather than implying a
   live toggle, unless and until this is confirmed otherwise from rt4-client
   source.
2. **Does the RT4-Client have its own independent enable/disable concept**
   — e.g. a manifest file inside each plugin ZIP, or a client-side config
   listing which plugin names are active — that this app's directory-move
   mechanism (moving the whole extracted folder between `plugins/` and
   `disabledPlugins/`) might silently conflict with, duplicate, or be
   overridden by? If rt4-client has its own idea of "disabled," this app's
   mechanism could be redundant or, worse, could unpack a plugin that
   rt4-client's own logic still treats as inactive (or vice versa).
3. **Is there a plugin manifest/API format (name, version, entry point,
   declared permissions, etc.) defined in the rt4-client source** that
   `PluginInfo` (§2) should mirror instead of the from-scratch shape
   proposed here? If one exists, `displayName` should come from that
   manifest rather than defaulting to the raw directory name, and `source`
   tracking might be redundant with manifest-declared identity.
4. **Which of the two coexisting mechanisms — this app's `-DpluginDir=`
   system property (`Tools.java:223`) or `config.json`'s own
   `"pluginsFolder": "plugins"` key
   (`app_pojavlauncher/src/main/assets/config.json:9`) — actually governs
   plugin discovery inside rt4-client, or must both agree?** Today both
   point at the same literal value (`"plugins"` relative to the same data
   dir) by convention, not by any enforced link in this repo's code — a
   manager UI that only updates one of them (e.g. if a future rt4-client
   version keys off `config.json` instead of the system property) could
   silently stop working after an rt4-client update. This is the same
   two-mechanism ambiguity plan 030 (`docs/design/artifact-config-
   integrity.md`) documents for config import in general.

**Answering any of these requires reading `gitlab.com/downthecrop/rt4-client`
source (specifically its plugin-loading/classloader code), which is out of
this repo's and this plan's scope.** Per this plan's own STOP condition, none
of these are answered with an assumed rt4-client behavior here.

## 6. Prototype

**None built this pass.** The plan permits a read-only Compose list (display
`plugins/`/`disabledPlugins/` contents, no toggle/import/remove wired to real
file operations) as a low-risk option, but this pass chose doc-only:

- The design's own dependency chain (§3) already establishes that a *useful*
  manager (one with a working toggle or import) requires plan 030's
  integrity confirmation dialog and plan 015's caps to compose correctly —
  both now exist, so the natural next step is a *build* plan consuming this
  document, not a partial UI carved out of it.
- A read-only list adds a new Activity + navigation wiring + a Gradle/Docker
  build-and-test cycle for a screen with no interactive behavior to verify
  beyond "does it list the same five directories `MyDialogFragment` already
  lists" — value low relative to the design work above, and this plan's own
  Step 4 sketch (§4) already specifies exactly what that screen would show,
  making the prototype code non-load-bearing for a future implementer.
- No source files were touched; `git status` after this pass shows only this
  document and the `plans/README.md` status-row update.

Follow-up (deferred, out of this plan, per the plan's own maintenance notes):
a future *build* plan should consume this document, plan 030's integrity
design, and plan 015's zip caps as its "Current state" inputs — and should
also consume whatever future investigation (if any) answers §5's open
questions from rt4-client source, revisiting `PluginInfo` (§2) to mirror any
manifest format that investigation reveals rather than shipping two
divergent shapes.
