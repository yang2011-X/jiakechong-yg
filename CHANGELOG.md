# 变更记录

## 1.3.1-android16-patched（2026-10-26）

首个可用补丁版本，基于官方 1.3.1 / versionCode 17。

- 修复 Android 16 启动即崩（Receiver not registered 循环）：s2.a() 不再 finish 所有界面。
- 修复三个列表页空白：命令前缀改为先 db connect 再执行，并在下发前 nsureConnected()。
- 修复应用管理必崩：用自写 com.beetle.AppDump（pp_process）替换依赖隐藏
  PackageParser 的 com.didjdk.adbhelper.Server。
- 新增 RcWatch 看门狗：APP 私有 adb server 掉线后自动重连、分发 helper、同步图标目录。
- 已知问题：应用图标统一显示为系统默认图标。