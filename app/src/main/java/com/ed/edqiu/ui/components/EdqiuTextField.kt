package com.ed.edqiu.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ed.edqiu.ui.anim.Motion

/**
 * iOS 风填充输入场（设计系统组件，2026-10-13）。
 *
 * 视觉：surfaceContainerLow 填充 + 12dp 圆角（Shapes.small 语义），常态无边框；
 * 聚焦时填充提亮一档 + primary 细描边浮现；label 独立于场上方（iOS grouped 表单语言，
 * 不再使用 M3 浮动标签）；光标色走 primary。
 *
 * 与 M3 OutlinedTextField 的分工：2026-10-13 起全应用输入场统一由本组件承担
 * （v1.9.1 完成全量迁移），新增表单一律使用本组件，不再引入 M3 TextField。
 */
@Composable
fun EdqiuTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    enabled: Boolean = true,
    isError: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    val container by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f)
            focused -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = tween(Motion.Control),
        label = "edqiuTextFieldContainer"
    )
    val outline by animateColorAsState(
        targetValue = when {
            isError -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
            focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            else -> Color.Transparent
        },
        animationSpec = tween(Motion.Control),
        label = "edqiuTextFieldOutline"
    )

    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = onSurfaceVariant,
                modifier = Modifier.padding(bottom = 5.dp)
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .background(container, RoundedCornerShape(12.dp))
                .border(1.dp, outline, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = singleLine,
            minLines = minLines,
            enabled = enabled,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            interactionSource = interaction,
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && placeholder != null) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1
                        )
                    }
                    inner()
                }
            }
        )
    }
}
