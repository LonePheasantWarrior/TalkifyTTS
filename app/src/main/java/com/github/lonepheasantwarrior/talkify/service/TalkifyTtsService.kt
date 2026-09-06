package com.github.lonepheasantwarrior.talkify.service

import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.domain.model.TtsProviderRegistry
import com.github.lonepheasantwarrior.talkify.domain.repository.AppConfigRepository
import com.github.lonepheasantwarrior.talkify.domain.repository.ProviderConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.app.notification.TalkifyNotificationHelper
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.SharedPreferencesAppConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.TtsTelemetryTracker
import com.github.lonepheasantwarrior.talkify.service.provider.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.provider.TtsProviderApi
import com.github.lonepheasantwarrior.talkify.service.provider.TtsProviderFactory
import com.github.lonepheasantwarrior.talkify.service.provider.TtsSynthesisListener
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/**
 * 前台阅读服务通知 ID
 */
private const val FOREGROUND_SERVICE_N_ID = 1001

/**
 * Talkify TTS 服务
 *
 * 实现 [TextToSpeechService]，作为系统 TTS 框架与本应用供应商之间的桥梁
 * 负责：
 * 1. 根据用户选择的供应商 ID 获取对应的合成供应商
 * 2. 获取用户配置的供应商设置
 * 3. 委托供应商执行实际的语音合成
 *
 * 采用请求队列机制实现请求调度，支持请求优先级和流量控制
 * 支持兼容模式和非兼容模式两种音频处理方式
 *
 * @property isStopped 服务停止标志，使用 AtomicBoolean 保证线程安全
 * @property wakeLock 电源唤醒锁，防止合成过程中设备休眠
 * @property isForegroundServiceRunning 前台服务运行状态
 * @property appConfigRepository 应用配置仓储，管理全局应用设置
 * @property currentProvider 当前活动的 TTS 供应商实例
 * @property currentProviderId 当前供应商的唯一标识符
 * @property currentConfig 当前供应商的配置信息
 */
class TalkifyTtsService : TextToSpeechService() {

    private var isStopped = AtomicBoolean(false)

    @Volatile
    private var activeContinuation: CancellableContinuation<Int?>? = null

    private var wakeLock: PowerManager.WakeLock? = null

    private val wifiLock: WifiManager.WifiLock by lazy {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Talkify:WifiLock")
    }

    private var isForegroundServiceRunning = false

    private var appConfigRepository: AppConfigRepository? = null

    private var currentProvider: TtsProviderApi? = null

    private var currentProviderId: String? = null

    private var currentConfig: BaseProviderConfig? = null

    override fun onCreate() {
        super.onCreate()
        TtsLogger.i("TalkifyTtsService onCreate")
        initializeWakeLock()
        initializeRepositories()
        val providerInitSuccess = initializeProvider()
        TtsLogger.d("Provider initialization result: $providerInitSuccess")
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
     * 每次合成请求结束后调用（TTS 请求由系统串行调度，无并发请求），
     * 释放前台状态以减少后台资源占用
     */
    private fun stopForegroundServiceIfIdle() {
        stopForegroundService()
    }

    /**
     * 供应商配置仓储映射表
     * 根据供应商 ID 获取对应的配置仓储
     */
    private val providerConfigRepositoryMap: MutableMap<String, ProviderConfigRepository> = mutableMapOf()

    /**
     * 获取指定供应商的配置仓储
     *
     * 根据供应商 ID 动态创建或获取对应的配置仓储实例
     * 支持多供应商配置隔离存储
     *
     * @param providerId 供应商唯一标识符
     * @return 对应供应商的配置仓储实例
     */
    private fun getProviderConfigRepository(providerId: String): ProviderConfigRepository? {
        providerConfigRepositoryMap[providerId]?.let { return it }
        val repository = TtsProviderFactory.createConfigRepository(providerId, applicationContext)
        if (repository == null) {
            TtsLogger.e("Unknown provider ID: $providerId, config repository unavailable")
            return null
        }
        providerConfigRepositoryMap[providerId] = repository
        return repository
    }

    /**
     * 初始化仓储
     *
     * 创建应用配置仓储的实例
     * 供应商配置仓储采用延迟初始化策略，根据实际使用的供应商动态创建
     */
    private fun initializeRepositories() {
        TtsLogger.d("Initializing repositories")
        try {
            appConfigRepository = SharedPreferencesAppConfigRepository(applicationContext)
            // 供应商配置仓储不再在这里统一初始化
            // 而是在 getProviderConfigRepository() 中根据供应商 ID 动态创建
            TtsLogger.i("Repositories initialized successfully")
        } catch (e: Exception) {
            TtsLogger.e("Failed to initialize repositories", e)
            TalkifyNotificationHelper.sendSystemNotification(this, getString(R.string.tts_error_init_failed))
        }
    }

    /**
     * 初始化 TTS 供应商
     *
     * 根据用户选择的供应商 ID 创建对应的合成供应商
     * 并从配置仓储加载供应商配置
     *
     * @return 初始化是否成功
     */
    private fun initializeProvider(): Boolean {
        if (appConfigRepository == null) {
            initializeRepositories()
        }

        val selectedProviderId = appConfigRepository?.getSelectedProviderId()
        val providerId = selectedProviderId ?: run {
            TtsLogger.w("No selected provider found, using default")
            TtsProviderRegistry.defaultProvider.id
        }

        TtsLogger.d("Initializing provider: $providerId")

        if (currentProviderId != providerId) {
            TtsLogger.i("Provider changed from $currentProviderId to $providerId, reinitializing")
            currentProvider?.release()
            currentProvider = TtsProviderFactory.createProvider(providerId)
            currentProviderId = providerId

            if (currentProvider == null) {
                TtsLogger.e("Failed to create provider: $providerId")
                TalkifyNotificationHelper.sendSystemNotification(this, getString(R.string.tts_error_provider_init_failed))
                return false
            }
        }

        val ttsProvider = TtsProviderRegistry.getProvider(providerId)
        if (ttsProvider == null) {
            TtsLogger.e("Provider not found in registry: $providerId")
            TalkifyNotificationHelper.sendSystemNotification(this, getString(R.string.tts_error_provider_not_found))
            return false
        }

        currentConfig = getProviderConfigRepository(providerId)?.getConfig(providerId)
        TtsLogger.d("Provider initialized: ${currentProvider?.getProviderName()}")
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

        if (currentProvider == null) {
            initializeProvider()
        }

        // 3. 最终检查
        return currentProvider?.getSupportedLanguages()?.contains(lang) ?: false
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
        val provider = currentProvider
        if (provider == null) {
            TtsLogger.w("onGetLanguage: no provider available")
            return null
        }

        if (!provider.isConfigured(currentConfig)) {
            TtsLogger.w("onGetLanguage: provider not configured")
            return null
        }

        val defaultLanguage = provider.getDefaultLanguage()
        TtsLogger.d("onGetLanguage: return ${defaultLanguage.contentToString()}")
        return defaultLanguage
    }

    override fun onGetVoices(): List<Voice?>? {
        TtsLogger.d("onGetVoices: it is")
        return currentProvider?.getSupportedVoices()
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
        val defaultVoiceName = currentProvider?.getDefaultVoiceId(lang, country, variant, currentVoiceId)
        TtsLogger.d("onGetDefaultVoiceNameFor: defaultVoiceName: $defaultVoiceName")
        return defaultVoiceName
    }

    /**
     * 检查声音 ID 是否正确
     *
     * 验证指定的声音 ID 是否被当前供应商支持
     *
     * @param voiceId 声音 ID
     * @return TextToSpeech.SUCCESS 或 TextToSpeech.ERROR
     */
    private fun isVoiceIdCorrect(voiceId: String?): Int {
        if (currentProvider == null) {
            initializeProvider()
        }

        return if (currentProvider?.isVoiceIdCorrect(voiceId) == true) {
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
            // 3. 准备供应商与配置
            // 每次合成前重新读取配置，确保获取最新的供应商选择
            val selectedProviderId = appConfigRepository?.getSelectedProviderId()
                ?: TtsProviderRegistry.defaultProvider.id
            
            // 检测供应商是否切换，如果切换则重新初始化
            if (currentProviderId != selectedProviderId) {
                TtsLogger.i("Provider changed from $currentProviderId to $selectedProviderId during synthesis, reinitializing")
                currentProvider?.release()
                currentProvider = TtsProviderFactory.createProvider(selectedProviderId)
                currentProviderId = selectedProviderId
                
                if (currentProvider == null) {
                    TtsLogger.e("Failed to create provider: $selectedProviderId")
                    TalkifyNotificationHelper.sendSystemNotification(
                        this@TalkifyTtsService,
                        getString(R.string.tts_error_provider_init_failed)
                    )
                    callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_NO_PROVIDER))
                    return@runBlocking
                }
                
                // 更新配置仓储的缓存
                providerConfigRepositoryMap.remove(selectedProviderId)
            }
            
            val providerId = currentProviderId
            val provider = currentProvider
            if (providerId == null || provider == null) {
                TtsLogger.e("processRequestSynchronously: provider not ready")
                callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_NO_PROVIDER))
                TalkifyNotificationHelper.sendSystemNotification(
                    this@TalkifyTtsService,
                    getString(R.string.tts_error_provider_not_ready)
                )
                return@runBlocking
            }

            val config = getProviderConfigRepository(providerId)?.getConfig(providerId)
            if (config == null) {
                TtsLogger.e("processRequestSynchronously: config repository unavailable for $providerId")
                callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_NO_PROVIDER))
                return@runBlocking
            }
            if (!provider.isConfigured(config)) {
                TtsLogger.e("processRequestSynchronously: config not ready")
                callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_PROVIDER_NOT_CONFIGURED))
                TalkifyNotificationHelper.sendSystemNotification(
                    this@TalkifyTtsService,
                    getString(R.string.tts_error_config_not_ready)
                )
                return@runBlocking
            }
            if (config.voiceId.isNotBlank() && request.voiceName.isNotBlank()) {
                if (config.voiceId != request.voiceName) {
                    TtsLogger.w("Synthesize: SynthesisRequest.voiceName: ${request.voiceName}, ProviderConfig.voiceId: ${config.voiceId}")
                }
            }

            val params = SynthesisParams(
                pitch = request.pitch.toFloat(),
                speechRate = request.speechRate.toFloat(),
                language = request.language
            )

            // 4. 初始化音频参数并通知系统开始
            var audioInitialized = false

            // 合成错误消息：provider 回调线程写、下方读取，用 AtomicReference 保证可见性
            val synthesisErrorMessage = AtomicReference<String?>(null)

            // 语音合成事件信息收集（限频上报：模型切换或累计15次触发）
            val effectiveModelId = config.modelId.ifBlank { provider.getDefaultModelId() }
            TtsTelemetryTracker.trackIfNeeded(providerId, effectiveModelId, text.length)

            // 5. 执行合成 (使用协程挂起)
            val result = withTimeoutOrNull(120_000L.milliseconds) {
                suspendCancellableCoroutine { continuation ->
                    activeContinuation = continuation

                    provider.synthesize(text, params, config, object : TtsSynthesisListener {
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

                            // 直接以 offset 分块写出，避免每 4KB 一次数组拷贝
                            val maxChunkSize = 4096
                            var offset = 0
                            while (offset < audioData.size) {
                                val chunkSize = minOf(maxChunkSize, audioData.size - offset)
                                callback.audioAvailable(audioData, offset, chunkSize)
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
                            synthesisErrorMessage.set(error)
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
                try { provider.stop() } catch (_: Exception) {}
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
                    TtsErrorCode.getErrorMessage(result, synthesisErrorMessage.get())
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
            stopForegroundServiceIfIdle()
            releaseWifiLock()
            releaseWakeLock()
        }
    }


    override fun onDestroy() {
        TtsLogger.i("TalkifyTtsService onDestroy")
        isStopped.set(true)
        try {
            currentProvider?.stop()
        } catch (e: android.os.RemoteException) {
            TtsLogger.w("Remote exception during provider stop, service may be disconnecting: ${e.message}")
        } catch (e: android.os.DeadObjectException) {
            TtsLogger.w("Provider connection lost during stop, service is being destroyed: ${e.message}")
        } catch (e: Exception) {
            TtsLogger.e("Unexpected error during provider stop", e)
        }
        try {
            currentProvider?.release()
        } catch (e: android.os.RemoteException) {
            TtsLogger.w("Remote exception during provider release: ${e.message}")
        } catch (e: android.os.DeadObjectException) {
            TtsLogger.w("Provider connection lost during release: ${e.message}")
        } catch (e: Exception) {
            TtsLogger.e("Unexpected error during provider release", e)
        }
        currentProvider = null
        currentConfig = null
        currentProviderId = null
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

        val continuation = activeContinuation
        if (continuation != null && continuation.isActive) {
            TtsLogger.d("onStop: cancelling active continuation")
            continuation.cancel()
        }
        activeContinuation = null

        try {
            currentProvider?.stop()
        } catch (e: android.os.RemoteException) {
            TtsLogger.w("Remote exception during provider stop in onStop: ${e.message}")
        } catch (e: android.os.DeadObjectException) {
            TtsLogger.w("Provider connection lost during onStop: ${e.message}")
        } catch (e: Exception) {
            TtsLogger.e("Unexpected error during provider stop in onStop", e)
        }
    }
}
