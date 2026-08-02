package com.ed.twitterdownloader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ed.twitterdownloader.ExternalDownloadRequest
import com.ed.twitterdownloader.ui.components.UrlInputBar
import com.ed.twitterdownloader.ui.components.VideoInfoCard
import com.ed.twitterdownloader.viewmodel.DownloadViewModel
import com.ed.twitterdownloader.viewmodel.HomeViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    downloadViewModel: DownloadViewModel,
    externalDownloadRequest: ExternalDownloadRequest? = null,
    onExternalDownloadConsumed: (Long) -> Unit = {}
) {
    val vm: HomeViewModel = viewModel()
    val state by vm.uiState.collectAsState()
    val engineReady by vm.isEngineInitialized.collectAsState()
    val engineError by vm.engineInitError.collectAsState()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(externalDownloadRequest?.requestId) {
        externalDownloadRequest?.let {
            vm.updateUrl(it.url)
            vm.resolveUrl()
            onExternalDownloadConsumed(it.requestId)
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("视频下载器", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                    Text(if (engineReady) "yt-dlp / FXTwitter 已就绪" else "下载引擎初始化中", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onNavigateToSettings) { Text("设置") }
            }
        }
        if (engineError != null) item {
            AssistChip(onClick = vm::retryEngineInit, label = { Text("引擎初始化失败：$engineError，点击重试") })
        }
        item {
            UrlInputBar(
                url = state.urlInput,
                onUrlChange = vm::updateUrl,
                onResolve = vm::resolveUrl,
                isLoading = state.isResolving
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val text = clipboard.getText()?.text.orEmpty()
                    if (text.isNotBlank()) vm.updateUrl(text)
                }) { Text("读取剪贴板") }
                OutlinedButton(onClick = { state.videoInfo?.let(downloadViewModel::startDownloadAll) }, enabled = state.videoInfo != null) { Text("全部下载") }
            }
        }
        state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
        state.videoInfo?.let { info ->
            item { VideoInfoCard(videoInfo = info) }
            items(info.formats.size) { index ->
                val format = info.formats[index]
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(format.displayText, fontWeight = FontWeight.Bold)
                            Text(format.mediaType.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = { downloadViewModel.startDownload(info, format) }) { Text("下载") }
                    }
                }
            }
        }
        if (state.videoInfo == null && state.error == null) item {
            Card { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("粘贴推文链接开始下载", fontWeight = FontWeight.Bold); Text("支持 x.com 和 twitter.com，优先 FXTwitter 直链，失败后使用 yt-dlp。") } }
        }
    }
}
