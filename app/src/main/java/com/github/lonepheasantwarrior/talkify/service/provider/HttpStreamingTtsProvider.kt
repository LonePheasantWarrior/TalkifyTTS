package com.github.lonepheasantwarrior.talkify.service.provider

import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.service.TtsErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * HTTP 流式合成供应商模板基类
 *
 * 适用于「单块文本一次 HTTP 请求、响应体内嵌音频数据」的供应商
 * （如火山引擎、小米 MiMo）。基类统一负责：
 * - 合成入口校验（配置/空文本/可读文本）与文本分块
 * - 分块预取流水线（见 [ChunkPipelineExecutor]）：当前块音频流式传输时，
 *   后续块请求已在服务端排队，消除块间网络往返空窗；音频严格按块序 flush
 * - OkHttp 连接池共享、请求级取消（stop/release）
 * - 首个音频块时触发 [TtsSynthesisListener.onSynthesisStarted]
 *
 * 子类只需实现差异化部分：
 * - [validateConfig]：配置校验
 * - [buildHttpRequest]：单块请求构建
 * - [processStreamResponse]：流式响应解析（经 [emitAudio] 回传音频）
 * - [mapHttpError]：HTTP 错误响应体 → 用户可读消息
 * - [chunkMaxLength] / [getAudioConfig] 等元数据
 */
abstract class HttpStreamingTtsProvider : AbstractTtsProvider() {

    /** 单块文本最大字符数 */
    protected abstract val chunkMaxLength: Int

    /** 校验配置是否可发起合成；返回错误消息，null 表示通过 */
    protected abstract fun validateConfig(config: BaseProviderConfig): String?

    /** 构建单块文本的流式 HTTP 请求 */
    protected abstract fun buildHttpRequest(
        text: String,
        config: BaseProviderConfig,
        params: SynthesisParams
    ): Request

    /**
     * 处理流式响应；返回该块是否成功。
     * 音频数据必须通过 [emitAudio] 回传：流水线模式下它会写入块缓冲并
     * 由流水线按序 flush，保证跨块音频顺序。
     */
    protected abstract suspend fun processStreamResponse(
        response: Response,
        chunkIndex: Int,
        config: BaseProviderConfig,
        params: SynthesisParams,
        listener: TtsSynthesisListener
    ): Boolean

    /** HTTP 非 2xx 时将错误响应体解析为用户可读消息 */
    protected abstract fun mapHttpError(errorBody: String): String

    // ==================== 共享设施 ====================

    protected val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    protected var isCancelled = false

    @Volatile
    protected var hasCompleted = false

    /** in-flight HTTP 请求集合：流水线下多块并发，stop() 需全部取消 */
    private val inFlightCalls: MutableSet<Call> = Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    @Volatile
    private var isFirstChunk = true

    final override fun synthesize(
        text: String,
        params: SynthesisParams,
        config: BaseProviderConfig,
        listener: TtsSynthesisListener
    ) {
        checkNotReleased()

        val validationError = validateConfig(config)
        if (validationError != null) {
            logError("Config validation failed: $validationError")
            listener.onError(validationError)
            return
        }

        if (text.isEmpty()) {
            logWarning("待朗读文本内容为空")
            listener.onSynthesisCompleted()
            return
        }

        if (!containsReadableText(text)) {
            logWarning("文本不包含可朗读的文字内容")
            listener.onSynthesisCompleted()
            return
        }

        logInfo("Starting synthesis: textLength=${text.length}, pitch=${params.pitch}, speechRate=${params.speechRate}")
        logDebug("Audio config: ${getAudioConfig().getFormatDescription()}")

        isCancelled = false
        hasCompleted = false
        isFirstChunk = true

        val textChunks = TextChunkSplitter.split(text, chunkMaxLength)
        if (textChunks.isEmpty()) {
            listener.onError("文本为空")
            return
        }

        logDebug("Text split into ${textChunks.size} chunks")

        providerScope.launch {
            try {
                synthesizePipelined(textChunks, config, params, listener)
            } catch (e: Exception) {
                if (!isCancelled && e !is kotlinx.coroutines.CancellationException) {
                    logError("Synthesis error", e)
                    withContext(Dispatchers.Main) {
                        listener.onError(TtsErrorMessages.synthesisFailed())
                    }
                }
            }
        }
    }

    override fun stop() {
        logInfo("Stopping synthesis")
        isCancelled = true
        cancelInFlightCalls()
    }

    override fun release() {
        logInfo("Releasing provider")
        isCancelled = true
        cancelInFlightCalls()
        providerScope.cancel()
        super.release()
    }

    /**
     * 供子类回传音频数据。
     *
     * 流水线模式下写入当前块缓冲（fetch 协程挂起于背压水位），由流水线
     * 按块序 flush；首个音频块自动触发 onSynthesisStarted。
     * 写入通道经协程上下文传递（[AudioSinkElement]），并发 fetch 天然隔离。
     */
    protected suspend fun emitAudio(audioData: ByteArray, listener: TtsSynthesisListener) {
        val sink = kotlin.coroutines.coroutineContext[AudioSinkElement]
        if (sink != null) {
            sink.send(audioData)
            return
        }

        if (isFirstChunk) {
            isFirstChunk = false
            listener.onSynthesisStarted()
        }
        val audioConfig = getAudioConfig()
        listener.onAudioAvailable(
            audioData,
            audioConfig.sampleRate,
            audioConfig.audioFormat,
            audioConfig.channelCount
        )
    }

    // ==================== 流水线合成 ====================

    private suspend fun synthesizePipelined(
        chunks: List<String>,
        config: BaseProviderConfig,
        params: SynthesisParams,
        listener: TtsSynthesisListener
    ) {
        val executor = ChunkPipelineExecutor()

        val allSucceeded = executor.execute(
            chunkCount = chunks.size,
            isCancelled = { isCancelled },
            fetch = { index, sink ->
                withContext(Dispatchers.IO + AudioSinkElement(sink)) {
                    fetchChunk(chunks[index], index, config, params, listener)
                }
            },
            emit = { data ->
                if (isFirstChunk) {
                    isFirstChunk = false
                    listener.onSynthesisStarted()
                }
                val audioConfig = getAudioConfig()
                listener.onAudioAvailable(
                    data,
                    audioConfig.sampleRate,
                    audioConfig.audioFormat,
                    audioConfig.channelCount
                )
            }
        )

        if (allSucceeded && !isCancelled) {
            hasCompleted = true
            withContext(Dispatchers.Main) {
                listener.onSynthesisCompleted()
            }
            logInfo("Synthesis completed successfully")
        }
    }

    /**
     * 拉取单块文本：发起 HTTP 请求并流式解析响应。
     * 音频数据经子类 [processStreamResponse] → [emitAudio] 写入当前块缓冲。
     */
    private suspend fun fetchChunk(
        text: String,
        chunkIndex: Int,
        config: BaseProviderConfig,
        params: SynthesisParams,
        listener: TtsSynthesisListener
    ): Boolean {
        try {
            val request = buildHttpRequest(text, config, params)

            val call = sharedOkHttpClient.newCall(request)
            inFlightCalls.add(call)

            try {
                val response = call.execute()
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "No error body"
                    logError("HTTP error: ${response.code}, body: $errorBody")

                    val errorMessage = mapHttpError(errorBody)
                    withContext(Dispatchers.Main) {
                        listener.onError(errorMessage)
                    }
                    response.close()
                    return false
                }

                logDebug("HTTP Response Code: ${response.code}")
                logDebug("HTTP Response Headers: ${response.headers}")

                return processStreamResponse(response, chunkIndex, config, params, listener)
            } finally {
                inFlightCalls.remove(call)
            }
        } catch (e: SocketTimeoutException) {
            logError("Network timeout", e)
            withContext(Dispatchers.Main) {
                listener.onError(TtsErrorMessages.networkTimeout())
            }
            return false
        } catch (e: IOException) {
            if (!isCancelled) {
                logError("Network error", e)
                withContext(Dispatchers.Main) {
                    listener.onError(TtsErrorMessages.networkUnavailable())
                }
            }
            return false
        } catch (e: Exception) {
            logError("Unexpected error during synthesis", e)
            withContext(Dispatchers.Main) {
                listener.onError(TtsErrorMessages.synthesisFailed())
            }
            return false
        }
    }

    private fun cancelInFlightCalls() {
        for (call in inFlightCalls) {
            try {
                call.cancel()
            } catch (_: Exception) {
            }
        }
        inFlightCalls.clear()
    }

    private companion object {
        // OkHttp 连接池配置：火山服务端 keep-alive 为 1 分钟，
        // 客户端设置为略小于服务端的值，避免刚好 1 分钟时服务端关闭连接而客户端仍复用
        const val CONNECTION_POOL_SIZE = 5
        const val CONNECTION_POOL_KEEP_ALIVE_SECONDS = 45L

        /** 全局共享 OkHttp 客户端：所有 HTTP 流式供应商复用连接池与调度线程池 */
        val sharedOkHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectionPool(
                    ConnectionPool(CONNECTION_POOL_SIZE, CONNECTION_POOL_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS)
                )
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}

/** 通用网络/合成错误消息（取自 [TtsErrorCode] 的统一文案） */
internal object TtsErrorMessages {
    fun networkTimeout(): String = TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_NETWORK_TIMEOUT)

    fun networkUnavailable(): String = TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_NETWORK_UNAVAILABLE)

    fun synthesisFailed(): String = TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_SYNTHESIS_FAILED)
}

/**
 * 将请求头转换为脱敏字符串用于日志输出。
 *
 * 名称包含 key/token/secret/authorization（不区分大小写）的头只保留前后 4 个字符。
 */
internal fun okhttp3.Headers.toMaskedString(): String {
    val sb = StringBuilder("{")
    for (i in 0 until size) {
        val name = name(i)
        val value = value(i)
        val maskedValue = if (SENSITIVE_HEADER_REGEX.containsMatchIn(name.lowercase())) {
            "${value.take(4)}****${value.takeLast(4)}"
        } else {
            value
        }
        sb.append("$name=$maskedValue")
        if (i < size - 1) sb.append(", ")
    }
    sb.append("}")
    return sb.toString()
}

private val SENSITIVE_HEADER_REGEX = Regex("key|token|secret|authorization")

/**
 * 音频写入通道的协程上下文元素：
 * 将当前块的音频 sink 沿 fetch 协程传递给子类的 [HttpStreamingTtsProvider.emitAudio] 调用
 */
internal class AudioSinkElement(
    val send: suspend (ByteArray) -> Unit
) : kotlin.coroutines.AbstractCoroutineContextElement(AudioSinkElement) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<AudioSinkElement>
}
