package com.github.lonepheasantwarrior.talkify.infrastructure.provider.local

import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelInfo
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelType
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
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
)

/**
 * Sherpa-onnx 本地 TTS 推理引擎封装
 *
 * 封装 Sherpa-onnx 的 [OfflineTts]，提供：
 * - 惰性初始化（首次 synthesize 时加载模型）
 * - VITS / Kokoro 双架构支持
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
    fun getModelId(): String = modelInfo.id

    /**
     * 初始化 Sherpa-onnx 引擎
     *
     * 根据 [LocalModelType] 选择对应的配置模式：
     * - VITS：加载 model.onnx + tokens.txt + lexicon.txt
     * - KOKORO：加载 model.onnx + tokens.txt + voices.bin + lexicon-zh.txt + espeak-ng-data/
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
     * @param voiceId 目标音色 ID（Kokoro 模型支持的音色，VITS 忽略此参数）
     * @param speed 语速倍率（1.0 为正常速度）
     * @return [SynthesisResult] 包含 PCM 音频数据及实际采样率
     * @throws IllegalStateException 引擎未初始化或已释放
     */
    fun synthesize(text: String, voiceId: String?, speed: Float): SynthesisResult {
        if (isReleased) throw IllegalStateException("Engine has been released")

        // 惰性初始化
        if (!isInitialized) {
            initialize()
        }

        val engine = tts ?: throw IllegalStateException("TTS engine not initialized")

        val audio = engine.generate(
            text = text,
            sid = if (modelInfo.modelType == LocalModelType.KOKORO) voiceId?.toIntOrNull() ?: 0 else 0,
            speed = speed.coerceIn(0.5f, 2.0f)
        )

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
     * 就立即触发 [onAudioChunk] 回调。首包音频可在 300-500ms 内到达。
     *
     * 回调返回 `true` 继续合成，返回 `false` 提前中断（对应停止播放场景）。
     *
     * **必须在后台线程调用**（此方法内部包含阻塞式 JNI 调用）。
     *
     * @param text 待合成文本
     * @param voiceId Kokoro 音色 ID（VITS 忽略此参数）
     * @param speed 语速倍率（0.5~2.0）
     * @param onAudioChunk 音频回调：(pcm16Data: ByteArray, sampleRate: Int) → Boolean
     * @return `true` 正常完成，`false` 被回调中断
     */
    fun synthesizeStream(
        text: String,
        voiceId: String?,
        speed: Float,
        onAudioChunk: (pcm16Data: ByteArray, sampleRate: Int) -> Boolean
    ): Boolean {
        if (isReleased) throw IllegalStateException("Engine has been released")

        if (!isInitialized) {
            initialize()
        }

        val engine = tts ?: throw IllegalStateException("TTS engine not initialized")
        val sampleRate = engine.sampleRate()

        val genConfig = GenerationConfig(
            sid = if (modelInfo.modelType == LocalModelType.KOKORO) voiceId?.toIntOrNull() ?: 0 else 0,
            speed = speed.coerceIn(0.5f, 2.0f)
        )

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

        for (fileName in modelInfo.downloadFileInfo.values) {
            val file = File(modelDir, fileName)
            if (!file.exists() || file.length() <= 0) {
                throw IllegalStateException("Model file missing or empty: ${file.absolutePath}")
            }
        }
    }

    /**
     * 根据模型类型构建 Sherpa-onnx 配置
     */
    private fun buildTtsConfig(): OfflineTtsConfig {
        val modelConfig = when (modelInfo.modelType) {
            LocalModelType.VITS -> buildVitsConfig()
            LocalModelType.KOKORO -> buildKokoroConfig()
        }

        return OfflineTtsConfig(
            model = modelConfig,
            maxNumSentences = 1
            // ruleFsts: v1.13.1 期望单个 .fst 文件路径，不能传目录；
            // 我们下载的 date-zh.fst / number-zh.fst / phone-zh.fst 是独立规则文件，
            // 无法通过此参数直接使用，留空以跳过 FST 文本正则化。
        )
    }

    /**
     * 构建 VITS 模型配置
     *
     * numThreads=4: 官方 RTF 基准测试显示 4 线程较 2 线程提升约 30% 推理速度
     */
    private fun buildVitsConfig(): OfflineTtsModelConfig {
        val vitsConfig = OfflineTtsVitsModelConfig(
            model = resolveFilePath("model.onnx"),
            tokens = resolveFilePath("tokens.txt"),
            lexicon = resolveFilePath("lexicon.txt")
        )
        return OfflineTtsModelConfig(
            vits = vitsConfig,
            numThreads = 4,
            provider = "cpu",
            debug = false
        )
    }

    /**
     * 构建 Kokoro 模型配置
     *
     * 使用 csukuangfj/kokoro-multi-lang-v1_1（sherpa-onnx 适配版）。
     * voices: voices.bin（103 个音色的向量文件）
     * dataDir: espeak-ng-data/ 目录（Kokoro 强制要求，sherpa-onnx C++ Validate() 拒绝空串）
     * numThreads=4: 官方 RTF 基准测试显示 4 线程较 2 线程提升约 30%
     */
    private fun buildKokoroConfig(): OfflineTtsModelConfig {
        val espeakDataDir = File(modelDir, "espeak-ng-data")
        // sherpa-onnx C++ Validate() 强制要求 dataDir 非空：
        //   if (data_dir.empty()) { LOGE(...); return false; }
        // Validate() 返回 false 后 JNI 不抛异常，留下未初始化状态 → generate() 时 SIGSEGV
        // 因此必须在 Kotlin 层拦截，抛明确异常而不是传空 dataDir
        if (!espeakDataDir.isDirectory || !File(espeakDataDir, "phontab").exists()) {
            throw IllegalStateException(
                "espeak-ng-data not found at ${espeakDataDir.absolutePath}. " +
                "Kokoro model requires espeak-ng-data for phoneme conversion. " +
                "Please re-download the model to restore this directory."
            )
        }
        val kokoroConfig = OfflineTtsKokoroModelConfig(
            model = resolveFilePath("model.onnx"),
            tokens = resolveFilePath("tokens.txt"),
            voices = resolveFilePath("voices.bin"),
            lexicon = resolveFilePath("lexicon-zh.txt"),
            dataDir = espeakDataDir.absolutePath
        )
        return OfflineTtsModelConfig(
            kokoro = kokoroConfig,
            numThreads = 4,
            provider = "cpu",
            debug = false
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
