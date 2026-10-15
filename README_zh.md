[English](README.md) | [简体中文](README_zh.md) | [Русский](README_ru.md)

# PICO 2D Resolution — Per-App Virtual-Display Resolution Mod

针对 **PICO 4 标准版 (A8110, Android 10 / API 29)** 的 2D 平面应用分辨率解锁模块。
允许**按应用单独设置** 2D 虚拟显示分辨率（不再锁定 1600×900）、自定义 DPI，并可将指定应用从远场浮窗切换至近场 Dock。

用 **Zygisk Vector (LSPosed 兼容框架)** 注入 `com.picovr.systemext` 实现 —— 无需替换系统 APK。

**当前版本：v1.4.2**

---

## 前提条件

- **设备**: PICO 4 头显 (支持 Phoenix/中国版固件)。
- **权限**: 需要 **Root 权限** ([教程](https://pico4.wiki/guides/root/01-root/)) 以应用系统级更改。
- **环境**: 必须安装并激活 **Magisk** 和 **LSPosed 框架** ([Zygisk Vector](https://github.com/JingMatrix/Vector))。
- **LSPosed 作用域**: 确保在 LSPosed 模块作用域中勾选了 `com.picovr.systemext`。

---

## 一、效果

| 项目 | 原厂 | 解锁后 |
|---|---|---|
| 2D 应用虚拟显示分辨率 | 1602×902 (density 200) | **按应用单独配置**（需在「默认设置」里显式配置才会生效） |
| DPI | 固定 200 | **可按应用覆盖**（可选） |
| 窗口模式 | APK 原生的浮窗或 Dock | **可按应用强制为普通浮窗或近场 Dock** |
| 作用范围 | 所有 2D 应用 | **非系统应用默认启用，系统应用可选** |

- 只改分辨率（px）+ 可选 DPI，**不改 density 时是超采样效果，画面比例不变**。
- 每应用独立配置，互不影响。

---

### 输入法（键盘专属屏，v1.4.2-ime.4）

键盘有专属虚拟屏（`NS_INPUT_METHOD`，固件硬编码 1045×460@200dpi），本模块同样将其解锁：

- 应用列表顶部固定的**「输入法」条目** → 选择已安装的输入法（当前默认的会标注）→ 按输入法独立配置分辨率，预设 1x / 1.5x / 2x / 3x（1045×460 / 1568×690 / 2090×920 / 3135×1380，密度随分辨率自动缩放）；
- 分辨率在**开机创建键盘屏时**应用：`"ime"` 段里保活目标（默认 fcitx5，可用 `"imeKeep"` 改）的条目优先于当前默认输入法的条目；**改动需重启头显生效**——键盘屏创建后中途 resize，窗口/表面链路仍停在创建尺寸，键盘会显示不全；
- 同时内置默认输入法保活（三层拦截把 `default_input_method` 改回讯飞的路径）；`persist.resfix.imekeep=0` 可关闭。

键盘**高度/比例**自适应请搭配独立模块 [fcitx5-pico/ime-module](https://github.com/hhhbwc/fcitx5-pico)（fcitx5 专用：保活 + 高度自适应）。

## 二、架构

```
PICO 4 (Android 10, API 29)
└─ Magisk + Zygisk Vector v2.2 (LSPosed 兼容框架)
   └─ 本模块 com.picoxr.resfix (mid)
       ├─ LSPosed hook: ResFix
       │    hook com.picovr.systemext 的
       │    AppContainer.createVirtualDisplay(String,int,int,int,int)
       │    → 解析 "NS_APP[<pkg>]" → 按包名查配置 → 覆盖 w/h(/density)
       │    AppManagerUtils.getWindowType(ActivityInfo)
       │    → 按包名将 Dock 应用返回为 near (type 2002)
       └─ GUI: AppListActivity + AppDetailActivity
            （应用列表 + 每应用分辨率设置）

配置通道: /data/local/tmp/resfix.cfg (JSON)
  - GUI App (root) 写入
  - ResFix hook 通过校验后的版本化配置快照读取
```

---

## 三、为什么 hook 而非替换 APK

- `SystemExt` 是 `sharedUserId="android.uid.system"` 的 PERSISTENT 系统应用，自签名替换 APK 会被 PackageManager 拒绝。故用 LSPosed hook。

---

## 四、配置文件格式 (/data/local/tmp/resfix.cfg)

```json
{
  "default": { "w": 2560, "h": 1440, "density": 200,
               "applyThird": true, "applySystem": false },
  "apps": {
    "com.example.app": { "w": 1920, "h": 1080, "density": 240, "dock": true }
  }
}
```

- `default`: 未单独配置的非系统应用（applyThird=true 时）统一用此分辨率。
- `apps.<pkg>`: 某应用的单独覆盖（禁用用 `"disabled": true`）。`w/h/density` 配置远场浮窗，`near_w/near_h/near_density` 配置近场 Dock；两套配置会同时保留，修改一种模式不会清除另一种模式。
- `apps.<pkg>.dock`: `true` 时将应用路由至原生近场 Dock（类型 2002）；`false` 时强制为普通远场浮窗（类型 3002），包括已被 Pico2Dock 转换过的应用。运行时会根据当前模式选择对应的分辨率配置。
- `density` 省略 = 跟随系统；不要在 JSON 中写入 `-1`。
- 宽度范围为 320–3840 px，高度范围为 240–2162 px，Density 范围为 72–1000。字段缺失、类型错误或超出范围的配置会在写入前被拒绝，不会传给 SystemExt。
- 配置最多包含 500 个应用条目，文件最大为 256 KiB。若 `Settings.Global` 内容无效，Hook 会回退读取配置文件；两个来源都无效时保留最后一份有效配置。

修改单个应用后，可用 **应用并重启 App** 保存并重启当前目标应用；普通 **保存** 只写入配置，不重启应用。全局默认配置没有单一目标应用，仍需手动重开受影响的应用。

---

## 五、构建

需要: JDK 17 + Android SDK (platform 34, build-tools 34)。使用项目内置的 Gradle Wrapper，它会选择本项目的 Gradle 版本。

```bash
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

---

## 六、部署

1. `adb install -r app-debug.apk`
2. 更新 LSPosed 模块数据库 apk_path
3. `adb reboot`（Vector 重新扫描模块）
4. 桌面打开 "PICO 2D Resolution" → 应用列表 → 点应用设置分辨率和窗口模式
5. 点 **应用并重启 App** 保存并立即重启该应用

---

## 七、版本命名

Android 的 `versionName`、Git tag、GitHub Release 标题和 Release APK 文件名统一使用同一版本号。例如 v1.4.2 对应 tag `v1.4.2` 和 `pico-resfix-v1.4.2.apk`。
