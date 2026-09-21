package com.smart.android.adsdk.internal;

import static org.junit.Assert.*;
import org.junit.Test;

public class FusionChannelsTest {
    @Test public void hq002SuffixKeepsKartnaForBothChannels() {
        FusionChannels c = new FusionChannels("GOOGLE_AD_TV_LOCKSCREEN_HQ002");
        assertEquals("GOOGLE_AD_TV_LOCKSCREEN_HQ002", c.googleChannel);
        assertEquals("GOOGLE_AD_TV_LOCKSCREEN_HQ002_TCL", c.tclChannel);
        assertEquals("https://api.kartna.cc/", c.apiBaseUrl);
    }
    @Test public void existingCvteMappingAndCaseArePreserved() {
        FusionChannels c = new FusionChannels(" google_ad_tv_cvte ");
        assertEquals("google_ad_tv_cvte_TCL", c.tclChannel);
        assertEquals("https://api.xartek.cc/", c.apiBaseUrl);
    }
    @Test public void regularChannelsKeepDefaultDomain() {
        FusionChannels c = new FusionChannels("CUSTOMER_A");
        assertEquals("CUSTOMER_A_TCL", c.tclChannel);
        assertEquals("https://api.kytira.cc/", c.apiBaseUrl);
    }
    @Test(expected = IllegalArgumentException.class) public void blankChannelIsRejected() {
        new FusionChannels("  ");
    }
}
