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
    public static final int MAX_WIDTH = 7680;
    public static final int MIN_HEIGHT = 240;
    public static final int MAX_HEIGHT = 4320;
    public static final int MIN_DENSITY = 72;
    public static final int MAX_DENSITY = 1000;

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
        return width >= MIN_WIDTH && width <= MAX_WIDTH
                && height >= MIN_HEIGHT && height <= MAX_HEIGHT;
    }

    public static boolean isDensityValid(int density) {
        return density >= MIN_DENSITY && density <= MAX_DENSITY;
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
    }

    private static void validateApp(JSONObject value) throws JSONException {
        validateResolution(value, "w", "h", "density");
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
