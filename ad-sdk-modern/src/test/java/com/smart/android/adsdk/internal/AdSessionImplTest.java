package com.smart.android.adsdk.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.ViewGroup;
import com.smart.android.adsdk.AdError;
import com.smart.android.adsdk.AdErrorCode;
import com.smart.android.adsdk.AdErrorStage;
import com.smart.android.adsdk.AdListener;
import com.smart.android.adsdk.AdRequest;
import com.smart.android.adsdk.AdResult;
import com.smart.android.adsdk.AdResultStatus;
import com.smart.android.adsdk.AdSession;
import com.smart.android.adsdk.AdState;
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
        fixture.resolver.succeed(new AdPlaybackConfig("https://example.test/vast", 20_000, 35_000L));
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
            AdErrorCode.AD_PLAYBACK_ERROR,
            AdErrorStage.PLAYER,
            "boom",
            null
        ));
        fixture.player.emitCompleted();
        session.release();

        assertEquals(1, fixture.finishedCount());
        assertEquals("finished:ERROR:AD_PLAYBACK_ERROR", fixture.events.get(2));
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
        fixture.resolver.succeed(new AdPlaybackConfig("https://example.test/vast", 20_000, 35_000L));

        assertTrue(fixture.resolver.cancelled);
        assertEquals(Arrays.asList("finished:CANCELLED"), fixture.events);
        assertEquals(1, fixture.finishedCount());
    }

    @Test
    public void consentResolverBlocksBeforeConfigResolve() {
        fixture.consentResolver = (context, channelId, callback) -> {
            callback.onBlocked("CONSENT_REQUIRED");
            return () -> {};
        };
        AdSessionImpl session = fixture.createSession();

        session.start();

        assertEquals(Arrays.asList("finished:SKIPPED:CONSENT_REQUIRED"), fixture.events);
        assertEquals(0, fixture.resolver.resolveCalls);
    }

    @Test
    public void consentResolverErrorFinishesBeforeConfigResolve() {
        fixture.consentResolver = (context, channelId, callback) -> {
            callback.onError(new AdError(
                AdErrorCode.INTERNAL_ERROR,
                AdErrorStage.INTERNAL,
                "consent failed",
                null
            ));
            return () -> {};
        };
        AdSessionImpl session = fixture.createSession();

        session.start();

        assertEquals(Arrays.asList("finished:ERROR:INTERNAL_ERROR"), fixture.events);
        assertEquals(0, fixture.resolver.resolveCalls);
    }

    @Test
    public void totalCallbackTimeoutFinishesSessionAndCancelsConfigRequest() {
        AdSessionImpl session = fixture.createSession();

        session.start();
        fixture.timeoutScheduler.fireLatest();

        assertTrue(fixture.resolver.cancelled);
        assertEquals(Arrays.asList("finished:ERROR:TIMEOUT"), fixture.events);
    }

    private static final class Fixture {
        private final FakeResolver resolver = new FakeResolver();
        private final FakePlayer player = new FakePlayer();
        private final FakeTimeoutScheduler timeoutScheduler = new FakeTimeoutScheduler();
        private final List<String> events = new ArrayList<>();
        private ConsentResolver consentResolver =
            (context, channelId, callback) -> {
                callback.onAllowed();
                return () -> {};
            };

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
                null,
                new AdRequest.Builder().setSoundEnabled(true).setRequestId("request-123").build(),
                listener,
                resolver,
                playerFactory,
                consentResolver,
                180_000L,
                timeoutScheduler,
                Runnable::run
            );
        }

        private AdSessionImpl startedSession() {
            AdSessionImpl session = createSession();
            session.start();
            resolver.succeed(new AdPlaybackConfig("https://example.test/vast", 20_000, 35_000L));
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

    private static final class FakeResolver implements RemoteAdConfigResolver {
        private Callback callback;
        private boolean cancelled;
        private int resolveCalls;

        @Override
        public Cancellable resolve(String channelId, String requestId, Callback callback) {
            resolveCalls++;
            this.callback = callback;
            return () -> cancelled = true;
        }

        private void succeed(AdPlaybackConfig config) {
            callback.onResolved(RemoteAdConfigResult.withAd(config));
        }

        private void skip(String reason) {
            callback.onResolved(RemoteAdConfigResult.skipped(reason));
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
        public void play(AdPlaybackConfig config, boolean soundEnabled) {
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

    private static final class FakeTimeoutScheduler implements TimeoutScheduler {
        private Runnable latestAction;
        private boolean cancelled;

        @Override
        public Cancellable schedule(Runnable action, long delayMs) {
            latestAction = action;
            cancelled = false;
            return () -> cancelled = true;
        }

        private void fireLatest() {
            if (!cancelled && latestAction != null) {
                latestAction.run();
            }
        }
    }
}
