package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.recorder

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import kotlin.math.max
import kotlin.random.Random

/**
 * `/api/record` 传输层（对齐 recorder.js 的批处理与分片协议）
 *
 * - record 流：增量事件缓冲（≥[MAX_BATCH_EVENTS] 条或序列化体积 >[MAX_BATCH_BYTES]）批量发送；
 *   FullSnapshot（solo）单独成包立即发送，不与增量混批
 * - 单事件超 [MAX_BATCH_BYTES] → 按 [FRAGMENT_CHUNK_BYTES] 切分为
 *   `umami:rrweb-event-fragment` 合成事件，逐片独立成包
 * - record payload.timestamp 使用单调秒（`max(now秒, 上次+1)`，对齐 B()），
 *   分片在基准秒上逐片 +1
 * - heatmap 流：≥[MAX_HEATMAP_EVENTS] 条批量发送，timestamp 为当前秒
 * - 会话令牌缺失时丢弃待发内容（对齐 recorder.js 的 `if (!s) return`）
 *
 * 线程安全：内部锁保护缓冲区与计数器，可在任意线程调用
 *
 * @param cacheProvider 会话令牌来源（UmamiClient.sessionCache）
 * @param post          网络发送注入点，body 为完整 JSON 字符串
 */
internal class RecordTransport(
    private val websiteId: String,
    private val cacheProvider: () -> String?,
    private val post: (body: String, cache: String) -> Unit,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()
    private val recordBuffer = mutableListOf<JSONObject>()
    private val heatmapBuffer = mutableListOf<JSONObject>()
    private var lastRecordTimestampSec = 0L

    /**
     * 追加一个 rrweb 事件
     *
     * @param solo            FullSnapshot 等需单独成包立即发送的事件
     * @param eventTimestampMs 事件自身携带的毫秒时间戳（分片事件沿用）
     */
    fun addRecordEvent(event: JSONObject, solo: Boolean = false, eventTimestampMs: Long = clockMs()) {
        synchronized(lock) {
            val oversize = payloadTooLarge(listOf(event))
            if (solo || oversize) {
                if (recordBuffer.isNotEmpty()) {
                    sendRecordLocked(recordBuffer.toList(), nextMonotonicSecLocked())
                    recordBuffer.clear()
                }
                val baseSec = nextMonotonicSecLocked()
                if (oversize) sendFragmentedLocked(event.toString(), eventTimestampMs, baseSec)
                else sendRecordLocked(listOf(event), baseSec)
                return
            }
            recordBuffer.add(event)
            if (recordBuffer.size >= MAX_BATCH_EVENTS || payloadTooLarge(recordBuffer)) {
                sendRecordLocked(recordBuffer.toList(), nextMonotonicSecLocked())
                recordBuffer.clear()
            }
        }
    }

    /** 追加一个热图事件（click/scroll），缓冲满 [MAX_HEATMAP_EVENTS] 触发批量发送 */
    fun addHeatmapEvent(event: JSONObject) {
        synchronized(lock) {
            heatmapBuffer.add(event)
            if (heatmapBuffer.size >= MAX_HEATMAP_EVENTS) flushHeatmapLocked()
        }
    }

    fun flushRecord() {
        synchronized(lock) {
            if (recordBuffer.isEmpty()) return
            sendRecordLocked(recordBuffer.toList(), nextMonotonicSecLocked())
            recordBuffer.clear()
        }
    }

    fun flushHeatmap() {
        synchronized(lock) { flushHeatmapLocked() }
    }

    /** 会话结束时冲刷全部缓冲 */
    fun flushAll() {
        synchronized(lock) {
            if (recordBuffer.isNotEmpty()) {
                sendRecordLocked(recordBuffer.toList(), nextMonotonicSecLocked())
                recordBuffer.clear()
            }
            flushHeatmapLocked()
        }
    }
    // ==================== 内部实现 ====================

    private fun flushHeatmapLocked() {
        if (heatmapBuffer.isEmpty()) return
        val events = heatmapBuffer.toList()
        heatmapBuffer.clear()
        val cache = cacheProvider() ?: return
        val payload = JSONObject()
            .put("website", websiteId)
            .put("timestamp", clockMs() / 1000)
            .put("events", JSONArray(events))
        post(wrap("heatmap", payload), cache)
    }

    /** 对齐 recorder.js 的 B()：单调秒，保证同秒内多次发送 timestamp 严格递增 */
    private fun nextMonotonicSecLocked(): Long {
        val nowSec = clockMs() / 1000
        val ts = max(nowSec, lastRecordTimestampSec + 1)
        lastRecordTimestampSec = ts
        return ts
    }

    private fun sendRecordLocked(events: List<JSONObject>, timestampSec: Long) {
        lastRecordTimestampSec = max(lastRecordTimestampSec, timestampSec)
        val cache = cacheProvider() ?: return
        val payload = JSONObject()
            .put("website", websiteId)
            .put("timestamp", timestampSec)
            .put("events", JSONArray(events))
        post(wrap("record", payload), cache)
    }

    /**
     * 超大事件分片（对齐 recorder.js 的 V/H 协议）：
     * 原 event JSON 字符串按 [FRAGMENT_CHUNK_BYTES]（UTF-8 预算）切片，
     * 每片为 `{type: umami:rrweb-event-fragment, data: {id, index, total, value}}`，
     * 逐片独立成包、payload.timestamp 逐片 +1，服务端按 id 重组后 JSON.parse 还原
     */
    private fun sendFragmentedLocked(eventJson: String, eventTimestampMs: Long, baseSec: Long) {
        val chunks = chunkByUtf8Bytes(eventJson, FRAGMENT_CHUNK_BYTES)
        val fragmentId = "${java.lang.Long.toString(clockMs(), 36)}-${randomBase36()}"
        chunks.forEachIndexed { index, chunk ->
            val fragment = Rrweb.fragmentEvent(fragmentId, index, chunks.size, chunk, eventTimestampMs)
            sendRecordLocked(listOf(fragment), baseSec + index)
        }
    }

    private fun payloadTooLarge(events: List<JSONObject>): Boolean {
        val payload = JSONObject()
            .put("website", websiteId)
            .put("timestamp", 0)
            .put("events", JSONArray(events))
        return wrap("record", payload).toByteArray(StandardCharsets.UTF_8).size > MAX_BATCH_BYTES
    }

    private fun wrap(type: String, payload: JSONObject): String =
        JSONObject().put("type", type).put("payload", payload).toString()

    /**
     * 按码点边界切分字符串，使每段 UTF-8 字节数不超过 [budgetBytes]，
     * 避免在代理对中间切分导致 JSON 序列化出现乱码
     */
    private fun chunkByUtf8Bytes(value: String, budgetBytes: Int): List<String> {
        if (value.toByteArray(StandardCharsets.UTF_8).size <= budgetBytes) return listOf(value)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < value.length) {
            var end = start
            var bytes = 0
            while (end < value.length) {
                val codePoint = value.codePointAt(end)
                val charBytes = utf8Length(codePoint)
                if (bytes + charBytes > budgetBytes) break
                bytes += charBytes
                end += Character.charCount(codePoint)
            }
            if (end == start) end = value.offsetByCodePoints(start, 1)
            chunks.add(value.substring(start, end))
            start = end
        }
        return chunks
    }

    private fun utf8Length(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }

    private fun randomBase36(): String = java.lang.Long.toString(Random.nextLong(0, 2176782336L), 36)

    companion object {
        private const val MAX_BATCH_EVENTS = 100
        private const val MAX_BATCH_BYTES = 500_000

        /** 分片切片预算：预算内最坏情况（全角转义 ×2）仍低于 MAX_BATCH_BYTES */
        private const val FRAGMENT_CHUNK_BYTES = 200_000

        private const val MAX_HEATMAP_EVENTS = 20
    }
}
