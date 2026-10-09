package com.ed.edqiu.ui.anim

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * 全局动画令牌表（2026-10-10 全量重构 · 总指令 §06「统一的动画系统」）。
 *
 * 单一事实来源：核心链路（导航转场/浮层/播放器控制层/折叠标题/按压反馈/滑杆）
 * 的时长与弹簧参数从这里取值；各页面一次性的局部过渡动画允许保留字面量，
 * 但新增动画一律先查本表——禁止再出现第 17 种 tween 时长。
 *
 * 时长语义分层（ms）：
 * - [Micro]：图标转换、颜色/透明度反馈等亚感知级微交互
 * - [Control]：滑杆/轨道增强、chip 状态切换等控件级过渡
 * - [PanelIn]/[Panel]：控制层与小面板进出（enter/exit 成对，enter 比 exit 长——消失要快）
 * - [Screen]：tab 切换、弹窗、折叠标题等页面级过渡
 * - [PlayerEnter]/[PlayerExit]：播放器容器变形（v1.6.9 验收节奏，只引用不改动）
 * - [OverlayExit]：右缘拖拽跟手返回的松手结算
 */
object Motion {
    // ===== 时长 =====

    /** 微交互：播放/暂停图标转换等。 */
    const val Micro = 140

    /** 控件级过渡：滑杆轨道增强、chip 状态。 */
    const val Control = 150

    /** 面板/控制层淡出（配 [PanelIn]）。 */
    const val Panel = 160

    /** 面板/控制层淡入（配 [Panel]）。 */
    const val PanelIn = 220

    /** 页面级：tab 切换、弹窗、大标题折叠行程内过渡。 */
    const val Screen = 300

    /** 播放器容器变形进场（430ms EaseOutCubic，验收定案）。 */
    const val PlayerEnter = 430

    /** 播放器容器变形退场（460ms EaseOutCubic，验收定案）。 */
    const val PlayerExit = 460

    /** 浮层右缘拖拽松手结算。 */
    const val OverlayExit = 400

    // ===== 缓动曲线 =====

    /** 全局主曲线（对应 FastOutSlowIn 语义，显式声明以便统一替换）。 */
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 出场/展开类（容器变形、面板滑入的减速度）。 */
    val EaseOutCubic = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

    // ===== 弹簧 =====

    /** 按压缩放反馈（低刚度、轻微过冲、无机械弹跳）。 */
    fun pressed() = spring<Float>(dampingRatio = 0.75f, stiffness = 600f)

    /** 滑杆 thumb / 播放进度 thumb 增强。 */
    fun thumb() = spring<Float>(dampingRatio = 0.8f, stiffness = 500f)

    /** 通用弹性（浮层滑入等既有手感）。 */
    fun standard() = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
}
