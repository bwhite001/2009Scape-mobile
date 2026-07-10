# Plan 016: Surface AsyncAssetManager unpack failures instead of silently clearing progress as success

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 8ee361ea1..HEAD -- app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/tasks/AsyncAssetManager.java app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/di/ProgressRepository.kt app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/ScapeLauncher.kt`
> If any of these files changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: LOW
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `8ee361ea1`, 2026-07-10

## Why this matters

On first run (and on every app start, since `unpackComponents`/`unpackSingleFiles`
run unconditionally from `TestStorageActivity`), the launcher extracts
`rt4.jar`, `config.json`, plugins, and GL/AWT support components from APK
assets on a background thread. If extraction fails partway (low storage, a
corrupt/unexpectedly-shaped asset, a permission error), the current code logs
the exception and then **unconditionally reports the task as finished** —
there is no branch that distinguishes success from failure before the
progress indicator is cleared. The home screen (`ScapeLauncher`) only gates
"Play HD"/"Play SD" on whether *any* task is still in progress
(`ProgressUiState.isBusy`), not on whether the tasks that did run actually
succeeded. So a user can tap Play immediately after a failed unpack, and the
launch path dereferences a missing `rt4.jar`/runtime/plugin directory,
crashing far from the actual cause with an opaque error. Fixing this means
introducing a real failure signal the UI can gate on, so a broken unpack
produces a clear message instead of a confusing crash three screens later.

## Current state

- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/tasks/AsyncAssetManager.java`
  — confirmed against the live file (209 lines total):

  ```java
  33  public static void unpackRuntime(AssetManager am) {
  34      /* Check if JRE is included */
  ...
  49      sExecutorService.execute(() -> {
  50
  51          try {
  52              MultiRTUtils.installRuntimeNamedBinpack(
  53                      am.open("components/jre/universal.tar.xz"),
  54                      am.open("components/jre/bin-" + archAsString(Tools.DEVICE_ARCHITECTURE) + ".tar.xz"),
  55                      "Internal", finalRt_version);
  56              MultiRTUtils.postPrepare("Internal");
  57          }catch (IOException e) {
  58              Log.e("JREAuto", "Internal JRE unpack failed", e);
  59          }
  60      });
  61  }
  ```

  Note: `unpackRuntime` does **not** call `ProgressLayout.setProgress`/
  `clearProgress` at all (despite `ProgressLayout.UNPACK_RUNTIME` existing as
  a constant, `ProgressLayout.java:34`) — it is invisible to the task-count
  system entirely, unlike the other two methods below. This is a pre-existing
  gap distinct from the "silently clears success" bug in the other two
  methods; see Scope and STOP conditions for how this plan handles it.

  ```java
  92  public static void unpackSingleFiles(Context ctx){
  93      ProgressLayout.setProgress(ProgressLayout.EXTRACT_SINGLE_FILES, 0);
  94      sExecutorService.execute(() -> {
  95          try {
  96              boolean overwrite = assetVersionChanged(ctx);
  97              Tools.copyAssetFile(ctx, "options.txt", Tools.DIR_GAME_NEW, false);
  98              Tools.copyAssetFile(ctx, "default.json", Tools.CTRLMAP_PATH, overwrite);
  99              Tools.copyAssetFile(ctx, "launcher_profiles.json", Tools.DIR_GAME_NEW, false);
  100              // NOTE: saveAssetVersion is intentionally NOT called here — unpackComponents does it
  101         } catch (IOException e) {
  102             Log.e("AsyncAssetManager", "Failed to unpack critical components !");
  103         }
  104         ProgressLayout.clearProgress(ProgressLayout.EXTRACT_SINGLE_FILES);
  105     });
  106  }
  ```

  ```java
  108  public static void unpackComponents(Context ctx){
  109      ProgressLayout.setProgress(ProgressLayout.EXTRACT_COMPONENTS, 0);
  110      sExecutorService.execute(() -> {
  111          try {
  112              boolean overwrite = assetVersionChanged(ctx);
  113              unpackComponent(ctx, "caciocavallo", false);
  114              unpackComponent(ctx, "caciocavallo17", false);
  115              // Since the Java module system doesn't allow multiple JARs to declare the same module,
  116              // we repack them to a single file here
  117              unpackComponent(ctx, "lwjgl3", false);
  118              unpackComponent(ctx, "security", true);
  119              Tools.copyAssetFile(ctx,"rt4.jar",Tools.DIR_DATA, false); // Change this to true if you're working on client features.
  120              Tools.copyAssetFile(ctx,"config.json",Tools.DIR_DATA, overwrite);
  121              extractAllPlugins(ctx);
  122              if (overwrite) saveAssetVersion(ctx);
  123          } catch (IOException e) {
  124              Log.e("AsyncAssetManager", "Failed o unpack components !",e );
  125          }
  126          ProgressLayout.clearProgress(ProgressLayout.EXTRACT_COMPONENTS);
  127      });
  128  }
  ```

  In both `unpackSingleFiles` and `unpackComponents`, `ProgressLayout.clearProgress(...)`
  is the line *immediately after* the `try/catch` block, so it runs whether
  the `try` succeeded or an `IOException` was caught — there is no code path
  where failure is distinguishable from success once these methods return.

- These are called unconditionally on every launch of `TestStorageActivity`
  (confirmed): `TestStorageActivity.java:64-65`:
  ```java
  64  AsyncAssetManager.unpackComponents(this);
  65  AsyncAssetManager.unpackSingleFiles(this);
  ```
  and `unpackRuntime` from `PojavApplication.java:76` (`onCreate`, inside a
  broader `try` whose `catch (Throwable throwable)` at line 77 routes to
  `FatalErrorActivity` — but that only catches synchronous exceptions thrown
  directly in `onCreate`; `unpackRuntime`'s actual unpack work runs later on
  `sExecutorService`, inside its own `try/catch (IOException)` at lines
  51-59, so a failure there is swallowed the same way, never reaching
  `PojavApplication`'s outer catch).

- The task-count / gating mechanism, confirmed (this differs from what a
  naive read of the launch path might suggest — there is **no**
  `runtimeReady()` method; the actual gate is `ProgressUiState.isBusy`):
  - `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/progresskeeper/ProgressKeeper.java` —
    `submitProgress` (lines 11-34) treats `resid == -1 && progress == -1` (i.e.
    exactly what `ProgressLayout.clearProgress` passes,
    `ProgressLayout.java:107-109`) as "this task ended", removes it from
    `sProgressStates`, and calls `updateTaskCount()` (lines 36-41), which
    notifies all `TaskCountListener`s with `sProgressStates.size()`.
  - `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/di/ProgressRepository.kt:12-19`:
    ```kotlin
    data class ProgressUiState(
        val taskCount: Int = 0,
        val progress: Int = 0,
        val messageResId: Int = 0,
        val args: List<Any?> = emptyList(),
    ) {
        val isBusy: Boolean get() = taskCount > 0
    }
    ```
    There is no error/failure field on this state today.
  - `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/ScapeLauncher.kt:78-84`:
    ```kotlin
    private inline fun launchIfReady(state: ProgressUiState, action: () -> Unit) {
        if (state.isBusy) {
            Toast.makeText(this, R.string.tasks_ongoing, Toast.LENGTH_LONG).show()
        } else {
            action()
        }
    }
    ```
    wired from `onPlayHd`/`onPlaySd` at lines 70-71. Once `clearProgress` fires
    (success or failure — see above), `taskCount` drops to 0, `isBusy` becomes
    `false`, and `launchIfReady` lets the user proceed regardless of whether
    the unpack actually completed.

- Convention: `ProgressRepository` is constructed once in
  `AppContainer.kt:24` and reached via the public static
  `PojavApplication.appContainer.progressRepository` (see
  `PojavApplication.java:75`, `AppContainer.kt:13-26`) — this is how
  `AsyncAssetManager` (package `net.kdt.pojavlaunch.tasks`) can reach the
  same repository instance `ScapeLauncher` observes, without a new DI
  framework (this repo deliberately has none, per `AppContainer.kt:9-11`).

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Build APK | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` | `BUILD SUCCESSFUL` |
| Unit tests | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug :app_pojavlauncher:testDebugUnitTest"` | all tests pass |

## Scope

**In scope:**
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/tasks/AsyncAssetManager.java` — `unpackSingleFiles`, `unpackComponents` (report failure instead of swallowing); `unpackRuntime` may optionally be included per Step 4 (kept separate because it currently has no progress tracking at all — see STOP conditions if this turns out to be a larger change than expected).
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/di/ProgressRepository.kt` — add a minimal error-state field/method.
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/ScapeLauncher.kt` — `launchIfReady` (gate on the new error state) and, if needed, a short user-facing message.

**Out of scope (do NOT touch):**
- `ProgressLayout.java`, `ProgressKeeper.java` — the legacy static observer plumbing is unchanged; this plan only adds a new field to the StateFlow-based `ProgressUiState`/`ProgressRepository` layer that already mirrors it, not the legacy system itself.
- `TestStorageActivity.java`'s call sites (lines 64-65) — no change needed; the fix lives in what happens after the failure, not in when unpack is invoked.
- `Tools.launchGLJRE` / `launchJavaRuntime` internals — this plan gates *before* they're reached (in `ScapeLauncher`'s `launchIfReady`), it does not add error handling inside them.
- Any retry/re-unpack UI — out of scope; the fix is "don't let the user proceed past a known failure," not "automatically fix the failure."

## Git workflow

- Branch: `advisor/016-surface-asyncassetmanager-unpack-failures`
- Commit per logical unit (e.g. one for `ProgressRepository`'s new field, one for `AsyncAssetManager`'s failure reporting, one for `ScapeLauncher`'s gate); message style: conventional commits, e.g. `fix: surface asset-unpack failures instead of silently reporting success`.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Add an error flag to `ProgressUiState` and a way to set/clear it

In `ProgressRepository.kt`, add a field to `ProgressUiState` (line 12-19),
e.g. `val unpackFailed: Boolean = false`, and add a public method on
`ProgressRepository` (alongside its existing `init` block, lines 57-60) that
flips it, e.g.:

```kotlin
fun reportUnpackFailure() {
    _state.value = _state.value.copy(unpackFailed = true)
}
```

Do not clear `unpackFailed` automatically elsewhere in this step — Step 2
decides when it's appropriate to reset (only ever on a *new* unpack attempt
starting, never as a side effect of an unrelated progress update).

**Verify**: `grep -n "unpackFailed" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/di/ProgressRepository.kt` → field + `reportUnpackFailure` both present.

### Step 2: Report failure from `AsyncAssetManager.unpackSingleFiles` and `unpackComponents`

In both methods' `catch (IOException e)` blocks (`AsyncAssetManager.java:101-103`
and `123-125`), after the existing `Log.e(...)` call, add:
```java
net.kdt.pojavlaunch.PojavApplication.appContainer.progressRepository.reportUnpackFailure();
```
(fully qualify or add an import — this file currently has no `PojavApplication`
import). Leave the subsequent `ProgressLayout.clearProgress(...)` call
unconditional exactly as it is today (lines 104 and 126) — clearing progress
still needs to happen so the busy-spinner goes away and the task count
returns to 0; the point of this plan is that `unpackFailed` now separately
survives that clear, so `ScapeLauncher` can still gate on it.

At the top of each method, before doing any work (`AsyncAssetManager.java:93`
and `109`, right after the existing `ProgressLayout.setProgress(...)` calls),
do NOT reset `unpackFailed` — a genuine first-run failure should keep
blocking launch until the app is restarted or storage/space is fixed; resetting
it on every call would let a failing device loop silently. (If a future plan
adds a retry button, that is the natural place to reset the flag — not here.)

**Verify**: `grep -n "reportUnpackFailure" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/tasks/AsyncAssetManager.java` → two matches, one inside each of `unpackSingleFiles` and `unpackComponents`'s catch blocks.

### Step 3: Gate `launchIfReady` on `unpackFailed`

In `ScapeLauncher.kt:78-84`, change `launchIfReady` to check the new field
before allowing the action:
```kotlin
private inline fun launchIfReady(state: ProgressUiState, action: () -> Unit) {
    when {
        state.unpackFailed -> Toast.makeText(this, R.string.unpack_failed, Toast.LENGTH_LONG).show()
        state.isBusy -> Toast.makeText(this, R.string.tasks_ongoing, Toast.LENGTH_LONG).show()
        else -> action()
    }
}
```
Add a new string resource `unpack_failed` to
`app_pojavlauncher/src/main/res/values/strings.xml` (the source of truth per
this repo's Crowdin convention — do not hand-add matching translations to
`values-*` dirs; those are Crowdin-managed) with clear wording, e.g. "Setup
failed to complete — check available storage and restart the app." Since
adding a locale/values file is not being done here (only a new string key in
the existing `values/strings.xml`), `scripts/languagelist_updater.sh` does
NOT need to be re-run for this change (it only matters when a `values-*`
*directory* is added/removed).

**Verify**: `grep -n "unpack_failed" app_pojavlauncher/src/main/res/values/strings.xml app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/ScapeLauncher.kt` → the string resource defined once, referenced once.

### Step 4 (optional, decide based on effort): Extend the same pattern to `unpackRuntime`

`unpackRuntime` (`AsyncAssetManager.java:33-61`) has no `ProgressLayout`
tracking at all today, so it is currently invisible to both `isBusy` and
(before this plan) any failure state. If you choose to cover it: add a
`reportUnpackFailure()` call in its `catch (IOException e)` block
(line 57-59) — this is a small, additive change (one line) and does not
require adding `ProgressLayout.setProgress`/`clearProgress` calls to
`unpackRuntime` (that would be a larger, riskier change to its
task-count visibility, and is not required to make failures visible via the
new `unpackFailed` flag). If this turns out to interact badly with
`unpackRuntime`'s existing early-return branches (lines 43-45, which are
normal "nothing to do" cases, not failures — do not mark those as
`unpackFailed`), skip this step and note it in the STOP conditions report
instead of guessing.

**Verify**: if implemented, `grep -n "reportUnpackFailure" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/tasks/AsyncAssetManager.java` → three matches total (Step 2's two plus this one).

## Test plan

- This is IO-and-Android-lifecycle-driven; a full end-to-end test would need
  Robolectric, which this repo does not have set up — do not add it for this
  plan.
- A small pure piece is testable: if you want coverage of the state
  transition itself, you can write a plain JUnit4 test against
  `ProgressUiState`'s `copy(unpackFailed = true)` behavior (trivial data-class
  behavior — only worth a test if you're unsure the `data class` `copy`
  semantics interact correctly with the other fields; otherwise this is
  optional).
- Device/described verification (required, list in the PR description since
  this is the actual regression check):
  1. Normal first run (nothing simulated) — unpack completes, Play HD/SD work
     as before (no regression from the added field/gate).
  2. Simulate a failure: temporarily rename/corrupt one asset referenced by
     `unpackComponents` (e.g. rename `assets/rt4.jar` before building a debug
     APK for this test only — do not commit this change) so
     `Tools.copyAssetFile(ctx,"rt4.jar",...)` throws `IOException`; install
     that build, launch the app, and confirm: the busy spinner clears (as
     before) but tapping Play HD/SD now shows the new "setup failed" toast
     instead of proceeding to a crash.
  3. Revert the simulated-failure asset change before this plan is considered
     verified — it must not ship.
- Run `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug :app_pojavlauncher:testDebugUnitTest"` → `BUILD SUCCESSFUL`, all tests pass.

## Done criteria

Machine-checkable. ALL must hold:

- [ ] Docker build command exits with `BUILD SUCCESSFUL`
- [ ] Docker test command passes
- [ ] `grep -n "unpackFailed" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/di/ProgressRepository.kt` shows the new field and `reportUnpackFailure` method
- [ ] `grep -n "reportUnpackFailure" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/tasks/AsyncAssetManager.java` shows it called from both `unpackSingleFiles`'s and `unpackComponents`'s catch blocks
- [ ] `grep -n "unpackFailed" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/ScapeLauncher.kt` shows `launchIfReady` checking it before `isBusy`
- [ ] No simulated-failure asset changes remain in the diff (`git status` / `git diff --stat` shows only the in-scope source files)
- [ ] Device steps 1-2 above completed and noted in the PR/commit description
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:

- The code at `AsyncAssetManager.java:33-128`, `ProgressRepository.kt:12-60`,
  or `ScapeLauncher.kt:78-84` doesn't match the excerpts above (drift) —
  re-read the live files before proceeding.
- A verification step fails twice after a reasonable fix attempt.
- Extending the pattern to `unpackRuntime` (Step 4) turns out to require
  adding full `ProgressLayout` task-count tracking to it (i.e. it's not a
  one-line addition) — skip Step 4, ship Steps 1-3, and report the gap rather
  than expanding this plan's scope to refactor `unpackRuntime`'s progress
  wiring.
- You find `PojavApplication.appContainer` can be `null` at the point
  `AsyncAssetManager`'s catch blocks would call
  `reportUnpackFailure()` (e.g. if unpack can somehow run before
  `AppContainer` is constructed at `PojavApplication.java:75`) — report the
  exact call path rather than adding a silent null-check that would just
  reintroduce the swallow-failure bug this plan exists to fix.

## Maintenance notes

- This plan does not add a retry mechanism — once `unpackFailed` is set, the
  only way forward today is restarting the app (which re-runs
  `unpackComponents`/`unpackSingleFiles` and could clear the flag on a
  subsequent success, since Step 1 explicitly does not auto-reset it
  elsewhere). If a future plan adds a "retry unpack" button, it should reset
  `unpackFailed` via `ProgressRepository` right before re-invoking the
  unpack methods.
- A reviewer should check that `unpackFailed` genuinely reflects "the app is
  not safely launchable," not just "something logged a warning" — if a future
  change adds a new failure mode that's actually recoverable (e.g. a
  non-critical plugin failing to extract while the core `rt4.jar`/config
  still unpacked fine), that failure mode may deserve its own, less severe
  signal rather than reusing this same all-or-nothing flag.
- `unpackRuntime`'s complete absence from the task-count system (noted in
  "Current state") is a separate, pre-existing gap this plan does not fully
  close (see Step 4's optional, narrow treatment) — a future plan could give
  it proper `ProgressLayout.UNPACK_RUNTIME` start/clear calls so it's visible
  in the busy spinner at all, not just in the error flag.
