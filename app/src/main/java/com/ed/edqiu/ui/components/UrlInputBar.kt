package com.ed.edqiu.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun UrlInputBar(
    url: String,
    onUrlChange: (String) -> Unit,
    onResolve: () -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("粘贴 X/Twitter 链接") }
        )
        Button(onClick = onResolve, enabled = !isLoading && url.isNotBlank()) {
            Text(if (isLoading) "解析中" else "解析")
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}
