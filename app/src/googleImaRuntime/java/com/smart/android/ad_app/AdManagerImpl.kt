package com.smart.android.ad_app

/** Pure IMA runtime selected only by the dedicated IMA build. */
object AdManagerImpl : IAdManager by GoogleImaAdManager
