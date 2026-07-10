# Plan 013: Harden config.json / plugin-zip import file handling in MyDialogFragment and SettingsActivity

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 8ee361ea1..HEAD -- app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MyDialogFragment.java app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt`
> If either file changed since this plan was written, compare the "Current
> state" excerpts against the live code before proceeding; on a mismatch,
> treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: LOW
- **Depends on**: none
- **Category**: security
- **Planned at**: commit `8ee361ea1`, 2026-07-10

## Why this matters

The Settings screen lets a user import a `config.json` (which decides which
server/IP:port the client connects to) or a plugin `.zip` (which becomes code
loaded onto the embedded JVM's classpath) from an arbitrary file picked via
`Intent.ACTION_GET_CONTENT`. The handling code in `MyDialogFragment.onActivityResult`
has three independent defects: (1) the JSON import path never checks that the
picked file even parses as JSON before overwriting the live `config.json`, so a
garbage or huge file corrupts the config the client relies on; (2) the ZIP
import path does not check `resultCode` at all, so cancelling the file picker
(`RESULT_CANCELED`, `data == null`) causes an uncaught `NullPointerException`
crash in the Settings dialog; (3) both paths leak the `InputStream`/`FileOutputStream`
pair whenever an `IOException` interrupts the copy loop, because the streams are
only closed on the success path. None of this requires an attacker — a user
tapping "cancel" or picking the wrong file triggers it. Fixing it makes the
import flow crash-proof and leak-free, and stops obviously-invalid data from
silently replacing a working `config.json`.

## Current state

- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MyDialogFragment.java` —
  `onActivityResult` (confirmed lines 190-245 in the current file; imports
  already include `Toast` at line 23, `IOException`/`InputStream`/`FileOutputStream`
  at the bottom of the import block):

  ```java
  190  @Override
  191  public void onActivityResult(int requestCode, int resultCode, Intent data) {
  192      if (requestCode == FILE_SELECT_CODE_JSON) {
  193          if (resultCode == RESULT_OK) {
  194              Uri uri = data.getData();
  195              File config = new File(Tools.DIR_DATA, "config.json"); // file to overwrite
  196              try {
  197                  Log.d("TAG", "Starting copy: " + uri.getPath());
  198                  InputStream inputStream = getContext().getContentResolver().openInputStream(uri);
  199                  FileOutputStream fileOutputStream = new FileOutputStream(config);
  200                  byte buf[] = new byte[1024];
  201                  int len;
  202                  while ((len = inputStream.read(buf)) > 0) {
  203                      fileOutputStream.write(buf, 0, len);
  204                  }
  205                  Toast.makeText(getContext(), "Config loaded. Please restart the app.",
  206                          Toast.LENGTH_SHORT).show();
  207
  208                  fileOutputStream.close();
  209                  inputStream.close();
  210              } catch (IOException e1) {
  211                  Log.d("error", "Error with file " + e1);
  212              }
  213          }
  214      } else if(requestCode == FILE_SELECT_CODE_ZIP) {
  215          Uri uri = data.getData();
  216          // Create a temporary file in your app's cache directory
  217          File tempFile = new File(getContext().getCacheDir(), "temp.zip");
  218
  219          try {
  220              // Open an InputStream to the selected file
  221              InputStream inputStream = getContext().getContentResolver().openInputStream(uri);
  222
  223              // Open a FileOutputStream to your temporary file
  224              FileOutputStream fileOutputStream = new FileOutputStream(tempFile);
  225
  226              // Copy the contents
  227              byte[] buffer = new byte[1024];
  228              int read;
  229              while ((read = inputStream.read(buffer)) != -1) {
  230                  fileOutputStream.write(buffer, 0, read);
  231              }
  232
  233              // Close the streams
  234              fileOutputStream.close();
  235              inputStream.close();
  236
  237              // Now you can pass the File object to your method
  238              AsyncAssetManager.extractPluginZip(tempFile);
  239
  240          } catch (IOException e) {
  241              throw new RuntimeException(e);
  242          }
  243      }
  244  }
  ```

  Defects, precisely:
  - JSON path (192-213): no `data != null` / `data.getData() != null` guard
    (would NPE if ever reached with a null Intent, though `RESULT_OK` normally
    implies non-null `data` here — guard anyway, cheap and correct); the byte
    copy overwrites `config` directly with **no validation that it's JSON at
    all** and **no size cap**; `fileOutputStream`/`inputStream` are closed only
    on the line *after* the write loop, so if `read()` or `write()` throws mid-
    copy, both streams leak (never reach `.close()`, no `finally`).
  - ZIP path (214-242): `resultCode` is **never checked** — if the user cancels
    the picker, `resultCode == RESULT_CANCELED` and `data` is commonly `null`,
    so `data.getData()` at line 215 throws `NullPointerException`, an unhandled
    crash of the Settings dialog. On any `IOException` during the copy, line
    241 does `throw new RuntimeException(e)`, which is also an unhandled crash
    (no toast, no graceful recovery) and the streams above leak the same way
    as the JSON path.

- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt` —
  `applyConfigFromUri` (confirmed lines 185-197 in the current file):

  ```kotlin
  185  private fun applyConfigFromUri(uri: Uri) {
  186      val repo = PojavApplication.appContainer.preferencesRepository
  187      try {
  188          val body = contentResolver.openInputStream(uri)
  189              ?.bufferedReader()?.use { it.readText() }
  190              ?: throw IllegalStateException("could not read file")
  191          val result = applyConfigJson(repo, body)
  192          Toast.makeText(this, "Loaded $result — IP/port overridden", Toast.LENGTH_LONG).show()
  193          recreate()
  194      } catch (e: Exception) {
  195          Toast.makeText(this, "Load failed: ${e.message}", Toast.LENGTH_LONG).show()
  196      }
  197  }
  ```

  This path already does the right thing on parse failure (catches
  `Exception`, shows a toast, does not crash — `applyConfigJson` at
  `SettingsActivity.kt:204-217` parses with `org.json.JSONObject` and throws a
  clear `IllegalStateException` for missing fields) and does not leak streams
  (`use {}` closes them). Its only gap is `it.readText()` at line 189 slurping
  the entire picked file into memory with no size cap — a multi-hundred-MB
  file picked by mistake (or a malicious file `content://` provider) can OOM
  the process before `applyConfigJson` ever gets to validate it.
  `importServerConfig` (`SettingsActivity.kt:151-178`) has the equivalent
  network-side unbounded read; that is explicitly **out of scope here** — see
  plan 014.

- Convention: this repo has no test harness beyond one JUnit4 test,
  `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/customcontrols/CameraPanTest.java`,
  which tests a small pure class (`CameraPan.java`) with no Android framework
  dependency. New pure logic (e.g. a size-cap check, a "is this valid JSON"
  check) should follow that shape: extract to a plain Java/Kotlin class under
  `net/kdt/pojavlaunch/...` with no `Context`/`Uri`/Android imports, and add a
  matching test under `app_pojavlauncher/src/test/java/...`.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Build APK | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` | `BUILD SUCCESSFUL`; APK at `app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk` |
| Unit tests | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug :app_pojavlauncher:testDebugUnitTest"` | all tests pass; report under `app_pojavlauncher/build/test-results/testDebugUnitTest/` |

## Scope

**In scope:**
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MyDialogFragment.java` — `onActivityResult` only (and a small extracted helper class if you choose to pull the size-cap/JSON-validity check out, per Step 3).
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt` — `applyConfigFromUri` only (add the size cap; do not touch `importServerConfig`'s network transport — that is plan 014).
- A new small test file under `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/...` if you extract pure logic (Step 3).

**Out of scope (do NOT touch):**
- `Tools.patchConfigJson` (`Tools.java:157-...`) — a separate normalizer covered by plan 018; do not change its port/IP handling.
- `AndroidManifest.xml`, `usesCleartextTraffic`, `DEFAULT_CONFIG_URL`, and `importServerConfig`'s `HttpURLConnection` — covered by plan 014. Do not add a "confirm before importing over HTTP" dialog here; that belongs to 014.
- `Tools.ZipTool.unzip` itself and its entry/size caps — covered by plan 015. This plan only fixes the *file picker plumbing* around the call to `AsyncAssetManager.extractPluginZip`, not the unzip internals.
- Any other `MyDialogFragment` UI (plugin enable/disable switches, lines 130-188) — unrelated to import handling.

## Git workflow

- Branch: `advisor/013-harden-config-import-validation`
- Commit per logical unit (e.g. one commit for the JSON path, one for the ZIP path, one for `SettingsActivity.kt`'s cap); message style: conventional commits, e.g. `fix: guard config/plugin import against cancel, leaks, and oversized files`.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Fix the ZIP import path — guard `resultCode`/`data`, stop leaking streams, stop crashing on IOException

In `MyDialogFragment.java`, replace the `else if(requestCode == FILE_SELECT_CODE_ZIP)` branch (lines 214-242) so that:
- It first checks `resultCode == RESULT_OK && data != null && data.getData() != null`; if not, it returns/falls through without touching `uri` (matching the JSON branch's existing `if (resultCode == RESULT_OK)` guard shape — this repo already imports `RESULT_OK` statically at the top of the file).
- The `InputStream`/`FileOutputStream` pair is opened with try-with-resources (or a `try { ... } finally { close both, ignoring secondary exceptions }`) so a mid-copy `IOException` cannot leak either stream.
- Add a byte-count cap while copying (reuse the constant/helper from Step 3) and abort with a caught exception + `Toast` if exceeded — do NOT let a huge file finish copying to `tempFile` uncapped.
- On any `IOException` (including a cap violation), replace `throw new RuntimeException(e)` with a `Toast.makeText(getContext(), "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show()` plus a `Log.e` — no crash.
- `tempFile` should be deleted after `AsyncAssetManager.extractPluginZip(tempFile)` succeeds or fails, so cancelled/rejected imports don't leave stale temp zips in the cache dir (use a `finally` block).

**Verify**: `grep -n "throw new RuntimeException" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MyDialogFragment.java` → no matches inside `onActivityResult` (the whole method, lines ~190-245-ish after your edit).

### Step 2: Fix the JSON import path — guard `data`, try-with-resources, validate before overwrite

In the `if (requestCode == FILE_SELECT_CODE_JSON)` branch (lines 192-213):
- Add `data != null && data.getData() != null` to the existing `resultCode == RESULT_OK` check.
- Copy the picked file's bytes into memory (or a temp file) first — **not directly onto `config`** — capped at the same byte limit as Step 1 (a plain few-MB ceiling is enough; `config.json` is a small file).
- After the capped read completes, validate it parses as JSON (`new org.json.JSONObject(new String(bytes, StandardCharsets.UTF_8))` in a try/catch) before writing anything to `Tools.DIR_DATA/config.json`. If either the size cap or the JSON parse fails, show a `Toast` describing the failure and leave the existing `config.json` untouched.
- Only if both checks pass, write the captured bytes to `config` (try-with-resources for the `FileOutputStream`).
- Keep the existing success `Toast` ("Config loaded. Please restart the app.").

**Verify**: `grep -n "JSONObject" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MyDialogFragment.java` → at least one match inside the JSON branch.

### Step 3 (optional but preferred): Extract the size cap / JSON-validity check into a small testable helper

Create a small pure class, e.g. `net/kdt/pojavlaunch/ImportGuard.java` (no Android imports — plain `byte[]`/`String` in, `boolean`/exception out), with:
- A `MAX_IMPORT_BYTES` constant (pick something like `5 * 1024 * 1024` — 5 MB; `config.json` and any legitimate plugin zip picked through this path are far smaller).
- A method that checks a byte count against the cap.
- A method that checks whether a `String` parses as a JSON object (wraps `org.json.JSONObject`).

Use this class from both `MyDialogFragment.java` (Steps 1-2) and `SettingsActivity.kt`'s `applyConfigFromUri` (Step 4), so the cap is defined once.

**Verify**: `./gradlew :app_pojavlauncher:assembleDebug` (via the Docker command above) still succeeds with the new file added.

### Step 4: Cap the read in `SettingsActivity.applyConfigFromUri`

In `SettingsActivity.kt:185-197`, change the `readText()` call at line 189 so it does not slurp an unbounded stream — e.g. wrap the `InputStream` to read at most `MAX_IMPORT_BYTES + 1` bytes and throw (into the existing `catch (e: Exception)` at line 194, which already shows a toast and does not crash) if the cap is exceeded. If you built the helper in Step 3, reuse its constant; otherwise define a local `private const val MAX_IMPORT_BYTES = 5 * 1024 * 1024` in this file.

**Verify**: `grep -n "MAX_IMPORT_BYTES" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/SettingsActivity.kt` → at least one match near `applyConfigFromUri`.

## Test plan

- If you extracted `ImportGuard` in Step 3, add
  `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/ImportGuardTest.java`
  modeled on `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/customcontrols/CameraPanTest.java`
  (plain JUnit4, no Android framework, no Robolectric). Cover:
  - a byte count under the cap passes; one at/over the cap is rejected.
  - a valid JSON object string passes; a non-JSON string (e.g. `"not json"`) and truncated/garbage JSON are rejected.
- The Activity-result wiring itself is not unit-testable without Robolectric
  (none is set up in this repo — do not add one for this plan). Verify by
  device/manual steps instead, and list them here for whoever runs the device
  smoke pass:
  1. Settings → Advanced → pick "Load plugin" (ZIP) → tap cancel in the file
     picker → app does not crash, no plugin extracted.
  2. Settings → Advanced → import a valid small plugin `.zip` → extracts
     normally into `plugins/` (no regression).
  3. Settings → Advanced → import a valid `config.json` copy → toast says
     loaded, file is overwritten.
  4. Settings → Advanced → import a garbage (non-JSON) file via the JSON
     picker → toast shows a failure, `config.json` is unchanged (compare
     mtime/hash before and after).
  5. Import a file larger than the chosen cap via either picker → rejected
     with a toast, no partial write.
- Run `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug :app_pojavlauncher:testDebugUnitTest"` → `BUILD SUCCESSFUL`, all tests pass including any new `ImportGuardTest`.

## Done criteria

Machine-checkable. ALL must hold:

- [ ] Docker build command above exits with `BUILD SUCCESSFUL`
- [ ] `docker run ... ./gradlew :app_pojavlauncher:testDebugUnitTest` passes (including any new test)
- [ ] `grep -n "throw new RuntimeException" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MyDialogFragment.java` returns no match inside `onActivityResult`
- [ ] `grep -n "resultCode == RESULT_OK" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MyDialogFragment.java` returns a match in **both** the JSON and ZIP branches
- [ ] No files outside the in-scope list are modified (`git status`)
- [ ] `plans/README.md` status row updated
- [ ] Device smoke steps 1-5 above completed and noted in the PR/commit description

## STOP conditions

Stop and report back (do not improvise) if:

- The code at `MyDialogFragment.java:190-245` or `SettingsActivity.kt:185-197` doesn't match the excerpts above (drift since this plan was written) — re-read the live file before proceeding.
- A verification step fails twice after a reasonable fix attempt.
- The fix appears to require touching `Tools.patchConfigJson`, `AndroidManifest.xml`, `Tools.ZipTool.unzip`'s internals, or `importServerConfig`'s HTTP transport — those belong to plans 018/014/015; report instead of expanding scope.
- You find the ZIP path is reachable with `data == null` even when `resultCode == RESULT_OK` in a way this plan's guard doesn't cover — report the exact repro rather than guessing at a broader fix.

## Maintenance notes

- A reviewer should confirm the size cap chosen in Step 3/4 is generous enough
  for legitimate config/plugin imports but still meaningfully bounds memory —
  5 MB is a starting suggestion, not a hard requirement; if it turns out too
  small for a real plugin zip users import this way, raise it, don't remove it.
- This plan intentionally does NOT change `Tools.ZipTool.unzip`'s internal
  entry/size accounting (zip-bomb protection) — that's plan 015, which is
  reached from the same `AsyncAssetManager.extractPluginZip` call this plan
  hardens the caller of. Both plans should land before the import flow is
  considered fully hardened.
- If a future change adds a "confirm before overwriting config.json" dialog,
  that's a natural follow-on to Step 2's validate-then-write ordering — the
  validated bytes are already held in memory before the write happens.
