# Bug-Fix Verification Pass — July 2026

Confirms the launcher-side fixes already shipped in the deployed APK actually
work on-device, and correctly re-classifies the bugs that are **not** launcher
bugs. See `../bugfix-2026-07-status.md` for the corrected tracker.

**APK under test:** `/runescape/webroot/2009Scape-mobile-tier1-debug.apk`
(built 2026-07-11, contains commit `5560d767f` — keyboard viewport + FGS type).
No rebuild needed. No ADB on host → sideload from the LAN download page
(`http://192.168.0.243:8080/`). Run on a physical **ARM64** device.

> Do a **fresh install** (uninstall first, or clear app data) so stale
> device-side unpacked assets from a prior APK don't skew the result.

## Preflight (Bug 4 — environment, not code)
- [ ] Game server running on `192.168.0.243`, port `43595` listening.
- [ ] Phone on the same `192.168.0.x` subnet (WiFi, not mobile data).
- [ ] Firewall allows inbound `43595` (and `8080` for the download page).
- [ ] `config.json` on device points at `192.168.0.243`, all ports `43595`.
      (This fork reads **`config.json`** via `-DconfigFile`, *not* `settings.json`.)

## Shipped fixes to confirm
- [ ] **Bug 3 (P0 crash):** app launches on an Android 14+ device with no crash
      dialog; logcat has no `MissingForegroundServiceTypeException`.
- [ ] Progress notification appears during asset/runtime unpack (POST_NOTIFICATIONS
      prompt on first launch, API 33+).
- [ ] **Bug 1 (keyboard):** Play HD → tap chat input → soft keyboard appears →
      game view shifts up, chat line stays visible above the keyboard.
- [ ] Dismissing the keyboard restores the full viewport, no black bar/artifact.
- [ ] Reaches the client **login screen** (no `error_game_js5connect`).
- [ ] Logcat shows **no** `Unable to load plugin __MACOSX` (fresh install; assets
      are clean, so this only appears if old app-data survived).

## Confirm-still-broken (rt4-client repo, not this launcher)
Expected to FAIL here — `rt4.jar` is unchanged. Failing confirms they are
client-side and belong in `downthecrop/rt4-client` (`lwjgl-mobile-callbacks`).
- [ ] **Bug 2/6:** minimap tile and world map render blank/black (HD **and** SD).
- [ ] **Bug 7:** minimap widget bleeds into the world map view.
- [ ] **Bug 8:** world map close button inconsistent / z-order artifact.

## Needs a verdict (drives a follow-up ticket if confirmed)
- [ ] **Bug 5:** in-game KB/MOUSE/SHIFT/TAB overlay labels — clipped/wrapped, or
      full? If still clipped, the fix is in the control-layout JSON
      (`assets/default.json`) / customcontrols rendering — **not** in
      `activity_java_gui_launcher.xml` as the original plan stated. File a
      separate ticket with the on-device screenshot.
