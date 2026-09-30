# 发版流程（GitHub Release）

本仓库（`Sdhop-let/Edqiu-Downloader`）的发版走 **GitHub Release**：APK 作为 Release 资产上传，
版本号与源码 tag、文档保持同步。APK 约 84MB，低于 GitHub 普通文件 100MB 限制，无需 Git LFS。

> ⚠️ 历史方案已废弃：早期曾通过 `scripts/publish-github-release.ps1` 把 APK 以 Git LFS 形式
> 推到独立公开仓库（`qiuqiu-fist/Edqiu-application-public-`）。该脚本与
> `watch-and-publish.ps1` 仅作留存参考，不要再使用。

## 发版步骤

1. **更新版本号**（`app/build.gradle.kts`）：

   ```kotlin
   versionCode = 45        // 每版 +1
   versionName = "1.6.9"
   ```

2. **同步文档**（保证文档与 APK 实时一致）：
   - `README.md` 顶部「当前版本」行与下载表；
   - 新建 `docs/RELEASE_NOTES-<版本号>.md` 更新说明。

3. **构建**：

   ```bash
   JAVA_HOME="D:\AndroidDev\jdk\jdk21" ./gradlew.bat :app:assembleDebug
   # 产物：app/build/outputs/apk/debug/app-debug.apk
   ```

4. **提交并打 tag**：

   ```bash
   git add -A
   git commit -m "v1.6.9：<本版摘要>"
   git tag v1.6.9
   ```

5. **推送**（需要可访问 github.com，必要时先开代理）：

   ```bash
   git push origin <分支名>
   git push origin v1.6.9
   ```

6. **创建 GitHub Release**（gh CLI，已 `gh auth login`）：

   ```bash
   gh release create v1.6.9 \
     app/build/outputs/apk/debug/app-debug.apk#Edqiu-v1.6.9.apk \
     --title "Edqiu v1.6.9" \
     --notes-file docs/RELEASE_NOTES-1.6.9.md
   ```

   `#` 后为资产重命名（下载到的文件名）。Release 页面即用户下载入口，
   README 的「下载」一节指向 `releases` 页，无需改链接。

## 一致性检查清单（发版前）

- [ ] `app/build.gradle.kts` 的 `versionName` = 本次 Release tag（去掉 v 前缀）
- [ ] `README.md` 版本行、`docs/RELEASE_NOTES-<版本>.md` 已就位
- [ ] `app-debug.apk` 由**当前提交**的源码构建（提交后如无改动可直接用既有产物）
- [ ] Release 资产名 = `Edqiu-v<版本号>.apk`
