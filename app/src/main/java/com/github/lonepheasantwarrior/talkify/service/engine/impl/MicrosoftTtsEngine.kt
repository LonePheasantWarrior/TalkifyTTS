package com.github.lonepheasantwarrior.talkify.service.engine.impl

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.speech.tts.Voice
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.BaseEngineConfig
import com.github.lonepheasantwarrior.talkify.domain.model.MicrosoftTtsConfig
import com.github.lonepheasantwarrior.talkify.service.TtsErrorCode
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.service.engine.AbstractTtsEngine
import com.github.lonepheasantwarrior.talkify.service.engine.AudioConfig
import com.github.lonepheasantwarrior.talkify.service.engine.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.engine.TtsSynthesisListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * 微软语音合成引擎实现
 *
 * 继承 [AbstractTtsEngine]，实现 TTS 引擎接口
 * 使用 WebSocket 连接到微软 Edge TTS 服务
 *
 * 引擎 ID：microsoft-tts
 * 服务提供商：Azure
 */
class MicrosoftTtsEngine : AbstractTtsEngine() {

    companion object {
        const val ENGINE_ID = "microsoft-tts"
        const val ENGINE_NAME = "微软语音合成"

        private const val BASE_URL = "speech.platform.bing.com/consumer/speech/synthesize/readaloud"
        private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val WSS_URL = "wss://$BASE_URL/edge/v1?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"
        private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
        private const val CHROMIUM_MAJOR_VERSION = "143"
        private const val SEC_MS_GEC_VERSION = "1-$CHROMIUM_FULL_VERSION"

        private const val WIN_EPOCH = 11644473600L
        private const val S_TO_NS = 1_000_000_000L

        private const val DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"
        private const val MAX_TEXT_LENGTH = 4096

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

        private fun dateToString(): String {
            val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            return sdf.format(Date())
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

        private fun splitTextByByteLength(text: String, maxBytes: Int): List<String> {
            val chunks = mutableListOf<String>()
            val utf8Bytes = text.toByteArray(Charsets.UTF_8)
            var offset = 0
            while (offset < utf8Bytes.size) {
                var end = min(offset + maxBytes, utf8Bytes.size)
                end = findSafeUtf8SplitPoint(utf8Bytes, end)
                end = findBestSplitPoint(utf8Bytes, offset, end)
                if (end <= offset) {
                    end = min(offset + maxBytes, utf8Bytes.size)
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

        private fun findSafeUtf8SplitPoint(bytes: ByteArray, end: Int): Int {
            var splitAt = end
            while (splitAt > 0) {
                try {
                    String(bytes, 0, splitAt, Charsets.UTF_8)
                    return splitAt
                } catch (_: Exception) {
                    splitAt--
                }
            }
            return splitAt
        }

        private fun findBestSplitPoint(bytes: ByteArray, start: Int, end: Int): Int {
            val subBytes = if (end <= bytes.size) bytes.copyOfRange(0, end) else bytes
            var splitAt = subBytes.lastIndexOf('\n'.code.toByte())
            if (splitAt >= start) {
                return splitAt + 1
            }
            splitAt = subBytes.lastIndexOf(' '.code.toByte())
            if (splitAt >= start) {
                return splitAt + 1
            }
            return end
        }
    }

    @Volatile
    private var currentWebSocket: WebSocket? = null

    @Volatile
    private var isCancelled = false

    @Volatile
    private var hasCompleted = false

    @Volatile
    private var audioBuffer = ByteArrayOutputStream()

    @Volatile
    private var synthesisJob: Job? = null

    private val client = OkHttpClient()

    val audioConfig: AudioConfig
        @JvmName("getAudioConfigProperty") get() = AudioConfig.MICROSOFT_TTS

    override fun getEngineId(): String = ENGINE_ID

    override fun getEngineName(): String = ENGINE_NAME

    override fun synthesize(
        text: String, params: SynthesisParams, config: BaseEngineConfig, listener: TtsSynthesisListener
    ) {
        checkNotReleased()

        val msConfig = config as? MicrosoftTtsConfig
        if (msConfig == null) {
            logError("Invalid config type, expected MicrosoftTtsConfig")
            listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_ENGINE_NOT_CONFIGURED))
            return
        }

        val cleanedText = removeIncompatibleCharacters(text)
        val textChunks = splitTextByByteLength(cleanedText, MAX_TEXT_LENGTH)

        if (textChunks.isEmpty()) {
            logWarning("待朗读文本内容为空")
            listener.onSynthesisCompleted()
            return
        }

        logInfo("Starting Microsoft TTS synthesis: textLength=${text.length}, chunks=${textChunks.size}")

        isCancelled = false
        hasCompleted = false
        audioBuffer.reset()

        synthesisJob = GlobalScope.launch(Dispatchers.IO) {
            try {
                processChunks(textChunks, params, msConfig, listener)
            } catch (e: Exception) {
                logError("Synthesis error", e)
                listener.onError("合成失败：${e.message}")
            }
        }
    }

    private suspend fun processChunks(
        chunks: List<String>,
        params: SynthesisParams,
        config: MicrosoftTtsConfig,
        listener: TtsSynthesisListener
    ) {
        listener.onSynthesisStarted()

        for ((index, chunk) in chunks.withIndex()) {
            if (isCancelled) {
                break
            }
            logDebug("Processing chunk ${index + 1}/${chunks.size}")
            synthesizeChunk(chunk, params, config, listener)
        }

        if (!isCancelled) {
            decodeAndPlayMp3(listener)
            listener.onSynthesisCompleted()
        }
    }

    private fun synthesizeChunk(
        text: String,
        params: SynthesisParams,
        config: MicrosoftTtsConfig,
        listener: TtsSynthesisListener
    ) {
        val done = AtomicBoolean(false)
        val error = AtomicBoolean(false)
        val lock = Object()

        val voice = if (config.voiceId.isNotEmpty()) config.voiceId else DEFAULT_VOICE
        val rate = convertRate(params.speechRate)
        val volume = convertVolume(params.volume)
        val pitch = convertPitch(params.pitch)

        val connectionId = connectId()
        val url = "$WSS_URL&ConnectionId=$connectionId" +
                "&Sec-MS-GEC=${generateSecMsGec()}&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"

        val requestBuilder = Request.Builder()
            .url(url)
        getHeadersWithMuid().forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }
        val request = requestBuilder.build()

        currentWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                logDebug("WebSocket connected")
                try {
                    sendConfigMessage(webSocket)
                    sendSsmlMessage(webSocket, voice, rate, volume, pitch, text)
                } catch (e: Exception) {
                    logError("Error sending messages", e)
                    error.set(true)
                    synchronized(lock) {
                        done.set(true)
                        lock.notify()
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val data = bytes.toByteArray()
                    if (data.size >= 2) {
                        val headerLength = data.copyOfRange(0, 2).let {
                            (it[0].toInt() and 0xFF) shl 8 or (it[1].toInt() and 0xFF)
                        }
                        if (headerLength + 2 <= data.size) {
                            val headerData = data.copyOfRange(2, 2 + headerLength)
                            val audioData = data.copyOfRange(2 + headerLength, data.size)
                            val headers = parseHeaders(headerData)
                            if (headers["Path"] == "audio" && audioData.isNotEmpty()) {
                                audioBuffer.write(audioData)
                            }
                        }
                    }
                } catch (e: Exception) {
                    logError("Error processing binary message", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val headerEnd = text.indexOf("\r\n\r\n")
                    if (headerEnd != -1) {
                        val headerText = text.substring(0, headerEnd)
                        val headers = parseHeaders(headerText.toByteArray())
                        if (headers["Path"] == "turn.end") {
                            synchronized(lock) {
                                done.set(true)
                                lock.notify()
                            }
                        }
                    }
                } catch (e: Exception) {
                    logError("Error processing text message", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                logDebug("WebSocket closing: $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                logDebug("WebSocket closed: $code $reason")
                synchronized(lock) {
                    if (!done.get()) {
                        done.set(true)
                        lock.notify()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                logError("WebSocket error", t)
                error.set(true)
                synchronized(lock) {
                    done.set(true)
                    lock.notify()
                }
            }
        })

        synchronized(lock) {
            while (!done.get()) {
                try {
                    lock.wait(60000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }

        currentWebSocket?.close(1000, "Done")
        currentWebSocket = null

        if (error.get()) {
            throw Exception("WebSocket error")
        }
    }

    private fun sendConfigMessage(webSocket: WebSocket) {
        val configMessage = "X-Timestamp:${dateToString()}\r\n" +
                "Content-Type:application/json; charset=utf-8\r\n" +
                "Path:speech.config\r\n\r\n" +
                "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
                "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
        webSocket.send(configMessage)
    }

    private fun sendSsmlMessage(
        webSocket: WebSocket,
        voice: String,
        rate: String,
        volume: String,
        pitch: String,
        text: String
    ) {
        val ssml = mkssml(voice, rate, volume, pitch, text)
        val message = ssmlHeadersPlusData(connectId(), dateToString(), ssml)
        webSocket.send(message)
    }

    private fun parseHeaders(data: ByteArray): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        val headerStr = String(data, Charsets.UTF_8)
        val lines = headerStr.split("\r\n")
        for (line in lines) {
            val colonPos = line.indexOf(':')
            if (colonPos != -1) {
                val key = line.substring(0, colonPos).trim()
                val value = line.substring(colonPos + 1).trim()
                headers[key] = value
            }
        }
        return headers
    }

    private fun convertRate(speechRate: Float): String {
        val ratePercent = ((speechRate - 100) / 100 * 100).toInt()
        return if (ratePercent >= 0) "+${ratePercent}%" else "${ratePercent}%"
    }

    private fun convertVolume(volume: Float): String {
        val volumePercent = (volume * 100 - 100).toInt()
        return if (volumePercent >= 0) "+${volumePercent}%" else "${volumePercent}%"
    }

    private fun convertPitch(pitch: Float): String {
        val pitchHz = ((pitch - 100) / 100 * 50).toInt()
        return if (pitchHz >= 0) "+${pitchHz}Hz" else "${pitchHz}Hz"
    }

    private fun decodeAndPlayMp3(listener: TtsSynthesisListener) {
        val mp3Data = audioBuffer.toByteArray()
        if (mp3Data.isEmpty()) {
            return
        }

        try {
            val tempFile = File.createTempFile("talkify-", ".mp3")
            try {
                tempFile.writeBytes(mp3Data)
                val extractor = MediaExtractor()
                extractor.setDataSource(tempFile.absolutePath)

                var audioTrackIndex = -1
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME)
                    if (mime?.startsWith("audio/") == true) {
                        audioTrackIndex = i
                        break
                    }
                }

                if (audioTrackIndex == -1) {
                    throw Exception("No audio track found")
                }

                extractor.selectTrack(audioTrackIndex)
                val format = extractor.getTrackFormat(audioTrackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"

                val codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()

                val bufferInfo = MediaCodec.BufferInfo()
                val timeoutUs = 10000L
                var inputDone = false
                var outputDone = false

                while (!outputDone && !isCancelled) {
                    if (!inputDone) {
                        val inputBufferIndex = codec.dequeueInputBuffer(timeoutUs)
                        if (inputBufferIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                            inputBuffer?.let {
                                val sampleSize = extractor.readSampleData(it, 0)
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(
                                        inputBufferIndex, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(
                                        inputBufferIndex, 0, sampleSize,
                                        extractor.sampleTime, 0
                                    )
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    while (outputBufferIndex >= 0 && !isCancelled) {
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                        outputBuffer?.let {
                            if (bufferInfo.size > 0) {
                                val pcmData = ByteArray(bufferInfo.size)
                                it.get(pcmData)
                                listener.onAudioAvailable(
                                    pcmData,
                                    audioConfig.sampleRate,
                                    audioConfig.audioFormat,
                                    audioConfig.channelCount
                                )
                            }
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                        outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    }

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }

                codec.stop()
                codec.release()
                extractor.release()

            } finally {
                tempFile.delete()
            }

        } catch (e: Exception) {
            logError("Error decoding MP3", e)
            throw e
        }
    }

    override fun getSupportedLanguages(): Set<String> {
        return SUPPORTED_LANGUAGES.toSet()
    }

    override fun getDefaultLanguages(): Array<String> {
        return arrayOf(Locale.SIMPLIFIED_CHINESE.isO3Language, Locale.SIMPLIFIED_CHINESE.isO3Country, "")
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
        return voiceId != null && voiceId.isNotBlank()
    }

    override fun stop() {
        logInfo("Stopping synthesis")
        isCancelled = true
        synthesisJob?.cancel()
        synthesisJob = null
        currentWebSocket?.close(1000, "Stop requested")
        currentWebSocket = null
    }

    override fun release() {
        logInfo("Releasing engine")
        isCancelled = true
        synthesisJob?.cancel()
        synthesisJob = null
        currentWebSocket?.close(1000, "Release")
        currentWebSocket = null
        super.release()
    }

    override fun isConfigured(config: BaseEngineConfig?): Boolean {
        val msConfig = config as? MicrosoftTtsConfig
        var result = false
        if (msConfig != null) {
            result = true
        }
        TtsLogger.d("$tag: isConfigured = $result")
        return result
    }

    override fun createDefaultConfig(): BaseEngineConfig {
        return MicrosoftTtsConfig()
    }

    override fun getConfigLabel(configKey: String, context: Context): String? {
        return when (configKey) {
            "voice_id" -> context.getString(R.string.voice_select_label)
            else -> null
        }
    }
}
