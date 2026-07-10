# Plan 022: Release the held map-drag button on the 2-to-1 finger transition (SD/AWT path)

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 8ee361ea1..HEAD -- app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/JavaGUILauncherActivity.java`
> If the file changed since this plan was written, compare the "Current
> state" excerpts below against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `8ee361ea1`, 2026-07-10

## Why this matters

In the SD (software/AWT) launch path, a two-finger drag simulates a real
held left-mouse-button drag so the RS world map (which only scrolls while a
button is held) can be panned. When the user drops from two fingers to one
mid-drag, Android delivers `ACTION_POINTER_UP` **while `getPointerCount()`
still reports the departing pointer** (it is only removed after the event is
dispatched). Because of this, the event is caught by the "two or more
fingers" branch of the touch handler, which has no case for
`ACTION_POINTER_UP` and simply returns without releasing the button. The very
next event is the remaining finger's `ACTION_MOVE` with `pointerCount() == 1`,
which falls into the single-finger camera-pan path — while the AWT
`BUTTON1_DOWN_MASK` press is still held from the two-finger drag. The result
is an unintended map-drag/mis-click bound to whatever the cursor passes over
while the user is now just trying to pan the camera with one finger. It
self-resolves on the final full lift, so it's a moderate rather than
permanently-stuck bug, but it corrupts every 2→1 transition during map
panning. This is the same bug class already fixed for the HD path in
`GLFWGLSurface.java` (commit `14f6f6baa`, "release held drag button when a
second finger starts scrolling") — this plan ports the same idea to the SD
path's actual failure point.

## Current state

`app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/JavaGUILauncherActivity.java`
— the field (`:62`):
```java
private boolean mMapDragging = false;
```

The `mTextureView` touch listener (`:169-235`). The two-or-more-finger branch
(`:178-194`):
```java
            if (event.getPointerCount() >= 2) {
                float cx = (event.getX(0) + event.getX(1)) / 2f;
                float cy = (event.getY(0) + event.getY(1)) / 2f;
                switch (action) {
                    case MotionEvent.ACTION_POINTER_DOWN: // second finger down: start drag
                        sendScaledMousePosition(cx + mTextureView.getX(), cy);
                        AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON1_DOWN_MASK, true);
                        mMapDragging = true;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (mMapDragging) sendScaledMousePosition(cx + mTextureView.getX(), cy);
                        break;
                }
                prevX = event.getX(0);
                prevY = event.getY(0);
                return true;
            }
```
Then the existing release check (`:196-204`):
```java
            // A finger lifted, ending a two-finger drag: release the held button.
            if (mMapDragging && (action == MotionEvent.ACTION_POINTER_UP
                    || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
                AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON1_DOWN_MASK, false);
                mMapDragging = false;
                prevX = event.getX();
                prevY = event.getY();
                return true;
            }
```

**This is NOT dead code you can assume already fixes the bug — trace it
carefully.** Per the Android `MotionEvent` contract, during
`ACTION_POINTER_UP`, `getPointerCount()` still counts the departing pointer
(it is removed only after dispatch; `getActionIndex()` tells you which index
is leaving). So while exactly 2 fingers are down and one lifts
(`ACTION_POINTER_UP`), `event.getPointerCount() == 2` is still true — the
`if (event.getPointerCount() >= 2)` branch above catches it FIRST, its
`switch(action)` has no `ACTION_POINTER_UP` case, and it unconditionally
`return true`s at line 193 — **before the `:196-204` release check is ever
reached**. That check only fires for the true final `ACTION_UP` (last finger,
`pointerCount() == 1`, which never entered the `>=2` branch at all) or for
an `ACTION_CANCEL` delivered while `pointerCount() < 2`. It does not fire for
the 2→1 `ACTION_POINTER_UP` itself — confirming the bug as described above.

The single-finger camera-pan path this bug leaks into (`:217-229`):
```java
            switch (action) {
                case MotionEvent.ACTION_UP: // 1
                case MotionEvent.ACTION_CANCEL: // 3
                case MotionEvent.ACTION_POINTER_UP: // 6
                    break;
                case MotionEvent.ACTION_MOVE: // 2
                    sendScaledMousePosition(x + mTextureView.getX(), y);
                    try {
                        panCamera(prevX-x, prevY-y);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    break;
            }
```

**Reference fix (template)** — the analogous bug on the HD path, already
fixed in commit `14f6f6baa` (`app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/GLFWGLSurface.java`):
```java
                    // A second finger arrived mid-drag: end the one-finger held drag
                    // before handling two-finger scroll, so scroll never runs with the
                    // left button still held.
                    if(mMenuDragging && pointerCount != 1){
                        sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, false);
                        mMenuDragging = false;
                    }
```
That fix handles the 1→2 transition; this plan needs the mirror-image 2→1
transition, but the pattern (release the held button at the exact point the
finger-count changes, before the other path can run) is the same.

`AWTInputBridge.sendMousePress` (`AWTInputBridge.java:27,31-33`) has both a
`(int awtButtons)` (press-then-release pulse) and a `(int awtButtons, boolean
isDown)` overload used for real held-drag press/release — this plan uses the
latter, matching the existing calls at `:184` and `:199`.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Build APK (Docker, no host JDK/SDK) | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` | `BUILD SUCCESSFUL`, APK at `app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk` |

## Scope

**In scope** (the only file you should modify):
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/JavaGUILauncherActivity.java` — the `mTextureView` touch listener only (`:169-235`)

**Out of scope** (do NOT touch, even though they look related):
- `GLFWGLSurface.java` — the HD path already has its own fix (`14f6f6baa`); do not duplicate/alter it.
- `Touchpad`'s two-finger right-click logic (`:100-167`, the `mTouchpadView` listener) — unrelated gesture (right-click simulate), not the map-drag path.
- The pre-existing `ACTION_CANCEL`-while-`pointerCount()>=2` gap noted above — same class of bug but not what this finding (F14) targets; see Maintenance notes.

## Git workflow

- Branch: `advisor/022-sd-mapdrag-two-to-one-finger`
- One commit, conventional style (see `git log`), e.g.:
  `fix: release held map-drag button on 2-to-1 finger transition (SD)`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Handle `ACTION_POINTER_UP` inside the two-or-more-finger branch

In `JavaGUILauncherActivity.java`, add a case to the `switch (action)` inside
the `if (event.getPointerCount() >= 2)` block (`:181-190`) so the release
happens at the exact point the finger count is about to drop below 2 — before
this branch returns and the remaining single-finger `ACTION_MOVE` can reach
the camera-pan path:

```java
                switch (action) {
                    case MotionEvent.ACTION_POINTER_DOWN: // second finger down: start drag
                        sendScaledMousePosition(cx + mTextureView.getX(), cy);
                        AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON1_DOWN_MASK, true);
                        mMapDragging = true;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (mMapDragging) sendScaledMousePosition(cx + mTextureView.getX(), cy);
                        break;
                    case MotionEvent.ACTION_POINTER_UP:
                        // A finger is about to leave. getPointerCount() still counts it
                        // here (Android dispatches POINTER_UP before removing the pointer),
                        // so this event would otherwise be swallowed by this branch with the
                        // button still held. If we're dropping to a single finger, end the
                        // drag now — before the remaining finger's ACTION_MOVE reaches the
                        // single-finger camera-pan path below.
                        if (mMapDragging && event.getPointerCount() - 1 < 2) {
                            AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON1_DOWN_MASK, false);
                            mMapDragging = false;
                        }
                        break;
                }
```

Do not remove the existing `:196-204` release check — keep it as the
belt-and-suspenders release for the true final lift (`ACTION_UP`) and for any
`ACTION_CANCEL` delivered once `pointerCount() < 2`.

**Verify**: `grep -n "ACTION_POINTER_UP" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/JavaGUILauncherActivity.java` → shows the new case inside the `pointerCount >= 2` switch (in addition to the pre-existing occurrence in the `:196-204` check and the `:220` single-finger switch).

### Step 2: Build

**Verify**: `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` → `BUILD SUCCESSFUL`.

## Test plan

No unit test — this is raw `MotionEvent`/AWT plumbing with no pure-logic seam
to extract cheaply (unlike `CameraPan`/`CameraPanTest.java`, which is the
pattern to reach for if a future refactor pulls the drag-state machine into
its own class). Verification is device smoke only:

- SD mode (Play SD): put two fingers down on the world map area to start a
  drag, move to confirm the map pans, then lift **one** finger while
  continuing to move the remaining finger.
  - Expected: the camera pans cleanly under the single remaining finger, with
    no stray click/drag artifact at the point the second finger lifted.
  - Before the fix: a held-button drag/mis-click occurs at the lift point and
    continues while single-finger panning.
- Repeat lifting the *other* finger first (test both orderings).
- Add this scenario as a new checklist item under "Launch"/"Play SD" in
  `docs/verification/device-smoke-checklist.md` (a short bullet, matching the
  existing list style), e.g.: `- [ ] SD: two-finger map-drag, lift one finger
  mid-drag — camera pans cleanly, no stuck click.`

## Done criteria

- [ ] `docker run ... ./gradlew :app_pojavlauncher:assembleDebug` → `BUILD SUCCESSFUL`
- [ ] The new `ACTION_POINTER_UP` case exists inside the `pointerCount >= 2` switch in `JavaGUILauncherActivity.java`, gated on `mMapDragging` and the pointer count dropping below 2
- [ ] `docs/verification/device-smoke-checklist.md` has the new SD 2-to-1 finger checklist item
- [ ] Device smoke: two-finger drag → lift one finger → camera pans cleanly, no stuck button (both lift orderings)
- [ ] `git status` shows only `JavaGUILauncherActivity.java` and the checklist doc modified
- [ ] `plans/README.md` row updated

## STOP conditions

- The code at `JavaGUILauncherActivity.java:169-235` doesn't match the excerpts in "Current state" (drift since this plan was written) — re-read the live file and reconcile before proceeding.
- `mMapDragging` or the touch-listener structure has been refactored into a different class/pattern since — STOP and report rather than force-fitting this diff.
- The build fails for a reason unrelated to this change after a reasonable fix attempt.
- Device smoke shows the fix doesn't fully resolve the mis-click (e.g. a 3-finger-to-1 drop, which this plan intentionally does not treat as ending the drag) — report the exact repro, do not silently broaden the fix beyond Step 1's `- 1 < 2` condition without checking in.

## Maintenance notes

- A `pointerCount()>=2` `ACTION_CANCEL` (cancel delivered while 2+ fingers are
  still down) has the same latent gap — it is swallowed by the same branch
  with no release. Not observed as a real-world trigger and out of scope for
  this plan (F14 is specifically the 2→1 transition); flag it if a future
  session reports a stuck button that this fix didn't resolve.
- If this drag-state logic is ever extracted into a shared class (mirroring
  `CameraPan.java`), fold both the HD (`GLFWGLSurface`) and SD
  (`JavaGUILauncherActivity`) finger-count transition handling into one
  tested implementation instead of two hand-maintained copies.
