package com.github.lonepheasantwarrior.talkify.service.multirole

import com.github.lonepheasantwarrior.talkify.TalkifyAppHolder
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.ReadingProfile
import com.github.lonepheasantwarrior.talkify.domain.multirole.MultiRoleParser
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.service.delivery.SynthesisDeliveryBridge
import com.github.lonepheasantwarrior.talkify.service.provider.AudioConfig
import com.github.lonepheasantwarrior.talkify.service.provider.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.provider.TtsProviderApi
import com.github.lonepheasantwarrior.talkify.service.provider.TtsProviderFactory
import com.github.lonepheasantwarrior.talkify.util.TalkifyAudioPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 多角色方案试听播放器。
 *
 * 与系统 TTS 共用 [MultiRoleParser] 与 [SpeechPlanExecutor]（同一解析、逐段音色
 * 覆盖与格式守卫），但输出适配为本应用内 [TalkifyAudioPlayer]，不会通过
 * 自调用系统 TTS 充当唯一测试路径。
 */
class MultiRolePreviewPlayer {

    companion object {
        const val STATE_IDLE = 0
        const val STATE_PLAYING = 1
        const val STATE_ERROR = 3

        private const val PLAYBACK_TIMEOUT_MS = 120_000L
        private const val PREVIEW_SYNTHESIS_TIMEOUT_MS = 120_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var currentState = STATE_IDLE

    @Volatile
    private var currentGeneration = 0L

    @Volatile
    private var activeBridge: SynthesisDeliveryBridge? = null

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var activeProvider: TtsProviderApi? = null

    @Volatile
    private var activePlayer: TalkifyAudioPlayer? = null

    @Volatile
    private var stopped = AtomicBoolean(true)

    private var stateListener: ((Int, String?) -> Unit)? = null

    fun setStateListener(listener: ((Int, String?) -> Unit)?) {
        stateListener = listener
    }

    fun speak(
        profile: ReadingProfile,
        config: BaseProviderConfig,
        text: String,
        params: SynthesisParams = SynthesisParams(language = "Auto"),
    ) {
        stop()
        stopped.set(false)
        val generation = ++currentGeneration
        transition(STATE_PLAYING)

        val job = scope.launch {
            var provider: TtsProviderApi? = null
            var bridge: SynthesisDeliveryBridge? = null
            var player: TalkifyAudioPlayer? = null
            try {
                val plan = MultiRoleParser.parse(text, profile).plan
                if (!plan.hasReadableContent || plan.segments.isEmpty()) {
                    fail(STATE_ERROR, getString(R.string.multirole_preview_no_text))
                    return@launch
                }
                provider = TtsProviderFactory.createProvider(profile.providerId)
                if (provider == null) {
                    fail(STATE_ERROR, getString(R.string.multirole_preview_provider_unavailable))
                    return@launch
                }
                activeProvider = provider
                val check = MultiRoleRuntimeValidator.validateWithProvider(
                    profile, provider, config
                )
                if (!check.ok) {
                    fail(STATE_ERROR, getString(R.string.multirole_preview_profile_invalid))
                    return@launch
                }
                val expected = check.audioConfig

                val executionBridge = SynthesisDeliveryBridge()
                bridge = executionBridge
                activeBridge = executionBridge
                val executor = SpeechPlanExecutor(scope)
                val producer = executor.start(
                    SpeechPlanExecutor.Config(
                        provider = provider,
                        providerConfig = config,
                        params = params,
                        plan = plan,
                        bridge = executionBridge,
                        expectedAudioConfig = expected,
                        timeoutMs = PREVIEW_SYNTHESIS_TIMEOUT_MS,
                    ),
                    isCancelled = { stopped.get() || generation != currentGeneration },
                )
                activeJob = producer

                var deliveredBytes = 0L
                val deadline = System.nanoTime() + PREVIEW_SYNTHESIS_TIMEOUT_MS * 1_000_000
                while (true) {
                    if (stopped.get() || generation != currentGeneration) return@launch
                    val remainingMs = (deadline - System.nanoTime()) / 1_000_000
                    if (remainingMs <= 0) {
                        fail(STATE_ERROR, getString(R.string.multirole_preview_timeout))
                        return@launch
                    }
                    when (val event = executionBridge.take(remainingMs)) {
                        null -> {
                            fail(STATE_ERROR, getString(R.string.multirole_preview_timeout))
                            return@launch
                        }
                        is SynthesisDeliveryBridge.Event.Cancelled -> return@launch
                        is SynthesisDeliveryBridge.Event.Failed -> {
                            fail(STATE_ERROR, previewErrorMessage(event.message))
                            return@launch
                        }
                        is SynthesisDeliveryBridge.Event.Completed -> {
                            if (deliveredBytes <= 0L) {
                                fail(STATE_ERROR, getString(R.string.multirole_preview_no_audio))
                                return@launch
                            }
                            val drained = player?.waitForPlaybackComplete(
                                timeoutSeconds = 120,
                                shouldStop = { stopped.get() },
                            ) ?: true
                            if (generation != currentGeneration) return@launch
                            if (!drained) {
                                fail(STATE_ERROR, getString(R.string.multirole_preview_playback_failed))
                            } else {
                                transition(STATE_IDLE)
                            }
                            return@launch
                        }
                        is SynthesisDeliveryBridge.Event.Audio -> {
                            if (expected != null &&
                                (event.sampleRate != expected.sampleRate ||
                                    event.audioFormat != expected.audioFormat ||
                                    event.channelCount != expected.channelCount)
                            ) {
                                fail(STATE_ERROR, getString(R.string.multirole_error_audio_format))
                                return@launch
                            }
                            var target = player
                            if (target == null) {
                                target = TalkifyAudioPlayer(
                                    sampleRate = event.sampleRate,
                                    channelCount = event.channelCount,
                                    audioFormat = event.audioFormat,
                                )
                                if (!target.createPlayer()) {
                                    fail(STATE_ERROR, getString(R.string.multirole_preview_playback_failed))
                                    return@launch
                                }
                                activePlayer = target
                                player = target
                            }
                            if (!target.play(event.data)) {
                                fail(STATE_ERROR, getString(R.string.multirole_preview_playback_failed))
                                return@launch
                            }
                            deliveredBytes += event.data.size
                        }
                    }
                }
            } catch (_: CancellationException) {
                // stop()/release() 的正常路径
            } catch (e: Exception) {
                TtsLogger.e("MultiRole preview failed: ${e.javaClass.simpleName}", e)
                if (generation == currentGeneration) {
                    fail(STATE_ERROR, getString(R.string.multirole_preview_failed))
                }
            } finally {
                try {
                    player?.stop()
                    player?.release()
                } catch (_: Exception) {
                }
                try {
                    provider?.stop()
                    provider?.release()
                } catch (_: Exception) {
                }
                bridge?.cancel()
                if (activePlayer === player) activePlayer = null
                if (activeProvider === provider) activeProvider = null
                if (activeBridge === bridge) activeBridge = null
            }
        }
        activeJob = job
    }

    fun stop() {
        stopped.set(true)
        currentGeneration++
        activeBridge?.cancel()
        activeJob?.cancel()
        try {
            activePlayer?.stop()
            activePlayer?.release()
        } catch (_: Exception) {
        }
        activePlayer = null
        try {
            activeProvider?.stop()
            activeProvider?.release()
        } catch (_: Exception) {
        }
        activeProvider = null
        activeBridge = null
        activeJob = null
        if (currentState != STATE_IDLE) transition(STATE_IDLE)
    }

    fun release() {
        stop()
        scope.cancel()
    }

    fun getState(): Int = currentState

    private fun transition(state: Int) {
        currentState = state
        stateListener?.invoke(state, null)
    }

    private fun fail(state: Int, message: String) {
        currentState = state
        stateListener?.invoke(state, message)
    }

    private fun previewErrorMessage(token: String?): String = when (token) {
        SpeechPlanExecutor.TOKEN_TIMEOUT -> getString(R.string.multirole_preview_timeout)
        SpeechPlanExecutor.TOKEN_ZERO_AUDIO -> getString(R.string.multirole_preview_no_audio)
        SpeechPlanExecutor.TOKEN_FORMAT_MISMATCH -> getString(R.string.multirole_error_audio_format)
        else -> getString(R.string.multirole_preview_failed)
    }

    private fun getString(resId: Int): String =
        TalkifyAppHolder.getContext()?.getString(resId) ?: ""
}
