package com.smart.android.ad_app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLConnection
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

internal object HaierAarUrlConnectionAudit {
    private data class Session(
        val auditId: String = UUID.randomUUID().toString(),
        val startedAtNs: Long = System.nanoTime(),
        val headers: LinkedHashMap<String, MutableList<String>> = linkedMapOf(),
        val body: ByteArrayOutputStream = ByteArrayOutputStream(),
        var method: String = "GET",
        var requestRecorded: Boolean = false,
        var responseRecorded: Boolean = false
    )

    private val sessions = Collections.synchronizedMap(
        WeakHashMap<URLConnection, Session>()
    )

    @Throws(IOException::class)
    fun open(url: URL, proxy: Proxy? = null): URLConnection {
        val normalizedUrl = URL(normalizeAarUrlUa(url.toString()))
        val connection = if (proxy == null) {
            normalizedUrl.openConnection()
        } else {
            normalizedUrl.openConnection(proxy)
        }
        val session = session(connection)
        setHeader(session, "User-Agent", HaierAarRuntimeBridge.currentEffectiveUa(), append = false)
        connection.setRequestProperty("User-Agent", HaierAarRuntimeBridge.currentEffectiveUa())
        return connection
    }

    fun setRequestProperty(connection: URLConnection, name: String?, value: String?) {
        val finalValue = if (name.equals("User-Agent", ignoreCase = true)) {
            HaierAarRuntimeBridge.currentEffectiveUa()
        } else {
            value
        }
        connection.setRequestProperty(name, finalValue)
        if (name != null) setHeader(session(connection), name, finalValue.orEmpty(), append = false)
    }

    fun addRequestProperty(connection: URLConnection, name: String?, value: String?) {
        val finalValue = if (name.equals("User-Agent", ignoreCase = true)) {
            HaierAarRuntimeBridge.currentEffectiveUa()
        } else {
            value
        }
        connection.addRequestProperty(name, finalValue)
        if (name != null) setHeader(session(connection), name, finalValue.orEmpty(), append = true)
    }

    @Throws(java.net.ProtocolException::class)
    fun setRequestMethod(connection: HttpURLConnection, method: String?) {
        connection.requestMethod = method
        synchronized(session(connection)) {
            session(connection).method = method.orEmpty()
        }
    }

    @Throws(IOException::class)
    fun connect(connection: URLConnection) {
        if (HaierAarRuntimeBridge.shouldBlockSdkAction(
                "urlconnection_connect",
                connection.url?.toString().orEmpty()
            )
        ) {
            recordBlocked(connection, "connect")
            return
        }
        prepare(connection)
        try {
            connection.connect()
        } catch (error: Throwable) {
            recordError(connection, error)
            throw error
        }
    }

    @Throws(IOException::class)
    fun getOutputStream(connection: URLConnection): OutputStream {
        if (HaierAarRuntimeBridge.shouldBlockSdkAction(
                "urlconnection_output_stream",
                connection.url?.toString().orEmpty()
            )
        ) {
            recordBlocked(connection, "getOutputStream")
            return ByteArrayOutputStream()
        }
        prepare(connection)
        return try {
            val delegate = connection.outputStream
            CapturingOutputStream(delegate, session(connection))
        } catch (error: Throwable) {
            recordError(connection, error)
            throw error
        }
    }

    @Throws(IOException::class)
    fun getInputStream(connection: URLConnection): InputStream {
        if (HaierAarRuntimeBridge.shouldBlockSdkAction(
                "urlconnection_input_stream",
                connection.url?.toString().orEmpty()
            )
        ) {
            recordBlocked(connection, "getInputStream")
            return ByteArrayInputStream(ByteArray(0))
        }
        prepare(connection)
        recordRequest(connection)
        return try {
            val input = connection.inputStream
            recordResponse(connection, responseCode(connection))
            input
        } catch (error: Throwable) {
            recordError(connection, error)
            throw error
        }
    }

    @Throws(IOException::class)
    fun getResponseCode(connection: HttpURLConnection): Int {
        if (HaierAarRuntimeBridge.shouldBlockSdkAction(
                "urlconnection_response_code",
                connection.url?.toString().orEmpty()
            )
        ) {
            recordBlocked(connection, "getResponseCode")
            return HttpURLConnection.HTTP_UNAVAILABLE
        }
        prepare(connection)
        recordRequest(connection)
        return try {
            val code = connection.responseCode
            recordResponse(connection, code)
            code
        } catch (error: Throwable) {
            recordError(connection, error)
            throw error
        }
    }

    fun getErrorStream(connection: HttpURLConnection): InputStream? {
        if (HaierAarRuntimeBridge.shouldBlockSdkAction(
                "urlconnection_error_stream",
                connection.url?.toString().orEmpty()
            )
        ) {
            recordBlocked(connection, "getErrorStream")
            return ByteArrayInputStream(ByteArray(0))
        }
        prepare(connection)
        recordRequest(connection)
        return try {
            val input = connection.errorStream
            recordResponse(connection, responseCode(connection))
            input
        } catch (error: Throwable) {
            recordError(connection, error)
            throw error
        }
    }

    fun disconnect(connection: HttpURLConnection) {
        if (HaierAarRuntimeBridge.shouldBlockSdkAction(
                "urlconnection_disconnect",
                connection.url?.toString().orEmpty()
            )
        ) {
            recordBlocked(connection, "disconnect")
            return
        }
        if (!session(connection).requestRecorded) recordRequest(connection)
        connection.disconnect()
    }

    private fun recordBlocked(connection: URLConnection, action: String) {
        val state = session(connection)
        val snapshot = synchronized(state) {
            if (!state.requestRecorded) state.requestRecorded = true
            snapshot(state)
        }
        HaierAarAuditUploader.enqueue(
            event(
                connection = connection,
                state = state,
                snapshot = snapshot,
                eventType = "AAR_HTTP_BLOCKED",
                responseCode = if (connection is HttpURLConnection) {
                    HttpURLConnection.HTTP_UNAVAILABLE
                } else {
                    null
                },
                errorMessage = "runtime_gate_disabled:$action"
            ),
            critical = true
        )
    }

    private fun prepare(connection: URLConnection) {
        val effectiveUa = HaierAarRuntimeBridge.currentEffectiveUa()
        val state = session(connection)
        if (!state.requestRecorded) {
            runCatching { connection.setRequestProperty("User-Agent", effectiveUa) }
            setHeader(state, "User-Agent", effectiveUa, append = false)
            if (connection is HttpURLConnection) {
                synchronized(state) { state.method = connection.requestMethod.orEmpty() }
            }
            runCatching {
                connection.requestProperties.forEach { (name, values) ->
                    if (name != null) {
                        synchronized(state) {
                            removeHeader(state, name)
                            state.headers[name] = values?.toMutableList() ?: mutableListOf()
                        }
                    }
                }
            }
        }
    }

    private fun recordRequest(connection: URLConnection) {
        val state = session(connection)
        val snapshot = synchronized(state) {
            if (state.requestRecorded) return
            state.requestRecorded = true
            snapshot(state)
        }
        HaierAarAuditUploader.enqueue(
            event(
                connection = connection,
                state = state,
                snapshot = snapshot,
                eventType = "AAR_HTTP_AUDIT"
            ),
            critical = isCritical(connection.url.toString())
        )
    }

    private fun recordResponse(connection: URLConnection, code: Int?) {
        val state = session(connection)
        val snapshot = synchronized(state) {
            if (state.responseRecorded) return
            state.responseRecorded = true
            snapshot(state)
        }
        val responseHeaders = runCatching {
            connection.headerFields.entries.joinToString("\n") { (name, values) ->
                "${name.orEmpty()}: ${values.orEmpty().joinToString(", ")}"
            }
        }.getOrDefault("")
        HaierAarAuditUploader.enqueue(
            event(
                connection = connection,
                state = state,
                snapshot = snapshot,
                eventType = "AAR_HTTP_RESPONSE",
                responseCode = code,
                responseHeaders = responseHeaders
            ),
            critical = isCritical(connection.url.toString())
        )
    }

    private fun recordError(connection: URLConnection, error: Throwable) {
        val state = session(connection)
        val snapshot = synchronized(state) {
            if (!state.requestRecorded) state.requestRecorded = true
            snapshot(state)
        }
        HaierAarAuditUploader.enqueue(
            event(
                connection = connection,
                state = state,
                snapshot = snapshot,
                eventType = "AAR_HTTP_ERROR",
                errorMessage = "${error.javaClass.name}: ${error.message.orEmpty()}"
            ),
            critical = true
        )
    }

    private data class Snapshot(
        val method: String,
        val headers: String,
        val body: ByteArray
    )

    private fun snapshot(state: Session): Snapshot {
        return Snapshot(
            method = state.method,
            headers = state.headers.entries.joinToString("\n") { (name, values) ->
                "$name: ${values.joinToString(", ")}"
            },
            body = state.body.toByteArray()
        )
    }

    private fun event(
        connection: URLConnection,
        state: Session,
        snapshot: Snapshot,
        eventType: String,
        responseCode: Int? = null,
        responseHeaders: String = "",
        errorMessage: String = ""
    ): HaierAarAuditEvent {
        val contentType = headerValue(state, "Content-Type")
        val body = captureAarBytes(snapshot.body, contentType)
        return HaierAarAuditEvent(
            eventType = eventType,
            sourceStack = "urlconnection:${connection.javaClass.name}",
            method = snapshot.method,
            urlRaw = connection.url.toString(),
            headersRaw = snapshot.headers,
            bodyRaw = body.raw,
            contentType = contentType,
            bodyEncoding = body.encoding,
            bodyLength = body.length,
            bodySha256 = body.sha256,
            responseCode = responseCode,
            responseHeadersRaw = responseHeaders,
            errorMessage = errorMessage,
            durationMs = elapsedMs(state.startedAtNs),
            coverage = "java_urlconnection_explicit_headers",
            auditId = state.auditId,
            extra = mapOf(
                "header_ua_final" to headerValue(state, "User-Agent"),
                "connection_class" to connection.javaClass.name
            )
        )
    }

    private fun session(connection: URLConnection): Session {
        synchronized(sessions) {
            return sessions.getOrPut(connection) { Session() }
        }
    }

    private fun setHeader(state: Session, name: String, value: String, append: Boolean) {
        synchronized(state) {
            val existingKey = state.headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }
            if (!append && existingKey != null) state.headers.remove(existingKey)
            val key = existingKey?.takeIf { append } ?: name
            state.headers.getOrPut(key) { mutableListOf() }.add(value)
        }
    }

    private fun removeHeader(state: Session, name: String) {
        state.headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }
            ?.let(state.headers::remove)
    }

    private fun headerValue(state: Session, name: String): String {
        return synchronized(state) {
            state.headers.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
                ?.joinToString(", ")
                .orEmpty()
        }
    }

    private fun responseCode(connection: URLConnection): Int? {
        if (connection !is HttpURLConnection) return null
        return runCatching { connection.responseCode }.getOrNull()
    }

    private fun elapsedMs(startedAtNs: Long): Long {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNs)
    }

    private fun isCritical(url: String): Boolean {
        return url.contains("/rtb/bid") || url.contains("/vastTag")
    }

    private class CapturingOutputStream(
        private val delegate: OutputStream,
        private val state: Session
    ) : OutputStream() {
        override fun write(value: Int) {
            delegate.write(value)
            synchronized(state) { state.body.write(value) }
        }

        override fun write(bytes: ByteArray) {
            delegate.write(bytes)
            synchronized(state) { state.body.write(bytes) }
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            delegate.write(bytes, offset, length)
            synchronized(state) { state.body.write(bytes, offset, length) }
        }

        override fun flush() {
            delegate.flush()
        }

        override fun close() {
            delegate.close()
        }
    }
}
