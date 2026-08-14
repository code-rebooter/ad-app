package com.smart.android.adsdk.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
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
    public void parsesAudioOnlyLinearMediaFilesAsPlayableAds() throws Exception {
        VastParsedResponse response = new VastParser().parse(
            "<VAST version=\"3.0\"><Ad><InLine>"
                + "<Impression><![CDATA[https://track.test/impression]]></Impression>"
                + "<Creatives><Creative><Linear><TrackingEvents>"
                + "<Tracking event=\"start\"><![CDATA[https://track.test/start]]></Tracking>"
                + "</TrackingEvents><MediaFiles>"
                + "<MediaFile type=\"audio/mpeg\" bitrate=\"128\">"
                + "<![CDATA[https://cdn.test/audio.mp3]]></MediaFile>"
                + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad></VAST>"
        );

        VastAd ad = response.getAd();
        assertEquals("https://cdn.test/audio.mp3", ad.getMediaUrl());
        assertEquals("audio/mpeg", ad.getMediaType());
        assertEquals("https://track.test/impression", ad.getImpressions().get(0));
        assertEquals("https://track.test/start", ad.getStartTrackers().get(0));
    }

    @Test
    public void parsesLinearTrackingEventsUsedByGoogleVast() throws Exception {
        VastParsedResponse response = new VastParser().parse(
            "<VAST version=\"3.0\"><Ad><InLine>"
                + "<Impression><![CDATA[https://track.test/impression]]></Impression>"
                + "<Error><![CDATA[https://track.test/error?[ERRORCODE]]]></Error>"
                + "<Creatives><Creative><Linear><TrackingEvents>"
                + "<Tracking event=\"creativeView\"><![CDATA[https://track.test/creative]]></Tracking>"
                + "<Tracking event=\"loaded\"><![CDATA[https://track.test/loaded]]></Tracking>"
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
        assertEquals("https://track.test/loaded", ad.getLoadedTrackers().get(0));
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
    public void parsesGoogleCustomTrackingExtensionsWithKnownReportingTiming() throws Exception {
        VastParsedResponse response = new VastParser().parse(
            "<VAST version=\"3.0\"><Ad><InLine>"
                + "<Impression><![CDATA[https://track.test/impression]]></Impression>"
                + "<Extensions>"
                + "<Extension type=\"ShowAdTracking\"><CustomTracking>"
                + "<Tracking event=\"show_ad\"><![CDATA[https://track.test/show-ad]]></Tracking>"
                + "</CustomTracking></Extension>"
                + "<Extension type=\"video_ad_loaded\"><CustomTracking>"
                + "<Tracking event=\"loaded\"><![CDATA[https://track.test/loaded-extension]]></Tracking>"
                + "</CustomTracking></Extension>"
                + "</Extensions>"
                + "<Creatives><Creative><Linear><TrackingEvents>"
                + "<Tracking event=\"loaded\"><![CDATA[https://track.test/loaded-standard]]></Tracking>"
                + "</TrackingEvents><MediaFiles>"
                + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/video.mp4]]></MediaFile>"
                + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad></VAST>"
        );

        VastAd ad = response.getAd();
        assertEquals(2, ad.getImpressions().size());
        assertEquals("https://track.test/impression", ad.getImpressions().get(0));
        assertEquals("https://track.test/show-ad", ad.getImpressions().get(1));
        assertEquals(2, ad.getLoadedTrackers().size());
        assertEquals("https://track.test/loaded-extension", ad.getLoadedTrackers().get(0));
        assertEquals("https://track.test/loaded-standard", ad.getLoadedTrackers().get(1));
    }

    @Test
    public void parsesVideoClicksSkipOffsetAndVerificationResources() throws Exception {
        VastParsedResponse response = new VastParser().parse(
            "<VAST version=\"4.3\"><Ad><InLine>"
                + "<ViewableImpression>"
                + "<Viewable><![CDATA[https://view.test/viewable]]></Viewable>"
                + "<NotViewable><![CDATA[https://view.test/not-viewable]]></NotViewable>"
                + "<ViewUndetermined><![CDATA[https://view.test/undetermined]]></ViewUndetermined>"
                + "</ViewableImpression>"
                + "<AdVerifications><Verification vendor=\"omid.test\">"
                + "<JavaScriptResource apiFramework=\"omid\" browserOptional=\"true\">"
                + "<![CDATA[https://verify.test/omid.js]]></JavaScriptResource>"
                + "<TrackingEvents><Tracking event=\"verificationNotExecuted\">"
                + "<![CDATA[https://verify.test/not-executed?[REASON]]]></Tracking>"
                + "</TrackingEvents></Verification></AdVerifications>"
                + "<Creatives><Creative><Linear skipoffset=\"00:00:05\">"
                + "<TrackingEvents>"
                + "<Tracking event=\"skip\"><![CDATA[https://track.test/skip]]></Tracking>"
                + "</TrackingEvents>"
                + "<VideoClicks>"
                + "<ClickThrough><![CDATA[https://click.test/open]]></ClickThrough>"
                + "<ClickTracking><![CDATA[https://click.test/track]]></ClickTracking>"
                + "<CustomClick><![CDATA[https://click.test/custom]]></CustomClick>"
                + "</VideoClicks>"
                + "<MediaFiles>"
                + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/video.mp4]]></MediaFile>"
                + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad></VAST>"
        );

        VastAd ad = response.getAd();
        assertEquals(5_000L, ad.getSkipOffsetMs());
        assertEquals("https://track.test/skip", ad.getSkipTrackers().get(0));
        assertEquals("https://click.test/open", ad.getClickThroughUrl());
        assertEquals("https://click.test/track", ad.getClickTrackingUrls().get(0));
        assertEquals("https://click.test/custom", ad.getCustomClickUrls().get(0));
        assertEquals("https://verify.test/omid.js", ad.getVerificationResources().get(0).getResourceUrl());
        assertEquals("https://view.test/viewable", ad.getViewableTrackers().get(0));
        assertEquals("https://view.test/not-viewable", ad.getNotViewableTrackers().get(0));
        assertEquals("https://view.test/undetermined", ad.getViewUndeterminedTrackers().get(0));
        assertEquals(
            "https://verify.test/not-executed?[REASON]",
            ad.getVerificationNotExecutedTrackers().get(0)
        );
    }

    @Test
    public void parsesExecutableVerificationResourcesForNotExecutedFallback() throws Exception {
        VastParsedResponse response = new VastParser().parse(
            "<VAST version=\"4.3\"><Ad><InLine>"
                + "<AdVerifications><Verification vendor=\"native.verify\">"
                + "<ExecutableResource apiFramework=\"omid\" type=\"native\">"
                + "<![CDATA[verify-native-resource]]></ExecutableResource>"
                + "<TrackingEvents><Tracking event=\"verificationNotExecuted\">"
                + "<![CDATA[https://verify.test/not-executed?[REASON]]]></Tracking>"
                + "</TrackingEvents></Verification></AdVerifications>"
                + "<Creatives><Creative><Linear><MediaFiles>"
                + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/video.mp4]]></MediaFile>"
                + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad></VAST>"
        );

        VastAd ad = response.getAd();
        assertEquals("verify-native-resource", ad.getVerificationResources().get(0).getResourceUrl());
        assertEquals("omid", ad.getVerificationResources().get(0).getApiFramework());
        assertEquals(
            "https://verify.test/not-executed?[REASON]",
            ad.getVerificationNotExecutedTrackers().get(0)
        );
    }

    @Test
    public void rejectsInteractiveVpaidMediaFilesInsteadOfPlayingThemAsVideo() throws Exception {
        try {
            new VastParser().parse(
                "<VAST version=\"4.3\"><Ad><InLine><Error>"
                    + "<![CDATA[https://track.test/error?[ERRORCODE]]]></Error>"
                    + "<Creatives><Creative><Linear><MediaFiles>"
                    + "<MediaFile type=\"application/javascript\" apiFramework=\"VPAID\">"
                    + "<![CDATA[https://cdn.test/ad.js]]></MediaFile>"
                    + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad></VAST>"
            );
            fail("Expected unsupported interactive creative failure");
        } catch (VastLoadException error) {
            assertEquals(403, error.getVastErrorCode());
            assertEquals("https://track.test/error?[ERRORCODE]", error.getErrorTrackers().get(0));
        }
    }

    @Test
    public void rejectsUnsupportedVideoMediaFilesAsLinearCapabilityError() throws Exception {
        try {
            new VastParser().parse(
                "<VAST version=\"4.3\"><Ad><InLine><Error>"
                    + "<![CDATA[https://track.test/error?[ERRORCODE]]]></Error>"
                    + "<Creatives><Creative><Linear><MediaFiles>"
                    + "<MediaFile type=\"video/x-unknown\">"
                    + "<![CDATA[https://cdn.test/video.unknown]]></MediaFile>"
                    + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad></VAST>"
            );
            fail("Expected unsupported media failure");
        } catch (VastLoadException error) {
            assertEquals(403, error.getVastErrorCode());
            assertEquals("https://track.test/error?[ERRORCODE]", error.getErrorTrackers().get(0));
        }
    }

    @Test
    public void rejectsInteractiveCreativeFileWhenNoVideoFallbackExists() throws Exception {
        try {
            new VastParser().parse(
                "<VAST version=\"4.3\"><Ad><InLine><Error>"
                    + "<![CDATA[https://track.test/error?[ERRORCODE]]]></Error>"
                    + "<Creatives><Creative><Linear><MediaFiles>"
                    + "<InteractiveCreativeFile type=\"application/javascript\" apiFramework=\"SIMID\">"
                    + "<![CDATA[https://cdn.test/simid.js]]></InteractiveCreativeFile>"
                    + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad></VAST>"
            );
            fail("Expected unsupported interactive creative failure");
        } catch (VastLoadException error) {
            assertEquals(409, error.getVastErrorCode());
            assertEquals("https://track.test/error?[ERRORCODE]", error.getErrorTrackers().get(0));
        }
    }

    @Test
    public void keepsVideoFallbackWhenInteractiveCreativeFileCannotRun() throws Exception {
        VastParsedResponse response = new VastParser().parse(
            "<VAST version=\"4.3\"><Ad><InLine><Error>"
                + "<![CDATA[https://track.test/error?[ERRORCODE]]]></Error>"
                + "<Creatives><Creative><Linear><MediaFiles>"
                + "<InteractiveCreativeFile type=\"application/javascript\" apiFramework=\"SIMID\">"
                + "<![CDATA[https://cdn.test/simid.js]]></InteractiveCreativeFile>"
                + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/video.mp4]]></MediaFile>"
                + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad></VAST>"
        );

        assertEquals("https://cdn.test/video.mp4", response.getAd().getMediaUrl());
        assertTrue(response.getAd().hasUnsupportedInteractiveCreative());
        assertEquals("https://track.test/error?[ERRORCODE]", response.getAd().getErrorTrackers().get(0));
    }

    @Test
    public void keepsVideoFallbackForVpaidMediaFileWithoutInteractiveCreativeFileError() throws Exception {
        VastParsedResponse response = new VastParser().parse(
            "<VAST version=\"4.3\"><Ad><InLine><Error>"
                + "<![CDATA[https://track.test/error?[ERRORCODE]]]></Error>"
                + "<Creatives><Creative><Linear><MediaFiles>"
                + "<MediaFile type=\"application/javascript\" apiFramework=\"VPAID\">"
                + "<![CDATA[https://cdn.test/vpaid.js]]></MediaFile>"
                + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/video.mp4]]></MediaFile>"
                + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad></VAST>"
        );

        assertEquals("https://cdn.test/video.mp4", response.getAd().getMediaUrl());
        assertTrue(response.getAd().hasUnsupportedInteractiveCreative());
        assertEquals(false, response.getAd().hasUnexecutedInteractiveCreativeFile());
    }

    @Test
    public void parsesPercentageSkipOffset() throws Exception {
        VastParsedResponse response = new VastParser().parse(
            "<VAST version=\"4.3\"><Ad><InLine><Creatives><Creative>"
                + "<Linear skipoffset=\"25%\"><MediaFiles>"
                + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/video.mp4]]></MediaFile>"
                + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad></VAST>"
        );

        assertEquals(-1L, response.getAd().getSkipOffsetMs());
        assertEquals(0.25f, response.getAd().getSkipOffsetPercent(), 0.0001f);
    }

    @Test
    public void keepsTrackingForTheSameLinearCreativeAsSelectedMedia() throws Exception {
        VastParsedResponse response = new VastParser().parse(
            "<VAST version=\"3.0\">"
                + "<Ad id=\"bad\"><InLine><Creatives><Creative><Linear>"
                + "<TrackingEvents><Tracking event=\"start\"><![CDATA[https://track.test/bad-start]]></Tracking>"
                + "</TrackingEvents><MediaFiles>"
                + "<MediaFile type=\"application/javascript\" apiFramework=\"VPAID\">"
                + "<![CDATA[https://cdn.test/bad.js]]></MediaFile>"
                + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad>"
                + "<Ad id=\"good\"><InLine><Creatives><Creative><Linear>"
                + "<TrackingEvents><Tracking event=\"start\"><![CDATA[https://track.test/good-start]]></Tracking>"
                + "</TrackingEvents><MediaFiles>"
                + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/good.mp4]]></MediaFile>"
                + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad>"
                + "</VAST>"
        );

        VastAd ad = response.getAd();
        assertEquals("https://cdn.test/good.mp4", ad.getMediaUrl());
        assertEquals(1, ad.getStartTrackers().size());
        assertEquals("https://track.test/good-start", ad.getStartTrackers().get(0));
    }

    @Test
    public void parsesMultiplePlayableLinearCreativesInSingleInlineAsAdBreak() throws Exception {
        VastParsedResponse response = new VastParser().parse(
            "<VAST version=\"3.0\"><Ad><InLine>"
                + "<Impression><![CDATA[https://track.test/imp]]></Impression>"
                + "<Creatives>"
                + "<Creative><Linear><TrackingEvents>"
                + "<Tracking event=\"start\"><![CDATA[https://track.test/start1]]></Tracking>"
                + "</TrackingEvents><MediaFiles>"
                + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/one.mp4]]></MediaFile>"
                + "</MediaFiles></Linear></Creative>"
                + "<Creative><Linear><TrackingEvents>"
                + "<Tracking event=\"start\"><![CDATA[https://track.test/start2]]></Tracking>"
                + "</TrackingEvents><MediaFiles>"
                + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/two.mp4]]></MediaFile>"
                + "</MediaFiles></Linear></Creative>"
                + "</Creatives></InLine></Ad></VAST>"
        );

        VastAdBreak adBreak = response.getAdBreak();
        assertEquals(2, adBreak.getAds().size());
        assertEquals("https://cdn.test/one.mp4", adBreak.getAds().get(0).getMediaUrl());
        assertEquals("https://track.test/start1", adBreak.getAds().get(0).getStartTrackers().get(0));
        assertEquals("https://track.test/imp", adBreak.getAds().get(0).getImpressions().get(0));
        assertEquals("https://cdn.test/two.mp4", adBreak.getAds().get(1).getMediaUrl());
        assertEquals("https://track.test/start2", adBreak.getAds().get(1).getStartTrackers().get(0));
        assertEquals("https://track.test/imp", adBreak.getAds().get(1).getImpressions().get(0));
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
    public void ignoresNestedLinearTrackingWhenParsingTheSelectedLinearCreative() throws Exception {
        VastParsedResponse response = new VastParser().parse(
            "<VAST version=\"3.0\"><Ad><InLine><Creatives><Creative><Linear>"
                + "<TrackingEvents>"
                + "<Tracking event=\"start\"><![CDATA[https://track.test/main-start]]></Tracking>"
                + "</TrackingEvents>"
                + "<Extensions><Extension>"
                + "<Linear><TrackingEvents>"
                + "<Tracking event=\"start\">https://track.test/nested-start</Tracking>"
                + "</TrackingEvents></Linear>"
                + "</Extension></Extensions>"
                + "<MediaFiles>"
                + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/video.mp4]]></MediaFile>"
                + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad></VAST>"
        );

        assertEquals(1, response.getAd().getStartTrackers().size());
        assertEquals("https://track.test/main-start", response.getAd().getStartTrackers().get(0));
    }

    @Test
    public void ignoresMonitoringLikeNodesOutsideTheirVastSections() throws Exception {
        VastParsedResponse response = new VastParser().parse(
            "<VAST version=\"4.3\"><Ad><InLine>"
                + "<Impression><![CDATA[https://track.test/real-impression]]></Impression>"
                + "<Error><![CDATA[https://track.test/real-error?[ERRORCODE]]]></Error>"
                + "<ViewableImpression>"
                + "<ViewUndetermined><![CDATA[https://view.test/real-undetermined]]></ViewUndetermined>"
                + "</ViewableImpression>"
                + "<AdVerifications><Verification vendor=\"real.verify\">"
                + "<JavaScriptResource apiFramework=\"omid\"><![CDATA[https://verify.test/real.js]]></JavaScriptResource>"
                + "</Verification></AdVerifications>"
                + "<Extensions><Extension>"
                + "<Impression>https://track.test/fake-impression</Impression>"
                + "<Error>https://track.test/fake-error?[ERRORCODE]</Error>"
                + "<ViewableImpression>"
                + "<ViewUndetermined>https://view.test/fake-undetermined</ViewUndetermined>"
                + "</ViewableImpression>"
                + "<AdVerifications><Verification vendor=\"fake.verify\">"
                + "<JavaScriptResource apiFramework=\"omid\">https://verify.test/fake.js</JavaScriptResource>"
                + "</Verification></AdVerifications>"
                + "</Extension></Extensions>"
                + "<Creatives><Creative><Linear><MediaFiles>"
                + "<MediaFile type=\"video/mp4\"><![CDATA[https://cdn.test/video.mp4]]></MediaFile>"
                + "</MediaFiles></Linear></Creative></Creatives></InLine></Ad></VAST>"
        );

        VastAd ad = response.getAd();
        assertEquals(1, ad.getImpressions().size());
        assertEquals("https://track.test/real-impression", ad.getImpressions().get(0));
        assertEquals(1, ad.getErrorTrackers().size());
        assertEquals("https://track.test/real-error?[ERRORCODE]", ad.getErrorTrackers().get(0));
        assertEquals(1, ad.getViewUndeterminedTrackers().size());
        assertEquals("https://view.test/real-undetermined", ad.getViewUndeterminedTrackers().get(0));
        assertEquals(1, ad.getVerificationResources().size());
        assertEquals("https://verify.test/real.js", ad.getVerificationResources().get(0).getResourceUrl());
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
    public void malformedVastXmlUsesParsingErrorCode() throws Exception {
        try {
            new VastParser().parse("<VAST version=\"4.3\"><Ad>");
            fail("Expected malformed XML failure");
        } catch (VastLoadException error) {
            assertEquals(100, error.getVastErrorCode());
        }
    }

    @Test
    public void parsesWrapperControlAttributes() throws Exception {
        VastParsedResponse response = new VastParser().parse(
            "<VAST version=\"4.3\"><Ad><Wrapper "
                + "followAdditionalWrappers=\"false\" allowMultipleAds=\"true\">"
                + "<VASTAdTagURI><![CDATA[https://ads.test/next]]></VASTAdTagURI>"
                + "</Wrapper></Ad></VAST>"
        );

        assertEquals(false, response.shouldFollowAdditionalWrappers());
        assertEquals(true, response.allowsMultipleAds());
    }

    @Test
    public void parsesNumericWrapperControlAttributes() throws Exception {
        VastParsedResponse response = new VastParser().parse(
            "<VAST version=\"4.0\"><Ad><Wrapper "
                + "followAdditionalWrappers=\"0\" allowMultipleAds=\"1\">"
                + "<VASTAdTagURI><![CDATA[https://ads.test/next]]></VASTAdTagURI>"
                + "</Wrapper></Ad></VAST>"
        );

        assertEquals(false, response.shouldFollowAdditionalWrappers());
        assertEquals(true, response.allowsMultipleAds());
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
