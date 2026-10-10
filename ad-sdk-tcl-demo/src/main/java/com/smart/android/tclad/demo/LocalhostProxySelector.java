package com.smart.android.tclad.demo;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;

/** Keeps adb-reversed fixtures local even when the test device has a system proxy. */
final class LocalhostProxySelector extends ProxySelector {
    private final ProxySelector delegate;

    private LocalhostProxySelector(ProxySelector delegate) { this.delegate = delegate; }

    static void install() {
        ProxySelector current = ProxySelector.getDefault();
        if (!(current instanceof LocalhostProxySelector))
            ProxySelector.setDefault(new LocalhostProxySelector(current));
    }

    @Override public List<Proxy> select(URI uri) {
        String host = uri.getHost();
        if ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)
                || "::1".equals(host) || "[::1]".equals(host) || delegate == null)
            return Collections.singletonList(Proxy.NO_PROXY);
        return delegate.select(uri);
    }

    @Override public void connectFailed(URI uri, SocketAddress address, IOException error) {
        if (delegate != null) delegate.connectFailed(uri, address, error);
    }
}
