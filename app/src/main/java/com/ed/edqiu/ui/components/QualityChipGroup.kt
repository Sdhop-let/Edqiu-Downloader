package com.ed.edqiu.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ed.edqiu.data.model.VideoFormat

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QualityChipGroup(
    formats: List<VideoFormat>,
    selectedFormat: VideoFormat?,
    onFormatSelected: (VideoFormat) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        formats.forEach { format ->
            // 2026-10-10 统一分段胶囊（替代 M3 FilterChip，选中=primary 实底 tint 语义）
            PillChip(
                label = format.displayText,
                selected = selectedFormat?.formatId == format.formatId &&
                    selectedFormat.mediaIndex == format.mediaIndex,
                onClick = { onFormatSelected(format) },
            )
        }
    }
}

