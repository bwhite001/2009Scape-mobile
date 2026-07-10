# Plan 009: Run the JUnit test suite in CI

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 8ee361ea1..HEAD -- .github/workflows/android.yml app_pojavlauncher/build.gradle app_pojavlauncher/src/test`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none (soft-depends on the tests already existing — confirmed they do; pairs with Plan 008's doc fix)
- **Category**: tests
- **Planned at**: commit `8ee361ea1`, 2026-07-10

## Why this matters

`app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/customcontrols/CameraPanTest.java`
exists and passes locally, but `.github/workflows/android.yml` — the only CI
workflow that runs on every push/PR — never invokes `testDebugUnitTest`. It
only calls `:app_pojavlauncher:assembleDebug`. That means a green CI run
today only certifies "the code compiles," not "the existing tests pass."
Any future regression in `CameraPan` (or any future test) would silently
never be checked by CI — someone would have to remember to run tests
locally. Wiring the existing test task into the existing JDK-17 CI leg is a
small, low-risk addition that closes this gap immediately and for free (the
task already exists and already passes).

## Current state

- `.github/workflows/android.yml`, confirmed in full (61 lines), single job
  `build` on `ubuntu-22.04`, steps in order:
  1. `Checkout` (line 19-20)
  2. `Set up JDK 8` (lines 22-26)
  3. `Set up Gradle` (gradle-version 8.9) (lines 28-31)
  4. `Build JRE JAR files` — `./scripts/languagelist_updater.sh` then `gradle :jre_lwjgl3glfw:build --no-daemon` (lines 33-37)
  5. `Set up JDK 17` (lines 39-43)
  6. `Install Android SDK components` — `sdkmanager "platforms;android-35" "build-tools;35.0.0" "ndk;25.2.9519653"` (lines 45-49)
  7. `Build Debug APK (no bundled runtime)` — `gradle :app_pojavlauncher:assembleDebug --stacktrace` then `mv ... out/app-debug.apk` (lines 51-54)
  8. `Upload APK` (lines 56-61)
  - Confirmed by grepping the file for `test`/`check`: the only matches are incidental ("Set up" step names and comment text) — there is **no** `testDebugUnitTest`, `test`, or `check` Gradle task anywhere in the workflow.
- `app_pojavlauncher/build.gradle:221`: `testImplementation 'junit:junit:4.13.2'` — the test dependency is already declared, no build.gradle change is needed for this plan.
- The test task runs and passes today (confirmed via the Docker build image): `./gradlew :app_pojavlauncher:testDebugUnitTest` → `BUILD SUCCESSFUL`, `CameraPanTest`'s 5 methods pass. JUnit XML report lands at `app_pojavlauncher/build/test-results/testDebugUnitTest/`.
- The workflow invokes Gradle directly as `gradle ...` (not `./gradlew ...`) inside the "Build JRE JAR files" and "Build Debug APK" steps — match this exact invocation style (bare `gradle`, since `Set up Gradle` pins `gradle-version: 8.9` via `gradle/gradle-build-action@v3`) rather than switching to the wrapper mid-workflow.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Run tests (Docker, local proxy for CI) | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:testDebugUnitTest --console=plain"` | `BUILD SUCCESSFUL`, 5 tests, 0 failures |
| Build gate (Docker) | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` | `BUILD SUCCESSFUL` |
| Combined (both tasks in one invocation, mirrors CI ordering) | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug :app_pojavlauncher:testDebugUnitTest --console=plain"` | `BUILD SUCCESSFUL` |
| YAML sanity check | `grep -n "testDebugUnitTest" .github/workflows/android.yml` | one match, in the new step |

## Scope

**In scope (the only file you should modify):**
- `.github/workflows/android.yml`

**Out of scope:**
- `app_pojavlauncher/build.gradle` — no build.gradle change is needed; the `junit` dependency already exists.
- Any other workflow file (`.github/workflows/gradle-publish.yml` is handled separately by Plan 007).
- Writing new tests — this plan only wires up the existing `CameraPanTest`.

## Git workflow

- Branch: `advisor/009-wire-unit-tests-into-ci`
- One commit, conventional-commit style matching `git log` (e.g. `test: run unit tests in CI`).
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Add a test step to the JDK-17 leg of `android.yml`

Insert a new step named `Run unit tests` immediately after "Build Debug APK
(no bundled runtime)" (so the APK build still fails fast on compile errors
before running tests) and before "Upload APK". Match the file's existing
invocation style exactly (bare `gradle`, same `run: |` block style as the
neighboring steps):

```yaml
      - name: Run unit tests
        run: |
          gradle :app_pojavlauncher:testDebugUnitTest --stacktrace
```

If you prefer fail-fast-before-assembling instead (testing first), that is
also acceptable — either ordering satisfies "Done criteria" below — but do
not remove or reorder the existing `assembleDebug` / `Upload APK` steps
relative to each other.

**Verify**: `grep -n "testDebugUnitTest" .github/workflows/android.yml` → one match. `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/android.yml'))"` (or any available YAML parser) → exits 0, confirming valid YAML.

### Step 2 (optional but recommended): Upload the test report as an artifact

Add a step after the test step to upload
`app_pojavlauncher/build/test-results/testDebugUnitTest/` using the same
`actions/upload-artifact@v4` action already used for the APK, e.g.:

```yaml
      - name: Upload test results
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: unit-test-results
          path: app_pojavlauncher/build/test-results/testDebugUnitTest/
          retention-days: 7
```

Use `if: always()` so failed-test runs still upload the report for
debugging. This step is additive and low-risk; skip it only if you want to
keep the diff minimal, but it is the recommended default.

**Verify**: `grep -n "unit-test-results" .github/workflows/android.yml` → one match (if added).

### Step 3: Verify the test task actually runs and passes locally (proxy for CI)

Since GitHub Actions cannot be run locally, use the Docker build image as
the closest available proxy — it uses the same JDK 17 + Gradle 8.9 + SDK/NDK
toolchain the workflow installs.

```
docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder \
  bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug :app_pojavlauncher:testDebugUnitTest --console=plain"
```

**Verify**: `BUILD SUCCESSFUL`, output includes `5 tests completed, 0 failed` (or equivalent per-test PASS lines for `CameraPanTest`).

## Test plan

- No new test files — this plan wires up the existing `CameraPanTest.java`
  (5 methods: `belowThresholdIsNone`, `horizontalMapsLeftRight`,
  `verticalMapsUpDown`, `invertYSwapsVertical`, `separateThresholdsPerAxis`).
- Structural pattern for any future test added alongside this: JUnit 4,
  no Android framework dependency, mirrors `CameraPanTest.java`.
- Verification: the Docker command in Step 3 → `BUILD SUCCESSFUL`, all 5
  tests pass.

## Done criteria

Machine-checkable. ALL must hold:

- [ ] `grep -n "testDebugUnitTest" .github/workflows/android.yml` → at least one match, inside a new step in the JDK-17 job
- [ ] The workflow YAML is still valid (parses with any standard YAML loader)
- [ ] Docker command `... :app_pojavlauncher:assembleDebug :app_pojavlauncher:testDebugUnitTest --console=plain` → `BUILD SUCCESSFUL`, 5 `CameraPanTest` tests pass, 0 failures
- [ ] `git status` shows only `.github/workflows/android.yml` modified
- [ ] `plans/README.md` status row for Plan 009 updated

## STOP conditions

Stop and report back (do not improvise) if:

- The job structure in `.github/workflows/android.yml` no longer matches the
  single-job, 8-step layout described in "Current state" (e.g. it has been
  split into multiple jobs, or the JDK-17 leg was restructured) — re-read
  the live file and adapt placement rather than forcing the step into a
  position that no longer makes sense.
- The Docker test command fails to build or the test fails — this is a
  regression in the code, not something this plan should paper over; report
  it instead of weakening the CI step (e.g. do not add `continue-on-error:
  true` to hide a real failure).
- `junit:junit:4.13.2` is no longer present in
  `app_pojavlauncher/build.gradle`'s `testImplementation` — the test
  dependency the whole plan assumes is missing; report rather than
  re-adding it yourself (that would be an undocumented scope expansion).

## Maintenance notes

- Once this lands, Plan 008's doc fix (if not already merged) can add one
  clause noting CI enforces the test suite — do that as a small follow-up
  edit to `CLAUDE.md`/`plans/README.md`, not as part of this plan's diff.
- Any future test added under `app_pojavlauncher/src/test/java/` is
  automatically picked up by `testDebugUnitTest` (Gradle's default test
  source-set discovery) — no further CI wiring is needed as the suite grows.
- If the suite later grows slow or flaky, consider splitting "fast unit
  tests always run" vs. a separate opt-in job — not a concern at 5 tests.
