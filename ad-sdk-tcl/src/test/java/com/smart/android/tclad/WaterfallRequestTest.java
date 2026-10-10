package com.smart.android.tclad;

import static org.junit.Assert.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class WaterfallRequestTest {
    static WaterfallRequest template() {
        return new WaterfallRequest.Builder()
            .setAppPackage("com.google.android.adais").setAppVersion("1.0.3")
            .setAppName("MovieArk").setAppBundle("com.tcl.movieark")
            .setAppStoreUrl("https://play.google.com/store/apps/details?id=com.tcl.movieark")
            .setAppKey("fixture-app-key").setSignatureMd5("00000000000000000000000000000000")
            .setDevicePartner("chhkj_1").setApplication(212)
            .setDeviceMake("RockChip").setDeviceModel("X88Pro13.D1010.FE")
            .setUserAgent("Dalvik/2.1.0 (Linux; U; Android 13; X88Pro13.D1010.FE Build/TQ3C.230805.001.B2)")
            .setAdvertisingId("00000000-0000-0000-0000-000000000001", "aaid").build(null);
    }
    static RequestParams capturedHostParams() {
        return new RequestParams.Builder().setAppCat("app").setAppDomain("com.google.android.adais")
            .setChannelName("TCL_AISHANG").setContentLanguage("en").setContentTitle("App Content")
            .setDevice("android").setDeviceLanguage("en-US").setArea("US").build();
    }
    static String resource(String name) throws Exception {
        try (var stream = WaterfallRequestTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }
    static void assertCapturedBody(String body) throws Exception {
        JsonObject expected = JsonParser.parseString(resource("tcl-waterfall-request.json")).getAsJsonObject();
        JsonObject actual = JsonParser.parseString(body).getAsJsonObject();
        assertEquals(58, actual.size());
        for (String field : new String[]{"cb", "timestamp"}) {
            assertTrue(actual.getAsJsonPrimitive(field).isNumber());
            assertTrue(Math.abs(System.currentTimeMillis() - actual.get(field).getAsLong()) < 10000);
            expected.add(field, actual.get(field));
        }
        assertEquals(expected, actual);
        for (String field : new String[]{"application", "playerWidth", "playerHeight", "minAdDuration",
            "maxAdDuration", "podDuration", "adType", "placement", "skip", "ap", "flags",
            "contentLivestream", "ProductionQuality", "podSequence", "dnt"})
            assertTrue(field, actual.getAsJsonPrimitive(field).isNumber());
    }
    @Test public void all58CapturedFieldsMatchIncludingEmptyValuesAndNumberTypes() throws Exception {
        assertCapturedBody(template().withParams(capturedHostParams()).toJson());
    }
    @Test public void hostOverridesOnlyNonemptyDeviceAndStoreFieldsAndKeepsSnapshots() {
        RequestParams.Builder host = new RequestParams.Builder().setDeviceMake("OEM")
            .setDeviceModel("TV").setAppStoreUrl("https://store.example/app")
            .setContentId("film-1").setContentGenre("Drama").setContentRating("PG")
            .setContentNetwork("Network").setContentLivestream(1).setProductionQuality(2)
            .setPodSequence(3).setDnt(1).setCoppa("0").setGdpr("1").setGdprConsent("consent");
        RequestParams first = host.build();
        host.setDeviceMake("Later").setGdprConsent(null);
        WaterfallRequest request = template().withParams(first);
        assertEquals("OEM", request.getValues().get("deviceMake"));
        assertEquals("TV", request.getValues().get("deviceModel"));
        assertEquals("https://store.example/app", request.getValues().get("appStoreUrl"));
        assertEquals(2, request.getValues().get("ProductionQuality"));
        assertEquals("consent", first.getValues().get("gdprConsent"));
        assertEquals("RockChip", template().withParams(new RequestParams.Builder().build()).getValues().get("deviceMake"));
        assertEquals("", template().withParams(new RequestParams.Builder().build()).getValues().get("gdprConsent"));
        try { request.getValues().put("did", "replacement"); fail(); } catch (UnsupportedOperationException expected) { }
    }
    @Test public void missingAdvertisingIdIsExplicitlyEmptyAndNeverReplacedByAnotherDeviceId() {
        var values = new WaterfallRequest.Builder().build(null).getValues();
        assertEquals("", values.get("did"));
        assertEquals("", values.get("IFAType"));
        assertEquals(0, values.get("playerWidth"));
        assertEquals(0, values.get("playerHeight"));
    }
}
