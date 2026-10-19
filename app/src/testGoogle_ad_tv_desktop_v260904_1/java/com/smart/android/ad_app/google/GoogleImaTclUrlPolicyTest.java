package com.smart.android.ad_app.google;

import org.junit.Test;
import static org.junit.Assert.*;

public class GoogleImaTclUrlPolicyTest {
    @Test public void removesUnimplementedFrameworksEvenIfBackendSuppliedThem() {
        String original="https://pubads.g.doubleclick.net/gampad/ads?iu=/mine&sdk_apis=7,8&omid_p=old&cust_params=a%2Bb%26c";
        String ima=original+"&sdkv=ima&js=ima-android&dc_omid_p=Google1&atv_bfp=example&unknown_future_sdk_field=1&ms=abc%2Bdef&uach=real%3D";
        String result=GoogleImaTclUrlPolicy.apply(original,ima,true,true,"my.app");
        assertFalse(result.contains("sdk_apis=")); assertFalse(result.contains("omid_p="));
        assertFalse(result.contains("sdkv=")); assertFalse(result.contains("js="));
        assertFalse(result.contains("atv_")); assertFalse(result.contains("unknown_future"));
        assertTrue(result.contains("cust_params=a%2Bb%26c"));
        assertTrue(result.contains("ms=abc%2Bdef")); assertTrue(result.contains("uach=real%3D"));
    }
    @Test public void outputAndPlaybackFollowTclRequestInsteadOfImaCapabilities() {
        String result=GoogleImaTclUrlPolicy.apply("https://a/ads?output=vast",
            "https://a/ads?output=xml_vast4&vpa=click&vpmute=0&ctv=1&msid=old",true,false,"actual.app");
        assertTrue(result.contains("output=vast")); assertTrue(result.contains("vpa=auto"));
        assertTrue(result.contains("vpmute=1")); assertFalse(result.contains("ctv="));
        assertTrue(result.contains("msid=actual.app")); assertFalse(result.contains("old"));
    }
    @Test public void droppedIdsAndSignalsCannotReturnFromBackendFallback() {
        String original="https://a/ads?rdid=old&idtype=aaid&is_lat=0&ms=old&uach=old&gdpr_consent=real&iu=/mine";
        String result=GoogleImaTclUrlPolicy.apply(original,"https://a/ads?iu=/mine",false,true,"app");
        for (String key:new String[]{"rdid=","idtype=","is_lat=","ms=","uach="}) assertFalse(result.contains(key));
        assertTrue(result.contains("gdpr_consent=real"));
    }
    @Test public void limitedTrackingDoesNotLeakAnAdvertisingId() {
        String result=GoogleImaTclUrlPolicy.apply("https://a/ads?rdid=old",
            "https://a/ads?rdid=collected&is_lat=1&correlator=123",false,true,"app");
        assertFalse(result.contains("rdid=")); assertTrue(result.contains("is_lat=1"));
        assertTrue(result.contains("correlator=123"));
    }
}
