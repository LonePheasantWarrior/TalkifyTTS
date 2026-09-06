package com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo

import android.content.Context
import android.content.SharedPreferences
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.domain.repository.ProviderConfigRepository
import com.github.lonepheasantwarrior.talkify.service.TtsLogger

/**
 * 供应商配置仓储基类（SharedPreferences 实现）
 *
 * 所有供应商配置统一存储在同一个 SharedPreferences 文件中，
 * 以 "engine_{providerId}_" 为键前缀实现供应商间配置隔离。
 *
 * 子类只需声明 [serialize] / [deserialize] 两个纯函数映射，
 * 无需再编写逐字段的 get/put/contains 模板代码。
 *
 * 序列化统一使用「相对键名 → 字符串值」，兼容历史数据：
 * 旧版本以原生类型（Boolean 等）写入的值会经 toString 还原。
 *
 * @param T 供应商配置类型
 * @param configClass 配置类型 Class，用于保存时的类型校验
 */
abstract class BasePrefsConfigRepository<T : BaseProviderConfig>(
    context: Context,
    private val configClass: Class<T>
) : ProviderConfigRepository {

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val logTag: String = configClass.simpleName

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
        for ((key, value) in sharedPreferences.all) {
            if (key.startsWith(prefix)) {
                values[key.removePrefix(prefix)] = value?.toString() ?: ""
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
        val editor = sharedPreferences.edit()
        for ((key, value) in serialized) {
            editor.putString("$prefix$key", value)
        }
        for (key in sharedPreferences.all.keys) {
            if (key.startsWith(prefix) && serialized.containsKey(key.removePrefix(prefix)).not()) {
                editor.remove(key)
            }
        }
        editor.apply()
    }

    final override fun hasConfig(providerId: String): Boolean {
        val prefix = prefsKey(providerId)
        return sharedPreferences.all.any { (key, value) ->
            key.startsWith(prefix) && !value?.toString().isNullOrBlank()
        }
    }

    private fun prefsKey(providerId: String): String = "engine_${providerId}_"

    private companion object {
        const val PREFS_NAME = "talkify_engine_configs"
    }
}
