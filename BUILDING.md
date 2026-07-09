# Building 2009Scape-mobile

This is a two-module Android project that needs **two different JDKs**:

- `:jre_lwjgl3glfw` — the LWJGL→GLFW shim — builds with **JDK 8**.
- `:app_pojavlauncher` — the Android app — builds with **JDK 17** (AGP 8.7.3).

There is **no single build task** — running `gradlew` with no arguments only prints
the task list. Run the two steps below in order.

## Build a debug APK

```bash
# 1. (JDK 8) Build the LWJGL shim jar. It emits directly into app assets.
./gradlew :jre_lwjgl3glfw:build

# 2. (JDK 17) Build the APK.
./gradlew :app_pojavlauncher:assembleDebug
```

Output: `app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk`

## Notes

- **Windows:** use `gradlew.bat` in place of `./gradlew`. You do **not** need to run
  `scripts/languagelist_updater.sh` (a bash script) on a fresh checkout — the
  `language_list.txt` asset it generates is already committed and correct. Only re-run
  it (on Linux/macOS/WSL) after adding or removing a `res/values-*` locale.
- **Both JDKs required:** if only one JDK is installed, one of the two steps will fail.
  Select the right JDK per step via `JAVA_HOME`.
- **Docker (any OS):** the checked-in `build.Dockerfile` provisions JDK 8 + JDK 17 +
  the Android SDK for a self-contained build.
- **SDK components:** `platforms;android-35`, `build-tools;35.0.0`, `ndk;25.2.9519653`.
