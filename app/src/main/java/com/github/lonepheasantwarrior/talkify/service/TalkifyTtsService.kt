package com.github.lonepheasantwarrior.talkify.service

import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.BaseEngineConfig
import com.github.lonepheasantwarrior.talkify.domain.model.TtsEngineRegistry
import com.github.lonepheasantwarrior.talkify.domain.repository.AppConfigRepository
import com.github.lonepheasantwarrior.talkify.domain.repository.EngineConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.app.notification.TalkifyNotificationHelper
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.SharedPreferencesAppConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.engine.repo.Qwen3TtsConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.engine.repo.SeedTts2ConfigRepository
import com.github.lonepheasantwarrior.talkify.service.engine.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.engine.TtsEngineApi
import com.github.lonepheasantwarrior.talkify.service.engine.TtsEngineFactory
import com.github.lonepheasantwarrior.talkify.service.engine.TtsSynthesisListener
import java.util.Locale
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
 * 采用请求队列机制实现请求调度，支持请求优先级和流量控制
 * 支持兼容模式和非兼容模式两种音频处理方式
 *
 * @property processingSemaphore 请求处理信号量，限制并发处理数量为 1
 * @property isStopped 服务停止标志，使用 AtomicBoolean 保证线程安全
 * @property isSynthesisInProgress 合成进行中标志，用于状态追踪
 * @property synthesisLatch 合成完成门闩，用于同步等待合成完成
 * @property wakeLock 电源唤醒锁，防止合成过程中设备休眠
 * @property isForegroundServiceRunning 前台服务运行状态
 * @property appConfigRepository 应用配置仓储，管理全局应用设置
 * @property engineConfigRepository 引擎配置仓储，管理各引擎配置
 * @property currentEngine 当前活动的 TTS 引擎实例
 * @property currentEngineId 当前引擎的唯一标识符
 * @property currentConfig 当前引擎的配置信息
 */
class TalkifyTtsService : TextToSpeechService() {

    private val processingSemaphore = Semaphore(1)

    private var isStopped = AtomicBoolean(false)

    private var isSynthesisInProgress = AtomicBoolean(false)

    private var synthesisLatch: java.util.concurrent.CountDownLatch? = null

    private var wakeLock: PowerManager.WakeLock? = null

    private val wifiLock: WifiManager.WifiLock by lazy {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Talkify:WifiLock")
    }

    private var isForegroundServiceRunning = false

    private var appConfigRepository: AppConfigRepository? = null

    private var engineConfigRepository: EngineConfigRepository? = null

    private var currentEngine: TtsEngineApi? = null

    private var currentEngineId: String? = null

    private var currentConfig: BaseEngineConfig? = null

    override fun onCreate() {
        super.onCreate()
        TtsLogger.i("TalkifyTtsService onCreate")
        initializeWakeLock()
        initializeRepositories()
        val engineInitSuccess = initializeEngine()
        TtsLogger.d("Engine initialization result: $engineInitSuccess")
    }

    /**
     * 初始化 WakeLock
     *
     * 用于防止在语音合成过程中设备进入休眠状态
     * 设置为部分唤醒锁，最长持有 10 分钟
     */
    private fun initializeWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Talkify:TtsServiceWakeLock"
        ).apply {
            setReferenceCounted(false)
        }
        TtsLogger.d("WakeLock initialized")
    }

    /**
     * 获取 WifiLock
     * 确保在合成期间 WiFi 保持高性能模式
     */
    private fun acquireWifiLock() {
        try {
            if (!wifiLock.isHeld) {
                wifiLock.acquire()
                TtsLogger.d("WifiLock acquired")
            }
        } catch (e: Exception) {
            TtsLogger.e("Failed to acquire WifiLock", e)
        }
    }

    /**
     * 释放 WifiLock
     */
    private fun releaseWifiLock() {
        try {
            if (wifiLock.isHeld) {
                wifiLock.release()
                TtsLogger.d("WifiLock released")
            }
        } catch (e: Exception) {
            TtsLogger.e("Failed to release WifiLock", e)
        }
    }

    /**
     * 启动前台服务
     *
     * 如果服务尚未运行，则启动为前台服务并显示通知
     * 使用 [TalkifyNotificationHelper.buildForegroundWithNotification] 构建通知
     * 
     * 注意：Android 12+ 限制了后台启动前台服务，当第三方应用在后台调用 TTS 时
     * 可能抛出 ForegroundServiceStartNotAllowedException，此时我们会静默降级为非前台服务
     */
    private fun startForegroundService() {
        if (!isForegroundServiceRunning) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        FOREGROUND_SERVICE_N_ID,
                        TalkifyNotificationHelper.buildForegroundWithNotification(this),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(FOREGROUND_SERVICE_N_ID, TalkifyNotificationHelper.buildForegroundWithNotification(this))
                }
                isForegroundServiceRunning = true
                TtsLogger.d("Foreground service started")
            } catch (e: Exception) {
                // Android 12+ 可能抛出 ForegroundServiceStartNotAllowedException
                // 当第三方应用在后台调用 TTS 服务时，系统禁止启动前台服务
                // 此时我们静默处理，继续以非前台服务模式运行
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && 
                    e is android.app.ForegroundServiceStartNotAllowedException) {
                    TtsLogger.w("Cannot start foreground service from background, continuing without foreground status")
                } else {
                    TtsLogger.w("Failed to start foreground service: ${e.message}")
                    TalkifyNotificationHelper.sendSystemNotification(this, getString(R.string.tts_error_foreground_service_failed))
                }
                // 标记为未运行前台服务，但允许继续执行 TTS 合成
                isForegroundServiceRunning = false
            }
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
     * 在空闲时停止前台服务
     *
     * 当请求队列为空且没有正在处理的请求时，停止前台服务
     * 减少后台资源占用
     */
    private fun stopForegroundServiceIfIdle() {
        if (processingSemaphore.availablePermits() == 0) {
            return
        }
        stopForegroundService()
    }

    /**
     * 引擎配置仓储映射表
     * 根据引擎 ID 获取对应的配置仓储
     */
    private val engineConfigRepositoryMap: MutableMap<String, EngineConfigRepository> = mutableMapOf()

    /**
     * 获取指定引擎的配置仓储
     *
     * 根据引擎 ID 动态创建或获取对应的配置仓储实例
     * 支持多引擎配置隔离存储
     *
     * @param engineId 引擎唯一标识符
     * @return 对应引擎的配置仓储实例
     */
    private fun getEngineConfigRepository(engineId: String): EngineConfigRepository {
        return engineConfigRepositoryMap.getOrPut(engineId) {
            when (engineId) {
                "qwen3-tts" -> Qwen3TtsConfigRepository(applicationContext)
                "seed-tts-2.0" -> SeedTts2ConfigRepository(applicationContext)
                else -> {
                    TtsLogger.w("Unknown engine ID: $engineId, using default Qwen3TtsConfigRepository")
                    Qwen3TtsConfigRepository(applicationContext)
                }
            }
        }
    }

    /**
     * 初始化仓储
     *
     * 创建应用配置仓储的实例
     * 引擎配置仓储采用延迟初始化策略，根据实际使用的引擎动态创建
     */
    private fun initializeRepositories() {
        TtsLogger.d("Initializing repositories")
        try {
            appConfigRepository = SharedPreferencesAppConfigRepository(applicationContext)
            // 引擎配置仓储不再在这里统一初始化
            // 而是在 getEngineConfigRepository() 中根据引擎 ID 动态创建
            TtsLogger.i("Repositories initialized successfully")
        } catch (e: Exception) {
            TtsLogger.e("Failed to initialize repositories", e)
            TalkifyNotificationHelper.sendSystemNotification(this, getString(R.string.tts_error_init_failed))
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
                TalkifyNotificationHelper.sendSystemNotification(this, getString(R.string.tts_error_engine_init_failed))
                return false
            }
        }

        val ttsEngine = TtsEngineRegistry.getEngine(engineId)
        if (ttsEngine == null) {
            TtsLogger.e("Engine not found in registry: $engineId")
            TalkifyNotificationHelper.sendSystemNotification(this, getString(R.string.tts_error_engine_not_found))
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
            TtsLogger.d("onIsLanguageAvailable: TextToSpeech.LANG_AVAILABLE [lang: $lang, country: $country, variant: $variant]")
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

        if (currentEngine == null) {
            initializeEngine()
        }

        // 3. 最终检查
        return currentEngine?.getSupportedLanguages()?.contains(lang) ?: false
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
        val engine = currentEngine
        if (engine == null) {
            TtsLogger.w("onGetLanguage: no engine available")
            return null
        }

        if (!engine.isConfigured(currentConfig)) {
            TtsLogger.w("onGetLanguage: engine not configured")
            return null
        }

        val defaultLanguages = engine.getDefaultLanguages()
        TtsLogger.d("onGetLanguage: return $defaultLanguages")
        return defaultLanguages
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
        var currentVoiceId: String? = null
        val config = currentConfig
        if (config != null && config.voiceId.isNotBlank()) {
            currentVoiceId = config.voiceId
        }
        val defaultVoiceName = currentEngine?.getDefaultVoiceId(lang, country, variant, currentVoiceId)
        TtsLogger.d("onGetDefaultVoiceNameFor: defaultVoiceName: $defaultVoiceName")
        return defaultVoiceName
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

        TtsLogger.d("onSynthesizeText: queuing text: ${request.charSequenceText}")
        processRequestSynchronously(request, callback)
    }

    /**
     * 同步处理合成请求 (修复版)
     *
     * 直接在当前线程阻塞等待合成结果，符合 Android TTS Service 标准生命周期。
     * 修复了死锁隐患，并增加了 WifiLock 以保证网络流式传输的稳定性。
     */
    private fun processRequestSynchronously(
        request: android.speech.tts.SynthesisRequest,
        callback: android.speech.tts.SynthesisCallback
    ) {
        // 1. 基础校验
        if (isStopped.get()) {
            callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
            return
        }
        val text = request.charSequenceText?.toString()
        if (text.isNullOrBlank()) {
            callback.done()
            return
        }

        // 2. 获取双重锁：WakeLock (CPU) + WifiLock (网络)
        // 这一步对于 Play 图书这种可能在后台/关屏播放的场景至关重要
        acquireWakeLock()
        acquireWifiLock()

        // 提升前台优先级，防止被系统查杀
        startForegroundService()

        try {
            // 3. 准备引擎与配置
            val engineId = currentEngineId
            val engine = currentEngine
            if (engineId == null || engine == null) {
                TtsLogger.e("processRequestSynchronously: engine not ready")
                callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_NO_ENGINE))
                TalkifyNotificationHelper.sendSystemNotification(this, getString(R.string.tts_error_engine_not_ready))
                return
            }

            val config = getEngineConfigRepository(engineId).getConfig(engineId)
            if (!engine.isConfigured(config)) {
                TtsLogger.e("processRequestSynchronously: config not ready")
                callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_ENGINE_NOT_CONFIGURED))
                TalkifyNotificationHelper.sendSystemNotification(this, getString(R.string.tts_error_config_not_ready))
                return
            }
            if (config.voiceId.isNotBlank() && request.voiceName.isNotBlank()) {
                if (config.voiceId != request.voiceName) {
                    TtsLogger.w("Synthesize: SynthesisRequest.voiceName: ${request.voiceName}, EngineConfig.voiceId: ${config.voiceId}")
                }
            }

            val params = SynthesisParams(
                pitch = request.pitch.toFloat(),
                speechRate = request.speechRate.toFloat(),
                language = request.language
                // volume 使用默认值 1.0f（SynthesisRequest 不直接提供 volume 参数）
            )

            // 4. 初始化音频参数并通知系统开始
            val audioConfig = engine.getAudioConfig()
            // 必须在写入任何数据前调用 start，否则会报 -1 错误
            callback.start(
                audioConfig.sampleRate,
                audioConfig.audioFormat,
                audioConfig.channelCount
            )

            // 5. 设置同步门闩 (Latch)
            // 我们需要阻塞当前函数，直到 callback.done() 被调用
            synthesisLatch = java.util.concurrent.CountDownLatch(1)
            var synthesisError: Int? = null

            // 6. 执行合成
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
                    // 直接将音频数据分块写入系统回调
                    val maxChunkSize = 4096
                    var offset = 0
                    while (offset < audioData.size) {
                        val chunkSize = minOf(maxChunkSize, audioData.size - offset)
                        val chunk = audioData.copyOfRange(offset, offset + chunkSize)
                        // 注意：如果 callback 失效，这里会抛出异常，会被外层 catch 捕获，这是安全的
                        callback.audioAvailable(chunk, 0, chunk.size)
                        offset += chunkSize
                    }
                }

                override fun onSynthesisCompleted() {
                    TtsLogger.d("Synthesis completed")
                    // 关键修复：必须解锁主流程
                    synthesisLatch?.countDown()
                }

                override fun onError(error: String) {
                    TtsLogger.e("Synthesis error: $error")
                    synthesisError = inferErrorCodeFromMessage(error)
                    // 关键修复：发生错误也必须解锁，否则会导致服务假死 2 分钟
                    synthesisLatch?.countDown()
                }
            })

            // 7. 阻塞等待
            // Play Books 会在这里卡住等待，直到合成完成或超时
            // 设置 120 秒超时防止网络永久挂起
            val finished = synthesisLatch?.await(120, java.util.concurrent.TimeUnit.SECONDS)

            if (finished == true) {
                if (synthesisError != null) {
                    // 确实发生了错误
                    callback.error(TtsErrorCode.toAndroidError(synthesisError))
                    TalkifyNotificationHelper.sendSystemNotification(this, getString(R.string.tts_error_synthesis_failed))
                } else {
                    // 正常完成
                    callback.done()
                }
            } else {
                // 等待超时
                TtsLogger.e("Synthesis timed out waiting for latch")
                // 尝试终止引擎请求
                try { engine.stop() } catch (_: Exception) {}
                callback.error(TextToSpeech.ERROR_NETWORK_TIMEOUT)
                TalkifyNotificationHelper.sendSystemNotification(this, getString(R.string.tts_error_synthesis_failed))
            }

        } catch (_: InterruptedException) {
            TtsLogger.w("Synthesis interrupted")
            callback.error(TextToSpeech.ERROR_SERVICE)
            TalkifyNotificationHelper.sendSystemNotification(this, getString(R.string.tts_error_synthesis_failed))
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            TtsLogger.e("Critical error in processRequestSynchronously", e)
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
            TalkifyNotificationHelper.sendSystemNotification(this, getString(R.string.tts_error_synthesis_failed))
        } finally {
            // 8. 统一清理资源
            // 无论成功还是失败，都要释放锁，防止耗电
            synthesisLatch = null
            isSynthesisInProgress.set(false)
            stopForegroundServiceIfIdle()
            releaseWifiLock()
            releaseWakeLock()
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

        if (processingSemaphore.availablePermits() == 0) {
            processingSemaphore.release()
        }
    }
}
