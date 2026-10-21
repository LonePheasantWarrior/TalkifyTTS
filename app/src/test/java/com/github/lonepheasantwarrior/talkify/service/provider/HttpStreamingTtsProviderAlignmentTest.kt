package com.github.lonepheasantwarrior.talkify.service.provider

import android.media.AudioFormat
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 走完整 synthesize 流水线的帧对齐集成测试：
 * 验证生产代码为每个 fetch 协程注入独立的 [PcmAlignmentElement]，
 * 而不是仅验证 [PcmFrameAssembler] 自身的拼接逻辑。
 */
class HttpStreamingTtsProviderAlignmentTest {

    @Test
    fun `production pipeline aligns each chunk with an isolated assembler`() {
        val provider = AlignmentTestProvider(readBufferSize = 3)
        val listener = CollectingListener()
        try {
            provider.synthesize("测试测试", SynthesisParams(), TestConfig(), listener)

            assertTrue(
                "synthesis did not complete, errors=${listener.errors}",
                listener.completion.await(10, TimeUnit.SECONDS)
            )
            assertTrue("unexpected errors: ${listener.errors}", listener.errors.isEmpty())
            assertTrue(listener.started.get())

            assertEquals(
                listOf(Triple(24000, AudioFormat.ENCODING_PCM_16BIT, 1)),
                listener.formats.distinct()
            )

            // 每块响应 5 字节、读取边界 3 字节、帧长 2 字节。若每个 fetch
            // 各持一个对齐器，每块只输出前 4 字节（末尾半帧丢弃）；
            // 若对齐器被跨块共享，奇字节日志会串到相邻块，输出必然不同。
            val flattened = listener.audio.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
            assertArrayEquals(
                byteArrayOf(0, 1, 2, 3, 10, 11, 12, 13),
                flattened
            )
            assertTrue(
                "every emitted chunk must contain whole frames: " +
                    listener.audio.map { it.size },
                listener.audio.all { it.size % 2 == 0 }
            )
        } finally {
            provider.release()
        }
    }

    private class TestConfig : BaseProviderConfig()

    private class AlignmentTestProvider(
        private val readBufferSize: Int
    ) : HttpStreamingTtsProvider(callbackDispatcher = Dispatchers.Unconfined) {

        override val chunkMaxLength: Int = 3

        override fun getProviderId(): String = "alignment-test"

        override fun getProviderName(): String = "Alignment Test"

        override fun isConfigured(config: BaseProviderConfig?): Boolean = true

        override fun createDefaultConfig(): BaseProviderConfig = TestConfig()

        override fun getAudioConfig(): AudioConfig = AudioConfig.OPENAI_TTS

        override fun validateConfig(config: BaseProviderConfig): String? = null

        override fun buildHttpRequest(
            text: String,
            config: BaseProviderConfig,
            params: SynthesisParams
        ): Request = Request.Builder()
            .url("https://tts.test/v1/audio/speech")
            // OkHttp 请求头只接受 ASCII，用块标记驱动假响应的内容
            .header(CHUNK_HEADER, if (text == "测试测") FIRST_CHUNK else SECOND_CHUNK)
            .build()

        override suspend fun processStreamResponse(
            response: Response,
            chunkIndex: Int,
            config: BaseProviderConfig,
            params: SynthesisParams,
            listener: TtsSynthesisListener
        ): Boolean {
            val body = response.body ?: return false
            val buffer = ByteArray(readBufferSize)
            body.byteStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    // 模拟真实供应商：HTTP 读取边界任意，直接交给生产 emitAudio
                    emitAudio(buffer.copyOf(read), listener)
                }
            }
            return true
        }

        override fun mapHttpError(errorBody: String): String = errorBody

        override fun httpClientFor(config: BaseProviderConfig): OkHttpClient =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val payload = if (chain.request().header(CHUNK_HEADER) == FIRST_CHUNK) {
                        byteArrayOf(0, 1, 2, 3, 4)
                    } else {
                        byteArrayOf(10, 11, 12, 13, 14)
                    }
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(payload.toResponseBody("audio/pcm".toMediaType()))
                        .build()
                }
                .build()

        private companion object {
            const val CHUNK_HEADER = "X-Test-Chunk"
            const val FIRST_CHUNK = "first"
            const val SECOND_CHUNK = "second"
        }
    }

    private class CollectingListener : TtsSynthesisListener {
        val audio = CopyOnWriteArrayList<ByteArray>()
        val errors = CopyOnWriteArrayList<String>()
        val formats = CopyOnWriteArrayList<Triple<Int, Int, Int>>()
        val started = AtomicBoolean(false)
        val completion = CountDownLatch(1)

        override fun onSynthesisStarted() {
            started.set(true)
        }

        override fun onAudioAvailable(
            audioData: ByteArray,
            sampleRate: Int,
            audioFormat: Int,
            channelCount: Int
        ) {
            audio.add(audioData)
            formats.add(Triple(sampleRate, audioFormat, channelCount))
        }

        override fun onSynthesisCompleted() {
            completion.countDown()
        }

        override fun onError(error: String) {
            errors.add(error)
            completion.countDown()
        }
    }
}
