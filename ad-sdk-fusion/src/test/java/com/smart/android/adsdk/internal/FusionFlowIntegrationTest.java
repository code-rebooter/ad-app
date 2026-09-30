package com.smart.android.adsdk.internal;

import static org.junit.Assert.*;
import com.google.gson.*;
import com.smart.android.adsdk.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import okhttp3.*;
import okio.Buffer;
import org.junit.Test;

/** Real channel sessions + HTTP clients + reporting; only playback and time are simulated. */
public class FusionFlowIntegrationTest {
    @Test public void tclCmpDetailsSurviveSessionAndBackendTraceUpload() throws Exception {
        try (Fixture f = new Fixture()) {
            f.skipCmp = false;
            f.session.start(); f.until(() -> f.google.playing);
            f.google.listener.onCompleted(); f.until(() -> f.tcl.playing);
            f.tcl.listener.onCompleted();
            f.until(() -> f.count("consent-log-report", null, "GOOGLE_AD_TV_LOCKSCREEN_HQ002_TCL") == 1);
            boolean found = false;
            for (Captured c : f.captured) {
                if (!c.path.endsWith("/consent-log-report") || !c.body.get("channel_id").getAsString().endsWith("_TCL")) continue;
                JsonObject summary = JsonParser.parseString(c.body.get("ad_log").getAsString()).getAsJsonObject();
                for (JsonElement element : summary.getAsJsonArray("steps")) {
                    JsonObject step = element.getAsJsonObject();
                    if ("CMP_API_SUCCESS".equals(step.get("eventType").getAsString())) {
                        assertEquals("{\"response\":{\"code\":200}}", step.get("adLog").getAsString());
                        found = true;
                    }
                }
            }
            assertTrue("CMP response missing from backend trace", found);
        }
    }

    @Test public void channelsAuthorizeSeriallyAndReportOriginalIdsToOneDomain() throws Exception {
        try (Fixture f = new Fixture()) {
            f.session.start(); f.until(() -> f.google.playing);
            assertFalse(f.tcl.playing);
            assertEquals(1, f.count("authorize", null, null));
            f.google.listener.onLoaded(); f.google.listener.onStarted(); f.google.listener.onCompleted();
            f.until(() -> f.tcl.playing);
            assertTrue(f.google.released); assertNull(f.result);
            assertTrue(f.google.config.isHiddenMode()); assertFalse(f.tcl.config.isHiddenMode());
            assertFalse(f.google.sound); assertTrue(f.tcl.sound);
            f.tcl.listener.onLoaded(); f.tcl.listener.onStarted();
            f.tcl.listener.onError(new AdError(AdErrorCode.AD_LOAD_ERROR, AdErrorStage.PLAYER,
                "701", null, "TCL", "701", null));
            f.until(() -> f.result != null && f.count("report", "AD_COMPLETED", null) == 1
                && f.count("report", "AD_ERROR", null) == 1);
            assertEquals(AdResultStatus.COMPLETED, f.result.getStatus());
            assertEquals(Arrays.asList("loaded", "started", "finished"), f.hostEvents);
            assertEquals(2, f.count("authorize", null, null));
            assertEquals(1, f.count("resolve", null, null));
            for (Captured request : f.captured) {
                assertEquals("api.kartna.cc", request.request.url().host());
                if (request.path.endsWith("/report")) {
                    String channel = request.body.get("channel_id").getAsString();
                    assertEquals(channel.endsWith("_TCL") ? "server-tcl" : "server-google",
                        request.body.get("request_id").getAsString());
                }
            }
            Captured tclError = f.find("AD_ERROR");
            JsonObject diagnostics = JsonParser.parseString(tclError.body.get("diagnostic_info").getAsString()).getAsJsonObject();
            assertEquals("701", diagnostics.get("originalCode").getAsString());
            assertEquals("TCL", diagnostics.get("errorSource").getAsString());
            assertEquals(120, f.session.getNextRequestSeconds());
        }
    }

    @Test public void googleTimeoutDoesNotSpendTclsTimeBudget() throws Exception {
        try (Fixture f = new Fixture()) {
            f.session.start(); f.until(() -> f.google.playing);
            assertEquals(60_000L, f.googleTime.delay);
            f.googleTime.fire(); f.until(() -> f.tcl.playing);
            assertEquals(90_000L, f.tclTime.delay);
            f.tcl.listener.onCompleted(); f.until(() -> f.result != null);
            assertEquals(AdResultStatus.COMPLETED, f.result.getStatus());
        }
    }

    @Test public void releaseCancelsActiveRequestsAndNeverAuthorizesTcl() throws Exception {
        try (Fixture f = new Fixture()) {
            f.session.start(); f.until(() -> f.google.playing);
            f.session.release(); f.until(() -> f.result != null);
            f.google.listener.onCompleted(); f.drain();
            assertTrue(f.google.released); assertFalse(f.tcl.playing);
            assertEquals(1, f.count("authorize", null, null));
            assertEquals(AdResultStatus.CANCELLED, f.result.getStatus());
            assertEquals(Arrays.asList("finished"), f.hostEvents);
        }
    }

    static final class Fixture implements AutoCloseable, AdListener {
        final BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        final List<Captured> captured = new CopyOnWriteArrayList<>();
        final List<String> hostEvents = new ArrayList<>();
        final Gson gson = new Gson();
        final FakePlayer google = new FakePlayer(), tcl = new FakePlayer();
        final Clock googleTime = new Clock(), tclTime = new Clock();
        final String base = "https://api.kartna.cc/";
        boolean skipCmp = true;
        final OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            Request r = chain.request(); Buffer buffer = new Buffer(); r.body().writeTo(buffer);
            JsonObject body = JsonParser.parseString(buffer.readUtf8()).getAsJsonObject();
            captured.add(new Captured(r, body));
            String path = r.url().encodedPath();
            boolean isTcl = body.has("channel_id") && body.get("channel_id").getAsString().endsWith("_TCL");
            String response = "{\"code\":100000,\"data\":{}}";
            if (path.endsWith("flow-control")) response = "{\"code\":100000,\"data\":{\"enabled\":true,\"skip_cmp\":" + skipCmp + "}}";
            if (path.endsWith("authorize")) response = "{\"code\":100000,\"data\":{\"authorized\":true,\"request_id\":\"server-"
                + (isTcl ? "tcl" : "google") + "\",\"hidden_mode\":" + !isTcl + ",\"sound_mode\":" + isTcl
                + ",\"next_request_seconds\":" + (isTcl ? 600 : 120) + ",\"ad_callback_timeout_seconds\":" + (isTcl ? 90 : 60) + "}}";
            if (path.endsWith("resolve")) response = "{\"code\":100000,\"data\":{\"ad_tag_url\":\"https://example.test/vast\"}}";
            return new Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(response, MediaType.get("application/json"))).build();
        }).build();
        final FusionAdSession session = new FusionAdSession(new AdRequest.Builder().build(), this, callbacks::add,
            (request, listener) -> channel(false, request, listener),
            (request, listener) -> channel(true, request, listener));
        AdResult result;

        ChannelSession channel(boolean isTcl, AdRequest request, AdListener listener) {
            String channel = "GOOGLE_AD_TV_LOCKSCREEN_HQ002" + (isTcl ? "_TCL" : "");
            FakePlayer player = isTcl ? tcl : google;
            return new AdSessionImpl(channel, null, null, request, listener,
                new RemoteAdConfigClient(DeviceInfo::empty, http, gson, new RemoteAdConfigParser(gson), base, isTcl),
                (container, events) -> { player.listener = events; return player; },
                new FlowControlClient(DeviceInfo::empty, http, gson, base),
                (ctx, id, callback) -> {
                    if (isTcl) callback.onTrace("CMP_API_SUCCESS", "status", "{\"response\":{\"code\":200}}");
                    callback.onAllowed(); return () -> {};
                },
                new Hq008AdReporter(DeviceInfo::empty, http, gson, channel, base, isTcl ? "tcl" : "ima"),
                180_000, isTcl ? tclTime : googleTime, callbacks::add);
        }
        void until(BooleanSupplier condition) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
                Runnable r = callbacks.poll(20, TimeUnit.MILLISECONDS); if (r != null) r.run();
            }
            assertTrue("Flow did not reach expected state", condition.getAsBoolean());
        }
        void drain() { Runnable r; while ((r = callbacks.poll()) != null) r.run(); }
        int count(String suffix, String event, String channel) {
            int count = 0;
            for (Captured c : captured) if (c.path.endsWith("/" + suffix)
                && (event == null || c.body.has("event_type") && event.equals(c.body.get("event_type").getAsString()))
                && (channel == null || channel.equals(c.body.get("channel_id").getAsString()))) count++;
            return count;
        }
        Captured find(String event) {
            for (Captured c : captured) if (c.path.endsWith("/report") && event.equals(c.body.get("event_type").getAsString())) return c;
            throw new AssertionError("Missing event " + event);
        }
        public void onLoaded(AdSession s) { assertSame(session, s); hostEvents.add("loaded"); }
        public void onStarted(AdSession s) { assertSame(session, s); hostEvents.add("started"); }
        public void onFinished(AdSession s, AdResult r) { assertSame(session, s); result = r; hostEvents.add("finished"); }
        public void close() { session.release(); drain(); http.dispatcher().cancelAll(); http.connectionPool().evictAll(); http.dispatcher().executorService().shutdown(); }
    }
    static final class Captured {
        final Request request; final JsonObject body; final String path;
        Captured(Request r, JsonObject b) { request = r; body = b; path = r.url().encodedPath(); }
    }
    static final class Clock implements TimeoutScheduler {
        Runnable action; long delay;
        public long nowMs() { return 0; }
        public Cancellable schedule(Runnable r, long ms) { action = r; delay = ms; return () -> { if (action == r) action = null; }; }
        void fire() { Runnable r = action; action = null; r.run(); }
    }
    static final class FakePlayer implements AdPlayer {
        Listener listener; AdPlaybackConfig config; boolean playing, released, sound;
        public void play(AdPlaybackConfig c, boolean s) { config = c; sound = s; playing = true; }
        public void pause() {} public void resume() {} public void setSoundEnabled(boolean s) { sound = s; }
        public void release() { released = true; }
    }
}
