package com.smart.android.adsdk.internal;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VastMediaFileTest {
    @Test
    public void recognizesCommonGoogleVideoRenditionsAsPlayable() {
        assertTrue(new VastMediaFile("https://cdn.test/video.mp4", "video/mp4", 640, 360, 900).isPlayable());
        assertTrue(new VastMediaFile("https://cdn.test/master.m3u8", "application/x-mpegURL", 640, 360, 900).isPlayable());
        assertTrue(new VastMediaFile("https://cdn.test/manifest.mpd", "application/dash+xml", 640, 360, 900).isPlayable());
        assertTrue(new VastMediaFile("https://cdn.test/manifest.isml/Manifest", "application/vnd.ms-sstr+xml", 640, 360, 900).isPlayable());
        assertTrue(new VastMediaFile("https://cdn.test/video.webm", "video/webm", 640, 360, 900).isPlayable());
        assertTrue(new VastMediaFile("https://cdn.test/file?id=123&format=mp4", "video/mp4", 640, 360, 900).isPlayable());
    }

    @Test
    public void prefersStreamingRenditionsBeforeProgressiveFallbacks() {
        VastMediaFile mp4 = new VastMediaFile("https://cdn.test/video.mp4", "video/mp4", 640, 360, 900);
        VastMediaFile hls = new VastMediaFile("https://cdn.test/master.m3u8", "application/x-mpegURL", 640, 360, 900);
        VastMediaFile dash = new VastMediaFile("https://cdn.test/manifest.mpd", "application/dash+xml", 640, 360, 900);

        assertTrue(hls.score() > mp4.score());
        assertTrue(dash.score() > mp4.score());
    }
}
