package com.smart.android.ad_app

internal object BuildFlavor {
    fun isHq008(flavor: String = BuildConfig.FLAVOR): Boolean {
        return flavor == "hq008" || flavor == "hq008XHSX"
    }

    fun isHq008Noneu(flavor: String = BuildConfig.FLAVOR): Boolean {
        return flavor == "hq008Noneu" || flavor == "hq008Noneuc2" || isTclPoly(flavor) || isHaierLsap(flavor)
    }

    fun isTclPoly(flavor: String = BuildConfig.FLAVOR): Boolean {
        return flavor == "tcl_poly"
    }

    fun isHaierLsap(flavor: String = BuildConfig.FLAVOR): Boolean {
        return flavor == "haier_lsap"
    }

    fun isHq008Family(flavor: String = BuildConfig.FLAVOR): Boolean {
        return isHq008(flavor) || isHq008Noneu(flavor)
    }
}
