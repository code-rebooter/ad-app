package com.smart.android.googlevideoad.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.ViewGroup;
import com.smart.android.googlevideoad.AdError;
import com.smart.android.googlevideoad.AdErrorCode;
import com.smart.android.googlevideoad.AdErrorStage;
import com.smart.android.googlevideoad.AdListener;
import com.smart.android.googlevideoad.AdRequest;
import com.smart.android.googlevideoad.AdResult;
import com.smart.android.googlevideoad.AdResultStatus;
import com.smart.android.googlevideoad.AdSession;
import com.smart.android.googlevideoad.AdState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class AdSessionImplTest {
    private Fixture fixture;

    @Before
    public void setUp() {
        fixture = new Fixture();
    }

    @Test
    public void sessionResolvesLoadsStartsAndCompletes() {
        AdSessionImpl session = fixture.createSession();

        session.start();
        fixture.resolver.succeed(new GamPlaybackConfig("https://example.test/vast", 20_000, 35_000L));
        fixture.player.emitLoaded();
        fixture.player.emitStarted();
        fixture.player.emitCompleted();

        assertEquals(Arrays.asList("loaded", "started", "finished:COMPLETED"), fixture.events);
        assertEquals(AdState.FINISHED, session.getState());
        assertTrue(fixture.player.released);
    }

    @Test
    public void unavailableConfigFinishesAsSkipped() {
        AdSessionImpl session = fixture.createSession();

        session.start();
        fixture.resolver.skip("NO_AD_TAG");

        assertEquals(Arrays.asList("finished:SKIPPED:NO_AD_TAG"), fixture.events);
        assertEquals(AdState.FINISHED, session.getState());
    }

    @Test
    public void resolverErrorFinishesAsError() {
        AdSessionImpl session = fixture.createSession();

        session.start();
        fixture.resolver.fail(new AdError(
            AdErrorCode.CONFIG_NETWORK_ERROR,
            AdErrorStage.CONFIG,
            "offline",
            null
        ));

        assertEquals(Arrays.asList("finished:ERROR:CONFIG_NETWORK_ERROR"), fixture.events);
    }

    @Test
    public void competingTerminalCallbacksNotifyOnlyOnce() {
        AdSessionImpl session = fixture.startedSession();

        fixture.player.emitError(new AdError(
            AdErrorCode.IMA_PLAYBACK_ERROR,
            AdErrorStage.PLAYER,
            "boom",
            null
        ));
        fixture.player.emitCompleted();
        session.release();

        assertEquals(1, fixture.finishedCount());
        assertEquals("finished:ERROR:IMA_PLAYBACK_ERROR", fixture.events.get(2));
    }

    @Test
    public void playbackControlsDelegateOnlyInLegalStates() {
        AdSessionImpl session = fixture.startedSession();

        session.pause();
        session.pause();
        session.setSoundEnabled(false);
        session.resume();
        session.resume();

        assertEquals(1, fixture.player.pauseCalls);
        assertEquals(1, fixture.player.resumeCalls);
        assertFalse(fixture.player.soundEnabled);
        assertEquals(AdState.PLAYING, session.getState());
    }

    @Test
    public void earlyReleaseCancelsWorkAndFinishesOnce() {
        AdSessionImpl session = fixture.createSession();

        session.start();
        session.release();
        session.release();
        fixture.resolver.succeed(new GamPlaybackConfig("https://example.test/vast", 20_000, 35_000L));

        assertTrue(fixture.resolver.cancelled);
        assertEquals(Arrays.asList("finished:CANCELLED"), fixture.events);
        assertEquals(1, fixture.finishedCount());
    }

    private static final class Fixture {
        private final FakeResolver resolver = new FakeResolver();
        private final FakePlayer player = new FakePlayer();
        private final List<String> events = new ArrayList<>();

        private AdSessionImpl createSession() {
            AdListener listener = new AdListener() {
                @Override
                public void onLoaded(AdSession session) {
                    events.add("loaded");
                }

                @Override
                public void onStarted(AdSession session) {
                    events.add("started");
                }

                @Override
                public void onFinished(AdSession session, AdResult result) {
                    String event = "finished:" + result.getStatus();
                    if (result.getStatus() == AdResultStatus.SKIPPED) {
                        event += ":" + result.getReason();
                    } else if (result.getStatus() == AdResultStatus.ERROR) {
                        event += ":" + result.getError().getCode();
                    }
                    events.add(event);
                }
            };
            AdPlayerFactory playerFactory = (container, playerListener) -> {
                player.listener = playerListener;
                return player;
            };
            return new AdSessionImpl(
                "CHANNEL_A",
                null,
                new AdRequest.Builder().setSoundEnabled(true).build(),
                listener,
                resolver,
                playerFactory,
                Runnable::run
            );
        }

        private AdSessionImpl startedSession() {
            AdSessionImpl session = createSession();
            session.start();
            resolver.succeed(new GamPlaybackConfig("https://example.test/vast", 20_000, 35_000L));
            player.emitLoaded();
            player.emitStarted();
            return session;
        }

        private int finishedCount() {
            int count = 0;
            for (String event : events) {
                if (event.startsWith("finished:")) {
                    count++;
                }
            }
            return count;
        }
    }

    private static final class FakeResolver implements GamConfigResolver {
        private Callback callback;
        private boolean cancelled;

        @Override
        public Cancellable resolve(String channelId, Callback callback) {
            this.callback = callback;
            return () -> cancelled = true;
        }

        private void succeed(GamPlaybackConfig config) {
            callback.onResolved(GamResolveResult.withAd(config));
        }

        private void skip(String reason) {
            callback.onResolved(GamResolveResult.skipped(reason));
        }

        private void fail(AdError error) {
            callback.onError(error);
        }
    }

    private static final class FakePlayer implements AdPlayer {
        private Listener listener;
        private int pauseCalls;
        private int resumeCalls;
        private boolean soundEnabled;
        private boolean released;

        @Override
        public void play(GamPlaybackConfig config, boolean soundEnabled) {
            this.soundEnabled = soundEnabled;
        }

        @Override
        public void pause() {
            pauseCalls++;
        }

        @Override
        public void resume() {
            resumeCalls++;
        }

        @Override
        public void setSoundEnabled(boolean enabled) {
            soundEnabled = enabled;
        }

        @Override
        public void release() {
            released = true;
        }

        private void emitLoaded() {
            listener.onLoaded();
        }

        private void emitStarted() {
            listener.onStarted();
        }

        private void emitCompleted() {
            listener.onCompleted();
        }

        private void emitError(AdError error) {
            listener.onError(error);
        }
    }
}
