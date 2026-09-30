package com.smart.android.ad_app

import java.util.WeakHashMap

internal object Hq008VideoFrameThrottle {
    private const val TARGET_FLAVOR = "ad_ytx01_sxk"
    private const val TARGET_FRAME_RATE = 12L
    private const val FRAME_INTERVAL_US = 1_000_000L / TARGET_FRAME_RATE

    private val lock = Any()
    private val rendererStates = WeakHashMap<Any, State>()

    @JvmStatic
    fun shouldRender(flavor: String, renderer: Any, presentationTimeUs: Long): Boolean {
        if (flavor != TARGET_FLAVOR || presentationTimeUs < 0L) {
            return true
        }

        synchronized(lock) {
            val state = rendererStates.getOrPut(renderer) { State() }
            if (presentationTimeUs < state.lastPresentationTimeUs) {
                state.reset()
            }
            state.lastPresentationTimeUs = presentationTimeUs

            // ExoPlayer may evaluate the same output buffer repeatedly until its release time.
            if (presentationTimeUs == state.lastAllowedPresentationTimeUs) {
                return true
            }

            val frameBucket = presentationTimeUs / FRAME_INTERVAL_US
            if (frameBucket == state.lastRenderedBucket) {
                return false
            }

            state.lastRenderedBucket = frameBucket
            state.lastAllowedPresentationTimeUs = presentationTimeUs
            return true
        }
    }

    @JvmStatic
    fun resetForTest() {
        synchronized(lock) {
            rendererStates.clear()
        }
    }

    private class State {
        var lastPresentationTimeUs = Long.MIN_VALUE
        var lastRenderedBucket = Long.MIN_VALUE
        var lastAllowedPresentationTimeUs = Long.MIN_VALUE

        fun reset() {
            lastPresentationTimeUs = Long.MIN_VALUE
            lastRenderedBucket = Long.MIN_VALUE
            lastAllowedPresentationTimeUs = Long.MIN_VALUE
        }
    }
}
