package com.picoxr.resfix;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.Test;

public class ConfigSchemaTest {
    @Test
    public void acceptsValidDefaultAndAppOverrides() throws Exception {
        ConfigSchema.parse("{\"default\":{\"w\":1920,\"h\":1080,\"density\":200,"
                + "\"applyThird\":true},\"apps\":{\"com.example.app\":{\"w\":2560,"
                + "\"h\":1440,\"density\":240,\"dock\":true}}}");
    }

    @Test(expected = JSONException.class)
    public void rejectsPartialResolution() throws Exception {
        ConfigSchema.parse("{\"apps\":{\"com.example.app\":{\"w\":1920}}}");
    }

    @Test(expected = JSONException.class)
    public void rejectsOutOfRangeResolution() throws Exception {
        ConfigSchema.parse("{\"default\":{\"w\":2147483647,\"h\":1080}}");
    }

    @Test(expected = JSONException.class)
    public void rejectsInvalidDensity() throws Exception {
        ConfigSchema.parse("{\"apps\":{\"com.example.app\":{\"w\":1920,\"h\":1080,"
                + "\"density\":0}}}");
    }

    @Test(expected = JSONException.class)
    public void rejectsWrongBooleanType() throws Exception {
        ConfigSchema.parse("{\"default\":{\"applyThird\":\"true\"}}");
    }

    @Test
    public void acceptsIndependentFloatingAndDockResolution() throws Exception {
        ConfigSchema.parse("{\"apps\":{\"com.example.app\":{"
                + "\"w\":1920,\"h\":1080,\"density\":200,"
                + "\"near_w\":1127,\"near_h\":752,\"near_density\":240,\"dock\":true}}}");
    }

    @Test
    public void acceptsLegacySingleResolutionEntry() throws Exception {
        ConfigSchema.parse("{\"apps\":{\"com.example.app\":{"
                + "\"w\":1920,\"h\":1080,\"dock\":true}}}");
    }

    @Test
    public void validatesKnownBoundaries() {
        assertTrue(ConfigSchema.isResolutionValid(ConfigSchema.MIN_WIDTH, ConfigSchema.MIN_HEIGHT));
        assertTrue(ConfigSchema.isResolutionValid(ConfigSchema.MAX_WIDTH, ConfigSchema.MAX_HEIGHT));
        assertFalse(ConfigSchema.isResolutionValid(ConfigSchema.MIN_WIDTH - 1, ConfigSchema.MIN_HEIGHT));
        assertFalse(ConfigSchema.isResolutionValid(ConfigSchema.MAX_WIDTH + 1, ConfigSchema.MAX_HEIGHT));
        assertTrue(ConfigSchema.isDensityValid(ConfigSchema.MIN_DENSITY));
        assertTrue(ConfigSchema.isDensityValid(ConfigSchema.MAX_DENSITY));
        assertFalse(ConfigSchema.isDensityValid(ConfigSchema.MIN_DENSITY - 1));
    }
}
