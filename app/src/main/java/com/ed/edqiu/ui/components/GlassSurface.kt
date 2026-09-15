package com.ed.edqiu.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
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
import com.ed.edqiu.ui.theme.ThemeEffects

/**
 * 玻璃层级（设计文档 §3.2；2026-09-14 通透度回调二轮）
 *
 * - L1 页面层：列表、卡片所在区域（大面玻璃，重透）
 * - L2 浮层：底部导航栏、播放器控制层（悬浮玻璃，重模糊）
 * - L3 弹出层：BottomSheet、Snackbar（最高层玻璃）
 *
 * 2026-09-14 二轮降白：一轮回调后实机反馈仍偏白。浅色底再降一档
 * （L1 0.28 / L2 0.42 / L3 0.56），高光再压；同时 GlassBackground
 * 底色渐变加浓，让玻璃"透出彩"而不是"透出白"。
 */
enum class GlassTier(val bgAlpha: Float, val edgeAlpha: Float) {
    L1(0.34f, 0.24f),
    L2(0.46f, 0.30f),
    L3(0.58f, 0.34f)
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
    // 全局效果：液态玻璃开关 + 模糊强度（由 EdqiuApp 顶层注入，实时跟随主题设置）
    val liquidGlass = ThemeEffects.LiquidGlassEnabled.current
    val blurStrength = ThemeEffects.BlurStrength.current

    // 玻璃底色 = 半透明白（主体）+ seed 色 tint（透出莫奈色彩）
    val base = if (dark) {
        Color(0xFF1A1D21).copy(alpha = tier.bgAlpha + 0.10f)
    } else {
        Color.White.copy(alpha = tier.bgAlpha)
    }
    // tint 叠加：让玻璃带上当前莫奈色（动态取色/种子色变化时玻璃同步变色）
    // 模糊强度越高 → tint 越轻，让被模糊的背景色域更透出（磨砂更"透"）
    val glassColor = androidx.compose.ui.graphics.lerp(
        base,
        tint.copy(alpha = 0.12f),
        (if (dark) 0.28f else 0.16f) * (1f - blurStrength * 0.5f)
    )

    // 2026-09-14 顺序修正：shadow 必须在 clip 之前 —— 原 clip→shadow 链把阴影整个裁掉
    //（卡片无浮起感）；正确链 = 先画阴影（不被裁）再裁内容圆角
    var m = modifier
    if (elevated) {
        // 悬浮阴影：iOS 风格的下投阴影
        m = m.shadow(
            elevation = if (tier == GlassTier.L1) 2.dp else 12.dp,
            shape = shape,
            ambientColor = Color.Black.copy(alpha = if (dark) 0.35f else 0.12f),
            spotColor = Color.Black.copy(alpha = if (dark) 0.45f else 0.16f)
        )
    }
    m = m.clip(shape)

    Box(modifier = m.background(glassColor)) {
        if (liquidGlass) {
            // 背景渐变折射：上部亮 + 底部微暗（模拟玻璃曲率）
            // 2026-09-14 三轮：白框根因组合拳——白色装饰层全线再压（镜面 0.12/描边 0.08），
            // 玻璃底色微升（L1 0.34）让高光相对弱化、玻璃呈"整块材质"而非"叠白框"
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = if (dark) 0.03f else 0.10f),
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = if (dark) 0.06f else 0.015f)
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
                                Color.White.copy(alpha = tier.edgeAlpha * 0.7f),
                                Color.White.copy(alpha = tier.edgeAlpha * 0.08f),
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
                                Color.White.copy(alpha = if (dark) 0.06f else 0.08f),
                                Color.White.copy(alpha = 0.03f),
                                Color.White.copy(alpha = if (dark) 0.015f else 0.04f)
                            ),
                            startY = 0f,
                            endY = 400f
                        )
                    )
            )
            // iOS 26 液态玻璃增强：顶部细镜面高光（更亮、更窄，模拟玻璃上沿反射）
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = if (dark) 0.08f else 0.12f),
                                Color.White.copy(alpha = 0.015f),
                                Color.Transparent
                            ),
                            startY = 0f,
                            endY = 40f
                        )
                    )
            )
            // 底部微弱反光（折射光感：玻璃下缘向内容区回弹一点光）
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = if (dark) 0.015f else 0.02f)
                            )
                        )
                    )
            )
            // 磨砂雾感：纯渐变实现（2026-09-14 v1.4.8 移除 blur 修饰符）——
            // blur 的离屏渲染 buffer 在 ColorOS 上合成出方形白色边框（每张玻璃卡下方可见），
            // 且渐变本身已是柔性薄雾，blur 增益极小；移除后视觉几乎无损而根因消除
            if (blurStrength > 0f) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = blurStrength * 0.10f),
                                    Color.Transparent,
                                    Color.White.copy(alpha = blurStrength * 0.04f)
                                ),
                                startY = 0f,
                                endY = 220f
                            )
                        )
                )
            }
        } else {
            // 液态玻璃关闭：哑光近实底（2026-09-14 与开启态拉开区分度）——
            // 底色 alpha 拉到 0.92 几乎不透明，无折射/高光/磨砂层，观感是"普通浅色卡片"
            Box(
                Modifier
                    .matchParentSize()
                    .background(if (dark) Color(0xFF1A1D21).copy(alpha = 0.92f) else Color.White.copy(alpha = 0.92f))
            )
        }
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

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // 背景层（莫奈渐变 + 径向光斑）包在内部 Box 中应用 blur → 仅柔化背景，不影响前景 content
        // 边缘保持默认 clamp：渐变在屏幕边缘连续，clamp 无伪影；Unbounded 会让透明色混入屏幕边缘
        Box(
            modifier = Modifier
                .fillMaxSize()
                .let { if (blurRadius > 0.dp) it.blur(blurRadius) else it }
        ) {
            // 底层：莫奈色域渐变 —— tint 来自 ColorScheme.primary（动态取色/种子色模式统一从 ColorScheme 取色）
            // 2026-09-14 二轮降白：浅色渐变加浓（玻璃透出彩而非白），暗色微调
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = if (dark) {
                                listOf(
                                    tintSeed.copy(alpha = 0.92f),
                                    tintSeed.copy(alpha = 0.62f),
                                    tertiary.copy(alpha = 0.60f)
                                )
                            } else {
                                listOf(
                                    tintSeed.copy(alpha = 0.98f),
                                    tintSeed.copy(alpha = 0.68f),
                                    tertiary.copy(alpha = 0.70f)
                                )
                            },
                            startY = 0f,
                            endY = 2800f
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
                                tintSeed.copy(alpha = 0.60f),
                                Color.Transparent
                            ),
                            radius = 1100f
                        )
                    )
            )
            // 右下 tertiary 光斑（降低 alpha 避免透过底部卡片）
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                tertiary.copy(alpha = 0.18f),
                                Color.Transparent
                            ),
                            center = Offset(900f, 2600f),
                            radius = 700f
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
                                secondary.copy(alpha = 0.26f),
                                Color.Transparent
                            ),
                            center = Offset(200f, 500f),
                            radius = 700f
                        )
                    )
            )
        }
        content()
    }
}
