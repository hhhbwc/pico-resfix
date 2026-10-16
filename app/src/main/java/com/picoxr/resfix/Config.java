package com.picoxr.resfix;

import org.json.JSONObject;

import android.content.Context;
import android.util.Log;
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

    /** Settings.Global keys mirrored for the hook inside SystemExt; it reads these names verbatim. */
    public static final String CONFIG_SETTING = "pico_systemext_coord_resfix_config";
    public static final String GENERATION_SETTING = "pico_systemext_coord_resfix_generation";
    public static final String PANELS_SETTING = "pico_systemext_coord_resfix_panels";

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
        /**
         * The values above are what the editor pre-fills, not what the module applies. Nothing
         * is resized until a default is written explicitly, so every consumer that reports a
         * default resolution has to check these flags first.
         */
        public boolean floatingConfigured, dockConfigured;
        public boolean applyThird = true, applySystem = false;
        public boolean showUser = true, showSystem = false, showVR = false, showModified = true;
        public boolean showIme = false;
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
            Process p = new ProcessBuilder("su", "-c", "cat '" + PATH + "'")
                    .redirectErrorStream(true).start();
            try {
                String value;
                try (java.io.InputStream in = p.getInputStream()) {
                    value = readStream(in);
                }
                if (p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return value;
                }
            } finally {
                if (p.isAlive()) p.destroyForcibly();
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

    /** Returns null when an existing configuration cannot be read or validated. */
    public static JSONObject readRootForWrite() {
        String s = readRaw();
        if (s == null) {
            return new File(PATH).exists() ? null : new JSONObject();
        }
        try { return ConfigSchema.parse(s); } catch (Throwable t) { return null; }
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

    public static JSONObject imeObj(JSONObject root) {
        try {
            if (root.has("ime")) return root.getJSONObject("ime");
        } catch (Throwable t) {}
        return new JSONObject();
    }

    public static JSONObject ensureImeObj(JSONObject root) {
        try {
            JSONObject ime = imeObj(root);
            root.put("ime", ime);
            return ime;
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    public interface RootMutation {
        void apply(JSONObject root) throws Exception;
    }

    /** Performs read, mutation, validation and write as one serialized transaction. */
    public static synchronized boolean updateRoot(RootMutation mutation) {
        if (mutation == null) return false;
        JSONObject root = readRootForWrite();
        if (root == null) return false;
        try {
            mutation.apply(root);
            ConfigSchema.validate(root);
            return writeRoot(root);
        } catch (Throwable t) {
            Log.w("PicoResFix", "configuration update rejected", t);
            return false;
        }
    }

    public static GlobalCfg getGlobal() {
        return globalFrom(readRoot());
    }

    /** Reads the preferences out of an already loaded root so one read serves several callers. */
    public static GlobalCfg globalFrom(JSONObject root) {
        GlobalCfg g = new GlobalCfg();
        if (root == null) return g;
        try {
            JSONObject d = defaultObj(root);
            g.floatingWidth = d.optInt("w", g.floatingWidth);
            g.floatingHeight = d.optInt("h", g.floatingHeight);
            g.floatingDensity = d.has("density") ? d.getInt("density") : g.floatingDensity;

            // Mirror the hook's legacy fallback: a config written before Floating and Dock were
            // separated only has w/h, and its Dock values must match what SystemExt will apply.
            String[] dockKeys = ConfigSchema.resolutionKeys(true, d);
            g.dockWidth = d.optInt(dockKeys[0], g.dockWidth);
            g.dockHeight = d.optInt(dockKeys[1], g.dockHeight);
            g.dockDensity = d.has(dockKeys[2]) ? d.getInt(dockKeys[2]) : g.dockDensity;

            g.floatingConfigured = d.has("w") && d.has("h");
            g.dockConfigured = d.has(dockKeys[0]) && d.has(dockKeys[1]);

            g.applyThird = d.optBoolean("applyThird", true);
            g.applySystem = d.optBoolean("applySystem", false);

            g.showUser = d.optBoolean("showUser", true);
            g.showSystem = d.optBoolean("showSystem", false);
            g.showVR = d.optBoolean("showVR", false);
            g.showModified = d.optBoolean("showModified", true);
            g.showIme = d.optBoolean("showIme", false);
        } catch (Throwable ignored) {}
        return g;
    }

    /**
     * @param appsJ already loaded {@code apps} object; callers that have the root beside them
     *              should pass it so each list does not pay for a second privileged read.
     */
    public static List<AppEntry> listApps(Context ctx, boolean showUser, boolean showSystem,
            boolean showVR, boolean showModified, GlobalCfg glob, JSONObject appsJ) {
        List<AppEntry> out = new ArrayList<>();
        try {
            PackageManager pm = ctx.getPackageManager();
            List<ApplicationInfo> all = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<ApplicationInfo> sorted = new java.util.ArrayList<>(all);
            final java.util.Map<String, String> labels = new java.util.HashMap<>();
            for (ApplicationInfo ai : sorted) {
                String pkg = ai == null ? null : ai.packageName;
                labels.put(pkg, safeLabel(pm, ai, pkg));
            }
            java.util.Collections.sort(sorted,
                    (a, b) -> labels.get(a == null ? null : a.packageName)
                            .compareToIgnoreCase(labels.get(b == null ? null : b.packageName)));
            if (appsJ == null) appsJ = appsObj(readRoot());
            for (ApplicationInfo ai : sorted) {
                if (ai == null) continue;
                String pkg = ai.packageName;
                if (pkg == null || pkg.equals(ctx.getPackageName())) continue; // hide ourselves

                try {
                    PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
                    if (pi.activities == null || pi.activities.length == 0) continue;
                } catch (Throwable ignored) {
                    continue;
                }

                boolean isVR;
                try {
                    isVR = ai.metaData != null && "vr".equals(ai.metaData.getString("pvr.app.type"));
                } catch (Throwable ignored) {
                    isVR = false;
                }
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
                        String[] keys = ConfigSchema.resolutionKeys(overDock, a);
                        String widthKey = keys[0];
                        String heightKey = keys[1];
                        String densityKey = keys[2];
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
                e.label = labels.get(pkg);
                e.isSystem = isSystem;
                e.isDock = overDock;
                e.hasOverride = hasOverride;

                if (overW > 0 && overH > 0) {
                    e.w = overW;
                    e.h = overH;
                    e.density = overD;
                } else {
                    // Without a configured default the hook leaves these apps alone, so the list
                    // must not dress them up with the editor's placeholder values - leave the
                    // entry blank and let the UI say nothing is being applied.
                    boolean configured = e.isDock ? glob.dockConfigured : glob.floatingConfigured;
                    if (configured) {
                        e.w = e.isDock ? glob.dockWidth : glob.floatingWidth;
                        e.h = e.isDock ? glob.dockHeight : glob.floatingHeight;
                        e.density = e.isDock ? glob.dockDensity : glob.floatingDensity;
                    }
                }
                out.add(e);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** The only input method this project's keyboard adaptations (height auto-fit, keep-alive) target. */
    public static final String ADAPTED_IME_PKG = "org.fcitx.fcitx5.android";

    public static List<AppEntry> listImes(Context ctx, JSONObject imeObj) {
        List<AppEntry> out = new ArrayList<>();
        try {
            PackageManager pm = ctx.getPackageManager();
            List<android.content.pm.ResolveInfo> services = pm.queryIntentServices(
                    new android.content.Intent("android.view.InputMethod"),
                    PackageManager.GET_META_DATA);
            if (services == null) return out;
            int flatW = imeObj == null ? 0 : imeObj.optInt("w", 0);
            int flatH = imeObj == null ? 0 : imeObj.optInt("h", 0);
            int flatD = imeObj == null || !imeObj.has("density") ? -1 : imeObj.optInt("density");
            for (android.content.pm.ResolveInfo info : services) {
                if (info.serviceInfo == null) continue;
                String id = new android.content.ComponentName(
                        info.serviceInfo.packageName, info.serviceInfo.name).flattenToShortString();
                String imePkg = info.serviceInfo.packageName;
                JSONObject entry = imeObj == null ? null : imeObj.optJSONObject(imePkg);
                AppEntry e = new AppEntry();
                e.pkg = "@ime:" + id;
                CharSequence label = info.loadLabel(pm);
                String base = label != null && label.length() > 0 ? label.toString() : imePkg;
                boolean adapted = ADAPTED_IME_PKG.equals(imePkg);
                e.label = base + ctx.getString(adapted
                        ? R.string.ime_adapted : R.string.ime_not_adapted);
                e.isSystem = false;
                e.isDock = false;
                e.hasOverride = entry != null;
                if (entry != null) {
                    e.w = entry.optInt("w", 0);
                    e.h = entry.optInt("h", 0);
                    e.density = entry.has("density") ? entry.optInt("density") : -1;
                } else if (flatW > 0 && flatH > 0) {
                    // keyboards without their own entry ride the flat "ime" section
                    e.w = flatW;
                    e.h = flatH;
                    e.density = flatD;
                }
                out.add(e);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static String safeLabel(PackageManager pm, ApplicationInfo ai, String fallback) {
        try {
            CharSequence label = ai == null ? null : ai.loadLabel(pm);
            return label == null ? String.valueOf(fallback) : String.valueOf(label);
        } catch (Throwable ignored) {
            return String.valueOf(fallback);
        }
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
            Process p = silence(new ProcessBuilder("su", "-c",
                    "umask 000; cat > '" + tmp + "' && chmod 666 '" + tmp
                            + "' && mv -f '" + tmp + "' '" + PATH + "'")).start();
            java.io.OutputStream os = p.getOutputStream();
            os.write(json.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
            if (!p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) {
                if (p.isAlive()) p.destroyForcibly();
                return false;
            }

            // Sync to Settings.Global to bypass SELinux file restrictions for the hook
            String escaped = json.replace("'", "'\\''");
            boolean settingsWritten = runRootCommand(
                    "settings put global " + CONFIG_SETTING + " '" + escaped + "'");
            String persisted = readRaw();
            if (!settingsWritten) {
                // The file is authoritative; Settings.Global is only a restricted-device fallback.
                // Do not report a successful file write as failed when this mirror is unavailable.
                Log.w("PicoResFix", "Settings.Global mirror unavailable");
            }
            if (persisted == null || !json.equals(persisted)) return false;
            publishCoordination(root);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Runs a root command whose output nobody reads. Leaving the pipes unattended lets a child
     * that prints more than the 64 KiB buffer block until waitFor() gives up, which stalls every
     * configuration write by its full timeout, so point them at /dev/null instead.
     */
    private static boolean runRootCommand(String command) {
        Process p = null;
        try {
            p = silence(new ProcessBuilder("su", "-c", command)).start();
            return p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }

    private static ProcessBuilder silence(ProcessBuilder builder) {
        try {
            // Always present on Android; the fallback keeps /dev/null-less hosts buildable.
            File sink = new File("/dev/null");
            if (sink.exists()) {
                builder.redirectOutput(sink);
                builder.redirectError(sink);
            }
        } catch (Throwable ignored) {}
        return builder;
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
            runRootCommand("settings put global " + PANELS_SETTING + " " + panels
                    + "; settings put global " + GENERATION_SETTING + " "
                    + System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
    }

    /** Outcome of a batch import, kept structural so the caller can supply localized wording. */
    public static final class BatchImportResult {
        public enum Status { OK, WRITE_FAILED, REJECTED }

        public final Status status;
        public final int count;

        BatchImportResult(Status status, int count) {
            this.status = status;
            this.count = count;
        }
    }

    public static BatchImportResult applyBatchJson(String raw) {
        if (raw == null) return new BatchImportResult(BatchImportResult.Status.REJECTED, 0);
        try {
            JSONObject input = ConfigSchema.parse(raw.trim());
            final int[] count = {0};
            boolean ok = updateRoot(root -> {
                JSONObject apps = ensureAppsObj(root);
                if (input.has("default")) {
                    root.put("default", input.getJSONObject("default"));
                    count[0]++;
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
                        count[0]++;
                    }
                }
                if (count[0] <= 0) throw new IllegalArgumentException("No batch entries");
                root.put("apps", apps);
            });
            return ok ? new BatchImportResult(BatchImportResult.Status.OK, count[0])
                    : new BatchImportResult(BatchImportResult.Status.WRITE_FAILED, 0);
        } catch (Throwable t) {
            return new BatchImportResult(BatchImportResult.Status.REJECTED, 0);
        }
    }

    public static boolean applyBatchSettings(List<String> packages, int w, int h, int density,
            boolean enabled, boolean dock) {
        if (packages == null || packages.isEmpty() || !ConfigSchema.isResolutionValid(w, h)
                || (density > 0 && !ConfigSchema.isDensityValid(density))) return false;
        try {
            final int[] changed = {0};
            return updateRoot(root -> {
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
                    changed[0]++;
                }
                if (changed[0] == 0) throw new IllegalArgumentException("No packages");
                root.put("apps", apps);
            });
        } catch (Throwable t) {
            return false;
        }
    }
}
