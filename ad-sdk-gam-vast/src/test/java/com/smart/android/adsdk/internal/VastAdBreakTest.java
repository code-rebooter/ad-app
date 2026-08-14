package com.smart.android.adsdk.internal;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VastAdBreakTest {
    @Test
    public void parsesAdPodInSequenceOrder() throws Exception {
        VastParsedResponse response = new VastParser().parse(
            "<VAST version=\"4.3\">"
                + "<Ad sequence=\"2\"><InLine><Creatives><Creative><Linear>"
                + "<MediaFiles><MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/two.mp4]]></MediaFile></MediaFiles>"
                + "</Linear></Creative></Creatives></InLine></Ad>"
                + "<Ad sequence=\"1\"><InLine><Creatives><Creative><Linear>"
                + "<MediaFiles><MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/one.mp4]]></MediaFile></MediaFiles>"
                + "</Linear></Creative></Creatives></InLine></Ad>"
                + "</VAST>"
        );

        assertEquals(2, response.getAdBreak().getAds().size());
        assertEquals("https://cdn.test/one.mp4", response.getAdBreak().getAds().get(0).getMediaUrl());
        assertEquals("https://cdn.test/two.mp4", response.getAdBreak().getAds().get(1).getMediaUrl());
    }

    @Test
    public void keepsOnlyFirstStandaloneAdWhenAdsDoNotDeclarePodSequence() throws Exception {
        VastParsedResponse response = new VastParser().parse(
            "<VAST version=\"4.3\">"
                + "<Ad><InLine><Creatives><Creative><Linear>"
                + "<MediaFiles><MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/one.mp4]]></MediaFile></MediaFiles>"
                + "</Linear></Creative></Creatives></InLine></Ad>"
                + "<Ad><InLine><Creatives><Creative><Linear>"
                + "<MediaFiles><MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/two.mp4]]></MediaFile></MediaFiles>"
                + "</Linear></Creative></Creatives></InLine></Ad>"
                + "</VAST>"
        );

        assertEquals(1, response.getAdBreak().getAds().size());
        assertEquals("https://cdn.test/one.mp4", response.getAdBreak().firstAd().getMediaUrl());
    }
}
