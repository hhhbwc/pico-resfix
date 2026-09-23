package com.picoxr.resfix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** Covers the key-selection rules shared by the SystemExt hook and the settings UI. */
public class ResolutionKeysTest {

    private static JSONObject entry(String json) throws Exception {
        return new JSONObject(json);
    }

    @Test
    public void floatingReadsUnprefixedKeys() throws Exception {
        JSONObject value = entry("{\"w\":1920,\"h\":1080,\"density\":240}");
        String[] keys = ConfigSchema.resolutionKeys(false, value);
        assertEquals("w", keys[0]);
        assertEquals("h", keys[1]);
        assertEquals("density", keys[2]);
    }

    @Test
    public void dockReadsNearPrefixedKeysWhenPresent() throws Exception {
        JSONObject value = entry("{\"w\":1920,\"h\":1080,\"near_w\":1127,\"near_h\":752,"
                + "\"near_density\":240}");
        String[] keys = ConfigSchema.resolutionKeys(true, value);
        assertEquals("near_w", keys[0]);
        assertEquals("near_h", keys[1]);
        assertEquals("near_density", keys[2]);
    }

    @Test
    public void dockFallsBackToLegacyPairSoOldConfigsKeepWorking() throws Exception {
        JSONObject value = entry("{\"w\":1920,\"h\":1080,\"density\":240}");
        String[] keys = ConfigSchema.resolutionKeys(true, value);
        assertEquals("w", keys[0]);
        assertEquals("h", keys[1]);
        assertEquals("density", keys[2]);
    }

    @Test
    public void dockFallsBackWhenOnlyOneNearKeyExists() throws Exception {
        JSONObject value = entry("{\"w\":1920,\"h\":1080,\"near_w\":1127}");
        String[] keys = ConfigSchema.resolutionKeys(true, value);
        assertEquals("w", keys[0]);
        assertEquals("h", keys[1]);
        assertEquals("density", keys[2]);
    }

    @Test
    public void floatingNeverBorrowsNearKeys() throws Exception {
        JSONObject value = entry("{\"near_w\":1127,\"near_h\":752}");
        String[] keys = ConfigSchema.resolutionKeys(false, value);
        assertEquals("w", keys[0]);
        assertEquals("h", keys[1]);
    }

    @Test
    public void nullEntryDoesNotThrow() {
        String[] keys = ConfigSchema.resolutionKeys(true, null);
        assertEquals("near_w", keys[0]);
        assertEquals("near_h", keys[1]);
    }

    @Test
    public void maxBoundaryStillInsidePixelCap() {
        assertTrue(ConfigSchema.isResolutionValid(ConfigSchema.MAX_WIDTH, ConfigSchema.MAX_HEIGHT));
        assertFalse(ConfigSchema.isResolutionValid(ConfigSchema.MAX_WIDTH, ConfigSchema.MAX_HEIGHT + 1));
        // Guards the caps the documentation promises actually reject something.
        assertTrue((long) ConfigSchema.MAX_WIDTH * ConfigSchema.MAX_HEIGHT <= ConfigSchema.MAX_PIXELS);
    }
}
