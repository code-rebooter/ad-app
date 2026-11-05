package com.smart.android.googleima.internal;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Keep;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.google.ads.interactivemedia.v3.api.AdsRequest;
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
import com.smart.android.googleima.GoogleImaAdUrlResolver;
import com.smart.android.googleima.GoogleImaAuditEvent;
import com.smart.android.googleima.logging.PropertyLog;

import java.lang.ref.ReferenceQueue;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import java.util.regex.Pattern;

/** Runtime targets used by the exact-version IMA bytecode patch. */
@Keep
public final class GoogleImaRuntimeBridge {
    private static final String TAG = "GoogleImaControl";
    private static final String HOST_BRIDGE =
            "com.smart.android.ad_app.Hq008XhsxAarRuntimeBridge";
    private static final Map<Object, Context> MS_OWNERS = new WeakHashMap<>();
    private static final ReferenceQueue<Object> AUDIT_OWNER_QUEUE = new ReferenceQueue<>();
    private static final Map<IdentityWeakReference, AuditState> NATIVE_REQUEST_AUDITS =
            new HashMap<>();
    private static final Map<IdentityWeakReference, AuditState> ASYNC_OWNER_AUDITS =
            new HashMap<>();
    private static final Map<WebView, WebViewAuditBinding> AUDITED_WEB_VIEWS =
            new WeakHashMap<>();
    private static final ThreadLocal<Boolean> URL_IDENTITY_FALLBACK = new ThreadLocal<>();
    private static final Object AUDIT_LOCK = new Object();
    private static final String AUDIT_JS_BRIDGE = "__smartImaAudit";
    private static final Pattern VPMUTE_PARAMETER =
            Pattern.compile("(?i)([?&])vpmute=[^&#]*");
    private static final String OMID_MEDIA_VOLUME_SCRIPT = """
            (function(reportedVolume) {
              var root = window;
              var key = '__smartImaOmidMediaVolumeV1';
              if (root[key]) {
                root[key].volume = reportedVolume;
                root[key].install();
                return;
              }
              var policy = { volume: reportedVolume };
              root[key] = policy;
              function normalize(message) {
                if (!message || typeof message !== 'object') return message;
                var method = message.omid_message_method;
                var index = method === 'SessionService.start' ? 1 :
                  method === 'SessionService.volumeChange' ? 0 : -1;
                if (index < 0) return message;
                try {
                  var encoded = typeof message.omid_message_args === 'string';
                  var args = encoded ? JSON.parse(message.omid_message_args) : message.omid_message_args;
                  if (!Array.isArray(args) || index >= args.length ||
                      typeof args[index] !== 'number' || !isFinite(args[index]) ||
                      args[index] < 0 || args[index] > 1) return message;
                  var original = args[index];
                  var values = args.slice();
                  values[index] = policy.volume;
                  var copy = Object.assign({}, message);
                  copy.omid_message_args = encoded ? JSON.stringify(values) : values;
                  try {
                    root.__smartImaAudit.onOmidMediaVolume(method, original, policy.volume);
                  } catch (_) {}
                  return copy;
                } catch (_) { return message; }
              }
              function installMetadata(namespace) {
                if (!namespace || typeof namespace.getVideoMetadata !== 'function') return false;
                if (!namespace.getVideoMetadata.__smartImaVolumeWrapped) {
                  var getMetadata = namespace.getVideoMetadata;
                  var wrappedMetadata = function() {
                    var metadata = getMetadata.apply(this, arguments);
                    if (!metadata || typeof metadata.volume !== 'number' ||
                        !isFinite(metadata.volume) || metadata.volume < 0 || metadata.volume > 1) return metadata;
                    var copy = Object.assign({}, metadata);
                    copy.volume = policy.volume;
                    try {
                      root.__smartImaAudit.onActiveViewMediaVolume(metadata.volume, policy.volume);
                    } catch (_) {}
                    return copy;
                  };
                  wrappedMetadata.__smartImaVolumeWrapped = true;
                  namespace.getVideoMetadata = wrappedMetadata;
                }
                return true;
              }
              policy.install = function() {
                var commonReady = installMetadata(root.ima && root.ima.common);
                var bridgeReady = installMetadata(root.ima && root.ima.bridge);
                var metadataReady = commonReady && bridgeReady;
                var service = root.omid && root.omid.v1_SessionServiceCommunication;
                if (!service || typeof service.handleExportedMessage !== 'function') return false;
                if (service.__smartImaVolumeWrapped) return !!metadataReady;
                var original = service.handleExportedMessage;
                service.handleExportedMessage = function(message, sender) {
                  return original.call(this, normalize(message), sender);
                };
                service.__smartImaVolumeWrapped = true;
                return !!metadataReady;
              };
              root.addEventListener('message', function(event) {
                var normalized = normalize(event.data);
                if (normalized !== event.data) {
                  try { event.data.omid_message_args = normalized.omid_message_args; } catch (_) {}
                }
              }, true);
              if (!policy.install()) {
                var attempts = 0;
                var timer = root.setInterval(function() {
                  if (policy.install() || ++attempts >= 200) root.clearInterval(timer);
                }, 25);
              }
            })(%VOLUME%);
            """;
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
    /**
     * Installs the UI-click bridge before the IMA Core bundle executes. It reroutes the
     * externally-invoked Kf path into the SDK's own UI handlers: K6.Zn for a body click (which
     * completes click tracking) or K6.learnMoreClicked when Learn More UI is enabled (which is
     * forwarded to Y6.M for CLICKED + navigation + pause). This avoids requiring disableUi=true
     * and avoids the external_click diagnostic produced by the gated Kf path.
     *
     * The script must be prepended to native_bridge__*.js: Android WebView on the target TV does
     * not support DOCUMENT_START_SCRIPT, so late fallback injection is after Core has captured
     * Object.create and installed its prototypes. The script is inert outside imasdk.googleapis.com.
     */
    private static final String IMA_CLICK_BYPASS_SCRIPT = """
            (function() {
              'use strict';
              try {
                var host = (window.location.hostname || '').toLowerCase();
                if (host !== 'imasdk.googleapis.com') return;
              } catch (_) { return; }
              var root = window;
              var key = '__smartImaClickRouteV2';
              if (root[key]) return;
              root[key] = true;
              var originalCreate = Object.create;
              if (typeof originalCreate !== 'function') return;

              function emit(stage, target) {
                try {
                  root.__smartImaAudit &&
                    root.__smartImaAudit.onClickBypass(String(stage), String(target));
                } catch (_) {}
              }

              function sourceOf(fn) {
                try { return Function.prototype.toString.call(fn); } catch (_) { return ''; }
              }

              function inheritedValue(proto, name) {
                var cursor = proto;
                while (cursor) {
                  var descriptor = Object.getOwnPropertyDescriptor(cursor, name);
                  if (descriptor) {
                    if (descriptor.get) {
                      try { return descriptor.get.call(proto); } catch (_) { return undefined; }
                    }
                    return descriptor.value;
                  }
                  cursor = Object.getPrototypeOf(cursor);
                }
                return undefined;
              }

              // K6.notify is stable across release/debug minification. A body click completes
              // click tracking through K6.report('click'); learnMoreClicked is forwarded to Y6.M
              // for CLICKED + navigation + pause. Do not call the minified Zn/Ho handler directly.
              // Y6.M calls this.pause() after a UI click. A synthetic click must not
              // change playback state, otherwise IMA reports PAUSED while the player keeps
              // advancing and never emits RESUMED. Shadow pause only for the synchronous
              // notify/dispatch call, then restore the original method.
              function notifyWithoutPause(renderer, notify) {
                var hadOwn = Object.prototype.hasOwnProperty.call(renderer, 'pause');
                var previous = renderer.pause;
                var shadowed = false;
                try {
                  renderer.pause = function() {};
                  shadowed = true;
                } catch (_) {}
                try {
                  return notify();
                } finally {
                  if (shadowed) {
                    try {
                      if (hadOwn) renderer.pause = previous;
                      else delete renderer.pause;
                    } catch (_) {}
                  }
                }
              }

              function routeThroughUi(renderer) {
                var ui = renderer && renderer.u;
                if (!ui || typeof ui.notify !== 'function') return false;
                if (ui.l !== true) return false;
                try {
                  if (renderer.U && renderer.U.h) {
                    notifyWithoutPause(renderer, function() {
                      ui.notify({ type: 'learnMoreClicked' });
                    });
                    return true;
                  }
                  notifyWithoutPause(renderer, function() {
                    ui.notify({ type: 'click', data: { Ys: true } });
                  });
                  return true;
                } catch (_) {
                  return false;
                }
              }

              function wrapExternalClick(original) {
                return function() {
                  if (routeThroughUi(this)) {
                    emit('ui_route', 'Kf');
                    return;
                  }
                  emit('ui_route_skipped', 'Kf');
                  return original.apply(this, arguments);
                };
              }

              function installTrap(object, name, inherited) {
                Object.defineProperty(object, name, {
                  configurable: true,
                  enumerable: false,
                  get: function() { return inherited; },
                  set: function(candidate) {
                    var replacement = candidate;
                    if (typeof candidate === 'function' &&
                        sourceOf(candidate).indexOf('external_click') !== -1) {
                      replacement = wrapExternalClick(candidate);
                      emit('installed', name);
                    }
                    Object.defineProperty(object, name, {
                      configurable: true,
                      writable: true,
                      enumerable: false,
                      value: replacement
                    });
                  }
                });
              }

              Object.create = function(proto, properties) {
                var object = originalCreate(proto, properties);
                try {
                  if (!object || !proto) return object;
                  var constructor = null;
                  try { constructor = proto.constructor; } catch (_) { return object; }
                  if (typeof constructor !== 'function' || constructor.prototype !== proto) {
                    return object;
                  }
                  var names = ['Kf', 'wf', 'pg'];
                  for (var i = 0; i < names.length; i++) {
                    if (properties &&
                        Object.prototype.hasOwnProperty.call(properties, names[i])) continue;
                    installTrap(object, names[i], inheritedValue(proto, names[i]));
                  }
                } catch (_) {}
                return object;
              };
            })();
            """;
    private static AuditState activeAudit;
    private static final Map<String, String> IMA_CORE_SCRIPT_CACHE = new HashMap<>();
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

    private static boolean reportedSoundEnabled(AuditState state) {
        return state == null || state.reportedSoundEnabled;
    }

    @Keep
    public static boolean reportedAdWillPlayMuted() {
        return !reportedSoundEnabled(currentAudit());
    }

    // Keep these entry-point names/signatures for the pinned bytecode patches.
    @Keep
    public static String forceUnmutedAdTagUrl(String adTagUrl) {
        String url = safe(adTagUrl);
        if (url.isEmpty()) return url;
        AuditState state = currentAudit();
        String normalized = normalizeAdTagUrl(url, state);
        emitSoundSignal(state, "request_vpmute", extractVpmute(url),
                reportedSoundEnabled(state) ? "0" : "1", Collections.emptyList());
        return normalized;
    }

    private static String normalizeAdTagUrl(String url, AuditState state) {
        if (url.isEmpty()) return url;
        String vpmute = reportedSoundEnabled(state) ? "0" : "1";
        int fragmentAt = url.indexOf('#');
        String base = fragmentAt < 0 ? url : url.substring(0, fragmentAt);
        String fragment = fragmentAt < 0 ? "" : url.substring(fragmentAt);
        String normalizedBase;
        if (VPMUTE_PARAMETER.matcher(base).find()) {
            normalizedBase = VPMUTE_PARAMETER.matcher(base).replaceAll("$1vpmute=" + vpmute);
        } else {
            String separator = base.endsWith("?") || base.endsWith("&")
                    ? "" : (base.contains("?") ? "&" : "?");
            normalizedBase = base + separator + "vpmute=" + vpmute;
        }
        return normalizedBase + fragment;
    }

    @Keep
    public static void forceUnmutedAdsRequest(AdsRequest request) {
        if (request == null) return;
        AuditState state = currentAudit();
        boolean soundEnabled = reportedSoundEnabled(state);
        request.setAdWillPlayMuted(!soundEnabled);
        emitSoundSignal(state, "request_video_play_muted", "UNKNOWN",
                soundEnabled ? "UNMUTED" : "MUTED", Collections.emptyList());
    }

    @Keep
    public static void bindAsyncAuditOwner(Object owner) {
        if (owner == null) return;
        synchronized (AUDIT_LOCK) {
            if (activeAudit == null) {
                PropertyLog.w(TAG, "audit=orphan event=async_owner_bind");
                return;
            }
            putAuditOwner(ASYNC_OWNER_AUDITS, owner, activeAudit);
        }
    }

    @Keep
    public static int forceUnmutedVolumePercentage(
            Object owner,
            int physicalVolumePercentage) {
        AuditState state = auditForAsyncOwner(owner);
        int reportedVolume = reportedSoundEnabled(state) ? 100 : 0;
        emitSoundSignal(
                state,
                "ima_core_volume_percentage",
                String.valueOf(physicalVolumePercentage),
                String.valueOf(reportedVolume),
                Collections.emptyList());
        return reportedVolume;
    }

    @Keep
    public static boolean hasObservedImaCoreVolume() {
        synchronized (AUDIT_LOCK) {
            return activeAudit != null && activeAudit.imaCoreVolumeObserved;
        }
    }

    @Keep
    public static float getObservedImaCoreReportedVolume() {
        synchronized (AUDIT_LOCK) {
            return activeAudit == null ? -1.0f : activeAudit.lastImaCoreReportedVolume;
        }
    }

    @Keep
    public static double forceUnmutedNativeVolume(Object owner) {
        AuditState state = auditForAsyncOwner(owner);
        double reportedVolume = reportedSoundEnabled(state) ? 1.0d : 0.0d;
        emitSoundSignal(
                state,
                "activeview_native_volume",
                "physical_read_blocked",
                String.valueOf(reportedVolume),
                Collections.emptyList());
        return reportedVolume;
    }

    @Keep
    public static Object[] forceUnmutedOmidArguments(
            WebView webView,
            String methodName,
            Object[] arguments) {
        Object[] actual = arguments == null ? new Object[0] : arguments;
        if (!"setDeviceVolume".equals(methodName) || actual.length == 0) return actual;
        Object[] normalized = actual.clone();
        AuditState state = auditForWebView(webView);
        float reportedVolume = reportedSoundEnabled(state) ? 1.0f : 0.0f;
        installOmidMediaVolumePolicy(webView, state);
        emitSoundSignal(
                state,
                "omid_device_volume",
                String.valueOf(actual[0]),
                String.valueOf(reportedVolume),
                currentOmidSessionIds(state));
        normalized[0] = Float.valueOf(reportedVolume);
        return normalized;
    }

    private static void installOmidMediaVolumePolicy(WebView webView, AuditState state) {
        if (webView == null || state == null || state.closed) return;
        String script = measurementVolumeScript(state);
        Runnable install = () -> {
            if (state.closed) return;
            try {
                webView.evaluateJavascript(script, null);
            } catch (RuntimeException failure) {
                PropertyLog.w(TAG, "control=omid_media_volume install_failed", failure);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) install.run();
        else webView.post(install);
    }

    private static String measurementVolumeScript(AuditState state) {
        return OMID_MEDIA_VOLUME_SCRIPT.replace("%VOLUME%",
                state.reportedSoundEnabled ? "1" : "0");
    }

    private static String clickRouteScript(AuditState state) {
        return state != null && state.clickRouteEnabled ? IMA_CLICK_BYPASS_SCRIPT : "";
    }

    private static String extractVpmute(String url) {
        int fragmentAt = url.indexOf('#');
        String base = fragmentAt < 0 ? url : url.substring(0, fragmentAt);
        java.util.regex.Matcher matcher = VPMUTE_PARAMETER.matcher(base);
        if (!matcher.find()) return "missing";
        String matched = matcher.group();
        int equalsAt = matched.indexOf('=');
        return equalsAt < 0 ? "" : matched.substring(equalsAt + 1);
    }

    private static void emitSoundSignal(String signal, String physicalValue, String reportedValue) {
        emitSoundSignal(signal, physicalValue, reportedValue, Collections.emptyList());
    }

    private static void emitSoundSignal(
            String signal,
            String physicalValue,
            String reportedValue,
            List<String> sessionIds) {
        AuditState state;
        synchronized (AUDIT_LOCK) {
            state = activeAudit;
        }
        emitSoundSignal(state, signal, physicalValue, reportedValue, sessionIds);
    }

    private static void emitSoundSignal(
            AuditState state,
            String signal,
            String physicalValue,
            String reportedValue,
            List<String> sessionIds) {
        if (state == null) {
            PropertyLog.w(TAG, "audit=orphan event=sound_signal signal=" + safe(signal));
            return;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("signal", signal);
        fields.put("physical_value", safe(physicalValue));
        fields.put("reported_value", safe(reportedValue));
        if (!sessionIds.isEmpty()) fields.put("session_ids", joinValues(sessionIds));
        synchronized (AUDIT_LOCK) {
            if ("ima_core_volume_percentage".equals(signal)) {
                state.imaCoreVolumeObserved = true;
                state.lastImaCoreReportedVolume = state.reportedSoundEnabled ? 1.0f : 0.0f;
            }
        }
        emit(state, "sound_signal", fields, null);
    }

    @Keep
    public static AdvertisingIdClient.Info getAdvertisingIdInfoForUrl(Context context) {
        AuditState auditOwner = currentAudit();
        AdvertisingResolution resolution = resolveAdvertisingId(context);
        URL_IDENTITY_FALLBACK.set(resolution.localFallback);
        emitAdvertisingIdentityResolution(auditOwner, "url", resolution);
        return new AdvertisingIdClient.Info(resolution.id, resolution.limitedAdTracking);
    }

    @Keep
    public static AdvertisingIdClient.Info getAdvertisingIdInfoForV2(Context context) {
        AuditState auditOwner = currentAudit();
        AdvertisingResolution resolution = resolveAdvertisingId(context);
        emitAdvertisingIdentityResolution(auditOwner, "v2", resolution);
        return new AdvertisingIdClient.Info(resolution.id, resolution.limitedAdTracking);
    }

    @Keep
    public static boolean shouldCollectAppSet(boolean configured) {
        boolean localFallback = Boolean.TRUE.equals(URL_IDENTITY_FALLBACK.get());
        URL_IDENTITY_FALLBACK.remove();
        return configured && !localFallback;
    }

    @Keep
    public static boolean forceIdentifierInfoCollection(boolean originalIdless) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("original_idless", Boolean.toString(originalIdless));
        fields.put("effective_idless", "false");
        emitCurrent("identity_control", fields, null);
        return false;
    }

    private static void emitAdvertisingIdentityResolution(
            AuditState state,
            String usage,
            AdvertisingResolution resolution) {
        if (state == null) {
            PropertyLog.w(TAG, "audit=orphan event=identity_resolution usage=" + safe(usage));
            return;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("identity_usage", safe(usage));
        fields.put("rdid_source", resolution.localFallback ? "fallback" : "gms");
        fields.put(
                "rdid_fallback_source",
                resolution.localFallback ? resolution.resolutionSource : ""
        );
        fields.put("limited_ad_tracking", Boolean.toString(resolution.limitedAdTracking));
        emit(state, "identity_control", fields, null);
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
                            false,
                            "gms"
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
                return new AdvertisingResolution(
                        local.id,
                        lastKnownLat,
                        true,
                        "shared_local"
                );
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
        return new AdvertisingResolution(tclId, lastKnownLat, true, "tcl_persisted");
    }

    @Keep
    public static void beginAudit(GoogleImaAdListener listener, String adTagUrl) {
        beginAudit(listener, adTagUrl, null);
    }

    public static void beginAudit(
            GoogleImaAdListener listener, String adTagUrl, GoogleImaAdUrlResolver resolver) {
        beginAudit(listener, adTagUrl, resolver, true);
    }

    public static void beginAudit(
            GoogleImaAdListener listener, String adTagUrl, GoogleImaAdUrlResolver resolver,
            boolean reportedSoundEnabled) {
        beginAudit(listener, adTagUrl, resolver, reportedSoundEnabled, false);
    }

    public static void beginAudit(
            GoogleImaAdListener listener, String adTagUrl, GoogleImaAdUrlResolver resolver,
            boolean reportedSoundEnabled, boolean clickRouteEnabled) {
        AuditState state = new AuditState(
                listener, resolver, reportedSoundEnabled, clickRouteEnabled);
        synchronized (AUDIT_LOCK) {
            activeAudit = state;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        String originalUrl = safe(adTagUrl);
        fields.put("ad_tag_url_original", originalUrl);
        fields.put("ad_tag_url", normalizeAdTagUrl(originalUrl, state));
        fields.put("vpmute_original", extractVpmute(originalUrl));
        fields.put("vpmute_normalized", reportedSoundEnabled ? "0" : "1");
        fields.put("reported_sound_enabled", String.valueOf(reportedSoundEnabled));
        fields.put("reported_sound_mode", reportedSoundEnabled ? "1" : "0");
        emit(state, "audit_begin", fields, null);
    }

    @Keep
    public static void markImaStarted(String source) {
        AuditState state = currentAudit();
        if (state == null) return;
        boolean changed;
        synchronized (AUDIT_LOCK) {
            if (activeAudit != state) return;
            changed = !state.imaStartedObserved;
            state.imaStartedObserved = true;
        }
        if (changed) emitMeasurementState(state, "ima_started", safe(source), "");
    }

    @Keep
    public static void auditImaMeasurementTerminal(String terminalEvent) {
        AuditState state = currentAudit();
        if (state == null) return;
        emitMeasurementState(state, "measurement_terminal", "", safe(terminalEvent));
    }

    @Keep
    public static void endAudit(GoogleImaAdListener listener, String reason) {
        AuditState state;
        synchronized (AUDIT_LOCK) {
            state = activeAudit;
            if (state == null || state.listener != listener) return;
            activeAudit = null;
            state.closed = true;
        }
        if (state.adUrlResolver != null) state.adUrlResolver.cancel();
        emit(state, "audit_end", fields("reason", safe(reason)), null);
    }

    @Keep
    public static void bindNativeRequestAudit(Object requestObject) {
        if (requestObject == null) return;
        synchronized (AUDIT_LOCK) {
            if (activeAudit == null) {
                PropertyLog.w(TAG, "audit=orphan event=native_request_bind");
                return;
            }
            putAuditOwner(NATIVE_REQUEST_AUDITS, requestObject, activeAudit);
        }
    }

    @Keep
    public static void bindNativeRequestAudit(Object requestObject, Object networkOwner) {
        synchronized (AUDIT_LOCK) {
            AuditState state = getAuditOwner(ASYNC_OWNER_AUDITS, networkOwner);
            if (state != null) putAuditOwner(NATIVE_REQUEST_AUDITS, requestObject, state);
        }
    }

    /** Invoked inside IMA's native networking worker, after Core has built its URL. */
    @Keep
    public static zzby prepareNativeRequest(zzby request) {
        AuditState state;
        synchronized (AUDIT_LOCK) {
            state = getAuditOwner(NATIVE_REQUEST_AUDITS, request);
        }
        if (!needsAdUrlResolution(state, request.url(), String.valueOf(request.requestType()))) {
            return request;
        }
        try {
            String url = resolveAdUrl(state, request.url());
            zzby resolved = copyNativeRequestUrl(request, url);
            synchronized (AUDIT_LOCK) {
                removeAuditOwner(NATIVE_REQUEST_AUDITS, request);
                putAuditOwner(NATIVE_REQUEST_AUDITS, resolved, state);
            }
            return resolved;
        } catch (Exception error) {
            emitAdUrlFailure(state, error);
            synchronized (AUDIT_LOCK) {
                removeAuditOwner(NATIVE_REQUEST_AUDITS, request);
            }
            return null;
        }
    }

    @Keep
    public static zzbz blockedNativeResponse(zzby request) {
        return zzbz.forError(request.id(), 1);
    }

    static zzby copyNativeRequestUrl(zzby request, String url) {
        // Core's deserialized request can omit fields that AutoValue's factory requires.
        return new zzby() {
            @Override public int connectionTimeoutMs() { return request.connectionTimeoutMs(); }
            @Override public int readTimeoutMs() { return request.readTimeoutMs(); }
            @Override public com.google.ads.interactivemedia.v3.impl.data.zzbx requestType() {
                return request.requestType();
            }
            @Override public String content() { return request.content(); }
            @Override public String id() { return request.id(); }
            @Override public String url() { return url; }
            @Override public String userAgent() { return request.userAgent(); }
        };
    }

    private static boolean needsAdUrlResolution(AuditState state, String url, String method) {
        return state != null && state.adUrlResolver != null && "GET".equals(method)
                && state.adUrlResolver.handles(url);
    }

    private static String resolveAdUrl(AuditState state, String original) throws Exception {
        if (state.closed) throw new IOException("IMA ad request cancelled");
        String resolved = state.adUrlResolver.resolve(original);
        if (state.closed) throw new IOException("IMA ad request cancelled");
        if (resolved == null || resolved.trim().isEmpty()) {
            throw new IOException("GAM patch returned no ad URL");
        }
        synchronized (state.resolvedAdUrls) {
            state.resolvedAdUrls.put(original, resolved);
        }
        return resolved;
    }

    private static void emitAdUrlFailure(AuditState state, Exception error) {
        if (state != null && !state.closed) {
            emit(state, "gam_patch_error", fields("error", safe(error.getMessage())), null);
        }
    }

    private static WebResourceResponse interceptPatchedAdRequest(
            AuditState state, String url, String method, Map<String, String> headers) {
        if (!needsAdUrlResolution(state, url, method)) return null;
        final String resolved;
        try {
            resolved = resolveAdUrl(state, url);
        } catch (Exception error) {
            emitAdUrlFailure(state, error);
            return blockedWebResponse();
        }
        HttpURLConnection connection = null;
        try {
            if (state.closed) return blockedWebResponse();
            connection = (HttpURLConnection) new URL(resolved).openConnection();
            int timeout = (int) Math.max(1L,
                    Math.min(20_000L, state.adUrlResolver.remainingTimeMs()));
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            connection.setRequestMethod("GET");
            for (Map.Entry<String, String> header : headers.entrySet()) {
                if (!"Accept-Encoding".equalsIgnoreCase(header.getKey())) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }
            connection.setRequestProperty("Accept-Encoding", "identity");
            int code = connection.getResponseCode();
            Map<String, String> responseHeaders = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                if (header.getKey() != null) {
                    responseHeaders.put(header.getKey(), String.join(", ", header.getValue()));
                }
            }
            String contentType = safe(connection.getContentType());
            String mime = contentType.split(";", 2)[0];
            if (mime.isEmpty()) mime = "application/xml";
            byte[] body;
            try (InputStream input = code >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (input != null) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                body = output.toByteArray();
            }
            if (state.closed) return blockedWebResponse();
            auditWebVastResponse(state, "webview_patched_http", "GET", resolved, code,
                    contentType, new String(body, StandardCharsets.UTF_8));
            String reason = safe(connection.getResponseMessage());
            return new WebResourceResponse(mime, "UTF-8", code,
                    reason.isEmpty() ? "Ad response" : reason, responseHeaders,
                    new ByteArrayInputStream(body));
        } catch (Exception error) {
            auditWebResource(state, "error", resolved, "GET", false, -1,
                    safe(error.getMessage()));
            return blockedWebResponse();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static WebResourceResponse blockedWebResponse() {
        return new WebResourceResponse("application/xml", "UTF-8", 503,
                "Ad request stopped", Collections.singletonMap("Access-Control-Allow-Origin", "*"),
                new ByteArrayInputStream(new byte[0]));
    }

    /**
     * Prepends the click bridge to IMA's native core bundle before WebView evaluates it.
     * DOCUMENT_START_SCRIPT is unavailable on the target TV WebView, so onPageStarted /
     * onPageFinished injection happens after Core has already captured Object.create.
     */
    private static WebResourceResponse interceptImaCoreScript(
            AuditState state,
            String url,
            Map<String, String> requestHeaders) {
        if (state == null || !state.clickRouteEnabled) return null;
        if (!isImaCoreScript(url)) return null;
        String cached;
        synchronized (IMA_CORE_SCRIPT_CACHE) {
            cached = IMA_CORE_SCRIPT_CACHE.get(url);
        }
        if (cached != null) return imaCoreScriptResponse(cached);

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(3_000);
            connection.setReadTimeout(5_000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("Accept-Encoding", "identity");
            String userAgent = headerValue(requestHeaders, "User-Agent");
            if (!userAgent.isEmpty()) connection.setRequestProperty("User-Agent", userAgent);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) return null;
            String contentEncoding = safe(connection.getContentEncoding());
            if (!contentEncoding.isEmpty() && !"identity".equalsIgnoreCase(contentEncoding)) {
                PropertyLog.w(TAG, "control=click_route core_script_encoding=" + contentEncoding);
                return null;
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[16_384];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            String body = new String(output.toByteArray(), StandardCharsets.UTF_8);
            if (!body.contains("external_click")) {
                PropertyLog.w(TAG, "control=click_route core_script_marker_missing");
                return null;
            }
            String injected = IMA_CLICK_BYPASS_SCRIPT + "\n" + body;
            synchronized (IMA_CORE_SCRIPT_CACHE) {
                IMA_CORE_SCRIPT_CACHE.put(url, injected);
            }
            PropertyLog.i(TAG, "control=click_route core_script_injected length=" + body.length());
            return imaCoreScriptResponse(injected);
        } catch (Throwable error) {
            PropertyLog.w(TAG, "control=click_route core_script_inject_failed", error);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static boolean isImaCoreScript(String url) {
        if (url == null || !url.startsWith("https://imasdk.googleapis.com/")) return false;
        int queryAt = url.indexOf('?');
        String path = queryAt < 0 ? url : url.substring(0, queryAt);
        return (path.contains("/native/core/") || path.contains("/js/core/"))
                && path.endsWith(".js");
    }

    private static WebResourceResponse imaCoreScriptResponse(String body) {
        return new WebResourceResponse(
                "application/javascript",
                "UTF-8",
                200,
                "OK",
                Collections.singletonMap("Cache-Control", "no-store"),
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static String headerValue(Map<String, String> headers, String name) {
        if (headers == null || name == null) return "";
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return safe(entry.getValue());
        }
        return "";
    }

    @Keep
    public static void auditNativeResponse(Object requestObject, Object responseObject) {
        if (!(requestObject instanceof zzby) || !(responseObject instanceof zzbz)) return;
        AuditState state;
        int responseIndex;
        synchronized (AUDIT_LOCK) {
            state = removeAuditOwner(NATIVE_REQUEST_AUDITS, requestObject);
            if (state == null) {
                PropertyLog.w(TAG, "audit=orphan event=native_response");
                return;
            }
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
            AuditState state,
            String captureSource,
            String method,
            String url,
            int status,
            String contentType,
            String content) {
        String safeContent = safe(content);
        if (!isVastXml(safeContent)) return;
        if (state != null) {
            synchronized (state.resolvedAdUrls) {
                url = state.resolvedAdUrls.getOrDefault(url, url);
                String key = url + ":" + sha256(safeContent);
                if ("webview_patched_http".equals(captureSource)) {
                    state.patchedWebVastHashes.add(key);
                } else if (state.patchedWebVastHashes.contains(key)) {
                    return;
                }
            }
        }
        int responseIndex;
        synchronized (AUDIT_LOCK) {
            if (state == null) {
                PropertyLog.w(TAG, "audit=orphan event=web_vast_response");
                return;
            }
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
    public static void auditOmidBridge(
            WebView webView,
            String methodName,
            Object[] arguments) {
        String method = safe(methodName);
        Object[] args = arguments == null ? new Object[0] : arguments;
        String payload = null;
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("method", method);
        fields.put("argument_count", String.valueOf(args.length));
        fields.put("argument_types", argumentTypes(args));
        String sessionId = findSessionId(method, args);
        AuditState state = auditForWebView(webView);
        if (state == null) {
            PropertyLog.w(TAG, "audit=orphan event=omid_bridge method=" + method);
            return;
        }
        if ("startSession".equals(method) && !sessionId.isEmpty()) {
            boolean changed;
            synchronized (AUDIT_LOCK) {
                state.activeOmidSessionIds.add(sessionId);
                changed = !state.omidStartedObserved;
                state.omidStartedObserved = true;
            }
            if (changed) emitMeasurementState(state, "omid_session_started", "", "");
        }
        List<String> activeSessionIds = currentOmidSessionIds(state);
        if (sessionId.isEmpty() && activeSessionIds.size() == 1) {
            sessionId = activeSessionIds.get(0);
        }
        if (!sessionId.isEmpty()) fields.put("session_id", sessionId);
        if (!activeSessionIds.isEmpty()) fields.put("session_ids", joinValues(activeSessionIds));
        if ("setState".equals(method) && args.length > 0) {
            fields.put("state", String.valueOf(args[0]));
        }
        if ("setDeviceVolume".equals(method) && args.length > 0) {
            fields.put("device_volume", String.valueOf(args[0]));
        }
        if ("setNativeViewHierarchy".equals(method) && args.length > 0) {
            String hierarchy = String.valueOf(args[0]);
            payload = hierarchy;
            fields.put("hierarchy_length", String.valueOf(hierarchy.length()));
            fields.put("hierarchy_sha256", sha256(hierarchy));
            fields.put("not_visible_reason_present",
                    String.valueOf(hierarchy.contains("notVisibleReason")));
            fields.put("window_focus_false_present",
                    String.valueOf(hierarchy.contains("\"hasWindowFocus\":false")));
        }
        if (state != null) emit(state, "omid_bridge", fields, payload);
        if (state != null && "finishSession".equals(method) && !sessionId.isEmpty()) {
            synchronized (AUDIT_LOCK) {
                state.activeOmidSessionIds.remove(sessionId);
            }
        }
    }

    @Keep
    public static void setAuditedWebViewClient(WebView webView, WebViewClient client) {
        if (webView == null) return;
        AuditState state = bindWebViewAudit(webView);
        if (state != null) state.webViewUserAgent = webView.getSettings().getUserAgentString();
        installWebViewNetworkAudit(webView, state);
        if (client instanceof AuditingWebViewClient || state == null) {
            webView.setWebViewClient(client);
            return;
        }
        webView.setWebViewClient(new AuditingWebViewClient(client, state));
    }

    private static AuditState bindWebViewAudit(WebView webView) {
        synchronized (AUDITED_WEB_VIEWS) {
            WebViewAuditBinding existing = AUDITED_WEB_VIEWS.get(webView);
            if (existing != null) return existing.state;
            AuditState state = currentAudit();
            if (state != null) {
                AUDITED_WEB_VIEWS.put(webView, new WebViewAuditBinding(state));
            }
            return state;
        }
    }

    private static AuditState auditForWebView(WebView webView) {
        if (webView == null) return null;
        synchronized (AUDITED_WEB_VIEWS) {
            WebViewAuditBinding binding = AUDITED_WEB_VIEWS.get(webView);
            return binding == null ? null : binding.state;
        }
    }

    private static AuditState auditForAsyncOwner(Object owner) {
        if (owner == null) return null;
        synchronized (AUDIT_LOCK) {
            return getAuditOwner(ASYNC_OWNER_AUDITS, owner);
        }
    }

    private static void putAuditOwner(
            Map<IdentityWeakReference, AuditState> owners,
            Object owner,
            AuditState state) {
        drainAuditOwnerQueue();
        owners.put(new IdentityWeakReference(owner, AUDIT_OWNER_QUEUE), state);
    }

    private static AuditState getAuditOwner(
            Map<IdentityWeakReference, AuditState> owners,
            Object owner) {
        drainAuditOwnerQueue();
        return owners.get(new IdentityWeakReference(owner, null));
    }

    private static AuditState removeAuditOwner(
            Map<IdentityWeakReference, AuditState> owners,
            Object owner) {
        drainAuditOwnerQueue();
        return owners.remove(new IdentityWeakReference(owner, null));
    }

    private static void drainAuditOwnerQueue() {
        IdentityWeakReference cleared;
        while ((cleared = (IdentityWeakReference) AUDIT_OWNER_QUEUE.poll()) != null) {
            NATIVE_REQUEST_AUDITS.remove(cleared);
            ASYNC_OWNER_AUDITS.remove(cleared);
        }
    }

    private static void installWebViewNetworkAudit(WebView webView, AuditState state) {
        if (state == null) return;
        synchronized (AUDITED_WEB_VIEWS) {
            WebViewAuditBinding binding = AUDITED_WEB_VIEWS.get(webView);
            if (binding == null || binding.networkAuditInstalled) return;
            try {
                webView.addJavascriptInterface(
                        new AuditJavascriptBridge(state),
                        AUDIT_JS_BRIDGE);
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    WebViewCompat.addDocumentStartJavaScript(
                            webView,
                            AUDIT_NETWORK_SCRIPT + measurementVolumeScript(state) +
                                    clickRouteScript(state),
                            Collections.singleton("*"));
                    binding.documentStartInstalled = true;
                    PropertyLog.i(TAG, "audit=vast webview_document_start=true");
                } else {
                    binding.documentStartInstalled = false;
                    PropertyLog.w(TAG, "audit=vast webview_document_start=false");
                }
                binding.networkAuditInstalled = true;
            } catch (Throwable error) {
                binding.documentStartInstalled = false;
                binding.networkAuditInstalled = true;
                PropertyLog.w(TAG, "audit=vast webview_install_failed", error);
            }
        }
    }

    private static void injectFallbackWebViewNetworkAudit(WebView webView, String phase) {
        synchronized (AUDITED_WEB_VIEWS) {
            WebViewAuditBinding binding = AUDITED_WEB_VIEWS.get(webView);
            if (binding == null || binding.documentStartInstalled) return;
        }
        try {
            AuditState state = auditForWebView(webView);
            if (state == null || state.closed) return;
            webView.evaluateJavascript(
                    AUDIT_NETWORK_SCRIPT + measurementVolumeScript(state) +
                            clickRouteScript(state),
                    null);
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
            AuditState state,
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
        if (state == null) {
            PropertyLog.w(TAG, "audit=orphan event=web_resource phase=" + safe(phase));
            return;
        }
        emit(state, "web_resource", fields, null);
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

    private static void emitMeasurementState(
            AuditState state,
            String phase,
            String trigger,
            String terminalEvent) {
        boolean imaStarted;
        boolean omidStarted;
        synchronized (AUDIT_LOCK) {
            imaStarted = state.imaStartedObserved;
            omidStarted = state.omidStartedObserved;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("phase", safe(phase));
        fields.put("trigger", safe(trigger));
        fields.put("terminal_event", safe(terminalEvent));
        fields.put("ima_started", String.valueOf(imaStarted));
        fields.put("omid_started", String.valueOf(omidStarted));
        fields.put("measurement_complete", String.valueOf(imaStarted && omidStarted));
        emit(state, "ima_measurement_state", fields, null);
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

    private static List<String> currentOmidSessionIds(AuditState state) {
        synchronized (AUDIT_LOCK) {
            if (state == null || state.activeOmidSessionIds.isEmpty()) {
                return Collections.emptyList();
            }
            return new ArrayList<>(state.activeOmidSessionIds);
        }
    }

    private static String joinValues(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(',');
            result.append(value);
        }
        return result.toString();
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
        final GoogleImaAdUrlResolver adUrlResolver;
        final boolean reportedSoundEnabled;
        final Map<String, String> resolvedAdUrls = new HashMap<>();
        final LinkedHashSet<String> patchedWebVastHashes = new LinkedHashSet<>();
        volatile boolean closed;
        volatile String webViewUserAgent = "";
        final LinkedHashSet<String> activeOmidSessionIds = new LinkedHashSet<>();
        int nativeResponseSequence;
        boolean imaCoreVolumeObserved;
        boolean imaStartedObserved;
        boolean omidStartedObserved;
        float lastImaCoreReportedVolume = -1.0f;

        final boolean clickRouteEnabled;

        AuditState(GoogleImaAdListener listener, GoogleImaAdUrlResolver resolver) {
            this(listener, resolver, true, false);
        }

        AuditState(GoogleImaAdListener listener, GoogleImaAdUrlResolver resolver,
                boolean reportedSoundEnabled) {
            this(listener, resolver, reportedSoundEnabled, false);
        }

        AuditState(GoogleImaAdListener listener, GoogleImaAdUrlResolver resolver,
                boolean reportedSoundEnabled, boolean clickRouteEnabled) {
            this.listener = listener == null ? new GoogleImaAdListener() {} : listener;
            this.adUrlResolver = resolver;
            this.reportedSoundEnabled = reportedSoundEnabled;
            this.clickRouteEnabled = clickRouteEnabled;
        }
    }

    private static final class WebViewAuditBinding {
        final AuditState state;
        boolean networkAuditInstalled;
        boolean documentStartInstalled;

        WebViewAuditBinding(AuditState actualState) {
            state = actualState;
        }
    }

    static final class IdentityWeakReference extends WeakReference<Object> {
        private final int identityHash;

        IdentityWeakReference(Object referent, ReferenceQueue<Object> queue) {
            super(referent, queue);
            identityHash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityWeakReference)) return false;
            Object referent = get();
            return referent != null && referent == ((IdentityWeakReference) other).get();
        }
    }

    private static final class AdvertisingResolution {
        final String id;
        final boolean limitedAdTracking;
        final boolean localFallback;
        final String resolutionSource;

        AdvertisingResolution(
                String actualId,
                boolean actualLimitedAdTracking,
                boolean actualLocalFallback,
                String actualResolutionSource
        ) {
            id = actualId;
            limitedAdTracking = actualLimitedAdTracking;
            localFallback = actualLocalFallback;
            resolutionSource = actualResolutionSource;
        }
    }

    private static final class AuditJavascriptBridge {
        private final AuditState state;

        AuditJavascriptBridge(AuditState actualState) {
            state = actualState;
        }

        @JavascriptInterface
        public void onOmidMediaVolume(String method, double originalVolume, double reportedVolume) {
            emitSoundSignal(state, "omid_media_player_volume", "core:" + originalVolume,
                    String.valueOf(reportedVolume), currentOmidSessionIds(state));
        }

        @JavascriptInterface
        public void onActiveViewMediaVolume(double originalVolume, double reportedVolume) {
            emitSoundSignal(state, "activeview_media_volume", "core:" + originalVolume,
                    String.valueOf(reportedVolume), Collections.emptyList());
        }

        @JavascriptInterface
        public void onClickBypass(String stage, String target) {
            PropertyLog.i(TAG, "control=fake_ctr click_bypass stage=" + safe(stage) +
                    " target=" + safe(target));
            Map<String, String> clickFields = fields("stage", safe(stage));
            clickFields.put("target", safe(target));
            emit(state, "click_bypass", clickFields, null);
        }

        @JavascriptInterface
        public void onVastResponse(
                String captureSource,
                String method,
                String url,
                int status,
                String contentType,
                String content) {
            auditWebVastResponse(
                    state,
                    captureSource,
                    method,
                    url,
                    status,
                    contentType,
                    content);
        }
    }

    private static final class AuditingWebViewClient extends WebViewClient {
        private final WebViewClient delegate;
        private final AuditState state;

        AuditingWebViewClient(WebViewClient delegate, AuditState actualState) {
            this.delegate = delegate == null ? new WebViewClient() : delegate;
            state = actualState;
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
            auditWebResource(state, "request", url, "GET", null, null, null);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", state.webViewUserAgent);
            WebResourceResponse clickBridge = interceptImaCoreScript(state, url, headers);
            if (clickBridge != null) return clickBridge;
            WebResourceResponse patched = interceptPatchedAdRequest(state, url, "GET", headers);
            if (patched != null) return patched;
            return delegate.shouldInterceptRequest(view, url);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(
                WebView view,
                WebResourceRequest request) {
            auditWebResource(
                    state,
                    "request",
                    request.getUrl().toString(),
                    request.getMethod(),
                    request.isForMainFrame(),
                    null,
                    null);
            WebResourceResponse clickBridge = interceptImaCoreScript(
                    state, request.getUrl().toString(), request.getRequestHeaders());
            if (clickBridge != null) return clickBridge;
            WebResourceResponse patched = interceptPatchedAdRequest(state,
                    request.getUrl().toString(), request.getMethod(), request.getRequestHeaders());
            if (patched != null) return patched;
            return delegate.shouldInterceptRequest(view, request);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            auditWebResource(state, "page_started", url, null, true, null, null);
            injectFallbackWebViewNetworkAudit(view, "page_started");
            delegate.onPageStarted(view, url, favicon);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            auditWebResource(state, "page_finished", url, null, true, null, null);
            injectFallbackWebViewNetworkAudit(view, "page_finished");
            delegate.onPageFinished(view, url);
        }

        @Override
        public void onLoadResource(WebView view, String url) {
            auditWebResource(state, "load", url, null, null, null, null);
            delegate.onLoadResource(view, url);
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onReceivedError(
                WebView view,
                int errorCode,
                String description,
                String failingUrl) {
            auditWebResource(
                    state,
                    "error",
                    failingUrl,
                    null,
                    null,
                    errorCode,
                    description);
            delegate.onReceivedError(view, errorCode, description, failingUrl);
        }

        @Override
        public void onReceivedError(
                WebView view,
                WebResourceRequest request,
                WebResourceError error) {
            auditWebResource(
                    state,
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
                    state,
                    "http_error",
                    request.getUrl().toString(),
                    request.getMethod(),
                    request.isForMainFrame(),
                    errorResponse.getStatusCode(),
                    errorResponse.getReasonPhrase());
            delegate.onReceivedHttpError(view, request, errorResponse);
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("phase", "render_process_gone");
            fields.put("did_crash", String.valueOf(detail != null && detail.didCrash()));
            if (detail != null) {
                fields.put("renderer_priority_at_exit", String.valueOf(detail.rendererPriorityAtExit()));
            }
            emit(state, "web_resource", fields, null);
            boolean handled = false;
            try {
                handled = delegate.onRenderProcessGone(view, detail);
            } catch (RuntimeException | LinkageError failure) {
                PropertyLog.w(
                        TAG,
                        "audit=webview event=render_process_gone delegate_failure=" +
                                failure.getClass().getSimpleName());
            } finally {
                synchronized (AUDITED_WEB_VIEWS) {
                    AUDITED_WEB_VIEWS.remove(view);
                }
            }
            if (!handled && view != null) {
                try {
                    view.destroy();
                    handled = true;
                } catch (RuntimeException | LinkageError failure) {
                    PropertyLog.w(
                            TAG,
                            "audit=webview event=render_process_gone destroy_failure=" +
                                    failure.getClass().getSimpleName());
                }
            }
            PropertyLog.w(
                    TAG,
                    "audit=webview event=render_process_gone handled=" + handled);
            return handled;
        }
    }
}
