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
    private static final String API_BASE_URL = "https://api.kytira.cc/";
    private static final String CONSENT_POPUP_URL =
        API_BASE_URL + "api/v2/ad/consent-popup";
    private static final String CONSENT_REPORT_URL =
        API_BASE_URL + "api/v2/ad/consent-report";

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
        try {
            sessionCreator = componentsFactory.create(context, config, dispatcher);
            dispatcher.dispatch(listener::onInitialized);
        } catch (RuntimeException error) {
            AdError adError = new AdError(
                AdErrorCode.INTERNAL_ERROR,
                AdErrorStage.INITIALIZATION,
                "Unable to initialize ad SDK",
                error
            );
            dispatcher.dispatch(() -> listener.onError(adError));
        }
    }

    public AdSession play(
        ViewGroup container,
        AdRequest request,
        AdListener listener
    ) {
        SessionCreator activeSessionCreator = sessionCreator;
        if (activeSessionCreator == null) {
            AdError error = new AdError(
                AdErrorCode.INIT_NOT_CALLED,
                AdErrorStage.INITIALIZATION,
                "AdSdk.initialize must be called before play",
                null
            );
            FailedAdSession failedSession = new FailedAdSession();
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
            Gson gson = new Gson();
            OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .callTimeout(20L, TimeUnit.SECONDS)
                .build();
            RemoteAdConfigResolver resolver = new RemoteAdConfigClient(
                applicationContext,
                okHttpClient,
                gson,
                new RemoteAdConfigParser(gson),
                API_BASE_URL
            );
            FlowControlResolver flowControlResolver = new FlowControlClient(
                applicationContext,
                okHttpClient,
                gson,
                API_BASE_URL
            );
            AdPlayerFactory playerFactory = new AdPlaybackControllerFactory(applicationContext);
            ConsentResolver consentResolver = new AdConsentResolver(
                okHttpClient,
                gson,
                CONSENT_POPUP_URL,
                CONSENT_REPORT_URL
            );
            String channelId = manifestConfig.getChannelId();
            Hq008AdReporter reporter = new Hq008AdReporter(
                applicationContext,
                okHttpClient,
                gson,
                channelId,
                API_BASE_URL
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
