package com.github.lonepheasantwarrior.talkify.infrastructure.app.repo

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleJson
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleLimits
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleSettings
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleState
import com.github.lonepheasantwarrior.talkify.domain.model.multirole.MultiRoleStorageFailure
import com.github.lonepheasantwarrior.talkify.domain.repository.MultiRoleRepository
import com.github.lonepheasantwarrior.talkify.domain.repository.MultiRoleUpdateResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 应用级唯一 DataStore 持有者。
 *
 * Activity 与 Service 同进程；刻意只创建一个实例（DataStore 禁止多实例并发访问同一文件）。
 * [recreate] 仅用于用户显式确认的损坏修复：先取消旧作用域并等待其结束，再删除文件。
 */
internal object MultiRoleDataStoreHolder {

    private val lock = Any()

    /** 串行化 create/recreate/delete；get 只短暂持有，不跨挂起点 */
    private val lifecycleLock = ReentrantLock()
    private var scope: CoroutineScope? = null
    private var store: DataStore<Preferences>? = null

    fun get(context: Context): DataStore<Preferences> = lifecycleLock.withLock {
        synchronized(lock) {
            store ?: createLocked(context.applicationContext).also { store = it }
        }
    }

    suspend fun recreate(context: Context) {
        val appContext = context.applicationContext
        // reset 是用户显式操作；持锁跨越 cancelAndJoin，避免另一个 get 在删文件后
        // 抢先创建实例、随后又被删除的竞态。所有 get 只持有短临界区，不会与它死锁。
        lifecycleLock.lock()
        try {
            val oldScope: CoroutineScope?
            synchronized(lock) {
                oldScope = scope
                scope = null
                store = null
            }
            oldScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            val file = dataFile(appContext)
            // DataStore 的临时文件与主文件都应清理，避免残留内容被下一次 create 读取
            file.delete()
            File(file.parentFile, file.name + ".tmp").delete()
            synchronized(lock) {
                if (store == null) {
                    store = createLocked(appContext)
                }
            }
        } finally {
            lifecycleLock.unlock()
        }
    }

    private fun createLocked(context: Context): DataStore<Preferences> {
        val file = dataFile(context)
        file.parentFile?.mkdirs()
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = newScope
        return PreferenceDataStoreFactory.create(
            scope = newScope,
            produceFile = { file },
        )
    }

    private fun dataFile(context: Context): File =
        File(File(context.filesDir, "datastore"), "talkify_multirole.preferences_pb")
}

/**
 * Preferences DataStore 实现。
 *
 * - 配置以单个版本化 JSON 字符串保存，避免 DataStore 的字段级默认值掩盖 schema 迁移问题；
 * - 请求入口用 [snapshot] 直读 DataStore，不读 UI 状态副本；
 * - 更新在 [edit] 事务内按当前值读改写；损坏/未来版本时抛 [AbortEdit] 中止事务，
 *   原文件保持不变；
 * - [state] 在损坏时只发出失败态并等待用户显式 [reset]，不会自动重置。
 */
class DataStoreMultiRoleRepository(context: Context) : MultiRoleRepository {

    private val appContext = context.applicationContext
    private val updateMutex = Mutex()
    private val resetSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val state: Flow<MultiRoleState> = flow {
        while (currentCoroutineContext().isActive) {
            val store = MultiRoleDataStoreHolder.get(appContext)
            try {
                store.data.collect { prefs ->
                    emit(readPreferences(prefs))
                }
            } catch (e: CancellationException) {
                // 重置会取消旧 DataStore 的作用域；只有当前收集器自身被取消才上抛
                if (!currentCoroutineContext().isActive) throw e
            } catch (e: Exception) {
                emit(MultiRoleState(settings = MultiRoleSettings(), failure = mapFailure(e)))
            }
            // 正常 data flow 不会完成；异常后等待用户显式重置，再切到新实例
            resetSignal.first()
        }
    }

    override suspend fun snapshot(): MultiRoleState {
        return try {
            val prefs = MultiRoleDataStoreHolder.get(appContext).data.first()
            readPreferences(prefs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MultiRoleState(settings = MultiRoleSettings(), failure = mapFailure(e))
        }
    }

    override suspend fun update(
        transform: (current: MultiRoleSettings) -> MultiRoleSettings,
    ): MultiRoleUpdateResult = updateMutex.withLock {
        val store = MultiRoleDataStoreHolder.get(appContext)
        var committed: MultiRoleSettings? = null
        try {
            store.edit { prefs ->
                val current = when (val decoded = MultiRoleJson.decode(prefs[KEY_JSON])) {
                    is MultiRoleJson.DecodeResult.Success -> decoded.settings
                    is MultiRoleJson.DecodeResult.Failure -> throw AbortEdit(decoded.failure)
                }
                val next = transform(current).copy(
                    schemaVersion = MultiRoleSettings.CURRENT_SCHEMA_VERSION,
                )
                if (exceedsStructuralLimits(next)) {
                    throw AbortEdit(MultiRoleStorageFailure.TooLarge)
                }
                when (val encoded = MultiRoleJson.encode(next)) {
                    is MultiRoleJson.EncodeResult.Success -> prefs[KEY_JSON] = encoded.json
                    MultiRoleJson.EncodeResult.TooLarge ->
                        throw AbortEdit(MultiRoleStorageFailure.TooLarge)
                }
                committed = next
            }
            committed?.let { MultiRoleUpdateResult.Success(it) }
                ?: MultiRoleUpdateResult.Failure(MultiRoleStorageFailure.Io(null))
        } catch (e: AbortEdit) {
            MultiRoleUpdateResult.Failure(e.failure)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MultiRoleUpdateResult.Failure(mapFailure(e))
        }
    }

    override suspend fun reset(): MultiRoleUpdateResult = updateMutex.withLock {
        try {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                MultiRoleDataStoreHolder.recreate(appContext)
            }
            val settings = MultiRoleSettings()
            when (val encoded = MultiRoleJson.encode(settings)) {
                is MultiRoleJson.EncodeResult.Success ->
                    MultiRoleDataStoreHolder.get(appContext).edit { prefs ->
                        prefs[KEY_JSON] = encoded.json
                    }
                MultiRoleJson.EncodeResult.TooLarge ->
                    return@withLock MultiRoleUpdateResult.Failure(MultiRoleStorageFailure.TooLarge)
            }
            // 唤醒等待中的 state 收集器，让它切换到重建后的 DataStore
            resetSignal.tryEmit(Unit)
            MultiRoleUpdateResult.Success(settings)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MultiRoleUpdateResult.Failure(mapFailure(e))
        }
    }

    private fun readPreferences(prefs: Preferences): MultiRoleState {
        return when (val decoded = MultiRoleJson.decode(prefs[KEY_JSON])) {
            is MultiRoleJson.DecodeResult.Success ->
                MultiRoleState(settings = decoded.settings)
            is MultiRoleJson.DecodeResult.Failure ->
                MultiRoleState(settings = MultiRoleSettings(), failure = decoded.failure)
        }
    }

    private fun exceedsStructuralLimits(settings: MultiRoleSettings): Boolean {
        if (settings.profiles.size > MultiRoleLimits.MAX_PROFILES) return true
        return settings.profiles.any { profile ->
            profile.roles.size > MultiRoleLimits.MAX_ROLES_PER_PROFILE ||
                profile.roles.any { it.aliases.size > MultiRoleLimits.MAX_ALIASES_PER_ROLE }
        }
    }

    private fun mapFailure(e: Exception): MultiRoleStorageFailure = when (e) {
        is CorruptionException -> MultiRoleStorageFailure.Corrupted
        is IOException -> MultiRoleStorageFailure.Io(e.message)
        else -> MultiRoleStorageFailure.Corrupted
    }

    private class AbortEdit(val failure: MultiRoleStorageFailure) : Exception()

    private companion object {
        val KEY_JSON = stringPreferencesKey("multirole_json")
    }
}

/**
 * 仓库单例：所有调用方共用同一个 DataStore 实例（文档第 6 节硬性要求）。
 */
object MultiRoleRepositoryProvider {
    @Volatile
    private var instance: MultiRoleRepository? = null

    fun get(context: Context): MultiRoleRepository {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: DataStoreMultiRoleRepository(context.applicationContext).also { instance = it }
        }
    }
}
