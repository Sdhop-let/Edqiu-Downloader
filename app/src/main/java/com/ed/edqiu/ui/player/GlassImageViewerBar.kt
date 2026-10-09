package com.ed.edqiu.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ed.edqiu.ui.components.MediaGlassSurface
import com.ed.edqiu.ui.util.pressableNoRipple
import com.ed.edqiu.ui.anim.Motion

/**
 * 图片查看器专属底部功能栏（2026-10-02 批次A）。
 *
 * 图片会话不再渲染 GlassActionsPanel / GlassPlayerControls / 播放圆钮 / 播放列表横条，
 * 底部只保留这一行动作：保存到相册 / 分享 / 打开X / 复制链接 / 删除。
 * 视觉对齐 GlassActionsPanel：MediaGlassSurface 深色玻璃（黑 60% + 顶部高光 + 内描边）+
 * GlassActionItem 同款动作位（白 12% 圆角底 / 删除位红系）；进出动画对齐现有面板
 *（fadeIn + slideInVertically，时长与 GlassPlayerControls 同规格）。
 * 动作回调全部由 PlayerScreen 注入（复用 shareMedia/openSource/copySourceLink 与
 * pendingDelete 流程，保存到相册为 PlayerScreen 私有 saveImageToGallery）。
 */
@Composable
fun GlassImageViewerBar(
    visible: Boolean,
    onSaveToGallery: () -> Unit,
    onShare: () -> Unit,
    onOpenX: () -> Unit,
    onCopyLink: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        // 2026-10-02 批次A：对齐 GlassPlayerControls 的进出动画规格（220/160ms + 1/3 位移）
        enter = fadeIn(tween(Motion.PanelIn)) +
            slideInVertically(tween(Motion.PanelIn)) { it / 3 },
        exit = fadeOut(tween(Motion.Panel)) +
            slideOutVertically(tween(Motion.Panel)) { it / 3 },
        modifier = modifier
    ) {
        MediaGlassSurface(
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // 动作位内容宽度不等（"保存到相册"5 字 vs "删除"2 字），SpaceBetween 让
            // 等间距分布随屏宽自适应；小字号（labelSmall）下各档位中文案不截断
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                GlassImageBarItem(
                    text = "保存到相册",
                    icon = Icons.Default.Save,
                    onClick = onSaveToGallery
                )
                GlassImageBarItem(
                    text = "分享",
                    icon = Icons.Outlined.Share,
                    onClick = onShare
                )
                GlassImageBarItem(
                    text = "打开X",
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    onClick = onOpenX
                )
                GlassImageBarItem(
                    text = "复制链接",
                    icon = Icons.Default.ContentCopy,
                    onClick = onCopyLink
                )
                GlassImageBarItem(
                    text = "删除",
                    icon = Icons.Default.Delete,
                    onClick = onDelete,
                    danger = true
                )
            }
        }
    }
}

/** 单个动作位：视觉对齐 GlassActionsPanel 内的 GlassActionItem（图标 18dp + labelSmall 黑体字）。 */
@Composable
private fun GlassImageBarItem(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    danger: Boolean = false
) {
    // 深色玻璃上的动作位配色与 GlassActionItem 完全一致（删除位红系警示）
    val bg = if (danger) Color(0xFFFF3B30).copy(alpha = 0.28f)
    else Color.White.copy(alpha = 0.12f)
    val content = if (danger) Color(0xFFFFB4AB) else Color.White
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .pressableNoRipple { onClick() }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = text, tint = content, modifier = Modifier.size(18.dp))
        Text(
            text = text,
            color = content,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}
