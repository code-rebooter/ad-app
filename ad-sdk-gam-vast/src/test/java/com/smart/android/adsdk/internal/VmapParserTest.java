package com.smart.android.adsdk.internal;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VmapParserTest {
    @Test
    public void parsesPreMidPostRollAdBreaksWithInlineAndReferencedTags() throws Exception {
        VmapAdSchedule schedule = new VmapParser().parse(
            "<vmap:VMAP xmlns:vmap=\"http://www.iab.net/videosuite/vmap\" version=\"1.0\">"
                + "<vmap:AdBreak timeOffset=\"start\" breakType=\"linear\" breakId=\"pre\">"
                + "<vmap:AdSource id=\"pre-source\" allowMultipleAds=\"false\">"
                + "<vmap:AdTagURI templateType=\"vast3\"><![CDATA[https://ads.test/pre-vast]]></vmap:AdTagURI>"
                + "</vmap:AdSource>"
                + "</vmap:AdBreak>"
                + "<vmap:AdBreak timeOffset=\"00:10:00.000\" breakType=\"linear\" breakId=\"mid\">"
                + "<vmap:AdSource id=\"mid-source\"><vmap:VASTData>"
                + "<VAST version=\"3.0\"><Ad><InLine><Creatives><Creative><Linear>"
                + "<MediaFiles><MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/mid.mp4]]></MediaFile></MediaFiles>"
                + "</Linear></Creative></Creatives></InLine></Ad></VAST>"
                + "</vmap:VASTData></vmap:AdSource>"
                + "</vmap:AdBreak>"
                + "<vmap:AdBreak timeOffset=\"end\" breakType=\"linear\" breakId=\"post\">"
                + "<vmap:TrackingEvents><vmap:Tracking event=\"breakStart\">"
                + "<![CDATA[https://track.test/break-start]]></vmap:Tracking></vmap:TrackingEvents>"
                + "</vmap:AdBreak>"
                + "</vmap:VMAP>"
        );

        assertEquals(3, schedule.getBreaks().size());
        assertEquals(VmapTimeOffset.start(), schedule.getBreaks().get(0).getTimeOffset());
        assertEquals("https://ads.test/pre-vast", schedule.getBreaks().get(0).getAdTagUrl());
        assertEquals(VmapTimeOffset.absolute(600_000L), schedule.getBreaks().get(1).getTimeOffset());
        assertEquals("https://cdn.test/mid.mp4", schedule.getBreaks().get(1).getInlineVast().getAd().getMediaUrl());
        assertEquals(VmapTimeOffset.end(), schedule.getBreaks().get(2).getTimeOffset());
        assertEquals(
            "https://track.test/break-start",
            schedule.getBreaks().get(2).getBreakStartTrackers().get(0)
        );
    }

    @Test
    public void ignoresAdTagUriAndVastDataOutsideAdSource() throws Exception {
        VmapAdSchedule schedule = new VmapParser().parse(
            "<vmap:VMAP xmlns:vmap=\"http://www.iab.net/videosuite/vmap\" version=\"1.0\">"
                + "<vmap:AdBreak timeOffset=\"start\" breakType=\"linear\" breakId=\"pre\">"
                + "<vmap:TrackingEvents>"
                + "<vmap:Tracking event=\"breakStart\"><![CDATA[https://track.test/break-start]]></vmap:Tracking>"
                + "</vmap:TrackingEvents>"
                + "<vmap:Extensions>"
                + "<vmap:Extension>"
                + "<vmap:AdTagURI><![CDATA[https://ads.test/not-an-ad-source]]></vmap:AdTagURI>"
                + "</vmap:Extension>"
                + "</vmap:Extensions>"
                + "</vmap:AdBreak>"
                + "</vmap:VMAP>"
        );

        assertEquals(1, schedule.getBreaks().size());
        assertEquals(null, schedule.getBreaks().get(0).getAdTagUrl());
        assertEquals(null, schedule.getBreaks().get(0).getInlineVast());
        assertEquals(
            "https://track.test/break-start",
            schedule.getBreaks().get(0).getBreakStartTrackers().get(0)
        );
    }

    @Test
    public void parsesInlineVastAdDataInsideAdSource() throws Exception {
        VmapAdSchedule schedule = new VmapParser().parse(
            "<vmap:VMAP xmlns:vmap=\"http://www.iab.net/videosuite/vmap\" version=\"1.0\">"
                + "<vmap:AdBreak timeOffset=\"start\" breakType=\"linear\" breakId=\"pre\">"
                + "<vmap:AdSource><vmap:VASTAdData>"
                + "<VAST version=\"3.0\"><Ad><InLine><Creatives><Creative><Linear>"
                + "<MediaFiles><MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/pre.mp4]]></MediaFile></MediaFiles>"
                + "</Linear></Creative></Creatives></InLine></Ad></VAST>"
                + "</vmap:VASTAdData></vmap:AdSource>"
                + "</vmap:AdBreak>"
                + "</vmap:VMAP>"
        );

        assertEquals("https://cdn.test/pre.mp4", schedule.getBreaks().get(0).getInlineVast().getAd().getMediaUrl());
    }

    @Test
    public void keepsUnknownTimeOffsetsOutOfPrerollPlayback() throws Exception {
        VmapAdSchedule schedule = new VmapParser().parse(
            "<vmap:VMAP xmlns:vmap=\"http://www.iab.net/videosuite/vmap\" version=\"1.0\">"
                + "<vmap:AdBreak timeOffset=\"#1\" breakType=\"linear\" breakId=\"mid\">"
                + "<vmap:AdSource><vmap:AdTagURI><![CDATA[https://ads.test/mid]]></vmap:AdTagURI></vmap:AdSource>"
                + "</vmap:AdBreak>"
                + "</vmap:VMAP>"
        );

        assertEquals(VmapTimeOffset.unscheduled(), schedule.getBreaks().get(0).getTimeOffset());
    }
}
