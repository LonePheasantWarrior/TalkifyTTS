package com.github.lonepheasantwarrior.talkify.domain.model

/**
 * 本地模型注册表
 *
 * 定义所有可用的本地 TTS 模型清单，作为模型元数据的单一真实来源。
 * 使用 HuggingFace 国内镜像 hf-mirror.com 作为默认下载源，
 * 保留 hf 原始地址作为备用源。
 *
 * 新增模型只需在此处添加一个 [LocalModelInfo] 条目即可。
 */
object LocalModelRegistry {

    /**
     * 默认 HuggingFace 镜像地址（国内加速）
     */
    const val DEFAULT_HF_MIRROR = "https://hf-mirror.com"

    /**
     * 备用原始 HuggingFace 地址
     */
    const val FALLBACK_HF_ORIGIN = "https://huggingface.co"

    /**
     * 获取默认下载基础 URL
     */
    fun getDefaultBaseUrl(): String = DEFAULT_HF_MIRROR

    /**
     * 获取备用下载基础 URL
     */
    fun getFallbackBaseUrl(): String = FALLBACK_HF_ORIGIN

    /**
     * VITS 普通话 (AISHELL3) 模型定义
     *
     * 来源：csukuangfj/vits-zh-aishell3
     * 架构：VITS
     * 语言：中文（普通话）
     */
    val VITS_ZH_AISHELL3 = LocalModelInfo(
        id = "vits-zh-aishell3",
        displayName = "VITS 普通话 (AISHELL3)",
        modelType = LocalModelType.VITS,
        description = "基于 AISHELL3 数据集训练的 VITS 中文普通话语音合成模型，音色自然流畅",
        downloadSizeBytes = 47_000_000L,
        downloadSizeDisplay = "~45 MB",
        md5 = "",  // 待实际下载后计算真实值
        downloadFileInfo = mapOf(
            "$DEFAULT_HF_MIRROR/csukuangfj/vits-zh-aishell3/resolve/main/vits-aishell3.onnx" to "model.onnx",
            "$DEFAULT_HF_MIRROR/csukuangfj/vits-zh-aishell3/resolve/main/tokens.txt" to "tokens.txt",
            "$DEFAULT_HF_MIRROR/csukuangfj/vits-zh-aishell3/resolve/main/lexicon.txt" to "lexicon.txt"
        ),
        voiceList = listOf(
            LocalModelVoice(voiceId = "aishell3_default", displayName = "默认女声", language = "zh")
        ),
        sampleRate = 22050,
        supportedLanguages = listOf("zh")
    )

    /**
     * VITS 粤语 (Xiaomai) 模型定义
     *
     * 来源：csukuangfj/vits-cantonese-hf-xiaomaiiwn
     * 架构：VITS
     * 语言：粤语
     */
    val VITS_CANTONESE_XIAOMAI = LocalModelInfo(
        id = "vits-cantonese-hf-xiaomai",
        displayName = "VITS 粤语 (Xiaomai)",
        modelType = LocalModelType.VITS,
        description = "基于 Xiaomai 数据集的 VITS 粤语语音合成模型",
        downloadSizeBytes = 52_000_000L,
        downloadSizeDisplay = "~50 MB",
        md5 = "",  // 待实际下载后计算真实值
        downloadFileInfo = mapOf(
            "$DEFAULT_HF_MIRROR/csukuangfj/vits-cantonese-hf-xiaomaiiwn/resolve/main/vits-cantonese-hf-xiaomaiiwn.onnx" to "model.onnx",
            "$DEFAULT_HF_MIRROR/csukuangfj/vits-cantonese-hf-xiaomaiiwn/resolve/main/tokens.txt" to "tokens.txt",
            "$DEFAULT_HF_MIRROR/csukuangfj/vits-cantonese-hf-xiaomaiiwn/resolve/main/lexicon.txt" to "lexicon.txt"
        ),
        voiceList = listOf(
            LocalModelVoice(voiceId = "cantonese_default", displayName = "默认粤语女声", language = "yue")
        ),
        sampleRate = 22050,
        supportedLanguages = listOf("yue")
    )

    /**
     * Kokoro-82M 模型定义（sherpa-onnx 适配版）
     *
     * 来源：csukuangfj/kokoro-multi-lang-v1_1（v1.1-zh, sherpa-onnx 专版）
     * 架构：KOKORO
     * 语言：中文 / 英文
     *
     * 注意：必须使用 csukuangfj 转换版，onnx-community 原始转换版
     * （tokenizer_config.json + 独立 voice.bin）不兼容 sherpa-onnx API。
     */
    val KOKORO_82M = LocalModelInfo(
        id = "kokoro-82m",
        displayName = "Kokoro-82M 中英混合",
        modelType = LocalModelType.KOKORO,
        description = "Kokoro-82M v1.1-zh 中英文混合 TTS 模型，支持 103 种音色",
        downloadSizeBytes = 450_000_000L,
        downloadSizeDisplay = "~440 MB",
        md5 = "",  // 待实际下载后计算真实值
        downloadFileInfo = mapOf(
            "$DEFAULT_HF_MIRROR/csukuangfj/kokoro-multi-lang-v1_1/resolve/main/model.onnx" to "model.onnx",
            "$DEFAULT_HF_MIRROR/csukuangfj/kokoro-multi-lang-v1_1/resolve/main/tokens.txt" to "tokens.txt",
            "$DEFAULT_HF_MIRROR/csukuangfj/kokoro-multi-lang-v1_1/resolve/main/voices.bin" to "voices.bin",
            "$DEFAULT_HF_MIRROR/csukuangfj/kokoro-multi-lang-v1_1/resolve/main/lexicon-zh.txt" to "lexicon-zh.txt",
            "$DEFAULT_HF_MIRROR/csukuangfj/kokoro-multi-lang-v1_1/resolve/main/lexicon-us-en.txt" to "lexicon-us-en.txt",
            "$DEFAULT_HF_MIRROR/csukuangfj/kokoro-multi-lang-v1_1/resolve/main/date-zh.fst" to "date-zh.fst",
            "$DEFAULT_HF_MIRROR/csukuangfj/kokoro-multi-lang-v1_1/resolve/main/number-zh.fst" to "number-zh.fst",
            "$DEFAULT_HF_MIRROR/csukuangfj/kokoro-multi-lang-v1_1/resolve/main/phone-zh.fst" to "phone-zh.fst"
        ),
        archiveAssets = mapOf(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/espeak-ng-data.tar.bz2" to ""
        ),
        voiceList = listOf(
            LocalModelVoice(voiceId = "3", displayName = "中文女声 001", language = "zh"),
            LocalModelVoice(voiceId = "4", displayName = "中文女声 002", language = "zh"),
            LocalModelVoice(voiceId = "7", displayName = "中文女声 005", language = "zh"),
            LocalModelVoice(voiceId = "18", displayName = "中文女声 026", language = "zh"),
            LocalModelVoice(voiceId = "38", displayName = "中文女声 071", language = "zh"),
            LocalModelVoice(voiceId = "50", displayName = "中文女声 086", language = "zh"),
            LocalModelVoice(voiceId = "58", displayName = "中文男声 009", language = "zh"),
            LocalModelVoice(voiceId = "67", displayName = "中文男声 025", language = "zh"),
            LocalModelVoice(voiceId = "76", displayName = "中文男声 045", language = "zh"),
            LocalModelVoice(voiceId = "96", displayName = "中文男声 089", language = "zh"),
            LocalModelVoice(voiceId = "102", displayName = "中文男声 100", language = "zh"),
            LocalModelVoice(voiceId = "0", displayName = "Maple (美式女声)", language = "en"),
            LocalModelVoice(voiceId = "1", displayName = "Sol (美式女声)", language = "en"),
            LocalModelVoice(voiceId = "2", displayName = "Vale (英式女声)", language = "en")
        ),
        sampleRate = 24000,
        supportedLanguages = listOf("zh", "en")
    )

    /**
     * 所有已注册的本地模型列表
     */
    val ALL_MODELS: List<LocalModelInfo> = listOf(
        VITS_ZH_AISHELL3,
        VITS_CANTONESE_XIAOMAI,
        KOKORO_82M
    )

    /**
     * 模型 ID → LocalModelInfo 映射（懒加载）
     */
    private val modelMap: Map<String, LocalModelInfo> by lazy {
        ALL_MODELS.associateBy { it.id }
    }

    /**
     * 根据模型 ID 获取模型元信息
     *
     * @param modelId 模型唯一标识符
     * @return 模型元信息，未找到时返回 null
     */
    fun getModel(modelId: String): LocalModelInfo? {
        return modelMap[modelId]
    }

    /**
     * 获取默认模型
     */
    fun getDefaultModel(): LocalModelInfo = VITS_ZH_AISHELL3
}
