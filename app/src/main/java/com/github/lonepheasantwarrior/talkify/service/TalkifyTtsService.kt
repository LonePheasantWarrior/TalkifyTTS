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
import com.github.lonepheasantwarrior.talkify.service.engine.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.engine.TtsEngineApi
import com.github.lonepheasantwarrior.talkify.service.engine.TtsEngineFactory
import com.github.lonepheasantwarrior.talkify.service.engine.TtsSynthesisListener
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

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

    @Volatile
    private var activeContinuation: CancellableContinuation<Int?>? = null

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
            TtsEngineFactory.createConfigRepository(engineId, applicationContext) ?: run {
                TtsLogger.w("Unknown engine ID: $engineId, using default Qwen3TtsConfigRepository")
                Qwen3TtsConfigRepository(applicationContext)
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
    ) = runBlocking {
        // 1. 基础校验
        if (isStopped.get()) {
            callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
            return@runBlocking
        }
        val text = request.charSequenceText?.toString()
        if (text.isNullOrBlank()) {
            callback.done()
            return@runBlocking
        }

        // 2. 获取双重锁：WakeLock (CPU) + WifiLock (网络)
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
                TalkifyNotificationHelper.sendSystemNotification(
                    this@TalkifyTtsService,
                    getString(R.string.tts_error_engine_not_ready)
                )
                return@runBlocking
            }

            val config = getEngineConfigRepository(engineId).getConfig(engineId)
            if (!engine.isConfigured(config)) {
                TtsLogger.e("processRequestSynchronously: config not ready")
                callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_ENGINE_NOT_CONFIGURED))
                TalkifyNotificationHelper.sendSystemNotification(
                    this@TalkifyTtsService,
                    getString(R.string.tts_error_config_not_ready)
                )
                return@runBlocking
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
            )

            // 4. 初始化音频参数并通知系统开始
            var audioInitialized = false
            var synthesisErrorMessage: String? = null

            // 5. 执行合成 (使用协程挂起)
            val result = withTimeoutOrNull(120_000L) {
                suspendCancellableCoroutine { continuation ->
                    activeContinuation = continuation

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
                            // 在收到第一个音频数据时初始化系统回调
                            if (!audioInitialized) {
                                audioInitialized = true
                                callback.start(sampleRate, audioFormat, channelCount)
                            }
                            
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
                            if (continuation.isActive) {
                                try {
                                    continuation.resume(TtsErrorCode.SUCCESS)
                                } catch (e: Exception) {
                                    TtsLogger.d("Resuming continuation failed: ${e.message}")
                                }
                            }
                        }

                        override fun onError(error: String) {
                            TtsLogger.e("Synthesis error: $error")
                            synthesisErrorMessage = error
                            val errorCode = TtsErrorCode.inferErrorCodeFromMessage(error)
                            if (continuation.isActive) {
                                try {
                                    continuation.resume(errorCode)
                                } catch (e: Exception) {
                                    TtsLogger.d("Resuming continuation failed: ${e.message}")
                                }
                            }
                        }
                    })
                }
            }

            // 6. 处理结果
            if (result == null) {
                // 等待超时
                TtsLogger.e("Synthesis timed out")
                try { engine.stop() } catch (_: Exception) {}
                callback.error(TextToSpeech.ERROR_NETWORK_TIMEOUT)
                TalkifyNotificationHelper.sendSystemNotification(
                    this@TalkifyTtsService,
                    TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_NETWORK_TIMEOUT)
                )
            } else if (result != TtsErrorCode.SUCCESS) {
                // 发生错误
                callback.error(TtsErrorCode.toAndroidError(result))
                TalkifyNotificationHelper.sendSystemNotification(
                    this@TalkifyTtsService,
                    TtsErrorCode.getErrorMessage(result, synthesisErrorMessage)
                )
            } else {
                // 正常完成
                callback.done()
            }

        } catch (_: InterruptedException) {
            TtsLogger.w("Synthesis interrupted")
            callback.error(TextToSpeech.ERROR_SERVICE)
            TalkifyNotificationHelper.sendSystemNotification(
                this@TalkifyTtsService,
                TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_GENERIC, "Synthesis interrupted")
            )
            Thread.currentThread().interrupt()
        } catch (_: CancellationException) {
            TtsLogger.i("Synthesis cancelled")
        } catch (e: Exception) {
            TtsLogger.e("Critical error in processRequestSynchronously", e)
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
            TalkifyNotificationHelper.sendSystemNotification(
                this@TalkifyTtsService,
                TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_UNKNOWN, e.message)
            )
        } finally {
            // 7. 统一清理资源
            activeContinuation = null
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

        val continuation = activeContinuation
        if (continuation != null && continuation.isActive) {
            TtsLogger.d("onStop: cancelling active continuation")
            continuation.cancel()
        }
        activeContinuation = null

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
