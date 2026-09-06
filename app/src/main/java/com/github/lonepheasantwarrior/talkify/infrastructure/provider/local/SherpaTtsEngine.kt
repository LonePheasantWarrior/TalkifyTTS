package com.github.lonepheasantwarrior.talkify.infrastructure.provider.local

import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelInfo
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig
import java.io.File

/**
 * 语音合成结果
 *
 * @param audioData PCM 16bit 单声道音频字节数组
 * @param sampleRate 音频实际采样率（由 Sherpa-onnx 引擎返回，非注册表中的预设值）
 */
data class SynthesisResult(
    val audioData: ByteArray,
    val sampleRate: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SynthesisResult

        if (sampleRate != other.sampleRate) return false
        if (!audioData.contentEquals(other.audioData)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = sampleRate
        result = 31 * result + audioData.contentHashCode()
        return result
    }
}

/**
 * Sherpa-onnx 本地 TTS 推理引擎封装（ZipVoice 零样本流匹配架构）
 *
 * 封装 Sherpa-onnx 的 [OfflineTts]，提供：
 * - 惰性初始化（首次 synthesize 时加载模型）
 * - ZipVoice-Distill 零样本合成：音色由参考音频 + 逐字稿定义
 * - 文本→PCM 16bit 音频的本地推理
 * - 线程安全与资源释放
 *
 * @param modelInfo 目标模型元信息
 * @param modelDir 模型文件所在目录（下载目录）
 */
class SherpaTtsEngine(
    private val modelInfo: LocalModelInfo,
    private val modelDir: File
) {

    private val tag = "SherpaTtsEngine[${modelInfo.id}]"

    @Volatile
    private var tts: OfflineTts? = null

    @Volatile
    private var isInitialized = false

    @Volatile
    private var isReleased = false

    /**
     * 获取引擎当前使用的模型 ID
     */
    @Suppress("unused")
    fun getModelId(): String = modelInfo.id

    /**
     * 初始化 Sherpa-onnx 引擎
     *
     * 加载 ZipVoice-Distill 所需文件：
     * encoder.int8.onnx + decoder.int8.onnx + tokens.txt + lexicon.txt
     * + espeak-ng-data/（tarball 解压产物）+ vocos_24khz.onnx（单独下载）
     *
     * @throws IllegalStateException 模型文件缺失或初始化失败
     */
    @Synchronized
    fun initialize() {
        if (isInitialized || isReleased) return

        TtsLogger.i("Initializing Sherpa-onnx engine for: ${modelInfo.id}", tag = tag)
        checkModelFiles()

        try {
            val config = buildTtsConfig()
            tts = OfflineTts(config = config)
            isInitialized = true
            TtsLogger.i("Sherpa-onnx engine initialized successfully: ${modelInfo.id}", tag = tag)
        } catch (e: Exception) {
            TtsLogger.e("Failed to initialize Sherpa-onnx engine", throwable = e, tag = tag)
            throw IllegalStateException("Failed to initialize TTS engine: ${e.message}", e)
        }
    }

    /**
     * 执行语音合成
     *
     * 将输入文本转换为 PCM 16bit 单声道音频数据。
     * 首次调用会自动触发初始化（惰性加载）。
     *
     * @param text 输入文本
     * @param referenceAudio 参考音频浮点样本（决定合成音色）
     * @param referenceSampleRate 参考音频采样率
     * @param referenceText 参考音频逐字稿，必须与音频内容完全一致
     * @param speed 语速倍率（1.0 为正常速度）
     * @return [SynthesisResult] 包含 PCM 音频数据及实际采样率
     * @throws IllegalStateException 引擎未初始化或已释放
     */
    fun synthesize(
        text: String,
        referenceAudio: FloatArray,
        referenceSampleRate: Int,
        referenceText: String,
        speed: Float
    ): SynthesisResult {
        if (isReleased) throw IllegalStateException("Engine has been released")

        // 惰性初始化
        if (!isInitialized) {
            initialize()
        }

        val engine = tts ?: throw IllegalStateException("TTS engine not initialized")

        val genConfig = buildGenerationConfig(referenceAudio, referenceSampleRate, referenceText, speed)
        val audio = engine.generateWithConfig(text, genConfig)

        val actualSampleRate = audio.sampleRate
        val pcmData = floatArrayToPcm16(audio.samples)

        TtsLogger.d("Generated ${pcmData.size} bytes at ${actualSampleRate}Hz", tag = tag)

        return SynthesisResult(audioData = pcmData, sampleRate = actualSampleRate)
    }

    /**
     * 流式语音合成（基于 generateWithConfigAndCallback 回调机制）。
     *
     * 与 [synthesize] 不同，本方法不会等待全文本合成完毕再返回，
     * 而是通过 Sherpa-onnx 的底层回调机制，每生成一小段 PCM 采样（通常为一个句子）
     * 就立即触发 [onAudioChunk] 回调。
     *
     * 回调返回 `true` 继续合成，返回 `false` 提前中断（对应停止播放场景）。
     *
     * **必须在后台线程调用**（此方法内部包含阻塞式 JNI 调用）。
     *
     * @param text 待合成文本
     * @param referenceAudio 参考音频浮点样本（决定合成音色）
     * @param referenceSampleRate 参考音频采样率
     * @param referenceText 参考音频逐字稿
     * @param speed 语速倍率（0.5~2.0）
     * @param onAudioChunk 音频回调：(pcm16Data: ByteArray, sampleRate: Int) → Boolean
     * @return `true` 正常完成，`false` 被回调中断
     */
    fun synthesizeStream(
        text: String,
        referenceAudio: FloatArray,
        referenceSampleRate: Int,
        referenceText: String,
        speed: Float,
        onAudioChunk: (pcm16Data: ByteArray, sampleRate: Int) -> Boolean
    ): Boolean {
        if (isReleased) throw IllegalStateException("Engine has been released")

        if (!isInitialized) {
            initialize()
        }

        val engine = tts ?: throw IllegalStateException("TTS engine not initialized")
        val sampleRate = engine.sampleRate()

        val genConfig = buildGenerationConfig(referenceAudio, referenceSampleRate, referenceText, speed)

        var completed = true

        // 使用 Java 桥接类 SherpaCallbackBridge，以确保 JNI 层能正确找到
        // invoke([F)Ljava/lang/Integer; 方法签名。Kotlin lambda 编译后
        // 生成 invoke([F)I（原始 int），与 JNI 期望的装箱 Integer 签名
        // 不匹配，会触发 NoSuchMethodError 并导致 SIGABRT 崩溃。
        // Java 类通过 auto-boxing 产生正确的字节码签名。
        @Suppress("UNCHECKED_CAST")
        val callback = object : SherpaCallbackBridge() {
            override fun onSamples(samples: FloatArray): Int {
                val pcm16 = floatArrayToPcm16(samples)
                val shouldContinue = onAudioChunk(pcm16, sampleRate)
                if (!shouldContinue) {
                    completed = false
                }
                return if (shouldContinue) 1 else 0
            }
        } as (FloatArray) -> Int

        engine.generateWithConfigAndCallback(text, genConfig, callback)

        return completed
    }

    /**
     * 释放 ONNX Runtime 及 Native C++ 内存
     *
     * 释放后可重新初始化，但推荐调用方在切换模型时创建新实例。
     */
    @Synchronized
    fun release() {
        if (isReleased) return
        isReleased = true
        isInitialized = false

        try {
            tts?.release()
            TtsLogger.i("Sherpa-onnx engine released: ${modelInfo.id}", tag = tag)
        } catch (e: Exception) {
            TtsLogger.e("Error releasing Sherpa-onnx engine", throwable = e, tag = tag)
        } finally {
            tts = null
        }
    }

    // ---- 内部方法 ----

    /**
     * 校验所有必需的模型文件是否存在
     */
    private fun checkModelFiles() {
        if (!modelDir.exists() || !modelDir.isDirectory) {
            throw IllegalStateException("Model directory not found: ${modelDir.absolutePath}")
        }

        val requiredFiles = modelInfo.downloadFileInfo.values + modelInfo.requiredLocalFiles
        for (fileName in requiredFiles) {
            val file = File(modelDir, fileName)
            if (!file.exists() || file.length() <= 0) {
                throw IllegalStateException("Model file missing or empty: ${file.absolutePath}")
            }
        }
    }

    /**
     * 构建 ZipVoice 生成参数
     *
     * numSteps=4: 官方文档对 Distill 模型的推荐值（步数越少越快，音质略降）
     */
    private fun buildGenerationConfig(
        referenceAudio: FloatArray,
        referenceSampleRate: Int,
        referenceText: String,
        speed: Float
    ): GenerationConfig = GenerationConfig(
        speed = speed.coerceIn(0.5f, 2.0f),
        referenceAudio = referenceAudio,
        referenceSampleRate = referenceSampleRate,
        referenceText = referenceText,
        numSteps = 4
    )

    /**
     * 根据模型类型构建 Sherpa-onnx 配置
     */
    private fun buildTtsConfig(): OfflineTtsConfig {
        val espeakDataDir = File(modelDir, "espeak-ng-data")
        // sherpa-onnx C++ Validate() 强制要求 dataDir 非空：
        //   if (data_dir.empty()) { LOGE(...); return false; }
        // Validate() 返回 false 后 JNI 不抛异常，留下未初始化状态 → generate() 时 SIGSEGV
        // 因此必须在 Kotlin 层拦截，抛明确异常而不是传空 dataDir
        if (!espeakDataDir.isDirectory || !File(espeakDataDir, "phontab").exists()) {
            throw IllegalStateException(
                "espeak-ng-data not found at ${espeakDataDir.absolutePath}. " +
                "ZipVoice model requires espeak-ng-data for phoneme conversion. " +
                "Please re-download the model to restore this directory."
            )
        }

        val zipVoiceConfig = OfflineTtsZipVoiceModelConfig(
            tokens = resolveFilePath("tokens.txt"),
            encoder = resolveFilePath("encoder.int8.onnx"),
            decoder = resolveFilePath("decoder.int8.onnx"),
            vocoder = resolveFilePath("vocos_24khz.onnx"),
            dataDir = espeakDataDir.absolutePath,
            lexicon = resolveFilePath("lexicon.txt")
        )

        // numThreads=4: 官方 RTF 基准测试显示 4 线程较 2 线程提升约 30% 推理速度
        return OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                zipvoice = zipVoiceConfig,
                numThreads = 4,
                provider = "cpu",
                debug = false
            ),
            maxNumSentences = 1
        )
    }

    /**
     * 解析模型文件路径
     *
     * 优先查找精确文件名，否则取 downloadFileInfo 中映射的本地文件名
     */
    private fun resolveFilePath(expectedName: String): String {
        // 先尝试精确匹配
        val exactFile = File(modelDir, expectedName)
        if (exactFile.exists()) return exactFile.absolutePath

        // 根据元信息中文件名映射查找
        val mappedName = modelInfo.downloadFileInfo.values.find { it == expectedName }
        if (mappedName != null) {
            return File(modelDir, mappedName).absolutePath
        }

        // 回退：取目录中任意匹配扩展名的文件
        val fallback = modelDir.listFiles()?.find {
            it.nameWithoutExtension == expectedName.substringBeforeLast('.')
        }
        if (fallback != null) return fallback.absolutePath

        // 最终回退：仅拼接路径，让 Sherpa-onnx 自行报错
        return File(modelDir, expectedName).absolutePath
    }

    /**
     * 将归一化 FloatArray 音频样本转换为 PCM 16bit 小端字节数组
     *
     * Sherpa-onnx v1.13+ 输出 [-1.0, 1.0] 的浮点样本，
     * 需要转为 Android AudioTrack 期望的 PCM 16bit 格式。
     */
    private fun floatArrayToPcm16(samples: FloatArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val pcm = (samples[i] * 32767).toInt().coerceIn(-32768, 32767)
            bytes[i * 2] = (pcm and 0xFF).toByte()          // 低字节
            bytes[i * 2 + 1] = ((pcm shr 8) and 0xFF).toByte() // 高字节
        }
        return bytes
    }
}
