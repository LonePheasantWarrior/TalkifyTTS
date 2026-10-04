package com.github.lonepheasantwarrior.talkify.infrastructure.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SharedPreferences 值级加解密器（Keystore AES-256-GCM）
 *
 * 历史职责：P0-2② 曾将供应商配置值加密落盘，2026-10-04 经评审回退为本地明文
 * 存储（凭据不落云、备份/迁移规则已排除配置文件，静态加密属弱收益）。
 * 现存职责有二：
 * - [decrypt]：`BasePrefsConfigRepository` 的迁移逻辑用它把 v1.0.35 加密版本
 *   落盘的 `enc:v1:` 密文一次性还原为明文；
 * - [encrypt]：当前无调用方，保留作为未来"备份/还原瞬间加解密"方案的复用件
 *   （本地存储保持明文，仅在备份导出/还原导入的边界处加解密）。
 *
 * 主密钥生成于 AndroidKeyStore，不出安全硬件（无 setUserAuthenticationRequired），
 * 不随备份/换机迁移——配合 backup_rules 对该 prefs 文件的排除，密文与密钥都
 * 不会离开原设备。
 *
 * 密文格式：`enc:v1:` + Base64(IV[12] || ciphertext+tag)。
 * 前缀同时承担"已加密/历史明文"的判别职责，迁移逻辑据此幂等。
 *
 * 解密失败（主密钥不可用）返回 null，由调用方按"字段缺失"处理并清理残留密文——
 * 用户重新填写 API Key 即可恢复，不会崩溃。
 */
object PrefsValueCipher {

    private const val TAG = "PrefsValueCipher"

    /** AndroidKeyStore 中的主密钥别名 */
    private const val KEY_ALIAS = "talkify_prefs_master_key"

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    fun isEncrypted(value: String): Boolean = CipherTextFormat.isEncrypted(value)

    fun encrypt(plain: String): String {
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
    fun decrypt(value: String): String? {
        val (iv, encrypted) = CipherTextFormat.decode(value) ?: return if (isEncrypted(value)) null else value
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, iv, 0, iv.size)
            )
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            // GCM tag 校验失败 = 密文与当前主密钥永久不匹配（密文损坏或密钥已轮换），
            // 重试无意义，调用方按"永久不可读"处理（移除该值、字段回落默认值）
            TtsLogger.e("$TAG: ciphertext failed tag verification (permanently unreadable): ${e.message}")
            null
        } catch (e: Exception) {
            // Keystore 服务不可用/TEE 瞬时故障等环境性失败：与永久不可读不可混淆——
            // 抛出而非返回 null，让调用方保留密文延后重试（见迁移与读取兜底路径）
            cachedKey = null
            TtsLogger.e("$TAG: decrypt deferred (keystore unavailable?): ${e.message}")
            throw CipherUnavailableException(e)
        }
    }

    /**
     * 主密钥进程级缓存。每次加解密若都走 KeyStore.load + getEntry，会产生两次
     * Keystore binder 往返；历史密文迁移与未来备份/还原的批量加解密都会连续
     * 调用本类多次，缓存把密钥获取摊薄为整个进程一次。密钥别名固定且仅由本类
     * 生成，缓存不会越过"密钥不可用→解密失败→清缓存重读"的失效路径。
     */
    @Volatile
    private var cachedKey: SecretKey? = null

    /**
     * 获取或生成主密钥。
     * 竞态防护：并发首用时两个线程可能同时走到 generateKey，后写者使先写者的
     * 密钥别名被替换——先写者加密的数据将不可解密，故整个"查 + 生成"必须在锁内。
     */
    private fun getOrCreateKey(): SecretKey {
        cachedKey?.let { return it }
        return synchronized(this) {
            cachedKey?.let { return it }
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            if (existing != null) {
                cachedKey = existing
                existing
            } else {
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
                generator.generateKey().also { cachedKey = it }
            }
        }
    }
}

/**
 * Keystore 环境性不可用（服务未就绪、TEE 瞬时故障、密钥存储读取失败等）。
 *
 * 与 [javax.crypto.AEADBadTagException]（tag 校验失败 = 密文永久不可读）严格区分：
 * 前者重试有意义，调用方（历史密文迁移、读取兜底）应保留密文延后处理；
 * 后者重试无意义，调用方应移除该值。
 */
class CipherUnavailableException(cause: Throwable) : IllegalStateException("keystore unavailable", cause)

