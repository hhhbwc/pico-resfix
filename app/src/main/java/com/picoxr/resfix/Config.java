package com.picoxr.resfix;

import org.json.JSONObject;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Config persistence for ResFix.
 * The config JSON lives at /data/local/tmp/resfix.cfg (world-writable tmp, read by the
 * Xposed hook inside com.picovr.systemext). The GUI app writes it via root (su).
 */
public final class Config {

    public static final String PATH = "/data/local/tmp/resfix.cfg";

    /** One app descriptor shown in the list. */
    public static class AppEntry {
        public String pkg;
        public CharSequence label;
        public boolean isSystem;
        public boolean hasOverride;
        public boolean isDock;
        public int w, h, density;   // effective values
    }

    public static class GlobalCfg {
        public int floatingWidth = 1602, floatingHeight = 902, floatingDensity = 200;   // Far mode default
        public int dockWidth = 1127, dockHeight = 752, dockDensity = 200; // Near mode default
        public boolean applyThird = true, applySystem = false;
        public boolean showUser = true, showSystem = false, showVR = false, showModified = true;
    }

    // --- read ---
    public static String readRaw() {
        try {
            File f = new File(PATH);
            if (f.exists() && f.canRead() && f.length() <= ConfigSchema.MAX_CONFIG_BYTES) {
                try (FileInputStream in = new FileInputStream(f)) {
                    return readStream(in);
                }
            }
        } catch (Throwable ignored) {}

        try {
            Process p = new ProcessBuilder("su", "-c", "cat '" + PATH + "'").start();
            String value;
            try (java.io.InputStream in = p.getInputStream()) {
                value = readStream(in);
            }
            if (p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
                return value;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String readStream(java.io.InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > ConfigSchema.MAX_CONFIG_BYTES) {
                throw new java.io.IOException("Configuration is too large");
            }
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    public static JSONObject readRoot() {
        String s = readRaw();
        if (s == null) return new JSONObject();
        try { return ConfigSchema.parse(s); } catch (Throwable t) { return new JSONObject(); }
    }

    public static JSONObject defaultObj(JSONObject root) {
        try {
            if (root.has("default")) return root.getJSONObject("default");
        } catch (Throwable t) {}
        return new JSONObject();
    }

    public static JSONObject appsObj(JSONObject root) {
        try {
            if (root.has("apps")) return root.getJSONObject("apps");
        } catch (Throwable t) {}
        return new JSONObject();
    }

    public static JSONObject ensureAppsObj(JSONObject root) {
        try {
            JSONObject apps = appsObj(root);
            root.put("apps", apps);
            return apps;
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    public static GlobalCfg getGlobal() {
        GlobalCfg g = new GlobalCfg();
        try {
            JSONObject d = defaultObj(readRoot());
            g.floatingWidth = d.optInt("w", g.floatingWidth);
            g.floatingHeight = d.optInt("h", g.floatingHeight);
            g.floatingDensity = d.has("density") ? d.getInt("density") : g.floatingDensity;

            g.dockWidth = d.optInt("near_w", g.dockWidth);
            g.dockHeight = d.optInt("near_h", g.dockHeight);
            g.dockDensity = d.has("near_density") ? d.getInt("near_density") : g.dockDensity;

            g.applyThird = d.optBoolean("applyThird", true);
            g.applySystem = d.optBoolean("applySystem", false);

            g.showUser = d.optBoolean("showUser", true);
            g.showSystem = d.optBoolean("showSystem", false);
            g.showVR = d.optBoolean("showVR", false);
            g.showModified = d.optBoolean("showModified", true);
        } catch (Throwable ignored) {}
        return g;
    }

    public static List<AppEntry> listApps(Context ctx, boolean showUser, boolean showSystem, boolean showVR, boolean showModified, GlobalCfg glob) {
        List<AppEntry> out = new ArrayList<>();
        try {
            PackageManager pm = ctx.getPackageManager();
            List<ApplicationInfo> all = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<ApplicationInfo> sorted = new java.util.ArrayList<>(all);
            java.util.Collections.sort(sorted,
                    (a, b) -> String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm))));
            JSONObject appsJ = appsObj(readRoot());
            for (ApplicationInfo ai : sorted) {
                String pkg = ai.packageName;
                if (pkg == null || pkg.equals(ctx.getPackageName())) continue; // hide ourselves

                boolean isVR = ai.metaData != null && "vr".equals(ai.metaData.getString("pvr.app.type"));
                boolean isDock = isAppDockMode(pm, pkg, ai);
                boolean isSystem = (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;

                boolean hasOverride = false;
                int overW = 0, overH = 0, overD = -1;
                boolean overDock = isDock;

                if (appsJ.has(pkg)) {
                    JSONObject a = appsJ.optJSONObject(pkg);
                    if (a != null) {
                        boolean resolutionEnabled = !a.optBoolean("disabled", false);
                        // Dock routing remains active even when resolution overriding is disabled.
                        // Treat that as a configured entry so filters cannot hide it.
                        hasOverride = resolutionEnabled || a.has("dock");
                        if (a.has("dock")) overDock = a.optBoolean("dock", false);
                        String widthKey = overDock ? "near_w" : "w";
                        String heightKey = overDock ? "near_h" : "h";
                        String densityKey = overDock ? "near_density" : "density";
                        // Legacy app entries used w/h for both modes.
                        if (overDock && (!a.has(widthKey) || !a.has(heightKey))) {
                            widthKey = "w";
                            heightKey = "h";
                            densityKey = "density";
                        }
                        overW = a.optInt(widthKey, 0);
                        overH = a.optInt(heightKey, 0);
                        overD = a.has(densityKey) ? a.optInt(densityKey, -1) : -1;
                        if (!resolutionEnabled) {
                            overW = 0;
                            overH = 0;
                            overD = -1;
                        }
                    }
                }

                // Filtering: skip if not active filter AND no override
                if (hasOverride && !showModified) continue;
                if (!hasOverride) {
                    if (isVR && !showVR) continue;
                    if (!isVR) {
                        if (isSystem && !showSystem) continue;
                        if (!isSystem && !showUser) continue;
                    }
                }
                
                AppEntry e = new AppEntry();
                e.pkg = pkg;
                CharSequence lb = ai.loadLabel(pm);
                e.label = (lb != null) ? lb : pkg;
                e.isSystem = isSystem;
                e.isDock = overDock;
                e.hasOverride = hasOverride;

                if (overW > 0 && overH > 0) {
                    e.w = overW;
                    e.h = overH;
                    e.density = overD;
                } else {
                    if (e.isDock) {
                        e.w = glob.dockWidth; e.h = glob.dockHeight; e.density = glob.dockDensity;
                    } else {
                        e.w = glob.floatingWidth; e.h = glob.floatingHeight; e.density = glob.floatingDensity;
                    }
                }
                out.add(e);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    public static boolean isAppDockMode(PackageManager pm, String pkg, ApplicationInfo ai) {
        try {
            if (ai == null) ai = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA);
            if (isMetadataNear(ai.metaData)) return true;
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES | PackageManager.GET_META_DATA);
            if (pi.activities != null) {
                for (ActivityInfo act : pi.activities) {
                    if (isMetadataNear(act.metaData)) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean isMetadataNear(android.os.Bundle meta) {
        if (meta == null) return false;
        if ("near".equals(meta.getString("pico.vr.position"))) return true;
        Object mode = meta.get("pvr.2dtovr.mode");
        if (mode != null) {
            String s = String.valueOf(mode);
            return "6".equals(s) || "near".equals(s);
        }
        return false;
    }

    // --- write (via su) ---
    public static synchronized boolean writeRoot(JSONObject root) {
        String json = root.toString();
        try {
            ConfigSchema.validate(root);
            if (json.getBytes(StandardCharsets.UTF_8).length > ConfigSchema.MAX_CONFIG_BYTES) return false;
            String tmp = PATH + ".tmp." + android.os.Process.myPid();
            Process p = new ProcessBuilder("su", "-c",
                    "umask 000; cat > '" + tmp + "' && chmod 666 '" + tmp
                            + "' && mv -f '" + tmp + "' '" + PATH + "'")
                    .redirectErrorStream(false).start();
            java.io.OutputStream os = p.getOutputStream();
            os.write(json.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
            if (!p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) return false;
            
            // Sync to Settings.Global to bypass SELinux file restrictions for the hook
            String escaped = json.replace("'", "'\\''");
            Process settings = new ProcessBuilder("su", "-c",
                    "settings put global pico_systemext_coord_resfix_config '" + escaped + "'").start();
            settings.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);

            String persisted = readRaw();
            if (persisted == null || !json.equals(persisted)) return false;
            publishCoordination(root);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void publishCoordination(JSONObject root) {
        try {
            int panels = 0;
            JSONObject apps = appsObj(root);
            java.util.Iterator<String> keys = apps.keys();
            while (keys.hasNext()) {
                JSONObject app = apps.optJSONObject(keys.next());
                if (app != null && app.optBoolean("dock", false)) panels++;
            }
            Process p = new ProcessBuilder("su", "-c",
                    "settings put global pico_systemext_coord_resfix_panels " + panels
                            + "; settings put global pico_systemext_coord_resfix_generation "
                            + System.currentTimeMillis())
                    .start();
            p.waitFor();
        } catch (Throwable ignored) {
        }
    }

    public static String applyBatchJson(String raw) {
        if (raw == null) return "No input";
        try {
            JSONObject input = ConfigSchema.parse(raw.trim());
            JSONObject root = readRoot();
            JSONObject apps = ensureAppsObj(root);

            int count = 0;

            if (input.has("default")) {
                root.put("default", input.getJSONObject("default"));
                count++;
            }

            JSONObject batchApps = input.optJSONObject("apps");
            if (batchApps != null) {
                java.util.Iterator<String> keys = batchApps.keys();
                while (keys.hasNext()) {
                    String pkg = keys.next();
                    if ("com.picoxr.resfix".equals(pkg)) continue;
                    JSONObject entry = batchApps.optJSONObject(pkg);
                    if (entry == null) continue;
                    apps.put(pkg, entry);
                    count++;
                }
            }

            if (count <= 0) return "No batch entries";
            root.put("apps", apps);
            try {
                ConfigSchema.validate(root);
            } catch (Throwable t) {
                return "Invalid configuration: " + t.getMessage();
            }
            return writeRoot(root) ? "Imported " + count + " item(s)" : "Write failed";
        } catch (Throwable t) {
            return "Import failed: " + t.getClass().getSimpleName();
        }
    }

    public static boolean applyBatchSettings(List<String> packages, int w, int h, int density,
            boolean enabled, boolean dock) {
        if (packages == null || packages.isEmpty() || !ConfigSchema.isResolutionValid(w, h)
                || (density > 0 && !ConfigSchema.isDensityValid(density))) return false;
        try {
            JSONObject root = readRoot();
            JSONObject apps = ensureAppsObj(root);
            for (String pkg : packages) {
                if (pkg == null || pkg.isEmpty() || "com.picoxr.resfix".equals(pkg)) continue;
                JSONObject entry = apps.optJSONObject(pkg);
                if (entry == null) entry = new JSONObject();
                entry.put("disabled", !enabled);
                entry.put("dock", dock);
                String prefix = dock ? "near_" : "";
                entry.put(prefix + "w", w);
                entry.put(prefix + "h", h);
                if (density > 0) entry.put(prefix + "density", density);
                else entry.remove(prefix + "density");
                apps.put(pkg, entry);
            }
            root.put("apps", apps);
            return writeRoot(root);
        } catch (Throwable t) {
            return false;
        }
    }
}
