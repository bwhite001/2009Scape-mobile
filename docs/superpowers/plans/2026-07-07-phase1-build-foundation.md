# Phase 1 — Build Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the launcher's build onto a modern toolchain (Gradle 8.9, AGP 8.7, Java 17 compile target, a version catalog, and the Kotlin Android plugin with Java/Kotlin interop) without touching the JNI/GL/JVM-boot core.

**Architecture:** Sequential build-config migration. Each task changes build files only, then verifies with a Gradle command and (for the final task) a device smoke test. No product code is rewritten in this phase; the Kotlin plugin is added and proven with a single throwaway interop file so later phases can write Kotlin.

**Tech Stack:** Gradle 8.9, Android Gradle Plugin 8.7.3, Kotlin 2.0.21, `compileSdk`/`targetSdk` 35, `minSdk` 21, `buildToolsVersion` 35.0.0, NDK 25.2.9519653 (unchanged), Groovy build scripts (Kotlin-DSL conversion is an optional final task).

## Global Constraints

- **`minSdk` stays 21** — do not raise it.
- **`compileSdk` = 35, `targetSdk` = 35, `buildToolsVersion` = "35.0.0".**
- **Java 17 is the app-wrapper compile target only.** The embedded OpenJDK still runs Java 8 bytecode at runtime; do not touch anything under `jre_lwjgl3glfw/` runtime behaviour or the `-D` launch wiring.
- **Do NOT modify load-bearing core:** `gl4es/`, `jre_lwjgl3glfw/`, `app_pojavlauncher/src/main/jni/` (incl. `xhook`), `MainActivity` GL path (`GLFWGLSurface`/`AWTCanvasView`), `Tools.launchGLJRE()` / `JREUtils.launchJavaVM` / `rt4.jar` classpath wiring.
- **ABI stays ARM/ARM64.** Do not add x86/x86_64.
- **Acceptance for the phase:** `./gradlew :app_pojavlauncher:assembleDebug --warning-mode all` is warning-clean, and the resulting APK installs and launches both the **HD** path (`ScapeLauncher` → "Play HD" → `MainActivity`) and the **SD** path ("Play SD" → `JavaGUILauncherActivity`) on a physical device running **API 21** and **API 35**.
- **Version pins (use exactly):** `agp = 8.7.3`, `kotlin = 2.0.21`, Gradle `8.9`.

## Prerequisites (environment — verify before Task 1)

- **JDK 17** installed and `JAVA_HOME` pointing at it (`java` is not on PATH in a bare shell — set it up first). AGP 8.7 refuses to run on anything below JDK 17.
- Android SDK with **Platform API 35** and **Build-Tools 35.0.0** installed (`sdkmanager "platforms;android-35" "build-tools;35.0.0"`).
- NDK **25.2.9519653** installed (already pinned in `app_pojavlauncher/build.gradle` via `ndkVersion`).
- A physical **ARM64 device** (or two: one API 21, one API 35). x86 emulators are unsupported by design.
- Current branch is `refactor/p1-build` (branch off `master` first — see Task 0).

## File map (what this phase touches)

- Modify: `gradle/wrapper/gradle-wrapper.properties` — Gradle distribution version.
- Modify: `app_pojavlauncher/build.gradle` — plugins block, `compileSdk`/`targetSdk`/`buildToolsVersion`, `minSdk`/`targetSdk` property form, `packaging` rename, `buildFeatures`, `compileOptions`, deps → catalog aliases.
- Modify: `gradle.properties` — drop the deprecated `android.defaults.buildfeatures.buildconfig` flag.
- Create: `gradle/libs.versions.toml` — version catalog.
- Create: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/KotlinInteropCheck.kt` — throwaway interop proof (removed at end of Task 5).
- Modify: `.github/workflows/android.yml` — CI Gradle version.
- Optional: `settings.gradle` → `settings.gradle.kts`, root `build.gradle` → `build.gradle.kts` (Task 7).

---

### Task 0: Create the working branch

**Files:** none (git only)

- [ ] **Step 1: Branch off master**

```bash
cd /runescape/2009Scape-mobile
git checkout master
git checkout -b refactor/p1-build
```

- [ ] **Step 2: Confirm a clean starting point**

Run: `git status`
Expected: `On branch refactor/p1-build` / `nothing to commit, working tree clean`

---

### Task 1: Upgrade the Gradle wrapper to 8.9

**Files:**
- Modify: `gradle/wrapper/gradle-wrapper.properties:3`

**Interfaces:**
- Produces: a Gradle 8.9 wrapper that all later tasks invoke via `./gradlew`.

- [ ] **Step 1: Edit the distribution URL**

Change line 3 of `gradle/wrapper/gradle-wrapper.properties` from:

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-8.0-bin.zip
```

to:

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-bin.zip
```

- [ ] **Step 2: Download and verify the wrapper runs**

Run: `./gradlew --version`
Expected: `Gradle 8.9` in the output, exit code 0. (This downloads the distribution on first run.)

- [ ] **Step 3: Commit**

```bash
git add gradle/wrapper/gradle-wrapper.properties
git commit -m "build: upgrade Gradle wrapper to 8.9"
```

---

### Task 2: Upgrade AGP to 8.7.3 and bump SDK/build-tools

This is the highest-risk task: an AGP major-version bump. Do the known-required AGP-8 migrations in one commit, then chase remaining deprecation warnings to zero.

**Files:**
- Modify: `app_pojavlauncher/build.gradle` (plugins block line 1-3; `android {}` block: `compileSdk`, `defaultConfig`, `buildTypes` unaffected, `packagingOptions`, `buildToolsVersion`; add `buildFeatures`)
- Modify: `gradle.properties`

**Interfaces:**
- Consumes: Gradle 8.9 wrapper (Task 1).
- Produces: an app module building against AGP 8.7.3 / compileSdk 35.

- [ ] **Step 1: Bump the AGP version in the plugins block**

In `app_pojavlauncher/build.gradle`, change:

```groovy
plugins {
    id 'com.android.application' version '7.4.2'
}
```

to:

```groovy
plugins {
    id 'com.android.application' version '8.7.3'
}
```

- [ ] **Step 2: Bump compileSdk and buildToolsVersion, keep targetSdk at 35, minSdk at 21**

In the `android {}` block, change:

```groovy
    compileSdk = 33
```

to:

```groovy
    compileSdk = 35
```

And change `buildToolsVersion = '33.0.2'` to:

```groovy
    buildToolsVersion = '35.0.0'
```

In `defaultConfig`, change the deprecated setter form to the property form and bump targetSdk (leave minSdk value at 21):

```groovy
        minSdk 21
        targetSdk 35
```

(These replace the existing `minSdkVersion 21` and `targetSdkVersion 33` lines.)

- [ ] **Step 3: Rename `packagingOptions` to `packaging`**

AGP 8 renamed this block. Change:

```groovy
    packagingOptions {
        jniLibs {
            useLegacyPackaging = true
        }
    }
```

to:

```groovy
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
```

- [ ] **Step 4: Enable `buildConfig` explicitly and drop the deprecated global flag**

AGP 8 no longer honours the global `android.defaults.buildfeatures.buildconfig` property (deprecated, removed in AGP 9) and defaults `buildConfig` to off. The code uses `BuildConfig`, so enable it per-module.

Add a `buildFeatures` block inside `android {}` (place it just above `compileOptions`):

```groovy
    buildFeatures {
        buildConfig true
    }
```

Then in `gradle.properties`, delete this line:

```properties
android.defaults.buildfeatures.buildconfig=true
```

- [ ] **Step 5: Build and surface all warnings**

Run: `./gradlew :app_pojavlauncher:assembleDebug --warning-mode all`
Expected: BUILD SUCCESSFUL. If it fails, read the first error — the most common AGP-8 blockers here are (a) an SDK/build-tools 35 not installed (fix via `sdkmanager`), (b) a dependency needing a newer version (deferred to Task 4). Do **not** fix dependency versions yet unless the build hard-fails on one.

- [ ] **Step 6: Drive remaining deprecation warnings to zero**

Re-read the `--warning-mode all` output. Resolve each concrete deprecation it names (beyond Steps 2-4, none are expected from this module's config, but the AGP upgrade assistant flags any that appear). Do not suppress warnings; fix them. Re-run until the output shows no deprecation warnings.

Run: `./gradlew :app_pojavlauncher:assembleDebug --warning-mode all`
Expected: BUILD SUCCESSFUL with no `Deprecated Gradle features` notice.

- [ ] **Step 7: Commit**

```bash
git add app_pojavlauncher/build.gradle gradle.properties
git commit -m "build: upgrade to AGP 8.7.3, compileSdk/targetSdk 35, build-tools 35.0.0"
```

---

### Task 3: Raise the app compile target to Java 17

**Files:**
- Modify: `app_pojavlauncher/build.gradle` (`compileOptions` block)

**Interfaces:**
- Consumes: AGP 8.7.3 (Task 2).
- Produces: Java 17 source/target for the app wrapper. (The `jre_lwjgl3glfw` module keeps its Java 8 toolchain — do not touch it.)

- [ ] **Step 1: Change the compile options**

In `app_pojavlauncher/build.gradle`, change:

```groovy
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
```

to:

```groovy
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
```

- [ ] **Step 2: Build**

Run: `./gradlew :app_pojavlauncher:assembleDebug --warning-mode all`
Expected: BUILD SUCCESSFUL, no new warnings.

- [ ] **Step 3: Commit**

```bash
git add app_pojavlauncher/build.gradle
git commit -m "build: raise app compile target to Java 17"
```

---

### Task 4: Add a version catalog and migrate app dependencies

**Files:**
- Create: `gradle/libs.versions.toml`
- Modify: `app_pojavlauncher/build.gradle` (plugins block + `dependencies` block)

**Interfaces:**
- Consumes: AGP 8.7.3 (Task 2).
- Produces: catalog aliases (`libs.plugins.android.application`, `libs.androidx.appcompat`, `libs.material`, etc.) consumed here and reused by later phases.

- [ ] **Step 1: Create the catalog file**

Create `gradle/libs.versions.toml` with exactly:

```toml
[versions]
agp = "8.7.3"
kotlin = "2.0.21"

javaxAnnotation = "1.3.2"
commonsCodec = "1.15"
androidxPreference = "1.2.0"
androidxDrawerlayout = "1.2.0"
androidxViewpager2 = "1.1.0-beta01"
androidxAnnotation = "1.5.0"
constraintlayout = "2.1.4"
checkerboarddrawable = "1.0.2"
portraitSdp = "ed33e89cbc"
portraitSsp = "6c02fd739b"
extendedView = "1.0.0"
gamepadRemapper = "71397676b5"
xz = "1.8"
exp4j = "60eaec6f78"
htmlcleaner = "2.6.1"
appcompat = "1.6.1"
material = "1.5.0"

[libraries]
javax-annotation = { module = "javax.annotation:javax.annotation-api", version.ref = "javaxAnnotation" }
commons-codec = { module = "commons-codec:commons-codec", version.ref = "commonsCodec" }
androidx-preference = { module = "androidx.preference:preference", version.ref = "androidxPreference" }
androidx-drawerlayout = { module = "androidx.drawerlayout:drawerlayout", version.ref = "androidxDrawerlayout" }
androidx-viewpager2 = { module = "androidx.viewpager2:viewpager2", version.ref = "androidxViewpager2" }
androidx-annotation = { module = "androidx.annotation:annotation", version.ref = "androidxAnnotation" }
androidx-constraintlayout = { module = "androidx.constraintlayout:constraintlayout", version.ref = "constraintlayout" }
androidx-appcompat = { module = "androidx.appcompat:appcompat", version.ref = "appcompat" }
material = { module = "com.google.android.material:material", version.ref = "material" }
checkerboarddrawable = { module = "com.github.duanhong169:checkerboarddrawable", version.ref = "checkerboarddrawable" }
portrait-sdp = { module = "com.github.PojavLauncherTeam:portrait-sdp", version.ref = "portraitSdp" }
portrait-ssp = { module = "com.github.PojavLauncherTeam:portrait-ssp", version.ref = "portraitSsp" }
extended-view = { module = "com.github.Mathias-Boulay:ExtendedView", version.ref = "extendedView" }
gamepad-remapper = { module = "com.github.Mathias-Boulay:android_gamepad_remapper", version.ref = "gamepadRemapper" }
xz = { module = "org.tukaani:xz", version.ref = "xz" }
exp4j = { module = "com.github.PojavLauncherTeam:exp4j", version.ref = "exp4j" }
htmlcleaner = { module = "net.sourceforge.htmlcleaner:htmlcleaner", version.ref = "htmlcleaner" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
```

- [ ] **Step 2: Switch the plugins block to the catalog alias**

In `app_pojavlauncher/build.gradle`, change:

```groovy
plugins {
    id 'com.android.application' version '8.7.3'
}
```

to:

```groovy
plugins {
    alias libs.plugins.android.application
}
```

- [ ] **Step 3: Replace the dependencies block with catalog aliases**

Replace the entire `dependencies { ... }` block in `app_pojavlauncher/build.gradle` with:

```groovy
dependencies {
    implementation libs.javax.annotation
    implementation libs.commons.codec
    implementation libs.androidx.preference
    implementation libs.androidx.drawerlayout
    implementation libs.androidx.viewpager2
    implementation libs.androidx.annotation
    implementation libs.androidx.constraintlayout
    implementation libs.checkerboarddrawable
    implementation libs.portrait.sdp
    implementation libs.portrait.ssp
    implementation libs.extended.view
    implementation libs.gamepad.remapper
    implementation libs.xz
    implementation libs.exp4j
    implementation libs.htmlcleaner
    implementation fileTree(dir: 'libs', include: ['*.jar'])
    implementation libs.androidx.appcompat
    implementation libs.material
}
```

(This preserves the exact same versions and the `fileTree` local-jar include; only the declaration style changes.)

- [ ] **Step 4: Build and confirm the dependency graph is unchanged**

Run: `./gradlew :app_pojavlauncher:assembleDebug --warning-mode all`
Expected: BUILD SUCCESSFUL, no warnings.

Run: `./gradlew :app_pojavlauncher:dependencies --configuration debugRuntimeClasspath | grep -E "appcompat|material|htmlcleaner"`
Expected: `androidx.appcompat:appcompat:1.6.1`, `com.google.android.material:material:1.5.0`, and `net.sourceforge.htmlcleaner:htmlcleaner:2.6.1` all resolve (same versions as before).

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml app_pojavlauncher/build.gradle
git commit -m "build: add version catalog and migrate app dependencies to it"
```

---

### Task 5: Add the Kotlin Android plugin and prove Java/Kotlin interop

**Files:**
- Modify: `app_pojavlauncher/build.gradle` (plugins block)
- Create (temporary): `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/KotlinInteropCheck.kt`

**Interfaces:**
- Consumes: catalog `libs.plugins.kotlin.android` (Task 4).
- Produces: a project where `.kt` files compile and interoperate with existing Java. No product code is converted in this phase.

- [ ] **Step 1: Apply the Kotlin plugin**

In `app_pojavlauncher/build.gradle`, change:

```groovy
plugins {
    alias libs.plugins.android.application
}
```

to:

```groovy
plugins {
    alias libs.plugins.android.application
    alias libs.plugins.kotlin.android
}
```

- [ ] **Step 2: Write a throwaway Kotlin file that calls into existing Java**

Create `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/KotlinInteropCheck.kt`:

```kotlin
package net.kdt.pojavlaunch

/**
 * Temporary interop proof for the Phase 1 build migration.
 * Confirms Kotlin compiles in this module and can reference existing Java
 * (Tools.APP_NAME is a public static field on the Java Tools class).
 * Deleted at the end of Task 5.
 */
internal object KotlinInteropCheck {
    fun appName(): String = Tools.APP_NAME
}
```

- [ ] **Step 3: Build to prove Kotlin compiles and links against Java**

Run: `./gradlew :app_pojavlauncher:assembleDebug --warning-mode all`
Expected: BUILD SUCCESSFUL. The build now runs the `compileDebugKotlin` task; interop against `Tools.APP_NAME` resolves without error.

- [ ] **Step 4: Remove the throwaway file**

```bash
git rm -f app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/KotlinInteropCheck.kt
```

- [ ] **Step 5: Build again to confirm the Kotlin toolchain is fine with zero Kotlin sources**

Run: `./gradlew :app_pojavlauncher:assembleDebug --warning-mode all`
Expected: BUILD SUCCESSFUL, no warnings. (An Android+Kotlin module builds cleanly with no `.kt` files.)

- [ ] **Step 6: Commit**

```bash
git add app_pojavlauncher/build.gradle
git commit -m "build: enable Kotlin Android plugin with Java interop"
```

---

### Task 6: Update CI to the new Gradle version

The workflow currently pins Gradle **7.6.1** via `gradle/gradle-build-action`, which will fail against AGP 8.7. Point CI at Gradle 8.9. The JDK matrix (JDK 8 to build the `jre_lwjgl3glfw` jar, JDK 17 for the app) stays — the Java-8 toolchain in that module is satisfied by the JDK 8 install already on the runner.

**Files:**
- Modify: `.github/workflows/android.yml`

- [ ] **Step 1: Bump the Gradle version in the workflow**

In `.github/workflows/android.yml`, change:

```yaml
      - name: Set up Gradle
        uses: gradle/gradle-build-action@v3
        with:
          gradle-version: 7.6.1
```

to:

```yaml
      - name: Set up Gradle
        uses: gradle/gradle-build-action@v3
        with:
          gradle-version: 8.9
```

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/android.yml
git commit -m "ci: bump Gradle to 8.9 for AGP 8.7 compatibility"
```

- [ ] **Step 3: Push the branch and confirm CI is green**

```bash
git push -u origin refactor/p1-build
```
Expected: the "Android CI" workflow on the pushed branch completes with a green check and uploads `app-debug.apk`. (CI builds the debug APK with no bundled runtime, same as before.)

---

### Task 7 (OPTIONAL): Convert the settings/root scripts to Kotlin DSL

Optional per the spec. Convert only the two small top-level scripts; leave the ~300-line `app_pojavlauncher/build.gradle` in Groovy this phase (its `exec`/closure logic is riskier to port and carries no maintainability payoff yet). Skip this task entirely if you want to defer all Kotlin-DSL work.

**Files:**
- Create: `settings.gradle.kts` (replaces `settings.gradle`)
- Create: `build.gradle.kts` (replaces the empty root `build.gradle`)

- [ ] **Step 1: Create `settings.gradle.kts`**

Create `settings.gradle.kts` with:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "PojavLauncher"
include(":jre_lwjgl3glfw")
include(":app_pojavlauncher")
```

- [ ] **Step 2: Create the root `build.gradle.kts` and remove the Groovy originals**

Create `build.gradle.kts` (empty root, matching the current empty `build.gradle`):

```kotlin
// Root project — configuration lives in the module build scripts.
```

Then remove the Groovy files:

```bash
git rm settings.gradle build.gradle
```

- [ ] **Step 3: Build to confirm settings resolve**

Run: `./gradlew :app_pojavlauncher:assembleDebug --warning-mode all`
Expected: BUILD SUCCESSFUL, `rootProject.name` still `PojavLauncher`, both modules present.

- [ ] **Step 4: Commit**

```bash
git add settings.gradle.kts build.gradle.kts
git commit -m "build: convert settings and root script to Kotlin DSL"
```

---

### Task 8: Phase acceptance — warning-clean build + device smoke test

**Files:** none (verification only)

- [ ] **Step 1: Warning-clean full build**

Run: `./gradlew clean :app_pojavlauncher:assembleDebug --warning-mode all`
Expected: BUILD SUCCESSFUL with **no** `Deprecated Gradle features were used in this build` notice.

- [ ] **Step 2: Install and launch on API 35 (ARM64)**

```bash
adb install -r app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk
adb shell monkey -p net.kdt.pojavlaunch.debug -c android.intent.category.LAUNCHER 1
```
Then manually: tap **Play HD** — the client boots into `MainActivity` and reaches the RT4-Client login screen. Return, tap **Play SD** — `JavaGUILauncherActivity` launches. No crash in `adb logcat`.

- [ ] **Step 3: Repeat the install + HD/SD launch on an API 21 (ARM) device**

Expected: same result — both paths launch, no crash. (API 21 is `minSdk`; this proves the floor still works.)

- [ ] **Step 4: Final commit / branch is ready for merge**

```bash
git status
```
Expected: clean tree. The branch `refactor/p1-build` is ready to merge to `master` per the spec's branch strategy.

---

## Self-Review

**Spec coverage (Phase 1 items from §3 of the design spec):**
- Gradle 7.6.1/8.0 → 8.x — Task 1 ✅
- AGP 7.4.2 → 8.x — Task 2 ✅
- compileSdk/targetSdk 33 → 35, minSdk stays 21, buildTools bump — Task 2 ✅
- `compileOptions` 1.8 → 17 (app only) — Task 3 ✅
- Add `libs.versions.toml` + migrate module deps — Task 4 ✅
- Kotlin Android plugin + Java/Kotlin interop, no code converted — Task 5 ✅
- Kotlin DSL conversion (optional) — Task 7 ✅ (marked optional)
- CI must not break on the AGP bump — Task 6 ✅ (not in the spec text but required for a working build; added)
- Acceptance: warning-clean `assembleDebug` + HD/SD launch on API 21 & 35 — Task 8 ✅
- Load-bearing core untouched — enforced by Global Constraints; no task edits `gl4es/`, `jre_lwjgl3glfw/`, `jni/`, the GL path, or launch wiring ✅

**Placeholder scan:** No "TBD"/"add appropriate…" placeholders; every code step shows the exact file content. Task 2 Step 6 ("drive remaining warnings to zero") names the concrete expected sources (Steps 2-4) rather than hand-waving; any additional warning is a real build output the engineer reads, not an unspecified instruction.

**Type/name consistency:** Catalog alias names defined in Task 4 (`libs.plugins.android.application`, `libs.plugins.kotlin.android`, `libs.androidx.appcompat`, `libs.material`, …) are the exact names referenced in Task 4 Steps 2-3 and Task 5 Step 1. The interop file references `Tools.APP_NAME`, a verified `public static String` field on the existing `Tools` Java class. Version pins (`agp = 8.7.3`, `kotlin = 2.0.21`, Gradle `8.9`, SDK 35, build-tools 35.0.0) match the Global Constraints and are identical everywhere they appear.
