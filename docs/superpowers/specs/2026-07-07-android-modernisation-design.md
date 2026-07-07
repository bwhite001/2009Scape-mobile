# 2009Scape-mobile — Android Modernisation (Design Spec)

**Date:** 2026-07-07
**Repo:** `bwhite001/2009Scape-mobile` (a fork of PojavLauncher, re-skinned to run the 2009Scape RT4-Client)
**Decisions locked in brainstorming:**
- **Hard fork** — no obligation to stay mergeable with upstream PojavLauncher/RT4-Client. Kotlin, restructuring, and shell rewrites are all permitted.
- **Primary goal: maintainability / dev velocity** — a clean, understandable launcher shell so future features are cheap to add. Not modernisation for its own sake.
- **UI depth: full Kotlin + Compose shell** — the pre-game launcher screens become Kotlin + Jetpack Compose. Maximum maintainability payoff, accepted as the largest cost.

> This spec supersedes an earlier generic "upgrade plan" draft. That draft was written against generic PojavLauncher assumptions; roughly half its premises did not hold for this fork (see **Appendix A**). Everything below is grounded in the actual code as of this date.

---

## 1. Verified current-state baseline

Measured from the repo, not assumed:

| Dimension | Actual value |
|---|---|
| applicationId / namespace | `net.kdt.pojavlaunch` (debug suffix `.debug`) |
| AGP / Gradle | **7.4.2 / 7.6.1** |
| compileSdk / targetSdk / minSdk | **33 / 33 / 21** |
| buildToolsVersion | **33.0.2** |
| Build scripts | Groovy; **no** version catalog |
| Java compile level | `sourceCompatibility`/`targetCompatibility` = **1.8** |
| Language | **100% Java. Zero Kotlin.** No Compose/Hilt/Room/ViewModel/coroutine/Navigation deps. |
| Launcher entry | `TestStorageActivity` holds `MAIN`/`LAUNCHER` → routes to `ScapeLauncher` (home). "Play HD" → `MainActivity` (GL). "Play SD" → `JavaGUILauncherActivity` (AWT). |
| GL surface owner | `MainActivity` + `GLFWGLSurface` / `AWTCanvasView`. **There is no `GameActivity`.** |
| Async model | Custom `Thread` / `Handler` + a progress bus (`ProgressKeeper` / `ExtraCore`). **No `AsyncTask` anywhere.** |
| Foreground service | `net.kdt.pojavlaunch.services.ProgressService` (real FGS: `startForegroundService` + notification via `Tools.buildNotificationChannel`). No AudioService/OpenAL FGS. |
| Storage | `getExternalFilesDir()` for API ≥29; `WRITE/READ_EXTERNAL_STORAGE` already capped `maxSdkVersion=28`. Scoped-storage migration **already done.** |
| Networking | `utils/DownloadUtils.java` uses raw `HttpURLConnection`. No OkHttp. |
| Native hook lib | `jni/xhook/` (**not** bytehook). |
| Theme | `Theme.AppCompat.NoActionBar`. |
| Back handling | 1 `onBackPressed`; `enableOnBackInvokedCallback` not set. |

## 2. Load-bearing components — do NOT refactor

These boot the embedded JVM inside a GL surface; touching them risks renderer/runtime breakage with no maintainability upside.

| Path / component | Reason |
|---|---|
| `gl4es/` (C, `Android.mk`) | OpenGL → GLES translation layer |
| `jre_lwjgl3glfw/` | LWJGL2→3/GLFW JNI bridge; also emits an app asset on build |
| `app_pojavlauncher/src/main/jni/` (`Android.mk`, `pojavexec`, `awt_*`, `tinywrapper`/`angle_gles2`, **`xhook`**) | Native JVM host + GL/AWT bridges + function hooking |
| `MainActivity` + `GLFWGLSurface` / `AWTCanvasView` GL/AWT path | The surface that owns the in-process JVM. Compose cannot host it — it stays View-based. |
| `Tools.launchGLJRE()` + `JREUtils.launchJavaVM` + `rt4.jar` classpath/`-D` wiring | The client boot contract (`rt4.client`, `config.json`, plugins) |
| ARM/ARM64 ABI restriction | JRE binaries are architecture-specific |

**Note:** the embedded OpenJDK runs Java 8 bytecode at runtime. Raising the *app's* compile target to Java 17 (Phase 1) does not affect the game runtime.

## 3. Phased plan

Each phase merges to `master` only after a smoke test on a physical ARM64 device (x86 emulator is unsupported by design). Branch chain:
`refactor/p1-build` → `p2-kotlin-arch` → `p3-compliance` → `p4-compose-ui` → `p5-network`.

### Phase 1 — Build foundation (highest value / lowest risk)
Everything else depends on this.
- Gradle wrapper 7.6.1 → **8.x**; AGP 7.4.2 → **8.x**.
- `compileSdk`/`targetSdk` 33 → **35**; **minSdk stays 21**; bump `buildToolsVersion` to match.
- `compileOptions` — **stays at 1.8** (revised during execution). Raising it to 17 broke `compileDebugJavaWithJavac`: the app ships compile-time stubs in platform packages (`dalvik.annotation.optimization.CriticalNative`, `com.oracle.dalvik.VMLauncher`), and Java ≥9 source mode enables the module system, which rejects redefining a platform-owned package ("package exists in another module: java.base"). AGP 8 only needs the *toolchain* on JDK 17 (satisfied); the source level does not have to be 17. Raising it later requires handling those stubs. Kotlin `jvmTarget` matches at `1.8`.
- Add `org.gradle.jvmargs=-Xmx4g` — packaging the large multi-ABI APK (uncompressed jniLibs + assets) OOMs on Gradle's 512m default heap.
- Add `gradle/libs.versions.toml` (version catalog); migrate module deps to it.
- Add the **Kotlin Android plugin** + enable Java/Kotlin interop (Kotlin compiles alongside Java; no code converted yet).
- CI: install SDK platform 35 / build-tools 35.0.0 / pinned NDK 25.2.9519653 explicitly (the runner does not ship the exact pinned NDK), and bump the pinned Gradle to 8.9.
- Kotlin DSL (`*.gradle.kts`) conversion — **optional**, file-by-file; the `ndkBuild` + `Android.mk` block translates directly.
- **Acceptance:** the GitHub Actions pipeline builds and uploads the debug APK on the upgraded toolchain (achieved — run on `refactor/p1-build`). Device HD/SD launch on API 21 & 35 remains to be confirmed by the user on hardware.

### Phase 2 — Kotlin + architecture
This is *introducing* modern architecture, not migrating off AsyncTask (there is none).
- Replace the custom `Thread`/`Handler` + `ProgressKeeper`/`ExtraCore` async in `tasks/` (asset unpack, JRE unpack, downloads) with **Kotlin coroutines** (`Dispatchers.IO` + `Flow` for progress).
- Introduce a `LauncherViewModel` (`StateFlow` for launch/download/unpack state) to replace `static`-field state in `ScapeLauncher` / `MainActivity` shell logic.
- Dependency injection: adopt **Hilt** for the shell (ViewModels, download/asset managers). *Open question — see §5.*
- `GameActivity` equivalent exemption: `MainActivity`'s GL boot path keeps its own threading; do not coroutine-ify the surface/JVM handoff.
- **Acceptance:** asset/JRE/download flows run on coroutines; shell state survives config change; HD + SD launch unaffected.

### Phase 3 — Platform compliance (small, targeted)
- **POST_NOTIFICATIONS** runtime request (API 33+) for `ProgressService`'s notification (currently posts without requesting → silent on API 33+).
- **`foregroundServiceType`** declaration for `ProgressService` (API 34 requirement) + typed `startForeground`. Likely `dataSync` (it tracks download/unpack tasks).
- **Predictive back** — set `android:enableOnBackInvokedCallback="true"` and migrate the one `onBackPressed` to `OnBackInvokedCallback`.
- **Explicitly NOT in scope:** scoped-storage migration — already handled (API≥29 uses `getExternalFilesDir`; legacy perms already `maxSdkVersion=28`).
- **Acceptance:** clean install on an API 34 device shows progress notifications, FGS starts without crash, back gesture works.

### Phase 4 — Compose UI shell (full Kotlin + Compose)
Compose only for pre-game screens; the GL/AWT surface stays View-based.
- Material 3 theme: `Theme.AppCompat.NoActionBar` → `Theme.Material3.DayNight.NoActionBar` (full M3 token set) for the app shell.
- Enable Compose; migrate launcher screens to Kotlin + Compose in this order (lowest coupling first):
  1. Settings dialog (`MyDialogFragment` content)
  2. HD/SD client picker + home (`ScapeLauncher`)
  3. First-run / progress UI (`ProgressLayout` surfaces)
- `MainActivity` GL path and `JavaGUILauncherActivity` AWT canvas remain View/`SurfaceView` hosts; use `ComposeView` interop only for chrome around them if needed.
- **Acceptance:** shell screens render in Compose under M3; navigating them still launches the game correctly on ARM64.

### Phase 5 — Network + observability
- Replace `DownloadUtils` `HttpURLConnection` with **OkHttp** (timeouts, retry, streaming to file).
- **SHA-256 integrity check** on downloaded/unpacked client artifacts (`rt4.jar`, plugin zips) before launch; meaningful error on mismatch.
- **Crash reporting: Sentry** (`sentry-android`, NDK integration) — captures both JVM and native `.so` crashes; no Google Play Services dependency (fits an open-source fork). DSN via manifest meta-data.
- **Acceptance:** artifacts verified before launch; failed download surfaces a real error; a forced test crash reports (Java + native) to Sentry.

## 4. Dependencies to add (indicative, pinned in the catalog)
Kotlin + coroutines; `androidx.lifecycle` (viewmodel/runtime-ktx); a DI framework (Hilt or alternative — decided per §5.1); Material `1.12.x`; Compose BOM + `ui`/`material3`/`activity-compose`; OkHttp; Sentry. Exact versions resolved in Phase 1's `libs.versions.toml` against AGP 8 / Kotlin compatibility.

## 5. Open questions (resolve before/within Phase 2)
1. **DI: Hilt vs. manual DI vs. Koin.** **RESOLVED (2026-07-07): manual service-locator** — a small hand-rolled DI object. Zero new build cost (no kapt/KSP), trivial to reason about, and easiest to verify blind; fits a shell with a handful of dependencies. Phase 2 also **folds in Phase 4 (Compose)** per the chosen "full Kotlin + Compose shell" depth — one combined shell-modernisation effort. Because CI can only prove compilation, Phase 2 is sliced into small, independently device-testable steps with hardware smoke-test checkpoints.
2. **Kotlin DSL conversion** — do it in Phase 1 or leave Groovy indefinitely? (Cosmetic; no functional impact.)
3. **Sentry hosting** — self-hosted vs. sentry.io; DSN provisioning.

## 6. Non-goals
- Rewriting the inherited PojavLauncher screens beyond the 2009Scape shell (`prefs/`, `multirt/`, `customcontrols/` editor, file pickers) — left as-is unless a shell migration forces a touch.
- Any change to the JNI/GL/JVM-boot core (§2).
- x86/x86_64 support.

---

## Appendix A — corrections applied to the original draft
Cut or retargeted because they didn't match this repo:
- **Cut:** "remove all AsyncTask" (none exist); scoped-storage migration (already done); OpenAL-audio foreground-service work (no such service).
- **Retargeted:** package `net.downthecrop.scape` → `net.kdt.pojavlaunch`; entry `JavaGUILauncherActivity` → `TestStorageActivity`/`ScapeLauncher`; "`GameActivity`/GL core" → `MainActivity`/`GLFWGLSurface`; "`libbytehook`" → `xhook`; SDK 35/24/bt-34 → real 33/21/bt-33.0.2 deltas; theme `AppCompat.Light.DarkActionBar` → `AppCompat.NoActionBar`; FGS/POST_NOTIFICATIONS work aimed at real `ProgressService`.
- **Unverifiable in this checkout:** release "2.4 / hd-2.4" (no git tags present).
