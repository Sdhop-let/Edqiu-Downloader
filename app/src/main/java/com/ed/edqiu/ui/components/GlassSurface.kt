package com.ed.edqiu.ui.components

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ed.edqiu.ui.theme.ThemeEffects
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
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
    // 2026-10-22 壁纸舞台配套降档：壁纸透进卡片，玻璃材质可见（旧值 L1 0.34/L2 0.46
    // 在真实壁纸背景上显得是"灰盒子"——用户反馈「卡片没有展示出自己特点」）；
    // L3 弹窗保持实感不动
    // 2026-10-22 二轮「更轻」档（用户拍板卡片优化）：L1 描边/顶光减半——玻璃更隐身、
    // 卡片内容更突出（轮廓线在壁纸上偏「贴膜感」）
    L1(0.26f, 0.24f, edgeLight = 0.45f, topLight = 0.50f),
    L2(0.38f, 0.30f, edgeLight = 1.15f, topLight = 1.20f),
    L3(0.58f, 0.34f, edgeLight = 1.45f, topLight = 1.50f)
}

/**
 * 玻璃内描边边缘光（2026-09-21 引入）。
 *
 * 投影移除后，卡片的"轮廓存在感"与层级浮起感全部转由材质承担，此修饰符即核心手段。
 *
 * 实现要点：
 * - 纯 Canvas 描边（[drawWithContent] + [Stroke]），**不涉及任何离屏渲染**
 *   —— 继承 v1.4.8 事故后的保守路线（该事故根因 = Compose 模糊层方形 clamp 用法
 *   + 白色装饰层过亮；「ColorOS 合成白框」归因已于 2026-10-22 实机实验证伪，见 MEMORY）。
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
 * 玻璃表面光装饰层（2026-10-22 从 GlassSurface 提取，真折射/手绘双路径共用）。
 *
 * 五层：背景渐变折射（曲率）→ 顶部方向性高光线 → 内高光渐变 → 细镜面高光 → 底部反光。
 * 全部是「玻璃表面的光」——与背后采样层叠加才是完整玻璃（Apple 结构：
 * 采样层 + 表面光层 + 内容层，缺一即「贴膜感」）。
 *
 * @param alphaScale 白色装饰整体缩放。真折射路径背后是 backdrop 真实采样
 *   （相邻卡片/缩略图/GlassBackground 色场），白装饰再重就白叠白，故 ×0.7 收敛；
 *   手绘回退路径背后无采样，×1 保持原观感。
 */
@Composable
private fun BoxScope.GlassSurfaceLights(
    dark: Boolean,
    tier: GlassTier,
    lightAngle: Float,
    lightIntensity: Float,
    lightStrength: Float,
    alphaScale: Float
) {
    // 背景渐变折射：上部亮 + 底部微暗（模拟玻璃曲率）
    // 2026-09-14 三轮：白框根因组合拳——白色装饰层全线再压（镜面 0.12/描边 0.08），
    // 玻璃底色微升（L1 0.34）让高光相对弱化、玻璃呈"整块材质"而非"叠白框"
    // 2026-10-21 对齐液态玻璃教程：alpha 乘光源亮度
    Box(
        Modifier
            .matchParentSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = (((if (dark) 0.03f else 0.10f) * lightIntensity) * alphaScale).coerceIn(0f, 1f)),
                    0.55f to Color.Transparent,
                    1f to Color.Black.copy(alpha = (((if (dark) 0.06f else 0.015f) * lightIntensity) * alphaScale).coerceIn(0f, 1f))
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
                            Color.White.copy(alpha = ((tier.edgeAlpha * 0.7f * tier.topLight * lightStrength * lightIntensity) * alphaScale).coerceIn(0f, 1f)),
                            Color.White.copy(alpha = ((tier.edgeAlpha * 0.08f * lightIntensity) * alphaScale).coerceIn(0f, 1f)),
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
                        Color.White.copy(alpha = (((if (dark) 0.06f else 0.08f) * lightIntensity) * alphaScale).coerceIn(0f, 1f)),
                        Color.White.copy(alpha = ((0.03f * lightIntensity) * alphaScale).coerceIn(0f, 1f)),
                        Color.White.copy(alpha = (((if (dark) 0.015f else 0.04f) * lightIntensity) * alphaScale).coerceIn(0f, 1f))
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
                            Color.White.copy(alpha = (((if (dark) 0.08f else 0.12f) * tier.topLight * lightStrength * lightIntensity) * alphaScale).coerceIn(0f, 1f)),
                            Color.White.copy(alpha = ((0.015f * lightIntensity) * alphaScale).coerceIn(0f, 1f)),
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
                        Color.White.copy(alpha = ((if (dark) 0.015f else 0.02f) * alphaScale).coerceIn(0f, 1f))
                    )
                )
            )
    )
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
    // ── 真折射路径判定（2026-10-22 A+ 方案尝试，实机 SIGSEGV 后关闭）──
    // 原设计：Android 13+ 且液态玻璃开启且 backdrop 可用且 CornerBasedShape（lens 要求）→
    // drawBackdrop 采样 shellBackdrop（AppNavigation.kt:264-268 就近覆盖 LocalAppBackdrop，
    // 其 layerBackdrop 捕获整个 NavHost 页面内容 :317）。
    // 库标准用法：玻璃在捕获链内、自身内容画在采样之后，不会自采样变实色。
    // 与 A 方案的本质区别：Modifier.blur 只糊本层绘制内容（白色渐变糊自己 = 白叠白），
    // drawBackdrop 糊的是背后真实像素（前序卡片/缩略图/GlassBackground 色场）——
    // 滚动时相邻内容从卡片玻璃后透出，即 Apple 列表的标志性效果。
    //
    // ⚠️ 2026-10-22 14:56 实机判决（PLK110）：item 内 drawBackdrop 触发
    // RenderThread SIGSEGV（Fatal signal 11 SEGV_ACCERR, tid RenderThread）——
    // 根因：NavHost 的 modifier 链上 graphicsLayer(compositingStrategy = Offscreen)
    // （底部渐隐 DstOut 所需，AppNavigation.kt:326）与 layerBackdrop 同链，离屏 RenderNode
    // 既是捕获源又是渲染目标，item 采样后画回同一纹理 = GPU feedback loop。
    // 库 demo 能跑因其无 Offscreen 包装。底栏 GlassOverlaySurface 稳定是因它在捕获链
    // **之外**（采样已完成的页面纹理）。结论：链内 item 采样此结构下不可用，
    // 强制 canReal=false 保留代码待「独立 backdrop + 捕获链外浮层」架构（见 MEMORY）。
    val backdrop = LocalAppBackdrop.current
    // 2026-10-22 15:36 二次实机判决（决定性）：canReal=true 时 RenderThread
    // 「Fatal signal 11 SIGSEGV … Cause: stack pointer is close to top of stack;
    // likely stack overflow」——item 内 drawBackdrop 采样包含自身的捕获链 =
    // 递归捕获（采样→链脏→重捕获→再采样→栈溢出），与 Offscreen 擦除层无关
    //（该层移除后依旧崩，实锤机制性死路）。库 demo 能跑是因玻璃在捕获链外
    // （采样不含自己）。**最终铁律：捕获链内的组件永远不能 drawBackdrop 该链**；
    // 卡片玻璃维持自绘（A 方案 blur），真折射只允许链外浮层（底栏模式）。
    val canReal = false
    // 真折射磨砂半径跟随全局磨砂强度（与 GlassOverlaySurface 同源 4-20dp：
    // 0%=通透见底 4dp，100%=重磨砂 20dp）
    val realBlur = (4f + 16f * frost).dp
    // 边缘折射（2026-10-21 对齐教程：深度 18dp = 折射带宽度；位移随折射强度缩放）
    val realRefraction = ThemeEffects.GlassRefractionStrength.current.coerceIn(0f, 1f)
    val realLensHeight = 18.dp
    val realLensAmount = 18.dp * (0.2f + 1.4f * realRefraction)

    val m = modifier
        .clip(shape)
        // 真折射路径的表面色由 drawBackdrop 的 onDrawSurface 绘制（画在采样之上），
        // 不再走 .background——否则底色会垫在采样层底下被一起糊进去
        .then(if (canReal) Modifier else Modifier.background(glassColor))
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
        if (canReal) {
            // ── 真折射采样层：vibrancy → blur → lens 作用于 shellBackdrop 真实内容 ──
            // 效果链顺序为库文档规定：饱和度提升 → 磨砂 → 边缘折射。
            // 表面色（glassColor）画在采样之上（onDrawSurface），是「玻璃本身的颜色」。
            Box(
                Modifier
                    .matchParentSize()
                    .drawBackdrop(
                        backdrop = backdrop!!,
                        shape = { shape as CornerBasedShape },
                        effects = {
                            vibrancy()
                            blur(realBlur.toPx())
                            lens(realLensHeight.toPx(), realLensAmount.toPx())
                        },
                        onDrawSurface = { drawRect(glassColor) }
                    )
            )
            // 表面光装饰：背后已有真实采样，白装饰 ×0.7 收敛，避免白叠白
            GlassSurfaceLights(
                dark = dark,
                tier = tier,
                lightAngle = lightAngle,
                lightIntensity = lightIntensity,
                lightStrength = lightStrength,
                alphaScale = 0.7f
            )
        } else if (liquidGlass) {
            // ── 手绘回退路径（Android 13 以下 / backdrop 不可用）：背后无采样，装饰保持原强度 ──
            GlassSurfaceLights(
                dark = dark,
                tier = tier,
                lightAngle = lightAngle,
                lightIntensity = lightIntensity,
                lightStrength = lightStrength,
                alphaScale = 1f
            )
            // 磨砂雾感（磨砂强度缩放）
            // （2026-09-14 v1.4.8 曾移除 blur 修饰符——当时归因 ColorOS 离屏白框；
            // 该 OS 归因 2026-10-22 实机四用例实验证伪，A 方案恢复真高斯磨砂）
            // 2026-10-21 对齐液态玻璃教程：雾感减半（对齐教程 Frost=4 的轻磨砂），
            // alpha 乘光源亮度
            // 2026-10-22 A 方案：渐变之上叠加真 RenderEffect 高斯（半径 8→36dp 随磨砂强度），
            // 把方向性渐变的硬过渡抹成均匀雾场——Apple 磨砂是高斯，不是线性渐隐。
            // 半径与 GlassBackground 背景 blur（frost²×64dp≈23dp @0.6）同量级，保证
            // 卡片雾场与背景雾场粒度一致；默认 0.6 → 24.8dp。
            // 安全配置沿用实验验证过的结构：Unbounded 边缘处理 + 外层 clip(shape) 保证圆角
            //（v1.4.6 教训：卡片内磨砂层用 Unbounded、全屏背景层才用默认 clamp；
            //  GlassBackground 的背景 blur 同款模式已在生产环境运行至今）。
            // API 31- 无 RenderEffect 支撑，退化为原纯渐变（与旧设备观感一致）。
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
                        .then(
                            if (Build.VERSION.SDK_INT >= 31) {
                                Modifier.blur((8f + 28f * frost).dp, BlurredEdgeTreatment.Unbounded)
                            } else {
                                Modifier
                            }
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
 * L0 背景层（2026-09-28 「单一色源」重构；2026-10-22 「舞台方案」壁纸化）。
 *
 * 2026-10-22 落地用户拍板：**背景接真实内容——系统壁纸虚化铺底，玻璃折射真实照片**。
 * 程序化色场无论怎么调都是平滑渐变（磨砂/折射采到均匀色，无细节）；
 * 壁纸 = 用户个性化、全场唯一、自动更新的真实图像源。结构：
 * - 有壁纸：壁纸 Image（Crop + blur 28dp 虚化，全屏层默认 clamp 正确用法）
 *   + 可读性 scrim（深色压暗 0.42 / 浅色提亮 0.38）+ **光斑减半**保留色彩氛围；
 * - 无壁纸（加载失败/无壁纸）：落回「中性基底 + 局部光斑团」色场，无缝降级。
 *
 * [blurRadius] 仍作用于该背景层（柔化光晕边缘）。
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
    // 壁纸异步加载（IO 线程 decode，进程级缓存）；null = 未加载完成或无壁纸 → 色场降级
    val context = LocalContext.current
    val wallpaper by produceState<ImageBitmap?>(initialValue = WallpaperProvider.current) {
        value = WallpaperProvider.load(context)
    }

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
            if (wallpaper != null) {
                // ── 壁纸舞台（2026-10-22）：真实照片虚化铺底 ──
                // 28dp 高斯让壁纸成为"氛围层"而非"内容层"——保留明暗起伏与色块，
                // 抹掉可辨识细节，前景卡片/文字不被干扰。全屏层用默认 Rectangle
                // clamp（边缘延伸填充，v1.4.6 教训的正确用法面）
                Image(
                    bitmap = wallpaper!!,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(28.dp)
                )
                // 可读性 scrim：深色压暗（前景白字对比）、浅色提亮（前景黑字对比）——
                // 只压壁纸明度，不压色相，玻璃磨砂后仍有真实色彩
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            if (dark) Color.Black.copy(alpha = 0.42f)
                            else Color.White.copy(alpha = 0.38f)
                        )
                )
            } else {
                // 基底降级：中性面三段渐变（background → surfaceContainer → surfaceContainerHigh），
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
            }
            // 色场光斑：壁纸模式下整体减半——只保留色彩氛围，不再承担"舞台主体"
            val spotScale = if (wallpaper != null) 0.5f else 1f
            // ── 色场（2026-10-22 重做：「中性基底 + 局部光斑团」替代全屏平铺染色）──
            // 旧版主色场 = primary/tertiary linearGradient 0.48/0.42 alpha 全屏平铺——
            // 实测把整屏染成单色（深色 RGB 45-123 暗绿/浅色 150-230 亮薄荷绿），
            // 光斑叠在已染透的底上层次全失效，玻璃背后无任何可折射素材（用户定性
            // 「一坨屎」的像素级根因，见 MEMORY）。新版原则：
            // 1. 基底永远中性（iOS systemBackground 族），色彩只出现在局部光斑里；
            // 2. 光斑团 = 高对比、有边界、位置错落的「物」——玻璃磨砂/折射才有素材；
            // 3. 每个主题都有一明一暗一对团（深色：黑影团压中部；浅色：灰影团），
            //    明暗差是玻璃「存在感」的第一来源（Apple 草地演示的核心）。
            // 左上主光斑：primary 饱和团（局部，非平铺）
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                scheme.primary.copy(alpha = (if (dark) 0.55f else 0.38f) * spotScale),
                                Color.Transparent
                            ),
                            center = Offset(wPx * 0.15f, hPx * 0.10f),
                            radius = minPx * 0.42f
                        )
                    )
            )
            // 右中次光斑：tertiary 青，与主光斑对角呼应
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                scheme.tertiary.copy(alpha = (if (dark) 0.45f else 0.32f) * spotScale),
                                Color.Transparent
                            ),
                            center = Offset(wPx * 0.88f, hPx * 0.32f),
                            radius = minPx * 0.38f
                        )
                    )
            )
            // 中部暗影团（2026-10-22 新增）：深色 = 大黑团、浅色 = 灰影团——
            // 制造整屏唯一的大暗部，让上方光斑「亮得起来」、玻璃明暗可辨
            // 壁纸模式下暗影交给壁纸自身明暗，此团减半只作辅助
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                (if (dark) Color.Black else Color(0xFF48484A))
                                    .copy(alpha = (if (dark) 0.55f else 0.12f) * spotScale),
                                Color.Transparent
                            ),
                            center = Offset(wPx * 0.50f, hPx * 0.54f),
                            radius = minPx * 0.58f
                        )
                    )
            )
            // 左下小高光团：白光「物」（深色模式玻璃高光素材；浅色改淡绿补光）
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                (if (dark) Color.White else scheme.primary)
                                    .copy(alpha = (if (dark) 0.10f else 0.22f) * spotScale),
                                Color.Transparent
                            ),
                            center = Offset(wPx * 0.12f, hPx * 0.74f),
                            radius = minPx * 0.30f
                        )
                    )
            )
            // 底部反光团：primary 回弹，给底栏玻璃一点色彩素材
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                scheme.primary.copy(alpha = (if (dark) 0.20f else 0.16f) * spotScale),
                                Color.Transparent
                            ),
                            center = Offset(wPx * 0.78f, hPx * 0.90f),
                            radius = minPx * 0.36f
                        )
                    )
            )
        }
        content()
    }
}
