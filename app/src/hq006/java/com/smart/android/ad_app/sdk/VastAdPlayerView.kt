package com.smart.android.ad_app.sdk

import androidx.core.net.toUri

// VastAdPlayerView.kt
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.media3.common.AdOverlayInfo
import androidx.media3.common.AdViewProvider
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ima.ImaAdsLoader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.exoplayer.source.ads.AdsMediaSource
import com.google.ads.interactivemedia.v3.api.AdEvent
import com.google.ads.interactivemedia.v3.api.ImaSdkFactory
import com.google.ads.interactivemedia.v3.api.ImaSdkSettings
import com.google.common.collect.ImmutableList
import kotlin.apply


/**
 * 纯广告播放器（不依赖 media3-ui）
 * 完全模仿官方 PlayerView 的实现方式，让 IMA 自动显示倒计时、跳过按钮、Learn More 等
 */
@UnstableApi
class VastAdPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), AdViewProvider {

    private var player: ExoPlayer? = null
    private var adsLoader: ImaAdsLoader? = null
    private var surfaceView: SurfaceView? = null

    // 专门给 IMA 放广告 UI 用的容器（必须有！）
    // 1. 懒加载创建 Overlay 容器，确保它永远存在
    private val adOverlayFrameLayout = FrameLayout(context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
    }

    // 对外回调（你自己决定要不要用）
    var onAdStarted: (() -> Unit)? = null
    var onAdCompleted: (() -> Unit)? = null
    var onAllAdsCompleted: (() -> Unit)? = null
    var onAdClicked: (() -> Unit)? = null
    var onAdError: ((String) -> Unit)? = null

    var isMuted: Boolean = true
        set(value) {
            field = value
            player?.volume = if (value) 0f else 1f
        }

    init {
        isVisible = false
        setBackgroundColor(Color.BLACK)
        isClickable = true // 整屏点击算广告点击
        // 2. 确保 Overlay 始终在最上层，且初始化时就添加进去
        // 注意：不要在 release() 中移除它
        addView(adOverlayFrameLayout)
    }

    /** 播放 VAST URL（最常见） */
    fun play(vastTagUrl: String) {
        playInternal(DataSpec(vastTagUrl.toUri()))
    }

    /** 播放 OpenRTB 返回的 adm XML 字符串（国内主流） */
    fun playWithAdm(admXmlString: String) {
        val dataSpec = DataSpec(Util.getDataUriForString("application/xml", admXmlString))
        playInternal(dataSpec)
    }

    private fun playInternal(adTagDataSpec: DataSpec) {
        // 播放前先清理旧的 Player 和 Loader，防止内存泄漏或状态冲突
        releasePlayerOnly()
        initializePlayer()

        val adsMediaSource = AdsMediaSource(
            SilenceMediaSource(0),   // 纯广告，无正片
            adTagDataSpec,
            "vast_ad_tag_${System.currentTimeMillis()}",
            DefaultMediaSourceFactory(context),
            adsLoader!!,
            this                                // 关键：传自己，实现 AdViewProvider
        )

        player?.apply {
            setMediaSource(adsMediaSource)
            playWhenReady = true
            volume = if (isMuted) 0f else 1f
            prepare()
        }
    }
    private val imaSdkSettings: ImaSdkSettings by lazy {
        ImaSdkFactory.getInstance().createImaSdkSettings().apply {
            // 需要时再打开
            isDebugMode = true
        }
    }


    private fun initializePlayer() {

        ImaSdkFactory.getInstance().initialize(context, imaSdkSettings)
        // 1. IMA AdsLoader
        adsLoader = ImaAdsLoader.Builder(context)
            .setAdEventListener { event ->
                println("当前的广告事件：${event.type}")
                when (event.type) {
                    AdEvent.AdEventType.LOADED -> {
                        isVisible = true
                    }

                    AdEvent.AdEventType.STARTED -> {
                        onAdStarted?.invoke()
                    }

                    AdEvent.AdEventType.COMPLETED -> {
                        onAdCompleted?.invoke()
                    }

                    AdEvent.AdEventType.SKIPPED,
                    AdEvent.AdEventType.ALL_ADS_COMPLETED -> {
                        isVisible = false
                        onAllAdsCompleted?.invoke()
                    }

                    AdEvent.AdEventType.CLICKED -> {
                        onAdClicked?.invoke()
                    }

                    else -> {}
                }
            }
            .setAdErrorListener { error ->
                println("当前的广告错误：${error}")
                isVisible = false
                onAdError?.invoke(error.toString())
            }.setImaSdkSettings(imaSdkSettings).setDebugModeEnabled(true)
            .build()

        // 2. ExoPlayer
        val mediaSourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context))
            .setLocalAdInsertionComponents({ adsLoader!! }, this)  // 同样传 this

        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()


        // 3. 动态创建 SurfaceView，并确保它位于 Overlay 下方 (index 0)
        if (surfaceView == null) {
            surfaceView = SurfaceView(context).apply {
                layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
            }
        }
        // 只有当 surfaceView 没有父布局时才添加
        if (surfaceView!!.parent == null) {
            // 这里的 0 确保它在 adOverlayFrameLayout 下面
            addView(surfaceView, 0)
        }


        player?.setVideoSurfaceView(surfaceView)
        adsLoader?.setPlayer(player)

        // 整屏点击也算点击广告
        setOnClickListener { onAdClicked?.invoke() }

        // 播放错误兜底
        player?.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                isVisible = false
                onAdError?.invoke(error.message ?: "Playback error")
            }
        })
    }

    fun pause() = player?.pause()
    fun resume() = player?.play()

    /**
     * 内部使用的释放逻辑，保留 View 结构，只释放播放器资源
     */
    private fun releasePlayerOnly() {
        adsLoader?.setPlayer(null)
        adsLoader?.release()
        adsLoader = null

        player?.stop()
        player?.release()
        player = null

        // 注意：不要 removeAllViews()，否则 adOverlayFrameLayout 没了
        // 如果需要，可以移除 SurfaceView，但保留 Overlay
        // removeView(surfaceView) // 可选
    }

    /**
     * 彻底销毁，通常在页面关闭时调用
     */
    fun release() {
        releasePlayerOnly()
        removeAllViews() // 只有彻底不用了才全清空
        surfaceView = null
        isVisible = false
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        releasePlayerOnly()
    }

    // ==================== AdsLoader.AdViewProvider 必须实现的两个方法 ====================

    /** IMA 把倒计时、跳过按钮、Learn More 全扔这里面 */
    override fun getAdViewGroup(): ViewGroup = adOverlayFrameLayout

    /** 告诉 OMSDK（广告可见性检测）哪些 View 是干嘛的，不写也没事 */
    override fun getAdOverlayInfos(): List<AdOverlayInfo> = ImmutableList.of()
}