package com.github.lonepheasantwarrior.talkify.infrastructure.security

/**
 * 值级加解密编解码抽象（R-M① 注入点）
 *
 * [BasePrefsConfigRepository] 经此接口使用 [PrefsValueCipher]（生产默认，
 * Keystore AES-256-GCM）；测试注入假实现以覆盖序列化/迁移/容错链路。
 * 三个方法语义与 PrefsValueCipher 同名方法一致。
 */
interface ValueCodec {
    fun isEncrypted(value: String): Boolean
    fun encrypt(plain: String): String

    /** 非密文值原样返回；密文解密失败返回 null */
    fun decrypt(value: String): String?
}
