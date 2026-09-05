# Plus Key Remapper - OnePlus 15

Remap the physical Plus (alert slider / side) key on the OnePlus 15 to any action: open an app, trigger a shortcut, send a broadcast, control media, and more.

## Features
- Single press and long press gesture detection
- Per-gesture action assignment (launch app, send broadcast, custom intent, media controls)
- Screen-on and screen-off support
- Preset actions for common use cases
- Keepalive worker to survive OxygenOS background kills
- Shizuku input reader with automatic reconnect and a logcat compatibility mode

## Requirements
- OnePlus 15 running OxygenOS (Android 15+)
- Accessibility Service permission
- Shizuku for direct input detection, or ADB `READ_LOGS` for logcat compatibility mode

## Shizuku 底层按键模式

1. 在手机安装并启动 [Shizuku](https://shizuku.rikka.app/guide/setup/)，无需 Root。
2. 在主页点击“检测方式”，选择“Shizuku 底层按键（推荐）”，允许 Shizuku 授权。
3. 保留悬浮窗权限，以便在后台启动映射的应用；截图、相机快门等操作仍需要无障碍权限。
4. 状态显示“已启用，正在监听 Plus 键”后测试单击、长按，以及锁屏、熄屏启动。

该模式不启动 logcat，也不要求 `READ_LOGS`。应用通过 Shizuku UserService 的 shell
身份执行 `getevent`，按 `gpio-keys` 和 `BTN_TRIGGER_HAPPY32` 能力自动识别设备，
不会固定使用 `/dev/input/event0`。仅转发目标按键的真实 DOWN/UP；仅单击模式在按下时
执行，启用长按且已分配长按操作时在松开前区分单击与 850 ms 长按。

连接中断会取消尚未完成的手势，并以 2–30 秒退避重连；进程存活检查不会将长时间
未按键误认为权限失效。日志模式仍要求前台恢复日志会话，两个模式不会同时监听。
旧安装保留日志模式，需要手动选择新模式。此读取方式不会吞掉系统原有的 Plus 键动作。

OxygenOS 的“权限监控”可能限制首次授权和新建 Shizuku 子服务。若出现“adb 权限受限”
或连接超时，需要按 Shizuku 官方指南调整该选项并重启 Shizuku。完成首次连接后可尝试
恢复权限监控并验证。应用会复用 shell 服务，在其中重启意外退出的 `getevent`；暂停
或应用进程退出会关闭输入读取，shell 服务保留为空闲状态，供后续恢复使用。如果整个
shell 服务被杀、Shizuku 重启或 APK 更新，可能需要重新处理这一系统限制。

非 Root Shizuku 在手机重启后需要重新启动，并应允许后台运行。Shizuku 重新连接时会尝试
恢复用户之前启用的监听；若 Android/OEM 阻止后台启动前台服务，通知会引导手动恢复。
允许 Shizuku 使用本应用与授予 `READ_LOGS` 是两件不同的事。

开发验证：`gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`。
单元测试覆盖真机事件格式、设备编号变化、重复事件、短按、长按、快速连按及断线重置。

## Build
```
./gradlew assembleRelease
```

## Package
`com.pluskeymap.app`

## Developer
Selenium Studio - seleniumstudio.app@gmail.com
