package com.picoxr.resfix;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * ResFix hook — per-app virtual-display resolution and dock-mode override for PICO 4.
 *
 * We hook AppContainer.createVirtualDisplay(String,int,int,int,int) in
 * com.picovr.systemext, invoked by AppRecord as createVirtualDisplay("NS_APP[<pkg>]",
 * this.mWidth, this.mHeight, this.mDensity, flags).
 *
 * To avoid the "right-side clipping" caused by overriding only the w/h ARGS (which leaves
 * AppRecord.mScale/mWidth/mHeight inconsistent), we override BOTH:
 *   - the call ARGS (w,h,density)  -> virtual display buffer is created at the target res
 *   - the this-object FIELDS (mWidth,mHeight,mDensity) -> SystemExt's own calculateScale(900/h)
 *     and later resizeSurface() stay consistent, so the on-screen window keeps ~1600x900
 *     physical size while rendering the higher-res buffer (supersample, no clipping).
 *
 * Config: /data/local/tmp/resfix.cfg (JSON)
 *   { "default": { "w":1920,"h":1080,"density":200,"applyThird":true,"applySystem":false },
 *     "apps":    { "<pkg>": { "w":2560,"h":1440,"density":240,"dock":true } } }
 *
 * Dock mode reproduces the SystemExt-visible parts of Pico2Dock's manifest patch at runtime:
 * near-panel routing (type 2002), native 900 x 600 dp layout, resizable-panel support, and
 * persistence while a fullscreen app is shown.
 * It deliberately does not mark the target as a VR app: pvr.2dtovr.mode is consumed outside
 * this Java launch path and treating a normal Android activity as VR would be unsafe.
 */
public class ResFix implements IXposedHookLoadPackage {

    static final String TAG = "PicoResFix";
    static final String CONFIG = "/data/local/tmp/resfix.cfg";

    static final class Cfg {
        int w, h, density = -1;
        boolean applyThird = true, applySystem = false;
    }

    private static String readConfigText() {
        try {
            File f = new File(CONFIG);
            if (!f.exists()) return null;
            FileInputStream in = new FileInputStream(f);
            byte[] data = new byte[(int) f.length()];
            int n = in.read(data);
            in.close();
            return new String(data, 0, n, StandardCharsets.UTF_8);
        } catch (Throwable t) { return null; }
    }

    static Cfg defaultConfig() {
        Cfg c = new Cfg();
        try {
            String s = readConfigText();
            if (s == null) return c;
            JSONObject root = new JSONObject(s);
            if (root.has("default")) {
                JSONObject d = root.getJSONObject("default");
                c.w = d.optInt("w", 0);
                c.h = d.optInt("h", 0);
                c.density = d.has("density") ? d.getInt("density") : -1;
                c.applyThird = d.optBoolean("applyThird", true);
                c.applySystem = d.optBoolean("applySystem", false);
            }
        } catch (Throwable ignored) {}
        return c;
    }

    static Cfg appConfig(String pkg) {
        try {
            String s = readConfigText();
            if (s == null) return null;
            JSONObject root = new JSONObject(s);
            if (!root.has("apps")) return null;
            JSONObject apps = root.getJSONObject("apps");
            if (!apps.has(pkg)) return null;
            JSONObject a = apps.getJSONObject(pkg);
            if (a.optBoolean("disabled", false)) return null;
            Cfg c = new Cfg();
            c.w = a.optInt("w", 0);
            c.h = a.optInt("h", 0);
            c.density = a.has("density") ? a.getInt("density") : -1;
            if (c.w <= 0 || c.h <= 0) return null;
            return c;
        } catch (Throwable ignored) { return null; }
    }

    /**
     * Per-app window mode override.
     * null means keep the target APK's native PICO metadata behavior; true selects Dock and
     * false selects the normal far floating type (3002). A missing "dock" key remains
     * backwards-compatible as no override.
     */
    static Boolean dockOverride(String pkg) {
        if (pkg == null) return null;
        try {
            String s = readConfigText();
            if (s == null) return null;
            JSONObject apps = new JSONObject(s).optJSONObject("apps");
            JSONObject app = apps != null ? apps.optJSONObject(pkg) : null;
            if (app == null || !app.has("dock")) return null;
            return app.optBoolean("dock");
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean isNonSystemApp(Object container) {
        if (container == null) return true;
        try {
            java.lang.reflect.Method m = container.getClass().getMethod("isSystemApp");
            m.setAccessible(true);
            return !((Boolean) m.invoke(container));
        } catch (Throwable t) { return true; }
    }

    static String pkgFromName(String name) {
        if (name == null || !name.startsWith("NS_APP[")) return null;
        int end = name.indexOf(']');
        if (end < 0) return null;
        String inner = name.substring("NS_APP[".length(), end);
        return inner.isEmpty() ? null : inner;
    }

    static String fieldString(Object o, String f) {
        try { return (String) XposedHelpers.getObjectField(o, f); }
        catch (Throwable t) { return null; }
    }

    /** Fallback pkg from this-object component name. */
    static String pkgFromThis(Object o) {
        try {
            Object cn = XposedHelpers.getObjectField(o, "mComponentName");
            if (cn != null) {
                String p = (String) cn.getClass().getMethod("getPackageName").invoke(cn);
                if (p != null) return p;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Decide target config. Returns null when this AppRecord should NOT be resized.
     */
    static Cfg decide(String pkg, Object appRecord) {
        if ("com.picoxr.resfix".equals(pkg)) return null;
        boolean sys = !isNonSystemApp(appRecord);
        Cfg app = (pkg != null) ? appConfig(pkg) : null;
        if (app != null) return app;
        Cfg glob = defaultConfig();
        if (sys && !glob.applySystem) return null;
        if (!sys && !glob.applyThird) return null;
        if (glob.w <= 0 || glob.h <= 0) return null;
        return glob;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (lp.packageName == null || !"com.picovr.systemext".equals(lp.packageName)) return;
        try {
            Class<?> activityInfo = XposedHelpers.findClass("android.content.pm.ActivityInfo", lp.classLoader);
            Class<?> appManagerUtils = XposedHelpers.findClass(
                    "com.bytedance.nativeshell.appmanager.AppManagerUtils", lp.classLoader);

            // AppRecord construction resolves the window type through this method. Returning 2002
            // before the record is built routes the app into the native near-panel Dock stack
            // without changing its installed package metadata.
            XposedHelpers.findAndHookMethod(appManagerUtils, "getWindowType", activityInfo,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object info = param.args[0];
                            String pkg = fieldString(info, "packageName");
                            if ("com.picoxr.resfix".equals(pkg)) return;
                            Boolean dock = dockOverride(pkg);
                            if (dock == null) return;
                            param.setResult(dock ? 2002 : 3002);
                            XposedBridge.log(TAG + ": " + pkg + " -> "
                                    + (dock ? "Dock (type 2002)" : "Floating (type 3002)"));
                        }
                    });

            Class<?> appRecord = XposedHelpers.findClass(
                    "com.bytedance.nativeshell.appmanager.AppRecord", lp.classLoader);

            // ActivityStarterControl reaches this sibling resolver via isNearPanel(). Hook it as
            // well so a Dock app is immediately allowed while an immersive VR activity is active,
            // matching the native near-panel policy rather than merely changing its final layer.
            XposedHelpers.findAndHookMethod(appRecord, "getWindowType", activityInfo,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object info = param.args[0];
                            String pkg = fieldString(info, "packageName");
                            if ("com.picoxr.resfix".equals(pkg)) return;
                            Boolean dock = dockOverride(pkg);
                            if (dock != null) param.setResult(dock ? 2002 : 3002);
                        }
                    });

            // Pico2Dock writes android:resizeableActivity="true" into every activity. SystemExt
            // records the parsed flag in mAppResizeable; keep that state true for runtime-Docked
            // apps so panel resize affordances do not depend on the original APK manifest.
            XposedHelpers.findAndHookMethod(appRecord, "prepareAppData", "android.content.Context",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String pkg = pkgFromThis(param.thisObject);
                            Boolean dock = dockOverride(pkg);
                            if (dock == null) return;
                            try {
                                XposedHelpers.setObjectField(param.thisObject, "mAppResizeable", dock);
                            } catch (Throwable ignored) {}
                        }
                    });
            XposedHelpers.findAndHookMethod(appRecord, "resizeable", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Boolean dock = dockOverride(pkgFromThis(param.thisObject));
                    if (dock != null) param.setResult(dock);
                }
            });

            Class<?> appContainer = XposedHelpers.findClass(
                    "com.bytedance.nativeshell.appmanager.AppContainer", lp.classLoader);

            // A NoNavigationBar/fullscreen AppRecord normally hides every visible 2002 panel
            // through updateVisible(false, VISIBLE_CHANGE_BY_HIDE_BY_FULLSCREEN_SHOW == 6).
            // Pico2Dock's custom-panel path remains usable in fullscreen content, so preserve
            // only configured Dock records for that specific reason. User closes, Home, screen
            // state, seethrough, and every non-Dock panel keep the stock visibility policy.
            XposedHelpers.findAndHookMethod(appContainer, "updateVisible", boolean.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean visible = (Boolean) param.args[0];
                            int changeType = (Integer) param.args[1];
                            String pkg = pkgFromThis(param.thisObject);
                            if (!visible && changeType == 6 && Boolean.TRUE.equals(dockOverride(pkg))) {
                                XposedBridge.log(TAG + ": keep Dock visible during fullscreen " + pkg);
                                param.setResult(false);
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(appContainer, "createVirtualDisplay",
                    String.class, int.class, int.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String name = (String) param.args[0];
                            String pkg = pkgFromName(name);
                            if (pkg == null) return;   // not a flat 2D app (e.g. NS_WINDOW_/caption)

                            Cfg cfg = decide(pkg, param.thisObject);
                            if (cfg == null || cfg.w <= 0 || cfg.h <= 0) return;

                            int ow = (Integer) param.args[1];
                            int oh = (Integer) param.args[2];
                            int od = (Integer) param.args[3];

                            // override call args (virtual display buffer)
                            param.args[1] = cfg.w;
                            param.args[2] = cfg.h;
                            if (cfg.density > 0) param.args[3] = cfg.density;

                            // override this-object fields so mScale + later resizeSurface stay consistent
                            try {
                                XposedHelpers.setIntField(param.thisObject, "mWidth", cfg.w);
                                XposedHelpers.setIntField(param.thisObject, "mHeight", cfg.h);
                                if (cfg.density > 0) {
                                    XposedHelpers.setIntField(param.thisObject, "mDensity", cfg.density);
                                }
                            } catch (Throwable ignored) {}

                            int nd = (Integer) param.args[3];
                            boolean sys = !isNonSystemApp(param.thisObject);
                            XposedBridge.log(TAG + ": " + name + " " + (sys ? "[sys]" : "[3rd]")
                                    + " " + ow + "x" + oh + "@" + od + " -> " + cfg.w + "x" + cfg.h + "@" + nd
                                    + " (mScale-consistent)");
                        }
                    });
            XposedBridge.log(TAG + ": installed (per-app resolution + Dock mode)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook failed");
            XposedBridge.log(t);
        }
    }
}
