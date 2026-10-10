package com.smart.android.tclad.demo;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Test-only process-wide HTTP/HTTPS attempt recording, including independent SDK clients. */
final class NetworkAudit extends ProxySelector {
    private final ProxySelector delegate;
    private final File output;
    private volatile boolean initialization = true;
    private int initializationAttempts;
    private NetworkAudit(Context context, ProxySelector delegate) {
        this.delegate = delegate;
        output = new File(context.getFilesDir(), "network-audit.jsonl");
    }
    static NetworkAudit install(Context context) {
        NetworkAudit audit = new NetworkAudit(context, ProxySelector.getDefault());
        ProxySelector.setDefault(audit);
        return audit;
    }
    void allowAdTraffic() { initialization = false; }
    synchronized int initializationAttempts() { return initializationAttempts; }
    @Override public synchronized List<Proxy> select(URI uri) {
        if (initialization) initializationAttempts++;
        try {
            JSONObject row = new JSONObject().put("timeMs", System.currentTimeMillis())
                .put("phase", initialization ? "initialization" : "ad").put("uri", uri.toString());
            JSONArray stack = new JSONArray();
            for (StackTraceElement frame : Thread.currentThread().getStackTrace())
                if (!frame.getClassName().startsWith("java.lang.Thread")) stack.put(frame.getClassName()+"."+frame.getMethodName());
            row.put("stack", stack);
            try (FileOutputStream stream = new FileOutputStream(output, true)) {
                stream.write((row+"\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception error) { throw new IllegalStateException("Cannot save network audit", error); }
        if (initialization) throw new SecurityException("Network is forbidden during isolation initialization/probes");
        return delegate == null ? java.util.Collections.singletonList(Proxy.NO_PROXY) : delegate.select(uri);
    }
    @Override public void connectFailed(URI uri, SocketAddress address, IOException error) {
        if (delegate != null) delegate.connectFailed(uri, address, error);
    }
}
