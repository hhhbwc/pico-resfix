package com.picoxr.resfix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * The difference between "a default the user configured" and "the placeholder value the editor
 * shows" decides whether anything is resized at all, so both directions are pinned here.
 */
public class GlobalDefaultsTest {

    private static JSONObject parse(String json) throws Exception {
        return ConfigSchema.parse(json);
    }

    @Test
    public void unconfiguredDefaultIsReportedAsNotConfigured() throws Exception {
        Config.GlobalCfg cfg = Config.globalFrom(parse("{\"default\":{\"showUser\":true}}"));
        assertFalse(cfg.floatingConfigured);
        assertFalse(cfg.dockConfigured);
    }

    @Test
    public void explicitFloatingDefaultIsConfigured() throws Exception {
        Config.GlobalCfg cfg = Config.globalFrom(parse(
                "{\"default\":{\"w\":1920,\"h\":1080,\"density\":240}}"));
        assertTrue(cfg.floatingConfigured);
        assertEquals(1920, cfg.floatingWidth);
        assertEquals(1080, cfg.floatingHeight);
        assertEquals(240, cfg.floatingDensity);
    }

    @Test
    public void legacyPairStillCountsAsConfiguredDockDefault() throws Exception {
        // Floating/Dock were split later; a config written before that only has w/h, and the
        // hook keeps reading it for Dock, so the answer must agree with the hook.
        Config.GlobalCfg cfg = Config.globalFrom(parse("{\"default\":{\"w\":1127,\"h\":752}}"));
        assertTrue(cfg.dockConfigured);
        assertTrue(cfg.floatingConfigured);
    }

    @Test
    public void nearKeysConfigureOnlyDock() throws Exception {
        Config.GlobalCfg cfg = Config.globalFrom(parse(
                "{\"default\":{\"near_w\":1127,\"near_h\":752}}"));
        assertTrue(cfg.dockConfigured);
        assertFalse(cfg.floatingConfigured);
    }

    @Test
    public void emptyRootLeavesEverythingUnconfigured() {
        Config.GlobalCfg cfg = Config.globalFrom(new JSONObject());
        assertFalse(cfg.floatingConfigured);
        assertFalse(cfg.dockConfigured);
        assertEquals(1602, cfg.floatingWidth);
    }

    @Test
    public void clearingTheDefaultResolutionKeepsUnrelatedPreferences() throws Exception {
        JSONObject root = parse("{\"default\":{\"w\":1920,\"h\":1080,\"density\":240,"
                + "\"near_w\":1127,\"near_h\":752,\"near_density\":200,"
                + "\"applyThird\":false,\"applySystem\":true,"
                + "\"showUser\":false,\"showSystem\":true,\"showVR\":true,"
                + "\"showModified\":false},\"apps\":{\"a.b\":{\"w\":1280,\"h\":720}}}");

        ConfigSchema.clearDefaultResolution(root);

        JSONObject def = root.getJSONObject("default");
        assertFalse("resolution keys must go", def.has("w"));
        assertFalse(def.has("h"));
        assertFalse(def.has("density"));
        assertFalse(def.has("near_w"));
        assertFalse(def.has("near_h"));
        assertFalse(def.has("near_density"));
        assertTrue("filters must survive", def.getBoolean("showVR"));
        assertFalse(def.getBoolean("showUser"));
        assertFalse(def.getBoolean("applyThird"));
        assertTrue(def.getBoolean("applySystem"));
        assertTrue("per-app entries must survive", root.getJSONObject("apps").has("a.b"));

        Config.GlobalCfg cfg = Config.globalFrom(root);
        assertFalse(cfg.floatingConfigured);
        assertFalse(cfg.dockConfigured);
    }

    @Test
    public void clearingDropsAnEmptyDefaultObject() throws Exception {
        JSONObject root = parse("{\"default\":{\"w\":1920,\"h\":1080}}");
        ConfigSchema.clearDefaultResolution(root);
        assertFalse("nothing left to store", root.has("default"));
    }

    @Test
    public void clearingWithoutDefaultDoesNothing() {
        JSONObject root = new JSONObject();
        ConfigSchema.clearDefaultResolution(root);
        assertFalse(root.has("default"));
    }
}
