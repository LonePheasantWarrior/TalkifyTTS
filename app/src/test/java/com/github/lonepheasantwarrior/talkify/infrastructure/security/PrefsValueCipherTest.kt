package com.github.lonepheasantwarrior.talkify.infrastructure.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [PrefsValueCipher] 解密语义的 JVM 级单测：只覆盖不依赖 Android Keystore 的分支
 * （格式判别与异常分类）。解密失败的两级分类是历史密文迁移的安全前提——
 * "永久不可读"（null → 移除该值）与"环境性不可用"（抛出 → 保留密文延后重试）
 * 混淆会导致瞬时故障误删用户凭据，故在此锁定；Keystore 真实路径由真机回归覆盖。
 */
class PrefsValueCipherTest {

    @Test
    fun `decrypt passes plaintext through unchanged`() {
        assertEquals("https://api.example.com", PrefsValueCipher.decrypt("https://api.example.com"))
        assertEquals("", PrefsValueCipher.decrypt(""))
    }

    @Test
    fun `decrypt returns null for permanently unreadable ciphertext`() {
        // 前缀合法但载荷损坏（Base64 非法 / 长度不足）：tag 无法校验，重试无意义
        assertNull(PrefsValueCipher.decrypt("enc:v1:not-base64!!"))
        assertNull(PrefsValueCipher.decrypt("enc:v1:AAAA"))
    }

    @Test
    fun `decrypt classifies keystore absence as unavailable not unreadable`() {
        // 格式合法的密文在无 AndroidKeyStore 的 JVM 上必然走到密钥获取失败：
        // 必须归类为环境性不可用（抛出，迁移延后重试）而非永久不可读（null，误删）
        val ciphertext = CipherTextFormat.encode(ByteArray(CipherTextFormat.IV_SIZE_BYTES), ByteArray(16))
        assertThrows(CipherUnavailableException::class.java) { PrefsValueCipher.decrypt(ciphertext) }
    }
}
