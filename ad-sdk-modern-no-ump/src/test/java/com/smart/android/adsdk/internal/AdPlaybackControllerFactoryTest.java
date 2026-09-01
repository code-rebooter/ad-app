package com.smart.android.adsdk.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class AdPlaybackControllerFactoryTest {

    @Test
    public void systemUidSessionsReuseOneProcessPlaybackEngine() {
        List<FakePlaybackEngine> createdEngines = new ArrayList<>();
        AdPlaybackControllerFactory.SharedEngineHolder holder =
            new AdPlaybackControllerFactory.SharedEngineHolder();
        AdPlaybackControllerFactory factory = new AdPlaybackControllerFactory(
            true,
            () -> {
                FakePlaybackEngine engine = new FakePlaybackEngine();
                createdEngines.add(engine);
                return engine;
            },
            holder,
            NoOpTimeoutScheduler::new
        );

        AdPlayer first = factory.create(null, new NoOpPlayerListener());
        AdPlayer second = factory.create(null, new NoOpPlayerListener());

        first.play(config(), true);
        first.release();
        second.play(config(), true);
        second.release();

        assertEquals(1, createdEngines.size());
        FakePlaybackEngine engine = createdEngines.get(0);
        assertEquals(2, engine.playCalls);
        assertEquals(2, engine.detachCalls);
        assertEquals(0, engine.releaseCalls);
    }

    @Test
    public void regularUidSessionsOwnAndReleaseSeparatePlaybackEngines() {
        List<FakePlaybackEngine> createdEngines = new ArrayList<>();
        AdPlaybackControllerFactory factory = new AdPlaybackControllerFactory(
            false,
            () -> {
                FakePlaybackEngine engine = new FakePlaybackEngine();
                createdEngines.add(engine);
                return engine;
            },
            new AdPlaybackControllerFactory.SharedEngineHolder(),
            NoOpTimeoutScheduler::new
        );

        AdPlayer first = factory.create(null, new NoOpPlayerListener());
        AdPlayer second = factory.create(null, new NoOpPlayerListener());
        first.release();
        second.release();

        assertEquals(2, createdEngines.size());
        assertNotSame(createdEngines.get(0), createdEngines.get(1));
        assertEquals(1, createdEngines.get(0).releaseCalls);
        assertEquals(1, createdEngines.get(1).releaseCalls);
    }

    @Test
    public void sharedHolderReturnsTheSameEngineAcrossFactories() {
        AdPlaybackControllerFactory.SharedEngineHolder holder =
            new AdPlaybackControllerFactory.SharedEngineHolder();
        FakePlaybackEngine expected = new FakePlaybackEngine();
        AdPlaybackControllerFactory firstFactory = new AdPlaybackControllerFactory(
            true,
            () -> expected,
            holder,
            NoOpTimeoutScheduler::new
        );
        AdPlaybackControllerFactory secondFactory = new AdPlaybackControllerFactory(
            true,
            FakePlaybackEngine::new,
            holder,
            NoOpTimeoutScheduler::new
        );

        AdPlayer first = firstFactory.create(null, new NoOpPlayerListener());
        AdPlayer second = secondFactory.create(null, new NoOpPlayerListener());

        first.play(config(), true);
        first.release();
        second.play(config(), true);

        assertSame(expected, holder.peek());
        assertEquals(2, expected.playCalls);
    }

    private AdPlaybackConfig config() {
        return new AdPlaybackConfig("https://example.test/vast", 20_000, 35_000L);
    }

    private static final class FakePlaybackEngine implements AdPlaybackEngine {
        private int playCalls;
        private int detachCalls;
        private int releaseCalls;

        @Override
        public void play(
            ViewGroup container,
            AdPlaybackConfig config,
            boolean soundEnabled,
            Listener listener
        ) {
            playCalls++;
        }

        @Override
        public void pause(Listener listener) {
        }

        @Override
        public void resume(Listener listener) {
        }

        @Override
        public void setSoundEnabled(Listener listener, boolean enabled) {
        }

        @Override
        public void detach(Listener listener) {
            detachCalls++;
        }

        @Override
        public void release() {
            releaseCalls++;
        }
    }

    private static final class NoOpPlayerListener implements AdPlayer.Listener {
        @Override
        public void onLoaded() {
        }

        @Override
        public void onStarted() {
        }

        @Override
        public void onCompleted() {
        }

        @Override
        public void onSkipped(String reason) {
        }

        @Override
        public void onError(com.smart.android.adsdk.AdError error) {
        }
    }

    private static final class NoOpTimeoutScheduler implements TimeoutScheduler {
        @Override
        public Cancellable schedule(Runnable action, long delayMs) {
            return () -> {};
        }
    }
}
