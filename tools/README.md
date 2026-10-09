# 开发工具

本目录是**离线开发 / 维护工具**，不参与 Gradle 构建，也不随 APK 分发。
应用的构建入口始终是仓库根目录的 `./gradlew`。

> 图标设计源文件不在本目录，见 [`docs/design/appicon/`](../docs/design/appicon/)。

## 内容

| 文件 | 用途 |
|---|---|
| `build-and-manage.ps1` | Windows：构建（`-BuildType debug\|release`）后自动调用 `manage-apk.ps1` 归档产物 |
| `manage-apk.ps1` | 把 `app-debug.apk` 按版本号归档到 `<仓库根>/apk-archive/`，并维护 `build-history.json` |
| `build_iter.sh` | 缓存隔离构建（见下）。用法 `bash tools/build_iter.sh <编号>` |
| `build_push.sh` | 同上，编号取时间戳，供「一条命令跑一次构建」 |
| `ui_consistency_audit.py` | UI 风格统一性审核：输入 adb 截图（PNG）+ uiautomator dump（XML），输出配色 / 间距 / 圆角的一致性偏差 |
| `ui_layout_analyze.py` | UI 布局结构分析：输入 uiautomator dump（XML），输出带文本 / 类名 / bounds 的层级树 |

## 缓存隔离构建（build_iter.sh）

本机安全代理会在 Gradle 守护进程退出后把 `.lock` 文件永久锁死（rename/delete 全拒），
造成「一个 `GRADLE_USER_HOME` 只能构建一次」。脚本因此每次都用全新 home：

1. 从编号最大的 `home_fresh*` 复制依赖缓存（跳过 `*.lock`）；
2. 复制 `init.d/`（动态代理识别）与 home 级 `gradle.properties`——顺带**只复制项目 pin 的那一个** Gradle 发行包
   （`wrapper/dists` 会堆积历次版本，十几 GB，全量复制没有意义）；
3. `chmod +w` 解除复制带来的只读属性；
4. 换全新 `ANDROID_USER_HOME`（避免 `debug.keystore.lock` 被毒化）；
5. 把项目 `.gradle` 与 `app/build` 改名让位（保留上一次产物便于对比）；
6. 用项目自带 wrapper（`./gradlew`）执行构建。

```bash
bash tools/build_iter.sh 7                     # → <工作区>/gradle/home_fresh7
EDQIU_TASKS=assembleRelease bash tools/build_iter.sh 8
```

### 可覆盖的环境变量（换机器只改这些，脚本内不写死任何绝对路径）

| 变量 | 默认 | 说明 |
|---|---|---|
| `EDQIU_BUILD_WORK` | `D:/AndroidDev` | 构建工作区；缓存 home 与 `android_user_home_*` 都建在其下 |
| `JAVA_HOME` | `<工作区>/jdk/jdk21` | JDK 21 |
| `ANDROID_HOME` | `<工作区>/sdk` | Android SDK（同时导出 `ANDROID_SDK_ROOT`） |
| `PYTHON` | `python3` → `python` → `py` 自动探测 | 仅用于复制缓存（数万文件，纯 shell 逐个复制会慢一个量级） |
| `EDQIU_TASKS` | `assembleDebug` | 要执行的 Gradle 任务 |

仓库根由**脚本自身位置**推导（`tools/` 的上一级），不依赖任何写死的路径。
日常单次构建不需要这套仪式时，直接 `./gradlew :app:assembleDebug` 即可。
