package net.kdt.pojavlaunch;

/**
 * Resolves the server IP/port the RT4-Client should dial, from up to five
 * candidate fields (raw strings, since callers source them either from
 * SharedPreferences strings or from parsed config.json values).
 *
 * IP precedence: ipAddress wins over ipManagement; blank/null on both falls
 * back to DEFAULT_IP.
 *
 * Port precedence: wlPort > js5Port > serverPort — the first candidate that
 * parses to an integer in [1, 65535] wins; a candidate that is null, blank,
 * non-numeric, or out of range is skipped in favor of the next; if none
 * resolve, falls back to DEFAULT_PORT.
 *
 * IMPORTANT: this server multiplexes login, JS5, and world-list on a SINGLE
 * port (43594 + worldId = 43595 for world 1). Callers that WRITE a config.json
 * (rather than just reading one for display) must write the resolved port to
 * all three of server_port/wl_port/js5_port — see Tools.patchConfigJson. A
 * prior bug pointed server_port at portInt-1 (a dead port) and caused
 * error_game_js5connect; do not reintroduce an offset between the three ports.
 */
public class ServerConfig {
    public static final String DEFAULT_IP = "127.0.0.1";
    public static final int DEFAULT_PORT = 43595;

    public static final class Result {
        public final String ip;
        public final int port;
        /**
         * True when none of the supplied port candidates resolved to a valid
         * [1, 65535] integer and {@link #port} therefore fell back to
         * {@link #DEFAULT_PORT}. Callers that source the port from a single
         * user-editable preference (e.g. Tools.patchConfigJson) should log a
         * warning when this is true so the fallback is not silent.
         */
        public final boolean portFellBackToDefault;
        public Result(String ip, int port, boolean portFellBackToDefault) {
            this.ip = ip;
            this.port = port;
            this.portFellBackToDefault = portFellBackToDefault;
        }
    }

    public static Result normalize(String ipAddress, String ipManagement,
                                    String serverPort, String wlPort, String js5Port) {
        String ip = firstNonBlank(ipAddress, ipManagement, DEFAULT_IP);
        Integer port = firstValidPort(wlPort, js5Port, serverPort);
        boolean fellBack = port == null;
        return new Result(ip, fellBack ? DEFAULT_PORT : port, fellBack);
    }

    /**
     * Resolves an ip field using the same semantics as
     * {@code JSONObject#optString(primaryKey, JSONObject#optString(secondaryKey, ""))}:
     * fall back from the primary candidate to the secondary ONLY when the
     * primary is entirely ABSENT (represented here as {@code null}), not
     * merely blank/empty when present. Pass {@code null} for a candidate
     * whose source key is absent, or the (possibly empty) string value when
     * the key is present.
     */
    public static String resolvePresentIp(String ipAddressIfPresent, String ipManagementIfPresent) {
        if (ipAddressIfPresent != null) return ipAddressIfPresent;
        if (ipManagementIfPresent != null) return ipManagementIfPresent;
        return "";
    }

    private static String firstNonBlank(String a, String b, String fallback) {
        if (a != null && !a.trim().isEmpty()) return a.trim();
        if (b != null && !b.trim().isEmpty()) return b.trim();
        return fallback;
    }

    private static Integer firstValidPort(String... candidates) {
        for (String c : candidates) {
            if (c == null || c.trim().isEmpty()) continue;
            try {
                int p = Integer.parseInt(c.trim());
                if (p >= 1 && p <= 65535) return p;
            } catch (NumberFormatException ignored) {
                // fall through to the next candidate
            }
        }
        return null;
    }
}
