# Edqiu v1.5.1 · Release Notes

| 项 | 值 |
| --- | --- |
| versionName | **1.5.1** |
| versionCode | 36 |
| 构建时间 | 2026-09-15 22:45 |
| APK | `Edqiu-v1.5.1-debug.apk`（77 MB · arm64-v8a） |
| APK MD5 | `5f6a257e0ced1a316ba67e18f508db25` |
| 下载 | [GitHub Release v1.5.1](https://github.com/Sdhop-let/Edqiu-Downloader/releases/tag/v1.5.1) |

v2 重构计划 **批次 2-5 全量落地**。

## 🚑 P0-2 · 下载引擎自救热修
- 三层下载通道（FXTwitter / 第三方 API / yt-dlp）全部失效时，进程内执行一次 `updateYoutubeDL` 自动重建引擎
- 重建成功后自动清除熔断状态，恢复下载能力，无需重启 App
- 新增 `DownloadEngineHealth` 管理引擎健康与熔断生命周期

## 🛟 P0-4 · WorkManager 持久下载队列
- 新增 `DownloadQueueWorker`：`requestDownload` 钩子登记唯一 Worker
- 进程被杀后队列自动续跑，下载不丢任务
- 不改变手动模式语义：手动确认流程保持原样

## 🎬 P1-1 · 播放列表化邻条预载
- media3 `setPreloadConfiguration` 8 秒邻条预载
- 切换视频改为 `seekTo(index)`，上滑零等待起播

## 🧹 P1-4① · pHash 视觉查重
- 数据库 `MIGRATION_7_8`：新增 `phash` 列与 `subscriptions` 表
- 新增 `PhashService`：DCT 64bit 感知哈希，汉明距离 ≤4 判重

## 📡 P2-1 · 作者订阅
- `SubscriptionManager` 每 25 分钟轮询 yt-dlp 作者主页（flat-playlist）
- 发现新推文 ID 自动 capture 并入队下载
- 入口：播放页 ⋮ 菜单订阅；设置页新增订阅管理卡

## 🔍 P1-4②③ · 媒体库文本搜索
- 收件箱与媒体库支持标题 / 作者 / 文案全文检索

## 安装说明

- arm64-v8a 专用，Android 16/17+ 真机
- debug 签名：与旧 debug 版可直接覆盖安装；从 release 版（v1.4.0）迁移需先卸载
