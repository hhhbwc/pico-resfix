package com.picoxr.resfix;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

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
    private static final ThreadLocal<String> launchingPackage = new ThreadLocal<>();

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

    static Boolean dockOverride(String pkg) {
        if (pkg == null) return null;
        JSONObject apps = configSnapshot().root.optJSONObject("apps");
        JSONObject app = apps == null ? null : apps.optJSONObject(pkg);
        return app != null && app.has("dock") ? app.optBoolean("dock") : null;
    }

    static Boolean isSystemApp(Object container) {
        if (container == null) return null;
        try {
            Method method = container.getClass().getMethod("isSystemApp");
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
        if (object == null) return null;
        try {
            // Try getPackageName() method
            try {
                Method m = object.getClass().getMethod("getPackageName");
                m.setAccessible(true);
                String pkg = (String) m.invoke(object);
                if (pkg != null && !pkg.isEmpty()) return pkg;
            } catch (Throwable ignored) {}

            // Try mPackageName field
            String pkgField = fieldString(object, "mPackageName");
            if (pkgField != null && !pkgField.isEmpty()) return pkgField;

            // Fallback to mComponentName
            Object componentName = XposedHelpers.getObjectField(object, "mComponentName");
            if (componentName != null) {
                return (String) componentName.getClass().getMethod("getPackageName").invoke(componentName);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    static boolean nativeDockMode(String pkg, Object appRecord) {
        try {
            Context context = systemContext();
            if (context == null || pkg == null) return false;
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(
                    pkg, PackageManager.GET_META_DATA);
            return Config.isAppDockMode(pm, pkg, ai);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Returns null when this AppRecord should not be resized. */
    static Cfg decide(String pkg, Object appRecord) {
        if ("com.picoxr.resfix".equals(pkg)) return null;
        Boolean dock = dockOverride(pkg);
        boolean effectiveDock = dock != null ? dock : nativeDockMode(pkg, appRecord);
        Cfg app = appConfig(pkg, effectiveDock);
        if (app != null) return app;
        Cfg global = defaultConfig(effectiveDock);
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
        installActivityStarterHooks(lp);
    }

    private static void installActivityStarterHooks(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> control = XposedHelpers.findClass(
                    "com.bytedance.nativeshell.appmanager.ActivityStarterControl", lp.classLoader);
            Class<?> utils = XposedHelpers.findClass(
                    "com.bytedance.nativeshell.appmanager.util.ActivityStarterControlUtils", lp.classLoader);

            Log.e(TAG, "Dumping methods for ActivityStarterControl:");
            for (Method method : control.getDeclaredMethods()) {
                Log.e(TAG, "  " + method.toString());
            }

            Log.e(TAG, "Dumping methods for ActivityStarterControlUtils:");
            for (Method method : utils.getDeclaredMethods()) {
                Log.e(TAG, "  " + method.toString());
            }

            XposedBridge.hookAllMethods(control, "handleStartActivity", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Log.e(TAG, "ActivityStarterControl.handleStartActivity called: " + Arrays.toString(param.args));
                }
            });

            XposedBridge.hookAllMethods(control, "checkAppSwitchAllowed", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Log.e(TAG, "ActivityStarterControl.checkAppSwitchAllowed called: " + Arrays.toString(param.args));
                }
            });

        } catch (Throwable t) {
            Log.e(TAG, "Failed to install ActivityStarter hooks", t);
        }
    }

    private static void installResolutionHook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> appContainer = XposedHelpers.findClass(
                    "com.bytedance.nativeshell.appmanager.AppContainer", lp.classLoader);
            // Signature: (String name, int w, int h, int density, int flags)
            XposedHelpers.findAndHookMethod(appContainer, "createVirtualDisplay",
                    String.class, int.class, int.class, int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String name = (String) param.args[0];
                                String pkg = pkgFromName(name);
                                if (pkg == null) return;
                                Cfg cfg = decide(pkg, param.thisObject);
                                if (cfg == null) return;

                                int targetW = cfg.w, targetH = cfg.h;
                                int targetDensity = cfg.density > 0 ? cfg.density : currentDensity(param.thisObject);
                                param.args[1] = targetW;
                                param.args[2] = targetH;
                                param.args[3] = targetDensity;

                                setInt(param.thisObject, "mWidth", targetW, true);
                                setInt(param.thisObject, "mHeight", targetH, true);
                                if (cfg.density > 0) setInt(param.thisObject, "mDensity", targetDensity, false);

                                Log.e(TAG, "route " + pkg + " -> " + targetW + "x" + targetH + "@" + targetDensity);
                            } catch (Throwable t) {
                                log("createVirtualDisplay hook failed", t);
                            }
                        }
                    });
        } catch (Throwable t) {
            log("failed to install resolution hook", t);
        }
        installAppRecordFix(lp);
    }

    private static void installAppRecordFix(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> appRecord = XposedHelpers.findClass(
                    "com.bytedance.nativeshell.appmanager.AppRecord", lp.classLoader);
            XposedHelpers.findAndHookMethod(appRecord, "prepareAppData", Context.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    String pkg = pkgFromThis(param.thisObject);
                    if (pkg != null) launchingPackage.set(pkg);
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object container = param.thisObject;
                        String pkg = pkgFromThis(container);
                        if (pkg == null) pkg = launchingPackage.get();
                        if (pkg == null) return;

                        Cfg cfg = decide(pkg, container);
                        if (cfg != null) {
                            setInt(container, "mWidth", cfg.w, false);
                            setInt(container, "mHeight", cfg.h, false);
                        }

                        Boolean dock = dockOverride(pkg);
                        if (dock != null) {
                            XposedHelpers.setObjectField(container, "mAppResizeable", dock);
                            trySetIntField(container, "mWindowType", dock ? 2002 : 3002);
                            trySetIntField(container, "mType", dock ? 2002 : 3002);
                        }
                    } catch (Throwable t) {
                        log("AppRecord.prepareAppData hook failed", t);
                    } finally {
                        launchingPackage.remove();
                    }
                }
            });
        } catch (Throwable t) {
            log("failed to install AppRecord fix", t);
        }
    }

    private static int currentDensity(Object container) {
        try {
            return XposedHelpers.getIntField(container, "mDensity");
        } catch (Throwable t) {
            return 200;
        }
    }

    private static void setInt(Object obj, String field, int value, boolean required) {
        try {
            XposedHelpers.setIntField(obj, field, value);
        } catch (Throwable t) {
            if (required) log("failed to set required field " + field, t);
        }
    }

    private static void installDockHooks(XC_LoadPackage.LoadPackageParam lp) {
        // Dump methods for RootAppContainer
        try {
            Class<?> rootAppContainer = XposedHelpers.findClass("com.bytedance.nativeshell.appmanager.RootAppContainer", lp.classLoader);
            if (rootAppContainer != null) {
                Log.e(TAG, "Dumping methods for RootAppContainer:");
                for (Method method : rootAppContainer.getDeclaredMethods()) {
                    Log.e(TAG, "  " + method.toString());
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to dump methods for RootAppContainer", t);
        }

        final Class<?> activityInfo;
        final Class<?> appManagerService;
        final Class<?> appManagerUtils;
        final Class<?> immersiveModeManager;
        final Class<?> appRecord;
        final Class<?> appContainer;

        try {
            activityInfo = XposedHelpers.findClass("android.content.pm.ActivityInfo", lp.classLoader);
            appManagerService = XposedHelpers.findClass("com.bytedance.nativeshell.appmanager.AppManagerService", lp.classLoader);
            appManagerUtils = XposedHelpers.findClass("com.bytedance.nativeshell.appmanager.AppManagerUtils", lp.classLoader);
            immersiveModeManager = XposedHelpers.findClass("com.bytedance.nativeshell.appmanager.action.ImmersiveModeManager", lp.classLoader);
            appRecord = XposedHelpers.findClass("com.bytedance.nativeshell.appmanager.AppRecord", lp.classLoader);
            appContainer = XposedHelpers.findClass("com.bytedance.nativeshell.appmanager.AppContainer", lp.classLoader);
        } catch (Throwable t) {
            Log.e(TAG, "failed to resolve dock hook classes", t);
            return;
        }

        // Hook AppManagerService.notifyAllowAppStart and see if it's called
        try {
            XposedBridge.hookAllMethods(appManagerService, "notifyAllowAppStart", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Log.e(TAG, "notifyAllowAppStart called with args: " + Arrays.toString(param.args));
                        if (param.args.length > 0 && param.args[0] != null) {
                            String pkg = fieldString(param.args[0], "packageName");
                            if (Boolean.TRUE.equals(dockOverride(pkg))) {
                                param.setResult(true);
                                Log.e(TAG, "Forced allow start for docked app via notifyAllowAppStart: " + pkg);
                            }
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "Error in notifyAllowAppStart hook", t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook notifyAllowAppStart", t);
        }

        try {
            XposedBridge.hookAllMethods(immersiveModeManager, "onAllowStartApp", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length > 0 && param.args[0] != null) {
                            String pkg = fieldString(param.args[0], "packageName");
                            if (Boolean.TRUE.equals(dockOverride(pkg))) {
                                param.setResult(true);
                                Log.e(TAG, "Forced allow start for docked app via onAllowStartApp: " + pkg);
                            }
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "Error in onAllowStartApp hook", t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook onAllowStartApp", t);
        }

        // VR hooks
        try {
            XC_MethodHook vrHook = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length > 0 && param.args[0] != null) {
                            String pkg = fieldString(param.args[0], "packageName");
                            if (Boolean.TRUE.equals(dockOverride(pkg))) {
                                param.setResult(false);
                                Log.e(TAG, "isVr(App/Activity): forced to false for docked app: " + pkg);
                            }
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "Error in VR hook", t);
                    }
                }
            };
            XposedBridge.hookAllMethods(appManagerUtils, "isVrActivity", vrHook);
            XposedBridge.hookAllMethods(appManagerUtils, "isVrApp", vrHook);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook VR methods", t);
        }

        // Hook AppManagerUtils.isNearPanel and ensure it returns true
        try {
            XposedBridge.hookAllMethods(appManagerUtils, "isNearPanel", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        String pkg = null;
                        if (param.args.length > 0 && param.args[0] != null) {
                            pkg = fieldString(param.args[0], "packageName");
                        }
                        if (pkg == null) pkg = launchingPackage.get();
                        if (Boolean.TRUE.equals(dockOverride(pkg))) {
                            param.setResult(true);
                            Log.e(TAG, "isNearPanel: forced to true for docked app: " + pkg);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "Error in isNearPanel hook", t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook isNearPanel", t);
        }

        // Hook AppManagerUtils.getVrSpacePosition(ActivityInfo) and return "near" for docked apps
        try {
            XposedBridge.hookAllMethods(appManagerUtils, "getVrSpacePosition", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        String pkg = null;
                        if (param.args.length > 0 && param.args[0] != null) {
                            pkg = fieldString(param.args[0], "packageName");
                        }
                        if (pkg == null) pkg = launchingPackage.get();
                        if (Boolean.TRUE.equals(dockOverride(pkg))) {
                            param.setResult("near");
                            Log.e(TAG, "AppManagerUtils.getVrSpacePosition: forced to 'near' for docked app: " + pkg);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "Error in AppManagerUtils.getVrSpacePosition hook", t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook AppManagerUtils.getVrSpacePosition", t);
        }

        // Hook AppRecord.getVrSpacePosition() if it exists
        try {
            XposedBridge.hookAllMethods(appRecord, "getVrSpacePosition", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        String pkg = pkgFromThis(param.thisObject);
                        if (pkg == null) pkg = launchingPackage.get();
                        if (Boolean.TRUE.equals(dockOverride(pkg))) {
                            param.setResult("near");
                            Log.e(TAG, "AppRecord.getVrSpacePosition: forced to 'near' for docked app: " + pkg);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "Error in AppRecord.getVrSpacePosition hook", t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook AppRecord.getVrSpacePosition", t);
        }

        // Suppress "Exit fullscreen" tips
        try {
            XC_MethodHook suppressTipsHook = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        String pkg = launchingPackage.get();
                        if (pkg != null && Boolean.TRUE.equals(dockOverride(pkg))) {
                            param.setResult(null);
                            Log.e(TAG, "Suppressed showImmersiveTips for docked app: " + pkg);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "Error in showImmersiveTips hook", t);
                    }
                }
            };
            XposedBridge.hookAllMethods(appManagerService, "showImmersiveTips", suppressTipsHook);
            XposedBridge.hookAllMethods(immersiveModeManager, "showImmersiveTips", suppressTipsHook);
        } catch (Throwable ignored) {}

        // Window type hooks
        try {
            XC_MethodHook windowTypeHook = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        String pkg = pkgFromThis(param.thisObject);
                        if (pkg == null && param.args.length > 0) {
                            Object arg0 = param.args[0];
                            if (arg0 instanceof String) {
                                String s = (String) arg0;
                                if (!"far".equals(s) && !"near".equals(s)) pkg = s;
                            } else if (arg0 != null) {
                                pkg = fieldString(arg0, "packageName");
                            }
                        }
                        if (pkg == null) pkg = launchingPackage.get();

                        if (pkg != null && !"com.picoxr.resfix".equals(pkg)) {
                            Boolean dock = dockOverride(pkg);
                            if (dock != null) {
                                int type = dock ? 2002 : 3002;
                                param.setResult(type);
                                Log.e(TAG, "route " + pkg + " -> " + type);
                            }
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "window type callback failed", t);
                    }
                }
            };

            String[] methodsToHook = {"getType", "getWindowType", "convertPositionToWindowType"};
            for (String methodName : methodsToHook) {
                XposedBridge.hookAllMethods(appRecord, methodName, windowTypeHook);
                XposedBridge.hookAllMethods(appManagerUtils, methodName, windowTypeHook);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook window type methods", t);
        }

        // AppRecord.obtain hook
        try {
            XposedHelpers.findAndHookMethod(appRecord, "obtain", Context.class, activityInfo, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        String pkg = fieldString(param.args[1], "packageName");
                        if (pkg != null) launchingPackage.set(pkg);
                    } catch (Throwable ignored) {}
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object record = param.getResult();
                        if (record == null) return;
                        String pkg = launchingPackage.get();
                        if (pkg == null) pkg = pkgFromThis(record);
                        Boolean dock = dockOverride(pkg);
                        if (Boolean.TRUE.equals(dock)) {
                            trySetIntField(record, "mType", 2002);
                            trySetIntField(record, "mWindowType", 2002);
                            XposedHelpers.setObjectField(record, "mAppResizeable", true);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "AppRecord.obtain hook failed", t);
                    } finally {
                        launchingPackage.remove();
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook AppRecord.obtain", t);
        }

        // Constructor hooks
        try {
            hookAllConstructors(appRecord, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        String pkg = pkgFromThis(param.thisObject);
                        if (pkg == null) pkg = launchingPackage.get();
                        Boolean dock = dockOverride(pkg);
                        if (Boolean.TRUE.equals(dock)) {
                            trySetIntField(param.thisObject, "mType", 2002);
                            trySetIntField(param.thisObject, "mWindowType", 2002);
                            XposedHelpers.setObjectField(param.thisObject, "mAppResizeable", true);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "AppRecord constructor hook failed", t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook constructors", t);
        }

        // resizeable hook
        try {
            XposedHelpers.findAndHookMethod(appRecord, "resizeable", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        String pkg = pkgFromThis(param.thisObject);
                        Boolean dock = dockOverride(pkg);
                        if (dock != null) param.setResult(dock);
                    } catch (Throwable t) {
                        Log.e(TAG, "resizeable hook failed", t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook resizeable", t);
        }

        // updateVisible hook
        try {
            XposedHelpers.findAndHookMethod(appContainer, "updateVisible", boolean.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length >= 2 && !(Boolean) param.args[0] && (Integer) param.args[1] == 6) {
                            String pkg = pkgFromThis(param.thisObject);
                            if (Boolean.TRUE.equals(dockOverride(pkg))) param.setResult(false);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "updateVisible hook failed", t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook updateVisible", t);
        }

        // Diagnostic hooks
        try {
            XposedBridge.hookAllMethods(appManagerService, "handleStartActivity", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Log.e(TAG, "BEFORE AppManagerService.handleStartActivity args: " + Arrays.toString(param.args));
                }
            });
            XposedBridge.hookAllMethods(immersiveModeManager, "updateImmersiveMode", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Log.e(TAG, "BEFORE ImmersiveModeManager.updateImmersiveMode args: " + Arrays.toString(param.args));
                }
            });
        } catch (Throwable ignored) {}
    }

    private static void hookAllConstructors(Class<?> clazz, XC_MethodHook hook) {
        try {
            XposedBridge.class.getMethod("hookAllConstructors", Class.class, XC_MethodHook.class)
                    .invoke(null, clazz, hook);
        } catch (Throwable ignored) {}
    }

    private static void trySetIntField(Object obj, String field, int value) {
        try { XposedHelpers.setIntField(obj, field, value); }
        catch (Throwable ignored) {}
    }

    private static void log(String message, Throwable error) {
        Log.e(TAG, message + (error == null ? "" : " (" + error + ")"));
    }
}
