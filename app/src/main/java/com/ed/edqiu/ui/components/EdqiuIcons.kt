package com.ed.edqiu.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Edqiu 品牌图标集 —— 「E 化身」系列。
 *
 * 设计语言：三个图标共用 E 的骨架（一竖三横），但每个都让 E 化身成功能本身：
 * - [Inbox]   E 化收件筐：竖线变筐壁、三横变层板，开口处一支"放入"箭头
 * - [Media]   E 化播放器：竖线 + 上下横成播放器框，中间横变播放三角
 * - [Profile] E 化人形：竖线化身、三横成递减肩线
 *
 * 品牌即形状，形状即功能 —— 无需签名徽章。
 * 统一 2f 线宽 + 圆角线帽/线接，保持线性图标语言。
 */
object EdqiuIcons {

    /** 收件箱 · E 化收件筐 */
    val Inbox: ImageVector by lazy {
        ImageVector.Builder(
            name = "EdqiuInbox",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // 筐（左侧竖线 + 顶部 + 右侧圆角 + 底部开口）
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null
            ) {
                moveTo(5f, 4f)
                lineTo(16f, 4f)
                arcTo(3f, 3f, 0f, false, true, 19f, 7f)
                lineTo(19f, 13f)
                arcTo(3f, 3f, 0f, false, true, 16f, 16f)
                lineTo(9f, 16f)
            }
            // E 层板一
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                fill = null
            ) {
                moveTo(5f, 9.5f)
                lineTo(14f, 9.5f)
            }
            // E 层板二
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                fill = null
            ) {
                moveTo(5f, 14.5f)
                lineTo(16f, 14.5f)
            }
            // 放入箭头（筐口向下）
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null
            ) {
                moveTo(17.5f, 15.5f)
                lineTo(17.5f, 20f)
                moveTo(17.5f, 20f)
                lineTo(19.4f, 18.1f)
                moveTo(17.5f, 20f)
                lineTo(15.6f, 18.1f)
            }
        }.build()
    }

    /** 媒体库 · E 化播放器 */
    val Media: ImageVector by lazy {
        ImageVector.Builder(
            name = "EdqiuMedia",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // E 竖线
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                fill = null
            ) {
                moveTo(8f, 4.5f)
                lineTo(8f, 19.5f)
            }
            // 上横（播放器顶框）
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                fill = null
            ) {
                moveTo(8f, 4.5f)
                lineTo(17.5f, 4.5f)
            }
            // 下横（播放器底框）
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                fill = null
            ) {
                moveTo(8f, 19.5f)
                lineTo(16f, 19.5f)
            }
            // 播放三角（E 的中间横被替换）
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.6f,
                strokeLineJoin = StrokeJoin.Round,
                fill = SolidColor(Color.Black)
            ) {
                moveTo(9.5f, 11.2f)
                lineTo(9.5f, 16.8f)
                lineTo(14.5f, 14f)
                close()
            }
        }.build()
    }

    /** 我的 · E 化人形 */
    val Profile: ImageVector by lazy {
        ImageVector.Builder(
            name = "EdqiuProfile",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // 头（E 竖线顶部的圆角）
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                fill = null
            ) {
                moveTo(8.6f, 8.3f)
                arcTo(2.9f, 2.9f, 0f, true, true, 8.6f, 2.5f)
                arcTo(2.9f, 2.9f, 0f, true, true, 8.6f, 8.3f)
            }
            // 身（E 竖线延伸）
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                fill = null
            ) {
                moveTo(8.6f, 8.3f)
                lineTo(8.6f, 12.5f)
            }
            // 肩线一（E 三横之一）
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                fill = null
            ) {
                moveTo(5.2f, 16f)
                lineTo(16.2f, 16f)
            }
            // 肩线二（E 三横之二）
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                fill = null
            ) {
                moveTo(6.4f, 19f)
                lineTo(14.8f, 19f)
            }
            // 肩线三（E 三横之三）
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                fill = null
            ) {
                moveTo(7.6f, 22f)
                lineTo(13.6f, 22f)
            }
        }.build()
    }
}
