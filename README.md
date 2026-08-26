[English](README.md) | [简体中文](README_zh.md) | [Русский](README_ru.md)

# PICO 2D Resolution — Per-App Virtual-Display Resolution Mod

A resolution unlocking module for 2D flat applications on **PICO 4 Standard Edition (A8110, Android 10 / API 29)**.
Allows **per-app configuration** of 2D virtual display resolution (no longer locked at 1600×900), custom DPI, and switching specified apps from far-field floating windows to near-field Dock.

Implemented by injecting into `com.picovr.systemext` using **Zygisk Vector (LSPosed compatible framework)** — no system APK replacement required.

**Current release: v1.16**

---

## Prerequisites

- **Device**: Pico 4 Headset (Phoenix/China firmware supported).
- **Permissions**: **Root Access** ([Guide](https://pico4.wiki/guides/root/01-root/)) is required to apply changes to system files.
- **Environment**: **Magisk** + **LSPosed Framework** ([Zygisk Vector](https://github.com/JingMatrix/Vector)) must be installed and active.
- **LSPosed Scope**: Ensure `com.picovr.systemext` is selected in the module scope.

---

## 1. Features

| Item | Original | Unlocked |
|---|---|---|
| 2D App Virtual Display Resolution | 1602×902 (density 200) | **Per-app configuration** (Default 2560×1440) |
| DPI | Fixed at 200 | **Overridable per-app** (Optional) |
| Window Mode | APK-defined Floating or Dock | **Force Floating or Near-field Dock per app** |
| Scope | All 2D Apps | **Enabled by default for non-system apps, optional for system apps** |

- Only changes resolution (px) + optional DPI. **Without changing density, it results in supersampling, keeping the aspect ratio unchanged.**
- Independent configuration per app, no mutual interference.

---

## 2. Architecture

```
PICO 4 (Android 10, API 29)
└─ Magisk + Zygisk Vector v2.2 (LSPosed compatible framework)
   └─ This Module com.picoxr.resfix (mid)
       ├─ LSPosed hook: ResFix
       │    hooks com.picovr.systemext's
       │    AppContainer.createVirtualDisplay(String,int,int,int,int)
       │    → Parses "NS_APP[<pkg>]" → Checks config by package name → Overrides w/h(/density)
       │    AppManagerUtils.getWindowType(ActivityInfo)
       │    → Returns "near" (type 2002) for Dock apps by package name
       └─ GUI: AppListActivity + AppDetailActivity
            (App list + per-app resolution settings)

Config path: /data/local/tmp/resfix.cfg (JSON)
  - Written by GUI App (root)
  - Read by ResFix through a validated, versioned configuration snapshot
```

---

## 3. Why hook instead of replacing APK?

- `SystemExt` is a PERSISTENT system app with `sharedUserId="android.uid.system"`. Replacing the APK with a self-signed one would be rejected by PackageManager. Thus, LSPosed hook is used.

---

## 4. Config Format (/data/local/tmp/resfix.cfg)

```json
{
  "default": { "w": 2560, "h": 1440, "density": 200,
               "applyThird": true, "applySystem": false },
  "apps": {
    "com.example.app": { "w": 1920, "h": 1080, "density": 240, "dock": true }
  }
}
```

- `default`: Uniform resolution for non-configured non-system apps (when applyThird=true).
- `apps.<pkg>`: Individual override for an app (use `"disabled": true` to disable). `w/h/density` configure Floating mode; `near_w/near_h/near_density` configure Dock mode. Both sets are retained, so changing one mode does not erase the other.
- `apps.<pkg>.dock`: `true` routes the app to the native Near-field Dock (type 2002); `false` forces the normal far floating window (type 3002), including apps previously converted by Pico2Dock. The active mode selects its own resolution set.
- `density` omitted = Follow system; do not write `-1` as a JSON value.
- Width must be 320–7680 px, height 240–4320 px, and density 72–1000. Invalid, incomplete, or wrongly typed entries are rejected before they reach SystemExt.
- A configuration may contain at most 500 app entries and must not exceed 256 KiB. If `Settings.Global` has invalid content, the hook falls back to the config file; if both sources fail, it keeps the last valid configuration.

After changing a per-app setting, use **Apply & Restart App** to save it and restart the selected target app. The plain **Save** button writes the configuration without restarting it. Default settings have no single target app, so they still require restarting affected apps manually.

---

## 5. Build (CLI only)

Requirements: JDK 17 + Android SDK (platform 34, build-tools 34). Use the checked-in Gradle Wrapper; it selects the project Gradle version.

```bash
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

---

## 6. Deployment

1. `adb install -r app-debug.apk`
2. Update LSPosed module database `apk_path`
3. `adb reboot` (Vector rescans modules)
4. Open "PICO 2D Resolution" on Home → App List → Tap an app to configure its resolution and window mode
5. Use **Apply & Restart App** to save and immediately restart that app

### 批量更改

主界面右上角菜单里的 `Batch Import` 支持一次导入多项配置。直接粘贴 JSON：

```json
{
  "default": {
    "w": 2560,
    "h": 1440,
    "density": 200,
    "applyThird": true,
    "applySystem": false
  },
  "apps": {
    "com.example.app1": { "w": 1920, "h": 1080, "density": 240, "dock": true },
    "com.example.app2": { "w": 2560, "h": 1440, "disabled": false },
    "com.example.app3": { "disabled": true }
  }
}
```

同名包会直接覆盖原有项；`default` 会替换全局默认项。

---

## 7. Versioning

Android `versionName`, Git tag, GitHub Release title, and release APK filename use the same version number. For example, v1.16 is published as tag `1.16` with `Pico-ResFix-v1.16.apk`.
