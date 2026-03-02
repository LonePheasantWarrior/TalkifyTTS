package com.github.lonepheasantwarrior.talkify.service.engine

import android.content.Context
import com.github.lonepheasantwarrior.talkify.domain.repository.EngineConfigRepository
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.engine.repo.Qwen3TtsConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.engine.repo.Qwen3TtsVoiceRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.engine.repo.SeedTts2ConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.engine.repo.SeedTts2VoiceRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.engine.repo.TencentTtsConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.engine.repo.TencentTtsVoiceRepository
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.service.engine.impl.Qwen3TtsEngine
import com.github.lonepheasantwarrior.talkify.service.engine.impl.SeedTts2Engine
import com.github.lonepheasantwarrior.talkify.service.engine.impl.TencentTtsEngine

/**
 * TTS 引擎工厂
 *
 * 核心职责：作为所有引擎相关组件（Engine, ConfigRepo, VoiceRepo）的统一构建入口。
 * 设计目标：实现完全的插件化架构，业务层只需通过 ID 请求组件，无需感知具体实现类。
 */
object TtsEngineFactory {

    /**
     * 引擎组件构建器集合
     * 封装了创建 Engine、ConfigRepo、VoiceRepo 的工厂 lambda
     */
    private data class ComponentFactories(
        val createEngine: () -> TtsEngineApi,
        val createConfigRepo: (Context) -> EngineConfigRepository,
        val createVoiceRepo: (Context) -> VoiceRepository
    )

    @Volatile
    private var registry: Map<String, ComponentFactories>? = null

    private val lock = Any()

    /**
     * 根据引擎 ID 创建引擎实例
     */
    fun createEngine(engineId: String): TtsEngineApi? {
        val factories = getRegistry()[engineId] ?: run {
            TtsLogger.w("TtsEngineFactory: engine not found - $engineId")
            return null
        }
        return try {
            factories.createEngine()
        } catch (e: Exception) {
            TtsLogger.e("TtsEngineFactory: failed to create engine - $engineId", e)
            null
        }
    }

    /**
     * 创建引擎配置仓储
     */
    fun createConfigRepository(engineId: String, context: Context): EngineConfigRepository? {
        val factories = getRegistry()[engineId] ?: return null
        return try {
            factories.createConfigRepo(context)
        } catch (e: Exception) {
            TtsLogger.e("TtsEngineFactory: failed to create config repo - $engineId", e)
            null
        }
    }

    /**
     * 创建引擎声音仓储
     */
    fun createVoiceRepository(engineId: String, context: Context): VoiceRepository? {
        val factories = getRegistry()[engineId] ?: return null
        return try {
            factories.createVoiceRepo(context)
        } catch (e: Exception) {
            TtsLogger.e("TtsEngineFactory: failed to create voice repo - $engineId", e)
            null
        }
    }

    fun isRegistered(engineId: String): Boolean {
        return getRegistry().containsKey(engineId)
    }

    // --- 内部注册逻辑 ---

    private fun getRegistry(): Map<String, ComponentFactories> {
        return registry ?: synchronized(lock) {
            registry ?: initializeRegistry().also { registry = it }
        }
    }

    private fun initializeRegistry(): Map<String, ComponentFactories> {
        TtsLogger.d("TtsEngineFactory: initializing registry")
        return mapOf(
            Qwen3TtsEngine.ENGINE_ID to ComponentFactories(
                createEngine = { Qwen3TtsEngine() },
                createConfigRepo = { ctx -> Qwen3TtsConfigRepository(ctx) },
                createVoiceRepo = { ctx -> Qwen3TtsVoiceRepository(ctx) }
            ),
            SeedTts2Engine.ENGINE_ID to ComponentFactories(
                createEngine = { SeedTts2Engine() },
                createConfigRepo = { ctx -> SeedTts2ConfigRepository(ctx) },
                createVoiceRepo = { ctx -> SeedTts2VoiceRepository(ctx) }
            ),
            TencentTtsEngine.ENGINE_ID to ComponentFactories(
                createEngine = { TencentTtsEngine() },
                createConfigRepo = { ctx -> TencentTtsConfigRepository(ctx) },
                createVoiceRepo = { ctx -> TencentTtsVoiceRepository(ctx) }
            )
        ).also {
            TtsLogger.i("TtsEngineFactory: ${it.size} engines registered")
        }
    }
}
