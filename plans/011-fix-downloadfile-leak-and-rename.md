# Plan 011: Fix stream leak and ignored `renameTo` result in `DownloadUtils.downloadFile`

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 8ee361ea1..HEAD -- app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils/DownloadUtils.java`
> If this file changed since this plan was written, compare the "Current
> state" excerpts against the live code before proceeding; on a mismatch,
> treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `8ee361ea1`, 2026-07-10

## Why this matters

`DownloadUtils.downloadFile` declares a `BufferedOutputStream bos = null;`
that is never assigned — the real output stream, `bos2`, is a *different*
local variable. Every catch block in the method checks and closes the dead
`bos` (always null, so `close()` is always skipped) but never closes `bos2`,
so on any download failure the underlying `FileOutputStream` on the temp file
leaks a file descriptor. Separately, the method calls
`tempOut.renameTo(out)` and ignores its boolean return value, then
unconditionally deletes `tempOut` on the "success" path — so if the rename
silently fails (common cross-filesystem, or when `out` already exists and
locked on some filesystems), the caller believes the download succeeded but
`out` was never created and the only copy (`tempOut`) is then deleted out
from under it. This is reached from `Tools.java` (`Tools.downloadFile`, which
forwards straight to `DownloadUtils.downloadFile`) — used for things like
runtime/config file downloads. Both bugs are fixed by rewriting the method to
use try-with-resources on the real stream and to check `renameTo`'s result.

## Current state

`app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils/DownloadUtils.java:56-90`
(`downloadFile`, confirmed by direct read — exact line numbers below):

```java
56  public static void downloadFile(String url, File out) throws IOException {
57      out.getParentFile().mkdirs();
58      File tempOut = File.createTempFile(out.getName(), ".part", out.getParentFile());
59      BufferedOutputStream bos = null;
60      try {
61          OutputStream bos2 = new BufferedOutputStream(new FileOutputStream(tempOut));
62          try {
63              download(url, bos2);
64              tempOut.renameTo(out);
65              if (bos2 != null) {
66                  bos2.close();
67              }
68              if (tempOut.exists()) {
69                  tempOut.delete();
70              }
71          } catch (IOException th2) {
72              if (bos != null) {
73                  bos.close();
74              }
75              if (tempOut.exists()) {
76                  tempOut.delete();
77              }
78              throw th2;
79          }
80      } catch (IOException th3) {
81
82          if (bos != null) {
83              bos.close();
84          }
85          if (tempOut.exists()) {
86              tempOut.delete();
87          }
88          throw th3;
89      }
90  }
```

Confirmed: `bos` (line 59) is declared and checked in both catch blocks
(lines 72, 82) but is **never assigned** anywhere in the method — it is
always `null`, so `bos.close()` never runs. The actual stream, `bos2`
(line 61), is only closed on the non-exceptional path (line 66) — on either
catch path it leaks. `tempOut.renameTo(out)`'s return value is discarded
(line 64); the temp file is deleted unconditionally afterward (lines 68-69)
regardless of whether the rename actually moved it.

Call site: `Tools.java` (`downloadFile(String urlInput, String nameOutput)`)
forwards directly to `DownloadUtils.downloadFile(urlInput, file)` — confirm
exact current line by `grep -n "downloadFile" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java`
before editing (this plan does not modify `Tools.java`, so the exact line
number there is not load-bearing, only the fact that it's an unguarded
pass-through).

For contrast, `downloadFileMonitored` (same file, lines 96-123) is already
correctly try-with-resources'd (hardened by Plan 002) — use it as the style
reference for resource handling, but do NOT modify it as part of this plan.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Build APK | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` | `BUILD SUCCESSFUL`; APK at `app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk` |
| Unit tests | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug :app_pojavlauncher:testDebugUnitTest"` | `BUILD SUCCESSFUL` |

(No host JDK/Android SDK exists in this environment — always run through the
`2009scape-apk-builder` Docker image, per repo `CLAUDE.md` / `MEMORY.md`.)

## Scope

**In scope** (the only file/method you should modify):
- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils/DownloadUtils.java` — the `downloadFile(String, File)` method only (lines 56-90).

**Out of scope** (do NOT touch, even though they look related):
- `downloadFileMonitored` (both overloads) and `downloadFileMonitoredWithHeaders`
  — already hardened by Plan 002; do not re-touch.
- `download(String, OutputStream)` / `download(URL, OutputStream)` — the
  underlying network primitive; not implicated in this bug.
- `downloadString`, `downloadStringCached` — unrelated methods in the same file.
- `Tools.java` and any other caller of `downloadFile` — the fix is entirely
  internal to `DownloadUtils.downloadFile`'s resource/rename handling; the
  method signature and thrown-exception contract (`IOException`) are unchanged.

## Git workflow

- Branch: `advisor/011-fix-downloadfile-leak-and-rename`
- One commit, conventional-commit style (e.g. matching
  `fix: stylus barrel-button release uses the pressed button (#31)`).
  Suggested message: `fix: close the real stream and check renameTo() in DownloadUtils.downloadFile`
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: Rewrite `downloadFile` to use try-with-resources on the real stream and check `renameTo`

Replace the method body (lines 56-90) with a version that:
1. Removes the dead `bos` variable entirely.
2. Opens the actual `BufferedOutputStream` (what is currently `bos2`) inside
   a try-with-resources so it is closed on every path, success or exception.
3. Checks the return value of `tempOut.renameTo(out)`; only deletes `tempOut`
   when the rename **failed** (as cleanup of the now-orphaned temp file), and
   throws an `IOException` when the rename fails so the caller doesn't
   silently believe it succeeded.
4. Preserves the existing "delete the temp file on any exception from
   `download()`" cleanup behavior.

Target shape (adjust only if it does not compile as written — keep the
intent: real stream closed via try-with-resources, `renameTo` checked,
`out`/`tempOut` end state consistent with what actually happened):

```java
public static void downloadFile(String url, File out) throws IOException {
    out.getParentFile().mkdirs();
    File tempOut = File.createTempFile(out.getName(), ".part", out.getParentFile());
    try {
        try (OutputStream bos = new BufferedOutputStream(new FileOutputStream(tempOut))) {
            download(url, bos);
        }
        if (!tempOut.renameTo(out)) {
            throw new IOException("Failed to rename " + tempOut + " to " + out);
        }
    } catch (IOException e) {
        if (tempOut.exists()) {
            tempOut.delete();
        }
        throw e;
    }
}
```

Note the semantic change from the original: `tempOut` is now deleted **only**
in the catch block (i.e. only when `download()` threw, or when `renameTo`
returned false and this method threw its own `IOException` which is caught by
the same block) — never on the successful-rename path, since after a
successful `renameTo` the file at `tempOut`'s old path no longer exists (it
*is* now `out`), so the old unconditional `tempOut.exists()` check after a
successful rename was already always false in practice; this rewrite just
makes that fall out of the control flow instead of an explicit no-op check.

**Verify**: `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` → `BUILD SUCCESSFUL`.

### Step 2: Confirm the dead variable and unchecked rename are gone

**Verify**:
- `grep -n "BufferedOutputStream bos = null" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils/DownloadUtils.java` → no match.
- `grep -n "tempOut.renameTo(out);" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils/DownloadUtils.java` → no match (the bare unchecked-statement form is gone; it should now appear only inside an `if (!tempOut.renameTo(out))` or equivalent checked form).

## Test plan

`downloadFile` does real network + filesystem I/O (`HttpURLConnection`,
`File.createTempFile`, `File.renameTo`) — this is not a clean, hermetic JUnit
target the way `CameraPanTest` is for pure logic, and this plan will not
fabricate a fake/mocked network test to force one. Verification is:

- The Docker `assembleDebug` build (compiles the change).
- A described manual/device check (run once, on-device or via `adb shell`
  against the emulator/device the app is installed on): trigger any flow that
  calls `Tools.downloadFile` / `DownloadUtils.downloadFile` (e.g. a
  runtime/config download path in the app), then confirm (a) the target file
  exists at the expected final path afterward, and (b) no lingering `.part`
  temp file remains in the same directory. Repeat once with a deliberately
  broken URL (or airplane mode mid-download) and confirm the method throws
  `IOException` and does *not* leave a `.part` file behind (i.e. it cleaned
  up on failure) and does not throw an unrelated `NullPointerException` (the
  old dead-`bos` path could mask an underlying leak without crashing, so
  "throws IOException cleanly" is the meaningful signal here, not "doesn't
  crash").

## Done criteria

Machine-checkable. ALL must hold:

- [ ] `grep -n "BufferedOutputStream bos = null" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils/DownloadUtils.java` returns no match
- [ ] `grep -n "tempOut.renameTo(out);" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils/DownloadUtils.java` returns no match (only a checked form remains)
- [ ] Docker `assembleDebug` exits with `BUILD SUCCESSFUL`
- [ ] `git status` shows only `DownloadUtils.java` modified
- [ ] `plans/README.md` status row for Plan 011 updated

## STOP conditions

Stop and report back (do not improvise) if:

- `downloadFile` (lines 56-90) no longer matches the "Current state" excerpt
  — in particular, if `bos` is already correctly wired to the real stream, or
  `renameTo`'s result is already checked, the method has already been
  refactored (drift); report instead of re-doing the work.
- The build fails twice after a reasonable fix attempt.
- The fix appears to require touching `Tools.java` or `downloadFileMonitored*`
  — it should not; if it does, the assumption that this bug is isolated to
  `downloadFile` is false, and that's worth reporting rather than expanding scope.

## Maintenance notes

- This mirrors the pattern already applied to `downloadFileMonitored` by
  Plan 002 (try-with-resources, checked I/O) — keep the style consistent with
  that method for future readers of this file.
- A reviewer should double-check the changed failure semantics: previously,
  a failed `renameTo` was silently ignored (caller believed success); after
  this fix, it surfaces as a thrown `IOException`. Any caller of
  `DownloadUtils.downloadFile` / `Tools.downloadFile` that assumed a "throws
  IOException" contract already existed (the signature is unchanged) is
  unaffected; a caller that somehow relied on the old silent-failure behavior
  (unlikely — none found in this pass) would need to add error handling.
- Not investigated as part of this plan: whether `out.getParentFile()`
  (line 57) can be null (e.g. if `out` is a bare filename with no parent) —
  that would NPE before reaching the code this plan touches; out of scope,
  flag as a possible follow-up if it surfaces.
