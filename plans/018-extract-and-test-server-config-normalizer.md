# Plan 018: Extract a single, tested `ServerConfig.normalize` and route both server-config writers through it

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 8ee361ea1..HEAD -- app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt`
> If either in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED (launch-critical path — this is the server-connection contract)
- **Depends on**: none
- **Category**: tests (+ small refactor)
- **Planned at**: commit `8ee361ea1`, 2026-07-10

## Why this matters

There are currently **two independent copies** of "which field is the IP, which is the port, what's the fallback" logic for the server-connection contract that the RT4-Client reads from `config.json`:

1. `Tools.java:157` `patchConfigJson` — writes prefs (`serverIp`/`serverPort`) into the on-disk `config.json`, with a `NumberFormatException` → `43595` fallback, and forcibly sets `server_port`, `wl_port`, and `js5_port` all to the same parsed value.
2. `SettingsActivity.kt:204` `applyConfigJson` — parses an **imported** `config.json` body, picks the IP from `ip_address` else `ip_management`, picks the port by precedence `wl_port` → `js5_port` → `server_port` → `43595`, with **no range validation** on the parsed port.

These two encodings can disagree (e.g. an imported config that only sets `server_port`, or one where `wl_port != server_port`), and this general area is exactly what produces `error_game_js5connect` (see the comment at `Tools.java:180-184` documenting a prior bug where `server_port` pointed at a dead `portInt - 1`). This is also the highest-churn code in the repo recently (commits `cf28a332f`, `d3315054d`, `da0682f38`, `d9aedc18a` all touch this area) and has zero test coverage pinning the contract. A regression here breaks the ability to connect to a server at all — the core function of this app.

Consolidating both call sites onto one pure, tested function removes the drift risk and gives future changes (e.g. Plan 029's profile switcher) a single seam to route through.

## Current state

- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java:157-203` — `patchConfigJson(Activity)`:

```java
public static void patchConfigJson(Activity activity) {
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
        if (port == null || port.trim().isEmpty()) port = "43595";
        int portInt;
        try {
            portInt = Integer.parseInt(port.trim());
        } catch (NumberFormatException e) {
            portInt = 43595;
            android.util.Log.w("Tools", "patchConfigJson: invalid port '" + port + "', using 43595");
        }
        // This server multiplexes login, JS5 and world-list on a single port
        // (43594 + worldId = 43595 for world 1), so all three must be that same
        // port. The known-good desktop server_profiles.json uses 43595 for all
        // three; the previous "portInt - 1" pointed server_port at a dead 43594
        // and caused error_game_js5connect.
        json.put("server_port", portInt);
        json.put("wl_port",     portInt);
        json.put("js5_port",    portInt);

        try (java.io.FileWriter fw = new java.io.FileWriter(configFile)) {
            fw.write(json.toString(2));
        }
        ...
    } catch (Exception e) {
        android.util.Log.w("Tools", "patchConfigJson failed: " + e.getMessage());
    }
}
```

- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt:199-217` — `applyConfigJson`:

```kotlin
/**
 * Parse a server config.json body and write serverIp / serverPort prefs from
 * ip_address + wl/js5/server port. Returns "ip:port"; throws on bad data.
 * Shared by both the URL import and the file-picker import.
 */
private fun applyConfigJson(repo: PreferencesRepository, body: String): String {
    val json = org.json.JSONObject(body)
    val ip = json.optString("ip_address", json.optString("ip_management", ""))
    if (ip.isEmpty()) throw IllegalStateException("config has no ip_address")
    val port = when {
        json.has("wl_port")     -> json.getInt("wl_port")
        json.has("js5_port")    -> json.getInt("js5_port")
        json.has("server_port") -> json.getInt("server_port")
        else -> 43595
    }
    repo.putString("serverIp", ip)
    repo.putString("serverPort", port.toString())
    return "$ip:$port"
}
```
Called from `importServerConfig` (`SettingsActivity.kt:151-178`, background thread, network fetch) and `applyConfigFromUri` (`SettingsActivity.kt:185-197`, UI thread, file-picker result) — both pass the raw JSON body string in and use the returned `"ip:port"` string only for a Toast message.

- Structural pattern to mirror for the new pure class: `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/customcontrols/CameraPan.java` (pure static class, no Android imports) and its test `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/customcontrols/CameraPanTest.java` (JUnit 4, `org.junit.Test` + `assertEquals`, no Android framework deps, `@Test public void <case>()` methods).

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Build APK | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` | `BUILD SUCCESSFUL`; APK at `app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk` |
| Unit tests | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug :app_pojavlauncher:testDebugUnitTest"` | `BUILD SUCCESSFUL`; report under `app_pojavlauncher/build/test-results/testDebugUnitTest/` shows all tests passing |
| Find other callers | `grep -rn "patchConfigJson\|applyConfigJson" app_pojavlauncher/src/main/java` | only the two call sites above (plus `launchGLJRE` calling `patchConfigJson`) |

## Scope

**In scope:**
- NEW `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/ServerConfig.java`
- NEW `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/ServerConfigTest.java`
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java` — `patchConfigJson` only, turned into a thin adapter
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt` — `applyConfigJson` only, turned into a thin adapter

**Out of scope (do NOT touch, even though related):**
- Import file-handling / the file-picker and URL-fetch plumbing around `applyConfigJson` (`importServerConfig`, `applyConfigFromUri`) — that's Plan 013.
- Transport/networking code — that's Plan 014.
- `launchGLJRE` or anything else in `Tools.java` beyond `patchConfigJson`.
- Any change to the preference keys (`serverIp`, `serverPort`) or the on-disk `config.json` field names.

## Git workflow

- Branch: `advisor/018-extract-and-test-server-config-normalizer`
- Commit per step; conventional-commit style matching `git log` (e.g. `test: extract ServerConfig.normalize and cover the server-connection contract`).
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Create the pure `ServerConfig` class

Create `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/ServerConfig.java` with **no Android imports** (mirror `customcontrols/CameraPan.java`):

```java
package net.kdt.pojavlaunch;

/**
 * Resolves the server IP/port the RT4-Client should dial, from up to five
 * candidate fields (raw strings, since callers source them either from
 * SharedPreferences strings or from parsed config.json values).
 *
 * IP precedence: ipAddress wins over ipManagement; blank/null on both falls
 * back to DEFAULT_IP.
 *
 * Port precedence: wlPort > js5Port > serverPort — the first candidate that
 * parses to an integer in [1, 65535] wins; a candidate that is null, blank,
 * non-numeric, or out of range is skipped in favor of the next; if none
 * resolve, falls back to DEFAULT_PORT.
 *
 * IMPORTANT: this server multiplexes login, JS5, and world-list on a SINGLE
 * port (43594 + worldId = 43595 for world 1). Callers that WRITE a config.json
 * (rather than just reading one for display) must write the resolved port to
 * all three of server_port/wl_port/js5_port — see Tools.patchConfigJson. A
 * prior bug pointed server_port at portInt-1 (a dead port) and caused
 * error_game_js5connect; do not reintroduce an offset between the three ports.
 */
public class ServerConfig {
    public static final String DEFAULT_IP = "127.0.0.1";
    public static final int DEFAULT_PORT = 43595;

    public static final class Result {
        public final String ip;
        public final int port;
        public Result(String ip, int port) {
            this.ip = ip;
            this.port = port;
        }
    }

    public static Result normalize(String ipAddress, String ipManagement,
                                    String serverPort, String wlPort, String js5Port) {
        String ip = firstNonBlank(ipAddress, ipManagement, DEFAULT_IP);
        Integer port = firstValidPort(wlPort, js5Port, serverPort);
        return new Result(ip, port != null ? port : DEFAULT_PORT);
    }

    private static String firstNonBlank(String a, String b, String fallback) {
        if (a != null && !a.trim().isEmpty()) return a.trim();
        if (b != null && !b.trim().isEmpty()) return b.trim();
        return fallback;
    }

    private static Integer firstValidPort(String... candidates) {
        for (String c : candidates) {
            if (c == null || c.trim().isEmpty()) continue;
            try {
                int p = Integer.parseInt(c.trim());
                if (p >= 1 && p <= 65535) return p;
            } catch (NumberFormatException ignored) {
                // fall through to the next candidate
            }
        }
        return null;
    }
}
```

**Verify**: file compiles as part of Step 4's build.

### Step 2: Route `Tools.patchConfigJson` through `ServerConfig.normalize`

Replace the port-parsing block in `patchConfigJson` (`Tools.java:157-203`) so it calls the new class instead of hand-rolling the parse/fallback. Target shape (keep the surrounding try/catch, the read-back log lines, and the doc comment about the multiplexed port — only the parsing/`json.put` block changes):

```java
public static void patchConfigJson(Activity activity) {
    try {
        android.content.SharedPreferences prefs =
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(activity);
        String ip   = prefs.getString("serverIp",   ServerConfig.DEFAULT_IP);
        String port = prefs.getString("serverPort",  String.valueOf(ServerConfig.DEFAULT_PORT));
        if (ip == null || ip.isEmpty()) return;

        File configFile = new File(DIR_DATA, "config.json");
        if (!configFile.exists()) return;

        String raw = read(configFile.getAbsolutePath());
        org.json.JSONObject json = new org.json.JSONObject(raw);

        // This server multiplexes login, JS5 and world-list on a single port
        // (43594 + worldId = 43595 for world 1), so all three must be that same
        // port. The known-good desktop server_profiles.json uses 43595 for all
        // three; a prior "portInt - 1" pointed server_port at a dead 43594 and
        // caused error_game_js5connect.
        ServerConfig.Result resolved = ServerConfig.normalize(ip, ip, port, null, null);
        json.put("ip_address",    resolved.ip);
        json.put("ip_management", resolved.ip);
        json.put("server_port", resolved.port);
        json.put("wl_port",     resolved.port);
        json.put("js5_port",    resolved.port);

        try (java.io.FileWriter fw = new java.io.FileWriter(configFile)) {
            fw.write(json.toString(2));
        }

        // Read-back so the device log shows exactly what the client will dial.
        String readback = "config.json -> ip=" + resolved.ip
                + " server_port=" + resolved.port
                + " wl_port=" + resolved.port
                + " js5_port=" + resolved.port;
        android.util.Log.i("Tools", readback);
        try { Logger.appendToLog("[launcher] " + readback); } catch (Throwable ignored) {}
    } catch (Exception e) {
        android.util.Log.w("Tools", "patchConfigJson failed: " + e.getMessage());
    }
}
```

Note the common case is unchanged: a valid numeric pref port (e.g. `"43595"`) resolves to that same int and is written to all three fields, exactly as before.

**Verify**: `grep -n "portInt\|NumberFormatException" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java` → no matches remain in `patchConfigJson` (the manual parse is gone).

### Step 3: Route `SettingsActivity.applyConfigJson` through `ServerConfig.normalize`

Replace the body of `applyConfigJson` (`SettingsActivity.kt:204-217`) to source candidates from the parsed JSON and delegate to `ServerConfig.normalize`, keeping the existing "throw if there's no IP at all" contract:

```kotlin
private fun applyConfigJson(repo: PreferencesRepository, body: String): String {
    val json = org.json.JSONObject(body)
    val ipAddress = if (json.has("ip_address")) json.getString("ip_address") else null
    val ipManagement = if (json.has("ip_management")) json.getString("ip_management") else null
    if (ipAddress.isNullOrEmpty() && ipManagement.isNullOrEmpty()) {
        throw IllegalStateException("config has no ip_address")
    }
    val serverPort = if (json.has("server_port")) json.getInt("server_port").toString() else null
    val wlPort = if (json.has("wl_port")) json.getInt("wl_port").toString() else null
    val js5Port = if (json.has("js5_port")) json.getInt("js5_port").toString() else null

    val resolved = ServerConfig.normalize(ipAddress, ipManagement, serverPort, wlPort, js5Port)
    repo.putString("serverIp", resolved.ip)
    repo.putString("serverPort", resolved.port.toString())
    return "${resolved.ip}:${resolved.port}"
}
```

This preserves the current precedence (`wl_port` > `js5_port` > `server_port` > default) and ADDS the range validation (1-65535) that was previously missing.

**Verify**: `grep -n "when {" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt` → the old inline `when` port-precedence block is gone from `applyConfigJson`.

### Step 4: Add `ServerConfigTest`

Create `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/ServerConfigTest.java`, mirroring `CameraPanTest.java`'s structure (`org.junit.Test` + `assertEquals`, one `@Test` method per case). Cover:

- missing port (all three port args `null`) → `DEFAULT_PORT`
- non-numeric/invalid port string (e.g. `"abc"`) on all three → `DEFAULT_PORT`
- only `serverPort` set, `wlPort`/`js5Port` null → resolves to `serverPort`'s value
- mismatched `wlPort`/`js5Port`/`serverPort` (all three different) → `wlPort` wins
- empty/blank `ipAddress` (`""` or `"  "`) with a non-blank `ipManagement` → falls back to `ipManagement`
- both IP args blank/null → `DEFAULT_IP`
- port out of range (e.g. `"0"` and `"70000"`) → `DEFAULT_PORT`
- the common/happy case: all three ports equal and valid (e.g. `"43595"`) and a normal IP → that exact ip/port pair, unchanged

**Verify**: `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:testDebugUnitTest --tests net.kdt.pojavlaunch.ServerConfigTest"` → all new tests pass.

### Step 5: Full build + test gate

**Verify**: `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug :app_pojavlauncher:testDebugUnitTest"` → `BUILD SUCCESSFUL`, all tests (old + new) pass.

### Step 6: Device smoke — actual server connect (REQUIRED, launch-critical)

Install the debug APK on a physical device and, per `docs/verification/device-smoke-checklist.md`, verify BOTH:
- **Play HD** boots into the game (GL surface renders, reaches the client login screen) against a real 2009Scape server.
- **Play SD** launches the AWT path and also reaches the login screen against the same server.

Confirm no `error_game_js5connect` (or any new connect error) appears. This is not optional for this plan — it is the only end-to-end confirmation that the refactor preserved the resolved-port behavior against a live server.

**Verify**: both HD and SD reach the login screen; device logcat shows the `config.json -> ip=... server_port=... wl_port=... js5_port=...` line with all three ports equal to the configured port.

## Test plan

- New tests: `ServerConfigTest` (Step 4 list above), plus the existing `CameraPanTest` continuing to pass.
- Structural pattern: `customcontrols/CameraPanTest.java`.
- Verification: `./gradlew :app_pojavlauncher:testDebugUnitTest` (via Docker, see Commands) → all pass, including the new `ServerConfigTest` cases.

## Done criteria

Machine-checkable. ALL must hold:

- [ ] `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug :app_pojavlauncher:testDebugUnitTest"` exits 0 / `BUILD SUCCESSFUL`
- [ ] `ServerConfigTest` exists under `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/` and all its cases (Step 4) pass
- [ ] Both `Tools.patchConfigJson` and `SettingsActivity.applyConfigJson` call `ServerConfig.normalize` (`grep -n "ServerConfig.normalize" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt` shows one hit in each)
- [ ] Device smoke (Step 6): HD and SD both connect to a real server with no js5connect error
- [ ] No files outside the in-scope list are modified (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:

- The code at `Tools.java:157` or `SettingsActivity.kt:199-217` doesn't match the excerpts in "Current state" (drift since this plan was written).
- Reconciling the two precedence rules changes the **resolved port for the common case** (all three port fields already equal and valid, e.g. `server_port == wl_port == js5_port == 43595`) — that must stay byte-identical before and after this change. If your implementation of Steps 2/3 produces a different resolved port than before for that case, STOP and report; do not ship a change that alters common-case connectivity.
- A step's verification fails twice after a reasonable fix attempt.
- The device smoke test (Step 6) shows a connect failure that did not occur before this change.
- You discover a caller of `patchConfigJson` or `applyConfigJson` that depends on the OLD (unvalidated) port passing through unchanged (e.g. relies on an out-of-range port reaching `config.json` for some downstream reason) — the new range validation would change behavior for that caller.

## Maintenance notes

- Plan 029 (server-profile switcher, if/when planned) should route through `ServerConfig.normalize` rather than re-deriving IP/port logic a third time.
- Reviewer: check that the doc comment's stated precedence (`wlPort > js5Port > serverPort`) matches what `SettingsActivity.applyConfigJson` used to do before this change (it should — this plan does not change SettingsActivity's precedence order, only adds range validation and moves the logic into `ServerConfig`).
- Follow-up (deferred, out of scope here): Plan 013's import file-handling could also validate the config.json shape/size before it ever reaches `applyConfigJson` — not needed by this plan since `ServerConfig.normalize` already degrades safely on bad/missing fields.
