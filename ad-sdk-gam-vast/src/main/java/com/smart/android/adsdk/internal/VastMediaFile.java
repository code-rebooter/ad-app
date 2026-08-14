package com.smart.android.adsdk.internal;

final class VastMediaFile {
    private static final String MIME_MP4 = "video/mp4";
    private static final String MIME_WEBM = "video/webm";
    private static final String MIME_QUICKTIME = "video/quicktime";
    private static final String MIME_3GPP = "video/3gpp";
    private static final String MIME_MP2T = "video/mp2t";
    private static final String MIME_AUDIO_MPEG = "audio/mpeg";
    private static final String MIME_AUDIO_MP4 = "audio/mp4";
    private static final String MIME_AUDIO_AAC = "audio/aac";
    private static final String MIME_AUDIO_OGG = "audio/ogg";
    private static final String MIME_AUDIO_OPUS = "audio/opus";
    private static final String MIME_AUDIO_WEBM = "audio/webm";
    private static final String MIME_AUDIO_WAV = "audio/wav";
    private static final String MIME_AUDIO_FLAC = "audio/flac";
    private static final String MIME_HLS = "application/x-mpegURL";
    private static final String MIME_DASH = "application/dash+xml";
    private static final String MIME_SMOOTH_STREAMING = "application/vnd.ms-sstr+xml";

    private final String url;
    private final String type;
    private final String apiFramework;
    private final int width;
    private final int height;
    private final int bitrate;

    VastMediaFile(String url, String type, int width, int height, int bitrate) {
        this(url, type, null, width, height, bitrate);
    }

    VastMediaFile(
        String url,
        String type,
        String apiFramework,
        int width,
        int height,
        int bitrate
    ) {
        this.url = url;
        this.type = type;
        this.apiFramework = apiFramework;
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

    String getApiFramework() {
        return apiFramework;
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
        return !isInteractive() && getFormatScore() > 0;
    }

    boolean isInteractive() {
        String normalizedApiFramework = normalizedApiFramework();
        String normalizedType = normalizedType();
        return normalizedApiFramework.contains("vpaid")
            || normalizedApiFramework.contains("simid")
            || normalizedType.contains("application/javascript")
            || normalizedType.contains("application/x-javascript")
            || normalizedType.contains("text/javascript");
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
        if (isQuickTime()) {
            return MIME_QUICKTIME;
        }
        if (is3gpp()) {
            return MIME_3GPP;
        }
        if (isMp2t()) {
            return MIME_MP2T;
        }
        if (isAudioMp4()) {
            return MIME_AUDIO_MP4;
        }
        if (isAudioMpeg()) {
            return MIME_AUDIO_MPEG;
        }
        if (isAudioAac()) {
            return MIME_AUDIO_AAC;
        }
        if (isAudioOgg()) {
            return MIME_AUDIO_OGG;
        }
        if (isAudioOpus()) {
            return MIME_AUDIO_OPUS;
        }
        if (isAudioWebm()) {
            return MIME_AUDIO_WEBM;
        }
        if (isAudioWav()) {
            return MIME_AUDIO_WAV;
        }
        if (isAudioFlac()) {
            return MIME_AUDIO_FLAC;
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
        if (isQuickTime() || is3gpp() || isMp2t()) {
            return 28_000;
        }
        if (isAudioMp4() || isAudioMpeg() || isAudioAac()) {
            return 20_000;
        }
        if (isAudioOgg() || isAudioOpus() || isAudioWebm() || isAudioWav() || isAudioFlac()) {
            return 19_000;
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
        if (normalizedType.startsWith("audio/")) {
            return false;
        }
        return normalizedType.contains("video/mp4")
            || normalizedType.contains("application/mp4")
            || (normalizedType.isEmpty() && normalizedUrl.contains(".mp4"));
    }

    private boolean isWebm() {
        String normalizedType = normalizedType();
        String normalizedUrl = normalizedUrl();
        return normalizedType.contains("video/webm")
            || normalizedUrl.contains(".webm");
    }

    private boolean isQuickTime() {
        String normalizedType = normalizedType();
        String normalizedUrl = normalizedUrl();
        return normalizedType.contains("video/quicktime")
            || normalizedUrl.contains(".mov");
    }

    private boolean is3gpp() {
        String normalizedType = normalizedType();
        String normalizedUrl = normalizedUrl();
        return normalizedType.contains("video/3gpp")
            || normalizedType.contains("video/3gp")
            || normalizedUrl.contains(".3gp")
            || normalizedUrl.contains(".3gpp");
    }

    private boolean isMp2t() {
        String normalizedType = normalizedType();
        String normalizedUrl = normalizedUrl();
        return normalizedType.contains("video/mp2t")
            || normalizedType.contains("video/mp2ts")
            || normalizedUrl.contains(".ts");
    }

    private boolean isAudioMpeg() {
        String normalizedType = normalizedType();
        String normalizedUrl = normalizedUrl();
        return normalizedType.contains("audio/mpeg")
            || normalizedType.contains("audio/mp3")
            || normalizedUrl.contains(".mp3");
    }

    private boolean isAudioMp4() {
        String normalizedType = normalizedType();
        String normalizedUrl = normalizedUrl();
        return normalizedType.contains("audio/mp4")
            || normalizedType.contains("audio/x-m4a")
            || normalizedUrl.contains(".m4a");
    }

    private boolean isAudioAac() {
        String normalizedType = normalizedType();
        String normalizedUrl = normalizedUrl();
        return normalizedType.contains("audio/aac")
            || normalizedType.contains("audio/aacp")
            || normalizedUrl.contains(".aac");
    }

    private boolean isAudioOgg() {
        String normalizedType = normalizedType();
        String normalizedUrl = normalizedUrl();
        return normalizedType.contains("audio/ogg")
            || normalizedUrl.contains(".ogg");
    }

    private boolean isAudioOpus() {
        String normalizedType = normalizedType();
        String normalizedUrl = normalizedUrl();
        return normalizedType.contains("audio/opus")
            || normalizedUrl.contains(".opus");
    }

    private boolean isAudioWebm() {
        String normalizedType = normalizedType();
        return normalizedType.contains("audio/webm");
    }

    private boolean isAudioWav() {
        String normalizedType = normalizedType();
        String normalizedUrl = normalizedUrl();
        return normalizedType.contains("audio/wav")
            || normalizedType.contains("audio/x-wav")
            || normalizedUrl.contains(".wav");
    }

    private boolean isAudioFlac() {
        String normalizedType = normalizedType();
        String normalizedUrl = normalizedUrl();
        return normalizedType.contains("audio/flac")
            || normalizedUrl.contains(".flac");
    }

    private String normalizedType() {
        return type == null ? "" : type.toLowerCase();
    }

    private String normalizedApiFramework() {
        return apiFramework == null ? "" : apiFramework.toLowerCase();
    }

    private String normalizedUrl() {
        return url == null ? "" : url.toLowerCase();
    }
}
