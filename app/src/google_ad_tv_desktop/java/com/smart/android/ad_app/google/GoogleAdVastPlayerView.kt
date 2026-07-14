package com.smart.android.ad_app.google

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.widget.FrameLayout
import androidx.core.net.toUri
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ima.ImaAdsLoader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.exoplayer.source.ads.AdsMediaSource
import androidx.media3.ui.PlayerView
import com.google.ads.interactivemedia.v3.api.AdEvent

@UnstableApi
class GoogleAdVastPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    companion object {
        private const val TAG = "GoogleAdVastPlayer"
    }

    private val playerView = PlayerView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        useController = false
        setControllerHideDuringAds(true)
        setKeepContentOnPlayerReset(false)
        setShutterBackgroundColor(Color.BLACK)
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val adStartupTimeoutRunnable = Runnable {
        if (!hasFinished && !adStarted) {
            failOnce("Ad start timeout")
        }
    }

    private var playbackPlayer: ExoPlayer? = null
    private var adsLoader: ImaAdsLoader? = null
    private var hasFinished = false
    private var adLoaded = false
    private var adStarted = false
    private var firstFrameRendered = false
    private var firstFrameNotified = false
    private var adStartupTimeoutMs = GoogleAdTvDesktopVastConfig.DEFAULT_AD_STARTUP_TIMEOUT_MS

    var onAdLoaded: (() -> Unit)? = null
    var onAdStarted: (() -> Unit)? = null
    var onAdFirstFrameRendered: (() -> Unit)? = null
    var onAdFinished: (() -> Unit)? = null
    var onAdFailed: ((String) -> Unit)? = null

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isClickable = true
        addView(playerView)
    }

    fun play(
        adTagUrl: String,
        soundEnabled: Boolean,
        adLoadTimeoutMs: Int,
        adStartupTimeoutMs: Long
    ) {
        releasePlayerOnly()
        hasFinished = false
        adLoaded = false
        adStarted = false
        firstFrameRendered = false
        firstFrameNotified = false
        this.adStartupTimeoutMs = adStartupTimeoutMs
        initializePlayer(adLoadTimeoutMs)

        val playbackSpec = GoogleAdVastPlaybackSpec(
            adTagUrl = adTagUrl,
            adsId = "google_ad_tv_desktop_${System.currentTimeMillis()}"
        )
        Log.i(TAG, "Requesting VAST ad: $adTagUrl")
        mainHandler.removeCallbacks(adStartupTimeoutRunnable)
        mainHandler.postDelayed(
            adStartupTimeoutRunnable,
            adStartupTimeoutMs
        )

        playbackPlayer?.apply {
            volume = if (soundEnabled) 1f else 0f
            setMediaSource(createAdMediaSource(playbackSpec))
            playWhenReady = true
            prepare()
        }
    }

    fun release() {
        releasePlayerOnly()
        mainHandler.removeCallbacks(adStartupTimeoutRunnable)
    }

    private fun initializePlayer(adLoadTimeoutMs: Int) {
        adsLoader = ImaAdsLoader.Builder(context)
            .setMediaLoadTimeoutMs(adLoadTimeoutMs)
            .setAdEventListener { event ->
                Log.i(TAG, "IMA event: ${event.type}")
                when (event.type) {
                    AdEvent.AdEventType.LOADED -> {
                        notifyLoadedOnce()
                        extendAdStartupTimeout()
                    }
                    AdEvent.AdEventType.CONTENT_PAUSE_REQUESTED -> {
                        extendAdStartupTimeout()
                    }
                    AdEvent.AdEventType.STARTED -> {
                        notifyLoadedOnce()
                        adStarted = true
                        mainHandler.removeCallbacks(adStartupTimeoutRunnable)
                        onAdStarted?.invoke()
                        notifyAdFirstFrameOnce()
                    }
                    AdEvent.AdEventType.CONTENT_RESUME_REQUESTED -> {
                        if (!adStarted) {
                            failOnce("Ad did not start before content resumed")
                        }
                    }
                    AdEvent.AdEventType.COMPLETED,
                    AdEvent.AdEventType.ALL_ADS_COMPLETED,
                    AdEvent.AdEventType.SKIPPED -> {
                        if (adStarted) {
                            finishOnce()
                        } else {
                            failOnce("Ad did not start before content resumed")
                        }
                    }
                    else -> Unit
                }
            }
            .setAdErrorListener { error ->
                Log.w(TAG, "IMA ad error: $error")
                failOnce(error?.toString() ?: "Unknown ad error")
            }
            .build()

        val mediaSourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context))
            .setLocalAdInsertionComponents(
                { adsLoader ?: error("AdsLoader unavailable") },
                playerView
            )

        playbackPlayer = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().also { exoPlayer ->
                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        Log.i(TAG, "Playback state changed: $playbackState")
                        if (hasFinished) return
                        if (!adStarted && playbackState == Player.STATE_ENDED) {
                            failOnce("Ad finished without start event")
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        Log.i(TAG, "Player isPlaying=$isPlaying")
                    }

                    override fun onRenderedFirstFrame() {
                        Log.i(TAG, "First video frame rendered")
                        firstFrameRendered = true
                        notifyAdFirstFrameOnce()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "Player error", error)
                        failOnce(error.message ?: "Playback error")
                    }
                })
            }

        playerView.player = playbackPlayer
        adsLoader?.setPlayer(playbackPlayer)
    }

    private fun notifyLoadedOnce() {
        if (adLoaded || hasFinished) return
        adLoaded = true
        onAdLoaded?.invoke()
    }

    private fun notifyAdFirstFrameOnce() {
        if (hasFinished || firstFrameNotified || !adStarted || !firstFrameRendered) return
        firstFrameNotified = true
        onAdFirstFrameRendered?.invoke()
    }

    private fun extendAdStartupTimeout() {
        if (hasFinished || adStarted) return
        mainHandler.removeCallbacks(adStartupTimeoutRunnable)
        mainHandler.postDelayed(
            adStartupTimeoutRunnable,
            adStartupTimeoutMs
        )
    }

    private fun createAdMediaSource(playbackSpec: GoogleAdVastPlaybackSpec): AdsMediaSource {
        val contentSource = SilenceMediaSource(GoogleAdTvDesktopVastConfig.SILENCE_CONTENT_DURATION_US)
        val adMediaSourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context))
        return AdsMediaSource(
            contentSource,
            DataSpec(playbackSpec.adTagUrl.toUri()),
            playbackSpec.adsId,
            adMediaSourceFactory,
            adsLoader ?: error("AdsLoader unavailable"),
            playerView
        )
    }

    private fun releasePlayerOnly() {
        mainHandler.removeCallbacks(adStartupTimeoutRunnable)
        adsLoader?.setPlayer(null)
        adsLoader?.release()
        adsLoader = null

        playerView.player = null
        playbackPlayer?.stop()
        playbackPlayer?.release()
        playbackPlayer = null
    }

    private fun finishOnce() {
        if (hasFinished) return
        hasFinished = true
        mainHandler.removeCallbacks(adStartupTimeoutRunnable)
        Log.i(TAG, "Ad flow finished")
        onAdFinished?.invoke()
    }

    private fun failOnce(message: String) {
        if (hasFinished) return
        hasFinished = true
        mainHandler.removeCallbacks(adStartupTimeoutRunnable)
        Log.w(TAG, "Ad flow failed: $message")
        onAdFailed?.invoke(message)
    }

    private data class GoogleAdVastPlaybackSpec(
        val adTagUrl: String,
        val adsId: String
    )
}
