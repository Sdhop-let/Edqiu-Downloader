package com.ed.edqiu.ui.splash

import com.ed.edqiu.BuildConfig
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private enum class SplashStage {
    Launch,
    Brand,
    Enter
}

private val SplashBlue = Color(0xFF2563EB)
private val SplashInk = Color(0xFF111827)
private val SplashMuted = Color(0xFF6B7280)
private val SplashLine = Color(0xFFE5E7EB)

@Composable
fun EdqiuSplashFlow(
    modifier: Modifier = Modifier,
    onFinished: () -> Unit
) {
    var stage by remember { mutableStateOf(SplashStage.Launch) }

    LaunchedEffect(stage) {
        when (stage) {
            SplashStage.Launch -> {
                delay(2300L)
                stage = SplashStage.Brand
            }

            SplashStage.Brand -> {
                delay(3000L)
                stage = SplashStage.Enter
            }

            SplashStage.Enter -> {
                delay(2400L)
                onFinished()
            }
        }
    }

    Crossfade(
        targetState = stage,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "EdqiuSplashStage",
        modifier = modifier.fillMaxSize()
    ) { targetStage ->
        when (targetStage) {
            SplashStage.Launch -> LaunchStage()
            SplashStage.Brand -> BrandStage(onSkip = { stage = SplashStage.Enter })
            SplashStage.Enter -> EnterStage()
        }
    }
}

@Composable
private fun LaunchStage() {
    val progress = remember { Animatable(0f) }
    val pulse by rememberInfiniteTransition(label = "SplashGlow")
        .animateFloat(
            initialValue = 0.94f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "GlowScale"
        )

    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 2000, delayMillis = 300, easing = FastOutSlowInEasing)
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(180.dp)
                .scale(pulse)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            SplashBlue.copy(alpha = 0.12f),
                            SplashBlue.copy(alpha = 0.04f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        EdqiuWordmark(fontSize = 48, compact = true)

        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(2.dp)
                .background(SplashBlue.copy(alpha = 0.10f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress.value)
                    .background(SplashBlue)
            )
        }
    }
}

@Composable
private fun BrandStage(onSkip: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onSkip)
            .background(
                Brush.radialGradient(
                    colors = listOf(Color.White, Color(0xFFF9FAFB)),
                    radius = 900f
                )
            )
            .padding(horizontal = 32.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 136.dp, bottom = 76.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            EdqiuWordmark(fontSize = 32, compact = false)
            Text(
                text = "链接收件 · 视频下载",
                modifier = Modifier.padding(top = 12.dp),
                color = SplashMuted,
                fontSize = 13.sp,
                letterSpacing = 1.1.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top
            ) {
                FeatureItem(icon = Icons.Outlined.Link, label = "链接解析")
                FeatureItem(icon = Icons.Outlined.Download, label = "快速下载")
                FeatureItem(icon = Icons.Outlined.Inbox, label = "智能收件")
            }

            Spacer(modifier = Modifier.weight(1f))

        }
    }
}

@Composable
private fun EnterStage() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.White, Color.White, Color(0xFFEFF6FF))
                )
            )
            .padding(horizontal = 32.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(0.28f))
            EdqiuWordmark(fontSize = 40, compact = true)
            Text(
                text = "让链接触手可取",
                modifier = Modifier.padding(top = 14.dp),
                color = Color(0xFF374151),
                fontSize = 15.sp
            )

            Spacer(modifier = Modifier.weight(0.56f))
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                modifier = Modifier.padding(bottom = 32.dp),
                color = Color(0xFFD1D5DB),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun EdqiuWordmark(fontSize: Int, compact: Boolean) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = SplashInk)) {
                append("Es")
            }
            withStyle(SpanStyle(color = SplashBlue)) {
                append(if (compact) "Qp" else " Qp")
            }
        },
        fontSize = fontSize.sp,
        lineHeight = fontSize.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        letterSpacing = (-0.5).sp
    )
}

@Composable
private fun FeatureItem(icon: ImageVector, label: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            color = Color(0xFFF3F4F6)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = SplashBlue,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Text(label, color = SplashMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
