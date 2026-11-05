package com.github.lonepheasantwarrior.talkify.domain.model.multirole

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 多角色配置的版本化 JSON 编解码。
 *
 * 存储契约（见文档第 6 节）：
 * - 整个配置作为单个 JSON 字符串保存在 Preferences DataStore；
 * - schema 带版本号，缺字段用安全默认值，未知未来版本不得覆盖；
 * - 编码后最大 1 MiB；解析本身有界（输入长度先于解析校验）。
 */
object MultiRoleJson {

    private const val KEY_SCHEMA_VERSION = "schemaVersion"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ACTIVE_PROFILE = "activeProfileId"
    private const val KEY_PROFILES = "profiles"

    sealed interface DecodeResult {
        data class Success(val settings: MultiRoleSettings) : DecodeResult
        data class Failure(val failure: MultiRoleStorageFailure) : DecodeResult
    }

    sealed interface EncodeResult {
        data class Success(val json: String) : EncodeResult
        data object TooLarge : EncodeResult
    }

    fun encode(settings: MultiRoleSettings): EncodeResult {
        val root = JSONObject()
        root.put(KEY_SCHEMA_VERSION, MultiRoleSettings.CURRENT_SCHEMA_VERSION)
        root.put(KEY_ENABLED, settings.enabled)
        settings.activeProfileId?.let { root.put(KEY_ACTIVE_PROFILE, it.value) }
        root.put(KEY_PROFILES, JSONArray().apply {
            settings.profiles.forEach { put(encodeProfile(it)) }
        })
        val json = root.toString()
        if (json.toByteArray(Charsets.UTF_8).size > MultiRoleLimits.MAX_ENCODED_BYTES) {
            return EncodeResult.TooLarge
        }
        return EncodeResult.Success(json)
    }

    private fun encodeProfile(profile: ReadingProfile): JSONObject = JSONObject().apply {
        put("id", profile.id.value)
        put("name", profile.name)
        put("providerId", profile.providerId)
        profile.narratorVoice?.let { put("narratorVoice", encodeVoice(it)) }
        profile.defaultRoleVoice?.let { put("defaultRoleVoice", encodeVoice(it)) }
        put("roles", JSONArray().apply {
            profile.roles.forEach { role ->
                put(JSONObject().apply {
                    put("id", role.id.value)
                    put("name", role.name)
                    put("aliases", JSONArray(role.aliases))
                    role.voice?.let { put("voice", encodeVoice(it)) }
                })
            }
        })
        put("delimiters", JSONArray().apply {
            profile.delimiters.forEach { pair ->
                put(JSONObject().apply {
                    put("open", pair.open)
                    put("close", pair.close)
                    put("enabled", pair.enabled)
                })
            }
        })
    }

    private fun encodeVoice(voice: VoiceRef): JSONObject =
        JSONObject().put("voiceId", voice.voiceId)

    fun decode(raw: String?): DecodeResult {
        if (raw.isNullOrEmpty()) return DecodeResult.Success(MultiRoleSettings())
        if (raw.length > MultiRoleLimits.MAX_ENCODED_BYTES) {
            return DecodeResult.Failure(MultiRoleStorageFailure.TooLarge)
        }
        // 以 UTF-8 字节数做硬上限；char 数只用于快速拒绝，避免先将可疑大串解析成对象树
        if (raw.toByteArray(Charsets.UTF_8).size > MultiRoleLimits.MAX_ENCODED_BYTES) {
            return DecodeResult.Failure(MultiRoleStorageFailure.TooLarge)
        }
        return try {
            val root = JSONObject(raw)
            val version = if (root.has(KEY_SCHEMA_VERSION)) root.getInt(KEY_SCHEMA_VERSION) else 1
            if (version > MultiRoleSettings.CURRENT_SCHEMA_VERSION || version < 0) {
                return DecodeResult.Failure(MultiRoleStorageFailure.UnsupportedVersion(version))
            }
            val profilesJson = root.optJSONArray(KEY_PROFILES) ?: JSONArray()
            if (profilesJson.length() > MultiRoleLimits.MAX_PROFILES) {
                return DecodeResult.Failure(MultiRoleStorageFailure.Corrupted)
            }
            val profiles = ArrayList<ReadingProfile>(profilesJson.length())
            val profileIds = mutableSetOf<String>()
            for (i in 0 until profilesJson.length()) {
                val profile = decodeProfile(
                    profilesJson.optJSONObject(i)
                        ?: return DecodeResult.Failure(MultiRoleStorageFailure.Corrupted)
                )
                if (!profileIds.add(profile.id.value)) {
                    // 稳定 ID 重复说明文件损坏；保留原文件等待用户显式修复，不能静默合并
                    return DecodeResult.Failure(MultiRoleStorageFailure.Corrupted)
                }
                profiles += profile
            }
            val activeRaw = root.optString(KEY_ACTIVE_PROFILE, "")
            val settings = MultiRoleSettings(
                enabled = root.optBoolean(KEY_ENABLED, false),
                activeProfileId = activeRaw.takeIf { it.isNotBlank() }?.let { ProfileId(it) },
                profiles = profiles,
                schemaVersion = MultiRoleSettings.CURRENT_SCHEMA_VERSION,
            )
            DecodeResult.Success(settings)
        } catch (_: JSONException) {
            DecodeResult.Failure(MultiRoleStorageFailure.Corrupted)
        } catch (_: NumberFormatException) {
            DecodeResult.Failure(MultiRoleStorageFailure.Corrupted)
        }
    }

    private fun decodeProfile(obj: JSONObject): ReadingProfile {
        val id = obj.optString("id", "").takeIf { it.isNotBlank() }
            ?: throw JSONException("missing profile id")
        val name = obj.optString("name", "")
        val providerId = obj.optString("providerId", "")
        val rolesJson = obj.optJSONArray("roles") ?: JSONArray()
        if (rolesJson.length() > MultiRoleLimits.MAX_ROLES_PER_PROFILE) {
            throw JSONException("too many roles")
        }
        val roles = ArrayList<Role>(rolesJson.length())
        val roleIds = mutableSetOf<String>()
        for (i in 0 until rolesJson.length()) {
            val roleObj = rolesJson.optJSONObject(i) ?: throw JSONException("invalid role")
            val roleId = roleObj.optString("id", "").takeIf { it.isNotBlank() }
                ?: throw JSONException("missing role id")
            val aliasesJson = roleObj.optJSONArray("aliases") ?: JSONArray()
            if (aliasesJson.length() > MultiRoleLimits.MAX_ALIASES_PER_ROLE) {
                throw JSONException("too many aliases")
            }
            val aliases = ArrayList<String>(aliasesJson.length())
            for (a in 0 until aliasesJson.length()) {
                val alias = aliasesJson.optString(a, null) ?: throw JSONException("invalid alias")
                aliases += alias
            }
            if (!roleIds.add(roleId)) throw JSONException("duplicate role id")
            roles += Role(
                id = RoleId(roleId),
                name = roleObj.optString("name", ""),
                aliases = aliases,
                voice = decodeVoice(roleObj.optJSONObject("voice")),
            )
        }
        val delimitersJson = obj.optJSONArray("delimiters")
        val delimiters = if (delimitersJson == null) {
            MultiRoleSettings.defaultDelimiterPairs()
        } else {
            val list = ArrayList<DelimiterPair>(delimitersJson.length())
            for (i in 0 until delimitersJson.length()) {
                val pairObj = delimitersJson.optJSONObject(i) ?: throw JSONException("invalid delimiter")
                if (!pairObj.has("open") || !pairObj.has("close")) {
                    throw JSONException("missing delimiter codepoint")
                }
                val open = pairObj.getInt("open")
                val close = pairObj.getInt("close")
                if (open < 0 || open > Character.MAX_CODE_POINT ||
                    close < 0 || close > Character.MAX_CODE_POINT
                ) {
                    throw JSONException("invalid delimiter codepoint")
                }
                list += DelimiterPair(
                    open = open,
                    close = close,
                    enabled = pairObj.optBoolean("enabled", true),
                )
            }
            list
        }
        return ReadingProfile(
            id = ProfileId(id),
            name = name,
            providerId = providerId,
            narratorVoice = decodeVoice(obj.optJSONObject("narratorVoice")),
            defaultRoleVoice = decodeVoice(obj.optJSONObject("defaultRoleVoice")),
            roles = roles,
            delimiters = delimiters,
        )
    }

    private fun decodeVoice(obj: JSONObject?): VoiceRef? {
        if (obj == null) return null
        val voiceId = obj.optString("voiceId", "")
        return voiceId.takeIf { it.isNotBlank() }?.let { VoiceRef(it) }
    }
}
