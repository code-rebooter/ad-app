package com.smart.android.adsdk.internal;

import static org.junit.Assert.assertEquals;
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
        assertTrue(new VastMediaFile("https://cdn.test/video.mov", "video/quicktime", 640, 360, 900).isPlayable());
        assertTrue(new VastMediaFile("https://cdn.test/video.3gp", "video/3gpp", 640, 360, 900).isPlayable());
        assertTrue(new VastMediaFile("https://cdn.test/video.ts", "video/mp2t", 640, 360, 900).isPlayable());
    }

    @Test
    public void recognizesCommonAudioAdMediaFilesAsPlayable() {
        assertTrue(new VastMediaFile("https://cdn.test/ad.mp3", "audio/mpeg", 0, 0, 128).isPlayable());
        assertTrue(new VastMediaFile("https://cdn.test/ad.m4a", "audio/mp4", 0, 0, 128).isPlayable());
        assertTrue(new VastMediaFile("https://cdn.test/ad.aac", "audio/aac", 0, 0, 128).isPlayable());
        assertTrue(new VastMediaFile("https://cdn.test/ad.ogg", "audio/ogg", 0, 0, 128).isPlayable());
        assertTrue(new VastMediaFile("https://cdn.test/ad.opus", "audio/opus", 0, 0, 128).isPlayable());
        assertTrue(new VastMediaFile("https://cdn.test/ad.webm", "audio/webm", 0, 0, 128).isPlayable());
        assertTrue(new VastMediaFile("https://cdn.test/ad.wav", "audio/wav", 0, 0, 128).isPlayable());
        assertTrue(new VastMediaFile("https://cdn.test/ad.flac", "audio/flac", 0, 0, 128).isPlayable());
    }

    @Test
    public void keepsAudioMimeTypeForAudioOnlyMp4Container() {
        VastMediaFile audio = new VastMediaFile("https://cdn.test/ad.mp4", "audio/mp4", 0, 0, 128);

        assertEquals("audio/mp4", audio.getPlayerMimeType());
    }

    @Test
    public void prefersStreamingRenditionsBeforeProgressiveFallbacks() {
        VastMediaFile mp4 = new VastMediaFile("https://cdn.test/video.mp4", "video/mp4", 640, 360, 900);
        VastMediaFile hls = new VastMediaFile("https://cdn.test/master.m3u8", "application/x-mpegURL", 640, 360, 900);
        VastMediaFile dash = new VastMediaFile("https://cdn.test/manifest.mpd", "application/dash+xml", 640, 360, 900);

        assertTrue(hls.score() > mp4.score());
        assertTrue(dash.score() > mp4.score());
    }

    @Test
    public void prefersVideoRenditionsBeforeProgressiveAudioWhenBothExist() {
        VastMediaFile video = new VastMediaFile("https://cdn.test/video.mp4", "video/mp4", 640, 360, 900);
        VastMediaFile audio = new VastMediaFile("https://cdn.test/ad.mp3", "audio/mpeg", 0, 0, 128);

        assertTrue(video.score() > audio.score());
    }
}
