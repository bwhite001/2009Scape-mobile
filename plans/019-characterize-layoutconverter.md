# Plan 019: Characterize `LayoutConverter`'s v1/v2 upgrade path with an injectable width/height seam

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 8ee361ea1..HEAD -- app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/customcontrols/LayoutConverter.java`
> If this file changed since this plan was written, compare the "Current
> state" excerpts against the live code before proceeding; on a mismatch,
> treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: MED (refactor touches on-screen-control layout upgrade — a regression corrupts user control layouts)
- **Depends on**: none
- **Category**: tests
- **Planned at**: commit `8ee361ea1`, 2026-07-10

## Why this matters

`LayoutConverter.loadAndConvertIfNecessary` upgrades a user's saved on-screen control layout from older formats (v1 with no `version` field, v2) to the current v3/v4 shape, doing ratio math against the device's current screen dimensions (`CallbackBridge.physicalWidth`/`physicalHeight`) to convert legacy absolute-pixel button positions into scale-independent `dynamicX`/`dynamicY` expressions. This runs on every app upgrade for any user with an old layout file. A regression here corrupts the on-screen controls that are the core input surface for the entire client — there is no way to play the game without them. The method also has a silent `return null` on an unrecognized `version` value (`LayoutConverter.java:34`) which is an unhandled state a caller could NPE on. There is currently zero test coverage of any of this.

## Current state

`app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/customcontrols/LayoutConverter.java` (full file, 125 lines):

- `:17-39` — the entry point, branching on `version`:
```java
public static CustomControls loadAndConvertIfNecessary(String jsonPath) throws IOException, JsonSyntaxException {

    String jsonLayoutData = Tools.read(jsonPath);
    try {
        JSONObject layoutJobj = new JSONObject(jsonLayoutData);

        if(!layoutJobj.has("version")) { //v1 layout
            CustomControls layout = LayoutConverter.convertV1Layout(layoutJobj);
            layout.save(jsonPath);
            return layout;
        }else if (layoutJobj.getInt("version") == 2) {
            CustomControls layout = LayoutConverter.convertV2Layout(layoutJobj);
            layout.save(jsonPath);
            return layout;
        }else if (layoutJobj.getInt("version") == 3 || layoutJobj.getInt("version") == 4) {
            return Tools.GLOBAL_GSON.fromJson(jsonLayoutData, CustomControls.class);
        }else{
            return null;
        }
    }catch (JSONException e) {
        throw new JsonSyntaxException("Failed to load",e);
    }
}
```
- `:40-79` — `convertV2Layout(JSONObject)`: uses `CallbackBridge.physicalWidth`/`physicalHeight` statics directly at lines ~49, ~54, ~67, ~72, e.g.:
```java
if(!Tools.isValidString(n_button.dynamicX) && button.has("x")) {
    double buttonC = button.getDouble("x");
    double ratio = buttonC/CallbackBridge.physicalWidth;
    n_button.dynamicX = ratio + " * ${screen_width}";
}
```
- `:80-123` — `convertV1Layout(JSONObject)`: same ratio pattern against the same statics at ~:95 and ~:100, plus non-ratio field copying (keycodes, opacity, toggle flags, etc.) that doesn't touch `CallbackBridge` at all.

Both `convertV1Layout` and `convertV2Layout` are coupled to: (a) `Tools.read` / a real file path (only in the entry point, not in these two methods themselves), (b) `Tools.GLOBAL_GSON` (a plain `public static final Gson` field, `Tools.java:80` — no Android dependency, safe to use from a JVM unit test), and (c) the `CallbackBridge.physicalWidth`/`physicalHeight` statics (an LWJGL/GLFW bridge class — NOT safe or meaningful to reference from a unit test; it reflects live GL surface state).

- Structural pattern to mirror: `customcontrols/CameraPan.java` (pure static methods, explicit parameters instead of statics) + `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/customcontrols/CameraPanTest.java` (JUnit 4, `org.junit.Test` + `assertEquals`).
- `org.json.JSONObject`/`JSONArray` are safe to construct and use directly in this repo's JVM unit tests (no Robolectric needed) — they are pure-Java classes in the Android SDK's mockable jar and are not stubbed out, unlike e.g. `android.util.Log`.
- `Tools.read(String path)` (`Tools.java:528-530`) is plain Java file IO (`FileInputStream` + `IOUtils.toString`) with no Android dependency, so it is safe to call from a unit test against a real temp file.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Build APK | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` | `BUILD SUCCESSFUL` |
| Unit tests | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug :app_pojavlauncher:testDebugUnitTest"` | `BUILD SUCCESSFUL`; report under `app_pojavlauncher/build/test-results/testDebugUnitTest/` |
| Find callers of the two convert methods | `grep -rn "convertV1Layout\|convertV2Layout" app_pojavlauncher/src/main/java` | only the two call sites inside `loadAndConvertIfNecessary` |

## Scope

**In scope:**
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/customcontrols/LayoutConverter.java` — add testable overloads of `convertV1Layout`/`convertV2Layout`; keep the existing entry point (`loadAndConvertIfNecessary`) and existing no-arg-overload signatures working exactly as before.
- NEW `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/customcontrols/LayoutConverterTest.java`

**Out of scope (do NOT touch, even though related):**
- `ControlData`, `ControlDrawerData`, `CustomControls` (the model classes) — do not modify them; use a tiny accessor ONLY if strictly required to assert on a private field, and note it explicitly if you add one (minimize).
- `CallbackBridge` itself.
- Any caller of `loadAndConvertIfNecessary` (e.g. wherever the controls editor loads a saved layout on startup).

## Git workflow

- Branch: `advisor/019-characterize-layoutconverter`
- Commit per step; conventional-commit style (e.g. `test: characterize LayoutConverter v1/v2 upgrade with an injectable width/height seam`).
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Add width/height-injectable overloads (the seam)

In `LayoutConverter.java`, add two new overloads that take explicit `width`/`height` `int` parameters instead of reading `CallbackBridge.physicalWidth`/`physicalHeight`, and make the existing no-arg-signature methods thin delegates to them:

```java
public static CustomControls convertV2Layout(JSONObject oldLayoutJson) throws JSONException {
    return convertV2Layout(oldLayoutJson, CallbackBridge.physicalWidth, CallbackBridge.physicalHeight);
}

public static CustomControls convertV2Layout(JSONObject oldLayoutJson, int width, int height) throws JSONException {
    CustomControls layout = Tools.GLOBAL_GSON.fromJson(oldLayoutJson.toString(), CustomControls.class);
    JSONArray layoutMainArray = oldLayoutJson.getJSONArray("mControlDataList");
    layout.mControlDataList = new ArrayList<>(layoutMainArray.length());
    for(int i = 0; i < layoutMainArray.length(); i++) {
        JSONObject button = layoutMainArray.getJSONObject(i);
        ControlData n_button = Tools.GLOBAL_GSON.fromJson(button.toString(), ControlData.class);
        if(!Tools.isValidString(n_button.dynamicX) && button.has("x")) {
            double buttonC = button.getDouble("x");
            double ratio = buttonC/width;
            n_button.dynamicX = ratio + " * ${screen_width}";
        }
        if(!Tools.isValidString(n_button.dynamicY) && button.has("y")) {
            double buttonC = button.getDouble("y");
            double ratio = buttonC/height;
            n_button.dynamicY = ratio + " * ${screen_height}";
        }
        layout.mControlDataList.add(n_button);
    }
    // ... mDrawerDataList loop unchanged except buttonC/CallbackBridge.physicalWidth
    // -> buttonC/width, and .../physicalHeight -> .../height
    layout.version = 3;
    return layout;
}
```

Apply the same pattern to `convertV1Layout`: keep `convertV1Layout(JSONObject)` as a one-line delegate to a new `convertV1Layout(JSONObject, int width, int height)` that has the identical body with `CallbackBridge.physicalWidth`/`physicalHeight` replaced by the `width`/`height` parameters at both ratio sites (~:95, ~:100 in the current file).

Do not change `loadAndConvertIfNecessary` in this step — it keeps calling the no-arg overloads, so its behavior is provably unchanged (it now just calls a delegate instead of the body directly).

**Verify**: `grep -n "CallbackBridge.physical" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/customcontrols/LayoutConverter.java` → the only remaining matches are inside the two no-arg delegate methods (`convertV1Layout(JSONObject)` / `convertV2Layout(JSONObject)`), not inside the new width/height overloads.

### Step 2: Characterization tests for the ratio math

Create `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/customcontrols/LayoutConverterTest.java`. Build small fixture JSON strings for a v1 layout (one button in `mControlDataList`, matching the fields `convertV1Layout` reads: `isDynamicBtn`, `dynamicX`, `dynamicY`, `name`, `transparency`, `passThruEnabled`, `isToggle`, `height`, `width`, `isRound`, `holdShift`, `holdCtrl`, `holdAlt`, `keycode`, and top-level `scaledAt`) and a v2 layout (`mControlDataList` + `mDrawerDataList`, each button/drawer having explicit `x`/`y` and blank/missing `dynamicX`/`dynamicY` so the ratio branch is exercised).

This is a CHARACTERIZATION test: you do not need to hand-derive the expected ratio string. Instead:
1. Write the test with a fixed `width`/`height` (e.g. `1920`/`1080`) and a fixed `x`/`y` in the fixture (e.g. `x=960`, `y=540`).
2. First run the test with a placeholder assertion (e.g. print `n_button.dynamicX` via `System.out` or a deliberately-failing `assertEquals("", actual)`) to capture the ACTUAL current output string.
3. Lock that exact captured string in as the expected value in a real `assertEquals`.

Cover:
- `convertV1Layout(JSONObject, width, height)`: the ratio-derived `dynamicX`/`dynamicY` for a button with `x`/`y` set and no pre-existing `dynamicX`/`dynamicY`; also assert the non-ratio fields it copies (e.g. `name`, `keycodes` array holding the plain `keycode` when no modifier flags are set) to confirm the delegate didn't change field-copying behavior.
- `convertV2Layout(JSONObject, width, height)`: same ratio assertion for both `mControlDataList` and one `mDrawerDataList` entry (which reads `x`/`y` from a nested `properties` object).
- The unknown-version `return null` branch: write a real temp JSON file (e.g. via `java.io.File.createTempFile` or `org.junit.rules.TemporaryFolder`) containing `{"version": 5}` (or a similarly minimal unknown-version body), call `LayoutConverter.loadAndConvertIfNecessary(path)` on it, and assert the result is `null`. This exercises the real entry point (file IO + `Tools.read`) without going through `CustomControls.save`, since the null branch returns before any conversion or save happens.

**Verify**: `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:testDebugUnitTest --tests net.kdt.pojavlaunch.customcontrols.LayoutConverterTest"` → all new tests pass.

### Step 3: Full build + test gate

**Verify**: `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug :app_pojavlauncher:testDebugUnitTest"` → `BUILD SUCCESSFUL`, all tests (old + new) pass.

## Test plan

- New tests: `LayoutConverterTest` covering v1 ratio conversion, v2 ratio conversion (main list + drawer list), and the unknown-version `null` branch (Step 2 list above).
- Structural pattern: `customcontrols/CameraPanTest.java`.
- Characterization approach: capture-then-lock-in the current computed ratio strings rather than hand-deriving them (see Step 2).
- Verification: `./gradlew :app_pojavlauncher:testDebugUnitTest` (via Docker) → all pass, including new `LayoutConverterTest` cases.

## Done criteria

Machine-checkable. ALL must hold:

- [ ] `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug :app_pojavlauncher:testDebugUnitTest"` exits 0 / `BUILD SUCCESSFUL`
- [ ] `LayoutConverterTest` exists and all its cases (Step 2) pass
- [ ] The new width/height overloads exist and contain no `CallbackBridge` references (`grep -c "CallbackBridge" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/customcontrols/LayoutConverter.java` shows exactly 2 remaining hits, both inside the two no-arg delegate methods — plus the existing `import org.lwjgl.glfw.CallbackBridge;` line)
- [ ] `loadAndConvertIfNecessary`'s behavior (its branching and its no-arg-overload calls) is textually unchanged except for calling the new delegates
- [ ] No files outside the in-scope list are modified (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:

- The code at `LayoutConverter.java` doesn't match the excerpts in "Current state" (drift since this plan was written).
- Extracting the width/height seam turns out to require pulling in additional Android framework types that can't be cleanly injected as parameters without a larger refactor of `CustomControls`/`ControlData` — STOP and report exactly what forced it, and propose the minimal seam you found instead of expanding scope.
- A step's verification fails twice after a reasonable fix attempt.
- `org.json.JSONObject` turns out to throw "not mocked" (Android-stub) errors in the actual test run in this repo's Docker/Gradle setup, contradicting the "safe in JVM unit tests" assumption in "Current state" — STOP and report; this would mean the test needs Robolectric, which is a bigger addition than this plan scopes.

## Maintenance notes

- Reviewer: confirm the new width/height overloads are byte-for-byte the same ratio math as before (just parameterized) — this is a characterization plan, not a behavior change. Any deviation in the assert values from what Step 2 actually captures at write-time is a red flag, not an improvement to "fix."
- Follow-up (deferred, out of scope here): the silent `return null` on unknown `version` is still unhandled by whatever calls `loadAndConvertIfNecessary` — a future plan could make callers handle `null` explicitly (e.g. fall back to default controls) instead of risking an NPE. Not fixed here; this plan only pins the current behavior with a test.
