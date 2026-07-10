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
        public Result(String ip, int port) {
            this.ip = ip;
            this.port = port;
        }
    }

    public static Result normalize(String ipAddress, String ipManagement,
                                    String serverPort, String wlPort, String js5Port) {
        String ip = firstNonBlank(ipAddress, ipManagement, DEFAULT_IP);
        Integer port = firstValidPort(wlPort, js5Port, serverPort);
        return new Result(ip, port != null ? port : DEFAULT_PORT);
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
