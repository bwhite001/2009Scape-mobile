package net.kdt.pojavlaunch;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ServerConfigTest {

    @Test public void missingPortFallsBackToDefault() {
        ServerConfig.Result r = ServerConfig.normalize("10.0.0.5", null, null, null, null);
        assertEquals(ServerConfig.DEFAULT_PORT, r.port);
    }

    @Test public void invalidPortOnAllThreeFallsBackToDefault() {
        ServerConfig.Result r = ServerConfig.normalize("10.0.0.5", null, "abc", "abc", "abc");
        assertEquals(ServerConfig.DEFAULT_PORT, r.port);
    }

    @Test public void onlyServerPortSetResolvesToServerPort() {
        ServerConfig.Result r = ServerConfig.normalize("10.0.0.5", null, "9999", null, null);
        assertEquals(9999, r.port);
    }

    @Test public void mismatchedPortsWlPortWins() {
        ServerConfig.Result r = ServerConfig.normalize("10.0.0.5", null, "1111", "3333", "2222");
        assertEquals(3333, r.port);
    }

    @Test public void blankIpAddressFallsBackToIpManagement() {
        ServerConfig.Result r1 = ServerConfig.normalize("", "10.0.0.9", "43595", null, null);
        assertEquals("10.0.0.9", r1.ip);
        ServerConfig.Result r2 = ServerConfig.normalize("  ", "10.0.0.9", "43595", null, null);
        assertEquals("10.0.0.9", r2.ip);
    }

    @Test public void bothIpArgsBlankFallsBackToDefaultIp() {
        ServerConfig.Result r = ServerConfig.normalize(null, "", "43595", null, null);
        assertEquals(ServerConfig.DEFAULT_IP, r.ip);
    }

    @Test public void portOutOfRangeFallsBackToDefault() {
        ServerConfig.Result r1 = ServerConfig.normalize("10.0.0.5", null, "0", null, null);
        assertEquals(ServerConfig.DEFAULT_PORT, r1.port);
        ServerConfig.Result r2 = ServerConfig.normalize("10.0.0.5", null, "70000", null, null);
        assertEquals(ServerConfig.DEFAULT_PORT, r2.port);
    }

    /**
     * Locks the common/happy-case behavior: all three port fields already
     * equal and valid (as written by a prior run of patchConfigJson, or a
     * well-formed imported config.json) must resolve to that exact same
     * ip/port pair, unchanged from the pre-extraction inline logic in both
     * Tools.patchConfigJson and SettingsActivity.applyConfigJson.
     */
    @Test public void commonCaseAllPortsEqualResolvesUnchanged() {
        ServerConfig.Result r = ServerConfig.normalize("192.168.0.243", "192.168.0.243",
                "43595", "43595", "43595");
        assertEquals("192.168.0.243", r.ip);
        assertEquals(43595, r.port);
    }
}
