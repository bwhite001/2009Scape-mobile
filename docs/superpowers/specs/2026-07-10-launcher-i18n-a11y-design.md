# Launcher i18n + accessibility bundle — design

**Date:** 2026-07-10
**Status:** Approved (brainstorming) — ready for implementation plan
**Scope:** `ScapeLauncher.kt`, `SettingsActivity.kt`, `ui/rs/RsControls.kt`, `ui/rs/ScapeLogo.kt`, `res/values/strings.xml`

## Problem

The RS2 Compose reskin (commits `1d32c4bd8`…`89d2872d9`) shipped two regressions against
existing project standards:

1. **i18n:** the reskinned launcher home + settings screens hardcode English string
   literals. Only 4 `stringResource` calls exist across both screens; ~60 user-facing
   strings are literals. This bypasses the repo's Crowdin translation pipeline
   (`crowdin.yml`, `values-*`, `language_list.txt`), which CLAUDE.md names as the source
   of truth: *"String resources feed Crowdin translations."* Every non-English user sees
   English for the entire launcher and settings UI.

2. **Accessibility:** interactive controls are below the 48dp minimum touch target
   (`RsToggle` 22dp tall, `RsBackButton` ~30dp), and TalkBack support is nearly absent
   (one `semantics` in the whole UI). Custom `Box`+`clickable` buttons carry no button
   role; sliders announce no value; decorative elements are not hidden from the reader.

This is presentation-only work: no behavior, no logic, no SharedPreferences key changes.

## Part 1 — Internationalization

### Model change (the crux)

Preference metadata lives in top-level `val` lists, e.g.
`BoolPref("ignoreNotch", "Ignore notch", false)`. `stringResource` is `@Composable` and
cannot be called from a top-level `val`. The label field therefore changes from a
resolved `String` to a string **resource id**, resolved at render time in the row
composable:

```kotlin
private data class BoolPref(val key: String, @StringRes val labelRes: Int, val def: Boolean)
private data class IntPref(val key: String, @StringRes val labelRes: Int, val def: Int, val range: IntRange)
private data class StringPref(
    val key: String,
    @StringRes val labelRes: Int,
    val def: String,
    val keyboardType: KeyboardType = KeyboardType.Text,
)
```

- `BoolRow` / `IntRow` / `StringRow`: `Text(stringResource(pref.labelRes), …)`.
- `prefSection` / `section` take a `@StringRes Int` title and resolve it before calling
  `RsSectionHeader`.
- `RsSectionHeader` / `RsHeaderBand` keep their `String` parameter (they receive an
  already-resolved value from the caller) — no signature change needed.
- `SERVER_STRINGS`, `VIDEO_*`, `CONTROL_*`, `JAVA_*`, `MISC_*`, `EXPERIMENTAL_BOOLS`
  entries switch to resource ids.

### Home screen (`ScapeLauncher.kt`, `ScapeLogo.kt`)

- "Play HD" / "Play SD" / "Settings" → `stringResource` at the `RsButton`/`RsLink` call.
- `ScapeLogo` tagline "THE WORLD AS IT WAS" → `stringResource` resolved inside `ScapeLogo`.
- "2009Scape" logo text stays a literal (brand/proper noun).

### Toast / error messages (`SettingsActivity.kt`)

All import/load user-facing toasts move to resources, using `getString(resId, arg)` for
the `%s`-interpolated ones. The HTTP-vs-HTTPS branch keeps its two distinct messages.

### String naming

`snake_case`, matching `strings.xml` convention, grouped by prefix:
`launcher_*`, `settings_*` (sections, buttons, toasts), `pref_*` (preference labels).
Only `res/values/strings.xml` (the English source) is edited — no `values-*` locale is
added, so `scripts/languagelist_updater.sh` is **not** required. Crowdin ingests new keys.

Full key inventory in the Appendix.

## Part 2 — Accessibility

### Touch targets (≥48dp) — `RsControls.kt`

Expand the *interactive* region to 48dp without changing the drawn size, using Compose's
built-in `Modifier.minimumInteractiveComponentSize()`:

- `RsToggle` — drawn 44×22dp; toggle area expands to 48dp min.
- `RsBackButton` — ~30dp; expands to 48dp min.
- `RsButton` / `RsLink` — already ≥40dp; add the same modifier for guaranteed compliance
  (no visual change).

### TalkBack semantics — `RsControls.kt`, `SettingsActivity.kt`

- `RsButton`, `RsLink`, `RsBackButton`: declare `Role.Button` (via
  `semantics { role = Role.Button }` or `clickable(onClickLabel = …)`).
- `IntRow` slider: add `Modifier.semantics { stateDescription = value.toString() }` so the
  value is announced alongside the label.
- `RsToggle`: already declares `Role.Switch` — unchanged.
- Decorative `Rivet` (and any future orb/separator decoration): `Modifier.clearAndSetSemantics {}`
  so TalkBack skips them.

## Out of scope

- P3 findings: dead light-theme branch in `LauncherTheme`, mockup orb/separator
  decorations, vestigial `minecraft_ten.ttf` / `noto_sans_bold.ttf`.
- The `:game` / AWT (`Play SD`) in-game surface — this bundle is the launcher shell only.
- Notch/cutout default (handled by existing `Tools.ignoreNotch`, working as designed).

## Verification

- **Automated:** Compose `@Preview`s render the affected composables (visual check of
  touch-target expansion). The ktlint CI gate must pass on the Kotlin edits. The APK must
  build via the Docker image (`2009scape-apk-builder`).
- **Manual (on-device, required — cannot run headless):** per
  `docs/verification/device-smoke-checklist.md` —
  1. Switch device language to a translated locale; confirm launcher + settings labels are
     translated (or fall back to English cleanly for untranslated keys).
  2. Enable TalkBack; confirm buttons announce as buttons, the slider announces its value,
     toggles announce on/off, and decorations are skipped.
  3. Confirm every toggle/slider/button is comfortably tappable (48dp).

## Appendix — string key inventory

### Home (`launcher_*`)
| Key | English |
|-----|---------|
| launcher_play_hd | Play HD |
| launcher_play_sd | Play SD |
| launcher_settings | Settings |
| launcher_tagline | THE WORLD AS IT WAS |

### Sections (`settings_section_*`)
`server` Server · `video` Video · `controls` Controls · `java` Java · `misc` Misc ·
`experimental` Experimental · `advanced` Advanced. Plus `settings_title` = Settings.

### Settings actions / misc (`settings_*`)
| Key | English |
|-----|---------|
| settings_import_url | Import config from URL |
| settings_load_file | Load config.json from file |
| settings_edit_controls | Edit on-screen controls |
| settings_open_advanced | Open advanced settings |
| settings_advanced_desc | Renderer, runtime, plugins, and imports |

### Toasts (`settings_*`, `%s` where noted)
| Key | English |
|-----|---------|
| settings_import_need_url | Set a config import URL first |
| settings_import_http_warn | Importing over HTTP (unencrypted) from %s… |
| settings_import_start | Importing config from %s… |
| settings_import_ok | Imported %s — IP/port overridden |
| settings_import_failed | Import failed: %s |
| settings_load_ok | Loaded %s — IP/port overridden |
| settings_load_failed | Load failed: %s |

### Preference labels (`pref_*`)
| Key | English |
|-----|---------|
| pref_server_ip | Server IP address |
| pref_server_port | Server port |
| pref_server_config_url | Config import URL |
| pref_ignore_notch | Ignore notch |
| pref_sustained_performance | Sustained performance mode |
| pref_force_vsync | Force VSync |
| pref_resolution_scale | Resolution scale |
| pref_horizontal_inset | Horizontal inset |
| pref_disable_gestures | Disable gestures |
| pref_disable_double_tap | Disable double-tap to swap hands |
| pref_single_tap_right_click | Single-tap opens right-click menu |
| pref_haptic | Haptic feedback |
| pref_mouse_start | Start with virtual mouse enabled |
| pref_button_all_caps | Uppercase button labels |
| pref_enable_gyro | Enable gyro aiming |
| pref_gyro_smoothing | Gyro smoothing |
| pref_gyro_invert_x | Invert gyro X |
| pref_gyro_invert_y | Invert gyro Y |
| pref_button_size | Button size |
| pref_mouse_scale | Mouse pointer size |
| pref_mouse_speed | Mouse speed |
| pref_long_press_delay | Long-press delay (ms) |
| pref_gyro_sensitivity | Gyro sensitivity |
| pref_gyro_sample_rate | Gyro sample rate (ms) |
| pref_gamepad_deadzone | Gamepad deadzone |
| pref_java_sandbox | Java sandbox |
| pref_ram_allocation | RAM allocation (MB) |
| pref_java_args | Custom Java arguments |
| pref_check_libraries | Verify library integrity |
| pref_arc_capes | Arc capes |
| pref_dump_shaders | Dump shaders |
| pref_big_core_affinity | Big-core affinity |
