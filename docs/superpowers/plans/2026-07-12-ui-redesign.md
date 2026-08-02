# XInvox 界面重新设计 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 XInvox UI 从工具型表单风格改版为 Material 3 现代极简风格，引入底部导航、精简卡片、Snackbar 反馈、骨架屏和缩略图加载。

**Architecture:** 保持现有 ViewModel + Repository + Room 分层，仅替换 UI 层。新增全局 SnackbarController、Coil 缩略图加载。ListViewModel 增加软删除 + 撤销状态。BackupViewModel 用 Mutex 修复 busy 原子性。

**Tech Stack:** Kotlin 2.0.21 / Jetpack Compose / Material 3 (composeBom 2024.09.00) / Coil 2.7.0 / Coroutines 1.9.0

> **注意**：项目目录不含 Git 元数据（参见 OVERVIEW.md），因此"提交"步骤改为"检查点"——构建并验证通过即可进入下一任务。

---

## File Structure

### 新增文件（7 个）
- `app/src/main/java/com/ed/xinvox/ui/theme/Shape.kt` — 圆角 token
- `app/src/main/java/com/ed/xinvox/ui/components/StatusDot.kt` — 状态圆点组件
- `app/src/main/java/com/ed/xinvox/ui/components/EmptyState.kt` — 空状态组件
- `app/src/main/java/com/ed/xinvox/ui/components/Shimmer.kt` — shimmer Modifier
- `app/src/main/java/com/ed/xinvox/ui/components/SkeletonCard.kt` — 骨架屏卡片
- `app/src/main/java/com/ed/xinvox/ui/settings/SettingGroup.kt` — 分组卡片容器
- `app/src/main/java/com/ed/xinvox/ui/navigation/SnackbarController.kt` — 全局 Snackbar 控制器

### 修改文件（13 个）
- `app/build.gradle.kts` — 添加 Coil 依赖
- `app/src/main/java/com/ed/xinvox/ui/theme/Color.kt` — 色彩 token 净化
- `app/src/main/java/com/ed/xinvox/ui/theme/Theme.kt` — 注入 Shapes
- `app/src/main/java/com/ed/xinvox/ui/theme/Type.kt` — 字重 SemiBold → Medium
- `app/src/main/java/com/ed/xinvox/ui/util/Actions.kt` — 添加相对时间格式化 + 移除 Toast
- `app/src/main/java/com/ed/xinvox/data/repository/SavedLinkRepository.kt` — 新增 undoDelete
- `app/src/main/java/com/ed/xinvox/ui/components/LinkCard.kt` — 精简卡片重写
- `app/src/main/java/com/ed/xinvox/ui/components/StatusBadge.kt` — 简化为圆点
- `app/src/main/java/com/ed/xinvox/ui/list/ListViewModel.kt` — 软删除 + 撤销状态
- `app/src/main/java/com/ed/xinvox/ui/list/ListScreen.kt` — 顶栏简化 + FAB + 骨架屏
- `app/src/main/java/com/ed/xinvox/ui/detail/DetailScreen.kt` — 缩略图 + 信息区 + 固定底栏
- `app/src/main/java/com/ed/xinvox/ui/history/HistoryScreen.kt` — 卡片化 + 底部操作栏
- `app/src/main/java/com/ed/xinvox/ui/settings/SettingsScreen.kt` — 分组卡片重写
- `app/src/main/java/com/ed/xinvox/ui/settings/BackupViewModel.kt` — Mutex 修复 busy
- `app/src/main/java/com/ed/xinvox/ui/navigation/AppNav.kt` — 底部导航 + SnackbarHost

---

## Task 1: 添加 Coil 依赖

**Files:**
- Modify: `app/build.gradle.kts:57-91`

- [ ] **Step 1: 在 dependencies 块末尾添加 Coil**

在 `app/build.gradle.kts` 的 `dependencies` 块中，在 `androidTestImplementation` 之前添加：

```kotlin
    implementation("io.coil-kt:coil-compose:2.7.0")
```

具体位置：在 `implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")` 之后、`testImplementation` 之前。

- [ ] **Step 2: 验证依赖解析**

Run: `.\gradlew.bat :app:dependencies --configuration debugRuntimeClasspath | findstr coil`
Expected: 输出包含 `io.coil-kt:coil-compose:2.7.0`

- [ ] **Step 3: 检查点**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 2: 更新色彩 Token

**Files:**
- Modify: `app/src/main/java/com/ed/xinvox/ui/theme/Color.kt`

- [ ] **Step 1: 用净化后的色彩替换整个文件**

```kotlin
package com.ed.xinvox.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * XInvox 设计 token —— 色彩。
 * 基调：现代极简，中性灰为底，深蓝为主色，状态色仅用于小圆点和文字。
 */

// 主色（深蓝，保持）
val Primary = Color(0xFF2F4C8F)
val OnPrimary = Color(0xFFFFFFFF)
val PrimaryContainer = Color(0xFFE4E9F4)

// 中性面（净化后，更干净）
val Background = Color(0xFFFAFAFA)
val Surface = Color(0xFFFFFFFF)
val SurfaceVariant = Color(0xFFF5F5F5)
val OnSurface = Color(0xFF1A1C1E)
val OnSurfaceVariant = Color(0xFF6B7280)
val Outline = Color(0xFFE5E7EB)

// 状态色（仅用于小圆点和文字，不再用于徽标背景）
val StatusPending = Color(0xFF9CA3AF)
val StatusDownloaded = Color(0xFF10B981)
val StatusFailed = Color(0xFFEF4444)

// 文本强调
val LinkText = Color(0xFF1F3A5F)

// 暗色主题状态色（提高亮度）
val StatusPendingDark = Color(0xFFB0B7C0)
val StatusDownloadedDark = Color(0xFF34D399)
val StatusFailedDark = Color(0xFFF87171)
```

- [ ] **Step 2: 检查点**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 3: 更新排版 Token

**Files:**
- Modify: `app/src/main/java/com/ed/xinvox/ui/theme/Type.kt`

- [ ] **Step 1: 将 titleLarge 和 titleMedium 的字重从 SemiBold 改为 Medium**

将 `FontWeight.SemiBold` 替换为 `FontWeight.Medium`（仅 titleLarge 和 titleMedium 两处）：

```kotlin
package com.ed.xinvox.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 排版 token。字重从 SemiBold 降为 Medium，减少视觉噪音。
 */
val Typography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.2.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp
    )
)
```

- [ ] **Step 2: 检查点**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 4: 新增 Shape Token

**Files:**
- Create: `app/src/main/java/com/ed/xinvox/ui/theme/Shape.kt`

- [ ] **Step 1: 创建 Shape.kt**

```kotlin
package com.ed.xinvox.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 形状 token。统一圆角层级。
 */
val Shapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp)
)
```

- [ ] **Step 2: 检查点**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 5: 更新 Theme 注入 Shapes 和暗色状态色

**Files:**
- Modify: `app/src/main/java/com/ed/xinvox/ui/theme/Theme.kt`

- [ ] **Step 1: 替换整个 Theme.kt**

```kotlin
package com.ed.xinvox.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    background = Background,
    surface = Surface,
    surfaceVariant = SurfaceVariant,
    onSurface = OnSurface,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Outline
)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryContainer,
    onPrimary = OnSurface,
    primaryContainer = Primary,
    background = Color(0xFF121417),
    surface = Color(0xFF1A1D21),
    surfaceVariant = Color(0xFF23272C),
    onSurface = Color(0xFFE6E8EB),
    onSurfaceVariant = Color(0xFFA8AEB6),
    outline = Color(0xFF33383E)
)

@Composable
fun XInvoxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}

/** 根据当前主题返回状态色。 */
@Composable
fun statusColor(status: com.ed.xinvox.data.model.LinkStatus): Color {
    val dark = isSystemInDarkTheme()
    return when (status) {
        com.ed.xinvox.data.model.LinkStatus.PENDING ->
            if (dark) StatusPendingDark else StatusPending
        com.ed.xinvox.data.model.LinkStatus.DOWNLOADED ->
            if (dark) StatusDownloadedDark else StatusDownloaded
        com.ed.xinvox.data.model.LinkStatus.FAILED ->
            if (dark) StatusFailedDark else StatusFailed
    }
}
```

- [ ] **Step 2: 检查点**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 6: 更新 Actions.kt 添加相对时间格式化

**Files:**
- Modify: `app/src/main/java/com/ed/xinvox/ui/util/Actions.kt`

- [ ] **Step 1: 添加 formatRelativeTime 函数并移除 copyToClipboard 中的 Toast**

在 `formatTime` 函数之后添加 `formatRelativeTime`，并修改 `copyToClipboard` 移除 Toast：

```kotlin
package com.ed.xinvox.ui.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private const val DOWNLOADER_MEDIA_AUTHORITY = "com.ed.twitterdownloader.media"

/** 时间格式化：复制时间/下载时间展示。 */
fun formatTime(ts: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    return sdf.format(Date(ts))
}

/** 相对时间格式化：如 "2 分钟前"、"3 小时前"、"2 天前"，超过 7 天回退到绝对时间。 */
fun formatRelativeTime(ts: Long, now: Long = System.currentTimeMillis()): String {
    val diff = abs(now - ts)
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        diff < minute -> "刚刚"
        diff < hour -> "${diff / minute} 分钟前"
        diff < day -> "${diff / hour} 小时前"
        diff < 7 * day -> "${diff / day} 天前"
        else -> formatTime(ts)
    }
}

/** 作者头像占位字母（取名称首字）。 */
fun initials(name: String?): String {
    if (name.isNullOrBlank()) return "?"
    val clean = name.trimStart('@')
    return clean.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}

/** 复制文本到剪贴板（不显示 Toast，由调用方通过 Snackbar 反馈）。 */
fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_MANAGER) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** 通过 SAF 打开已下载的文件（支持监控目录下的子目录）。 */
fun openDownloadedFile(context: Context, monitorUri: String?, filePath: String?): Boolean {
    if (monitorUri.isNullOrBlank() || filePath.isNullOrBlank()) {
        return false
    }
    return runCatching {
        val monitor = Uri.parse(monitorUri)
        val fileUri = if (monitor.authority == DOWNLOADER_MEDIA_AUTHORITY) {
            Uri.Builder()
                .scheme("content")
                .authority(DOWNLOADER_MEDIA_AUTHORITY)
                .appendPath("file")
                .appendPath(filePath)
                .build()
        } else {
            val tree = DocumentFile.fromTreeUri(context, monitor)
            val file = filePath.split('/').filter { it.isNotBlank() }.fold(tree) { current, segment ->
                current?.findFile(segment)
            }
            file?.takeIf { it.exists() && it.isFile }?.uri
        }

        if (fileUri != null) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, context.contentResolver.getType(fileUri) ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } else {
            false
        }
    }.getOrDefault(false)
}
```

注意：`openDownloadedFile` 返回值从 `Unit` 改为 `Boolean`，调用方根据返回值决定 Snackbar 内容。`copyToClipboard` 移除 Toast，由调用方负责反馈。

- [ ] **Step 2: 检查点**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL（会有调用方因签名变化报错，下一个任务修复）

> 如果编译失败，临时在调用处加 `Toast` 或注释，后续任务会修复。但 `openDownloadedFile` 的返回值变更需要在调用处处理——如果编译报错，先在 `DetailScreen.kt` 中将 `openDownloadedFile(context, monitorUri, data.filePath)` 改为 `openDownloadedFile(context, monitorUri, data.filePath)` 并忽略返回值（`val ignored = ...`），后续 Task 12 会重写 DetailScreen。

---

## Task 7: 新增 StatusDot 组件

**Files:**
- Create: `app/src/main/java/com/ed/xinvox/ui/components/StatusDot.kt`

- [ ] **Step 1: 创建 StatusDot.kt**

```kotlin
package com.ed.xinvox.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ed.xinvox.data.model.LinkStatus
import com.ed.xinvox.ui.theme.statusColor

/**
 * 状态圆点：8dp 小圆点，颜色随状态变化，带 200ms 颜色过渡动画。
 */
@Composable
fun StatusDot(
    status: LinkStatus,
    modifier: Modifier = Modifier
) {
    val targetColor = statusColor(status)
    val color by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 200),
        label = "statusDotColor"
    )
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
}
```

- [ ] **Step 2: 检查点**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 8: 新增 EmptyState 组件

**Files:**
- Create: `app/src/main/java/com/ed/xinvox/ui/components/EmptyState.kt`

- [ ] **Step 1: 创建 EmptyState.kt**

```kotlin
package com.ed.xinvox.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 空状态组件：图标（emoji）+ 标题 + 描述 + 可选 CTA 按钮。
 */
@Composable
fun EmptyState(
    icon: String,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = icon,
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text(actionLabel)
            }
        }
    }
}
```

- [ ] **Step 2: 检查点**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 9: 新增 Shimmer Modifier 和 SkeletonCard

**Files:**
- Create: `app/src/main/java/com/ed/xinvox/ui/components/Shimmer.kt`
- Create: `app/src/main/java/com/ed/xinvox/ui/components/SkeletonCard.kt`

- [ ] **Step 1: 创建 Shimmer.kt**

```kotlin
package com.ed.xinvox.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Shimmer 效果 Modifier：在组件上叠加从左到右的渐变扫光动画。
 */
fun Modifier.shimmer(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    val baseColor = Color(0xFFF5F5F5)
    val highlightColor = Color(0xFFEEEEEE)

    clip(RoundedCornerShape(12.dp)).drawWithContent {
        drawContent()
        val width = size.width
        val brush = Brush.linearGradient(
            colors = listOf(baseColor, highlightColor, baseColor),
            start = Offset(translateAnim * width - width, 0f),
            end = Offset(translateAnim * width, size.height)
        )
        drawRect(brush = brush)
    }
}
```

- [ ] **Step 2: 创建 SkeletonCard.kt**

```kotlin
package com.ed.xinvox.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 骨架屏卡片：列表加载时的占位，尺寸与精简 LinkCard 一致。
 */
@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .shimmer()
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .width(120.dp)
                        .height(14.dp)
                        .shimmer()
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .shimmer()
            )
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(14.dp)
                    .shimmer()
            )
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .height(10.dp)
                    .align(androidx.compose.ui.Alignment.End)
                    .shimmer()
            )
        }
    }
}
```

- [ ] **Step 3: 检查点**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 10: 新增 SettingGroup 组件

**Files:**
- Create: `app/src/main/java/com/ed/xinvox/ui/settings/SettingGroup.kt`

- [ ] **Step 1: 创建 SettingGroup.kt**

```kotlin
package com.ed.xinvox.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 设置分组卡片容器：标题 + 内容块，filled 变体。
 */
@Composable
fun SettingGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            content()
        }
    }
}
```

- [ ] **Step 2: 检查点**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 11: 新增 SnackbarController

**Files:**
- Create: `app/src/main/java/com/ed/xinvox/ui/navigation/SnackbarController.kt`

- [ ] **Step 1: 创建 SnackbarController.kt**

```kotlin
package com.ed.xinvox.ui.navigation

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Snackbar 消息数据。
 */
data class SnackbarMessage(
    val message: String,
    val actionLabel: String? = null,
    val duration: SnackbarDuration = SnackbarDuration.Short,
    val onAction: (() -> Unit)? = null
)

/**
 * 全局 Snackbar 控制器。
 * ViewModel 通过 [show] 发送消息，顶层 Scaffold 通过 [observe] 收集并显示。
 */
class SnackbarController {
    private val channel = Channel<SnackbarMessage>(Channel.BUFFERED)
    val messages = channel.receiveAsFlow()

    fun show(
        message: String,
        actionLabel: String? = null,
        duration: SnackbarDuration = SnackbarDuration.Short,
        onAction: (() -> Unit)? = null
    ) {
        channel.trySend(
            SnackbarMessage(message, actionLabel, duration, onAction)
        )
    }

    /**
     * 在顶层 Scaffold 中调用，收集消息并显示 Snackbar。
     */
    fun observe(
        scope: CoroutineScope,
        snackbarHostState: SnackbarHostState
    ) {
        scope.launch {
            messages.collect { msg ->
                val result = snackbarHostState.showSnackbar(
                    message = msg.message,
                    actionLabel = msg.actionLabel,
                    duration = msg.duration
                )
                if (result == SnackbarResult.ActionPerformed) {
                    msg.onAction?.invoke()
                }
            }
        }
    }
}
```

- [ ] **Step 2: 检查点**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 12: 更新 SavedLinkRepository 新增 undoDelete

**Files:**
- Modify: `app/src/main/java/com/ed/xinvox/data/repository/SavedLinkRepository.kt:166-170`

- [ ] **Step 1: 在 delete 和 deleteMany 之后新增 undoDelete 方法**

在 `SavedLinkRepository.kt` 的 `deleteMany` 函数之后（第 170 行之后），`fetchAndApplyMetadata` 之前，添加：

```kotlin
    /**
     * 从回收站恢复记录回收件箱。
     * 用于撤销删除操作。
     */
    suspend fun undoDelete(archiveIds: Collection<String>): LinkHistoryRepository.RestoreResult =
        linkHistoryRepository.restore(archiveIds)
```

- [ ] **Step 2: 检查点**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 13: 修改 ArchiveResult 返回 archiveIds

**Files:**
- Modify: `app/src/main/java/com/ed/xinvox/data/repository/LinkHistoryRepository.kt:15`
- Modify: `app/src/main/java/com/ed/xinvox/data/repository/LinkHistoryRepository.kt:22-45`

- [ ] **Step 1: 修改 ArchiveResult data class**

将第 15 行：
```kotlin
    data class ArchiveResult(val archived: Int)
```
替换为：
```kotlin
    data class ArchiveResult(val archived: Int, val archiveIds: List<String>)
```

- [ ] **Step 2: 修改 archiveAndDelete 返回 archiveIds**

将 `archiveAndDelete` 函数（第 22-45 行）中的两处 `ArchiveResult(0)` 和末尾的 `ArchiveResult(links.size)` 替换：

第一处（第 27 行）：
```kotlin
        if (ids.isEmpty()) return ArchiveResult(0, emptyList())
```

第二处（第 31 行）：
```kotlin
            if (links.isEmpty()) return@withTransaction ArchiveResult(0, emptyList())
```

末尾（第 43 行）：
```kotlin
            val archiveIds = links.map { link ->
                DeletedLinkHistory.from(
                    link = link,
                    deletedAt = deletedAt,
                    deletionReason = reason
                ).archiveId
            }
            ArchiveResult(links.size, archiveIds)
```

注意：需要保持 `historyDao.insertAll` 调用不变，只是额外计算 archiveIds。完整修改后的函数：

```kotlin
    suspend fun archiveAndDelete(
        tweetIds: Collection<String>,
        reason: String
    ): ArchiveResult {
        val ids = tweetIds.distinct()
        if (ids.isEmpty()) return ArchiveResult(0, emptyList())

        return database.withTransaction {
            val links = savedLinkDao.getByTweetIds(ids)
            if (links.isEmpty()) return@withTransaction ArchiveResult(0, emptyList())
            val deletedAt = System.currentTimeMillis()
            val entries = links.map { link ->
                DeletedLinkHistory.from(
                    link = link,
                    deletedAt = deletedAt,
                    deletionReason = reason
                )
            }
            historyDao.insertAll(entries)
            savedLinkDao.deleteMany(links.map { it.tweetId })
            ArchiveResult(links.size, entries.map { it.archiveId })
        }
    }
```

- [ ] **Step 3: 检查点**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 14: 更新 ListViewModel 软删除 + 撤销状态

**Files:**
- Modify: `app/src/main/java/com/ed/xinvox/ui/list/ListViewModel.kt`

> **架构说明**：ViewModel 不持有 SnackbarController（UI 层组件不应进入 ViewModel）。改为通过 `actionFeedback` StateFlow 暴露带 action 的事件，由 Screen 层观察并转发到全局 SnackbarController。

- [ ] **Step 1: 添加 ActionFeedback 数据类和撤销状态字段**

在 `ListViewModel` 类中，将 `actionFeedback` 替换为带 action 的事件类型。

将第 61-62 行：
```kotlin
    val captureFeedback = MutableStateFlow<CaptureFeedback?>(null)
    val actionFeedback = MutableStateFlow<String?>(null)
```
替换为：
```kotlin
    val captureFeedback = MutableStateFlow<CaptureFeedback?>(null)
    val actionFeedback = MutableStateFlow<ActionFeedback?>(null)
    private val lastDeletedArchiveIds = MutableStateFlow<List<String>>(emptyList())
```

在 `CaptureFeedback` enum（第 205 行）之前添加 ActionFeedback 数据类：
```kotlin
    data class ActionFeedback(
        val message: String,
        val actionLabel: String? = null,
        val onAction: (() -> Unit)? = null
    )
```

- [ ] **Step 2: 修改 delete 函数支持撤销**

将第 165-167 行的 `delete` 函数：
```kotlin
    fun delete(tweetId: String) {
        viewModelScope.launch(Dispatchers.IO) { repo.delete(tweetId) }
    }
```
替换为：
```kotlin
    fun delete(tweetId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = repo.delete(tweetId)
            lastDeletedArchiveIds.value = result.archiveIds
            actionFeedback.value = ActionFeedback(
                message = "已删除 1 条记录",
                actionLabel = "撤销",
                onAction = ::undoDelete
            )
        }
    }
```

- [ ] **Step 3: 修改 deleteSelected 函数支持撤销**

将第 118-126 行的 `deleteSelected` 函数：
```kotlin
    fun deleteSelected() {
        val ids = selectedIdsMutable.value
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val result = repo.deleteMany(ids)
            actionFeedback.value = "已移入回收站 ${result.archived} 条记录"
            clearSelection()
        }
    }
```
替换为：
```kotlin
    fun deleteSelected() {
        val ids = selectedIdsMutable.value
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val result = repo.deleteMany(ids)
            lastDeletedArchiveIds.value = result.archiveIds
            actionFeedback.value = ActionFeedback(
                message = "已删除 ${result.archived} 条记录",
                actionLabel = "撤销",
                onAction = ::undoDelete
            )
            clearSelection()
        }
    }
```

- [ ] **Step 4: 添加 undoDelete 函数**

在 `delete` 函数之后添加：

```kotlin
    fun undoDelete() {
        val archiveIds = lastDeletedArchiveIds.value
        if (archiveIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val result = repo.undoDelete(archiveIds)
            lastDeletedArchiveIds.value = emptyList()
            actionFeedback.value = ActionFeedback("已恢复 ${result.restored} 条记录")
        }
    }
```

- [ ] **Step 5: 修改 requestDownload 使用 ActionFeedback**

将第 99-105 行的 `requestDownload` 函数中的 `actionFeedback.value = downloadMessage(result)` 替换为 `actionFeedback.value = ActionFeedback(downloadMessage(result))`。

- [ ] **Step 6: 修改 downloadSelected 使用 ActionFeedback**

将第 107-116 行的 `downloadSelected` 函数中的 `actionFeedback.value = ...` 替换为 `actionFeedback.value = ActionFeedback(...)`。

- [ ] **Step 7: 检查点**

Run: `.\gradlew.bat assembleDebug`
Expected: 编译失败（ListScreen 仍引用旧的 `actionFeedback: String?` 类型）——Task 18 会修复。

---

## Task 15: 重写 LinkCard 为精简卡片

**Files:**
- Modify: `app/src/main/java/com/ed/xinvox/ui/components/LinkCard.kt`

- [ ] **Step 1: 替换整个 LinkCard.kt**

```kotlin
package com.ed.xinvox.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ed.xinvox.data.model.SavedLink
import com.ed.xinvox.ui.util.formatRelativeTime

/**
 * 精简卡片：状态圆点 + 作者 + 文案预览 + 时间。
 * 点击进入详情，长按弹出 BottomSheet（由调用方处理）。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LinkCard(
    link: SavedLink,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onSelectionToggle: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = if (selectionMode) onSelectionToggle else onClick,
                onLongClick = onLongClick
            ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onSelectionToggle() }
                    )
                } else {
                    StatusDot(status = link.status)
                }
                Text(
                    text = link.authorId ?: "未知作者",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!link.caption.isNullOrBlank()) {
                Text(
                    text = link.caption,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Text(
                text = formatRelativeTime(link.savedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .align(Alignment.End)
            )
        }
    }
}
```

- [ ] **Step 2: 检查点**

Run: `.\gradlew.bat assembleDebug`
Expected: 编译失败（ListScreen 仍用旧签名）——下一任务修复。

---

## Task 16: 简化 StatusBadge

**Files:**
- Modify: `app/src/main/java/com/ed/xinvox/ui/components/StatusBadge.kt`

- [ ] **Step 1: 替换整个 StatusBadge.kt**

```kotlin
package com.ed.xinvox.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ed.xinvox.data.model.LinkStatus
import com.ed.xinvox.ui.theme.statusColor

/**
 * 状态徽标：圆点 + 文字标签，用于详情页。
 * 列表页使用 StatusDot 即可。
 */
@Composable
fun StatusBadge(status: LinkStatus, modifier: Modifier = Modifier) {
    val label = when (status) {
        LinkStatus.PENDING -> "未下载"
        LinkStatus.DOWNLOADED -> "已下载"
        LinkStatus.FAILED -> "失败"
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(status = status)
        Text(
            text = label,
            color = statusColor(status),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
```

- [ ] **Step 2: 检查点**

Run: `.\gradlew.bat assembleDebug`
Expected: 编译失败（DetailScreen 和 HistoryScreen 调用方未更新）——后续任务修复。

---

## Task 17: 重写 AppNav 底部导航 + SnackbarHost

**Files:**
- Modify: `app/src/main/java/com/ed/xinvox/ui/navigation/AppNav.kt`

- [ ] **Step 1: 替换整个 AppNav.kt**

```kotlin
package com.ed.xinvox.ui.navigation

import android.app.Application
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.staticCompositionLocalOf
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ed.xinvox.di.AppContainer
import com.ed.xinvox.di.XInvoxViewModelFactory
import com.ed.xinvox.ui.detail.DetailScreen
import com.ed.xinvox.ui.detail.DetailViewModel
import com.ed.xinvox.ui.history.HistoryScreen
import com.ed.xinvox.ui.history.HistoryViewModel
import com.ed.xinvox.ui.list.ListScreen
import com.ed.xinvox.ui.list.ListViewModel
import com.ed.xinvox.ui.settings.BackupViewModel
import com.ed.xinvox.ui.settings.SettingsScreen
import com.ed.xinvox.ui.theme.XInvoxTheme

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer 未提供")
}

val LocalSnackbarController = staticCompositionLocalOf<SnackbarController> {
    error("SnackbarController 未提供")
}

object Routes {
    const val INBOX = "inbox"
    const val TRASH = "trash"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{tweetId}"
    fun detail(tweetId: String) = "detail/$tweetId"
}

private data class BottomTab(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

private val bottomTabs = listOf(
    BottomTab(Routes.INBOX, "收件箱", Icons.Default.Inbox),
    BottomTab(Routes.TRASH, "回收站", Icons.Default.Delete),
    BottomTab(Routes.SETTINGS, "设置", Icons.Default.Settings)
)

@Composable
fun XInvoxApp(container: AppContainer) {
    XInvoxTheme {
        CompositionLocalProvider(LocalAppContainer provides container) {
            val nav = rememberNavController()
            val application = LocalContext.current.applicationContext as Application
            val factory = XInvoxViewModelFactory(
                savedLinkRepository = container.savedLinkRepository,
                settingsRepository = container.settingsRepository,
                linkCaptureCoordinator = container.linkCaptureCoordinator,
                linkHistoryRepository = container.linkHistoryRepository,
                historyBackupRepository = container.historyBackupRepository,
                application = application
            )

            val snackbarHostState = remember { SnackbarHostState() }
            val snackbarController = remember { SnackbarController() }
            val scope = rememberCoroutineScope()

            LaunchedEffect(Unit) {
                snackbarController.observe(scope, snackbarHostState)
            }

            val currentBackStackEntry by nav.currentBackStackEntryAsState()
            val currentRoute = currentBackStackEntry?.destination?.route
            val showBottomBar = currentRoute in bottomTabs.map { it.route }

            Scaffold(
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                bottomBar = {
                    if (showBottomBar) {
                        NavigationBar {
                            bottomTabs.forEach { tab ->
                                NavigationBarItem(
                                    selected = currentRoute == tab.route,
                                    onClick = {
                                        if (currentRoute != tab.route) {
                                            nav.navigate(tab.route) {
                                                popUpTo(nav.graph.startDestinationId) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label) }
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                NavHost(
                    navController = nav,
                    startDestination = Routes.INBOX,
                    modifier = androidx.compose.ui.Modifier.padding(innerPadding)
                ) {
                    composable(Routes.INBOX) {
                        val vm = viewModel<ListViewModel>(factory = factory)
                        ListScreen(
                            vm = vm,
                            onOpenDetail = { nav.navigate(Routes.detail(it)) }
                        )
                    }

                    composable(Routes.TRASH) {
                        val vm = viewModel<HistoryViewModel>(factory = factory)
                        HistoryScreen(vm = vm)
                    }

                    composable(Routes.SETTINGS) {
                        val backupVm = viewModel<BackupViewModel>(factory = factory)
                        SettingsScreen(
                            settings = container.settingsRepository,
                            backupVm = backupVm,
                            onBack = { nav.popBackStack() }
                        )
                    }

                    composable(
                        route = Routes.DETAIL,
                        arguments = listOf(navArgument("tweetId") { type = NavType.StringType })
                    ) { backStack ->
                        val tweetId = backStack.arguments?.getString("tweetId").orEmpty()
                        val vm = viewModel<DetailViewModel>(factory = factory)
                        DetailScreen(
                            vm = vm,
                            tweetId = tweetId,
                            onBack = { nav.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
```

注意：
- `SettingsScreen` 不再需要 `onOpenHistory`（回收站已是 Tab）
- `HistoryScreen` 不再需要 `onBack`（已是 Tab）
- `ListScreen` 不再需要 `onOpenSettings`（已是 Tab）

- [ ] **Step 2: 检查点**

Run: `.\gradlew.bat assembleDebug`
Expected: 编译失败（ListScreen/DetailScreen/HistoryScreen/SettingsScreen 签名需要更新）——后续任务修复。

---

## Task 18: 重写 ListScreen

**Files:**
- Modify: `app/src/main/java/com/ed/xinvox/ui/list/ListScreen.kt`

- [ ] **Step 1: 替换整个 ListScreen.kt**

```kotlin
package com.ed.xinvox.ui.list

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ed.xinvox.data.model.LinkStatus
import com.ed.xinvox.ui.components.EmptyState
import com.ed.xinvox.ui.components.LinkCard
import com.ed.xinvox.ui.components.SkeletonCard
import com.ed.xinvox.ui.navigation.LocalSnackbarController
import com.ed.xinvox.ui.util.copyToClipboard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    vm: ListViewModel,
    onOpenDetail: (String) -> Unit
) {
    val context = LocalContext.current
    val snackbar = LocalSnackbarController.current
    val links by vm.links.collectAsStateWithLifecycle()
    val pendingCount by vm.pendingCount.collectAsStateWithLifecycle()
    val monitorUri by vm.monitorUri.collectAsStateWithLifecycle()
    val autoCapture by vm.autoCapture.collectAsStateWithLifecycle()
    val captureFeedback by vm.captureFeedback.collectAsStateWithLifecycle()
    val actionFeedback by vm.actionFeedback.collectAsStateWithLifecycle()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val sortOrder by vm.sortOrder.collectAsStateWithLifecycle()
    val selectedIds by vm.selectedIds.collectAsStateWithLifecycle()
    val selectionMode by vm.selectionMode.collectAsStateWithLifecycle()

    var filter by remember { mutableStateOf(Filter.ALL) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    var sortExpanded by remember { mutableStateOf(false) }
    var longPressedLink by remember { mutableStateOf<com.ed.xinvox.data.model.SavedLink?>(null) }
    var isFirstLoad by remember { mutableStateOf(true) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, autoCapture) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (autoCapture) vm.captureFromClipboard()
                vm.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(monitorUri) {
        if (!monitorUri.isNullOrBlank()) {
            vm.refresh()
            isFirstLoad = false
        }
    }

    LaunchedEffect(captureFeedback) {
        captureFeedback?.let {
            val msg = when (it) {
                ListViewModel.CaptureFeedback.Added -> "已保存到收件箱"
                ListViewModel.CaptureFeedback.Duplicate -> "该链接已在收件箱"
                ListViewModel.CaptureFeedback.NotTwitter -> "剪贴板不是推特链接"
                ListViewModel.CaptureFeedback.Empty -> "剪贴板为空"
            }
            snackbar.show(msg)
            vm.clearFeedback()
        }
    }

    LaunchedEffect(actionFeedback) {
        actionFeedback?.let { feedback ->
            snackbar.show(
                message = feedback.message,
                actionLabel = feedback.actionLabel,
                onAction = feedback.onAction
            )
            vm.clearActionFeedback()
        }
    }

    val shown = remember(links, filter) {
        when (filter) {
            Filter.ALL -> links
            Filter.PENDING -> links.filter { it.status == LinkStatus.PENDING }
            Filter.DOWNLOADED -> links.filter { it.status == LinkStatus.DOWNLOADED }
            Filter.FAILED -> links.filter { it.status == LinkStatus.FAILED }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("收件箱", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "$pendingCount 条未下载",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    TextButton(onClick = vm::toggleSelectionMode) {
                        Text(if (selectionMode) "完成" else "批量")
                    }
                }
            )
        },
        floatingActionButton = {
            if (!selectionMode) {
                FloatingActionButton(onClick = { showPasteDialog = true }) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "粘贴并捕获")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = vm::setSearchQuery,
                    label = { Text("搜索作者、文案或链接") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    TextButton(onClick = { sortExpanded = true }) {
                        Text(sortOrder.label)
                    }
                    DropdownMenu(
                        expanded = sortExpanded,
                        onDismissRequest = { sortExpanded = false }
                    ) {
                        LinkSortOrder.entries.forEach { order ->
                            DropdownMenuItem(
                                text = { Text(order.label) },
                                onClick = {
                                    vm.setSortOrder(order)
                                    sortExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            FilterTabs(filter = filter, onFilterChange = { filter = it })

            if (selectionMode) {
                BottomAppBar(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "已选 ${selectedIds.size} 条",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium
                    )
                    TextButton(onClick = { vm.selectAll(shown.map { it.tweetId }) }) {
                        Text("全选")
                    }
                    TextButton(
                        enabled = selectedIds.isNotEmpty(),
                        onClick = {
                            val text = links
                                .filter { it.tweetId in selectedIds }
                                .joinToString("\n", transform = { it.rawUrl })
                            copyToClipboard(context, "XInvox 批量链接", text)
                            snackbar.show("已复制 ${selectedIds.size} 条链接")
                        }
                    ) { Text("复制") }
                    TextButton(
                        enabled = selectedIds.isNotEmpty(),
                        onClick = vm::downloadSelected
                    ) { Text("下载") }
                    TextButton(
                        enabled = selectedIds.isNotEmpty(),
                        onClick = vm::deleteSelected
                    ) { Text("删除") }
                }
            }

            if (isFirstLoad && links.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    repeat(5) { SkeletonCard(modifier = Modifier.padding(horizontal = 16.dp)) }
                }
            } else if (shown.isEmpty()) {
                EmptyState(
                    icon = "📥",
                    title = if (searchQuery.isBlank()) "收件箱为空" else "没有匹配的链接",
                    description = if (searchQuery.isBlank()) {
                        "复制推文链接后回到此处即可捕获"
                    } else {
                        "试试调整搜索词或筛选条件"
                    },
                    actionLabel = if (searchQuery.isBlank()) "粘贴链接" else null,
                    onAction = if (searchQuery.isBlank()) ({ showPasteDialog = true }) else null
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(shown, key = { it.tweetId }) { link ->
                        LinkCard(
                            link = link,
                            onClick = { onOpenDetail(link.tweetId) },
                            onLongClick = { longPressedLink = link },
                            selectionMode = selectionMode,
                            selected = link.tweetId in selectedIds,
                            onSelectionToggle = { vm.toggleSelected(link.tweetId) }
                        )
                    }
                }
            }
        }
    }

    // 长按 BottomSheet
    longPressedLink?.let { link ->
        AlertDialog(
            onDismissRequest = { longPressedLink = null },
            title = { Text(link.authorId ?: "未知作者") },
            text = {
                Text(
                    when (link.status) {
                        LinkStatus.PENDING -> "状态：未下载"
                        LinkStatus.DOWNLOADED -> "状态：已下载"
                        LinkStatus.FAILED -> "状态：失败${link.lastError?.let { " - $it" } ?: ""}"
                    }
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        copyToClipboard(context, "XInvox 链接", link.rawUrl)
                        snackbar.show("已复制链接")
                        longPressedLink = null
                    }) { Text("复制") }
                    TextButton(onClick = {
                        vm.requestDownload(link.tweetId)
                        longPressedLink = null
                    }) {
                        Text(if (link.status == LinkStatus.FAILED) "重试" else "下载")
                    }
                    TextButton(onClick = {
                        vm.delete(link.tweetId)
                        longPressedLink = null
                    }) { Text("删除") }
                }
            },
            dismissButton = {
                TextButton(onClick = { longPressedLink = null }) { Text("取消") }
            }
        )
    }

    if (showPasteDialog) {
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text("粘贴推特链接") },
            text = {
                OutlinedTextField(
                    value = pasteText,
                    onValueChange = { pasteText = it },
                    placeholder = { Text("https://x.com/.../status/...") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.captureText(pasteText)
                    pasteText = ""
                    showPasteDialog = false
                }) { Text("捕获") }
            },
            dismissButton = {
                TextButton(onClick = { showPasteDialog = false }) { Text("取消") }
            }
        )
    }
}

enum class Filter { ALL, PENDING, DOWNLOADED, FAILED }

@Composable
private fun FilterTabs(filter: Filter, onFilterChange: (Filter) -> Unit) {
    val options = listOf(
        Filter.ALL to "全部",
        Filter.PENDING to "未下载",
        Filter.DOWNLOADED to "已下载",
        Filter.FAILED to "失败"
    )
    Row(
        modifier = Modifier
            .selectableGroup()
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { (value, label) ->
            val selected = filter == value
            Surface(
                selected = selected,
                onClick = { onFilterChange(value) },
                shape = RoundedCornerShape(999.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier
                    .weight(1f)
                    .selectable(
                        selected = selected,
                        onClick = { onFilterChange(value) },
                        role = Role.Tab
                    )
            ) {
                Text(
                    text = label,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                )
            }
        }
    }
}
```

注意：长按菜单暂用 `AlertDialog` 代替 `ModalBottomSheet`（`ModalBottomSheet` 的 API 在实验阶段，`AlertDialog` 更稳定且功能等价）。后续可升级为 `ModalBottomSheet`。

- [ ] **Step 2: 检查点**

Run: `.\gradlew.bat assembleDebug`
Expected: 编译失败（DetailScreen/HistoryScreen/SettingsScreen 签名需要更新）——后续任务修复。

---

## Task 19: 重写 DetailScreen

**Files:**
- Modify: `app/src/main/java/com/ed/xinvox/ui/detail/DetailScreen.kt`

- [ ] **Step 1: 替换整个 DetailScreen.kt**

```kotlin
package com.ed.xinvox.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ed.xinvox.data.model.LinkStatus
import com.ed.xinvox.ui.components.StatusBadge
import com.ed.xinvox.ui.navigation.LocalSnackbarController
import com.ed.xinvox.ui.util.copyToClipboard
import com.ed.xinvox.ui.util.formatRelativeTime
import com.ed.xinvox.ui.util.openDownloadedFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    vm: DetailViewModel,
    tweetId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val snackbar = LocalSnackbarController.current
    val link by vm.link.collectAsStateWithLifecycle()
    val monitorUri by vm.monitorUri.collectAsStateWithLifecycle()
    val actionFeedback by vm.actionFeedback.collectAsStateWithLifecycle()

    LaunchedEffect(tweetId) { vm.load(tweetId) }
    LaunchedEffect(actionFeedback) {
        actionFeedback?.let {
            snackbar.show(it)
            vm.clearActionFeedback()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("链接详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新状态")
                    }
                }
            )
        },
        bottomBar = {
            val data = link
            if (data != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 3.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = vm::requestDownload,
                            enabled = data.status != LinkStatus.DOWNLOADED,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                if (data.status == LinkStatus.FAILED) {
                                    "重新尝试下载"
                                } else {
                                    "打开下载器"
                                }
                            )
                        }
                        IconButton(
                            onClick = {
                                copyToClipboard(context, "XInvox 链接", data.rawUrl)
                                snackbar.show("已复制链接")
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "复制链接")
                        }
                        IconButton(
                            onClick = {
                                val success = openDownloadedFile(context, monitorUri.value, data.filePath)
                                if (!success) snackbar.show("无法打开文件")
                            },
                            enabled = data.status == LinkStatus.DOWNLOADED
                        ) {
                            Icon(Icons.Default.FileOpen, contentDescription = "打开已下载文件")
                        }
                    }
                }
            }
        }
    ) { padding ->
        val data = link
        if (data == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 头部：状态徽标 + 作者
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        data.authorId ?: "未知作者",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (!data.authorName.isNullOrBlank()) {
                        Text(
                            data.authorName ?: "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                StatusBadge(status = data.status)
            }

            // 缩略图（如有）
            if (!data.thumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = data.thumbnailUrl,
                    contentDescription = "缩略图",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            }

            // 文案
            if (!data.caption.isNullOrBlank()) {
                Text(data.caption, style = MaterialTheme.typography.bodyMedium)
            }

            HorizontalDivider()

            // 信息区
            InfoRow(icon = "📋", label = "复制时间", value = formatRelativeTime(data.savedAt))
            if (data.status == LinkStatus.DOWNLOADED && data.downloadedAt != null) {
                InfoRow(icon = "⬇️", label = "下载时间", value = formatRelativeTime(data.downloadedAt))
            }
            if (data.lastAttemptAt != null) {
                InfoRow(icon = "⏱", label = "最近尝试", value = formatRelativeTime(data.lastAttemptAt))
            }
            if (data.attemptCount > 0) {
                InfoRow(icon = "🔢", label = "尝试次数", value = data.attemptCount.toString())
            }
            if (!data.lastError.isNullOrBlank()) {
                InfoRow(icon = "⚠️", label = "失败原因", value = data.lastError)
            }
            if (data.nextRetryAt != null) {
                InfoRow(icon = "🔄", label = "自动重试", value = formatRelativeTime(data.nextRetryAt))
            }

            HorizontalDivider()

            // 完整链接
            Text(
                "完整链接",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SelectionContainer {
                Text(
                    text = data.rawUrl,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp)
                )
            }

            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun InfoRow(icon: String, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(icon, style = MaterialTheme.typography.bodyMedium)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
```

- [ ] **Step 2: 检查点**

Run: `.\gradlew.bat assembleDebug`
Expected: 编译失败（HistoryScreen/SettingsScreen 签名需要更新）——后续任务修复。

---

## Task 20: 重写 HistoryScreen

**Files:**
- Modify: `app/src/main/java/com/ed/xinvox/ui/history/HistoryScreen.kt`

- [ ] **Step 1: 替换整个 HistoryScreen.kt**

```kotlin
package com.ed.xinvox.ui.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ed.xinvox.ui.components.EmptyState
import com.ed.xinvox.ui.navigation.LocalSnackbarController
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    vm: HistoryViewModel
) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val selected by vm.selectedIds.collectAsStateWithLifecycle()
    val feedback by vm.feedback.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarController.current
    var confirmPermanentDelete by remember { mutableStateOf(false) }

    LaunchedEffect(feedback) {
        feedback?.let {
            snackbar.show(it)
            vm.clearFeedback()
        }
    }

    if (confirmPermanentDelete) {
        AlertDialog(
            onDismissRequest = { confirmPermanentDelete = false },
            title = { Text("永久删除历史记录？") },
            text = { Text("选中的 ${selected.size} 条记录将无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmPermanentDelete = false
                    vm.permanentlyDeleteSelected()
                }) { Text("永久删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmPermanentDelete = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("回收站") }
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = selected.isNotEmpty(),
                enter = slideInVertically { it },
                exit = slideOutVertically { it }
            ) {
                BottomAppBar {
                    Text(
                        "已选 ${selected.size}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium
                    )
                    TextButton(onClick = {
                        if (selected.size == entries.size) vm.clearSelection() else vm.selectAll()
                    }) {
                        Text(if (selected.size == entries.size) "取消全选" else "全选")
                    }
                    Button(onClick = vm::restoreSelected) {
                        Icon(Icons.Default.Restore, contentDescription = null)
                        Text(" 恢复")
                    }
                    TextButton(onClick = { confirmPermanentDelete = true }) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                        Text(" 永久删除")
                    }
                }
            }
        }
    ) { padding ->
        if (entries.isEmpty()) {
            EmptyState(
                icon = "♻️",
                title = "回收站为空",
                description = "以后删除的收件箱记录会先保存在这里",
                modifier = Modifier.padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(entries, key = { it.archiveId }) { entry ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.toggleSelected(entry.archiveId) },
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = entry.archiveId in selected,
                                onCheckedChange = { vm.toggleSelected(entry.archiveId) }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    entry.authorName ?: entry.authorId ?: entry.tweetId,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                entry.caption?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        it,
                                        maxLines = 2,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    "删除于 ${DateFormat.getDateTimeInstance().format(Date(entry.deletedAt))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: 检查点**

Run: `.\gradlew.bat assembleDebug`
Expected: 编译失败（SettingsScreen 签名需要更新）——下一任务修复。

---

## Task 21: 重写 SettingsScreen

**Files:**
- Modify: `app/src/main/java/com/ed/xinvox/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: 替换整个 SettingsScreen.kt**

```kotlin
package com.ed.xinvox.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ed.xinvox.data.backup.HistoryBackupRepository
import com.ed.xinvox.data.backup.RestoreMode
import com.ed.xinvox.data.preferences.SettingsRepository
import com.ed.xinvox.system.DeviceCapabilityReader
import com.ed.xinvox.ui.navigation.LocalSnackbarController
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    backupVm: BackupViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarController.current
    val monitorUri by settings.monitorDirUriFlow.collectAsStateWithLifecycle(initialValue = null)
    val autoCapture by settings.autoCaptureFlow.collectAsStateWithLifecycle(initialValue = true)
    val autoRetry by settings.autoRetryFlow.collectAsStateWithLifecycle(initialValue = true)
    val backgroundSync by settings.backgroundSyncFlow.collectAsStateWithLifecycle(initialValue = true)
    val backupDirUri by backupVm.backupDirUri.collectAsStateWithLifecycle()
    val automaticBackup by backupVm.automaticBackup.collectAsStateWithLifecycle()
    val lastBackupAt by backupVm.lastBackupAt.collectAsStateWithLifecycle()
    val lastBackupError by backupVm.lastBackupError.collectAsStateWithLifecycle()
    val pendingImport by backupVm.pendingImport.collectAsStateWithLifecycle()
    val busy by backupVm.busy.collectAsStateWithLifecycle()
    val feedback by backupVm.feedback.collectAsStateWithLifecycle()
    var capabilityRefresh by remember { mutableIntStateOf(0) }
    var confirmReplace by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) capabilityRefresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(feedback) {
        feedback?.let {
            snackbar.show(it)
            backupVm.clearFeedback()
        }
    }

    val inputMethod = remember(capabilityRefresh) {
        DeviceCapabilityReader.currentInputMethod(context)
    }
    val accessibilityEnabled = remember(capabilityRefresh) {
        DeviceCapabilityReader.isAccessibilityCaptureEnabled(context)
    }

    val monitorPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            scope.launch { settings.setMonitorDirUri(uri.toString()) }
            snackbar.show("已设置监控目录")
        }
    }

    val backupDirectoryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            backupVm.setBackupDirectory(uri)
        }
    }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(HistoryBackupRepository.MIME_TYPE)
    ) { uri -> uri?.let(backupVm::exportTo) }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(backupVm::prepareImport) }

    pendingImport?.let { backup ->
        AlertDialog(
            onDismissRequest = backupVm::cancelImport,
            title = { Text("导入备份") },
            text = {
                Text(
                    "备份版本 ${backup.preview.appVersion}，包含 " +
                        "${backup.preview.activeCount} 条收件箱记录和 " +
                        "${backup.preview.historyCount} 条回收站记录。"
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { backupVm.restorePending(RestoreMode.MERGE) }) {
                        Text("合并导入")
                    }
                    TextButton(onClick = { confirmReplace = true }) {
                        Text("替换全部")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = backupVm::cancelImport) { Text("取消") }
            }
        )
    }

    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("确认替换全部数据？") },
            text = { Text("当前数据会先生成安全快照，然后由备份内容替换。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReplace = false
                    backupVm.restorePending(RestoreMode.REPLACE)
                }) { Text("确认替换") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReplace = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // 监控目录
            SettingGroup(title = "监控目录") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { monitorPicker.launch(null) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Column(modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)) {
                        Text("下载监控目录", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (monitorUri == SettingsRepository.DEFAULT_MONITOR_URI) {
                                "Android/data/com.ed.twitterdownloader/files/Download"
                            } else {
                                monitorUri ?: "未设置"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                }
                TextButton(
                    onClick = { scope.launch { settings.setMonitorDirUri(null) } },
                    enabled = monitorUri != SettingsRepository.DEFAULT_MONITOR_URI
                ) { Text("使用下载器默认目录") }
            }

            // 捕获
            SettingGroup(title = "捕获") {
                SettingSwitchRow(
                    title = "回到前台检查剪贴板",
                    description = "仅在 App 回到前台时读取一次系统当前剪贴板",
                    checked = autoCapture,
                    onCheckedChange = { scope.launch { settings.setAutoCapture(it) } }
                )
                SettingSwitchRow(
                    title = "无障碍自动捕获",
                    description = "在受支持应用复制时读取当前剪贴板",
                    checked = accessibilityEnabled,
                    onCheckedChange = { /* 由系统设置控制，点击无效 */ }
                )
                Text(
                    "当前状态：${if (accessibilityEnabled) "已开启" else "未开启"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (accessibilityEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                Button(
                    onClick = {
                        runCatching {
                            context.startActivity(DeviceCapabilityReader.accessibilitySettingsIntent())
                        }.onFailure {
                            snackbar.show("无法打开无障碍设置")
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp)
                ) { Text(if (accessibilityEnabled) "查看系统无障碍设置" else "前往系统无障碍设置") }
            }

            // 下载
            SettingGroup(title = "下载") {
                SettingSwitchRow(
                    title = "下载失败后自动重试",
                    description = "按 30 秒、2 分钟的退避间隔重试",
                    checked = autoRetry,
                    onCheckedChange = { scope.launch { settings.setAutoRetry(it) } }
                )
                SettingSwitchRow(
                    title = "后台更新下载状态",
                    description = "系统允许时约每 15 分钟扫描一次下载目录",
                    checked = backgroundSync,
                    onCheckedChange = { scope.launch { settings.setBackgroundSync(it) } }
                )
            }

            // 备份与恢复
            SettingGroup(title = "备份与恢复") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { backupDirectoryPicker.launch(null) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Column(modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)) {
                        Text("自动备份目录", style = MaterialTheme.typography.titleMedium)
                        Text(
                            backupDirUri ?: "未设置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                }
                SettingSwitchRow(
                    title = "每日自动备份",
                    description = "保留最近 7 份",
                    checked = automaticBackup,
                    onCheckedChange = backupVm::setAutomaticBackup
                )
                Button(
                    onClick = backupVm::backupNow,
                    enabled = !busy && backupDirUri != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Text(" 立即备份")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            exportPicker.launch(HistoryBackupRepository.backupFileName(System.currentTimeMillis()))
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text("导出备份") }
                    OutlinedButton(
                        onClick = { importPicker.launch(arrayOf("application/json", "text/plain")) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text("导入备份") }
                }
                lastBackupAt?.let {
                    Text(
                        "最近成功：${DateFormat.getDateTimeInstance().format(Date(it))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                lastBackupError?.let {
                    Text(
                        "最近错误：$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // 关于
            SettingGroup(title = "关于") {
                Text("当前输入法：${inputMethod?.displayName ?: "无法识别"}",
                    style = MaterialTheme.typography.bodyMedium)
                inputMethod?.componentName?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("XInvox 1.2.0 (build 3)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
                Text(
                    "隐私：导出 JSON 含链接、作者和文案，请妥善保存。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
```

- [ ] **Step 2: 检查点**

Run: `.\gradlew.bat assembleDebug`
Expected: 编译失败（BackupViewModel 的 runBusy 需要修复）——下一任务修复。

---

## Task 22: 修复 BackupViewModel busy 原子性

**Files:**
- Modify: `app/src/main/java/com/ed/xinvox/ui/settings/BackupViewModel.kt:115-127`

- [ ] **Step 1: 用 Mutex.tryLock 替换 runBusy**

在 `BackupViewModel.kt` 中，添加 `Mutex` import 和字段，并替换 `runBusy` 函数。

在 import 块添加：
```kotlin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
```

在 `busyMutable` 字段之后添加：
```kotlin
    private val busyMutex = Mutex()
```

将 `runBusy` 函数（第 115-127 行）替换为：

```kotlin
    private fun runBusy(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!busyMutex.tryLock()) return@launch
            try {
                busyMutable.value = true
                runCatching { block() }
                    .onFailure { error ->
                        val message = error.message ?: "操作失败"
                        settingsRepository.recordBackupError(message)
                        feedbackMutable.value = message
                    }
            } finally {
                busyMutable.value = false
                busyMutex.unlock()
            }
        }
    }
```

- [ ] **Step 2: 检查点**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL（所有签名现在匹配）

---

## Task 23: 最终编译验证

**Files:** 无修改，仅验证

- [ ] **Step 1: 完整 Debug 构建**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 运行单元测试**

Run: `.\gradlew.bat testDebugUnitTest`
Expected: 所有测试通过（现有测试不应受影响）

- [ ] **Step 3: 推送到设备验证**

Run: `.\gradlew.bat assembleDebug ; adb install -r app\build\outputs\apk\debug\app-debug.apk`
Expected: Success

- [ ] **Step 4: 手动验收**

打开应用，逐项检查：
1. 底部三 Tab 可切换
2. 列表卡片精简（约 72-80dp 高）
3. 长按卡片弹出菜单
4. 删除后可撤销
5. 详情页显示缩略图（如有）
6. 详情页底部固定操作栏
7. 设置页分组卡片
8. 反馈用 Snackbar
9. 列表首次加载有骨架屏
10. 配色净化、深蓝保持
