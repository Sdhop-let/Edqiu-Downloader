package com.ed.edqiu.ui.history

import com.ed.edqiu.ui.util.pressableNoRipple
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ed.edqiu.ui.components.EmptyState
import com.ed.edqiu.ui.components.FeedbackKind
import com.ed.edqiu.ui.navigation.LocalSnackbarController
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    vm: HistoryViewModel
) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val selected by vm.selectedIds.collectAsStateWithLifecycle()
    val feedback by vm.feedback.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarController.current
    var confirmPermanentDelete by remember { mutableStateOf(false) }

    LaunchedEffect(feedback) {
        feedback?.let {
            snackbar.show(it, kind = FeedbackKind.SUCCESS)
            vm.clearFeedback()
        }
    }

    if (confirmPermanentDelete) {
        AlertDialog(
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("回收站") }
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = selected.isNotEmpty(),
                enter = slideInVertically { it },
                exit = slideOutVertically { it }
            ) {
                BottomAppBar {
                    Text(
                        "已选 ${selected.size}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium
                    )
                    TextButton(onClick = {
                        if (selected.size == entries.size) vm.clearSelection() else vm.selectAll()
                    }) {
                        Text(if (selected.size == entries.size) "取消全选" else "全选")
                    }
                    Button(onClick = vm::restoreSelected) {
                        Icon(Icons.Default.Restore, contentDescription = null)
                        Text(" 恢复")
                    }
                    TextButton(onClick = { confirmPermanentDelete = true }) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                        Text(" 永久删除")
                    }
                }
            }
        }
    ) { padding ->
        if (entries.isEmpty()) {
            EmptyState(
                icon = "♻️",
                title = "回收站为空",
                description = "以后删除的收件箱记录会先保存在这里",
                modifier = Modifier.padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(entries, key = { it.archiveId }) { entry ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressableNoRipple { vm.toggleSelected(entry.archiveId) },
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        // 列表滚动项：去除默认 elevation 阴影，减滚动合成压力
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = entry.archiveId in selected,
                                onCheckedChange = { vm.toggleSelected(entry.archiveId) }
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
    }
}