# Phase 2 — "Full Kotlin + Compose Shell" Decomposition

Phase 2 (as chosen: full Kotlin + Compose shell, manual service-locator DI) is too large for one plan. It is split into six independently device-testable sub-plans. Each ends with a **hardware smoke test** — CI can only prove compilation, not that the launcher runs.

## The migration boundary (from the shell survey)

The manifest already runs the shell in process `:launcher` and the HD game in `:game`. That boundary **is** the migration boundary:

- **Compose/Kotlin targets (`:launcher`):** `ScapeLauncher` (home), `MyDialogFragment` + the `prefs/screens/*` settings tree, `MissingStorageActivity`, `FatalErrorActivity`, `TestStorageActivity` bootstrap; the async/progress system (`tasks/AsyncAssetManager`, `progresskeeper/*`, `com.kdt.mcgui.ProgressLayout`); global state in `LauncherPreferences`, `Tools.DIR_*`, `PojavApplication.sExecutorService`, `ExtraCore`.
- **Untouchable View/JNI/GL:** `MainActivity` (`:game`) + `activity_basemain.xml`, `JavaGUILauncherActivity` (AWT) + its layout, `GLFWGLSurface`, `AWTCanvasView`, `Touchpad`, `customcontrols/*`, `CallbackBridge`, `AWTInputBridge`, `utils/JREUtils`, `Tools.launchGLJRE`, `multirt/*`, `jni/`, `jniLibs/`, `rt4.jar`. The Compose home reaches these **only via `startActivity` intents**, exactly as today.

## Design decisions

- **DI = manual service-locator** (`AppContainer`, built in `PojavApplication`). No Hilt/KSP.
- **Preferences = SharedPreferences-backed repository, NOT DataStore.** `LauncherPreferences` keys are read by the `:game` process at launch; keep SharedPreferences (same keys, same semantics) and wrap it in a Compose-friendly `PreferencesRepository` exposing `StateFlow`. `LauncherPreferences` static fields remain the game process's read path.
- **Progress = `StateFlow` repository** mirroring `ProgressKeeper`, introduced non-invasively (ProgressKeeper keeps working; the repo observes it) so slices stay small.
- **Compose is launcher-only.** The `:game`/AWT activities keep the AppCompat `AppTheme`; a separate Compose `LauncherTheme` (M3) applies to the shell.

## Sub-plans (execute in order; each is a merge unit with a device smoke test)

| Sub-plan | Scope | Risk | Device test |
|---|---|---|---|
| **2a** (this batch) | Compose/coroutine deps + Compose compiler plugin; `AppContainer` service-locator; `ProgressRepository` (StateFlow over ProgressKeeper); a minimal `LauncherTheme`; convert `MissingStorageActivity` to Compose as the first real screen | Low | App builds; missing-storage screen renders; normal launch still works |
| **2b** | `PreferencesRepository` over existing SharedPreferences (all ~35 keys); `LauncherPreferences` reads through it; no UI change yet | Medium | Every setting still loads; game reads correct values |
| **2c** | Compose **home** (`ScapeLauncher`): Play HD/SD (unchanged intents), settings entry, progress bar bound to `ProgressRepository`, branding | Medium | Home renders; both launch paths work; progress shows during unpack |
| **2d** | Compose **settings** replacing `MyDialogFragment` + pref tree (video/control/java/misc/experimental), plugin list mgmt, config/plugin file import (`ActivityResultContracts`) | High | Each setting persists + is honored by the game; plugin toggle + imports work |
| **2e** | `tasks/AsyncAssetManager` → coroutines (`AppContainer` scope/dispatchers) + Flow progress; retire `sExecutorService` usage in the shell | Medium | Fresh-install unpack of runtime/components/plugins works |
| **2f** | M3 `LauncherTheme` polish; `FatalErrorActivity` + `TestStorageActivity` to Compose; remove dead View layouts | Low | Error dialog + bootstrap flow render |

Each sub-plan gets its own `docs/superpowers/plans/2026-07-07-phase2<letter>-*.md`. Start with **2a**.
