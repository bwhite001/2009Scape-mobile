# Plan 010: Fix `ConcurrentModificationException` in `ProgressKeeper.updateTaskCount`

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 8ee361ea1..HEAD -- app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/progresskeeper/ProgressKeeper.java`
> If this file changed since this plan was written, compare the "Current
> state" excerpts against the live code before proceeding; on a mismatch,
> treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `8ee361ea1`, 2026-07-10

## Why this matters

`ProgressKeeper.updateTaskCount()` iterates `sTaskCountListeners` with a plain
for-each while holding the class's static monitor (all methods are `static
synchronized`, and the monitor is reentrant). `ProgressKeeper.waitUntilDone`'s
internal listener calls `removeTaskCountListener(this)` — which mutates that
same list — from inside the callback it's being invoked from during that very
iteration. Any code path that reaches zero pending tasks while a
`waitUntilDone` caller is registered (a very common shutdown/completion path)
throws a `ConcurrentModificationException` out of `submitProgress`, aborting
whatever completion logic depends on it and potentially surfacing as a crash
via the app's uncaught-exception handler (`FatalErrorActivity`). The fix is a
one-line change: iterate a snapshot instead of the live list.

## Current state

`app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/progresskeeper/ProgressKeeper.java`
— the whole class is single-file; imports at the top already include
`java.util.ArrayList` (line 3), so no new import is needed.

`ProgressKeeper.java:39-44` (`updateTaskCount`):
```java
private static synchronized void updateTaskCount() {
    int count = sProgressStates.size();
    for(TaskCountListener listener : sTaskCountListeners) {
        listener.onUpdateTaskCount(count);
    }
}
```

`ProgressKeeper.java:72-74` (`removeTaskCountListener` — the mutation that
collides with the iteration above):
```java
public static synchronized void removeTaskCountListener(TaskCountListener listener) {
    sTaskCountListeners.remove(listener);
}
```

`ProgressKeeper.java:82-98` (`waitUntilDone` — the caller that triggers the
collision; note line 94 calls `removeTaskCountListener(this)`
**unconditionally, outside the `if(taskCount == 0)` block** — a secondary
quirk: the listener removes itself on the very first task-count update it
receives, regardless of whether the count is actually zero. This means
`waitUntilDone`'s listener only ever "sees" one update, which happens to
still be functionally correct for its purpose (it either fires the runnable
on that first update if the count is already 0, or never fires it again after
that — matching intent since a fresh listener is added each time
`waitUntilDone` is called and count-drops-to-zero is usually the very next
update). This plan does **not** need to fix that quirk — mention it in the
commit message as a documented secondary observation only. The CME is the
actual fix target):
```java
public static void waitUntilDone(final Runnable runnable) {
    // If we do it the other way the listener would be removed before it was added, which will cause a listener object leak
    if(getTaskCount() == 0) {
        runnable.run();
        return;
    }
    TaskCountListener listener = new TaskCountListener() {
        @Override
        public void onUpdateTaskCount(int taskCount) {
            if(taskCount == 0) {
                runnable.run();
            }
            removeTaskCountListener(this);
        }
    };
    addTaskCountListener(listener);
}
```

All excerpts above match the live file exactly as of `8ee361ea1` (verified by
direct read, not guessed from an audit note).

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Build APK | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` | `BUILD SUCCESSFUL`; APK at `app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk` |
| Unit tests | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug :app_pojavlauncher:testDebugUnitTest"` | `BUILD SUCCESSFUL`; JUnit report under `app_pojavlauncher/build/test-results/testDebugUnitTest/` shows the new test passing |

(No host JDK/Android SDK exists in this environment — always run through the
`2009scape-apk-builder` Docker image, per repo `CLAUDE.md` / `MEMORY.md`.)

## Scope

**In scope** (the only files you should modify):
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/progresskeeper/ProgressKeeper.java`
- `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/progresskeeper/ProgressKeeperTest.java` (create)

**Out of scope** (do NOT touch, even though they look related):
- `waitUntilDone`'s unconditional self-removal quirk (documented above) —
  fixing the CME does not require fixing this, and changing it risks altering
  observed behavior for existing callers. Leave it as-is.
- `ProgressListener`/`sProgressListeners` iteration in `submitProgress`
  (`ProgressKeeper.java:30-36`) — this loop has no known self-removing
  listener today; do not touch it.
- Any other file that calls into `ProgressKeeper`.

## Git workflow

- Branch: `advisor/010-fix-progresskeeper-cme`
- One commit, conventional-commit style matching repo history (e.g.
  `fix: keep PRI/SEC hidden after exiting the control editor (#23)`,
  `fix: stylus barrel-button release uses the pressed button (#31)`).
  Suggested message: `fix: avoid ConcurrentModificationException in ProgressKeeper.updateTaskCount`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Snapshot the listener list before iterating

In `updateTaskCount()`, replace the direct iteration over `sTaskCountListeners`
with an iteration over a defensive copy, so a listener that removes itself
mid-callback (as `waitUntilDone`'s does) cannot invalidate the live iterator:

```java
private static synchronized void updateTaskCount() {
    int count = sProgressStates.size();
    for(TaskCountListener listener : new ArrayList<>(sTaskCountListeners)) {
        listener.onUpdateTaskCount(count);
    }
}
```

`java.util.ArrayList` is already imported (line 3) — no import changes needed.
Do not switch `sTaskCountListeners` itself to `CopyOnWriteArrayList` unless
the snapshot approach turns out to be insufficient (it is sufficient here;
prefer the smaller diff).

**Verify**: `grep -n "new ArrayList<>(sTaskCountListeners)" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/progresskeeper/ProgressKeeper.java` → one match, inside `updateTaskCount`.

### Step 2: Add a regression test

Create `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/progresskeeper/ProgressKeeperTest.java`,
modeled structurally on
`app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/customcontrols/CameraPanTest.java`
(plain JUnit 4, no Android framework/instrumentation dependency —
`ProgressKeeper` and `TaskCountListener` are pure Java/statics, so this is
directly testable without mocking Android).

Test design: register a `TaskCountListener` that calls
`ProgressKeeper.removeTaskCountListener(this)` from inside its own
`onUpdateTaskCount` callback (mirroring `waitUntilDone`'s pattern exactly),
then drive a start+end cycle via `submitProgress` and assert no exception
propagates. Example shape:

```java
package net.kdt.pojavlaunch.progresskeeper;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ProgressKeeperTest {
    @Test public void selfRemovingListenerDuringUpdateDoesNotThrowCME() {
        final int[] observedCount = {-1};
        TaskCountListener selfRemoving = new TaskCountListener() {
            @Override
            public void onUpdateTaskCount(int taskCount) {
                observedCount[0] = taskCount;
                ProgressKeeper.removeTaskCountListener(this);
            }
        };
        ProgressKeeper.addTaskCountListener(selfRemoving, false);
        // Starting a task drives updateTaskCount() -> iterates sTaskCountListeners,
        // during which selfRemoving removes itself. Must not throw CME.
        ProgressKeeper.submitProgress("test-record-010", 0, 0);
        assertEquals(1, observedCount[0]);
        // Clean up: end the task so state doesn't leak into other tests in the same JVM.
        ProgressKeeper.submitProgress("test-record-010", -1, -1);
    }
}
```

Note `ProgressKeeper`'s state (`sProgressStates`, `sTaskCountListeners`) is
process-static, so this test must clean up after itself (end the task it
started) to avoid polluting other tests that share the JVM/test process.

If, after attempting this, the statics genuinely make the test flaky or
order-dependent in the real test run (e.g. interference from another test
class touching the same statics), do not fight it into passing by weakening
the assertion — fall back honestly: remove the test, note in the commit
message that a clean unit test was not feasible due to shared static state,
and rely on the build plus a manual repro instead (start a task via any
`ProgressKeeper.submitProgress` call site, e.g. trigger a download/unpack
flow that uses `waitUntilDone`, and confirm no crash / no CME in logcat when
it completes).

**Verify**: `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:testDebugUnitTest --tests net.kdt.pojavlaunch.progresskeeper.ProgressKeeperTest"` → `BUILD SUCCESSFUL`, test passes.

## Test plan

- New test: `ProgressKeeperTest.selfRemovingListenerDuringUpdateDoesNotThrowCME`
  in `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/progresskeeper/ProgressKeeperTest.java`,
  covering the exact regression (a listener removing itself mid-iteration of
  `updateTaskCount`) — this is the specific bug this plan fixes.
- Structural pattern: `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/customcontrols/CameraPanTest.java`
  (plain JUnit 4, `org.junit.Test` + `assertEquals`, no Android deps).
- If a clean unit test proves infeasible (see Step 2 fallback), say so
  explicitly in the commit message — do not fabricate a passing-but-vacuous
  test.
- Verification: `./gradlew :app_pojavlauncher:testDebugUnitTest` (via Docker) → all pass, including the new test if written.

## Done criteria

Machine-checkable. ALL must hold:

- [ ] `grep -n "new ArrayList<>(sTaskCountListeners)" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/progresskeeper/ProgressKeeper.java` (or an equivalent `CopyOnWriteArrayList` conversion) returns a match
- [ ] Docker `assembleDebug` exits with `BUILD SUCCESSFUL`
- [ ] Docker `testDebugUnitTest` exits with `BUILD SUCCESSFUL` (new test present and passing, or its infeasibility is documented in the commit message per the Step 2 fallback)
- [ ] `git status` shows only `ProgressKeeper.java` (and, if added, `ProgressKeeperTest.java`) modified
- [ ] `plans/README.md` status row for Plan 010 updated

## STOP conditions

Stop and report back (do not improvise) if:

- `updateTaskCount()` or `removeTaskCountListener()` no longer match the
  "Current state" excerpts (drift since this plan was written).
- The current code already snapshots the list (e.g. already iterates a copy)
  or `sTaskCountListeners` is already a `CopyOnWriteArrayList` — then the bug
  is already fixed; report this instead of making a redundant change.
- The build fails twice after a reasonable fix attempt.
- Writing `ProgressKeeperTest` requires touching any file outside the Scope
  list above (e.g. to expose new test hooks) — report instead of expanding scope.

## Maintenance notes

- The same reentrant-static-monitor pattern exists in `submitProgress`'s
  iteration over `sProgressListeners` (`ProgressKeeper.java:30-36`); it is
  out of scope here because no current listener there self-removes, but a
  future `ProgressListener` implementation that does would hit the same class
  of bug. Worth a follow-up note for whoever touches that method next.
- `waitUntilDone`'s unconditional self-removal (documented in "Current
  state") is a pre-existing quirk, not a regression from this fix — do not
  "helpfully" move the `removeTaskCountListener(this)` inside the
  `if(taskCount == 0)` block as part of this plan; that changes behavior for
  every existing caller of `waitUntilDone` and needs its own review.
- Reviewer: confirm the new test cleans up its own state (ends the task it
  starts) so it doesn't leave `sProgressStates`/`sTaskCountListeners` dirty
  for other tests sharing the JVM.
