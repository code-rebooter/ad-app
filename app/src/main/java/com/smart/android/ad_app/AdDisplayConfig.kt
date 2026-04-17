package com.smart.android.ad_app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object AdDisplayConfig {

    private const val TAG = "AdDisplayConfig"
    private const val PREFS_NAME = "ad_display_config"
    private const val KEY_LOCAL_HIDDEN = "local_hidden_mode"
    private const val KEY_REMOTE_HIDDEN = "remote_hidden_mode"
    private const val KEY_REMOTE_SET = "remote_config_set"
    private const val KEY_REMOTE_URL = "remote_config_url"

    private val executor = Executors.newSingleThreadExecutor()
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        if (BuildConfig.FLAVOR == "hq008") {
            prefs?.edit()
                ?.putBoolean(KEY_LOCAL_HIDDEN, true)
                ?.commit()
            Log.i(TAG, "Force hidden mode for hq008. local=${isLocalHiddenMode()}")
        }
    }

    fun isLocalHiddenMode(): Boolean {
        return prefs?.getBoolean(KEY_LOCAL_HIDDEN, true) ?: true
    }

    fun setLocalHiddenMode(hidden: Boolean) {
        prefs?.edit()?.putBoolean(KEY_LOCAL_HIDDEN, hidden)?.apply()
        Log.i(TAG, "Local hidden mode set to $hidden")
    }

    fun isRemoteConfigSet(): Boolean {
        return prefs?.getBoolean(KEY_REMOTE_SET, false) ?: false
    }

    fun isRemoteHiddenMode(): Boolean {
        return prefs?.getBoolean(KEY_REMOTE_HIDDEN, true) ?: true
    }

    fun getRemoteConfigUrl(): String {
        return prefs?.getString(KEY_REMOTE_URL, "") ?: ""
    }

    fun setRemoteConfigUrl(url: String) {
        prefs?.edit()?.putString(KEY_REMOTE_URL, url)?.apply()
    }

    fun fetchRemoteConfig(url: String? = null, onResult: ((Boolean) -> Unit)? = null) {
        val configUrl = url ?: getRemoteConfigUrl()
        if (configUrl.isBlank()) {
            Log.w(TAG, "Remote config URL is empty. Skipping fetch.")
            onResult?.invoke(false)
            return
        }

        executor.execute {
            runCatching {
                val connection = URL(configUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.requestMethod = "GET"

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val hiddenMode = json.optBoolean("hidden_mode", true)
                    prefs?.edit()
                        ?.putBoolean(KEY_REMOTE_HIDDEN, hiddenMode)
                        ?.putBoolean(KEY_REMOTE_SET, true)
                        ?.apply()
                    Log.i(TAG, "Remote config fetched: hidden_mode=$hiddenMode")
                    onResult?.invoke(true)
                } else {
                    Log.w(TAG, "Remote config fetch failed: HTTP ${connection.responseCode}")
                    onResult?.invoke(false)
                }
                connection.disconnect()
            }.onFailure { error ->
                Log.e(TAG, "Remote config fetch error: ${error.message}")
                onResult?.invoke(false)
            }
        }
    }

    fun clearRemoteConfig() {
        prefs?.edit()
            ?.remove(KEY_REMOTE_HIDDEN)
            ?.remove(KEY_REMOTE_SET)
            ?.apply()
        Log.i(TAG, "Remote config cleared.")
    }

    fun setRemoteHiddenMode(hidden: Boolean) {
        prefs?.edit()
            ?.putBoolean(KEY_REMOTE_HIDDEN, hidden)
            ?.putBoolean(KEY_REMOTE_SET, true)
            ?.apply()
        Log.i(TAG, "Remote hidden mode set to $hidden")
    }

    fun isHiddenMode(): Boolean {
        if (BuildConfig.FLAVOR == "hq008") {
            return if (isRemoteConfigSet()) {
                isRemoteHiddenMode()
            } else {
                isLocalHiddenMode()
            }
        }
        return if (isRemoteConfigSet()) {
            isRemoteHiddenMode()
        } else {
            isLocalHiddenMode()
        }
    }
}
