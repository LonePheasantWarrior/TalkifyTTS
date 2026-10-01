package com.github.lonepheasantwarrior.talkify.service.provider.impl

import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.domain.model.LanguageBoost
import com.github.lonepheasantwarrior.talkify.domain.model.MiniMaxConfig
import com.github.lonepheasantwarrior.talkify.domain.model.ProviderIds
import com.github.lonepheasantwarrior.talkify.service.TtsErrorCode
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.service.provider.AbstractTtsProvider
import com.github.lonepheasantwarrior.talkify.service.provider.AudioConfig
import com.github.lonepheasantwarrior.talkify.service.provider.HexCodec
import com.github.lonepheasantwarrior.talkify.service.provider.MiniMaxErrorParser
import com.github.lonepheasantwarrior.talkify.service.provider.MiniMaxParamMapper
import com.github.lonepheasantwarrior.talkify.service.provider.Mp3StreamDecoder
import com.github.lonepheasantwarrior.talkify.service.provider.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.provider.TextChunkSplitter
import com.github.lonepheasantwarrior.talkify.service.provider.TtsSynthesisListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * MiniMax - 语音合成供应商实现（WebSocket 版）
 *
 * 继承 [AbstractTtsProvider]，实现 TTS 供应商接口
 * 基于 OkHttp WebSocket 实现流式音频合成，相比 HTTP 方案显著降低首字播放延迟
 *
 * 供应商 ID：miniMax
 * 供应商：MiniMax
 * API 文档：https://platform.minimaxi.com/docs/llms.txt
 */
class MiniMaxProvider : AbstractTtsProvider() {

    companion object {
        const val DEFAULT_WSS_URL = "wss://api.minimaxi.com/ws/v1/t2a_v2"

        private const val MAX_TEXT_LENGTH = 10000

        private const val PIPE_BUFFER_SIZE = 65536

        /** 协议事件等待超时（N8）：服务端 TCP 存活但不下发任何事件时的兜底口径，对齐腾讯云分块超时 */
        private const val PROTOCOL_IDLE_TIMEOUT_MS = 30_000L
    }

    private val providerJob = SupervisorJob()
    private val providerScope = CoroutineScope(Dispatchers.IO + providerJob)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // 长连接心跳：readTimeout(0) 下 NAT/代理静默断连只能靠 120s 服务层兜底，
        // 心跳让 OkHttp 主动发现死连接（对齐 Azure 的 20s，P2-B4）
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var isCancelled = false

    @Volatile
    private var hasCompleted = false

    @Volatile
    private var currentWebSocket: WebSocket? = null

    private var synthesisJob: Job? = null

    /**
     * 缓存的声音ID列表，从资源文件加载
     */
    override val voiceIds: List<String> by lazy {
        loadVoiceIdsFromXml(R.xml.minimax_voices)
    }

    override val fallbackVoiceId: String = "male-qn-qingse"

    override val configLabels: Map<String, Int> = mapOf(
        "api_key" to R.string.api_key_label,
        "continuous_sound" to R.string.label_continuous_sound
    )

    override val supportedLanguages: Array<String> = arrayOf("zho", "eng")

    override fun getProviderId(): String = ProviderIds.MiniMax.providerId

    override fun getProviderName(): String = ProviderIds.MiniMax.provider

    override fun getDefaultApiUrl(): String = DEFAULT_WSS_URL

    override fun getDefaultModelId(): String = ProviderIds.MiniMax.defaultModelId

    override fun getAudioConfig(): AudioConfig = AudioConfig.MINIMAX_TTS

    override fun synthesize(
        text: String,
        params: SynthesisParams,
        config: BaseProviderConfig,
        listener: TtsSynthesisListener
    ) {
        checkNotReleased()

        val miniMaxConfig = config as? MiniMaxConfig
        if (miniMaxConfig == null) {
            logError("Invalid config type, expected MiniMaxConfig")
            listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_PROVIDER_NOT_CONFIGURED))
            return
        }

        if (miniMaxConfig.apiKey.isEmpty()) {
            logError("API Key is not configured")
            listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_PROVIDER_NOT_CONFIGURED))
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

        logInfo("Starting synthesis: textLength=${text.length}, pitch=${params.pitch}, speechRate=${params.speechRate}, continuousSound=${miniMaxConfig.continuousSound}")

        // 会话代际：入口取消旧任务并捕获快照，全部回调出口校验（P1-15）
        val session = beginSynthesisSession()
        isCancelled = false
        hasCompleted = false

        synthesisJob?.cancel()
        synthesisJob = providerScope.launch {
            try {
                listener.onSynthesisStarted()
                performWebSocketSynthesis(text, miniMaxConfig, params, listener, session)
                if (isSynthesisSessionActive(session) && !isCancelled && !hasCompleted) {
                    hasCompleted = true
                    listener.onSynthesisCompleted()
                    logInfo("Synthesis completed successfully")
                }
            } catch (e: Exception) {
                // 入口取消上一会话时旧协程抛 CancellationException：会话已失效，
                // 不得误报为合成错误（P1-2 同族竞态）
                if (!isCancelled && e !is kotlinx.coroutines.CancellationException && isSynthesisSessionActive(session)) {
                    logError("Synthesis error", e)
                    listener.onError(e.message ?: "合成失败")
                }
            }
        }
    }

    /**
     * 通过 WebSocket 执行完整的语音合成流程
     */
    private suspend fun performWebSocketSynthesis(
        text: String,
        config: MiniMaxConfig,
        params: SynthesisParams,
        listener: TtsSynthesisListener,
        session: Long
    ) {
        val pipeClosed = AtomicBoolean(false)
        val pipedOutputStream = PipedOutputStream()
        val pipedInputStream = withContext(Dispatchers.IO) {
            PipedInputStream(pipedOutputStream, PIPE_BUFFER_SIZE)
        }

        val decodeJob = providerScope.launch(Dispatchers.Default) {
            decodeMp3Stream(pipedInputStream, listener, session)
        }

        val connectionDeferred = CompletableDeferred<WebSocket>()
        val taskStartedDeferred = CompletableDeferred<Unit>()
        val taskFinishedDeferred = CompletableDeferred<Unit>()
        val errorDeferred = CompletableDeferred<String>()

        val wsListener = MiniMaxWebSocketListener(
            pipedOutputStream = pipedOutputStream,
            pipeClosed = pipeClosed,
            connectionDeferred = connectionDeferred,
            taskStartedDeferred = taskStartedDeferred,
            taskFinishedDeferred = taskFinishedDeferred,
            errorDeferred = errorDeferred,
            config = config,
            params = params
        )

        try {
            val request = Request.Builder()
                .url(config.apiUrl.ifBlank { DEFAULT_WSS_URL })
                .header("Authorization", "Bearer ${config.apiKey}")
                .build()

            currentWebSocket = client.newWebSocket(request, wsListener)

            val webSocket = connectionDeferred.await()

            if (isCancelled) {
                webSocket.close(1000, "Cancelled")
                return
            }

            wsListener.sendTaskStart(webSocket)

            taskStartedDeferred.await()

            if (isCancelled) {
                webSocket.close(1000, "Cancelled")
                return
            }

            val textChunks = TextChunkSplitter.split(text, MAX_TEXT_LENGTH)
            logDebug("Text split into ${textChunks.size} chunks for WebSocket streaming")

            wsListener.sendTextChunks(webSocket, textChunks)

            // P1-2：任务完成优先。服务端 task_finished 后通常立即关闭连接，
            // "连接关闭"错误与完成信号几乎同时就绪，select 在多子句同时就绪时
            // 随机选择，会以约 50% 概率把成功合成误报为错误。改为确定性判定：
            // taskFinishedDeferred 仅由 task_finished 消息正常完成（completeAllDeferred
            // 不再将其标记为异常完成），它已就绪（含 select 竞态后复核）一律按成功收尾。
            // N8：select 包超时——服务端 TCP 存活但协议挂起（连接后不再下发任何
            // 事件）时此处原本永久挂起，仅靠服务层 120s 兜底；超时口径对齐腾讯云
            // 分块超时（30s），超时后按错误收尾
            val finishedNormally = if (taskFinishedDeferred.isCompleted) {
                true
            } else {
                withTimeoutOrNull(PROTOCOL_IDLE_TIMEOUT_MS) {
                    select {
                        taskFinishedDeferred.onAwait { true }
                        errorDeferred.onAwait { false }
                    }
                } ?: false
            } || taskFinishedDeferred.isCompleted

            if (finishedNormally) {
                // 正常完成后显式关闭连接：readTimeout(0) 使空闲连接永不超时，
                // 不关闭则每次成功合成都遗留一条空闲连接（P1-2/P2-B4）
                runCatching { webSocket.close(1000, "Done") }
            } else {
                // N8：errorDeferred 未就绪即 select 超时（协议挂起），直接取超时文案，
                // 不能再 await()（将永久挂起）；主动断开残留连接——readTimeout(0) 下
                // 该连接不会自愈
                val timedOut = !errorDeferred.isCompleted
                val errorMsg = if (timedOut) "语音合成超时，请稍后重试" else errorDeferred.await()
                logError("WebSocket task failed: $errorMsg")
                // G2/N1：出口校验——stop() 作废会话后 errorDeferred 的迟到错误不得穿透
                // （对照同方法外层 catch 的同名校验，此处此前漏配）
                if (isSynthesisSessionActive(session)) {
                    listener.onError(errorMsg)
                    // 错误即本场终态：作废会话——外层不再补发 onSynthesisCompleted
                    // （避免 onError 后跟 completed 的双重终态），同时静默 decode 残留回调
                    invalidateSynthesisSession()
                }
                if (timedOut) {
                    runCatching { webSocket.close(1000, "Protocol timeout") }
                }
            }
        } catch (e: Exception) {
            if (!isCancelled && e !is kotlinx.coroutines.CancellationException && isSynthesisSessionActive(session)) {
                logError("WebSocket synthesis error", e)
                listener.onError(e.message ?: "WebSocket连接失败")
                // 错误即本场终态：作废会话，防止外层补发 onSynthesisCompleted（双重终态）
                invalidateSynthesisSession()
            }
        } finally {
            // 非挂起关闭：协程被 stop() 取消时 withContext 会直接抛 CancellationException，
            // 导致管道永不关闭、解码线程永久阻塞在 readFrame（线程/管道泄漏）
            runCatching { pipedOutputStream.flush() }
            runCatching { pipedOutputStream.close() }
            pipeClosed.set(true)
            decodeJob.join()
            // 仅当本会话仍是当前会话才清空引用：旧任务的 finally 可能晚于新任务的
            // 赋值执行，无条件置 null 会抹掉新会话的连接引用（P3-24）
            if (isSynthesisSessionActive(session)) {
                currentWebSocket = null
            }
        }
    }

    /**
     * WebSocket 事件监听器
     */
    inner class MiniMaxWebSocketListener(
        private val pipedOutputStream: PipedOutputStream,
        private val pipeClosed: AtomicBoolean,
        private val connectionDeferred: CompletableDeferred<WebSocket>,
        private val taskStartedDeferred: CompletableDeferred<Unit>,
        private val taskFinishedDeferred: CompletableDeferred<Unit>,
        private val errorDeferred: CompletableDeferred<String>,
        private val config: MiniMaxConfig,
        private val params: SynthesisParams
    ) : WebSocketListener() {

        private val voiceId: String by lazy {
            if (config.voiceId.isNotEmpty()) {
                extractRealVoiceName(config.voiceId) ?: config.voiceId
            } else {
                resolveVoiceForLanguage(config.voiceId, params.language)
            }
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            logDebug("WebSocket connected: ${response.code}")
            connectionDeferred.complete(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (pipeClosed.get() || isCancelled) return

            try {
                val json = JSONObject(text)
                val event = json.optString("event", "")

                when (event) {
                    "connected_success" -> {
                        val baseResp = json.optJSONObject("base_resp")
                        val statusCode = baseResp?.optInt("status_code", -1) ?: -1
                        if (statusCode != 0) {
                            val statusMsg = baseResp?.optString("status_msg", "") ?: ""
                            val errorMsg = MiniMaxErrorParser.parse(statusCode, statusMsg)
                            logError("connected_success error: $errorMsg")
                            if (!errorDeferred.isCompleted) {
                                errorDeferred.complete(errorMsg)
                            }
                        } else {
                            logDebug("Received connected_success, session_id=${json.optString("session_id")}")
                        }
                    }
                    "task_started" -> {
                        val baseResp = json.optJSONObject("base_resp")
                        val statusCode = baseResp?.optInt("status_code", -1) ?: -1
                        if (statusCode != 0) {
                            val statusMsg = baseResp?.optString("status_msg", "") ?: ""
                            val errorMsg = MiniMaxErrorParser.parse(statusCode, statusMsg)
                            logError("task_started error: $errorMsg")
                            if (!errorDeferred.isCompleted) {
                                errorDeferred.complete(errorMsg)
                            }
                        } else {
                            logDebug("Received task_started")
                            if (!taskStartedDeferred.isCompleted) {
                                taskStartedDeferred.complete(Unit)
                            }
                        }
                    }
                    "task_continued" -> {
                        handleTaskContinued(json)
                    }
                    "task_finished" -> {
                        logDebug("Received task_finished")
                        if (!taskFinishedDeferred.isCompleted) {
                            taskFinishedDeferred.complete(Unit)
                        }
                    }
                    "task_failed" -> {
                        val baseResp = json.optJSONObject("base_resp")
                        val statusCode = baseResp?.optInt("status_code", -1) ?: -1
                        val statusMsg = baseResp?.optString("status_msg", "") ?: ""
                        val errorMsg = MiniMaxErrorParser.parse(statusCode, statusMsg)
                        logError("Received task_failed: $errorMsg")
                        if (!errorDeferred.isCompleted) {
                            errorDeferred.complete(errorMsg)
                        }
                    }
                }
            } catch (e: Exception) {
                // N19-b：帧体可能携带 hex 音频（用户文本的语音呈现），release 下也
                // 不整帧进 logcat——只记事件类型与帧长，原文仅在 debug 门控下输出
                val eventType = runCatching { JSONObject(text).optString("event", "?") }.getOrDefault("?")
                logError("Error processing WebSocket message: event=$eventType, length=${text.length}")
                TtsLogger.d("$tag: failed frame body: $text")
            }
        }

        /**
         * 处理 WebSocket 的 task_continued 事件
         *
         * 解析 JSON 中的 hex 编码 MP3 音频数据，解码后写入管道输出流
         *
         * @param json WebSocket 接收到的 JSON 消息
         */
        private fun handleTaskContinued(json: JSONObject) {
            val baseResp = json.optJSONObject("base_resp")
            if (baseResp != null) {
                val statusCode = baseResp.optInt("status_code", 0)
                if (statusCode != 0) {
                    val statusMsg = baseResp.optString("status_msg", "")
                    logError("task_continued error: status_code=$statusCode, status_msg=$statusMsg")
                    if (!errorDeferred.isCompleted) {
                        errorDeferred.complete(MiniMaxErrorParser.parse(statusCode, statusMsg))
                    }
                    return
                }
            }

            val dataObj = json.optJSONObject("data")
            if (dataObj != null) {
                val audioHex = dataObj.optString("audio", "")
                if (audioHex.isNotBlank()) {
                    val mp3Bytes = HexCodec.decode(audioHex)
                    if (mp3Bytes.isNotEmpty() && !pipeClosed.get()) {
                        try {
                            pipedOutputStream.write(mp3Bytes)
                        } catch (e: Exception) {
                            logDebug("Pipe write error: ${e.message}")
                        }
                    } else if (mp3Bytes.isEmpty()) {
                        logWarning("Audio hex decode failed, length=${audioHex.length}")
                    }
                }
            }

            val isFinal = json.optBoolean("is_final", false)
            if (isFinal) {
                val extraInfo = json.optJSONObject("extra_info")
                if (extraInfo != null) {
                    logDebug(
                        "Chunk complete: audio_length=${extraInfo.optInt("audio_length")}ms, " +
                                "usage_characters=${extraInfo.optInt("usage_characters")}"
                    )
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            logError("WebSocket failure", t as? Exception ?: Exception(t))

            val errorMsg = when {
                response != null -> "WebSocket连接失败: HTTP ${response.code}"
                t.message?.contains("401", true) == true -> "鉴权失败，请检查 API Key"
                else -> "WebSocket连接失败: ${t.message}"
            }

            if (!connectionDeferred.isCompleted) {
                connectionDeferred.completeExceptionally(t)
            }
            if (!errorDeferred.isCompleted) {
                errorDeferred.complete(errorMsg)
            }
            completeAllDeferred(errorMsg)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            logDebug("WebSocket closing: code=$code, reason=$reason")
            completeAllDeferred("连接关闭: $reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            logDebug("WebSocket closed: code=$code, reason=$reason")
            completeAllDeferred("连接已关闭")
        }

        /**
         * 标记所有 Deferred 为已完成并关闭管道
         *
         * 在连接异常关闭或出错时统一处理所有异步状态
         *
         * @param errorMsg 错误消息
         */
        private fun completeAllDeferred(errorMsg: String) {
            pipeClosed.set(true)
            try { pipedOutputStream.close() } catch (_: Exception) {}

            if (!errorDeferred.isCompleted) {
                errorDeferred.complete(errorMsg)
            }
            if (!taskStartedDeferred.isCompleted) {
                taskStartedDeferred.completeExceptionally(Exception(errorMsg))
            }
            // 注意：不再将 taskFinishedDeferred 标记为异常完成——它是 select 判定
            // "任务正常完成"的唯一信号（连接关闭/失败经 errorDeferred 传递，P1-2）
        }

        /**
         * 发送 WebSocket 任务启动消息
         *
         * 构建 task_start JSON 消息，包含音色设置、音频格式等参数
         *
         * @param webSocket 已连接的 WebSocket 实例
         */
        fun sendTaskStart(webSocket: WebSocket) {
            val speed = MiniMaxParamMapper.convertSpeechRate(params.speechRate)
            val vol = MiniMaxParamMapper.convertVolume(params.volume)
            val pitch = ((params.pitch - 100f) * 12f / 100f).roundToInt().coerceIn(-12, 12)

            val effectiveModel = config.modelId.ifBlank { getDefaultModelId() }
            val message = JSONObject().apply {
                put("event", "task_start")
                put("model", effectiveModel)
                config.continuousSound?.let { put("continuous_sound", it) }
                put("voice_setting", JSONObject().apply {
                    put("voice_id", voiceId)
                    put("speed", speed)
                    put("vol", vol)
                    put("pitch", pitch)
                })
                put("audio_setting", JSONObject().apply {
                    put("sample_rate", getAudioConfig().sampleRate)
                    put("bitrate", 128000)
                    put("format", "mp3")
                    put("channel", getAudioConfig().channelCount)
                })
                if (config.languageBoost != LanguageBoost.OFF) {
                    put("language_boost", config.languageBoost.apiValue)
                }
                if (config.englishNormalization) {
                    put("english_normalization", true)
                }
            }

            logInfo("Sending task_start: voice=$voiceId, speed=$speed, vol=$vol, pitch=$pitch, continuousSound=${config.continuousSound}, languageBoost=${config.languageBoost}, englishNormalization=${config.englishNormalization}")
            logInfo("task_start body: ${message.toString(2)}")
            webSocket.send(message.toString())
        }

        /**
         * 通过 WebSocket 流式发送文本片段
         *
         * 逐个发送 task_continue 消息，最后发送 task_finish 结束信号
         *
         * @param webSocket 已连接的 WebSocket 实例
         * @param chunks 分割后的文本片段列表
         */
        fun sendTextChunks(webSocket: WebSocket, chunks: List<String>) {
            for ((index, chunk) in chunks.withIndex()) {
                if (isCancelled || pipeClosed.get()) break

                val message = JSONObject().apply {
                    put("event", "task_continue")
                    put("text", chunk)
                }

                logDebug("Sending task_continue ${index + 1}/${chunks.size}, length=${chunk.length}")
                webSocket.send(message.toString())
            }

            if (!isCancelled && !pipeClosed.get()) {
                val finishMessage = JSONObject().apply {
                    put("event", "task_finish")
                }
                logDebug("Sending task_finish")
                webSocket.send(finishMessage.toString())
            }
        }
    }

    /**
     * 解码 MP3 流并输出 PCM 音频数据
     *
     * 从管道输入流读取 MP3 帧，使用 JLayer 逐帧解码为 PCM，
     * 并通过 [listener] 回调输出音频数据
     *
     * @param inputStream 管道输入流，由 WebSocket 线程写入 MP3 数据
     * @param listener 音频合成监听器，接收解码后的 PCM 数据
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
                channelCount  // 使用 JLayer 实际解码的声道数
            )
        }
    }

    /**
     * 根据语言解析对应的默认音色
     *
     * 当用户未指定音色时，根据目标语言选取合适的默认音色：
     * - 中文语言：使用 [male-qn-qingse]
     * - 英语语言：使用 [English_Graceful_Lady]
     * - 其他语言：回退到通用默认值
     *
     * @param voiceId 用户指定的音色 ID，为空时使用语言匹配的默认值
     * @param language 目标语言代码（zho/eng 等）
     * @return 解析后的音色 ID
     */
    private fun resolveVoiceForLanguage(voiceId: String, language: String?): String {
        if (voiceId.isNotBlank() && voiceIds.contains(voiceId)) {
            return voiceId
        }
        return when (language?.lowercase()) {
            "zh", "zho", "chi", "cn" -> "male-qn-qingse"
            "en", "eng" -> "English_Graceful_Lady"
            else -> voiceId.ifBlank { "male-qn-qingse" }
        }
    }

    /**
     * 停止当前语音合成
     *
     * 关闭 WebSocket 连接并取消合成协程
     */
    override fun stop() {
        logInfo("Stopping synthesis")
        isCancelled = true
        // 作废当前会话代际：停止后到达的残留回调被出口校验静默丢弃（P1-15）
        invalidateSynthesisSession()
        currentWebSocket?.close(1000, "User cancelled")
        currentWebSocket = null
        synthesisJob?.cancel()
        synthesisJob = null
        hasCompleted = false
    }

    /**
     * 释放供应商资源
     *
     * 关闭 WebSocket 连接、取消协程并释放底层资源
     */
    override fun release() {
        logInfo("Releasing provider")
        isCancelled = true
        currentWebSocket?.close(1000, "Provider released")
        currentWebSocket = null
        synthesisJob?.cancel()
        synthesisJob = null
        providerJob.cancel()
        // 释放独立持有的 OkHttp 连接池与调度线程池（本类未复用全局共享客户端）
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        super.release()
    }

    /**
     * 检查供应商是否已完成配置
     *
     * 验证 API Key 是否已填写
     */
    override fun isConfigured(config: BaseProviderConfig?): Boolean {
        return isConfiguredAs(config) { c: MiniMaxConfig -> c.apiKey.isNotBlank() }
    }

    /**
     * 创建默认供应商配置
     */
    override fun createDefaultConfig(): BaseProviderConfig {
        return MiniMaxConfig()
    }
}
