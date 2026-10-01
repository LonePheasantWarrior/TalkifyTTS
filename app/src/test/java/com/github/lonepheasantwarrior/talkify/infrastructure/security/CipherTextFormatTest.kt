package com.github.lonepheasantwarrior.talkify.infrastructure.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CipherTextFormat] 纯 JVM 单测：密文格式往返、前缀判别、损坏输入拒收。
 * Keystore 加解密路径（[PrefsValueCipher]）依赖 AndroidKeyStore，不在 JVM 覆盖面内。
 */
class CipherTextFormatTest {

    @Test
    fun `encode decode roundtrip preserves payload`() {
        val iv = ByteArray(12) { it.toByte() }
        val encrypted = "音频密文-payload".toByteArray(Charsets.UTF_8)

        val encoded = CipherTextFormat.encode(iv, encrypted)
        assertTrue(CipherTextFormat.isEncrypted(encoded))
        assertTrue(encoded.startsWith("enc:v1:"))

        val (iv2, enc2) = CipherTextFormat.decode(encoded)!!
        assertArrayEquals(iv, iv2)
        assertArrayEquals(encrypted, enc2)
    }

    @Test
    fun `plaintext value is not encrypted and decodes to null`() {
        assertFalse(CipherTextFormat.isEncrypted("sk-plain-api-key"))
        assertNull(CipherTextFormat.decode("sk-plain-api-key"))
    }

    @Test
    fun `corrupt base64 payload yields null`() {
        assertNull(CipherTextFormat.decode("enc:v1:!!!not-base64!!!"))
    }

    @Test
    fun `blob shorter than iv yields null`() {
        // 仅 4 字节：不足 IV 长度（历史上手动截断/磁盘损坏的防御）
        val short = java.util.Base64.getEncoder().encodeToString(ByteArray(4))
        assertNull(CipherTextFormat.decode("enc:v1:$short"))
    }

    @Test
    fun `iv-only blob is rejected as corrupt`() {
        // 与 PrefsValueCipher 历史守卫一致：blob 不足 IV+1 字节（空载荷）视为损坏
        val encoded = CipherTextFormat.encode(ByteArray(12), ByteArray(0))
        assertNull(CipherTextFormat.decode(encoded))
    }
}
