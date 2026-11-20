package com.ed.edqiu.ui.privacy

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
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
 * 整卡/详情共用：持续按住 3 秒才显示，松手、滚动接管或离开立即复位。
 * 使用指针事件作用域的超时，同时监听松手/取消；null 取消不能当成超时显示。
 * 有 onTap 时接管整卡短按，无 onTap 时保留子控件点击与父级滚动。
 */
@Composable
fun Modifier.privacyRevealGesture(
    enabled: Boolean,
    gestureKey: Any?,
    onRevealChange: (Boolean) -> Unit,
    onTap: (() -> Unit)? = null,
): Modifier {
    val currentOnRevealChange by rememberUpdatedState(onRevealChange)
    val currentOnTap by rememberUpdatedState(onTap)
    return this.pointerInput(enabled, gestureKey) {
        try {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                // 详情只观察按下，不抢走按钮短按；整卡则沿用点击手势的消费规则。
                val down = awaitFirstDown(
                    pass = if (currentOnTap != null) PointerEventPass.Main else PointerEventPass.Initial,
                )
                if (currentOnTap != null) down.consume()
                try {
                    var finished = false
                    val up = withTimeoutOrNull(3000L) {
                        val result = waitForUpOrCancellation()
                        finished = true
                        result
                    }
                    if (finished) {
                        // 松手才允许短按；取消（null）不得点击或显示。
                        if (up != null && currentOnTap != null) {
                            up.consume()
                            currentOnTap?.invoke()
                        }
                    } else {
                        currentOnRevealChange(true)
                        // 满 3 秒后只负责收尾，绝不再派发整卡点击。
                        // Initial 阶段消费松手，避免子按钮先收到 up 而误触点击。
                        waitForUpOrCancellation(pass = PointerEventPass.Initial)?.consume()
                    }
                } finally {
                    currentOnRevealChange(false)
                }
            }
        } finally {
            // pointerInput 因 key/模式变化或组件离开被取消时也必须隐藏。
            currentOnRevealChange(false)
        }
    }
}

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

/**
 * 隐私模式模糊容器（无手势版，2026-11-12）：模糊/蒙层逻辑与 [PrivacyGateBox]
 * 完全一致，但不自带「按住查看」手势——reveal 由外层（如 LinkCard 整卡）统一控制，
 * 避免卡片内多段文字/缩略图各自挂手势产生嵌套手势竞争。`enabled` 直接表达
 * 「当前是否应模糊」（已含 reveal 判断，外部传 `隐私模式 && 未按住`）。
 */
@Composable
fun PrivacyBlurBox(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    fallbackIcon: ImageVector = Icons.Outlined.VisibilityOff,
    content: @Composable BoxScope.() -> Unit,
) {
    val legacyMask = enabled && Build.VERSION.SDK_INT < 31
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .privacyBlur(enabled),
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

/**
 * 隐私模式文字门控（2026-11-12）：`enabled` 时用占位符替换真实文字，防偷看
 * （连文字长度都不泄露）；占位符沿用原 [Text] 的样式参数，切换不引起布局跳动。
 * 真实文字的显隐由外部 reveal 状态控制（`enabled` = 隐私模式 && 未按住查看）。
 */
@Composable
fun PrivacyText(
    text: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
    placeholder: String = "••••••",
) {
    Text(
        text = if (enabled) placeholder else text,
        modifier = modifier,
        style = style,
        color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        lineHeight = lineHeight,
        maxLines = maxLines,
        minLines = minLines,
        overflow = overflow,
        textAlign = textAlign,
    )
}
