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
import com.smart.android.ad_app.AdConfigManager
import com.smart.android.ad_app.AdLocalLog as Log
import com.smart.android.ad_app.adDebugPrintln
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
    var requestAdId: String? = null
    var enableDebugLogging: Boolean = false

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
            isDebugMode = enableDebugLogging
        }
    }

    private fun debugLog(message: String) {
        if (enableDebugLogging) {
            adDebugPrintln(message)
        }
    }


    private fun initializePlayer() {

        ImaSdkFactory.getInstance().initialize(context, imaSdkSettings)
        // 1. IMA AdsLoader
        adsLoader = ImaAdsLoader.Builder(context)
            // 将媒体加载超时时间从默认的 8 秒增加到 15 秒
            .setMediaLoadTimeoutMs(20000)
            .setAdEventListener { event: AdEvent ->
                debugLog("当前的广告事件：${event.type}")
                when (event.type) {
                    AdEvent.AdEventType.LOADED -> {
                        isVisible = true
                        if (enableDebugLogging) {
                            event.ad?.let { ad ->
                                // IMA Android SDK 可用的 Ad 属性
                                val adId = ad.adId
                                val title = ad.title
                                val description = ad.description
                                val contentType = ad.contentType
                                val duration = ad.duration
                                val width = ad.vastMediaWidth
                                val height = ad.vastMediaHeight
                                val bitrate = ad.vastMediaBitrate
                                val dealId = ad.dealId
                                val adSystem = ad.adSystem
                                val advertiserName = ad.advertiserName
                                val creativeId = ad.creativeId
                                val creativeAdId = ad.creativeAdId

                                debugLog("=== VAST 广告诊断信息 ===")
                                debugLog("广告ID: $adId")
                                debugLog("标题: $title")
                                debugLog("内容类型: $contentType")
                                debugLog("时长: ${duration}s")
                                debugLog("分辨率: ${width}x${height}")
                                debugLog("码率: ${bitrate}kbps")
                                debugLog("广告系统: $adSystem")
                                debugLog("创意ID: $creativeId")

                                Thread {
                                    val networkDiagnostics = getNetworkDiagnostics()
                                    val fullDiagnostics = buildString {
                                        append("{")
                                        append("\"adId\":\"${adId ?: ""}\",")
                                        append("\"title\":\"${title?.replace("\"", "'") ?: ""}\",")
                                        append("\"description\":\"${description?.replace("\"", "'") ?: ""}\",")
                                        append("\"contentType\":\"${contentType ?: ""}\",")
                                        append("\"duration\":$duration,")
                                        append("\"width\":$width,")
                                        append("\"height\":$height,")
                                        append("\"bitrate\":$bitrate,")
                                        append("\"dealId\":\"${dealId ?: ""}\",")
                                        append("\"adSystem\":\"${adSystem ?: ""}\",")
                                        append("\"advertiserName\":\"${advertiserName ?: ""}\",")
                                        append("\"creativeId\":\"${creativeId ?: ""}\",")
                                        append("\"creativeAdId\":\"${creativeAdId ?: ""}\",")
                                        append(networkDiagnostics)
                                        append("}")
                                    }
                                    AdConfigManager.reportAdStatus(
                                        "loaded_diagnostics",
                                        fullDiagnostics,
                                        requestAdId
                                    )
                                }.start()
                            }
                        }
                    }

                    AdEvent.AdEventType.STARTED -> {
                        AdConfigManager.reportAdStatus("play_start", "播放开始", requestAdId)
                        onAdStarted?.invoke()

                    }
                    AdEvent.AdEventType.FIRST_QUARTILE -> {
                        AdConfigManager.reportAdStatus("play_25", "播放进度25%", requestAdId)
                    }

                    AdEvent.AdEventType.MIDPOINT -> {
                        AdConfigManager.reportAdStatus("play_50", "播放进度50%", requestAdId)
                    }

                    AdEvent.AdEventType.THIRD_QUARTILE -> {
                        AdConfigManager.reportAdStatus("play_75", "播放进度75%", requestAdId)
                    }
                    AdEvent.AdEventType.COMPLETED -> {
                        AdConfigManager.reportAdStatus("completed", "播放完成", requestAdId)
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
                debugLog("当前的广告错误：${error}")
                isVisible = false
                val errorMsg = error?.toString() ?: "UNKNOWN_AD_ERROR"
                debugLog("当前的广告错误：$errorMsg")
                isVisible = false
                AdConfigManager.reportAdStatus("failed", errorMsg, requestAdId)
                onAdError?.invoke(errorMsg)
            }.setImaSdkSettings(imaSdkSettings).setDebugModeEnabled(enableDebugLogging)
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

    /**
     * 获取网络诊断信息（公网IP + ping测试）
     * 注意：此方法必须在子线程调用
     * @return JSON 格式的诊断信息字符串（不含外层大括号的结尾）
     */
    private fun getNetworkDiagnostics(): String {
        val result = StringBuilder()
        
        debugLog("=== 网络连通性诊断 ===")
        
        // 1. 获取公网IP
        var publicIp = ""
        val ipStartTime = System.currentTimeMillis()
        try {
            // 尝试多个公网IP服务
            val ipServices = listOf(
                "https://api.ipify.org",
                "https://ipinfo.io/ip",
                "https://icanhazip.com"
            )
            for (service in ipServices) {
                try {
                    val url = java.net.URL(service)
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    if (conn.responseCode == 200) {
                        publicIp = conn.inputStream.bufferedReader().readText().trim()
                        conn.disconnect()
                        break
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    // 继续尝试下一个服务
                }
            }
        } catch (e: Exception) {
            publicIp = "FAILED:${e.message}"
        }
        val ipFetchTime = System.currentTimeMillis() - ipStartTime
        debugLog("公网IP: $publicIp (获取耗时: ${ipFetchTime}ms)")
        result.append("\"publicIp\":\"$publicIp\",")
        result.append("\"ipFetchTimeMs\":$ipFetchTime,")
        
        // 2. Ping 关键服务器测试网络质量
        val pingTargets = mapOf(
            "google_dns" to "8.8.8.8",
            "ali_dns" to "223.5.5.5",
            "google_ads" to "pagead2.googlesyndication.com",
            "doubleclick" to "pubads.g.doubleclick.net"
        )
        
        val pingResults = mutableMapOf<String, Long>()
        for ((name, host) in pingTargets) {
            val pingTime = pingHost(host)
            pingResults[name] = pingTime
            debugLog("Ping $name ($host): ${if (pingTime >= 0) "${pingTime}ms" else "FAILED"}")
        }
        
        result.append("\"pingResults\":{")
        result.append(pingResults.entries.joinToString(",") { "\"${it.key}\":${it.value}" })
        result.append("}")
        
        debugLog("=== 网络诊断完成 ===")
        
        return result.toString()
    }
    
    /**
     * Ping 指定主机（TCP连接测试）
     * @return 延迟时间(ms)，失败返回 -1
     */
    private fun pingHost(host: String): Long {
        return try {
            val startTime = System.currentTimeMillis()
            val socket = java.net.Socket()
            // 判断是IP还是域名，选择不同的端口
            val port = if (host.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$"))) 53 else 443
            socket.connect(java.net.InetSocketAddress(host, port), 5000)
            val elapsed = System.currentTimeMillis() - startTime
            socket.close()
            elapsed
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * 诊断方法：测试 VAST 视频 CDN 连通性并返回结果
     * 注意：此方法必须在子线程调用
     * @return JSON 格式的诊断信息字符串（不含外层大括号）
     */
    private fun pingMediaUrlAndGetResult(mediaUrl: String): String {
        val result = StringBuilder()
        
        try {
            val url = java.net.URL(mediaUrl)
            val host = url.host
            
            debugLog("=== CDN 连通性诊断 ===")
            debugLog("测试 Host: $host")
            result.append("\"host\":\"$host\",")
            
            // 1. DNS 解析测试
            var dnsTime: Long = -1
            var ipAddresses = ""
            val dnsStartTime = System.currentTimeMillis()
            try {
                val addresses = java.net.InetAddress.getAllByName(host)
                dnsTime = System.currentTimeMillis() - dnsStartTime
                debugLog("DNS 解析耗时: ${dnsTime}ms")
                ipAddresses = addresses.joinToString(";") { it.hostAddress ?: "" }
                addresses.forEach { addr ->
                    debugLog("  IP: ${addr.hostAddress}")
                }
            } catch (e: Exception) {
                debugLog("DNS 解析失败: ${e.message}")
                ipAddresses = "FAILED:${e.message}"
            }
            result.append("\"dnsTimeMs\":$dnsTime,")
            result.append("\"ipAddresses\":\"$ipAddresses\",")
            
            // 2. HTTP HEAD 请求测试（不下载完整内容，只获取头部信息）
            var httpResponseCode = -1
            var responseTime: Long = -1
            var contentLength: Long = -1
            var httpContentType = ""
            
            val connStartTime = System.currentTimeMillis()
            try {
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.apply {
                    requestMethod = "HEAD"
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("User-Agent", "ExoPlayer")
                }
                
                try {
                    httpResponseCode = connection.responseCode
                    responseTime = System.currentTimeMillis() - connStartTime
                    contentLength = connection.contentLengthLong
                    httpContentType = connection.contentType ?: ""
                    
                    debugLog("总响应耗时: ${responseTime}ms")
                    debugLog("HTTP 响应码: $httpResponseCode")
                    debugLog("Content-Length: ${contentLength / 1024}KB ($contentLength bytes)")
                    debugLog("Content-Type: $httpContentType")
                    
                    if (httpResponseCode != 200) {
                        debugLog("⚠️ 警告：非正常响应码，可能是CDN问题")
                    }
                    if (responseTime > 5000) {
                        debugLog("⚠️ 警告：响应时间过长，可能导致超时！")
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                debugLog("HTTP 请求失败: ${e.message}")
                httpContentType = "FAILED:${e.message}"
            }
            result.append("\"httpResponseCode\":$httpResponseCode,")
            result.append("\"responseTimeMs\":$responseTime,")
            result.append("\"contentLengthBytes\":$contentLength,")
            result.append("\"httpContentType\":\"$httpContentType\",")
            
            // 3. 简单的 TCP 连接测试
            var tcpPingTime: Long = -1
            try {
                val pingStartTime = System.currentTimeMillis()
                val socket = java.net.Socket()
                val port = if (url.protocol == "https") 443 else 80
                socket.connect(java.net.InetSocketAddress(host, port), 5000)
                tcpPingTime = System.currentTimeMillis() - pingStartTime
                socket.close()
                debugLog("TCP 连接延迟: ${tcpPingTime}ms")
            } catch (e: Exception) {
                debugLog("TCP 连接测试失败: ${e.message}")
            }
            result.append("\"tcpPingMs\":$tcpPingTime")
            
            debugLog("=== 诊断完成 ===")
            
        } catch (e: Exception) {
            debugLog("CDN 诊断失败: ${e.message}")
            Log.e("VastAdPlayerView", "CDN 诊断失败: ${e.message}", e)
            result.append("\"error\":\"${e.message?.replace("\"", "'")}\"")
        }
        
        return result.toString()
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
