package com.smart.android.adsdk.internal.tclcmp

import android.util.Log

internal object TclCmpLocalLog {
    private val enabled: Boolean
        get() = TclCmpManager.isDebugLoggingEnabled()

    fun d(tag: String, message: String): Int {
        return if (enabled) Log.d(tag, message) else 0
    }

    fun d(tag: String, message: String, throwable: Throwable?): Int {
        return if (enabled) {
            if (throwable == null) Log.d(tag, message) else Log.d(tag, message, throwable)
        } else {
            0
        }
    }

    fun i(tag: String, message: String): Int {
        return if (enabled) Log.i(tag, message) else 0
    }

    fun i(tag: String, message: String, throwable: Throwable?): Int {
        return if (enabled) {
            if (throwable == null) Log.i(tag, message) else Log.i(tag, message, throwable)
        } else {
            0
        }
    }

    fun w(tag: String, message: String): Int {
        return if (enabled) Log.w(tag, message) else 0
    }

    fun w(tag: String, message: String, throwable: Throwable?): Int {
        return if (enabled) {
            if (throwable == null) Log.w(tag, message) else Log.w(tag, message, throwable)
        } else {
            0
        }
    }

    fun e(tag: String, message: String): Int {
        return if (enabled) Log.e(tag, message) else 0
    }

    fun e(tag: String, message: String, throwable: Throwable?): Int {
        return if (enabled) {
            if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
        } else {
            0
        }
    }
}
