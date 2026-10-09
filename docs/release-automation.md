# 发版流程（GitHub Release）

本仓库（`Sdhop-let/Edqiu-Downloader`）的发版**全部在 GitHub 侧自动完成**：
推一个 `v*` tag → GitHub Actions 构建 APK → 创建 Release 并上传资产。

- 工作流：[`.github/workflows/release.yml`](../.github/workflows/release.yml)
- 资产名：`Edqiu-v<版本号>.apk`（约 71 MB，低于 GitHub 普通文件 100 MB 限制，无需 Git LFS）
- **本地不需要 `gh release create`**，也不需要能访问 `api.github.com`——只需能 `git push`。

> ⚠️ 历史方案已废弃（脚本已移入 [`archive/scripts/`](archive/scripts/)）：
> 早期由 `publish-github-release.ps1` 把 APK 以 Git LFS 推到独立公开仓
> （`qiuqiu-fist/Edqiu-application-public-`），配合 `watch-and-publish.ps1` 监听自动发布。
> 该链路与当前 Release 通道冲突，**不要再用**。

## 构建产物与签名

CI 按仓库 Secrets 是否配置签名凭据走双模式：

| 条件 | 构建 | 结果 |
|---|---|---|
| 配置了 `SIGNING_KEYSTORE_BASE64` / `SIGNING_STORE_PASSWORD` / `SIGNING_KEY_ALIAS` / `SIGNING_KEY_PASSWORD` | `assembleRelease` | **正式签名包，已装用户可覆盖升级** |
| 未配置 | `assembleDebug` | debug 签名包，仅供全新安装 |

## 发版步骤

1. **更新版本号**（`app/build.gradle.kts`，`versionCode` 每版 +1）：

   ```kotlin
   versionCode = 48
   versionName = "1.8.0"
   ```

2. **写本版说明**：新建 `docs/RELEASE_NOTES-<版本号>.md`。
   CI 取 `docs/RELEASE_NOTES-*.md` 中**版本号最大**的一份作为 Release 正文，因此文件名必须与 tag 一致。

3. **同步文档**：
   - `README.md` 顶部的版本行；
   - `docs/CHANGELOG.md` 的版本总览表与详细章节各追加一条。

4. **本地验证构建**（建议，CI 失败时排查成本更高）：

   ```bash
   JAVA_HOME="<JDK21 路径>" ./gradlew :app:assembleDebug
   JAVA_HOME="<JDK21 路径>" ./gradlew :app:assembleRelease   # 需自备 keystore.properties
   ```

5. **提交并打 tag**：

   ```bash
   git add -A
   git commit -m "v1.8.0：<本版摘要>"
   git tag v1.8.0
   ```

6. **推送**（tag 是触发器，必须单独推）：

   ```bash
   git push origin main
   git push origin v1.8.0
   ```

   > 本机若直连 github.com 超时，可临时挂代理（不改全局配置）：
   > `git -c http.proxy=http://127.0.0.1:7890 push origin main`

7. **确认 Release**：在 Actions 页看工作流跑完（约 5 分钟）。
   构建失败时 CI 会把日志推到 `ci-logs` 分支并在 run summary 里贴出尾部，便于公开排查。

## 一致性检查清单（推送 tag 前）

- [ ] `app/build.gradle.kts` 的 `versionName` = 本次 tag（去掉 `v` 前缀）
- [ ] `docs/RELEASE_NOTES-<版本>.md` 已就位，且版本号在 `docs/` 中最大
- [ ] `README.md` 版本行、`docs/CHANGELOG.md` 已同步
- [ ] 本地 `assembleDebug` / `assembleRelease` 至少跑通一个
