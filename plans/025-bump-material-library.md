# Plan 025: Bump the `material` (Material Components) library off its 2022 pin

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 8ee361ea1..HEAD -- gradle/libs.versions.toml app_pojavlauncher/build.gradle app_pojavlauncher/src/main/res/values`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P3
- **Effort**: S (the bump itself) / M (if it surfaces theme regressions requiring device-level triage)
- **Risk**: MED
- **Depends on**: none
- **Category**: migration
- **Planned at**: commit `8ee361ea1`, 2026-07-10

## Why this matters

`gradle/libs.versions.toml:26` pins `material = "1.5.0"` — Google's Material
Components for Android library, released February 2022 — while the rest of
the stack has moved well past it: `composeBom = "2024.09.03"`,
`lifecycle = "2.8.6"`, `activityCompose = "1.9.2"` (same file, lines 5-7).
A ~2.5-year-old transitive/direct UI-support library sitting underneath a
2024-era Compose Material 3 UI is a plausible source of resource-resolution
or theme-attribute surprises on newer OEM Android skins (compileSdk 35
devices), and it simply misses ~2.5 years of bugfixes. This plan is a
routine dependency hygiene bump — low code-diff, but it touches the app's
visual theme resolution, so it is scored MED risk and requires an on-device
check, not just a compile-gate pass.

## Current state

- `gradle/libs.versions.toml:25`, confirmed verbatim: `material = "1.5.0"`.
- Line 36 (same file): `material = { module = "com.google.android.material:material", version.ref = "material" }`.
- `app_pojavlauncher/build.gradle:208`: `implementation libs.material` — this is the only reference to the `material` library in the build file; it is on the active `implementation` path (not `testImplementation`/`debugImplementation`), so it ships in the release APK.
- Neighboring versions in the same file for context: `composeBom = "2024.09.03"` (line 5), `lifecycle = "2.8.6"` (line 6), `activityCompose = "1.9.2"` (line 7), `agp = "8.7.3"` (line 2), `kotlin = "2.0.21"` (line 3), `appcompat = "1.6.1"` (line 24).
- **Drift from the original finding, confirmed by direct inspection — read this before proceeding**: the original audit assumed the app's XML theme extends `Theme.MaterialComponents` and that the risk was "mixing a 2022 Material XML theme with 2024 Compose M3." That is **not quite what's in the repo**. The actual theme, `app_pojavlauncher/src/main/res/values/styles.xml:4`:
  ```xml
  <style name="AppTheme" parent="@style/Theme.AppCompat.NoActionBar">
  ```
  extends **`Theme.AppCompat.NoActionBar`**, not any `Theme.MaterialComponents.*` variant. A repo-wide search
  (`grep -rln "Theme.MaterialComponents" app_pojavlauncher/src/main/res/values*/*.xml`) found **no matches**, and
  a search for direct Material widget usage (`grep -rln "MaterialButton\|MaterialCardView\|com.google.android.material" app_pojavlauncher/src/main/java app_pojavlauncher/src/main/res`) also found **no matches** — this app does not appear to use Material Components widgets or theme attributes directly anywhere in its own XML/Java/Kotlin.
  The one theme-relevant line that plausibly needs `material` at runtime is
  `styles.xml:10`: `<item name="preferenceTheme">@style/PreferenceThemeOverlay.v14.Material</item>` — this style is provided by `androidx.preference` (used via `libs.androidx.preference` in `build.gradle:193`), which itself depends on `com.google.android.material:material` for that overlay's resolution. So the realistic risk surface for this bump is narrower than originally framed: it is about `androidx.preference`'s Material-styled preference screens (`SettingsActivity`'s "Advanced" legacy dialog and any `PreferenceFragment`-based UI, per `CLAUDE.md`'s architecture notes on `prefs/`), not a `Theme.MaterialComponents` attribute clash. Adjust expectations and the device-smoke focus accordingly (Step 3 below reflects this).

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Confirm current version | `grep -n 'material = "1.5.0"' gradle/libs.versions.toml` | one match, before the bump |
| Check latest 1.12.x (and current stable) release | check https://mvnrepository.com/artifact/com.google.android.material/material or https://maven.google.com/web/index.html#com.google.android.material:material | latest 1.12.x version number |
| Build gate (Docker) | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` | `BUILD SUCCESSFUL` |

## Scope

**In scope (the only file you should modify for the version bump):**
- `gradle/libs.versions.toml` (the `material` version string only)

**Out of scope:**
- Any other dependency version in the same file (composeBom, lifecycle, activityCompose, appcompat, etc.) — this plan is scoped to `material` only; do not opportunistically bump neighbors.
- `styles.xml` or any theme XML — do not "fix forward" by rewriting the theme parent unless a build/runtime failure in Step 2 specifically requires it (see STOP conditions); if so, treat that as a signal to stop and report, not a green light to redesign the theme.
- Adding new Material Components widget usage — this plan only changes the dependency version, not what uses it.

## Git workflow

- Branch: `advisor/025-bump-material-library`
- One commit, conventional-commit style matching `git log` (e.g. `chore: bump material to <new-version>`).
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Determine the target version

Check the latest stable release in the 1.12.x line of
`com.google.android.material:material` (as of this writing, 1.12.0 is the
latest stable; confirm against Maven Central/Google's Maven repo since a
newer patch may exist by execution time). If 1.12.x fails to build cleanly
in Step 2, fall back to an intermediate version (e.g. 1.9.x or 1.10.x) and
retry — bump incrementally rather than jumping straight back to 1.5.0's
neighborhood.

**Verify**: you have a specific target version string (e.g. `1.12.0`) before editing anything.

### Step 2: Bump the version and build

Edit `gradle/libs.versions.toml:25` from `material = "1.5.0"` to the target
version, e.g. `material = "1.12.0"`. Do not touch the `[libraries]` entry
at line 36 — it already references `version.ref = "material"` and needs no
change.

```
docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder \
  bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug --stacktrace"
```

**Verify**: `BUILD SUCCESSFUL`. If the build fails (e.g. a resource-linking error, a `Theme.MaterialComponents` requirement newly enforced by the bumped library, or a min-compileSdk mismatch), retry with a smaller version increment per Step 1's fallback guidance. If two version attempts both fail to build, treat this as a STOP condition (see below) rather than trying every intermediate version indefinitely.

### Step 3: Device-verify the screens that plausibly depend on `material`

Per the "Current state" drift note, the realistic risk surface is
`androidx.preference`'s `PreferenceThemeOverlay.v14.Material`-styled UI, not
a `Theme.MaterialComponents` attribute clash (this app's theme doesn't use
that parent). Install the rebuilt debug APK and, following
`docs/verification/device-smoke-checklist.md`'s existing structure, check:
- The launcher home screen (`ScapeLauncher`) renders with no crash and no visibly broken widget styling.
- **Settings → "Advanced"** — the legacy dialog/preference-based screen (per `CLAUDE.md`: "Advanced" opens the legacy dialog: renderer, runtime, plugins, import) — since this is the most likely path to actually exercise `PreferenceThemeOverlay.v14.Material`-derived styling.
- Any other `Preference`/`PreferenceFragment`-based screen reachable from Settings.
- `adb logcat` during the above shows no `Resources$NotFoundException`, `InflateException`, or `Theme` attribute-resolution `FATAL EXCEPTION`.

**Verify**: no crash, no attribute-resolution exceptions in logcat, screens visually intact.

## Test plan

There is no unit-test coverage for UI theming in this repo (`CameraPanTest`
is unrelated pure logic) — verification here is build-gate + on-device
smoke, not new automated tests. Do not attempt to add a Robolectric/theme
test as part of this plan; that would be new test infrastructure, out of
scope for a dependency bump.

- Verification: Docker `assembleDebug` → `BUILD SUCCESSFUL`, plus the
  device-smoke steps in Step 3 above, all passing.

## Done criteria

Machine-checkable (build) + device-checked (theme) — ALL must hold:

- [ ] `grep -n 'material = "1\.5\.0"' gradle/libs.versions.toml` → no match (old version gone)
- [ ] `grep -n 'material = "' gradle/libs.versions.toml` → shows the new target version
- [ ] Docker `assembleDebug` command → `BUILD SUCCESSFUL`
- [ ] Device smoke: launcher home screen and Settings → Advanced (legacy preference dialog) open with no crash and no theme-attribute exception in `adb logcat`
- [ ] `git status` shows only `gradle/libs.versions.toml` modified
- [ ] `plans/README.md` status row for Plan 025 updated (include which version was landed on, since it may differ from the initially-targeted 1.12.x if a fallback was needed)

## STOP conditions

Stop and report back (do not improvise) if:

- Two different version attempts (e.g. 1.12.x and one fallback) both fail
  to build — report the specific build error rather than continuing to
  guess at versions.
- Any build error explicitly demands the app's theme parent become
  `Theme.MaterialComponents.*` (i.e. the new `material` version enforces a
  `MaterialComponents`-derived theme at build/inflate time, since AppCompat
  and MaterialComponents themes aren't always drop-in compatible) — this
  would mean the bump requires a theme migration, which is a materially
  larger and riskier change than this plan's scope; stop and report instead
  of rewriting `styles.xml`.
- The device-smoke step in Step 3 shows a crash or attribute-resolution
  exception tied to the bump — report the exact logcat exception and the
  version landed on; do not silently revert to 1.5.0 without reporting why.

## Maintenance notes

- If a theme migration to `Theme.MaterialComponents` is ever undertaken
  (e.g. to adopt Material widgets directly), that is a separate, larger
  plan — this bump does not attempt it and intentionally leaves
  `styles.xml`'s `Theme.AppCompat.NoActionBar` parent untouched.
- The corrected understanding from this plan (no direct Material widget/theme
  usage in-repo; the real dependency is `androidx.preference`'s
  `PreferenceThemeOverlay.v14.Material`) should inform any future decision
  about whether `material` is even still needed as a direct dependency, or
  whether it could be left purely transitive via `androidx.preference` —
  that investigation is out of scope here but worth flagging for whoever
  next touches this dependency.
