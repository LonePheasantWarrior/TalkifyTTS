package com.github.lonepheasantwarrior.talkify.service.provider

import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.service.TtsErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * 返回发起 HTTP 请求使用的 OkHttpClient。
     * 默认为全局共享客户端；需要自定义网络行为（如经代理访问）的供应商可覆写。
     * 每个分块请求都会调用，实现应保持轻量（可通过 [OkHttpClient.newBuilder]
     * 复用共享连接池，或自行缓存构建结果）。
     */
    protected open fun httpClientFor(config: BaseProviderConfig): OkHttpClient = sharedOkHttpClient

    @Volatile
    private var cachedProxiedClient: OkHttpClient? = null

    @Volatile
    private var cachedProxiedKey: String? = null

    /**
     * 自定义 API 地址的明文端点校验（N19-f）
     *
     * minSdk 30 平台默认全面禁明文（cleartext）流量，用户填入 http:// 端点只会得到
     * 误导性的"网络不可用"；在配置校验层显式拦截并给出可操作提示。
     * 刻意不开 networkSecurityConfig 白名单（平台默认禁明文是安全资产）
     */
    protected fun validateCleartextEndpoint(apiUrl: String): String? {
        if (apiUrl.trim().startsWith("http://")) {
            return "系统安全策略禁止明文 HTTP 端点，请改用 https:// 地址"
        }
        return null
    }

    /**
     * 基于全局共享客户端派生一个走指定代理的客户端。
     * newBuilder() 复用共享连接池与调度线程池，构建开销可忽略；
     * 结果按代理参数缓存，代理设置未变化时不重复构建。
     * check-then-act 收敛进同步块，避免并发首调时"新 key 配旧 client"（P2-B9）
     */
    protected fun proxiedHttpClient(setting: ProxySetting): OkHttpClient {
        val key = "${setting.isSocks}|${setting.host}|${setting.port}"
        synchronized(this) {
            cachedProxiedClient?.let { cached ->
                if (cachedProxiedKey == key) return cached
            }
            val built = sharedOkHttpClient.newBuilder()
                .proxy(setting.toJavaProxy())
                .build()
            cachedProxiedKey = key
            cachedProxiedClient = built
            return built
        }
    }

    // ==================== 共享设施 ====================

    protected val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    protected var isCancelled = false

    /** in-flight HTTP 请求集合：流水线下多块并发，stop() 需全部取消 */
    private val inFlightCalls: MutableSet<Call> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var isFirstChunk = true

    /** 当前在飞的合成任务：新会话入口先取消，防止旧任务在 isCancelled 复位后复活（P1-15） */
    private var synthesisJob: Job? = null

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

        // 会话代际：入口自增并捕获快照，全部回调出口校验（P1-15）
        val session = beginSynthesisSession()
        isCancelled = false
        isFirstChunk = true

        val textChunks = TextChunkSplitter.split(text, chunkMaxLength)

        logDebug("Text split into ${textChunks.size} chunks")

        // 入口取消上一会话（对齐 MiniMax/LocalModelProvider）：stop() 是异步语义，
        // 上一次"尚未完全终止的合成"的协程若不显式取消，会在 isCancelled 复位后复活（P1-15）
        synthesisJob?.cancel()
        synthesisJob = providerScope.launch {
            try {
                synthesizePipelined(textChunks, config, params, listener, session)
            } catch (e: Exception) {
                if (!isCancelled && e !is CancellationException && isSynthesisSessionActive(session)) {
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
        // N1：作废当前会话——stop 后残留的音频/错误回调被出口校验静默丢弃
        invalidateSynthesisSession()
        cancelInFlightCalls()
        synthesisJob?.cancel()
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

        // 无 sink 回退：当前编排下不可达——fetch 协程恒在注入 AudioSinkElement 的
        // 上下文内启动（ChunkPipelineExecutor）。刻意保留为防御分支（N11 注释固化）：
        // 子类若绕过流水线直接调用 emitAudio（如单块直连路径实验），仍能得到正确的
        // 直投监听器行为而非静默丢失
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
        listener: TtsSynthesisListener,
        session: Long
    ) {
        val executor = ChunkPipelineExecutor()

        val allSucceeded = executor.execute(
            chunkCount = chunks.size,
            isCancelled = { isCancelled },
            fetch = { index, sink ->
                withContext(Dispatchers.IO + AudioSinkElement(sink)) {
                    fetchChunk(chunks[index], index, config, params, listener, session)
                }
            },
            emit = { data ->
                // 过期会话的残留音频静默丢弃：取消是协作式的，旧协程在取消
                // 信号生效前可能已产出音频（P1-15）
                if (isSynthesisSessionActive(session)) {
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
            }
        )

        if (allSucceeded && !isCancelled && isSynthesisSessionActive(session)) {
            withContext(Dispatchers.Main) {
                listener.onSynthesisCompleted()
            }
            logInfo("Synthesis completed successfully")
        }
    }

    /**
     * 拉取单块文本：发起 HTTP 请求并流式解析响应。
     * 音频数据经子类 [processStreamResponse] → [emitAudio] 写入当前块缓冲。
     * [session] 用于错误出口的会话代际校验——stop() 后被取消的在飞请求会以
     * IOException 形态抵达此处，旧会话的错误不得上报给监听器（P1-15 契约闭环）
     */
    private suspend fun fetchChunk(
        text: String,
        chunkIndex: Int,
        config: BaseProviderConfig,
        params: SynthesisParams,
        listener: TtsSynthesisListener,
        session: Long
    ): Boolean {
        try {
            val request = buildHttpRequest(text, config, params)

            val call = httpClientFor(config).newCall(request)
            inFlightCalls.add(call)

            try {
                // use{} 收拢 Response 生命周期：成功路径的关闭不再依赖子类自觉，
                // body-null 早退与异常路径不再泄漏连接（P2-B1）
                val response = call.execute()
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val errorBody = resp.body?.string() ?: "No error body"
                        logError("HTTP error: ${resp.code}, body: $errorBody")

                        val errorMessage = mapHttpError(errorBody)
                        if (isSynthesisSessionActive(session)) {
                            withContext(Dispatchers.Main) {
                                listener.onError(errorMessage)
                            }
                        }
                        return false
                    }

                    logDebug("HTTP Response Code: ${resp.code}")
                    // 响应头与请求头同走脱敏通道，避免服务端回显的凭据/标识泄露（P2-B9）
                    logDebug("HTTP Response Headers: ${resp.headers.toMaskedString()}")

                    return processStreamResponse(resp, chunkIndex, config, params, listener)
                }
            } finally {
                inFlightCalls.remove(call)
            }
        } catch (e: SocketTimeoutException) {
            logError("Network timeout", e)
            if (isSynthesisSessionActive(session)) {
                withContext(Dispatchers.Main) {
                    listener.onError(TtsErrorMessages.networkTimeout())
                }
            }
            return false
        } catch (e: IOException) {
            // 仅 isCancelled 判定不够：stop→synthesize 交错时新会话入口会把 isCancelled
            // 复位为 false，旧会话被取消的在飞请求会误报为网络错误——以会话代际为准
            if (!isCancelled && isSynthesisSessionActive(session)) {
                logError("Network error", e)
                withContext(Dispatchers.Main) {
                    listener.onError(TtsErrorMessages.networkUnavailable())
                }
            }
            return false
        } catch (e: CancellationException) {
            // 协程取消（stop/release）不是合成错误，必须原样重抛由取消机制处理
            throw e
        } catch (e: Exception) {
            logError("Unexpected error during synthesis", e)
            if (isSynthesisSessionActive(session)) {
                withContext(Dispatchers.Main) {
                    listener.onError(TtsErrorMessages.synthesisFailed())
                }
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
 * 名称包含 key/token/secret/authorization（不区分大小写）的头做掩码处理。
 * 掩码强度：≤8 字符整段掩码（区间重叠时 take/takeLast 会拼出原值）；更长的
 * 值仅保留首尾各 2 字符用于人工核对（P3-25）。
 */
internal fun okhttp3.Headers.toMaskedString(): String {
    val sb = StringBuilder("{")
    for (i in 0 until size) {
        val name = name(i)
        val value = value(i)
        val maskedValue = if (SENSITIVE_HEADER_REGEX.containsMatchIn(name.lowercase())) {
            value.maskSecret()
        } else {
            value
        }
        sb.append("$name=$maskedValue")
        if (i < size - 1) sb.append(", ")
    }
    sb.append("}")
    return sb.toString()
}

/** 凭据掩码：短值整段掩码，长值仅保留首尾各 2 字符（P3-25） */
internal fun String.maskSecret(): String = when {
    length <= 8 -> "****"
    else -> "${take(2)}****${takeLast(2)}"
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
