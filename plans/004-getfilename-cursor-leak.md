# Plan 004: Fix Cursor leak and empty-result crash in `Tools.getFileName`

> **Executor instructions**: Follow step by step; verify each step; obey STOP conditions; update `plans/README.md`.
>
> **Drift check (run first)**: `git diff --stat 0e75ab512..HEAD -- app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java` — on change, compare to "Current state"; mismatch = STOP.

## Status
- **Priority**: P2
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: bug (resource leak)
- **Planned at**: commit `0e75ab512`, 2026-07-07

## Why this matters
`Tools.getFileName` queries a content provider for a picked file's display name but leaks the `Cursor` on one return path and calls `moveToFirst()` without checking its result. On providers that omit `DISPLAY_NAME` (`columnIndex == -1`), the method returns without `close()` — a `CursorWindow`/handle leak; on an empty cursor, `getString` after an unchecked `moveToFirst()` can crash. This is hit on file-picking flows (mod install, runtime import, plugin/config import). A try-with-resources and a `moveToFirst()` guard fix both.

## Current state
`app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java:614-623`:
```java
public static String getFileName(Context ctx, Uri uri) {
    Cursor c = ctx.getContentResolver().query(uri, null, null, null, null);
    if(c == null) return uri.getLastPathSegment(); // idk myself but it happens on asus file manager
    c.moveToFirst();
    int columnIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
    if(columnIndex == -1) return uri.getLastPathSegment();   // <-- leaks c
    String fileName = c.getString(columnIndex);
    c.close();
    return fileName;
}
```
`android.database.Cursor`, `android.net.Uri`, `android.provider.OpenableColumns`, `android.content.Context` are already imported in `Tools.java`. Java 8 source level.

## Commands you will need
| Purpose | Command | Expected |
|---|---|---|
| Build (local) | `./gradlew :app_pojavlauncher:assembleDebug` | `BUILD SUCCESSFUL` |
| Build (CI fallback) | push branch | Android CI green |

## Scope
**In scope:** `Tools.java` — the `getFileName` method only.
**Out of scope:** callers; other cursor usages elsewhere.

## Git workflow
- Branch: `advisor/004-getfilename-cursor-leak`
- One commit, e.g. `fix: close Cursor and guard moveToFirst in getFileName`.

## Steps

### Step 1: Wrap the Cursor in try-with-resources and guard `moveToFirst`
Replace the method body with:
```java
public static String getFileName(Context ctx, Uri uri) {
    try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
        if (c == null || !c.moveToFirst()) return uri.getLastPathSegment();
        int columnIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        if (columnIndex == -1) return uri.getLastPathSegment();
        return c.getString(columnIndex);
    }
}
```
`Cursor` implements `Closeable`, so try-with-resources closes it on every path (including the two early returns). The `!c.moveToFirst()` guard handles empty cursors.

**Verify**: `./gradlew :app_pojavlauncher:assembleDebug` → `BUILD SUCCESSFUL` (or CI green).

### Step 2: Confirm no manual `c.close()` remains and every path is covered
**Verify**: `grep -n "getFileName" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java` locates the method; visually confirm the `Cursor` is only referenced inside the `try (...)` header and body.

## Test plan
No harness (Plan 006). Manual on-device: pick a file via any import flow (Settings → Advanced → Load config/plugin) and confirm the display name still resolves and the app doesn't crash. Repeat picking several files to confirm no cursor-exhaustion warnings in logcat.

## Done criteria
- [ ] `getFileName` uses `try (Cursor c = ...)` and guards `moveToFirst()`
- [ ] `./gradlew :app_pojavlauncher:assembleDebug` exits 0 (or CI green)
- [ ] `git status` shows only `Tools.java` modified
- [ ] `plans/README.md` row updated

## STOP conditions
- The method doesn't match the "Current state" excerpt (drift).
- Build fails twice after a reasonable fix attempt.

## Maintenance notes
- Reviewer: confirm `Cursor` is `AutoCloseable` on `minSdk 21` (it is — `Cursor extends Closeable` since API 16) so try-with-resources is valid.
- Low blast radius; safe to batch with other small `Tools.java` fixes if desired, but keep commits separate for clean review.
