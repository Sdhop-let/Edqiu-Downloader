package com.ed.edqiu.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 状态语义色（2026-10-10 固定配色重构：统一为 iOS 系统色，不随取色/强调色变化）。
 * 用法约定：彩色文本/圆点 + 同色 12% 底（StatusPill/StatusBadge/StatusDot）。
 */
val StatusPending = Color(0xFFC93400) // iOS orange 的浅色可读档（白底彩字 AA 达标）
val StatusDownloaded = Color(0xFF1F8A3D) // iOS green 浅色可读档
val StatusFailed = Color(0xFFD70015) // iOS red 浅色可读档
val StatusGone = Color(0xFF8E8E93) // iOS systemGray

val StatusPendingDark = Color(0xFFFF9F0A) // iOS orange dark
val StatusDownloadedDark = Color(0xFF30D158) // iOS green dark
val StatusFailedDark = Color(0xFFFF453A) // iOS red dark
val StatusGoneDark = Color(0xFF98989D) // iOS systemGray dark

val GlassHighlight = Color(0xFFFFFFFF)
val GlassShadow = Color(0xFF0F172A)
