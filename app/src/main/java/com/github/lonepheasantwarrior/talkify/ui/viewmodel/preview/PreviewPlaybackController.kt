package com.github.lonepheasantwarrior.talkify.ui.viewmodel.preview

import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.service.TtsPreviewPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 语音预览控制器
 *
 * 管理 [TtsPreviewPlayer] 的生命周期与预览播放状态，
 * 与启动检查、模型下载等状态域相互独立。
 */
class PreviewPlaybackController {

    private val logTag = "PreviewPlaybackController"

    private var previewPlayer: TtsPreviewPlayer? = null
    private var currentPreviewProviderId: String? = null

    private val _isPreviewPlaying = MutableStateFlow(false)
    val isPreviewPlaying: StateFlow<Boolean> = _isPreviewPlaying.asStateFlow()

    private val _previewErrorMessage = MutableStateFlow<String?>(null)
    val previewErrorMessage: StateFlow<String?> = _previewErrorMessage.asStateFlow()

    fun playPreview(providerId: String, text: String, config: BaseProviderConfig) {
        if (previewPlayer == null || currentPreviewProviderId != providerId) {
            TtsLogger.d(logTag) { "Initializing preview player for provider: $providerId" }
            previewPlayer?.release()
            previewPlayer = TtsPreviewPlayer(providerId).apply {
                setStateListener { state, errorMessage ->
                    _isPreviewPlaying.value = state == TtsPreviewPlayer.STATE_PLAYING
                    if (state == TtsPreviewPlayer.STATE_ERROR) {
                        _previewErrorMessage.value = errorMessage
                    }
                }
            }
            currentPreviewProviderId = providerId
        }

        // 清除之前的错误信息
        _previewErrorMessage.value = null
        previewPlayer?.speak(text, config)
    }

    fun stopPreview() {
        previewPlayer?.stop()
    }

    fun clearPreviewError() {
        _previewErrorMessage.value = null
    }

    fun release() {
        previewPlayer?.release()
        previewPlayer = null
        currentPreviewProviderId = null
    }
}
