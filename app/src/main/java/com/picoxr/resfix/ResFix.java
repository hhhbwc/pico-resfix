package com.picoxr.resfix;

import android.content.Context;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Per-app virtual-display resolution and dock-mode override for PICO SystemExt. */
public class ResFix implements IXposedHookLoadPackage {
    static final String TAG = "PicoResFix";
    static final String CONFIG = "/data/local/tmp/resfix.cfg";
    private static final String CONFIG_SETTING = "pico_systemext_coord_resfix_config";
    private static final String GENERATION_SETTING = "pico_systemext_coord_resfix_generation";

    static final class Cfg {
        final int w;
        final int h;
        final int density;
        final boolean applyThird;
        final boolean applySystem;

        Cfg(int w, int h, int density, boolean applyThird, boolean applySystem) {
            this.w = w;
            this.h = h;
            this.density = density;
            this.applyThird = applyThird;
            this.applySystem = applySystem;
        }
    }

    private static final class Snapshot {
        final String key;
        final JSONObject root;

        Snapshot(String key, JSONObject root) {
            this.key = key;
            this.root = root;
        }
    }

    private static volatile Snapshot snapshot;

    private static Context systemContext() {
        try {
            Object activityThread = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null), "currentActivityThread");
            return activityThread == null ? null
                    : (Context) XposedHelpers.callMethod(activityThread, "getSystemContext");
        } catch (Throwable t) {
            log("failed to obtain system context", t);
            return null;
        }
    }

    private static Snapshot configSnapshot() {
        Context context = systemContext();
        String generation = null;
        String settingsConfig = null;
        if (context != null) {
            try {
                generation = Settings.Global.getString(context.getContentResolver(), GENERATION_SETTING);
                settingsConfig = Settings.Global.getString(context.getContentResolver(), CONFIG_SETTING);
            } catch (Throwable t) {
                log("failed to read configuration settings", t);
            }
        }

        String fileConfig = readFileConfig();
        JSONObject parsed = parseConfig(fileConfig, CONFIG);
        String source = CONFIG;
        if (parsed == null) {
            parsed = parseConfig(settingsConfig, "Settings.Global");
            source = "Settings.Global";
        }
        if (parsed == null) {
            Snapshot current = snapshot;
            if (current != null) {
                log("configuration reload failed; keeping last valid snapshot", null);
                return current;
            }
            parsed = new JSONObject();
        }

        String content = source + ":" + (source.equals(CONFIG) ? fileConfig : settingsConfig);
        String key = (generation == null ? "" : generation) + ":" + content.hashCode();
        Snapshot current = snapshot;
        if (current != null && current.key.equals(key)) return current;
        Snapshot loaded = new Snapshot(key, parsed);
        snapshot = loaded;
        return loaded;
    }

    private static JSONObject parseConfig(String text, String source) {
        if (text == null || text.isEmpty()) return null;
        try {
            return ConfigSchema.parse(text);
        } catch (Throwable t) {
            log("invalid configuration from " + source, t);
            return null;
        }
    }

    private static String readFileConfig() {
        try {
            File file = new File(CONFIG);
            if (!file.exists() || file.length() > ConfigSchema.MAX_CONFIG_BYTES) return null;
            try (FileInputStream in = new FileInputStream(file)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (out.size() + read > ConfigSchema.MAX_CONFIG_BYTES) return null;
                    out.write(buffer, 0, read);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } catch (Throwable t) {
            log("failed to read configuration file", t);
            return null;
        }
    }

    static Cfg defaultConfig(boolean dock) {
        JSONObject value = configSnapshot().root.optJSONObject("default");
        if (value == null) return new Cfg(0, 0, -1, true, false);
        try {
            String widthKey = dock ? "near_w" : "w";
            String heightKey = dock ? "near_h" : "h";
            String densityKey = dock ? "near_density" : "density";
            int width = value.has(widthKey) ? value.getInt(widthKey) : 0;
            int height = value.has(heightKey) ? value.getInt(heightKey) : 0;
            int density = value.has(densityKey) ? value.getInt(densityKey) : -1;
            return new Cfg(width, height, density, value.optBoolean("applyThird", true),
                    value.optBoolean("applySystem", false));
        } catch (Throwable t) {
            log("invalid default configuration", t);
            return new Cfg(0, 0, -1, true, false);
        }
    }

    static Cfg appConfig(String pkg, boolean dock) {
        if (pkg == null) return null;
        JSONObject apps = configSnapshot().root.optJSONObject("apps");
        JSONObject value = apps == null ? null : apps.optJSONObject(pkg);
        if (value == null || value.optBoolean("disabled", false)) return null;
        try {
            String widthKey = dock ? "near_w" : "w";
            String heightKey = dock ? "near_h" : "h";
            String densityKey = dock ? "near_density" : "density";
            // Older entries only had w/h; keep them working for Dock until explicitly split.
            if (dock && (!value.has(widthKey) || !value.has(heightKey))) {
                widthKey = "w";
                heightKey = "h";
                densityKey = "density";
            }
            int width = value.getInt(widthKey);
            int height = value.getInt(heightKey);
            int density = value.has(densityKey) ? value.getInt(densityKey) : -1;
            return ConfigSchema.isResolutionValid(width, height)
                    && (density < 0 || ConfigSchema.isDensityValid(density))
                    ? new Cfg(width, height, density, true, false) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** null preserves the target APK's native PICO metadata behavior. */
    static Boolean dockOverride(String pkg) {
        if (pkg == null) return null;
        JSONObject apps = configSnapshot().root.optJSONObject("apps");
        JSONObject app = apps == null ? null : apps.optJSONObject(pkg);
        return app != null && app.has("dock") ? app.optBoolean("dock") : null;
    }

    static Boolean isSystemApp(Object container) {
        if (container == null) return null;
        try {
            java.lang.reflect.Method method = container.getClass().getMethod("isSystemApp");
            method.setAccessible(true);
            Object result = method.invoke(container);
            return result instanceof Boolean ? (Boolean) result : null;
        } catch (Throwable t) {
            log("unable to classify app record", t);
            return null;
        }
    }

    static String pkgFromName(String name) {
        if (name == null || !name.startsWith("NS_APP[")) return null;
        int end = name.indexOf(']');
        if (end < 0) return null;
        String inner = name.substring("NS_APP[".length(), end);
        return inner.isEmpty() ? null : inner;
    }

    static String fieldString(Object object, String field) {
        try { return (String) XposedHelpers.getObjectField(object, field); }
        catch (Throwable t) { return null; }
    }

    static String pkgFromThis(Object object) {
        try {
            Object componentName = XposedHelpers.getObjectField(object, "mComponentName");
            return componentName == null ? null : (String) componentName.getClass()
                    .getMethod("getPackageName").invoke(componentName);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Returns null when this AppRecord should not be resized. */
    static Cfg decide(String pkg, Object appRecord) {
        if ("com.picoxr.resfix".equals(pkg)) return null;
        Boolean dock = dockOverride(pkg);
        Cfg app = appConfig(pkg, Boolean.TRUE.equals(dock));
        if (app != null) return app;
        Cfg global = defaultConfig(Boolean.TRUE.equals(dock));
        if (!ConfigSchema.isResolutionValid(global.w, global.h)) return null;
        Boolean system = isSystemApp(appRecord);
        if (system == null) return null;
        if (system && !global.applySystem) return null;
        if (!system && !global.applyThird) return null;
        return global;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!"com.picovr.systemext".equals(lp.packageName)) return;
        installResolutionHook(lp);
        installDockHooks(lp);
    }

    private static void installResolutionHook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> appContainer = XposedHelpers.findClass(
                    "com.bytedance.nativeshell.appmanager.AppContainer", lp.classLoader);
            XposedHelpers.findAndHookMethod(appContainer, "createVirtualDisplay",
                    String.class, int.class, int.class, int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String name = (String) param.args[0];
                            String pkg = pkgFromName(name);
                            if (pkg == null) return;
                            Cfg cfg = decide(pkg, param.thisObject);
                            if (cfg == null) return;
                            param.args[1] = cfg.w;
                            param.args[2] = cfg.h;
                            if (cfg.density > 0) param.args[3] = cfg.density;
                            try {
                                XposedHelpers.setIntField(param.thisObject, "mWidth", cfg.w);
                                XposedHelpers.setIntField(param.thisObject, "mHeight", cfg.h);
                                if (cfg.density > 0) XposedHelpers.setIntField(param.thisObject, "mDensity", cfg.density);
                            } catch (Throwable t) {
                                log("failed to update AppContainer dimensions", t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": installed resolution hook");
        } catch (Throwable t) {
            log("failed to install resolution hook", t);
        }
    }

    private static void installDockHooks(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> activityInfo = XposedHelpers.findClass("android.content.pm.ActivityInfo", lp.classLoader);
            Class<?> appManagerUtils = XposedHelpers.findClass(
                    "com.bytedance.nativeshell.appmanager.AppManagerUtils", lp.classLoader);
            Class<?> appRecord = XposedHelpers.findClass(
                    "com.bytedance.nativeshell.appmanager.AppRecord", lp.classLoader);
            Class<?> appContainer = XposedHelpers.findClass(
                    "com.bytedance.nativeshell.appmanager.AppContainer", lp.classLoader);
            XC_MethodHook windowTypeHook = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        String pkg = fieldString(param.args[0], "packageName");
                        Boolean dock = dockOverride(pkg);
                        if (dock != null && !"com.picoxr.resfix".equals(pkg)) {
                            param.setResult(dock ? 2002 : 3002);
                            XposedBridge.log(TAG + ": route " + pkg + " -> " + (dock ? 2002 : 3002));
                        }
                    } catch (Throwable t) {
                        log("window type callback failed", t);
                    }
                }
            };
            hook("AppManagerUtils.getWindowType", () -> XposedHelpers.findAndHookMethod(
                    appManagerUtils, "getWindowType", activityInfo, windowTypeHook));
            hook("AppRecord.getWindowType", () -> XposedHelpers.findAndHookMethod(
                    appRecord, "getWindowType", activityInfo, windowTypeHook));
            hook("AppRecord.prepareAppData", () -> XposedHelpers.findAndHookMethod(
                    appRecord, "prepareAppData", "android.content.Context", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Boolean dock = dockOverride(pkgFromThis(param.thisObject));
                                if (dock != null) XposedHelpers.setObjectField(
                                        param.thisObject, "mAppResizeable", dock);
                            } catch (Throwable t) {
                                log("prepareAppData callback failed", t);
                            }
                        }
                    }));
            hook("AppRecord.resizeable", () -> XposedHelpers.findAndHookMethod(
                    appRecord, "resizeable", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Boolean dock = dockOverride(pkgFromThis(param.thisObject));
                                if (dock != null) param.setResult(dock);
                            } catch (Throwable t) {
                                log("resizeable callback failed", t);
                            }
                        }
                    }));
            hook("AppContainer.updateVisible", () -> XposedHelpers.findAndHookMethod(
                    appContainer, "updateVisible", boolean.class, int.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args.length >= 2 && param.args[0] instanceof Boolean
                                        && param.args[1] instanceof Integer
                                        && !(Boolean) param.args[0] && (Integer) param.args[1] == 6
                                        && Boolean.TRUE.equals(dockOverride(pkgFromThis(param.thisObject)))) {
                                    param.setResult(false);
                                }
                            } catch (Throwable t) {
                                log("updateVisible callback failed", t);
                            }
                        }
                    }));
        } catch (Throwable t) {
            log("failed to resolve dock hook classes", t);
        }
    }

    private interface HookInstall {
        void install() throws Throwable;
    }

    private static void hook(String name, HookInstall install) {
        try {
            install.install();
            XposedBridge.log(TAG + ": installed " + name);
        } catch (Throwable t) {
            log("failed to install " + name, t);
        }
    }

    private static void log(String message, Throwable error) {
        XposedBridge.log(TAG + ": " + message + (error == null ? "" : " (" + error + ")"));
    }
}
