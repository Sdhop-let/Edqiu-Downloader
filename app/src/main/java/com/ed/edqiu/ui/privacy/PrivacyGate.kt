package com.ed.edqiu.ui.privacy

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 隐私模式全局开关（2026-11-07）：true = 当前处于隐私模式，
 * 媒体展示面应模糊处理。由 AppNav 按浏览模式注入，媒体面统一读取。
 */
val LocalPrivacyBlur = androidx.compose.runtime.staticCompositionLocalOf { false }

/** 隐私模式媒体模糊半径。API 31+ Modifier.blur 真高斯；Unbounded 边缘 + 外层 clip
 *  是 GlassSurface 磨砂路径验证过的安全结构（2026-10-22「ColorOS 禁 blur」已证伪）。 */
private val PrivacyBlurRadius = 28.dp

/** 媒体面模糊修饰符：API 31+ 真模糊，低版本 no-op（回退交给 PrivacyGateBox 蒙层）。 */
fun Modifier.privacyBlur(enabled: Boolean): Modifier =
    if (enabled && Build.VERSION.SDK_INT >= 31) {
        this.blur(PrivacyBlurRadius, BlurredEdgeTreatment.Unbounded)
    } else this

/**
 * 隐私模式媒体门控容器：内容整体模糊，**按住查看**——手指按住期间临时清晰，
 * 松开（或手势被滚动接管）立即恢复模糊，防手滑误展示。
 *
 * - API 31+：Modifier.blur 真高斯；
 * - API < 31：Modifier.blur 无效，回退表面色蒙层 + 「已隐藏」图标（minSdk 24 兜底）。
 *
 * 内容自身若可点击（如媒体库卡片进播放器），按住查看与点击行为并存：
 * 快速点按 = 短暂清晰 + 触发点击（公共模式下播放器也不自动播，二次防护）。
 */
@Composable
fun PrivacyGateBox(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    fallbackIcon: ImageVector = Icons.Outlined.VisibilityOff,
    content: @Composable BoxScope.() -> Unit,
) {
    var reveal by remember { mutableStateOf(false) }
    val active = enabled && !reveal
    val legacyMask = active && Build.VERSION.SDK_INT < 31

    Box(
        modifier = modifier.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onPress = {
                    reveal = true
                    try {
                        awaitRelease()
                    } finally {
                        reveal = false
                    }
                }
            )
        }
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .privacyBlur(active),
            content = content,
        )
        if (legacyMask) {
            Column(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.86f)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                Icon(
                    imageVector = fallbackIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = "已隐藏",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
