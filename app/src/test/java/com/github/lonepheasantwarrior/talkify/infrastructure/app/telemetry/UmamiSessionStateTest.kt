package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UmamiSessionStateTest {

    private val state = UmamiSessionState()

    @Test
    fun `令牌轮换使旧录制会话失效`() {
        state.setPolicy(epoch = 1, enabled = true)
        state.applyResponse("TOKEN_A", disabled = false)
        val first = state.recordSession("site", enabled = true)!!
        assertEquals("TOKEN_A", first.token)
        assertTrue(state.isRecordSessionCurrent(first, enabled = true))

        state.applyResponse("TOKEN_B", disabled = false)
        assertFalse("旧缓冲不得改挂新令牌", state.isRecordSessionCurrent(first, enabled = true))
        val second = state.recordSession("site", enabled = true)!!
        assertEquals("TOKEN_B", second.token)
        assertTrue(state.isRecordSessionCurrent(second, enabled = true))
    }

    @Test
    fun `许可代际变化使旧会话失效`() {
        state.setPolicy(1, enabled = true)
        state.applyResponse("TOKEN_A", disabled = false)
        val old = state.recordSession("site", enabled = true)!!

        state.setPolicy(2, enabled = true)
        assertFalse(state.isRecordSessionCurrent(old, enabled = true))
    }

    @Test
    fun `关闭许可后无录制会话`() {
        state.setPolicy(1, enabled = true)
        state.applyResponse("TOKEN_A", disabled = false)
        state.setPolicy(2, enabled = false)
        assertNull(state.recordSession("site", enabled = false))
        assertEquals(2L, state.policyEpoch())
    }

    @Test
    fun `远端停用覆盖录制通道且前台探测只解除停用标记`() {
        state.setPolicy(1, enabled = true)
        state.applyResponse("TOKEN_A", disabled = false)
        val session = state.recordSession("site", enabled = true)!!
        state.setRemoteDisabled()
        assertTrue(state.isRemoteDisabled())
        assertNull(state.recordSession("site", enabled = true))
        assertFalse(state.isRecordSessionCurrent(session, enabled = true))

        state.beginForegroundProbe()
        assertFalse(state.isRemoteDisabled())
        // 令牌已在停用时清空，必须等下一次基础响应重新建立
        assertNull(state.recordSession("site", enabled = true))
        state.applyResponse("TOKEN_C", disabled = false)
        assertEquals("TOKEN_C", state.recordSession("site", enabled = true)!!.token)
    }

    @Test
    fun `响应携带disabled时立即停用`() {
        state.setPolicy(1, enabled = true)
        state.applyResponse("TOKEN_A", disabled = false)
        state.applyResponse(null, disabled = true)
        assertTrue(state.isRemoteDisabled())
        assertNull(state.currentToken())
    }

    @Test
    fun `旧代际响应不能覆盖新会话`() {
        state.setPolicy(1, enabled = true)
        state.applyResponse("TOKEN_A", disabled = false)
        state.setPolicy(2, enabled = true)
        state.applyResponse("TOKEN_NEW", disabled = false, expectedPolicyEpoch = 2)

        assertFalse(state.applyResponse("TOKEN_OLD", disabled = false, expectedPolicyEpoch = 1))
        assertEquals("TOKEN_NEW", state.currentToken())
        assertFalse(state.onInvalidToken(expectedPolicyEpoch = 1))
        assertEquals("TOKEN_NEW", state.currentToken())
        assertFalse(state.setRemoteDisabled(expectedPolicyEpoch = 1))
        assertFalse(state.isRemoteDisabled())
    }

    @Test
    fun `无效令牌清除后允许重建`() {
        state.setPolicy(1, enabled = true)
        state.applyResponse("TOKEN_A", disabled = false)
        state.onInvalidToken()
        assertNull(state.recordSession("site", enabled = true))
        state.applyResponse("TOKEN_B", disabled = false)
        assertEquals("TOKEN_B", state.recordSession("site", enabled = true)!!.token)
    }
}
