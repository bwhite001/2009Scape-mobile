# OSRS Mobile Feature Port — Tier 1 (Input) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the four dependency-free, launcher-side touch features from Tier 1 of the OSRS-mobile feature-port spec — a tap-location indicator, haptic feedback on long-press, a single-tap-right-click mode, and a verification pass over physical keyboard/mouse routing.

**Architecture:** All work lives in the inherited Java GL/input layer (`GLFWGLSurface`, `Touchpad`, `MainActivity`, `LauncherPreferences`, `pref_control.xml`, the app manifest) and routes input exclusively through the existing `org.lwjgl.glfw.CallbackBridge`. No new JNI entrypoints, no `rt4.jar`/RT4-client changes, no dependency on the in-flight Kotlin/Compose modernisation. New settings are added to the existing legacy preference screen (they use existing SharedPreferences keys, so modernisation phase 2b/2d absorbs them for free).

**Tech Stack:** Java 8 (app source level, unchanged), Kotlin 2.0.21, Android (`compileSdk`/`targetSdk` 35, `minSdk 21`), AGP 8.7.3 / Gradle 8.9, Jetpack Compose + Material 3 (the merged launcher shell / `SettingsActivity`), `CallbackBridge` (in the `jre_lwjgl3glfw` JDK-8 shim module).

**Base branch:** `master` at `64349eb69` (Phase 1+2 merged) or later.

## Global Constraints

- **No automated test harness exists.** Per the repo's CLAUDE.md, verification is: *does the APK build, and does it launch + behave correctly on a physical device.* There is no JUnit/instrumented-test infrastructure and adding one for input-injection view code is out of scope (YAGNI). Every task below ends with a **manual on-device smoke test** in place of an automated test cycle.
- **Build recipe (canonical, from CLAUDE.md / CI).** Run from repo root, in this order, every time you need an APK:
  1. `./scripts/languagelist_updater.sh` (regenerates `assets/language_list.txt`; required before any app build)
  2. `./gradlew :jre_lwjgl3glfw:build` (JDK 8 — emits the LWJGL shim asset)
  3. `./gradlew :app_pojavlauncher:assembleDebug` (JDK 17 → `app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk`)
  This requires the full Android toolchain (JDK 8 **and** JDK 17, NDK `25.2.9519653`). The device smoke test is performed by the developer on physical ARM64 hardware (x86 emulator is unsupported by design).
- **All input injection goes through `CallbackBridge`** (`sendMouseButton`, `putMouseEventWithCoords`, `sendScroll`, `sendKeyPress`, `sendCursorPos`). Do not add JNI entrypoints.
- **Match the surrounding PojavLauncher Java style** — minimal, idiomatic diffs (this is a downstream fork).
- **String resources:** user-facing text goes in `app_pojavlauncher/src/main/res/values/strings.xml` (the Crowdin source of truth). Do not hand-add `values-*` locale copies.
- **New preference keys** follow the existing pattern: an `android.preference` entry in `pref_control.xml` (key string) + a `public static` field in `LauncherPreferences` populated in `loadPreferences()`.
- **Runtime-value gotcha (do not "fix"):** `PREF_USE_ALTERNATE_SURFACE` has a field initializer of `true` but `loadPreferences()` sets it from `getBoolean("alternate_surface", false)` → the effective default is **false**, so the live HD path is the **TextureView** branch of `GLFWGLSurface.start()` (the branch that constructs `longPressDetector`). Task 1 depends on this.
- **Commit after each task** with a `feat:`/`chore:` message; keep each task independently revertable.
- **Base = `master` @ Phase 1+2 (`64349eb69` or later).** AGP 8.7.3, Gradle 8.9, Kotlin 2.0.21, compileSdk/targetSdk 35 (app Java source level stays 1.8). The Compose launcher shell — including `SettingsActivity` (the live settings surface) and `PreferencesRepository` — is already merged. The in-game View/GL layer this plan targets (`GLFWGLSurface`, `MainActivity`, `activity_basemain.xml`, the manifest) was on modernisation's *untouchable* list and is unchanged, so this plan's line references still hold.

---

### Task 1: Haptic feedback on long-press right-click

**Files:**
- Modify: `app_pojavlauncher/src/main/AndroidManifest.xml` (add VIBRATE permission)
- Create: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils/Haptics.java`
- Modify: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/GLFWGLSurface.java` (import + `onLongPress`, ~line 223-229)

**Interfaces:**
- Produces: `net.kdt.pojavlaunch.utils.Haptics.vibrate(android.content.Context context, int durationMs)` — static, one-shot vibration, API-gated, no-op if no vibrator; and `Haptics.LONG_PRESS_MS` (int constant, default long-press buzz duration).
- Consumes: nothing from other tasks.

- [ ] **Step 1: Add the VIBRATE permission**

In `AndroidManifest.xml`, alongside the other `<uses-permission>` entries (after the `FOREGROUND_SERVICE` line, ~line 19), add:

```xml
    <uses-permission android:name="android.permission.VIBRATE" />
```

- [ ] **Step 2: Create the Haptics helper**

Create `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils/Haptics.java`:

```java
package net.kdt.pojavlaunch.utils;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/** Minimal one-shot haptic helper for touch interactions. Launcher-side only. */
public final class Haptics {
    private Haptics() {}

    /** Default long-press vibration duration in milliseconds.
     *  Intensity becomes a user setting in a later tier (modernisation phase 2d). */
    public static final int LONG_PRESS_MS = 45;

    /** Fire a one-shot vibration. No-op when the device has no vibrator or duration <= 0. */
    public static void vibrate(Context context, int durationMs) {
        if (context == null || durationMs <= 0) return;
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            //noinspection deprecation
            vibrator.vibrate(durationMs);
        }
    }
}
```

- [ ] **Step 3: Call it from the long-press right-click handler**

In `GLFWGLSurface.java`, add the import near the other `net.kdt.pojavlaunch.utils.*` imports (after line 43):

```java
import net.kdt.pojavlaunch.utils.Haptics;
```

Then in the TextureView branch's `longPressDetector` (currently lines 223-229), add the vibration call inside `onLongPress`, before the right-click is sent:

```java
            longPressDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
                @Override
                public void onLongPress(MotionEvent e) {
                    super.onLongPress(e);
                    Haptics.vibrate(getContext(), Haptics.LONG_PRESS_MS);
                    CallbackBridge.putMouseEventWithCoords(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, CallbackBridge.mouseX, CallbackBridge.mouseY);
                }
            });
```

- [ ] **Step 4: Build**

Run the three-command build recipe from Global Constraints. Expected: `assembleDebug` succeeds; APK produced at `app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk`.

- [ ] **Step 5: On-device smoke test**

Install the APK on the ARM64 device. Launch **Play HD**, log into the world, and **long-press** on the game world.
Expected: a short haptic buzz fires *and* the right-click "Choose Option" menu appears (existing behaviour, now with haptics). Confirm normal short taps do **not** buzz.

- [ ] **Step 6: Commit**

```bash
git add app_pojavlauncher/src/main/AndroidManifest.xml \
        app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils/Haptics.java \
        app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/GLFWGLSurface.java
git commit -m "feat(input): haptic feedback on long-press right-click"
```

**Note / known limitation:** haptics fire only on the TextureView long-press path (the live default). If the user enables the experimental alternate surface (`alternate_surface=true`, SurfaceView branch), `longPressDetector` is not constructed and no haptic fires there — out of scope for Tier 1.

---

### Task 2: Single-tap right-click mode

**Files:**
- Modify: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/prefs/LauncherPreferences.java` (field + load)
- Modify: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/GLFWGLSurface.java` (non-grabbing single-tap path, ~lines 266-269)
- Modify: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt` (one `BoolPref` line in `CONTROL_BOOLS`)

> **Settings home resolved (supersedes decision B).** Phase 2d has already merged: the Compose
> `SettingsActivity` is the live settings surface and its `CONTROL_BOOLS` list is data-driven, so the
> toggle is a **one-line, permanent** addition — rendered and persisted automatically, no legacy XML,
> no strings resource, no rework. (Decision B assumed 2d was still future and would discard a legacy
> toggle; that no longer applies.) Legacy `pref_control.xml` still exists but is superseded — do **not**
> add the toggle there.

**Interfaces:**
- Produces: `LauncherPreferences.PREF_SINGLE_TAP_RIGHTCLICK` (public static boolean; SharedPreferences key `"singleTapRightClick"`, default false).
- Consumes: nothing from other tasks.

- [ ] **Step 1: Add the preference field**

In `LauncherPreferences.java`, add the field next to the other gesture prefs (after `PREF_DISABLE_SWAP_HAND`, line 39):

```java
    public static boolean PREF_SINGLE_TAP_RIGHTCLICK = false;
```

- [ ] **Step 2: Load the preference**

In `LauncherPreferences.loadPreferences(...)`, next to the `disableDoubleTap` load (after line 88), add:

```java
        PREF_SINGLE_TAP_RIGHTCLICK = DEFAULT_PREF.getBoolean("singleTapRightClick", false);
```

- [ ] **Step 3: Route the tap based on the preference**

In `GLFWGLSurface.onTouchEvent`, the non-grabbing single-tap block currently reads (lines 266-269):

```java
            if(mSingleTapDetector.onTouchEvent(e)){ //
                CallbackBridge.putMouseEventWithCoords(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, CallbackBridge.mouseX, CallbackBridge.mouseY);
                return true;
            }
```

Replace it with:

```java
            if(mSingleTapDetector.onTouchEvent(e)){ //
                int tapButton = LauncherPreferences.PREF_SINGLE_TAP_RIGHTCLICK
                        ? LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT
                        : LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT;
                CallbackBridge.putMouseEventWithCoords(tapButton, CallbackBridge.mouseX, CallbackBridge.mouseY);
                return true;
            }
```

- [ ] **Step 4: Add the toggle to the Compose settings**

In `SettingsActivity.kt`, add one entry to the `CONTROL_BOOLS` list (immediately after the `disableDoubleTap` entry, ~line 55):

```kotlin
    BoolPref("singleTapRightClick", "Single-tap opens right-click menu", false),
```

The existing `prefSection` / `BoolRow` machinery renders it under the **Controls** section and persists it through `PreferencesRepository` under the `singleTapRightClick` key — the same key `LauncherPreferences` reads in Step 2. No other change needed.

- [ ] **Step 5: Build**

Run the three-command build recipe. Expected: success.

- [ ] **Step 6: On-device smoke test**

Install and open the launcher. Open **Settings → Controls**; confirm the new **"Single-tap opens right-click menu"** switch is present and defaults **off**. Enable it, launch **Play HD**, log in, single-tap the game world.
Expected (ON): a single tap opens the right-click "Choose Option" menu instead of performing the default action. Toggle OFF → a single tap performs the normal default action again.

- [ ] **Step 7: Commit**

```bash
git add app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/prefs/LauncherPreferences.java \
        app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/GLFWGLSurface.java \
        app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt
git commit -m "feat(input): single-tap right-click mode with Compose settings toggle"
```

**Note / scope:** affects the non-grabbing (visible-cursor) tap path — the primary path for a point-and-click RS client; grab-mode (first-person capture) is unchanged. The toggle lands in the merged Compose `SettingsActivity` (`CONTROL_BOOLS`), persisted via `PreferencesRepository` under `singleTapRightClick` — the same key `LauncherPreferences` reads for the game process.

---

### Task 3: Tap-location indicator overlay

**Files:**
- Create: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/TapIndicatorView.java`
- Modify: `app_pojavlauncher/src/main/res/layout/activity_basemain.xml` (add overlay to `content_frame`)
- Modify: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java` (bind the view)
- Modify: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/GLFWGLSurface.java` (trigger on `ACTION_DOWN`)

**Interfaces:**
- Produces:
  - `net.kdt.pojavlaunch.TapIndicatorView` (a `View`; not clickable/focusable → passes touch through).
  - `TapIndicatorView.showAt(float x, float y)` — instance; animate a ring at view-space coordinates.
  - `TapIndicatorView.showTap(float x, float y)` — static; forwards to the active instance (no-op if none). Called by `GLFWGLSurface`.
- Consumes: nothing from other tasks.

- [ ] **Step 1: Create the overlay view**

Create `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/TapIndicatorView.java`:

```java
package net.kdt.pojavlaunch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * Transparent full-screen overlay that draws a brief fading ring at the last tap
 * location, so the touch point is visible behind the finger. Launcher-only visual;
 * it never consumes touch (not clickable / not focusable).
 *
 * Single colour by design: the OSRS red-vs-yellow ("interacting vs not") distinction
 * requires knowing whether the tap hit an entity, which is client-side (RT4) knowledge
 * not available in the launcher. That variant belongs to the Bucket B client spec.
 */
public class TapIndicatorView extends View {
    private static final long DURATION_MS = 350L;
    private static final float MAX_RADIUS_DP = 22f;

    private static TapIndicatorView sInstance;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mMaxRadiusPx;
    private float mTapX, mTapY;
    private long mStartTime = -1L;

    public TapIndicatorView(Context context) { this(context, null); }

    public TapIndicatorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(false);
        setClickable(false);
        mMaxRadiusPx = MAX_RADIUS_DP * getResources().getDisplayMetrics().density;
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(Math.max(2f, mMaxRadiusPx * 0.12f));
        sInstance = this;
    }

    /** Trigger a ring centred at the given view-space (screen-pixel) coordinates. */
    public void showAt(float x, float y) {
        mTapX = x;
        mTapY = y;
        mStartTime = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    /** Forward a tap to the active overlay instance, if any. */
    public static void showTap(float x, float y) {
        TapIndicatorView v = sInstance;
        if (v != null) v.showAt(x, y);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mStartTime < 0) return;
        long elapsed = SystemClock.uptimeMillis() - mStartTime;
        if (elapsed >= DURATION_MS) { mStartTime = -1L; return; }
        float t = elapsed / (float) DURATION_MS;              // 0..1 progress
        float radius = mMaxRadiusPx * (0.35f + 0.65f * t);    // grows outward
        int alpha = (int) (200 * (1f - t));                   // fades out
        mPaint.setColor(Color.argb(alpha, 255, 225, 90));     // yellow ring
        canvas.drawCircle(mTapX, mTapY, radius, mPaint);
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (sInstance == this) sInstance = null;
    }
}
```

- [ ] **Step 2: Add the overlay to the layout**

In `activity_basemain.xml`, add the overlay as the last child of `ControlLayout` (`main_control_layout`), immediately after the `DrawerPullButton` (`drawer_button`) closing tag and before `</net.kdt.pojavlaunch.customcontrols.ControlLayout>`:

```xml
			<net.kdt.pojavlaunch.TapIndicatorView
				android:id="@+id/main_tap_indicator"
				android:layout_width="match_parent"
				android:layout_height="match_parent"
				android:elevation="5dp"/>
```

(Placing it last within `ControlLayout` draws it on top of the game surface and controls; being non-clickable, it passes touches through to the siblings beneath. It shares `GLFWGLSurface`'s coordinate space — both are full-bleed children under the same `content_frame` padding — so raw touch coordinates line up.)

- [ ] **Step 3: Bind the view in MainActivity**

In `MainActivity.java`, add a field next to the other view fields (after line 70, `private LoggerView loggerView;`):

```java
    private TapIndicatorView tapIndicatorView;
```

Then in `bindValues()` (after `contentFrame = findViewById(R.id.content_frame);`, line 211), add:

```java
        tapIndicatorView = findViewById(R.id.main_tap_indicator);
```

(Binding it holds a non-static reference for lifecycle clarity; `GLFWGLSurface` reaches it via the static `showTap` hook, which the view nulls in `onDetachedFromWindow`.)

- [ ] **Step 4: Trigger the indicator on tap-down**

In `GLFWGLSurface.onTouchEvent`, right after the gesture detectors are fed (after `longPressDetector.onTouchEvent(e);`, line 244), add:

```java
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            TapIndicatorView.showTap(e.getX(), e.getY());
        }
```

- [ ] **Step 5: Build**

Run the three-command build recipe. Expected: success.

- [ ] **Step 6: On-device smoke test**

Install, launch **Play HD**, log in. Tap around the game world.
Expected: each tap shows a brief (~350ms) yellow ring that grows and fades at the touch point; taps still register/interact exactly as before (the ring never blocks input). Open the on-screen control buttons and confirm they still work (overlay does not steal their touches).

- [ ] **Step 7: Commit**

```bash
git add app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/TapIndicatorView.java \
        app_pojavlauncher/src/main/res/layout/activity_basemain.xml \
        app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java \
        app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/GLFWGLSurface.java
git commit -m "feat(input): tap-location indicator overlay"
```

---

### Task 4: Verify physical keyboard (F-keys) & mouse routing

This is an **investigation task**, not a code task — Tier 1 §1.7/§1.8 is "verify," because the routing already partly exists (`GLFWGLSurface.dispatchGenericMotionEvent` handles `SOURCE_MOUSE` scroll/hover/buttons at lines 447-485; `processKeyEvent` handles keys at line 512 via `EfficientAndroidLWJGLKeycode`). The deliverable is a findings note; any brokenness found becomes a follow-up code task, out of this plan's scope.

**Files:**
- Create: `docs/superpowers/notes/2026-07-07-input-routing-verification.md`

**Interfaces:** none.

- [ ] **Step 1: Build a current-code APK**

Run the three-command build recipe on the branch with Tasks 1-3 merged. (No code change in this task; you are testing existing routing.)

- [ ] **Step 2: On-device test — physical keyboard**

Pair a Bluetooth or connect a USB-OTG keyboard. Launch **Play HD**, log in. Press each of `F1`-`F12`, the number row `1`-`9`, and typing keys (e.g. in the chat box).
Record for each: did the client respond as it does on desktop? (F-keys → client tab/keybind actions; numbers → hotbar/where applicable; letters → chat input.)

- [ ] **Step 3: On-device test — physical mouse**

Pair/connect a mouse. Test: left-click (default action), right-click (Choose Option menu), scroll wheel (camera zoom), and cursor hover movement.
Record which work and which do not.

- [ ] **Step 4: Write the findings note**

Create `docs/superpowers/notes/2026-07-07-input-routing-verification.md` recording, per input, **works / broken / partial**, with the device model + Android version tested. For anything broken, name the suspect code path so a follow-up task can target it:
- F-key / keyboard issues → `GLFWGLSurface.processKeyEvent` (line 512) and the key map in `utils/EfficientAndroidLWJGLKeycode` / `utils/LwjglGlfwKeycode`.
- Mouse button/scroll/hover issues → `GLFWGLSurface.dispatchGenericMotionEvent` (line 447) and `sendMouseButtonUnconverted` (line 564).

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/notes/2026-07-07-input-routing-verification.md
git commit -m "docs: physical keyboard/mouse input routing verification findings"
```

**Follow-up trigger:** if Step 4 records any "broken/partial", open a small follow-up plan scoped to those specific code paths. Do not fix them inline here — Tier 1 scoped this as verification only.

---

## Task dependency & ordering

Tasks 1, 2, 3 are **independent** and touch mostly disjoint code (Task 1 & 2 & 3 each touch `GLFWGLSurface.java` in different, non-overlapping regions — long-press ctor, single-tap block, and `ACTION_DOWN` respectively — so if run in parallel, merge them in listed order to keep the diffs clean). Task 4 should run **last**, on a build that already includes 1-3, so the verification reflects shipping code. Recommended execution order: **1 → 2 → 3 → 4**.
