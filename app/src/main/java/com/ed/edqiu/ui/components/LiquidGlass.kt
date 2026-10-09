package com.ed.edqiu.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.ed.edqiu.ui.theme.ThemeEffects

/**
 * 全局液态玻璃背景（Kyant0/AndroidLiquidGlass "Backdrop" 库驱动）。
 *
 * 由 [com.ed.edqiu.ui.navigation.AppNav.EdqiuApp]（外层，捕获 GlassBackground + 外层 NavHost）
 * 与 [com.ed.edqiu.navigation.AppNavigation]（内层 shell，捕获页面内容）分层提供，
 * 玻璃组件从 CompositionLocal 就近取用：
 * - 底部导航栏 → 内层 shellBackdrop（折射正下方的列表内容）
 * - 胶囊反馈 → 外层 appBackdrop
 *
 * 玻璃组件必须位于捕获链（Modifier.layerBackdrop 节点）之外，否则会把上一帧
 * 自己的绘制采样回来造成 alpha 逐帧累积（玻璃逐步变实色）。
 */
val LocalAppBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** 悬浮玻璃明暗风格。Dark 对应媒体/胶囊场景的深色玻璃语义。 */
enum class OverlayGlassStyle { Light, Dark }

/**
 * 当前是否可走 Backdrop 真折射路径（Android 13+ 且液态玻璃开启且 backdrop 可用）。
 * 供 [GlassOverlaySurface] 之外的定制玻璃（如胶囊反馈深玻璃）做路径分支。
 */
@Composable
fun rememberRealGlassAvailable(): Boolean {
    val backdrop = LocalAppBackdrop.current
    val liquidGlass = ThemeEffects.LiquidGlassEnabled.current
    return backdrop != null && liquidGlass && Build.VERSION.SDK_INT >= 33
}

/**
 * 真液态玻璃悬浮层（iOS 26 Liquid Glass 质感，Backdrop 真折射）。
 *
 * 效果链（库文档规定顺序）：vibrancy（饱和度增强）→ blur（磨砂）→ lens（边缘折射，Android 13+）。
 * Android 13+ 且液态玻璃开关开启且 backdrop 可用时走真折射；
 * 否则回退现有视觉：
 * - [OverlayGlassStyle.Light] → [GlassSurface] 渐变模拟玻璃（L2）
 * - [OverlayGlassStyle.Dark] → 深色玻璃（黑 60% + 高光描边，原 MediaGlassSurface 语义）
 *
 * @param shape 必须 CornerBasedShape（lens 折射要求）
 * @param blurRadius 磨砂强度；传 null（默认）时按全局「模糊强度」滑块取值
 *   （4-20dp，ThemeEffects.GlassFrostStrength），底栏等所有真玻璃表面统一跟随；
 *   特殊场景（如视频控制层需要固定观感）可显式传固定值
 * @param lensHeight 边缘折射带高度，需 ≤ 最小圆角半径
 * @param lensAmount 折射位移量
 */
@Composable
fun GlassOverlaySurface(
    modifier: Modifier = Modifier,
    shape: CornerBasedShape = RoundedCornerShape(24.dp),
    style: OverlayGlassStyle = OverlayGlassStyle.Light,
    blurRadius: Dp? = null,
    lensHeight: Dp = 14.dp,
    lensAmount: Dp = 14.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val backdrop = LocalAppBackdrop.current
    val liquidGlass = ThemeEffects.LiquidGlassEnabled.current
    // 磨砂度随「磨砂强度」取值（0%=通透见底 4dp，100%=重磨砂 20dp）
    val effectiveBlur = blurRadius ?: ((4f + 16f * ThemeEffects.GlassFrostStrength.current).dp)
    // Android 13+（RuntimeShader/AGSL 折射）；31/32 设备走回退——ColorOS 上 RenderEffect
    // 离屏渲染有白框历史 bug（v1.4.8 教训），不冒险
    val canReal = backdrop != null && liquidGlass && Build.VERSION.SDK_INT >= 33

    // 2026-09-21 投影已整体移除：原 elevated 参数（12dp shadow）在玻璃透明后
    // 会从背后透出成"长方形色块"，故连同参数一并删除（真折射/深色回退两条路径都改）。
    if (canReal) {
        RealGlassOverlay(
            modifier = modifier,
            shape = shape,
            style = style,
            blurRadius = effectiveBlur,
            lensHeight = lensHeight,
            lensAmount = lensAmount,
            backdrop = backdrop!!,
            content = content
        )
    } else {
        FallbackGlassOverlay(
            modifier = modifier,
            shape = shape,
            style = style,
            content = content
        )
    }
}

/** 真折射路径：AGSL 折射 + RenderEffect 磨砂 + 莫奈色表面。 */
@Composable
private fun RealGlassOverlay(
    modifier: Modifier,
    shape: CornerBasedShape,
    style: OverlayGlassStyle,
    blurRadius: Dp,
    lensHeight: Dp,
    lensAmount: Dp,
    backdrop: Backdrop,
    content: @Composable BoxScope.() -> Unit
) {
    val darkTheme = MaterialTheme.colorScheme.background.luminance() <= 0.5f
    // 三参数独立（2026-09-28 拆分）：透明度 t 控表面色 alpha，折射 r 控边缘位移；
    // 磨砂度已由 blurRadius 承载
    val t = ThemeEffects.GlassTransparency.current.coerceIn(0f, 1f)
    val r = ThemeEffects.GlassRefractionStrength.current.coerceIn(0f, 1f)
    // 玻璃外观（v1.8.0）：压暗程度 + 亮边强度；默认中性元，不改变既有观感
    val dim = ThemeEffects.effectiveDim(ThemeEffects.GlassDimAmount.current, darkTheme)
    val lightStrength = ThemeEffects.GlassEdgeLightStrength.current.coerceIn(0f, 1.6f)
    // 表面色（透明度：0=实 0.58，1=透 0.16；Dark 风格（媒体条）保持可读下限）
    val surface = when (style) {
        OverlayGlassStyle.Light ->
            if (darkTheme) Color(0xFF1A1D21).copy(alpha = 0.62f - 0.36f * t)
            else Color.White.copy(alpha = 0.58f - 0.42f * t)
        OverlayGlassStyle.Dark -> Color(0xFF14181C).copy(alpha = 0.80f - 0.28f * t)
    }
    // 压暗：只压 RGB、保留 alpha——保持半透明语义，避免把「透」压成「实」
    val drawnSurface = if (dim > 0f) {
        Color(
            red = surface.red * (1f - dim),
            green = surface.green * (1f - dim),
            blue = surface.blue * (1f - dim),
            alpha = surface.alpha
        )
    } else {
        surface
    }

    // 顺序：clip（内容裁剪）→ drawBackdrop（按 shape 绘制折射层）
    // 2026-09-21 投影已移除：原 shadow 在 clip 之前，玻璃透明后投影外扩暴露成色块。
    val m = modifier.clip(shape)

    Box(
        modifier = m.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                // Kyant0 iOS 26 复刻效果链（顺序为库文档规定）：饱和度提升 → 磨砂 → 边缘折射。
                // vibrancy 的饱和度增益是"如水般通透"的核心；lens 位移随「折射强度」
                // 0.2x-1.6x 缩放——低强度水面平静，高强度边缘如水滴折动
                vibrancy()
                blur(blurRadius.toPx())
                lens(lensHeight.toPx(), lensAmount.toPx() * (0.2f + 1.4f * r))
            },
            onDrawSurface = { drawRect(drawnSurface) }
        )
    ) {
        // 顶部窄镜面高光：叠加在真折射之上（玻璃上沿反射，Liquid 灵魂的最后一笔）
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = ((if (style == OverlayGlassStyle.Dark) 0.14f else 0.10f) * lightStrength).coerceIn(0f, 1f)),
                            Color.White.copy(alpha = (0.02f * lightStrength).coerceIn(0f, 1f)),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = 60f
                    )
                )
        )
        content()
    }
}

/** 回退路径：非 Android 13+ / 玻璃开关关闭 / backdrop 不可用。 */
@Composable
private fun FallbackGlassOverlay(
    modifier: Modifier,
    shape: CornerBasedShape,
    style: OverlayGlassStyle,
    content: @Composable BoxScope.() -> Unit
) {
    when (style) {
        OverlayGlassStyle.Light ->
            GlassSurface(
                modifier = modifier,
                tier = GlassTier.L2,
                shape = shape,
                content = content
            )
        OverlayGlassStyle.Dark -> FallbackDarkGlassSurface(
            modifier = modifier,
            shape = shape,
            content = content
        )
    }
}

/** 深色玻璃回退：原 MediaGlassSurface 视觉（黑 60% + 顶部高光 + 内描边）。 */
@Composable
private fun FallbackDarkGlassSurface(
    modifier: Modifier,
    shape: CornerBasedShape,
    content: @Composable BoxScope.() -> Unit
) {
    // 2026-09-21 投影已移除（原 12dp shadow，投影透出成因同上）。
    Box(modifier = modifier.clip(shape).background(Color.Black.copy(alpha = 0.60f))) {
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

/** 记录捕获层的兜底底色（backdrop 空白区域的折射采样色）。 */
@Composable
fun rememberBackdropBaseColor(): Color =
    MaterialTheme.colorScheme.background
