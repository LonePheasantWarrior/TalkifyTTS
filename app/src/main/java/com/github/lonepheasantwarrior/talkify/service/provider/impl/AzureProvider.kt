package com.github.lonepheasantwarrior.talkify.service.provider.impl

import android.speech.tts.Voice
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.AzureConfig
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.domain.model.ProviderIds
import com.github.lonepheasantwarrior.talkify.service.TtsErrorCode
import com.github.lonepheasantwarrior.talkify.service.provider.AbstractTtsProvider
import com.github.lonepheasantwarrior.talkify.service.provider.AudioConfig
import com.github.lonepheasantwarrior.talkify.service.provider.AzureParamMapper
import com.github.lonepheasantwarrior.talkify.service.provider.Mp3StreamDecoder
import com.github.lonepheasantwarrior.talkify.service.provider.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.provider.TtsSynthesisListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Random
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * 微软语音合成供应商实现
 *
 * 继承 [AbstractTtsProvider]，实现 TTS 供应商接口
 * 支持真正的流式音频合成，边接收边播放
 *
 * 核心优化：
 * 1. 跨 synthesize() 调用复用同一条 WebSocket 长连接，消除重复握手延迟
 * 2. 请求流水线（Pipelining）：提前发送下一个 chunk 的 SSML 请求，
 *    让服务端在当前 chunk 音频传输期间就开始处理下一个 chunk，
 *    将 chunk 间的间隔从 ~3s 降低到 <1s
 *
 * 供应商：Azure
 */
class AzureProvider : AbstractTtsProvider() {

    companion object {
        private const val BASE_URL = "speech.platform.bing.com/consumer/speech/synthesize/readaloud"
        private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        const val DEFAULT_WSS_URL = "wss://$BASE_URL/edge/v1?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"
        private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
        private const val CHROMIUM_MAJOR_VERSION = "143"
        private const val SEC_MS_GEC_VERSION = "1-$CHROMIUM_FULL_VERSION"

        private const val WIN_EPOCH = 11644473600L
        private const val S_TO_NS = 1_000_000_000L

        private const val DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"
        private const val MAX_TEXT_LENGTH = 4096
        private const val PIPE_BUFFER_SIZE = 65536 // 64KB 扩容管道，防止 OkHttp 接收线程阻塞拖慢网络

        /** 连接空闲超时时间（毫秒），超过此时间未使用则主动关闭连接释放资源 */
        private const val CONNECTION_IDLE_TIMEOUT_MS = 60_000L

        /**
         * 预取窗口大小：同时在服务端排队处理的 chunk 数量
         * 设为 3 表示当前 chunk 正在接收音频时，后续 2 个 chunk 已经在服务端排队/处理中
         * 更大的窗口确保服务端始终有待处理的请求，消除 chunk 间的等待间隔
         */
        private const val PREFETCH_WINDOW = 3

        private val SUPPORTED_LANGUAGES = arrayOf("zho", "eng", "deu", "ita", "por", "spa", "jpn", "kor", "fra", "rus")
        private val random = Random()

        private fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray(Charsets.US_ASCII))
            return hash.joinToString("") { "%02x".format(it).uppercase(Locale.US) }
        }

        private fun generateSecMsGec(): String {
            val currentTimeSeconds = System.currentTimeMillis() / 1000.0
            var ticks = currentTimeSeconds + WIN_EPOCH
            ticks -= ticks % 300
            ticks *= S_TO_NS / 100.0
            val strToHash = "${ticks.toLong()}$TRUSTED_CLIENT_TOKEN"
            return sha256Hex(strToHash)
        }

        private fun generateMuid(): String {
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it).uppercase(Locale.US) }
        }

        private fun getHeadersWithMuid(): Map<String, String> {
            return mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR_VERSION.0.0.0 Safari/537.36 " +
                        "Edg/$CHROMIUM_MAJOR_VERSION.0.0.0",
                "Accept-Encoding" to "gzip, deflate, br, zstd",
                "Accept-Language" to "en-US,en;q=0.9",
                "Pragma" to "no-cache",
                "Cache-Control" to "no-cache",
                "Origin" to "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold",
                "Sec-WebSocket-Version" to "13",
                "Cookie" to "muid=${generateMuid()};"
            )
        }

        /** UTC 时间格式化器（线程安全，可跨线程复用）；OkHttp 回调线程与合成协程共用 */
        private val utcDateTimeFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern(
                "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
                Locale.US
            ).withZone(ZoneOffset.UTC)

        private fun dateToString(): String {
            return utcDateTimeFormatter.format(Instant.now())
        }

        private fun connectId(): String {
            return UUID.randomUUID().toString().replace("-", "")
        }

        private fun mkssml(voice: String, rate: String, volume: String, pitch: String, text: String): String {
            return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                    "<voice name='$voice'>" +
                    "<prosody pitch='$pitch' rate='$rate' volume='$volume'>" +
                    escapeXml(text) +
                    "</prosody>" +
                    "</voice>" +
                    "</speak>"
        }

        private fun escapeXml(text: String): String {
            return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
        }

        private fun ssmlHeadersPlusData(requestId: String, timestamp: String, ssml: String): String {
            return "X-RequestId:$requestId\r\n" +
                    "Content-Type:application/ssml+xml\r\n" +
                    "X-Timestamp:${timestamp}Z\r\n" +
                    "Path:ssml\r\n\r\n" +
                    ssml
        }

        private fun removeIncompatibleCharacters(text: String): String {
            val chars = text.toCharArray()
            for (i in chars.indices) {
                val code = chars[i].code
                if ((code in 0..8) || (code in 11..12) || (code in 14..31)) {
                    chars[i] = ' '
                }
            }
            return String(chars)
        }

        private fun splitTextByByteLength(text: String): List<String> {
            val chunks = mutableListOf<String>()
            val utf8Bytes = text.toByteArray(Charsets.UTF_8)
            var offset = 0
            while (offset < utf8Bytes.size) {
                var end = min(offset + MAX_TEXT_LENGTH, utf8Bytes.size)
                end = findSafeUtf8SplitPoint(utf8Bytes, end)
                end = findBestSplitPoint(utf8Bytes, offset, end)
                if (end <= offset) {
                    end = min(offset + MAX_TEXT_LENGTH, utf8Bytes.size)
                    end = findSafeUtf8SplitPoint(utf8Bytes, end)
                }
                val chunk = String(utf8Bytes, offset, end - offset, Charsets.UTF_8).trim()
                if (chunk.isNotEmpty()) {
                    chunks.add(chunk)
                }
                offset = end
            }
            return chunks
        }

        /**
         * 找到不会切断多字节 UTF-8 字符的安全切分点。
         *
         * [String] 构造器对非法 UTF-8 序列采用替换（U+FFFD）而非抛异常语义，
         * 无法用 try/catch 检测边界；改为直接检查 [end] 前最后一个字节：
         * 若落在多字节序列的中间，则回退到该序列的首字节处切分。
         */
        private fun findSafeUtf8SplitPoint(bytes: ByteArray, end: Int): Int {
            if (end <= 0) return 0
            // 从 end-1 向前回退，跳过 UTF-8 续字节（10xxxxxx），定位当前序列首字节
            var lead = end - 1
            while (lead >= 0 && (bytes[lead].toInt() and 0xC0) == 0x80) lead--
            if (lead < 0) return end
            val firstByte = bytes[lead].toInt() and 0xFF
            val charLength = when {
                firstByte and 0x80 == 0 -> 1
                firstByte and 0xE0 == 0xC0 -> 2
                firstByte and 0xF0 == 0xE0 -> 3
                firstByte and 0xF8 == 0xF0 -> 4
                else -> return end
            }
            return if (lead + charLength <= end) end else lead
        }

        private fun findBestSplitPoint(bytes: ByteArray, start: Int, end: Int): Int {
            var splitAt = lastIndexOfByte(bytes, '\n'.code.toByte(), start, end)
            if (splitAt >= start) {
                return splitAt + 1
            }
            splitAt = lastIndexOfByte(bytes, ' '.code.toByte(), start, end)
            if (splitAt >= start) {
                return splitAt + 1
            }
            return end
        }

        /** 在 [start, end) 范围内从后向前查找指定字节，未找到返回 -1 */
        private fun lastIndexOfByte(bytes: ByteArray, needle: Byte, start: Int, end: Int): Int {
            for (i in end - 1 downTo start) {
                if (bytes[i] == needle) return i
            }
            return -1
        }
    }

    // ==================== 持久化 WebSocket 连接 ====================

    /**
     * 持久化 WebSocket 连接，跨 synthesize() 调用复用
     * 通过 Mutex 保证协程安全的连接获取与释放。
     * N7：release() 无锁读取本组字段，@Volatile 保证可见性
     */
    private val connectionMutex = Mutex()

    @Volatile
    private var persistentWebSocket: WebSocket? = null

    @Volatile
    private var persistentListener: PersistentWebSocketListener? = null

    /** 连接是否处于可用状态（已连接且未被服务端关闭） */
    @Volatile
    private var isConnectionAlive = false

    /** 空闲超时定时器（N7：scheduleIdleTimeout/closeConnectionInternal/release 多线程写点，@Volatile 保可见） */
    @Volatile
    private var idleTimeoutJob: Job? = null

    /** 上次使用连接的时间戳 */
    @Volatile
    private var lastUsedTimestamp = 0L

    /** 当前合成使用的 API 地址（用户自定义或默认），用于创建新连接时确定目标端点 */
    @Volatile
    private var currentApiUrl: String = DEFAULT_WSS_URL

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS) // 心跳保活，防止中间网络设备超时断连
        .build()

    @Volatile
    private var isCancelled = false

    private val providerJob = SupervisorJob()
    private val providerScope = CoroutineScope(Dispatchers.IO + providerJob)

    private var synthesisJob: Job? = null

    init {
        // DNS 预热：前置网络层握手准备，显著降低首次合成请求时的 DNS 解析延迟
        providerScope.launch {
            try {
                java.net.InetAddress.getByName("speech.platform.bing.com")
            } catch (_: Exception) {
                // 忽略预热异常，不影响主流程
            }
        }
    }

    override fun getProviderId(): String = ProviderIds.Azure.providerId
    override fun getProviderName(): String = ProviderIds.Azure.provider
    override fun getDefaultModelId(): String = ProviderIds.Azure.defaultModelId

    override fun getDefaultApiUrl(): String = DEFAULT_WSS_URL

    override fun synthesize(
        text: String, params: SynthesisParams, config: BaseProviderConfig, listener: TtsSynthesisListener
    ) {
        checkNotReleased()

        val msConfig = config as? AzureConfig
        if (msConfig == null) {
            logError("Invalid config type, expected AzureConfig")
            listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_PROVIDER_NOT_CONFIGURED))
            return
        }

        // 更新当前 API 地址（用户自定义优先，为空时使用默认值）；关闭旧连接在合成协程内
        // 经 connectionMutex 执行，避免与连接复用/空闲超时路径互斥失效
        val newApiUrl = msConfig.apiUrl.ifBlank { DEFAULT_WSS_URL }

        val cleanedText = removeIncompatibleCharacters(text)
        val textChunks = splitTextByByteLength(cleanedText)

        if (textChunks.isEmpty()) {
            logWarning("待朗读文本内容为空")
            listener.onSynthesisCompleted()
            return
        }

        logInfo("Starting Microsoft TTS synthesis: textLength=${text.length}, chunks=${textChunks.size}")

        // 会话代际：入口取消旧任务并捕获快照，回调出口校验——旧会话协程若不取消，
        // beginStreamingSession 覆写监听器状态后旧协程永久挂起、管道与解码线程泄漏（P2-B14/P1-15）
        val session = beginSynthesisSession()
        isCancelled = false

        synthesisJob?.cancel()
        synthesisJob = providerScope.launch {
            try {
                if (newApiUrl != currentApiUrl) {
                    logInfo("API URL changed, closing old connection and will create new: $newApiUrl")
                    currentApiUrl = newApiUrl
                    closeConnection()
                }
                listener.onSynthesisStarted()
                processChunks(textChunks, params, msConfig, listener, session)
                if (!isCancelled && isSynthesisSessionActive(session)) {
                    listener.onSynthesisCompleted()
                }
            } catch (e: Exception) {
                if (!isCancelled && e !is CancellationException && isSynthesisSessionActive(session)) {
                    logError("Synthesis error", e)
                    listener.onError("合成失败：${e.message}")
                }
            }
        }
    }

    /**
     * 流水线式处理多个 chunk —— 零间隔版本
     *
     * 核心思路：
     * - 活跃 chunk（队列头部）的音频在 OkHttp 回调线程上直接写入管道，零延迟
     * - 预取 chunk 的音频暂存到内存缓冲区
     * - 当活跃 chunk 收到 turn.end 时，下一个 chunk 立即被提升为活跃，
     *   其已缓冲的音频在同一个回调中被一次性 drain 到管道，后续音频也直接写入
     * - 整个提升 + drain 过程发生在 OkHttp 回调线程内，不经过协程调度，
     *   消除了之前 deferred.await() → flushChunkAudio() 的协程切换延迟
     *
     * processChunks 协程只负责：发送 SSML 请求 + 等待所有 chunk 完成 + 资源清理
     */
    private suspend fun processChunks(
        chunks: List<String>,
        params: SynthesisParams,
        config: AzureConfig,
        listener: TtsSynthesisListener,
        session: Long
    ) {
        val pipeClosed = AtomicBoolean(false)
        val pipedOutputStream = PipedOutputStream()
        val pipedInputStream = PipedInputStream(pipedOutputStream, PIPE_BUFFER_SIZE)

        // 解码是 CPU 密集型操作，调度至 Default
        val decodeJob = providerScope.launch(Dispatchers.Default) {
            decodeMp3Stream(pipedInputStream, listener, session)
        }
        try {
            // 1. 获取或复用持久化 WebSocket 连接
            val (webSocket, wsListener) = getOrCreateConnection(pipedOutputStream, pipeClosed)

            val voice = config.voiceId.ifEmpty { DEFAULT_VOICE }
            val rate = AzureParamMapper.convertRate(params.speechRate)
            val volume = AzureParamMapper.convertVolume(params.volume)
            val pitch = AzureParamMapper.convertPitch(params.pitch)

            // 2. 为每个 chunk 生成唯一 RequestId 并创建完成信号
            val requestIds = chunks.map { connectId() }
            val deferreds = chunks.map { CompletableDeferred<Result<Unit>>() }

            // 注册到监听器：第一个 chunk 为活跃（直接写管道），其余为预取（缓冲）
            wsListener.beginStreamingSession(requestIds, deferreds, pipedOutputStream)

            // 3. 流水线发送：预取窗口内的 chunk 立即发送
            var nextToSend = 0
            while (nextToSend < chunks.size && nextToSend < PREFETCH_WINDOW) {
                if (isCancelled) break
                logDebug("Pipelining: sending chunk ${nextToSend + 1}/${chunks.size} (requestId=${requestIds[nextToSend].take(8)}...)")
                sendSsmlMessageWithId(webSocket, requestIds[nextToSend], voice, rate, volume, pitch, chunks[nextToSend])
                nextToSend++
            }

            // 4. 按顺序等待每个 chunk 完成，完成一个就补发一个新的
            for (i in chunks.indices) {
                if (isCancelled) break

                val result = deferreds[i].await()
                result.getOrThrow()

                logDebug("Chunk ${i + 1}/${chunks.size} completed")

                // 补发下一个 chunk（滑动窗口）
                if (nextToSend < chunks.size && !isCancelled) {
                    logDebug("Pipelining: sending chunk ${nextToSend + 1}/${chunks.size} (requestId=${requestIds[nextToSend].take(8)}...)")
                    sendSsmlMessageWithId(webSocket, requestIds[nextToSend], voice, rate, volume, pitch, chunks[nextToSend])
                    nextToSend++
                }
            }

            // 5. 合成完成，标记连接空闲
            wsListener.endStreamingSession()
            lastUsedTimestamp = System.currentTimeMillis()
            wsListener.detachPipe()
            scheduleIdleTimeout()
        } catch (e: Exception) {
            closeConnection()
            throw e
        } finally {
            // 非挂起关闭：协程被 stop() 取消时 withContext 会直接抛 CancellationException，
            // 导致管道永不关闭、解码线程永久阻塞在 readFrame（线程/管道泄漏）
            runCatching { pipedOutputStream.close() }
            pipeClosed.set(true)

            decodeJob.join()
        }
    }

    // ==================== 持久化连接管理 ====================

    /**
     * 获取可复用的 WebSocket 连接，如果不存在或已失效则新建
     */
    private suspend fun getOrCreateConnection(
        pipedOutputStream: PipedOutputStream,
        pipeClosed: AtomicBoolean
    ): Pair<WebSocket, PersistentWebSocketListener> = connectionMutex.withLock {
        // 取消空闲超时计时器（连接正在被使用）
        idleTimeoutJob?.cancel()
        idleTimeoutJob = null

        val existingWs = persistentWebSocket
        val existingListener = persistentListener

        if (existingWs != null && existingListener != null && isConnectionAlive) {
            logDebug("Reusing persistent WebSocket connection")
            existingListener.attachPipe(pipedOutputStream, pipeClosed)
            return@withLock Pair(existingWs, existingListener)
        }

        // 连接不存在或已失效，新建连接
        logInfo("Creating new persistent WebSocket connection")
        closeConnectionInternal()

        val listener = PersistentWebSocketListener(pipedOutputStream, pipeClosed)
        val webSocket = openWebSocket(listener)

        persistentWebSocket = webSocket
        persistentListener = listener
        isConnectionAlive = true
        lastUsedTimestamp = System.currentTimeMillis()

        Pair(webSocket, listener)
    }

    /**
     * 打开新的 WebSocket 连接并等待握手完成
     */
    private suspend fun openWebSocket(listener: PersistentWebSocketListener): WebSocket {
        val connectionId = connectId()
        // 自定义 URL 可能不带查询串：无条件用 "&" 拼接会把参数沦为路径的一部分（P2-B16）
        val separator = if (currentApiUrl.contains('?')) "&" else "?"
        val url = "$currentApiUrl${separator}ConnectionId=$connectionId" +
                "&Sec-MS-GEC=${generateSecMsGec()}&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"

        val requestBuilder = Request.Builder().url(url)
        getHeadersWithMuid().forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }

        // 捕获 newWebSocket 返回值：握手窗口期协程被取消（stop/release/120s 兜底）时，
        // awaitConnection 抛出而 persistentWebSocket 尚未赋值，握手中的连接将无持有者
        // 去 close，还被 pingInterval 长期保活——取消路径显式 cancel 该连接（P2-B17）
        val webSocket = client.newWebSocket(requestBuilder.build(), listener)
        try {
            return listener.awaitConnection()
        } catch (e: Throwable) {
            webSocket.cancel()
            throw e
        }
    }

    /**
     * 安排空闲超时关闭连接
     */
    private fun scheduleIdleTimeout() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = providerScope.launch {
            kotlinx.coroutines.delay(CONNECTION_IDLE_TIMEOUT_MS)
            val elapsed = System.currentTimeMillis() - lastUsedTimestamp
            if (elapsed >= CONNECTION_IDLE_TIMEOUT_MS) {
                logInfo("WebSocket idle timeout reached, closing connection")
                closeConnection()
            }
        }
    }

    /**
     * 关闭当前持久化连接。
     *
     * 统一经 [connectionMutex] 互斥：连接复用（getOrCreateConnection）、空闲超时关闭
     * 与合成失败清理共享同一把锁，避免「复用判活 → 另一线程关闭」的检查后失效竞态。
     */
    private suspend fun closeConnection() {
        connectionMutex.withLock {
            closeConnectionInternal()
        }
    }

    /** 仅在已持有 [connectionMutex] 时调用 */
    private fun closeConnectionInternal() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = null
        persistentWebSocket?.close(1000, "Done")
        persistentWebSocket = null
        persistentListener = null
        isConnectionAlive = false
    }

    // ==================== 持久化 WebSocket 监听器（零间隔流式） ====================

    /**
     * 持久化 WebSocket 监听器，支持零间隔流式音频输出
     *
     * 核心机制：维护一个有序的 chunk 队列，队列头部为"活跃 chunk"。
     * - 活跃 chunk 的音频数据在 OkHttp 回调线程上直接写入管道（零延迟）
     * - 非活跃 chunk 的音频数据暂存到内存缓冲区
     * - 当活跃 chunk 收到 turn.end 时，立即提升下一个 chunk 为活跃，
     *   并在同一个回调中将其已缓冲的音频一次性 drain 到管道
     *
     * 这样 chunk 之间的间隔 = 0（纯内存操作，无网络/协程调度延迟）
     */
    inner class PersistentWebSocketListener(
        pipedOutputStream: PipedOutputStream,
        pipeClosed: AtomicBoolean
    ) : WebSocketListener() {

        private val connectionDeferred = CompletableDeferred<Result<WebSocket>>()

        @Volatile
        private var currentPipedOutputStream: PipedOutputStream? = pipedOutputStream

        @Volatile
        private var currentPipeClosed: AtomicBoolean = pipeClosed

        // ---- 流式状态（所有字段通过 streamLock 同步） ----

        private val streamLock = Any()

        /** 有序的 RequestId 列表，索引即 chunk 顺序 */
        private var orderedRequestIds: List<String> = emptyList()

        /** 当前活跃 chunk 的索引（其音频直接写管道） */
        private var activeChunkIndex = 0

        /** RequestId → 音频缓冲队列（仅非活跃 chunk 使用） */
        private val audioBuffers = ConcurrentHashMap<String, ConcurrentLinkedQueue<ByteArray>>()

        /** RequestId → 完成信号 */
        private val chunkDeferreds = ConcurrentHashMap<String, CompletableDeferred<Result<Unit>>>()

        /** 用于直接写管道的输出流引用（在 streamLock 内访问） */
        private var streamingPipe: PipedOutputStream? = null

        /** 是否处于流式会话中 */
        @Volatile
        private var inSession = false

        /**
         * 开始流式会话
         * @param requestIds 有序的 RequestId 列表
         * @param deferreds 对应的完成信号列表
         * @param pipe 音频输出管道
         */
        fun beginStreamingSession(
            requestIds: List<String>,
            deferreds: List<CompletableDeferred<Result<Unit>>>,
            pipe: PipedOutputStream
        ) {
            synchronized(streamLock) {
                audioBuffers.clear()
                chunkDeferreds.clear()
                orderedRequestIds = requestIds
                activeChunkIndex = 0
                streamingPipe = pipe
                inSession = true

                for (i in requestIds.indices) {
                    chunkDeferreds[requestIds[i]] = deferreds[i]
                    // 非活跃 chunk 需要缓冲区；活跃 chunk 直接写管道不需要
                    if (i > 0) {
                        audioBuffers[requestIds[i]] = ConcurrentLinkedQueue()
                    }
                }
            }
        }

        /**
         * 结束流式会话，清理状态
         */
        fun endStreamingSession() {
            synchronized(streamLock) {
                inSession = false
                orderedRequestIds = emptyList()
                activeChunkIndex = 0
                audioBuffers.clear()
                chunkDeferreds.clear()
                streamingPipe = null
            }
        }

        fun attachPipe(pipedOutputStream: PipedOutputStream, pipeClosed: AtomicBoolean) {
            currentPipedOutputStream = pipedOutputStream
            currentPipeClosed = pipeClosed
        }

        fun detachPipe() {
            currentPipedOutputStream = null
        }

        suspend fun awaitConnection(): WebSocket {
            return connectionDeferred.await().getOrThrow()
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            logDebug("WebSocket connected (persistent)")
            try {
                sendConfigMessage(webSocket)
                connectionDeferred.complete(Result.success(webSocket))
            } catch (e: Exception) {
                connectionDeferred.complete(Result.failure(e))
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val closed = currentPipeClosed
            if (closed.get() || isCancelled) return

            try {
                val buffer = bytes.asByteBuffer()
                if (buffer.remaining() < 2) return

                val headerLength = (buffer.get().toInt() and 0xFF) shl 8 or (buffer.get().toInt() and 0xFF)
                if (buffer.remaining() < headerLength) return

                val headerBytes = ByteArray(headerLength)
                buffer.get(headerBytes)
                val headerStr = String(headerBytes, Charsets.UTF_8)

                if (!headerStr.contains("Path:audio") && !headerStr.contains("Path: audio")) return
                if (buffer.remaining() <= 0) return

                val audioData = ByteArray(buffer.remaining())
                buffer.get(audioData)

                routeAudioData(headerStr, audioData)
            } catch (e: Exception) {
                if (!closed.get()) {
                    logError("Error processing audio message", e)
                    handleConnectionFailure(e)
                }
            }
        }

        /**
         * 路由音频数据：活跃 chunk 直接写管道，其余缓冲
         * 在 streamLock 内执行，保证与 turn.end 的提升操作互斥
         */
        private fun routeAudioData(headerStr: String, audioData: ByteArray) {
            synchronized(streamLock) {
                if (!inSession) {
                    // 非会话模式（不应该发生，但安全回退）
                    currentPipedOutputStream?.write(audioData)
                    return
                }

                val requestId = extractRequestId(headerStr)

                if (requestId != null && activeChunkIndex < orderedRequestIds.size
                    && requestId == orderedRequestIds[activeChunkIndex]
                ) {
                    // 活跃 chunk → 直接写管道，零延迟
                    streamingPipe?.write(audioData)
                } else if (requestId != null) {
                    // 非活跃 chunk → 缓冲
                    audioBuffers[requestId]?.add(audioData)
                } else {
                    // RequestId 解析失败，直接写管道
                    streamingPipe?.write(audioData)
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val closed = currentPipeClosed
            if (closed.get() || isCancelled) return

            try {
                if (text.contains("Path:turn.end") || text.contains("Path: turn.end")) {
                    val requestId = extractRequestIdFromText(text)
                    handleTurnEnd(requestId)
                }
            } catch (e: Exception) {
                logError("Error processing text message", e)
            }
        }

        /**
         * 处理 turn.end：完成当前 chunk，提升下一个 chunk 为活跃并立即 drain 其缓冲
         * 全部在 streamLock 内完成，保证与 routeAudioData 互斥
         */
        private fun handleTurnEnd(requestId: String?) {
            synchronized(streamLock) {
                if (!inSession || orderedRequestIds.isEmpty()) {
                    // 非会话模式，按旧逻辑处理
                    if (requestId != null) {
                        chunkDeferreds[requestId]?.complete(Result.success(Unit))
                    }
                    return
                }

                // 确定是哪个 chunk 完成了
                val completedId = requestId
                    ?: if (activeChunkIndex < orderedRequestIds.size) orderedRequestIds[activeChunkIndex] else null

                if (completedId != null) {
                    chunkDeferreds[completedId]?.complete(Result.success(Unit))
                }

                // 提升下一个 chunk 为活跃
                activeChunkIndex++

                if (activeChunkIndex < orderedRequestIds.size) {
                    val nextId = orderedRequestIds[activeChunkIndex]
                    val pipe = streamingPipe

                    // 立即 drain 已缓冲的音频数据到管道
                    if (pipe != null) {
                        val queue = audioBuffers.remove(nextId)
                        if (queue != null) {
                            var data = queue.poll()
                            while (data != null) {
                                pipe.write(data)
                                data = queue.poll()
                            }
                        }
                    }
                    // 从此刻起，nextId 的后续音频会在 routeAudioData 中直接写管道
                }
            }
        }

        /**
         * 从消息头中提取 X-RequestId
         */
        private fun extractRequestId(headerStr: String): String? {
            val prefix = "X-RequestId:"
            val startIdx = headerStr.indexOf(prefix)
            if (startIdx < 0) return null
            val valueStart = startIdx + prefix.length
            val endIdx = headerStr.indexOf('\r', valueStart).let {
                if (it < 0) headerStr.indexOf('\n', valueStart) else it
            }
            return if (endIdx > valueStart) headerStr.substring(valueStart, endIdx).trim()
            else headerStr.substring(valueStart).trim().takeIf { it.isNotEmpty() }
        }

        private fun extractRequestIdFromText(text: String): String? {
            return extractRequestId(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            logDebug("WebSocket closing: code=$code, reason=$reason")
            markConnectionDead()
            completeAllPending(code, reason, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            logDebug("WebSocket closed: code=$code, reason=$reason")
            markConnectionDead()
            completeAllPending(code, reason, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            logError("WebSocket failure", if (t is Exception) t else Exception(t))
            markConnectionDead()
            handleConnectionFailure(t)
        }

        private fun markConnectionDead() {
            isConnectionAlive = false
        }

        private fun handleConnectionFailure(t: Throwable) {
            markConnectionDead()
            currentPipeClosed.set(true)
            try { currentPipedOutputStream?.close() } catch (_: Exception) {}

            val exception = if (t is Exception) t else Exception(t)

            if (!connectionDeferred.isCompleted) {
                connectionDeferred.complete(Result.failure(exception))
            }

            for (deferred in chunkDeferreds.values) {
                if (!deferred.isCompleted) {
                    deferred.complete(Result.failure(exception))
                }
            }
        }

        private fun completeAllPending(code: Int?, reason: String?, t: Throwable?) {
            currentPipeClosed.set(true)
            try { currentPipedOutputStream?.close() } catch (_: Exception) {}

            val exception = t ?: Exception("WebSocket closed with code: $code, reason: $reason")

            if (!connectionDeferred.isCompleted) {
                connectionDeferred.complete(Result.failure(if (exception is Exception) exception else Exception(exception)))
            }

            // P2-B3：会话进行中收到 onClosing(1000) 是服务端提前关闭（音频截断），
            // 不能把未完成块标成功；仅在空闲期（无活动流式会话）的正常关闭按成功收尾
            val sessionActive = synchronized(streamLock) { inSession }
            for (deferred in chunkDeferreds.values) {
                if (!deferred.isCompleted) {
                    if (code == 1000 && !sessionActive) {
                        deferred.complete(Result.success(Unit))
                    } else {
                        deferred.complete(Result.failure(if (exception is Exception) exception else Exception(exception)))
                    }
                }
            }
        }
    }

    // ==================== 音频解码 ====================

    /**
     * 解码 MP3 流并输出 PCM 音频数据
     *
     * @param session 会话代际快照：stop() 作废会话后，解码线程在取消探测生效前
     * 已产出的尾帧不得穿透到监听器（G1/N1）
     */
    private fun decodeMp3Stream(
        inputStream: PipedInputStream,
        listener: TtsSynthesisListener,
        session: Long
    ) {
        Mp3StreamDecoder.decodeMp3Stream(
            inputStream,
            isCancelled = { isCancelled }
        ) { pcmBytes, sampleRate, channelCount ->
            if (!isSynthesisSessionActive(session)) return@decodeMp3Stream
            listener.onAudioAvailable(
                pcmBytes,
                sampleRate,
                AudioConfig.DEFAULT_AUDIO_FORMAT,
                channelCount
            )
        }
    }

    // ==================== WebSocket 消息构建 ====================

    private fun sendConfigMessage(webSocket: WebSocket) {
        val configMessage = "X-Timestamp:${dateToString()}\r\n" +
                "Content-Type:application/json; charset=utf-8\r\n" +
                "Path:speech.config\r\n\r\n" +
                "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
                "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
        webSocket.send(configMessage)
    }

    /**
     * 发送带指定 RequestId 的 SSML 消息（流水线模式使用）
     */
    private fun sendSsmlMessageWithId(
        webSocket: WebSocket,
        requestId: String,
        voice: String,
        rate: String,
        volume: String,
        pitch: String,
        text: String
    ) {
        val ssml = mkssml(voice, rate, volume, pitch, text)
        val message = ssmlHeadersPlusData(requestId, dateToString(), ssml)
        webSocket.send(message)
    }

    // ==================== 供应商元数据 ====================

    override fun getAudioConfig(): AudioConfig = AudioConfig.MICROSOFT_TTS

    override fun getSupportedLanguages(): Set<String> {
        return SUPPORTED_LANGUAGES.toSet()
    }

    override fun getSupportedVoices(): List<Voice> {
        val voices = mutableListOf<Voice>()
        for (langCode in getSupportedLanguages()) {
            val locale = Locale.forLanguageTag(langCode)
            voices.add(
                Voice(
                    DEFAULT_VOICE,
                    locale,
                    Voice.QUALITY_NORMAL,
                    Voice.LATENCY_NORMAL,
                    true,
                    emptySet()
                )
            )
        }
        return voices
    }

    override fun getDefaultVoiceId(lang: String?, country: String?, variant: String?, currentVoiceId: String?): String {
        return currentVoiceId ?: DEFAULT_VOICE
    }

    override fun isVoiceIdCorrect(voiceId: String?): Boolean {
        return !voiceId.isNullOrBlank()
    }

    // ==================== 生命周期管理 ====================

    override fun stop() {
        logInfo("Stopping synthesis")
        isCancelled = true
        // 作废当前会话代际：停止后到达的残留回调被出口校验静默丢弃（P1-15）
        invalidateSynthesisSession()
        synthesisJob?.cancel()
        synthesisJob = null
    }

    override fun release() {
        logInfo("Releasing provider")
        isCancelled = true
        invalidateSynthesisSession()
        // 先取消在飞作业再关闭连接：合成协程可能挂起在 connectionMutex 上等待握手，
        // 取消使其立即释放锁，避免释放路径被阻塞到连接超时
        synthesisJob?.cancel()
        synthesisJob = null
        providerJob.cancel()
        // P2-B13：不在调用线程（主线程 onDestroy）runBlocking 等待优雅关闭帧完成——
        // WebSocket.close 本身非阻塞，关闭帧由 OkHttp 线程异步收尾；
        // 连接池仅 evictAll：dispatcher 线程池为 60s keepalive 的缓存池无需 shutdown，
        // 且 shutdown 会丢弃尚未发完的关闭帧
        // 连接关闭收口进 connectionMutex（与 getOrCreateConnection 的赋值路径互斥）：
        // 无锁直关时"握手恰在 release 期间返回"的协程会把新连接写入已被清空的字段，
        // 该连接此后无持有者关闭、还被 pingInterval 长期保活（泄漏）。在飞协程已被
        // 上方 cancel，awaitConnection 挂起点随即抛 CancellationException 释放锁，
        // 此处锁等待有界，不构成 P2-B13 所避免的关闭帧等待
        runBlocking {
            connectionMutex.withLock {
                runCatching { persistentWebSocket?.close(1000, "Provider released") }
                persistentWebSocket = null
                persistentListener = null
                isConnectionAlive = false
                idleTimeoutJob?.cancel()
                idleTimeoutJob = null
            }
        }
        client.connectionPool.evictAll()
        super.release()
    }

    override fun isConfigured(config: BaseProviderConfig?): Boolean {
        return isConfiguredAs(config) { _: AzureConfig -> true }
    }

    override fun createDefaultConfig(): BaseProviderConfig {
        return AzureConfig()
    }

    override fun createDefaultLanguage(): Array<String> {
        return arrayOf(Locale.SIMPLIFIED_CHINESE.isO3Language, Locale.SIMPLIFIED_CHINESE.isO3Country, "")
    }

    override val configLabels: Map<String, Int>
        get() = mapOf("voice_id" to R.string.voice_select_label)
}