# 开发工具

本目录是**离线开发 / 维护工具**，不参与 Gradle 构建，也不随 APK 分发。
应用的构建入口始终是仓库根目录的 `./gradlew`。

> 图标设计源文件不在本目录，见 [`docs/design/appicon/`](../docs/design/appicon/)。

## 内容

| 文件 | 用途 |
|---|---|
| `build-and-manage.ps1` | Windows：构建（`-BuildType debug\|release`）后自动调用 `manage-apk.ps1` 归档产物 |
| `manage-apk.ps1` | 把 `app-debug.apk` 按版本号归档到 `<仓库根>/apk-archive/`，并维护 `build-history.json` |
| `build_iter.sh` | 本机构建仪式：用全新 `GRADLE_USER_HOME` + `ANDROID_USER_HOME` 构建，规避 Gradle 锁被永久毒化。用法 `bash build_iter.sh <编号>` |
| `build_push.sh` | 同上仪式的一次性封装（无参数） |
| `ui_consistency_audit.py` | UI 风格统一性审核：输入 adb 截图（PNG）+ uiautomator dump（XML），输出配色 / 间距 / 圆角的一致性偏差 |
| `ui_layout_analyze.py` | UI 布局结构分析：输入 uiautomator dump（XML），输出带文本 / 类名 / bounds 的层级树 |

## ⚠️ 两个构建脚本含机器相关绝对路径

`build_iter.sh` 与 `build_push.sh` 是为了绕过本机一个具体的 Gradle 锁问题写的，
内部写死了 `BASE` / `JAVA_HOME` / `ANDROID_HOME` / `GRADLE` 等绝对路径，**换机器必须先改**。

在当前这台机器上这两个脚本已失效：

- `build_iter.sh` 的 `BASE` 指向 `C:/Users/LENOVO/xinvox-local-build2`（仓库实际在 `D:/Edqiu/repo`）；
- `build_push.sh` 的 `GRADLE` 指向 `C:/Users/LENOVO/gradle-8.7-ea/...`（该目录已不存在）。

日常构建直接用根目录的 wrapper 即可，无需这两个脚本：

```bash
JAVA_HOME="<JDK21>" ANDROID_HOME="<SDK>" ./gradlew :app:assembleDebug
```
