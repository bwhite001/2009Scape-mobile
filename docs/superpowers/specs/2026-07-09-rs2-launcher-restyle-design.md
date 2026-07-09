# RS2 Launcher Restyle — Design

**Date:** 2026-07-09
**Status:** Approved (recommended options selected by user)
**Scope owner:** `2009Scape-mobile` launcher (Compose shell)

## Goal

Restyle the launcher's two Compose screens — the Home screen (`ScapeLauncher.kt`)
and the Settings screen (`SettingsActivity.kt`) — to match the RuneScape 2 /
2009-era interface visual language (mahogany + gold panel chrome, green pill
buttons, parchment text, nested borders with corner rivets, rune-glow logo),
based on the user-provided interactive HTML mockup. Additionally, add and fully
wire a **Haptic feedback** setting.

The current screens are **Jetpack Compose (Material3)** under `LauncherTheme`
(`ui/theme/LauncherTheme.kt`), NOT XML layouts. The restyle is therefore a
Compose theme + reusable-composable effort, not the `LayerDrawable`/`layer-list`
XML approach mentioned in the original brief.

## Decisions (from brainstorming)

1. **Settings scope:** Re-skin ALL existing preferences, keep existing sections
   (Video / Controls / Java / Misc / Experimental + Advanced dialog). Nothing is
   removed. The mockup's curated look becomes the style applied across every
   section.
2. **Fonts:** Use an actual RuneScape-style TTF (private LAN build). See
   "Font sourcing & fallback" for the licensing/availability risk handling.
3. **Net-new settings:** Add AND fully wire (not just placeholder). The only
   genuinely new functional item is **Haptic feedback**.
4. **Approach:** A — Compose-native RS2 design system (theme tokens + reusable
   composables). No rewrite to Views. Textures via gradients/Canvas; optional
   PNG textures can be grafted later if pure-Compose looks flat.

## Non-goals

- In-game overlay / on-screen control-button chrome (a separate XML/custom-View
  system) is **out of scope**.
- The legacy Advanced dialog (`MyDialogFragment`, Java/XML) is kept functional
  with only a light color pass — full RS2 chrome there is disproportionate.
- The mockup's "Save settings" button is dropped: prefs already persist live via
  `PreferencesRepository`.
- The mockup's "Touch mode" row is informational (describes default input
  behavior), not a discrete preference — rendered as a non-interactive info row.

## Architecture

### 1. Theme layer — `ui/theme/`

- **`RsColors.kt`** — palette tokens pulled from the mockup / RS2 chrome:
  - `bgDeep #0D0800`, `bgPanel #1C1008`, `borderGold #8C6914`,
    `borderLight #C8A040`, `borderDark #3A2408`, `parchment #C8A96E`,
    `textBright #FFDD88`, `textBody #E8D89A`, `textMuted #9A8860`,
    `greenRs #3A9A20`, `greenLight #5DC435`, `greenDark #1E5C0E`,
    `greenText #1A3A0A`.
- **`LauncherTheme.kt`** — extend the existing `darkColorScheme` mapping
  (primary=green, background/surface=mahogany, onSurface=parchment) and add an
  `RsTypography` (Material3 `Typography`) built from the bundled font family.
  Keep the existing `LauncherTheme(content)` entry point signature.

### 2. RS2 component library — `ui/rs/`

Each is a small, self-contained `@Composable` with a `@Preview`:

- **`RsPanel`** — the signature chrome: a `Modifier.drawBehind` (or wrapping
  `Box` + `Canvas`) that paints nested border rings (dark → gold-light → dark),
  an inner inset highlight, and four corner rivets (radial-gradient discs).
  Accepts `header: String? = null` (renders `RsHeaderBand` when set) and a
  content slot.
- **`RsHeaderBand`** — gold vertical-gradient title band with bright,
  letter-spaced small-caps title text.
- **`RsButton`** — green pill with layered bevel (top highlight, bottom shadow,
  gold/dark outline). `RsButtonMuted` — brown/gold variant for secondary actions.
- **`RsToggle`** — RS-styled switch: inset dark track, gold square knob, green
  glow + rune dot in the ON state. Maps to a `Boolean` + `onCheckedChange`.
- **`RsSlider`** — inset dark track, square gold thumb; wraps Material `Slider`
  behavior (value, range, onValueChangeFinished).
- **`RsSectionHeader`** — small-caps parchment label with gold hairline.
- **`RsBackButton`**, **`RsLink`** — styled affordances used on the screens.
- **`ScapeLogo`** — serif display title "2009Scape" + subtitle
  "The world as it was", with an `infiniteTransition` rune-glow shadow
  (lightweight; only on the Home screen).

### 3. Screen application

- **`ScapeLauncher.kt` (Home):** wrap content in `RsPanel`; `ScapeLogo`; an orb
  divider row; `RsButton` Play HD / Play SD; `RsLink` Settings; restyle the busy
  `LinearProgressIndicator`/message. Behavior (launch intents, runtimeReady
  gating, POST_NOTIFICATIONS request) unchanged.
- **`SettingsActivity.kt` (Settings):** wrap in `RsPanel` + `RsHeaderBand`
  ("Settings"); replace `BoolRow`→RsToggle row, `IntRow`→RsSlider row,
  `StringRow`→RS-styled `OutlinedTextField`, section labels→`RsSectionHeader`.
  Keep every existing pref (all `*_BOOLS`/`*_INTS`/`SERVER_STRINGS`), the URL +
  file config import actions, and the "Open advanced settings…" button
  (→ `RsButtonMuted`). Preference keys/semantics preserved exactly.

### 4. Haptic feedback (new, fully wired)

- **Pref:** key `haptic`, default `true`. Add a `BoolPref` in `CONTROL_BOOLS`
  ("Haptic feedback" / "Vibrate on long-press and right-click trigger").
- **Read path:** read the `haptic` value directly from the default
  `SharedPreferences` at each trigger site (`PreferenceManager
  .getDefaultSharedPreferences(context).getBoolean("haptic", true)`), cached in a
  field refreshed in `onResume`. No new `LauncherPreferences` static is required.
- **Wiring:**
  - **SD (`JavaGUILauncherActivity`):** in `longPressDetector.onLongPress` and
    the two-finger right-click branch, call a `hapticTick()` helper (a
    `performHapticFeedback(LONG_PRESS)` on the touch view, or a short
    `Vibrator` one-shot) gated by the `haptic` pref.
  - **HD (`MainActivity`/`Touchpad`):** at the right-click / long-press trigger
    site in the touch path, same gated `hapticTick()`.
- Uses the existing `android.permission.VIBRATE` (already in the manifest).

### 5. Fonts — sourcing & fallback

- Target: a RuneScape UI-style TTF at `app_pojavlauncher/src/main/res/font/`
  (e.g. `runescape_uf.ttf` for body/labels; a serif display for the logo — may
  reuse the same or a bundled serif).
- **Risk:** the true RS2 UI fonts are bitmap; TTF "recreations" are the
  gray-area part, and availability at build time is not guaranteed.
- **Handling to keep the build green:** the `FontFamily` is defined so a bundled
  file is used if present; the theme falls back to `FontFamily.Serif` (logo) and
  a condensed sans (body) otherwise. Implementation will attempt to source a
  free RS-style recreation; if unavailable/inappropriate, a permissively-licensed
  lookalike is bundled instead and the commit message states exactly which font
  shipped. The user can drop their preferred `.ttf` in at the documented path.

## Verification

- **Build:** `:app_pojavlauncher:compileDebugSources` then
  `:app_pojavlauncher:assembleDebug` must both succeed (this repo has no unit
  tests; APK build + on-device launch is the verification standard per CLAUDE.md).
- **Previews:** a `@Preview` for each RS component and for both screens, so the
  composition is exercised at compile time.
- **On-device (user):** visual check of Home + Settings; confirm toggles/sliders
  work, config import still works, and haptics fire on long-press/right-click.

## Files

| File | Change |
|------|--------|
| `ui/theme/RsColors.kt` | New — palette tokens |
| `ui/theme/LauncherTheme.kt` | Extend colorScheme; add RsTypography + FontFamily |
| `ui/rs/RsComponents.kt` (or split files) | New — RsPanel/Button/Toggle/Slider/etc. |
| `ScapeLauncher.kt` | Apply RS2 components to Home |
| `SettingsActivity.kt` | Apply RS2 components to all sections; add Haptic pref |
| `JavaGUILauncherActivity.java` | Gated haptic on SD long-press/right-click |
| `MainActivity.java` / `Touchpad.java` | Gated haptic on HD right-click/long-press |
| `res/font/*.ttf` | Bundle RS-style (or fallback) font |
