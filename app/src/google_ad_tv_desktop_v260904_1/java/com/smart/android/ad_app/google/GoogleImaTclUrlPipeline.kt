package com.smart.android.ad_app.google

import com.smart.android.ad_app.normalizeAarUrlUa

/** The server switch only gates TCL adaptation; common corrections always follow. */
internal object GoogleImaTclUrlPipeline {
    data class Result(val url: String, val beforeCommonPatchUrl: String) {
        val commonPatchChanged: Boolean get() = url != beforeCommonPatchUrl
    }

    fun apply(
        originalUrl: String,
        imaUrl: String,
        tclPatchEnabled: Boolean,
        muted: Boolean,
        television: Boolean,
        packageName: String,
        fallbackUid: () -> String = { "" },
        commonPatch: (String) -> String = { normalizeAarUrlUa(it) }
    ): Result {
        val selected = if (tclPatchEnabled) {
            GoogleImaTclUrlPolicy.apply(originalUrl, imaUrl, muted, television, packageName)
        } else imaUrl
        val corrected = commonPatch(selected)
        return Result(GoogleTclRdidFallback.apply(corrected, fallbackUid), selected)
    }
}
