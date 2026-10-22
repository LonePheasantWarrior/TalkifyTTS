package com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryPolicyStateTest {

    @Test
    fun `捕获的许可在关闭并重开后永久失效`() {
        val state = TelemetryPolicyState()
        state.initialize(enabled = true)
        val old = state.capture()
        assertTrue(state.isCurrent(old))

        assertTrue(state.setEnabled(false))
        assertFalse(state.isCurrent(old))
        assertTrue(state.setEnabled(true))

        val fresh = state.capture()
        assertFalse(state.isCurrent(old))
        assertTrue(state.isCurrent(fresh))
        assertTrue(fresh.epoch > old.epoch)
    }

    @Test
    fun `同一开关状态不推进代际`() {
        val state = TelemetryPolicyState()
        state.initialize(enabled = true)
        val before = state.epoch()

        assertFalse(state.setEnabled(true))
        assertEquals(before, state.epoch())
    }

    @Test
    fun `提交动作只在捕获时的代际内执行`() {
        val state = TelemetryPolicyState()
        state.initialize(enabled = true)
        val permit = state.capture()

        assertEquals("written", state.runIfCurrent(permit) { "written" })
        state.setEnabled(false)
        assertNull(state.runIfCurrent(permit) { "should-not-run" })
    }

    @Test
    fun `关闭期间捕获的许可即使重新开启也不可提交`() {
        val state = TelemetryPolicyState()
        state.initialize(enabled = false)
        val offPermit = state.capture()
        state.setEnabled(true)

        assertFalse(offPermit.enabled)
        assertFalse(state.isCurrent(offPermit))
        assertNull(state.runIfCurrent(offPermit) { "should-not-run" })
    }
}
