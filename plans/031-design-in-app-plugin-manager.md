# Plan 031: Design — in-app plugin manager (enable/disable/import)

> **Executor instructions**: This is a DESIGN plan, not a build-everything plan.
> The deliverable is a design doc (plugin descriptor + enable/disable
> persistence model, safe-import design, a Compose plugin-manager screen
> sketch) plus an explicit, prominent list of the rt4-client-side (external
> repo) open questions — and a prototype ONLY if it stays low-risk. Follow the
> steps in order; run every verification command; if a STOP condition
> triggers, stop and report — do not improvise past it. When done, update this
> plan's row in `plans/README.md` — unless a reviewer dispatched you and told
> you they maintain the index.
>
> **Drift check (run first)**: `git diff --stat 8ee361ea1..HEAD -- app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MyDialogFragment.java app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/tasks/AsyncAssetManager.java app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt`
> If any of these changed since this plan was written, re-read the live file
> and compare against the "Current state" excerpts before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P3
- **Effort**: L (coarse — the in-app half is boundable; the true cost hinges
  entirely on the rt4-client-side open question, which this repo cannot
  answer directly)
- **Risk**: MED
- **Depends on**: `plans/030-spike-artifact-config-integrity.md` should
  precede any REAL (non-prototype) plugin import work — a plugin ZIP is
  executable-adjacent content and importing it without an integrity/trust
  story is the same risk class Plan 030 inventories. `plans/015-*.md` (zip
  caps) should also precede real import for resource-exhaustion limits. This
  design plan itself can proceed without either landing first — it only reads
  and designs.
- **Category**: direction
- **Planned at**: commit `8ee361ea1`, 2026-07-10

## Why this matters

Client plugins ship as five fixed ZIPs baked into the APK
(`GroundItems`, `LoginTimer`, `MobileClientBindings`, `RememberMyLogin`,
`SlayerTrackerPlugin` — confirmed via `ls app_pojavlauncher/src/main/assets/plugins/`)
and are unzipped once at first run; there is no in-app way to add, remove, or
toggle a plugin without rebuilding the APK or using a crashy legacy dialog
flow. Meanwhile there IS already a plugin enable/disable UI stub
(`MyDialogFragment.processPluginDirectory`) that moves a plugin's extracted
directory between `plugins/` and `disabledPlugins/` — so half the "plugin
manager" concept already exists, just in the legacy `View`-based "Advanced"
dialog rather than the new Compose settings screen, and without any safe
import path behind it. A design that consolidates this into the Compose
settings surface, with a safe import story, lets users install community
plugins without a rebuild. The load-bearing open question — whether `rt4.jar`
(the RT4-Client, a separate repo) actually re-reads `disabledPlugins/` moves at
runtime or only at its own startup/plugin-discovery time — is external to this
repo and must be answered before any of this ships.

## Current state

Files and roles:
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MyDialogFragment.java`
  — the legacy "Advanced" settings dialog; already builds a plugin list with
  enable/disable `Switch` rows, and has a (per `plans/013-*.md`'s finding)
  crashy plugin-ZIP import path.
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/tasks/AsyncAssetManager.java`
  — unpacks the five bundled plugin ZIPs once; owns `extractPluginZip`, the
  entry point any new import path must also call (or replace).
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java` — wires
  `-DpluginDir=` for the launched JVM; this is the ONE place this repo tells
  the RT4-Client where plugins live — it does not tell it anything about
  which are "enabled."
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt` —
  the new Compose settings screen; today explicitly punts plugins to the
  legacy dialog (`"Renderer, runtime, plugins, and imports"`,
  `SettingsActivity.kt:264`) rather than surfacing them directly.

Confirmed excerpts (re-read at commit `8ee361ea1`):

**The existing plugin list + enable/disable UI** —
`MyDialogFragment.java:109-132` (`addPluginsToList`):
```java
private void addPluginsToList() {
    // Path for plugins and disabled plugins
    pluginsDirectory = new File(Tools.DIR_DATA + "/plugins/");
    disabledPluginsDirectory = new File(Tools.DIR_DATA + "/disabledPlugins/");
    ...
    // Clear the existing list
    pluginList.removeAllViews();
    // Process the enabled plugins
    processPluginDirectory(pluginsDirectory, true);
    // Process the disabled plugins
    processPluginDirectory(disabledPluginsDirectory, false);
}
```
`MyDialogFragment.java:134-188` (`processPluginDirectory`) builds one row per
plugin subdirectory with a name `TextView` and a `Switch`; the switch handler
(`:163-177`) is the actual enable/disable mechanism today — it just
`File.renameTo`s the plugin's extracted directory between `plugins/` and
`disabledPlugins/`:
```java
pluginSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
    File fromDir = isChecked ? disabledPluginsDirectory : pluginsDirectory;
    File toDir = isChecked ? pluginsDirectory : disabledPluginsDirectory;
    File fromFile = new File(fromDir, file.getName());
    File toFile = new File(toDir, file.getName());
    // Move the directory
    boolean success = fromFile.renameTo(toFile);
    if (!success) {
        Log.e("TAG", "Failed to move directory: " + fromFile.getPath() + " to " + toFile.getPath());
    }
});
```
This is the "enable/disable persistence model" that already exists: **the
filesystem location IS the enabled/disabled state** — no separate persisted
descriptor (no name/version/id list in `SharedPreferences` or anywhere else).
A directory present under `plugins/` is enabled; present under
`disabledPlugins/` is disabled. Any new design should keep this filesystem-as-
state-store model (it's simple and matches how `-DpluginDir=` is consumed) or
explicitly justify replacing it.

**The unpack-once entry point plugins go through today** —
`AsyncAssetManager.java:130-166` (`extractAllPlugins`), called once from
`unpackComponents` (`:121`), and `AsyncAssetManager.java:169-171`
(`extractPluginZip`), the import entry point:
```java
public static void extractPluginZip(File plugin) throws IOException {
    Tools.ZipTool.unzip(plugin, new File(Tools.DIR_DATA + "/plugins/"));
}
```
Both the bundled-plugin unpack and any user import ultimately call the same
`Tools.ZipTool.unzip`, which is already zip-slip-guarded (`Tools.java:597-599`,
plan 001) but has NO hash/integrity check (plan 030's territory) and no size
caps (plan 015's territory).

**The import path today (crashy, per plan 013)** —
`MyDialogFragment.java:214-243` (`onActivityResult`, `FILE_SELECT_CODE_ZIP`):
copies the user-picked file to `cacheDir/temp.zip`, then calls
`AsyncAssetManager.extractPluginZip(tempFile)` — but does NOT call
`addPluginsToList()` afterward to refresh the visible list, and doesn't handle
`inputStream`/`data.getData()` being null (a `NullPointerException` risk if
the picker returns no data). Note: this repo's Plan 013 (external to this
plan's own scope) is presumably the place that fixes the crash itself — this
plan's design should assume that fix lands separately and design the
*manager* UX on top of a working import.

**Where `-DpluginDir=` is set for the launched JVM** —
`Tools.java:219`:
```java
javaArgList.add("-DpluginDir="+ Tools.DIR_DATA + "/plugins/");
```
This is passed ONCE, at launch time (inside `launchGLJRE`, `Tools.java:205-228`).
This is the crux of the external open question: this repo only tells the JVM
where the plugin directory is at the moment it starts the JVM. Whether the
RT4-Client (`rt4.jar`, source at `gitlab.com/downthecrop/rt4-client`) itself
watches that directory, rescans it on some trigger, or only reads it once at
its own startup determines whether "toggle a plugin" can ever take effect
without a full app relaunch — this repo's code gives no evidence either way,
because plugin loading itself happens inside `rt4.jar`, not in this repo.

**Where a Compose plugin-manager screen would plug in** —
`SettingsActivity.kt:262-268`:
```kotlin
item {
    RsSectionHeader("Advanced")
    Text("Renderer, runtime, plugins, and imports", color = RsColors.textMuted)
    Spacer(Modifier.height(8.dp))
    RsButton("Open advanced settings", onClick = onOpenAdvanced, muted = true)
    Spacer(Modifier.height(20.dp))
}
```
`onOpenAdvanced` (`SettingsActivity.kt:129`) currently opens
`MyDialogFragment` (the legacy `View`-based dialog) via
`MyDialogFragment().show(supportFragmentManager, "advanced")`. A new
Compose plugin-manager screen would most naturally be a new
`RsButton("Manage plugins", ...)` alongside it, launching either a new
Activity or a nested Compose screen — matching the existing
`onOpenControls`/`CustomControlsActivity` pattern (`SettingsActivity.kt:128`)
of "settings screen button launches a dedicated Activity" rather than
inlining a complex list into the main settings `LazyColumn`.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Build APK (Docker, no host JDK/SDK) | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` | `BUILD SUCCESSFUL`; APK at `app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk` |
| Unit tests (if a prototype adds one) | append `:app_pojavlauncher:testDebugUnitTest` | JUnit report under `app_pojavlauncher/build/test-results/testDebugUnitTest/`, all green |
| Confirm current bundled plugin set | `ls app_pojavlauncher/src/main/assets/plugins/` | `GroundItems.zip LoginTimer.zip MobileClientBindings.zip RememberMyLogin.zip SlayerTrackerPlugin.zip` |
| Confirm plugin dir wiring | `grep -n "pluginDir\|pluginsFolder" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java app_pojavlauncher/src/main/assets/config.json` | shows `-DpluginDir=` in `Tools.java` and `"pluginsFolder": "plugins"` in `config.json` — two separate mechanisms telling the client roughly the same thing, worth reconciling in the design |

## Scope

**In scope:**
- Reading and documenting the existing plugin list/enable/disable UI
  (`MyDialogFragment`) and unpack path (`AsyncAssetManager`).
- Designing a plugin descriptor (name, source, enabled state) and confirming
  whether it should replace or just formalize the existing
  filesystem-location-is-state model.
- Designing a SAFE import flow that reuses plan 015's zip caps and plan 030's
  integrity design (by reference — do not re-litigate their designs here,
  just state the dependency).
- Sketching a Compose plugin-manager screen (list/toggle/import/remove).
- An explicit, separately-headed "RT4-Client (external repo) open questions"
  section.
- A prototype ONLY if it is low-risk: e.g. a read-only Compose list screen
  that just displays `plugins/`/`disabledPlugins/` contents (no toggle/import
  wired to real file moves yet) is acceptable; a toggle or import prototype is
  NOT low-risk without plan 030 landed first — do not build it.

**Out of scope (do NOT touch beyond reading, this pass):**
- Fixing the crashy import flow itself — that's `plans/013-*.md`'s scope;
  this plan's design assumes that fix lands independently.
- Any change inside `rt4.jar` or the rt4-client source — that repo is
  external (`gitlab.com/downthecrop/rt4-client`); this plan can only describe
  what needs to be TRUE there, not change it.
- Executing real plugin import/toggle wiring ahead of plan 030's integrity
  design.
- Replacing `MyDialogFragment`'s existing switch mechanism wholesale without
  first confirming (open question) whether the RT4-Client even notices a
  directory move after its own startup.

## Git workflow

- Branch: `advisor/031-design-in-app-plugin-manager`
- Commit per step; message style matches repo, e.g. `docs: design in-app
  plugin manager (descriptor, safe import, Compose UX)` for the design commit,
  `feat: read-only Compose plugin list prototype (spike, unwired)` for any
  prototype commit.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Document the existing plugin enumeration/enable model exactly

Re-confirm `MyDialogFragment.java:109-188` against the live file (drift check
at top of this plan). Write down, precisely: what makes a plugin "enabled"
today (directory under `Tools.DIR_DATA + "/plugins/"`) vs "disabled"
(directory under `Tools.DIR_DATA + "/disabledPlugins/"`), and that there is NO
separate descriptor file/list anywhere — the directory listing itself, via
`File.listFiles()` (`MyDialogFragment.java:135`), is the enumeration.

**Verify**: design doc's "Current model" section accurately restates this
without inventing a persistence mechanism that doesn't exist.

### Step 2: Define a plugin descriptor + enable/disable persistence model

Decide: keep the filesystem-location-as-state model (simplest, already
working, matches `-DpluginDir=` semantics) and layer a lightweight descriptor
ONLY for metadata the filesystem can't hold — e.g.:
```kotlin
data class PluginInfo(
    val directoryName: String,  // matches the extracted dir name, e.g. "GroundItems"
    val displayName: String,    // could default to directoryName
    val enabled: Boolean,       // derived from which directory it's currently in, not stored separately
    val source: PluginSource,   // BUNDLED (shipped in assets/plugins/) or IMPORTED (user-added)
)
```
Document why `enabled` should stay DERIVED from directory location (single
source of truth, no drift risk between a stored flag and the actual file
location) rather than a separately persisted boolean.

**Verify**: design doc states the descriptor shape and explicitly justifies
keeping enabled-state derived rather than separately persisted (or argues the
opposite, with reasoning).

### Step 3: Define SAFE import

Document that a real (non-prototype) import path must compose:
(a) plan 015's zip caps (entry count/size limits) before extraction,
(b) plan 030's chosen integrity design for Boundary 5 (plugin ZIP import) —
at minimum a size cap plus an explicit user-facing "installing untrusted code"
confirmation, since Plan 030 is expected to conclude no cryptographic trust
anchor exists for community plugins without external infrastructure,
(c) refresh the visible list after import (note: today's
`MyDialogFragment.java:214-243` import does NOT call `addPluginsToList()`
afterward — a bug worth flagging even though fixing it is plan 013's job, not
this plan's).

**Verify**: design doc's import section cites plans 015 and 030 by name and
states the three-part composition above.

### Step 4: Sketch a Compose plugin-manager screen

Sketch a screen reached via a new `RsButton("Manage plugins", ...)` next to
the existing `"Open advanced settings"` button (`SettingsActivity.kt:266`),
showing: one row per plugin (`PluginInfo.displayName` + a toggle mirroring
`RsToggle`, matching `BoolRow`'s pattern at `SettingsActivity.kt:290-303`),
an "Import plugin" button reusing the existing file-picker pattern
(`SettingsActivity.kt:115-118`'s `registerForActivityResult(OpenDocument())`
style, extended to a ZIP mime type per `MyDialogFragment.java:255-258`'s
existing mime list), and a remove action for `IMPORTED`-source plugins only
(bundled plugins should not be permanently removable, only disabled — note
this distinction in the design).

**Verify**: design doc has the screen sketch, states the entry point, and
distinguishes remove-eligibility by `PluginSource`.

### Step 5: Write the RT4-client (external repo) open questions — REQUIRED, do not skip

This is the gating section. Document, explicitly:
- Does `rt4.jar` read `-DpluginDir=` (or `config.json`'s `"pluginsFolder"`
  key, confirmed present at `app_pojavlauncher/src/main/assets/config.json`)
  once at its own startup, or does it rescan at some later point? If
  once-only, then toggling a plugin via this app's UI has NO effect until the
  next full game relaunch — the manager screen's copy/UX must say so
  explicitly rather than implying a live toggle.
- Does the RT4-Client itself have its own enable/disable concept (e.g. reading
  a manifest inside each plugin ZIP, or a client-side config listing active
  plugins) that this app's directory-move mechanism might conflict with or
  duplicate?
- Is there a plugin API/manifest format (name, version, entry point) defined
  in the rt4-client source that this repo's `PluginInfo` descriptor (Step 2)
  should mirror, instead of inventing its own shape from scratch?
- Two config-adjacent mechanisms currently coexist and may already be
  slightly inconsistent: this app's `-DpluginDir=` system property
  (`Tools.java:219`) and `config.json`'s own `"pluginsFolder": "plugins"` key
  (`app_pojavlauncher/src/main/assets/config.json`) — confirm (from the
  rt4-client source, not guesswork) which one actually governs plugin
  discovery, or whether both must agree.

None of these can be answered by reading this repo alone — flag that clearly
in the design doc rather than guessing at rt4-client internals.

**Verify**: design doc has a distinct "RT4-Client (external repo) open
questions" section with all four items above, each marked as requiring
`gitlab.com/downthecrop/rt4-client` source investigation to resolve, not
resolved from this repo.

## Test plan

- No existing unit-test coverage of `MyDialogFragment.java` or
  `AsyncAssetManager.java` (the repo's one JUnit test, `CameraPanTest.java`,
  is unrelated pure-logic code, and both these files have Android/`File`
  dependencies that don't fit its "pure logic, no Android framework deps"
  pattern without Robolectric).
- If Step 4's read-only prototype is built, no new automated test is required
  for a display-only list (no logic to unit-test beyond a directory listing);
  rely on the build + manual on-device confirmation that the list renders the
  same plugins `MyDialogFragment` shows.
- Verification: the Docker build command above → `BUILD SUCCESSFUL`.

## Done criteria

Machine-checkable. ALL must hold:

- [ ] Design doc covers: existing model (Step 1), descriptor + persistence
      model (Step 2), safe-import composition citing plans 015/030 (Step 3),
      Compose screen sketch (Step 4)
- [ ] "RT4-Client (external repo) open questions" section exists with all 4
      items from Step 5
- [ ] If a read-only prototype was attempted: `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` → `BUILD SUCCESSFUL`
- [ ] No toggle/import/remove logic was wired to real file operations in this
      pass (only a read-only list, if any prototype exists at all):
      `grep -n "renameTo\|extractPluginZip" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/ui` (or wherever the prototype screen lives) returns no matches
- [ ] No files outside the in-scope list are modified (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:
- The excerpts in "Current state" don't match the live file (drift beyond
  line-number shift).
- You find yourself needing to guess how `rt4.jar` discovers/reloads plugins
  to make a design decision — stop, record it under Step 5, and do NOT
  fabricate an assumed rt4-client behavior to move forward.
- A prototype attempt drifts into wiring real enable/disable or import logic
  — that requires plan 030 (integrity) first; scale back to read-only or stop.
- `plans/013-*.md` or `plans/015-*.md` don't exist yet in `plans/` when you
  reach Step 3 — cite them by their planned number/purpose anyway (per this
  plan's spec) and proceed; do not block the whole design on their existence.

## Maintenance notes

- Reviewer: the single most important thing to check in this design is that
  it does NOT promise "live toggle without relaunch" unless Step 5's rt4-client
  investigation actually confirmed that's possible — a wrong assumption here
  produces a shipped feature that silently doesn't work.
- Follow-up (deferred, out of this plan): actually building the import/toggle
  UI and wiring it to real file operations is a build plan that must consume
  this design doc AND plan 030's integrity design AND plan 015's zip caps as
  its "Current state" inputs; it should also consume whatever plan (if any)
  answers Step 5's open questions via rt4-client source investigation.
- If the rt4-client investigation (Step 5) reveals a plugin manifest format,
  revisit Step 2's `PluginInfo` descriptor to mirror it rather than shipping
  two divergent shapes.
