package com.github.lonepheasantwarrior.talkify.service

import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.DeadObjectException
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.RemoteException
import android.os.SystemClock
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelConfig
import com.github.lonepheasantwarrior.talkify.domain.model.TtsProviderRegistry
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleState
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.ReadingProfile
import com.github.lonepheasantwarrior.talkify.domain.multirole.SegmentKind
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleValidator
import com.github.lonepheasantwarrior.talkify.domain.multirole.AnalyzedSegment
import com.github.lonepheasantwarrior.talkify.domain.multirole.MultiRoleParser
import com.github.lonepheasantwarrior.talkify.domain.multirole.PlanWarnings
import com.github.lonepheasantwarrior.talkify.domain.multirole.SpeechPlan
import com.github.lonepheasantwarrior.talkify.domain.multirole.SpeechSegment
import com.github.lonepheasantwarrior.talkify.domain.repository.AppConfigRepository
import com.github.lonepheasantwarrior.talkify.domain.repository.MultiRoleRepository
import com.github.lonepheasantwarrior.talkify.domain.repository.ProviderConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.app.notification.NotificationIds
import com.github.lonepheasantwarrior.talkify.infrastructure.app.notification.TalkifyNotificationHelper
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.MultiRoleRepositoryProvider
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.SharedPreferencesAppConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.TelemetryIds
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.TtsTelemetryTracker
import com.github.lonepheasantwarrior.talkify.service.delivery.SynthesisCallbackConsumer
import com.github.lonepheasantwarrior.talkify.service.delivery.SynthesisDeliveryBridge
import com.github.lonepheasantwarrior.talkify.service.multirole.EffectiveTtsTargetResolver
import com.github.lonepheasantwarrior.talkify.service.multirole.MultiRoleRuntimeValidator
import com.github.lonepheasantwarrior.talkify.service.multirole.SpeechPlanExecutor
import com.github.lonepheasantwarrior.talkify.service.provider.AudioConfig
import com.github.lonepheasantwarrior.talkify.service.provider.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.provider.TtsProviderApi
import com.github.lonepheasantwarrior.talkify.service.provider.TtsProviderFactory
import com.github.lonepheasantwarrior.talkify.service.provider.impl.LocalModelProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

/**
 * Talkify TTS 服务
 *
 * 实现 [TextToSpeechService]，作为系统 TTS 框架与本应用供应商之间的桥梁。
 *
 * 本版本在保留单音色外部行为的前提下，把交付层重构为：
 * - 供应商回调线程经 [SynthesisDeliveryBridge] 有界入队；
 * - `onSynthesizeText` 所在线程串行消费并调用 [SynthesisCallback]；
 * - 单音色与多角色共用 [SpeechPlanExecutor]，多角色逐段设置 `voiceOverride`；
 * - 所有系统发现入口（语言/音色/CHECK 数据）使用同一个生效配置解析器。
 *
 * 供应商生命周期使用引用计数池：并发 discovery 与合成切换不会被
 * release 掉另一个线程正在使用的实例。
 */
class TalkifyTtsService : TextToSpeechService() {

    private val isStopped = AtomicBoolean(false)

    private var wakeLock: PowerManager.WakeLock? = null

    private val wifiLock: WifiManager.WifiLock by lazy {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Talkify:WifiLock")
    }

    @Volatile
    private var isForegroundServiceRunning = false

    @Volatile
    private var appConfigRepository: AppConfigRepository? = null

    @Volatile
    private var multiRoleRepository: MultiRoleRepository? = null

    @Volatile
    private var activeRequest: ActiveRequest? = null

    /** onStop/onDestroy 递增；用于关闭“准备阶段尚未登记 activeRequest”的取消窗口 */
    private val stopGeneration = java.util.concurrent.atomic.AtomicLong(0)

    @Volatile
    private var preferredProviderId: String? = null

    /** 最近一次回退/错误通知时间，避免连续段落刷屏 */
    private val lastNoticeAt = HashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        TtsLogger.i("TalkifyTtsService onCreate")
        initializeWakeLock()
        initializeRepositories()
        initializePreferredProvider()
        warmUpLocalModelEngineIfApplicable()
    }

    // ==================== 生命周期/通知 ====================

    private fun initializeWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Talkify:TtsServiceWakeLock"
        ).apply { setReferenceCounted(false) }
        TtsLogger.d("WakeLock initialized")
    }

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

    private fun acquireWakeLock() {
        wakeLock?.let {
            if (!it.isHeld) {
                it.acquire(10 * 60 * 1000L)
                TtsLogger.d("WakeLock acquired")
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                TtsLogger.d("WakeLock released")
            }
        }
    }

    private fun enterForegroundState() {
        if (!isForegroundServiceRunning) {
            foregroundIdleHandler.removeCallbacks(exitForegroundRunnable)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NotificationIds.TTS_PLAYBACK,
                        TalkifyNotificationHelper.buildForegroundWithNotification(this),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(
                        NotificationIds.TTS_PLAYBACK,
                        TalkifyNotificationHelper.buildForegroundWithNotification(this)
                    )
                }
                isForegroundServiceRunning = true
                TtsLogger.d("Foreground service started")
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is android.app.ForegroundServiceStartNotAllowedException
                ) {
                    TtsLogger.w("Cannot start foreground service from background, continuing without foreground status")
                } else {
                    TtsLogger.w("Failed to start foreground service: ${e.message}")
                    TalkifyNotificationHelper.sendSystemNotification(
                        this, getString(R.string.tts_error_foreground_service_failed)
                    )
                }
                isForegroundServiceRunning = false
            }
        }
    }

    private fun exitForegroundState() {
        if (isForegroundServiceRunning) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundServiceRunning = false
            TtsLogger.d("Foreground service stopped")
        }
    }

    private fun scheduleForegroundIdleExit() {
        if (isStopped.get()) return
        foregroundIdleHandler.removeCallbacks(exitForegroundRunnable)
        foregroundIdleHandler.postDelayed(exitForegroundRunnable, FOREGROUND_IDLE_EXIT_DELAY_MS)
    }

    private val foregroundIdleHandler = Handler(Looper.getMainLooper())
    private val exitForegroundRunnable = Runnable { exitForegroundState() }

    /** 去重后的系统通知（同一 key 60s 内只发一次） */
    private fun notifyOnce(key: String, message: String) {
        synchronized(lastNoticeAt) {
            val now = SystemClock.elapsedRealtime()
            val last = lastNoticeAt[key]
            if (last != null && now - last < NOTICE_DEDUPE_MS) return
            lastNoticeAt[key] = now
        }
        TalkifyNotificationHelper.sendSystemNotification(this, message)
    }

    private companion object {
        const val FOREGROUND_IDLE_EXIT_DELAY_MS = 10_000L
        const val NOTICE_DEDUPE_MS = 60_000L
        const val REQUEST_TIMEOUT_MS = 120_000L
        const val MULTI_ROLE_READ_TIMEOUT_MS = 400L
    }

    // ==================== 仓储 ====================

    private val providerConfigRepositoryMap: MutableMap<String, ProviderConfigRepository> =
        ConcurrentHashMap()

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

    private fun getConfig(providerId: String): BaseProviderConfig? =
        getProviderConfigRepository(providerId)?.getConfig(providerId)

    private fun initializeRepositories() {
        TtsLogger.d("Initializing repositories")
        try {
            appConfigRepository = SharedPreferencesAppConfigRepository(applicationContext)
            multiRoleRepository = MultiRoleRepositoryProvider.get(applicationContext)
            TtsLogger.i("Repositories initialized successfully")
        } catch (e: Exception) {
            TtsLogger.e("Failed to initialize repositories", e)
            notifyOnce("init_failed", getString(R.string.tts_error_init_failed))
        }
    }

    private fun mainProviderId(): String =
        appConfigRepository?.getSelectedProviderId() ?: TtsProviderRegistry.defaultProvider.id

    /**
     * 请求/发现入口读取多角色配置的不可变快照。
     *
     * DataStore 是唯一数据源；这里只做有界同步等待（合成线程/ binder 线程不可无限阻塞）。
     * 超时返回 null，由调用方按“设置不可读”回退，不维护自行失效的进程内缓存。
     */
    private fun readMultiRoleStateBlocking(): MultiRoleState? {
        val repository = multiRoleRepository ?: return null
        return try {
            runBlocking {
                withTimeoutOrNull(MULTI_ROLE_READ_TIMEOUT_MS.milliseconds) {
                    repository.snapshot()
                }
            }
        } catch (_: CancellationException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    // ==================== 供应商池 ====================

    private class ProviderEntry(
        val id: String,
        val provider: TtsProviderApi,
    ) {
        var refs: Int = 0
        var retired: Boolean = false
        var released: Boolean = false
    }

    private class ProviderLease(val entry: ProviderEntry) {
        val provider: TtsProviderApi get() = entry.provider
    }

    private val providerLock = Any()
    private val providerEntries = HashMap<String, ProviderEntry>()

    private fun acquireProvider(providerId: String): ProviderLease? {
        synchronized(providerLock) {
            val existing = providerEntries[providerId]
            if (existing != null) {
                existing.refs++
                return ProviderLease(existing)
            }
            if (TtsProviderRegistry.getProvider(providerId) == null) {
                TtsLogger.e("Provider not found in registry: $providerId")
                return null
            }
            val provider = TtsProviderFactory.createProvider(providerId) ?: return null
            val entry = ProviderEntry(providerId, provider)
            entry.refs = 1
            providerEntries[providerId] = entry
            return ProviderLease(entry)
        }
    }

    private fun releaseProvider(lease: ProviderLease) {
        val toRelease: ProviderEntry?
        synchronized(providerLock) {
            val entry = lease.entry
            if (entry.refs > 0) entry.refs--
            toRelease = if (entry.refs == 0 && (entry.retired || entry.id != preferredProviderId)) {
                providerEntries.remove(entry.id)
                entry.released = true
                entry
            } else {
                null
            }
        }
        toRelease?.let { releaseEntry(it) }
    }

    private fun markPreferred(providerId: String) {
        val toRelease = ArrayList<ProviderEntry>()
        synchronized(providerLock) {
            preferredProviderId = providerId
            val iterator = providerEntries.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next().value
                if (entry.id != providerId && entry.refs == 0) {
                    iterator.remove()
                    entry.released = true
                    toRelease += entry
                }
            }
        }
        toRelease.forEach { releaseEntry(it) }
    }

    private fun releaseEntry(entry: ProviderEntry) {
        if (entry.released && entry.refs > 0) return
        try {
            entry.provider.stop()
        } catch (_: Exception) {
        }
        try {
            entry.provider.release()
        } catch (_: Exception) {
        }
    }

    private fun releaseAllProviders() {
        val toRelease = ArrayList<ProviderEntry>()
        synchronized(providerLock) {
            val iterator = providerEntries.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next().value
                if (entry.refs == 0) {
                    iterator.remove()
                    entry.released = true
                    toRelease += entry
                } else {
                    entry.retired = true
                }
            }
        }
        toRelease.forEach { releaseEntry(it) }
    }

    private fun initializePreferredProvider() {
        val base = EffectiveTtsTargetResolver.resolve(mainProviderId(), readMultiRoleStateBlocking())
        val lease = acquireProvider(base.providerId) ?: run {
            TtsLogger.e("Failed to create provider: ${base.providerId}")
            return
        }
        try {
            markPreferred(base.providerId)
            TtsLogger.i("Preferred provider initialized: ${base.providerId}")
        } finally {
            releaseProvider(lease)
        }
    }

    private fun warmUpLocalModelEngineIfApplicable() {
        val base = EffectiveTtsTargetResolver.resolve(mainProviderId(), readMultiRoleStateBlocking())
        val lease = acquireProvider(base.providerId) ?: return
        try {
            val provider = lease.provider as? LocalModelProvider ?: return
            val config = getConfig(base.providerId) as? LocalModelConfig
            val modelId = config?.modelId?.takeIf { it.isNotBlank() }
                ?: provider.getDefaultModelId()
            try {
                provider.warmUp(modelId)
            } catch (e: Exception) {
                TtsLogger.w("Local engine warm-up skipped: ${e.message}")
            }
        } finally {
            releaseProvider(lease)
        }
    }

    // ==================== 生效配置解析 ====================

    private data class DiscoveryTarget(
        val provider: TtsProviderApi,
        val config: BaseProviderConfig?,
        val profile: ReadingProfile?,
    )

    private data class RequestTarget(
        val providerId: String,
        val lease: ProviderLease?,
        val config: BaseProviderConfig?,
        val profile: ReadingProfile?,
        val settings: com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleSettings?,
        val audioConfig: AudioConfig?,
        val fallback: EffectiveTtsTargetResolver.Fallback?,
    )

    private fun withDiscoveryTarget(
        block: (DiscoveryTarget) -> Unit,
    ) {
        try {
            val base = EffectiveTtsTargetResolver.resolve(mainProviderId(), readMultiRoleStateBlocking())
            var lease: ProviderLease? = null
            var profile: ReadingProfile? = null
            var config: BaseProviderConfig? = null
            try {
                if (base.profile != null) {
                    val candidate = acquireProvider(base.profile.providerId)
                    val candidateConfig = getConfig(base.profile.providerId)
                    val runtime = if (candidate != null) {
                        try {
                            MultiRoleRuntimeValidator.validateWithProvider(
                                base.profile, candidate.provider, candidateConfig
                            )
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                    if (candidate != null && runtime?.ok == true) {
                        lease = candidate
                        profile = base.profile
                        config = candidateConfig
                        markPreferred(base.profile.providerId)
                    } else {
                        candidate?.let { releaseProvider(it) }
                    }
                }
                if (lease == null) {
                    val mainId = mainProviderId()
                    val mainLease = acquireProvider(mainId) ?: return
                    lease = mainLease
                    config = getConfig(mainId)
                    markPreferred(mainId)
                }
                block(DiscoveryTarget(lease.provider, config, profile))
            } finally {
                lease?.let { releaseProvider(it) }
            }
        } catch (e: Exception) {
            // binder 探测线程上的供应商/配置异常不能导致整个 TTS 引擎崩溃
            TtsLogger.e("Discovery target failed: ${e.javaClass.simpleName}", e)
        }
    }

    /**
     * 请求入口：先选方案绑定供应商并完整校验；不满足时回退主界面普通供应商单音色。
     * 主界面供应商未配置但方案供应商就绪时，方案优先成立（文档第 7.1 节）。
     */
    private fun acquireRequestTarget(): RequestTarget {
        val base = EffectiveTtsTargetResolver.resolve(mainProviderId(), readMultiRoleStateBlocking())
        val profile = base.profile
        if (profile == null) {
            base.fallback?.let { notifyFallbackOnce(it) }
            return acquireSingleTarget(base.providerId, base)
        }

        val profileLease = try {
            acquireProvider(profile.providerId)
        } catch (e: Exception) {
            TtsLogger.e("Failed to acquire profile provider: ${e.javaClass.simpleName}", e)
            null
        }
        if (profileLease == null) {
            val fallback = EffectiveTtsTargetResolver.Fallback(
                EffectiveTtsTargetResolver.FallbackReason.PROVIDER_UNKNOWN
            )
            notifyFallbackOnce(fallback)
            return acquireSingleTarget(mainProviderId(), base, fallback)
        }
        val config = try {
            getConfig(profile.providerId)
        } catch (e: Exception) {
            TtsLogger.e("Failed to read profile config: ${e.javaClass.simpleName}", e)
            null
        }
        val runtime = try {
            MultiRoleRuntimeValidator.validateWithProvider(
                profile, profileLease.provider, config
            )
        } catch (e: Exception) {
            TtsLogger.e("Profile runtime validation failed: ${e.javaClass.simpleName}", e)
            null
        }
        if (runtime == null || !runtime.ok) {
            releaseProvider(profileLease)
            val issue = runtime?.issues?.firstOrNull()
            val fallback = issue?.let {
                EffectiveTtsTargetResolver.Fallback(
                    EffectiveTtsTargetResolver.fallbackReasonFor(it), it
                )
            } ?: EffectiveTtsTargetResolver.Fallback(
                EffectiveTtsTargetResolver.FallbackReason.PROFILE_INVALID
            )
            notifyFallbackOnce(fallback)
            return acquireSingleTarget(mainProviderId(), base, fallback)
        }
        markPreferred(profile.providerId)
        return RequestTarget(
            providerId = profile.providerId,
            lease = profileLease,
            config = config,
            profile = profile,
            settings = base.settings,
            audioConfig = runtime.audioConfig,
            fallback = null,
        )
    }

    private fun acquireSingleTarget(
        providerId: String,
        base: EffectiveTtsTargetResolver.Target,
        fallback: EffectiveTtsTargetResolver.Fallback? = base.fallback,
    ): RequestTarget {
        val lease = try {
            acquireProvider(providerId)
        } catch (e: Exception) {
            TtsLogger.e("Failed to acquire single provider: ${e.javaClass.simpleName}", e)
            null
        }
        if (lease != null) markPreferred(providerId)
        val config = try {
            getConfig(providerId)
        } catch (e: Exception) {
            TtsLogger.e("Failed to read single config: ${e.javaClass.simpleName}", e)
            null
        }
        return RequestTarget(
            providerId = providerId,
            lease = lease,
            config = config,
            profile = null,
            settings = base.settings,
            audioConfig = null,
            fallback = fallback,
        )
    }

    private fun notifyFallbackOnce(fallback: EffectiveTtsTargetResolver.Fallback) {
        val key = "fallback_${fallback.reason.name}"
        val message = when (fallback.reason) {
            EffectiveTtsTargetResolver.FallbackReason.SETTINGS_UNREADABLE ->
                getString(R.string.multirole_fallback_settings_unreadable)
            EffectiveTtsTargetResolver.FallbackReason.PROVIDER_NOT_CONFIGURED,
            EffectiveTtsTargetResolver.FallbackReason.MODEL_NOT_READY ->
                getString(R.string.multirole_fallback_provider_unavailable)
            EffectiveTtsTargetResolver.FallbackReason.VOICE_UNAVAILABLE,
            EffectiveTtsTargetResolver.FallbackReason.OUTPUT_FORMAT_UNKNOWN,
            EffectiveTtsTargetResolver.FallbackReason.OUTPUT_FORMAT_MISMATCH ->
                getString(R.string.multirole_fallback_voice_incompatible)
            else -> getString(R.string.multirole_fallback_single_voice)
        }
        notifyOnce(key, message)
    }

    // ==================== 语言/音色发现 ====================

    override fun onIsLanguageAvailable(
        lang: String?,
        country: String?,
        variant: String?,
    ): Int {
        val locale = buildLocaleSafely(lang, country, variant)
        if (locale == null) {
            TtsLogger.w("onIsLanguageAvailable: invalid locale tags [lang: $lang, country: $country, variant: $variant]")
            return TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (isLanguageSupported(locale.language)) {
            return TextToSpeech.LANG_AVAILABLE
        }
        return TextToSpeech.LANG_NOT_SUPPORTED
    }

    fun isLanguageSupported(lang: String?): Boolean {
        if (lang == null) return false
        var supported = false
        withDiscoveryTarget { target ->
            supported = target.provider.getSupportedLanguages().contains(lang)
        }
        return supported
    }

    override fun onLoadLanguage(
        lang: String?,
        country: String?,
        variant: String?,
    ): Int {
        val locale = buildLocaleSafely(lang, country, variant)
        if (locale == null) return TextToSpeech.LANG_NOT_SUPPORTED
        return if (isLanguageSupported(locale.language)) {
            if (!country.isNullOrBlank()) {
                TextToSpeech.LANG_COUNTRY_AVAILABLE
            } else {
                TextToSpeech.LANG_AVAILABLE
            }
        } else {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

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

                else -> Locale.Builder().setLanguage(lang).build()
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    private val iso3ToIso2Region: Map<String, String> by lazy {
        buildMap {
            for (iso2 in Locale.getISOCountries()) {
                val iso3 = Locale.Builder().setRegion(iso2).build().isO3Country
                if (iso3.isNotEmpty()) put(iso3, iso2)
            }
        }
    }

    private fun convertToValidRegionCode(country: String): String {
        val normalized = country.uppercase(Locale.US)
        if (normalized.length != 3) return normalized
        return iso3ToIso2Region[normalized] ?: normalized
    }

    override fun onGetLanguage(): Array<String> {
        var result: Array<String> = emptyArray()
        withDiscoveryTarget { target ->
            val config = target.config
            if (config != null && target.provider.isConfigured(config)) {
                result = target.provider.getDefaultLanguage()
            }
        }
        return result
    }

    override fun onGetVoices(): List<Voice> {
        var voices: List<Voice> = emptyList()
        withDiscoveryTarget { target ->
            voices = target.provider.getSupportedVoices()
        }
        return voices
    }

    override fun onGetDefaultVoiceNameFor(
        lang: String?,
        country: String?,
        variant: String?,
    ): String? {
        var voiceName: String? = null
        withDiscoveryTarget { target ->
            val currentVoiceId = target.profile?.narratorVoice?.voiceId
                ?: target.config?.voiceId?.takeIf { it.isNotBlank() }
            voiceName = target.provider.getDefaultVoiceId(
                lang, country, variant, currentVoiceId
            )
        }
        return voiceName
    }

    private fun isVoiceIdCorrect(voiceId: String?): Int {
        var correct = false
        withDiscoveryTarget { target ->
            correct = target.provider.isVoiceIdCorrect(voiceId)
        }
        return if (correct) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    }

    override fun onIsValidVoiceName(voiceName: String?): Int = isVoiceIdCorrect(voiceName)

    override fun onLoadVoice(voiceName: String?): Int = isVoiceIdCorrect(voiceName)

    // ==================== 合成入口 ====================

    override fun onSynthesizeText(
        request: SynthesisRequest?,
        callback: SynthesisCallback?,
    ) {
        if (request == null || callback == null) {
            TtsLogger.e("onSynthesizeText: null request or callback")
            return
        }
        processRequestSynchronously(request, callback)
    }

    private fun processRequestSynchronously(
        request: SynthesisRequest,
        callback: SynthesisCallback,
    ) = runBlocking {
        val text = request.charSequenceText?.toString()
        val startNanos = System.nanoTime()
        val generationAtStart = stopGeneration.get()

        if (isStopped.get()) {
            reportCallbackError(callback, TextToSpeech.ERROR_INVALID_REQUEST)
            return@runBlocking
        }
        if (text.isNullOrBlank()) {
            val attempt = TtsTelemetryTracker.begin(mainProviderId(), 0)
            attempt.markIgnored("empty_text")
            attempt.report()
            callback.done()
            return@runBlocking
        }

        val target = try {
            acquireRequestTarget()
        } catch (e: Exception) {
            // 配置/供应商校验异常按单音色可用性失败处理，不能让 SynthThread 崩溃
            TtsLogger.e("Resolve request target failed: ${e.javaClass.simpleName}", e)
            RequestTarget(
                providerId = mainProviderId(),
                lease = null,
                config = null,
                profile = null,
                settings = null,
                audioConfig = null,
                fallback = null,
            )
        }
        val attempt = TtsTelemetryTracker.begin(target.providerId, text.length)
        if (target.profile != null) attempt.markMultiRole()
        val cancelled = AtomicBoolean(false)
        var provider: TtsProviderApi? = null
        var bridge: SynthesisDeliveryBridge? = null
        var requestScope: CoroutineScope? = null
        var producer: Job? = null
        var finalStatus: SynthesisCallbackConsumer.Status? = null

        try {
            attempt.updateTextLength(text.length)
            attempt.markPreparationStarted()

            acquireWakeLock()
            acquireWifiLock()
            enterForegroundState()

            val lease = target.lease
            val providerInstance = lease?.provider
            if (lease == null || providerInstance == null) {
                attempt.markError("no_provider", TtsTelemetryTracker.STAGE_PREPARATION)
                reportCallbackError(callback, TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_NO_PROVIDER))
                notifyOnce("no_provider", getString(R.string.tts_error_provider_not_ready))
                return@runBlocking
            }
            provider = providerInstance
            val config = target.config
            if (config == null) {
                attempt.markError("config_unavailable", TtsTelemetryTracker.STAGE_PREPARATION)
                reportCallbackError(callback, TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_NO_PROVIDER))
                return@runBlocking
            }
            if (!providerInstance.isConfigured(config)) {
                attempt.markError("not_configured", TtsTelemetryTracker.STAGE_PREPARATION)
                reportCallbackError(callback, TtsErrorCode.toAndroidError(TtsErrorCode.ERROR_PROVIDER_NOT_CONFIGURED))
                notifyOnce("not_configured", getString(R.string.tts_error_config_not_ready))
                return@runBlocking
            }

            if (generationAtStart != stopGeneration.get()) {
                attempt.markCancelled("stopped_before_start")
                callback.done()
                return@runBlocking
            }

            val profile = target.profile
            val plan = if (profile != null) {
                MultiRoleParser.parse(text, profile).plan
            } else {
                singleVoicePlan(text)
            }
            if (!plan.hasReadableContent || plan.segments.isEmpty()) {
                attempt.markError("no_readable_text", TtsTelemetryTracker.STAGE_SYNTHESIS)
                reportCallbackError(callback, TextToSpeech.ERROR_SYNTHESIS)
                return@runBlocking
            }

            if (profile == null && config.voiceId.isNotBlank() && !request.voiceName.isNullOrEmpty()) {
                if (config.voiceId != request.voiceName) {
                    TtsLogger.w("Synthesize: request voice differs from configured voice (ignored)")
                }
            }

            val model = TelemetryIds.model(
                providerInstance.getEffectiveModelId(config),
                providerInstance.getDefaultModelId(),
                providerInstance.getKnownModelIds(),
            )
            val voice = if (profile != null) {
                // 多角色没有单一“生效音色”；不伪装成某个角色声音
                TelemetryIds.multiRoleVoice()
            } else {
                TelemetryIds.voice(
                    providerInstance.getEffectiveVoiceId(config),
                    providerInstance::isVoiceIdCorrect,
                )
            }
            attempt.updateMetadata(model, voice)

            val params = SynthesisParams(
                pitch = request.pitch.toFloat(),
                speechRate = request.speechRate.toFloat(),
                language = request.language,
            )
            val expectedAudioConfig = target.audioConfig

            attempt.markSynthesisStarted()
            val deliveryBridge = SynthesisDeliveryBridge()
            bridge = deliveryBridge
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            requestScope = scope
            val executor = SpeechPlanExecutor(scope)
            val producerJob = executor.start(
                SpeechPlanExecutor.Config(
                    provider = providerInstance,
                    providerConfig = config,
                    params = params,
                    plan = plan,
                    bridge = deliveryBridge,
                    expectedAudioConfig = expectedAudioConfig,
                    timeoutMs = REQUEST_TIMEOUT_MS,
                ),
                isCancelled = { cancelled.get() || isStopped.get() },
            )
            producer = producerJob
            val active = ActiveRequest(
                bridge = deliveryBridge,
                producer = producerJob,
                scope = scope,
                provider = providerInstance,
                cancelled = cancelled,
            )
            activeRequest = active
            if (isStopped.get() || generationAtStart != stopGeneration.get()) {
                active.cancel()
            }

            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
            val remainingMs = (REQUEST_TIMEOUT_MS - elapsedMs).coerceAtLeast(0)
            val result = SynthesisCallbackConsumer(callback).deliver(
                deliveryBridge,
                remainingMs,
                isCancelled = { cancelled.get() || isStopped.get() },
            )
            finalStatus = result.status
            if (result.providerCompleted) attempt.markProviderCompleted()
            result.firstSampleRate?.let { attempt.markFirstAudio(it) }
            attempt.recordAudioDelivered(result.audioBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())

            when (result.status) {
                SynthesisCallbackConsumer.Status.SUCCESS -> {
                    val doneResult = callback.done()
                    if (doneResult != TextToSpeech.SUCCESS) {
                        // 框架已进入终态，不再补报 error()（Android 契约）
                        attempt.markFrameworkFailure("done_$doneResult")
                        attempt.markSuccess()
                    } else {
                        attempt.markSuccess()
                    }
                }

                SynthesisCallbackConsumer.Status.TIMEOUT -> {
                    attempt.markTimeout()
                    stopProviderQuietly(providerInstance)
                    reportCallbackError(callback, TextToSpeech.ERROR_NETWORK_TIMEOUT)
                    notifyOnce(
                        "request_timeout",
                        TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_NETWORK_TIMEOUT)
                    )
                }

                SynthesisCallbackConsumer.Status.ERROR -> {
                    attempt.markError("synthesis_error", TtsTelemetryTracker.STAGE_SYNTHESIS)
                    stopProviderQuietly(providerInstance)
                    reportCallbackError(callback, TextToSpeech.ERROR_SYNTHESIS)
                    val message = if (profile != null) {
                        getString(R.string.multirole_error_synthesis_failed)
                    } else {
                        TtsErrorCode.getErrorMessage(
                            TtsErrorCode.inferErrorCodeFromMessage(result.errorMessage.orEmpty()),
                            result.errorMessage,
                        )
                    }
                    notifyOnce("synthesis_error", message)
                }

                SynthesisCallbackConsumer.Status.FORMAT_MISMATCH -> {
                    attempt.markError("audio_format_mismatch", TtsTelemetryTracker.STAGE_SYNTHESIS)
                    stopProviderQuietly(providerInstance)
                    reportCallbackError(callback, TextToSpeech.ERROR_SYNTHESIS)
                    notifyOnce(
                        "format_mismatch",
                        getString(R.string.multirole_error_audio_format)
                    )
                }

                SynthesisCallbackConsumer.Status.NO_AUDIO -> {
                    attempt.markError("empty_audio", TtsTelemetryTracker.STAGE_SYNTHESIS)
                    stopProviderQuietly(providerInstance)
                    reportCallbackError(callback, TextToSpeech.ERROR_SYNTHESIS)
                }

                SynthesisCallbackConsumer.Status.FRAMEWORK_REJECTED -> {
                    attempt.markFrameworkFailure(
                        result.frameworkDetail ?: "framework_rejected"
                    )
                    attempt.markSuccess()
                    stopProviderQuietly(providerInstance)
                    reportCallbackError(callback, TextToSpeech.ERROR_SYNTHESIS)
                }

                SynthesisCallbackConsumer.Status.CANCELLED -> {
                    attempt.markCancelled()
                }
            }
        } catch (_: InterruptedException) {
            TtsLogger.w("Synthesis interrupted")
            attempt.markError("InterruptedException")
            reportCallbackError(callback, TextToSpeech.ERROR_SERVICE)
            Thread.currentThread().interrupt()
        } catch (_: CancellationException) {
            TtsLogger.i("Synthesis cancelled")
            attempt.markCancelled()
        } catch (e: Exception) {
            TtsLogger.e("Critical error in processRequestSynchronously", e)
            attempt.markError(e.javaClass.simpleName)
            reportCallbackError(callback, TextToSpeech.ERROR_SYNTHESIS)
            notifyOnce("critical_error", getString(R.string.multirole_error_synthesis_failed))
        } finally {
            cancelled.set(true)
            val currentActive = activeRequest
            if (currentActive != null) {
                currentActive.cancel()
            }
            activeRequest = null
            bridge?.cancel()
            producer?.cancel()
            requestScope?.cancel()
            if (finalStatus != SynthesisCallbackConsumer.Status.SUCCESS) {
                provider?.let { stopProviderQuietly(it) }
            }
            target.lease?.let { releaseProvider(it) }
            attempt.report()
            scheduleForegroundIdleExit()
            releaseWifiLock()
            releaseWakeLock()
        }
    }

    /**
     * 框架已进入终态（STOPPED/ERROR/done）后不得再回调 error()；
     * hasFinished() 是框架提供的唯一可靠终态查询。
     */
    private fun reportCallbackError(callback: SynthesisCallback, errorCode: Int) {
        try {
            if (!callback.hasFinished()) callback.error(errorCode)
        } catch (_: Exception) {
            // 框架 binder 已断开：回调失败不能拖垮合成线程
        }
    }

    private fun singleVoicePlan(text: String): SpeechPlan {
        val analysis = if (text.isEmpty()) {
            emptyList()
        } else {
            listOf(
                AnalyzedSegment(
                    start = 0,
                    endExclusive = text.length,
                    kind = SegmentKind.NARRATION,
                    roleId = null,
                    roleName = null,
                    defaultRole = false,
                    voiceId = "",
                    reason = null,
                )
            )
        }
        val segments = if (text.isEmpty()) {
            emptyList()
        } else {
            listOf(SpeechSegment(0, text.length, SegmentKind.NARRATION, null, ""))
        }
        return SpeechPlan(
            text = text,
            segments = segments,
            analysis = analysis,
            warnings = PlanWarnings(),
            hasReadableContent = MultiRoleValidator.hasReadableContent(text),
        )
    }

    private fun stopProviderQuietly(provider: TtsProviderApi) {
        try {
            provider.stop()
        } catch (_: Exception) {
        }
    }

    private class ActiveRequest(
        val bridge: SynthesisDeliveryBridge,
        val producer: Job,
        val scope: CoroutineScope,
        val provider: TtsProviderApi,
        val cancelled: AtomicBoolean,
    ) {
        fun cancel() {
            cancelled.set(true)
            bridge.cancel()
            producer.cancel()
            scope.cancel()
            try {
                provider.stop()
            } catch (_: Exception) {
            }
        }
    }

    private fun cancelActiveSynthesis() {
        activeRequest?.cancel()
        activeRequest = null
    }

    override fun onDestroy() {
        TtsLogger.i("TalkifyTtsService onDestroy")
        isStopped.set(true)
        stopGeneration.incrementAndGet()
        cancelActiveSynthesis()
        releaseAllProviders()
        appConfigRepository = null
        multiRoleRepository = null
        foregroundIdleHandler.removeCallbacks(exitForegroundRunnable)
        releaseWifiLock()
        releaseWakeLock()
        exitForegroundState()
        foregroundIdleHandler.removeCallbacks(exitForegroundRunnable)
        TtsTelemetryTracker.flushMetrics()
        super.onDestroy()
    }

    override fun onStop() {
        TtsLogger.d("onStop called")
        stopGeneration.incrementAndGet()
        cancelActiveSynthesis()
        TtsTelemetryTracker.flushMetrics()
    }
}
