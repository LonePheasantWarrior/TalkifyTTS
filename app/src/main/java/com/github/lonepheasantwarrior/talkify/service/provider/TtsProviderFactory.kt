package com.github.lonepheasantwarrior.talkify.service.provider

import android.content.Context
import com.github.lonepheasantwarrior.talkify.domain.model.ProviderIds
import com.github.lonepheasantwarrior.talkify.domain.repository.ProviderConfigRepository
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo.AliyunBailianConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo.AliyunBailianVoiceRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo.AzureConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo.AzureVoiceRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo.MiniMaxConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo.MiniMaxVoiceRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo.TencentCloudConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo.TencentCloudVoiceRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo.VolcengineConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo.VolcengineVoiceRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo.LocalModelConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo.LocalModelVoiceRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo.XiaomiConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo.XiaomiVoiceRepository
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.service.provider.impl.AliyunBailianProvider
import com.github.lonepheasantwarrior.talkify.service.provider.impl.AzureProvider
import com.github.lonepheasantwarrior.talkify.service.provider.impl.LocalModelProvider
import com.github.lonepheasantwarrior.talkify.service.provider.impl.MiniMaxProvider
import com.github.lonepheasantwarrior.talkify.service.provider.impl.TencentCloudProvider
import com.github.lonepheasantwarrior.talkify.service.provider.impl.VolcengineProvider
import com.github.lonepheasantwarrior.talkify.service.provider.impl.XiaomiProvider

/**
 * TTS 供应商工厂
 *
 * 核心职责：作为所有供应商相关组件（Provider, ConfigRepo, VoiceRepo）的统一构建入口。
 * 设计目标：实现完全的插件化架构，业务层只需通过 ID 请求组件，无需感知具体实现类。
 */
object TtsProviderFactory {

    /**
     * 供应商组件构建器集合
     * 封装了创建 Provider、ConfigRepo、VoiceRepo 的工厂 lambda
     */
    private data class ComponentFactories(
        val providerFactory: () -> TtsProviderApi,
        val createConfigRepo: (Context) -> ProviderConfigRepository,
        val createVoiceRepo: (Context) -> VoiceRepository
    )

    @Volatile
    private var registry: Map<String, ComponentFactories>? = null

    private val lock = Any()

    /**
     * 根据供应商 ID 创建供应商实例
     */
    fun createProvider(providerId: String): TtsProviderApi? {
        val factories = getRegistry()[providerId] ?: run {
            TtsLogger.w("TtsProviderFactory: provider not found - $providerId")
            return null
        }
        return try {
            factories.providerFactory()
        } catch (e: Exception) {
            TtsLogger.e("TtsProviderFactory: failed to create provider - $providerId", e)
            null
        }
    }

    /**
     * 创建供应商配置仓储
     */
    fun createConfigRepository(providerId: String, context: Context): ProviderConfigRepository? {
        val factories = getRegistry()[providerId] ?: return null
        return try {
            factories.createConfigRepo(context)
        } catch (e: Exception) {
            TtsLogger.e("TtsProviderFactory: failed to create config repo - $providerId", e)
            null
        }
    }

    /**
     * 创建供应商声音仓储
     */
    fun createVoiceRepository(providerId: String, context: Context): VoiceRepository? {
        val factories = getRegistry()[providerId] ?: return null
        return try {
            factories.createVoiceRepo(context)
        } catch (e: Exception) {
            TtsLogger.e("TtsProviderFactory: failed to create voice repo - $providerId", e)
            null
        }
    }

    fun isRegistered(providerId: String): Boolean {
        return getRegistry().containsKey(providerId)
    }

    // --- 内部注册逻辑 ---

    private fun getRegistry(): Map<String, ComponentFactories> {
        return registry ?: synchronized(lock) {
            registry ?: initializeRegistry().also { registry = it }
        }
    }

    private fun initializeRegistry(): Map<String, ComponentFactories> {
        TtsLogger.d("TtsProviderFactory: initializing registry")
        return mapOf(
            ProviderIds.AliyunBailian.providerId to ComponentFactories(
                providerFactory = { AliyunBailianProvider() },
                createConfigRepo = { ctx -> AliyunBailianConfigRepository(ctx) },
                createVoiceRepo = { ctx -> AliyunBailianVoiceRepository(ctx) }
            ),
            ProviderIds.Volcengine.providerId to ComponentFactories(
                providerFactory = { VolcengineProvider() },
                createConfigRepo = { ctx -> VolcengineConfigRepository(ctx) },
                createVoiceRepo = { ctx -> VolcengineVoiceRepository(ctx) }
            ),
            ProviderIds.TencentCloud.providerId to ComponentFactories(
                providerFactory = { TencentCloudProvider() },
                createConfigRepo = { ctx -> TencentCloudConfigRepository(ctx) },
                createVoiceRepo = { ctx -> TencentCloudVoiceRepository(ctx) }
            ),
            ProviderIds.Azure.providerId to ComponentFactories(
                providerFactory = { AzureProvider() },
                createConfigRepo = { ctx -> AzureConfigRepository(ctx) },
                createVoiceRepo = { ctx -> AzureVoiceRepository(ctx) }
            ),
            ProviderIds.Xiaomi.providerId to ComponentFactories(
                providerFactory = { XiaomiProvider() },
                createConfigRepo = { ctx -> XiaomiConfigRepository(ctx) },
                createVoiceRepo = { ctx -> XiaomiVoiceRepository(ctx) }
            ),
            ProviderIds.MiniMax.providerId to ComponentFactories(
                providerFactory = { MiniMaxProvider() },
                createConfigRepo = { ctx -> MiniMaxConfigRepository(ctx) },
                createVoiceRepo = { ctx -> MiniMaxVoiceRepository(ctx) }
            ),
            ProviderIds.LocalModel.providerId to ComponentFactories(
                providerFactory = { LocalModelProvider() },
                createConfigRepo = { ctx -> LocalModelConfigRepository(ctx) },
                createVoiceRepo = { ctx -> LocalModelVoiceRepository(LocalModelConfigRepository(ctx)) }
            )
        ).also {
            TtsLogger.i("TtsProviderFactory: ${it.size} providers registered")
        }
    }
}
