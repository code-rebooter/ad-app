package com.smart.android.ad_app

internal object BuildFlavor {
    fun isHq008(flavor: String = BuildConfig.FLAVOR): Boolean {
        return flavor == "hq008" ||
                flavor == "hq008XHSX" ||
                flavor == "tcl_aishang" ||
                flavor == "ad_ytx01" ||
                flavor == "ad_ytx01_sxk" ||
                flavor == "ad_ytx01_jx" ||
                flavor == "ad_album_101_001"
    }

    fun isHq008Noneu(flavor: String = BuildConfig.FLAVOR): Boolean {
        return flavor == "hq008Noneu" ||
                flavor == "hq008Noneuc2" ||
                isTclPoly(flavor) ||
                isHaierLsap(flavor) ||
                isGoogleAdTvDesktop(flavor) ||
                isGoogleAdTvLockscreen(flavor)
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
        return flavor == "google_ad_tv_desktop" ||
                flavor == "google_ad_tv_desktop_jm" ||
                flavor == "google_ad_tv_desktop_ytx" ||
                flavor == "google_ad_tv_desktop_007" ||
                flavor == "google_ad_tv_desktop_v260904_1" ||
                flavor == "google_ad_tv_desktop_tpmaotai1935"
    }

    fun isGoogleAdTvLockscreen(flavor: String = BuildConfig.FLAVOR): Boolean {
        return flavor == "google_ad_tv_lockscreen" ||
                flavor == "google_ad_tv_lockscreen_hq002"
    }

    fun isHq008Family(flavor: String = BuildConfig.FLAVOR): Boolean {
        return isHq008(flavor) || isHq008Noneu(flavor)
    }
}
