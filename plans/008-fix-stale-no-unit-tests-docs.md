# Plan 008: Fix the stale "no unit tests" claim in CLAUDE.md and plans/README.md

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 8ee361ea1..HEAD -- CLAUDE.md plans/README.md app_pojavlauncher/src/test`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none (pairs with Plan 009 — see Maintenance notes)
- **Category**: docs
- **Planned at**: commit `8ee361ea1`, 2026-07-10

## Why this matters

`CLAUDE.md` is the onboarding document every agent (and contributor) reads
before touching this repo, and it currently states a **false** fact: that
there are no unit tests. This was true when written but a JUnit test
(`CameraPanTest`) and its dependency (`junit:junit:4.13.2`) were added since.
A stale-and-wrong claim is worse than a missing one — it actively steers
agents away from writing or running tests, and away from noticing the one
existing test as a pattern to follow. `plans/README.md` repeats the same now-false
claim in its "Verification reality" note. Both need a small, accurate
correction so future work (including Plan 009, which wires this test into CI)
builds on a correct premise.

## Current state

- `CLAUDE.md:57` (repo root, in the "Build & run" section), confirmed verbatim:
  ```
  There are **no unit tests** in this repo (`CriticalNativeTest.java` / `TestStorageActivity.java` are runtime checks, not JUnit). Verification is: does the APK build and does it launch the client on-device. Device smoke checklist: `docs/verification/device-smoke-checklist.md`.
  ```
  This is false today: `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/customcontrols/CameraPanTest.java` exists (5 `@Test` methods: `belowThresholdIsNone`, `horizontalMapsLeftRight`, `verticalMapsUpDown`, `invertYSwapsVertical`, `separateThresholdsPerAxis`) and `app_pojavlauncher/build.gradle:221` has `testImplementation 'junit:junit:4.13.2'`.
- `plans/README.md:9`, confirmed verbatim (in the "Verification reality for this repo" note):
  ```
  **Verification reality for this repo:** there is no unit-test suite. The gate is
  `./gradlew :app_pojavlauncher:assembleDebug` (needs JDK 17, SDK 35, build-tools
  35.0.0, NDK 25.2.9519653) — or push a branch and use the green "Android CI" run.
  ```
  Same stale claim.
- The test itself, confirmed at `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/customcontrols/CameraPanTest.java:1-6`:
  ```java
  package net.kdt.pojavlaunch.customcontrols;

  import static org.junit.Assert.assertEquals;
  import org.junit.Test;

  public class CameraPanTest {
  ```
  This is a plain JUnit 4 test with no Android framework dependency — it is the structural pattern any new unit test in this repo should follow (pure logic extracted from Android/IO code, tested directly).
- The test command (confirmed working via the Docker build image, per this session's operator memory): `./gradlew :app_pojavlauncher:testDebugUnitTest`, run inside the `2009scape-apk-builder` Docker image (no host JDK/SDK exists). Output lands under `app_pojavlauncher/build/test-results/testDebugUnitTest/`.
- CI (`.github/workflows/android.yml`) does **not** currently run this test (confirmed: the workflow has no `test`/`check` step) — that gap is Plan 009's scope, not this plan's. This plan only fixes the documentation; do not add a CI step here.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Confirm stale text | `grep -n "no unit tests" CLAUDE.md` | one match, before edit |
| Confirm stale text | `grep -n "no unit-test suite" plans/README.md` | one match, before edit |
| Run the existing test (Docker) | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:testDebugUnitTest"` | `BUILD SUCCESSFUL`, 5 tests pass |
| Build gate (Docker) | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` | `BUILD SUCCESSFUL` |

## Scope

**In scope (the only files you should modify):**
- `CLAUDE.md`
- `plans/README.md`

**Out of scope:**
- Adding a CI step to run tests — that is Plan 009.
- Adding new tests or touching `CameraPanTest.java` — this plan is docs-only.
- Any other section of either file beyond the specific stale sentences identified above.

## Git workflow

- Branch: `advisor/008-fix-stale-no-unit-tests-docs`
- One commit, conventional-commit style matching `git log` (e.g. `docs: correct stale "no unit tests" claim in CLAUDE.md and plans/README`).
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Correct `CLAUDE.md:57`

Replace the false sentence with an accurate one that: (a) states a JUnit unit-test suite exists under `app_pojavlauncher/src/test/java/`, (b) names `CameraPanTest`/pure-logic seams like `CameraPan` as the current test target and pattern to follow, and (c) gives the test command. Keep it to roughly the same length (a few lines) and keep the existing device-smoke-checklist pointer. For example (adapt wording, don't just paste verbatim if it reads awkwardly in context):

```
A JUnit 4 unit-test suite exists under `app_pojavlauncher/src/test/java/` (e.g. `customcontrols/CameraPanTest.java`, testing the pure-logic `CameraPan` class with no Android framework dependency — the pattern to follow for new tests). Run it with `./gradlew :app_pojavlauncher:testDebugUnitTest` (via the Docker build image described above). Beyond that, verification is: does the APK build, and does it launch/behave correctly on-device. Device smoke checklist: `docs/verification/device-smoke-checklist.md`.
```

**Verify**: `grep -n "no unit tests" CLAUDE.md` → no output. `grep -n "testDebugUnitTest" CLAUDE.md` → at least one match.

### Step 2: Correct `plans/README.md:9`

Replace the "Verification reality for this repo" sentence about there being no unit-test suite with an accurate one, keeping the rest of that paragraph (the `assembleDebug` gate description and device-checklist mention) intact. For example:

```
**Verification reality for this repo:** a JUnit unit-test suite exists (`app_pojavlauncher/src/test/java/`, e.g. `CameraPanTest`), run via `./gradlew :app_pojavlauncher:testDebugUnitTest`. The build gate is
`./gradlew :app_pojavlauncher:assembleDebug` (needs JDK 17, SDK 35, build-tools
35.0.0, NDK 25.2.9519653) — or push a branch and use the green "Android CI" run.
```

**Verify**: `grep -n "no unit-test suite" plans/README.md` → no output. `grep -n "testDebugUnitTest" plans/README.md` → at least one match.

### Step 3: Confirm the test command still works as documented

Run the Docker test command from the table above and confirm it matches what you just wrote.

**Verify**: `BUILD SUCCESSFUL`, 5 tests pass, 0 failures.

## Test plan

This plan changes documentation only; there are no new tests to write. The
"test" is that the commands the docs now reference actually work:

- Verification: `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:testDebugUnitTest"` → `BUILD SUCCESSFUL`, all 5 `CameraPanTest` methods pass.

## Done criteria

Machine-checkable. ALL must hold:

- [ ] `grep -n "no unit tests" CLAUDE.md` → no output
- [ ] `grep -n "no unit-test suite" plans/README.md` → no output
- [ ] `grep -n "testDebugUnitTest" CLAUDE.md plans/README.md` → at least one match in each file
- [ ] Docker test command above exits `BUILD SUCCESSFUL`
- [ ] Docker `assembleDebug` command exits `BUILD SUCCESSFUL`
- [ ] `git status` shows only `CLAUDE.md` and `plans/README.md` modified
- [ ] `plans/README.md` status row for Plan 008 updated

## STOP conditions

Stop and report back (do not improvise) if:

- `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/customcontrols/CameraPanTest.java` no longer exists (the premise of this plan is gone — report rather than writing docs about a test that isn't there).
- The exact sentence at `CLAUDE.md:57` (or its line number) no longer matches the excerpt above — re-read the current file and adjust the edit to the real surrounding text rather than force-fitting the template wording.
- The Docker test command fails to build or fails tests — that is a code problem, not a docs problem; report it instead of editing the docs to describe a broken command.

## Maintenance notes

- This plan pairs with Plan 009 (wiring `testDebugUnitTest` into CI): once that lands, add one clause to the corrected sentences here (or in a follow-up edit) noting that CI enforces the test suite on every push/PR. Do not pre-emptively claim CI enforcement in this plan — it is not true until Plan 009 lands.
- If more unit tests are added later, keep this section's example minimal (one exemplar test) rather than enumerating every test — the goal is pointing agents at the pattern, not maintaining a test inventory in prose docs.
