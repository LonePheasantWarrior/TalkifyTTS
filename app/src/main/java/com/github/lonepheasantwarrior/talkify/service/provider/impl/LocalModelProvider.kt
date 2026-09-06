package com.github.lonepheasantwarrior.talkify.service.provider.impl

import android.content.Context
import android.media.AudioFormat
import android.speech.tts.Voice
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelConfig
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelRegistry
import com.github.lonepheasantwarrior.talkify.domain.model.ProviderIds
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.local.LocalModelManager
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.local.SherpaTtsEngine
import com.github.lonepheasantwarrior.talkify.service.TtsErrorCode
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.service.provider.AbstractTtsProvider
import com.github.lonepheasantwarrior.talkify.service.provider.AudioConfig
import com.github.lonepheasantwarrior.talkify.service.provider.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.provider.TtsSynthesisListener
import com.github.lonepheasantwarrior.talkify.service.provider.VOICE_NAME_SEPARATOR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import java.io.File

/**
 * 本地模型 TTS 供应商实现
 *
 * 基于 Sherpa-onnx 实现完全离线的 AI 语音合成。
 * 遵循 [AbstractTtsProvider] 契约，与现有云端供应商完全兼容。
 *
 * 核心特性：
 * 1. 离线推理：无需网络连接，所有计算在本地完成
 * 2. 引擎缓存：按模型 ID 缓存 SherpaTtsEngine 实例，避免重复初始化
 * 3. 动态采样率：根据模型实际采样率配置音频输出
 * 4. 文本分块：长文本自动分块后逐块合成
 *
 * 供应商 ID：localModel
 */
class LocalModelProvider : AbstractTtsProvider() {

    companion object {
        /** 引擎级别的日志标签 */
        private const val TAG = "LocalModelProvider"

        /** 默认语速倍率 */
        private const val DEFAULT_SPEED = 1.0f

        /** 引擎空闲释放超时：Kokoro 模型约 440MB native 内存，空闲期间应归还系统 */
        private const val ENGINE_IDLE_TIMEOUT_MS = 5 * 60 * 1000L
    }

    // ---- 引擎缓存 ----

    private val engineLock = Any()

    /** 引擎代际计数：空闲释放任务执行前校验，防止释放正在使用的新引擎 */
    private var engineGeneration = 0

    @Volatile
    private var engine: SherpaTtsEngine? = null

    @Volatile
    private var currentModelId: String? = null

    // ---- 协程管理 ----

    private val providerJob = SupervisorJob()
    private val providerScope = CoroutineScope(Dispatchers.Default + providerJob)
    private var synthesisJob: Job? = null
    private var engineIdleJob: Job? = null

    @Volatile
    private var isCancelled = false

    // ==================== 供应商身份 ====================

    override fun getProviderId(): String = ProviderIds.LocalModel.providerId
    override fun getProviderName(): String = ProviderIds.LocalModel.provider
    override fun getDefaultModelId(): String = ProviderIds.LocalModel.defaultModelId
    override fun getDefaultApiUrl(): String = ""  // 不使用 API 地址

    // ==================== 音频配置 ====================

    /**
     * 获取当前引擎的音频配置，根据模型动态确定采样率
     */
    override fun getAudioConfig(): AudioConfig {
        val modelId = currentModelId ?: getDefaultModelId()
        val modelInfo = LocalModelRegistry.getModel(modelId)
        val sampleRate = modelInfo?.sampleRate ?: AudioConfig.DEFAULT_SAMPLE_RATE
        return AudioConfig.createStandard(sampleRate = sampleRate)
    }

    // ==================== 配置检查 ====================

    override fun isConfigured(config: BaseProviderConfig?): Boolean {
        val lc = config as? LocalModelConfig ?: return false
        val modelId = lc.modelId.ifBlank { return false }
        return LocalModelManager.isModelDownloaded(modelId)
    }

    override fun createDefaultConfig(): BaseProviderConfig {
        return LocalModelConfig()
    }

    // ==================== 合成逻辑 ====================

    override fun synthesize(
        text: String,
        params: SynthesisParams,
        config: BaseProviderConfig,
        listener: TtsSynthesisListener
    ) {
        checkNotReleased()

        val lc = config as? LocalModelConfig
        if (lc == null) {
            logError("Invalid config type, expected LocalModelConfig")
            listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_PROVIDER_NOT_CONFIGURED))
            return
        }

        // 确定目标模型 ID
        val modelId = lc.modelId.ifBlank { getDefaultModelId() }

        // 检查模型是否已下载
        if (!LocalModelManager.isModelDownloaded(modelId)) {
            logWarning("Model not downloaded: $modelId")
            listener.onError("模型未下载，请先在设置中下载模型")
            return
        }

        if (text.isEmpty()) {
            logWarning("待朗读文本内容为空")
            listener.onSynthesisCompleted()
            return
        }

        if (!containsReadableText(text)) {
            logWarning("文本不包含可朗读的文字内容")
            listener.onSynthesisCompleted()
            return
        }

        val modelInfo = LocalModelRegistry.getModel(modelId)
        if (modelInfo == null) {
            logError("Unknown model: $modelId")
            listener.onError("未知模型: $modelId")
            return
        }

        logInfo("Starting local synthesis: model=$modelId, textLength=${text.length}")

        isCancelled = false
        cancelEngineIdleRelease()
        engineGeneration++

        synthesisJob = providerScope.launch {
            var synthesisSucceeded = true
            try {
                val currentEngine = ensureEngine(modelId, modelInfo)

                val speed = if (params.speechRate > 0) {
                    params.speechRate / 100f
                } else {
                    DEFAULT_SPEED
                }

                listener.onSynthesisStarted()

                // 真正流式合成：Sherpa-onnx 每生成一小段 PCM（通常为一个句子）
                // 就通过 generateWithConfigAndCallback 回调第一时间送达给 Android TTS callback
                currentEngine.synthesizeStream(text, lc.voiceId, speed) { pcmData, sampleRate ->
                    if (!isCancelled) {
                        listener.onAudioAvailable(
                            pcmData,
                            sampleRate,
                            AudioFormat.ENCODING_PCM_16BIT,
                            1  // 单声道
                        )
                    }
                    // 返回 true 继续合成，false 中断（对应停止播放）
                    !isCancelled
                }

                if (!isCancelled) {
                    listener.onSynthesisCompleted()
                    logInfo("Streaming synthesis completed successfully")
                }
            } catch (e: Exception) {
                synthesisSucceeded = false
                if (!isCancelled) {
                    logError("Synthesis error", e)
                    listener.onError("本地合成失败: ${e.message}")
                }
            } finally {
                if (synthesisSucceeded) {
                    scheduleEngineIdleRelease()
                }
            }
        }
    }

    // ==================== 引擎管理 ====================

    /**
     * 确保引擎实例可用
     *
     * 以模型 ID 为 key 缓存引擎。
     * 切换模型时自动释放旧引擎并创建新引擎。
     */
    private fun ensureEngine(
        modelId: String,
        modelInfo: com.github.lonepheasantwarrior.talkify.domain.model.LocalModelInfo
    ): SherpaTtsEngine = synchronized(engineLock) {
        if (engine != null && currentModelId == modelId) {
            logDebug("Reusing cached engine for: $modelId")
            return engine!!
        }

        // 切换模型：释放旧引擎
        if (engine != null) {
            logInfo("Switching model from $currentModelId to $modelId, releasing old engine")
            engine?.release()
            engine = null
        }

        // 获取模型下载目录
        val modelDir = LocalModelManager.getModelDownloadedDir(modelId)
            ?: throw IllegalStateException("无法获取模型目录: $modelId")

        // 创建新引擎
        val newEngine = SherpaTtsEngine(modelInfo, modelDir)
        newEngine.initialize()
        engine = newEngine
        currentModelId = modelId

        logInfo("Engine initialized for: $modelId")
        newEngine
    }

    // ---- 引擎空闲释放 ----

    private fun scheduleEngineIdleRelease() {
        engineIdleJob?.cancel()
        val generation = engineGeneration
        engineIdleJob = providerScope.launch {
            kotlinx.coroutines.delay(ENGINE_IDLE_TIMEOUT_MS)
            synchronized(engineLock) {
                // 代际校验：若期间有新合成开始（generation 变化）或已换引擎，跳过释放
                if (engineGeneration != generation) return@synchronized
                val idleEngine = engine ?: return@synchronized
                logInfo("Engine idle for ${ENGINE_IDLE_TIMEOUT_MS}ms, releasing native resources")
                engine = null
                currentModelId = null
                idleEngine.release()
            }
        }
    }

    private fun cancelEngineIdleRelease() {
        engineIdleJob?.cancel()
        engineIdleJob = null
    }

    // ==================== 生命周期管理 ====================

    override fun stop() {
        logInfo("Stopping synthesis")
        isCancelled = true
        synthesisJob?.cancel()
        synthesisJob = null
    }

    override fun release() {
        logInfo("Releasing provider")
        isCancelled = true
        synthesisJob?.cancel()
        synthesisJob = null
        cancelEngineIdleRelease()
        providerJob.cancel()
        synchronized(engineLock) {
            engine?.release()
            engine = null
        }
        currentModelId = null
        super.release()
    }

    // ==================== 供应商元数据 ====================

    override fun getSupportedLanguages(): Set<String> {
        return setOf("zho", "eng", "yue")
    }

    override fun getDefaultLanguage(): Array<String> {
        return arrayOf(Locale.SIMPLIFIED_CHINESE.language, Locale.SIMPLIFIED_CHINESE.country, "")
    }

    override fun getSupportedVoices(): List<Voice> {
        val voices = mutableListOf<Voice>()
        val modelId = currentModelId ?: getDefaultModelId()
        val modelInfo = LocalModelRegistry.getModel(modelId) ?: return voices

        for (voice in modelInfo.voiceList) {
            voices.add(
                Voice(
                    "${voice.voiceId}$VOICE_NAME_SEPARATOR${voice.displayName}",
                    Locale.forLanguageTag(voice.language),
                    Voice.QUALITY_NORMAL,
                    Voice.LATENCY_NORMAL,
                    true,
                    emptySet()
                )
            )
        }
        return voices
    }

    override fun getDefaultVoiceId(
        lang: String?,
        country: String?,
        variant: String?,
        currentVoiceId: String?
    ): String {
        if (!currentVoiceId.isNullOrBlank()) return currentVoiceId
        val modelId = currentModelId ?: getDefaultModelId()
        val modelInfo = LocalModelRegistry.getModel(modelId)
        return modelInfo?.voiceList?.firstOrNull()?.voiceId ?: "default"
    }

    override fun isVoiceIdCorrect(voiceId: String?): Boolean {
        if (voiceId.isNullOrBlank()) return false
        val modelId = currentModelId ?: getDefaultModelId()
        val modelInfo = LocalModelRegistry.getModel(modelId) ?: return false
        val realName = extractRealVoiceName(voiceId) ?: voiceId
        return modelInfo.voiceList.any { it.voiceId == realName }
    }

    override fun getConfigLabel(configKey: String, context: Context): String? {
        return when (configKey) {
            "model_id" -> context.getString(R.string.model_select_label)
            "voice_id" -> context.getString(R.string.voice_select_label)
            else -> super.getConfigLabel(configKey, context)
        }
    }
}
