package com.smart.android.adsdk.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

public class VastTrackerTest {
    @Test
    public void firesTrackingUrlsWithSupportedMacrosExpanded() throws Exception {
        RecordingInterceptor interceptor = new RecordingInterceptor(2);
        VastTracker tracker = new VastTracker(new OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build());
        tracker.setMediaType("application/dash+xml");

        tracker.fire(Arrays.asList(
            "https://track.test/start?cb=[CACHEBUSTING]&cb2=%5BCACHEBUSTER%5D&mt=%5BAD_MT%5D",
            "https://track.test/complete?ts=[TIMESTAMP]&ts2=%5BTIMESTAMP%5D",
            " "
        ));

        assertTrue(interceptor.await());
        assertEquals(2, interceptor.count);
        okhttp3.HttpUrl startUrl = interceptor.findPath("/start");
        okhttp3.HttpUrl completeUrl = interceptor.findPath("/complete");
        assertEquals("track.test", startUrl.host());
        assertTrue(startUrl.queryParameter("cb").matches("\\d{8,}"));
        assertTrue(startUrl.queryParameter("cb2").matches("\\d{8,}"));
        assertEquals("application/dash+xml", startUrl.queryParameter("mt"));
        assertEquals("track.test", completeUrl.host());
        String timestamp = completeUrl.queryParameter("ts");
        String encodedTimestamp = completeUrl.queryParameter("ts2");
        assertTrue(timestamp != null);
        assertTrue(encodedTimestamp != null);
        assertTrue(timestamp.matches(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"
        ) || timestamp.matches(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{4}"
        ));
        assertTrue(encodedTimestamp.matches(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"
        ));
    }

    @Test
    public void firesErrorTrackingUrlsWithErrorCodeMacroExpanded() throws Exception {
        RecordingInterceptor interceptor = new RecordingInterceptor(1);
        VastTracker tracker = new VastTracker(new OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build());

        tracker.fireError(Collections.singletonList(
            "https://track.test/error?code=[ERRORCODE]&cb=%%CACHEBUSTING%%"
        ), 405);

        assertTrue(interceptor.await());
        assertEquals(1, interceptor.count);
        assertEquals("405", interceptor.urls.get(0).queryParameter("code"));
        assertTrue(interceptor.urls.get(0).queryParameter("cb").matches("\\d{8,}"));
    }

    @Test
    public void expandsReasonAdPlayheadAndUnknownMacrosForTrackingCompatibility() throws Exception {
        RecordingInterceptor interceptor = new RecordingInterceptor(1);
        VastTracker tracker = new VastTracker(new OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build());
        tracker.setAdPlayheadMs(65_432L);

        tracker.fireVerificationNotExecuted(Collections.singletonList(
            "https://track.test/verification?reason=[REASON]&playhead=[ADPLAYHEAD]"
                + "&encoded=%5BCONTENTPLAYHEAD%5D&unknown=[UNSUPPORTED_MACRO]"
        ), "omid_missing");

        assertTrue(interceptor.await());
        okhttp3.HttpUrl url = interceptor.urls.get(0);
        assertEquals("omid_missing", url.queryParameter("reason"));
        assertEquals("00:01:05.432", url.queryParameter("playhead"));
        assertEquals("00:01:05.432", url.queryParameter("encoded"));
        assertEquals("-1", url.queryParameter("unknown"));
    }

    @Test
    public void expandsLowercaseUnknownMacrosUsedByGoogleUrls() throws Exception {
        RecordingInterceptor interceptor = new RecordingInterceptor(1);
        VastTracker tracker = new VastTracker(new OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build());

        tracker.fire(Collections.singletonList(
            "https://track.test/google?plain=[gw_fbsaeid]&encoded=%5Bgw_fbsaeid%5D"
        ));

        assertTrue(interceptor.await());
        okhttp3.HttpUrl url = interceptor.urls.get(0);
        assertEquals("-1", url.queryParameter("plain"));
        assertEquals("-1", url.queryParameter("encoded"));
    }

    @Test
    public void ignoresNullTrackingLists() {
        VastTracker tracker = new VastTracker(new OkHttpClient());

        tracker.fire(null);
        tracker.fire(Collections.emptyList());
    }

    private static final class RecordingInterceptor implements Interceptor {
        private final CountDownLatch latch;
        private final java.util.List<okhttp3.HttpUrl> urls = new java.util.ArrayList<>();
        private int count;

        RecordingInterceptor(int expectedCount) {
            latch = new CountDownLatch(expectedCount);
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            urls.add(chain.request().url());
            count++;
            latch.countDown();
            return new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(204)
                .message("No Content")
                .body(ResponseBody.create("", MediaType.get("text/plain")))
                .build();
        }

        boolean await() throws InterruptedException {
            return latch.await(2, TimeUnit.SECONDS);
        }

        okhttp3.HttpUrl findPath(String path) {
            for (okhttp3.HttpUrl url : urls) {
                if (path.equals(url.encodedPath())) {
                    return url;
                }
            }
            throw new AssertionError("Missing tracking path: " + path + ", urls=" + urls);
        }
    }
}
