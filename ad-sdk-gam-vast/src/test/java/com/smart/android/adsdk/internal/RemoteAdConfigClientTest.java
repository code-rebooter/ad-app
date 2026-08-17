package com.smart.android.adsdk.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.smart.android.adsdk.AdError;
import com.smart.android.adsdk.AdErrorCode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
    private static final String API_BASE_URL = "https://example.test/";

    @Test
    public void requestsFlowAuthorizeThenGamConfig() throws Exception {
        RecordingInterceptor interceptor = RecordingInterceptor.standardSuccess();
        RemoteAdConfigClient client = createClient(interceptor);
        CallbackRecorder callback = new CallbackRecorder();

        client.resolve("CHANNEL_A", null, callback);

        RemoteAdConfigResult result = callback.awaitResult();
        List<Request> requests = interceptor.getRecordedRequests();
        assertEquals("/api/v2/ad/sdk/flow-control", requests.get(0).url().encodedPath());
        assertEquals("/api/v2/ad/sdk/authorize", requests.get(1).url().encodedPath());
        assertEquals("/api/v2/ad/google-gam/resolve", requests.get(2).url().encodedPath());
        assertTrue(readRequestBody(requests.get(0)).contains("\"channel_id\":\"CHANNEL_A\""));
        assertTrue(readRequestBody(requests.get(1)).contains("\"channel_id\":\"CHANNEL_A\""));
        String gamRequestBody = readRequestBody(requests.get(2));
        assertTrue(gamRequestBody.contains("\"channel_id\":\"CHANNEL_A\""));
        assertTrue(gamRequestBody.contains("\"mac\":\"AA:BB:CC:DD:EE:FF\""));
        assertFalse(gamRequestBody.contains("\"request_id\""));
        assertFalse(gamRequestBody.contains("\"package_name\""));
        assertFalse(gamRequestBody.contains("\"version_code\""));
        assertFalse(gamRequestBody.contains("\"slot_id\""));
        assertTrue(result.hasAd());
        assertEquals("server-request-123", result.getConfig().getRequestId());
        assertTrue(result.getConfig().isHiddenMode());
    }

    @Test
    public void postsOptionalRequestIdToAuthorizeWhenProvided() throws Exception {
        RecordingInterceptor interceptor = RecordingInterceptor.standardSuccess();
        RemoteAdConfigClient client = createClient(interceptor);
        CallbackRecorder callback = new CallbackRecorder();

        client.resolve("CHANNEL_A", "request-123", callback);

        callback.awaitResult();
        assertTrue(readRequestBody(interceptor.getRecordedRequests().get(1)).contains("\"request_id\":\"request-123\""));
    }

    @Test
    public void disabledFlowControlSkipsBeforeAuthorize() throws Exception {
        RecordingInterceptor interceptor = RecordingInterceptor.flowDisabled();
        RemoteAdConfigClient client = createClient(interceptor);
        CallbackRecorder callback = new CallbackRecorder();

        client.resolve("CHANNEL_A", null, callback);

        RemoteAdConfigResult result = callback.awaitResult();
        assertEquals("FLOW_CONTROL_DISABLED", result.getSkipReason());
        assertEquals(1, interceptor.getRecordedRequests().size());
    }

    @Test
    public void deniedAuthorizeSkipsBeforeGamConfig() throws Exception {
        RecordingInterceptor interceptor = RecordingInterceptor.authorizeDenied();
        RemoteAdConfigClient client = createClient(interceptor);
        CallbackRecorder callback = new CallbackRecorder();

        client.resolve("CHANNEL_A", null, callback);

        RemoteAdConfigResult result = callback.awaitResult();
        assertEquals("AUTHORIZE_DENIED", result.getSkipReason());
        assertEquals(2, interceptor.getRecordedRequests().size());
    }

    @Test
    public void nonSuccessfulGamHttpResponseReturnsHttpError() throws Exception {
        RemoteAdConfigClient client = createClient(RecordingInterceptor.gamRespond(503, "unavailable"));
        CallbackRecorder callback = new CallbackRecorder();

        client.resolve("CHANNEL_A", null, callback);

        assertEquals(AdErrorCode.CONFIG_HTTP_ERROR, callback.awaitError().getCode());
    }

    @Test
    public void gamTransportFailureReturnsNetworkError() throws Exception {
        RemoteAdConfigClient client = createClient(RecordingInterceptor.gamFail(new IOException("offline")));
        CallbackRecorder callback = new CallbackRecorder();

        client.resolve("CHANNEL_A", null, callback);

        assertEquals(AdErrorCode.CONFIG_NETWORK_ERROR, callback.awaitError().getCode());
    }

    @Test
    public void malformedGamResponseReturnsParseError() throws Exception {
        RemoteAdConfigClient client = createClient(RecordingInterceptor.gamRespond(200, "not-json"));
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
            RemoteAdConfigClientTest::fakeDeviceInfo,
            okHttpClient,
            gson,
            new RemoteAdConfigParser(gson),
            API_BASE_URL
        );
    }

    private static DeviceInfo fakeDeviceInfo() {
        return new DeviceInfo(
            "com.example.test",
            "1.0",
            7L,
            "android-id",
            "00000000-0000-4000-8000-000000000001",
            "AA:BB:CC:DD:EE:FF",
            "192.168.0.2",
            "JUnit",
            "Make",
            "Model",
            "13",
            "zh-CN",
            1920,
            1080,
            1920,
            1080
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
        private final List<Request> recordedRequests = new ArrayList<>();
        private final int gamCode;
        private final String gamBody;
        private final IOException gamFailure;
        private final boolean flowEnabled;
        private final boolean authorized;

        static RecordingInterceptor standardSuccess() {
            return new RecordingInterceptor(200, gamBody(), null, true, true);
        }

        static RecordingInterceptor flowDisabled() {
            return new RecordingInterceptor(200, gamBody(), null, false, true);
        }

        static RecordingInterceptor authorizeDenied() {
            return new RecordingInterceptor(200, gamBody(), null, true, false);
        }

        static RecordingInterceptor gamRespond(int code, String body) {
            return new RecordingInterceptor(code, body, null, true, true);
        }

        static RecordingInterceptor gamFail(IOException failure) {
            return new RecordingInterceptor(0, null, failure, true, true);
        }

        private RecordingInterceptor(
            int gamCode,
            String gamBody,
            IOException gamFailure,
            boolean flowEnabled,
            boolean authorized
        ) {
            this.gamCode = gamCode;
            this.gamBody = gamBody;
            this.gamFailure = gamFailure;
            this.flowEnabled = flowEnabled;
            this.authorized = authorized;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request recordedRequest = chain.request();
            recordedRequests.add(recordedRequest);
            String path = recordedRequest.url().encodedPath();
            if (path.endsWith("/flow-control")) {
                return response(recordedRequest, 200, "{\"code\":100000,\"data\":{\"enabled\":" + flowEnabled + "}}");
            }
            if (path.endsWith("/authorize")) {
                return response(
                    recordedRequest,
                    200,
                    "{\"code\":100000,\"data\":{\"authorized\":" + authorized
                        + ",\"hidden_mode\":true,\"sound_mode\":false,"
                        + "\"next_request_seconds\":60,"
                        + "\"request_id\":\"server-request-123\"}}"
                );
            }
            if (gamFailure != null) {
                throw gamFailure;
            }
            return response(recordedRequest, gamCode, gamBody);
        }

        List<Request> getRecordedRequests() {
            return recordedRequests;
        }

        private static String gamBody() {
            return "{\"code\":100000,\"data\":{\"ad_tag_url\":\"https://example.test/vast\"}}";
        }

        private Response response(Request request, int code, String body) {
            return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body(ResponseBody.create(body, MediaType.get("application/json")))
                .build();
        }
    }
}
