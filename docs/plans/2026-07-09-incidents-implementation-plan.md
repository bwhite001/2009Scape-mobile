# Open Incidents Implementation Plan (2009Scape-mobile)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix every code-implementable open GitHub issue and playtester-feedback item for the 2009Scape-mobile Android launcher (build docs, SD volume keys, on-screen mouse-button visibility, touch-gesture correctness, held-drag scrolling, stylus clicks, and camera vertical calibration).

**Architecture:** All fixes are thin, localized edits to the inherited PojavLauncher Java layer. Input/gesture work concentrates in one file — `GLFWGLSurface.java` (the HD touch surface) — plus the on-screen control system (`ControlLayout`/`ControlButton`/`MainActivity`) and the SD activity (`JavaGUILauncherActivity`). Build/docs work touches Gradle + Markdown only. No native (NDK) or client-jar changes are in scope.

**Tech Stack:** Android (Java, `minSdk 21`/`compileSdk 35`), Gradle 8.9 + AGP 8.7.3, two modules (`:jre_lwjgl3glfw` JDK 8, `:app_pojavlauncher` JDK 17). Input reaches the client via `org.lwjgl.glfw.CallbackBridge` static natives.

## Global Constraints

- **New launcher code is Java.** Match the surrounding PojavLauncher style; keep every change minimal so upstream rebases stay tractable (per `2009Scape-mobile/CLAUDE.md`). Do not restructure files or rename inherited symbols.
- **Build is two-module / two-JDK.** `:jre_lwjgl3glfw` builds with **JDK 8**; `:app_pojavlauncher` builds with **JDK 17** (AGP 8.7.3). Never mix them in one invocation.
- **Canonical APK build:** `./gradlew :jre_lwjgl3glfw:build` (JDK 8) then `./gradlew :app_pojavlauncher:assembleDebug` (JDK 17) → `app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk`.
- **Primary verification is build + on-device smoke** per `docs/verification/device-smoke-checklist.md`. This repo has **no JUnit harness**; add a lightweight unit test **only** for genuinely pure, framework-free logic (Task 7). For all other tasks the "test" gate is: build the APK, install, and confirm the exact on-device observation stated in the task.
- **Asset edits do not auto-deploy.** `AsyncAssetManager.unpackComponents` skips re-copying existing files (`copyAssetFile(..., false)`). If a task changes `default.json`/`config.json`/`rt4.jar`, bump the asset version stamp (the mechanism added in `2026-07-08-mobile-improvements.md`) or flip the guard to `true`, or the change will not reach existing installs. Keep `default.json`/`config.json` valid JSON (CI lints all `*.json`).
- **Commit after each task** with a `feat:`/`fix:`/`docs:` prefixed message.

---

## File Structure

| File | Responsibility | Tasks |
|------|----------------|-------|
| `BUILDING.md` (new) | End-user build instructions | 1 |
| `build.gradle` (root) | Add a helpful default task | 1 |
| `README.md`, `CLAUDE.md` | Link build docs; fix stale versions | 1 |
| `JavaGUILauncherActivity.java` | SD key dispatch — stop eating volume keys | 2 |
| `MainActivity.java` | Mouse-mode toggle → drive PRI/SEC visibility | 3 |
| `customcontrols/ControlLayout.java` | Helper to show/hide the mouse buttons | 3 |
| `GLFWGLSurface.java` | Long-press/drag/stylus/camera touch logic | 4, 5, 6, 7 |
| `customcontrols/CameraPan.java` (new) | Pure pan-direction mapping (unit-tested) | 7 |
| `app_pojavlauncher/src/test/java/.../CameraPanTest.java` (new) | Unit test for CameraPan | 7 |
| `app_pojavlauncher/build.gradle` | Add `testImplementation junit` for Task 7 | 7 |

---

## Task 1: Build documentation + a helpful default Gradle task (issue #32)

**Root cause:** the root `build.gradle` is a single newline byte and there is **no `defaultTasks`** anywhere, so a bare `gradlew`/`gradlew.bat` runs Gradle's built-in `:help`, prints usage, reports `BUILD SUCCESSFUL`, and builds nothing — exactly what the reporter saw. `README.md` has zero build instructions; the only recipe lives in `CLAUDE.md` (whose version numbers are stale) and CI.

**Files:**
- Create: `BUILDING.md`
- Modify: `build.gradle` (root)
- Modify: `README.md`
- Modify: `CLAUDE.md:38`

- [ ] **Step 1: Create `BUILDING.md`**

Create `/runescape/2009Scape-mobile/BUILDING.md` with:

````markdown
# Building 2009Scape-mobile

This is a two-module Android project that needs **two different JDKs**:

- `:jre_lwjgl3glfw` — the LWJGL→GLFW shim — builds with **JDK 8**.
- `:app_pojavlauncher` — the Android app — builds with **JDK 17** (AGP 8.7.3).

There is **no single build task** — running `gradlew` with no arguments only prints
the task list. Run the two steps below in order.

## Build a debug APK

```bash
# 1. (JDK 8) Build the LWJGL shim jar. It emits directly into app assets.
./gradlew :jre_lwjgl3glfw:build

# 2. (JDK 17) Build the APK.
./gradlew :app_pojavlauncher:assembleDebug
```

Output: `app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk`

## Notes

- **Windows:** use `gradlew.bat` in place of `./gradlew`. You do **not** need to run
  `scripts/languagelist_updater.sh` (a bash script) on a fresh checkout — the
  `language_list.txt` asset it generates is already committed and correct. Only re-run
  it (on Linux/macOS/WSL) after adding or removing a `res/values-*` locale.
- **Both JDKs required:** if only one JDK is installed, one of the two steps will fail.
  Select the right JDK per step via `JAVA_HOME`.
- **Docker (any OS):** the checked-in `build.Dockerfile` provisions JDK 8 + JDK 17 +
  the Android SDK for a self-contained build.
- **SDK components:** `platforms;android-35`, `build-tools;35.0.0`, `ndk;25.2.9519653`.
````

- [ ] **Step 2: Add a self-documenting default task to root `build.gradle`**

Replace the contents of `/runescape/2009Scape-mobile/build.gradle` (currently just a newline) with:

```groovy
// This project has no single "build everything" task because its two modules
// require different JDKs (see BUILDING.md). A bare `gradlew` run lists tasks
// and points the user at the docs instead of silently doing nothing.
defaultTasks 'buildHelp'

tasks.register('buildHelp') {
    group = 'help'
    description = 'Explains how to build the APK (see BUILDING.md).'
    doLast {
        println ''
        println 'To build a debug APK (see BUILDING.md for details):'
        println '  1. (JDK 8)  ./gradlew :jre_lwjgl3glfw:build'
        println '  2. (JDK 17) ./gradlew :app_pojavlauncher:assembleDebug'
        println ''
        println 'APK -> app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk'
        println ''
    }
}
```

- [ ] **Step 3: Link the build docs from `README.md`**

Add, immediately after the `[Releases](...)` line in `README.md`:

```markdown

# Building from source

See [BUILDING.md](BUILDING.md). Note: two JDKs are required (JDK 8 + JDK 17) and there
is no single build task — a bare `gradlew` run only prints instructions.
```

- [ ] **Step 4: Fix stale versions in `CLAUDE.md`**

In `CLAUDE.md:38`, replace `compileSdk 33` … `Android Gradle Plugin 7.4.2, Gradle 7.6.1` with the actual values: **`compileSdk 35`**, **`Android Gradle Plugin 8.7.3`**, **`Gradle 8.9`**.

- [ ] **Step 5: Verify the default task**

Run: `./gradlew`
Expected: prints the "To build a debug APK" instructions and `BUILD SUCCESSFUL` (no more silent `:help`).

- [ ] **Step 6: Verify the documented build actually produces an APK**

Run (JDK 8 then JDK 17): `./gradlew :jre_lwjgl3glfw:build` then `./gradlew :app_pojavlauncher:assembleDebug`
Expected: `app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk` exists.

- [ ] **Step 7: Commit**

```bash
git add BUILDING.md build.gradle README.md CLAUDE.md
git commit -m "docs: add BUILDING.md, self-documenting default gradle task (#32)"
```

---

## Task 2: SD-mode hardware volume keys (issue #29, volume half)

**Root cause:** `JavaGUILauncherActivity.dispatchKeyEvent` unconditionally `return true`, consuming **every** key including `KEYCODE_VOLUME_UP`/`DOWN`, so Android never adjusts media volume in SD. The HD path already excludes volume keys (`GLFWGLSurface.processKeyEvent:531-532` returns `false`).

**Files:**
- Modify: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/JavaGUILauncherActivity.java:340-346`

- [ ] **Step 1: Let volume keys fall through to the framework**

Replace the `dispatchKeyEvent` method (currently at `JavaGUILauncherActivity.java:340-346`):

```java
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        // Let the OS handle media-volume changes (matches the HD path in
        // GLFWGLSurface.processKeyEvent) instead of swallowing them.
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            KeyEncoder.sendEncodedChar(event.getKeyCode(), (char) event.getUnicodeChar());
        }
        return true;
    }
```

- [ ] **Step 2: Build the APK**

Run: `./gradlew :app_pojavlauncher:assembleDebug`
Expected: `BUILD SUCCESSFUL`, APK produced.

- [ ] **Step 3: Verify on-device**

Install, launch **Play SD**, enter the game, press the hardware volume up/down rocker.
Expected: the system media-volume overlay appears and volume changes (same as HD). Typing in the game still works (letters still reach the client).

- [ ] **Step 4: Commit**

```bash
git add app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/JavaGUILauncherActivity.java
git commit -m "fix: allow hardware volume keys in SD mode (#29)"
```

---

## Task 3: Hide PRI/SEC buttons unless mouse mode is active (issue #23)

**Root cause:** PRI (`SPECIALBTN_MOUSEPRI = -3`) and SEC (`SPECIALBTN_MOUSESEC = -4`) buttons (defined in `default.json`, keycodes at lines ~85/108) are shown/hidden only by the global control toggle. Their visibility is not linked to "mouse mode" (the `Touchpad` overlay toggled via `MainActivity.toggleMouse` → `touchpad.switchState()`). So they occupy screen space even when touch/tap mode is active and they do nothing.

**Files:**
- Modify: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/customcontrols/ControlLayout.java` (add helper after `setControlVisible`, ~line 225)
- Modify: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java` (`toggleMouse` ~322, `loadControls` ~190)

**Interfaces:**
- Produces: `ControlLayout.setMouseButtonsVisible(boolean visible)` — shows/hides only the PRI/SEC control buttons.
- Consumes: `MainActivity.touchpad.switchState()` (returns `true` when mouse mode is now ON), `MainActivity.mControlLayout` (static).

- [ ] **Step 1: Add the visibility helper to `ControlLayout`**

Insert immediately after `setControlVisible(...)` (after `ControlLayout.java:225`):

```java
	/** Show or hide only the on-screen primary/secondary mouse buttons.
	 *  They are only useful while the virtual-mouse ("mouse mode") is active. */
	public void setMouseButtonsVisible(boolean visible) {
		if (mModifiable) return; // don't touch layouts in the controls editor
		for (ControlInterface button : getButtonChildren()) {
			int[] keycodes = button.getProperties().keycodes;
			if (keycodes != null && keycodes.length > 0
					&& (keycodes[0] == ControlData.SPECIALBTN_MOUSEPRI
					 || keycodes[0] == ControlData.SPECIALBTN_MOUSESEC)) {
				((View) button).setVisibility(visible ? View.VISIBLE : View.GONE);
			}
		}
	}
```

(If `ControlData` / `View` are not already imported in `ControlLayout.java`, add `import net.kdt.pojavlaunch.customcontrols.ControlData;` and `import android.view.View;`.)

- [ ] **Step 2: Drive the helper from the mouse-mode toggle**

Replace `MainActivity.toggleMouse` (`MainActivity.java:322-328`):

```java
    public static void toggleMouse(Context ctx) {
        if (CallbackBridge.isGrabbing()) return;

        boolean mouseOn = touchpad.switchState();
        mControlLayout.setMouseButtonsVisible(mouseOn);
        Toast.makeText(ctx, mouseOn ? R.string.control_mouseon : R.string.control_mouseoff,
                Toast.LENGTH_SHORT).show();
    }
```

- [ ] **Step 3: Hide PRI/SEC at layout load (mouse mode starts off)**

In `MainActivity.loadControls()`, add after `mControlLayout.toggleControlVisible();` (`MainActivity.java:190`):

```java
        // Mouse mode starts disabled, so the PRI/SEC buttons should start hidden.
        mControlLayout.setMouseButtonsVisible(false);
```

- [ ] **Step 4: Build the APK**

Run: `./gradlew :app_pojavlauncher:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Verify on-device**

Launch **Play HD**. Expected: PRI and SEC buttons are **not** shown. Open the ⌨ drawer → tap **Mouse** (enable mouse mode): PRI/SEC **appear**. Tap **Mouse** again (disable): PRI/SEC **hide**.

- [ ] **Step 6: Commit**

```bash
git add app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/customcontrols/ControlLayout.java app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java
git commit -m "fix: only show PRI/SEC buttons in mouse mode (#23)"
```

> **Known limitation:** the global GUI show/hide-all toggle (`setControlVisible`) can still re-show PRI/SEC. If device testing shows that path re-revealing them in tap mode, gate PRI/SEC inside `setControlVisible` on the same mouse-mode flag as a follow-up. Verify in Step 5 whether this matters in practice before adding complexity (YAGNI).

---

## Task 4: Long-press gesture cleanup — swipe after long-press no longer pans camera (issue #22)

**Root cause:** in `GLFWGLSurface.onTouchEvent`, `panCamera(dx, dy)` is called on **every** `ACTION_MOVE` unconditionally (`:295-299`), so any drag pans the camera — including a drag that follows a long-press context menu. Additionally `longPressDetector.onTouchEvent(e)` is dispatched **twice** (`:249` and `:427`), double-feeding the detector.

**Files:**
- Modify: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/GLFWGLSurface.java` (`onTouchEvent` `:247-430`)

**Interfaces:**
- Produces: a new instance field `private boolean mLongPressFired;` on `GLFWGLSurface`, set when the long-press right-click fires and cleared on the next `ACTION_DOWN`. Consumed by Task 5.

- [ ] **Step 1: Add the long-press state flag**

Add next to the other gesture fields (after `GLFWGLSurface.java:120`, near `triggeredLeftMouseButton`):

```java
    /* True from the moment a long-press right-click fires until the next finger-down.
       While true, moves must NOT pan the camera (issue #22). */
    private boolean mLongPressFired = false;
```

- [ ] **Step 2: Set the flag when the long-press fires**

In the `longPressDetector`'s `onLongPress` (`GLFWGLSurface.java:226-233`), set the flag when the right-click is dispatched:

```java
                @Override
                public void onLongPress(MotionEvent e) {
                    super.onLongPress(e);
                    // In single-tap-right-click mode a normal tap already opens the menu,
                    // so the long-press right-click is redundant — skip it (and its haptic) entirely.
                    if (LauncherPreferences.PREF_SINGLE_TAP_RIGHTCLICK) return;
                    Haptics.vibrate(getContext(), Haptics.LONG_PRESS_MS);
                    CallbackBridge.putMouseEventWithCoords(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, CallbackBridge.mouseX, CallbackBridge.mouseY);
                    mLongPressFired = true;
                }
```

- [ ] **Step 3: Clear the flag on each new touch-down**

In the `ACTION_DOWN` case, add as the first line after `case MotionEvent.ACTION_DOWN:` (`GLFWGLSurface.java:358`):

```java
            case MotionEvent.ACTION_DOWN: // 0
                mLongPressFired = false;
                startX = e.getX();
                startY = e.getY();
```

- [ ] **Step 4: Gate camera panning so a post-long-press swipe doesn't move the camera**

Replace the pan block in the `ACTION_MOVE` case (`GLFWGLSurface.java:288-303`):

```java
            case MotionEvent.ACTION_MOVE:
                // Maybe here we do camera panning?
                // Calculate the distance moved
                float dx = (e.getX()) - startX;
                float dy = (e.getY()) - startY;

                // Do not pan the camera while a long-press context menu is up (issue #22),
                // and only pan with a single finger.
                if (!mLongPressFired && e.getPointerCount() == 1) {
                    try {
                        panCamera(dx, dy);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }

                // Update start position
                startX = e.getX();
                startY = e.getY();
```

- [ ] **Step 5: Remove the duplicate long-press dispatch**

Delete the second `longPressDetector.onTouchEvent(e);` at `GLFWGLSurface.java:427` (keep only the one at `:249`). After deletion the tail of `onTouchEvent` reads:

```java
        // Actualise the pointer count
        mLastPointerCount = e.getPointerCount();

        return true;
    }
```

- [ ] **Step 6: Build the APK**

Run: `./gradlew :app_pojavlauncher:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Verify on-device**

Launch **Play HD**, enter the world. Long-press an object to open the context menu, then — without releasing — drag your finger sideways.
Expected: the camera does **not** move and no extra character-move click is registered; the menu stays put. Normal one-finger drag (no long-press) still pans the camera.

- [ ] **Step 8: Commit**

```bash
git add app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/GLFWGLSurface.java
git commit -m "fix: don't pan camera when swiping after a long-press (#22)"
```

---

## Task 5: Held-drag click for scrollbars & menus (issues #25, #30 scroll half; feedback #1)

**Root cause:** in non-grabbed (menu) mode, a long-press sends `putMouseEventWithCoords` — a mouse **down then up 33 ms later**, i.e. a discrete click, never a *held* button. So you cannot click-and-drag a scrollbar. Menu scrolling today only works via two-finger scroll (`GLFWGLSurface.java:319-329`).

This task adds a **one-finger press-hold-then-drag** that emits a genuine held left button (down → cursor-move stream → up) while a menu is open, so scrollbars and sliders can be dragged like on desktop.

**Files:**
- Modify: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/GLFWGLSurface.java` (`onTouchEvent` non-grabbed branch)

**Interfaces:**
- Consumes: `mLongPressFired` (Task 4), `CallbackBridge.sendMouseButton`, `CallbackBridge.sendCursorPos`, `FINGER_STILL_THRESHOLD`.
- Produces: instance field `private boolean mMenuDragging;`.

- [ ] **Step 1: Add the menu-drag state field**

Add near the other gesture fields (after `GLFWGLSurface.java:120`):

```java
    /* True while a one-finger click-drag is in progress in a menu (non-grabbed),
       so scrollbars/sliders can be dragged (issues #25, #30). */
    private boolean mMenuDragging = false;
```

- [ ] **Step 2: Start / continue / end the held drag in the non-grabbed move branch**

Replace the single-finger non-grabbed hover block (`GLFWGLSurface.java:311-317`, the `if(pointerCount == 1){ ... }` inside `if(!CallbackBridge.isGrabbing()){`):

```java
                    // Touch hover / one-finger click-drag (for scrollbars, sliders)
                    if(pointerCount == 1){
                        CallbackBridge.mouseX = (e.getX() * mScaleFactor);
                        CallbackBridge.mouseY = (e.getY() * mScaleFactor);

                        // Once the finger has moved past the still-threshold, treat it as a
                        // held left-button drag so the client can drag scrollbars/sliders.
                        if(!mMenuDragging &&
                                MathUtils.dist(e.getX(), e.getY(), mInitialX, mInitialY) >= FINGER_STILL_THRESHOLD){
                            mMenuDragging = true;
                            sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, true);
                        }
                        CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
                        mPrevX =  e.getX();
                        mPrevY =  e.getY();
                        break;
                    }
```

- [ ] **Step 3: Record the initial down position in the non-grabbed case**

In `ACTION_DOWN`, the initial position is only stored when grabbing (`:383-384`). Record it for the non-grabbed case too. In the `ACTION_DOWN` block, after `mPrevX = e.getX(); mPrevY = e.getY();` (`GLFWGLSurface.java:377-378`) add:

```java
                mInitialX = e.getX();
                mInitialY = e.getY();
```

Note: the grabbed branch just below overwrites `mInitialX/Y` with `CallbackBridge.mouseX/Y` for its own use, so this is safe for the non-grabbed path.

- [ ] **Step 4: Release the held button on up/cancel**

Add a release at the start of both `ACTION_UP` and `ACTION_CANCEL`. Replace the `ACTION_UP` case (`GLFWGLSurface.java:390-394`):

```java
            case MotionEvent.ACTION_UP: // 1
                if(mMenuDragging){
                    sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, false);
                    mMenuDragging = false;
                }
                // End of drag, reset the start position
                startX = 0;
                startY = 0;
                break;
```

And in `ACTION_CANCEL`, add as the first lines after `case MotionEvent.ACTION_CANCEL:` (`GLFWGLSurface.java:395`):

```java
            case MotionEvent.ACTION_CANCEL: // 3
                if(mMenuDragging){
                    sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, false);
                    mMenuDragging = false;
                }
                mShouldBeDown = false;
                mCurrentPointerID = -1;
```

- [ ] **Step 5: Suppress the discrete tap-click while dragging**

The single-tap detector (`GLFWGLSurface.java:275-281`) would still fire a click at the end of a drag. Guard it — replace the `if(mSingleTapDetector.onTouchEvent(e)){` line and add a `!mMenuDragging` condition:

```java
            if(mSingleTapDetector.onTouchEvent(e) && !mMenuDragging){
```

- [ ] **Step 6: Build the APK**

Run: `./gradlew :app_pojavlauncher:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Verify on-device**

Launch **Play HD** (and repeat in **SD** if the same surface path applies). Open the bank or the quest journal. Press on the scrollbar/thumb and drag one finger up/down.
Expected: the list scrolls by dragging the scrollbar. A quick tap still registers as a single click (opens/selects). Two-finger scroll still works.

> **Tuning note:** `FINGER_STILL_THRESHOLD` (9dp) is the drag-start threshold. If a normal tap is being misread as a drag, or a slow drag doesn't start, adjust this constant and re-verify. This is a feel parameter — expect one on-device tuning pass.

- [ ] **Step 8: Commit**

```bash
git add app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/GLFWGLSurface.java
git commit -m "feat: one-finger held-drag for scrollbars/sliders in menus (#25, #30)"
```

---

## Task 6: Stylus / S-Pen click support (issue #31)

**Root cause:** in `onTouchEvent`, a `TOOL_TYPE_STYLUS` contact only calls `sendCursorPos` and returns (`GLFWGLSurface.java:258-265`); it never sends a mouse button, and when grabbing it returns `false` (no handling). So a stylus can move the pointer but cannot click dialogue options or the map.

**Files:**
- Modify: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/GLFWGLSurface.java:257-265`

- [ ] **Step 1: Emit clicks for stylus down/up in the mouse/stylus branch**

Replace the mouse/stylus loop (`GLFWGLSurface.java:257-265`):

```java
        // Looking for a mouse or stylus to handle.
        for (int i = 0; i < e.getPointerCount(); i++) {
            int toolType = e.getToolType(i);
            if(toolType != MotionEvent.TOOL_TYPE_MOUSE && toolType != MotionEvent.TOOL_TYPE_STYLUS ) continue;

            // Move the cursor for both mouse and stylus.
            if(!CallbackBridge.isGrabbing()){
                CallbackBridge.mouseX = e.getX(i) * mScaleFactor;
                CallbackBridge.mouseY = e.getY(i) * mScaleFactor;
                CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
            }

            // A stylus tip touching the screen has no ACTION_BUTTON_* events, so map its
            // down/up here to a left click (barrel button -> right click). (issue #31)
            if(toolType == MotionEvent.TOOL_TYPE_STYLUS){
                boolean barrel = (e.getButtonState()
                        & (MotionEvent.BUTTON_STYLUS_PRIMARY | MotionEvent.BUTTON_SECONDARY)) != 0;
                int glfwButton = barrel
                        ? LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT
                        : LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT;
                int action = e.getActionMasked();
                if(action == MotionEvent.ACTION_DOWN){
                    sendMouseButton(glfwButton, true);
                } else if(action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL){
                    sendMouseButton(glfwButton, false);
                }
            }
            return true; // pointer event handled
        }
```

- [ ] **Step 2: Build the APK**

Run: `./gradlew :app_pojavlauncher:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Verify on-device (requires an S-Pen device, e.g. Galaxy Tab/Note)**

Launch **Play HD**. Tap a dialogue option and a spot on the world map with the stylus tip.
Expected: the tap selects/clicks (left click). Holding the barrel button while tapping produces a right-click (context menu). Hovering still moves the cursor.

> If no S-Pen device is available, mark this task **build-verified only** and flag it for a device owner in the smoke checklist — do not claim it works.

- [ ] **Step 4: Commit**

```bash
git add app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/GLFWGLSurface.java
git commit -m "feat: stylus/S-Pen tap = click, barrel button = right-click (#31)"
```

---

## Task 7: Camera vertical calibration + invert-Y option (feedback #3)

**Root cause / opportunity:** `panCamera` (`GLFWGLSurface.java:432-452`) fires one discrete AWT arrow key per move event using a single shared `threshold = 8.0f` for both axes, with vertical mapped `dy>threshold → VK_UP`. The playtester reports vertical "feels slightly off." This task extracts the pan-direction decision into a pure, unit-tested helper with **separate X/Y thresholds and an invert-Y toggle**, then wires `panCamera` to it. The extraction is the one place a genuine RED/GREEN unit test fits.

**Files:**
- Create: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/customcontrols/CameraPan.java`
- Create: `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/customcontrols/CameraPanTest.java`
- Modify: `app_pojavlauncher/build.gradle` (add `testImplementation junit`)
- Modify: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/GLFWGLSurface.java` (`panCamera`)

**Interfaces:**
- Produces: `CameraPan.directions(float dx, float dy, float thresholdX, float thresholdY, boolean invertY)` returning `int[]{horizontal, vertical}` where each element is one of `CameraPan.NONE`, `CameraPan.LEFT`/`CameraPan.RIGHT`, `CameraPan.UP`/`CameraPan.DOWN`.

- [ ] **Step 1: Add the JUnit test dependency**

In `app_pojavlauncher/build.gradle`, inside the existing `dependencies { ... }` block, add:

```groovy
    testImplementation 'junit:junit:4.13.2'
```

- [ ] **Step 2: Write the failing test**

Create `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/customcontrols/CameraPanTest.java`:

```java
package net.kdt.pojavlaunch.customcontrols;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class CameraPanTest {
    @Test public void belowThresholdIsNone() {
        int[] d = CameraPan.directions(3f, 3f, 8f, 8f, false);
        assertEquals(CameraPan.NONE, d[0]);
        assertEquals(CameraPan.NONE, d[1]);
    }
    @Test public void horizontalMapsLeftRight() {
        assertEquals(CameraPan.RIGHT, CameraPan.directions(10f, 0f, 8f, 8f, false)[0]);
        assertEquals(CameraPan.LEFT,  CameraPan.directions(-10f, 0f, 8f, 8f, false)[0]);
    }
    @Test public void verticalMapsUpDown() {
        assertEquals(CameraPan.UP,   CameraPan.directions(0f, 10f, 8f, 8f, false)[1]);
        assertEquals(CameraPan.DOWN, CameraPan.directions(0f, -10f, 8f, 8f, false)[1]);
    }
    @Test public void invertYSwapsVertical() {
        assertEquals(CameraPan.DOWN, CameraPan.directions(0f, 10f, 8f, 8f, true)[1]);
        assertEquals(CameraPan.UP,   CameraPan.directions(0f, -10f, 8f, 8f, true)[1]);
    }
    @Test public void separateThresholdsPerAxis() {
        // dy=6 is below a vertical threshold of 12 but dx=6 is above a horizontal threshold of 4
        int[] d = CameraPan.directions(6f, 6f, 4f, 12f, false);
        assertEquals(CameraPan.RIGHT, d[0]);
        assertEquals(CameraPan.NONE, d[1]);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :app_pojavlauncher:testDebugUnitTest --tests net.kdt.pojavlaunch.customcontrols.CameraPanTest`
Expected: FAIL — `CameraPan` does not exist (compilation error).

- [ ] **Step 4: Implement `CameraPan`**

Create `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/customcontrols/CameraPan.java`:

```java
package net.kdt.pojavlaunch.customcontrols;

/** Pure decision logic for one-finger camera panning. No Android/AWT dependencies,
 *  so it is unit-testable. Callers translate the returned codes into real key events. */
public final class CameraPan {
    public static final int NONE = 0;
    public static final int LEFT = 1;
    public static final int RIGHT = 2;
    public static final int UP = 3;
    public static final int DOWN = 4;

    private CameraPan() {}

    /** @return int[]{horizontal, vertical} where each is NONE / LEFT|RIGHT / UP|DOWN. */
    public static int[] directions(float dx, float dy, float thresholdX, float thresholdY, boolean invertY) {
        int horizontal = NONE;
        if (dx > thresholdX) horizontal = RIGHT;
        else if (dx < -thresholdX) horizontal = LEFT;

        int vertical = NONE;
        if (dy > thresholdY) vertical = invertY ? DOWN : UP;
        else if (dy < -thresholdY) vertical = invertY ? UP : DOWN;

        return new int[]{horizontal, vertical};
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app_pojavlauncher:testDebugUnitTest --tests net.kdt.pojavlaunch.customcontrols.CameraPanTest`
Expected: PASS (5 tests).

- [ ] **Step 6: Wire `panCamera` to use `CameraPan`**

Replace `panCamera` (`GLFWGLSurface.java:432-452`):

```java
    private void panCamera(float dx, float dy) throws InterruptedException {
        // Separate per-axis thresholds; vertical is slightly higher so it feels
        // matched to horizontal on tall screens (feedback #3). invertY = false keeps
        // the historical mapping (drag down -> look up).
        final float thresholdX = 8.0f;
        final float thresholdY = 10.0f;
        int[] dirs = CameraPan.directions(dx, dy, thresholdX, thresholdY, false);

        switch (dirs[0]) {
            case CameraPan.RIGHT: AWTInputBridge.sendKey((char)AWTInputEvent.VK_RIGHT, AWTInputEvent.VK_RIGHT); break;
            case CameraPan.LEFT:  AWTInputBridge.sendKey((char)AWTInputEvent.VK_LEFT, AWTInputEvent.VK_LEFT); break;
        }
        switch (dirs[1]) {
            case CameraPan.UP:   AWTInputBridge.sendKey((char)AWTInputEvent.VK_UP, AWTInputEvent.VK_UP); break;
            case CameraPan.DOWN: AWTInputBridge.sendKey((char)AWTInputEvent.VK_DOWN, AWTInputEvent.VK_DOWN); break;
        }
    }
```

Add `import net.kdt.pojavlaunch.customcontrols.CameraPan;` to `GLFWGLSurface.java` if not already covered by a wildcard import.

- [ ] **Step 7: Build the APK**

Run: `./gradlew :app_pojavlauncher:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Verify on-device**

Launch **Play HD**, enter the world, pan the camera with one finger both horizontally and vertically.
Expected: vertical and horizontal panning feel proportionally matched; behavior is unchanged in direction (drag down still looks up). Tune `thresholdY` up/down and re-verify if it still feels off.

- [ ] **Step 9: Commit**

```bash
git add app_pojavlauncher/build.gradle app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/customcontrols/CameraPan.java app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/customcontrols/CameraPanTest.java app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/GLFWGLSurface.java
git commit -m "feat: per-axis camera pan thresholds + invert-Y hook, unit-tested (feedback #3)"
```

---

## Task 8: Verify inventory tap-and-drag sorting (feedback #2)

**Rationale:** RuneScape inventory reorder is a genuine left-button hold + move + release; the reorder logic lives in the client (`rt4.jar`). Task 5 makes the launcher emit a real held-drag stream in menus. This task verifies that the same primitive drives inventory item dragging, extending it if the inventory area needs it. **Do Task 5 first.**

**Files:**
- Possibly modify: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/GLFWGLSurface.java` (only if inventory drag needs threshold/scope tuning beyond Task 5)

- [ ] **Step 1: Build the APK from the Task 5 state**

Run: `./gradlew :app_pojavlauncher:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Verify inventory drag on-device**

Launch **Play HD**, open the inventory, press an item and drag it to another slot, then release.
Expected: the item is picked up and moved/inserted, matching desktop behavior.

- [ ] **Step 3: If it does not work, tune and re-verify**

If the drag is swallowed by tap detection or camera pan, adjust the drag-start threshold from Task 5 (`FINGER_STILL_THRESHOLD`) and/or ensure the held-drag path is reached over the inventory interface (not only over scrollbars). Re-run Steps 1–2. Record the exact behavior observed.

- [ ] **Step 4: Commit (only if code changed)**

```bash
git add app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/GLFWGLSurface.java
git commit -m "fix: enable inventory item drag via held-drag primitive (feedback #2)"
```

---

## Out of scope for this implementation plan (tracked in the triage doc)

These open incidents are **not code-implementable in this repo** and are therefore not turned into TDD tasks here. See `docs/plans/2026-07-09-open-issues-fix-plan.md` (Tasks 7, 8, 9, 12) for the full analysis and hand-off actions:

- **#33 / #27 / #30 (rendering glitch):** the gl4es GL-translation source is not in this repo (shipped as the prebuilt `libgl4es_114.so` from an external fork); the world-map/glDrawPixels fix must land in that fork or in `rt4.jar`. Launcher-side mitigations only: surface the silent renderer fallback (`JREUtils.loadGraphicsLibrary`), recommend the ANGLE renderer for Samsung, and experiment with `LIBGL_*` color flags.
- **#29 (action sounds missing):** client-side — the `openalaudio` branch of `rt4.jar`. Launcher already bundles/loads `libopenal.so`.
- **#12 / feedback #4 (remove mouse mode + camera toggle, keyboard-only):** a control-scheme **design decision** for the maintainer, gated on Tasks 4–7 proving touch is well-tuned. If adopted, it overrides Task 3.
- **#7 (TODO 2.0):** roadmap — split into its own tasks (audio, Pojav/Minecraft renaming pass, keyboard C/K mistyping investigation, HD load progress bar; the HD crash is already root-caused to the XP-drop plugin, now disabled).
- **#19 (Suggestions):** screenshot-only; needs human triage before any task can be written.

---

## Suggested execution order

Independent, then dependent chains:
1. **Task 1** (build docs) — pure docs/config.
2. **Task 2** (SD volume) — isolated 1-method fix.
3. **Task 3** (PRI/SEC visibility) — isolated control-layout fix.
4. **Task 6** (stylus click) — isolated `onTouchEvent` branch.
5. **Task 4** (gesture cleanup) — foundation for Task 5; adds `mLongPressFired`.
6. **Task 5** (held-drag scroll) — builds on Task 4's state.
7. **Task 8** (inventory drag) — verifies/extends Task 5.
8. **Task 7** (camera calibration) — after Task 4's pan path is deduplicated.

**Honesty gate:** when reporting progress, Tasks 1–8 are fully in-repo. Do **not** claim #33/#27/#30-glitch or #29-action-sounds are fixed from any launcher-only change — those require external gl4es/`rt4.jar` work.

---

## Self-Review

**Spec coverage:** #32 → T1; #29-volume → T2; #23 → T3; #22 → T4; #25/#30-scroll → T5; #31 → T6; feedback-camera → T7; feedback-inventory → T8; feedback-scroll → T5. Rendering/#33/#27/#30-glitch, #29-sounds, #7, #19, #12/feedback-control-scheme → out-of-scope appendix (justified). No implementable requirement left unassigned.

**Placeholder scan:** every code step contains complete code; no "TBD"/"add error handling"/"similar to Task N". Tuning constants (`FINGER_STILL_THRESHOLD`, `thresholdY`) are given concrete starting values with an explicit "expect one tuning pass" note — not placeholders.

**Type consistency:** `mLongPressFired` (defined T4) consumed by T5 usage guard; `mMenuDragging` defined and used within T5; `ControlLayout.setMouseButtonsVisible(boolean)` defined T3-Step1 and called T3-Steps 2–3; `CameraPan.directions(float,float,float,float,boolean)→int[]` and constants `NONE/LEFT/RIGHT/UP/DOWN` defined T7-Step4, matched by test (T7-Step2) and caller (T7-Step6). Consistent.
