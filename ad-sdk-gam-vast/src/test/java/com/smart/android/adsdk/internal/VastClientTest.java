package com.smart.android.adsdk.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

public class VastClientTest {
    @Test
    public void loadsFirstPlayableVastFromVmapAdBreak() throws Exception {
        Map<String, String> responses = new LinkedHashMap<>();
        responses.put("https://ads.test/vmap", "<vmap:VMAP xmlns:vmap=\"http://www.iab.net/videosuite/vmap\">"
            + "<vmap:AdBreak timeOffset=\"start\" breakType=\"linear\">"
            + "<vmap:TrackingEvents>"
            + "<vmap:Tracking event=\"breakStart\"><![CDATA[https://track.test/break-start]]></vmap:Tracking>"
            + "<vmap:Tracking event=\"breakEnd\"><![CDATA[https://track.test/break-end]]></vmap:Tracking>"
            + "</vmap:TrackingEvents>"
            + "<vmap:AdSource><vmap:AdTagURI><![CDATA[https://ads.test/vast]]></vmap:AdTagURI>"
            + "</vmap:AdSource></vmap:AdBreak></vmap:VMAP>");
        responses.put("https://ads.test/vast", "<VAST version=\"3.0\"><Ad><InLine>"
            + "<Impression><![CDATA[https://track.test/impression]]></Impression>"
            + "<Creatives><Creative><Linear><MediaFiles>"
            + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/video.mp4]]></MediaFile>"
            + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad></VAST>");
        RoutingInterceptor interceptor = new RoutingInterceptor(responses);
        VastClient client = new VastClient(new OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build());
        CallbackRecorder callback = new CallbackRecorder();

        client.load("https://ads.test/vmap", 5_000, callback);

        VastAd ad = callback.awaitAdBreak().firstAd();
        assertEquals("https://cdn.test/video.mp4", ad.getMediaUrl());
        assertEquals("https://track.test/impression", ad.getImpressions().get(0));
        assertEquals(
            "https://track.test/break-start",
            callback.adBreak.getBreakStartTrackers().get(0)
        );
        assertEquals(
            "https://track.test/break-end",
            callback.adBreak.getBreakEndTrackers().get(0)
        );
        assertEquals(2, interceptor.requestCount);
    }

    @Test
    public void doesNotPlayMidrollVmapBreakWithoutHostContentTimeline() throws Exception {
        Map<String, String> responses = new LinkedHashMap<>();
        responses.put("https://ads.test/vmap", "<vmap:VMAP xmlns:vmap=\"http://www.iab.net/videosuite/vmap\">"
            + "<vmap:AdBreak timeOffset=\"00:10:00.000\" breakType=\"linear\">"
            + "<vmap:AdSource><vmap:AdTagURI><![CDATA[https://ads.test/midroll]]></vmap:AdTagURI>"
            + "</vmap:AdSource></vmap:AdBreak></vmap:VMAP>");
        RoutingInterceptor interceptor = new RoutingInterceptor(responses);
        VastClient client = new VastClient(new OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build());
        CallbackRecorder callback = new CallbackRecorder();

        client.load("https://ads.test/vmap", 5_000, callback);

        VastLoadException error = callback.awaitError();
        assertEquals(303, error.getVastErrorCode());
        assertEquals(1, interceptor.requestCount);
    }

    @Test
    public void returnsAllAdsFromVastAdPod() throws Exception {
        Map<String, String> responses = new LinkedHashMap<>();
        responses.put("https://ads.test/pod", "<VAST version=\"4.3\">"
            + "<Ad sequence=\"1\"><InLine><Creatives><Creative><Linear><MediaFiles>"
            + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/one.mp4]]></MediaFile>"
            + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad>"
            + "<Ad sequence=\"2\"><InLine><Creatives><Creative><Linear><MediaFiles>"
            + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/two.mp4]]></MediaFile>"
            + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad>"
            + "</VAST>");
        VastClient client = new VastClient(new OkHttpClient.Builder()
            .addInterceptor(new RoutingInterceptor(responses))
            .build());
        CallbackRecorder callback = new CallbackRecorder();

        client.load("https://ads.test/pod", 5_000, callback);

        VastAdBreak adBreak = callback.awaitAdBreak();
        assertEquals(2, adBreak.getAds().size());
        assertEquals("https://cdn.test/one.mp4", adBreak.getAds().get(0).getMediaUrl());
        assertEquals("https://cdn.test/two.mp4", adBreak.getAds().get(1).getMediaUrl());
    }

    @Test
    public void resolvesEachSequencedWrapperInVastAdPod() throws Exception {
        Map<String, String> responses = new LinkedHashMap<>();
        responses.put("https://ads.test/pod", "<VAST version=\"4.3\">"
            + "<Ad sequence=\"2\"><Wrapper>"
            + "<Impression><![CDATA[https://track.test/wrapper-two-impression]]></Impression>"
            + "<VASTAdTagURI><![CDATA[https://ads.test/two]]></VASTAdTagURI>"
            + "</Wrapper></Ad>"
            + "<Ad sequence=\"1\"><Wrapper>"
            + "<Impression><![CDATA[https://track.test/wrapper-one-impression]]></Impression>"
            + "<VASTAdTagURI><![CDATA[https://ads.test/one]]></VASTAdTagURI>"
            + "</Wrapper></Ad>"
            + "</VAST>");
        responses.put("https://ads.test/one", "<VAST version=\"4.3\"><Ad><InLine>"
            + "<Impression><![CDATA[https://track.test/inline-one-impression]]></Impression>"
            + "<Creatives><Creative><Linear><MediaFiles>"
            + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/one.mp4]]></MediaFile>"
            + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad></VAST>");
        responses.put("https://ads.test/two", "<VAST version=\"4.3\"><Ad><InLine>"
            + "<Impression><![CDATA[https://track.test/inline-two-impression]]></Impression>"
            + "<Creatives><Creative><Linear><MediaFiles>"
            + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/two.mp4]]></MediaFile>"
            + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad></VAST>");
        RoutingInterceptor interceptor = new RoutingInterceptor(responses);
        VastClient client = new VastClient(new OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build());
        CallbackRecorder callback = new CallbackRecorder();

        client.load("https://ads.test/pod", 5_000, callback);

        VastAdBreak adBreak = callback.awaitAdBreak();
        assertEquals(2, adBreak.getAds().size());
        assertEquals("https://cdn.test/one.mp4", adBreak.getAds().get(0).getMediaUrl());
        assertEquals("https://track.test/wrapper-one-impression", adBreak.getAds().get(0).getImpressions().get(0));
        assertEquals("https://track.test/inline-one-impression", adBreak.getAds().get(0).getImpressions().get(1));
        assertEquals("https://cdn.test/two.mp4", adBreak.getAds().get(1).getMediaUrl());
        assertEquals("https://track.test/wrapper-two-impression", adBreak.getAds().get(1).getImpressions().get(0));
        assertEquals("https://track.test/inline-two-impression", adBreak.getAds().get(1).getImpressions().get(1));
        assertEquals(3, interceptor.requestCount);
    }

    @Test
    public void loadsWrapperThatAllowsMultipleAdsWhenItResolvesToVastAdPod() throws Exception {
        Map<String, String> responses = new LinkedHashMap<>();
        responses.put("https://ads.test/wrapper", "<VAST version=\"4.3\"><Ad><Wrapper allowMultipleAds=\"true\">"
            + "<Impression><![CDATA[https://track.test/wrapper-impression]]></Impression>"
            + "<VASTAdTagURI><![CDATA[https://ads.test/pod]]></VASTAdTagURI>"
            + "</Wrapper></Ad></VAST>");
        responses.put("https://ads.test/pod", "<VAST version=\"4.3\">"
            + "<Ad sequence=\"1\"><InLine>"
            + "<Impression><![CDATA[https://track.test/inline-one-impression]]></Impression>"
            + "<Creatives><Creative><Linear><MediaFiles>"
            + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/one.mp4]]></MediaFile>"
            + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad>"
            + "<Ad sequence=\"2\"><InLine>"
            + "<Impression><![CDATA[https://track.test/inline-two-impression]]></Impression>"
            + "<Creatives><Creative><Linear><MediaFiles>"
            + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/two.mp4]]></MediaFile>"
            + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad>"
            + "</VAST>");
        VastClient client = new VastClient(new OkHttpClient.Builder()
            .addInterceptor(new RoutingInterceptor(responses))
            .build());
        CallbackRecorder callback = new CallbackRecorder();

        client.load("https://ads.test/wrapper", 5_000, callback);

        VastAdBreak adBreak = callback.awaitAdBreak();
        assertEquals(2, adBreak.getAds().size());
        assertEquals("https://cdn.test/one.mp4", adBreak.getAds().get(0).getMediaUrl());
        assertEquals("https://track.test/wrapper-impression", adBreak.getAds().get(0).getImpressions().get(0));
        assertEquals("https://track.test/inline-one-impression", adBreak.getAds().get(0).getImpressions().get(1));
        assertEquals("https://cdn.test/two.mp4", adBreak.getAds().get(1).getMediaUrl());
        assertEquals("https://track.test/wrapper-impression", adBreak.getAds().get(1).getImpressions().get(0));
        assertEquals("https://track.test/inline-two-impression", adBreak.getAds().get(1).getImpressions().get(1));
    }

    @Test
    public void inheritsGoogleCustomTrackingExtensionsFromWrapper() throws Exception {
        Map<String, String> responses = new LinkedHashMap<>();
        responses.put("https://ads.test/wrapper", "<VAST version=\"4.3\"><Ad><Wrapper>"
            + "<Extensions>"
            + "<Extension type=\"ShowAdTracking\"><CustomTracking>"
            + "<Tracking event=\"show_ad\"><![CDATA[https://track.test/wrapper-show]]></Tracking>"
            + "</CustomTracking></Extension>"
            + "<Extension type=\"video_ad_loaded\"><CustomTracking>"
            + "<Tracking event=\"loaded\"><![CDATA[https://track.test/wrapper-loaded]]></Tracking>"
            + "</CustomTracking></Extension>"
            + "</Extensions>"
            + "<VASTAdTagURI><![CDATA[https://ads.test/inline]]></VASTAdTagURI>"
            + "</Wrapper></Ad></VAST>");
        responses.put("https://ads.test/inline", "<VAST version=\"4.3\"><Ad><InLine>"
            + "<Impression><![CDATA[https://track.test/inline-impression]]></Impression>"
            + "<Creatives><Creative><Linear><TrackingEvents>"
            + "<Tracking event=\"loaded\"><![CDATA[https://track.test/inline-loaded]]></Tracking>"
            + "</TrackingEvents><MediaFiles>"
            + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/video.mp4]]></MediaFile>"
            + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad></VAST>");
        VastClient client = new VastClient(new OkHttpClient.Builder()
            .addInterceptor(new RoutingInterceptor(responses))
            .build());
        CallbackRecorder callback = new CallbackRecorder();

        client.load("https://ads.test/wrapper", 5_000, callback);

        VastAd ad = callback.awaitAdBreak().firstAd();
        assertEquals("https://track.test/wrapper-show", ad.getImpressions().get(0));
        assertEquals("https://track.test/inline-impression", ad.getImpressions().get(1));
        assertEquals("https://track.test/wrapper-loaded", ad.getLoadedTrackers().get(0));
        assertEquals("https://track.test/inline-loaded", ad.getLoadedTrackers().get(1));
    }

    @Test
    public void rejectsWrapperThatResolvesToAdPodToAvoidDuplicatingWrapperTrackers() throws Exception {
        Map<String, String> responses = new LinkedHashMap<>();
        responses.put("https://ads.test/wrapper", "<VAST version=\"4.3\"><Ad><Wrapper>"
            + "<Impression><![CDATA[https://track.test/wrapper-impression]]></Impression>"
            + "<Error><![CDATA[https://track.test/wrapper-error?[ERRORCODE]]]></Error>"
            + "<VASTAdTagURI><![CDATA[https://ads.test/pod]]></VASTAdTagURI>"
            + "</Wrapper></Ad></VAST>");
        responses.put("https://ads.test/pod", "<VAST version=\"4.3\">"
            + "<Ad sequence=\"1\"><InLine><Creatives><Creative><Linear><MediaFiles>"
            + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/one.mp4]]></MediaFile>"
            + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad>"
            + "<Ad sequence=\"2\"><InLine><Creatives><Creative><Linear><MediaFiles>"
            + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/two.mp4]]></MediaFile>"
            + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad>"
            + "</VAST>");
        VastClient client = new VastClient(new OkHttpClient.Builder()
            .addInterceptor(new RoutingInterceptor(responses))
            .build());
        CallbackRecorder callback = new CallbackRecorder();

        client.load("https://ads.test/wrapper", 5_000, callback);

        VastLoadException error = callback.awaitError();
        assertEquals(303, error.getVastErrorCode());
        assertEquals("https://track.test/wrapper-error?[ERRORCODE]", error.getErrorTrackers().get(0));
    }

    @Test
    public void rejectsWrapperWithOnlyErrorTrackerThatResolvesToAdPodWithoutAllowMultipleAds() throws Exception {
        Map<String, String> responses = new LinkedHashMap<>();
        responses.put("https://ads.test/wrapper", "<VAST version=\"4.3\"><Ad><Wrapper>"
            + "<Error><![CDATA[https://track.test/wrapper-error?[ERRORCODE]]]></Error>"
            + "<VASTAdTagURI><![CDATA[https://ads.test/pod]]></VASTAdTagURI>"
            + "</Wrapper></Ad></VAST>");
        responses.put("https://ads.test/pod", "<VAST version=\"4.3\">"
            + "<Ad sequence=\"1\"><InLine><Creatives><Creative><Linear><MediaFiles>"
            + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/one.mp4]]></MediaFile>"
            + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad>"
            + "<Ad sequence=\"2\"><InLine><Creatives><Creative><Linear><MediaFiles>"
            + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/two.mp4]]></MediaFile>"
            + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad>"
            + "</VAST>");
        VastClient client = new VastClient(new OkHttpClient.Builder()
            .addInterceptor(new RoutingInterceptor(responses))
            .build());
        CallbackRecorder callback = new CallbackRecorder();

        client.load("https://ads.test/wrapper", 5_000, callback);

        VastLoadException error = callback.awaitError();
        assertEquals(303, error.getVastErrorCode());
        assertEquals("https://track.test/wrapper-error?[ERRORCODE]", error.getErrorTrackers().get(0));
    }

    @Test
    public void rejectsNestedWrapperAdPodWhenAnyWrapperDisallowsMultipleAds() throws Exception {
        Map<String, String> responses = new LinkedHashMap<>();
        responses.put("https://ads.test/outer", "<VAST version=\"4.3\"><Ad><Wrapper allowMultipleAds=\"true\">"
            + "<Error><![CDATA[https://track.test/outer-error?[ERRORCODE]]]></Error>"
            + "<VASTAdTagURI><![CDATA[https://ads.test/inner]]></VASTAdTagURI>"
            + "</Wrapper></Ad></VAST>");
        responses.put("https://ads.test/inner", "<VAST version=\"4.3\"><Ad><Wrapper>"
            + "<Error><![CDATA[https://track.test/inner-error?[ERRORCODE]]]></Error>"
            + "<VASTAdTagURI><![CDATA[https://ads.test/pod]]></VASTAdTagURI>"
            + "</Wrapper></Ad></VAST>");
        responses.put("https://ads.test/pod", "<VAST version=\"4.3\">"
            + "<Ad sequence=\"1\"><InLine><Creatives><Creative><Linear><MediaFiles>"
            + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/one.mp4]]></MediaFile>"
            + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad>"
            + "<Ad sequence=\"2\"><InLine><Creatives><Creative><Linear><MediaFiles>"
            + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/two.mp4]]></MediaFile>"
            + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad>"
            + "</VAST>");
        VastClient client = new VastClient(new OkHttpClient.Builder()
            .addInterceptor(new RoutingInterceptor(responses))
            .build());
        CallbackRecorder callback = new CallbackRecorder();

        client.load("https://ads.test/outer", 5_000, callback);

        VastLoadException error = callback.awaitError();
        assertEquals(303, error.getVastErrorCode());
        assertEquals("https://track.test/outer-error?[ERRORCODE]", error.getErrorTrackers().get(0));
        assertEquals("https://track.test/inner-error?[ERRORCODE]", error.getErrorTrackers().get(1));
    }

    @Test
    public void loadsVmapInlineVastWrapperAdSource() throws Exception {
        Map<String, String> responses = new LinkedHashMap<>();
        responses.put("https://ads.test/vmap", "<vmap:VMAP xmlns:vmap=\"http://www.iab.net/videosuite/vmap\">"
            + "<vmap:AdBreak timeOffset=\"start\" breakType=\"linear\">"
            + "<vmap:AdSource><vmap:VASTAdData>"
            + "<VAST version=\"4.3\"><Ad><Wrapper>"
            + "<VASTAdTagURI><![CDATA[https://ads.test/inline]]></VASTAdTagURI>"
            + "</Wrapper></Ad></VAST>"
            + "</vmap:VASTAdData></vmap:AdSource></vmap:AdBreak></vmap:VMAP>");
        responses.put("https://ads.test/inline", "<VAST version=\"4.3\"><Ad><InLine>"
            + "<Creatives><Creative><Linear><MediaFiles>"
            + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/video.mp4]]></MediaFile>"
            + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad></VAST>");
        VastClient client = new VastClient(new OkHttpClient.Builder()
            .addInterceptor(new RoutingInterceptor(responses))
            .build());
        CallbackRecorder callback = new CallbackRecorder();

        client.load("https://ads.test/vmap", 5_000, callback);

        assertEquals("https://cdn.test/video.mp4", callback.awaitAdBreak().firstAd().getMediaUrl());
    }

    @Test
    public void preservesInteractiveFallbackMarkerThroughWrapperResolution() throws Exception {
        Map<String, String> responses = new LinkedHashMap<>();
        responses.put("https://ads.test/wrapper", "<VAST version=\"4.3\"><Ad><Wrapper>"
            + "<VASTAdTagURI><![CDATA[https://ads.test/inline]]></VASTAdTagURI>"
            + "<Error><![CDATA[https://track.test/wrapper-error?[ERRORCODE]]]></Error>"
            + "</Wrapper></Ad></VAST>");
        responses.put("https://ads.test/inline", "<VAST version=\"4.3\"><Ad><InLine>"
            + "<Error><![CDATA[https://track.test/inline-error?[ERRORCODE]]]></Error>"
            + "<Creatives><Creative><Linear><MediaFiles>"
            + "<InteractiveCreativeFile type=\"application/javascript\" apiFramework=\"SIMID\">"
            + "<![CDATA[https://cdn.test/simid.js]]></InteractiveCreativeFile>"
            + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/video.mp4]]></MediaFile>"
            + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad></VAST>");
        VastClient client = new VastClient(new OkHttpClient.Builder()
            .addInterceptor(new RoutingInterceptor(responses))
            .build());
        CallbackRecorder callback = new CallbackRecorder();

        client.load("https://ads.test/wrapper", 5_000, callback);

        VastAd ad = callback.awaitAdBreak().firstAd();
        assertTrue(ad.hasUnsupportedInteractiveCreative());
        assertEquals("https://track.test/wrapper-error?[ERRORCODE]", ad.getErrorTrackers().get(0));
        assertEquals("https://track.test/inline-error?[ERRORCODE]", ad.getErrorTrackers().get(1));
    }

    @Test
    public void rejectsAdditionalWrapperWhenOuterWrapperDisallowsFollowing() throws Exception {
        Map<String, String> responses = new LinkedHashMap<>();
        responses.put("https://ads.test/outer", "<VAST version=\"4.3\"><Ad><Wrapper followAdditionalWrappers=\"false\">"
            + "<Error><![CDATA[https://track.test/outer-error?[ERRORCODE]]]></Error>"
            + "<VASTAdTagURI><![CDATA[https://ads.test/inner]]></VASTAdTagURI>"
            + "</Wrapper></Ad></VAST>");
        responses.put("https://ads.test/inner", "<VAST version=\"4.3\"><Ad><Wrapper>"
            + "<Error><![CDATA[https://track.test/inner-error?[ERRORCODE]]]></Error>"
            + "<VASTAdTagURI><![CDATA[https://ads.test/inline]]></VASTAdTagURI>"
            + "</Wrapper></Ad></VAST>");
        VastClient client = new VastClient(new OkHttpClient.Builder()
            .addInterceptor(new RoutingInterceptor(responses))
            .build());
        CallbackRecorder callback = new CallbackRecorder();

        client.load("https://ads.test/outer", 5_000, callback);

        VastLoadException error = callback.awaitError();
        assertEquals(302, error.getVastErrorCode());
        assertEquals("https://track.test/outer-error?[ERRORCODE]", error.getErrorTrackers().get(0));
    }

    @Test
    public void mapsVastRequestFailureToWrapperUriTimeoutErrorCode() throws Exception {
        VastClient client = new VastClient(new OkHttpClient.Builder()
            .addInterceptor(chain -> {
                throw new IOException("network down");
            })
            .build());
        CallbackRecorder callback = new CallbackRecorder();

        client.load("https://ads.test/vast", 5_000, callback);

        assertEquals(301, callback.awaitError().getVastErrorCode());
    }

    @Test
    public void mapsWrapperDepthExceededToTooManyWrapperRedirectsErrorCode() throws Exception {
        Map<String, String> responses = new LinkedHashMap<>();
        responses.put("https://ads.test/wrapper", "<VAST version=\"4.3\"><Ad><Wrapper>"
            + "<Error><![CDATA[https://track.test/wrapper-error?[ERRORCODE]]]></Error>"
            + "<VASTAdTagURI><![CDATA[https://ads.test/wrapper]]></VASTAdTagURI>"
            + "</Wrapper></Ad></VAST>");
        VastClient client = new VastClient(new OkHttpClient.Builder()
            .addInterceptor(new RoutingInterceptor(responses))
            .build());
        CallbackRecorder callback = new CallbackRecorder();

        client.load("https://ads.test/wrapper", 5_000, callback);

        VastLoadException error = callback.awaitError();
        assertEquals(302, error.getVastErrorCode());
        assertEquals("https://track.test/wrapper-error?[ERRORCODE]", error.getErrorTrackers().get(0));
    }

    private static final class CallbackRecorder implements VastClient.Callback {
        private final CountDownLatch latch = new CountDownLatch(1);
        private VastAdBreak adBreak;
        private VastLoadException error;

        @Override
        public void onLoaded(VastAdBreak adBreak) {
            this.adBreak = adBreak;
            latch.countDown();
        }

        @Override
        public void onError(VastLoadException error) {
            this.error = error;
            latch.countDown();
        }

        VastAdBreak awaitAdBreak() throws InterruptedException {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
            if (error != null) {
                throw new AssertionError(error.getMessage(), error);
            }
            return adBreak;
        }

        VastLoadException awaitError() throws InterruptedException {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
            return error;
        }
    }

    private static final class RoutingInterceptor implements Interceptor {
        private final Map<String, String> responses;
        private int requestCount;

        RoutingInterceptor(Map<String, String> responses) {
            this.responses = responses;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            requestCount++;
            String body = responses.get(request.url().toString());
            if (body == null) {
                return response(request, 404, "");
            }
            return response(request, 200, body);
        }

        private Response response(Request request, int code, String body) {
            return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body(ResponseBody.create(body, MediaType.get("application/xml")))
                .build();
        }
    }
}
