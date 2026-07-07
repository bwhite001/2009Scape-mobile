# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repository is

`2009Scape-mobile` is an **Android launcher that runs the 2009Scape RT4-Client on a phone**. It is a fork of **PojavLauncher / Boardwalk** — an Android launcher that boots a real desktop JVM + LWJGL to run *Minecraft: Java Edition* — re-skinned and rewired to run the 2009Scape RT4-Client (`rt4.jar`) instead. Almost all the plumbing (bundled JRE, JNI GL translation, AWT/GLFW bridges, on-screen controls) is inherited PojavLauncher code; only a thin layer is 2009Scape-specific.

**Consequence:** the codebase is saturated with vestigial Minecraft/Mojang concepts — `.minecraft` directories, `launcher_profiles.json`, version manifests, modpack installers, `DIR_GAME_HOME = .../PojavLauncher`. These are inherited and mostly unused. Do not mistake them for active 2009Scape functionality. When in doubt, follow the 2009Scape-specific entry points listed below.

This repo is the **client**. The server it connects to lives in a separate checkout (`../` — see `/runescape/CLAUDE.md`, the 2009Scape game server). The client and server talk over the ports in `config.json`.

The upstream client source (a mobile-callbacks branch of RT4-Client) is at `gitlab.com/downthecrop/rt4-client` (branch `lwjgl-mobile-callbacks`); `rt4.jar` in assets is a prebuilt artifact of it, not built here.

## The 2009Scape-specific layer (start here)

Everything that makes this "2009Scape" rather than "Minecraft launcher" is a handful of files:

- **`app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/ScapeLauncher.java`** — the home screen. Two buttons: **Play HD** → `MainActivity` (hardware GL path), **Play SD** → `JavaGUILauncherActivity` (software/AWT path). Plus a settings dialog.
- **`Tools.launchGLJRE()`** (in `Tools.java`) — the actual game launch. Assembles the JVM command line and calls `JREUtils.launchJavaVM`. This is where `rt4.jar`, its main class, and the `-D` system properties are wired:
  - `RT4_MAIN_CLASS = "rt4.client"` (the RT4-Client entry point)
  - classpath = LWJGL3 classes + `rt4.jar`
  - `-DconfigFile=<data>/config.json`, `-DpluginDir=<data>/plugins/`, `-DclientHomeOverride=<gamedir>`, `-DglfwWidth/-DglfwHeight`
- **`app_pojavlauncher/src/main/assets/`** holds the bundled client payload:
  - `rt4.jar` — the prebuilt RT4-Client.
  - `config.json` — **server connection config** (`ip_address`, `ip_management`, `world`, `server_port`, `wl_port`, `js5_port`, `pluginsFolder`). Ships pointing at `127.0.0.1`; edit this to target a real server. RT4-Client reads it via `-DconfigFile`.
  - `plugins/*.zip` — client-side RT4 plugins (`GroundItems`, `LoginTimer`, `MobileClientBindings`, `RememberMyLogin`, `SlayerTrackerPlugin`), unzipped into `plugins/` at first run.
  - `default.json` — the **default on-screen control layout** (touch buttons: Keyboard, PRI/SEC mouse, Tab, Shift, etc.), copied to the controlmap dir.
- **`app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/tasks/AsyncAssetManager.java`** — unpacks the above from APK assets into app data on startup (`unpackComponents`, `unpackSingleFiles`, plugin unzip). Note the `false` "already-unpacked?" guards: it skips re-copying `rt4.jar`/`config.json`/plugins if present — **flip the `false` to `true` in `unpackComponents` while iterating on client features**, or the old copy sticks around (comment in-file says as much).

If you're changing *what game runs or how it connects*, it's one of these files. If you're changing *how Android renders/controls the JVM*, it's the inherited PojavLauncher layer below.

## Build & run

Gradle project, **two modules** with **different Java levels** (see `gradle.properties` `configureondemand` note):

- **`jre_lwjgl3glfw`** — builds with **JDK 8**. A reimplementation of the LWJGL 2 API on top of LWJGL 3 / GLFW so the old client can run on the mobile GL stack. Its `jar` task writes `lwjgl-glfw-classes.jar` + a `version` stamp directly into `app_pojavlauncher/src/main/assets/components/lwjgl3/` — i.e. building this module *produces an app asset*.
- **`app_pojavlauncher`** — the Android app (`namespace net.kdt.pojavlaunch`, `compileSdk 33`, `minSdk 21`), builds with **JDK 17** + Android Gradle Plugin 7.4.2, Gradle 7.6.1.

CI (`.github/workflows/android.yml`) is the canonical build recipe. Replicate it locally:

```bash
# 1. Regenerate the language list asset (REQUIRED before an app build; the
#    build reads assets/language_list.txt, which lists the res/values-* dirs)
./scripts/languagelist_updater.sh

# 2. Build the LWJGL shim jar (JDK 8) — emits into app assets
./gradlew :jre_lwjgl3glfw:build

# 3. Build the APK (JDK 17)
./gradlew :app_pojavlauncher:assembleDebug
#   -> app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk
```

Other build types (`app_pojavlauncher/build.gradle`): `debug` (suffix `.debug`, signed with the checked-in `debug.keystore` / password `android`), `proguard` (minified), `release`, `gplay` (Play Store, signed with `upload.jks` via `GPLAY_KEYSTORE_PASSWORD` env). `versionCode` is derived from the date (or GitHub run number in CI); `versionName` comes from `git describe --tags`.

There are **no unit tests** in this repo (`CriticalNativeTest.java` / `TestStorageActivity.java` are runtime checks, not JUnit). Verification is: does the APK build and does it launch the client on-device.

### Native code (NDK)

`app_pojavlauncher/src/main/jni/` is built via `ndkBuild` (`Android.mk`, `ndkVersion 25.2.9519653`) as part of the app build — you don't invoke it separately. Key native modules: `pojavexec` (spawns/hosts the JVM, JNI bridge), `awt_xawt`/`pojavexec_awt` (AWT rendering into an Android surface via Caciocavallo), `tinywrapper` + `angle_gles2` and the vendored `gl4es/` (translate the client's desktop GL calls to GLES/EGL), `xhook` (function hooking). The Caciocavallo AWT backend jars live in `assets/components/caciocavallo{,17}/`.

## Runtime architecture (how a launch actually works)

1. **Launcher entry** is `TestStorageActivity` (holds the `MAIN`/`LAUNCHER` intent in `AndroidManifest.xml`); it validates storage then routes to `ScapeLauncher`.
2. `ScapeLauncher` unpacks assets (`AsyncAssetManager`) and shows Play HD / Play SD.
3. A **JRE runtime must be present** to run `rt4.jar`. The APK ships **without a bundled runtime** (CI: "Build Debug APK (no bundled runtime)"); the JRE is provisioned at runtime and managed under the `multirt/` package (`MultiRTUtils`, `runtimes/` dir). `ScapeLauncher.runtimeReady()` gates play until unpack finishes.
4. **Play HD → `MainActivity`** → `Tools.launchGLJRE()` builds the JVM args and `JREUtils.launchJavaVM` (in `utils/`) forks the embedded JVM running `rt4.client` with `rt4.jar` on the classpath. The client renders through the LWJGL3→GLFW shim onto an Android `GLFWGLSurface`; touch/keyboard events are fed in via `CallbackBridge` and the `customcontrols/` on-screen control system.
5. **Play SD → `JavaGUILauncherActivity`** is the software/AWT-canvas path (`AWTCanvasView`, `AWTInputBridge`).

Package map under `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/`: `customcontrols/` (on-screen touch controls + editor, driven by the control-layout JSON), `prefs/` (settings screens, `LauncherPreferences`), `multirt/` (JRE management), `tasks/` (async asset/runtime unpacking), `services/` (progress notification service), `utils/` (`JREUtils` launch, `JSONUtils`, keycode mapping, arch detection), `progresskeeper/` (task-count/progress bus).

## Working conventions

- **Match the PojavLauncher style.** This is a downstream fork; keep changes minimal and idiomatic to the surrounding (Java) code so upstream rebases stay tractable. New launcher code is Java here (unlike the server repo, which is Kotlin-first).
- **`config.json` and `default.json` are hand-edited data** and are validated only by the app parsing them — keep them valid JSON. `config.json`'s ports are the contract with the game server.
- **String resources feed Crowdin translations** (`crowdin.yml`): the source of truth is `app_pojavlauncher/src/main/res/values/strings.xml`; `values-*` dirs are translations. Always run `scripts/languagelist_updater.sh` after adding/removing a `values-*` locale (and before any app build).
- **To iterate on client (`rt4.jar` / plugin) content**, replace the asset and flip the relevant `copyAssetFile(..., false)` to `true` in `AsyncAssetManager.unpackComponents` so it re-copies over the stale unpacked copy; otherwise the old file in app data wins.
