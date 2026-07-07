# Plan 006: Establish a verification baseline (build gate + device smoke checklist)

> **Executor instructions**: This plan creates documentation only — it does not modify source. Follow the steps, verify, and update `plans/README.md`.
>
> **Drift check (run first)**: `git diff --stat 0e75ab512..HEAD -- .github/workflows/android.yml` — if CI changed, re-read it before quoting commands below.

## Status
- **Priority**: P1 (unblocker)
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: dx / tests
- **Planned at**: commit `0e75ab512`, 2026-07-07

## Why this matters
This repo has **no automated test suite** and no local runtime verification, and a large Kotlin/Compose launcher-shell rewrite was recently merged to `master` **verified only to compile** — not verified to run. Every behavioral fix (Plans 003, 005) and every future shell change ships blind unless there is a repeatable way to confirm the app still works. Realistically, unit tests are a poor fit for this codebase (GL/JNI, embedded JVM, no local toolchain in some environments); the achievable baseline is: (1) the CI APK build as the compile gate — which already exists — and (2) a **documented, repeatable on-device smoke checklist** covering the launch paths and the new Compose surfaces. This unblocks safe execution of the other plans.

## Current state
- CI: `.github/workflows/android.yml` runs on push/PR, builds `:app_pojavlauncher:assembleDebug` and uploads an `app-debug` artifact. This is the compile gate — green run == it builds.
- No `src/test` or `src/androidTest` sources exist (`find app_pojavlauncher/src -path '*/test/*' -o -path '*/androidTest/*'` → none).
- Recently migrated to Compose (unverified at runtime): home (`ScapeLauncher.kt`), settings (`SettingsActivity.kt`), `MissingStorageActivity.kt`; DI via `di/AppContainer.kt`. Launch paths unchanged: Play HD → `MainActivity` (GL), Play SD → `JavaGUILauncherActivity` (AWT).
- Repo doc: root `CLAUDE.md` states verification is "does the APK build, and does it launch + behave correctly on a physical device."

## Commands you will need
| Purpose | Command | Expected |
|---|---|---|
| Build (local) | `./gradlew :app_pojavlauncher:assembleDebug` | `BUILD SUCCESSFUL` (JDK 17, SDK 35, build-tools 35.0.0, NDK 25.2.9519653) |
| Build (CI) | push a branch | "Android CI" green, `app-debug` artifact |
| Install | `adb install -r <path>/app-debug.apk` | `Success` |
| Launch | `adb shell monkey -p net.kdt.pojavlaunch.debug -c android.intent.category.LAUNCHER 1` | app starts |

## Scope
**In scope (create):**
- `docs/verification/device-smoke-checklist.md`

**Out of scope:** adding a JUnit/instrumented-test framework (poor fit, separate decision); changing CI; touching source.

## Git workflow
- Branch: `advisor/006-verification-baseline`
- One commit, e.g. `docs: add device smoke-test checklist as verification baseline`.

## Steps

### Step 1: Create the device smoke checklist
Create `docs/verification/device-smoke-checklist.md` with the content below. It must cover the launch paths and every migrated Compose surface, since those are the highest-risk unverified areas.

```markdown
# Device Smoke Checklist

Run on a physical **ARM64** device (x86 emulator is unsupported). Build the
debug APK (CI `app-debug` artifact or `./gradlew :app_pojavlauncher:assembleDebug`)
and `adb install -r`. Tick every item before merging a shell change.

## Launch (unchanged native paths)
- [ ] App launches to the home screen without crash (`adb logcat` clean of FATAL).
- [ ] **Play HD** boots into the game (GL surface renders, reaches the client login screen).
- [ ] **Play SD** launches the AWT path without crash.
- [ ] First run: runtime/asset unpack shows progress and completes; game launches afterward.

## Compose home (ScapeLauncher)
- [ ] Home renders (title, Play HD, Play SD, Settings).
- [ ] Progress indicator appears while unpack/download tasks run and clears when done.
- [ ] Tapping Play while a task is running shows the "tasks in progress" toast, not a launch.

## Compose settings (SettingsActivity)
- [ ] Settings opens from the home button.
- [ ] Each toggle persists across app restart.
- [ ] Each slider persists across app restart.
- [ ] "Custom Java arguments" typing is smooth (no per-keystroke stall) and persists.
- [ ] A changed setting is honored by the game on next launch.
- [ ] "Edit on-screen controls" opens the controls editor.
- [ ] "Advanced" opens the legacy dialog (renderer, runtime, plugins, import).

## Missing-storage screen
- [ ] When storage is unavailable, the Compose alert screen renders (image + text).

## Import flows (if changed)
- [ ] Importing a benign plugin .zip extracts into plugins/ and appears in the list.
- [ ] Importing a config.json replaces config and prompts restart.
```

**Verify**: `test -f docs/verification/device-smoke-checklist.md && echo OK` → `OK`.

### Step 2: Link it from the repo's contributor docs
Add a one-line pointer to the checklist in root `CLAUDE.md` under its verification note (search for "physical device"), e.g.: "Device smoke checklist: `docs/verification/device-smoke-checklist.md`." Do not restructure the file.

**Verify**: `grep -n "device-smoke-checklist" CLAUDE.md` → one match.

## Test plan
This plan *is* the test-plan infrastructure. No code tests to add. Verification is that the two files exist and are linked (Done criteria).

## Done criteria
- [ ] `docs/verification/device-smoke-checklist.md` exists with the launch + Compose sections
- [ ] `CLAUDE.md` links to it
- [ ] `git status` shows only these two files changed
- [ ] `plans/README.md` row updated

## STOP conditions
- Root `CLAUDE.md` does not exist or has no verification section to link from — STOP and ask where the pointer should live (do not create a competing doc).

## Maintenance notes
- Whoever executes Plans 001–005 should run the relevant checklist sections on-device and record pass/fail in the PR.
- Future: if the team decides automated coverage is worth it, the highest-value first target is a Robolectric/unit test around `PreferencesRepository` key round-tripping and `ProgressRepository` state mapping (pure JVM, no GL/JNI) — noted here, not built in this plan.
