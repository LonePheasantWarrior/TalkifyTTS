package com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo

import android.content.Context
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.domain.repository.ProviderConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.security.PrefsValueCipher
import com.github.lonepheasantwarrior.talkify.infrastructure.security.ValueCodec
import com.github.lonepheasantwarrior.talkify.service.TtsLogger

/**
 * 供应商配置仓储基类（SharedPreferences 实现，值级加密）
 *
 * 所有供应商配置统一存储在同一个 SharedPreferences 文件中，
 * 以 "engine_{providerId}_" 为键前缀实现供应商间配置隔离。
 * 键名（字段名）保持明文，值经 [PrefsValueCipher]（Keystore AES-GCM）加密——
 * API Key 等凭据不再以明文落盘（P0-2②）。
 *
 * 子类只需声明 [serialize] / [deserialize] 两个纯函数映射，
 * 无需再编写逐字段的 get/put/contains 模板代码。
 *
 * 序列化统一使用「相对键名 → 字符串值」，兼容历史数据：
 * 旧版本以原生类型（Boolean 等）写入的值会经 toString 还原。
 *
 * 可测性（R-H 注入点先行）：存储后端 [KeyValueStore] 与加解密 [ValueCodec]
 * 均可注入，测试以内存实现覆盖序列化/迁移/容错链路；生产默认
 * SharedPreferences + PrefsValueCipher，行为不变。
 *
 * @param T 供应商配置类型
 * @param configClass 配置类型 Class，用于保存时的类型校验
 * @param context 应用上下文；仅在未注入 [store] 时用于构建默认 SharedPreferences 后端
 * @param store 存储后端，默认 SharedPreferences（PREFS_NAME）
 * @param codec 值编解码，默认 [PrefsValueCipher]
 */
abstract class BasePrefsConfigRepository<T : BaseProviderConfig>(
    context: Context?,
    private val configClass: Class<T>,
    store: KeyValueStore? = null,
    private val codec: ValueCodec = PrefsValueCipher,
) : ProviderConfigRepository {

    private val store: KeyValueStore =
        store ?: SharedPreferencesKeyValueStore(requireNotNull(context) { "context required when store not injected" }, PREFS_NAME)

    private val logTag: String = configClass.simpleName

    init {
        migrateLegacyPlaintextValues()
    }

    /**
     * 将历史明文值一次性迁移为加密存储。
     * 以 [ValueCodec.isEncrypted] 前缀识别已加密值，迁移幂等；
     * Keystore 不可用（极少见）时保持明文并在下次启动重试（P0-2②）。
     */
    private fun migrateLegacyPlaintextValues() {
        if (store.getBoolean(KEY_MIGRATED, false)) return
        try {
            val toMigrate = store.all.mapNotNull { (key, value) ->
                val str = value?.toString() ?: return@mapNotNull null
                if (str.isBlank() || codec.isEncrypted(str)) null else key to str
            }
            if (toMigrate.isEmpty()) {
                store.edit { it.putBoolean(KEY_MIGRATED, true) }
                return
            }
            store.edit {
                for ((key, plain) in toMigrate) {
                    it.putString(key, codec.encrypt(plain))
                }
                it.putBoolean(KEY_MIGRATED, true)
            }
            TtsLogger.i("$logTag: migrated ${toMigrate.size} legacy plaintext value(s) to encrypted storage")
        } catch (e: Exception) {
            TtsLogger.e("$logTag: encryption migration deferred: ${e.message}")
        }
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
        val undecryptableKeys = mutableListOf<String>()
        for ((key, value) in store.all) {
            if (!key.startsWith(prefix)) continue
            val str = value?.toString() ?: ""
            val plain = if (str.isBlank()) "" else codec.decrypt(str)
            if (plain == null) {
                undecryptableKeys.add(key)
                values[key.removePrefix(prefix)] = ""
            } else {
                values[key.removePrefix(prefix)] = plain
            }
        }
        if (undecryptableKeys.isNotEmpty()) {
            // 主密钥不可用（异常恢复场景）：清空不可解密的残留密文，字段回落默认值，
            // 用户重新填写凭据即恢复（P0-2② 容错）
            store.edit {
                for (key in undecryptableKeys) it.remove(key)
            }
            TtsLogger.w("$logTag: removed ${undecryptableKeys.size} value(s) undecryptable with current keystore key")
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
        try {
            writeValues(prefix, serialized) { value ->
                if (value.isBlank()) value else codec.encrypt(value)
            }
        } catch (e: Exception) {
            // Keystore 瞬时不可用（如恢复出厂后还原备份）：降级明文落盘并复位迁移标志，
            // 下次启动由 migrateLegacyPlaintextValues 重新加密（与读路径"清理回落默认值"
            // 的容错策略对齐，N4）——用户保存动作不能因加密失败而整体丢失
            TtsLogger.w("$logTag: encrypted save failed (${e.message}), writing plaintext until next launch")
            try {
                writeValues(prefix, serialized) { value -> value }
                store.edit { it.putBoolean(KEY_MIGRATED, false) }
            } catch (fallbackError: Exception) {
                TtsLogger.e("$logTag: config save failed entirely: ${fallbackError.message}")
            }
        }
    }

    /** 以「相对键 → 值」批量写入前缀命名空间，[transform] 决定落盘形态（密文/明文），并清除序列化中已消失的旧键 */
    private inline fun writeValues(
        prefix: String,
        serialized: Map<String, String>,
        crossinline transform: (String) -> String
    ) {
        // crossinline：transform 经 store.edit 的对象表达式间接调用，禁止非局部返回
        store.edit {
            for ((key, value) in serialized) {
                it.putString("$prefix$key", transform(value))
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

        /** 明文→密文一次性迁移完成标志（本身不敏感，明文存于同文件） */
        const val KEY_MIGRATED = "enc_migrated_v1"
    }
}
