# Plan 001: Reject path-traversal ("Zip Slip") entries in ZIP extraction

> **Executor instructions**: Follow this plan step by step. Run every verification command and confirm the expected result before moving on. If a STOP condition occurs, stop and report — do not improvise. When done, update this plan's row in `plans/README.md`.
>
> **Drift check (run first)**: `git diff --stat 0e75ab512..HEAD -- app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/tasks/AsyncAssetManager.java`
> If either file changed since this plan was written, compare the "Current state" excerpts to the live code before proceeding; on a mismatch, STOP.

## Status
- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: security
- **Planned at**: commit `0e75ab512`, 2026-07-07

## Why this matters
`Tools.ZipTool.unzip` builds each output file path directly from the ZIP entry name with no containment check. A user can import a plugin `.zip` from the settings dialog (`MyDialogFragment` → `AsyncAssetManager.extractPluginZip`), so a crafted archive with `../` entry names can write **outside** `DIR_DATA/plugins/` — e.g. overwrite `DIR_DATA/config.json` (redirects the server the client connects to) or `DIR_DATA/rt4.jar` (the client the JVM runs). Since extracted content becomes code/config the embedded JVM loads, this is an arbitrary-file-overwrite → code-execution risk gated only by convincing a user to import a "plugin". A canonical-path containment check closes it and rejects only malicious archives.

## Current state
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java` — the `ZipTool.unzip` helper. As of this commit, `Tools.java:534-561`:
  ```java
  public static void unzip(File zipFile, File targetDirectory) throws IOException {
      final int BUFFER_SIZE = 1024;
      ZipInputStream zis = new ZipInputStream(
              new BufferedInputStream(new FileInputStream(zipFile)));
      try {
          ZipEntry ze;
          int count;
          byte[] buffer = new byte[BUFFER_SIZE];
          while ((ze = zis.getNextEntry()) != null) {
              File file = new File(targetDirectory, ze.getName());   // <-- line 543, no containment check
              File dir = ze.isDirectory() ? file : file.getParentFile();
              if (!dir.isDirectory() && !dir.mkdirs())
                  throw new FileNotFoundException("Failed to ensure directory: " +
                          dir.getAbsolutePath());
              if (ze.isDirectory())
                  continue;
              FileOutputStream fout = new FileOutputStream(file);
              try {
                  while ((count = zis.read(buffer)) != -1)
                      fout.write(buffer, 0, count);
              } finally {
                  fout.close();
              }
          }
      } finally {
          zis.close();
      }
  }
  ```
- Reachable from untrusted input: `MyDialogFragment.java:214-242` copies a user-picked `.zip` to `cacheDir/temp.zip` and calls `AsyncAssetManager.extractPluginZip(tempFile)`; `AsyncAssetManager.java:140-141`:
  ```java
  public static void extractPluginZip(File plugin) throws IOException {
      Tools.ZipTool.unzip(plugin, new File(Tools.DIR_DATA + "/plugins/"));
  ```
- `java.io.IOException` and `java.io.FileNotFoundException` are already imported in `Tools.java` (used in this method). No new imports needed.
- Convention: this repo has no test harness; verification is a successful build plus (for behavioral changes) an on-device check. New code must be Java 8 compatible (module `compileOptions` are `VERSION_1_8`).

## Commands you will need
| Purpose | Command | Expected on success |
|---|---|---|
| Build (local) | `./gradlew :app_pojavlauncher:assembleDebug` | `BUILD SUCCESSFUL`. Requires JDK 17, Android SDK platform 35, build-tools 35.0.0, NDK 25.2.9519653. |
| Build (CI fallback) | push branch to GitHub | "Android CI" workflow run is green, uploads `app-debug` |

If you have no local Android toolchain, use the CI fallback: push the branch and confirm the Actions run is green.

## Scope
**In scope:**
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java` (the `unzip` method only)

**Out of scope (do NOT touch):**
- `AsyncAssetManager.java` — no change needed; it calls the now-safe `unzip`.
- The `zip(...)` compression method in `ZipTool` — only `unzip` is vulnerable.
- Any native/`jni`/`gl4es`/`jre_lwjgl3glfw` code.

## Git workflow
- Branch: `advisor/001-zip-slip-plugin-import`
- One commit; message style matches repo (e.g. `fix(security): reject zip-slip entries in ZipTool.unzip`).
- Do NOT push or open a PR unless the operator instructed it (pushing to trigger CI verification is fine if you have no local build).

## Steps

### Step 1: Add a canonical-path containment check in `unzip`
In `Tools.java`, inside the `while ((ze = zis.getNextEntry()) != null)` loop, immediately after `File file = new File(targetDirectory, ze.getName());`, insert a containment guard that throws before any directory is created or any stream opened:

```java
File file = new File(targetDirectory, ze.getName());
String canonicalTarget = targetDirectory.getCanonicalPath() + File.separator;
if (!file.getCanonicalPath().startsWith(canonicalTarget)) {
    throw new IOException("Blocked zip-slip entry outside target directory: " + ze.getName());
}
```

This runs for both directory and file entries, so a traversal directory entry is rejected too. `getCanonicalPath()` resolves `..` segments and symlinks, so `../` names no longer escape.

**Verify**: `./gradlew :app_pojavlauncher:assembleDebug` → `BUILD SUCCESSFUL` (or CI green).

### Step 2: Confirm the guard is present and precedes I/O
**Verify**: `grep -n "Blocked zip-slip entry" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java` → one match, on a line **before** the `new FileOutputStream(file)` line.

## Test plan
No unit-test harness exists in this repo (see Plan 006). Manual verification instead:
- On-device (or via a scratch JVM if available): import a benign plugin `.zip` through Settings → Advanced → "Load plugin" and confirm it still extracts into `plugins/` normally (no regression).
- Adversarial confirmation is optional and must not ship in the repo: a `.zip` containing an entry named `../config.json` should now cause `extractPluginZip` to throw `IOException` instead of overwriting `DIR_DATA/config.json`. Do not commit any such test archive.

## Done criteria
- [ ] `./gradlew :app_pojavlauncher:assembleDebug` exits 0 (or CI run green)
- [ ] `grep -n "getCanonicalPath().startsWith" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java` returns a match inside `unzip`
- [ ] `git status` shows only `Tools.java` modified
- [ ] `plans/README.md` row updated

## STOP conditions
- The `unzip` code doesn't match the "Current state" excerpt (drift).
- The build fails twice after a reasonable fix attempt.
- The fix appears to need changes outside `Tools.java`.

## Maintenance notes
- Reviewer: confirm the check uses `getCanonicalPath()` (not `getAbsolutePath()`, which does not resolve `..`) and that the `File.separator` suffix prevents a sibling-prefix bypass (e.g. target `/a/plugins` vs `/a/plugins-evil`).
- If a future feature legitimately needs to write outside the target dir from an archive, that belongs in a separate, explicitly-trusted code path — do not relax this guard.
- Related: the same class of check should be applied to tar extraction in `MultiRTUtils.uncompressTarXZ` (separate finding, not in this plan).
