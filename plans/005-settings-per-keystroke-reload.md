# Plan 005: Stop reloading all preferences on every settings write (UI jank/ANR)

> **Executor instructions**: Follow step by step; verify each step; obey STOP conditions; update `plans/README.md`.
>
> **Drift check (run first)**: `git diff --stat 0e75ab512..HEAD -- app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/di/PreferencesRepository.kt app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt` — on change, compare to "Current state"; mismatch = STOP.

## Status
- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: perf (regression introduced by the Compose settings migration)
- **Planned at**: commit `0e75ab512`, 2026-07-07

## Why this matters
The new Compose settings screen persists a preference through `PreferencesRepository`, whose `commit{}` calls `reloadLauncherPreferences()` → `LauncherPreferences.loadPreferences(context)` **after every write**. `loadPreferences` is heavy: it re-initializes path constants, reads ~40 keys, parses Java args, and enumerates installed runtimes from the filesystem (`MultiRTUtils.getRuntimes()`). The "Custom Java arguments" field writes on **every keystroke**, so typing triggers a full filesystem runtime scan per character — UI jank and ANR risk; toggles/sliders do the same on each change. The fix: writes should just write; the expensive static-reload should happen once when the user leaves the settings screen (the launch path reads the statics later, and the separate `:game` process reads SharedPreferences fresh at its own launch).

## Current state
`app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/di/PreferencesRepository.kt` — the write path:
```kotlin
fun putBoolean(key: String, value: Boolean) = commit { putBoolean(key, value) }
fun putInt(key: String, value: Int) = commit { putInt(key, value) }
fun putString(key: String, value: String?) = commit { putString(key, value) }

private inline fun commit(block: SharedPreferences.Editor.() -> Unit) {
    prefs.edit().apply(block).apply()
    reloadLauncherPreferences()      // <-- runs full loadPreferences on EVERY write
}

/** Keep the legacy static fields (read by the launch path) in sync after a write. */
fun reloadLauncherPreferences() {
    LauncherPreferences.loadPreferences(context)
}
```
`SettingsActivity.kt` — `JavaArgsRow` calls `repo.putString("javaArgs", it)` inside `OnValueChange` (every keystroke); `BoolRow`/`IntRow` call `putBoolean`/`putInt` on each change. `SettingsActivity` is an `AppCompatActivity`/`BaseActivity` with the standard `onStop` lifecycle hook available.

Convention: Kotlin, coroutines available; `PojavApplication.appContainer.preferencesRepository` is the singleton accessor.

## Commands you will need
| Purpose | Command | Expected |
|---|---|---|
| Build (local) | `./gradlew :app_pojavlauncher:assembleDebug` | `BUILD SUCCESSFUL` |
| Build (CI fallback) | push branch | Android CI green |

## Scope
**In scope:**
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/di/PreferencesRepository.kt`
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt`

**Out of scope:** `LauncherPreferences.java` (do not change what `loadPreferences` does); the legacy `MyDialogFragment`/PreferenceFragment path; `ProgressRepository`.

## Git workflow
- Branch: `advisor/005-settings-reload-hotpath`
- One commit, e.g. `perf(settings): reload prefs on screen exit, not per write`.

## Steps

### Step 1: Remove the per-write reload
In `PreferencesRepository.kt`, drop the `reloadLauncherPreferences()` call from `commit`:
```kotlin
private inline fun commit(block: SharedPreferences.Editor.() -> Unit) {
    prefs.edit().apply(block).apply()
}
```
Keep the public `reloadLauncherPreferences()` method as-is (callers will invoke it explicitly).

**Verify**: `grep -n "reloadLauncherPreferences" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/di/PreferencesRepository.kt` → the method still exists but is **not** called inside `commit`.

### Step 2: Reload once when leaving the settings screen
In `SettingsActivity.kt`, add an `onStop` override that reloads the static bank so the launch path (this process) sees the new values:
```kotlin
override fun onStop() {
    super.onStop()
    PojavApplication.appContainer.preferencesRepository.reloadLauncherPreferences()
}
```
(Place it in the `SettingsActivity` class body, alongside `onCreate`.)

**Verify**: `./gradlew :app_pojavlauncher:assembleDebug` → `BUILD SUCCESSFUL` (or CI green).

### Step 3 (optional, only if Step 1–2 verified): debounce the text field
Optional polish to avoid a SharedPreferences write per keystroke in `JavaArgsRow`: keep the local `text` state updating on every change, but persist in a `LaunchedEffect(text)` that `delay(400)`s before calling `repo.putString("javaArgs", text)`. Skip this if unsure — Steps 1–2 already remove the expensive work.

**Verify**: build succeeds.

## Test plan
No harness (Plan 006). Manual on-device:
- Open Settings, type a long string into "Custom Java arguments" — typing must be smooth (no per-character stall).
- Toggle several switches and move sliders — no lag.
- Leave Settings (back), then relaunch the game (Play HD/SD) — confirm a changed setting (e.g. a slider that affects controls, or `javaArgs`) is actually applied, proving the `onStop` reload propagated to the launch path.

## Done criteria
- [ ] `commit` no longer calls `reloadLauncherPreferences`
- [ ] `SettingsActivity.onStop` calls `reloadLauncherPreferences()` exactly once
- [ ] `./gradlew :app_pojavlauncher:assembleDebug` exits 0 (or CI green)
- [ ] On-device: typing in Java-args is smooth; a changed setting is honored after leaving settings and launching
- [ ] `git status` shows only the two in-scope files modified
- [ ] `plans/README.md` row updated

## STOP conditions
- `PreferencesRepository.kt` / `SettingsActivity.kt` don't match the "Current state" (drift — the Phase 2 shell may have changed).
- After the change, a setting altered in the Compose screen is NOT honored by the game on next launch (would mean some read path relied on the per-write reload) — STOP and report; the reload may also need to fire in `onPause`, or a specific caller needs it.

## Maintenance notes
- Reviewer: confirm the launch entry points that read `LauncherPreferences.*` statics run after `SettingsActivity.onStop` (they do — launching happens from the home screen, which is re-entered after settings closes) and that the `:game` process reads SharedPreferences fresh at its own start.
- If settings are later split across multiple Compose screens/activities, each must reload on exit, or (better) move the launch path to read through `PreferencesRepository` directly and retire the static bank.
