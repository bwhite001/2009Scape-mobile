# Plan 017: Null `MainActivity`'s static View fields in `onDestroy` and guard their static readers

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 8ee361ea1..HEAD -- app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java`
> If this file changed since this plan was written, compare the "Current
> state" excerpts against the live code before proceeding; on a mismatch,
> treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: S-M
- **Risk**: MED (static access from native callbacks — a wrong guard can turn
  a previously-working late callback into a silent no-op, or a missed guard
  can turn this fix into a new NPE source)
- **Depends on**: none
- **Category**: bug (memory leak + latent NPE)
- **Planned at**: commit `8ee361ea1`, 2026-07-10

## Why this matters

`MainActivity` holds three fields as `static`: `touchpad` (`private static
Touchpad`), `touchCharInput` (`public static TouchCharInput`), and
`mControlLayout` (`public static ControlLayout`). They're assigned from
`findViewById` in `bindValues()`, called from `initLayout()` in `onCreate`.
Each of these `View` subclasses holds an implicit reference to the `Activity`
context it was created from (standard Android `View`→`Context` chain). Because
the fields are `static`, that reference chain is retained at the *class*
level, not the *instance* level — so every time `MainActivity` is destroyed
(configuration change, back-navigation-and-relaunch, process do-over), the
just-destroyed `Activity` and its entire View tree stay reachable through
these statics until the *next* `MainActivity` instance overwrites them in its
own `bindValues()`. That's a real (if bounded) leak on every activity
recreation. Separately, static entry points that the native/JNI side calls
into (`toggleMouse`, `openLink`, `openPath`) dereference these fields
directly with no null-check; a native callback that fires in the narrow
window after `onDestroy()` but before the next `bindValues()` populates fresh
values would NPE. The fix has two parts that must both land together: null
the fields in `onDestroy()` (fixes the leak), and add null-guards to every
static reader that doesn't already have one (prevents the NPE the nulling
would otherwise introduce).

## Current state

`app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java` — all
line numbers below confirmed by direct read against the live file.

Static field declarations, `MainActivity.java:63-77`:
```java
63  public static volatile ClipboardManager GLOBAL_CLIPBOARD;
64  public static final String INTENT_MINECRAFT_VERSION = "intent_version";
65
66  volatile public static boolean isInputStackCall;
67
68  public static TouchCharInput touchCharInput;
69  private GLFWGLSurface minecraftGLView;
70  private static Touchpad touchpad;
71  private LoggerView loggerView;
72  private DrawerLayout drawerLayout;
73  private ListView navDrawer;
74  private View mDrawerPullButton;
75  private View contentFrame;
76  private GyroControl mGyroControl = null;
77  public static ControlLayout mControlLayout;
```
(`GLOBAL_CLIPBOARD` at line 63 is explicitly out of scope for this plan — see
Scope below; it's a system service reference, not a `View`, and is lower risk.)

Assignment site, `bindValues()`, `MainActivity.java:207-222`:
```java
207  private void bindValues(){
208      mControlLayout = findViewById(R.id.main_control_layout);
209      minecraftGLView = findViewById(R.id.main_game_render_view);
210      touchpad = findViewById(R.id.main_touchpad);
211      drawerLayout = findViewById(R.id.main_drawer_options);
212      navDrawer = findViewById(R.id.main_navigation_view);
213      loggerView = findViewById(R.id.mainLoggerView);
214      loggerView.setVisibility(View.VISIBLE);
215      mControlLayout = findViewById(R.id.main_control_layout);
216      touchCharInput = findViewById(R.id.mainTouchCharInput);
217      mDrawerPullButton = findViewById(R.id.drawer_button);
218      contentFrame = findViewById(R.id.content_frame);
219      int inset = (int) PREF_INSET_X;
220      //touchpad.setPadding(inset,inset,inset,inset);
221      contentFrame.setPadding(inset,inset,inset,inset);
222  }
```
(Note line 208 and 215 both assign `mControlLayout` — a pre-existing harmless
duplicate in the current code; not in scope to dedupe here, leave it as-is.)

Current `onDestroy()`, `MainActivity.java:253-258` — does **not** null any of
the three static View fields:
```java
253  @Override
254  protected void onDestroy() {
255      super.onDestroy();
256      CallbackBridge.removeGrabListener(touchpad);
257      CallbackBridge.removeGrabListener(minecraftGLView);
258  }
```

Every reference to `touchpad`, `mControlLayout`, or `touchCharInput` in the
file (from `grep -n "touchpad\|mControlLayout\|touchCharInput"
app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java`),
classified by whether the enclosing method is `static` (and therefore a
possible post-`onDestroy()` native entry point) or an instance method (only
reachable while some `MainActivity` instance is alive and, for lifecycle
callbacks, only in a state where the fields are already known-populated):

**Static methods that dereference these fields (the ones this plan must guard):**
- `toggleMouse(Context ctx)`, `MainActivity.java:329-336`:
  ```java
  329  public static void toggleMouse(Context ctx) {
  330      if (CallbackBridge.isGrabbing()) return;
  331
  332      boolean mouseOn = touchpad.switchState();
  333      mControlLayout.setMouseButtonsVisible(mouseOn);
  334      Toast.makeText(ctx, mouseOn ? R.string.control_mouseon : R.string.control_mouseoff,
  335              Toast.LENGTH_SHORT).show();
  336  }
  ```
  Uses both `touchpad` (line 332) and `mControlLayout` (line 333) with no null-check.
- `openLink(String link)`, `MainActivity.java:433-444`:
  ```java
  433  public static void openLink(String link) {
  434      Context ctx = touchpad.getContext(); // no more better way to obtain a context statically
  435      ((Activity)ctx).runOnUiThread(() -> {
  ...
  444  }
  ```
  Uses `touchpad` (line 434) with no null-check.
- `openPath(String path)`, `MainActivity.java:446-457`:
  ```java
  446  public static void openPath(String path) {
  447      Context ctx = touchpad.getContext(); // no more better way to obtain a context statically
  448      ((Activity)ctx).runOnUiThread(() -> {
  ...
  457  }
  ```
  Uses `touchpad` (line 447) with no null-check.

**Static method already correctly guarded (confirm, do not re-guard):**
- `switchKeyboardState()`, `MainActivity.java:371-373`:
  ```java
  371  public static void switchKeyboardState() {
  372      if(touchCharInput != null) touchCharInput.switchKeyboardState();
  373  }
  ```

**Other static methods in the file that do NOT touch these three fields**
(confirmed by reading `fullyExit()` at line 292, `isAndroid8OrHigher()` at
line 296, `dialogForceClose(Context)` at line 338, `querySystemClipboard()`
at line 459, `putClipboardData(String, String)` at line 477 — the latter two
use `GLOBAL_CLIPBOARD`, which is out of scope) — no changes needed to these.

**Instance methods/lambdas that reference the fields** (out of scope to
guard — they only run while an activity instance exists and, for Android
lifecycle callbacks specifically, Android guarantees they don't fire after
`onDestroy()` for that instance): `onCreate` (line 88), `initLayout` (lines
104-110, 117, 127, 139, 162-172), `loadControls` (lines 183-197),
`bindValues` (the assignment site itself, lines 208-216), `onConfigurationChanged`
(line 267), `onActivityResult` (line 285), `openCustomControls` (line 318),
`dispatchKeyEvent` (line 355, line 362), `exitEditor` (lines 500-505).

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Build APK | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` | `BUILD SUCCESSFUL`; APK at `app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk` |

(No host JDK/Android SDK exists in this environment — always run through the
`2009scape-apk-builder` Docker image, per repo `CLAUDE.md` / `MEMORY.md`.)

## Scope

**In scope** (the only file you should modify):
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java`:
  - `onDestroy()` (lines 253-258): add nulling of `touchpad`, `touchCharInput`, `mControlLayout`.
  - `toggleMouse(Context)` (lines 329-336): add a null-guard.
  - `openLink(String)` (lines 433-444): add a null-guard.
  - `openPath(String)` (lines 446-457): add a null-guard.

**Out of scope** (do NOT touch, even though they look related):
- `GLOBAL_CLIPBOARD` (line 63) — a `ClipboardManager` system-service
  reference, not a `View`; lower risk (no `Activity` context chain the same
  way), and `querySystemClipboard`/`putClipboardData` are not part of this
  finding. Leave as-is.
- `switchKeyboardState()` (lines 371-373) — already correctly guarded; do not
  modify (re-guarding it is a no-op edit that adds noise to the diff).
- `BaseActivity.java` — confirmed (by reading the file) to have no
  `onDestroy` override and no nulling logic; this plan does not add any to
  it. Leave it as-is.
- Any other activity (`JavaGUILauncherActivity`, `ScapeLauncher`, etc.) — not
  part of this finding.
- The duplicate `mControlLayout = findViewById(...)` assignment in
  `bindValues()` (lines 208 and 215) — harmless pre-existing duplication, not
  in scope to dedupe here.
- `minecraftGLView` — it is an *instance* field (line 69: `private
  GLFWGLSurface minecraftGLView;`), not static; not part of this finding.

## Git workflow

- Branch: `advisor/017-null-mainactivity-static-views-ondestroy`
- Commit per logical unit (or one commit for the whole plan, since the
  nulling and the guards must land together to avoid introducing new NPEs —
  prefer a single commit so the fix is never in a half-applied state on
  `master`), conventional-commit style. Suggested message:
  `fix: null MainActivity's static View fields in onDestroy and guard their static readers`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Null the three static View fields in `onDestroy()`

After the existing `removeGrabListener` calls, add nulling of `touchpad`,
`touchCharInput`, and `mControlLayout`:

```java
@Override
protected void onDestroy() {
    super.onDestroy();
    CallbackBridge.removeGrabListener(touchpad);
    CallbackBridge.removeGrabListener(minecraftGLView);
    touchpad = null;
    touchCharInput = null;
    mControlLayout = null;
}
```

Order matters: `removeGrabListener(touchpad)` must run *before* `touchpad` is
nulled (it still needs the live reference to find/remove itself from the
listener list) — the shape above already puts the nulling after both
`removeGrabListener` calls, so this is satisfied.

**Verify**: `grep -n "touchpad = null\|touchCharInput = null\|mControlLayout = null" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java` → three matches, all inside `onDestroy()`.

### Step 2: Guard `toggleMouse(Context)`

Add a null-check at the top of `toggleMouse` so a call after `onDestroy()`
no-ops instead of NPEing on `touchpad.switchState()` or
`mControlLayout.setMouseButtonsVisible(...)`:

```java
public static void toggleMouse(Context ctx) {
    if (CallbackBridge.isGrabbing()) return;
    if (touchpad == null || mControlLayout == null) return;

    boolean mouseOn = touchpad.switchState();
    mControlLayout.setMouseButtonsVisible(mouseOn);
    Toast.makeText(ctx, mouseOn ? R.string.control_mouseon : R.string.control_mouseoff,
            Toast.LENGTH_SHORT).show();
}
```

**Verify**: `grep -n "touchpad == null || mControlLayout == null" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java` → one match, inside `toggleMouse`.

### Step 3: Guard `openLink(String)`

`openLink` uses `touchpad.getContext()` purely as a static way to obtain a
live `Context` (per its own comment). If `touchpad` is null there is no
context to act on, so the method should simply return:

```java
public static void openLink(String link) {
    if (touchpad == null) return;
    Context ctx = touchpad.getContext(); // no more better way to obtain a context statically
    ((Activity)ctx).runOnUiThread(() -> {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            setUri(ctx, link, intent);
            ctx.startActivity(intent);
        } catch (Throwable th) {
            Tools.showError(ctx, th);
        }
    });
}
```

**Verify**: `grep -n "if (touchpad == null) return;" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java` → two matches (this one and the one added in Step 4).

### Step 4: Guard `openPath(String)`

Same pattern as `openLink`:

```java
@SuppressWarnings("unused") //TODO: actually use it
public static void openPath(String path) {
    if (touchpad == null) return;
    Context ctx = touchpad.getContext(); // no more better way to obtain a context statically
    ((Activity)ctx).runOnUiThread(() -> {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(DocumentsContract.buildDocumentUri(ctx.getString(R.string.storageProviderAuthorities), path), "*/*");
            ctx.startActivity(intent);
        } catch (Throwable th) {
            Tools.showError(ctx, th);
        }
    });
}
```

**Verify**: `grep -n "if (touchpad == null) return;" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java` → two matches total (confirms both Step 3 and Step 4 landed).

### Step 5: Build

**Verify**: `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` → `BUILD SUCCESSFUL`.

## Test plan

This is an Activity-lifecycle/static-state issue with a native-callback
interaction — not something a hermetic JUnit test (in the style of
`CameraPanTest`) can exercise, since it requires a real `Activity` lifecycle
and `findViewById`-backed views. No fake test will be written for this.
Verification is the Docker build plus an on-device check:

- Install the built debug APK on a device/emulator, launch into `MainActivity`
  (Play HD), then force a recreation (rotate the device if orientation isn't
  locked, or use `adb shell am force-stop` + relaunch, or navigate back and
  re-enter) — confirm the app does not crash and the game view/controls come
  up correctly in the new instance (proves the guards don't break the normal
  path and the nulling doesn't leave the new instance in a broken state,
  since `bindValues()` repopulates the statics fresh in the new instance's
  `onCreate`).
- If feasible on the test device, use a heap dump / LeakCanary-style tool
  (see Maintenance notes) after destroying `MainActivity` to confirm the
  previous `Activity` instance is no longer reachable via
  `MainActivity.touchpad` / `.mControlLayout` / `.touchCharInput` — this is
  optional (device/tooling dependent) and not required for Done criteria, but
  worth doing if a profiler is available.
- Exercise `toggleMouse`, `openLink`, or `openPath` normally (e.g. toggle the
  virtual mouse via its on-screen control) before and after a recreation
  cycle, confirming no NPE in logcat.

Describe these as manual/device steps in the PR/commit description — do not
claim a passing automated test exists for this plan.

## Done criteria

Machine-checkable. ALL must hold:

- [ ] `onDestroy()` in `MainActivity.java` nulls `touchpad`, `touchCharInput`, and `mControlLayout` (verify: `grep -n "touchpad = null\|touchCharInput = null\|mControlLayout = null" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java` → 3 matches)
- [ ] `toggleMouse`, `openLink`, and `openPath` each have a null-guard on `touchpad` (and `toggleMouse` additionally on `mControlLayout`) before first dereference
- [ ] Docker `assembleDebug` exits with `BUILD SUCCESSFUL`
- [ ] `git status` shows only `MainActivity.java` modified
- [ ] `plans/README.md` status row for Plan 017 updated

## STOP conditions

Stop and report back (do not improvise) if:

- Any of the cited excerpts (`onDestroy`, `toggleMouse`, `openLink`,
  `openPath`, `switchKeyboardState`) no longer match the live file — report
  the actual current code instead of guessing how to adapt.
- `grep -n "touchpad\|mControlLayout\|touchCharInput"
  app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java`
  turns up a *new* static method reading one of these fields that isn't
  listed in "Current state" above (i.e. the file gained a static reader
  since this plan was written) — guard it too, following the same pattern,
  but STOP and report first if you're unsure whether a given call site is
  reachable statically after `onDestroy()` (e.g. it's inside a lambda whose
  capture semantics aren't obvious) rather than guessing.
- You find a code path where nulling one of these fields would break
  something that legitimately needs the field to remain valid after
  `onDestroy()` (e.g. a pending native callback that is expected to complete
  using the old instance's views) — this is not expected per the current
  read of the file (native callbacks calling `toggleMouse`/`openLink`/`openPath`
  are exactly the case this plan defends against with the new guards, not a
  case that needs the old, stale reference), but if you find one, report
  instead of skipping the null-assignment.
- The build fails twice after a reasonable fix attempt.

## Maintenance notes

- The next time a new `public static` method is added to `MainActivity` that
  reads `touchpad`, `touchCharInput`, or `mControlLayout`, it must include the
  same null-guard pattern established here — there's no compiler enforcement
  for this, so a reviewer should specifically check for it in future PRs
  touching `MainActivity`'s static surface.
- Consider (as a separate, not-in-this-plan follow-up) whether these three
  fields should be static at all — they exist as statics purely so native/JNI
  callback entry points (`toggleMouse`, `openLink`, `openPath`,
  `switchKeyboardState`) can be `static` themselves (JNI calls into static
  Java methods more simply than instance methods bound to "the current
  activity"). A cleaner long-term fix would route native callbacks through a
  single static "current activity" accessor with its own lifecycle-aware
  null-out, rather than three independent static View fields — out of scope
  here since it's a larger structural change, not a bug fix.
- If a profiler/LeakCanary is added to the project later (none exists today
  per the repo's "no unit tests" / verification-reality note in `CLAUDE.md`),
  this fix is a good candidate for a regression check: assert no
  `MainActivity` instance is retained after `onDestroy()` + GC.
