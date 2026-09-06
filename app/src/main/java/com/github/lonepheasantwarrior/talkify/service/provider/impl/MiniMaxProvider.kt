package com.github.lonepheasantwarrior.talkify.service.provider.impl

import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.domain.model.LanguageBoost
import com.github.lonepheasantwarrior.talkify.domain.model.MiniMaxConfig
import com.github.lonepheasantwarrior.talkify.domain.model.ProviderIds
import com.github.lonepheasantwarrior.talkify.service.TtsErrorCode
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
    }

    private val providerJob = SupervisorJob()
    private val providerScope = CoroutineScope(Dispatchers.IO + providerJob)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
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

        isCancelled = false
        hasCompleted = false

        synthesisJob?.cancel()
        synthesisJob = providerScope.launch {
            try {
                listener.onSynthesisStarted()
                performWebSocketSynthesis(text, miniMaxConfig, params, listener)
                if (!isCancelled && !hasCompleted) {
                    hasCompleted = true
                    listener.onSynthesisCompleted()
                }
                logInfo("Synthesis completed successfully")
            } catch (e: Exception) {
                if (!isCancelled) {
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
        listener: TtsSynthesisListener
    ) {
        val pipeClosed = AtomicBoolean(false)
        val pipedOutputStream = PipedOutputStream()
        val pipedInputStream = withContext(Dispatchers.IO) {
            PipedInputStream(pipedOutputStream, PIPE_BUFFER_SIZE)
        }

        val decodeJob = providerScope.launch(Dispatchers.Default) {
            decodeMp3Stream(pipedInputStream, listener)
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

            select {
                taskFinishedDeferred.onAwait { }
                errorDeferred.onAwait { errorMsg ->
                    logError("WebSocket task failed: $errorMsg")
                    listener.onError(errorMsg)
                }
            }
        } catch (e: Exception) {
            if (!isCancelled) {
                logError("WebSocket synthesis error", e)
                listener.onError(e.message ?: "WebSocket连接失败")
            }
        } finally {
            // 非挂起关闭：协程被 stop() 取消时 withContext 会直接抛 CancellationException，
            // 导致管道永不关闭、解码线程永久阻塞在 readFrame（线程/管道泄漏）
            runCatching { pipedOutputStream.flush() }
            runCatching { pipedOutputStream.close() }
            pipeClosed.set(true)
            decodeJob.join()
            currentWebSocket = null
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
                logError("Error processing WebSocket message: $text", e)
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
            if (!taskFinishedDeferred.isCompleted) {
                taskFinishedDeferred.completeExceptionally(Exception(errorMsg))
            }
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
            val emotion = resolveEmotion(params)

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
                    if (emotion.isNotBlank()) {
                        put("emotion", emotion)
                    }
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
     */
    private fun decodeMp3Stream(inputStream: PipedInputStream, listener: TtsSynthesisListener) {
        Mp3StreamDecoder.decodeMp3Stream(
            inputStream,
            isCancelled = { isCancelled }
        ) { pcmBytes, sampleRate, channelCount ->
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
     * 解析合成参数中的情感设置
     *
     * 预留接口，当前返回空字符串表示不设置情感参数
     *
     * @param params 合成参数
     * @return 情感标识字符串，空字符串表示不设置
     */
    private fun resolveEmotion(params: SynthesisParams): String {
        return ""
    }


    /**
     * 停止当前语音合成
     *
     * 关闭 WebSocket 连接并取消合成协程
     */
    override fun stop() {
        logInfo("Stopping synthesis")
        isCancelled = true
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

    /**
     * 获取配置项的中文标签
     */
    override fun getConfigLabel(configKey: String, context: android.content.Context): String? {
        return when (configKey) {
            "continuous_sound" -> "合成配置"
            else -> super.getConfigLabel(configKey, context)
        }
    }
}
