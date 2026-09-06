package com.github.lonepheasantwarrior.talkify.domain.model

/**
 * 本地模型下载状态枚举
 */
enum class ModelDownloadStatus {
    /** 尚未下载 */
    NOT_DOWNLOADED,
    /** 正在下载中 */
    DOWNLOADING,
    /** 已下载且完整性校验通过，可用 */
    DOWNLOADED,
    /** 下载错误 */
    ERROR
}

/**
 * 本地模型支持的单个音色
 *
 * ZipVoice 类零样本模型的"音色"由一段参考音频定义，用户侧无感知（体验仍是选音色→合成）。
 *
 * @param voiceId 音色唯一标识符
 * @param displayName 音色展示名称
 * @param language 语言代码
 * @param referenceFileName 参考音频文件在模型目录内的相对路径（如 "test_wavs/leijun-1.wav"）
 * @param referenceText 参考音频的逐字稿，必须与音频内容完全一致，否则克隆质量明显下降
 */
data class LocalModelVoice(
    val voiceId: String,
    val displayName: String,
    val language: String,
    val referenceFileName: String = "",
    val referenceText: String = ""
)

/**
 * 本地模型完整元信息
 *
 * 定义一个可下载的本地 TTS 模型的全部元数据：
 * - 基本信息（ID、名称、描述）
 * - 下载信息（大小、MD5、文件列表）
 * - 运行时参数（采样率、音色列表、支持语言）
 *
 * @param id 模型唯一标识符，如 "zipvoice_distill"
 * @param displayName 面向用户的模型展示名称
 * @param description 一句话描述
 * @param downloadSizeBytes 下载包总字节数
 * @param downloadSizeDisplay 下载大小的用户友好展示，如 "~45 MB"
 * @param md5 MD5 校验值（下载后验证完整性）
 * @param downloadFileInfo URL → 本地文件名映射
 * @param requiredLocalFiles 下载/解压完成后模型目录内必须存在的关键文件（相对路径），
 *   用于下载状态判定与合成前校验。archiveAssets 解压产物不经过 downloadFileInfo，
 *   需在此声明（如 tarball 内的 encoder.int8.onnx）
 * @param voiceList 该模型支持的音色列表
 * @param sampleRate 输出音频采样率（Hz）
 * @param supportedLanguages 支持的语言代码列表
 */
data class LocalModelInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val downloadSizeBytes: Long,
    val downloadSizeDisplay: String,
    val md5: String,
    val downloadFileInfo: Map<String, String>,
    val requiredLocalFiles: List<String> = emptyList(),
    val voiceList: List<LocalModelVoice>,
    val sampleRate: Int,
    val supportedLanguages: List<String>,
    /**
     * 归档资源 URL → 解压目标子目录 映射
     * 支持 tar.bz2 格式的归档文件下载与解压。
     * 例如: "https://.../espeak-ng-data.tar.bz2" to "espeak-ng-data"
     * value 为 "" 表示解压到模型根目录。
     */
    val archiveAssets: Map<String, String> = emptyMap()
)
