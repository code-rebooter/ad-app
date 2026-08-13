package com.smart.android.adsdk.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.List;
import org.junit.Test;

public class VastParserTest {
    @Test
    public void selectsStreamingMediaAndPreservesMimeType() throws Exception {
        VastParsedResponse response = new VastParser().parse(
            "<VAST version=\"3.0\"><Ad><InLine><Creatives><Creative><Linear>"
                + "<MediaFiles>"
                + "<MediaFile type=\"video/mp4\" width=\"1280\" height=\"720\" bitrate=\"300\">"
                + "<![CDATA[https://cdn.test/video.mp4]]></MediaFile>"
                + "<MediaFile type=\"application/dash+xml\" width=\"0\" height=\"0\">"
                + "<![CDATA[https://cdn.test/manifest.mpd]]></MediaFile>"
                + "</MediaFiles>"
                + "</Linear></Creative></Creatives></InLine></Ad></VAST>"
        );

        assertEquals("https://cdn.test/manifest.mpd", response.getAd().getMediaUrl());
        assertEquals("application/dash+xml", response.getAd().getMediaType());
        assertEquals(2, response.getAd().getMediaFiles().size());
        assertEquals(
            "https://cdn.test/video.mp4",
            response.getAd().getMediaFiles().get(1).getUrl()
        );
    }

    @Test
    public void parsesLinearTrackingEventsUsedByGoogleVast() throws Exception {
        VastParsedResponse response = new VastParser().parse(
            "<VAST version=\"3.0\"><Ad><InLine>"
                + "<Impression><![CDATA[https://track.test/impression]]></Impression>"
                + "<Error><![CDATA[https://track.test/error?[ERRORCODE]]]></Error>"
                + "<Creatives><Creative><Linear><TrackingEvents>"
                + "<Tracking event=\"creativeView\"><![CDATA[https://track.test/creative]]></Tracking>"
                + "<Tracking event=\"start\"><![CDATA[https://track.test/start]]></Tracking>"
                + "<Tracking event=\"firstQuartile\"><![CDATA[https://track.test/q1]]></Tracking>"
                + "<Tracking event=\"midpoint\"><![CDATA[https://track.test/q2]]></Tracking>"
                + "<Tracking event=\"thirdQuartile\"><![CDATA[https://track.test/q3]]></Tracking>"
                + "<Tracking event=\"complete\"><![CDATA[https://track.test/complete]]></Tracking>"
                + "<Tracking event=\"mute\"><![CDATA[https://track.test/mute]]></Tracking>"
                + "<Tracking event=\"unmute\"><![CDATA[https://track.test/unmute]]></Tracking>"
                + "<Tracking event=\"pause\"><![CDATA[https://track.test/pause]]></Tracking>"
                + "<Tracking event=\"resume\"><![CDATA[https://track.test/resume]]></Tracking>"
                + "<Tracking event=\"progress\" offset=\"00:00:05\">"
                + "<![CDATA[https://track.test/progress5]]></Tracking>"
                + "<Tracking event=\"progress\" offset=\"75%\">"
                + "<![CDATA[https://track.test/progress75]]></Tracking>"
                + "</TrackingEvents><MediaFiles>"
                + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/video.mp4]]></MediaFile>"
                + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad></VAST>"
        );

        VastAd ad = response.getAd();
        assertEquals("https://track.test/impression", ad.getImpressions().get(0));
        assertEquals("https://track.test/error?[ERRORCODE]", ad.getErrorTrackers().get(0));
        assertEquals("https://track.test/creative", ad.getCreativeViewTrackers().get(0));
        assertEquals("https://track.test/start", ad.getStartTrackers().get(0));
        assertEquals("https://track.test/q1", ad.getFirstQuartileTrackers().get(0));
        assertEquals("https://track.test/q2", ad.getMidpointTrackers().get(0));
        assertEquals("https://track.test/q3", ad.getThirdQuartileTrackers().get(0));
        assertEquals("https://track.test/complete", ad.getCompleteTrackers().get(0));
        assertEquals("https://track.test/mute", ad.getMuteTrackers().get(0));
        assertEquals("https://track.test/unmute", ad.getUnmuteTrackers().get(0));
        assertEquals("https://track.test/pause", ad.getPauseTrackers().get(0));
        assertEquals("https://track.test/resume", ad.getResumeTrackers().get(0));
        assertEquals("https://track.test/progress5", ad.getProgressTrackers().get(0).getUrl());
        assertEquals(5_000L, ad.getProgressTrackers().get(0).getOffsetMs());
        assertEquals("https://track.test/progress75", ad.getProgressTrackers().get(1).getUrl());
        assertEquals(0.75f, ad.getProgressTrackers().get(1).getOffsetPercent(), 0.0001f);
    }

    @Test
    public void ignoresCompanionTrackingWhenParsingLinearVideoEvents() throws Exception {
        VastParsedResponse response = new VastParser().parse(
            "<VAST version=\"3.0\"><Ad><InLine><Creatives>"
                + "<Creative><Linear><TrackingEvents>"
                + "<Tracking event=\"creativeView\"><![CDATA[https://track.test/linear]]></Tracking>"
                + "</TrackingEvents><MediaFiles>"
                + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/video.mp4]]></MediaFile>"
                + "</MediaFiles></Linear></Creative>"
                + "<Creative><CompanionAds><Companion><TrackingEvents>"
                + "<Tracking event=\"creativeView\"><![CDATA[https://track.test/companion]]></Tracking>"
                + "</TrackingEvents></Companion></CompanionAds></Creative>"
                + "</Creatives></InLine></Ad></VAST>"
        );

        assertEquals(1, response.getAd().getCreativeViewTrackers().size());
        assertEquals("https://track.test/linear", response.getAd().getCreativeViewTrackers().get(0));
    }

    @Test
    public void carriesErrorTrackersWhenNoPlayableMediaExists() throws Exception {
        try {
            new VastParser().parse(
                "<VAST version=\"3.0\"><Ad><InLine>"
                    + "<Error><![CDATA[https://track.test/error?[ERRORCODE]]]></Error>"
                    + "</InLine></Ad></VAST>"
            );
            fail("Expected VAST load failure");
        } catch (VastLoadException error) {
            assertEquals(303, error.getVastErrorCode());
            List<String> trackers = error.getErrorTrackers();
            assertEquals(1, trackers.size());
            assertEquals("https://track.test/error?[ERRORCODE]", trackers.get(0));
        }
    }

    @Test
    public void mergesWrapperErrorTrackersWhenInlineHasNoPlayableMedia() {
        VastClient.TrackingBundle inherited = new VastClient.TrackingBundle();
        inherited.merge(VastParsedResponse.wrapper(
            "https://ad.test/wrapper",
            java.util.Collections.emptyList(),
            java.util.Collections.emptyList(),
            java.util.Collections.emptyList(),
            java.util.Collections.emptyList(),
            java.util.Collections.emptyList(),
            java.util.Collections.emptyList(),
            java.util.Collections.emptyList(),
            java.util.Collections.emptyList(),
            java.util.Collections.emptyList(),
            java.util.Collections.emptyList(),
            java.util.Collections.emptyList(),
            java.util.Collections.emptyList(),
            java.util.Collections.singletonList("https://track.test/wrapper-error")
        ));
        VastLoadException inlineError = new VastLoadException(
            "inline error",
            303,
            java.util.Collections.singletonList("https://track.test/inline-error")
        );

        VastLoadException merged = inherited.toError(inlineError);

        assertEquals(303, merged.getVastErrorCode());
        assertEquals(2, merged.getErrorTrackers().size());
        assertEquals("https://track.test/wrapper-error", merged.getErrorTrackers().get(0));
        assertEquals("https://track.test/inline-error", merged.getErrorTrackers().get(1));
    }
}
