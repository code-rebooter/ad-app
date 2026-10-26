package com.smart.android.ad_app.google

import com.google.gson.Gson

/** Record the actual backend result handed to TCL in the existing batch log. */
internal object GoogleTclFinalUrlLog {
    fun json(requestId: String, finalUrl: String): String = Gson().toJson(
        linkedMapOf("request_id" to requestId, "final_url" to finalUrl)
    )
}
