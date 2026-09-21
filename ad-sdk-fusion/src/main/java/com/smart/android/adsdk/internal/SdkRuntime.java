package com.smart.android.adsdk.internal;

import android.content.Context;
import android.view.ViewGroup;
import com.google.gson.Gson;
import com.smart.android.adsdk.AdError;
import com.smart.android.adsdk.AdErrorCode;
import com.smart.android.adsdk.AdErrorStage;
import com.smart.android.adsdk.AdListener;
import com.smart.android.adsdk.AdRequest;
import com.smart.android.adsdk.AdResult;
import com.smart.android.adsdk.AdSession;
import com.smart.android.adsdk.AdState;
import com.smart.android.adsdk.InitializationListener;
import com.smart.android.adsdk.SdkConfig;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

public final class SdkRuntime {
    private final CallbackDispatcher dispatcher;
    private final ComponentsFactory componentsFactory;
    private volatile SessionCreator sessionCreator;
    private Object activeCall;

    SdkRuntime(CallbackDispatcher dispatcher, ComponentsFactory componentsFactory) {
        this.dispatcher = dispatcher;
        this.componentsFactory = componentsFactory;
    }

    public static SdkRuntime createDefault() {
        MainThreadDispatcher dispatcher = new MainThreadDispatcher();
        return new SdkRuntime(dispatcher, new DefaultComponentsFactory());
    }

    public synchronized void initialize(
        Context context,
        SdkConfig config,
        InitializationListener listener
    ) {
        SdkLog.configure(config.isDebugLogging());
        SdkLog.i("AdSdk", "initialize timeoutMs=" + config.getAdCallbackTimeoutMs());
        try {
            sessionCreator = componentsFactory.create(context, config, dispatcher);
            SdkLog.i("AdSdk", "onInitialized");
            dispatcher.dispatch(listener::onInitialized);
        } catch (RuntimeException error) {
            SdkLog.e("AdSdk", "initialization failed", error);
            AdError adError = AdErrors.from(AdErrorCode.INTERNAL_ERROR, AdErrorStage.INITIALIZATION, error, null);
            dispatcher.dispatch(() -> listener.onError(adError));
        }
    }

    public synchronized AdSession play(
        ViewGroup container,
        AdRequest request,
        AdListener listener
    ) {
        SdkLog.i("AdSdk", "play requestId=" + request.getRequestId()
            + " container=" + System.identityHashCode(container));
        SessionCreator activeSessionCreator = sessionCreator;
        if (activeSessionCreator == null) {
            AdError error = new AdError(
                AdErrorCode.INIT_NOT_CALLED,
                AdErrorStage.INITIALIZATION,
                "AdSdk.initialize must be called before play",
                null
            );
            FailedAdSession failedSession = new FailedAdSession();
            SdkLog.w("AdSdk", "onFinished " + AdResult.error(error));
            dispatcher.dispatch(() -> listener.onFinished(failedSession, AdResult.error(error)));
            return failedSession;
        }
        if (activeCall != null) {
            FailedAdSession failed = new FailedAdSession();
            AdResult result = AdResult.error(new AdError(AdErrorCode.INVALID_ARGUMENT,
                AdErrorStage.INTERNAL, "An ad flow is already active", null));
            dispatcher.dispatch(() -> listener.onFinished(failed, result));
            return failed;
        }
        Object call = new Object();
        activeCall = call;
        AdListener guarded = new AdListener() {
            @Override public void onLoaded(AdSession session) { listener.onLoaded(session); }
            @Override public void onStarted(AdSession session) { listener.onStarted(session); }
            @Override public void onFinished(AdSession session, AdResult result) {
                synchronized (SdkRuntime.this) {
                    if (activeCall == call) activeCall = null;
                }
                listener.onFinished(session, result);
            }
        };
        try {
            return activeSessionCreator.createAndStart(container, request, guarded);
        } catch (RuntimeException error) {
            if (activeCall == call) activeCall = null;
            FailedAdSession failed = new FailedAdSession();
            AdResult result = AdResult.error(AdErrors.from(AdErrorCode.INTERNAL_ERROR,
                AdErrorStage.INTERNAL, error, null));
            dispatcher.dispatch(() -> listener.onFinished(failed, result));
            return failed;
        }
    }

    interface ComponentsFactory {
        SessionCreator create(
            Context context,
            SdkConfig config,
            CallbackDispatcher dispatcher
        );
    }

    interface SessionCreator {
        AdSession createAndStart(
            ViewGroup container,
            AdRequest request,
            AdListener listener
        );
    }

    private static final class DefaultComponentsFactory implements ComponentsFactory {
        @Override
        public SessionCreator create(
            Context context,
            SdkConfig config,
            CallbackDispatcher dispatcher
        ) {
            Context resolvedContext = context.getApplicationContext();
            if (resolvedContext == null) {
                resolvedContext = context;
            }
            final Context applicationContext = resolvedContext;
            ManifestAdConfig manifestConfig = ManifestAdConfig.read(applicationContext);
            FusionChannels channels = new FusionChannels(manifestConfig.getChannelId());
            String apiBaseUrl = channels.apiBaseUrl;
            SdkLog.i("AdSdkFusion", "google=" + channels.googleChannel + " tcl=" + channels.tclChannel
                + " sharedApiBaseUrl=" + apiBaseUrl);
            Gson gson = new Gson();
            OkHttpClient http = new OkHttpClient.Builder().callTimeout(20L, TimeUnit.SECONDS)
                .addInterceptor(new SdkHttpLoggingInterceptor()).build();
            RemoteAdConfigResolver googleResolver = new RemoteAdConfigClient(applicationContext,
                http, gson, new RemoteAdConfigParser(gson), apiBaseUrl);
            RemoteAdConfigResolver tclResolver = new RemoteAdConfigClient(
                () -> DeviceInfo.collect(applicationContext), http, gson,
                new RemoteAdConfigParser(gson), apiBaseUrl, true);
            FlowControlResolver flow = new FlowControlClient(applicationContext, http, gson, apiBaseUrl);
            AdPlayerFactory googlePlayer = new AdPlaybackControllerFactory(applicationContext);
            AdPlayerFactory tclPlayer = (container, adListener) ->
                new TclAdPlayer(applicationContext, container, channels.tclChannel, adListener);
            ConsentResolver googleConsent = new AdConsentResolver(http, gson,
                apiBaseUrl + "api/v2/ad/consent-popup", apiBaseUrl + "api/v2/ad/consent-report");
            TclConsentResolver tclConsent = new TclConsentResolver(applicationContext, http, gson,
                apiBaseUrl, dispatcher);
            tclConsent.initialize(applicationContext, channels.tclChannel);
            Hq008AdReporter googleReporter = new Hq008AdReporter(
                () -> DeviceInfo.collect(applicationContext), http, gson, channels.googleChannel, apiBaseUrl, "ima");
            Hq008AdReporter tclReporter = new Hq008AdReporter(
                () -> DeviceInfo.collect(applicationContext), http, gson, channels.tclChannel, apiBaseUrl, "tcl");
            long timeout = config.getAdCallbackTimeoutMs();
            return (container, request, listener) -> {
                FusionAdSession session = new FusionAdSession(request, listener, dispatcher,
                    (childRequest, childListener) -> new AdSessionImpl(channels.googleChannel,
                        applicationContext, container, childRequest, childListener, googleResolver,
                        googlePlayer, flow, googleConsent, googleReporter, timeout,
                        new MainThreadTimeoutScheduler(), dispatcher),
                    (childRequest, childListener) -> new AdSessionImpl(channels.tclChannel,
                        applicationContext, container, childRequest, childListener, tclResolver,
                        tclPlayer, flow, tclConsent, tclReporter, timeout,
                        new MainThreadTimeoutScheduler(), dispatcher));
                session.start();
                return session;
            };
        }
    }

    private static final class FailedAdSession implements AdSession {
        @Override
        public AdState getState() {
            return AdState.FINISHED;
        }

        @Override
        public void pause() {
        }

        @Override
        public void resume() {
        }

        @Override
        public void setSoundEnabled(boolean enabled) {
        }

        @Override
        public void release() {
        }
    }
}
