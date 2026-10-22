package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import okhttp3.Response

/**
 * 有界响应体读取
 *
 * 遥测响应只可能是很小的 JSON；限制读取字节数，避免异常代理/错误页触发大对象分配。
 * 超过上限时不尝试解码，直接按协议错误处理。
 */
internal sealed interface LimitedBody {
    data class Text(val value: String) : LimitedBody
    data object Absent : LimitedBody
    data object TooLarge : LimitedBody
}

internal fun readLimitedBody(response: Response, maxBytes: Int): LimitedBody {
    val body = response.body ?: return LimitedBody.Absent
    return try {
        val source = body.source()
        source.request(maxBytes.toLong() + 1)
        if (source.buffer.size > maxBytes) {
            LimitedBody.TooLarge
        } else {
            LimitedBody.Text(source.buffer.clone().readUtf8())
        }
    } catch (_: Exception) {
        LimitedBody.TooLarge
    }
}
