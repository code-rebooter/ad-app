package com.smart.android.tclad.runtime

import com.google.gson.Gson
import com.google.gson.JsonParser

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Our patch API is separate from TCL's advertising HTTP client. */
internal class GoogleGamPatchClient(
    private val client: OkHttpClient,
    private val endpoint: String
) {
    companion object {
        val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder().retryOnConnectionFailure(false)
                .followRedirects(false).followSslRedirects(false).build()
        }
    }

    fun request(
        parameters: Map<String, Any>, timeoutMs: Long,
        onResult: (url: String?, error: String?) -> Unit
    ): Call {
        require(timeoutMs > 0) { "GAM patch timeout must be positive" }
        val body = GoogleGamPatchProtocol.requestJson(parameters)
        val request = Request.Builder().url(endpoint)
            .header("Accept", "application/json")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val call = client.newCall(request)
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(null, e.message ?: "GAM patch network failure")
            }

            override fun onResponse(call: Call, response: Response) {
                val result = response.use {
                    runCatching {
                        GoogleGamPatchProtocol.responseUrl(it.code, it.body?.string().orEmpty())
                    }
                }
                result.fold(
                    onSuccess = { onResult(it, null) },
                    onFailure = { onResult(null, it.message ?: "GAM patch response failure") }
                )
            }
        })
        return call
    }
}

internal object GoogleGamPatchProtocol {
    private val gson = Gson()

    fun requestJson(parameters: Map<String, Any>): String {
        for (key in listOf("request_id", "channel_id", "ad_tag_url", "rdid")) {
            require((parameters[key] as? String)?.isNotBlank() == true) { "Missing GAM patch $key" }
        }
        return gson.toJson(parameters)
    }

    /** Preserve the backend URL byte-for-byte after JSON decoding. */
    fun responseUrl(httpCode: Int, raw: String): String {
        if (httpCode != 200) throw IOException("GAM patch HTTP $httpCode")
        val root = JsonParser.parseString(raw)
        if (!root.isJsonObject) throw IOException("GAM patch response must be an object")
        val values = root.asJsonObject
        val code = values.get("code")
        if (code == null || !code.isJsonPrimitive || !code.asJsonPrimitive.isNumber ||
            code.asBigDecimal.compareTo(200.toBigDecimal()) != 0) {
            val message = values.get("msg")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            throw IOException(message?.takeIf { it.isNotBlank() } ?: "GAM patch unsuccessful code")
        }
        val data = values.get("data")
        val url = data?.takeIf { it.isJsonObject }?.asJsonObject?.get("ad_tag_url")
        if (url == null || !url.isJsonPrimitive || !url.asJsonPrimitive.isString || url.asString.isBlank()) {
            throw IOException("GAM patch returned no ad_tag_url")
        }
        return url.asString
    }
}
