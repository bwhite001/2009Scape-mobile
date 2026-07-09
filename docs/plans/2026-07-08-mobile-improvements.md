# Mobile Improvements Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Improve the 2009Scape Android client for day-to-day LAN play: keep assets fresh on update, let users set the server IP from Settings, and clean up the on-screen control bar.

**Architecture:** Three independent changes. (1) Version-stamp the bundled assets so `default.json` and `config.json` are re-copied whenever the APK increments — no more stale files blocking new features. (2) Expose server IP/port in SettingsActivity; patch the on-disk `config.json` in `Tools.launchGLJRE` before the JVM starts. (3) Collapse the four standalone top-bar buttons (KEYBOARD/MOUSE/SHIFT/TAB) into a single `ControlDrawer` in `default.json` so the game viewport loses less vertical space.

**Tech Stack:** Android (Java/Kotlin), Compose (SettingsActivity), SharedPreferences via `PreferencesRepository`, `org.json.JSONObject` for config patching, `ControlDrawerData` JSON for control layout.

**Already done (do NOT redo):**
- `android:windowSoftInputMode="adjustNothing"` on MainActivity — keyboard overlays game ✓
- `SPECIALBTN_COMMAND = -10` + `commandText` field + Bank/Home/Loc buttons in default.json ✓

---

## Task 1: Asset version tracking

Fixes the `copyAssetFile(..., false)` guard so updated `default.json` (command buttons) and `config.json` are re-deployed when the app updates. Without this, all previous changes are dead on existing installs.

**Files:**
- Create: `app_pojavlauncher/src/main/assets/build_version.txt`
- Modify: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/tasks/AsyncAssetManager.java`

**Background:** `Tools.copyAssetFile(ctx, "default.json", path, false)` skips the copy if the file already exists on disk. Adding a version stamp lets us detect when the bundled version is newer and force-overwrite.

**Step 1: Create the version stamp file**

```
app_pojavlauncher/src/main/assets/build_version.txt
```
Contents (single line, no newline):
```
2
```
(Increment this number every time assets change going forward.)

**Step 2: Add a version-check helper in AsyncAssetManager.java**

In `AsyncAssetManager.java`, add this private static method **above** `unpackSingleFiles`:

```java
private static final String PREF_ASSET_VERSION = "asset_version";

/** Returns true if the bundled asset version is newer than what's stored in prefs. */
private static boolean assetVersionChanged(Context ctx) {
    try {
        InputStream is = ctx.getAssets().open("build_version.txt");
        int bundled = Integer.parseInt(new String(is.readAllBytes()).trim());
        is.close();
        int stored = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
                .getInt(PREF_ASSET_VERSION, 0);
        return bundled > stored;
    } catch (Exception e) {
        return true; // re-copy on any error
    }
}

/** Saves the bundled asset version to prefs so we don't re-copy next time. */
private static void saveAssetVersion(Context ctx) {
    try {
        InputStream is = ctx.getAssets().open("build_version.txt");
        int bundled = Integer.parseInt(new String(is.readAllBytes()).trim());
        is.close();
        ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
                .edit().putInt(PREF_ASSET_VERSION, bundled).apply();
    } catch (Exception ignored) {}
}
```

Note: `readAllBytes()` requires API 33+; if minSdk < 33 use this helper instead:
```java
private static String readFully(InputStream is) throws IOException {
    byte[] buf = new byte[64];
    int n = is.read(buf);
    return n > 0 ? new String(buf, 0, n).trim() : "";
}
```
Replace `new String(is.readAllBytes()).trim()` with `readFully(is)` both times.

**Step 3: Use version check in unpackSingleFiles**

Replace the existing `unpackSingleFiles` method body:

```java
public static void unpackSingleFiles(Context ctx){
    ProgressLayout.setProgress(ProgressLayout.EXTRACT_SINGLE_FILES, 0);
    sExecutorService.execute(() -> {
        try {
            boolean overwrite = assetVersionChanged(ctx);
            Tools.copyAssetFile(ctx, "options.txt", Tools.DIR_GAME_NEW, false); // never overwrite user options
            Tools.copyAssetFile(ctx, "default.json", Tools.CTRLMAP_PATH, overwrite);
            Tools.copyAssetFile(ctx, "launcher_profiles.json", Tools.DIR_GAME_NEW, false);
            if (overwrite) saveAssetVersion(ctx);
        } catch (IOException e) {
            Log.e("AsyncAssetManager", "Failed to unpack critical components !");
        }
        ProgressLayout.clearProgress(ProgressLayout.EXTRACT_SINGLE_FILES);
    });
}
```

**Step 4: Also overwrite config.json in unpackComponents when version changed**

In `unpackComponents`, the `config.json` copy line currently:
```java
Tools.copyAssetFile(ctx,"config.json",Tools.DIR_DATA, false);
```
Change to:
```java
Tools.copyAssetFile(ctx,"config.json",Tools.DIR_DATA, assetVersionChanged(ctx));
```

**Step 5: Build and verify**

```bash
cd /runescape/2009Scape-mobile
./gradlew :app_pojavlauncher:assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

**Step 6: Commit**

```bash
git add app_pojavlauncher/src/main/assets/build_version.txt \
        app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/tasks/AsyncAssetManager.java
git commit -m "feat: asset version tracking — overwrite default.json/config.json on app update"
```

---

## Task 2: Server IP/port in Settings

Lets the user enter the LAN server IP from the Settings screen. At launch, `Tools.launchGLJRE` patches the on-disk `config.json` before starting the JVM.

**Files:**
- Modify: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt`
- Modify: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java`

**Background:** `config.json` lives at `Tools.DIR_DATA/config.json` and is read by the RT4-Client via `-DconfigFile`. Currently it ships as `127.0.0.1`. We store the desired IP in SharedPreferences and overwrite the JSON keys just before launching the JVM.

**Step 1: Add StringPref data class to SettingsActivity.kt**

At the top of `SettingsActivity.kt`, alongside the existing `BoolPref`/`IntPref` data classes:
```kotlin
private data class StringPref(val key: String, val label: String, val def: String)
```

**Step 2: Add server prefs list**

After the existing `private val EXPERIMENTAL_BOOLS` declaration:
```kotlin
private val SERVER_STRINGS = listOf(
    StringPref("serverIp",   "Server IP address", "127.0.0.1"),
    StringPref("serverPort", "Server port",       "43595"),
)
```

**Step 3: Add StringRow composable**

After the `IntRow` composable:
```kotlin
@Composable
private fun StringRow(pref: StringPref, repo: PreferencesRepository) {
    var value by remember { mutableStateOf(repo.getString(pref.key, pref.def) ?: pref.def) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(pref.label, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = value,
            onValueChange = { value = it; repo.putString(pref.key, it) },
            modifier = Modifier.width(180.dp),
            singleLine = true,
        )
    }
}
```

**Step 4: Add a "Server" section to the settings LazyColumn**

In the `SettingsScreen` composable, find the `LazyColumn` block. It currently has sections like `section("Video", ...)`. Add a server section as the first entry:

```kotlin
section("Server") {
    SERVER_STRINGS.forEach { item(it.key) { StringRow(it, repo) } }
}
```

**Step 5: Patch config.json in Tools.launchGLJRE**

In `Tools.java`, add this private helper **before** `launchGLJRE`:

```java
/** Reads serverIp/serverPort from SharedPreferences and patches the on-disk config.json. */
private static void patchConfigJson(Activity activity) {
    try {
        android.content.SharedPreferences prefs =
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(activity);
        String ip   = prefs.getString("serverIp",   "127.0.0.1");
        String port = prefs.getString("serverPort",  "43595");
        if (ip == null || ip.isEmpty()) return;

        File configFile = new File(DIR_DATA, "config.json");
        if (!configFile.exists()) return;

        String raw = read(configFile.getAbsolutePath());
        org.json.JSONObject json = new org.json.JSONObject(raw);
        json.put("ip_address",    ip);
        json.put("ip_management", ip);
        int portInt = Integer.parseInt(port);
        json.put("server_port", portInt - 1); // server_port is worldId-based (43594)
        json.put("wl_port",     portInt);
        json.put("js5_port",    portInt);

        try (java.io.FileWriter fw = new java.io.FileWriter(configFile)) {
            fw.write(json.toString(2));
        }
    } catch (Exception e) {
        android.util.Log.w("Tools", "patchConfigJson failed: " + e.getMessage());
    }
}
```

**Step 6: Call patchConfigJson just before the JVM launches**

In `launchGLJRE`, add the call as the first line inside the method:

```java
public static void launchGLJRE(final Activity activity) throws Throwable {
    patchConfigJson(activity);   // <-- add this line
    Runtime runtime = MultiRTUtils.forceReread("Internal");
    // ... rest unchanged
```

**Step 7: Build and verify**

```bash
./gradlew :app_pojavlauncher:assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

**Step 8: Commit**

```bash
git add app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt \
        app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java
git commit -m "feat: server IP/port in Settings, patched into config.json at launch"
```

---

## Task 3: Collapse KEYBOARD/MOUSE/SHIFT/TAB into a ControlDrawer

Replaces the four separate top-bar buttons with a single `⌨` drawer button that expands DOWN to reveal them. Frees ~60px of game height.

**Files:**
- Modify: `app_pojavlauncher/src/main/assets/default.json`
- Bump: `app_pojavlauncher/src/main/assets/build_version.txt` (increment to `3`)

**Background:** `mDrawerDataList` in the control layout JSON holds `ControlDrawerData` objects. Each has a `properties` (the toggle button) and `buttonProperties` (the revealed buttons). `orientation: "DOWN"` makes the drawer expand downward when tapped.

**Step 1: Rewrite default.json**

Replace the entire file with the version below. Changes:
- Remove the 4 standalone entries: `Keyboard`, `Mouse`, `SHIFT`, `Tab`
- Add one `ControlDrawerData` entry in `mDrawerDataList` that contains them
- Keep `PRI`, `SEC`, and the 3 command buttons (`Bank`, `Home`, `Loc`) as standalone

```json
{
  "mControlDataList": [
    {
      "bgColor": 1291845632,
      "commandText": "::bank",
      "cornerRadius": 0.0,
      "dynamicX": "${margin}",
      "dynamicY": "${screen_height} - ${margin} * 2 - ${height} * 3",
      "height": 25.0,
      "isDynamicBtn": false,
      "isHideable": true,
      "isSwipeable": false,
      "isToggle": false,
      "keycodes": [-10, 0, 0, 0],
      "name": "Bank",
      "opacity": 1.0,
      "passThruEnabled": false,
      "strokeColor": -1,
      "strokeWidth": 0,
      "width": 55.0
    },
    {
      "bgColor": 1291845632,
      "commandText": "::home",
      "cornerRadius": 0.0,
      "dynamicX": "${margin} * 2 + px(55.0)",
      "dynamicY": "${screen_height} - ${margin} * 2 - ${height} * 3",
      "height": 25.0,
      "isDynamicBtn": false,
      "isHideable": true,
      "isSwipeable": false,
      "isToggle": false,
      "keycodes": [-10, 0, 0, 0],
      "name": "Home",
      "opacity": 1.0,
      "passThruEnabled": false,
      "strokeColor": -1,
      "strokeWidth": 0,
      "width": 55.0
    },
    {
      "bgColor": 1291845632,
      "commandText": "::loc",
      "cornerRadius": 0.0,
      "dynamicX": "${margin} * 3 + px(110.0)",
      "dynamicY": "${screen_height} - ${margin} * 2 - ${height} * 3",
      "height": 25.0,
      "isDynamicBtn": false,
      "isHideable": true,
      "isSwipeable": false,
      "isToggle": false,
      "keycodes": [-10, 0, 0, 0],
      "name": "Loc",
      "opacity": 1.0,
      "passThruEnabled": false,
      "strokeColor": -1,
      "strokeWidth": 0,
      "width": 55.0
    },
    {
      "bgColor": 1291845632,
      "cornerRadius": 0.0,
      "dynamicX": "${margin}",
      "dynamicY": "0.0037037036 * ${screen_height} + (px(29.714285) /100.0 * ${preferred_scale}) + ${margin}",
      "height": 49.904762,
      "isDynamicBtn": false,
      "isHideable": true,
      "isSwipeable": false,
      "isToggle": false,
      "keycodes": [-3, 0, 0, 0],
      "name": "PRI",
      "opacity": 1.0,
      "passThruEnabled": false,
      "strokeColor": -1,
      "strokeWidth": 0,
      "width": 49.904762
    },
    {
      "bgColor": 1291845632,
      "cornerRadius": 0.0,
      "dynamicX": "${margin} * 2 + px(49.904762)",
      "dynamicY": "0.0037037036 * ${screen_height} + (px(29.714285) /100.0 * ${preferred_scale}) + ${margin}",
      "height": 49.904762,
      "isDynamicBtn": false,
      "isHideable": true,
      "isSwipeable": false,
      "isToggle": false,
      "keycodes": [-4, 0, 0, 0],
      "name": "SEC",
      "opacity": 1.0,
      "passThruEnabled": false,
      "strokeColor": -1,
      "strokeWidth": 0,
      "width": 49.904762
    }
  ],
  "mDrawerDataList": [
    {
      "orientation": "DOWN",
      "properties": {
        "bgColor": 1291845632,
        "cornerRadius": 0.0,
        "dynamicX": "${margin}",
        "dynamicY": "${margin}",
        "height": 29.714285,
        "isDynamicBtn": false,
        "isHideable": false,
        "isSwipeable": false,
        "isToggle": false,
        "keycodes": [0, 0, 0, 0],
        "name": "⌨",
        "opacity": 1.0,
        "passThruEnabled": false,
        "strokeColor": -1,
        "strokeWidth": 0,
        "width": 44.0
      },
      "buttonProperties": [
        {
          "bgColor": 1291845632,
          "cornerRadius": 0.0,
          "dynamicX": "${margin}",
          "dynamicY": "${margin}",
          "height": 29.714285,
          "isDynamicBtn": false,
          "isHideable": true,
          "isSwipeable": false,
          "isToggle": false,
          "keycodes": [-1, 0, 0, 0],
          "name": "KB",
          "opacity": 1.0,
          "passThruEnabled": false,
          "strokeColor": -1,
          "strokeWidth": 0,
          "width": 44.0
        },
        {
          "bgColor": 1291845632,
          "cornerRadius": 0.0,
          "dynamicX": "${margin}",
          "dynamicY": "${margin}",
          "height": 29.714285,
          "isDynamicBtn": false,
          "isHideable": true,
          "isSwipeable": false,
          "isToggle": false,
          "keycodes": [-5, 0, 0, 0],
          "name": "Mouse",
          "opacity": 1.0,
          "passThruEnabled": false,
          "strokeColor": -1,
          "strokeWidth": 0,
          "width": 44.0
        },
        {
          "bgColor": 1291845632,
          "cornerRadius": 0.0,
          "dynamicX": "${margin}",
          "dynamicY": "${margin}",
          "height": 29.714285,
          "isDynamicBtn": false,
          "isHideable": true,
          "isSwipeable": false,
          "isToggle": true,
          "keycodes": [340, 0, 0, 0],
          "name": "SHF",
          "opacity": 1.0,
          "passThruEnabled": false,
          "strokeColor": -1,
          "strokeWidth": 0,
          "width": 44.0
        },
        {
          "bgColor": 1291845632,
          "cornerRadius": 0.0,
          "dynamicX": "${margin}",
          "dynamicY": "${margin}",
          "height": 29.714285,
          "isDynamicBtn": false,
          "isHideable": true,
          "isSwipeable": false,
          "isToggle": false,
          "keycodes": [258, 0, 0, 0],
          "name": "Tab",
          "opacity": 1.0,
          "passThruEnabled": false,
          "strokeColor": -1,
          "strokeWidth": 0,
          "width": 44.0
        }
      ]
    }
  ],
  "scaledAt": 100.0,
  "version": 4
}
```

**Step 2: Validate JSON**

```bash
python3 -c "import json; json.load(open('app_pojavlauncher/src/main/assets/default.json')); print('OK')"
```
Expected: `OK`

**Step 3: Bump build_version.txt to 3**

```
echo -n "3" > app_pojavlauncher/src/main/assets/build_version.txt
```

**Step 4: Build**

```bash
./gradlew :app_pojavlauncher:assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

**Step 5: Commit**

```bash
git add app_pojavlauncher/src/main/assets/default.json \
        app_pojavlauncher/src/main/assets/build_version.txt
git commit -m "feat: collapse KB/Mouse/Shift/Tab into ⌨ drawer, free top-bar screen space"
```

---

## Summary of all changes

| # | File | What changes |
|---|------|-------------|
| 1 | `assets/build_version.txt` | New — version stamp |
| 2 | `tasks/AsyncAssetManager.java` | Version-aware overwrite for `default.json` |
| 3 | `SettingsActivity.kt` | `StringPref` + Server IP/port rows |
| 4 | `Tools.java` | `patchConfigJson()` called before JVM launch |
| 5 | `assets/default.json` | ⌨ drawer replaces 4 top-bar buttons; bumped to v3 |
| 6 | `AndroidManifest.xml` | `adjustNothing` ✓ (already done) |
| 7 | `ControlData.java` | `SPECIALBTN_COMMAND` + `commandText` ✓ (already done) |
| 8 | `ControlButton.java` | `sendChatCommand()` handler ✓ (already done) |
