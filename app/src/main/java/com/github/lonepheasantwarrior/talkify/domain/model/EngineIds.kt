package com.github.lonepheasantwarrior.talkify.domain.model

/**
 * 引擎 ID 密封类
 *
 * 提供类型安全的引擎标识定义
 * 使用密封类确保只有定义的引擎 ID 可用，防止无效的引擎 ID
 *
 * 引擎 ID 命名规范：`{provider}_{service}_{version}`
 * - provider: 服务提供商（如 ali_bailian）
 * - service: 服务名称（如 qwen3_tts）
 * - version: 版本号（可选）
 */
sealed class EngineIds {
    /**
     * 阿里云百炼 - 通义千问3语音合成引擎
     *
     * @property value 引擎唯一标识符：qwen3-tts
     * @property displayName 显示名称：通义千问3语音合成
     * @property provider 服务提供商：阿里云百炼
     */
    data object Qwen3Tts : EngineIds() {
        override val value: String = "qwen3-tts"
        override val displayName: String = "通义千问3语音合成"
        override val provider: String = "阿里云百炼"
    }

    /**
     * 引擎唯一标识符
     */
    abstract val value: String

    /**
     * 引擎显示名称
     */
    abstract val displayName: String

    /**
     * 服务提供商
     */
    abstract val provider: String

    companion object {
        /**
         * 获取所有定义的引擎 ID 列表
         */
        val entries: List<EngineIds> by lazy {
            listOf(Qwen3Tts)
        }
    }
}

/**
 * 将引擎 ID 转换为 TtsEngine 数据类
 *
 * @return TtsEngine 实例
 */
fun EngineIds.toTtsEngine(): TtsEngine {
    return TtsEngine(
        id = this.value,
        name = this.displayName,
        provider = this.provider
    )
}
