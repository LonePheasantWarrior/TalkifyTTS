package com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo

import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.infrastructure.security.ValueCodec
import com.github.lonepheasantwarrior.talkify.service.TtsErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BasePrefsConfigRepository] 序列化/迁移/容错链路单测（R-M①）
 *
 * 经 [KeyValueStore] 与 [ValueCodec] 注入点以内存实现覆盖，不含 Android 依赖。
 * 用户数据兼容是回归代价最高的面：读写对称、可选字段清除、明文迁移幂等、
 * 密钥失效清理、N4 加密写失败降级，均在此锁定。
 */
class BasePrefsConfigRepositoryTest {

    // ==================== 测试装置 ====================

    /** 测试配置：基类三字段 + 一个可选字段（验证"序列化缺失键清除"语义） */
    private class TestConfig(
        override val voiceId: String = "",
        override val apiUrl: String = "",
        override val modelId: String = "",
        val optional: String = ""
    ) : BaseProviderConfig()

    /** 内存键值存储：edit 语义对齐 SharedPreferences（transform 返回后原子应用） */
    private class InMemoryKeyValueStore : KeyValueStore {
        val map = LinkedHashMap<String, Any?>()
        override val all: Map<String, Any?> get() = map.toMap()
        override fun getString(key: String): String? = map[key] as? String
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
            (map[key] as? Boolean) ?: defaultValue

        override fun edit(transform: (KeyValueStore.Editor) -> Unit) {
            val ops = mutableListOf<(MutableMap<String, Any?>) -> Unit>()
            transform(object : KeyValueStore.Editor {
                override fun putString(key: String, value: String) { ops.add { it[key] = value } }
                override fun putBoolean(key: String, value: Boolean) { ops.add { it[key] = value } }
                override fun remove(key: String) { ops.add { it.remove(key) } }
            })
            ops.forEach { it(map) }
        }
    }

    /**
     * 假编解码：`enc:fake:` 前缀 + 倒序可逆变换。
     * [failDecryptMarkers] 中的密文解密返回 null（模拟主密钥不可用）；
     * [throwOnEncrypt] 置位时 encrypt 抛异常（模拟 Keystore 瞬时不可用）
     */
    private class FakeCodec(
        private val failDecryptMarkers: Set<String> = emptySet(),
        private val throwOnEncrypt: Boolean = false
    ) : ValueCodec {
        override fun isEncrypted(value: String) = value.startsWith("enc:fake:")
        override fun encrypt(plain: String): String {
            if (throwOnEncrypt) throw IllegalStateException("keystore unavailable")
            return "enc:fake:" + plain.reversed()
        }

        override fun decrypt(value: String): String? {
            if (!isEncrypted(value)) return value
            val plain = value.removePrefix("enc:fake:").reversed()
            return if (plain in failDecryptMarkers) null else plain
        }
    }

    private class TestRepo(
        store: KeyValueStore? = null,
        codec: ValueCodec = FakeCodec()
    ) : BasePrefsConfigRepository<TestConfig>(
        // store 恒注入：context 不会触达，传 null 即可（Kotlin 侧无法桩化抽象 Context）
        null, TestConfig::class.java, store, codec
    ) {
        override fun serialize(config: TestConfig) = buildMap {
            if (config.voiceId.isNotEmpty()) put("voice_id", config.voiceId)
            if (config.apiUrl.isNotEmpty()) put("api_url", config.apiUrl)
            if (config.modelId.isNotEmpty()) put("model_id", config.modelId)
            if (config.optional.isNotEmpty()) put("optional", config.optional)
        }

        override fun deserialize(values: Map<String, String>) = TestConfig(
            voiceId = values["voice_id"] ?: "",
            apiUrl = values["api_url"] ?: "",
            modelId = values["model_id"] ?: "",
            optional = values["optional"] ?: ""
        )
    }

    companion object {
        private const val PID = "testProvider"
    }

    // ==================== 序列化/读写对称 ====================

    @Test
    fun `save then get roundtrips config`() {
        val store = InMemoryKeyValueStore()
        val repo = TestRepo(store)

        repo.saveConfig(PID, TestConfig(voiceId = "v1", apiUrl = "https://a", modelId = "m1"))
        val loaded = repo.getConfig(PID) as TestConfig

        assertEquals("v1", loaded.voiceId)
        assertEquals("https://a", loaded.apiUrl)
        assertEquals("m1", loaded.modelId)
    }

    @Test
    fun `keys absent from serialization are removed on save`() {
        val store = InMemoryKeyValueStore()
        val repo = TestRepo(store)

        // 先写入含 optional 的配置，再保存不含 optional 的配置 → optional 被清除
        repo.saveConfig(PID, TestConfig(voiceId = "v1", optional = "keep-me"))
        repo.saveConfig(PID, TestConfig(voiceId = "v1"))

        val loaded = repo.getConfig(PID) as TestConfig
        assertEquals("", loaded.optional)
        assertFalse(store.map.containsKey("engine_${PID}_optional"))
    }

    @Test
    fun `hasConfig reflects prefix values only`() {
        val store = InMemoryKeyValueStore()
        val repo = TestRepo(store)

        assertFalse(repo.hasConfig(PID))
        repo.saveConfig(PID, TestConfig(voiceId = "v1"))
        assertTrue(repo.hasConfig(PID))
    }

    @Test
    fun `save with unexpected config type is skipped`() {
        val store = InMemoryKeyValueStore()
        val repo = TestRepo(store)

        repo.saveConfig(PID, object : BaseProviderConfig() {})

        assertTrue(store.map.none { it.key.startsWith("engine_${PID}_") })
    }

    // ==================== 明文迁移（幂等） ====================

    @Test
    fun `legacy plaintext values are migrated on construction`() {
        val store = InMemoryKeyValueStore()
        store.map["engine_${PID}_voice_id"] = "legacy-voice"
        store.map["engine_${PID}_api_url"] = "https://legacy"

        TestRepo(store) // 构造即迁移

        val migratedVoice = store.getString("engine_${PID}_voice_id")!!
        val migratedUrl = store.getString("engine_${PID}_api_url")!!
        assertTrue(migratedVoice.startsWith("enc:fake:"))
        assertTrue(migratedUrl.startsWith("enc:fake:"))
        assertTrue(store.getBoolean(BasePrefsConfigRepository.KEY_MIGRATED, false))
        // 可逆还原：迁移未破坏数据
        assertEquals("legacy-voice", FakeCodec().decrypt(migratedVoice))
    }

    @Test
    fun `migration is idempotent across re-construction`() {
        val store = InMemoryKeyValueStore()
        store.map["engine_${PID}_voice_id"] = "legacy-voice"
        TestRepo(store)
        val first = store.getString("engine_${PID}_voice_id")!!

        TestRepo(store) // 二次构造：迁移标志已置位，不再改写
        assertEquals(first, store.getString("engine_${PID}_voice_id"))
    }

    @Test
    fun `already encrypted values are not double migrated`() {
        val store = InMemoryKeyValueStore()
        val codec = FakeCodec()
        store.map["engine_${PID}_voice_id"] = codec.encrypt("already-enc")
        store.map[BasePrefsConfigRepository.KEY_MIGRATED] = false

        TestRepo(store, codec)

        assertEquals(codec.encrypt("already-enc"), store.getString("engine_${PID}_voice_id"))
    }

    @Test
    fun `migration defers when encrypt fails`() {
        val store = InMemoryKeyValueStore()
        store.map["engine_${PID}_voice_id"] = "legacy-plain"

        TestRepo(store, FakeCodec(throwOnEncrypt = true))

        // 值保持明文，标志未置位 → 下次启动重试
        assertEquals("legacy-plain", store.getString("engine_${PID}_voice_id"))
        // 标志未置位（absent）：下次构造仍会重试迁移
        assertFalse(store.map.containsKey(BasePrefsConfigRepository.KEY_MIGRATED))
    }

    // ==================== 读路径容错（密钥失效清理） ====================

    @Test
    fun `undecryptable values are removed and fall back to defaults`() {
        val store = InMemoryKeyValueStore()
        // FakeCodec.decrypt 先倒序还原：明文 BROKEN 对应密文后缀 NEKORB
        store.map["engine_${PID}_voice_id"] = "enc:fake:NEKORB"
        val codec = FakeCodec(failDecryptMarkers = setOf("BROKEN"))

        val loaded = TestRepo(store, codec).getConfig(PID) as TestConfig

        assertEquals("", loaded.voiceId)
        assertFalse(store.map.containsKey("engine_${PID}_voice_id"))
    }

    // ==================== N4：加密写路径容错 ====================

    @Test
    fun `save falls back to plaintext when encrypt fails`() {
        val store = InMemoryKeyValueStore()
        val repo = TestRepo(store, FakeCodec(throwOnEncrypt = true))

        repo.saveConfig(PID, TestConfig(voiceId = "v1", apiUrl = "https://a"))

        // 保存动作不因加密失败丢失：明文落盘 + 迁移标志复位（下次启动重加密）
        assertEquals("v1", store.getString("engine_${PID}_voice_id"))
        assertEquals("https://a", store.getString("engine_${PID}_api_url"))
        // 迁移标志复位为 false：下次启动由 migrateLegacyPlaintextValues 重新加密
        assertFalse(store.getBoolean(BasePrefsConfigRepository.KEY_MIGRATED, true))
        // 降级数据可被后续读取还原（decode 原样放行明文）
        val loaded = TestRepo(store).getConfig(PID) as TestConfig
        assertEquals("v1", loaded.voiceId)
    }

    // ==================== 序列化兼容（历史原生类型值） ====================

    @Test
    fun `legacy boolean stored value is read via toString`() {
        val store = InMemoryKeyValueStore()
        // 旧版本以原生类型写入：读取侧经 value?.toString() 兼容
        store.map["engine_${PID}_voice_id"] = true

        val loaded = TestRepo(store).getConfig(PID) as TestConfig

        assertEquals("true", loaded.voiceId)
    }

    // ==================== N10：toAndroidError else 细化（服务层高频路径） ====================

    @Test
    fun `provider side errors map to ERROR_SERVICE not INVALID_REQUEST`() {
        for (code in intArrayOf(
                TtsErrorCode.ERROR_NO_PROVIDER,
                TtsErrorCode.ERROR_PROVIDER_NOT_FOUND,
                TtsErrorCode.ERROR_PROVIDER_INIT_FAILED,
                TtsErrorCode.ERROR_UNKNOWN,
                TtsErrorCode.ERROR_GENERIC
            )) {
                assertEquals(
                    "errorCode=$code 应映射 ERROR_SERVICE",
                    android.speech.tts.TextToSpeech.ERROR_SERVICE,
                    TtsErrorCode.toAndroidError(code)
                )
            }
    }

    @Test
    fun `fallback unknown code maps to generic ERROR`() {
        assertEquals(
            android.speech.tts.TextToSpeech.ERROR,
            TtsErrorCode.toAndroidError(9999)
        )
    }
}
