package com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 键值存储抽象（重构方案 R-H 第一刀：注入点先行）
 *
 * [BasePrefsConfigRepository] 全部存储访问经此接口路由，测试可注入内存实现
 * 覆盖序列化/迁移/容错链路；未来更换存储后端（如拆进程换 MMKV，见
 * LocalModelManager 的预留说明）只需新增实现，业务代码零改动。
 *
 * 批量写语义对齐 SharedPreferences：[edit] 的变更在 transform 返回后原子应用，
 * transform 内经 [all] 读到的仍是旧快照。
 */
interface KeyValueStore {

    /** 全量快照（只读；值可能为 String/Boolean 等原生类型，兼容历史写入） */
    val all: Map<String, Any?>

    fun getString(key: String): String?

    fun getBoolean(key: String, defaultValue: Boolean): Boolean

    fun edit(transform: (Editor) -> Unit)

    interface Editor {
        fun putString(key: String, value: String)
        fun putBoolean(key: String, value: Boolean)
        fun remove(key: String)
    }
}

/** [KeyValueStore] 的 SharedPreferences 实现（生产默认后端） */
class SharedPreferencesKeyValueStore(
    context: Context,
    fileName: String
) : KeyValueStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    override val all: Map<String, Any?>
        get() = prefs.all

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        prefs.getBoolean(key, defaultValue)

    override fun edit(transform: (KeyValueStore.Editor) -> Unit) {
        // KTX edit{}：批量写同一 editor 并提交（与调用方原先手动管理的语义一致）
        prefs.edit {
            val editor = this
            transform(object : KeyValueStore.Editor {
                override fun putString(key: String, value: String) { editor.putString(key, value) }
                override fun putBoolean(key: String, value: Boolean) { editor.putBoolean(key, value) }
                override fun remove(key: String) { editor.remove(key) }
            })
        }
    }
}
