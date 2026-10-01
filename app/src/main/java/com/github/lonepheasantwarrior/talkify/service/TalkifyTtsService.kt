package com.github.lonepheasantwarrior.talkify.service

import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.DeadObjectException
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.RemoteException
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelConfig
import com.github.lonepheasantwarrior.talkify.domain.model.TtsProviderRegistry
import com.github.lonepheasantwarrior.talkify.domain.repository.AppConfigRepository
import com.github.lonepheasantwarrior.talkify.domain.repository.ProviderConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.app.notification.NotificationIds
import com.github.lonepheasantwarrior.talkify.infrastructure.app.notification.TalkifyNotificationHelper
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.SharedPreferencesAppConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.TtsTelemetryTracker
import com.github.lonepheasantwarrior.talkify.service.TalkifyTtsService.Companion.FOREGROUND_IDLE_EXIT_DELAY_MS
import com.github.lonepheasantwarrior.talkify.service.provider.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.provider.TtsProviderApi
import com.github.lonepheasantwarrior.talkify.service.provider.TtsProviderFactory
import com.github.lonepheasantwarrior.talkify.service.provider.TtsSynthesisListener
import com.github.lonepheasantwarrior.talkify.service.provider.impl.LocalModelProvider
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
 * Talkify TTS 服务
 *
 * 实现 [TextToSpeechService]，作为系统 TTS 框架与本应用供应商之间的桥梁。
 * 负责：
 * 1. 根据用户选择的供应商 ID 获取对应的合成供应商
 * 2. 获取用户配置的供应商设置
 * 3. 委托供应商执行实际的语音合成
 *
 * 并发模型（与实现保持一致）：
 * - 系统框架经单个 SynthThread 串行分发 onSynthesizeText，本服务在请求内
 *   runBlocking 同步等待合成结果，单请求上限 120s（[processRequestSynchronously]）
 * - 语言/音色探测回调（onIsLanguageAvailable 等）由 binder 线程池并发调用，
 *   与 SynthThread 共享的供应商生命周期字段经 [providerLifecycleLock] 串行化
 *   （选择 + 创建 + 替换收敛在 [ensureProvider] 锁内）
 * - 合成期间经 startForeground 提升优先级并持有 WakeLock/WifiLock，
 *   请求结束进入空闲延迟退出前台，避免逐句通知闪烁
 *
 * @property isStopped 服务停止标志，使用 AtomicBoolean 保证线程安全
 * @property activeContinuation 在途合成的挂起点，onStop/onDestroy 经其取消阻塞中的请求
 * @property wakeLock 电源唤醒锁，防止合成过程中设备休眠
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

    // 以下生命周期字段在主线程（onCreate/onDestroy）、SynthThread（onSynthesizeText）
    // 与 binder 线程（onIsLanguageAvailable/onGetLanguage 等）间共享，须保证可见性
    @Volatile
    private var isForegroundServiceRunning = false

    @Volatile
    private var appConfigRepository: AppConfigRepository? = null

    @Volatile
    private var currentProvider: TtsProviderApi? = null

    @Volatile
    private var currentProviderId: String? = null

    @Volatile
    private var currentConfig: BaseProviderConfig? = null

    override fun onCreate() {
        super.onCreate()
        TtsLogger.i("TalkifyTtsService onCreate")
        initializeWakeLock()
        initializeRepositories()
        val providerInitSuccess = initializeProvider()
        TtsLogger.d("Provider initialization result: $providerInitSuccess")
        warmUpLocalModelEngineIfApplicable()
    }

    /**
     * 本地模型供应商场景下异步预热引擎
     *
     * ZipVoice 引擎首次初始化需加载约 200MB 模型（秒级），在服务创建时
     * （客户端刚绑定、通常尚未发起朗读）提前加载，消除首次朗读的首音延迟。
     */
    private fun warmUpLocalModelEngineIfApplicable() {
        val provider = currentProvider as? LocalModelProvider ?: return
        val modelId = (currentConfig as? LocalModelConfig)?.modelId
            ?.takeIf { it.isNotBlank() }
            ?: provider.getDefaultModelId()
        try {
            provider.warmUp(modelId)
        } catch (e: Exception) {
            // 预热失败静默忽略：首次合成时仍会走正常初始化路径
            TtsLogger.w("Local engine warm-up skipped: ${e.message}")
        }
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
     * 提升为前台服务
     *
     * 如果服务尚未处于前台状态，则 startForeground 并显示常驻通知
     * 使用 [TalkifyNotificationHelper.buildForegroundWithNotification] 构建通知
     *
     * 注意：Android 12+ 限制了后台启动前台服务，当第三方应用在后台调用 TTS 时
     * 可能抛出 ForegroundServiceStartNotAllowedException，此时我们会静默降级为非前台服务
     */
    private fun enterForegroundState() {
        if (!isForegroundServiceRunning) {
            // 新请求到来：撤销此前排定的空闲退出，避免刚退出又立即进入造成通知闪烁
            foregroundIdleHandler.removeCallbacks(exitForegroundRunnable)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NotificationIds.TTS_PLAYBACK,
                        TalkifyNotificationHelper.buildForegroundWithNotification(this),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NotificationIds.TTS_PLAYBACK, TalkifyNotificationHelper.buildForegroundWithNotification(this))
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
     * 退出前台状态
     *
     * 移除前台服务状态和关联的通知
     */
    private fun exitForegroundState() {
        if (isForegroundServiceRunning) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundServiceRunning = false
            TtsLogger.d("Foreground service stopped")
        }
    }

    /**
     * 空闲延迟退出前台
     *
     * 每次合成请求结束后调用（TTS 请求由系统串行调度，无并发请求）。
     * 不立即退出：连续朗读场景下逐句 startForeground/stopForeground 会让通知
     * 每句闪烁一次；改为最后一句结束后延迟 [FOREGROUND_IDLE_EXIT_DELAY_MS]
     * 再退出（P3-2），期间新请求到来则由 [enterForegroundState] 撤销排定任务
     */
    private fun scheduleForegroundIdleExit() {
        // N19-e：服务已销毁（onDestroy 置位 isStopped）后不再投递延迟任务——
        // 否则已销毁实例被多持 10s 并幽灵回调 stopForeground
        if (isStopped.get()) return
        foregroundIdleHandler.removeCallbacks(exitForegroundRunnable)
        foregroundIdleHandler.postDelayed(exitForegroundRunnable, FOREGROUND_IDLE_EXIT_DELAY_MS)
    }

    private val foregroundIdleHandler = Handler(Looper.getMainLooper())

    private val exitForegroundRunnable = Runnable { exitForegroundState() }

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
     * 在空闲时退出前台的延迟
     */
    private companion object {
        const val FOREGROUND_IDLE_EXIT_DELAY_MS = 10_000L
    }

    /**
     * 供应商配置仓储映射表
     * 根据供应商 ID 获取对应的配置仓储
     *
     * 读写来自多个 binder/Synth 线程，使用 ConcurrentHashMap 保证线程安全
     */
    private val providerConfigRepositoryMap: MutableMap<String, ProviderConfigRepository> =
        java.util.concurrent.ConcurrentHashMap()

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
     * 供应商生命周期锁
     *
     * "选择 + 创建 + 释放 + 替换"必须在锁内完成：语言/音色探测回调由 binder
     * 线程池并发进入（AOSP 不保证单线程），与 SynthThread 共享以下字段，
     * 无锁时并发线程可能双重创建/释放，或 release 掉另一线程正在使用的实例（P1-17）。
     * 字段保留 @Volatile 供锁外读优化。
     */
    private val providerLifecycleLock = Any()

    /**
     * 确保指定供应商已创建（幂等）
     *
     * 供 binder 探测路径、onCreate 与每次合成请求共同调用：
     * - 供应商未变且实例存活：快速返回，不做磁盘读
     * - 供应商变更或上次创建失败：在锁内释放旧实例并创建新实例
     *
     * @return 供应商是否就绪
     */
    private fun ensureProvider(providerId: String): Boolean {
        synchronized(providerLifecycleLock) {
            if (currentProviderId == providerId && currentProvider != null) {
                return true
            }

            TtsLogger.i("Provider changed from $currentProviderId to $providerId, reinitializing")
            currentProvider?.release()
            currentProvider = TtsProviderFactory.createProvider(providerId)

            if (currentProvider == null) {
                // P1-5：创建失败必须清空 currentProviderId。若保留 providerId，
                // 后续调用因 "currentProviderId == providerId" 跳过重建，
                // 所有合成只能报 "provider not ready"，只能重启服务恢复
                currentProviderId = null
                TtsLogger.e("Failed to create provider: $providerId")
                TalkifyNotificationHelper.sendSystemNotification(this, getString(R.string.tts_error_provider_init_failed))
                return false
            }
            currentProviderId = providerId

            // 供应商切换后强制丢弃配置仓储缓存，避免读到旧供应商的遗留配置
            providerConfigRepositoryMap.remove(providerId)

            if (TtsProviderRegistry.getProvider(providerId) == null) {
                TtsLogger.e("Provider not found in registry: $providerId")
                TalkifyNotificationHelper.sendSystemNotification(this, getString(R.string.tts_error_provider_not_found))
                return false
            }

            // 仅在创建路径加载配置；快速路径不读磁盘（binder 探测高频调用）
            currentConfig = getProviderConfigRepository(providerId)?.getConfig(providerId)
            TtsLogger.d("Provider initialized: ${currentProvider?.getProviderName()}")
            return true
        }
    }

    /**
     * 初始化 TTS 供应商
     *
     * 读取用户选择的供应商 ID 并经 [ensureProvider] 创建对应实例、加载配置。
     * 调用频率低（onCreate 与 provider 未就绪时的探测路径），故每次刷新 currentConfig
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

        if (!ensureProvider(providerId)) {
            return false
        }

        currentConfig = getProviderConfigRepository(providerId)?.getConfig(providerId)
        return true
    }

    override fun onIsLanguageAvailable(
        lang: String?,
        country: String?,
        variant: String?
    ): Int {
        val locale = buildLocaleSafely(lang, country, variant)
        if (locale == null) {
            TtsLogger.w("onIsLanguageAvailable: invalid locale tags [lang: $lang, country: $country, variant: $variant]")
            return TextToSpeech.LANG_NOT_SUPPORTED
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

        return currentProvider?.getSupportedLanguages()?.contains(lang) ?: false
    }

    override fun onLoadLanguage(
        lang: String?,
        country: String?,
        variant: String?
    ): Int {
        val locale = buildLocaleSafely(lang, country, variant)
        if (locale == null) {
            TtsLogger.w("onLoadLanguage: invalid locale tags [lang: $lang, country: $country, variant: $variant]")
            return TextToSpeech.LANG_NOT_SUPPORTED
        }

        if (isLanguageSupported(locale.language)) {
            return if (!country.isNullOrBlank()) {
                TtsLogger.d("onLoadLanguage: LANG_COUNTRY_AVAILABLE. lang: $lang, country: $country, variant: $variant")
                TextToSpeech.LANG_COUNTRY_AVAILABLE
            } else {
                TtsLogger.d("onLoadLanguage: LANG_AVAILABLE (no country). lang: $lang")
                TextToSpeech.LANG_AVAILABLE
            }
        }
        TtsLogger.w("onLoadLanguage: not support language [${locale.language}]")
        return TextToSpeech.LANG_NOT_SUPPORTED
    }

    /**
     * 由客户端传入的语言/国家/变体标签构建 Locale。
     *
     * 参数来自任意第三方 TTS 客户端，格式不受信任：[Locale.Builder] 的
     * setLanguage/setRegion/setVariant 遇到非法输入会抛 IllformedLocaleException
     * （RuntimeException），在 binder 线程上未捕获将导致整个引擎被系统禁用。
     * 因此全程捕获并返回 null（调用方按不支持处理）。
     */
    private fun buildLocaleSafely(lang: String?, country: String?, variant: String?): Locale? {
        if (lang == null) return null
        return try {
            when {
                country != null && variant != null -> Locale.Builder()
                    .setLanguage(lang)
                    .setRegion(convertToValidRegionCode(country))
                    .setVariant(variant)
                    .build()

                country != null -> Locale.Builder()
                    .setLanguage(lang)
                    .setRegion(convertToValidRegionCode(country))
                    .build()

                else -> Locale.Builder()
                    .setLanguage(lang)
                    .build()
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    /**
     * ISO 3166-1 alpha-3 → alpha-2 全量映射（懒构建，仅构建一次）
     *
     * AOSP 客户端调用 isLanguageAvailable 时传 `loc.getISO3Country()`（三字母码），
     * 而 [Locale.Builder.setRegion] 只接受两字母或数字 UN M.49 码；此前仅硬编码
     * 7 国，zh-TW/pt-BR 等常见 locale 直接抛 IllformedLocaleException 被吞为
     * LANG_NOT_SUPPORTED（P1-4）。
     */
    private val iso3ToIso2Region: Map<String, String> by lazy {
        buildMap {
            for (iso2 in Locale.getISOCountries()) {
                // 少数历史代码（如 AN）无 ISO3 对应，getISO3Country 返回空串，跳过
                val iso3 = Locale("", iso2).isO3Country
                if (iso3.isNotEmpty()) put(iso3, iso2)
            }
        }
    }

    /**
     * 转换国家代码为 [Locale.Builder] 接受的有效区域码
     *
     * 两字母 ISO 3166-1 alpha-2 与数字 UN M.49 码原样放行；三字母 alpha-3 码
     * 经全量映射转换为两字母；无法识别的输入原样返回，由
     * [buildLocaleSafely] 的异常捕获统一按不支持处理
     *
     * @param country 原始国家代码（来自任意第三方客户端，格式不受信任）
     * @return 标准化后的区域码
     */
    private fun convertToValidRegionCode(country: String): String {
        val normalized = country.uppercase(Locale.US)
        if (normalized.length != 3) {
            return normalized
        }
        return iso3ToIso2Region[normalized] ?: normalized
    }

    override fun onGetLanguage(): Array<String> {
        val provider = currentProvider
        if (provider == null) {
            TtsLogger.w("onGetLanguage: no provider available")
            return emptyArray()
        }

        if (!provider.isConfigured(currentConfig)) {
            TtsLogger.w("onGetLanguage: provider not configured")
            return emptyArray()
        }

        val defaultLanguage = provider.getDefaultLanguage()
        TtsLogger.d("onGetLanguage: return ${defaultLanguage.contentToString()}")
        return defaultLanguage
    }

    override fun onGetVoices(): List<Voice> {
        TtsLogger.d("onGetVoices: requested")
        // 框架契约要求非空返回：provider 未就绪时回退空列表，避免 binder 序列化 NPE
        return currentProvider?.getSupportedVoices().orEmpty()
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
        request: SynthesisRequest?,
        callback: SynthesisCallback?
    ) {
        if (request == null || callback == null) {
            TtsLogger.e("onSynthesizeText: null request or callback")
            return
        }

        // 惰性求值：release 下 d() 被门控后字符串不再求值，用户朗读原文不进系统日志（P1-13）
        TtsLogger.d { "onSynthesizeText: queuing text: ${request.charSequenceText}" }
        processRequestSynchronously(request, callback)
    }

    /**
     * 同步处理合成请求 (修复版)
     *
     * 直接在当前线程阻塞等待合成结果，符合 Android TTS Service 标准生命周期。
     * 修复了死锁隐患，并增加了 WifiLock 以保证网络流式传输的稳定性。
     */
    private fun processRequestSynchronously(
        request: SynthesisRequest,
        callback: SynthesisCallback
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
        enterForegroundState()

        // 语音合成遥测计时器：创建于合成开始前，各出口标记终态，finally 统一上报
        var attempt: TtsTelemetryTracker.Attempt? = null

        try {
            // 3. 准备供应商与配置
            // 每次合成前重新读取配置，确保获取最新的供应商选择；
            // 变更检测与创建收敛在 ensureProvider 的生命周期锁内（P1-5/P1-17）
            val selectedProviderId = appConfigRepository?.getSelectedProviderId()
                ?: TtsProviderRegistry.defaultProvider.id

            if (!ensureProvider(selectedProviderId)) {
                callback.error(TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_NO_PROVIDER))
                return@runBlocking
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
            // request.voiceName 为 Java 框架类平台类型（String!）：客户端仅 setLanguage
            // 未 setVoice 时为 null，必须用 isNullOrEmpty 判空而非 isNotBlank（P0-1）
            if (config.voiceId.isNotBlank() && !request.voiceName.isNullOrEmpty()) {
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

            // 语音合成遥测：创建计时器（完成后由 finally 统一上报）
            val effectiveModelId = config.modelId.ifBlank { provider.getDefaultModelId() }
            attempt = TtsTelemetryTracker.begin(providerId, effectiveModelId, config.voiceId, text.length)

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
                            // onStop 取消 continuation 后、provider 的 native 合成退出前，
                            // 残留回调可能继续产出音频（且新请求会重置 provider 的取消标志），
                            // 按 continuation 存活性丢弃过期音频，避免写给已废弃的请求
                            if (!continuation.isActive) {
                                TtsLogger.d("onAudioAvailable: request cancelled, dropping stale audio")
                                return
                            }

                            // 在收到第一个音频数据时初始化系统回调
                            if (!audioInitialized) {
                                audioInitialized = true
                                attempt.markFirstAudio(sampleRate)
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
                attempt.markTimeout()
                try { provider.stop() } catch (_: Exception) {}
                callback.error(TextToSpeech.ERROR_NETWORK_TIMEOUT)
                TalkifyNotificationHelper.sendSystemNotification(
                    this@TalkifyTtsService,
                    TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_NETWORK_TIMEOUT)
                )
            } else if (result != TtsErrorCode.SUCCESS) {
                // 发生错误
                attempt.markError(result.toString())
                callback.error(TtsErrorCode.toAndroidError(result))
                TalkifyNotificationHelper.sendSystemNotification(
                    this@TalkifyTtsService,
                    TtsErrorCode.getErrorMessage(result, synthesisErrorMessage.get())
                )
            } else {
                // 正常完成
                attempt.markSuccess()
                callback.done()
            }

        } catch (_: InterruptedException) {
            TtsLogger.w("Synthesis interrupted")
            attempt?.markError("InterruptedException")
            callback.error(TextToSpeech.ERROR_SERVICE)
            TalkifyNotificationHelper.sendSystemNotification(
                this@TalkifyTtsService,
                TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_GENERIC, "Synthesis interrupted")
            )
            Thread.currentThread().interrupt()
        } catch (_: CancellationException) {
            TtsLogger.i("Synthesis cancelled")
            attempt?.markCancelled()
        } catch (e: Exception) {
            TtsLogger.e("Critical error in processRequestSynchronously", e)
            attempt?.markError(e.javaClass.simpleName)
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
            TalkifyNotificationHelper.sendSystemNotification(
                this@TalkifyTtsService,
                TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_UNKNOWN, e.message)
            )
        } finally {
            // 7. 统一清理资源
            attempt?.report()
            activeContinuation = null
            scheduleForegroundIdleExit()
            releaseWifiLock()
            releaseWakeLock()
        }
    }


    /**
     * 取消在途合成的挂起点
     *
     * onStop 与 onDestroy 共用：不取消时 runBlocking 阻塞的 SynthThread 会
     * 持续等待到合成完成或 120s 超时，onDestroy 场景下还会向已销毁的服务
     * 回调"网络超时"并发出误导性通知（P1-18）
     */
    private fun cancelActiveSynthesis() {
        val continuation = activeContinuation
        if (continuation != null && continuation.isActive) {
            TtsLogger.d("cancelActiveSynthesis: cancelling active continuation")
            continuation.cancel()
        }
        activeContinuation = null
    }

    override fun onDestroy() {
        TtsLogger.i("TalkifyTtsService onDestroy")
        isStopped.set(true)
        cancelActiveSynthesis()
        try {
            currentProvider?.stop()
        } catch (e: DeadObjectException) {
            // 先捕获子类 DeadObjectException 再捕获父类 RemoteException，否则本分支不可达（P1-18）
            TtsLogger.w("Provider connection lost during stop, service is being destroyed: ${e.message}")
        } catch (e: RemoteException) {
            TtsLogger.w("Remote exception during provider stop, service may be disconnecting: ${e.message}")
        } catch (e: Exception) {
            TtsLogger.e("Unexpected error during provider stop", e)
        }
        try {
            currentProvider?.release()
        } catch (e: DeadObjectException) {
            TtsLogger.w("Provider connection lost during release: ${e.message}")
        } catch (e: RemoteException) {
            TtsLogger.w("Remote exception during provider release: ${e.message}")
        } catch (e: Exception) {
            TtsLogger.e("Unexpected error during provider release", e)
        }
        currentProvider = null
        currentConfig = null
        currentProviderId = null
        foregroundIdleHandler.removeCallbacks(exitForegroundRunnable)
        // 与合成 finally 成对补齐 WifiLock 释放，消除"销毁与在途 finally 之间"的泄漏窗口（P1-18）
        releaseWifiLock()
        releaseWakeLock()
        exitForegroundState()
        // N19-e：最后防线——在飞 finally 若在 isStopped 置位前已通过 scheduleForegroundIdleExit
        // 的校验，其 postDelayed 可能落在上方 removeCallbacks 之后，销毁路径末尾再补一次移除
        foregroundIdleHandler.removeCallbacks(exitForegroundRunnable)
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

        cancelActiveSynthesis()

        try {
            currentProvider?.stop()
        } catch (e: DeadObjectException) {
            TtsLogger.w("Provider connection lost during onStop: ${e.message}")
        } catch (e: RemoteException) {
            TtsLogger.w("Remote exception during provider stop in onStop: ${e.message}")
        } catch (e: Exception) {
            TtsLogger.e("Unexpected error during provider stop in onStop", e)
        }
    }
}
