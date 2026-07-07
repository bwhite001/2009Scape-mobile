# 2009Scape-mobile — OSRS Mobile Feature Port (Design Spec)

**Date:** 2026-07-07
**Repo:** `bwhite001/2009Scape-mobile` (PojavLauncher fork running the 2009Scape RT4-Client)
**Reference client:** Official OSRS Android (`com.jagex.oldscape.android`, v239.2), UI 2.0 (Nov 2024)
**Status:** Spec only — no implementation authorised yet.

> This spec catalogues OSRS-mobile touch/QoL features and defines which are worth porting to
> this launcher. It **supersedes the handed-in "OSRS Mobile — Feature Analysis" draft**, whose
> per-feature "Current 2009Scape state" fields were largely guesses written against a generic
> PojavLauncher mental model. Every state claim below is **measured from this checkout** as of
> this date. The OSRS *behavioural* research from that draft is preserved; only the state/effort
> assessments and the roadmap are corrected.

---

## 1. Purpose & relationship to the modernisation effort

Two efforts touch this repo:

- **Android modernisation** (`docs/superpowers/specs/2026-07-07-android-modernisation-design.md`) —
  build foundation → Kotlin/Compose shell → compliance → network. Currently at **phase 2a**.
  It deliberately **freezes the in-game GL/input/JNI layer** as "do not refactor":
  `GLFWGLSurface`, `Touchpad`, `CallbackBridge`, `customcontrols/*`, `MainActivity`, the JNI core.
- **This spec** — mobile *gameplay* feature parity (touch gestures, on-screen QoL, in-client helpers).

Nearly all launcher-side features here live **inside that frozen layer**. That is not a conflict:
the modernisation freezes it against *Compose/Kotlin refactoring*, not against *additive Java feature
work*. The two efforts touch disjoint file sets — the modernisation works the pre-game **shell**
(screens, prefs, async), this spec works the in-game **input surface**.

### The sequencing rule (engine vs. settings-surface)

The boundary that matters is **not** "input vs. shell." It is **engine vs. settings-surface**:

- **Input/gesture/overlay *engine* work** → the frozen Java GL classes (`GLFWGLSurface`, `Touchpad`,
  `customcontrols/`). Modernisation never touches these, so this work can proceed **any time, in
  parallel, with zero merge collision**.
- **The *settings/preference surface* for each feature** (toggles, sliders) → this is exactly what
  modernisation **phase 2b** (`PreferencesRepository` over SharedPreferences) and **phase 2d**
  (Compose settings screen) rebuild. Adding settings to the legacy `MyDialogFragment` /
  `LauncherPreferences` now means phase 2d has to migrate them → double work + merge pain.

**Rule:** build a feature's *engine* against the existing Java classes now; build its *settings
surface* on top of the modernised prefs (after 2b/2d). A feature with **no** setting (e.g. tap
indicator) can ship end-to-end now. A feature that is **mostly** a setting (e.g. haptic-intensity
picker) should wait for 2b/2d, or land its engine now behind a hardcoded default and get its UI later.

New preference *keys* may be added to `LauncherPreferences` at any time (phase 2b wraps existing keys
without renaming them); it is the *settings UI* that should not be built twice.

---

## 2. Measured current-state baseline

Grep-verified against this checkout, not assumed. This table is the spec's single biggest correction
to the handed-in draft.

| # | Feature | Handed-in draft claim | **Measured reality** |
|---|---|---|---|
| §1.1 | Tap = left click | Done | **Done.** `GLFWGLSurface` sends scaled `sendCursorPos`/`sendMouseButton`. |
| §1.2A | Long-press right-click | Done | **Done.** `Touchpad`/`GLFWGLSurface` + `PREF_LONGPRESS_TRIGGER` (default **300ms**, already configurable — draft wrongly said "add a delay pref"). |
| §1.2B | Single-tap right-click mode | Absent | **Absent.** Related pref `PREF_DISABLE_GESTURES` exists but is not this. Genuine gap. |
| §1.3 | Pinch-to-zoom | Unknown / likely absent, Medium | **Done.** `GLFWGLSurface.ScaleListener` maps pinch → **F3/F4** key presses. Only *retune* (discrete F-key vs. smooth scroll) is open. |
| §1.4 | Camera rotate (swipe / hold) | Swipe exists; hold-no-move unknown | **Partial.** Gyro camera system present (`PREF_ENABLE_GYRO`, sensitivity/smoothing/invert-X/Y). **Hold-to-rotate-without-moving** gesture is absent. |
| §1.5 | Tap-to-drop | Not confirmed | **Absent** (launcher side). Client side needs rt4-client (see §5). |
| §1.6 | Configurable tap delay | — | **Present** via `PREF_LONGPRESS_TRIGGER` + `TapDetector` (TAP_MAX_DELTA_MS=300). |
| §1.7/§1.8 | External keyboard F-keys | Partial | F-key maps exist (`EfficientAndroidLWJGLKeycode`, `LwjglGlfwKeycode`). End-to-end physical-keyboard F1–F12 routing **needs verification on hardware**. |
| §1.8 | Mouse (`onGenericMotionEvent`) | "may be swallowed" | **Done for scroll.** `GLFWGLSurface.onGenericMotionEvent` handles `SOURCE_MOUSE` `AXIS_HSCROLL`/`AXIS_VSCROLL` → `sendScroll`, plus hover/button paths. Verify button + hover completeness. |
| §3.2 | Resolution scaling | Done | **Done.** `PREF_SCALE_FACTOR` (default 60). Slider UI is a phase-2d shell item. |
| §3.4 | Tap indicator overlay | Unknown | **Absent.** Pure launcher overlay — no dependency. |
| §5.1 | Haptic feedback | Absent | **Absent.** No `Vibrator`/`VibrationEffect`/`performHapticFeedback` anywhere. Genuine gap. |
| §4.1 | EGL-preserve on pause | Partial | `setPreserveEGLContextOnPause` **not set**. Overlaps modernisation — see §6. |
| §4.3 | Split-screen / config change | Broken | Not handled. Overlaps modernisation — see §6. |
| — | Existing input stack (context) | (draft assumed bare `onTouchEvent`) | Rich: `GLFWGLSurface` + `Touchpad` + `TapDetector` + `customcontrols/` (JSON control map) + `gamepad/` + `GyroControl`. New gestures must **coexist** with the control-map overlay, not replace it. |

**Takeaway:** of the draft's 8 "Sprint 1" items, **3 are already done** (pinch, long-press delay,
mouse scroll), **2 need only verification** (F-key routing, mouse button/hover completeness), and
**3 are genuine gaps** (single-tap right-click, hold-to-rotate, tap indicator + haptics).

---

## 3. Feature inventory (re-bucketed with verified state)

Buckets unchanged from the draft: **A** = launcher/shell Java; **B** = RT4-client Java (`rt4.jar`);
**C** = server/protocol. The OSRS behaviour descriptions are carried over from the draft; the
**state** and **effort** columns are corrected here.

### Bucket A — launcher-side (this repo, frozen Java GL layer)

| Ref | Feature | Verified state | Real effort | Notes |
|---|---|---|---|---|
| §1.2B | Single-tap right-click toggle | Gap | Low | Route no-move `DOWN`+`UP` as `BUTTON_RIGHT`. New pref key. |
| §1.3 | Pinch-zoom **retune** | Done (F3/F4) | Low | Optional: swap discrete F-key for smoother scroll; may not be worth it. |
| §1.4 | Hold-to-rotate-without-moving | Gap | Medium | Timed state machine in `GLFWGLSurface`; must not fight `TapDetector`/long-press/gyro. |
| §3.4 | Tap indicator overlay | Gap | Medium | Transparent `Canvas` overlay `View` above the surface; **no setting → shippable now**. |
| §5.1 | Haptic feedback | Gap | Low | `VibrationEffect` on long-press trigger; intensity is a setting → engine now, UI post-2d. |
| §1.7/1.8 | Physical keyboard F-keys | Verify | Low–Med | Confirm `KeyEvent` F1–F12 → GLFW mapping on hardware. |
| §1.8 | Mouse button/hover routing | Verify | Low | Scroll done; confirm button + `HOVER_MOVE`. |
| §2.1/2.2 | Overlay hotkey strip + profiles | Gap | Medium | Fits the **existing `customcontrols/` control-map system** — extend it, don't build a parallel overlay. Reassess against control-map capabilities first. |

### Bucket B — RT4-client changes (**separate future spec**, see §5)

Tap-to-drop (§1.5, client half), collapsible chat/minimap (§2.4/2.6), chat timestamps (§2.5),
view-distance pref plumbing (§3.1), tooltip long-hold (§5.2), tile markers (§3.3), NPC/entity
highlighting (§5.3), menu-entry swapper (§5.4), and all skill-QoL overlays (§6). **None can start
until a client build exists** — see §5.

### Bucket C — server/protocol

Hiscores in-client lookup and session tokens. **Out of scope** for this LAN fork.

---

## 4. Priority tiers (not a calendar)

This is a solo project; the draft's "1-week sprints" are replaced by value/independence tiers.

**Tier 1 — ship now, no dependencies, high friction relief:**
1. Tap indicator overlay (§3.4) — no setting, pure overlay.
2. Haptic feedback engine (§5.1) — hardcoded intensity default; picker later.
3. Single-tap right-click mode (§1.2B) — engine now, toggle later.
4. Verify F-key + mouse button/hover routing (§1.7/1.8) — may be free.

**Tier 2 — engine now, settings after modernisation 2b/2d:**
5. Hold-to-rotate-without-moving (§1.4) — the trickiest gesture-arbitration work.
6. Overlay hotkey strip via `customcontrols/` (§2.1/2.2) — scope against existing control-map first.
7. Pinch-zoom retune (§1.3) — only if the F-key zoom feels bad in practice.

**Tier 3 — deferred to the Bucket B spec (§5).**

**Not scheduled here (owned by modernisation spec):** EGL-preserve (§4.1), split-screen/config-change
(§4.3), foreground-service-type + POST_NOTIFICATIONS, encrypted credentials (§4.2). See §6.

---

## 5. Bucket B prerequisite — standing up the rt4-client build

**Hard blocker.** `rt4.jar` in `app_pojavlauncher/src/main/assets/` is a **prebuilt artifact**. The
client source (`gitlab.com/downthecrop/rt4-client`, branch `lwjgl-mobile-callbacks`) is **not checked
out anywhere in this repo**. Therefore **no Bucket B feature can begin** until:

- **Task zero:** check out `downthecrop/rt4-client` @ `lwjgl-mobile-callbacks`, build it (JDK 8,
  `./gradlew jar` per the server-repo CLAUDE.md), and confirm the freshly built `rt4.jar` boots on
  device when dropped into assets (flip the `copyAssetFile(..., false)` → `true` in
  `AsyncAssetManager.unpackComponents` so the new jar overwrites the stale unpacked copy).

Only once that loop works does Bucket B become tractable. **All Bucket B features are therefore
carved out into a dedicated future spec** (`…-osrs-mobile-client-features-design.md`) that begins with
this prerequisite. This spec does not plan them beyond listing them in §3.

---

## 6. Non-goals & de-scoped items

- **Bucket B client features** — deferred to their own spec (§5).
- **Bucket C** (hiscores, session tokens) — out of scope for a private LAN fork.
- **Session/lifecycle items** (EGL-preserve §4.1, split-screen §4.3, FGS type, POST_NOTIFICATIONS,
  encrypted credentials §4.2) — **owned by the modernisation spec (Phase 3/5)**; cross-referenced,
  not duplicated here.
- **Full dual-column side-panel rearrangement** (§2.1 full) — major client UI surgery; not worth it.
- **App-store / Sentry / signing framing** from the draft — modernisation Phase 5 concern, not a
  gameplay feature.
- **New JNI entrypoints** — all input injection routes through the existing
  `CallbackBridge` (in the `jre_lwjgl3glfw` JDK-8 shim module), per the modernisation freeze.

---

## 7. Implementation notes (carried from draft, corrected)

- **Coordinate scaling is already handled** in `GLFWGLSurface` via `mScaleFactor =
  PREF_SCALE_FACTOR/100f` (applied at lines ~254/263). New gestures that inject cursor positions must
  reuse `mScaleFactor` — do not re-derive scaling.
- **Gesture arbitration is the real risk**, not the injection. `TapDetector`, long-press right-click,
  gyro camera, pinch (F3/F4), and the `customcontrols/` overlay all already consume touch events. Any
  new gesture (hold-to-rotate especially) must be defined as a state machine that coexists with these,
  with explicit priority ordering, or it will mis-fire.
- **Overlay views** (tap indicator, hotkey strip) must be `focusable="false"` and pass non-consumed
  touches through to `GLFWGLSurface` beneath.
```
FrameLayout (root)
  ├── GLFWGLSurface        — game render + existing input (unchanged)
  ├── TapIndicatorView     — transparent, passthrough
  └── HotkeyOverlay        — prefer extending customcontrols/ over a new sibling view
```
- **`CallbackBridge` surface** used by all Bucket A work:
  `sendCursorPos`, `sendMouseButton`, `sendScroll`, `sendKeyPress`.
