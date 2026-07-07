# Plan 003: Stop `compareSHA1` from reporting a match on read errors

> **Executor instructions**: Follow step by step; verify each step; obey STOP conditions; update `plans/README.md` when done.
>
> **Drift check (run first)**: `git diff --stat 0e75ab512..HEAD -- app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java` — on change, compare to "Current state"; mismatch = STOP.

## Status
- **Priority**: P1
- **Effort**: S
- **Risk**: MED
- **Depends on**: none
- **Category**: security / bug (integrity)
- **Planned at**: commit `0e75ab512`, 2026-07-07

## Why this matters
`Tools.compareSHA1` is the integrity gate for launch artifacts (used with the `checkLibraries` preference, `LauncherPreferences.PREF_CHECK_LIBRARY_SHA`). It currently returns `true` (i.e. "hash matches, artifact is good") from its `catch (IOException)` block — so a truncated, corrupt, or unreadable file is reported as *verified*. A broken artifact then reaches the JVM launch classpath and fails later with an opaque error instead of being rejected/re-fetched. This defeats the only integrity check in the download path. The fix is to fail closed on read errors.

## Current state
`app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java:565-580`:
```java
public static boolean compareSHA1(File f, String sourceSHA) {
    try {
        String sha1_dst;
        try (InputStream is = new FileInputStream(f)) {
            sha1_dst = new String(Hex.encodeHex(org.apache.commons.codec.digest.DigestUtils.sha1(is)));
        }
        if(sourceSHA != null) {
            return sha1_dst.equalsIgnoreCase(sourceSHA);
        } else{
            return true; // fake match
        }
    }catch (IOException e) {
        Log.i("SHA1","Fake-matching a hash due to a read error",e);
        return true;
    }
}
```
Two "fake match" paths: (a) `sourceSHA == null` → `true` (some callers intentionally pass `null` for best-effort — leaving this is acceptable but must be explicit), and (b) `catch (IOException)` → `true` (the dangerous one: a file that can't be read is called verified).

## Commands you will need
| Purpose | Command | Expected |
|---|---|---|
| Find callers | `grep -rn "compareSHA1" app_pojavlauncher/src/main/java` | list of call sites |
| Build (local) | `./gradlew :app_pojavlauncher:assembleDebug` | `BUILD SUCCESSFUL` |
| Build (CI fallback) | push branch | Android CI green |

## Scope
**In scope:** `Tools.java` — the `compareSHA1` method only.
**Out of scope:** callers (do not change how `null` is passed); the download methods (Plan 002); adding a new hashing algorithm.

## Git workflow
- Branch: `advisor/003-comparesha1-fake-match`
- One commit, e.g. `fix(security): fail compareSHA1 closed on read error`.

## Steps

### Step 1: Survey callers (understand the `null` contract)
Run `grep -rn "compareSHA1" app_pojavlauncher/src/main/java`. Note which call sites pass a literal `null` vs a real expected hash. You are NOT changing callers — this is to confirm the `null`-means-skip contract is intentional (it is, per the comment) so you leave path (a) alone.

**Verify**: you have the list; no code changed yet.

### Step 2: Fail closed on read error
Change only the `catch` block to return `false`, and document the `null` behavior. Result:
```java
public static boolean compareSHA1(File f, String sourceSHA) {
    try {
        String sha1_dst;
        try (InputStream is = new FileInputStream(f)) {
            sha1_dst = new String(Hex.encodeHex(org.apache.commons.codec.digest.DigestUtils.sha1(is)));
        }
        // A null expected hash means the caller opted out of verification (best-effort).
        if (sourceSHA == null) return true;
        return sha1_dst.equalsIgnoreCase(sourceSHA);
    } catch (IOException e) {
        // Fail closed: an unreadable/corrupt file is NOT verified.
        Log.w("SHA1", "Hash check failed to read file; treating as mismatch", e);
        return false;
    }
}
```

**Verify**: `./gradlew :app_pojavlauncher:assembleDebug` → `BUILD SUCCESSFUL` (or CI green).

### Step 3: Confirm the dangerous path is gone
**Verify**: `grep -n "Fake-matching a hash due to a read error" app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java` → **no matches**.

## Test plan
No harness (Plan 006). Manual on-device: launch the game normally (the common path where library hashes match) and confirm it still boots — i.e. the tightened check does not produce false mismatches on good artifacts. If a re-download/repair flow is triggered by a mismatch, confirm it behaves (see STOP condition).

## Done criteria
- [ ] `catch (IOException)` returns `false`; the "fake match" comment/return in the catch is removed
- [ ] `./gradlew :app_pojavlauncher:assembleDebug` exits 0 (or CI green)
- [ ] `git status` shows only `Tools.java` modified
- [ ] `plans/README.md` row updated

## STOP conditions
- A caller depends on `compareSHA1` returning `true` for an unreadable file to *proceed* with launch (i.e. read-failure was load-bearing). If Step 1 reveals such a caller, STOP and report — the fix may need a caller-side "unverified, proceed anyway" decision instead.
- Launching the game now fails at the integrity check on a genuinely-good artifact (would indicate an unrelated read problem) — STOP and report.

## Maintenance notes
- Reviewer: verify the `null` (opt-out) path is intentional and documented; confirm no caller was silently relying on the old catch behavior.
- Follow-up (deferred): callers currently pass `null` in places — a future plan could thread real expected hashes through the download path so integrity is actually enforced end-to-end. Out of scope here.
