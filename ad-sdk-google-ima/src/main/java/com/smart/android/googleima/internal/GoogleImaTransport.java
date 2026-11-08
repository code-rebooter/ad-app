package com.smart.android.googleima.internal;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URLConnection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Actual native/WebView requests owned by one IMA session, including late opens. */
final class GoogleImaTransport {
    private final Set<HttpURLConnection> connections =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean closed;

    void add(URLConnection connection) throws IOException {
        if (!(connection instanceof HttpURLConnection)) return;
        HttpURLConnection http = (HttpURLConnection) connection;
        synchronized (this) {
            if (!closed) { connections.add(http); return; }
        }
        http.disconnect();
        throw new IOException("IMA session cancelled");
    }

    synchronized void remove(URLConnection connection) { connections.remove(connection); }

    void close() {
        HttpURLConnection[] pending;
        synchronized (this) {
            closed = true;
            pending = connections.toArray(new HttpURLConnection[0]);
            connections.clear();
        }
        for (HttpURLConnection connection : pending) {
            try { connection.disconnect(); } catch (RuntimeException ignored) { }
        }
    }
}
