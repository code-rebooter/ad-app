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
    private static final String DEFAULT_API_BASE_URL = "https://api.kytira.cc/";
    private static final String CVTE_CHANNEL_ID = "GOOGLE_AD_TV_CVTE";
    private static final String CVTE_API_BASE_URL = "https://api.xartek.cc/";
    private static final String LOCKSCREEN_HQ002_CHANNEL_ID = "GOOGLE_AD_TV_LOCKSCREEN_HQ002";
    private static final String LOCKSCREEN_HQ002_API_BASE_URL = "https://api.kartna.cc/";

    private final CallbackDispatcher dispatcher;
    private final ComponentsFactory componentsFactory;
    private volatile SessionCreator sessionCreator;

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

    public AdSession play(
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
        return activeSessionCreator.createAndStart(container, request, listener);
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
            String channelId = manifestConfig.getChannelId();
            String apiBaseUrl = resolveApiBaseUrl(channelId);
            SdkLog.i("AdSdk", "channel=" + channelId + " apiBaseUrl=" + apiBaseUrl);
            Gson gson = new Gson();
            OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .callTimeout(20L, TimeUnit.SECONDS)
                .addInterceptor(new SdkHttpLoggingInterceptor())
                .build();
            RemoteAdConfigResolver resolver = new RemoteAdConfigClient(
                applicationContext,
                okHttpClient,
                gson,
                new RemoteAdConfigParser(gson),
                apiBaseUrl
            );
            FlowControlResolver flowControlResolver = new FlowControlClient(
                applicationContext,
                okHttpClient,
                gson,
                apiBaseUrl
            );
            AdPlayerFactory playerFactory = new AdPlaybackControllerFactory(applicationContext);
            ConsentResolver consentResolver = new AdConsentResolver(
                okHttpClient,
                gson,
                apiBaseUrl + "api/v2/ad/consent-popup",
                apiBaseUrl + "api/v2/ad/consent-report"
            );
            Hq008AdReporter reporter = new Hq008AdReporter(
                applicationContext,
                okHttpClient,
                gson,
                channelId,
                apiBaseUrl
            );
            long adCallbackTimeoutMs = config.getAdCallbackTimeoutMs();
            return (container, request, listener) -> {
                AdSessionImpl session = new AdSessionImpl(
                    channelId,
                    applicationContext,
                    container,
                    request,
                    listener,
                    resolver,
                    playerFactory,
                    flowControlResolver,
                    consentResolver,
                    reporter,
                    adCallbackTimeoutMs,
                    new MainThreadTimeoutScheduler(),
                    dispatcher
                );
                session.start();
                return session;
            };
        }

        private String resolveApiBaseUrl(String channelId) {
            String normalizedChannelId = channelId == null ? "" : channelId.trim();
            if (LOCKSCREEN_HQ002_CHANNEL_ID.equalsIgnoreCase(normalizedChannelId)) {
                return LOCKSCREEN_HQ002_API_BASE_URL;
            }
            return CVTE_CHANNEL_ID.equalsIgnoreCase(normalizedChannelId)
                ? CVTE_API_BASE_URL
                : DEFAULT_API_BASE_URL;
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
