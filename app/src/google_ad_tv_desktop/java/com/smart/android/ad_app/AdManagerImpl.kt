package com.smart.android.ad_app

/** Forward both legacy calls and the per-request authorize payload. */
object AdManagerImpl : IAdManager by GoogleAdTvDesktopAdManager
