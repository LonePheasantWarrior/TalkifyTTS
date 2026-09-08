package com.github.lonepheasantwarrior.talkify.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import com.github.lonepheasantwarrior.talkify.TalkifyAppHolder
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.recorder.UmamiRecorder
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 遥测事件捕获宿主
 *
 * 包裹应用根内容，为 recorder 子系统捕获三类 UI 事件：
 * - **点击**：根级指针拦截（[PointerEventPass.Initial]，不消费事件，不影响子组件手势），
 *   down→up 位移不超过 touchSlop 判定为点击，取 up 坐标（窗口内 px）
 * - **触摸移动**：按住期间持续采样，由 [UmamiRecorder] 内部节流
 * - **视口尺寸**：根容器尺寸变化（旋转/分屏），去重后通知 recorder 重拍快照
 *
 * 页面滚动深度由 [rememberTelemetryScrollObserver] 在各滚动容器上精确观测，
 * 不经根级嵌套滚动推断。捕获失败对业务零影响，所有回调在 recorder 内部异常吞没。
 */
@Composable
fun TelemetryCaptureHost(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val touchSlopPx: Float = LocalViewConfiguration.current.touchSlop
    val hostView = LocalView.current
    DisposableEffect(hostView) {
        TalkifyAppHolder.setSemanticsHostView(hostView)
        onDispose {
            if (TalkifyAppHolder.semanticsHostView() === hostView) {
                TalkifyAppHolder.setSemanticsHostView(null)
            }
        }
    }
    Box(
        modifier = modifier
            .onSizeChanged { UmamiRecorder.onViewportResized(it.width, it.height) }
            .pointerInput(touchSlopPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var upPosition: Offset? = null
                    var maxDistance = 0f
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) {
                            upPosition = change.position
                            break
                        }
                        maxDistance = max(maxDistance, (change.position - down.position).getDistance())
                        UmamiRecorder.onPointerMove(
                            change.position.x.roundToInt(),
                            change.position.y.roundToInt()
                        )
                    }
                    val up = upPosition ?: return@awaitEachGesture
                    if (maxDistance <= touchSlopPx) {
                        UmamiRecorder.onClick(up.x.roundToInt(), up.y.roundToInt())
                    }
                }
            }
    ) {
        content()
    }
}

/**
 * 观测页面滚动容器的位置，为热图上报精确的滚动深度
 *
 * 应与 [androidx.compose.foundation.verticalScroll] 使用同一个 [ScrollState]：
 * `value` 即页面 scrollTop，`maxValue` 即最大可滚动距离（`pageH = maxValue + 视口高`），
 * 与网页端 recorder 的 scrollPct 语义一致
 */
@Composable
fun rememberTelemetryScrollObserver(state: ScrollState) {
    LaunchedEffect(state) {
        snapshotFlow { state.value to state.maxValue }
            .distinctUntilChanged()
            .collect { (scrollTop, maxScroll) ->
                UmamiRecorder.onScrollPosition(scrollTop, maxScroll)
            }
    }
}
