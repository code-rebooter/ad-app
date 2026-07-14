package com.smart.android.ad_app

internal object BuildFlavor {
    fun isHq008(flavor: String = BuildConfig.FLAVOR): Boolean {
        return flavor == "hq008" || flavor == "hq008XHSX" || flavor == "tcl_aishang" || flavor == "ad_ytx01"
    }

    fun isHq008Noneu(flavor: String = BuildConfig.FLAVOR): Boolean {
        return flavor == "hq008Noneu" ||
                flavor == "hq008Noneuc2" ||
                isTclPoly(flavor) ||
                isHaierLsap(flavor) ||
                isGoogleAdTvDesktop(flavor)
    }

    fun isTclPoly(flavor: String = BuildConfig.FLAVOR): Boolean {
        return flavor == "tcl_poly"
    }

    fun isHaierLsap(flavor: String = BuildConfig.FLAVOR): Boolean {
        return flavor == "haier_lsap" ||
                isAddyHq1002(flavor) ||
                isAddyJams(flavor)
    }

    fun isAddyHq1002(flavor: String = BuildConfig.FLAVOR): Boolean {
        return flavor == "addy_hq1002"
    }

    fun isAddyJams(flavor: String = BuildConfig.FLAVOR): Boolean {
        return flavor == "addy_jams"
    }

    fun isGoogleAdTvDesktop(flavor: String = BuildConfig.FLAVOR): Boolean {
        return flavor == "google_ad_tv_desktop"
    }

    fun isHq008Family(flavor: String = BuildConfig.FLAVOR): Boolean {
        return isHq008(flavor) || isHq008Noneu(flavor)
    }
}
