package com.smart.android.ad_app.google

import com.google.gson.Gson

/** Complete URL belongs in the existing batch's ad_log, never its 512-character message. */
internal object GoogleTclFinalUrlLog {
    fun json(requestId: String, finalUrl: String, tclPatchEnabled: Boolean): String = Gson().toJson(
        linkedMapOf("request_id" to requestId, "final_url" to finalUrl,
            "ima_tcl_url_patch" to if (tclPatchEnabled) 1 else 0)
    )
}
