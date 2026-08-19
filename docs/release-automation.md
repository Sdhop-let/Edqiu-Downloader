# GitHub 自动发版说明

此项目已新增本地发版脚本，用于把 APK、版本信息和升级说明联动推送到公开仓库：

`https://github.com/qiuqiu-fist/Edqiu-application-public-.git`

## 推荐方式：手动发布一次版本

在项目根目录运行：

```powershell
.\scripts\publish-github-release.ps1
```

默认行为：

1. 自动把 `app/build.gradle.kts` 的 `versionCode` 加 1。
2. 自动把 `versionName` 升级一个 patch 版本，例如 `2.1.2` -> `2.1.3`。
3. 执行 `:app:assembleDebug` 构建 APK。
4. 将 APK 复制到公开仓库：
   - `apk/twitter-downloader-latest.apk`
   - `releases/v版本号/twitter-downloader-v版本号.apk`
5. 自动生成升级文档：
   - `docs/latest-upgrade.md`
   - `releases/v版本号/UPGRADE.md`
6. 自动生成 `releases/latest.json`。
7. 自动 commit 并 push 到 GitHub。

## 指定更新说明

```powershell
.\scripts\publish-github-release.ps1 -ReleaseNotes "- 修复播放页返回卡顿。`n- 优化一加 15 顶部留白。"
```

## 指定版本升级类型

```powershell
.\scripts\publish-github-release.ps1 -Bump patch
.\scripts\publish-github-release.ps1 -Bump minor
.\scripts\publish-github-release.ps1 -Bump major
```

## 只生成本地提交，不推送

```powershell
.\scripts\publish-github-release.ps1 -NoPush
```

## 可选：监听改动并自动发布

如果你确实希望“改动后自动生成版本并推送”，可以运行：

```powershell
.\scripts\watch-and-publish.ps1
```

它会监听项目文件变化，并在停止改动 120 秒后自动发布一个 patch 版本。

注意：自动监听会频繁生成版本，适合最终打包阶段；日常开发更推荐使用手动发布脚本。

## Git LFS 说明

APK 超过 GitHub 100 MB 普通文件限制，公开仓库已使用 Git LFS 管理：

- `apk/*.apk`
- `releases/**/*.apk`