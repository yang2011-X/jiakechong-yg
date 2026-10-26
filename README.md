# 甲壳虫ADB助手 1.3.1 · Android 16 兼容补丁（非官方）

把官方网站下载的 **甲壳虫ADB助手 v1.3.1（com.didjdk.adbhelper）** 修补成能在
**Android 16 / HyperOS** 上正常打开的版本。

原版 1.3.1 在 Android 16 上一启动就自我结束并且 `onDestroy` 抛
`IllegalArgumentException: Receiver not registered`，随后被系统反复重启，界面根本进不去；
即使能进，`文件管理 / 应用管理 / 进程管理` 三个列表与投屏也都不可用。

本仓库**只包含补丁工具与我方新增的 Java 代码**，不包含原版 APK、也不包含反编译出来的
第三方 smali 源码——你需要自己提供官方 APK，脚本会在本地解码、打补丁、重新签名。

## 修复了什么

| # | 症状 | 根因 | 修法 |
|---|---|---|---|
| 1 | 启动即退出并崩溃循环 | native `onCreate` 里调用的 `c.c.a.s2.a()` 会 `finish` 掉所有 Activity，随后 `onDestroy` 里 `unregisterReceiver` 一个从未注册成功的 receiver | 把 `s2.a()` 改成只打一行日志 |
| 2 | 列表页空白 / 一直转圈 | App 用自己的 adb server 跑 `-t 1`，server 重启后 transport id 失效，且不会自动重连 | 命令前缀改为 `adb connect <host>; sleep 0.4; adb`（`RcWatch.prefix()`），并在下发列表命令前 `RcWatch.ensureConnected()` |
| 3 | 应用管理永远空 / JSON 解析异常 | 依赖隐藏类 `com.didjdk.adbhelper.pm.PackageManager`（`app_process` 跑，用已废弃的 `PackageParser` 解析 APK），Android 16 上必然抛 `PackageParserException` | 用一个自写 helper `com.beetle.AppDump` 顶替服务端，输出同名字段 JSON |
| 4 | 内置 adb 起不了 server（`fdsan ... fd is owned by unique_fd`） | Android 16 上的 fdsan 行为变化 | 新增 `RcWatch` 看门狗：App 自己的 server 活着时保持 `adb connect` 心跳；adb 调用统一走 5038 端口，避免与 App 客户端的 5037 `--reply-fd` 握手抢端口 |
| 5 | 应用图标全是绿机器人 | helper 只在 `hasIcon` 时写 PNG，多数系统应用没有独立图标 | **未修复**，属已知观感缺陷 |

## 目录结构

```
patches/beetle-android16.json   6 个 smali 文件的锚点式补丁（base -> patched）
tools/apply_patches.py          应用补丁（每个锚点必须唯一命中，否则报错退出）
tools/gen_patches.py            从「原始解码树」和「已修树」重新生成上面的 JSON
tools/build.ps1                 一键：解码 -> 打补丁 -> 编译两个 dex -> 打包 -> 签名
javarc/.../RcWatch.java         看门狗 + 命令前缀 + helper 分发 + 图标同步
javaserver/com/beetle/AppDump.java  设备端 app_process helper，输出应用列表 JSON
docs/                           截图
```

## 构建

```powershell
# 需要：JDK 11+、Android build-tools 34（d8/zipalign/apksigner）、platforms/android-34、
#       apktool 2.11.x、Python 3.8+
$env:ANDROID_SDK_ROOT = "C:\Users\you\android-sdk"
$env:JAVA_HOME        = "C:\Program Files\Java\jdk-21"
$env:PYTHON           = "C:\Python312\python.exe"

pwsh -File tools\build.ps1 -Apk "D:\downloads\beetle-adbhelper-1.3.1.apk" `
     -ApktoolJar "D:\tools\apktool.jar" `
     -Keystore  "D:\keys\patch.keystore" -KsPass "yourpass" -KeyPass "yourpass"
```

产物默认在 `build\beetle-adbhelper-1.3.1-android16-patched.apk`。
不指定 `-Keystore` 时脚本会自动生成 `.keystore\patch.keystore`（口令 `beetle123`），
这样打出来的包**签名与原版不同，无法覆盖安装官方版本**，需要先卸载官方版（会丢 App 内数据）。

编译 `RcWatch.java` / `AppDump.java` 时脚本固定加了 `-parameters`：build-tools 34 自带的
d8 8.2.2 在解析 javac 21 生成的 `MethodParameters`（匿名内部类构造器里名字为空）时会抛
`NullPointerException: Cannot invoke "String.length()" because "<parameter1>" is null`。

## 打补丁的位置

补丁只改 6 个文件，其余 2227 个 smali 文件原样保留：

| smali 文件 | 改动 |
|---|---|
| `c/c/a/s2.smali` | `a()` 方法体替换为一行 `Log.e("BEETLE_RC", ...)` |
| `c/c/a/v3/g0.smali` | 读取陈旧的 `MainActivity.u`（`"adb -t 1 "`）改为 `RcWatch.prefix()` |
| `c/c/a/v3/i0/d.smali` | 拼命令前插入 `RcWatch.ensureConnected()` |
| `c/c/a/v3/i0/q.smali` | 服务端命令由 `/adbhelper_server.apk ... com.didjdk.adbhelper.Server` 改为 `/beetle_server.apk ... com.beetle.AppDump` |
| `c/c/a/v3/k0/t.smali` | 文件管理下发命令前插入 `RcWatch.ensureConnected()` |
| `com/didjdk/adbhelper/MyApplication.smali` | `onCreate` 里启动 `RcWatch` |

`tools/gen_patches.py <原始解码树> <已修树> patches/beetle-android16.json` 可以重新生成补丁；
`tools/apply_patches.py` 复现出的树与原修改树逐字节一致（仅 3 个文件的空行格式有差异）。

## 已知限制

- 必须先用你自己的官方 APK 解码；仓库不提供原版 APK 与反编译源码。
- 自签名无法覆盖官方版本，安装前要卸载官方版。
- 需要对端开启无线调试（或 `adb tcpip 5555`），本机与被控端同一局域网。
- 应用图标统一为默认机器人（见上表第 5 条）。
- 仅针对 **v1.3.1 / versionCode 17** 验证；其它版本 smali 行号会变，锚点补丁可能不匹配。

## 许可与声明

`tools/`、`javarc/`、`javaserver/` 下的代码为 MIT（见 LICENSE）。
本仓库与甲壳虫ADB助手的作者无关，仅为个人设备兼容性修复的补丁工具集；
原版 App 的版权归其作者所有，使用前请自行确认是否符合当地法律与软件许可。
