package com.smart.android.ad_app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleUmpConsentSessionGateTest {
    @Test
    fun `late callbacks from an old session cannot finish the active session`() {
        val gate = GoogleUmpConsentSessionGate()
        val oldHost = Any()
        val newHost = Any()

        val oldSession = gate.start()
        assertTrue(gate.attach(oldSession, oldHost))
        assertTrue(gate.clear(oldSession, oldHost))

        val newSession = gate.start()
        assertTrue(gate.attach(newSession, newHost))

        assertFalse(gate.isActive(oldSession, oldHost))
        assertFalse(gate.clear(oldSession, oldHost))
        assertTrue(gate.isActive(newSession, newHost))
    }

    @Test
    fun `a stale activity cannot attach to a newer session`() {
        val gate = GoogleUmpConsentSessionGate()
        val oldSession = gate.start()
        val newSession = gate.start()

        assertFalse(gate.attach(oldSession, Any()))
        assertTrue(gate.attach(newSession, Any()))
    }

    @Test
    fun `a different activity cannot replace the attached host`() {
        val gate = GoogleUmpConsentSessionGate()
        val session = gate.start()
        val activeHost = Any()

        assertTrue(gate.attach(session, activeHost))
        assertFalse(gate.attach(session, Any()))
        assertTrue(gate.isActive(session, activeHost))
    }
}
