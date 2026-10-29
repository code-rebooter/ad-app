package com.smart.android.googleima.internal;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Keep;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.google.ads.interactivemedia.v3.impl.data.zzby;
import com.google.ads.interactivemedia.v3.impl.data.zzbz;
import com.google.android.gms.ads.identifier.AdvertisingIdClient;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.smart.android.ad_app.google.ima.local.ImaSharedAdvertisingIdentity;
import com.smart.android.ad_app.google.ima.local.signal.PrimitiveObserver;
import com.smart.android.ad_app.google.ima.local.signal.android.ImaLocalFlagsProvider;
import com.smart.android.ad_app.google.ima.local.signal.android.ImaLocalMsOwner;
import com.smart.android.googleima.GoogleImaAdListener;
import com.smart.android.googleima.GoogleImaAuditEvent;
import com.smart.android.googleima.logging.PropertyLog;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runtime targets used by the exact-version IMA bytecode patch. */
@Keep
public final class GoogleImaRuntimeBridge {
    private static final String TAG = "GoogleImaControl";
    private static final String HOST_BRIDGE =
            "com.smart.android.ad_app.Hq008XhsxAarRuntimeBridge";
    private static final Map<Object, Context> MS_OWNERS = new WeakHashMap<>();
    private static final Map<WebView, Boolean> AUDITED_WEB_VIEWS = new WeakHashMap<>();
    private static final ThreadLocal<Boolean> URL_IDENTITY_FALLBACK = new ThreadLocal<>();
    private static final Object AUDIT_LOCK = new Object();
    private static final String AUDIT_JS_BRIDGE = "__smartImaAudit";
    private static final String AUDIT_NETWORK_SCRIPT = """
            (function() {
              'use strict';
              var root = typeof globalThis === 'object' ? globalThis : window;
              if (root.__smartImaVastAuditV1 === true) return;
              root.__smartImaVastAuditV1 = true;

              function isVast(text) {
                return typeof text === 'string' &&
                  /<(?:[\\w.-]+:)?VAST(?:\\s|>)/i.test(text);
              }

              function emit(source, method, url, status, contentType, text) {
                try {
                  if (!isVast(text) || !root.__smartImaAudit) return;
                  root.__smartImaAudit.onVastResponse(
                    String(source || ''),
                    String(method || 'GET'),
                    String(url || ''),
                    Number(status) || 0,
                    String(contentType || ''),
                    text
                  );
                } catch (_) {}
              }

              var Xhr = root.XMLHttpRequest;
              if (Xhr && Xhr.prototype) {
                var originalOpen = Xhr.prototype.open;
                var originalSend = Xhr.prototype.send;
                Xhr.prototype.open = function(method, url) {
                  this.__smartImaAuditMethod = method;
                  this.__smartImaAuditUrl = url;
                  return originalOpen.apply(this, arguments);
                };
                Xhr.prototype.send = function() {
                  var xhr = this;
                  if (!xhr.__smartImaAuditBound) {
                    xhr.__smartImaAuditBound = true;
                    xhr.addEventListener('loadend', function() {
                      try {
                        emit(
                          'webview_xhr',
                          xhr.__smartImaAuditMethod,
                          xhr.responseURL || xhr.__smartImaAuditUrl,
                          xhr.status,
                          xhr.getResponseHeader('content-type') || '',
                          xhr.responseText
                        );
                      } catch (_) {}
                    });
                  }
                  return originalSend.apply(this, arguments);
                };
              }

              var originalFetch = root.fetch;
              if (typeof originalFetch === 'function') {
                root.fetch = function(input, init) {
                  var method = init && init.method ? init.method : 'GET';
                  var requestedUrl = typeof input === 'string'
                    ? input
                    : (input && input.url) || '';
                  return originalFetch.apply(this, arguments).then(function(response) {
                    try {
                      var copy = response.clone();
                      copy.text().then(function(text) {
                        emit(
                          'webview_fetch',
                          method,
                          response.url || requestedUrl,
                          response.status,
                          response.headers && response.headers.get
                            ? response.headers.get('content-type') || ''
                            : '',
                          text
                        );
                      }).catch(function() {});
                    } catch (_) {}
                    return response;
                  });
                };
              }
            })();
            """;
    private static AuditState activeAudit;
    private static final ExecutorService MS_EXECUTOR = Executors.newCachedThreadPool(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "GoogleImaLocalMs");
                    thread.setDaemon(true);
                    return thread;
                }
            }
    );

    private GoogleImaRuntimeBridge() {}

    @Keep
    public static AdvertisingIdClient.Info getAdvertisingIdInfoForUrl(Context context) {
        AdvertisingResolution resolution = resolveAdvertisingId(context);
        URL_IDENTITY_FALLBACK.set(resolution.localFallback);
        return new AdvertisingIdClient.Info(resolution.id, resolution.limitedAdTracking);
    }

    @Keep
    public static AdvertisingIdClient.Info getAdvertisingIdInfoForV2(Context context) {
        AdvertisingResolution resolution = resolveAdvertisingId(context);
        return new AdvertisingIdClient.Info(resolution.id, resolution.limitedAdTracking);
    }

    @Keep
    public static boolean shouldCollectAppSet(boolean configured) {
        boolean localFallback = Boolean.TRUE.equals(URL_IDENTITY_FALLBACK.get());
        URL_IDENTITY_FALLBACK.remove();
        return configured && !localFallback;
    }

    private static AdvertisingResolution resolveAdvertisingId(Context context) {
        boolean lastKnownLat = false;
        try {
            AdvertisingIdClient.Info original =
                    AdvertisingIdClient.getAdvertisingIdInfo(context);
            if (original != null) {
                lastKnownLat = original.isLimitAdTrackingEnabled();
                if (ImaSharedAdvertisingIdentity.isUsable(original.getId())) {
                    PropertyLog.i(TAG, "control=advertisingId source=gms");
                    return new AdvertisingResolution(
                            original.getId(),
                            lastKnownLat,
                            false
                    );
                }
            }
        } catch (Exception | LinkageError failure) {
            PropertyLog.w(TAG, "control=advertisingId source=gms result=unavailable", failure);
        }

        try {
            ImaSharedAdvertisingIdentity.AdvertisingInfo local =
                    ImaSharedAdvertisingIdentity.readAdvertising(context);
            lastKnownLat = local.limitedAdTracking;
            if (ImaSharedAdvertisingIdentity.isUsable(local.id)) {
                PropertyLog.i(TAG, "control=advertisingId source=shared_local");
                return new AdvertisingResolution(local.id, lastKnownLat, true);
            }
        } catch (RuntimeException | LinkageError failure) {
            PropertyLog.w(
                    TAG,
                    "control=advertisingId source=shared_local result=unavailable",
                    failure
            );
        }

        String tclId = ImaSharedAdvertisingIdentity.readOrCreateTclId(context);
        PropertyLog.i(TAG, "control=advertisingId source=tcl_persisted");
        return new AdvertisingResolution(tclId, lastKnownLat, true);
    }

    @Keep
    public static void beginAudit(GoogleImaAdListener listener, String adTagUrl) {
        AuditState state = new AuditState(listener);
        synchronized (AUDIT_LOCK) {
            activeAudit = state;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("ad_tag_url", safe(adTagUrl));
        emit(state, "audit_begin", fields, null);
    }

    @Keep
    public static void endAudit(GoogleImaAdListener listener, String reason) {
        AuditState state;
        synchronized (AUDIT_LOCK) {
            state = activeAudit;
            if (state == null || state.listener != listener) return;
            activeAudit = null;
        }
        emit(state, "audit_end", fields("reason", safe(reason)), null);
    }

    @Keep
    public static void auditNativeResponse(Object requestObject, Object responseObject) {
        if (!(requestObject instanceof zzby) || !(responseObject instanceof zzbz)) return;
        AuditState state;
        int responseIndex;
        synchronized (AUDIT_LOCK) {
            state = activeAudit;
            if (state == null) return;
            responseIndex = ++state.nativeResponseSequence;
        }

        zzby request = (zzby) requestObject;
        zzbz response = (zzbz) responseObject;
        String content = safe(response.content());
        boolean vast = isVastXml(content);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("response_index", String.valueOf(responseIndex));
        fields.put("request_id", safe(request.id()));
        fields.put("response_id", safe(response.id()));
        fields.put("request_url", safe(request.url()));
        fields.put("request_type", String.valueOf(request.requestType()));
        fields.put("content_type", safe(response.contentType()));
        fields.put("error_code", String.valueOf(response.errorCode()));
        fields.put("content_length", String.valueOf(content.length()));
        fields.put("content_sha256", sha256(content));
        fields.put("is_vast", String.valueOf(vast));
        fields.put("capture_source", "native_xhr");
        emit(state, vast ? "vast_response" : "native_xhr_response", fields, vast ? content : null);
    }

    private static void auditWebVastResponse(
            String captureSource,
            String method,
            String url,
            int status,
            String contentType,
            String content) {
        String safeContent = safe(content);
        if (!isVastXml(safeContent)) return;
        AuditState state;
        int responseIndex;
        synchronized (AUDIT_LOCK) {
            state = activeAudit;
            if (state == null) return;
            responseIndex = ++state.nativeResponseSequence;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("response_index", String.valueOf(responseIndex));
        fields.put("request_id", "");
        fields.put("response_id", "");
        fields.put("request_url", safe(url));
        fields.put("request_type", safe(method));
        fields.put("content_type", safe(contentType));
        fields.put("http_status", String.valueOf(status));
        fields.put("error_code", status >= 200 && status < 400 ? "0" : String.valueOf(status));
        fields.put("content_length", String.valueOf(safeContent.length()));
        fields.put("content_sha256", sha256(safeContent));
        fields.put("is_vast", "true");
        fields.put("capture_source", safe(captureSource));
        emit(state, "vast_response", fields, safeContent);
    }

    @Keep
    public static void auditOmidBridge(String methodName, Object[] arguments) {
        String method = safe(methodName);
        Object[] args = arguments == null ? new Object[0] : arguments;
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("method", method);
        fields.put("argument_count", String.valueOf(args.length));
        fields.put("argument_types", argumentTypes(args));
        String sessionId = findSessionId(method, args);
        if (!sessionId.isEmpty()) fields.put("session_id", sessionId);
        if ("setState".equals(method) && args.length > 0) {
            fields.put("state", String.valueOf(args[0]));
        }
        if ("setDeviceVolume".equals(method) && args.length > 0) {
            fields.put("device_volume", String.valueOf(args[0]));
        }
        if ("setNativeViewHierarchy".equals(method) && args.length > 0) {
            String hierarchy = String.valueOf(args[0]);
            fields.put("hierarchy_length", String.valueOf(hierarchy.length()));
            fields.put("hierarchy_sha256", sha256(hierarchy));
            fields.put("not_visible_reason_present",
                    String.valueOf(hierarchy.contains("notVisibleReason")));
            fields.put("window_focus_false_present",
                    String.valueOf(hierarchy.contains("\"hasWindowFocus\":false")));
        }
        emitCurrent("omid_bridge", fields, null);
    }

    @Keep
    public static void setAuditedWebViewClient(WebView webView, WebViewClient client) {
        if (webView == null) return;
        installWebViewNetworkAudit(webView);
        if (client instanceof AuditingWebViewClient || currentAudit() == null) {
            webView.setWebViewClient(client);
            return;
        }
        webView.setWebViewClient(new AuditingWebViewClient(client));
    }

    private static void installWebViewNetworkAudit(WebView webView) {
        synchronized (AUDITED_WEB_VIEWS) {
            if (AUDITED_WEB_VIEWS.containsKey(webView)) return;
            try {
                webView.addJavascriptInterface(new AuditJavascriptBridge(), AUDIT_JS_BRIDGE);
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    WebViewCompat.addDocumentStartJavaScript(
                            webView,
                            AUDIT_NETWORK_SCRIPT,
                            Collections.singleton("*"));
                    AUDITED_WEB_VIEWS.put(webView, Boolean.TRUE);
                    PropertyLog.i(TAG, "audit=vast webview_document_start=true");
                } else {
                    AUDITED_WEB_VIEWS.put(webView, Boolean.FALSE);
                    PropertyLog.w(TAG, "audit=vast webview_document_start=false");
                }
            } catch (Throwable error) {
                AUDITED_WEB_VIEWS.put(webView, Boolean.FALSE);
                PropertyLog.w(TAG, "audit=vast webview_install_failed", error);
            }
        }
    }

    private static void injectFallbackWebViewNetworkAudit(WebView webView, String phase) {
        synchronized (AUDITED_WEB_VIEWS) {
            if (!Boolean.FALSE.equals(AUDITED_WEB_VIEWS.get(webView))) return;
        }
        try {
            webView.evaluateJavascript(AUDIT_NETWORK_SCRIPT, null);
            PropertyLog.i(TAG, "audit=vast webview_fallback_injected phase=" + phase);
        } catch (Throwable error) {
            PropertyLog.w(TAG, "audit=vast webview_fallback_failed phase=" + phase, error);
        }
    }

    @Keep
    public static boolean bindLocalMsOwner(Object owner, Context context) {
        Context application = context.getApplicationContext();
        synchronized (MS_OWNERS) {
            MS_OWNERS.put(owner, application == null ? context : application);
        }
        PropertyLog.i(TAG, "control=ms source=local gass=false dynamicVm=false");
        return true;
    }

    @Keep
    public static String localMsFor(Object owner, Integer timeoutMillis) {
        final Context context;
        synchronized (MS_OWNERS) {
            context = MS_OWNERS.get(owner);
        }
        if (context == null) {
            PropertyLog.e(TAG, "control=ms result=owner_missing");
            return "3";
        }
        Callable<String> operation = new Callable<String>() {
            @Override
            public String call() throws Exception {
                return generateLocalMs(context);
            }
        };
        if (timeoutMillis == null || timeoutMillis <= 0) {
            return runLocalMs(operation);
        }
        Future<String> result = MS_EXECUTOR.submit(operation);
        try {
            return result.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            result.cancel(true);
            PropertyLog.w(TAG, "control=ms result=timeout timeoutMs=" + timeoutMillis);
            return "17";
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            result.cancel(true);
            PropertyLog.w(TAG, "control=ms result=interrupted");
            return "3";
        } catch (ExecutionException error) {
            PropertyLog.e(TAG, "control=ms result=failed", error.getCause());
            return "3";
        }
    }

    @Keep
    public static boolean normalizeGksFlag(boolean requested) {
        if (requested) {
            PropertyLog.i(TAG, "control=gks requested=true effective=false network=local");
        }
        return false;
    }

    @Keep
    public static String emptyAdsIdentityToken(Object requesterType, String requestId) {
        PropertyLog.i(TAG, "control=signalSdk blocked=true requestId=" + safe(requestId));
        return "";
    }

    @Keep
    public static List<Object> emptySecureSignals(String phase) {
        PropertyLog.i(TAG, "control=secureSignals blocked=true phase=" + safe(phase));
        return Collections.emptyList();
    }

    @Keep
    public static Task<List<Object>> completedEmptySecureSignals(String phase) {
        return Tasks.forResult(emptySecureSignals(phase));
    }

    @Keep
    public static void recordGassBlocked(String owner) {
        PropertyLog.i(TAG, "control=gassClearcut blocked=true owner=" + safe(owner));
    }

    @Keep
    public static void recordDynamicVmBlocked(String owner) {
        PropertyLog.i(TAG, "control=dynamicVm blocked=true owner=" + safe(owner));
    }

    private static void auditWebResource(
            String phase,
            String url,
            String method,
            Boolean mainFrame,
            Integer errorCode,
            String errorDescription) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("phase", safe(phase));
        fields.put("url", safe(url));
        if (method != null) fields.put("http_method", method);
        if (mainFrame != null) fields.put("main_frame", String.valueOf(mainFrame));
        if (errorCode != null) fields.put("error_code", String.valueOf(errorCode));
        if (errorDescription != null) fields.put("error", errorDescription);
        emitCurrent("web_resource", fields, null);
    }

    private static AuditState currentAudit() {
        synchronized (AUDIT_LOCK) {
            return activeAudit;
        }
    }

    private static void emitCurrent(
            String eventType,
            Map<String, String> fields,
            String payload) {
        AuditState state = currentAudit();
        if (state != null) emit(state, eventType, fields, payload);
    }

    private static void emit(
            AuditState state,
            String eventType,
            Map<String, String> fields,
            String payload) {
        try {
            state.listener.onImaAuditEvent(new GoogleImaAuditEvent(
                    eventType,
                    SystemClock.elapsedRealtime(),
                    fields,
                    payload));
        } catch (Throwable error) {
            PropertyLog.w(TAG, "audit callback failed event=" + eventType, error);
        }
    }

    private static Map<String, String> fields(String name, String value) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(name, value);
        return fields;
    }

    private static boolean isVastXml(String content) {
        if (content.isEmpty()) return false;
        String normalized = content.toLowerCase(Locale.US);
        return normalized.contains("<vast") || normalized.contains(":vast");
    }

    private static String argumentTypes(Object[] arguments) {
        StringBuilder result = new StringBuilder();
        for (Object argument : arguments) {
            if (result.length() > 0) result.append(',');
            result.append(argument == null ? "null" : argument.getClass().getSimpleName());
        }
        return result.toString();
    }

    private static String findSessionId(String method, Object[] arguments) {
        if (arguments.length == 0) return "";
        if (("startSession".equals(method) || "finishSession".equals(method))
                && arguments[0] instanceof String) {
            return safe((String) arguments[0]);
        }
        Object last = arguments[arguments.length - 1];
        return last instanceof String ? safe((String) last) : "";
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format(Locale.US, "%02x", item));
            return result.toString();
        } catch (Exception error) {
            return "";
        }
    }

    private static String runLocalMs(Callable<String> operation) {
        try {
            return operation.call();
        } catch (Exception error) {
            PropertyLog.e(TAG, "control=ms result=failed", error);
            return "3";
        }
    }

    private static String generateLocalMs(final Context context) throws Exception {
        final int sdk = hostInt("getAndroidSdkInt");
        ImaLocalFlagsProvider.IntSource sdkSource = new ImaLocalFlagsProvider.IntSource() {
            @Override
            public int get() {
                return sdk;
            }

            @Override
            public String provenance() {
                return "host-shared-identity-policy";
            }
        };
        String architecture = hostString("getSystemProperty", "os.arch");
        ImaLocalMsOwner.Configuration configuration =
                ImaLocalMsOwner.Configuration.pinnedGoogleUrlOnly(
                        architecture == null ? "" : architecture,
                        "host-shared-system-property-policy"
                );
        try (ImaLocalMsOwner owner = ImaLocalMsOwner.create(
                context,
                sdkSource,
                true,
                configuration,
                new com.smart.android.ad_app.google.ima.local.signal.CompatMode1Runtime.ProcessState(),
                PrimitiveObserver.NONE
        )) {
            String ms = owner.generate().ms;
            if (ms == null || ms.isEmpty()) {
                throw new IllegalStateException("local ms generator returned an empty value");
            }
            PropertyLog.i(TAG, "control=ms result=success length=" + ms.length());
            return ms;
        }
    }

    private static int hostInt(String name) throws Exception {
        Object value = hostMethod(name).invoke(null);
        if (!(value instanceof Number)) {
            throw new IllegalStateException("Host bridge returned a non-number for " + name);
        }
        return ((Number) value).intValue();
    }

    private static String hostString(String name, String argument) throws Exception {
        Object value = hostMethod(name, String.class).invoke(null, argument);
        return value == null ? null : String.valueOf(value);
    }

    private static Method hostMethod(String name, Class<?>... types) throws Exception {
        Class<?> bridge = Class.forName(
                HOST_BRIDGE,
                true,
                GoogleImaRuntimeBridge.class.getClassLoader()
        );
        return bridge.getMethod(name, types);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class AuditState {
        final GoogleImaAdListener listener;
        int nativeResponseSequence;

        AuditState(GoogleImaAdListener listener) {
            this.listener = listener == null ? new GoogleImaAdListener() {} : listener;
        }
    }

    private static final class AdvertisingResolution {
        final String id;
        final boolean limitedAdTracking;
        final boolean localFallback;

        AdvertisingResolution(
                String actualId,
                boolean actualLimitedAdTracking,
                boolean actualLocalFallback
        ) {
            id = actualId;
            limitedAdTracking = actualLimitedAdTracking;
            localFallback = actualLocalFallback;
        }
    }

    private static final class AuditJavascriptBridge {
        @JavascriptInterface
        public void onVastResponse(
                String captureSource,
                String method,
                String url,
                int status,
                String contentType,
                String content) {
            auditWebVastResponse(captureSource, method, url, status, contentType, content);
        }
    }

    private static final class AuditingWebViewClient extends WebViewClient {
        private final WebViewClient delegate;

        AuditingWebViewClient(WebViewClient delegate) {
            this.delegate = delegate == null ? new WebViewClient() : delegate;
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return delegate.shouldOverrideUrlLoading(view, url);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return delegate.shouldOverrideUrlLoading(view, request);
        }

        @SuppressWarnings("deprecation")
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            auditWebResource("request", url, "GET", null, null, null);
            return delegate.shouldInterceptRequest(view, url);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(
                WebView view,
                WebResourceRequest request) {
            auditWebResource(
                    "request",
                    request.getUrl().toString(),
                    request.getMethod(),
                    request.isForMainFrame(),
                    null,
                    null);
            return delegate.shouldInterceptRequest(view, request);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            auditWebResource("page_started", url, null, true, null, null);
            injectFallbackWebViewNetworkAudit(view, "page_started");
            delegate.onPageStarted(view, url, favicon);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            auditWebResource("page_finished", url, null, true, null, null);
            injectFallbackWebViewNetworkAudit(view, "page_finished");
            delegate.onPageFinished(view, url);
        }

        @Override
        public void onLoadResource(WebView view, String url) {
            auditWebResource("load", url, null, null, null, null);
            delegate.onLoadResource(view, url);
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onReceivedError(
                WebView view,
                int errorCode,
                String description,
                String failingUrl) {
            auditWebResource("error", failingUrl, null, null, errorCode, description);
            delegate.onReceivedError(view, errorCode, description, failingUrl);
        }

        @Override
        public void onReceivedError(
                WebView view,
                WebResourceRequest request,
                WebResourceError error) {
            auditWebResource(
                    "error",
                    request.getUrl().toString(),
                    request.getMethod(),
                    request.isForMainFrame(),
                    error.getErrorCode(),
                    String.valueOf(error.getDescription()));
            delegate.onReceivedError(view, request, error);
        }

        @Override
        public void onReceivedHttpError(
                WebView view,
                WebResourceRequest request,
                WebResourceResponse errorResponse) {
            auditWebResource(
                    "http_error",
                    request.getUrl().toString(),
                    request.getMethod(),
                    request.isForMainFrame(),
                    errorResponse.getStatusCode(),
                    errorResponse.getReasonPhrase());
            delegate.onReceivedHttpError(view, request, errorResponse);
        }
    }
}
