package com.github.lonepheasantwarrior.talkify.ui.theme

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect

/**
 * Talkify Expressive 动效规格。
 *
 * material3 1.4.0 的 MotionScheme 为 internal API，此处按官方 ExpressiveMotionTokens
 * 参数自建同款弹簧：位置/尺寸/旋转类用 spatial，颜色/透明度/数值类用 effects。
 */
object TalkifyMotion {
    val spatialDefault: SpringSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 380f)
    val spatialFast: SpringSpec<Float> = spring(dampingRatio = 0.6f, stiffness = 800f)
    val spatialSlow: SpringSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 200f)
    val effectsDefault: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 1600f)
    val effectsFast: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 3800f)
    val effectsSlow: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 800f)

    fun <T> spatialDefaultOf(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.8f, stiffness = 380f, visibilityThreshold = visibilityThreshold)

    fun <T> spatialFastOf(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.6f, stiffness = 800f, visibilityThreshold = visibilityThreshold)

    fun <T> effectsDefaultOf(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(dampingRatio = 1f, stiffness = 1600f, visibilityThreshold = visibilityThreshold)

    // sharedBounds 边界变形专用：须在 NavHost 转场（250ms）内收敛完毕，否则交接时残留
    // 弹簧行程会在动画结束瞬间跳到真实布局位置（默认 spring(400) 收敛约 330ms，会错位）
    val sharedBoundsMorph: BoundsTransform =
        BoundsTransform { _, _ ->
            spring(
                dampingRatio = 1f,
                stiffness = 1600f,
                visibilityThreshold = Rect.VisibilityThreshold
            )
        }
}

const val SharedKeyBrandMark = "talkify.brand.mark"
const val SharedKeyBrandTitle = "talkify.brand.title"

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedBrandBounds(
    key: String,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?
): Modifier =
    if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            sharedBounds(
                sharedContentState = rememberSharedContentState(key),
                animatedVisibilityScope = animatedVisibilityScope,
                boundsTransform = TalkifyMotion.sharedBoundsMorph
            )
        }
    } else {
        this
    }
