package com.github.lonepheasantwarrior.talkify

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.github.lonepheasantwarrior.talkify.domain.model.TtsProviderRegistry
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleState
import com.github.lonepheasantwarrior.talkify.domain.repository.MultiRoleRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.MultiRoleRepositoryProvider
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.SharedPreferencesAppConfigRepository
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.service.multirole.EffectiveTtsTargetResolver
import com.github.lonepheasantwarrior.talkify.service.multirole.MultiRoleRuntimeValidator
import com.github.lonepheasantwarrior.talkify.service.provider.TtsProviderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/**
 * TTS 数据检查 Activity
 *
 * 响应系统或其他应用的 CHECK_TTS_DATA 请求，返回**当前生效供应商**支持的语言列表。
 * 生效供应商与系统发现/合成入口使用同一决策：多角色开启且方案有效时使用方案
 * 绑定供应商，否则使用主界面普通供应商。方案音色/格式不兼容时回退普通单音色。
 *
 * 配置读取在后台线程完成并有界；Activity 完成前不会阻塞主线程等待磁盘。
 */
class TalkifyCheckDataActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        TtsLogger.d("CHECK_TTS_DATA: TalkifyCheckDataActivity started")
        super.onCreate(savedInstanceState)

        Thread {
            val result = runCatching { resolveEffectiveProvider() }
            if (result.isFailure) {
                TtsLogger.e("CHECK_TTS_DATA: resolve failed", result.exceptionOrNull())
            }
            val target = result.getOrNull()
            if (target == null || !target.configured) {
                TtsLogger.i("CHECK_TTS_DATA: effective provider is not configured, returning FAIL")
                finishWithResult(TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL, null)
                return@Thread
            }
            val supportedLanguages = target.languages
            // 客户端可携带 EXTRA_CHECK_VOICE_DATA_FOR 只询问特定 locale；
            // 未携带时报告全量支持语言。
            @Suppress("DEPRECATION")
            val requested = intent?.getStringExtra(TextToSpeech.Engine.EXTRA_CHECK_VOICE_DATA_FOR)
            val (available, unavailable) =
                partitionByRequestedLanguage(supportedLanguages, requested)
            val returnData = Intent()
            returnData.putStringArrayListExtra(
                TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available
            )
            returnData.putStringArrayListExtra(
                TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, unavailable
            )
            finishWithResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, returnData)
        }.start()
    }

    private data class Target(
        val providerId: String,
        val configured: Boolean,
        val languages: ArrayList<String>,
    )

    /**
     * 读取多角色快照并决策生效供应商，随后创建一次 Provider 校验配置、取语言表。
     */
    private fun resolveEffectiveProvider(): Target {
        val appConfigRepository = SharedPreferencesAppConfigRepository(this)
        val mainProviderId = appConfigRepository.getSelectedProviderId()
            ?: TtsProviderRegistry.defaultProvider.id
        val multiRoleRepository: MultiRoleRepository =
            MultiRoleRepositoryProvider.get(applicationContext)
        val state: MultiRoleState? = try {
            runBlocking {
                withTimeoutOrNull(400.milliseconds) { multiRoleRepository.snapshot() }
            }
        } catch (_: CancellationException) {
            null
        } catch (_: Exception) {
            null
        }
        var base = EffectiveTtsTargetResolver.resolve(mainProviderId, state)
        var provider = TtsProviderFactory.createProvider(base.providerId)
        var config = TtsProviderFactory.createConfigRepository(base.providerId, this)
            ?.getConfig(base.providerId)
        var configured = provider != null && config != null && provider.isConfigured(config)

        if (base.profile != null && provider == null) {
            // 方案供应商实例创建失败：回退主界面普通供应商
            base = EffectiveTtsTargetResolver.Target(
                providerId = mainProviderId,
                profile = null,
                settings = base.settings,
            )
            provider = TtsProviderFactory.createProvider(base.providerId)
            config = TtsProviderFactory.createConfigRepository(base.providerId, this)
                ?.getConfig(base.providerId)
            configured = provider != null && config != null && provider.isConfigured(config)
        }

        if (base.profile != null && provider != null) {
            val check = MultiRoleRuntimeValidator.validateWithProvider(
                base.profile, provider, config
            )
            if (!check.ok) {
                // 方案在发现阶段已失效：回退普通单音色，与合成入口一致
                provider.release()
                base = EffectiveTtsTargetResolver.Target(
                    providerId = mainProviderId,
                    profile = null,
                    settings = base.settings,
                )
                provider = TtsProviderFactory.createProvider(base.providerId)
                config = TtsProviderFactory.createConfigRepository(base.providerId, this)
                    ?.getConfig(base.providerId)
                configured = provider != null && config != null && provider.isConfigured(config)
            }
        }
        val languages = ArrayList(provider?.getSupportedLanguages()?.toList().orEmpty())
        try {
            provider?.release()
        } catch (_: Exception) {
        }
        return Target(base.providerId, configured, languages)
    }

    private fun finishWithResult(resultCode: Int, data: Intent?) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            setResult(resultCode, data)
            finish()
        }
    }

    /**
     * 按客户端询问的 locale 拆分可用/不可用语言
     */
    private fun partitionByRequestedLanguage(
        supported: ArrayList<String>,
        requestedTag: String?,
    ): Pair<ArrayList<String>, ArrayList<String>> {
        if (requestedTag.isNullOrBlank()) {
            return Pair(supported, arrayListOf())
        }
        val language = runCatching {
            Locale.forLanguageTag(requestedTag).getISO3Language()
        }.getOrDefault("")
            .ifEmpty { requestedTag.substringBefore('-').lowercase(Locale.US) }

        return if (language in supported) {
            Pair(arrayListOf(language), arrayListOf())
        } else {
            Pair(arrayListOf(), arrayListOf(language))
        }
    }
}
