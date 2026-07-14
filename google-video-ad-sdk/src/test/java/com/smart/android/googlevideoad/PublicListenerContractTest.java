package com.smart.android.googlevideoad;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class PublicListenerContractTest {

    @Test
    public void adSessionExposesPlaybackControls() {
        AtomicInteger calls = new AtomicInteger();
        AdSession session = new AdSession() {
            @Override
            public AdState getState() {
                return AdState.PLAYING;
            }

            @Override
            public void pause() {
                calls.incrementAndGet();
            }

            @Override
            public void resume() {
                calls.incrementAndGet();
            }

            @Override
            public void setSoundEnabled(boolean enabled) {
                calls.incrementAndGet();
            }

            @Override
            public void release() {
                calls.incrementAndGet();
            }
        };

        session.pause();
        session.resume();
        session.setSoundEnabled(false);
        session.release();

        assertEquals(AdState.PLAYING, session.getState());
        assertEquals(4, calls.get());
    }

    @Test
    public void listenersExposeLifecycleCallbacks() {
        AtomicInteger calls = new AtomicInteger();
        AdListener adListener = new AdListener() {
            @Override
            public void onLoaded(AdSession session) {
                calls.incrementAndGet();
            }

            @Override
            public void onStarted(AdSession session) {
                calls.incrementAndGet();
            }

            @Override
            public void onFinished(AdSession session, AdResult result) {
                calls.incrementAndGet();
            }
        };
        InitializationListener initializationListener = new InitializationListener() {
            @Override
            public void onInitialized() {
                calls.incrementAndGet();
            }

            @Override
            public void onError(AdError error) {
                calls.incrementAndGet();
            }
        };

        adListener.onLoaded(null);
        adListener.onStarted(null);
        adListener.onFinished(null, AdResult.completed());
        initializationListener.onInitialized();
        initializationListener.onError(null);

        assertEquals(5, calls.get());
    }
}
