package com.smart.android.adsdk.universal.internal;

import android.content.Context;
import android.view.ViewGroup;
import com.google.gson.Gson;
import com.smart.android.adsdk.universal.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

/** Independent SDK runtime; no manifest channel, existing SDK runtime or old Google player is loaded. */
public final class SdkRuntime {
    private final CallbackDispatcher dispatcher;
    private final ConfigLoader configLoader;
    private final ComponentsBuilder componentsBuilder;
    private final KeyConfigRefresh.Store suppliedStore;
    private final KeyConfigRefresh.Clock suppliedClock;
    private final TimeoutScheduler suppliedScheduler;
    public SdkRuntime() { this(new MainThreadDispatcher(), null, null); }
    SdkRuntime(CallbackDispatcher dispatcher, ConfigLoader loader, ComponentsBuilder builder) {
        this(dispatcher, loader, builder, null, System::currentTimeMillis, null);
    }
    SdkRuntime(CallbackDispatcher dispatcher, ConfigLoader loader, ComponentsBuilder builder,
               KeyConfigRefresh.Store store, KeyConfigRefresh.Clock clock, TimeoutScheduler scheduler) {
        this.dispatcher = dispatcher; this.configLoader = loader; this.componentsBuilder = builder;
        suppliedStore = store; suppliedClock = clock; suppliedScheduler = scheduler;
    }
    interface ConfigLoader { void request(String key, KeyConfigClient.Callback callback); }
    interface ComponentsBuilder { SessionCreator create(Context context, OkHttpClient client, String base, SdkConfig config, KeyConfiguration registration); }
    private final List<InitializationListener> pending = new ArrayList<>();
    private String key;
    private boolean initializing;
    private SessionCreator sessions;
    private Object activeCall;
    private KeyConfigRefresh refresh;
    private KeyConfiguration applied, replacement;
    private SessionCreator replacementSessions;
    private Context app;
    private OkHttpClient http;
    private SdkConfig options;
    public synchronized void initialize(Context context, String sdkKey, SdkConfig config, InitializationListener listener) {
        if (key != null && !key.equals(sdkKey)) {
            dispatcher.dispatch(() -> listener.onError(error("Only one sdkKey is allowed per process", null))); return;
        }
        if (sessions != null) { dispatcher.dispatch(refresh::check); dispatcher.dispatch(listener::onInitialized); return; }
        pending.add(listener);
        if (initializing) return;
        key = sdkKey; initializing = true; SdkLog.redactKey(sdkKey);
        Context resolved = context == null ? null : context.getApplicationContext();
        app = resolved == null ? context : resolved; options = config;
        http = new OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build();
        try {
            KeyConfigRefresh.Store store = suppliedStore;
            if (store == null) store = configLoader == null ? new KeyConfigStore(app) : new KeyConfigRefresh.Store() {
                @Override public KeyConfigRefresh.Entry load(String ignored) { return null; }
                @Override public void save(String ignored, KeyConfigRefresh.Entry entry) {}
            };
            TimeoutScheduler scheduler = suppliedScheduler;
            if (scheduler == null) scheduler = configLoader == null ? new MainThreadTimeoutScheduler() : (action, delay) -> () -> {};
            ConfigLoader loader = configLoader == null
                ? (requestedKey, result) -> new KeyConfigClient(() -> RequestVersions.collect(app), http,
                    KeyConfigClient.DEFAULT_BASE_URL).request(requestedKey, result)
                : configLoader;
            refresh = new KeyConfigRefresh(sdkKey, store, suppliedClock, scheduler, loader, dispatcher,
                new KeyConfigRefresh.Listener() {
                    @Override public void onConfiguration(KeyConfiguration registration) { prepareReplacement(registration); }
                    @Override public void onFailure(Throwable failure, boolean hasCache) {
                        synchronized (SdkRuntime.this) {
                            if (sessions == null) {
                                refresh.stop(); initializing = false; key = null;
                                notifyInitialization(failure);
                            } else SdkLog.w("AdSdkConfig", "Configuration refresh failed; retaining cached configuration", failure);
                        }
                    }
                });
            dispatcher.dispatch(refresh::start);
        } catch (RuntimeException | LinkageError failure) {
            if (refresh != null) refresh.stop();
            initializing = false; key = null; notifyInitialization(failure);
        }
    }
    private synchronized void prepareReplacement(KeyConfiguration registration) {
        SessionCreator prepared = applied != null && applied.sameRegistration(registration) ? sessions
            : componentsBuilder == null ? createSessions(app, http, KeyConfigClient.DEFAULT_BASE_URL, options, registration)
            : componentsBuilder.create(app, http, KeyConfigClient.DEFAULT_BASE_URL, options, registration);
        if (prepared == null) throw new IllegalArgumentException("Missing SDK playback components");
        replacement = registration; replacementSessions = prepared;
        tryApplyReplacement(true);
        if (sessions == null) throw new IllegalStateException("Native registration is still busy");
        if (initializing) { initializing = false; notifyInitialization(null); }
    }
    private void tryApplyReplacement(boolean propagateFailure) {
        if (replacement == null || activeCall != null) return;
        try {
            if (componentsBuilder == null) {
                if (!NativeRuntime.installRegistration(app, applied, replacement)) return;
            } else TclIdentityBridge.install(replacement.tclIdentity);
            SdkLog.redactChannels(replacement.googleChannelId, replacement.tclChannelId);
            applied = replacement; sessions = replacementSessions;
            replacement = null; replacementSessions = null;
        } catch (RuntimeException | LinkageError failure) {
            replacement = null; replacementSessions = null;
            if (sessions == null || propagateFailure) throw failure;
            SdkLog.w("AdSdkConfig", "Configuration application failed; retaining previous registration", failure);
        }
    }
    private void notifyInitialization(Throwable failure) {
        List<InitializationListener> callbacks = new ArrayList<>(pending); pending.clear();
        for (InitializationListener callback : callbacks) dispatcher.dispatch(() -> {
            if (failure == null) callback.onInitialized();
            else callback.onError(error("SDK key initialization failed", failure));
        });
    }
    public synchronized boolean isInitialized() {
        return sessions != null;
    }
    private SessionCreator createSessions(Context app, OkHttpClient http, String base, SdkConfig config, KeyConfiguration registration) {
        Gson gson = new Gson();
        RemoteAdConfigClient google = new RemoteAdConfigClient(app, http, gson, new RemoteAdConfigParser(gson), base);
        google.setIdentifierContext(app);
        RemoteAdConfigResolver tcl = new RemoteAdConfigClient(() -> DeviceInfo.collect(app), http, gson,
            new RemoteAdConfigParser(gson), base, true);
        FlowControlResolver flow = new FlowControlClient(app, http, gson, base);
        ConsentResolver googleCmp = new GoogleConsentResolver(app, http, gson, base);
        TclConsentResolver tclCmp = new TclConsentResolver(app, http, gson, base, dispatcher);
        Hq008AdReporter googleReporter = new Hq008AdReporter(() -> DeviceInfo.collect(app), http, gson, registration.googleChannelId, base, "original_adurl_uniplayer");
        Hq008AdReporter tclReporter = new Hq008AdReporter(() -> DeviceInfo.collect(app), http, gson, registration.tclChannelId, base, "tcl");
        return (host, request, listener) -> {
            FusionAdSession session = new FusionAdSession(request, listener, dispatcher,
                (child, callbacks) -> {
                    NativeRuntime.enter(app, "google");
                    return new AdSessionImpl(registration.googleChannelId, app, host, child, callbacks, google,
                        (container, events) -> new GoogleTclAdPlayer(app, container, events), flow, googleCmp,
                        googleReporter, config.getAdCallbackTimeoutMs(), new MainThreadTimeoutScheduler(), dispatcher);
                },
                (child, callbacks) -> {
                    NativeRuntime.enter(app, "tcl");
                    tclCmp.initialize(app, registration.tclChannelId);
                    return new AdSessionImpl(registration.tclChannelId, app, host, child, callbacks, tcl,
                        (container, events) -> new TclAdPlayer(app, container, registration.tclChannelId, events), flow, tclCmp,
                        tclReporter, config.getAdCallbackTimeoutMs(), new MainThreadTimeoutScheduler(), dispatcher);
                });
            session.start(); return session;
        };
    }
    public synchronized AdSession play(ViewGroup container, AdRequest request, AdListener listener) {
        if (sessions == null) return reject(listener, AdErrorCode.INIT_NOT_CALLED, "SDK key initialization has not completed");
        if (activeCall != null) return reject(listener, AdErrorCode.INVALID_ARGUMENT, "An ad flow is already active");
        dispatcher.dispatch(refresh::check);
        tryApplyReplacement(false);
        if (replacement != null && !applied.sameRegistration(replacement))
            return reject(listener, AdErrorCode.INTERNAL_ERROR, "Native registration update is waiting for previous CMP work");
        Object call = new Object(); activeCall = call;
        AdListener guarded = new AdListener() {
            @Override public void onLoaded(AdSession session) { listener.onLoaded(session); }
            @Override public void onStarted(AdSession session) { listener.onStarted(session); }
            @Override public void onFinished(AdSession session, AdResult result) {
                synchronized (SdkRuntime.this) { if (activeCall == call) activeCall = null; }
                listener.onFinished(session, result);
            }
        };
        try { return sessions.create(container, request, guarded); }
        catch (RuntimeException | LinkageError failure) { activeCall = null; return reject(listener, AdErrorCode.INTERNAL_ERROR, "Ad flow failed: " + failure); }
    }
    private AdSession reject(AdListener listener, AdErrorCode code, String message) {
        FailedSession failed = new FailedSession();
        dispatcher.dispatch(() -> listener.onFinished(failed, AdResult.error(new AdError(code, AdErrorStage.INITIALIZATION, message, null))));
        return failed;
    }
    private static AdError error(String message, Throwable failure) { return new AdError(AdErrorCode.CONFIG_PARSE_ERROR, AdErrorStage.INITIALIZATION, message, failure); }
    interface SessionCreator { AdSession create(ViewGroup container, AdRequest request, AdListener listener); }
    private static final class FailedSession implements AdSession {
        @Override public AdState getState() { return AdState.FINISHED; }
        @Override public void pause() {} @Override public void resume() {} @Override public void setSoundEnabled(boolean value) {} @Override public void release() {}
    }
}
