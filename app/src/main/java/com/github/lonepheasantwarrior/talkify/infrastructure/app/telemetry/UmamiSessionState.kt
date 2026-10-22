package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

/**
 * Umami 会话状态（单一写入方：基础发送 worker）
 *
 * 把用户许可代际（policyEpoch）与服务端会话代际（sessionEpoch）分开：
 * - 录制缓冲绑定创建时的不可变 [RecordSession]，令牌轮换后旧缓冲不再可发送
 * - 远端 disabled 同时覆盖基础事件与录制通道
 * - 所有状态更新只由顺序发送器调用，避免并发响应互相覆盖
 */
internal class UmamiSessionState {

    data class RecordSession(
        val websiteId: String,
        val token: String,
        val sessionEpoch: Long,
        val policyEpoch: Long,
    )

    private var token: String? = null
    private var sessionEpoch = 0L
    private var remoteDisabled = false
    private var policyEpoch = 0L

    @Synchronized
    fun setPolicy(epoch: Long, enabled: Boolean) {
        policyEpoch = epoch
        if (!enabled) {
            token = null
            sessionEpoch++
            remoteDisabled = false
        }
    }

    @Synchronized
    fun policyEpoch(): Long = policyEpoch

    /**
     * 应用一次基础发送响应；令牌变化或停用都会推进会话代际
     *
     * @param expectedPolicyEpoch 响应所属的许可代际；不匹配时拒绝写入并返回 false，
     *   防止旧请求的回包覆盖关闭/重开后的新会话
     * @return 是否真的应用了响应
     */
    fun applyResponse(
        cache: String?,
        disabled: Boolean,
        expectedPolicyEpoch: Long = policyEpoch,
    ): Boolean = synchronized(this) {
        if (expectedPolicyEpoch != policyEpoch) return@synchronized false
        if (disabled) {
            remoteDisabled = true
            token = null
            sessionEpoch++
            return@synchronized true
        }
        val normalized = cache?.takeUnless { it.isBlank() }
        if (normalized != token) {
            token = normalized
            sessionEpoch++
        }
        true
    }

    @Synchronized
    fun onInvalidToken(expectedPolicyEpoch: Long = policyEpoch): Boolean {
        if (expectedPolicyEpoch != policyEpoch) return false
        token = null
        sessionEpoch++
        return true
    }

    /** 前台低频探测：允许一次远端停用状态复核，不改变令牌 */
    @Synchronized
    fun beginForegroundProbe(expectedPolicyEpoch: Long = policyEpoch): Boolean {
        if (expectedPolicyEpoch != policyEpoch) return false
        remoteDisabled = false
        return true
    }

    @Synchronized
    fun setRemoteDisabled(expectedPolicyEpoch: Long = policyEpoch): Boolean {
        if (expectedPolicyEpoch != policyEpoch) return false
        remoteDisabled = true
        token = null
        sessionEpoch++
        return true
    }

    @Synchronized
    fun isRemoteDisabled(): Boolean = remoteDisabled

    @Synchronized
    fun currentToken(): String? = token

    @Synchronized
    fun sessionEpoch(): Long = sessionEpoch

    @Synchronized
    fun recordSession(websiteId: String, enabled: Boolean): RecordSession? {
        if (!enabled || remoteDisabled) return null
        val current = token ?: return null
        return RecordSession(websiteId, current, sessionEpoch, policyEpoch)
    }

    @Synchronized
    fun isRecordSessionCurrent(session: RecordSession, enabled: Boolean): Boolean =
        enabled && !remoteDisabled &&
            session.policyEpoch == policyEpoch &&
            session.sessionEpoch == sessionEpoch &&
            session.token == token
}
