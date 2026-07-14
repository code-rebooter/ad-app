package com.smart.android.googlevideoad.internal;

import android.content.Context;
import android.view.ViewGroup;
import com.google.gson.Gson;
import com.smart.android.googlevideoad.AdError;
import com.smart.android.googlevideoad.AdErrorCode;
import com.smart.android.googlevideoad.AdErrorStage;
import com.smart.android.googlevideoad.AdListener;
import com.smart.android.googlevideoad.AdRequest;
import com.smart.android.googlevideoad.AdResult;
import com.smart.android.googlevideoad.AdSession;
import com.smart.android.googlevideoad.AdState;
import com.smart.android.googlevideoad.InitializationListener;
import com.smart.android.googlevideoad.SdkConfig;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

public final class SdkRuntime {
    private static final String GAM_RESOLVE_URL =
        "https://api.kytira.cc/api/v2/ad/google-gam/resolve";

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

    public void initialize(
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
                "Unable to initialize Google video ad SDK",
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
                "GoogleVideoAds.initialize must be called before play",
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
            Context applicationContext = context.getApplicationContext();
            if (applicationContext == null) {
                applicationContext = context;
            }
            Gson gson = new Gson();
            OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .callTimeout(20L, TimeUnit.SECONDS)
                .build();
            GamConfigResolver resolver = new GamConfigClient(
                okHttpClient,
                gson,
                new GamConfigParser(gson),
                GAM_RESOLVE_URL
            );
            AdPlayerFactory playerFactory = new ImaAdPlayerFactory(applicationContext);
            String channelId = config.getChannelId();
            return (container, request, listener) -> {
                AdSessionImpl session = new AdSessionImpl(
                    channelId,
                    container,
                    request,
                    listener,
                    resolver,
                    playerFactory,
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
