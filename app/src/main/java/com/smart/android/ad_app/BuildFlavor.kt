package com.smart.android.ad_app

internal object BuildFlavor {
    fun isHq008(flavor: String = BuildConfig.FLAVOR): Boolean {
        return flavor == "hq008"
    }

    fun isHq008Noneu(flavor: String = BuildConfig.FLAVOR): Boolean {
        return flavor == "hq008Noneu" || flavor == "hq008Noneuc2" || isHq008Poly(flavor)
    }

    fun isHq008Poly(flavor: String = BuildConfig.FLAVOR): Boolean {
        return flavor == "hq008poly"
    }

    fun isHq008Family(flavor: String = BuildConfig.FLAVOR): Boolean {
        return isHq008(flavor) || isHq008Noneu(flavor)
    }
}
