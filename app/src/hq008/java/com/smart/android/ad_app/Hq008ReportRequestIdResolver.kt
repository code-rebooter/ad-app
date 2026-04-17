package com.smart.android.ad_app

internal object Hq008ReportRequestIdResolver {
    fun resolve(authorizeRequestId: String?): String {
        return authorizeRequestId?.takeIf { it.isNotBlank() } ?: Hq008AdReporter.newRequestId()
    }
}
