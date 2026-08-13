package com.smart.android.adsdk.internal;

final class VastMediaFile {
    private static final String MIME_MP4 = "video/mp4";
    private static final String MIME_WEBM = "video/webm";
    private static final String MIME_HLS = "application/x-mpegURL";
    private static final String MIME_DASH = "application/dash+xml";
    private static final String MIME_SMOOTH_STREAMING = "application/vnd.ms-sstr+xml";

    private final String url;
    private final String type;
    private final int width;
    private final int height;
    private final int bitrate;

    VastMediaFile(String url, String type, int width, int height, int bitrate) {
        this.url = url;
        this.type = type;
        this.width = width;
        this.height = height;
        this.bitrate = bitrate;
    }

    String getUrl() {
        return url;
    }

    String getType() {
        return type;
    }

    int getWidth() {
        return width;
    }

    int getHeight() {
        return height;
    }

    int getBitrate() {
        return bitrate;
    }

    boolean isPlayable() {
        return getFormatScore() > 0;
    }

    String getPlayerMimeType() {
        if (isHls()) {
            return MIME_HLS;
        }
        if (isDash()) {
            return MIME_DASH;
        }
        if (isSmoothStreaming()) {
            return MIME_SMOOTH_STREAMING;
        }
        if (isMp4()) {
            return MIME_MP4;
        }
        if (isWebm()) {
            return MIME_WEBM;
        }
        return type;
    }

    int score() {
        int score = getFormatScore();
        if (width > 0 && height > 0) {
            int pixels = width * height;
            if (pixels <= 1920 * 1080) {
                score += pixels / 1_000;
            } else {
                score += 1920 * 1080 / 1_000;
            }
        }
        if (bitrate > 0) {
            score += Math.min(bitrate, 5_000);
        }
        return score;
    }

    private int getFormatScore() {
        if (isHls()) {
            return 50_000;
        }
        if (isDash()) {
            return 49_000;
        }
        if (isSmoothStreaming()) {
            return 48_000;
        }
        if (isMp4()) {
            return 30_000;
        }
        if (isWebm()) {
            return 29_000;
        }
        return 0;
    }

    private boolean isHls() {
        String normalizedType = normalizedType();
        String normalizedUrl = normalizedUrl();
        return normalizedType.contains("application/x-mpegurl")
            || normalizedType.contains("application/vnd.apple.mpegurl")
            || normalizedUrl.contains(".m3u8");
    }

    private boolean isDash() {
        String normalizedType = normalizedType();
        String normalizedUrl = normalizedUrl();
        return normalizedType.contains("application/dash+xml")
            || normalizedUrl.contains(".mpd");
    }

    private boolean isSmoothStreaming() {
        String normalizedType = normalizedType();
        String normalizedUrl = normalizedUrl();
        return normalizedType.contains("application/vnd.ms-sstr+xml")
            || normalizedUrl.contains(".ism/manifest")
            || normalizedUrl.contains(".isml/manifest");
    }

    private boolean isMp4() {
        String normalizedType = normalizedType();
        String normalizedUrl = normalizedUrl();
        return normalizedType.contains("video/mp4")
            || normalizedType.contains("application/mp4")
            || normalizedUrl.contains(".mp4");
    }

    private boolean isWebm() {
        String normalizedType = normalizedType();
        String normalizedUrl = normalizedUrl();
        return normalizedType.contains("video/webm")
            || normalizedUrl.contains(".webm");
    }

    private String normalizedType() {
        return type == null ? "" : type.toLowerCase();
    }

    private String normalizedUrl() {
        return url == null ? "" : url.toLowerCase();
    }
}
