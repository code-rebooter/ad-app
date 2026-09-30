package com.smart.android.adsdk.internal;

import static org.junit.Assert.*;
import com.google.gson.Gson;
import com.smart.android.adsdk.AdError;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import okio.Buffer;
import org.junit.Test;

public class TclAuthorizationTest {
    @Test public void tclOnlyAuthorizesAndUsesItsOwnReturnedConfiguration() throws Exception {
        List<Request> requests = new CopyOnWriteArrayList<>();
        OkHttpClient http = http(requests,
            "{\"code\":100000,\"data\":{\"authorized\":true,\"request_id\":\"tcl-server\","
            + "\"hidden_mode\":false,\"sound_mode\":false,\"next_request_seconds\":300,\"ad_callback_timeout_seconds\":60}}");
        Gson gson = new Gson();
        RemoteAdConfigClient client = new RemoteAdConfigClient(
            () -> DeviceInfo.empty(), http, gson, new RemoteAdConfigParser(gson), "https://api.kartna.cc/", true);
        Recorder recorder = new Recorder();
        client.resolve("GOOGLE_AD_TV_LOCKSCREEN_HQ002_TCL", "tcl-local", recorder);
        assertTrue(recorder.done.await(3, TimeUnit.SECONDS));
        assertEquals(1, requests.size());
        Request request = requests.get(0);
        assertEquals("https://api.kartna.cc/api/v2/ad/sdk/authorize", request.url().toString());
        Buffer body = new Buffer(); request.body().writeTo(body);
        String json = body.readUtf8();
        assertTrue(json.contains("GOOGLE_AD_TV_LOCKSCREEN_HQ002_TCL"));
        assertTrue(json.contains("tcl-local"));
        assertTrue(recorder.result.hasAd());
        assertEquals("tcl-server", recorder.result.getConfig().getRequestId());
        assertFalse(recorder.result.getConfig().isHiddenMode());
        assertEquals(Boolean.FALSE, recorder.result.getConfig().getSoundEnabled());
        assertEquals(300, recorder.result.getConfig().getNextRequestSeconds());
        assertEquals(Long.valueOf(60_000), recorder.authorization.getAdCallbackTimeoutMs());
    }

    @Test public void deniedTclRetainsBackendReasonAndNeverCallsGam() throws Exception {
        List<Request> requests = new CopyOnWriteArrayList<>(); Gson gson = new Gson();
        RemoteAdConfigClient client = new RemoteAdConfigClient(() -> DeviceInfo.empty(),
            http(requests, "{\"code\":100000,\"message\":\"channel disabled\",\"data\":{\"authorized\":false,\"request_id\":\"denied-id\"}}"),
            gson, new RemoteAdConfigParser(gson), "https://api.kartna.cc/", true);
        Recorder recorder = new Recorder(); client.resolve("G_TCL", "local", recorder);
        assertTrue(recorder.done.await(3, TimeUnit.SECONDS));
        assertEquals(1, requests.size()); assertFalse(recorder.result.hasAd());
        assertEquals("denied-id", recorder.authorization.getRequestId());
        assertEquals("channel disabled", recorder.result.getError().getMessage());
    }

    private OkHttpClient http(List<Request> requests, String response) {
        return new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.add(chain.request());
            if (!chain.request().url().encodedPath().endsWith("/authorize")) {
                throw new AssertionError("TCL must not request GAM configuration");
            }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(ResponseBody.create(response, MediaType.get("application/json"))).build();
        }).build();
    }

    static class Recorder implements RemoteAdConfigResolver.Callback {
        final CountDownLatch done = new CountDownLatch(1);
        RemoteAdConfigResult result; FlowAuthorizedConfig authorization;
        public void onAuthorizationResponse(FlowAuthorizedConfig config) { authorization = config; }
        public void onResolved(RemoteAdConfigResult result) { this.result = result; done.countDown(); }
        public void onError(AdError error) { done.countDown(); fail(error.toString()); }
    }
}
