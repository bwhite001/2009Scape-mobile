# Open Issues Fix Plan (2009Scape-mobile)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Each task is independent unless noted; verify on-device per `docs/verification/device-smoke-checklist.md`.

**Goal:** Triage and fix every open GitHub issue (11 as of 2026-07-09) on `2009scape/2009Scape-mobile`. This plan indexes each issue, records the exact code it lives in (grounded in a codebase sweep), and groups the fixes into ordered tasks. It is explicit about which issues are **fixable in this repo** vs which are rooted in the **external client** (`rt4.jar`, gitlab `downthecrop/rt4-client`) or the **external gl4es fork** (shipped only as the prebuilt `libgl4es_114.so`) and therefore cannot be fixed by editing this repo alone.

**Repo layout reminder:** HD path = `MainActivity` → `GLFWGLSurface` (GL via LWJGL3 shim → `libgl4es_114.so`). SD path = `JavaGUILauncherActivity` → AWT canvas. The game client `rt4.jar` and the gl4es GL-translation source are **not in this tree**.

---

## Issue index

| # | Type | Cluster | Fixable here? | One-line |
|---|------|---------|---------------|----------|
| #32 | bug | build/docs | ✅ yes | Bare `gradlew` runs `:help`, produces no APK; no build docs |
| #29 (volume) | bug | input | ✅ yes | Volume keys dead in SD mode |
| #23 | bug | controls | ✅ yes | PRI/SEC buttons shown even when not in mouse mode |
| #22 | bug | input | ✅ yes | Swipe after long-press moves camera / extra clicks |
| #25, #30 (scroll) | bug | input | ✅ yes | Long-press = discrete right-click; no held-drag → can't scroll menus |
| #31 | enh | input | ✅ yes | S-Pen/stylus produces cursor move but no click |
| #33, #27, #30 (glitch) | bug | GL render | ⚠️ partial | World-map discoloration/tearing; HD zoom-out white; Samsung GL load fail |
| #29 (sounds) | bug | audio | ❌ external | In-game action sounds missing (both modes) — client-side |
| #7 | enh | roadmap | mixed | TODO 2.0 list (audio, renaming, drag-click, keyboard, HD crash) |
| #19 | enh | meta | triage | "Suggestions" — screenshot only, needs manual triage |

Priority order below: **P0 quick, high-value in-repo wins → P1 in-repo input fixes → P2 rendering (launcher levers + external hand-off) → P3 roadmap/triage.**

---

## Task 1 (P0): Fix build docs + `gradlew` default task — issue #32

**Root cause (verified):** root `build.gradle` is effectively empty (a single newline) and there is **no `defaultTasks` declaration anywhere**, so bare `gradlew`/`gradlew.bat` falls through to Gradle's built-in `:help` task — prints usage, reports `BUILD SUCCESSFUL`, builds nothing. The only build recipe lives in `CLAUDE.md` and `.github/workflows/android.yml`; `README.md` has zero build instructions and there is no `CONTRIBUTING`/`BUILDING`.

**Files:**
- Modify: `README.md` (add a Build section) — or create `BUILDING.md` and link it from README.
- Modify: `build.gradle` (root) — optional `defaultTasks` so a bare run does something useful.
- Modify: `CLAUDE.md:38` — stale version numbers.

**Step 1 — Document the real build.** Add a "Building from source" section (README or new `BUILDING.md`) with the canonical two-module, two-JDK recipe from CI (`android.yml`):

```bash
# 1. (JDK 8) build the LWJGL shim jar — emits into app assets
#    On a fresh checkout language_list.txt is already committed & correct,
#    so scripts/languagelist_updater.sh is only needed after adding/removing a values-* locale.
./gradlew :jre_lwjgl3glfw:build

# 2. (JDK 17) build the APK
./gradlew :app_pojavlauncher:assembleDebug
# -> app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk
```

Call out explicitly (these are what tripped the reporter): (a) two JDKs are required — **JDK 8** for `:jre_lwjgl3glfw` (`JavaLanguageVersion.of(8)`), **JDK 17** for `:app_pojavlauncher` (AGP 8.7.3); (b) there is **no single build task** — running bare `gradlew` does nothing; (c) Windows users can't run the bash `languagelist_updater.sh` but don't need it on a fresh checkout, and can alternatively use the checked-in `build.Dockerfile` (temurin-17 + openjdk-8 + Android SDK) for a one-command cross-platform build.

**Step 2 — Make bare `gradlew` self-documenting (optional but directly addresses the report).** In root `build.gradle` add either a `defaultTasks 'tasks'` (so a bare run lists tasks) **or** a tiny helper task that prints the build instructions and set it as default. Do **not** default to `assembleDebug` — it would fail confusingly on machines without the right JDK selected.

**Step 3 — Fix stale docs.** `CLAUDE.md:38` says `compileSdk 33 … AGP 7.4.2, Gradle 7.6.1`; actual values are **compileSdk 35, AGP 8.7.3, Gradle 8.9** (`app_pojavlauncher/build.gradle:84`, `gradle/libs.versions.toml:2`, `gradle-wrapper.properties`). Correct them.

**Verify:** fresh clone → follow the new README steps on both a Linux and a Windows/Docker path → APK is produced.

---

## Task 2 (P0): Volume keys in SD mode — issue #29 (volume half)

**Root cause (verified):** `JavaGUILauncherActivity.dispatchKeyEvent` (`JavaGUILauncherActivity.java:341-346`) unconditionally `return true`, consuming **every** key including `KEYCODE_VOLUME_UP`/`DOWN`, so Android never adjusts volume. HD works because `GLFWGLSurface.processKeyEvent` (`GLFWGLSurface.java:531-532`) explicitly `return false` for the two volume keys, letting the framework handle them.

**File:** `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/JavaGUILauncherActivity.java:341-346`

**Fix:** mirror the HD exclusion — let volume keys fall through:

```java
@Override
public boolean dispatchKeyEvent(KeyEvent event) {
    int kc = event.getKeyCode();
    if (kc == KeyEvent.KEYCODE_VOLUME_UP || kc == KeyEvent.KEYCODE_VOLUME_DOWN)
        return super.dispatchKeyEvent(event); // let the framework change volume
    if (event.getAction() == KeyEvent.ACTION_DOWN) {
        KeyEncoder.sendEncodedChar(event.getKeyCode(), (char) event.getUnicodeChar());
    }
    return true;
}
```

**Verify:** SD mode in-game → hardware volume rocker changes system media volume (matches HD).

---

## Task 3 (P0): Hide PRI/SEC buttons unless mouse mode active — issue #23

**Root cause (verified):** PRI (`SPECIALBTN_MOUSEPRI = -3`) and SEC (`SPECIALBTN_MOUSESEC = -4`) are defined in `default.json` (lines 75-120, both `"isHideable": true`) and their action is in `ControlButton.sendSpecialKey` (`ControlButton.java:220-230`). Their visibility follows only the global show/hide-all toggle (`ControlLayout.setControlVisible` → `ControlButton.setVisible`, `ControlButton.java:82-85`) — it is **not** linked to "mouse mode". "Mouse mode" = the `Touchpad` overlay toggled by `SPECIALBTN_VIRTUALMOUSE (-5)` → `MainActivity.toggleMouse` → `Touchpad.switchState()` (`MainActivity.java:322-328`, `Touchpad.java:144-163`).

**Files:** `MainActivity.java` (toggleMouse / touchpad state), `customcontrols/ControlLayout.java` (find buttons by keycode + set visibility), `customcontrols/buttons/ControlButton.java`.

**Fix approach:** when `Touchpad` is disabled, hide the PRI/SEC control buttons; when enabled, show them. Add a helper in `ControlLayout` to toggle visibility of buttons whose keycode is `SPECIALBTN_MOUSEPRI`/`SPECIALBTN_MOUSESEC`, and call it from `MainActivity.toggleMouse` (both enable and disable branches) plus once at layout load so the initial state (touchpad off) hides them. Keep it keyed off the touchpad enabled flag, not the global GUI toggle.

**Verify:** launch HD → PRI/SEC not shown; tap ⌨ drawer → Mouse (enable touchpad) → PRI/SEC appear; disable → they hide again.

> **⚠️ Design decision — coordinate with Task 12.** Community feedback (OnilinkZ, below) suggests *removing mouse mode and the camera-toggle button entirely* once touch is tuned, leaving only the keyboard button. If that direction is taken, this task becomes moot (no mouse mode → no PRI/SEC to conditionally show). Treat Task 3 as the **safe near-term fix** and Task 12 as the **contingent longer-term simplification**; do Task 3 now, revisit if Task 12 is approved.

---

## Task 4 (P1): Long-press gesture cleanup — issue #22

**Root cause (verified):** there is no unified gesture state machine; per-`ACTION_MOVE` logic in `GLFWGLSurface.onTouchEvent` causes three overlapping problems:
1. `panCamera(dx,dy)` is called on **every** `ACTION_MOVE` unconditionally (`GLFWGLSurface.java:288-303`, method `:432-452`) — any swipe pans the camera.
2. The long-press left-button hold flag `triggeredLeftMouseButton` (set in the delayed handler `:132`) is not cleared when a drag starts; the finger-still test only gates the initial trigger.
3. `longPressDetector` is fed the event twice (`:248-249` and again `:427`), and `mSingleTapDetector`/`mDoubleTapDetector` still run during the same gesture (`:275,285`) → extra clicks.

**File:** `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/GLFWGLSurface.java` (`onTouchEvent` switch, the pan/handler/detector wiring). Mirror in `JavaGUILauncherActivity.java:162-209` if the same pan logic exists there.

**Fix approach:**
- Gate `panCamera` so it only fires when actually in the pan gesture (not while a menu/long-press is active and not on the same finger that triggered a long-press). Track a `gestureConsumed`/`longPressActive` flag set on long-press trigger and cleared on `ACTION_UP/CANCEL`.
- Once a long-press fires, suppress the tap detectors and camera pan for the remainder of that gesture (so lifting doesn't register a stray tap and moving doesn't pan).
- Remove the duplicate `longPressDetector.onTouchEvent` dispatch (feed it once).
- Bonus (from the issue's "bonus points"): keep the context menu open on drag and select on release — deferred; note as follow-up, do not block the core fix.

**Verify:** long-press to open context menu, then drag without releasing → camera does **not** move, no extra character-move click registers, menu stays usable.

---

## Task 5 (P1): Held-drag / scrollable menus — issues #25, #30 (scroll half)

**Root cause (verified):** in non-grabbed (GUI/menu) mode, long-press calls `CallbackBridge.putMouseEventWithCoords` (`CallbackBridge.java:27-30`) which sends a mouse **down then up 33 ms later** — a discrete click, never a *held* button. So there is no way to click-and-drag a scrollbar in menus. (In world-grab mode a real held left-button exists via the `MSG_LEFT_MOUSE_BUTTON_CHECK` handler `:124-143`, but not in menus.) Two-finger scroll emulation exists (`GLFWGLSurface.java:319-329`) but users expect to drag the scrollbar directly. Per maintainer comment on #25, HD click-drag currently requires enabling mouse mode and holding PRI.

**File:** `GLFWGLSurface.java` (non-grabbed touch branch), `CallbackBridge.java`.

**Fix approach (pick one, brainstorm before implementing):**
- **(a) Held-press drag in menu mode:** when a finger goes down in non-grabbed mode and then moves beyond `FINGER_SCROLL_THRESHOLD` before the long-press timer, send a real `sendMouseButton(LEFT, true)` at the down position and stream `sendCursorPos` on move, then `sendMouseButton(LEFT, false)` on up — i.e. emulate a genuine click-drag so scrollbars work. Must not conflict with tap-to-click.
- **(b) Make two-finger scroll reliable** and document it as the menu-scroll gesture.
- Recommended: (a), because #25/#30 explicitly want scrollbar dragging and it also improves general menu usability. Coordinate with Task 4 so the two long-press/drag reworks don't collide (do Task 4 first, then build this on the cleaned-up state machine).

**Verify:** open bank/quest journal in SD and HD → drag the scrollbar with one finger → it scrolls.

---

## Task 6 (P1): S-Pen / stylus click support — issue #31

**Root cause (verified):** in `GLFWGLSurface.onTouchEvent` (`:258-265`) a `TOOL_TYPE_STYLUS` contact only calls `sendCursorPos` and returns — **no `sendMouseButton`** is ever generated, and when grabbing it returns false. `dispatchGenericMotionEvent` (`:471-493`) handles hover + `ACTION_BUTTON_PRESS/RELEASE` for mouse/stylus, but a stylus tapping the touchscreen arrives via `onTouchEvent`, which has no click path for it. No pressure / `getButtonState` barrel-button handling anywhere. `JavaGUILauncherActivity` has no tool-type handling at all.

**File:** `GLFWGLSurface.java` (`onTouchEvent` stylus branch `:258-265`; optionally `dispatchGenericMotionEvent`).

**Fix approach:** treat a stylus tip contact in `onTouchEvent` like a left-click: on stylus `ACTION_DOWN` send `sendMouseButton(LEFT, true)` at the position, on `ACTION_UP` send `false`; map the barrel button (`getButtonState() & BUTTON_STYLUS_PRIMARY/SECONDARY`) to right-click. Keep cursor-pos updates on move. Ensure grabbed vs non-grabbed both work (currently returns false when grabbing).

**Verify (needs S-Pen device, e.g. Galaxy Tab/Note):** stylus tap selects dialogue options and clicks the map; barrel button = right-click.

---

## Task 7 (P2): Rendering — Samsung GL load failure, world-map & zoom-out glitches — issues #33, #27, #30 (glitch half)

**Critical scope finding (verified):** the GL-translation source (**gl4es**) is **not in this repo** — `gl4es/` is empty and it ships only as the prebuilt `libgl4es_114.so` that CI pulls from an external fork. glDrawPixels passes straight through the stock LWJGL3 shim (`jre_lwjgl3glfw/.../GL11.java:2221+`) to that `.so`; `tinywrapper/main.c` (the ANGLE path) does not implement it. The prior "wrapped glDrawPixels" world-map fix therefore lives in **rt4.jar (the client) or the external gl4es fork**, neither in this tree. **These bugs cannot be fully fixed by editing this repo.** SD mode renders the world map correctly, confirming the defect is in the HD GL-translation stack, not launcher logic.

**What CAN be done in this repo (launcher levers):**

**7a — Stop the silent renderer fallback from hiding failures (#33).** `JREUtils.loadGraphicsLibrary` (`JREUtils.java:414-438`) silently falls back to GL4ES when the selected renderer `.so` fails to `dlopen` (`:431-436`). On Samsung devices where GL4ES itself is the problem, users have no signal and no easy alternate. Action: surface a user-visible toast/log when the fallback triggers, and make the renderer choice easy to reach (see 7b).

**7b — Expose/verify the renderer picker & try ANGLE for Samsung (#33).** Renderer is a preference (`LauncherPreferences.PREF_RENDERER`, default `opengles2` = GL4ES; UI `res/xml/pref_video.xml`, values in `headings_array.xml:37-41`). The ANGLE option (`opengles3_desktopgl_angle_vulkan` → `libtinywrapper.so` + `libEGL_angle.so`) is a candidate workaround for Samsung GL4ES failures. Action: confirm the renderer picker is reachable from Settings on the ScapeLauncher UI, document "if HD fails to load or the world map glitches on Samsung, switch renderer to ANGLE (or use SD)," and consider auto-selecting/recommending ANGLE on known-bad Samsung + GL4ES combos.

**7c — Investigate the color-format levers for discoloration/off-white (#33, #30).** Existing color env vars are set in `JREUtils.java:164-173` (`LIBGL_NORMALIZE=1` — "Fix white color on banner and sheep", `LIBGL_MIPMAP=3`, `LIBGL_NOINTOVLHACK=1`). The HD surface is fixed to `AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM` (`egl_bridge.c:734`, no alpha). The off-white-on-zoom-out and world-map discoloration are consistent with a pixel-format/readback mismatch in the GL4ES path. Action: experiment with these `LIBGL_*` flags and the buffer format as a launcher-side mitigation, but treat the real fix as external (below).

**External hand-off (must be tracked, cannot fix here):** the authoritative world-map/glDrawPixels fix belongs in the gl4es fork and/or `rt4.jar` (gitlab `downthecrop/rt4-client`). Action: open/verify upstream issues there referencing #27 and #33, and when a fixed `libgl4es_114.so`/`rt4.jar` is produced, update the bundled assets (remember to flip the `copyAssetFile(..., false)` guards in `AsyncAssetManager` — or bump the asset version stamp — so the new jar/so actually deploys).

**Verify:** on a Samsung A15/Tab S9 (repro devices): HD loads; renderer switch to ANGLE works; world map opens without discoloration/tearing; zoom-out does not white-out. Where launcher levers can't fix it, confirm the SD fallback is documented and the upstream tickets are filed.

---

## Task 8 (P3): In-game action sounds — issue #29 (sounds half) + issue #7 (audio)

**Root cause (verified):** `libopenal.so` is bundled for all ABIs and `dlopen`ed in **both** modes (`JREUtils.java:94`); the LWJGL `org/lwjgl/openal/*` bindings live in `lwjgl-glfw-classes.jar` but that jar is only on the **HD** classpath (`Tools.launchGLJRE`/`getLWJGL3ClassPath`), **not** the SD path (`JavaGUILauncherActivity.launchJavaRuntime` uses `-jar rt4.jar` with no `-cp`). Music playing but action sounds missing in **both** modes points to the defect being in **rt4.jar (client)** — the `openalaudio` branch called out in issue #7 — not launcher wiring.

**Actions:**
- **Launcher-side (this repo):** ensure the SD launch also puts the LWJGL/OpenAL bindings on the classpath if the client needs them there (align SD with HD classpath) — low-risk, may unblock any OpenAL binding resolution in SD.
- **Client-side (external, primary fix):** integrate/verify the `openalaudio` rt4-client branch (gitlab `downthecrop/rt4-client`), rebuild `rt4.jar`, drop it into assets, and force-redeploy (asset version bump / `copyAssetFile(..., true)`).
- Note the reporter's "reinstall fixes first-time music breakage" — likely the stale-asset unpack guard; the asset-version-stamp mechanism (already added in the prior mobile-improvements plan) should be confirmed to cover `rt4.jar`.

**Verify:** mining/woodcutting/attacking play sound in both SD and HD; music toggle independent of action sounds.

---

## Task 9 (P3): Roadmap & triage — issues #7, #19

**#7 (TODO 2.0):** a maintainer roadmap. Break its live items into their own tracked tasks: audio (→ Task 8), "rename Pojav/Minecraft vestiges" (cosmetic refactor; large, low-risk, do as a dedicated pass), drag-clicking (→ Tasks 4/5), "capital C/K mistyping / keyboard weirdness" (needs a dedicated keyboard-input investigation in `CallbackBridge`/`KeyEncoder` — not yet mapped; spawn an investigation), HD progress bar on load, and the HD crash (already root-caused in-thread to the XP-drop plugin, now disabled by default — verify and close). Recommend converting #7 into a checklist and closing sub-items as the tasks above land.

**#19 (Suggestions):** body is a single screenshot with no text — **requires manual triage**. Action: a human (or an image-capable pass) must read the screenshot and file discrete issues; do not guess. Flag to the maintainer.

---

## Community feedback — OnilinkZ (2026-07-09)

Playtester feedback (game is fully playable end-to-end; these are polish/direction items). Mapped to tasks:

| Point | Feedback | Maps to |
|-------|----------|---------|
| 1 | Interface scrolling should be reworked (minor) | **Task 5** (#25/#30) — reinforces |
| 2 | Sort inventory with tap-and-drag | **Task 10** (new) |
| 3 | Camera is serviceable but needs polish; vertical axis feels slightly off | **Task 11** (new) |
| 4 | Camera-toggle + mouse-mode buttons could be removed — a well-tuned mobile port needs only the keyboard button; in SD, add arrow-key binds on the right for fine camera control, keyboard button top-left | **Task 12** (new; interacts with Task 3) |

---

## Task 10 (P2): Inventory tap-and-drag item sorting — feedback #2 (relates to #7 "drag clicking")

**Goal:** let players drag items within the inventory to reorder them, as on desktop (press item, drag to new slot, release).

**Where:** this is a *held click-drag* over the game viewport — the same primitive missing in Task 5. In RS, inventory reorder is a left-button hold + move + release over the inventory interface. It depends on the held-drag capability from Task 5 (real `sendMouseButton(LEFT,true)` → `sendCursorPos` stream → `sendMouseButton(LEFT,false)`), not on a discrete click. The reorder *logic itself* lives in the client (`rt4.jar`); the launcher only needs to deliver a genuine press-move-release mouse stream.

**Fix approach:** once Task 5 lands, verify that a one-finger press-hold-drag over an inventory slot produces the correct held-button + cursor-move sequence the client interprets as item drag. If Task 5's held-drag is gated to scrollbars/menus only, extend it to inventory. Likely **no new primitive** beyond Task 5 — mainly tuning the long-press-vs-drag thresholds so a deliberate item drag isn't swallowed by tap or camera pan (coordinate with Tasks 4/5).

**Verify:** press an inventory item, drag to another slot, release → items swap/insert as on desktop.

---

## Task 11 (P2): Camera polish / vertical-axis calibration — feedback #3

**Root cause area:** camera panning is `GLFWGLSurface.panCamera(dx,dy)` (`GLFWGLSurface.java:432-452`), which sends AWT arrow keys, and the grabbed-camera math at `:333-356`. Feedback: horizontal is fine, **vertical feels slightly off** (sensitivity mismatch or inverted/over-damped axis).

**Fix approach:** audit the vertical (dy) branch of the camera math vs horizontal — check for asymmetric sensitivity scaling, an off-by sign, or different thresholds between axes. Consider exposing an X/Y camera-sensitivity + invert-Y preference in `LauncherPreferences` so it's tunable per device. Do this **after** Task 4 (gesture cleanup) so the pan path is already deduplicated.

**Verify:** on-device, vertical and horizontal camera drags feel proportionally matched; add a sensitivity slider if calibration alone doesn't satisfy.

---

## Task 12 (P3, design decision): Simplify the control scheme toward keyboard-only — feedback #4

**Proposal:** if touch controls are tuned well enough (Tasks 4, 5, 6, 11), the on-screen **camera-toggle** and **mouse-mode** buttons become redundant — a mobile port ideally needs only the keyboard button. In **SD** specifically, replace the camera-mode button with **arrow-key binds on the right-hand side** for fine camera control, and move the keyboard button to the **top-left**.

**Why this is a decision, not a task-yet:** it is contingent on the touch rework proving good enough that mouse mode is no longer needed, and it directly overrides Task 3 (#23) (if mouse mode is deleted, there is no PRI/SEC visibility to manage). It changes the default control layout (`default.json`) and the ⌨ drawer contents.

**Recommended sequencing:**
1. Ship Tasks 4/5/6/11 (touch/camera tuning).
2. Re-evaluate with playtesters whether mouse mode is still needed.
3. If not: remove the mouse-mode + camera-toggle buttons from `default.json` / the ⌨ drawer, add SD right-side arrow-key binds, reposition the keyboard button top-left. Otherwise: keep Task 3's conditional hiding.

**Open question for the maintainer:** commit to keyboard-only now (bolder, matches the playtester's vision) or keep mouse mode as a fallback and just tidy visibility (Task 3)? This gates whether Task 3 or Task 12 is the final state.

---

## Suggested execution order

1. **Task 1** (#32 build docs) — unblocks contributors, pure docs/config.
2. **Task 2** (#29 SD volume) — 3-line fix, verified root cause.
3. **Task 3** (#23 PRI/SEC visibility) — small, self-contained.
4. **Task 4** (#22 gesture cleanup) — foundation for Task 5.
5. **Task 5** (#25/#30 held-drag scroll) — builds on Task 4.
6. **Task 6** (#31 stylus click) — independent input add.
7. **Task 11** (camera vertical calibration) — after Task 4's pan cleanup.
8. **Task 10** (inventory drag-sort) — after Task 5's held-drag primitive.
9. **Task 7** (#33/#27/#30 rendering) — launcher levers + upstream hand-off.
10. **Task 8** (#29 sounds / #7 audio) — mostly external client work.
11. **Task 12** (control-scheme simplification) — re-evaluate after 4/5/6/11 ship; **decision gates Task 3's final state**.
12. **Task 9** (#7 roadmap, #19 triage) — planning/triage.

**Reality check on "fix all":** Tasks 1–6, 10, 11 are fully fixable in this repo. Task 7 (world map / Samsung GL) and Task 8 (action sounds) are **rooted in external artifacts** (`rt4.jar`, gl4es fork) and can only be *mitigated* here + fixed upstream. Task 12 is a design decision for the maintainer. #19 needs human triage. State this honestly when reporting progress — do not claim #27/#33/#29-sounds are "fixed" from a launcher-only change.
