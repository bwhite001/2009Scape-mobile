# Plan 023: Release the stylus button when it lifts as a secondary pointer

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 8ee361ea1..HEAD -- app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/GLFWGLSurface.java`
> If the file changed since this plan was written, compare the "Current
> state" excerpts below against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW (single-stylus-only use is unaffected; this only matters when a finger and the stylus are down together)
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `8ee361ea1`, 2026-07-10

## Why this matters

`GLFWGLSurface.onTouchEvent` maps a stylus tip down/up to a mouse click
(barrel button held = right-click) because a stylus tip touching the screen
has no `ACTION_BUTTON_*` events of its own (issue #31, commits `b5adfbe6d`,
`8e1458535`). The press/release is keyed off `ACTION_DOWN`/`ACTION_UP`/
`ACTION_CANCEL` on the loop index that matches `TOOL_TYPE_STYLUS`. This
works for stylus-only interaction, but if a finger is already down (or goes
down) alongside the stylus, the stylus's own lift arrives as
`ACTION_POINTER_UP` (a secondary pointer lifting, not the terminal
`ACTION_UP` for the whole gesture) — which this code does not handle. The
mouse/GLFW button the stylus pressed (possibly `GLFW_MOUSE_BUTTON_RIGHT` via
the barrel button) is never released, producing a stuck right-click/context
state until an unrelated future full `ACTION_DOWN`→`ACTION_UP` cycle happens
to clear it. This is rare (requires stylus+finger simultaneously) but is a
real correctness gap in code introduced this session; the fix is a small,
scoped addition to the same stylus block.

## Current state

`app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/GLFWGLSurface.java` —
the field (`:128-130`):
```java
    /* GLFW button chosen at stylus-down, reused at stylus-up so barrel-button
       right-clicks release the same button they pressed (issue #31). */
    private int mStylusButton = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT;
```

The per-pointer loop in `onTouchEvent` (`:269-296`):
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
                int action = e.getActionMasked();
                if(action == MotionEvent.ACTION_DOWN){
                    boolean barrel = (e.getButtonState()
                            & (MotionEvent.BUTTON_STYLUS_PRIMARY | MotionEvent.BUTTON_SECONDARY)) != 0;
                    mStylusButton = barrel
                            ? LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT
                            : LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT;
                    sendMouseButton(mStylusButton, true);
                } else if(action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL){
                    sendMouseButton(mStylusButton, false);
                }
            }
            return true; // pointer event handled
        }
```
Confirmed via `git blame`: this block is entirely from this session
(`b5adfbe6d`, `8e1458535`), so there is no upstream history to reconcile —
this plan's diff is additive to fresh code.

`ACTION_POINTER_DOWN`/`ACTION_POINTER_UP` (a secondary pointer joining/leaving
while at least one other pointer stays down) are not handled anywhere in this
block — only the terminal `ACTION_DOWN`/`ACTION_UP`/`ACTION_CANCEL` for the
whole gesture are. Per the Android `MotionEvent` contract, `getActionIndex()`
gives the index of the pointer that is the *subject* of an
`ACTION_POINTER_DOWN`/`ACTION_POINTER_UP` event — this is the key you need to
tell "is it specifically the stylus pointer (index `i`) that is lifting"
apart from "some other pointer (e.g. a finger) is lifting while the stylus
stays down" (which must NOT release the stylus button).

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Build APK (Docker, no host JDK/SDK) | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` | `BUILD SUCCESSFUL`, APK at `app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk` |

## Scope

**In scope** (the only file you should modify):
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/GLFWGLSurface.java` — the stylus block only (`:282-294`)

**Out of scope** (do NOT touch, even though they look related):
- The `mMenuDragging` one-finger-drag logic (`:340-362`) and the
  `ACTION_UP`/`ACTION_CANCEL` in-world cleanup (`:445-485`, see plan 027) —
  unrelated code paths in the same file.
- The mouse (`TOOL_TYPE_MOUSE`) branch of the same `if` — mice don't have this
  problem (they report real `ACTION_BUTTON_PRESS`/`RELEASE`, not a synthetic
  tip-down/up); do not change behavior for `TOOL_TYPE_MOUSE`.

## Git workflow

- Branch: `advisor/023-stylus-pointer-up-release`
- One commit, conventional style, e.g.:
  `fix: release stylus button when it lifts as a secondary pointer (#31)`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Release on `ACTION_POINTER_UP` when the departing pointer is the stylus

Extend the stylus release condition (`:291`) to also cover
`ACTION_POINTER_UP`, guarded by `e.getActionIndex() == i` so it only fires
when pointer `i` (the one this loop iteration matched as the stylus) is the
one actually lifting — not some other finger lifting while the stylus stays
down:

```java
            if(toolType == MotionEvent.TOOL_TYPE_STYLUS){
                int action = e.getActionMasked();
                if(action == MotionEvent.ACTION_DOWN){
                    boolean barrel = (e.getButtonState()
                            & (MotionEvent.BUTTON_STYLUS_PRIMARY | MotionEvent.BUTTON_SECONDARY)) != 0;
                    mStylusButton = barrel
                            ? LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT
                            : LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT;
                    sendMouseButton(mStylusButton, true);
                } else if(action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                        || (action == MotionEvent.ACTION_POINTER_UP && e.getActionIndex() == i)){
                    sendMouseButton(mStylusButton, false);
                }
            }
```

**Verify**: `grep -n "ACTION_POINTER_UP" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/GLFWGLSurface.java` → the stylus block's release condition now includes `ACTION_POINTER_UP && e.getActionIndex() == i`.

### Step 2: Build

**Verify**: `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` → `BUILD SUCCESSFUL`.

## Test plan

No clean unit test — this depends on multi-pointer `MotionEvent` state
(`getToolType`, `getActionIndex`, `getButtonState`) that isn't practical to
construct outside instrumented/on-device testing, unlike the pure-logic
`CameraPan`/`CameraPanTest.java` pattern. Verification is device smoke, and it
requires an actual stylus (e.g. S-Pen) device:

- Press a finger down first, then tap with the stylus using the barrel
  button (triggers right-click), then lift the **stylus** first while the
  finger stays down.
  - Expected: no stuck right-click/context-menu state after the stylus lifts.
  - Before the fix: the right mouse button state remains "held" from the
    engine's perspective until an unrelated full down/up cycle clears it.
- If no S-Pen/stylus-capable device is available to the executor, mark this
  device-verify step **PENDING** in the PR/report — do not claim it was
  verified. The build-green gate still applies regardless.
- Add a bullet to `docs/verification/device-smoke-checklist.md` (new
  "Stylus (if device available)" subsection or appended to an existing input
  section), e.g.: `- [ ] (S-Pen only) Finger down, stylus barrel-tap, lift
  stylus first — no stuck right-click.`

## Done criteria

- [ ] `docker run ... ./gradlew :app_pojavlauncher:assembleDebug` → `BUILD SUCCESSFUL`
- [ ] The stylus release condition in `GLFWGLSurface.java` includes `ACTION_POINTER_UP && e.getActionIndex() == i`
- [ ] `docs/verification/device-smoke-checklist.md` has the new stylus checklist item
- [ ] Device smoke on an S-Pen/stylus device passes, OR is explicitly marked PENDING with a one-line reason (no stylus device available)
- [ ] `git status` shows only `GLFWGLSurface.java` and the checklist doc modified
- [ ] `plans/README.md` row updated

## STOP conditions

- The stylus block at `GLFWGLSurface.java:282-294` doesn't match the excerpt in "Current state" (drift since this plan was written) — re-read the live file and reconcile before proceeding.
- The stylus block has been refactored to track pointer IDs differently (e.g. a dedicated `mStylusPointerId` field) since this plan was written — adapt the fix to the new shape rather than reintroducing the index-based check, and note the deviation in your report.
- The build fails for a reason unrelated to this change after a reasonable fix attempt.

## Maintenance notes

- If a future change adds multi-stylus support (unlikely on Android, but
  possible with a stylus + a second styled input), the single `mStylusButton`
  field would need to become per-pointer-id state; this plan intentionally
  keeps the existing single-field model since only one stylus is realistically
  ever active.
- Reviewer: confirm `e.getActionIndex()` is read on the same `e` used for
  `toolType`/`i` in this loop (it is a property of the whole event, not
  per-pointer, so this is just checking "is pointer index `i` the subject of
  this specific action").
