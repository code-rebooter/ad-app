package com.smart.android.tclad.runtime

/** Confirm REQUESTED once per host request; keep unconfirmed failures only in error diagnostics. */
internal class TclSdkRequestState {
    private var attempts = 0
    private var startedAtMs: Long? = null
    private var respondedAtMs: Long? = null
    private var httpStatus: Int? = null
    private var businessCode: String? = null
    private var error: String? = null
    private var terminalReason: String? = null
    private var reported = false

    @Synchronized fun begin(atMs: Long): Boolean {
        if (terminalReason != null) return false
        attempts++
        if (startedAtMs == null) startedAtMs = atMs
        return true
    }

    @Synchronized fun responded(atMs: Long, status: Int) {
        if (terminalReason != null) return
        if (respondedAtMs == null) respondedAtMs = atMs
        httpStatus = status
    }

    @Synchronized fun businessResult(code: String?) {
        if (terminalReason == null && businessCode == null) businessCode = code
    }

    @Synchronized fun failed(cause: String) {
        if (terminalReason == null) error = cause
    }

    @Synchronized fun finish(reason: String) {
        if (terminalReason == null) terminalReason = reason
    }

    @Synchronized fun takeRequestedReport(): Map<String, Any?>? {
        if (reported || respondedAtMs == null) return null
        reported = true
        return diagnostics()
    }

    @Synchronized fun diagnostics(): Map<String, Any?> = linkedMapOf(
        "sdkRequestOutcome" to when {
            respondedAtMs != null -> "REQUESTED"
            terminalReason == null -> "PENDING"
            attempts > 0 -> "NO_RESPONSE"
            else -> "NOT_SENT"
        },
        "sdkRequestResponded" to (respondedAtMs != null),
        "sdkRequestAttempted" to (attempts > 0),
        "sdkRequestAttempts" to attempts,
        "sdkRequestStartedAtMs" to startedAtMs,
        "sdkRequestRespondedAtMs" to respondedAtMs,
        "sdkRequestHttpStatus" to httpStatus,
        "sdkRequestBusinessCode" to businessCode,
        "sdkRequestError" to error,
        "sdkRequestTerminalReason" to terminalReason
    )
}
