package com.smart.android.tclad;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ResolvedAdTest {
    @Test public void fullBackendResponseRetainsEveryOriginalRoutingAndFallbackInput() {
        String json = "{\"code\":100000,\"data\":{\"adsUrl\":\"https://ads.example/tag?a=1&b=2\","
            + "\"beaconingType\":2,\"adType\":7,\"adUnitID\":\"slot-9\","
            + "\"adPlatformCode\":\"vendor-code\",\"guaranteeLink\":\"https://ads.example/fallback\"}}";
        var input = ResolvedAd.fromTclResponse(json);
        var original = input.toOriginalResponse();
        assertEquals(json, input.toJson());
        assertEquals("100000", original.getCode());
        assertEquals("https://ads.example/tag?a=1&b=2", original.getData().getAdsUrl());
        assertEquals(2, original.getData().getBeaconType());
        assertEquals(7, original.getData().getWaterfallType());
        assertEquals("slot-9", original.getData().getAdUnitID());
        assertEquals("vendor-code", original.getData().getAdPlatformCode());
        assertEquals("https://ads.example/fallback", original.getData().getGuaranteeLink());
    }
    @Test public void directTagAdapterSelectsOriginalTclEngineAndDoesNotMutateTheUrl() {
        String tag = "https://ads.example/tag?correlator=&nested=a%26b%3Dc&requestId=old";
        var value = new ResolvedAd.Builder(tag).setAdUnitId("slot").setGuaranteeLink("https://ads.example/back").build().toOriginalResponse().getData();
        assertEquals(tag, value.getAdsUrl());
        assertEquals(2, value.getBeaconType());
        assertEquals("0", value.getAdPlatformCode());
        assertEquals("slot", value.getAdUnitID());
        assertEquals("https://ads.example/back", value.getGuaranteeLink());
    }
}
