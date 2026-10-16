package com.smart.android.adsdk.universal.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.smart.android.adsdk.universal.AdSdk;
import com.smart.android.adsdk.universal.AdError;
import com.smart.android.adsdk.universal.AdErrorCode;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.Test;

public class RemoteAdConfigClientTest {
    private static final String API_BASE_URL =
        "https://example.test/";

    @Test
    public void postsChannelIdAndReturnsParsedConfig() throws Exception {
        RecordingInterceptor interceptor = RecordingInterceptor.respond(
            200,
            "{\"code\":100000,\"data\":{\"authorized\":true,\"ad_tag_url\":\"https://example.test/vast\"}}"
        );
        RemoteAdConfigClient client = createClient(interceptor);
        CallbackRecorder callback = new CallbackRecorder();

        client.resolve("CHANNEL_A", null, callback);

        RemoteAdConfigResult result = callback.awaitResult();
        Request request = interceptor.getRecordedRequest();
        assertNotNull(request);
        assertEquals("/api/v2/ad/sdk/authorize", request.url().encodedPath());
        assertTrue(readRequestBody(request).contains("\"channel_id\":\"CHANNEL_A\""));
        assertTrue(result.hasAd());
    }

    @Test
    public void authorizeKeepsChannelAndMacAndAddsVersionFields() throws Exception {
        RecordingInterceptor interceptor = RecordingInterceptor.respond(
            200,
            "{\"code\":100000,\"data\":{\"authorized\":true,\"ad_tag_url\":\"https://example.test/vast\"}}"
        );
        RemoteAdConfigClient client = createClient(interceptor);
        CallbackRecorder callback = new CallbackRecorder();

        client.resolve("CHANNEL_A", "request-123", callback);

        callback.awaitResult();
        JsonObject body = JsonParser.parseString(readRequestBody(interceptor.getRecordedRequest())).getAsJsonObject();
        assertEquals("request-123", body.get("request_id").getAsString());
        assertEquals("CHANNEL_A", body.get("channel_id").getAsString());
        assertEquals("00:00:00:00:00:00", body.get("mac").getAsString());
        assertEquals("", body.get("androidid").getAsString());
        assertEquals(0L, body.get("ad_version").getAsLong());
        assertEquals("", body.get("ad_version_name").getAsString());
        assertEquals(AdSdk.getSdkVersion(), body.get("sdk_version").getAsString());
    }

    @Test
    public void nonSuccessfulHttpResponseReturnsHttpError() throws Exception {
        RemoteAdConfigClient client = createClient(RecordingInterceptor.respond(503, "unavailable"));
        CallbackRecorder callback = new CallbackRecorder();

        client.resolve("CHANNEL_A", null, callback);

        assertEquals(AdErrorCode.CONFIG_HTTP_ERROR, callback.awaitError().getCode());
    }

    @Test
    public void transportFailureReturnsNetworkError() throws Exception {
        RemoteAdConfigClient client = createClient(RecordingInterceptor.fail(new IOException("offline")));
        CallbackRecorder callback = new CallbackRecorder();

        client.resolve("CHANNEL_A", null, callback);

        assertEquals(AdErrorCode.CONFIG_NETWORK_ERROR, callback.awaitError().getCode());
    }

    @Test
    public void malformedResponseReturnsParseError() throws Exception {
        RemoteAdConfigClient client = createClient(RecordingInterceptor.respond(200, "not-json"));
        CallbackRecorder callback = new CallbackRecorder();

        client.resolve("CHANNEL_A", null, callback);

        assertEquals(AdErrorCode.CONFIG_PARSE_ERROR, callback.awaitError().getCode());
    }

    private RemoteAdConfigClient createClient(Interceptor interceptor) {
        Gson gson = new Gson();
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build();
        return new RemoteAdConfigClient(
            DeviceInfo::empty,
            okHttpClient,
            gson,
            new RemoteAdConfigParser(gson),
            API_BASE_URL
        );
    }

    private String readRequestBody(Request request) throws IOException {
        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        return buffer.readUtf8();
    }

    private static final class CallbackRecorder implements RemoteAdConfigResolver.Callback {
        private final CountDownLatch latch = new CountDownLatch(1);
        private RemoteAdConfigResult result;
        private AdError error;

        @Override
        public void onResolved(RemoteAdConfigResult result) {
            this.result = result;
            this.error = result.getError();
            latch.countDown();
        }

        @Override
        public void onError(AdError error) {
            this.error = error;
            latch.countDown();
        }

        RemoteAdConfigResult awaitResult() throws InterruptedException {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
            return result;
        }

        AdError awaitError() throws InterruptedException {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
            return error;
        }
    }

    private static final class RecordingInterceptor implements Interceptor {
        private final int code;
        private final String body;
        private final IOException failure;
        private volatile Request recordedRequest;

        static RecordingInterceptor respond(int code, String body) {
            return new RecordingInterceptor(code, body, null);
        }

        static RecordingInterceptor fail(IOException failure) {
            return new RecordingInterceptor(0, null, failure);
        }

        private RecordingInterceptor(int code, String body, IOException failure) {
            this.code = code;
            this.body = body;
            this.failure = failure;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            recordedRequest = chain.request();
            if (failure != null) {
                throw failure;
            }
            return new Response.Builder()
                .request(recordedRequest)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body(ResponseBody.create(body, MediaType.get("application/json")))
                .build();
        }

        Request getRecordedRequest() {
            return recordedRequest;
        }
    }
}
