# RS2 Launcher Restyle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restyle the Compose launcher (Home + Settings) to the RuneScape 2 visual language and add a fully-wired Haptic feedback setting.

**Architecture:** Compose-native (Approach A). Add RS2 theme tokens + a reusable composable library (`ui/rs/`), then apply them to `ScapeLauncher.kt` and `SettingsActivity.kt`. Haptic feedback is a new pref read at the SD/HD right-click & long-press trigger sites. No conversion to XML Views.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Android res/font, Java (for the SD/HD touch activities).

## Global Constraints

- Module `app_pojavlauncher`: `compileSdk 35`, `minSdk 21`, `targetSdk 35`, JDK 17, Compose/Material3 (BOM).
- Build only inside the Docker image: `docker run --rm -v "$PWD":/project -w /project -e GRADLE_USER_HOME=/project/.gradle-cache 2009scape-apk-builder bash -lc 'git config --global --add safe.directory /project; ./gradlew <task> --console=plain'`.
- No unit tests exist; per-task verification = `:app_pojavlauncher:compileDebugSources`; final = `:app_pojavlauncher:assembleDebug`.
- Before committing, `git checkout --` the build-regenerated artifacts: `assets/language_list.txt`, `assets/components/lwjgl3/version`, `assets/components/lwjgl3/lwjgl-glfw-classes.jar`.
- Preserve exact SharedPreferences keys/semantics. `LauncherTheme(content)` entry-point signature unchanged.
- New Kotlin lives under `net.kdt.pojavlaunch.ui.*`. Match surrounding style.

---

### Task 1: Theme foundation — colors, font, typography

**Files:**
- Create: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/ui/theme/RsColors.kt`
- Create: `app_pojavlauncher/src/main/res/font/rs_font.ttf` (bundled RS-style or fallback)
- Modify: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/ui/theme/LauncherTheme.kt`

**Interfaces:**
- Produces: `object RsColors { val bgDeep, bgPanel, borderGold, borderLight, borderDark, parchment, textBright, textBody, textMuted, greenRs, greenLight, greenDark, greenText: Color }`
- Produces: `val RsFontFamily: FontFamily`, `val RsDisplayFamily: FontFamily`, `val RsTypography: Typography`
- Produces: unchanged `@Composable fun LauncherTheme(useDarkTheme, content)`

- [ ] **Step 1: Source the font.** Attempt to download a free RS UI-style TTF into `res/font/rs_font.ttf`. If unavailable/inappropriate, bundle a permissively-licensed lookalike (SIL OFL serif for display + condensed sans) and record which shipped. The file MUST exist so the build resolves `R.font.rs_font`.
- [ ] **Step 2: Write `RsColors.kt`** with the palette from the spec (hex values verbatim).
- [ ] **Step 3: Extend `LauncherTheme.kt`** — map `darkColorScheme` to RS tokens (primary=greenRs, background/surface=bgPanel, onBackground/onSurface=textBody, secondary=borderGold). Add `RsFontFamily`/`RsDisplayFamily` from `R.font.rs_font` (with `FontFamily.Serif`/`FontFamily.SansSerif` as declared fallbacks) and an `RsTypography`. Keep the `LauncherTheme(content)` signature; pass `typography = RsTypography`.
- [ ] **Step 4: Compile.** Run `compileDebugSources`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** (`feat: RS2 theme tokens, font, typography`).

---

### Task 2: RS2 component library

**Files:**
- Create: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/ui/rs/RsChrome.kt` (RsPanel, RsHeaderBand, RsSectionHeader, rivets/border drawing)
- Create: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/ui/rs/RsControls.kt` (RsButton, RsButtonMuted, RsToggle, RsSlider, RsBackButton, RsLink)
- Create: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/ui/rs/ScapeLogo.kt`

**Interfaces:**
- Produces:
  - `@Composable fun RsPanel(modifier: Modifier = Modifier, header: String? = null, content: @Composable ColumnScope.() -> Unit)`
  - `@Composable fun RsHeaderBand(title: String, modifier: Modifier = Modifier)`
  - `@Composable fun RsSectionHeader(text: String)`
  - `@Composable fun RsButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, muted: Boolean = false)`  (RsButtonMuted = RsButton(muted=true))
  - `@Composable fun RsToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit)`
  - `@Composable fun RsSlider(value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>, onValueChangeFinished: () -> Unit)`
  - `@Composable fun RsBackButton(onClick: () -> Unit)`
  - `@Composable fun RsLink(text: String, onClick: () -> Unit)`
  - `@Composable fun ScapeLogo(modifier: Modifier = Modifier)`

- [ ] **Step 1: Write `RsChrome.kt`.** `RsPanel` = a `Box`/`Column` with `Modifier.drawBehind` painting: outer dark stroke, gold-light ring, inner dark ring, panel fill (`RsColors.bgPanel`), and four corner rivets (radial `Brush` discs at the corners). Optional `RsHeaderBand` at top when `header != null`. `RsSectionHeader` = small-caps letter-spaced `Text` in `parchment` with a gold hairline `Divider`. Add `@Preview` composables (dark background).
- [ ] **Step 2: Write `RsControls.kt`.** `RsButton` = `Box`/`Surface` with green vertical `Brush` gradient + layered border (gold/dark) + bevel, `greenText` label, `clickable`; `muted` uses brown gradient + `parchment` label. `RsToggle` = a `Box` (inset dark track; animated gold square knob; green glow + rune dot when checked) with `toggleable`. `RsSlider` = wrap Material `Slider` with custom `colors()` (gold thumb, dark track) or a custom track via `Canvas`. `RsBackButton`/`RsLink` = styled `Text`/small button. Add `@Preview`s.
- [ ] **Step 3: Write `ScapeLogo.kt`.** Serif display "2009Scape" (`RsDisplayFamily`, `textBright`) + subtitle "The world as it was" (`textMuted`, letter-spaced). Rune glow via `rememberInfiniteTransition` animating the text shadow/alpha. `@Preview`.
- [ ] **Step 4: Compile.** `compileDebugSources`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** (`feat: RS2 Compose component library`).

---

### Task 3: Apply RS2 to the Home screen

**Files:**
- Modify: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/ScapeLauncher.kt`

**Interfaces:**
- Consumes: `RsPanel`, `RsButton`, `RsLink`, `ScapeLogo`, `RsColors`.

- [ ] **Step 1:** Rewrite the `HomeScreen` composable body: `Surface(color = RsColors.bgDeep)` → centered `RsPanel { ScapeLogo(); orb divider Row; RsButton("Play HD", onPlayHd); RsButton("Play SD", onPlaySd); RsLink("Settings", onSettings) }`. When `progress.isBusy`, show a restyled progress row (thin gold `LinearProgressIndicator` + message). Keep all callbacks/params and `launchIfReady` gating unchanged.
- [ ] **Step 2: Compile.** `compileDebugSources`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 3: Commit** (`feat: RS2 restyle of launcher home screen`).

---

### Task 4: Apply RS2 to Settings + add Haptic pref

**Files:**
- Modify: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt`

**Interfaces:**
- Consumes: `RsPanel`, `RsHeaderBand`, `RsSectionHeader`, `RsToggle`, `RsSlider`, `RsButton`, `RsBackButton`, `RsColors`.
- Produces: pref key `"haptic"` (Boolean, default true) present in `CONTROL_BOOLS`.

- [ ] **Step 1:** Add `BoolPref("haptic", "Haptic feedback", true)` to `CONTROL_BOOLS` (description via label; the row shows the sub-text used by other rows).
- [ ] **Step 2:** Wrap `SettingsScreen` content in `Surface(color = RsColors.bgDeep)` + `RsPanel(header = null)` with a top `RsHeaderBand("Settings")` and `RsBackButton(onBack)`. Replace: `BoolRow` internals → `RsToggle`; `IntRow` slider → `RsSlider`; `StringRow` field → RS-styled `OutlinedTextField` (gold border colors); `prefSection`/`section` titles → `RsSectionHeader`. Keep the URL import, file-picker import, JavaArgs row, and "Open advanced settings…" (`RsButton(muted=true)`). Preserve all pref lists and keys.
- [ ] **Step 3: Compile.** `compileDebugSources`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 4: Commit** (`feat: RS2 restyle of settings screen + haptic pref`).

---

### Task 5: Wire haptic feedback (SD + HD)

**Files:**
- Modify: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/JavaGUILauncherActivity.java`
- Modify: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java` (and/or `Touchpad.java` where right-click fires)

**Interfaces:**
- Consumes: pref `"haptic"` from default SharedPreferences.

- [ ] **Step 1:** In each activity add a `private boolean mHaptic;` refreshed in `onResume` from `PreferenceManager.getDefaultSharedPreferences(this).getBoolean("haptic", true)`, and a `private void hapticTick(View v){ if(mHaptic) v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING); }`.
- [ ] **Step 2 (SD):** Call `hapticTick(mTextureView)` in `longPressDetector.onLongPress` and in the two-finger right-click branch of the `mTextureView`/`mTouchPad` listeners.
- [ ] **Step 3 (HD):** Call `hapticTick(...)` at the HD right-click / long-press trigger site (locate in `MainActivity`/`Touchpad` touch handling).
- [ ] **Step 4: Compile.** `compileDebugSources`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** (`feat: wire haptic feedback on long-press/right-click`).

---

### Task 6: Full build, deploy, push

- [ ] **Step 1:** `:app_pojavlauncher:assembleDebug`. Expected: BUILD SUCCESSFUL; APK at `app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk`.
- [ ] **Step 2:** `git checkout --` the regenerated artifacts (language_list.txt, lwjgl3/version, lwjgl-glfw-classes.jar).
- [ ] **Step 3:** Copy APK → `/runescape/webroot/2009Scape-mobile-tier1-debug.apk` and `/runescape/webroot/2009Scape-mobile.apk`.
- [ ] **Step 4:** `git push origin master`.

## Self-Review

- **Spec coverage:** theme (T1), components (T2), Home (T3), Settings all-sections + haptic pref (T4), haptic wiring HD+SD (T5), build/deploy (T6). Advanced dialog light pass = via `RsButton(muted)` entry only (dialog itself left legacy, per spec non-goal). Font risk handled in T1 Step 1. Covered.
- **Placeholder scan:** component internals are described with concrete Compose mechanisms (drawBehind/Brush/toggleable/infiniteTransition); font file guaranteed to exist. OK.
- **Type consistency:** `RsColors`, `RsPanel(header=)`, `RsButton(muted=)`, `RsToggle(checked,onCheckedChange)`, `RsSlider(value,onValueChange,valueRange,onValueChangeFinished)`, `hapticTick(View)` used consistently across tasks.
