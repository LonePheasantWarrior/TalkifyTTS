package com.github.lonepheasantwarrior.talkify.service.provider.impl

import android.content.Context
import android.media.AudioFormat
import android.speech.tts.Voice
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.TalkifyAppHolder
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelConfig
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelInfo
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelRegistry
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelVoice
import com.github.lonepheasantwarrior.talkify.domain.model.ProviderIds
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.local.LocalModelManager
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.local.LocalVoiceCatalog
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.local.SherpaTtsEngine
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.local.WavSampleReader
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale

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

        /** 引擎空闲释放超时：ZipVoice 模型约 200MB native 内存，空闲期间应归还系统 */
        private const val ENGINE_IDLE_TIMEOUT_MS = 5 * 60 * 1000L

        // ---- 进程级共享引擎状态 ----
        // Provider 实例随使用方各自创建（TTS 服务 / 设置页音色预览），但 ZipVoice
        // 引擎约 200MB native 内存，必须进程内共享：否则两个使用方同时合成会各自
        // 加载一份模型，内存翻倍且推理争抢 CPU

        private val engineLock = Any()

        /** 引擎代际计数：空闲释放任务执行前校验，防止释放正在使用的新引擎 */
        private var engineGeneration = 0

        @Volatile
        private var engine: SherpaTtsEngine? = null

        @Volatile
        private var currentModelId: String? = null

        /** 空闲释放定时器挂在独立 scope：不随任何 Provider 实例 release 而失效 */
        private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        private var engineIdleJob: Job? = null

        /** 共享引擎不支持并发 native 推理（服务与预览同时合成会争抢 CPU），串行化 */
        private val synthesisMutex = Mutex()

        /** 参考音频缓存：模型目录内容经下载校验后不可变，避免每次合成重读磁盘 */
        private val referenceCache = mutableMapOf<String, WavSampleReader.WavSamples>()

        /**
         * 获取共享引擎实例
         *
         * 以模型 ID 为 key 缓存引擎，切换模型时自动释放旧引擎并创建新引擎。
         */
        private fun ensureEngine(
            modelId: String,
            modelInfo: LocalModelInfo
        ): SherpaTtsEngine = synchronized(engineLock) {
            if (engine != null && currentModelId == modelId) {
                TtsLogger.d("Reusing cached engine for: $modelId", tag = TAG)
                return engine!!
            }

            // 切换模型：释放旧引擎
            if (engine != null) {
                TtsLogger.i("Switching model from $currentModelId to $modelId, releasing old engine", tag = TAG)
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

            TtsLogger.i("Engine initialized for: $modelId", tag = TAG)
            newEngine
        }

        // ---- 引擎空闲释放 ----

        private fun scheduleEngineIdleRelease() {
            engineIdleJob?.cancel()
            val generation = engineGeneration
            engineIdleJob = engineScope.launch {
                kotlinx.coroutines.delay(ENGINE_IDLE_TIMEOUT_MS)
                synchronized(engineLock) {
                    // 代际校验：若期间有新合成开始（generation 变化）或已换引擎，跳过释放
                    if (engineGeneration != generation) return@synchronized
                    val idleEngine = engine ?: return@synchronized
                    TtsLogger.i("Engine idle for ${ENGINE_IDLE_TIMEOUT_MS}ms, releasing native resources", tag = TAG)
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
    }

    // ---- 协程管理 ----

    private val providerJob = SupervisorJob()
    private val providerScope = CoroutineScope(Dispatchers.Default + providerJob)
    private var synthesisJob: Job? = null

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
        val modelInfo = resolveModelInfo(currentModelId ?: "")
        return AudioConfig.createStandard(sampleRate = modelInfo.sampleRate)
    }

    // ==================== 配置检查 ====================

    override fun isConfigured(config: BaseProviderConfig?): Boolean {
        val lc = config as? LocalModelConfig ?: return false
        val modelId = lc.modelId.ifBlank { return false }
        return LocalModelManager.isModelDownloaded(resolveModelInfo(modelId).id)
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

        // 确定目标模型：历史版本持久化的模型 ID 可能已从注册表移除，此时回退默认模型
        val modelInfo = resolveModelInfo(lc.modelId)
        val modelId = modelInfo.id

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

        logInfo("Starting local synthesis: model=$modelId, textLength=${text.length}")

        isCancelled = false
        cancelEngineIdleRelease()
        engineGeneration++

        synthesisJob = providerScope.launch {
            try {
                val currentEngine = ensureEngine(modelId, modelInfo)

                val speed = if (params.speechRate > 0) {
                    params.speechRate / 100f
                } else {
                    DEFAULT_SPEED
                }

                listener.onSynthesisStarted()

                // ZipVoice 音色 = 参考音频 + 逐字稿；音色以内置目录为准，
                // 历史持久化的 voiceId（如已隐藏的雷军音色）不在目录内时
                // 透明迁移到目录默认音色，用户侧体验不变
                val requestedVoiceName = extractRealVoiceName(lc.voiceId) ?: lc.voiceId
                val bundledVoices = LocalVoiceCatalog.getVoices()
                val voice = bundledVoices.firstOrNull { it.voiceId == requestedVoiceName }
                    ?: bundledVoices.firstOrNull()
                    ?: modelInfo.voiceList.firstOrNull { it.voiceId == requestedVoiceName }
                    ?: modelInfo.voiceList.firstOrNull()
                    ?: throw IllegalStateException("模型 ${modelInfo.id} 未配置音色")
                if (requestedVoiceName != voice.voiceId) {
                    logWarning("Voice '$requestedVoiceName' unavailable, falling back to '${voice.voiceId}'")
                }
                val modelDir = LocalModelManager.getModelDownloadedDir(modelId)
                    ?: throw IllegalStateException("无法获取模型目录: $modelId")
                val reference = synchronized(referenceCache) {
                    referenceCache.getOrPut("${modelInfo.id}:${voice.voiceId}") {
                        if (voice.isBundled) {
                            val context = TalkifyAppHolder.getContext()
                                ?: throw IllegalStateException("Context unavailable for bundled voice: ${voice.voiceId}")
                            context.assets.open("${LocalVoiceCatalog.ASSETS_DIR}/${voice.referenceFileName}")
                                .use { WavSampleReader.read(it) }
                        } else {
                            WavSampleReader.read(File(modelDir, voice.referenceFileName))
                        }
                    }
                }
                logInfo("Using voice=${voice.voiceId}, reference=${voice.referenceFileName}")

                // 真正流式合成：Sherpa-onnx 每生成一小段 PCM（通常为一个句子）
                // 就通过 generateWithConfigAndCallback 回调第一时间送达给 Android TTS callback。
                // 共享引擎串行化：后到请求排队等待，避免并发推理争抢 CPU
                synthesisMutex.withLock {
                    currentEngine.synthesizeStream(
                        text = text,
                        referenceAudio = reference.samples,
                        referenceSampleRate = reference.sampleRate,
                        referenceText = voice.referenceText,
                        speed = speed
                    ) { pcmData, sampleRate ->
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
                }

                if (!isCancelled) {
                    listener.onSynthesisCompleted()
                    logInfo("Streaming synthesis completed successfully")
                }
            } catch (e: Exception) {
                if (!isCancelled) {
                    logError("Synthesis error", e)
                    listener.onError("本地合成失败: ${e.message}")
                }
            } finally {
                // 成功与失败都要安排：失败路径若不安排，引擎 200MB 内存会永不释放
                scheduleEngineIdleRelease()
            }
        }
    }

    // ==================== 引擎管理 ====================

    /**
     * 解析目标模型元信息
     *
     * 注册表查不到（历史版本持久化的已移除模型 ID 或空值）时回退默认模型，
     * 避免升级后本地模型功能整体不可用
     */
    private fun resolveModelInfo(rawModelId: String?): LocalModelInfo {
        val resolved = rawModelId?.takeIf { it.isNotBlank() }
            ?.let { LocalModelRegistry.getModel(it) }
        return resolved ?: LocalModelRegistry.getDefaultModel()
    }

    /**
     * 异步预热共享引擎
     *
     * 引擎首次初始化需加载约 200MB 模型（秒级）。由 TTS 服务在创建时
     * （客户端刚绑定、通常尚未发起朗读）调用，把模型加载提前到朗读请求
     * 之前，消除首次合成的首音延迟。预热后引擎按空闲超时正常释放。
     */
    fun warmUp(modelId: String) {
        engineScope.launch {
            try {
                val modelInfo = resolveModelInfo(modelId)
                if (!LocalModelManager.isModelDownloaded(modelInfo.id)) return@launch
                cancelEngineIdleRelease()
                engineGeneration++
                ensureEngine(modelInfo.id, modelInfo)
                scheduleEngineIdleRelease()
            } catch (e: Exception) {
                // 预热失败静默忽略：首次合成时仍会走正常初始化路径
                TtsLogger.w("Engine warm-up failed: ${e.message}", tag = TAG)
            }
        }
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
        // 共享引擎不随实例释放：其他使用方（TTS 服务 / 音色预览）可能仍在使用，
        // 由空闲释放定时器统一回收
        providerJob.cancel()
        super.release()
    }

    // ==================== 供应商元数据 ====================

    override fun getSupportedLanguages(): Set<String> {
        return setOf("zho", "eng")
    }

    override fun getDefaultLanguage(): Array<String> {
        return arrayOf(Locale.SIMPLIFIED_CHINESE.language, Locale.SIMPLIFIED_CHINESE.country, "")
    }

    override fun getSupportedVoices(): List<Voice> {
        val voices = mutableListOf<Voice>()
        for (voice in getDisplayVoices()) {
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
        return getDisplayVoices().firstOrNull()?.voiceId ?: "default"
    }

    override fun isVoiceIdCorrect(voiceId: String?): Boolean {
        if (voiceId.isNullOrBlank()) return false
        val realName = extractRealVoiceName(voiceId) ?: voiceId
        return getDisplayVoices().any { it.voiceId == realName }
    }

    /**
     * 展示/可选音色：内置音色目录优先（就绪后不再展示注册表兜底音色）；
     * 目录异常为空时回退注册表音色兜底
     */
    private fun getDisplayVoices(): List<LocalModelVoice> {
        val bundled = LocalVoiceCatalog.getVoices()
        return if (bundled.isNotEmpty()) bundled else resolveModelInfo(currentModelId).voiceList
    }

    override fun getConfigLabel(configKey: String, context: Context): String? {
        return when (configKey) {
            "model_id" -> context.getString(R.string.model_select_label)
            "voice_id" -> context.getString(R.string.voice_select_label)
            else -> super.getConfigLabel(configKey, context)
        }
    }
}
