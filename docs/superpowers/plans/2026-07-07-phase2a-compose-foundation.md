# Phase 2a — Compose/Kotlin Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development or superpowers:executing-plans. Steps use `- [ ]` checkboxes.

**Goal:** Stand up the Kotlin/Compose + coroutines foundation for the launcher shell — dependencies, the Compose compiler plugin, a manual `AppContainer` service-locator, a `ProgressRepository` (StateFlow over the legacy `ProgressKeeper`), a minimal M3 `LauncherTheme`, and the first real Compose screen (`MissingStorageActivity`) — without changing any runtime behavior of the game path.

**Architecture:** Additive. New Kotlin/Compose code sits beside the existing Java; `ProgressKeeper`/`ProgressLayout` keep working unchanged (the repository only *observes* them). Only one trivial, static screen (`MissingStorageActivity`) is converted, to prove Compose renders end-to-end.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose (BOM 2024.09.03) + Material 3, `org.jetbrains.kotlin.plugin.compose`, coroutines 1.8.1, lifecycle 2.8.6, activity-compose 1.9.2.

## Global Constraints

- **Verification is CI-compile + device smoke test.** CI proves it compiles; only a device proves it runs. This environment has no JDK/SDK — push to CI (`refactor/p2a-compose-foundation`) and read the run; the human runs the device check.
- **Do NOT touch load-bearing View/JNI/GL:** `MainActivity`, `JavaGUILauncherActivity`, `GLFWGLSurface`, `AWTCanvasView`, `Touchpad`, `customcontrols/*`, `CallbackBridge`, `AWTInputBridge`, `utils/JREUtils`, `Tools.launchGLJRE`, `multirt/*`, `jni/`, `jniLibs/`, `rt4.jar`.
- **`ProgressKeeper` / `ProgressLayout` stay unchanged.** The repository observes; it does not replace them yet.
- **Compose is launcher-only.** The `:game`/AWT activities keep `AppTheme`.
- **Kotlin 2.0 needs the Compose compiler *plugin*** (`org.jetbrains.kotlin.plugin.compose`), NOT `composeOptions.kotlinCompilerExtensionVersion`.
- **Version pins:** composeBom `2024.09.03`, coroutines `1.8.1`, lifecycle `2.8.6`, activityCompose `1.9.2`, compose compiler = kotlin `2.0.21`.

## Prerequisites

- Phase 1 is on `refactor/p1-build` (AGP 8.7.3, Gradle 8.9, Kotlin 2.0.21 plugin enabled). **Branch 2a off `refactor/p1-build`**, not `master`.
- The CI log-capture trick from Phase 1 is available if a run fails (route `assembleDebug` output to a webhook inbox).

## File map

- Modify: `gradle/libs.versions.toml` — add compose/coroutine/lifecycle versions, libraries, and the compose-compiler plugin.
- Modify: `app_pojavlauncher/build.gradle` — apply compose-compiler plugin, `buildFeatures { compose true }`, add deps.
- Create: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/di/AppContainer.kt`
- Create: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/di/ProgressRepository.kt`
- Create: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/ui/theme/LauncherTheme.kt`
- Modify: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/PojavApplication.java` — build `appContainer`.
- Replace: `.../MissingStorageActivity.java` → `.../MissingStorageActivity.kt` (Compose).

---

### Task 0: Branch off Phase 1

- [ ] **Step 1:** `git checkout refactor/p1-build && git checkout -b refactor/p2a-compose-foundation`
- [ ] **Step 2:** `git log --oneline -1` → shows the Phase 1 head (`ded469910` or later).

---

### Task 1: Add Compose/coroutine dependencies and the Compose compiler plugin

**Files:** Modify `gradle/libs.versions.toml`, `app_pojavlauncher/build.gradle`

- [ ] **Step 1: Add versions, libraries, and plugin to the catalog**

In `gradle/libs.versions.toml`, under `[versions]` add:

```toml
coroutines = "1.8.1"
composeBom = "2024.09.03"
lifecycle = "2.8.6"
activityCompose = "1.9.2"
```

Under `[libraries]` add:

```toml
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { module = "androidx.compose.ui:ui" }
androidx-compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
androidx-compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
```

Under `[plugins]` add:

```toml
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 2: Apply the compose compiler plugin**

In `app_pojavlauncher/build.gradle`, change the plugins block to add the alias:

```groovy
plugins {
    alias libs.plugins.android.application
    alias libs.plugins.kotlin.android
    alias libs.plugins.compose.compiler
}
```

- [ ] **Step 3: Enable the Compose build feature**

In `app_pojavlauncher/build.gradle`, extend the existing `buildFeatures` block:

```groovy
    buildFeatures {
        buildConfig true
        compose true
    }
```

- [ ] **Step 4: Add the Compose/coroutine dependencies**

In the `dependencies { ... }` block of `app_pojavlauncher/build.gradle`, add (do not remove existing entries):

```groovy
    implementation libs.kotlinx.coroutines.android
    implementation platform(libs.androidx.compose.bom)
    implementation libs.androidx.compose.ui
    implementation libs.androidx.compose.material3
    implementation libs.androidx.compose.ui.tooling.preview
    implementation libs.androidx.activity.compose
    implementation libs.androidx.lifecycle.viewmodel.compose
    implementation libs.androidx.lifecycle.runtime.compose
    debugImplementation libs.androidx.compose.ui.tooling
```

- [ ] **Step 5: Commit, push, verify CI compiles**

```bash
git add gradle/libs.versions.toml app_pojavlauncher/build.gradle
git commit -m "build(2a): add Compose + coroutines deps and compose-compiler plugin"
git push -u origin refactor/p2a-compose-foundation
```
Verify: the Android CI run on this branch reaches **BUILD SUCCESSFUL** (poll the Actions API run for the branch; a green `Build Debug APK` step). Compose + BOM resolve; no Kotlin/compose-compiler version error.

---

### Task 2: Manual service-locator (`AppContainer`) built in `PojavApplication`

**Files:** Create `di/AppContainer.kt`; modify `PojavApplication.java`

**Interfaces:**
- Produces: `PojavApplication.appContainer` (static) → `AppContainer.progressRepository` (used by later tasks/sub-plans).

- [ ] **Step 1: Create the service-locator (with a placeholder that Task 3 fills)**

Create `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/di/AppContainer.kt`:

```kotlin
package net.kdt.pojavlaunch.di

/**
 * Minimal manual service-locator for the launcher shell. Built once in
 * PojavApplication.onCreate and reached via PojavApplication.appContainer.
 * No DI framework (no Hilt/KSP) by design.
 */
class AppContainer {
    val progressRepository: ProgressRepository = ProgressRepository()
}
```

- [ ] **Step 2: Build it in `PojavApplication`**

In `PojavApplication.java`, add the import and a static field, and instantiate it in `onCreate` after the `Tools.DIR_*` paths are set and **before** `AsyncAssetManager.unpackRuntime(getAssets())` (so the repository is observing before the first progress is emitted).

Add near the other imports:

```java
import net.kdt.pojavlaunch.di.AppContainer;
```

Add the field beside `sExecutorService`:

```java
	public static AppContainer appContainer;
```

Insert immediately before the `AsyncAssetManager.unpackRuntime(getAssets());` line:

```java
			appContainer = new AppContainer();
```

- [ ] **Step 3: Commit, push, verify CI compiles**

```bash
git add app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/di/AppContainer.kt app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/PojavApplication.java
git commit -m "feat(2a): add manual AppContainer service-locator"
git push
```
Verify: CI **BUILD SUCCESSFUL** (Kotlin↔Java interop resolves; `di/ProgressRepository` referenced here is created in Task 3 — Task 2 and 3 must land together, so run CI after Task 3, or squash 2+3 into one push).

> Note: `AppContainer` references `ProgressRepository` (Task 3). If executing task-by-task with CI between each, push after Task 3. Otherwise the Task 2 push will not compile.

---

### Task 3: `ProgressRepository` (StateFlow over `ProgressKeeper`)

**Files:** Create `di/ProgressRepository.kt`

**Interfaces:**
- Consumes: `ProgressKeeper.addListener(String, ProgressListener)`, `ProgressKeeper.addTaskCountListener(TaskCountListener, boolean)`; `ProgressLayout` key constants (`UNPACK_RUNTIME`, `EXTRACT_COMPONENTS`, `EXTRACT_SINGLE_FILES`, `INSTALL_MODPACK`). `ProgressListener` = `onProgressStarted()`, `onProgressUpdated(int, int, Object...)`, `onProgressEnded()`. `TaskCountListener` = `onUpdateTaskCount(int)` (SAM).
- Produces: `ProgressRepository.state: StateFlow<ProgressUiState>` consumed by the Compose home in sub-plan 2c.

- [ ] **Step 1: Create the repository**

Create `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/di/ProgressRepository.kt`:

```kotlin
package net.kdt.pojavlaunch.di

import com.kdt.mcgui.ProgressLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper
import net.kdt.pojavlaunch.progresskeeper.ProgressListener
import net.kdt.pojavlaunch.progresskeeper.TaskCountListener

/** UI-facing progress snapshot mirrored from the global ProgressKeeper. */
data class ProgressUiState(
    val taskCount: Int = 0,
    val progress: Int = 0,
    val messageResId: Int = 0,
    val args: List<Any?> = emptyList(),
) {
    val isBusy: Boolean get() = taskCount > 0
}

/**
 * Bridges the legacy static ProgressKeeper observer API to a StateFlow so
 * Compose can observe unpack/download progress. Non-invasive: ProgressKeeper
 * and ProgressLayout are unchanged and keep working during the migration.
 * Callbacks arrive on arbitrary threads; MutableStateFlow is thread-safe.
 */
class ProgressRepository {
    private val _state = MutableStateFlow(ProgressUiState())
    val state: StateFlow<ProgressUiState> = _state.asStateFlow()

    private val observedKeys = listOf(
        ProgressLayout.UNPACK_RUNTIME,
        ProgressLayout.EXTRACT_COMPONENTS,
        ProgressLayout.EXTRACT_SINGLE_FILES,
        ProgressLayout.INSTALL_MODPACK,
    )

    // Held to keep strong references (ProgressKeeper may store weakly elsewhere).
    private val taskCountListener = TaskCountListener { count ->
        _state.value = _state.value.copy(taskCount = count)
    }

    private val progressListener = object : ProgressListener {
        override fun onProgressStarted() {}
        override fun onProgressUpdated(progress: Int, resid: Int, vararg va: Any?) {
            _state.value = _state.value.copy(
                progress = progress,
                messageResId = resid,
                args = va.toList(),
            )
        }
        override fun onProgressEnded() {
            _state.value = _state.value.copy(progress = 0, messageResId = 0, args = emptyList())
        }
    }

    init {
        observedKeys.forEach { ProgressKeeper.addListener(it, progressListener) }
        ProgressKeeper.addTaskCountListener(taskCountListener, true)
    }
}
```

- [ ] **Step 2: Commit, push, verify CI compiles**

```bash
git add app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/di/ProgressRepository.kt
git commit -m "feat(2a): ProgressRepository exposing ProgressKeeper as StateFlow"
git push
```
Verify: CI **BUILD SUCCESSFUL**. If `onProgressUpdated`'s vararg override fails to compile (Java `Object...` mismatch), adjust the signature to match exactly what `ProgressListener.java` declares (check `vararg va: Any?` vs `Any`); re-push. Read the error via the log-capture inbox if needed.

---

### Task 4: `LauncherTheme` + convert `MissingStorageActivity` to Compose

**Files:** Create `ui/theme/LauncherTheme.kt`; replace `MissingStorageActivity.java` with `.kt`

**Interfaces:**
- Consumes: `R.drawable.storage_alert`, `R.string.storage_required` (both exist, used by the old `storage_test_no_sdcard.xml`).
- Produces: `LauncherTheme { }` composable wrapper reused by all shell screens in later sub-plans.

- [ ] **Step 1: Create the launcher M3 theme**

Create `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/ui/theme/LauncherTheme.kt`:

```kotlin
package net.kdt.pojavlaunch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand colors pulled from the existing launcher artwork / colors.xml.
private val ScapeGreen = Color(0xFF57CC33) // minebutton_color
private val ScapeBrown = Color(0xFF231C09) // launcher background
private val ScapeText = Color(0xFFB8A078)  // settings text

private val DarkColors = darkColorScheme(
    primary = ScapeGreen,
    background = ScapeBrown,
    surface = ScapeBrown,
    onBackground = ScapeText,
    onSurface = ScapeText,
)

private val LightColors = lightColorScheme(primary = ScapeGreen)

/** Material 3 theme for the launcher shell only. The :game / AWT activities keep AppTheme. */
@Composable
fun LauncherTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (useDarkTheme) DarkColors else LightColors,
        content = content,
    )
}
```

- [ ] **Step 2: Replace the Activity with a Compose version**

Delete the old Java file and create the Kotlin one:

```bash
git rm app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MissingStorageActivity.java
```

Create `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MissingStorageActivity.kt`:

```kotlin
package net.kdt.pojavlaunch

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.kdt.pojavlaunch.ui.theme.LauncherTheme

/** Shown when the external storage root is unavailable. First Compose screen in the shell. */
class MissingStorageActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LauncherTheme { MissingStorageScreen() } }
    }
}

@Composable
private fun MissingStorageScreen() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(painter = painterResource(R.drawable.storage_alert), contentDescription = null)
            Spacer(Modifier.height(16.dp))
            Text(text = stringResource(R.string.storage_required), textAlign = TextAlign.Center)
        }
    }
}
```

(The manifest entry `.MissingStorageActivity` is unchanged — same class name/package. The old `res/layout/storage_test_no_sdcard.xml` is now unused; its removal is deferred to sub-plan 2f.)

- [ ] **Step 3: Commit, push, verify CI compiles**

```bash
git add app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/ui/theme/LauncherTheme.kt \
        app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MissingStorageActivity.kt
git commit -m "feat(2a): LauncherTheme (M3) + MissingStorageActivity in Compose"
git push
```
Verify: CI **BUILD SUCCESSFUL** and the `Upload APK` step produces `app-debug`.

- [ ] **Step 4: DEVICE smoke test (human)**

Install the CI `app-debug` artifact on an ARM64 device and:
1. Launch normally → `ScapeLauncher` home appears; **Play HD** and **Play SD** still boot the game (Compose foundation did not disturb the launch path).
2. Trigger the missing-storage screen (e.g. deny storage on an API ≤28 device, or temporarily point the storage root at an unmounted path) → the new **Compose** screen renders the alert image + text under the M3 theme.

Expected: both pass. If the home or launch path regressed, the foundation deps/plugin are interfering — investigate before proceeding to 2b.

---

## Self-Review

**Spec/scope coverage (2a per the decomposition):** deps + compose compiler plugin (Task 1) ✅; `AppContainer` service-locator (Task 2) ✅; `ProgressRepository` StateFlow (Task 3) ✅; minimal `LauncherTheme` + first Compose screen (Task 4) ✅. No preferences/home/settings/async work (those are 2b–2e) — correctly out of scope.

**Placeholder scan:** All new files have complete code. The one forward reference (`AppContainer`→`ProgressRepository`) is called out with a push-ordering note so it compiles.

**Type/name consistency:** `PojavApplication.appContainer` → `AppContainer.progressRepository` → `ProgressRepository.state: StateFlow<ProgressUiState>` are consistent across tasks. `ProgressLayout` key constants and the `ProgressListener`/`TaskCountListener` signatures match the surveyed Java API (verify `onProgressUpdated` vararg form against `ProgressListener.java` at compile time). Version pins match Global Constraints.

**Known blind risk:** this code is authored without a local compile. CI-compile validates syntax/types; the Task 4 device test validates rendering. The most likely fix-up is the `onProgressUpdated` vararg override signature — adjust to the exact Java declaration if CI flags it.
