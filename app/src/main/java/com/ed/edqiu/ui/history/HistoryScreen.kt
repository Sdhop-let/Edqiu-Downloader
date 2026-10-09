package com.ed.edqiu.ui.history

import com.ed.edqiu.ui.util.pressableNoRipple
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ed.edqiu.ui.components.CollapsingNavTitleBar
import com.ed.edqiu.ui.components.EmptyState
import com.ed.edqiu.ui.components.FeedbackKind
import com.ed.edqiu.ui.components.GlassSurface
import com.ed.edqiu.ui.components.GlassTier
import com.ed.edqiu.ui.components.SmoothAlertDialog
import com.ed.edqiu.ui.components.rememberTitleCollapseProgress
import com.ed.edqiu.ui.navigation.LocalSnackbarController
import java.text.DateFormat
import java.util.Date

/**
 * 回收站（2026-10-10 全量重构：移除全工程最后一处 M3 原生 TopAppBar/BottomAppBar/Card 形态）。
 *
 * 统一为 iOS 大标题语言：列表内大标题随滚动收起，顶部紧凑导航条淡入；
 * 批量操作条与列表项改用全局玻璃体系（L2 操作条 / L1 卡片），与收件箱同构。
 */
@Composable
fun HistoryScreen(
    vm: HistoryViewModel,
    onBack: (() -> Unit)? = null
) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val selected by vm.selectedIds.collectAsStateWithLifecycle()
    val feedback by vm.feedback.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarController.current
    var confirmPermanentDelete by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val titleCollapse by rememberTitleCollapseProgress(listState)

    LaunchedEffect(feedback) {
        feedback?.let {
            snackbar.show(it, kind = FeedbackKind.SUCCESS)
            vm.clearFeedback()
        }
    }

    if (confirmPermanentDelete) {
        SmoothAlertDialog(
            onDismissRequest = { confirmPermanentDelete = false },
            title = { Text("永久删除历史记录？") },
            text = { Text("选中的 ${selected.size} 条记录将无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmPermanentDelete = false
                    vm.permanentlyDeleteSelected()
                }) { Text("永久删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmPermanentDelete = false }) { Text("取消") }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CollapsingNavTitleBar(
            title = "回收站",
            progress = titleCollapse,
            modifier = Modifier.align(Alignment.TopCenter),
            onBack = onBack
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 132.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 大标题头（列表内，随滚动收起——与下载中心同一模式）
            item(key = "trash_header") {
                Text(
                    text = "回收站",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(start = 2.dp, top = 6.dp, bottom = 10.dp)
                )
            }

            if (entries.isEmpty()) {
                item(key = "trash_empty") {
                    EmptyState(
                        icon = "♻️",
                        title = "回收站为空",
                        description = "以后删除的收件箱记录会先保存在这里",
                        modifier = Modifier.statusBarsPadding()
                    )
                }
            } else {
                items(entries, key = { it.archiveId }) { entry ->
                    GlassSurface(
                        tier = GlassTier.L1,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressableNoRipple { vm.toggleSelected(entry.archiveId) }
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 6.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = entry.archiveId in selected,
                                onCheckedChange = { vm.toggleSelected(entry.archiveId) },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = MaterialTheme.colorScheme.primary
                                )
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    entry.authorName ?: entry.authorId ?: entry.tweetId,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                entry.caption?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        it,
                                        maxLines = 2,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    "删除于 ${DateFormat.getDateTimeInstance().format(Date(entry.deletedAt))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // 底部批量操作条：玻璃化（替代 M3 BottomAppBar），与收件箱 BatchActionBar 同语言
        AnimatedVisibility(
            visible = selected.isNotEmpty(),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            GlassSurface(
                tier = GlassTier.L2,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "已选 ${selected.size}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = {
                        if (selected.size == entries.size) vm.clearSelection() else vm.selectAll()
                    }) {
                        Text(if (selected.size == entries.size) "取消全选" else "全选")
                    }
                    TextButton(onClick = vm::restoreSelected) {
                        Icon(
                            Icons.Default.Restore,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        Text("恢复")
                    }
                    TextButton(
                        onClick = { confirmPermanentDelete = true },
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            Icons.Default.DeleteForever,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        Text("永久删除")
                    }
                }
            }
        }
    }
}
