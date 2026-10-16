package com.picoxr.resfix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Robustness of the "ime" section: garbage must be rejected before it reaches the
 * systemext hook. Rejection is deliberately whole-config (fail-closed): the hook keeps
 * its last good snapshot and the GUI refuses to write, so nothing half-valid leaks in.
 */
public class ImeSchemaRobustnessTest {

    private static JSONObject parse(String json) throws JSONException {
        return ConfigSchema.parse(json);
    }

    @Test
    public void validPerImeAndFlatMixParses() throws Exception {
        JSONObject root = parse("{\"apps\":{},\"ime\":{\"w\":2090,\"h\":920,\"density\":400,"
                + "\"org.fcitx.fcitx5.android\":{\"w\":1045,\"h\":460},"
                + "\"imeKeep\":\"org.fcitx.fcitx5.android/.input.FcitxInputMethodService\"}}");
        assertEquals(400, root.getJSONObject("ime").getInt("density"));
        assertEquals(1045, root.getJSONObject("ime")
                .getJSONObject("org.fcitx.fcitx5.android").getInt("w"));
    }

    @Test
    public void imeAsStringRejected() {
        assertThrows(JSONException.class, () -> parse("{\"ime\":\"oops\"}"));
    }

    @Test
    public void imeAsNumberRejected() {
        assertThrows(JSONException.class, () -> parse("{\"ime\":123}"));
    }

    @Test
    public void imeEntryAsNumberRejected() {
        assertThrows(JSONException.class,
                () -> parse("{\"ime\":{\"org.fcitx.fcitx5.android\":123}}"));
    }

    @Test
    public void imeEntryAsArrayRejected() {
        assertThrows(JSONException.class,
                () -> parse("{\"ime\":{\"x\":[1045,460]}}"));
    }

    @Test
    public void imeEntryMissingHeightRejected() {
        assertThrows(JSONException.class,
                () -> parse("{\"ime\":{\"org.fcitx.fcitx5.android\":{\"w\":1045}}}"));
    }

    @Test
    public void imeEntryAbsurdWidthRejected() {
        assertThrows(JSONException.class,
                () -> parse("{\"ime\":{\"x\":{\"w\":99999,\"h\":460}}}"));
    }

    @Test
    public void imeEntryNegativeWidthRejected() {
        assertThrows(JSONException.class,
                () -> parse("{\"ime\":{\"x\":{\"w\":-5,\"h\":460}}}"));
    }

    @Test
    public void imeEntryOversizedPixelsRejected() {
        // 3840 x 3126 = 12,003,840 px, above the 12M budget
        assertThrows(JSONException.class,
                () -> parse("{\"ime\":{\"x\":{\"w\":3840,\"h\":3126}}}"));
    }

    @Test
    public void imeMaxDimensionsAccepted() throws Exception {
        // 3840 x 2162 = the largest dimensions within the caps (8.3M px, below the 12M budget)
        parse("{\"ime\":{\"x\":{\"w\":3840,\"h\":2162}}}");
    }

    @Test
    public void imeHeightAboveCapRejected() {
        // the height cap (2162) triggers before the pixel budget ever could
        assertThrows(JSONException.class,
                () -> parse("{\"ime\":{\"x\":{\"w\":3840,\"h\":3125}}}"));
    }

    @Test
    public void imeDensityBounds() {
        assertThrows(JSONException.class,
                () -> parse("{\"ime\":{\"x\":{\"w\":1045,\"h\":460,\"density\":1001}}}"));
        assertThrows(JSONException.class,
                () -> parse("{\"ime\":{\"x\":{\"w\":1045,\"h\":460,\"density\":71}}}"));
    }

    @Test
    public void imeKeepNonStringRejected() {
        assertThrows(JSONException.class, () -> parse("{\"ime\":{\"imeKeep\":123}}"));
    }

    @Test
    public void imeEmptyPackageNameRejected() {
        assertThrows(JSONException.class, () -> parse("{\"ime\":{\"\":{\"w\":1045,\"h\":460}}}"));
    }

    @Test
    public void emptyImeObjectAccepted() throws Exception {
        parse("{\"ime\":{}}");
    }

    @Test
    public void unicodePackageKeyAccepted() throws Exception {
        parse("{\"ime\":{\"输入法包名\":{\"w\":1045,\"h\":460}}}");
    }

    @Test
    public void badImeSectionRejectsWholeConfig() {
        // documented fail-closed behavior: the hook keeps its last good snapshot
        assertThrows(JSONException.class, () -> parse(
                "{\"apps\":{\"a\":{\"w\":1280,\"h\":722}},\"ime\":{\"x\":{\"w\":1,\"h\":1}}}"));
    }

    @Test
    public void clearImeEntryDropsEntryAndEmptySection() throws Exception {
        JSONObject root = parse("{\"ime\":{\"a\":{\"w\":1045,\"h\":460},"
                + "\"b\":{\"w\":1045,\"h\":460}}}");
        ConfigSchema.clearImeEntry(root, "a");
        assertTrue(root.getJSONObject("ime").has("b"));
        ConfigSchema.clearImeEntry(root, "b");
        assertFalse(root.has("ime"));
    }

    @Test
    public void clearImeEntryOnMissingThingsIsNoop() throws Exception {
        JSONObject root = parse("{\"apps\":{}}");
        ConfigSchema.clearImeEntry(root, "a");
        assertFalse(root.has("ime"));
        JSONObject withOther = parse("{\"ime\":{\"imeKeep\":\"a/b\"}}");
        ConfigSchema.clearImeEntry(withOther, "a");
        // imeKeep is not an entry: the section survives
        assertTrue(withOther.has("ime"));
    }
}
