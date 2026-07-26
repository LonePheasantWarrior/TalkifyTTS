package com.github.lonepheasantwarrior.talkify.domain.model

/**
 * 供应商 ID 密封类
 *
 * 提供类型安全的供应商标识定义，使用密封类确保只有已定义的供应商可用。
 *
 * 字段语义：
 * - [providerId]：供应商唯一标识符，如 "aliyunBailian"、"volcengine"，用于内部路由与持久化
 * - [defaultModelId]：该供应商的默认模型标识符，如 "qwen3-tts"、"seed-tts-2.0"
 * - [provider]：供应商（服务提供商）展示名称，如 "阿里云百炼"、"火山引擎"
 *
 * 供应商 ID 字典参见 [doc/供应商ID字典.txt]。
 */
sealed class ProviderIds {
    /**
     * 火山引擎
     */
    data object Volcengine : ProviderIds() {
        override val providerId: String = "volcengine"
        override val defaultModelId: String = "seed-tts-2.0"
        override val provider: String = "火山引擎"
    }

    /**
     * 腾讯云
     */
    data object TencentCloud : ProviderIds() {
        override val providerId: String = "tencentCloud"
        override val defaultModelId: String = "tencent-tts"
        override val provider: String = "腾讯云"
    }

    /**
     * 阿里云百炼
     */
    data object AliyunBailian : ProviderIds() {
        override val providerId: String = "aliyunBailian"
        override val defaultModelId: String = "qwen3-tts-flash"
        override val provider: String = "阿里云百炼"
    }

    /**
     * Azure - 微软语音合成供应商
     */
    data object Azure : ProviderIds() {
        override val providerId: String = "azure"
        override val defaultModelId: String = "microsoft-tts"
        override val provider: String = "Azure"
    }

    /**
     * 小米 - MiMo 语音合成供应商
     */
    data object Xiaomi : ProviderIds() {
        override val providerId: String = "xiaomi"
        override val defaultModelId: String = "mimo-v2.5-tts"
        override val provider: String = "小米"
    }

    /**
     * MiniMax - 语音合成供应商
     */
    data object MiniMax : ProviderIds() {
        override val providerId: String = "miniMax"
        override val defaultModelId: String = "speech-2.8-turbo"
        override val provider: String = "MiniMax"
    }

    /**
     * 本地模型 - 离线 AI 语音合成
     */
    data object LocalModel : ProviderIds() {
        override val providerId: String = "localModel"
        override val defaultModelId: String = "vits-zh-aishell3"
        override val provider: String = "本地模型"
    }

    /**
     * 供应商唯一标识符，用于内部路由、注册表 key 和持久化键。
     */
    abstract val providerId: String

    /**
     * 该供应商的默认模型标识符（如 "qwen3-tts"、"seed-tts-2.0"）。
     */
    abstract val defaultModelId: String

    /**
     * 供应商（服务提供商）展示名称，用于 UI 呈现。
     */
    abstract val provider: String

    companion object {
        /**
         * 获取所有定义的供应商 ID 列表
         */
        val entries: List<ProviderIds> by lazy {
            listOf(Azure, Volcengine, TencentCloud, AliyunBailian, Xiaomi, MiniMax, LocalModel)
        }
    }
}

/**
 * 将供应商 ID 转换为 [TtsProvider] 数据类。
 *
 * 字段映射：
 * - [TtsProvider.id] ← [ProviderIds.providerId]（内部路由与持久化用）
 * - [TtsProvider.name] ← [ProviderIds.provider]（UI 主展示名）
 * - [TtsProvider.provider] ← [ProviderIds.defaultModelId]（UI 次展示模型标识）
 *
 * @return TtsProvider 实例
 */
fun ProviderIds.toTtsProvider(): TtsProvider {
    return TtsProvider(
        id = this.providerId,
        name = this.provider,
        provider = this.defaultModelId
    )
}
