package com.ed.edqiu.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 玻璃层级（设计文档 §3.2）
 *
 * - L1 页面层：列表、卡片所在区域（大面玻璃，重透）
 * - L2 浮层：底部导航栏、播放器控制层（悬浮玻璃，重模糊）
 * - L3 弹出层：BottomSheet、Snackbar（最高层玻璃）
 */
enum class GlassTier(val bgAlpha: Float, val edgeAlpha: Float) {
    L1(0.62f, 0.35f),
    L2(0.72f, 0.45f),
    L3(0.84f, 0.50f)
}

/**
 * 毛玻璃表面组件（iOS Liquid Glass 质感模拟）。
 *
 * 特征：
 * 1. 背景渐变折射（上部偏亮 + 底部微暗，模拟玻璃曲率）
 * 2. 内外双层描边（内高光白 + 外细边，Liquid 灵魂）
 * 3. 悬浮阴影（L2/L3 带 elevation，浮起感）
 * 4. 顶部折射高光线
 *
 * @param tier 玻璃层级
 * @param shape 玻璃形状
 * @param elevated 是否带悬浮阴影（默认 true）
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    tier: GlassTier = GlassTier.L1,
    shape: Shape = RoundedCornerShape(24.dp),
    tint: Color = MaterialTheme.colorScheme.surfaceContainer,
    elevated: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val dark = MaterialTheme.colorScheme.background.luminance() <= 0.5f
    // 玻璃底色 = 半透明白（主体）+ seed 色 tint（透出莫奈色彩）
    val base = if (dark) {
        Color(0xFF1A1D21).copy(alpha = tier.bgAlpha + 0.10f)
    } else {
        Color.White.copy(alpha = tier.bgAlpha)
    }
    // tint 叠加：让玻璃带上当前莫奈色（动态取色/种子色变化时玻璃同步变色）
    // 权重偏低——让背景色更透出（玻璃才看得出"色彩内容"）
    val glassColor = androidx.compose.ui.graphics.lerp(
        base,
        tint.copy(alpha = 0.12f),
        if (dark) 0.28f else 0.16f
    )

    var m = modifier.clip(shape)
    if (elevated) {
        // 悬浮阴影：iOS 风格的下投阴影
        m = m.shadow(
            elevation = if (tier == GlassTier.L1) 2.dp else 12.dp,
            shape = shape,
            ambientColor = Color.Black.copy(alpha = if (dark) 0.35f else 0.12f),
            spotColor = Color.Black.copy(alpha = if (dark) 0.45f else 0.16f)
        )
    }

    Box(modifier = m.background(glassColor)) {
        // 背景渐变折射：上部亮 + 底部微暗
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = if (dark) 0.06f else 0.42f),
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = if (dark) 0.10f else 0.03f)
                    )
                )
        )
        // 顶部折射高光线（Liquid 灵魂）
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = tier.edgeAlpha),
                            Color.White.copy(alpha = tier.edgeAlpha * 0.15f),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = 90f
                    )
                )
        )
        // 内高光描边：顶部亮、底部暗（玻璃边缘光）
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (dark) 0.12f else 0.28f),
                            Color.White.copy(alpha = 0.06f),
                            Color.White.copy(alpha = if (dark) 0.04f else 0.10f)
                        ),
                        startY = 0f,
                        endY = 400f
                    )
                )
        )
        content()
    }
}

/**
 * 视频场景专用深色玻璃。
 *
 * 与 [GlassSurface] 的关键区别：
 * - **永远深色底**（黑 60%），不受明暗主题影响
 * - 顶部高光是**白色 18%**（中等强度，不像普通玻璃那么抢戏）
 * - 适合视频播放器玻璃面板——在动态彩色视频画面上保持低调
 *
 * 视频控制层玻璃必须是深色的原因：浅色玻璃在视频上会形成大块白雾，
 * 淹没视频内容焦点；深色玻璃让用户视线锁定在视频本身。
 */
@Composable
fun MediaGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(0.dp),
    content: @Composable BoxScope.() -> Unit
) {
    var m = modifier.clip(shape)
    Box(modifier = m.background(Color.Black.copy(alpha = 0.60f))) {
        // 顶部高光（白色 18%）—— 给深色玻璃一个微妙的"反光"，模拟 iOS 控制层
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.18f),
                            Color.White.copy(alpha = 0.04f),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = 120f
                    )
                )
        )
        // 内部白色描边（顶部 18%，底部 4%）—— Liquid 灵魂在深色底上更精致
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.22f),
                            Color.White.copy(alpha = 0.06f),
                            Color.White.copy(alpha = 0.04f)
                        ),
                        startY = 0f,
                        endY = 60f
                    )
                )
        )
        content()
    }
}

/**
 * L0 背景层：全屏莫奈色域渐变 + 径向光斑。
 *
 * 关键：背景必须「有色彩」——玻璃材质需要可折射的内容，
 * 纯白背景会让玻璃和白纸无异。此层用种子色构建 iOS 壁纸式的彩色氛围：
 * 顶部高饱和 seed 光晕 → 中部 mid 过渡 → 底部 tertiary 微光，
 * 并叠加两个径向光斑增强「玻璃下有内容」的质感。
 *
 * @param seed 莫奈种子色（动态取色时为壁纸色，种子色模式为预设色）
 * @param content 页面内容（玻璃卡片叠放在此之上）
 */
@Composable
fun GlassBackground(
    seed: Color,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val dark = MaterialTheme.colorScheme.background.luminance() <= 0.5f
    val secondary = MaterialTheme.colorScheme.secondaryContainer
    val tertiary = MaterialTheme.colorScheme.tertiaryContainer
    // tint 颜色：优先用 ColorScheme.primary（保证 dynamicColor=true 时背景跟随壁纸）。
    // 仅在 ColorScheme.primary 是默认色（即未启用动态取色）时回退到传入 seed。
    val tintSeed = MaterialTheme.colorScheme.primary
        .takeIf { it != Color.Unspecified && it.alpha > 0f }
        ?: seed

    val bgModifier = if (blurRadius > 0.dp) modifier.blur(blurRadius) else modifier

    Box(
        modifier = bgModifier.fillMaxSize()
    ) {
        // 底层：莫奈色域渐变 —— tint 来自 ColorScheme.primary（动态取色/种子色模式统一从 ColorScheme 取色）
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = if (dark) {
                            listOf(
                                tintSeed.copy(alpha = 0.85f),
                                tintSeed.copy(alpha = 0.55f),
                                tertiary.copy(alpha = 0.55f)
                            )
                        } else {
                            listOf(
                                tintSeed.copy(alpha = 0.88f),
                                tintSeed.copy(alpha = 0.52f),
                                tertiary.copy(alpha = 0.58f)
                            )
                        },
                        startY = 0f,
                        endY = 1400f
                    )
                )
        )
        // 顶部 seed 光晕（让顶部 seed 色更显眼）
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            tintSeed.copy(alpha = if (dark) 0.55f else 0.55f),
                            Color.Transparent
                        ),
                        radius = 1100f
                    )
                )
        )
        // 右下 tertiary 光斑
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            tertiary.copy(alpha = if (dark) 0.32f else 0.32f),
                            Color.Transparent
                        ),
                        center = Offset(900f, 2200f),
                        radius = 900f
                    )
                )
        )
        // 左上 secondary 微光
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            secondary.copy(alpha = if (dark) 0.24f else 0.22f),
                            Color.Transparent
                        ),
                        center = Offset(200f, 500f),
                        radius = 700f
                    )
                )
        )
        content()
    }
}
