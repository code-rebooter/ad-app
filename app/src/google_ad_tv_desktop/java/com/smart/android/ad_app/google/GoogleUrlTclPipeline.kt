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
    ): Result = apply(generatedUrl, fallbackUid, suppressIdentifierFallback, commonPatch, null)

    fun apply(
        generatedUrl: String,
        fallbackUid: () -> String = { "" },
        suppressIdentifierFallback: Boolean = false,
        commonPatch: (String) -> String = { normalizeAarUrlUa(it) },
        reportedSoundEnabled: Boolean?
    ): Result {
        val corrected = commonPatch(generatedUrl)
        val soundCorrected = reportedSoundEnabled?.let { GoogleReportedSoundMode.applyToUrl(corrected, it) }
            ?: corrected
        val finalUrl = if (suppressIdentifierFallback) {
            soundCorrected
        } else {
            GoogleTclRdidFallback.apply(soundCorrected, fallbackUid)
        }
        return Result(finalUrl, generatedUrl)
    }

}
