package com.github.lonepheasantwarrior.talkify.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 实时语音波形（voice-memo 风格圆头竖条）
 *
 * 振幅历史来自预览播放链路的真实 PCM 包络（TtsPreviewPlayer 以 50ms 窗 RMS
 * 采样、按 AudioTrack 播放头映射），最右端即当前音量，左侧为约 3.2s 历史、
 * 透明度渐隐；静音时退化为一条圆点基线。
 */
@Composable
fun VoiceWaveBars(
    amplitudes: FloatArray,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    barCount: Int = 22,
) {
    Spacer(
        modifier = modifier.drawBehind {
            if (amplitudes.isEmpty() || barCount <= 0) return@drawBehind
            val slot = size.width / barCount
            val barWidth = slot * 0.62f
            val minBarHeight = 2.dp.toPx()
            val centerY = size.height / 2f
            val lastIndex = amplitudes.size - 1
            for (i in 0 until barCount) {
                val t = if (barCount == 1) 1f else i / (barCount - 1f)
                val amp = amplitudes[(t * lastIndex).roundToInt()].coerceIn(0f, 1f)
                val barHeight = (amp * size.height).coerceAtLeast(minBarHeight)
                drawRoundRect(
                    color = color.copy(alpha = 0.35f + 0.65f * t),
                    topLeft = Offset(i * slot + (slot - barWidth) / 2f, centerY - barHeight / 2f),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f)
                )
            }
        }
    )
}
