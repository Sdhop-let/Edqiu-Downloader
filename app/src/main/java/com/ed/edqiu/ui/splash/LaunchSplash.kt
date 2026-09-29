package com.ed.edqiu.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 0.5s 极简开屏 —— 仅首次安装显示，淡入 + 停留 + 淡出。品牌名：Edqiu */
@Composable
fun LaunchSplash(onFinished: () -> Unit) {
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // 淡入 150ms
        alpha.animateTo(1f, animationSpec = tween(150))
        // 停留 200ms
        kotlinx.coroutines.delay(200L)
        // 淡出 150ms
        alpha.animateTo(0f, animationSpec = tween(150))
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF2563EB)), // 品牌蓝底
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Edqiu",
            modifier = Modifier.alpha(alpha.value),
            color = Color.White,
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
    }
}
