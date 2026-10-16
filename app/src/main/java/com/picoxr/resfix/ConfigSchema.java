package com.picoxr.resfix;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/** Validates the JSON shared by the settings UI and the SystemExt hook. */
public final class ConfigSchema {
    public static final int MAX_CONFIG_BYTES = 256 * 1024;
    public static final int MAX_APP_ENTRIES = 500;
    public static final int MIN_WIDTH = 320;
    public static final int MAX_WIDTH = 3840;
    public static final int MIN_HEIGHT = 240;
    public static final int MAX_HEIGHT = 2162;
    public static final int MIN_DENSITY = 72;
    public static final int MAX_DENSITY = 1000;

    /** Stock keyboard display the firmware hardcodes in InputMethodWindow. */
    public static final int IME_BASE_WIDTH = 1045;
    public static final int IME_BASE_HEIGHT = 460;
    public static final int IME_BASE_DENSITY = 200;

    /**
     * Maximum total pixels an app may request. PICO 4 (A8110) renders about 7.2MP per
     * eye pair; anything beyond ~1.7x that cannot reliably be allocated, and the
     * display server then downgrades the buffer silently while still reporting
     * success. That mismatch between requested and actual buffer size is what shows
     * up as tearing lines, so cap it rather than accepting a request the hardware
     * will quietly ignore.
     */
    public static final long MAX_PIXELS = 12_000_000L;

    private ConfigSchema() {}

    public static JSONObject parse(String text) throws JSONException {
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES) {
            throw new JSONException("Configuration is missing or too large");
        }
        JSONObject root = new JSONObject(text);
        validate(root);
        return root;
    }

    public static void validate(JSONObject root) throws JSONException {
        if (root == null) throw new JSONException("Configuration root is missing");
        if (root.has("default")) validateDefault(root.getJSONObject("default"));
        if (root.has("ime")) validateIme(root.getJSONObject("ime"));
        if (!root.has("apps")) return;
        JSONObject apps = root.getJSONObject("apps");
        int count = 0;
        Iterator<String> keys = apps.keys();
        while (keys.hasNext()) {
            String pkg = keys.next();
            if (++count > MAX_APP_ENTRIES) throw new JSONException("Too many app entries");
            if (pkg == null || pkg.isEmpty() || pkg.length() > 255) {
                throw new JSONException("Invalid package name");
            }
            validateApp(apps.getJSONObject(pkg));
        }
    }

    public static boolean isResolutionValid(int width, int height) {
        if (width < MIN_WIDTH || width > MAX_WIDTH
                || height < MIN_HEIGHT || height > MAX_HEIGHT) return false;
        return (long) width * height <= MAX_PIXELS;
    }

    public static boolean isDensityValid(int density) {
        return density >= MIN_DENSITY && density <= MAX_DENSITY;
    }

    /**
     * Resolves which JSON keys carry the requested window mode's resolution.
     *
     * <p>Entries written before Floating and Dock were separated only ever had {@code w/h/density}
     * and used them for both modes. Dock keeps reading that legacy pair when its own keys are
     * absent, so upgrading firmware or GUI does not silently drop existing configurations.
     * Every consumer must route through this helper; duplicating the rules locally is how
     * {@code ResFix} and {@code Config} previously drifted apart.
     */
    public static String[] resolutionKeys(boolean dock, JSONObject value) {
        String widthKey = dock ? "near_w" : "w";
        String heightKey = dock ? "near_h" : "h";
        String densityKey = dock ? "near_density" : "density";
        if (dock && value != null && (!value.has(widthKey) || !value.has(heightKey))) {
            widthKey = "w";
            heightKey = "h";
            densityKey = "density";
        }
        return new String[]{widthKey, heightKey, densityKey};
    }

    /**
     * Names that only carry a resolution. Everything else under {@code default} is a UI
     * preference (the four {@code show*} filters, {@code applyThird}, {@code applySystem}),
     * so clearing a configured default must key off these names instead of dropping the
     * whole object.
     */
    private static final String[] RESOLUTION_KEYS = {
            "w", "h", "density", "near_w", "near_h", "near_density"
    };

    /**
     * Removes the default resolution while keeping the unrelated preferences stored beside it.
     * An object left with nothing in it is dropped so "no default" stays indistinguishable
     * from "never configured".
     */
    public static void clearDefaultResolution(JSONObject root) {
        JSONObject value = root == null ? null : root.optJSONObject("default");
        if (value == null) return;
        for (String key : RESOLUTION_KEYS) value.remove(key);
        if (value.length() == 0) root.remove("default");
    }

    /**
     * The "ime" section: a per-package map ({"<pkg>": {"w","h","density"}}) plus the
     * legacy flat shape (w/h/density directly on the section) and the optional
     * "imeKeep" full-IME-id string naming the keep-alive target.
     */
    private static void validateIme(JSONObject ime) throws JSONException {
        if (ime.has("w") || ime.has("h")) {
            validateResolution(ime, "w", "h", "density");
        }
        if (ime.has("imeKeep") && !(ime.get("imeKeep") instanceof String)) {
            throw new JSONException("imeKeep must be a string");
        }
        Iterator<String> keys = ime.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("imeKeep".equals(key) || "w".equals(key)
                    || "h".equals(key) || "density".equals(key)) {
                continue;
            }
            Object entry = ime.opt(key);
            if (!(entry instanceof JSONObject)) {
                throw new JSONException("ime entries must be objects: " + key);
            }
            validateResolution((JSONObject) entry, "w", "h", "density");
        }
    }

    /** Removes one IME's resolution entry; drops the whole section when nothing is left. */
    public static void clearImeEntry(JSONObject root, String imePkg) {
        JSONObject ime = root == null ? null : root.optJSONObject("ime");
        if (ime == null) return;
        ime.remove(imePkg);
        if (ime.length() == 0) root.remove("ime");
    }

    private static void validateDefault(JSONObject value) throws JSONException {
        validateResolution(value, "w", "h", "density");
        validateResolution(value, "near_w", "near_h", "near_density");
        validateBoolean(value, "applyThird");
        validateBoolean(value, "applySystem");
        validateBoolean(value, "showUser");
        validateBoolean(value, "showSystem");
        validateBoolean(value, "showVR");
        validateBoolean(value, "showModified");
        validateBoolean(value, "showIme");
    }

    private static void validateApp(JSONObject value) throws JSONException {
        validateResolution(value, "w", "h", "density");
        validateResolution(value, "near_w", "near_h", "near_density");
        validateBoolean(value, "disabled");
        validateBoolean(value, "dock");
    }

    private static void validateResolution(JSONObject value, String widthKey, String heightKey,
            String densityKey) throws JSONException {
        boolean hasWidth = value.has(widthKey);
        boolean hasHeight = value.has(heightKey);
        if (hasWidth != hasHeight) throw new JSONException("Resolution requires width and height");
        if (hasWidth && !isResolutionValid(value.getInt(widthKey), value.getInt(heightKey))) {
            throw new JSONException("Resolution is out of range");
        }
        if (value.has(densityKey)) {
            if (!hasWidth || !isDensityValid(value.getInt(densityKey))) {
                throw new JSONException("Density is invalid");
            }
        }
    }

    private static void validateBoolean(JSONObject value, String key) throws JSONException {
        if (value.has(key) && !(value.get(key) instanceof Boolean)) {
            throw new JSONException(key + " must be boolean");
        }
    }
}
