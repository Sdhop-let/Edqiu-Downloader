package com.ed.edqiu.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ed.edqiu.data.model.LinkStatus
import com.ed.edqiu.ui.theme.statusColor

/**
 * 状态徽标：圆点 + 文字标签，用于详情页。
 * 列表页使用 StatusDot 即可。
 */
@Composable
fun StatusBadge(status: LinkStatus, modifier: Modifier = Modifier) {
    val label = when (status) {
        LinkStatus.PENDING -> "未下载"
        LinkStatus.DOWNLOADED -> "已下载"
        LinkStatus.FAILED -> "失败"
        LinkStatus.DELETED -> "推文不存在"
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(status = status)
        Text(
            text = label,
            color = statusColor(status),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
    }
}