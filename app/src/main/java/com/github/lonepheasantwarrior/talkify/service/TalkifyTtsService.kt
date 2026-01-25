package com.github.lonepheasantwarrior.talkify.service

import android.content.Context
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.EngineConfig
import com.github.lonepheasantwarrior.talkify.domain.model.TtsEngineRegistry
import com.github.lonepheasantwarrior.talkify.domain.repository.AppConfigRepository
import com.github.lonepheasantwarrior.talkify.domain.repository.EngineConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.app.notification.TalkifyNotificationHelper
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.SharedPreferencesAppConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.engine.repo.Qwen3TtsConfigRepository
import com.github.lonepheasantwarrior.talkify.service.engine.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.engine.TtsEngineApi
import com.github.lonepheasantwarrior.talkify.service.engine.TtsEngineFactory
import com.github.lonepheasantwarrior.talkify.service.engine.TtsSynthesisListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前台阅读服务通知 ID
 */
private const val FOREGROUND_SERVICE_N_ID = 1001

/**
 * Talkify TTS 服务
 *
 * 实现 [TextToSpeechService]，作为系统 TTS 框架与本应用引擎之间的桥梁
 * 负责：
 * 1. 根据用户选择的引擎 ID 获取对应的合成引擎
 * 2. 获取用户配置的引擎设置
 * 3. 委托引擎执行实际的语音合成
 *
 * 采用仓储模式获取配置，支持多引擎切换
 *
 * @property currentEngine 当前引擎实例
 * @property currentEngineId 当前引擎 ID
 * @property currentConfig 当前引擎配置
 */
class TalkifyTtsService : TextToSpeechService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val requestQueue = LinkedBlockingQueue<SynthesisRequestWrapper>()
    private val processingSemaphore = Semaphore(1)
    private var isStopped = AtomicBoolean(false)
    private var isSynthesisInProgress = AtomicBoolean(false)
    private var synthesisLatch: java.util.concurrent.CountDownLatch? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isForegroundServiceRunning = false
    private var activeCallback: android.speech.tts.SynthesisCallback? = null

    private var appConfigRepository: AppConfigRepository? = null
    private var engineConfigRepository: EngineConfigRepository? = null
    private var currentEngine: TtsEngineApi? = null
    private var currentEngineId: String? = null
    private var currentConfig: EngineConfig? = null
    private var currentPlayer: CompatibilityModePlayer? = null

    private data class SynthesisRequestWrapper(
        val request: android.speech.tts.SynthesisRequest,
        val callback: android.speech.tts.SynthesisCallback
    )

    override fun onCreate() {
        super.onCreate()
        TtsLogger.i("TalkifyTtsService onCreate")
        initializeWakeLock()
        initializeRepositories()
        val engineInitSuccess = initializeEngine()
        TtsLogger.d("Engine initialization result: $engineInitSuccess")
        startRequestProcessor()
    }

    /**
     * 初始化 WakeLock
     *
     * 用于防止在语音合成过程中设备进入休眠状态
     * 设置为部分唤醒锁，最长持有 10 分钟
     */
    private fun initializeWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Talkify:TtsServiceWakeLock"
        ).apply {
            setReferenceCounted(false)
        }
        TtsLogger.d("WakeLock initialized")
    }

    /**
     * 启动前台服务
     *
     * 如果服务尚未运行，则启动为前台服务并显示通知
     * 使用 [TalkifyNotificationHelper.buildForegroundWithNotification] 构建通知
     */
    private fun startForegroundService() {
        if (!isForegroundServiceRunning) {
            startForeground(FOREGROUND_SERVICE_N_ID, TalkifyNotificationHelper.buildForegroundWithNotification(this))
            isForegroundServiceRunning = true
            TtsLogger.d("Foreground service started")
        }
    }

    /**
     * 停止前台服务
     *
     * 移除前台服务状态和关联的通知
     */
    private fun stopForegroundService() {
        if (isForegroundServiceRunning) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundServiceRunning = false
            TtsLogger.d("Foreground service stopped")
        }
    }

    /**
     * 获取 WakeLock
     *
     * 如果 WakeLock 未持有，则尝试获取
     * 最长持有 10 分钟
     */
    private fun acquireWakeLock() {
        wakeLock?.let {
            if (!it.isHeld) {
                it.acquire(10 * 60 * 1000L)
                TtsLogger.d("WakeLock acquired")
            }
        }
    }

    /**
     * 释放 WakeLock
     *
     * 如果 WakeLock 已持有，则释放它
     */
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                TtsLogger.d("WakeLock released")
            }
        }
    }

    /**
     * 启动请求处理器
     *
     * 在协程中持续从请求队列中取出合成请求并处理
     * 循环运行直到服务停止
     */
    private fun startRequestProcessor() {
        serviceScope.launch {
            while (!isStopped.get()) {
                try {
                    val wrapper = requestQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (wrapper != null) {
                        processingSemaphore.acquire()
                        processRequestInternal(wrapper)
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    TtsLogger.e("Critical error in request processor", e)
                    try {
                        Thread.sleep(1000)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }
    }

    /**
     * 内部请求处理方法
     *
     * 处理单个合成请求，包括参数验证、引擎调用和回调通知
     * 完成后释放信号量
     *
     * @param wrapper 包含请求和回调的包装对象
     */
    private fun processRequestInternal(wrapper: SynthesisRequestWrapper) {
        val (request, callback) = wrapper

        if (isStopped.get()) {
            TtsLogger.d("processRequestInternal: service is stopped, skipping request")
            callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
            processingSemaphore.release()
            return
        }

        val text = request.charSequenceText?.toString()

        if (text.isNullOrBlank()) {
            TtsLogger.w("processRequestInternal: empty text")
            callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
            processingSemaphore.release()
            return
        }

        TtsLogger.d("processRequestInternal: text length = ${text.length}")

        acquireWakeLock()
        activeCallback = callback

        val engineId = currentEngineId
        if (engineId == null) {
            TtsLogger.e("processRequestInternal: no engine ID available")
            TalkifyNotificationHelper.sendSystemNotification(this, getString(R.string.tts_error_no_engine_id_available))
            callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_ENGINE_NOT_FOUND))
            processingSemaphore.release()
            return
        }

        val engine = currentEngine
        if (engine == null) {
            TtsLogger.e("processRequestInternal: no engine available")
            TalkifyNotificationHelper.sendSystemNotification(this, getString(R.string.tts_error_no_engine))
            callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_NO_ENGINE))
            processingSemaphore.release()
            return
        }

        val config = engineConfigRepository?.getConfig(engineId)
        if (config == null) {
            TtsLogger.e("processRequestInternal: no config available")
            TalkifyNotificationHelper.sendSystemNotification(this, getString(R.string.tts_error_no_config_available))
            callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_CONFIG_NOT_FOUND))
            processingSemaphore.release()
            return
        }

        if (!engine.isConfigured(config)) {
            TtsLogger.w("processRequestInternal: engine not configured")
            TalkifyNotificationHelper.sendSystemNotification(this, getString(R.string.tts_error_engine_not_configured))
            callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_ENGINE_NOT_CONFIGURED))
            processingSemaphore.release()
            return
        }

        val params = SynthesisParams(
            pitch = request.pitch.toFloat(),
            speechRate = request.speechRate.toFloat(),
            volume = 1.0f,
            language = request.language
        )

        val isCompatibilityMode = appConfigRepository?.isCompatibilityModeEnabled() ?: false

        try {
            val audioConfig = engine.getAudioConfig()
            callback.start(
                audioConfig.sampleRate,
                audioConfig.audioFormat,
                audioConfig.channelCount
            )
            TtsLogger.d("Synthesis started, compatibilityMode=$isCompatibilityMode")

            if (isCompatibilityMode) {
                processWithCompatibilityMode(text, params, config, audioConfig, callback)
            } else {
                processWithoutCompatibilityMode(text, params, config, callback)
            }
        } catch (e: Exception) {
            TtsLogger.e("Synthesis failed", e)
            TalkifyNotificationHelper.sendSystemNotification(this, getString(R.string.tts_error_synthesis_failed))
            callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_SYNTHESIS_FAILED))
            releaseWakeLock()
            stopForegroundServiceIfIdle()
            processingSemaphore.release()
        }
    }

    /**
     * 在空闲时停止前台服务
     *
     * 当请求队列为空且没有正在处理的请求时，停止前台服务
     * 减少后台资源占用
     */
    private fun stopForegroundServiceIfIdle() {
        if (!requestQueue.isEmpty() || processingSemaphore.availablePermits() == 0) {
            return
        }
        stopForegroundService()
    }

    /**
     * 使用兼容模式处理合成请求
     *
     * 兼容模式会等待音频完全播放后才完成回调
     * 适用于需要音频输出到扬声器的场景
     *
     * @param text 要合成的文本
     * @param params 合成参数
     * @param config 引擎配置
     * @param audioConfig 音频配置
     * @param callback 合成回调
     */
    private fun processWithCompatibilityMode(
        text: String,
        params: SynthesisParams,
        config: EngineConfig,
        audioConfig: com.github.lonepheasantwarrior.talkify.service.engine.AudioConfig,
        callback: android.speech.tts.SynthesisCallback
    ) {
        val engine = currentEngine ?: run {
            callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_NO_ENGINE))
            processingSemaphore.release()
            return
        }

        if (currentPlayer == null) {
            currentPlayer = CompatibilityModePlayer(audioConfig)
            if (currentPlayer?.initialize() != true) {
                TtsLogger.e("Failed to initialize compatibility player")
                callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_SYNTHESIS_FAILED))
                processingSemaphore.release()
                return
            }
        }

        val player = currentPlayer!!

        startForegroundService()
        engine.synthesize(text, params, config, object : TtsSynthesisListener {
            override fun onSynthesisStarted() {
                TtsLogger.d("Compatibility mode: synthesis started")
            }

            override fun onAudioAvailable(
                audioData: ByteArray,
                sampleRate: Int,
                audioFormat: Int,
                channelCount: Int
            ) {
                val maxChunkSize = 4096
                var offset = 0
                while (offset < audioData.size) {
                    val chunkSize = minOf(maxChunkSize, audioData.size - offset)
                    val chunk = audioData.copyOfRange(offset, offset + chunkSize)
                    callback.audioAvailable(chunk, 0, chunk.size)
                    offset += chunkSize
                }
                player.playAllAndWait(audioData)
            }

            override fun onSynthesisCompleted() {
                TtsLogger.d("Compatibility mode: synthesis completed, waiting for playback")
            }

            override fun onError(error: String) {
                TtsLogger.e("Compatibility mode: synthesis error: $error")
                val errorCode = inferErrorCodeFromMessage(error)
                callback.error(TtsErrorCode.toAndroidError(errorCode))
                releaseWakeLock()
                stopForegroundServiceIfIdle()
                processingSemaphore.release()
            }
        })
    }

    /**
     * 使用非兼容模式处理合成请求
     *
     * 非兼容模式直接返回音频数据，不等待播放完成
     * 适用于音频流直接写入 AudioTrack 的场景
     *
     * @param text 要合成的文本
     * @param params 合成参数
     * @param config 引擎配置
     * @param callback 合成回调
     */
    private fun processWithoutCompatibilityMode(
        text: String,
        params: SynthesisParams,
        config: EngineConfig,
        callback: android.speech.tts.SynthesisCallback
    ) {
        val engine = currentEngine ?: run {
            callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_NO_ENGINE))
            processingSemaphore.release()
            return
        }

        startForegroundService()
        engine.synthesize(text, params, config, object : TtsSynthesisListener {
            override fun onSynthesisStarted() {
                TtsLogger.d("Synthesis started callback")
            }

            override fun onAudioAvailable(
                audioData: ByteArray,
                sampleRate: Int,
                audioFormat: Int,
                channelCount: Int
            ) {
                val maxChunkSize = 4096
                var offset = 0
                while (offset < audioData.size) {
                    val chunkSize = minOf(maxChunkSize, audioData.size - offset)
                    val chunk = audioData.copyOfRange(offset, offset + chunkSize)
                    callback.audioAvailable(chunk, 0, chunk.size)
                    offset += chunkSize
                }
            }

            override fun onSynthesisCompleted() {
                TtsLogger.d("Synthesis completed")
                callback.done()
                releaseWakeLock()
                stopForegroundServiceIfIdle()
                processingSemaphore.release()
            }

            override fun onError(error: String) {
                TtsLogger.e("Synthesis error: $error")
                val errorCode = inferErrorCodeFromMessage(error)
                callback.error(TtsErrorCode.toAndroidError(errorCode))
                releaseWakeLock()
                stopForegroundServiceIfIdle()
                processingSemaphore.release()
            }
        })
    }

    /**
     * 初始化仓储
     *
     * 创建应用配置仓储和引擎配置仓储的实例
     * 用于后续获取用户配置和引擎设置
     */
    private fun initializeRepositories() {
        TtsLogger.d("Initializing repositories")
        try {
            appConfigRepository = SharedPreferencesAppConfigRepository(applicationContext)
            engineConfigRepository = Qwen3TtsConfigRepository(applicationContext)
            TtsLogger.i("Repositories initialized successfully")
        } catch (e: Exception) {
            TtsLogger.e("Failed to initialize repositories", e)
        }
    }

    /**
     * 根据错误消息推断错误码
     *
     * 通过解析错误消息中的关键词来判断具体的错误类型
     * 支持认证失败、超时、网络错误、限流、服务器错误等
     *
     * @param errorMessage 错误消息文本
     * @return 对应的 TtsErrorCode 错误码
     */
    private fun inferErrorCodeFromMessage(errorMessage: String): Int {
        return when {
            errorMessage.contains("API Key", ignoreCase = true) ||
                    errorMessage.contains("认证", ignoreCase = true) ||
                    errorMessage.contains("auth", ignoreCase = true) -> {
                TtsErrorCode.ERROR_API_AUTH_FAILED
            }

            errorMessage.contains("超时", ignoreCase = true) ||
                    errorMessage.contains("timeout", ignoreCase = true) -> {
                TtsErrorCode.ERROR_NETWORK_TIMEOUT
            }

            errorMessage.contains("网络", ignoreCase = true) ||
                    errorMessage.contains("连接", ignoreCase = true) ||
                    errorMessage.contains("network", ignoreCase = true) ||
                    errorMessage.contains("connect", ignoreCase = true) -> {
                TtsErrorCode.ERROR_NETWORK_UNAVAILABLE
            }

            errorMessage.contains("频率", ignoreCase = true) ||
                    errorMessage.contains("rate limit", ignoreCase = true) ||
                    errorMessage.contains("429", ignoreCase = true) -> {
                TtsErrorCode.ERROR_API_RATE_LIMITED
            }

            errorMessage.contains("服务器", ignoreCase = true) ||
                    errorMessage.contains("server", ignoreCase = true) ||
                    errorMessage.contains("500", ignoreCase = true) ||
                    errorMessage.contains("502", ignoreCase = true) ||
                    errorMessage.contains("503", ignoreCase = true) -> {
                TtsErrorCode.ERROR_API_SERVER_ERROR
            }

            errorMessage.contains("API Key", ignoreCase = true) ||
                    errorMessage.contains("配置", ignoreCase = true) -> {
                TtsErrorCode.ERROR_ENGINE_NOT_CONFIGURED
            }

            else -> TtsErrorCode.ERROR_SYNTHESIS_FAILED
        }
    }

    /**
     * 初始化 TTS 引擎
     *
     * 根据用户选择的引擎 ID 创建对应的合成引擎
     * 并从配置仓储加载引擎配置
     *
     * @return 初始化是否成功
     */
    private fun initializeEngine(): Boolean {
        if (appConfigRepository == null) {
            initializeRepositories()
        }

        val selectedEngineId = appConfigRepository?.getSelectedEngineId()
        val engineId = selectedEngineId ?: run {
            TtsLogger.w("No selected engine found, using default")
            TtsEngineRegistry.defaultEngine.id
        }

        TtsLogger.d("Initializing engine: $engineId")

        if (currentEngineId != engineId) {
            TtsLogger.i("Engine changed from $currentEngineId to $engineId, reinitializing")
            currentEngine?.release()
            currentEngine = TtsEngineFactory.createEngine(engineId)
            currentEngineId = engineId

            if (currentEngine == null) {
                TtsLogger.e("Failed to create engine: $engineId")
                return false
            }
        }

        val ttsEngine = TtsEngineRegistry.getEngine(engineId)
        if (ttsEngine == null) {
            TtsLogger.e("Engine not found in registry: $engineId")
            return false
        }

        currentConfig = engineConfigRepository?.getConfig(engineId)
        TtsLogger.d("Engine initialized: ${currentEngine?.getEngineName()}")
        return true
    }

    override fun onIsLanguageAvailable(
        lang: String?,
        country: String?,
        variant: String?
    ): Int {
        val locale = when {
            lang != null && country != null && variant != null -> Locale.Builder()
                .setLanguage(lang)
                .setRegion(convertToValidRegionCode(country))
                .setVariant(variant)
                .build()

            lang != null && country != null -> Locale.Builder()
                .setLanguage(lang)
                .setRegion(convertToValidRegionCode(country))
                .build()

            lang != null -> Locale.Builder()
                .setLanguage(lang)
                .build()

            else -> {
                TtsLogger.w("onIsLanguageAvailable: lang: $lang, country: $country, variant: $variant")
                TtsLogger.w("onIsLanguageAvailable: null language, returning NOT_SUPPORTED")
                return TextToSpeech.LANG_NOT_SUPPORTED
            }
        }

        if (isLanguageSupported(locale.language)) {
            TtsLogger.i("onIsLanguageAvailable: TextToSpeech.LANG_AVAILABLE [lang: $lang, country: $country, variant: $variant]")
            return TextToSpeech.LANG_AVAILABLE
        }
        TtsLogger.w("onIsLanguageAvailable: not support language [${locale.language}]")
        return TextToSpeech.LANG_NOT_SUPPORTED
    }

    /**
     * 检查并兼容多种 ISO 639 格式的语言代码
     * 支持范围: "zh", "en", "de", "it", "pt", "es", "ja", "ko", "fr", "ru"
     */
    fun isLanguageSupported(lang: String?): Boolean {
        if (lang == null) return false

        // 1. 基础清理：转小写并去掉空格（防御大写或异常输入）
        // 2. 映射表：将所有常见的三字母 ISO 639-2/3 代码映射到你的双字母标准上
        val normalizedCode = when (val code = lang.lowercase().trim()) {
            // 中文映射
            "zho", "chi" -> "zh"
            // 英文映射
            "eng" -> "en"
            // 德语映射
            "deu", "ger" -> "de"
            // 意大利语
            "ita" -> "it"
            // 葡萄牙语
            "por" -> "pt"
            // 西班牙语
            "spa" -> "es"
            // 日语
            "jpn" -> "ja"
            // 韩语
            "kor" -> "ko"
            // 法语
            "fra", "fre" -> "fr"
            // 俄语
            "rus" -> "ru"
            // 如果本身就是双字母或者不在上述范围，保持原样
            else -> code
        }

        if (currentEngine == null) {
            initializeEngine()
        }

        // 3. 最终检查
        return currentEngine?.getSupportedLanguages()?.contains(normalizedCode) ?: false
    }

    override fun onLoadLanguage(
        lang: String?,
        country: String?,
        variant: String?
    ): Int {
        val locale = when {
            lang != null && country != null && variant != null -> Locale.Builder()
                .setLanguage(lang)
                .setRegion(convertToValidRegionCode(country))
                .setVariant(variant)
                .build()

            lang != null && country != null -> Locale.Builder()
                .setLanguage(lang)
                .setRegion(convertToValidRegionCode(country))
                .build()

            lang != null -> Locale.Builder()
                .setLanguage(lang)
                .build()

            else -> {
                TtsLogger.w("onLoadLanguage: lang: $lang, country: $country, variant: $variant")
                TtsLogger.w("onLoadLanguage: null language, returning NOT_SUPPORTED")
                return TextToSpeech.LANG_NOT_SUPPORTED
            }
        }

        if (isLanguageSupported(locale.language)) {
            return if (!country.isNullOrBlank()) {
                TtsLogger.d("onLoadLanguage: LANG_COUNTRY_AVAILABLE. lang: $lang, country: $country, variant: $variant")
                TextToSpeech.LANG_COUNTRY_AVAILABLE
            }else{
                TtsLogger.w("onIsLanguageAvailable: not support country [${country}]")
                TextToSpeech.LANG_AVAILABLE
            }
        }
        TtsLogger.w("onIsLanguageAvailable: not support language [${locale.language}]")
        return TextToSpeech.LANG_NOT_SUPPORTED
    }

    /**
     * 转换国家代码为有效区域码
     *
     * 将各种格式的国家代码标准化为双字母 ISO 3166-1 alpha-2 格式
     * 支持常见国家的中英文缩写和三字母代码
     *
     * @param country 原始国家代码
     * @return 标准化后的区域码
     */
    private fun convertToValidRegionCode(country: String): String {
        return when (country.uppercase()) {
            "CHN", "CN" -> "CN"
            "USA", "US" -> "US"
            "GBR", "GB" -> "GB"
            "JPN", "JP" -> "JP"
            "DEU", "DE" -> "DE"
            "FRA", "FR" -> "FR"
            "KOR", "KR" -> "KR"
            else -> country.uppercase()
        }
    }

    override fun onGetLanguage(): Array<String>? {
        TtsLogger.d("onGetLanguage: it is")
        val engine = currentEngine
        if (engine == null) {
            TtsLogger.w("onGetLanguage: no engine available")
            return null
        }

        val config = currentConfig
        val isAvailable = config?.apiKey?.isNotBlank() == true

        if (!isAvailable) {
            TtsLogger.w("onGetLanguage: engine not configured")
            return null
        }

        return currentEngine?.getDefaultLanguages()
    }

    override fun onGetVoices(): List<Voice?>? {
        TtsLogger.d("onGetVoices: it is")
        return currentEngine?.getSupportedVoices()
    }

    override fun onGetDefaultVoiceNameFor(
        lang: String?,
        country: String?,
        variant: String?
    ): String? {
        TtsLogger.d("onGetDefaultVoiceNameFor: lang: $lang, country: $country, variant: $variant")
        return currentEngine?.getDefaultVoiceId(lang, country, variant)
    }

    /**
     * 检查声音 ID 是否正确
     *
     * 验证指定的声音 ID 是否被当前引擎支持
     *
     * @param voiceId 声音 ID
     * @return TextToSpeech.SUCCESS 或 TextToSpeech.ERROR
     */
    private fun isVoiceIdCorrect(voiceId: String?): Int {
        if (currentEngine == null) {
            initializeEngine()
        }

        return if (currentEngine?.isVoiceIdCorrect(voiceId) == true) {
            TextToSpeech.SUCCESS
        } else {
            TextToSpeech.ERROR
        }
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        TtsLogger.d("onIsValidVoiceName: voiceName [$voiceName]")
        val returnSignal = isVoiceIdCorrect(voiceName)
        TtsLogger.d("onIsValidVoiceName: return [$returnSignal]")
        return returnSignal
    }

    override fun onLoadVoice(voiceName: String?): Int {
        TtsLogger.d("onLoadVoice: voiceName [$voiceName]")
        val returnSignal = isVoiceIdCorrect(voiceName)
        TtsLogger.d("onLoadVoice: return [$returnSignal]")
        return returnSignal
    }

    override fun onSynthesizeText(
        request: android.speech.tts.SynthesisRequest?,
        callback: android.speech.tts.SynthesisCallback?
    ) {
        if (request == null || callback == null) {
            TtsLogger.e("onSynthesizeText: null request or callback")
            return
        }

        val isCompatibilityMode = appConfigRepository?.isCompatibilityModeEnabled() ?: false

        if (isCompatibilityMode) {
            TtsLogger.d("onSynthesizeText[CompatibilityMode]: queuing text: ${request.charSequenceText}")
            processRequestSynchronously(request, callback)
        } else {
            TtsLogger.d("onSynthesizeText:  queue size = ${requestQueue.size + 1}, text: ${request.charSequenceText},")
            requestQueue.put(SynthesisRequestWrapper(request, callback))
        }
    }

    /**
     * 同步处理合成请求
     *
     * 兼容模式下同步处理单个请求，等待音频完全播放后才返回
     * 用于需要音频输出到扬声器的场景
     *
     * @param request 合成请求
     * @param callback 合成回调
     */
    private fun processRequestSynchronously(
        request: android.speech.tts.SynthesisRequest,
        callback: android.speech.tts.SynthesisCallback
    ) {
        if (isStopped.get()) {
            TtsLogger.d("processRequestSynchronously: service is stopped, skipping request")
            callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
            return
        }

        val text = request.charSequenceText?.toString()

        if (text.isNullOrBlank()) {
            TtsLogger.w("processRequestSynchronously: empty text")
            callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
            return
        }

        TtsLogger.d("processRequestSynchronously: compatibility mode, text length = ${text.length}")

        if (isSynthesisInProgress.getAndSet(true)) {
            TtsLogger.d("processRequestSynchronously: synthesis in progress, stopping current playback")
            currentPlayer?.stop()
            synthesisLatch = null
        }

        acquireWakeLock()

        val engineId = currentEngineId
        if (engineId == null) {
            TtsLogger.e("processRequestSynchronously: no engine ID available")
            TalkifyNotificationHelper.sendSystemNotification(this, getString(R.string.tts_error_no_engine_id_available))
            callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_ENGINE_NOT_FOUND))
            return
        }

        val engine = currentEngine
        if (engine == null) {
            TtsLogger.e("processRequestSynchronously: no engine available")
            TalkifyNotificationHelper.sendSystemNotification(this, getString(R.string.tts_error_no_engine))
            callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_NO_ENGINE))
            return
        }

        val config = engineConfigRepository?.getConfig(engineId)
        if (config == null) {
            TtsLogger.e("processRequestSynchronously: no config available")
            TalkifyNotificationHelper.sendSystemNotification(this, getString(R.string.tts_error_no_config_available))
            callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_CONFIG_NOT_FOUND))
            return
        }

        if (!engine.isConfigured(config)) {
            TtsLogger.w("processRequestSynchronously: engine not configured")
            TalkifyNotificationHelper.sendSystemNotification(this, getString(R.string.tts_error_engine_not_configured))
            callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_ENGINE_NOT_CONFIGURED))
            return
        }

        val params = SynthesisParams(
            pitch = request.pitch.toFloat(),
            speechRate = request.speechRate.toFloat(),
            volume = 1.0f,
            language = request.language
        )

        try {
            val audioConfig = engine.getAudioConfig()
            callback.start(
                audioConfig.sampleRate,
                audioConfig.audioFormat,
                audioConfig.channelCount
            )

            if (currentPlayer == null) {
                currentPlayer = CompatibilityModePlayer(audioConfig)
                if (currentPlayer?.initialize() != true) {
                    TtsLogger.e("Failed to initialize compatibility player")
                    callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_SYNTHESIS_FAILED))
                    return
                }
            }

            val player = currentPlayer!!

            synthesisLatch = java.util.concurrent.CountDownLatch(1)
            var synthesisError: String? = null

            startForegroundService()
            engine.synthesize(text, params, config, object : TtsSynthesisListener {
                override fun onSynthesisStarted() {
                    TtsLogger.d("processRequestSynchronously: synthesis started")
                }

                override fun onAudioAvailable(
                    audioData: ByteArray,
                    sampleRate: Int,
                    audioFormat: Int,
                    channelCount: Int
                ) {
                    player.playAllAndWait(audioData)
                }

                override fun onSynthesisCompleted() {
                    TtsLogger.d("processRequestSynchronously: synthesis completed")
                    synthesisLatch?.countDown()
                }

                override fun onError(error: String) {
                    TtsLogger.e("processRequestSynchronously: synthesis error: $error")
                    synthesisError = error
                    synthesisLatch?.countDown()
                }
            })

            try {
                synthesisLatch?.await(120, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                isSynthesisInProgress.set(false)
                releaseWakeLock()
                stopForegroundService()
                callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_SYNTHESIS_FAILED))
                return
            }

            isSynthesisInProgress.set(false)
            releaseWakeLock()
            stopForegroundService()
            if (synthesisError != null) {
                val code = inferErrorCodeFromMessage(synthesisError)
                callback.error(TtsErrorCode.toAndroidError(code))
            } else {
                callback.done()
            }
        } catch (e: Exception) {
            TtsLogger.e("processRequestSynchronously: Synthesis failed", e)
            TalkifyNotificationHelper.sendSystemNotification(this, getString(R.string.tts_error_synthesis_failed))
            isSynthesisInProgress.set(false)
            releaseWakeLock()
            stopForegroundService()
            callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_SYNTHESIS_FAILED))
        }
    }

    override fun onDestroy() {
        TtsLogger.i("TalkifyTtsService onDestroy")
        isStopped.set(true)
        try {
            currentEngine?.stop()
        } catch (e: android.os.RemoteException) {
            TtsLogger.w("Remote exception during engine stop, service may be disconnecting: ${e.message}")
        } catch (e: android.os.DeadObjectException) {
            TtsLogger.w("Engine connection lost during stop, service is being destroyed: ${e.message}")
        } catch (e: Exception) {
            TtsLogger.e("Unexpected error during engine stop", e)
        }
        try {
            currentEngine?.release()
        } catch (e: android.os.RemoteException) {
            TtsLogger.w("Remote exception during engine release: ${e.message}")
        } catch (e: android.os.DeadObjectException) {
            TtsLogger.w("Engine connection lost during release: ${e.message}")
        } catch (e: Exception) {
            TtsLogger.e("Unexpected error during engine release", e)
        }
        currentEngine = null
        currentConfig = null
        currentEngineId = null
        try {
            currentPlayer?.release()
        } catch (e: Exception) {
            TtsLogger.e("Error releasing player", e)
        }
        currentPlayer = null
        serviceScope.cancel()
        releaseWakeLock()
        stopForegroundService()
        super.onDestroy()
    }

    /**
     * 服务停止回调
     *
     * 当系统请求服务停止时调用
     * 释放正在进行的合成操作和播放器资源
     */
    override fun onStop() {
        TtsLogger.d("onStop called")
        isSynthesisInProgress.set(false)

        val latch = synthesisLatch
        if (latch != null && latch.count > 0) {
            TtsLogger.d("onStop: counting down synthesis latch")
            latch.countDown()
        }
        synthesisLatch = null

        try {
            currentEngine?.stop()
        } catch (e: android.os.RemoteException) {
            TtsLogger.w("Remote exception during engine stop in onStop: ${e.message}")
        } catch (e: android.os.DeadObjectException) {
            TtsLogger.w("Engine connection lost during onStop: ${e.message}")
        } catch (e: Exception) {
            TtsLogger.e("Unexpected error during engine stop in onStop", e)
        }

        try {
            currentPlayer?.release()
        } catch (e: Exception) {
            TtsLogger.e("Error releasing player in onStop", e)
        }
        currentPlayer = null

        requestQueue.clear()
        if (processingSemaphore.availablePermits() == 0) {
            processingSemaphore.release()
        }
    }
}
