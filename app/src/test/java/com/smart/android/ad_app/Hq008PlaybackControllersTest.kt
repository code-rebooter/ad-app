package com.smart.android.ad_app

import org.junit.Assert.*
import org.junit.Test

class Hq008PlaybackControllersTest {
    private class Player { var releases = 0 }
    private class Controller(val player: Player)
    private val owners = Hq008PlaybackControllers<Controller> { it.player }
    private fun begin() = owners.begin { it.player.releases++ }

    @Test fun `returned and loaded wrappers share native player without releasing it before playback`() {
        val player = Player()
        val lease = begin()
        assertTrue(owners.attach(lease, Controller(player)))
        assertTrue(owners.attach(lease, Controller(player)))
        assertEquals(0, player.releases)
        owners.close(lease)
        assertEquals(1, player.releases)
    }

    @Test fun `cancel before load releases the returned handle and late wrapper only once`() {
        val player = Player()
        val lease = begin()
        owners.attach(lease, Controller(player))
        owners.close(lease)
        assertFalse(owners.attach(lease, Controller(player)))
        owners.close(lease)
        assertEquals(1, player.releases)
    }

    @Test fun `late old cleanup cannot stop a native player already reused by next request`() {
        val sharedPlayer = Player()
        val old = begin()
        owners.attach(old, Controller(sharedPlayer))
        val next = begin()
        owners.attach(next, Controller(sharedPlayer))
        assertEquals(1, sharedPlayer.releases)
        assertFalse(owners.attach(old, Controller(sharedPlayer)))
        owners.close(old)
        assertEquals(1, sharedPlayer.releases)
        owners.close(next)
        assertEquals(2, sharedPlayer.releases)
    }

    @Test fun `late controller from cancelled request is released without touching active player`() {
        val old = begin()
        owners.close(old)
        val next = begin()
        val newPlayer = Player()
        owners.attach(next, Controller(newPlayer))
        // A wrapper may arrive for the first time after cancellation.
        assertFalse(owners.attach(old, Controller(newPlayer)))
        val latePlayer = Player()
        assertFalse(owners.attach(old, Controller(latePlayer)))
        assertFalse(owners.attach(old, Controller(latePlayer)))
        assertEquals(1, latePlayer.releases)
        assertEquals(0, newPlayer.releases)
        owners.close(next)
        assertEquals(1, newPlayer.releases)
    }
}
