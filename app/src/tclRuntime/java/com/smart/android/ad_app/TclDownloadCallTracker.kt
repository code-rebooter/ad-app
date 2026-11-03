package com.smart.android.ad_app

import okhttp3.Call
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

/** A returned response still owns its Call until its body reaches EOF, closes, or fails. */
internal class TclDownloadCallTracker {
    private val calls: MutableSet<Call> = Collections.newSetFromMap(IdentityHashMap())
    private var stopped = false

    fun execute(call: Call, proceed: () -> Response): Response {
        val accepted = synchronized(this) { !stopped && calls.add(call) }
        if (!accepted) {
            call.cancel()
            throw IOException("TCL request closed")
        }
        try {
            val response = proceed()
            val body = response.body
            if (body == null) {
                release(call)
                return response
            }
            val source = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long = try {
                    super.read(sink, byteCount).also { if (it == -1L) release(call) }
                } catch (error: Throwable) {
                    release(call)
                    throw error
                }

                override fun close() {
                    try { super.close() } finally { release(call) }
                }
            }.buffer()
            return response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = source
            }).build()
        } catch (error: Throwable) {
            release(call)
            throw error
        }
    }

    fun cancelAll() {
        val pending = synchronized(this) {
            stopped = true
            calls.toList()
        }
        pending.forEach(Call::cancel)
    }

    private fun release(call: Call) {
        synchronized(this) { calls.remove(call) }
    }
}
