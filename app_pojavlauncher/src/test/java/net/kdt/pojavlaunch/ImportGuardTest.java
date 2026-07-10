package net.kdt.pojavlaunch;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ImportGuardTest {
    @Test public void underCapPasses() {
        assertTrue(ImportGuard.isWithinSizeLimit(ImportGuard.MAX_IMPORT_BYTES - 1));
    }

    @Test public void atOrOverCapIsRejected() {
        assertFalse(ImportGuard.isWithinSizeLimit(ImportGuard.MAX_IMPORT_BYTES + 1));
        assertFalse(ImportGuard.isWithinSizeLimit(ImportGuard.MAX_IMPORT_BYTES));
    }

    @Test public void validJsonObjectPasses() {
        assertTrue(ImportGuard.isValidJsonObject("{\"ip_address\":\"127.0.0.1\"}"));
    }

    @Test public void nonJsonStringIsRejected() {
        assertFalse(ImportGuard.isValidJsonObject("not json"));
    }

    @Test public void truncatedJsonIsRejected() {
        assertFalse(ImportGuard.isValidJsonObject("{\"ip_address\":"));
    }
}
