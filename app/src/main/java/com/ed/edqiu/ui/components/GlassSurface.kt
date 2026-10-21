package com.ed.edqiu.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ed.edqiu.ui.theme.ThemeEffects
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

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
 *
 * 2026-09-21 投影移除 + 边缘光引入：投影去掉后层级浮起感转由材质承担，
 * 故为每档补充 [edgeLight]（内描边边缘光强度系数）与 [topLight]（顶部带强度系数），
 * 越高层越亮，形成不依赖投影的层级信号。
 */
enum class GlassTier(
    val bgAlpha: Float,
    val edgeAlpha: Float,
    /** 内描边边缘光强度系数（全轮廓的白+seed 双色描边）。 */
    val edgeLight: Float = 1f,
    /** 顶部渐变光带强度系数（玻璃上沿反射）。 */
    val topLight: Float = 1f
) {
    L1(0.34f, 0.24f, edgeLight = 0.85f, topLight = 0.90f),
    L2(0.46f, 0.30f, edgeLight = 1.15f, topLight = 1.20f),
    L3(0.58f, 0.34f, edgeLight = 1.45f, topLight = 1.50f)
}

/**
 * 玻璃内描边边缘光（2026-09-21 引入）。
 *
 * 投影移除后，卡片的"轮廓存在感"与层级浮起感全部转由材质承担，此修饰符即核心手段。
 *
 * 实现要点：
 * - 纯 Canvas 描边（[drawWithContent] + [Stroke]），**不涉及任何离屏渲染**
 *   —— 规避 v1.4.8 在 ColorOS 上 blur/RenderEffect 合成方形白框的历史坑。
 * - 双色：顶部白高光（环境光反射）+ 底部 seed 莫奈色微光（环境色反射），
 *   中间过渡由 seed 的降 alpha 承担，使玻璃边缘"透出彩"而非纯白描边。
 * - 描边为内描边（沿形状内侧半宽绘制），不会溢出卡片轮廓。
 *
 * @param shape 玻璃形状（与卡片一致，描边才能贴合圆角）
 * @param whiteTop 顶部白高光强度
 * @param whiteBottom 底部白高光强度
 * @param seed 莫奈 seed 色（与 GlassBackground 同源，取 ColorScheme.primary）
 * @param seedTop 顶部 seed 色叠加强度
 * @param seedBottom 底部 seed 色强度（通常略高于顶部，模拟下缘环境色回弹）
 * @param width 描边宽度
 * @param lightStrength 亮边强度倍数（v1.8.0）：等比缩放四路高光 alpha，
 *   1 = 中性（v1.7.0 观感），0 = 熄灭全部高光，1.6 = 强反光
 * @param lightAngle 光源角度（2026-10-21 对齐液态玻璃教程，度，数学惯例）：
 *   -45 = 左上（Apple/教程默认），决定边缘光渐变的亮端方向
 * @param lightIntensity 光源亮度 0-2：等比缩放全部描边 alpha（与 lightStrength 相乘生效）
 * @param dispersion 色散强度 0-1：光源同侧偏暖红、背光侧偏冷的次级光谱副描边
 */
fun Modifier.glassEdgeLight(
    shape: Shape,
    seed: Color,
    whiteTop: Float,
    whiteBottom: Float,
    seedTop: Float,
    seedBottom: Float,
    width: Dp = 1.25.dp,
    lightStrength: Float = 1f,
    lightAngle: Float = -45f,
    lightIntensity: Float = 1f,
    dispersion: Float = 0f
): Modifier = this.drawWithContent {
    drawContent()
    val stroke = width.toPx()
    if (stroke <= 0f) return@drawWithContent
    val ls = lightStrength.coerceAtLeast(0f)
    // 2026-10-21 对齐液态玻璃教程：光源物理亮度（GlassLightIntensity）统一参与
    // 全部描边 alpha 缩放，与 lightStrength（用户偏好倍率）相乘生效
    val li = lightIntensity.coerceIn(0f, 2f)
    val halfInset = stroke / 2f
    // 2026-10-21 对齐液态玻璃教程：渐变方向由 lightAngle 驱动（替代固定垂直渐变）。
    // 屏幕坐标 y 向下，θ=-45 须指向左上 → 指向光源的单位向量取 (sin θ, -cos θ)：
    // θ=0 → (0,-1) 正上（退化为原"上亮下暗"），θ=-45 → (-0.707,-0.707) 左上 ✓
    val rad = lightAngle * PI.toFloat() / 180f
    val dx = sin(rad)
    val dy = -cos(rad)
    val cx = size.width / 2f
    val cy = size.height / 2f
    val radius = hypot(size.width, size.height) / 2f
    // 亮端锚在光源侧、暗端在背光侧（半径取对角线一半，覆盖整个卡片对角）
    val lightSide = Offset(cx + dx * radius, cy + dy * radius)
    val darkSide = Offset(cx - dx * radius, cy - dy * radius)
    // 按光源方向的 linearGradient 决定"光源侧亮、背光侧暖"的边缘特征
    val whiteBrush = Brush.linearGradient(
        start = lightSide,
        end = darkSide,
        colors = listOf(
            Color.White.copy(alpha = (whiteTop * ls * li).coerceIn(0f, 1f)),
            Color.White.copy(alpha = (whiteBottom * ls * li).coerceIn(0f, 1f))
        )
    )
    val seedBrush = Brush.linearGradient(
        start = lightSide,
        end = darkSide,
        colors = listOf(
            seed.copy(alpha = (seedTop * ls * li).coerceIn(0f, 1f)),
            seed.copy(alpha = (seedBottom * ls * li).coerceIn(0f, 1f))
        )
    )
    // 内描边：把形状尺寸向内缩一个线宽，再整体外移半个线宽 → 描边恰好贴内侧轮廓，
    // 且外缘不越过卡片边界
    val outline = shape.createOutline(
        size = androidx.compose.ui.geometry.Size(
            (size.width - stroke).coerceAtLeast(0f),
            (size.height - stroke).coerceAtLeast(0f)
        ),
        layoutDirection = layoutDirection,
        density = this
    )
    // 把 Outline 转为 Path：只用 addRoundRect / addRect 这两个稳定 API 覆盖本 app
    // 全部形状（RoundedCornerShape、CircleShape 都产出 Outline.Rounded）
    val path = androidx.compose.ui.graphics.Path()
    when (outline) {
        is androidx.compose.ui.graphics.Outline.Rounded -> path.addRoundRect(outline.roundRect)
        is androidx.compose.ui.graphics.Outline.Rectangle -> path.addRect(outline.rect)
        is androidx.compose.ui.graphics.Outline.Generic -> path.addPath(outline.path)
    }
    val strokeStyle = Stroke(width = stroke)
    // 用 translate 把描边整体外移半个线宽，使描边贴住内侧轮廓
    translate(left = halfInset, top = halfInset) {
        // 色散副描边（2026-10-21 对齐液态玻璃教程）：白光经玻璃边缘折射分解出的
        // 红/青次级光谱——暖色偏在光源同侧（R 通道偏折大）、冷色偏在背光侧
        // （B 通道偏折大）。alpha ≤ 0.15 极其微妙，只作折射点缀不喧宾夺主；
        // 偏移量 1.2×stroke < 2×stroke，保证副描边不溢出卡片轮廓。
        if (dispersion > 0.01f) {
            val dispAlpha = (0.10f * dispersion * lightIntensity).coerceIn(0f, 1f)
            if (dispAlpha > 0f) {
                // 暖色副边：朝光源方向偏移
                translate(left = dx * stroke * 1.2f, top = dy * stroke * 1.2f) {
                    drawPath(
                        path = path,
                        color = Color(0xFFFF6B4A).copy(alpha = dispAlpha),
                        style = strokeStyle
                    )
                }
                // 冷色副边：朝背光方向偏移
                translate(left = -dx * stroke * 1.2f, top = -dy * stroke * 1.2f) {
                    drawPath(
                        path = path,
                        color = Color(0xFF4AC8FF).copy(alpha = dispAlpha),
                        style = strokeStyle
                    )
                }
            }
        }
        // seed 层先画（打底，让玻璃边缘透出莫奈色）；白色高光层叠加（光源侧更亮）
        drawPath(path = path, brush = seedBrush, style = strokeStyle)
        drawPath(path = path, brush = whiteBrush, style = strokeStyle)
    }
}

/**
 * 毛玻璃表面组件（iOS Liquid Glass 质感模拟）。
 *
 * 特征：
 * 1. 背景渐变折射（上部偏亮 + 底部微暗，模拟玻璃曲率）
 * 2. 内外双层描边（内高光白 + 外细边，Liquid 灵魂）
 * 3. 顶部折射高光线
 * 4. 内描边边缘光（白 + seed 双色，2026-09-21 新增）
 *
 * 2026-09-21 移除投影：投影（原 elevated/2dp/12dp shadow）在玻璃高透明后
 * 会从卡片背后透出成"长方形色块"（shadow 的外扩模糊超出圆角轮廓，矩形包围盒
 * 暴露在透明玻璃之下）。层级浮起感改由材质本身承担——顶光 + 内高光描边 +
 * 玻璃底色 alpha 分级（L1/L2/L3）+ 内描边边缘光，不再依赖投影落差。
 *
 * ⚠️ 内容对齐坑（2026-10-20 实机 bug）：内容 Box 为普通 Box——默认 TopStart 且
 * **不传最小约束**（不同于 Material3 Surface 的 propagateMinConstraints=true）。
 * 固定尺寸表层（圆钮/徽标）若直接塞 `Box(contentAlignment = Center)`，内层只包
 * 内容尺寸并钉在左上角，圆钮上会探出圆弧外。正确姿势：内层
 * `Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center)`。
 *
 * @param tier 玻璃层级
 * @param shape 玻璃形状
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    tier: GlassTier = GlassTier.L1,
    shape: Shape = RoundedCornerShape(24.dp),
    tint: Color = MaterialTheme.colorScheme.primaryContainer,
    content: @Composable BoxScope.() -> Unit
) {
    val dark = MaterialTheme.colorScheme.background.luminance() <= 0.5f
    // 全局效果：玻璃三参数（透明度/磨砂/折射）+ 液态玻璃开关（EdqiuApp 顶层注入，实时跟随）
    val liquidGlass = ThemeEffects.LiquidGlassEnabled.current
    val transparency = ThemeEffects.GlassTransparency.current.coerceIn(0f, 1f)
    val frost = ThemeEffects.GlassFrostStrength.current.coerceIn(0f, 1f)
    // 玻璃外观（v1.8.0）：默认中性元，老用户升级后观感不变
    val edgeWidth = ThemeEffects.GlassEdgeWidth.current.coerceIn(0f, 2.5f)
    val lightStrength = ThemeEffects.GlassEdgeLightStrength.current.coerceIn(0f, 1.6f)
    val dim = ThemeEffects.effectiveDim(ThemeEffects.GlassDimAmount.current, dark)
    // 光源三参数（2026-10-21 对齐液态玻璃教程）：角度/亮度/色散，实时跟随全局注入
    val lightAngle = ThemeEffects.GlassLightAngle.current
    val lightIntensity = ThemeEffects.GlassLightIntensity.current.coerceIn(0f, 1f)
    val dispersion = ThemeEffects.GlassDispersion.current.coerceIn(0f, 1f)

    // 玻璃底色 = 半透明白（主体）+ 主题容器色 tint（带主题色的磨砂面板）
    // 透明度 t：底色 alpha 在 1.3x（实）与 0.55x（透）之间缩放——深色背景上卡片明度远高于背景，
    // 图底关系由结构保证
    val alphaScale = 1.30f - 0.75f * transparency
    val base = if (dark) {
        Color(0xFF1A1D21).copy(alpha = (tier.bgAlpha + 0.10f) * alphaScale)
    } else {
        Color.White.copy(alpha = tier.bgAlpha * alphaScale)
    }
    // tint 叠加：磨砂越强 → tint 越轻，让被磨砂的背景色域更透出
    val glassColor = androidx.compose.ui.graphics.lerp(
        base,
        tint.copy(alpha = if (dark) 0.30f else 0.34f),
        (if (dark) 0.45f else 0.40f) * (1f - frost * 0.4f)
    )

    // 2026-09-21 投影已移除：shadow 的外扩模糊会从透明玻璃背后透出（长方形色块）。
    // 层级浮起感改由材质承担（顶光 + 内高光描边 + tier.bgAlpha 分级 + 内描边边缘光）。
    // 2026-10-21：edgeSeed 覆盖逻辑已随「玻璃描边颜色」设置项移除——恒取主题 primary（固定绿）。
    val edgeSeed = MaterialTheme.colorScheme.primary
        .takeIf { it != Color.Unspecified && it.alpha > 0f }
        ?: tint
    val m = modifier
        .clip(shape)
        .background(glassColor)
        .glassEdgeLight(
            shape = shape,
            seed = edgeSeed,
            // 浅色主题：白高光为主；深色主题：白高光减弱让 seed 色更主导
            // 2026-09-28 区分度修复：seed 描边加权——浅背景上白色描边不可见，
            // 主题色描边承担卡片轮廓定义
            whiteTop = (if (dark) 0.20f else 0.26f) * tier.edgeLight,
            whiteBottom = (if (dark) 0.05f else 0.07f) * tier.edgeLight,
            seedTop = (if (dark) 0.22f else 0.20f) * tier.edgeLight,
            seedBottom = (if (dark) 0.34f else 0.30f) * tier.edgeLight,
            width = edgeWidth.dp,
            lightStrength = lightStrength,
            // 2026-10-21 对齐液态玻璃教程：光源角度/亮度/色散接入边缘光
            lightAngle = lightAngle,
            lightIntensity = lightIntensity,
            dispersion = dispersion
        )

    Box(modifier = m) {
        if (liquidGlass) {
            // 背景渐变折射：上部亮 + 底部微暗（模拟玻璃曲率）
            // 2026-09-14 三轮：白框根因组合拳——白色装饰层全线再压（镜面 0.12/描边 0.08），
            // 玻璃底色微升（L1 0.34）让高光相对弱化、玻璃呈"整块材质"而非"叠白框"
            // 2026-10-21 对齐液态玻璃教程：alpha 乘光源亮度
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = ((if (dark) 0.03f else 0.10f) * lightIntensity).coerceIn(0f, 1f)),
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = ((if (dark) 0.06f else 0.015f) * lightIntensity).coerceIn(0f, 1f))
                        )
                    )
            )
            // 顶部折射高光线（Liquid 灵魂）
            // 2026-09-21：强度乘 tier.topLight —— 投影移除后靠顶部带强度区分层级
            // 2026-10-21 对齐液态玻璃教程：改为按 lightAngle 方向的 linearGradient——
            // 亮端锚定在光源侧边缘、沿背光方向 90px 渐隐（θ=0 退化为原顶部横带）；
            // 正顶部横带是"贴膜感"来源，方向性高光才是"真玻璃在光下"的物理观感
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        val rad = lightAngle * PI.toFloat() / 180f
                        val dx = sin(rad)
                        val dy = -cos(rad)
                        // 矩形沿光源方向的半跨距：亮端正好落在光源侧边缘上
                        val halfSpan = abs(dx) * size.width / 2f + abs(dy) * size.height / 2f
                        val start = Offset(size.width / 2f + dx * halfSpan, size.height / 2f + dy * halfSpan)
                        val end = Offset(start.x - dx * 90f, start.y - dy * 90f)
                        drawRect(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = (tier.edgeAlpha * 0.7f * tier.topLight * lightStrength * lightIntensity).coerceIn(0f, 1f)),
                                    Color.White.copy(alpha = (tier.edgeAlpha * 0.08f * lightIntensity).coerceIn(0f, 1f)),
                                    Color.Transparent
                                ),
                                start = start,
                                end = end
                            )
                        )
                    }
            )
            // 内高光描边：顶部亮、底部暗（玻璃边缘光）；2026-10-21 alpha 乘光源亮度
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = ((if (dark) 0.06f else 0.08f) * lightIntensity).coerceIn(0f, 1f)),
                                Color.White.copy(alpha = (0.03f * lightIntensity).coerceIn(0f, 1f)),
                                Color.White.copy(alpha = ((if (dark) 0.015f else 0.04f) * lightIntensity).coerceIn(0f, 1f))
                            ),
                            startY = 0f,
                            endY = 400f
                        )
                    )
            )
            // iOS 26 液态玻璃增强：细镜面高光（更亮、更窄，模拟玻璃上沿反射）
            // 2026-10-21 对齐液态玻璃教程：同样改为按 lightAngle 方向的 linearGradient，
            // 渐变长度 60px，alpha 乘光源亮度
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        val rad = lightAngle * PI.toFloat() / 180f
                        val dx = sin(rad)
                        val dy = -cos(rad)
                        val halfSpan = abs(dx) * size.width / 2f + abs(dy) * size.height / 2f
                        val start = Offset(size.width / 2f + dx * halfSpan, size.height / 2f + dy * halfSpan)
                        val end = Offset(start.x - dx * 60f, start.y - dy * 60f)
                        drawRect(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = ((if (dark) 0.08f else 0.12f) * tier.topLight * lightStrength * lightIntensity).coerceIn(0f, 1f)),
                                    Color.White.copy(alpha = (0.015f * lightIntensity).coerceIn(0f, 1f)),
                                    Color.Transparent
                                ),
                                start = start,
                                end = end
                            )
                        )
                    }
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
            // 磨砂雾感（磨砂强度缩放）：纯渐变实现
            // （2026-09-14 v1.4.8 移除 blur 修饰符——ColorOS 离屏白框 bug，永不恢复）
            // 2026-10-21 对齐液态玻璃教程：雾感减半（对齐教程 Frost=4 的轻磨砂），
            // alpha 乘光源亮度
            if (frost > 0f) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = ((0.05f + 0.13f * frost) * lightIntensity).coerceIn(0f, 1f)),
                                    Color.Transparent,
                                    Color.White.copy(alpha = ((0.02f + 0.05f * frost) * lightIntensity).coerceIn(0f, 1f))
                                ),
                                startY = 0f,
                                endY = 220f
                            )
                        )
                )
            }
        } else {
            // 液态玻璃关闭：普通哑光面板——透明度 t 与磨砂感 f 独立可调：
            // alpha 0.55（半透，透出背景色场）↔ 0.96（近实底）
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh
                            .copy(alpha = 0.96f - 0.41f * transparency)
                    )
            )
            if (frost > 0f) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.04f + 0.12f * frost),
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.01f + 0.05f * frost)
                                ),
                                startY = 0f,
                                endY = 260f
                            )
                        )
                )
            }
        }
        // 压暗程度（v1.8.0）：叠加在材质层之上、内容之下——只压暗玻璃本身，
        // 不影响 content() 的文字对比度。dim=0（默认）时不产生任何绘制。
        if (dim > 0f) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = dim))
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
 * L0 背景层（2026-09-28 「单一色源」重构 —— 替代旧的手绘莫奈渐变）。
 *
 * 旧版用 primary 高 alpha 手绘三段渐变 + 三个径向光斑，与 ColorScheme 分属两套体系，
 * 是"取色/强调色/风格/模糊怎么调都打架"的根源（primary 被强调色或壁纸轮番污染，
 * 背景亮度和色相永远跟卡片对不上）。新版直接取 ColorScheme 中性面做基底
 * （background → surfaceContainer 垂直渐变），叠两团容器色光晕（primaryContainer /
 * tertiaryContainer——与主题同一调色板、明度受控），背景与卡片、取色天然同源，
 * 任何配色方案下结构上都不会割裂。
 *
 * [blurRadius] 仍作用于该背景层（柔化光晕边缘）。
 *
 * 2026-10-21 对齐液态玻璃教程（色场韵律化）：
 * - 外层改为 [BoxWithConstraints]，所有光斑按窗口实际尺寸**比例定位**——
 *   修复旧版硬编码像素位置 Offset(200f, 2400f) 在不同分辨率/折叠屏上错位的问题；
 * - 主色场 alpha 调淡（给基底与光斑让位），新增左上冷色亮团 + 右中饱和深团，
 *   让背景出现明暗起伏的"物"——纯均匀背景上玻璃没有可折射的空间层次（教程核心）；
 * - blurRadius 与已有 blur 修饰符逻辑保持原样。
 */
@Composable
fun GlassBackground(
    modifier: Modifier = Modifier,
    blurRadius: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() <= 0.5f
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 窗口实际尺寸 → 像素（radialGradient 的 center/radius 以 px 计）
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val minPx = minOf(wPx, hPx)
        // 背景层包在内部 Box 中应用 blur → 仅柔化背景，不影响前景 content
        Box(
            modifier = Modifier
                .fillMaxSize()
                .let { if (blurRadius > 0.dp) it.blur(blurRadius) else it }
        ) {
            // 基底：中性面三段渐变（background → surfaceContainer → surfaceContainerHigh），
            // 越往下越沉，给底部内容（列表尾/底栏）一个稳定的"地面"
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to scheme.background,
                            0.6f to scheme.surfaceContainer,
                            1f to scheme.surfaceContainerHigh
                        )
                    )
            )
            // 主色场（2026-09-28 图底关系修复）：primary/tertiary 是调色板中的"饱和深色"。
            // 2026-10-21 对齐液态玻璃教程：alpha 调淡（浅 0.40/0.20/0.36、深 0.48/0.26/0.42），
            // 给基底与下方光斑让位，避免整屏一片糊
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            0f to scheme.primary.copy(alpha = if (dark) 0.48f else 0.40f),
                            0.5f to scheme.primary.copy(alpha = if (dark) 0.26f else 0.20f),
                            1f to scheme.tertiary.copy(alpha = if (dark) 0.42f else 0.36f)
                        )
                    )
            )
            // 左上亮团（2026-10-21 新增）：tertiary 冷色高光，与主色场对角呼应，
            // 制造背景明暗起伏的"物"，玻璃边缘光/折射才有空间层次可表现
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                scheme.tertiary.copy(alpha = if (dark) 0.34f else 0.30f),
                                Color.Transparent
                            ),
                            center = Offset(wPx * 0.16f, hPx * 0.08f),
                            radius = minPx * 0.55f
                        )
                    )
            )
            // 右中深团（2026-10-21 新增）：primary 饱和团，压住右侧中段，
            // 与左上亮团形成对角韵律，避免色场中段过空
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                scheme.primary.copy(alpha = if (dark) 0.36f else 0.30f),
                                Color.Transparent
                            ),
                            center = Offset(wPx * 0.90f, hPx * 0.40f),
                            radius = minPx * 0.65f
                        )
                    )
            )
            // 左下补光：primary 反向光斑，避免色场中段过空。
            // 2026-10-21 对齐液态玻璃教程：位置/半径改按窗口比例定位
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                scheme.primary.copy(alpha = if (dark) 0.25f else 0.22f),
                                Color.Transparent
                            ),
                            center = Offset(wPx * 0.10f, hPx * 0.80f),
                            radius = minPx * 0.50f
                        )
                    )
            )
        }
        content()
    }
}
