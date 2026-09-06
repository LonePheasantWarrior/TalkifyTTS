package com.github.lonepheasantwarrior.talkify.service.provider

/**
 * 十六进制编解码工具
 *
 * 供 MiniMax 等返回 hex 编码音频数据的供应商使用。
 * 纯函数实现，便于单元测试。
 */
object HexCodec {

    /**
     * 将十六进制字符串转换为字节数组
     *
     * @param hex 十六进制字符串（可含空白字符）
     * @return 解码后的字节数组；长度非法或解码失败时返回空数组
     */
    fun decode(hex: String): ByteArray {
        return try {
            val cleanHex = hex.replace(WHITESPACE, "")
            if (cleanHex.length % 2 != 0) {
                ByteArray(0)
            } else {
                val bytes = ByteArray(cleanHex.length / 2)
                for (i in bytes.indices) {
                    val high = digitValue(cleanHex[i * 2])
                    val low = digitValue(cleanHex[i * 2 + 1])
                    if (high < 0 || low < 0) return ByteArray(0)
                    bytes[i] = ((high shl 4) or low).toByte()
                }
                bytes
            }
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    private fun digitValue(c: Char): Int {
        return when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }
    }

    private val WHITESPACE = Regex("\\s")
}
