package com.smart.android.ad_app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleImaTerminalGateTest {
    @Test
    fun `terminal handling can only begin once`() {
        val gate = GoogleImaTerminalGate()

        assertTrue(gate.begin())
        assertTrue(gate.isFinishing)
        assertFalse(gate.begin())
    }

    @Test
    fun `pending completion can be forced to settle exactly once`() {
        val settlement = GoogleImaCompletionSettlement()
        var releases = 0
        var runtimeAuditFinishes = 0
        var completionCallbacks = 0

        assertTrue(settlement.begin("CONTENT_RESUME_REQUESTED"))
        assertTrue(settlement.isPending)

        repeat(2) {
            settlement.settle()?.let { event ->
                assertEquals("CONTENT_RESUME_REQUESTED", event)
                releases += 1
                runtimeAuditFinishes += 1
                completionCallbacks += 1
            }
        }

        assertFalse(settlement.isPending)
        assertEquals(1, releases)
        assertEquals(1, runtimeAuditFinishes)
        assertEquals(1, completionCallbacks)
        assertNull(settlement.settle())
        assertFalse(settlement.begin("ALL_ADS_COMPLETED"))
    }
}
