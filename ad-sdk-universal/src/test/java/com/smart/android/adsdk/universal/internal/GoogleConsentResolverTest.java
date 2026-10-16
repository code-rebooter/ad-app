package com.smart.android.adsdk.universal.internal;

import static org.junit.Assert.*;
import com.google.gson.*;
import com.smart.android.adsdk.universal.AdError;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.Test;

public class GoogleConsentResolverTest {
    @Test public void noUmpActionIsReportedAsExecutedOrAllowed() throws Exception {
        for(String action:Arrays.asList("ACCEPT_ALL","SAVE_SETTINGS","REJECT","SKIP_ALREADY_DECIDED","unknown")) {
            try(MockWebServer server=new MockWebServer()) {
                server.enqueue(new MockResponse().setBody("{\"code\":100000,\"data\":{\"consent_action\":\""+action+"\"}}"));
                Recorder result=new Recorder(); resolver(server).resolve(null,"customer-google-101",result);
                assertTrue(result.done.await(3,TimeUnit.SECONDS)); assertNull(result.error);
                assertFalse("No real consent state exists: "+action,result.allowed);
                assertEquals("SKIP_ALREADY_DECIDED".equals(action) ? "CONSENT_STATE_UNAVAILABLE" : "unknown".equals(action) ? "DECISION_UNAVAILABLE" : "UMP_UNAVAILABLE",result.reason);
                assertEquals(1,server.getRequestCount());
            }
        }
    }
    @Test public void serverDeferIsReportedWithoutPretendingToExecuteConsent() throws Exception {
        try(MockWebServer server=new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"code\":100000,\"data\":{\"consent_action\":\" maybe_later \"}}"));
            server.enqueue(new MockResponse().setBody("{\"code\":100000}"));
            Recorder result=new Recorder();resolver(server).resolve(null,"customer-google-101",result);
            assertTrue(result.done.await(3,TimeUnit.SECONDS));assertTrue(result.allowed);assertNull(result.error);
            assertEquals("/api/v2/ad/consent-popup",server.takeRequest().getPath());
            RecordedRequest report=server.takeRequest();assertEquals("/api/v2/ad/consent-report",report.getPath());
            JsonObject body=JsonParser.parseString(report.getBody().readUtf8()).getAsJsonObject();
            assertEquals("MAYBE_LATER",body.get("consent_action").getAsString());assertEquals("customer-google-101",body.get("channel_id").getAsString());assertFalse(body.has("provider"));assertFalse(body.has("sdk_key"));
        }
    }
    private static GoogleConsentResolver resolver(MockWebServer server) {
        String base=server.url("/").toString();OkHttpClient http=new OkHttpClient();
        return new GoogleConsentResolver(TclCmpBackendClient.forGoogle(http,new Gson(),base,DeviceInfo::empty,()->23));
    }
    private static class Recorder implements ConsentResolver.Callback {
        final CountDownLatch done=new CountDownLatch(1); boolean allowed; String reason; AdError error;
        @Override public void onAllowed() { allowed=true;done.countDown(); }
        @Override public void onBlocked(String value) { reason=value;done.countDown(); }
        @Override public void onError(AdError value) { error=value;done.countDown(); }
    }
}
