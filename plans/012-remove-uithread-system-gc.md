# Plan 012: Remove `System.gc()` calls from the UI thread in `MainActivity`

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 8ee361ea1..HEAD -- app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java`
> If this file changed since this plan was written, compare the "Current
> state" excerpts against the live code before proceeding; on a mismatch,
> treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: bug (perf/jank)
- **Planned at**: commit `8ee361ea1`, 2026-07-10

## Why this matters

`MainActivity.java` calls `System.gc()` three times, all on the Android main
(UI) thread: twice inside the mouse-speed dialog's button click handlers
(`adjustMouseSpeedLive`) and once inside `exitEditor` (the custom-controls
editor exit path). `System.gc()` is only ever a *request* to the runtime —
ART already manages GC scheduling well — but the request itself is not free:
triggering (or attempting to trigger) a full GC synchronously on the thread
that's also responsible for drawing frames and responding to touch input can
cause visible jank (dropped frames, input lag) for no compensating benefit.
All three calls are safe to delete outright; nothing downstream depends on a
GC having actually run at that point.

## Current state

`app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java` —
confirmed by direct read: there are **exactly three** occurrences of
`System.gc()` in the file, all on the UI thread (`grep -n "System.gc()"
app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java` →
lines 400, 404, 502).

`MainActivity.java:396-407` (`adjustMouseSpeedLive` — dialog positive/negative
button handlers; the `System.gc()` calls are lines 400 and 404):
```java
        b.setPositiveButton(android.R.string.ok, (dialogInterface, i) -> {
            LauncherPreferences.PREF_MOUSESPEED = ((float)tmpMouseSpeed)/100f;
            LauncherPreferences.DEFAULT_PREF.edit().putInt("mousespeed",tmpMouseSpeed).apply();
            dialogInterface.dismiss();
            System.gc();
        });
        b.setNegativeButton(android.R.string.cancel, (dialogInterface, i) -> {
            dialogInterface.dismiss();
            System.gc();
        });
        b.show();
```
Both handlers run on the UI thread (they are `AlertDialog` button-click
callbacks). The `System.gc()` call is the last statement in each and has no
return value or side effect consumed afterward — deleting it changes nothing
else in the method.

`MainActivity.java:497-512` (`exitEditor` — the `EditorExitable` interface
override; the `System.gc()` call is line 502):
```java
    @Override
    public void exitEditor() {
        try {
            MainActivity.mControlLayout.loadLayout((CustomControls)null);
            MainActivity.mControlLayout.setModifiable(false);
            System.gc();
            MainActivity.mControlLayout.loadLayout(LauncherPreferences.PREF_DEFAULTCTRL_PATH);
            mControlLayout.setMouseButtonsVisible(false);
            mDrawerPullButton.setVisibility(mControlLayout.hasMenuButton() ? View.GONE : View.VISIBLE);
        } catch (IOException e) {
            Tools.showError(this,e);
        }
        navDrawer.setAdapter(gameActionArrayAdapter);
        navDrawer.setOnItemClickListener(gameActionClickListener);
        isInEditor = false;
    }
```
`exitEditor` is called from UI-thread code (the custom-controls editor exit
flow via `ControlLayout.askToExit` → `EditorExitable.exitEditor()`). The
`System.gc()` call at line 502 sits between two `mControlLayout.loadLayout(...)`
calls — it does not gate or synchronize anything between them (no
try/finally, no callback wait); it's a bare fire-and-forget GC hint. Deleting
it does not change the sequencing of the two `loadLayout` calls around it.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Build APK | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` | `BUILD SUCCESSFUL`; APK at `app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk` |

(No host JDK/Android SDK exists in this environment — always run through the
`2009scape-apk-builder` Docker image, per repo `CLAUDE.md` / `MEMORY.md`.)

## Scope

**In scope** (the only file you should modify):
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java` — delete the 3 `System.gc()` statements at lines 400, 404, 502 only.

**Out of scope** (do NOT touch, even though they look related):
- Any `System.gc()` call in any other file (vestigial/inherited PojavLauncher
  trees, `multirt/`, `jni/`, etc. per repo `CLAUDE.md` conventions) — this
  plan is scoped to `MainActivity.java` only. If you find `System.gc()`
  elsewhere, do not remove it as part of this plan.
- The surrounding logic in `adjustMouseSpeedLive` and `exitEditor` — only the
  `System.gc();` statements themselves are deleted; no reordering, no other
  edits.

## Git workflow

- Branch: `advisor/012-remove-uithread-system-gc`
- One commit, conventional-commit style (e.g. matching
  `fix: keep PRI/SEC hidden after exiting the control editor (#23)`).
  Suggested message: `fix: remove UI-thread System.gc() calls in MainActivity`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Delete the two `System.gc()` calls in `adjustMouseSpeedLive`

In `MainActivity.java`, remove the `System.gc();` line from both the
`setPositiveButton` and `setNegativeButton` lambda bodies (currently lines
400 and 404), leaving the rest of each lambda (the `dialogInterface.dismiss();`
call and, in the positive case, the preference-writing lines) unchanged.

**Verify**: `grep -n "System.gc()" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java` → exactly one remaining match (the one in `exitEditor`, to be removed in Step 2).

### Step 2: Delete the `System.gc()` call in `exitEditor`

Remove the `System.gc();` line (currently line 502) from `exitEditor`,
leaving the two `mControlLayout.loadLayout(...)` calls that surround it
adjacent to each other, unchanged otherwise.

**Verify**: `grep -n "System.gc()" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java` → no matches.

### Step 3: Build

**Verify**: `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` → `BUILD SUCCESSFUL`.

## Test plan

This is a pure deletion of no-op-in-effect statements with no logic branch,
loop, or state depending on them — there is no meaningful unit test to write
(there is nothing to assert: the method's observable behavior before and
after is identical modulo GC timing, which is not something a JUnit test can
or should assert on). Verification is the Docker build (compiles) plus an
optional manual/device sanity check: open the in-game menu → "Adjust mouse
speed" dialog, tap OK and Cancel, and separately open/exit the custom
controls editor — confirm both flows still behave the same as before (dialog
closes, editor exits, controls layout reloads) with no crash. This is a
low-risk enough change that the device check is optional, not required, for
Done criteria — the build gate is sufficient given the change is a pure
deletion.

## Done criteria

Machine-checkable. ALL must hold:

- [ ] `grep -n "System.gc()" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java` returns no matches
- [ ] Docker `assembleDebug` exits with `BUILD SUCCESSFUL`
- [ ] `git status` shows only `MainActivity.java` modified
- [ ] `plans/README.md` status row for Plan 012 updated

## STOP conditions

Stop and report back (do not improvise) if:

- `grep -n "System.gc()" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java` shows a count other than 3 before you start (i.e. not exactly the two occurrences in `adjustMouseSpeedLive` plus the one in `exitEditor`) — the file has drifted from the "Current state" excerpts; report the actual count and locations instead of guessing which to remove.
- Any of the three calls turns out to sit inside logic where its removal changes control flow (e.g. inside a try/finally where its absence would change which exception propagates, or gated behind a condition) — none of the three do, per the excerpts above, but if the live code disagrees, stop and report rather than removing it anyway.
- The build fails twice after a reasonable fix attempt.

## Maintenance notes

- This is a pure jank-reduction cleanup; no behavioral change is intended or
  expected. A reviewer should just confirm the diff is exactly 3 line
  deletions with no other edits.
- If profiling later shows an actual GC-pressure problem around these code
  paths (e.g. large allocations in `ControlLayout.loadLayout`), the right fix
  is reducing allocations or using `Runtime`/`Debug` heap APIs to diagnose,
  not reintroducing `System.gc()` calls on the UI thread.
