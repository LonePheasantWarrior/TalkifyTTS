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
     * ZipVoice-Distill 模型定义（sherpa-onnx 适配版，int8 量化）
     *
     * 来源：k2-fsa/sherpa-onnx 官方 Releases（tts-models / vocoder-models 标签）
     * 架构：ZipVoice（流匹配零样本 TTS，音色由参考音频定义）
     * 语言：中文 / 英文
     *
     * 许可注意：模型权重基于 Emilia 数据集训练（CC-BY-NC-4.0），仅限非商用。
     *
     * 下载架构说明：模型文件 + espeak-ng-data + 参考音频均打包在官方 tarball 中，
     * 走 archiveAssets 一次性下载解压；声码器 vocos_24khz.onnx 单独下载。
     * GitHub 资源由下载服务自动叠加国内加速代理链（ghfast.top → gh-proxy.com → 源站），
     * 大陆直连 GitHub Releases 会被连接重置或仅有百 KB 级速率，不可直接使用。
     * 未采用「hf-mirror 拆分下载」的原因：k2-fsa/ZipVoice 的 HuggingFace 仓库
     * 仅有 encoder/decoder/tokens，缺少 lexicon、espeak-ng-data、vocoder 与参考
     * 音频（已全站排查确认），tarball 是唯一完整的官方发布物。
     *
     * 临时音色说明：test_wavs/leijun-1.wav 为官方测试音频（真人声纹），
     * 仅用于本地合成链路验证，正式发布前必须替换为授权干净的音色包。
     */
    val ZIPVOICE_DISTILL = LocalModelInfo(
        id = "zipvoice_distill",
        displayName = "ZipVoice-Distill 中英混合",
        description = "ZipVoice-Distill 零样本流匹配 TTS（int8 量化），自然度显著优于 VITS/Kokoro，音色由参考音频克隆",
        downloadSizeBytes = 204_000_000L,
        downloadSizeDisplay = "~200 MB",
        md5 = "",  // 待实际下载后计算真实值
        downloadFileInfo = mapOf(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/vocos_24khz.onnx" to "vocos_24khz.onnx"
        ),
        requiredLocalFiles = listOf(
            "encoder.int8.onnx",
            "decoder.int8.onnx",
            "tokens.txt",
            "lexicon.txt",
            "espeak-ng-data/phontab",
            "test_wavs/leijun-1.wav"
        ),
        archiveAssets = mapOf(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-zipvoice-distill-int8-zh-en-emilia.tar.bz2" to ""
        ),
        voiceList = listOf(
            LocalModelVoice(
                voiceId = "temp_leijun",
                displayName = "临时测试音色（勿发布）",
                language = "zh",
                referenceFileName = "test_wavs/leijun-1.wav",
                referenceText = "那还是三十六年前, 一九八七年. 我呢考上了武汉大学的计算机系."
            )
        ),
        sampleRate = 24000,
        supportedLanguages = listOf("zh", "en")
    )

    /**
     * 所有已注册的本地模型列表
     */
    val ALL_MODELS: List<LocalModelInfo> = listOf(
        ZIPVOICE_DISTILL
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
    fun getDefaultModel(): LocalModelInfo = ZIPVOICE_DISTILL
}
