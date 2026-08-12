package com.smart.android.adsdk.internal;

final class VastMediaFile {
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
        String normalizedType = type == null ? "" : type.toLowerCase();
        String normalizedUrl = url == null ? "" : url.toLowerCase();
        return normalizedType.contains("video/mp4")
            || normalizedType.contains("application/x-mpegurl")
            || normalizedType.contains("application/vnd.apple.mpegurl")
            || normalizedUrl.endsWith(".mp4")
            || normalizedUrl.contains(".m3u8");
    }

    int score() {
        int score = 0;
        String normalizedType = type == null ? "" : type.toLowerCase();
        if (normalizedType.contains("application/x-mpegurl")
            || normalizedType.contains("application/vnd.apple.mpegurl")
            || url.toLowerCase().contains(".m3u8")) {
            score += 20_000;
        }
        if (normalizedType.contains("video/mp4") || url.toLowerCase().contains(".mp4")) {
            score += 10_000;
        }
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
}
