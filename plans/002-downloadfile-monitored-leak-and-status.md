# Plan 002: Close streams and check HTTP status in monitored downloads

> **Executor instructions**: Follow step by step; run every verification command and confirm its result before proceeding. Obey STOP conditions. Update this plan's row in `plans/README.md` when done.
>
> **Drift check (run first)**: `git diff --stat 0e75ab512..HEAD -- app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils/DownloadUtils.java` — on any change, compare "Current state" to live code; mismatch = STOP.

## Status
- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: bug (resource leak) / security (integrity + DoS)
- **Planned at**: commit `0e75ab512`, 2026-07-07

## Why this matters
`downloadFileMonitored` and `downloadFileMonitoredWithHeaders` open an `InputStream`, a `FileOutputStream`, and an `HttpURLConnection` with no `try/finally`. Any exception mid-transfer (a dropped connection — the common case) leaks the file descriptor and socket; on a long-running launcher process, repeated retries during first-run asset/runtime fetch exhaust FDs. Neither method checks `getResponseCode()`, so an HTTP error body (404/500 page) is written to the destination file and later treated as a valid artifact. The sibling `download()` method already does both things correctly — this plan brings the monitored variants up to the same standard.

## Current state
`app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils/DownloadUtils.java`. The correct reference pattern already in this file, `download(URL, OutputStream)` at `:21-47`, checks status and closes in `finally`. The two broken methods:

`:96-118`:
```java
public static void downloadFileMonitored(String urlInput,File outputFile, @Nullable byte[] buffer,
                                         Tools.DownloaderFeedback monitor) throws IOException {
    if (!outputFile.exists()) {
        outputFile.getParentFile().mkdirs();
    }
    HttpURLConnection conn = (HttpURLConnection) new URL(urlInput).openConnection();
    InputStream readStr = conn.getInputStream();
    FileOutputStream fos = new FileOutputStream(outputFile);
    int cur;
    int oval = 0;
    int len = conn.getContentLength();
    if(buffer == null) buffer = new byte[65535];
    while ((cur = readStr.read(buffer)) != -1) {
        oval += cur;
        fos.write(buffer, 0, cur);
        monitor.updateProgress(oval, len);
    }
    fos.close();
    conn.disconnect();
}
```
`:160-184` `downloadFileMonitoredWithHeaders` is identical except it sets `User-Agent` and `Cookies` request properties before `conn.getInputStream()`.

Imports already present: `java.io.*`, `java.net.*`. Java 8 source level — `try`-with-resources is available.

## Commands you will need
| Purpose | Command | Expected |
|---|---|---|
| Build (local) | `./gradlew :app_pojavlauncher:assembleDebug` | `BUILD SUCCESSFUL` (JDK 17, SDK 35, build-tools 35.0.0, NDK 25.2.9519653) |
| Build (CI fallback) | push branch | "Android CI" green, `app-debug` uploaded |

## Scope
**In scope:** `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils/DownloadUtils.java` — the two monitored methods only.
**Out of scope:** `download()` / `downloadString()` / `downloadFile()` (a separate finding covers `downloadFile`); callers of these methods; anything native.

## Git workflow
- Branch: `advisor/002-downloadfile-monitored-leak`
- One commit, e.g. `fix(download): close streams and check HTTP status in monitored downloads`.

## Steps

### Step 1: Harden `downloadFileMonitored`
Replace the body from the `HttpURLConnection conn = ...` line through `conn.disconnect();` with a status check and guaranteed cleanup:

```java
HttpURLConnection conn = (HttpURLConnection) new URL(urlInput).openConnection();
conn.setConnectTimeout(10000);
try {
    if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
        throw new IOException("Server returned HTTP " + conn.getResponseCode()
                + " for " + urlInput);
    }
    if (buffer == null) buffer = new byte[65535];
    int len = conn.getContentLength();
    try (InputStream readStr = conn.getInputStream();
         FileOutputStream fos = new FileOutputStream(outputFile)) {
        int cur;
        int oval = 0;
        while ((cur = readStr.read(buffer)) != -1) {
            oval += cur;
            fos.write(buffer, 0, cur);
            monitor.updateProgress(oval, len);
        }
    }
} finally {
    conn.disconnect();
}
```

**Verify**: build succeeds (see Commands).

### Step 2: Apply the same shape to `downloadFileMonitoredWithHeaders`
Keep the two `conn.setRequestProperty(...)` calls (`User-Agent`, `Cookies`) **before** the `getResponseCode()` call, then use the identical try/finally + try-with-resources structure as Step 1.

**Verify**: `./gradlew :app_pojavlauncher:assembleDebug` → `BUILD SUCCESSFUL` (or CI green).

### Step 3: Confirm no bare stream opens remain in these two methods
**Verify**: `grep -n "getInputStream\|new FileOutputStream" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils/DownloadUtils.java` → the occurrences inside the two monitored methods are within `try (...)` resource headers.

## Test plan
No harness (see Plan 006). Manual: on-device, exercise a flow that downloads (first-run runtime/asset fetch or a mod/runtime import) and confirm normal downloads still complete and show progress. If feasible, point one download at a URL that 404s and confirm it now throws rather than writing an error page to the destination file.

## Done criteria
- [ ] `./gradlew :app_pojavlauncher:assembleDebug` exits 0 (or CI green)
- [ ] Both monitored methods check `getResponseCode()` and use try-with-resources; `conn.disconnect()` is in a `finally`
- [ ] `git status` shows only `DownloadUtils.java` modified
- [ ] `plans/README.md` row updated

## STOP conditions
- The two methods don't match the "Current state" excerpts (drift).
- Build fails twice after a reasonable fix attempt.
- A caller relies on the old behavior of writing a body regardless of status (search callers of `downloadFileMonitored*`; if one deliberately downloads from a non-200 endpoint, STOP and report).

## Maintenance notes
- Reviewer: verify `monitor.updateProgress` is still called on the success path and that `getContentLength()` is read before the stream is consumed.
- Follow-up (deferred): there is no integrity check on downloaded artifacts and no TLS pinning; artifact integrity is Plan 003's `compareSHA1` fix plus a separate future plan. Note it in review but don't expand this plan.
