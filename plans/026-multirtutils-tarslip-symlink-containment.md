# Plan 026: Contain tar extraction in MultiRTUtils against path traversal and fix the symlink argument order

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 8ee361ea1..HEAD -- app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/multirt/MultiRTUtils.java`
> If this file changed since this plan was written, compare the "Current
> state" excerpt against the live code before proceeding; on a mismatch, treat
> it as a STOP condition.

## Status

- **Priority**: P3
- **Effort**: S
- **Risk**: LOW (latent — the only current input is the bundled in-APK JRE tarball, not remote/user-controlled data)
- **Depends on**: none
- **Category**: security
- **Planned at**: commit `8ee361ea1`, 2026-07-10

## Why this matters

`MultiRTUtils.uncompressTarXZ` is the tar-extraction routine used to unpack
the embedded JRE runtime. Unlike `Tools.ZipTool.unzip` (hardened against
path-traversal "Zip Slip" entries by plan 001), this method builds its
destination path directly from the tar entry name with **no canonical-path
containment check**, so a `../../../` entry name would write outside the
intended destination directory — the same vulnerability class already fixed
for ZIP. Separately, the symlink-handling branch calls
`Os.symlink(tarEntry.getName(), tarEntry.getLinkName())` with **neither
argument joined against the destination directory at all** (both file and
directory branches below it correctly build `destPath = new File(dest,
tarEntry.getName())`, but the symlink branch does not), and the argument
order itself looks swapped relative to `Os.symlink`'s expected
`(target, linkpath)` order (POSIX `symlink(target, linkpath)`: create a link
named `linkpath` whose contents point at `target`) — as written, the tar
entry's *own path* (which should become the new symlink's location) is passed
as the *target*, and the stored link value (which should be the target) is
passed as the *location*. Today the only source of tar data is the bundled
`universal.tar.xz` shipped inside the APK (`AsyncAssetManager.java:52-55`),
so this is not remotely exploitable without first tampering with the APK
itself — but it is the same bug class as the already-patched ZIP path, and
fixing it now is cheap and prevents it from becoming a real issue if this
method is ever reused for less-trusted tar input.

## Current state

- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/multirt/MultiRTUtils.java`
  — confirmed against the live file. `uncompressTarXZ` (lines 226-268):

  ```java
  226  private static void uncompressTarXZ(final InputStream tarFileInputStream, final File dest) throws IOException {
  227      if(dest.isFile()) throw new IOException("Attempting to unpack into a file");
  228      if(!dest.exists() && !dest.mkdirs()) throw new IOException("Failed to create destination directory");
  229
  230      byte[] buffer = new byte[8192];
  231      TarArchiveInputStream tarIn = new TarArchiveInputStream(
  232              new XZCompressorInputStream(tarFileInputStream)
  233      );
  234      TarArchiveEntry tarEntry = tarIn.getNextTarEntry();
  235      // tarIn is a TarArchiveInputStream
  236      while (tarEntry != null) {
  237
  238          final String tarEntryName = tarEntry.getName();
  239          // publishProgress(null, "Unpacking " + tarEntry.getName());
  240          ProgressLayout.setProgress(ProgressLayout.UNPACK_RUNTIME, 100, R.string.global_unpacking, tarEntryName);
  241
  242          File destPath = new File(dest, tarEntry.getName());
  243          File destParent = destPath.getParentFile();
  244          if (tarEntry.isSymbolicLink()) {
  245              if(destParent != null && !destParent.exists() && !destParent.mkdirs())
  246                  throw new IOException("Failed to create parent directory for symlink");
  247              try {
  248                  // android.system.Os
  249                  // Libcore one support all Android versions
  250                  Os.symlink(tarEntry.getName(), tarEntry.getLinkName());
  251              } catch (Throwable e) {
  252                  Log.e("MultiRT", e.toString());
  253              }
  254
  255          } else if (tarEntry.isDirectory()) {
  256              if(!destPath.exists() && !destPath.mkdirs())
  257                  throw new IOException("Failed to create directory");
  258          } else if (!destPath.exists() || destPath.length() != tarEntry.getSize()) {
  259              if(destParent != null && !destParent.exists() && !destParent.mkdirs())
  260                  throw new IOException("Failed to create parent directory for file");
  261
  262              FileOutputStream os = new FileOutputStream(destPath);
  263              IOUtils.copyLarge(tarIn, os, buffer);
  264              os.close();
  265
  266          }
  267          tarEntry = tarIn.getNextTarEntry();
  268      }
  269      tarIn.close();
  270  }
  ```

  Key facts, precisely:
  - `File destPath = new File(dest, tarEntry.getName())` (line 242) is used
    for the directory and regular-file branches (lines 255-264) — **no
    canonical-path check** against `dest` exists anywhere in this method,
    unlike `Tools.ZipTool.unzip` (`Tools.java:596-600`) which has
    `file.getCanonicalPath().startsWith(canonicalTarget)`. A tar entry named
    e.g. `../../../some/other/path` would resolve `destPath` outside `dest`
    and get written to directly (the file branch) or have directories created
    outside `dest` (the directory branch).
  - The symlink branch (lines 244-253) does **not** use `destPath` at all —
    it calls `Os.symlink(tarEntry.getName(), tarEntry.getLinkName())` with the
    **raw, unqualified tar entry strings**, neither one joined against `dest`.
    This means: (a) there is no containment check on either the symlink's
    location or its target, and (b) the created symlink's location is
    whatever `tarEntry.getName()` resolves to relative to the process's
    current working directory — not necessarily inside `dest` at all, since
    it's passed as a bare string rather than as `destPath.getAbsolutePath()`.
  - Argument order: `Os.symlink` (from `android.system.Os`, backed by POSIX
    `symlink(2)`) creates a new link *at* the second argument (`linkpath`)
    whose contents point *at* the first argument (`target`) — i.e. the
    expected call shape is `Os.symlink(target, linkpath)`. Here,
    `tarEntry.getName()` — the tar entry's own path, which is where the new
    symlink file should be *located* — is passed as the first argument
    (`target`), and `tarEntry.getLinkName()` — the value the tar format
    stores as what the link should *point at* — is passed as the second
    argument (`linkpath`, i.e. where the link file gets created). This reads
    as swapped relative to what the surrounding code is trying to do (create
    a symlink *at* the entry's location *pointing to* the stored link target).
    Confidence on this reading is MEDIUM, not certain — verify against
    `android.system.Os`'s actual documented parameter order (bundled Android
    SDK docs in the build image, or `https://developer.android.com/reference/android/system/Os#symlink(java.lang.String,%20java.lang.String)`
    if the executor environment has any doc access) before changing the call,
    per the STOP condition below. There is no other `Os.symlink` call
    elsewhere in this repo to cross-check against.

- Reachability, confirmed: the only caller chain into `uncompressTarXZ` is
  `installRuntimeNamedNoRemove`/`installRuntimeNamedBinpack`-style methods
  fed by `AsyncAssetManager.unpackRuntime`
  (`app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/tasks/AsyncAssetManager.java:52-55`):
  ```java
  52  MultiRTUtils.installRuntimeNamedBinpack(
  53          am.open("components/jre/universal.tar.xz"),
  54          am.open("components/jre/bin-" + archAsString(Tools.DEVICE_ARCHITECTURE) + ".tar.xz"),
  55          "Internal", finalRt_version);
  ```
  Both tar sources are opened straight from bundled APK assets
  (`ctx.getAssets()` / `AssetManager.open`) — there is currently no remote or
  user-supplied tar input reaching this code, unlike the ZIP path (plan
  001/015) which is reachable from a user-picked file. This is why Risk is
  LOW/latent rather than the MED/HIGH of the ZIP-side findings.

- Convention: `Tools.ZipTool.unzip`'s existing containment guard
  (`Tools.java:596-600`) is the pattern to mirror:
  ```java
  597  String canonicalTarget = targetDirectory.getCanonicalPath() + File.separator;
  598  if (!file.getCanonicalPath().startsWith(canonicalTarget)) {
  599      throw new IOException("Blocked zip-slip entry outside target directory: " + ze.getName());
  600  }
  ```

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Build APK | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` | `BUILD SUCCESSFUL` |
| Unit tests | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug :app_pojavlauncher:testDebugUnitTest"` | all tests pass |

## Scope

**In scope:**
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/multirt/MultiRTUtils.java` — the `uncompressTarXZ` tar-extraction loop only.
- New test file (attempt): `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/multirt/MultiRTUtilsTarTest.java`, only if `uncompressTarXZ` (or an extracted equivalent) can be made callable from a plain JUnit test.

**Out of scope (do NOT touch):**
- Everything else in `MultiRTUtils.java` — `getRuntimes`, `installRuntimeNamedBinpack`, unpack200 handling, `postPrepare`, the `sCache` map, etc. This is a vestigial-but-load-bearing file (per `/runescape/2009Scape-mobile/CLAUDE.md`'s convention list, `multirt/` is generally off-limits except for this one targeted guard) — do not refactor, rename, or reformat anything outside the extraction loop.
- `Tools.ZipTool.unzip` and its existing zip-slip guard — reference only, do not modify.
- `AsyncAssetManager.java` — no change needed; it calls the now-safer `uncompressTarXZ` (indirectly) unmodified.

## Git workflow

- Branch: `advisor/026-multirtutils-tarslip-symlink-containment`
- One commit for the containment guard, a separate commit if the symlink argument-order fix is made (so it can be reverted independently if the order turns out to already be correct); message style: conventional commits, e.g. `fix: reject tar-slip entries in MultiRTUtils tar extraction`.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Add canonical-path containment for file and directory entries

In `uncompressTarXZ`, immediately after `File destPath = new File(dest,
tarEntry.getName());` (`MultiRTUtils.java:242`) and before any of the
`isSymbolicLink()`/`isDirectory()`/file branches use it, add a guard mirroring
`Tools.ZipTool.unzip`'s:

```java
File destPath = new File(dest, tarEntry.getName());
String canonicalDest = dest.getCanonicalPath() + File.separator;
if (!destPath.getCanonicalPath().startsWith(canonicalDest)) {
    throw new IOException("Blocked tar-slip entry outside destination directory: " + tarEntry.getName());
}
```

This covers the directory branch (lines 255-257) and the regular-file branch
(lines 258-264) since both already use `destPath`.

**Verify**: `grep -n "Blocked tar-slip entry" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/multirt/MultiRTUtils.java` → one match, positioned before the `isSymbolicLink()`/`isDirectory()` branches.

### Step 2: Contain the symlink branch and use `destPath` consistently

The symlink branch (lines 244-253) currently ignores `destPath` entirely. Fix
it to build the symlink's location the same way the other branches do, and
apply the same containment check to that location:

```java
if (tarEntry.isSymbolicLink()) {
    if(destParent != null && !destParent.exists() && !destParent.mkdirs())
        throw new IOException("Failed to create parent directory for symlink");
    try {
        // android.system.Os.symlink(target, linkpath): create linkpath as a
        // symlink whose contents point at target. destPath (already
        // containment-checked above) is where the link file goes;
        // tarEntry.getLinkName() is the stored target string.
        Os.symlink(tarEntry.getLinkName(), destPath.getAbsolutePath());
    } catch (Throwable e) {
        Log.e("MultiRT", e.toString());
    }
}
```

Only make this change if you have confirmed the `(target, linkpath)`
parameter order against actual `android.system.Os` documentation or a
reliable reference in the executor's environment — see the STOP condition
below if you cannot confirm it. Since `destPath` is now used here too, it
automatically inherits the containment check added in Step 1 (both branches
compute `destPath` from the same line, `MultiRTUtils.java:242`, before either
branch runs).

Note: this fixes *where the new symlink file is created* and *the
containment of that location*; it deliberately does not attempt to validate
or contain the symlink's **target** value (`tarEntry.getLinkName()`) itself —
a symlink is allowed to point outside `dest` semantically (e.g. to a shared
system library), and validating arbitrary target strings is a different,
broader problem than the file/directory containment this plan addresses. If
you believe the target also needs containment, note it as a follow-up rather
than expanding this plan.

**Verify**: `grep -n "Os.symlink" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/multirt/MultiRTUtils.java` → one match, using `destPath` (or `destPath.getAbsolutePath()`), not the raw `tarEntry.getName()`.

### Step 3: Build and manually reason about behavior change

Since this is a latent/bundled-input-only path, there's no untrusted tar to
test against on-device short of crafting one. Build and, if feasible, add the
JUnit test in Step 4; otherwise verify by:
- `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` → `BUILD SUCCESSFUL`.
- Device: install the build, let it perform a fresh first-run unpack (clear
  app data first), confirm the JRE actually installs and the app can still
  launch a game session (Play HD or Play SD reaches the client) — this
  exercises the *legitimate* tar (bundled `universal.tar.xz`, which contains
  real files/directories and, plausibly, real symlinks in a JRE layout) through
  the newly-changed code path and confirms the fix didn't break normal
  extraction.

**Verify**: app installs, first-run unpack completes without a new
"Blocked tar-slip entry" exception in logcat, and a game launch reaches the
client (device smoke, per
`docs/verification/device-smoke-checklist.md`).

### Step 4 (attempt, not required): JUnit test with a crafted tar

Attempt a JUnit4 test (modeled on
`app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/customcontrols/CameraPanTest.java`'s
plain-JUnit4, no-Robolectric shape) that builds a small `.tar.xz` in-memory or
in a temp dir (using `org.apache.commons.compress`, already a project
dependency per the imports in `MultiRTUtils.java:15-17`) containing one normal
entry and one `../`-traversal entry, then calls `uncompressTarXZ` — but note
`uncompressTarXZ` is `private`; either:
- add a package-private (not public) test-seam overload if that's a small,
  clean change, or
- call the public `installRuntimeNamedNoRemove`-style entry point that
  eventually reaches it, if that's callable standalone with a fake
  `InputStream` and a temp `dest` dir without needing a real `Context`.

If neither is a clean, small change, do NOT force it — skip the automated
test, rely on Step 3's build + reasoning + device smoke, and say so explicitly
in the plan's completion notes.

**Verify** (if implemented): `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:testDebugUnitTest"` → new test passes, confirming the traversal entry is rejected.

## Test plan

- Preferred: `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/multirt/MultiRTUtilsTarTest.java` per Step 4, if a clean test seam exists.
- Required regardless: Step 3's build + device smoke (fresh-install unpack succeeds, game launch reaches the client) to confirm no regression to legitimate JRE extraction.
- If Step 4's automated test isn't feasible, explicitly record in the commit/PR description that verification was build + reasoning + device smoke only, and why (e.g. "`uncompressTarXZ` is private and not cleanly callable without a real `Context`/asset pipeline").

## Done criteria

Machine-checkable. ALL must hold:

- [ ] Docker build command exits with `BUILD SUCCESSFUL`
- [ ] `grep -n "Blocked tar-slip entry" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/multirt/MultiRTUtils.java` returns one match, positioned before the symlink/directory/file branches use `destPath`
- [ ] `grep -n "Os.symlink" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/multirt/MultiRTUtils.java` shows the call using a `destPath`-derived location, not a raw `tarEntry.getName()` string — **only if** the argument-order fix in Step 2 was made; if the order was left unchanged because it couldn't be confirmed, this line instead confirms the call was NOT changed and the ambiguity was reported per the STOP condition
- [ ] Device: fresh-install unpack + a successful game launch (HD or SD) after the change, per Step 3
- [ ] No files outside the in-scope list are modified (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:

- The code at `MultiRTUtils.java:226-268` doesn't match the excerpt above (drift) — re-read the live file before proceeding.
- You cannot confirm `android.system.Os.symlink`'s parameter order
  (`(target, linkpath)` vs. `(linkpath, target)`) from a reliable source in
  your environment — leave the call's argument order **unchanged**, still
  apply the Step 1 containment guard (which is independent of the argument
  order question), and report the ambiguity rather than guessing. Do not ship
  a "fix" to the argument order you couldn't verify.
- A verification step fails twice after a reasonable fix attempt.
- The fix appears to require touching any part of `MultiRTUtils.java` outside
  `uncompressTarXZ`, or any other vestigial-tree file listed as out-of-bounds
  in `/runescape/2009Scape-mobile/CLAUDE.md` (`jni/`, `jniLibs/`,
  `org/lwjgl/**`, `AWTInputEvent.java`, `jre_lwjgl3glfw/`, `gl4es/`).
- The device smoke test (Step 3) shows the legitimate bundled JRE tar now
  fails to extract (e.g. a real symlink entry in `universal.tar.xz` is
  rejected by the new containment check, or the symlink fix breaks a
  previously-working — even if accidentally — extraction) — revert the
  specific change that caused it and report which entry/behavior broke,
  rather than loosening the containment check to compensate.

## Maintenance notes

- This closes the same vulnerability class as plan 001 (Zip Slip) for the tar
  path, but the input here is still bundled-APK-only, so treat this as
  defense-in-depth rather than an active exploit fix — do not represent it as
  more urgent than its LOW/latent risk rating.
- If this method is ever reused for a non-bundled (remote/user-supplied) tar
  source in the future, the containment guard added here becomes load-bearing
  rather than defense-in-depth — a reviewer picking this up later should
  re-check that assumption before relaxing anything here.
- The symlink target value (`tarEntry.getLinkName()`) itself is still
  unvalidated after this plan (see Step 2's note) — a symlink could still be
  created pointing anywhere on the filesystem the app's UID can reach, even
  though its own location is now contained. If a future audit decides target
  validation matters, that's a separate, explicit follow-up, not an implicit
  extension of this plan.
