package com.ed.twitterdownloader.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ed.twitterdownloader.data.model.VideoFormat

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
            FilterChip(
                selected = selectedFormat?.formatId == format.formatId &&
                    selectedFormat.mediaIndex == format.mediaIndex,
                onClick = { onFormatSelected(format) },
                label = {
                    Text(
                        text = format.displayText,
                        style = MaterialTheme.typography.labelLarge
                    )
                },
                shape = MaterialTheme.shapes.small,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    }
}

