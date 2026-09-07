package com.github.lonepheasantwarrior.talkify.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sin

/**
 * 均衡器条动效（品牌视觉元素）
 *
 * 一组随时间往复起伏的竖条，用于表达"语音"意象：
 * - [animated] = true：加载态、播放中的动态均衡器
 * - [animated] = false：静态波形，用作品牌标识点缀
 *
 * 各条共用同一时间轴、按条序错开相位，保证循环处无跳变。
 */
@Composable
fun EqualizerBars(
    barCount: Int,
    color: Color,
    modifier: Modifier = Modifier,
    barWidth: Dp = 4.dp,
    barGap: Dp = 3.dp,
    minHeight: Dp = 8.dp,
    maxHeight: Dp = 28.dp,
    animated: Boolean = true
) {
    val transition = rememberInfiniteTransition(label = "equalizer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "equalizer_progress"
    )

    val phaseStep = (Math.PI / barCount).toFloat()
    val range = maxHeight - minHeight

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(barGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(barCount) { index ->
            val fraction = if (animated) {
                abs(sin(progress * 2f * Math.PI.toFloat() + index * phaseStep))
            } else {
                abs(sin(index * phaseStep + 0.4f))
            }
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .height(minHeight + range * fraction)
                    .background(color, RoundedCornerShape(percent = 50))
            )
        }
    }
}
