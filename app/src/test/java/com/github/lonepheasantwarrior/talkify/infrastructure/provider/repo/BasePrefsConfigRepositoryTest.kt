package com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo

import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.infrastructure.security.CipherUnavailableException
import com.github.lonepheasantwarrior.talkify.service.TtsErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BasePrefsConfigRepository] 序列化/迁移链路单测（R-M①）
 *
 * 经 [KeyValueStore] 与历史密文解码器注入点以内存实现覆盖，不含 Android 依赖。
 * 用户数据兼容是回归代价最高的面：读写对称、可选字段清除、v1.0.35 加密版本的
 * `enc:v1:` 密文还原为明文（幂等、无变更不写盘）、Keystore 不可用延迟重试与
 * 读取兜底、不可解密清理、陈旧迁移标志清理、历史原生类型兼容，均在此锁定。
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

        /** 实际变更计数：值变化的写入与对存在键的移除各计一次（锁"无变更不写盘"） */
        var changeCount = 0
            private set

        override val all: Map<String, Any?> get() = map.toMap()
        override fun getString(key: String): String? = map[key] as? String
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
            (map[key] as? Boolean) ?: defaultValue

        override fun edit(transform: (KeyValueStore.Editor) -> Unit) {
            val ops = mutableListOf<(MutableMap<String, Any?>) -> Unit>()
            transform(object : KeyValueStore.Editor {
                override fun putString(key: String, value: String) {
                    ops.add {
                        if (it[key] != value) { it[key] = value; changeCount++ }
                    }
                }
                override fun putBoolean(key: String, value: Boolean) {
                    ops.add {
                        if (it[key] != value) { it[key] = value; changeCount++ }
                    }
                }
                override fun remove(key: String) {
                    ops.add {
                        if (it.containsKey(key)) { it.remove(key); changeCount++ }
                    }
                }
            })
            ops.forEach { it(map) }
        }
    }

    /**
     * 测试用历史密文编解码：与生产 [com.github.lonepheasantwarrior.talkify.infrastructure.security.PrefsValueCipher]
     * 同构（真实 `enc:v1:` 前缀 + 可逆变换），使读取兜底的 [com.github.lonepheasantwarrior.talkify.infrastructure.security.CipherTextFormat.isEncrypted]
     * 前缀判别在生产与测试间走同一逻辑。[unavailable] 置位时解码抛出
     * [CipherUnavailableException]（模拟 Keystore 环境性不可用）；
     * [failDecryptMarkers] 中的密文解码返回 null（模拟 tag 校验失败，永久不可读）。
     */
    private class FakeCodec(
        var unavailable: Boolean = false,
        private val failDecryptMarkers: Set<String> = emptySet()
    ) {
        fun encrypt(plain: String) = "enc:v1:" + plain.reversed()
        fun decrypt(value: String): String? {
            if (unavailable) throw CipherUnavailableException(IllegalStateException("test keystore down"))
            if (!value.startsWith("enc:v1:")) return value
            val plain = value.removePrefix("enc:v1:").reversed()
            return if (plain in failDecryptMarkers) null else plain
        }
    }

    private class TestRepo(
        store: KeyValueStore? = null,
        codec: FakeCodec = FakeCodec()
    ) : BasePrefsConfigRepository<TestConfig>(
        // store 恒注入：context 不会触达，传 null 即可（Kotlin 侧无法桩化抽象 Context）
        null, TestConfig::class.java, store, codec::decrypt
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
        // 本地明文存储：值原样落盘
        assertEquals("v1", store.getString("engine_${PID}_voice_id"))
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

    // ==================== 历史密文迁移（v1.0.35 加密版本 → 明文） ====================

    @Test
    fun `legacy ciphertext values are migrated back to plaintext on construction`() {
        val store = InMemoryKeyValueStore()
        val codec = FakeCodec()
        store.map["engine_${PID}_voice_id"] = codec.encrypt("legacy-voice")
        store.map["engine_${PID}_api_url"] = codec.encrypt("https://legacy")
        // 明文值不受迁移影响
        store.map["engine_${PID}_model_id"] = "already-plain"

        val repo = TestRepo(store, codec) // 构造即迁移

        assertEquals("legacy-voice", store.getString("engine_${PID}_voice_id"))
        assertEquals("https://legacy", store.getString("engine_${PID}_api_url"))
        assertEquals("already-plain", store.getString("engine_${PID}_model_id"))
        // 迁移后读取语义与普通明文一致
        val loaded = repo.getConfig(PID) as TestConfig
        assertEquals("legacy-voice", loaded.voiceId)
        assertEquals("already-plain", loaded.modelId)
    }

    @Test
    fun `ciphertext migration is idempotent and writes nothing when converged`() {
        val store = InMemoryKeyValueStore()
        val codec = FakeCodec()
        store.map["engine_${PID}_voice_id"] = codec.encrypt("legacy-voice")
        TestRepo(store, codec)
        val changesAfterFirst = store.changeCount
        assertTrue(changesAfterFirst > 0)

        TestRepo(store, codec) // 二次构造：存储已无密文、标志已清，零写盘
        assertEquals("无变更不得写盘", changesAfterFirst, store.changeCount)
    }

    @Test
    fun `undecryptable ciphertext is removed and falls back to defaults`() {
        val store = InMemoryKeyValueStore()
        val codec = FakeCodec(failDecryptMarkers = setOf("BROKEN"))
        // FakeCodec.decrypt 先倒序还原：明文 BROKEN 对应密文后缀 NEKORB
        store.map["engine_${PID}_voice_id"] = "enc:v1:NEKORB"

        val loaded = TestRepo(store, codec).getConfig(PID) as TestConfig

        // tag 校验失败 = 密文永久不可读：移除而非保留乱码
        assertEquals("", loaded.voiceId)
        assertFalse(store.map.containsKey("engine_${PID}_voice_id"))
    }

    @Test
    fun `keystore unavailable defers migration and preserves ciphertext`() {
        val store = InMemoryKeyValueStore()
        val codec = FakeCodec(unavailable = true)
        val ciphertext = codec.encrypt("legacy-voice")
        store.map["engine_${PID}_voice_id"] = ciphertext

        val repo = TestRepo(store, codec) // 构造期迁移延迟：密文原样保留

        assertEquals("瞬时故障不得删除凭据", ciphertext, store.getString("engine_${PID}_voice_id"))
        // 读取兜底：解码环境性失败按字段缺失处理，不崩溃、不误用密文串
        val loaded = repo.getConfig(PID) as TestConfig
        assertEquals("", loaded.voiceId)
    }

    @Test
    fun `read fallback recovers once keystore becomes available`() {
        val store = InMemoryKeyValueStore()
        val codec = FakeCodec()
        store.map["engine_${PID}_voice_id"] = codec.encrypt("legacy-voice")

        codec.unavailable = true
        val repo = TestRepo(store, codec) // 构造期迁移延迟
        assertEquals("", (repo.getConfig(PID) as TestConfig).voiceId)

        codec.unavailable = false // Keystore 恢复：下次构造完成迁移
        TestRepo(store, codec)
        assertEquals("legacy-voice", store.getString("engine_${PID}_voice_id"))
        assertEquals("legacy-voice", (repo.getConfig(PID) as TestConfig).voiceId)
    }

    @Test
    fun `stale migration flag key is cleaned up regardless of value`() {
        val store = InMemoryKeyValueStore()
        // 旧加密实现 Keystore 失败降级路径会写入 false，清理不得只认 true
        store.map[BasePrefsConfigRepository.KEY_MIGRATED] = false

        TestRepo(store)

        assertFalse(store.map.containsKey(BasePrefsConfigRepository.KEY_MIGRATED))
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
