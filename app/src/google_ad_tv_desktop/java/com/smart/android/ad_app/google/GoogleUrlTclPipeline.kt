package com.smart.android.ad_app.google

import com.smart.android.ad_app.normalizeAarUrlUa

/** Local corrections finish before our backend patch API is called. */
internal object GoogleUrlTclPipeline {
    data class Result(val url: String, val beforeCommonPatchUrl: String) {
        val commonPatchChanged: Boolean get() = url != beforeCommonPatchUrl
    }

    fun apply(
        generatedUrl: String,
        fallbackUid: () -> String = { "" },
        suppressIdentifierFallback: Boolean = false,
        commonPatch: (String) -> String = { normalizeAarUrlUa(it) }
    ): Result {
        val corrected = commonPatch(generatedUrl)
        val finalUrl = if (suppressIdentifierFallback) {
            corrected
        } else {
            GoogleTclRdidFallback.apply(corrected, fallbackUid)
        }
        return Result(finalUrl, generatedUrl)
    }
}
