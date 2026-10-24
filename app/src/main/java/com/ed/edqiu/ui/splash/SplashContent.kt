package com.ed.edqiu.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ed.edqiu.ui.components.WallpaperProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 液态玻璃开屏（2026-10-24 重设计，替代 2026-09-30 Titanic 水波字）。
 *
 * 设计原则——开屏即品牌世界的第一帧：
 * - **舞台延续**：与主界面同一套「壁纸虚化铺底 + 深色 scrim + 绿光斑」舞台语言
 *  （复用 WallpaperProvider，无权限、失败回退纯色场）——开屏到主界面无视觉断裂；
 * - **玻璃徽记**：E 徽章 + 玻璃环光，与「我的」页头像、Tab 栏徽章同构；
 * - **一次性动画**（全部有限时长，无 infinite——Titanic 时代的教训：常驻动画
 *   让应用永不过滤空闲，系统推迟 Activity 收尾，与主界面 RESUME 乱序触发
 *   mStopped 冻结）：徽记弹性入场 → 标题浮起 → 环光单程扫掠 → 版本号淡入；
 * - 保留「就绪即走」语义外的跳过入口（整页点按 + 右上角胶囊）。
 */
@Composable
fun SplashContent(onSkip: () -> Unit) {
    val context = LocalContext.current
    // 舞台：壁纸异步加载（进程缓存未热时首次 ~100ms），到位前先纯色场
    val wallpaper by produceState<ImageBitmap?>(initialValue = WallpaperProvider.current) {
        value = WallpaperProvider.load(context)
    }

    // ── 一次性入场动画状态（全部有限时长）──
    val emblemScale = remember { Animatable(0.82f) }
    val emblemAlpha = remember { Animatable(0f) }
    val titleProgress = remember { Animatable(0f) }
    val captionAlpha = remember { Animatable(0f) }
    val sweepProgress = remember { Animatable(0f) }
    val chromeAlpha = remember { Animatable(0f) } // 跳过钮 + 版本号
    LaunchedEffect(Unit) {
        launch { emblemAlpha.animateTo(1f, tween(260)) }
        launch { emblemScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 380f)) }
        launch {
            delay(140)
            titleProgress.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
        }
        launch {
            delay(300)
            captionAlpha.animateTo(1f, tween(320))
        }
        launch {
            delay(220)
            sweepProgress.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
        }
        launch {
            delay(560)
            chromeAlpha.animateTo(1f, tween(280))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F0D))
            // 整页点按跳过（保留旧交互；无涟漪）
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSkip
            )
    ) {
        // ── 舞台层：壁纸（Crop + 压暗 0.55，保持品牌深色场）──
        wallpaper?.let { wp ->
            Image(
                bitmap = wp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.45f)
            )
        }
        // scrim：压住壁纸保文字对比（开屏文字为固定白色系）
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0F0D).copy(alpha = 0.55f)))
        // 绿光斑：与主界面色场同语言（左上主光斑 + 右下回弹）
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF34C759).copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(x = 260f, y = 380f),
                    radius = 900f
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF4AC8FF).copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(x = 1100f, y = 2300f),
                    radius = 800f
                )
            )
        )

        // ── 中央徽记 + 标题 ──
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GlassEmblem(
                sweep = sweepProgress.value,
                modifier = Modifier
                    .size(112.dp)
                    .scale(emblemScale.value)
                    .alpha(emblemAlpha.value)
            )
            Spacer(Modifier.height(26.dp))
            Text(
                text = "Edqiu",
                color = Color(0xFFF4FBF6),
                fontSize = 34.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp,
                style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 2f), 8f)),
                modifier = Modifier
                    .alpha(titleProgress.value)
                    .graphicsLayerY(titleProgress.value)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "X 视频收件箱 · 媒体库 · 订阅",
                color = Color(0xFFBFE8CD).copy(alpha = 0.85f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
                modifier = Modifier.alpha(captionAlpha.value)
            )
        }

        // ── 右上角跳过胶囊（与 App 玻璃语言同构）──
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 52.dp, end = 20.dp)
                .alpha(chromeAlpha.value)
                .clip(CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                .background(Color.White.copy(alpha = 0.10f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onSkip
                )
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            Text("跳过", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }

        // ── 底部版本号 ──
        Text(
            text = "v${com.ed.edqiu.BuildConfig.VERSION_NAME}",
            color = Color.White.copy(alpha = 0.38f),
            fontSize = 11.sp,
            letterSpacing = 1.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 46.dp)
                .alpha(chromeAlpha.value)
        )
    }
}

/** 标题浮起：alpha 驱动 + 12dp→0 上移。 */
private fun Modifier.graphicsLayerY(progress: Float): Modifier =
    this.then(
        Modifier.padding(top = ((1f - progress) * 10).dp)
    )

/**
 * 玻璃徽记：外环白渐变描边（随 sweep 单程扫过高光）+ 内核绿玻璃 + E 字。
 * 与「我的」页头像 / Tab 栏徽章同构的品牌符号。
 */
@Composable
private fun GlassEmblem(sweep: Float, modifier: Modifier = Modifier) {
    val ringColor = Color.White
    Box(
        modifier = modifier
            .shadow(20.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.6f))
            .drawBehind {
                // 玻璃环：基础半透明白描边
                drawCircle(
                    color = ringColor.copy(alpha = 0.30f),
                    radius = size.minDimension / 2f - 2.dp.toPx(),
                    style = Stroke(width = 2.dp.toPx())
                )
                // 扫掠高光：单程掠过环带的亮弧（0.35→1 alpha 峰值随 sweep 移动）
                if (sweep > 0.01f) {
                    val angle = (-90f + sweep * 360f) * Math.PI.toFloat() / 180f
                    val r = size.minDimension / 2f - 2.dp.toPx()
                    val cx = center.x + kotlin.math.cos(angle) * r
                    val cy = center.y + kotlin.math.sin(angle) * r
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = (0.85f * (1f - kotlin.math.abs(sweep * 2f - 1f))).coerceIn(0f, 0.85f)),
                                Color.Transparent
                            ),
                            center = Offset(cx, cy),
                            radius = 46f
                        ),
                        radius = 46f,
                        center = Offset(cx, cy)
                    )
                }
            }
    ) {
        // 内核：深色玻璃底 + 绿色辉光
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF2FA352).copy(alpha = 0.55f),
                            Color(0xFF0E1F16).copy(alpha = 0.92f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "E",
                color = Color(0xFFEAFBF0),
                fontSize = 44.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1).sp
            )
        }
    }
}
