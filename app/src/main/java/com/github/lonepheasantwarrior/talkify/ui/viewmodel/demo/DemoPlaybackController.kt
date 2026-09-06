package com.github.lonepheasantwarrior.talkify.ui.viewmodel.demo

import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.service.TalkifyTtsDemoService
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Demo 试听控制器
 *
 * 管理 [TalkifyTtsDemoService] 的生命周期与试听状态，
 * 与启动检查、模型下载等状态域相互独立。
 */
class DemoPlaybackController {

    private val logTag = "DemoPlaybackController"

    private var demoService: TalkifyTtsDemoService? = null
    private var currentDemoProviderId: String? = null

    private val _isDemoPlaying = MutableStateFlow(false)
    val isDemoPlaying: StateFlow<Boolean> = _isDemoPlaying.asStateFlow()

    private val _demoErrorMessage = MutableStateFlow<String?>(null)
    val demoErrorMessage: StateFlow<String?> = _demoErrorMessage.asStateFlow()

    fun playDemo(providerId: String, text: String, config: BaseProviderConfig) {
        if (demoService == null || currentDemoProviderId != providerId) {
            TtsLogger.d(logTag) { "Initializing Demo Service for provider: $providerId" }
            demoService?.release()
            demoService = TalkifyTtsDemoService(providerId).apply {
                setStateListener { state, errorMessage ->
                    _isDemoPlaying.value = state == TalkifyTtsDemoService.STATE_PLAYING
                    if (state == TalkifyTtsDemoService.STATE_ERROR) {
                        _demoErrorMessage.value = errorMessage
                    }
                }
            }
            currentDemoProviderId = providerId
        }

        // 清除之前的错误信息
        _demoErrorMessage.value = null
        demoService?.speak(text, config)
    }

    fun stopDemo() {
        demoService?.stop()
    }

    fun clearDemoError() {
        _demoErrorMessage.value = null
    }

    fun release() {
        demoService?.release()
        demoService = null
        currentDemoProviderId = null
    }
}
