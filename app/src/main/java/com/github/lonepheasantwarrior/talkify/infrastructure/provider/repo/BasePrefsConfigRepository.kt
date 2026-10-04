package com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo

import android.content.Context
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.domain.repository.ProviderConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.security.CipherTextFormat
import com.github.lonepheasantwarrior.talkify.infrastructure.security.CipherUnavailableException
import com.github.lonepheasantwarrior.talkify.infrastructure.security.PrefsValueCipher
import com.github.lonepheasantwarrior.talkify.service.TtsLogger

/**
 * 供应商配置仓储基类（SharedPreferences 实现，本地明文存储）
 *
 * 所有供应商配置统一存储在同一个 SharedPreferences 文件中，
 * 以 "engine_{providerId}_" 为键前缀实现供应商间配置隔离。
 * 键名与值均为明文——凭据不落云（备份/迁移规则已排除本文件，P0-2①），
 * 值级加密经评审定为弱收益（2026-10-04）后回退；加密能力保留在
 * [PrefsValueCipher]，供未来"备份/还原瞬间加解密"方案复用。
 *
 * 子类只需声明 [serialize] / [deserialize] 两个纯函数映射，
 * 无需再编写逐字段的 get/put/contains 模板代码。
 *
 * 序列化统一使用「相对键名 → 字符串值」，兼容历史数据：
 * 旧版本以原生类型（Boolean 等）写入的值会经 toString 还原。
 *
 * 可测性（R-H 注入点先行）：存储后端 [KeyValueStore] 与历史密文解码器均可注入，
 * 测试以内存实现覆盖序列化/迁移链路，无需触达 Android Keystore。
 *
 * @param T 供应商配置类型
 * @param configClass 配置类型 Class，用于保存时的类型校验
 * @param context 应用上下文；仅在未注入 [store] 时用于构建默认 SharedPreferences 后端
 * @param store 存储后端，默认 SharedPreferences（PREFS_NAME）
 * @param legacyCiphertextDecoder 历史密文解码器，生产默认 [PrefsValueCipher.decrypt]；
 *   迁移与读取兜底仅在遇到 `enc:` 密文时调用。返回 null 表示密文永久不可读
 *   （tag 校验失败/载荷损坏，该值将被移除）；抛出 [CipherUnavailableException]
 *   表示 Keystore 环境性不可用（迁移延后重试、读取回落默认值）
 */
abstract class BasePrefsConfigRepository<T : BaseProviderConfig>(
    context: Context?,
    private val configClass: Class<T>,
    store: KeyValueStore? = null,
    private val legacyCiphertextDecoder: (String) -> String? = PrefsValueCipher::decrypt,
) : ProviderConfigRepository {

    private val store: KeyValueStore =
        store ?: SharedPreferencesKeyValueStore(requireNotNull(context) { "context required when store not injected" }, PREFS_NAME)

    private val logTag: String = configClass.simpleName

    init {
        migrateLegacyCiphertextValues()
    }

    /**
     * 将 P0-2② 加密版本（v1.0.35）落盘的密文一次性还原为明文。
     *
     * 识别与解码都交给 [legacyCiphertextDecoder]（密文格式知识收敛在解码器内）。
     * 两段式执行：先完成全部解码并汇总变更，确有变更才提交一次写盘——
     * - 解码返回 null（GCM tag 校验失败/载荷损坏 = 密文永久不可读）→ 移除该值，
     *   字段回落默认值，用户重填凭据即恢复，不会崩溃；
     * - 解码抛出 [CipherUnavailableException]（Keystore 环境性不可用）→ 放弃本次
     *   迁移并原样保留全部密文，下次构造重试——瞬时故障绝不构成删除凭据的理由，
     *   迁移延迟期间由 [getConfig] 的读取兜底保证功能正确。
     * 明文值经解码器原样返回，迁移后存储中不再有密文，天然幂等。
     * 历史加密迁移的标志键 `enc_migrated_v1` 在新语义下无意义，迁移时顺手清理。
     */
    private fun migrateLegacyCiphertextValues() {
        val plaintextWrites = HashMap<String, String>()
        val removals = mutableListOf<String>()
        try {
            for ((key, value) in store.all) {
                val str = value?.toString()
                if (str.isNullOrBlank()) continue
                val plain = legacyCiphertextDecoder(str)
                when {
                    plain == null -> removals.add(key)
                    plain != str -> plaintextWrites[key] = plain
                }
            }
        } catch (e: CipherUnavailableException) {
            TtsLogger.w("$logTag: ciphertext migration deferred (keystore unavailable): ${e.message}")
            return
        }
        // 存在即清理（旧实现的降级路径会写入 false，不得用真值判断混淆"存在"与"为真"）
        val legacyFlagPresent = store.all.containsKey(KEY_MIGRATED)
        if (plaintextWrites.isEmpty() && removals.isEmpty() && !legacyFlagPresent) return
        store.edit {
            for ((key, plain) in plaintextWrites) it.putString(key, plain)
            for (key in removals) it.remove(key)
            it.remove(KEY_MIGRATED)
        }
        TtsLogger.i("$logTag: migrated ${plaintextWrites.size} legacy ciphertext value(s) back to plaintext" +
                if (removals.isNotEmpty()) " (${removals.size} permanently unreadable removed)" else "")
    }

    /**
     * 将配置序列化为「相对键名 → 字符串值」映射。
     *
     * 返回映射中不存在的键会从存储中移除（用于可选字段置空语义，如 MiniMax 的
     * continuousSound），保证读写对称。
     */
    protected abstract fun serialize(config: T): Map<String, String>

    /**
     * 从「相对键名 → 字符串值」映射还原配置，键缺失时使用字段默认值
     */
    protected abstract fun deserialize(values: Map<String, String>): T

    final override fun getConfig(providerId: String): BaseProviderConfig {
        val prefix = prefsKey(providerId)
        val values = HashMap<String, String>()
        for ((key, value) in store.all) {
            if (!key.startsWith(prefix)) continue
            val str = value?.toString() ?: ""
            // 读取兜底：迁移延迟（Keystore 瞬时不可用时放弃本次）期间字段可能仍是
            // 密文，此处即时解码保证功能正确；迁移完成后该分支不再触达。解码环境性
            // 失败按字段缺失处理（回落默认值），密文保留待下次迁移重试。
            values[key.removePrefix(prefix)] =
                if (CipherTextFormat.isEncrypted(str)) {
                    runCatching { legacyCiphertextDecoder(str) }.getOrNull().orEmpty()
                } else {
                    str
                }
        }
        return deserialize(values)
    }

    final override fun saveConfig(providerId: String, config: BaseProviderConfig) {
        if (!configClass.isInstance(config)) {
            TtsLogger.w("$logTag: unexpected config type ${config::class.java.simpleName}, skip saving")
            return
        }
        @Suppress("UNCHECKED_CAST")
        val typed = config as T

        val prefix = prefsKey(providerId)
        val serialized = serialize(typed)
        store.edit {
            for ((key, value) in serialized) {
                it.putString("$prefix$key", value)
            }
            for (key in store.all.keys) {
                if (key.startsWith(prefix) && !serialized.containsKey(key.removePrefix(prefix))) {
                    it.remove(key)
                }
            }
        }
    }

    final override fun hasConfig(providerId: String): Boolean {
        val prefix = prefsKey(providerId)
        return store.all.any { (key, value) ->
            key.startsWith(prefix) && !value?.toString().isNullOrBlank()
        }
    }

    /** 历史键前缀（"engine" 实指供应商），勿改——已安装用户的 API Key 等配置存于该前缀下 */
    private fun prefsKey(providerId: String): String = "engine_${providerId}_"

    internal companion object {
        /** 历史文件名，勿改（兼容已安装用户数据） */
        const val PREFS_NAME = "talkify_engine_configs"

        /** P0-2② 加密迁移的历史标志键：仅用于迁移时从用户数据中清理，不再读写 */
        const val KEY_MIGRATED = "enc_migrated_v1"
    }
}
