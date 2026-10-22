package com.ed.edqiu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * debug-only：「ColorOS 离屏白框坑」真伪判定实验（2026-10-22）。
 *
 * 背景：MEMORY 记载 v1.4.8 起立下铁律「玻璃组件永不加 Modifier.blur——ColorOS 离屏渲染
 * 合成方形白框」，但 2026-09-14 日志自认该结论只是「最强假设」，且计划中的
 * 「关模糊开关截图对比」实验从未执行。本页用受控用例一次性判定：
 *
 * - C1 复现 v1.4.6 已确认根因：默认 BlurredEdgeTreatment.Rectangle 把白色磨砂层
 *   以方形边界 clamp，圆角卡内露方形白边（Compose 语义，与 OS 无关）。
 * - C2 复现 v1.4.7 被指「ColorOS 离屏白框」的配置：blur(6dp, Unbounded)。
 *   若本页 C2 无白带 → 铁律的 OS 归因破产。
 * - C3 干净真模糊（re-enable 候选）：无白装饰层，直接 blur(16dp) + 标准 clip。
 * - C4 对照组：同 C3 但不 blur。
 *
 * 深/浅色各截一张图（白带在深色下最明显）。
 */
class BlurTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var dark by remember { mutableStateOf(true) }
            MaterialTheme(
                colorScheme = if (dark) darkColorScheme() else lightColorScheme()
            ) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 48.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Blur 白框实验（debug）",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Button(onClick = { dark = !dark }) {
                                Text(if (dark) "切浅色" else "切深色")
                            }
                        }

                        Case(
                            tag = "C1 · v1.4.6 已确认根因复现",
                            note = "白磨砂层 + blur(6dp) 默认 Rectangle 边缘处理，外层圆角 clip。预期：圆角内露方形白边（Compose 语义，非 OS）",
                            edgeTreatment = BlurredEdgeTreatment.Rectangle,
                            blurRadius = 6.dp
                        )
                        Case(
                            tag = "C2 · v1.4.7「ColorOS 白框」配置复现",
                            note = "同上但 Unbounded。历史判定：白色磨砂从卡片下缘向外洇出同宽亮带。本页若无白带 → OS 归因不成立",
                            edgeTreatment = BlurredEdgeTreatment.Unbounded,
                            blurRadius = 6.dp
                        )
                        Case(
                            tag = "C3 · 干净真模糊（re-enable 候选）",
                            note = "无白装饰层，彩色内容直接 blur(16dp) + 标准 clip。观察有无伪影/白边",
                            edgeTreatment = BlurredEdgeTreatment.Rectangle,
                            blurRadius = 16.dp,
                            cleanBlur = true
                        )
                        Case(
                            tag = "C4 · 对照组（无 blur）",
                            note = "同 C3 但不 blur。与 C3 对比确认模糊生效且干净",
                            edgeTreatment = BlurredEdgeTreatment.Rectangle,
                            blurRadius = 0.dp,
                            cleanBlur = true
                        )
                        Spacer(Modifier.height(40.dp))
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Case(
    tag: String,
    note: String,
    edgeTreatment: BlurredEdgeTreatment,
    blurRadius: androidx.compose.ui.unit.Dp,
    cleanBlur: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tag, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Text(
            note,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(16.dp))
        ) {
            // 彩色「照片」背景：给 blur 提供可折射内容
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                drawRect(Color(0xFF20342E))
                drawCircle(Color(0xFFE8A33D), radius = w * 0.20f, center = Offset(w * 0.18f, h * 0.30f))
                drawCircle(Color(0xFF3E7CB1), radius = w * 0.24f, center = Offset(w * 0.62f, h * 0.22f))
                drawCircle(Color(0xFFC4543C), radius = w * 0.18f, center = Offset(w * 0.85f, h * 0.62f))
                drawCircle(Color(0xFF6B9E4F), radius = w * 0.22f, center = Offset(w * 0.35f, h * 0.75f))
                drawCircle(Color(0xFFD9C9A3), radius = w * 0.12f, center = Offset(w * 0.50f, h * 0.50f))
                // 细条纹：模糊后应化为柔雾（有细节才看得出模糊）
                for (i in 0 until 12) {
                    val x = w * (i / 12f)
                    drawRect(
                        color = Color.White.copy(alpha = 0.10f),
                        topLeft = Offset(x, 0f),
                        size = androidx.compose.ui.geometry.Size(w / 48f, h)
                    )
                }
            }
            // 被测玻璃卡
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(width = 230.dp, height = 110.dp)
                    .clip(RoundedCornerShape(24.dp))
            ) {
                if (cleanBlur) {
                    // C3/C4：卡内直接放彩色内容，对内容做模糊
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(
                                if (blurRadius > 0.dp) {
                                    Modifier.blur(blurRadius, edgeTreatment)
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawRect(Color(0xFF2E5945))
                            drawCircle(Color(0xFFE8A33D), radius = size.width * 0.30f, center = Offset(size.width * 0.25f, size.height * 0.35f))
                            drawCircle(Color(0xFF3E7CB1), radius = size.width * 0.34f, center = Offset(size.width * 0.70f, size.height * 0.60f))
                            drawCircle(Color(0xFFC4543C), radius = size.width * 0.20f, center = Offset(size.width * 0.55f, size.height * 0.25f))
                        }
                    }
                } else {
                    // C1/C2：复现历史磨砂层——白色渐变 + blur
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.22f),
                                        Color.White.copy(alpha = 0.10f),
                                        Color.White.copy(alpha = 0.16f)
                                    )
                                )
                            )
                            .then(
                                if (blurRadius > 0.dp) {
                                    Modifier.blur(blurRadius, edgeTreatment)
                                } else {
                                    Modifier
                                }
                            )
                    )
                }
            }
        }
    }
}
