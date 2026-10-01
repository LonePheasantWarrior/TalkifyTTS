package com.github.lonepheasantwarrior.talkify.infrastructure.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SharedPreferences 值级加密器（Keystore AES-256-GCM）
 *
 * 用于 P0-2②：供应商 API Key 等凭据在 `talkify_engine_configs` 中的存储加密。
 * 主密钥生成于 AndroidKeyStore，不出安全硬件（无 setUserAuthenticationRequired，
 * 合成路径需无感解密），不随备份/换机迁移——配合 backup_rules 对该 prefs 文件的
 * 排除，密文与密钥都不会离开原设备。
 *
 * 密文格式：`enc:v1:` + Base64(IV[12] || ciphertext+tag)。
 * 前缀同时承担"已加密/历史明文"的判别职责，迁移逻辑据此幂等。
 *
 * 解密失败（主密钥不可用）返回 null，由调用方按"字段缺失"处理并清理残留密文——
 * 用户重新填写 API Key 即可恢复，不会崩溃。
 */
object PrefsValueCipher : ValueCodec {

    private const val TAG = "PrefsValueCipher"

    /** AndroidKeyStore 中的主密钥别名 */
    private const val KEY_ALIAS = "talkify_prefs_master_key"

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    /** 密文标记前缀（格式职责已剥离至 [CipherTextFormat]，此处为兼容引用） */
    val ENC_PREFIX: String get() = CipherTextFormat.ENC_PREFIX

    override fun isEncrypted(value: String): Boolean = CipherTextFormat.isEncrypted(value)

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        require(iv.size == CipherTextFormat.IV_SIZE_BYTES) { "unexpected GCM IV size: ${iv.size}" }
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return CipherTextFormat.encode(iv, encrypted)
    }

    /**
     * 解密；非密文值（历史明文）原样返回，密文解密失败返回 null
     */
    override fun decrypt(value: String): String? {
        val (iv, encrypted) = CipherTextFormat.decode(value) ?: return if (isEncrypted(value)) null else value
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, iv, 0, iv.size)
            )
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            TtsLogger.e("$TAG: decrypt failed (keystore key unavailable?): ${e.message}")
            null
        }
    }

    /**
     * 获取或生成主密钥。
     * 竞态防护：并发首用时两个线程可能同时走到 generateKey，后写者使先写者的
     * 密钥别名被替换——先写者加密的数据将不可解密，故整个"查 + 生成"必须在锁内。
     */
    private fun getOrCreateKey(): SecretKey = synchronized(this) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        generator.generateKey()
    }
}
