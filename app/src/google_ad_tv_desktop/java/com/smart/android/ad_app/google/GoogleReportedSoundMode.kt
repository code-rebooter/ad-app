package com.smart.android.ad_app.google

/** Shared by the independent URL generator and the pure IMA patch client. */
internal object GoogleReportedSoundMode {
    private val vpmuteParameter = Regex("([?&])vpmute=[^&#]*", RegexOption.IGNORE_CASE)

    fun applyToUrl(url: String, soundEnabled: Boolean): String {
        val fragmentAt = url.indexOf('#')
        val base = if (fragmentAt < 0) url else url.substring(0, fragmentAt)
        val fragment = if (fragmentAt < 0) "" else url.substring(fragmentAt)
        val vpmute = if (soundEnabled) "0" else "1"
        val normalized = if (vpmuteParameter.containsMatchIn(base)) {
            vpmuteParameter.replace(base) { "${it.groupValues[1]}vpmute=$vpmute" }
        } else {
            val separator = when {
                base.endsWith('?') || base.endsWith('&') -> ""
                '?' in base -> "&"
                else -> "?"
            }
            "$base${separator}vpmute=$vpmute"
        }
        return normalized + fragment
    }
}
