package com.smart.android.ad_app

internal class GoogleUmpConsentSessionGate {
    private var nextSessionId = 0L
    private var activeSessionId = 0L
    private var activeHost: Any? = null

    fun start(): Long {
        activeSessionId = ++nextSessionId
        activeHost = null
        return activeSessionId
    }

    fun attach(sessionId: Long, host: Any): Boolean {
        if (sessionId == 0L || sessionId != activeSessionId) return false
        val currentHost = activeHost
        if (currentHost != null && currentHost !== host) return false
        activeHost = host
        return true
    }

    fun isActive(sessionId: Long, host: Any): Boolean {
        return sessionId != 0L &&
            sessionId == activeSessionId &&
            activeHost === host
    }

    fun clear(sessionId: Long, host: Any? = null): Boolean {
        if (sessionId == 0L || sessionId != activeSessionId) return false
        if (host != null && activeHost !== host) return false
        if (host == null && activeHost != null) return false
        activeSessionId = 0L
        activeHost = null
        return true
    }
}
