# Plan 020: Add unit tests for zero-coverage pure/near-pure helper functions

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 8ee361ea1..HEAD -- app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils/JSONUtils.java app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java`
> If either in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: tests
- **Planned at**: commit `8ee361ea1`, 2026-07-10

## Why this matters

Several small, pure or near-pure functions have zero test coverage despite being easy to test in isolation: `JSONUtils.insertSingleJSONValue` (the `${key}` substitution used to build launch args), `Tools.isValidString`, and `Tools.compareSHA1` (already hardened against a "fake match on read error" bug by Plan 003, but with no regression test locking that fix in). These are zero-risk coverage wins: they grow the JUnit suite this repo is trying to establish (see Plan 006/009), and for `compareSHA1` specifically, a test pins an already-fixed security-relevant bug so nobody accidentally reverts it later.

## Current state

- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils/JSONUtils.java` (full file, 20 lines):
```java
package net.kdt.pojavlaunch.utils;

import java.util.*;

public class JSONUtils {
    public static String[] insertJSONValueList(String[] args, Map<String, String> keyValueMap) {
        for (int i = 0; i < args.length; i++) {
            args[i] = insertSingleJSONValue(args[i], keyValueMap);
        }
        return args;
    }

    public static String insertSingleJSONValue(String value, Map<String, String> keyValueMap) {
        String valueInserted = value;
        for (Map.Entry<String, String> keyValue : keyValueMap.entrySet()) {
            valueInserted = valueInserted.replace("${" + keyValue.getKey() + "}", keyValue.getValue() == null ? "" : keyValue.getValue());
        }
        return valueInserted;
    }
}
```
Both methods are pure (no Android imports, no static engine state) and package-public already — no visibility change needed.

- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java:783-785` — `isValidString`:
```java
public static boolean isValidString(String string) {
    return string != null && !string.isEmpty();
}
```
Pure, public, static. Note it treats a whitespace-only string (e.g. `" "`) as VALID (only checks `isEmpty()`, not blank) — this is current, intentional-looking behavior worth locking in with a test rather than "fixing" (out of scope to change).

- `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java:622-636` — `compareSHA1` (this is the POST-Plan-003 version, already fixed):
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
`sourceSHA == null` returning `true` (by-design opt-out, per the comment) and the `IOException` path returning `false` (fail-closed, the Plan 003 fix) are both worth a regression test — this is exactly the kind of fix a future edit could accidentally revert without a test catching it.

Both `Tools.java` methods used here rely only on `java.io.*` and `org.apache.commons.codec.*` (already an `implementation` dependency — see `app_pojavlauncher/build.gradle:192` `implementation libs.commons.codec` — available transitively on the unit-test classpath) and `android.util.Log` (only in the `catch` block of `compareSHA1`; `Log.w` is a stubbed Android call in JVM unit tests but by default returns `0`/does nothing rather than throwing, since it is one of the always-mocked-safe android.jar calls used throughout Android unit tests — if it throws in this repo's specific test setup, see STOP conditions).

- Structural pattern to mirror: `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/customcontrols/CameraPanTest.java` (JUnit 4, `org.junit.Test` + `assertEquals`/`assertTrue`/`assertFalse`, one `@Test` method per case, no Android framework deps beyond what's proven safe above).

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Build APK | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug"` | `BUILD SUCCESSFUL` |
| Unit tests | `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug :app_pojavlauncher:testDebugUnitTest"` | `BUILD SUCCESSFUL`; report under `app_pojavlauncher/build/test-results/testDebugUnitTest/` |

## Scope

**In scope (new files only):**
- NEW `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/utils/JSONUtilsTest.java`
- NEW `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/ToolsPureTest.java`

**Out of scope:**
- No changes to `JSONUtils.java` or `Tools.java` source — this is a pure test-adding plan. If a target method turns out to be inaccessible from the test source set (it is not, per "Current state," but confirm), the ONLY acceptable source change is a minimal, explicitly-called-out visibility widening (e.g. `private` → package-private) — do not do this silently.
- Any other method in `Tools.java` besides `isValidString` and `compareSHA1`.
- `compareSHA1`'s callers or the download/library-check flow around it (that's Plan 002/003's territory).

## Git workflow

- Branch: `advisor/020-trivial-pure-function-unit-tests`
- Commit per step; conventional-commit style (e.g. `test: cover JSONUtils.insertSingleJSONValue, Tools.isValidString, Tools.compareSHA1`).
- Do NOT push or open a PR unless instructed.

## Steps

### Step 1: `JSONUtilsTest`

Create `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/utils/JSONUtilsTest.java` covering `insertSingleJSONValue`:
- single key substitution: `insertSingleJSONValue("hello ${name}", Map.of("name", "world"))` → `"hello world"`
- multiple keys substituted in one string
- a key present in the input string's `${...}` syntax but NOT present in the map → left untouched (literal `${missingKey}` remains in the output)
- a map entry whose value is `null` → substituted with an empty string (per the `keyValue.getValue() == null ? "" : keyValue.getValue()` branch)
- no `${...}` placeholders present → input returned unchanged
- (optional, low-effort extra) one test for `insertJSONValueList` confirming it applies the substitution across every element of a `String[]`

**Verify**: `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:testDebugUnitTest --tests net.kdt.pojavlaunch.utils.JSONUtilsTest"` → all pass.

### Step 2: `ToolsPureTest` — `isValidString`

In `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/ToolsPureTest.java`, add cases for `Tools.isValidString`:
- `null` → `false`
- `""` → `false`
- a non-empty string (e.g. `"x"`) → `true`
- a whitespace-only string (e.g. `" "`) → `true` (this locks in the CURRENT — not necessarily "ideal" — behavior; do not change the source to make this `false`)

**Verify**: build compiles; run the class filter below in Step 4.

### Step 3: `ToolsPureTest` — `compareSHA1`

Add cases for `Tools.compareSHA1` using `org.junit.rules.TemporaryFolder` (or `File.createTempFile`) to avoid touching real app data:
- matching hash → `true`: write known bytes to a temp file, compute the expected hash via `org.apache.commons.codec.digest.DigestUtils.sha1Hex(bytes)` (same library the source uses) so the test doesn't hand-compute a hash, then assert `Tools.compareSHA1(file, expectedHash)` is `true`.
- mismatched hash → `false`: same temp file, pass an obviously-wrong hash string.
- `sourceSHA == null` → `true` regardless of file contents (locks in the documented opt-out behavior at `Tools.java:629`).
- unreadable file → `false` (locks in the Plan 003 fail-closed fix): pass a `File` that cannot be opened for reading, e.g. a path inside a `TemporaryFolder`-created directory (a directory, not a file) so `new FileInputStream(f)` throws `FileNotFoundException` (an `IOException` subtype), and assert the result is `false`.

**Verify**: build compiles; run the class filter below in Step 4.

### Step 4: Full build + test gate

**Verify**: `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug :app_pojavlauncher:testDebugUnitTest"` → `BUILD SUCCESSFUL`, all tests (old + new) pass. Report the total new test count (expect roughly 5-6 in `JSONUtilsTest` + roughly 7-8 in `ToolsPureTest`).

## Test plan

- `JSONUtilsTest`: cases listed in Step 1.
- `ToolsPureTest`: `isValidString` cases (Step 2) + `compareSHA1` cases (Step 3), in one file (both are `Tools` statics).
- Structural pattern: `customcontrols/CameraPanTest.java`.
- Verification: `./gradlew :app_pojavlauncher:testDebugUnitTest` (via Docker) → all pass, including all new cases.

## Done criteria

Machine-checkable. ALL must hold:

- [ ] `docker run --rm -v /runescape/2009Scape-mobile:/project 2009scape-apk-builder bash -lc "cd /project && ./gradlew :app_pojavlauncher:assembleDebug :app_pojavlauncher:testDebugUnitTest"` exits 0 / `BUILD SUCCESSFUL`
- [ ] `JSONUtilsTest` and `ToolsPureTest` both exist under `app_pojavlauncher/src/test/java/net/kdt/pojavlaunch/` and all listed cases pass
- [ ] `JSONUtils.java` and `Tools.java` are UNCHANGED (`git diff --stat` shows no hits for either, unless a visibility-widening exception was explicitly justified and called out in the commit message)
- [ ] No files outside the in-scope list are modified (`git status`)
- [ ] `plans/README.md` status row updated with the new test count

## STOP conditions

Stop and report back (do not improvise) if:

- The code at `JSONUtils.java` or `Tools.java:622-636`/`Tools.java:783-785` doesn't match the excerpts in "Current state" (drift since this plan was written).
- A target method is inaccessible from the `src/test/java` source set and cannot be tested without a real refactor (not expected here, since both classes/methods are already `public static`) — skip that specific method, note why in the commit message and in your final report, and continue with the rest.
- `android.util.Log.w(...)` (invoked inside `compareSHA1`'s catch block) throws an exception in this repo's actual JVM unit-test run instead of silently no-op'ing — STOP and report; work around it only by using `TemporaryFolder`/valid mocking, not by editing `Tools.java`.
- A step's verification fails twice after a reasonable fix attempt.

## Maintenance notes

- Reviewer: confirm no source file changed (this plan is test-only) unless a visibility exception was explicitly called out and justified.
- Follow-up (deferred, out of scope here): `Tools.java`'s broader "god object" surface (noted in `plans/README.md`'s deferred list) has many more untested static helpers; this plan intentionally picks only the three smallest, safest wins. A future plan could continue the sweep.
