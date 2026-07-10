# Plan 015: Cap total size / entry count in ZipTool.unzip to stop zip-bomb extraction

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 8ee361ea1..HEAD -- app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java`
> If this file changed since this plan was written, compare the "Current
> state" excerpt against the live code before proceeding; on a mismatch, treat
> it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: LOW
- **Depends on**: none
- **Category**: security
- **Planned at**: commit `8ee361ea1`, 2026-07-10

## Why this matters

`Tools.ZipTool.unzip` already rejects path-traversal ("Zip Slip") entries
(added by plan 001 — do not touch or re-add that guard) but has **no limit on
total uncompressed bytes written, per-entry size, or entry count**. This
method is reachable from a user-picked, fully untrusted `.zip` file: Settings
→ Advanced → "Load plugin" → `MyDialogFragment.java:238` copies the picked
file to a temp file and calls `AsyncAssetManager.extractPluginZip`
(`AsyncAssetManager.java:169-171`), which calls straight into `unzip` with no
size accounting. A small, deliberately crafted archive (a handful of KB
compressed) can expand to gigabytes on extraction, filling device storage — a
denial-of-service a user can trigger by importing one bad file, no special
privileges needed. Extracted plugin content is not just data, either — it
ends up on the embedded JVM's classpath via `-DpluginDir=` (`Tools.java:219`
for the HD/GL path, `JavaGUILauncherActivity.java:458` for the SD/AWT path),
so this is also the entry point for the next tier of risk (arbitrary code
loaded with no integrity check) — but that (signing/hashing untrusted
plugins) is out of scope here; this plan only closes the storage-exhaustion
gap and records the integrity gap as a named follow-up.

## Current state

- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java` — confirmed
  current lines 587-618, `ZipTool.unzip` (note: line numbers shifted from
  plan 001's original 534-561 because that plan already inserted the zip-slip
  guard at lines 597-600 — **do not remove or alter those three lines**):

  ```java
  587  public static void unzip(File zipFile, File targetDirectory) throws IOException {
  588      final int BUFFER_SIZE = 1024;
  589      ZipInputStream zis = new ZipInputStream(
  590              new BufferedInputStream(new FileInputStream(zipFile)));
  591      try {
  592          ZipEntry ze;
  593          int count;
  594          byte[] buffer = new byte[BUFFER_SIZE];
  595          while ((ze = zis.getNextEntry()) != null) {
  596              File file = new File(targetDirectory, ze.getName());
  597              String canonicalTarget = targetDirectory.getCanonicalPath() + File.separator;
  598              if (!file.getCanonicalPath().startsWith(canonicalTarget)) {
  599                  throw new IOException("Blocked zip-slip entry outside target directory: " + ze.getName());
  600              }
  601              File dir = ze.isDirectory() ? file : file.getParentFile();
  602              if (!dir.isDirectory() && !dir.mkdirs())
  603                  throw new FileNotFoundException("Failed to ensure directory: " +
  604                          dir.getAbsolutePath());
  605              if (ze.isDirectory())
  606                  continue;
  607              FileOutputStream fout = new FileOutputStream(file);
  608              try {
  609                  while ((count = zis.read(buffer)) != -1)
  610                      fout.write(buffer, 0, count);
  611              } finally {
  612                  fout.close();
  613              }
  614          }
  615      } finally {
  616          zis.close();
  617      }
  618  }
  619  }
  ```

  No cap exists on: bytes written per entry, cumulative bytes written across
  the archive, or number of entries processed.

- Callers, confirmed:
  - `AsyncAssetManager.java:169-171` (untrusted, user-picked import):
    ```java
    169  public static void extractPluginZip(File plugin) throws IOException {
    170      Tools.ZipTool.unzip(plugin, new File(Tools.DIR_DATA + "/plugins/"));
    171  }
    ```
  - `AsyncAssetManager.java:159-163` (trusted, bundled-in-APK asset unpack — the
    same `unzip` is shared, so any cap added must not reject legitimate bundled
    plugin content):
    ```java
    159  Tools.copyAssetFile(ctx, PLUGIN_PATH + "/" + plugin, Tools.DIR_DATA, true);
    160  Tools.ZipTool.unzip(
    161          new File(Tools.DIR_DATA + "/" + plugin),
    162          new File(Tools.DIR_DATA + "/plugins/")
    163  );
    ```
  - `MyDialogFragment.java:238` calls `AsyncAssetManager.extractPluginZip(tempFile)` from the file-picker path — the untrusted-input entry point.

- Bundled plugin sizes (measured directly, `unzip -l` on each
  `app_pojavlauncher/src/main/assets/plugins/*.zip`), so the caps chosen must
  sit comfortably above these:

  | Plugin | Compressed | Uncompressed total | Entry count |
  |---|---|---|---|
  | GroundItems.zip | ~294 KB | ~3,379,735 bytes (~3.4 MB, dominated by one `item_configs.json` at ~3.35 MB) | 5 |
  | MobileClientBindings.zip | ~7.6 KB | ~12,200 bytes | 5 |
  | LoginTimer.zip | ~6.0 KB | ~9,785 bytes | 4 |
  | SlayerTrackerPlugin.zip | ~3.3 KB | ~5,868 bytes | 1 |
  | RememberMyLogin.zip | ~2.8 KB | ~5,760 bytes | 1 |

  The largest bundled plugin is ~3.4 MB uncompressed across 5 entries.
  Re-verify these numbers yourself before picking thresholds (`unzip -l
  <file>` on each file under
  `app_pojavlauncher/src/main/assets/plugins/`) in case assets changed since
  this plan was written.

- Convention: the one existing unit test,
  `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/customcontrols/CameraPanTest.java`,
  is a plain JUnit4 test with no Android framework dependency. `ZipTool.unzip`
  takes a `File`/`File` pair and is a `static` method with no `Context`
  dependency, so it is directly callable from a JUnit4 test using
  `java.nio.file.Files.createTempDirectory` for scratch space.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Build APK | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` | `BUILD SUCCESSFUL` |
| Unit tests | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug :app_pojavlauncher:testDebugUnitTest"` | all tests pass |

## Scope

**In scope:**
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java` — `ZipTool.unzip` only (add caps; do not touch `ZipTool.zip` or the zip-slip guard at lines 597-600).
- New test file: `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/ZipToolTest.java`.

**Out of scope (do NOT touch):**
- The zip-slip canonical-path guard (`Tools.java:597-600`) — already correct, leave it exactly as-is.
- `AsyncAssetManager.java` and `MyDialogFragment.java` — no change needed; they call the now-capped `unzip` unmodified. (The file-picker plumbing around them — cancel/crash/leak handling — is plan 013's scope, not this one.)
- `multirt/MultiRTUtils.java`'s separate tar extraction (`uncompressTarXZ`) — a related but distinct code path, covered by plan 026. Do not touch it here.
- Plugin/code integrity checking (signing, hashing, allowlisting) — explicitly deferred to direction plan 030. Do not attempt a signing/verification system in this plan.

## Git workflow

- Branch: `advisor/015-zip-bomb-and-plugin-integrity-caps`
- One commit for the cap logic, one for the test; message style: conventional commits, e.g. `fix: cap total size and entry count in ZipTool.unzip to prevent zip-bomb extraction`.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Add cumulative-size, per-entry-size, and entry-count caps to `unzip`

In `Tools.java`, inside `ZipTool`, add named constants above `unzip` (values
chosen comfortably above the ~3.4 MB / 5-entry largest bundled plugin found in
"Current state" — leave headroom for future bundled plugins to grow, e.g. an
order of magnitude above observed sizes):

```java
private static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 200L * 1024 * 1024; // 200 MB
private static final long MAX_ENTRY_UNCOMPRESSED_BYTES = 50L * 1024 * 1024;  // 50 MB
private static final int MAX_ENTRY_COUNT = 5000;
```

Then, in the `while ((ze = zis.getNextEntry()) != null)` loop (`Tools.java:595`
onward):
- Increment an entry counter each iteration; if it exceeds `MAX_ENTRY_COUNT`,
  throw `IOException` before any further processing of that entry.
- Track cumulative bytes written across the whole archive in a `long`
  accumulator declared before the loop.
- In the inner copy loop (currently `Tools.java:608-613`), after each
  `fout.write(buffer, 0, count)`, add `count` to both a per-entry counter and
  the cumulative accumulator; if the per-entry counter exceeds
  `MAX_ENTRY_UNCOMPRESSED_BYTES` or the cumulative accumulator exceeds
  `MAX_TOTAL_UNCOMPRESSED_BYTES`, close the output stream, delete the
  partially-written `file`, and throw `IOException` with a message identifying
  which cap was hit and the entry name.
- Ensure these checks happen incrementally *during* the copy (not just via
  `ZipEntry.getSize()`, which callers can lie about in the local file header) —
  count actual bytes written, matching how the zip-slip guard already avoids
  trusting entry metadata blindly.

**Verify**: `grep -n "MAX_TOTAL_UNCOMPRESSED_BYTES\|MAX_ENTRY_UNCOMPRESSED_BYTES\|MAX_ENTRY_COUNT" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java` → three constant definitions plus usages inside `unzip`.

### Step 2: Confirm bundled plugins still fit under the caps

Re-run the size table from "Current state" against the caps chosen in Step 1
(3.4 MB / 5 entries vs 50 MB per-entry / 200 MB total / 5000 entries — should
pass with wide margin). If any bundled plugin is close to or over a cap,
raise the cap and re-verify — do not shrink a bundled plugin to fit.

**Verify**: `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` → `BUILD SUCCESSFUL` (compiles), then device: app launches and all five bundled plugins (`GroundItems`, `LoginTimer`, `MobileClientBindings`, `RememberMyLogin`, `SlayerTrackerPlugin`) still appear extracted under the plugins directory after a fresh install / cache-cleared run.

### Step 3: Add a JUnit test exercising both the happy path and the caps

Create `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/ZipToolTest.java`,
modeled on `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/customcontrols/CameraPanTest.java`'s
plain-JUnit4 shape (`@Test`, `assertEquals`/`assertThrows`, no Android
framework/Robolectric). Use `java.nio.file.Files.createTempDirectory` for both
the source zip location and the extraction target so nothing touches the real
filesystem paths. Cover:
- A small, normal zip (2-3 tiny entries, well under all three caps) extracts
  successfully and the extracted file contents match what was zipped.
- A zip built in-test with an entry count over `MAX_ENTRY_COUNT` (many tiny
  empty entries — cheap to construct, keeps the test file small) is rejected
  with `IOException`.
- A zip built in-test with a single entry whose actual written bytes exceed
  `MAX_ENTRY_UNCOMPRESSED_BYTES` (or, to keep the test fast, temporarily use a
  smaller constant injected via a test-only overload/package-private setter if
  `ZipTool`'s constants aren't easily test-overridable — do not add a public
  API surface just for testing; if the constants are `private static final`
  and not easily overridden, write the entry-count test as the primary
  regression case and use a real end-to-end run only for a modestly sized
  (e.g. a few MB) "obviously over a much smaller test-local cap passed via
  reflection" — pick whichever approach keeps the test file itself small; do
  not commit a multi-hundred-MB test fixture).

Keep the malicious test zips synthesized at test-run time (e.g. via
`java.util.zip.ZipOutputStream` in a `@Before`/helper method) — do not commit
static `.zip` binary fixtures to the repo.

**Verify**: `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:testDebugUnitTest"` → all tests pass, including the new `ZipToolTest` cases.

## Test plan

- `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/ZipToolTest.java` (new) — see Step 3 for exact cases: normal small zip extracts; entry-count-over-cap zip rejected; oversized-entry zip rejected.
- Structural pattern to follow: `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/customcontrols/CameraPanTest.java`.
- Device: confirm all five bundled plugins still extract after this change (Step 2).
- Run: `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug :app_pojavlauncher:testDebugUnitTest"` → `BUILD SUCCESSFUL`, all tests pass.

## Done criteria

Machine-checkable. ALL must hold:

- [ ] Docker build command exits with `BUILD SUCCESSFUL`
- [ ] Docker test command passes, including new `ZipToolTest` cases
- [ ] `grep -n "MAX_TOTAL_UNCOMPRESSED_BYTES\|MAX_ENTRY_UNCOMPRESSED_BYTES\|MAX_ENTRY_COUNT" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java` shows caps defined and referenced inside `unzip`
- [ ] `grep -n "Blocked zip-slip entry" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java` still shows the untouched plan-001 guard
- [ ] Device: app launches and all five bundled plugins load (see Step 2)
- [ ] No files outside the in-scope list are modified (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:

- The code at `Tools.java:587-618` doesn't match the excerpt above (drift, e.g. the zip-slip guard is missing entirely, meaning plan 001 hasn't landed yet) — re-read the live file; if the zip-slip guard is genuinely absent, report it rather than silently adding both fixes yourself.
- A bundled plugin's uncompressed size or entry count is measured (Step 2) to
  actually exceed a chosen cap — raise the cap and re-verify; if raising the
  cap to accommodate it would defeat the purpose of the cap (e.g. a bundled
  plugin is itself hundreds of MB), report the sizes and stop rather than
  picking an arbitrarily large "cap" that isn't actually protective.
- A verification step fails twice after a reasonable fix attempt.
- The fix appears to require touching `AsyncAssetManager.java`,
  `MyDialogFragment.java`, or `MultiRTUtils.java` — out of scope; report.

## Maintenance notes

- The integrity gap this plan does NOT close: extracted plugin `.class` files
  become JVM classpath content with zero signature/hash verification
  (`-DpluginDir=` in `Tools.java:219` and `JavaGUILauncherActivity.java:458`).
  That is a distinct, larger effort (code signing or a hash allowlist) tracked
  as a follow-up in the advisor's direction plan (plan 030) — do not attempt
  it here.
- If a future bundled plugin legitimately needs to exceed these caps (e.g. a
  much larger data-driven plugin), the caps must be raised deliberately and
  the "Current state" size table in this file re-measured — don't silently
  bump the constant without re-checking headroom against real bundled sizes.
- A reviewer should confirm the per-entry/cumulative counters use bytes
  actually written to disk, not `ZipEntry.getSize()`/`getCompressedSize()`
  (which come from the zip's own metadata and can be forged) — this mirrors
  why the existing zip-slip guard checks `getCanonicalPath()` of the
  destination rather than trusting the entry name's apparent shape.
