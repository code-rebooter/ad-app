package com.smart.android.adsdk.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import org.junit.Before;
import org.junit.Test;

public class RemoteAdConfigParserTest {
    private RemoteAdConfigParser parser;

    @Before
    public void setUp() {
        parser = new RemoteAdConfigParser(new Gson());
    }

    @Test
    public void parsesWrappedPlaybackConfig() throws Exception {
        String json = "{\"code\":100000,\"data\":{\"enabled\":true," +
            "\"ad_tag_url\":\"https://pubads.g.doubleclick.net/test\"," +
            "\"ad_load_timeout_ms\":12000,\"ad_startup_timeout_ms\":25000}}";

        RemoteAdConfigResult result = parser.parse(json);

        assertTrue(result.hasAd());
        assertEquals("https://pubads.g.doubleclick.net/test", result.getConfig().getAdTagUrl());
        assertEquals(12000, result.getConfig().getAdLoadTimeoutMs());
        assertEquals(25000L, result.getConfig().getAdStartupTimeoutMs());
    }

    @Test
    public void parsesWrappedPlaybackConfigWithHttpStyleBusinessCode() throws Exception {
        String json = "{\"code\":200,\"data\":{\"enabled\":true," +
            "\"ad_tag_url\":\"https://pubads.g.doubleclick.net/test\"}}";

        RemoteAdConfigResult result = parser.parse(json);

        assertTrue(result.hasAd());
        assertEquals("https://pubads.g.doubleclick.net/test", result.getConfig().getAdTagUrl());
    }

    @Test
    public void fillsBlankGamCorrelatorBeforePlayback() throws Exception {
        RemoteAdConfigResult result = parser.parse(
            "{\"ad_tag_url\":\"https://pubads.g.doubleclick.net/gampad/ads"
                + "?iu=/21775744923/external/single_preroll_skippable"
                + "&output=vast&correlator=\"}"
        );

        okhttp3.HttpUrl url = okhttp3.HttpUrl.parse(result.getConfig().getAdTagUrl());
        assertTrue(result.hasAd());
        assertEquals("pubads.g.doubleclick.net", url.host());
        assertTrue(url.queryParameter("correlator").matches("\\d{13,}"));
    }

    @Test
    public void addsMissingGamCorrelatorBeforePlayback() throws Exception {
        RemoteAdConfigResult result = parser.parse(
            "{\"ad_tag_url\":\"https://pubads.g.doubleclick.net/gampad/ads"
                + "?iu=/21775744923/external/single_preroll_skippable"
                + "&output=vast\"}"
        );

        okhttp3.HttpUrl url = okhttp3.HttpUrl.parse(result.getConfig().getAdTagUrl());
        assertTrue(result.hasAd());
        assertTrue(url.queryParameter("correlator").matches("\\d{13,}"));
    }

    @Test
    public void replacesGamCorrelatorMacroBeforePlayback() throws Exception {
        RemoteAdConfigResult result = parser.parse(
            "{\"ad_tag_url\":\"https://pubads.g.doubleclick.net/gampad/ads"
                + "?iu=/21775744923/external/single_preroll_skippable"
                + "&output=vast&correlator=[correlator]\"}"
        );

        okhttp3.HttpUrl url = okhttp3.HttpUrl.parse(result.getConfig().getAdTagUrl());
        assertTrue(result.hasAd());
        assertTrue(url.queryParameter("correlator").matches("\\d{13,}"));
    }

    @Test
    public void keepsNonGamUrlUnchangedWhenFillingCorrelator() throws Exception {
        RemoteAdConfigResult result = parser.parse(
            "{\"ad_tag_url\":\"https://example.test/vast?correlator=\"}"
        );

        assertEquals("https://example.test/vast?correlator=", result.getConfig().getAdTagUrl());
    }

    @Test
    public void parsesDirectPlaybackConfigWithDefaults() throws Exception {
        RemoteAdConfigResult result = parser.parse(
            "{\"ad_tag_url\":\"https://example.test/vast\"}"
        );

        assertTrue(result.hasAd());
        assertEquals(RemoteAdConfigParser.DEFAULT_AD_LOAD_TIMEOUT_MS, result.getConfig().getAdLoadTimeoutMs());
        assertEquals(RemoteAdConfigParser.DEFAULT_AD_STARTUP_TIMEOUT_MS, result.getConfig().getAdStartupTimeoutMs());
    }

    @Test
    public void disabledFlagWithoutAdTagProducesNoAdTagSkipResult() throws Exception {
        RemoteAdConfigResult result = parser.parse(
            "{\"code\":100000,\"data\":{\"enabled\":false}}"
        );

        assertFalse(result.hasAd());
        assertEquals("NO_AD_TAG", result.getSkipReason());
    }

    @Test
    public void disabledFlagDoesNotBlockReturnedAdTag() throws Exception {
        RemoteAdConfigResult result = parser.parse(
            "{\"code\":100000,\"data\":{\"enabled\":false,\"ad_tag_url\":\"https://example.test/vast\"}}"
        );

        assertTrue(result.hasAd());
        assertEquals("https://example.test/vast", result.getConfig().getAdTagUrl());
    }

    @Test
    public void blankAdTagProducesSkipResult() throws Exception {
        RemoteAdConfigResult result = parser.parse(
            "{\"code\":100000,\"data\":{\"enabled\":true,\"ad_tag_url\":\"   \"}}"
        );

        assertFalse(result.hasAd());
        assertEquals("NO_AD_TAG", result.getSkipReason());
    }

    @Test(expected = RemoteAdConfigParseException.class)
    public void unsuccessfulBusinessCodeFailsParsing() throws Exception {
        parser.parse("{\"code\":500001,\"msg\":\"NO_MATCHED_CONFIG\",\"data\":null}");
    }

    @Test(expected = RemoteAdConfigParseException.class)
    public void malformedJsonFailsParsing() throws Exception {
        parser.parse("not-json");
    }
}
