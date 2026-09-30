package com.smart.android.adsdk.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.content.Context;
import android.view.ViewGroup;
import com.smart.android.adsdk.AdError;
import com.smart.android.adsdk.AdErrorCode;
import com.smart.android.adsdk.AdListener;
import com.smart.android.adsdk.AdRequest;
import com.smart.android.adsdk.AdResult;
import com.smart.android.adsdk.AdSession;
import com.smart.android.adsdk.AdState;
import com.smart.android.adsdk.InitializationListener;
import com.smart.android.adsdk.SdkConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class SdkRuntimeTest {

    @Test
    public void initializeBuildsComponentsAndReportsSuccess() {
        RecordingComponentsFactory factory = new RecordingComponentsFactory();
        RecordingInitializationListener listener = new RecordingInitializationListener();
        SdkRuntime runtime = new SdkRuntime(Runnable::run, factory);
        SdkConfig config = new SdkConfig.Builder().build();

        runtime.initialize(null, config, listener);

        assertSame(config, factory.config);
        assertEquals(1, listener.successCount);
        assertEquals(0, listener.errors.size());
    }

    @Test
    public void playBeforeInitializationFinishesWithInitError() {
        SdkRuntime runtime = new SdkRuntime(Runnable::run, new RecordingComponentsFactory());
        RecordingAdListener listener = new RecordingAdListener();

        AdSession session = runtime.play(
            null,
            new AdRequest.Builder().build(),
            listener
        );

        assertEquals(AdState.FINISHED, session.getState());
        assertEquals(AdErrorCode.INIT_NOT_CALLED, listener.results.get(0).getError().getCode());
    }

    @Test
    public void initializedRuntimeDelegatesPlayToSessionCreator() {
        RecordingComponentsFactory factory = new RecordingComponentsFactory();
        SdkRuntime runtime = new SdkRuntime(Runnable::run, factory);
        runtime.initialize(
            null,
            new SdkConfig.Builder().build(),
            new RecordingInitializationListener()
        );
        AdRequest request = new AdRequest.Builder().setSoundEnabled(true).build();
        RecordingAdListener listener = new RecordingAdListener();

        AdSession session = runtime.play(null, request, listener);

        assertSame(factory.session, session);
        assertSame(request, factory.request);
        factory.listener.onFinished(session, AdResult.completed());
        assertEquals(1, listener.results.size());
        assertEquals(com.smart.android.adsdk.AdResultStatus.COMPLETED, listener.results.get(0).getStatus());
    }

    @Test
    public void overlappingPlayIsRejectedUntilEntireFlowFinishes() {
        RecordingComponentsFactory factory = new RecordingComponentsFactory();
        SdkRuntime runtime = new SdkRuntime(Runnable::run, factory);
        runtime.initialize(null, new SdkConfig.Builder().build(), new RecordingInitializationListener());
        AdSession first = runtime.play(null, new AdRequest.Builder().build(), new RecordingAdListener());
        AdListener firstListener = factory.listener;
        RecordingAdListener overlapping = new RecordingAdListener();

        runtime.play(null, new AdRequest.Builder().build(), overlapping);

        assertSame(firstListener, factory.listener);
        assertEquals(AdErrorCode.INVALID_ARGUMENT, overlapping.results.get(0).getError().getCode());
        firstListener.onFinished(first, AdResult.completed());
        RecordingAdListener next = new RecordingAdListener();
        assertSame(factory.session, runtime.play(null, new AdRequest.Builder().build(), next));
        assertEquals(0, next.results.size());
    }

    @Test
    public void terminalCallbackCanStartNextFlowWithoutBeingMarkedBusy() {
        RecordingComponentsFactory factory = new RecordingComponentsFactory();
        SdkRuntime runtime = new SdkRuntime(Runnable::run, factory);
        runtime.initialize(null, new SdkConfig.Builder().build(), new RecordingInitializationListener());
        RecordingAdListener next = new RecordingAdListener();
        AdSession first = runtime.play(null, new AdRequest.Builder().build(), new AdListener() {
            @Override public void onLoaded(AdSession session) {}
            @Override public void onStarted(AdSession session) {}
            @Override public void onFinished(AdSession session, AdResult result) {
                runtime.play(null, new AdRequest.Builder().build(), next);
            }
        });

        factory.listener.onFinished(first, AdResult.completed());

        assertEquals(0, next.results.size());
        RecordingAdListener overlapping = new RecordingAdListener();
        runtime.play(null, new AdRequest.Builder().build(), overlapping);
        assertEquals(AdErrorCode.INVALID_ARGUMENT, overlapping.results.get(0).getError().getCode());
    }

    @Test
    public void synchronousStartFailureDoesNotLeaveRuntimeBusy() {
        int[] attempts = {0};
        FakeSession next = new FakeSession();
        SdkRuntime runtime = new SdkRuntime(Runnable::run, (context, config, dispatcher) ->
            (container, request, listener) -> {
                if (++attempts[0] == 1) throw new IllegalStateException("native setup failed");
                return next;
            });
        runtime.initialize(null, new SdkConfig.Builder().build(), new RecordingInitializationListener());
        RecordingAdListener failed = new RecordingAdListener();

        runtime.play(null, new AdRequest.Builder().build(), failed);

        assertEquals("native setup failed", failed.results.get(0).getError().getMessage());
        assertSame(next, runtime.play(null, new AdRequest.Builder().build(), new RecordingAdListener()));
        assertEquals(2, attempts[0]);
    }

    private static final class RecordingComponentsFactory
        implements SdkRuntime.ComponentsFactory {
        private final FakeSession session = new FakeSession();
        private SdkConfig config;
        private AdRequest request;
        private AdListener listener;

        @Override
        public SdkRuntime.SessionCreator create(
            Context context,
            SdkConfig config,
            CallbackDispatcher dispatcher
        ) {
            this.config = config;
            return (container, request, listener) -> {
                this.request = request;
                this.listener = listener;
                return session;
            };
        }
    }

    private static final class RecordingInitializationListener
        implements InitializationListener {
        private int successCount;
        private final List<AdError> errors = new ArrayList<>();

        @Override
        public void onInitialized() {
            successCount++;
        }

        @Override
        public void onError(AdError error) {
            errors.add(error);
        }
    }

    private static final class RecordingAdListener implements AdListener {
        private final List<AdResult> results = new ArrayList<>();

        @Override
        public void onLoaded(AdSession session) {
        }

        @Override
        public void onStarted(AdSession session) {
        }

        @Override
        public void onFinished(AdSession session, AdResult result) {
            results.add(result);
        }
    }

    private static final class FakeSession implements AdSession {
        @Override
        public AdState getState() {
            return AdState.RESOLVING_CONFIG;
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
