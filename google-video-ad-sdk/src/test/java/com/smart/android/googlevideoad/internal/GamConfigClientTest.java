package com.smart.android.googlevideoad.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.smart.android.googlevideoad.AdError;
import com.smart.android.googlevideoad.AdErrorCode;
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

public class GamConfigClientTest {
    private static final String RESOLVE_URL =
        "https://example.test/api/v2/ad/google-gam/resolve";

    @Test
    public void postsChannelIdAndReturnsParsedConfig() throws Exception {
        RecordingInterceptor interceptor = RecordingInterceptor.respond(
            200,
            "{\"code\":100000,\"data\":{\"ad_tag_url\":\"https://example.test/vast\"}}"
        );
        GamConfigClient client = createClient(interceptor);
        CallbackRecorder callback = new CallbackRecorder();

        client.resolve("CHANNEL_A", callback);

        GamResolveResult result = callback.awaitResult();
        Request request = interceptor.getRecordedRequest();
        assertNotNull(request);
        assertEquals("/api/v2/ad/google-gam/resolve", request.url().encodedPath());
        assertTrue(readRequestBody(request).contains("\"channel_id\":\"CHANNEL_A\""));
        assertTrue(result.hasAd());
    }

    @Test
    public void nonSuccessfulHttpResponseReturnsHttpError() throws Exception {
        GamConfigClient client = createClient(RecordingInterceptor.respond(503, "unavailable"));
        CallbackRecorder callback = new CallbackRecorder();

        client.resolve("CHANNEL_A", callback);

        assertEquals(AdErrorCode.CONFIG_HTTP_ERROR, callback.awaitError().getCode());
    }

    @Test
    public void transportFailureReturnsNetworkError() throws Exception {
        GamConfigClient client = createClient(RecordingInterceptor.fail(new IOException("offline")));
        CallbackRecorder callback = new CallbackRecorder();

        client.resolve("CHANNEL_A", callback);

        assertEquals(AdErrorCode.CONFIG_NETWORK_ERROR, callback.awaitError().getCode());
    }

    @Test
    public void malformedResponseReturnsParseError() throws Exception {
        GamConfigClient client = createClient(RecordingInterceptor.respond(200, "not-json"));
        CallbackRecorder callback = new CallbackRecorder();

        client.resolve("CHANNEL_A", callback);

        assertEquals(AdErrorCode.CONFIG_PARSE_ERROR, callback.awaitError().getCode());
    }

    private GamConfigClient createClient(Interceptor interceptor) {
        Gson gson = new Gson();
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build();
        return new GamConfigClient(
            okHttpClient,
            gson,
            new GamConfigParser(gson),
            RESOLVE_URL
        );
    }

    private String readRequestBody(Request request) throws IOException {
        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        return buffer.readUtf8();
    }

    private static final class CallbackRecorder implements GamConfigResolver.Callback {
        private final CountDownLatch latch = new CountDownLatch(1);
        private GamResolveResult result;
        private AdError error;

        @Override
        public void onResolved(GamResolveResult result) {
            this.result = result;
            latch.countDown();
        }

        @Override
        public void onError(AdError error) {
            this.error = error;
            latch.countDown();
        }

        GamResolveResult awaitResult() throws InterruptedException {
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
