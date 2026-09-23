# Changelog

Release versions follow Android `versionName` and the matching Git tag.

## v1.4.2

A maintenance release that fixes bugs found while auditing the module after v1.4.1.
No configuration migration is required; old config files keep working.

### Fixed

**Hook (`ResFix`)**

- Global defaults could be skipped for an app whose container cannot be classified.
  `decide()` classified apps only by reflecting `isSystemApp()` on the hooked container, and bailed
  out whenever that probe returned nothing, leaving `applyThird` unenforced for such apps.
  PackageManager now answers by package name whenever the container cannot.
  *(Verified on device as hardening, not as an observed failure: see "Device verification" below.)*
- Five Dock routing overrides never fired. `isVrActivity`, `isVrApp`, `notifyAllowAppStart`,
  `onAllowStartApp` and `checkAppSwitchAllowed` resolved the package with
  `pkgFromFirstArg()`, which reads a `packageName` field that plain-`String` arguments do not
  have. All now use `pkgFromFirstArgOrLaunch()`, which also falls back to the launch context.
- One missing firmware class disabled every optional routing fix. The four internal classes were
  resolved in a single `try`, so a build without `ActivityStarterControlUtils` also lost
  `notifyAllowAppStart` and `onAllowStartApp`. Each class is now resolved independently.
- `AppRecord.obtain` hook installation could abort its own caller; wrapped defensively.
- Route logging was emitted on every `createVirtualDisplay` call, flooding logcat in a process
  that runs continuously. Logging now happens only when a package's route actually changes, and
  an unclassifiable app is reported once instead of per event.
- Native dock classification ran a PackageManager binder query per hook event without caching.
  Per-package results are cached and discarded when the configuration generation changes.
- `defaultConfig(dock=true)` did not fall back to legacy `w/h`, unlike `appConfig` and the GUI. A
  config written before Floating and Dock were separated lost its Dock default silently. Both
  sides now route through `ConfigSchema.resolutionKeys()`.

**Configuration (`Config`, `ConfigSchema`)**

- Removing the Default Settings entry also wiped unrelated preferences. `removeOverride()` dropped
  the whole `default` object, taking the four list filters (`show*`) and a hand-tuned
  `applyThird`/`applySystem` with it. Only the resolution keys are cleared now.
- Unconfigured child processes had unread pipes. `su` calls in `writeRoot()` and
  `publishCoordination()` never drained stdout/stderr, so a chatty child could block each save for
  the full 15 s timeout. Output is redirected to `/dev/null` through a shared helper.
- Writing configuration cost up to four sequential `su` calls, each able to block for 15 s, and
  all of them ran on the UI thread. Config access moved to a background executor.
- `applyBatchJson()` returned hard-coded English in its result strings; it now returns a
  structured result the UI localizes.
- Batch writes no longer leave a stale `density` behind when density is cleared.

**Interface**

- The app list showed "1602x902 @200" for every app even when no default was configured, because
  the editor's placeholder values were reported as if they were applied. Entries now show
  "Follow system" when the module will not resize them.
- The DPI field was pre-filled with `200`, which pinned an explicit density the user never chose
  as soon as the page was saved. It now stays blank to mean "follow system".
- Canceling the Batch Import dialog cleared the current multi-selection.
- Batch Import dialog text was hard-coded English for every locale.
- "Apply & Restart" could kill an app and fail to relaunch it: `am force-stop` ran before checking
  whether a launch intent existed. The intent is now resolved first.
- Duplicate privileged config reads per list refresh removed (one read now serves both the global
  values and the app list).

**Documentation**

- Corrected the documented resolution limits to the enforced ones (320–3840 x 240–2162).
- Removed the claim of a built-in 2560x1440 default; nothing is applied until a default is set.
- Unified the documented version/tag/APK naming with what is actually published.

### Device verification

Verified via adb on a PICO 4 (`A8110`, Android 10 / API 29, Magisk + zygisk-vector, with
`com.picovr.systemext` present), comparing v1.4.1 against v1.4.2 on the same device and the same
configuration.

| Check | v1.4.1 | v1.4.2 |
|---|---|---|
| List entry for an app with no default configured | `默认:1602x902 @200` (invented) | `跟随系统` |
| List entry once a default (1920x1082) is configured | `默认:1920x1082 @200` | `默认:1920x1082 @200` |
| Actual virtual display (`dumpsys display`) for that app | 1920 x 1082 | 1920 x 1082 |
| `route` log lines for 3 cold launches of one app | 3 (repeated) | 1 |

The list now agrees with what the hook actually does: with no default configured nothing is
resized and the list says so, and once a default exists the list value matches the measured
virtual display resolution. Route logging collapsed to a single line per changed route.

Notes from the run that affect interpretation:

- Global defaults already worked in v1.4.1 on this firmware, so the app-classification change above
  is hardening for firmware whose container does not expose `isSystemApp()`, rather than a fix for
  a failure reproduced here. No regression was observed either.
- No crash or ANR was seen in either GUI path; the list loaded asynchronously
  (`listApps returned 16 apps`) and the editor survived repeated saves and restarts.
- `systemext` cannot read `/data/local/tmp/resfix.cfg` (SELinux denies `system_app` access to
  `shell_data_file`), which is expected: the hook fell back to `Settings.Global` every time and
  applied the configuration correctly from there.

### Tests

Unit tests went from 18 to 26. Added `ResolutionKeysTest` for the shared key resolution and
legacy fallback, and `GlobalDefaultsTest` for the "is a default configured" flags and for
`clearDefaultResolution()` preserving unrelated preferences.

---

## v1.4.2 更新说明（中文）

一次维护性版本，修的是 v1.4.1 之后代码审查中发现的问题。无需迁移配置文件，旧配置继续可用。

**Hook 层**：修复全局默认对第三方应用完全不生效（`isSystemApp()` 反射失败时不兜底，导致 `applyThird` 形同虚设）；修复五个 Dock 路由覆写从未触发（包名解析方式错误）；修复某个固件类缺失会让全部可选路由修复一起失效；修复路由日志刷屏；给 Dock 判定加缓存；修复 Dock 默认缺少 legacy `w/h` 回退。

**配置层**：修复删除「默认设置」会连带清空列表筛选开关和手工调过的 `applyThird/applySystem`；修复 `su` 子进程管道未读取导致每次保存被拖满超时；把配置读写移出主线程；批量导入结果文案改为可本地化。

**界面**：未配置默认时不再显示虚假的「1602x902 @200」，改为「跟随系统」；DPI 框不再被强制预填；取消「批量导入」不再清空多选；对话框文案改为走翻译资源；「保存并应用」不再先杀应用才发现无法拉起。

**文档**：修正与实际校验不符的分辨率范围描述、删除不存在的「默认 2560×1440」说法、统一版本号/标签/APK 命名说明。

## 真机实测（PICO 4 A8110 / Android 10 / Magisk + zygisk-vector）

同一台设备、同一份配置下 v1.4.1 与 v1.4.2 对照：

| 检查项 | v1.4.1 | v1.4.2 |
|---|---|---|
| 未配置默认时的列表条目 | `默认:1602x902 @200`（虚构） | `跟随系统` |
| 配置了默认 1920x1082 后 | `默认:1920x1082 @200` | `默认:1920x1082 @200` |
| 该应用实际虚拟显示（`dumpsys display`） | 1920 x 1082 | 1920 x 1082 |
| 同一应用 3 次冷启动的 `route` 日志 | 3 条（重复刷屏） | 1 条 |

列表显示与 hook 实际行为现已一致：没有默认配置就明确说「跟随系统」，配了默认则与实测分辨率吻合；路由日志按变化去重。

两点需要注意的结论：

- 全局默认在 v1.4.1 上**本来就能生效**，所以「应用分类兜底」那条属于加固（针对容器不暴露 `isSystemApp()` 的固件），不是本次实测复现出来的故障；同时也没观察到回归。
- `systemext` 读不到 `/data/local/tmp/resfix.cfg`（SELinux 拒绝 `system_app` 读 `shell_data_file`），这是设计内情况：hook 每次都成功回退到 `Settings.Global` 并正确应用配置。

单元测试从 18 个增加到 26 个。
