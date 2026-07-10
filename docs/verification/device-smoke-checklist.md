# Device Smoke Checklist

Run on a physical **ARM64** device (x86 emulator is unsupported). Build the
debug APK (CI `app-debug` artifact or `./gradlew :app_pojavlauncher:assembleDebug`)
and `adb install -r`. Tick every item before merging a shell change.

## Launch (unchanged native paths)
- [ ] App launches to the home screen without crash (`adb logcat` clean of FATAL).
- [ ] **Play HD** boots into the game (GL surface renders, reaches the client login screen).
- [ ] **Play SD** launches the AWT path without crash.
- [ ] First run: runtime/asset unpack shows progress and completes; game launches afterward.
- [ ] SD: two-finger map-drag, lift one finger mid-drag — camera pans cleanly, no stuck click.

## Stylus (if device available)
- [ ] (S-Pen only) Finger down, stylus barrel-tap, lift stylus first — no stuck right-click.

## Compose home (ScapeLauncher)
- [ ] Home renders (title, Play HD, Play SD, Settings).
- [ ] Progress indicator appears while unpack/download tasks run and clears when done.
- [ ] Tapping Play while a task is running shows the "tasks in progress" toast, not a launch.

## Compose settings (SettingsActivity)
- [ ] Settings opens from the home button.
- [ ] Each toggle persists across app restart.
- [ ] Each slider persists across app restart.
- [ ] "Custom Java arguments" typing is smooth (no per-keystroke stall) and persists.
- [ ] A changed setting is honored by the game on next launch.
- [ ] "Edit on-screen controls" opens the controls editor.
- [ ] "Advanced" opens the legacy dialog (renderer, runtime, plugins, import).

## Missing-storage screen
- [ ] When storage is unavailable, the Compose alert screen renders (image + text).

## Import flows (if changed)
- [ ] Importing a benign plugin .zip extracts into plugins/ and appears in the list.
- [ ] Importing a config.json replaces config and prompts restart.
- [ ] A plugin .zip whose entries contain `../` is rejected (not written outside plugins/).
