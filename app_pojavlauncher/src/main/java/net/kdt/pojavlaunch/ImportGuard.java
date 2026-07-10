package net.kdt.pojavlaunch;

/**
 * Pure helper for validating file-picker imports (config.json / plugin zip)
 * before they are written to disk. No Android dependencies so it can be unit
 * tested directly (see {@code ImportGuardTest}).
 *
 * <p>{@link #isValidJsonObject} is a deliberately dependency-free structural
 * check (brace/bracket balance, string-aware) rather than a call into
 * {@code org.json.JSONObject}: in this project's plain-JUnit unit-test
 * harness, Android's {@code org.json} classes are backed by an auto-mocked
 * jar whose constructors are no-ops that never throw regardless of input, so
 * a test built around them can't actually observe rejection of invalid JSON.
 * This check is intentionally lenient about full RFC 8259 conformance — its
 * job is only to reject obviously-garbage/truncated input before it
 * overwrites a working {@code config.json}, not to replace real parsing.
 */
public final class ImportGuard {

    /**
     * Maximum size, in bytes, accepted from a file-picker import
     * (config.json or plugin zip). Legitimate imports through this path are
     * far smaller; this just bounds worst-case memory/disk use from a huge or
     * malicious {@code content://} source.
     */
    public static final long MAX_IMPORT_BYTES = 5 * 1024 * 1024; // 5 MB

    private ImportGuard() {
    }

    /** Returns true if {@code byteCount} is strictly under {@link #MAX_IMPORT_BYTES}. */
    public static boolean isWithinSizeLimit(long byteCount) {
        return byteCount < MAX_IMPORT_BYTES;
    }

    /**
     * Returns true if {@code text} looks like a well-formed JSON object: it
     * must be brace-delimited and have balanced (string-aware) braces and
     * brackets. Rejects non-JSON text and truncated/garbage JSON.
     */
    public static boolean isValidJsonObject(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.length() < 2
                || trimmed.charAt(0) != '{'
                || trimmed.charAt(trimmed.length() - 1) != '}') {
            return false;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return !inString && depth == 0;
    }
}
