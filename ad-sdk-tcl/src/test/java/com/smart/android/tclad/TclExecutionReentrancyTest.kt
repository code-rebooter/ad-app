package com.smart.android.tclad

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.smart.android.tclad.runtime.TclExecution
import com.smart.android.tclad.runtime.TclPlaybackState
import com.smart.android.tclad.runtime.TclRuntimeSession
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TclExecutionReentrancyTest {
    private val controllers = mutableListOf<Controller>()

    @Before fun resetQueue() { Handler.reset() }
    @After fun closeControllers() {
        Looper.runOnMain { controllers.forEach { it.release() } }
        Handler.reset()
    }

    @Test fun `filled observer cancellation prevents filled after the terminal callback`() {
        val events = mutableListOf<String>()
        lateinit var handle: Controller
        handle = controller(object : AdStatusListener {
            override fun onAdExecutionEvent(event: String, fields: Map<String, Any?>) {
                events += "event:$event"
                if (event == "filled") handle.release()
            }
            override fun onAdFilled() { events += "filled" }
            override fun onAdCancelled() { events += "cancelled" }
        })

        emit(handle, "filled")

        assertEquals(listOf("event:filled", "cancelled"), events)
        assertTrue(handle.execution().isClosed())
        assertEquals(0, Handler.pendingCount())
    }

    @Test fun `filled observer replacement does not send old fill or close its replacement`() {
        val events = mutableListOf<String>()
        lateinit var replacement: Controller
        val old = controller(object : AdStatusListener {
            override fun onAdExecutionEvent(event: String, fields: Map<String, Any?>) {
                if (event == "filled") replacement = controller(object : AdStatusListener {
                    override fun onAdFilled() { events += "new:filled" }
                    override fun onAdCancelled() { events += "new:cancelled" }
                })
            }
            override fun onAdFilled() { events += "old:filled" }
            override fun onAdCancelled() { events += "old:cancelled" }
        })

        emit(old, "filled")
        emit(replacement, "filled")

        assertEquals(listOf("old:cancelled", "new:filled"), events)
        assertTrue(old.execution().isClosed())
        assertFalse(replacement.execution().isClosed())
        assertEquals(1, Handler.pendingCount()) // Only the replacement watchdog remains.
    }

    @Test fun `first frame observer cancellation does not synthesize a start or deadline`() {
        val events = mutableListOf<String>()
        lateinit var handle: Controller
        handle = controller(object : AdStatusListener {
            override fun onAdExecutionEvent(event: String, fields: Map<String, Any?>) {
                if (event == "first_frame") handle.release()
            }
            override fun onAdStartPlay() { events += "started" }
            override fun onPlaybackDeadline(deadline: Long) { events += "deadline" }
            override fun onAdCancelled() { events += "cancelled" }
        })

        emit(handle, "first_frame")

        assertEquals(listOf("cancelled"), events)
        assertEquals(0, Handler.pendingCount())
    }

    @Test fun `start callback cancellation prevents a later playback deadline`() {
        val events = mutableListOf<String>()
        lateinit var handle: Controller
        handle = controller(object : AdStatusListener {
            override fun onAdStartPlay() { events += "started"; handle.release() }
            override fun onPlaybackDeadline(deadline: Long) { events += "deadline" }
            override fun onAdCancelled() { events += "cancelled" }
        })
        session(handle).state.select(TclPlaybackState.Metadata(key = "ad:1"), SystemClock.elapsedRealtime())

        Looper.runOnMain { handle.execution().started(null) }

        assertEquals(listOf("started", "cancelled"), events)
        assertEquals(0, Handler.pendingCount())
    }

    @Test fun `normal filled and first frame still deliver one start and deadline`() {
        val events = mutableListOf<String>()
        val handle = controller(object : AdStatusListener {
            override fun onAdFilled() { events += "filled" }
            override fun onAdStartPlay() { events += "started" }
            override fun onPlaybackDeadline(deadline: Long) { events += "deadline" }
        })

        emit(handle, "filled")
        emit(handle, "first_frame")
        emit(handle, "first_frame")

        assertEquals(listOf("filled", "started", "deadline"), events)
        assertFalse(handle.execution().isClosed())
    }

    @Test fun `start callback replacement cannot publish an old deadline into the new round`() {
        val events = mutableListOf<String>()
        lateinit var replacement: Controller
        val old = controller(object : AdStatusListener {
            override fun onAdStartPlay() {
                events += "old:started"
                replacement = controller(object : AdStatusListener {
                    override fun onAdFilled() { events += "new:filled" }
                })
            }
            override fun onAdCancelled() { events += "old:cancelled" }
            override fun onPlaybackDeadline(deadline: Long) { events += "old:deadline" }
        })
        session(old).state.select(TclPlaybackState.Metadata(key = "old:ad"), SystemClock.elapsedRealtime())

        Looper.runOnMain { old.execution().started(null) }
        emit(replacement, "filled")

        assertEquals(listOf("old:started", "old:cancelled", "new:filled"), events)
        assertFalse(replacement.execution().isClosed())
        assertEquals(1, Handler.pendingCount())
    }

    private fun controller(listener: AdStatusListener): Controller = Controller().also {
        controllers += it
        Looper.runOnMain { it.begin(null, listener, 0f) }
        Handler.runPending()
    }

    private fun emit(handle: Controller, type: String) {
        Looper.runOnMain { session(handle).event(type) }
        Handler.runPending()
    }

    private fun session(handle: Controller): TclRuntimeSession =
        TclExecution::class.java.getDeclaredField("session").apply { isAccessible = true }
            .get(handle.execution()) as TclRuntimeSession
}
