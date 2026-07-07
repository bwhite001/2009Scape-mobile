# Tier 1 Input — Polish Follow-up (Design Spec)

**Date:** 2026-07-07
**Repo:** `bwhite001/2009Scape-mobile` (branch base: `master` @ `49c77efde`, the merged Tier 1 input features)
**Status:** Approved in brainstorming; ready for implementation plan.

## Purpose

Three small, isolated cleanups to the merged Tier 1 input features. Two are review-flagged Minor nits; one removes a redundant gesture the maintainer chose to tidy. No new features, no new files, no interface changes.

## Changes

### 1. Remove dead field (`MainActivity.java`)
The field `private TapIndicatorView tapIndicatorView;` (line 71) and its binding `tapIndicatorView = findViewById(R.id.main_tap_indicator);` (line 213) are never read — the overlay is reached via the static `TapIndicatorView.showTap` / `sInstance`. Delete both.

The XML view `@+id/main_tap_indicator` in `activity_basemain.xml` **stays** — the `TapIndicatorView` constructor sets `sInstance`, so the view must still be inflated.

### 2. Don't flash the tap ring in the controls editor (`GLFWGLSurface.java`)
Currently `onTouchEvent` calls `TapIndicatorView.showTap(...)` on `ACTION_DOWN` (line 248) *before* the controls-editor guard `if(((ControlLayout)getParent()).getModifiable()) return false;` (line 251), so dragging buttons in the on-screen controls editor flashes rings.

Reorder so the editor guard runs first:
```java
if (((ControlLayout)getParent()).getModifiable()) return false;
if (e.getActionMasked() == MotionEvent.ACTION_DOWN)
    TapIndicatorView.showTap(e.getX(), e.getY());
```
Normal gameplay is unaffected (gameplay taps do not hit `getModifiable()`); only editor-mode taps stop drawing a ring.

### 3. Suppress redundant long-press right-click in single-tap mode (`GLFWGLSurface.java`)
When `PREF_SINGLE_TAP_RIGHTCLICK` is on, a normal tap already opens the right-click "Choose Option" menu, so the long-press right-click is a redundant duplicate. Guard `onLongPress` to return early — making long-press **fully inert** in that mode (no right-click **and** no haptic; a buzz with no resulting action would be confusing):
```java
public void onLongPress(MotionEvent e) {
    super.onLongPress(e);
    if (LauncherPreferences.PREF_SINGLE_TAP_RIGHTCLICK) return;
    Haptics.vibrate(getContext(), Haptics.LONG_PRESS_MS);
    CallbackBridge.putMouseEventWithCoords(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, CallbackBridge.mouseX, CallbackBridge.mouseY);
}
```
Default mode (pref off) is **completely unchanged**: long-press still buzzes and right-clicks.

## Out of scope
- Long-press keeping a partial action (haptic-only) in single-tap mode — decided against (fully inert).
- Task 4 device verification (physical keyboard/mouse) — the maintainer's on-device checklist, not code.
- Red/"interacting" tap-ring color — Bucket B (needs client-side hit data).

## Verification
No unit-test harness (per repo). Compile-gate via the dev-server Docker build (`sync-and-build.sh`, warm), then merge to `master` + CI. Changes are dead-code removal and control-flow reorders/guards; runtime confirmation (no ring in editor, long-press inert in single-tap mode, default mode unchanged) folds into the on-device pass.

## Files touched
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java` (change 1)
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/GLFWGLSurface.java` (changes 2, 3)
