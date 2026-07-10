# Plan 028: Give the shared thread pool a named `ThreadFactory` and coherent keep-alive config

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 8ee361ea1..HEAD -- app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/PojavApplication.java`
> If this file changed since this plan was written, compare the "Current
> state" excerpt against the live code before proceeding; on a mismatch,
> treat it as a STOP condition.

## Status

- **Priority**: P3
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: tech-debt
- **Planned at**: commit `8ee361ea1`, 2026-07-10

## Why this matters

**Honest verdict up front: this is a LOW-leverage plan.** `PojavApplication.java:27` constructs the app's one shared background executor with `corePoolSize == maximumPoolSize == 4` and a 500ms keep-alive — but with core and max pool size equal, `allowCoreThreadTimeOut` never enabled, and an unbounded `LinkedBlockingQueue`, the keep-alive value is **dead configuration**: core threads never time out (that flag is off), and the max pool size is never exceeded (the unbounded queue absorbs everything before a thread could be added beyond core size), so the "500ms" is decorative. There is no unnamed-thread-in-crash-log problem proven to have caused a real incident, and no starvation bug proven here either (that would require a pooled task blocking on another task queued behind it in the same 4-thread pool — plausible in principle, not evidenced in this recon). The concrete, real benefit of doing this is: (1) crash logs / thread dumps get to show a labeled thread name (`pojav-pool-N`) instead of the JVM default `pool-N-thread-M`, which helps triage if a crash ever does show up in this pool, and (2) the keep-alive value stops being silently meaningless. Do this plan when you have spare, very-low-risk cycles — do not prioritize it over anything else in this batch.

## Current state

`app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/PojavApplication.java:25-28`:
```java
public class PojavApplication extends Application {
	public static final String CRASH_REPORT_TAG = "PojavCrashReport";
	public static final ExecutorService sExecutorService = new ThreadPoolExecutor(4, 4, 500, TimeUnit.MILLISECONDS,  new LinkedBlockingQueue<>());
	public static AppContainer appContainer;
```
Imports already present (`PojavApplication.java:16-19`):
```java
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
```
No `ThreadFactory` is passed — the JVM default factory is used, which names threads `pool-N-thread-M` with no indication they belong to this app's shared pool. The default (unbounded queue → `AbortPolicy`) rejection policy is also implicit; this plan does not change it (see Scope).

Three call sites all use `.execute(...)` only (confirmed via `grep -rn "sExecutorService" app_pojavlauncher/src/main/java`):
- `multirt/RTRecyclerViewAdapter.java:116`
- `tasks/AsyncAssetManager.java:49,94,110`
- `Tools.java:736,759`

None of them cast `sExecutorService` to `ThreadPoolExecutor` or otherwise depend on its concrete type — the field's declared type (`ExecutorService`) does not need to change.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Build APK | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` | `BUILD SUCCESSFUL` |
| Unit tests (no new tests expected, but keep the gate green) | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug :app_pojavlauncher:testDebugUnitTest"` | `BUILD SUCCESSFUL` |
| Confirm no caller depends on concrete type | `grep -rn "sExecutorService" app_pojavlauncher/src/main/java` | only the 4 `.execute(...)` call sites + the declaration itself |

## Scope

**In scope:**
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/PojavApplication.java` — the `sExecutorService` field declaration only.

**Out of scope (do NOT touch):**
- Callers of `sExecutorService` (`RTRecyclerViewAdapter.java`, `AsyncAssetManager.java`, `Tools.java`) — no caller-side change is needed or wanted.
- Pool sizing (`4, 4`) — do not resize without evidence of starvation; none was found in this recon.
- The rejection policy (stays the JVM default `AbortPolicy`) and the queue type (stays unbounded `LinkedBlockingQueue`) — changing either is a bigger behavioral decision than this plan covers.

## Git workflow

- Branch: `advisor/028-tune-shared-threadpool-executor`
- One commit, e.g. `fix: name the shared executor's threads and make its keep-alive config meaningful`.
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Add a named `ThreadFactory` and activate the keep-alive

Replace the single-line field declaration at `PojavApplication.java:27` with:

```java
public class PojavApplication extends Application {
	public static final String CRASH_REPORT_TAG = "PojavCrashReport";
	private static final ThreadPoolExecutor sThreadPoolExecutor = new ThreadPoolExecutor(
			4, 4, 500, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
			new java.util.concurrent.ThreadFactory() {
				private final java.util.concurrent.atomic.AtomicInteger mCount =
						new java.util.concurrent.atomic.AtomicInteger(0);
				@Override
				public Thread newThread(Runnable r) {
					Thread t = new Thread(r, "pojav-pool-" + mCount.getAndIncrement());
					t.setDaemon(true);
					return t;
				}
			});
	static {
		// Without this, corePoolSize == maximumPoolSize made the 500ms keep-alive
		// dead configuration (core threads never timed out, max was never exceeded
		// because the queue is unbounded). This activates it so idle threads
		// actually exit and get recreated on demand.
		sThreadPoolExecutor.allowCoreThreadTimeOut(true);
	}
	public static final ExecutorService sExecutorService = sThreadPoolExecutor;
	public static AppContainer appContainer;
```

Keep the field's PUBLIC type as `ExecutorService` (do not expose `ThreadPoolExecutor` — callers only ever call `.execute(...)`, per the "Current state" grep).

**Verify**: `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` → `BUILD SUCCESSFUL`.

### Step 2: Confirm no caller was relying on the old anonymous factory / dead keep-alive

**Verify**: `grep -rn "sExecutorService" app_pojavlauncher/src/main/java` → still only the 4 `.execute(...)` call sites (`RTRecyclerViewAdapter.java:116`, `AsyncAssetManager.java:49,94,110`, `Tools.java:736,759`) plus the declaration in `PojavApplication.java` — nothing casts to `ThreadPoolExecutor` or otherwise needed the old shape.

### Step 3: Full build gate

**Verify**: `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug :app_pojavlauncher:testDebugUnitTest"` → `BUILD SUCCESSFUL`.

## Test plan

No meaningful unit test for this change — it's JVM thread-pool wiring with no observable pure-logic surface worth a `CameraPanTest`-style test (thread naming/keep-alive timing is not reliably assertable in a fast unit test, and this plan is explicitly not trying to prove/disprove starvation). Verification is: the build stays green, and the field still satisfies `ExecutorService` for its 3 existing callers.

## Done criteria

Machine-checkable. ALL must hold:

- [ ] `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug :app_pojavlauncher:testDebugUnitTest"` exits 0 / `BUILD SUCCESSFUL`
- [ ] `sExecutorService` is backed by a `ThreadPoolExecutor` constructed with an explicit named `ThreadFactory` (`grep -n "ThreadFactory" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/PojavApplication.java` shows a hit)
- [ ] `allowCoreThreadTimeOut(true)` is called on it, OR the dead 500ms keep-alive argument is removed/set to `0` if the executor decides against enabling it — pick ONE and make the config internally coherent (no dead keep-alive value left in place unexplained)
- [ ] `git status` shows only `PojavApplication.java` modified
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:

- The code at `PojavApplication.java:27` doesn't match the excerpt in "Current state" (drift since this plan was written).
- Step 2's grep turns up a caller casting `sExecutorService` to `ThreadPoolExecutor` or otherwise depending on its exact construction (not expected, but if found, STOP — the fix may need to preserve more of the original shape than this plan assumes).
- A step's verification fails twice after a reasonable fix attempt.
- You find evidence (e.g. in an ANR report or existing bug tracker note) that this pool has actually starved in production — that would upgrade this from "clarity/config smell" to a real bug and should be reported back rather than silently expanded into a resizing fix under this plan's LOW-risk banner.

## Maintenance notes

- This plan deliberately does NOT resize the pool or change the rejection policy — if a future incident shows the 4-thread cap causing starvation (e.g. a task submitted from within another pooled task, deadlocking), that is a separate, higher-priority bug-fix plan, not a continuation of this one.
- Reviewer: this is explicitly a low-priority, optional cleanup (P3) — do not block higher-priority plans in this batch on it landing first.
