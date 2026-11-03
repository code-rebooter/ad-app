package com.smart.android.ad_app

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class TclDownloadCallTrackerTest {
    private fun call() = OkHttpClient().newCall(Request.Builder().url("http://127.0.0.1/unused").build())

    private fun response(call: Call, body: ResponseBody?) = Response.Builder()
        .request(call.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()

    @Test fun `slow VAST tail stays cancellable after 256 KiB capture and response return`() {
        SlowXmlServer().use { server ->
            val downloads = TclDownloadCallTracker()
            var capturedBytes = 0L
            val client = OkHttpClient.Builder()
                .readTimeout(15, TimeUnit.SECONDS)
                .addInterceptor { chain -> downloads.execute(chain.call()) { chain.proceed(chain.request()) } }
                // Same order as the runtime interceptor and native SDK capture interceptor.
                .addInterceptor { chain ->
                    chain.proceed(chain.request()).also {
                        capturedBytes = it.peekBody(256L * 1024L).bytes().size.toLong()
                    }
                }.build()
            val call = client.newCall(Request.Builder().url(server.url).build())
            val reader = Executors.newSingleThreadExecutor()
            try {
                call.execute().use { response ->
                    assertEquals(256L * 1024L, capturedBytes)
                    assertEquals("application/xml", response.body!!.contentType().toString())
                    val result = reader.submit<IOException?> {
                        try { response.body!!.string(); null } catch (error: IOException) { error }
                    }
                    try {
                        result.get(150, TimeUnit.MILLISECONDS)
                        fail("The XML tail must still be pending")
                    } catch (_: TimeoutException) { }
                    downloads.cancelAll()
                    assertTrue("Closing the session must cancel the returned Call", call.isCanceled())
                    assertNotNull("Body reading must fail before the network timeout", result.get(2, TimeUnit.SECONDS))
                    assertEquals("Cancellation must not depend on sending the XML tail", 1L, server.sendTail.count)
                }
            } finally {
                downloads.cancelAll()
                reader.shutdownNow()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }

    @Test fun `body EOF releases the Call and preserves the complete XML`() {
        val downloads = TclDownloadCallTracker()
        val call = call()
        val xml = "<VAST><!--${"x".repeat(300 * 1024)}--></VAST>"
        downloads.execute(call) { response(call, xml.toResponseBody("application/xml".toMediaType())) }.use {
            assertEquals(xml.toByteArray().size.toLong(), it.body!!.contentLength())
            val source = it.body!!.source()
            assertSame(source, it.body!!.source())
            assertEquals(xml, source.readUtf8())
            downloads.cancelAll()
            assertFalse("A fully consumed Call must leave the cancellation set", call.isCanceled())
        }
    }

    @Test fun `closing an unread body releases the Call and closes the delegate`() {
        val downloads = TclDownloadCallTracker()
        val call = call()
        var closed = false
        val source = object : ForwardingSource(Buffer().writeUtf8("<VAST/>")) {
            override fun close() { closed = true; super.close() }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = "application/xml".toMediaType()
            override fun contentLength() = -1L
            override fun source() = source
        }
        downloads.execute(call) { response(call, body) }.close()
        downloads.cancelAll()
        assertTrue(closed)
        assertFalse(call.isCanceled())
    }

    @Test fun `body read or close failure releases the Call`() {
        for (failRead in listOf(true, false)) {
            val downloads = TclDownloadCallTracker()
            val call = call()
            val failure = IOException("fixture body failure")
            val source = object : ForwardingSource(Buffer()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (failRead) throw failure
                    return super.read(sink, byteCount)
                }
                override fun close() {
                    if (!failRead) throw failure
                    super.close()
                }
            }.buffer()
            val body = object : ResponseBody() {
                override fun contentType() = null
                override fun contentLength() = -1L
                override fun source() = source
            }
            val response = downloads.execute(call) { response(call, body) }
            try {
                if (failRead) response.body!!.source().readByte() else response.body!!.source().close()
                fail("Delegate failure must propagate")
            } catch (error: IOException) {
                assertSame(failure, error)
            } finally { response.close() }
            downloads.cancelAll()
            assertFalse(call.isCanceled())
        }
    }

    @Test fun `failure before response or absent body releases the Call`() {
        val downloads = TclDownloadCallTracker()
        val failed = call()
        val failure = IOException("fixture connection failure")
        try {
            downloads.execute(failed) { throw failure }
            fail("Connection failure must propagate")
        } catch (error: IOException) { assertSame(failure, error) }
        val empty = call()
        downloads.execute(empty) { response(empty, null) }
        downloads.cancelAll()
        assertFalse(failed.isCanceled())
        assertFalse(empty.isCanceled())
    }

    @Test fun `cancel during response handoff still cancels its Call`() {
        val downloads = TclDownloadCallTracker()
        val call = call()
        downloads.execute(call) {
            downloads.cancelAll()
            response(call, "<VAST/>".toResponseBody())
        }.close()
        assertTrue(call.isCanceled())
    }

    @Test fun `a download entering after stop is cancelled without proceeding`() {
        val downloads = TclDownloadCallTracker()
        downloads.cancelAll()
        val call = call()
        try {
            downloads.execute(call) {
                fail("A closed session must not start another download")
                response(call, null)
            }
            fail("A closed session must reject the download")
        } catch (_: IOException) { }
        assertTrue(call.isCanceled())
    }

    private class SlowXmlServer : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val worker = Executors.newSingleThreadExecutor()
        @Volatile private var socket: Socket? = null
        val sendTail = CountDownLatch(1)
        val url = "http://127.0.0.1:${server.localPort}/vast.xml"

        init {
            worker.submit {
                server.accept().use { accepted ->
                    socket = accepted
                    accepted.soTimeout = 5_000
                    val input = accepted.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    val prefix = ("<VAST><!--" + "x".repeat(300 * 1024)).toByteArray()
                    val tail = "--></VAST>".toByteArray()
                    val output = accepted.getOutputStream()
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/xml\r\n" +
                        "Content-Length: ${prefix.size + tail.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    output.write(prefix)
                    output.flush()
                    if (sendTail.await(10, TimeUnit.SECONDS)) {
                        output.write(tail)
                        output.flush()
                    }
                }
            }
        }

        override fun close() {
            sendTail.countDown()
            socket?.close()
            server.close()
            worker.shutdownNow()
        }
    }
}
