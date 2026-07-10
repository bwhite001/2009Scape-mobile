# Plan 007: Remove the broken `gradle-publish.yml` release workflow

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 8ee361ea1..HEAD -- .github/workflows/gradle-publish.yml *.gradle app_pojavlauncher/build.gradle jre_lwjgl3glfw/build.gradle`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: dx
- **Planned at**: commit `8ee361ea1`, 2026-07-10

## Why this matters

`.github/workflows/gradle-publish.yml` fires on every GitHub release and is
guaranteed to fail: it runs `./gradlew publish`, a Gradle task that does not
exist anywhere in this project (there is no `maven-publish`/`publishing {}`
block in any `build.gradle`), and even the preceding `./gradlew build` step
would fail because this workflow only sets up JDK 17 — it never installs
JDK 8 (needed for `:jre_lwjgl3glfw:build`), never installs the Android
SDK/NDK, and never runs `scripts/languagelist_updater.sh`, all of which the
real, working build (`.github/workflows/android.yml`) requires. The net
effect: every release produces a red "Gradle Package" workflow run, a false
failure signal that also misleadingly implies a GitHub Packages publish
pipeline exists. It does not. Deleting the dead workflow removes both the
noise and the misleading implication, at zero cost — nothing today depends
on it succeeding or its output.

## Current state

- `.github/workflows/gradle-publish.yml` (44 lines), confirmed in full:
  - Trigger: `on: release: types: [created]` (lines 10-12).
  - `- name: Set up JDK 17` (lines 24-30) — only JDK 17, no JDK 8 leg.
  - `- name: Build with Gradle` / `run: ./gradlew build` (lines 35-36).
  - `- name: Publish to GitHub Packages` / `run: ./gradlew publish` (lines 40-44), using `USERNAME`/`TOKEN` env vars that no `build.gradle` reads.
  - No step installs the Android SDK, NDK, or a second (JDK 8) toolchain, and no step runs `./scripts/languagelist_updater.sh` — all of which `android.yml` does (see below) and which an app/library build requires.
- Confirmed no publish config exists anywhere in the Gradle files:
  ```
  grep -rn "publishing\|maven-publish" --include="*.gradle" .
  ```
  returns **no matches** (checked against root `build.gradle`, `app_pojavlauncher/build.gradle`, `jre_lwjgl3glfw/build.gradle`).
- Root `build.gradle:4`: `defaultTasks 'buildHelp'` — confirming there is deliberately no single default build/publish task for this multi-module project; `./gradlew build` with no target module is not the project's supported entry point.
- The working build recipe lives in `.github/workflows/android.yml` (JDK 8 leg → `languagelist_updater.sh` + `:jre_lwjgl3glfw:build`, then JDK 17 leg → SDK/NDK install → `:app_pojavlauncher:assembleDebug`). This is the pattern to point to (in Maintenance notes only) if a real publish pipeline is ever wanted — this plan does **not** build one.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Confirm no publish config | `grep -rn "publishing\|maven-publish" --include="*.gradle" .` | no output |
| Build (Docker, no host JDK/SDK) | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` | `BUILD SUCCESSFUL`, APK at `app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk` |
| List workflows | `ls .github/workflows/` | only `android.yml` remains |

## Scope

**In scope (the only file you should modify):**
- `.github/workflows/gradle-publish.yml` (delete)

**Out of scope:**
- `.github/workflows/android.yml` — the working build; leave untouched.
- Any `build.gradle` — do not add a `publishing {}` block as part of this plan; that is a separate, deliberate decision (see Maintenance notes).

## Git workflow

- Branch: `advisor/007-remove-broken-gradle-publish-workflow`
- One commit, conventional-commit style matching `git log` (e.g. `feat:`, `fix:`, `docs:`) — use `ci: remove broken gradle-publish release workflow`.
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Confirm no publish config exists anywhere

Run:
```
grep -rn "publishing\|maven-publish" --include="*.gradle" .
```

**Verify**: command returns no output. If it returns any match, STOP (see STOP conditions) — do not delete the workflow.

### Step 2: Delete the broken workflow

```
git rm .github/workflows/gradle-publish.yml
```

**Verify**: `test ! -f .github/workflows/gradle-publish.yml && echo OK` → `OK`.

### Step 3: Confirm the working CI workflow is untouched

```
git status
git diff --stat -- .github/workflows/android.yml
```

**Verify**: `git status` shows only `.github/workflows/gradle-publish.yml` deleted; the `android.yml` diff is empty.

## Test plan

No code behavior changes — this is a CI-config deletion. There is nothing to unit test. Verification is structural (file removed, no other files touched) plus the standing build gate to prove the rest of the repo still builds.

- Verification: `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` → `BUILD SUCCESSFUL` (this workflow file has no effect on the Gradle build itself, but running the gate confirms nothing else broke).

## Done criteria

Machine-checkable. ALL must hold:

- [ ] `test ! -f .github/workflows/gradle-publish.yml` exits 0
- [ ] `ls .github/workflows/` shows only `android.yml`
- [ ] `grep -rn "publish" .github/workflows/` returns no matches
- [ ] `git status` shows only the one file deleted (no other files modified)
- [ ] Docker `assembleDebug` command above exits with `BUILD SUCCESSFUL`
- [ ] `plans/README.md` status row for Plan 007 updated

## STOP conditions

Stop and report back (do not improvise) if:

- Step 1's grep DOES find a `publishing {}` / `maven-publish` block anywhere — that means the workflow is (or was intended to be) real; do not delete it, report the finding instead.
- The code/config at the locations in "Current state" doesn't match what's in `.github/workflows/gradle-publish.yml` today (e.g. it now has a JDK 8 leg or SDK setup) — the workflow may have been fixed already; re-evaluate before deleting.
- `git rm` fails for any reason (e.g. file already absent) — report rather than guessing why.

## Maintenance notes

- If a real "publish a release artifact" pipeline is wanted later, model it on `android.yml`'s two-JDK + SDK/NDK setup (JDK 8 leg for `:jre_lwjgl3glfw:build`, then JDK 17 + SDK 35 + NDK 25.2.9519653 for `:app_pojavlauncher:assembleRelease` or similar), and add an explicit `publishing {}` block to the relevant `build.gradle` before wiring a `publish` step — this plan intentionally does not do that.
- No follow-up is required after this deletion; it is a pure removal with no replacement.
