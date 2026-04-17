@file:Suppress("DEPRECATION")

// RtbAdManager.kt —— 绝对终极完美版（2025-11-19 彻底封神）
package com.smart.android.ad_app.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.gson.Gson
import com.smart.android.ad_app.BuildConfig
import io.github.lib_autorun.log.printLog
import io.github.lib_autorun.net.NetworkHelper
import io.github.lib_autorun.net.enum.RequestMethod
import java.util.*

object AdManager {

  private const val BID_URL = "https://api.kytira.cc/rtb/bid"

     //private const val BID_URL = "http://api.danixd.cc/rtb/bid"





    private val gson = Gson()

    fun requestHomeVideoAd(
        context: Context,
        onResult: (vastXml: String?, error: String?) -> Unit
    ) {

        val (w, h) = getScreenResolution(context)
        val ifa = getGoogleAdId(context) ?: getAndroidIdAsUuid(context)

        val requestBody = mapOf(
            "channel_id"  to BuildConfig.CHANNEL,
            "app_id"      to context.packageName,                                     // 1
            "app_name"    to "launcher",                                              // 2  按文档示例写死
            "bundle"      to context.packageName,                                     // 3
            "ua"          to (System.getProperty("http.agent") ?: ""),                // 4
            "ifa"         to ifa,                                      // 5  有就传，没有传空
            "make"        to Build.MANUFACTURER,                                      // 6
            "model"       to Build.MODEL,                                             // 7
            "os"          to "Android",                                               // 8
            "osv"         to Build.VERSION.RELEASE,                                   // 9
            "language"    to Locale.getDefault().toString().replace("_", "-"),        // 10
            "video_w"     to context.resources.displayMetrics.widthPixels,           // 11
            "video_h"     to context.resources.displayMetrics.heightPixels,          // 12
            "screen_w"    to w,           // 13
            "screen_h"    to h           // 14
        )

        if (BuildConfig.DEBUG) {
            "【RTB广告】发送竞价请求 → ".printLog()
            println(gson.toJson(requestBody))
        }

        NetworkHelper.makeRequest<AdData>(
            url = BID_URL,
            method = RequestMethod.POST,
            params = requestBody,
            isEncryted = false,
            useDomainSwitch = false,
        ) { response, error ->
            if (error != null) {
                "【RTB广告】请求失败：${error.message}".printLog()
                onResult(null, error.message?:"网络错误")
                return@makeRequest
            }

            onResult(response?.adm, null)
            if (BuildConfig.DEBUG) {
                "【RTB广告】服务器返回：".printLog()
                println(response ?: "null")
            }
        }
    }

    fun getScreenResolution(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds: Rect = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    fun getGoogleAdId(context: Context): String? {
        return try {
            val clazz = Class.forName("com.google.android.gms.ads.identifier.AdvertisingIdClient")
            val info = clazz.getMethod("getAdvertisingIdInfo", Context::class.java).invoke(null, context)
            info.javaClass.getMethod("getId").invoke(info) as String
        } catch (e: Exception) { null }
    }

    @SuppressLint("HardwareIds")
    fun getAndroidIdAsUuid(context: Context): String {
        // 1. 优先返回真实 Google Advertising ID
        getGoogleAdId(context)?.let { return it }

        return try {
            val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: return "00000000-0000-4000-8000-000000000000"

            // 过滤模拟器经典假值
            if (raw.isBlank() || raw.equals("9774d56d682f617c", ignoreCase = true)) {
                return "00000000-0000-4000-8000-000000000000"
            }

            // 2. 只保留十六进制字符
            val hex = raw.replace(Regex("[^0-9a-fA-F]"), "").lowercase()

            // 3. 生成 32 位基础 hex（重复 + 翻转补足）
            val base32 = if (hex.isEmpty()) {
                "00000000000000000000000000000000"
            } else if (hex.length >= 32) {
                hex.substring(0, 32)
            } else {
                val sb = StringBuilder()
                val rev = hex.reversed()
                while (sb.length < 32) {
                    sb.append(hex)
                    if (sb.length < 32) sb.append(rev)
                }
                sb.toString().substring(0, 32)
            }

            // 4. 严格按 8-4-4-4-12 拼接，强制符合 UUID v4 规范
            val p1 = base32.substring(0, 8)                    // 8 位
            val p2 = base32.substring(8, 12)                   // 4 位
            val p3 = "4" + base32.substring(12, 15)            // 第13位强制为 4 → 共4位
            val p4 = "8" + base32.substring(16, 19)            // 第17位强制为 8 → 共4位
            val p5 = base32.substring(20, 32)                  // 12 位

            "$p1-$p2-$p3-$p4-$p5"   // 永远是 8-4-4-4-12，36字符，完美！

        } catch (e: Exception) {
            "00000000-0000-4000-8000-000000000000"
        }
    }




}
