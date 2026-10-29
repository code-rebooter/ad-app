package com.smart.android.ad_app

import android.view.ViewGroup
import com.google.gson.JsonElement

interface IAdManager {
    fun init()
    fun showAd(
        flRoot: ViewGroup,
        adId: String? = null,
        soundEnabled: Boolean = false,
        callbackTimeoutMs: Long? = null,
        adStart: (() -> Unit)? = null,
        adError: (() -> Unit)? = null,
        adComplete: () -> Unit
    )
    /** Per-request authorized GAM payload; other players retain their existing entry. */
    fun showAd(
        flRoot: ViewGroup,
        adId: String?,
        soundEnabled: Boolean,
        callbackTimeoutMs: Long?,
        gamConfig: JsonElement?,
        channelId: String?,
        adStart: (() -> Unit)?,
        adError: (() -> Unit)?,
        adComplete: () -> Unit
    ) = showAd(flRoot, adId, soundEnabled, callbackTimeoutMs, adStart, adError, adComplete)

    fun destroyAd()
    /** Release only the session owned by this window/request. Null is an explicit global stop. */
    fun destroyAd(requestId: String?)
    /** Preserve the caller's real cancellation reason when the implementation supports auditing. */
    fun destroyAd(requestId: String?, reason: String) = destroyAd(requestId)
}
