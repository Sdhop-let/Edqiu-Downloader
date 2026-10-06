package com.ed.edqiu.ui.components

import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 二级页右缘跟手侧滑返回容器（2026-10）。
 *
 * 背景：自绘跟手返回此前只挂在 AppNav 的浮层路由（详情/网盘备份/媒体备份）上，
 * NavHost 二级页（如「WebDAV 同步」设置页）只有返回箭头，右缘侧滑返回缺失。
 *
 * 行为对齐浮层版拖拽（AppNav 浮层 pointerInput 同款规格）：
 * - 右缘 30dp 起手，位移过 slop 后按方向判定：横向 → 页面跟手右移（可逆），纵向 → 放行给滚动；
 *   手势语言与浮层一致：向左拖 = 关闭度增加（页面右移出屏）；
 * - 松手位移 > 35% 屏宽 → 先跟手滑出整屏再回调 [onBack]（NavHost popExit 从屏外继续，无跳变）；
 * - 否则回弹原位；
 * - 右缘挂 systemGestureExclusion 条（系统限高 200dp），把条内触摸从系统返回手势手中
 *   让给本组件；条外系统返回仍走 BackHandler 兜底。
 *
 * 实现注意（2026-10 实证）：`awaitEachGesture` 把手势循环整个跑在
 * `awaitPointerEventScope` 内、协程存活期间永不返回——任何"块外结算"都执行不到。
 * 结算动画必须经 [settleScope]（组合作用域）在事件作用域外启动。
 *
 * [enabled] = false 时不做任何手势判定、不挂排除条（用于一级页）。
 */
@Composable
fun EdgeSwipeBackBox(
    enabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val settleScope = rememberCoroutineScope()
    val settleJob = remember { mutableStateOf<Job?>(null) }

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = offsetX }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        // 结算动画进行中：旁观到手指抬起，不与新手势打架
                        if (settleJob.value?.isActive == true) {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Main)
                                if (e.changes.all { !it.pressed }) break
                            }
                            return@awaitEachGesture
                        }
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.position.x < size.width - edgePx()) {
                            // 非右缘起手：放行给子级（列表滚动等），仅旁观至手势结束
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Main)
                                if (e.changes.all { !it.pressed }) break
                            }
                            return@awaitEachGesture
                        }
                        val downId = down.id
                        var dirDecided = false
                        var isBack = false
                        var released = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == downId } ?: continue
                            if (!change.pressed) {
                                change.consume()
                                released = true
                                break
                            }
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            if (!dirDecided) {
                                if (abs(dx) < slopPx() && abs(dy) < slopPx()) continue
                                dirDecided = true
                                if (abs(dy) >= abs(dx)) break
                                isBack = true
                                dragging = true
                            }
                            if (!isBack) break
                            offsetX = (-dx).coerceIn(0f, size.width.toFloat())
                            change.consume()
                        }
                        if (isBack && released) {
                            val commit = offsetX > size.width * 0.35f
                            settleJob.value = settleScope.launch {
                                animate(
                                    initialValue = offsetX,
                                    targetValue = if (commit) size.width.toFloat() else 0f,
                                    animationSpec = tween(280, easing = EaseOutCubic)
                                ) { v, _ -> offsetX = v }
                                dragging = false
                                if (commit) onBack()
                            }
                        }
                    }
                }
        ) { content() }
        // 拖拽期间内容压暗（纯绘制层，不拦截触摸）
        AnimatedVisibility(
            visible = dragging,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(200)),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.16f)))
        }
        // 右缘系统手势排除条：系统限 200dp 高，条内边缘滑动归本组件、条外归系统返回
        if (enabled) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(24.dp)
                    .height(200.dp)
                    .systemGestureExclusion()
            )
        }
    }
}

/** 右缘起手判定带宽（px）。 */
private fun androidx.compose.ui.input.pointer.PointerInputScope.edgePx(): Float = 30.dp.toPx()

/** 方向判定 slop（px）。 */
private fun androidx.compose.ui.input.pointer.PointerInputScope.slopPx(): Float = 12.dp.toPx()
