package com.smart.android.ad_app

/** No UMP status is available in this experiment. Only the existing defer action needs no SDK. */
internal object GoogleTclBusinessDecision {
    data class Result(val canContinue: Boolean, val reportAction: String?, val reason: String)

    fun evaluate(action: String?): Result = when (action?.trim()?.uppercase()) {
        "MAYBE_LATER" -> Result(true, "MAYBE_LATER", "server_deferred")
        "ACCEPT_ALL", "SAVE_SETTINGS", "REJECT" -> Result(false, null, "ump_unavailable")
        "SKIP_ALREADY_DECIDED" -> Result(false, null, "consent_state_unavailable")
        else -> Result(false, null, "decision_unavailable")
    }
}
