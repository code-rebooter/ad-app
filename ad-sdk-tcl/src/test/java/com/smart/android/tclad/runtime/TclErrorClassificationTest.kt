package com.smart.android.tclad.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class TclErrorClassificationTest {
    private fun session() = TclRuntimeSession("request", "TCL_FFA", TclPlaybackState(0L, 180_000L),
        TclRuntimeAuditReporter("request", "TCL_FFA", false, false), {}) { }

    @Test fun `empty VAST differs from failed wrapper HTTP and invalid XML`() {
        val session = session()
        session.tagReady = true
        session.lastVast = TclVastObservation()
        assertEquals("no_fill", session.failureReason(false, false))
        session.lastDownloadError = "HTTP 503"
        assertEquals("vast_request_failed", session.failureReason(false, false))
        session.lastDownloadError = null
        session.lastVast = TclVastObservation(parseError = "Invalid XML")
        assertEquals("vast_parse_failed", session.failureReason(false, false))
    }

    @Test fun `a later media failure preserves filled and identifies the playback stage`() {
        val session = session()
        session.filled = true
        assertEquals("media_load_failed", session.failureReason(false, false))
        assertEquals("playback_start_failed", session.failureReason(true, false))
        assertEquals("playback_failed", session.failureReason(true, true))
        session.patchError = "backend rejected guarantee"
        assertEquals("gam_patch_failed", session.failureReason(true, true))
    }
}
