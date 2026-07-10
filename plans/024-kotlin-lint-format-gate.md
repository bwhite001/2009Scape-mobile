# Plan 024: Add a Kotlin-only lint/format gate (ktlint or Spotless)

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 8ee361ea1..HEAD -- app_pojavlauncher/build.gradle gradle/libs.versions.toml .github/workflows/android.yml app_pojavlauncher/src/main/java/net/kdt/pojavlaunch`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none (pairs well with Plan 009's CI test step — add this alongside it in the same JDK-17 leg)
- **Category**: dx
- **Planned at**: commit `8ee361ea1`, 2026-07-10

## Why this matters

This repo now has real, in-tree Kotlin (`ScapeLauncher.kt`, `SettingsActivity.kt`,
`MissingStorageActivity.kt`, the `di/` and `ui/` packages), but there is
**zero style enforcement** on it: no `.editorconfig`, no ktlint/detekt/Spotless
config, no pre-commit hook, and the Android lint block is explicitly
non-blocking (`abortOnError false`). The repo's own stated convention — "match
the surrounding style, keep changes minimal and idiomatic" — has nothing
mechanical backing it for the one part of the codebase (Kotlin) where new
code is actively being added. Without a gate, style drift across the Kotlin
files compounds silently and reviewers must catch every formatting nit by
eye. This plan adds a Kotlin-only, CI-wired check — deliberately scoped away
from the vestigial Java trees so it can never trigger a mass-reformat of
inherited PojavLauncher code.

## Current state

- No style-enforcement config exists anywhere in the repo. Confirmed:
  ```
  find . -iname ".editorconfig" -o -iname "*detekt*" -o -iname "*ktlint*"
  ```
  (excluding `.git/`) returns **no output**.
- `app_pojavlauncher/build.gradle:185-188`, confirmed verbatim:
  ```groovy
      lint {
          abortOnError false
      }
  ```
  This is Android Lint (Java/XML/resources), not a Kotlin style tool, and it is explicitly non-blocking — it doesn't help here.
- Kotlin files currently in-tree, confirmed via `find app_pojavlauncher/src -name "*.kt"` (11 files, all under `net/kdt/pojavlaunch/`):
  - `SettingsActivity.kt`
  - `ScapeLauncher.kt`
  - `MissingStorageActivity.kt`
  - `di/AppContainer.kt`
  - `di/ProgressRepository.kt`
  - `di/PreferencesRepository.kt`
  - `ui/rs/RsChrome.kt`
  - `ui/rs/ScapeLogo.kt`
  - `ui/rs/RsControls.kt`
  - `ui/theme/RsColors.kt`
  - `ui/theme/LauncherTheme.kt`
- Plugin/version setup, confirmed in `gradle/libs.versions.toml`:
  ```
  agp = "8.7.3"
  kotlin = "2.0.21"
  ```
  (lines 2-3), and `app_pojavlauncher/build.gradle` applies Kotlin via
  `alias libs.plugins.kotlin.android` (line 3) and Compose compiler via
  `alias libs.plugins.compose.compiler` (line 4). Any lint/format plugin
  added must be compatible with AGP 8.7.3 + Kotlin 2.0.21.
- CI attach point: `.github/workflows/android.yml`'s JDK-17 leg (see Plan
  009's "Current state" for the full step list) is where a
  `ktlintCheck`/`spotlessCheck` step belongs, ideally adjacent to the
  `testDebugUnitTest` step from Plan 009 (either plan can land first; if
  both land, put the lint/format step next to the test step for locality).

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Confirm no existing config | `find . -iname ".editorconfig" -o -iname "*detekt*" -o -iname "*ktlint*" \| grep -v /.git/` | no output (before this plan) |
| Build gate (Docker) | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` | `BUILD SUCCESSFUL` |
| Lint/format check (Docker, after Step 1-2) | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:ktlintCheck"` (or `spotlessCheck`, matching whichever tool you pick) | exit 0, green, on the existing 11 Kotlin files |

## Scope

**In scope:**
- `app_pojavlauncher/build.gradle` (add the ktlint/Spotless plugin block, scoped to Kotlin sources only)
- `gradle/libs.versions.toml` (add the plugin version)
- `.github/workflows/android.yml` (add the check step to the JDK-17 leg)
- Optionally `.editorconfig`, if the tool benefits from one — scope any Kotlin-specific rules in it to `[*.kt]`, do not set repo-wide Java formatting rules.

**Out of scope (do NOT do in this plan):**
- Reformatting any `.java` file, including the vestigial PojavLauncher trees
  (`multirt/`, `jni/`, `jniLibs/`, `org/lwjgl/**`, `AWTInputEvent.java`,
  `jre_lwjgl3glfw/`, `gl4es/`) — the tool/config must be scoped so it
  **cannot** touch these, e.g. via ktlint's Gradle plugin only ever
  targeting `**/*.kt`, or Spotless's `kotlin { target '...' }` block
  explicitly listing Kotlin file globs, not a repo-wide catch-all.
- Reformatting the 11 existing Kotlin files beyond what's needed to pass
  the check with a stock/default ruleset (see Step 3 — if this needs
  extensive reformatting, that becomes a STOP condition, not something to
  push through).
- Any change to `abortOnError false` in the Android `lint {}` block —
  unrelated tool, leave it alone.

## Git workflow

- Branch: `advisor/024-kotlin-lint-format-gate`
- Commit per logical unit (e.g. one commit adding the plugin + config, one
  for any minimal reformatting the check requires, one for the CI step),
  conventional-commit style matching `git log` (e.g.
  `dx: add ktlint check scoped to Kotlin sources`).
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Choose and add the plugin

Prefer **ktlint** (via the `org.jlleitschuh.gradle.ktlint` Gradle plugin) for
its Kotlin-only default scope and low config surface, or **Spotless**
(`com.diffplug.spotless`) if you want a single tool that could also format
other file types later (but configure it for Kotlin only in this plan).
Pick whichever has better current AGP 8.7.3 / Kotlin 2.0.21 / Gradle 8.9
compatibility — check the plugin's latest release notes before pinning a
version, since this plan should not guess a version that turns out
incompatible.

Add the plugin version to `gradle/libs.versions.toml`'s `[versions]` and
`[plugins]` blocks, following the existing pattern (see `agp`/`kotlin`
entries, lines 2-3 and 57-58). Apply it in `app_pojavlauncher/build.gradle`
via `alias libs.plugins.<name>`, matching how `kotlin-android` and
`compose-compiler` are already applied (lines 2-4).

Configure the tool to target **only** Kotlin files under
`app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/**/*.kt` and
`app_pojavlauncher/src/test/**/*.kt` (if any Kotlin test files exist) —
consult the chosen tool's docs for the exact include/exclude syntax
(ktlint's Gradle plugin scans `**/*.kt` by default within the module it's
applied to, which is already scoped correctly since this module has no
Java-formatting concern from ktlint; Spotless requires an explicit
`target` glob and this must be set to `'src/**/*.kt'` or narrower, never a
bare Java-inclusive glob).

**Verify**: `grep -n "ktlint\|spotless" gradle/libs.versions.toml app_pojavlauncher/build.gradle` → matches in both files.

### Step 2: Run the check locally and fix only what's needed

```
docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder \
  bash -lc "cd /project && ./gradlew :app_pojavlauncher:ktlintCheck"
```
(substitute `spotlessCheck` if that's the tool chosen)

If it fails on the 11 existing Kotlin files, first try the tool's own
auto-format task (`ktlintFormat` / `spotlessApply`) to bring them into
compliance mechanically, then re-run the check. Only hand-edit if the
auto-formatter can't resolve something.

**Verify**: the check command exits 0 with no violations reported.

### Step 3: Wire the check into CI

Add a step to `.github/workflows/android.yml`'s JDK-17 leg (see Plan 009's
Current state for the full step list; place this near the
`testDebugUnitTest` step if Plan 009 has landed, otherwise after "Build
Debug APK"), matching the file's existing bare-`gradle` invocation style:

```yaml
      - name: Check Kotlin style
        run: |
          gradle :app_pojavlauncher:ktlintCheck --stacktrace
```

**Verify**: `grep -n "ktlintCheck\|spotlessCheck" .github/workflows/android.yml` → one match. YAML still parses (`python3 -c "import yaml; yaml.safe_load(open('.github/workflows/android.yml'))"` → exit 0).

### Step 4: Confirm the build gate still passes

**Verify**: `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` → `BUILD SUCCESSFUL`.

## Test plan

This is tooling/config, not application logic — no new unit tests apply.
The "test" is the lint/format check itself:
- Verification: `./gradlew :app_pojavlauncher:ktlintCheck` (via Docker) →
  exit 0, green, over exactly the 11 Kotlin files listed in "Current state"
  (confirm no `.java` file appears in the tool's scanned-file output/log).

## Done criteria

Machine-checkable. ALL must hold:

- [ ] `./gradlew :app_pojavlauncher:ktlintCheck` (or `spotlessCheck`) exits 0 in the Docker image, on the existing Kotlin sources
- [ ] The check's own file-scan log (or a manual `grep` of its config) shows zero `.java` files were touched/scanned
- [ ] `.github/workflows/android.yml` contains the check step; YAML still valid
- [ ] Docker `assembleDebug` still exits `BUILD SUCCESSFUL`
- [ ] `git status` shows only `app_pojavlauncher/build.gradle`, `gradle/libs.versions.toml`, `.github/workflows/android.yml`, and (if reformatting was needed) the 11 Kotlin files under `net/kdt/pojavlaunch/**/*.kt` — no `.java` files modified
- [ ] `plans/README.md` status row for Plan 024 updated

## STOP conditions

Stop and report back (do not improvise) if:

- The existing 11 Kotlin files have enough violations that passing the
  check requires reformatting beyond what the tool's auto-formatter can fix
  mechanically (i.e. it would require semantic-adjacent hand-editing of the
  Compose UI files) — in that case, STOP and report; the right move may be
  to ship the config with a **baseline/suppression file** for the existing
  violations (ktlint and Spotless both support this) rather than forcing a
  reformat under this plan's authority, but that decision needs a human
  call, not an autonomous one.
- Any chosen plugin version is incompatible with AGP 8.7.3 / Kotlin 2.0.21
  / Gradle 8.9 (build fails to even apply the plugin) — try one older/newer
  minor version; if none work cleanly, STOP and report rather than forcing
  a downgrade of AGP/Kotlin/Gradle to accommodate the lint tool (that would
  be a much larger, out-of-scope change).
- The tool's default include-glob cannot be scoped away from Java files
  without non-obvious config — STOP rather than guessing at an
  include/exclude pattern that might silently reformat inherited Java.

## Maintenance notes

- As more Kotlin is added (e.g. future Compose screens), it is
  automatically covered by this gate — no further wiring needed.
- If detekt (static analysis, not just formatting) is wanted later, it's a
  separate follow-up — this plan covers formatting/style only (ktlint or
  Spotless), not deeper static analysis rules.
- If a baseline/suppression file was needed per the STOP condition above,
  record here which violations were grandfathered and why, so a future
  cleanup plan can target them specifically.
