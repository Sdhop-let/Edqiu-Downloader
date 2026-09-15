# Changelog · 全版本时间线

> 排序：新 → 旧。`code` = versionCode。MD5 为 debug APK 校验值（MD5 大小写不敏感）。
> 带链接的版本有 GitHub Release / 发布资产，其余为本地迭代构建。

| 版本 | code | 日期 | APK MD5 | 说明 / 链接 |
| --- | --- | --- | --- | --- |
| **v1.5.1** | 36 | 2026-09-15 | `5f6a257e0ced1a316ba67e18f508db25` | **当前最新** · v2 重构批次 2-5 全量 · [Release](https://github.com/Sdhop-let/Edqiu-Downloader/releases/tag/v1.5.1) · [notes](releases/v1.5.1/RELEASE-NOTES.md) |
| v1.5.0 | 35 | 2026-09-15 | `ba16088f646419fc7042537a47e1cf2e` | 重构周期中间构建 · [notes](releases/v1.5.0/RELEASE-NOTES.md) |
| v1.4.29 | 34 | 2026-09-15 | `fb574a8f03299355d8521a769db6dfdf` | 重构周期中间构建 · [notes](releases/v1.4.29/RELEASE-NOTES.md) |
| v1.4.28 | 33 | 2026-09-15 | `ff450eb4b809edf132051e224a88013d` | 重构周期中间构建 · [notes](releases/v1.4.28/RELEASE-NOTES.md) |
| v1.4.27 | 32 | 2026-09-15 | `0ef0c80059bcb281655cb0b7dd910457` | 重构周期中间构建 · [notes](releases/v1.4.27/RELEASE-NOTES.md) |
| v1.4.26 | 31 | 2026-09-15 | `9993fed784f020ea164884d48ccea470` | 重构周期中间构建 · [notes](releases/v1.4.26/RELEASE-NOTES.md) |
| v1.4.23 | 28 | 2026-09-15 | `8ac575e60f40a2d136be9978042f77cd` | 重构周期中间构建 · [notes](releases/v1.4.23/RELEASE-NOTES.md) |
| v1.4.22 | 27 | 2026-09-15 | — | 重构周期中间构建 |
| v1.4.15 | 20 | 2026-09-15 | `6e1fa60795f9189c142aa9d32ef71204` | 重构周期中间构建 |
| v1.4.13 | — | 2026-09-15 | — | 重构周期中间构建 |
| v1.4.8 | — | 2026-09-14 | — | 播放器/媒体库迭代 |
| v1.4.7 | — | 2026-09-14 | — | 播放器/媒体库迭代 |
| v1.4.4 | 9 | 2026-09 上旬 | — | 迭代构建 |
| v1.4.3 | 8 | 2026-09 上旬 | — | 底部胶囊提醒 · 网络下载优化 · 全局转场动画 · 卡片按压 |
| v1.4.2 | 7 | 2026-08-31 | — | [Release](https://github.com/Sdhop-let/Edqiu-Downloader/releases/tag/v1.4.2) |
| v1.4.1 | 6 | 2026-08-31 | — | 功能迭代 |
| v1.4.0 | 5 | 2026-08 | — | [Release](https://github.com/Sdhop-let/Edqiu-Downloader/releases/tag/v1.4.0) · release 签名 |
| v1.3.0 | — | 2026-07 | — | [Release](https://github.com/Sdhop-let/Edqiu-Downloader/releases/tag/v1.3.0) · [notes](docs/RELEASE_NOTES-1.3.0.md) |
| EsQp1 | — | 2026-07 | — | [Release](https://github.com/Sdhop-let/Edqiu-Downloader/releases/tag/EsQp1) |
| EsQp | — | 2026-07 | — | 首个公开版本 · [Release](https://github.com/Sdhop-let/Edqiu-Downloader/releases/tag/EsQp) |

## 2026-09-15 重构周期（v1.4.13 → v1.5.1）累计要点

- 播放页重构为 **X 风格竖滑流**（X 覆盖层 / 头像徽章行 / 底部中央操作钮 / 1.5s 沉浸）
- 媒体库头部**数据可视化改版**：统计卡兼筛选、分组降级次行、排序准则锚点
- 分组单位定为 **作者 + 发布日**；全库按推文发布时间倒序
- 发布时间回填器（Backfiller）动态调速 + UI 实时进度
- HLS 链路检测与画质升级重下载
- 玻璃胶囊反馈体系（CapsuleFeedback）与 Snackbar 互斥清场
- 收件箱/详情页下载迁至全局作用域（退出页面不中断）
