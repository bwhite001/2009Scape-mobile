# Plan 027: INVESTIGATE — does an in-world finger lift arrive as ACTION_UP or ACTION_CANCEL?

> **Executor instructions**: This is an INVESTIGATION plan, not a blind fix.
> Follow the steps in order. Step 1-2 are empirical (on-device) verification;
> **do not skip to the conditional fix in Step 3 without first confirming the
> premise on a real device.** If anything in "STOP conditions" occurs, stop
> and report — a wrong change here could break currently-working in-world
> input (mining/combat/dropping items all work today).
>
> **Drift check (run first)**: `git diff --stat 8ee361ea1..HEAD -- app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/GLFWGLSurface.java`
> If the file changed since this plan was written, compare the "Current
> state" excerpts below against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P3
- **Effort**: S
- **Risk**: LOW (the game is reportedly fully playable today, so this is most likely latent, not active — but the investigation must confirm that, and any fix must not regress working input)
- **Depends on**: none
- **Category**: bug (unconfirmed — this plan's job is to confirm or refute it)
- **Planned at**: commit `8ee361ea1`, 2026-07-10

## Why this matters

`GLFWGLSurface.onTouchEvent`'s big `switch (e.getActionMasked())` treats
`ACTION_UP` and `ACTION_CANCEL` very differently for in-world (grabbed) play.
`ACTION_UP` only resets drag bookkeeping; **all** of the in-world cleanup —
stopping the item-drop key repeat, releasing a hold-to-mine/attack left
mouse button, cancelling the pending long-press-right-click check, and firing
the quick-tap right-click — lives only under `ACTION_CANCEL`. If a real
finger lift during grabbed play is delivered as `ACTION_UP` (not
`ACTION_CANCEL`), none of that cleanup runs: a fired hold-to-mine left button
would stay held after lift (stuck mine/attack), a pending delayed check could
fire after the finger is already gone, item-drop repeat would not stop, and
the short-tap-right-click gesture would never fire in-world. The game is
reportedly fully playable today (mining/combat work), which is strong
circumstantial evidence that in-world lifts *do* arrive as `ACTION_CANCEL` in
practice (likely because of `requestPointerCapture()`/grab-state handling —
see "Current state" below) — but this has not been empirically confirmed.
This plan's job is to get that confirmation before anyone touches the code,
because merging the two cases on a wrong premise could either (a) be a no-op
if the premise is wrong, wasting the change, or (b) — worse — subtly change
working behavior if the two cases are merged without accounting for a real
semantic difference between them that this plan didn't anticipate.

## Current state

`app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/GLFWGLSurface.java`,
`onTouchEvent`'s switch, the two relevant cases (`:445-485`):
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
            case MotionEvent.ACTION_CANCEL: // 3
                if(mMenuDragging){
                    sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, false);
                    mMenuDragging = false;
                }
                mShouldBeDown = false;
                mCurrentPointerID = -1;

                hudKeyHandled = handleGuiBar((int)e.getX(), (int) e.getY());
                isTouchInHotbar = hudKeyHandled != -1;
                // We only treat in world events
                if (!CallbackBridge.isGrabbing()) break;

                // Stop the dropping of items
                sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_Q, 0, false);
                mHandler.removeMessages(MSG_DROP_ITEM_BUTTON_CHECK);

                // Remove the mouse left button
                if(triggeredLeftMouseButton){
                    sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, false);
                    triggeredLeftMouseButton = false;
                    break;
                }
                mHandler.removeMessages(MSG_LEFT_MOUSE_BUTTON_CHECK);

                // In case of a short click, just send a quick right click
                if(!LauncherPreferences.PREF_DISABLE_GESTURES &&
                        MathUtils.dist(mInitialX, mInitialY, CallbackBridge.mouseX, CallbackBridge.mouseY) < FINGER_STILL_THRESHOLD){
                    sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, true);
                    sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, false);
                }
                break;
```

**This split is pre-existing, not introduced this session.** `git blame`
shows `case MotionEvent.ACTION_UP:` / `case MotionEvent.ACTION_CANCEL:`
themselves (and the `mShouldBeDown`/`mCurrentPointerID`/drop-item/left-button/
short-click body) trace back to `4f53399d09`, `3dd0e0331b`, `da99ef47ea`,
`98a5d0b6fd` (SerpentSpirale, 2021-2022, upstream PojavLauncher history for
the equivalent `MinecraftGLView`/`MinecraftGLSurface(View)` classes). This
session's `mMenuDragging` commits (`97882d8104`, part of issues #25/#30) only
**added** the `if(mMenuDragging){ ...; mMenuDragging = false; }` block to
*both* cases identically — they did not create or alter the underlying
`ACTION_UP` vs `ACTION_CANCEL` asymmetry. So this is old, load-bearing
behavior, not a regression from recent work.

**Where grab state comes from** — `GLFWGLSurface` implements `GrabListener`
(`GrabListener.java:3-4`, `void onGrabState(boolean isGrabbing)`), and
`onGrabState`/`updateGrabState` (`GLFWGLSurface.java:745-766`):
```java
    @Override
    public void onGrabState(boolean isGrabbing) {
        post(()->updateGrabState(isGrabbing));
    }

    private void updateGrabState(boolean isGrabbing) {
        if(!MainActivity.isAndroid8OrHigher()) return;

        boolean hasPointerCapture = hasPointerCapture();
        if(isGrabbing){
            if(!hasPointerCapture) {
                requestFocus();
                requestPointerCapture();
            }
            return;
        }

        if(hasPointerCapture) {
            releasePointerCapture();
            clearFocus();
        }
    }
```
Note: `requestPointerCapture()`/`hasPointerCapture()` govern **mouse**
pointer capture (unbounded relative mouse movement for FPS-style look), not
touch dispatch — Android does not document pointer capture as changing
whether a finger's *touch* gesture is delivered as `ACTION_UP` vs
`ACTION_CANCEL`. So this mechanism is very unlikely to be the actual reason
in-world lifts might arrive as `ACTION_CANCEL`. More likely candidates for a
touch gesture being cancelled rather than completing normally: a parent
`ViewGroup` in the layout (`ControlLayout` — see
`app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/customcontrols/ControlLayout.java`)
intercepting the gesture (calling `requestDisallowInterceptTouchEvent` the
wrong way, or its own `onInterceptTouchEvent` stealing it mid-gesture), or the
system dispatching `ACTION_CANCEL` when a window loses focus/the gesture is
otherwise interrupted. **This plan does not have a confirmed causal
mechanism — that is exactly what Step 1 must establish empirically**, since
reasoning about Android's dispatch rules only gets you plausibility, not
proof.

`onTouchEvent`'s very first lines (`:256-262`) are also relevant context —
note the early `return false` when the controls editor is active:
```java
    public boolean onTouchEvent(MotionEvent e) {
        scaleGestureDetector.onTouchEvent(e);
        longPressDetector.onTouchEvent(e);
        // Kinda need to send this back to the layout
        if(((ControlLayout)getParent()).getModifiable()) return false;
```
This confirms `ControlLayout` (the parent `ViewGroup`) is directly in the
touch-dispatch chain, but a prior read of `ControlLayout` found **no**
`onInterceptTouchEvent`/`dispatchTouchEvent` override that would intercept or
cancel the game surface's own touch stream — `ControlLayout.onTouch()`
(`ControlLayout.java:325`) only handles per-button `MotionEvent`s already
routed by `ControlButton`, a separate stream from `GLFWGLSurface`'s own
`onTouchEvent`. Likewise, `setSystemUiVisibility`/immersive-mode flags exist
only in `Tools.setFullscreen()` (`Tools.java:377-392`) and
`JavaGUILauncherActivity.java:314-316`, called from `BaseActivity` on
create/resume — not tied to grab state, and not a documented source of
`ACTION_CANCEL` substitution for touch either. **In short: nothing in this
codebase's Java/Kotlin layer explicitly synthesizes `ACTION_CANCEL` for a
normal lift, and no clear causal mechanism was found by reading the code** —
this is exactly why Step 1's on-device observation is required rather than
reasoning it out from source. One soft signal in the existing code: the
`ACTION_CANCEL` case contains real, non-defensive gameplay logic (the
short-tap-to-right-click synthesis), which only makes sense if the original
author observed `ACTION_CANCEL` firing during ordinary play — but that is
circumstantial, not proof.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Build APK (Docker, no host JDK/SDK) | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` | `BUILD SUCCESSFUL`, APK at `app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk` |
| Install on device | `adb install -r app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk` | installs |
| Watch logs | `adb logcat -s Pojav2009Investigate` (tag suggested in Step 1) | shows the temporary log lines as you lift fingers |

## Scope

**In scope**: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/GLFWGLSurface.java` — investigation instrumentation (temporary, reverted) in Step 1, and the conditional fix in Step 3 (only if confirmed necessary).

**Out of scope**: `ControlLayout.java` and any other view in the dispatch chain — read-only for this investigation (Step 1 may need to *look at* `ControlLayout.onInterceptTouchEvent` if logging is inconclusive, but do not modify it); `JavaGUILauncherActivity.java` (SD path uses its own listener, unaffected by this HD-path question); plans 022/023 (separate findings, same file, different code regions — do not combine changes).

## Git workflow

- Branch: `advisor/027-investigate-actionup-cancel`
- Investigation commit (if any instrumentation is kept temporarily): `debug: temporary logging to confirm ACTION_UP vs ACTION_CANCEL on in-world lift` — **this commit must be reverted/removed before the branch is considered done**, so the final diff contains either nothing (latent, no fix) or only the Step 3 fix.
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Instrument to observe the real action on a normal in-world finger lift

Add a single temporary `Log.d` at the very top of the `switch` in
`onTouchEvent` (just before `switch (e.getActionMasked())`, near `:409` where
the switch begins, or immediately inside each of the `ACTION_UP` and
`ACTION_CANCEL` cases) that records: the action, `CallbackBridge.isGrabbing()`,
and `mCurrentPointerID`. Example:

```java
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_UP: // 1
                Log.d("Pojav2009Investigate", "ACTION_UP isGrabbing=" + CallbackBridge.isGrabbing());
                ...
            case MotionEvent.ACTION_CANCEL: // 3
                Log.d("Pojav2009Investigate", "ACTION_CANCEL isGrabbing=" + CallbackBridge.isGrabbing());
                ...
```//
(`GLFWGLSurface.java` already imports `android.util.Log` at `:20` — reuse it.)

Build and install per the Commands table, then on a physical device: launch
**Play HD**, log in, enter the world so the client is in grabbed/in-world
mode (not a menu), and perform normal play actions with simple finger
lifts — walk (tap-and-lift on the ground), click an NPC/object, and a
sustained hold-to-mine/attack style press-and-lift. Capture `adb logcat -s
Pojav2009Investigate` output for each.

**Verify**: the logcat output for a normal in-world lift shows exactly one of
`ACTION_UP isGrabbing=true` or `ACTION_CANCEL isGrabbing=true` per lift (not
both, and not neither — if you see neither, the instrumentation is
misplaced; double check the log statements are unconditionally at the top of
each case body, not after an early `break`/return in another code path).

### Step 2: Record the finding and revert instrumentation

Write down which action code actually observed (this goes in your final
report, not into a repo file). Then **remove the temporary `Log.d` lines**
so they don't ship.

**Verify**: `git diff app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/GLFWGLSurface.java` → empty (instrumentation fully reverted) before proceeding to Step 3.

### Step 3 (CONDITIONAL — only if Step 1 confirmed ACTION_UP leaks a held button in-world): merge the cases

**Do not perform this step if Step 1 showed `ACTION_CANCEL` for in-world
lifts** — see STOP conditions; in that case the finding is latent and this
plan ends at Step 2 with "no change needed."

If, and only if, Step 1 confirmed that a normal in-world finger lift arrives
as `ACTION_UP` (meaning the cleanup under `ACTION_CANCEL` is skipped today),
merge the two cases so `ACTION_UP` falls through into the same cleanup,
keeping the `mMenuDragging` release (already present in both) exactly once:

```java
            case MotionEvent.ACTION_UP: // 1
            case MotionEvent.ACTION_CANCEL: // 3
                if(mMenuDragging){
                    sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, false);
                    mMenuDragging = false;
                }
                // End of drag, reset the start position
                startX = 0;
                startY = 0;

                mShouldBeDown = false;
                mCurrentPointerID = -1;

                hudKeyHandled = handleGuiBar((int)e.getX(), (int) e.getY());
                isTouchInHotbar = hudKeyHandled != -1;
                // We only treat in world events
                if (!CallbackBridge.isGrabbing()) break;

                // Stop the dropping of items
                sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_Q, 0, false);
                mHandler.removeMessages(MSG_DROP_ITEM_BUTTON_CHECK);

                // Remove the mouse left button
                if(triggeredLeftMouseButton){
                    sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, false);
                    triggeredLeftMouseButton = false;
                    break;
                }
                mHandler.removeMessages(MSG_LEFT_MOUSE_BUTTON_CHECK);

                // In case of a short click, just send a quick right click
                if(!LauncherPreferences.PREF_DISABLE_GESTURES &&
                        MathUtils.dist(mInitialX, mInitialY, CallbackBridge.mouseX, CallbackBridge.mouseY) < FINGER_STILL_THRESHOLD){
                    sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, true);
                    sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, false);
                }
                break;
```

Note this makes the non-grabbed (`!isGrabbing()`) menu case run
`handleGuiBar`/`isTouchInHotbar` on plain `ACTION_UP` too, where previously
only `ACTION_CANCEL` did — re-run the full device smoke checklist's
menu/hotbar items (not just in-world play) after this change, since the menu
path is now reachable from a code path (`ACTION_UP`) it wasn't before.

**Verify**: `docker run ... ./gradlew :app_pojavlauncher:assembleDebug` → `BUILD SUCCESSFUL`, then device-smoke both in-world play (Step 1's scenarios) and menu/hotbar interaction.

## Test plan

No unit test — this is a `MotionEvent` dispatch question, not pure logic.
The entire "test" is the Step 1 instrumented observation plus, if Step 3
runs, a full device-smoke pass:

- Mining/combat: hold to mine/attack, lift — confirm the button doesn't
  stay stuck (already reportedly fine; re-confirm it stays fine after any
  Step 3 change).
- Item drop: trigger a drop, lift immediately — confirm the drop-key repeat
  stops.
- Quick tap in-world (below `FINGER_STILL_THRESHOLD` movement) — confirm the
  short-tap right-click still fires exactly once.
- Menu/hotbar interactions (only relevant if Step 3 ran) — confirm hotbar
  taps and menu clicks still behave identically to before.

## Done criteria

- [ ] Step 1's logcat observation recorded in the final report: in-world finger lift produces **ACTION_UP** or **ACTION_CANCEL** (state which)
- [ ] `git diff -- app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/GLFWGLSurface.java` is empty if the finding was latent (Step 3 skipped), OR contains only the merged-case fix if Step 3 ran
- [ ] If Step 3 ran: `docker run ... ./gradlew :app_pojavlauncher:assembleDebug` → `BUILD SUCCESSFUL`
- [ ] If Step 3 ran: device smoke passes for in-world play AND menu/hotbar interaction (both, since the merge changes the menu path's reachable actions too)
- [ ] `plans/README.md` row updated with the outcome ("latent, no fix" or "fixed, see commit X")

## STOP conditions

- **Step 1 shows `ACTION_CANCEL` for a normal in-world lift** → STOP after Step 2. Report "latent, no fix" — do not proceed to Step 3, do not change `GLFWGLSurface.java` behavior. The premise for a fix is false; changing the code anyway risks the currently-working in-world input for no benefit.
- The code at `GLFWGLSurface.java:445-485` doesn't match "Current state" (drift) — re-read the live file and reconcile before proceeding.
- Step 1's logging is ambiguous or inconsistent across repeated trials (e.g. sometimes `ACTION_UP`, sometimes `ACTION_CANCEL` for what looks like the same gesture) — STOP and report the raw observations; do not average them into a guess. This would suggest device- or gesture-shape-dependent behavior that needs a human decision, not an automatic merge.
- If Step 3 runs and device smoke shows a regression in mining/combat, item-drop, or menu/hotbar behavior — revert the Step 3 change immediately and report the regression; do not attempt to patch around it within this plan.

## Maintenance notes

- If Step 1 confirms `ACTION_CANCEL` (the expected/likely outcome given the
  game is playable today), this finding should be marked REJECTED in
  `plans/README.md` with the one-line rationale "confirmed latent: in-world
  lifts arrive as ACTION_CANCEL, not ACTION_UP — no behavior change needed,"
  so nobody re-opens it without new evidence.
- If the mechanism *is* confirmed to be `ACTION_CANCEL`, it would still be
  worth a follow-up (out of scope here) to note **why** — e.g. if
  `ControlLayout`'s intercept logic is the cause, that's useful for anyone
  later refactoring the custom-controls layer to not accidentally flip it to
  `ACTION_UP`.
- Whoever reviews a Step 3 diff should scrutinize the non-grabbed
  (`!isGrabbing()`) branch specifically — merging the cases makes
  `handleGuiBar`/hotbar handling reachable from plain `ACTION_UP`, which is a
  behavior change beyond just the in-world cleanup this plan set out to fix.
