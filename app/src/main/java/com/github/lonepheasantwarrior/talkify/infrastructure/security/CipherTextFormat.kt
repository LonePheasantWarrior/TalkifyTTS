package com.github.lonepheasantwarrior.talkify.infrastructure.security

import java.util.Base64

/**
 * 密文格式纯函数层（重构方案 R-M①：JVM 可测部分剥离）
 *
 * 定义 `enc:v1:` 前缀密文的编解码格式：`enc:v1:` + Base64(IV[12] || ciphertext+tag)。
 * 前缀同时承担"已加密/历史明文"的判别职责，迁移逻辑据此幂等。
 *
 * 与 [PrefsValueCipher] 拆分的动机：该层不含 Keystore 与 android.util.Base64 依赖，
 * 纯 JVM 可单测（格式往返/前缀判别/损坏输入），Keystore 路径仍由 PrefsValueCipher 承担。
 * java.util.Base64 与此前 android.util.Base64(NO_WRAP) 的输出逐字节一致，存量密文兼容。
 */
object CipherTextFormat {

    /** 密文标记前缀（v1 算法版本化，未来轮换算法时升 v2 并兼容解密） */
    const val ENC_PREFIX = "enc:v1:"

    const val IV_SIZE_BYTES = 12

    fun isEncrypted(value: String): Boolean = value.startsWith(ENC_PREFIX)

    fun encode(iv: ByteArray, encrypted: ByteArray): String {
        val blob = ByteArray(iv.size + encrypted.size)
        iv.copyInto(blob)
        encrypted.copyInto(blob, iv.size)
        return ENC_PREFIX + Base64.getEncoder().encodeToString(blob)
    }

    /**
     * 解析密文为 (IV, ciphertext+tag)；非密文值、Base64 非法或长度不足时返回 null
     */
    fun decode(value: String): Pair<ByteArray, ByteArray>? {
        if (!isEncrypted(value)) return null
        val blob = try {
            Base64.getDecoder().decode(value.removePrefix(ENC_PREFIX))
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (blob.size <= IV_SIZE_BYTES) return null
        return blob.copyOfRange(0, IV_SIZE_BYTES) to
                blob.copyOfRange(IV_SIZE_BYTES, blob.size)
    }
}
