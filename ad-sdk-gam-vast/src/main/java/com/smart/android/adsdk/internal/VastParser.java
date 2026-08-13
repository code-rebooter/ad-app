package com.smart.android.adsdk.internal;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

final class VastParser {

    VastParsedResponse parse(String xml) throws VastLoadException {
        if (xml == null || xml.trim().isEmpty()) {
            throw new VastLoadException("VAST response was empty");
        }

        List<String> impressions = new ArrayList<>();
        List<String> creativeViewTrackers = new ArrayList<>();
        List<String> startTrackers = new ArrayList<>();
        List<String> firstQuartileTrackers = new ArrayList<>();
        List<String> midpointTrackers = new ArrayList<>();
        List<String> thirdQuartileTrackers = new ArrayList<>();
        List<String> completeTrackers = new ArrayList<>();
        List<String> muteTrackers = new ArrayList<>();
        List<String> unmuteTrackers = new ArrayList<>();
        List<String> pauseTrackers = new ArrayList<>();
        List<String> resumeTrackers = new ArrayList<>();
        List<VastProgressTracker> progressTrackers = new ArrayList<>();
        List<String> errorTrackers = new ArrayList<>();
        List<VastMediaFile> mediaFiles = new ArrayList<>();
        String wrapperUrl = null;
        int linearDepth = -1;

        try {
            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(new StringReader(xml));
            int eventType;
            while ((eventType = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.END_TAG
                    && linearDepth > 0
                    && parser.getDepth() == linearDepth
                    && "Linear".equalsIgnoreCase(localName(parser.getName()))) {
                    linearDepth = -1;
                    continue;
                }
                if (eventType != XmlPullParser.START_TAG) {
                    continue;
                }

                String tag = localName(parser.getName());
                if ("Linear".equalsIgnoreCase(tag)) {
                    linearDepth = parser.getDepth();
                } else if ("Impression".equalsIgnoreCase(tag)) {
                    addText(parser, impressions);
                } else if ("Error".equalsIgnoreCase(tag)) {
                    addText(parser, errorTrackers);
                } else if ("VASTAdTagURI".equalsIgnoreCase(tag)
                    || "AdTagURI".equalsIgnoreCase(tag)) {
                    wrapperUrl = readText(parser);
                } else if (linearDepth > 0 && "Tracking".equalsIgnoreCase(tag)) {
                    String event = parser.getAttributeValue(null, "event");
                    String offset = parser.getAttributeValue(null, "offset");
                    String trackingUrl = readText(parser);
                    addTracker(
                        event,
                        offset,
                        trackingUrl,
                        creativeViewTrackers,
                        startTrackers,
                        firstQuartileTrackers,
                        midpointTrackers,
                        thirdQuartileTrackers,
                        completeTrackers,
                        muteTrackers,
                        unmuteTrackers,
                        pauseTrackers,
                        resumeTrackers,
                        progressTrackers
                    );
                } else if (linearDepth > 0 && "MediaFile".equalsIgnoreCase(tag)) {
                    String mediaType = parser.getAttributeValue(null, "type");
                    int width = parseInt(parser.getAttributeValue(null, "width"));
                    int height = parseInt(parser.getAttributeValue(null, "height"));
                    int bitrate = parseInt(parser.getAttributeValue(null, "bitrate"));
                    String mediaUrl = readText(parser);
                    if (!mediaUrl.isEmpty()) {
                        mediaFiles.add(new VastMediaFile(
                            mediaUrl,
                            mediaType,
                            width,
                            height,
                            bitrate
                        ));
                    }
                }
            }
        } catch (IOException | XmlPullParserException error) {
            throw new VastLoadException("Unable to parse VAST XML", error);
        }

        List<VastMediaFile> selectedMediaFiles = selectMediaFiles(mediaFiles);
        VastMediaFile selectedMediaFile = selectedMediaFiles.isEmpty() ? null : selectedMediaFiles.get(0);
        if (selectedMediaFile != null) {
            return VastParsedResponse.inline(new VastAd(
                selectedMediaFile.getUrl(),
                selectedMediaFile.getPlayerMimeType(),
                selectedMediaFiles,
                impressions,
                creativeViewTrackers,
                startTrackers,
                firstQuartileTrackers,
                midpointTrackers,
                thirdQuartileTrackers,
                completeTrackers,
                muteTrackers,
                unmuteTrackers,
                pauseTrackers,
                resumeTrackers,
                progressTrackers,
                errorTrackers
            ));
        }
        if (wrapperUrl != null && !wrapperUrl.isEmpty()) {
            return VastParsedResponse.wrapper(
                wrapperUrl,
                impressions,
                creativeViewTrackers,
                startTrackers,
                firstQuartileTrackers,
                midpointTrackers,
                thirdQuartileTrackers,
                completeTrackers,
                muteTrackers,
                unmuteTrackers,
                pauseTrackers,
                resumeTrackers,
                progressTrackers,
                errorTrackers
            );
        }
        throw new VastLoadException(
            "VAST_NO_ADS_AFTER_WRAPPER (303): VAST response contained no playable ad",
            303,
            errorTrackers
        );
    }

    private List<VastMediaFile> selectMediaFiles(List<VastMediaFile> mediaFiles) {
        List<VastMediaFile> playable = new ArrayList<>();
        for (VastMediaFile mediaFile : mediaFiles) {
            if (mediaFile.isPlayable()) {
                playable.add(mediaFile);
            }
        }
        Collections.sort(playable, new Comparator<VastMediaFile>() {
            @Override
            public int compare(VastMediaFile left, VastMediaFile right) {
                return Integer.compare(right.score(), left.score());
            }
        });
        return playable;
    }

    private void addTracker(
        String event,
        String offset,
        String trackingUrl,
        List<String> creativeViewTrackers,
        List<String> startTrackers,
        List<String> firstQuartileTrackers,
        List<String> midpointTrackers,
        List<String> thirdQuartileTrackers,
        List<String> completeTrackers,
        List<String> muteTrackers,
        List<String> unmuteTrackers,
        List<String> pauseTrackers,
        List<String> resumeTrackers,
        List<VastProgressTracker> progressTrackers
    ) {
        if (trackingUrl == null || trackingUrl.isEmpty() || event == null) {
            return;
        }
        switch (event.toLowerCase()) {
            case "creativeview":
                creativeViewTrackers.add(trackingUrl);
                break;
            case "start":
                startTrackers.add(trackingUrl);
                break;
            case "firstquartile":
                firstQuartileTrackers.add(trackingUrl);
                break;
            case "midpoint":
                midpointTrackers.add(trackingUrl);
                break;
            case "thirdquartile":
                thirdQuartileTrackers.add(trackingUrl);
                break;
            case "complete":
                completeTrackers.add(trackingUrl);
                break;
            case "mute":
                muteTrackers.add(trackingUrl);
                break;
            case "unmute":
                unmuteTrackers.add(trackingUrl);
                break;
            case "pause":
                pauseTrackers.add(trackingUrl);
                break;
            case "resume":
                resumeTrackers.add(trackingUrl);
                break;
            case "progress":
                VastProgressTracker progressTracker = parseProgressTracker(trackingUrl, offset);
                if (progressTracker != null) {
                    progressTrackers.add(progressTracker);
                }
                break;
            default:
                break;
        }
    }

    private VastProgressTracker parseProgressTracker(String trackingUrl, String offset) {
        if (offset == null || offset.trim().isEmpty()) {
            return null;
        }
        String normalizedOffset = offset.trim();
        if (normalizedOffset.endsWith("%")) {
            try {
                float percent = Float.parseFloat(
                    normalizedOffset.substring(0, normalizedOffset.length() - 1)
                ) / 100f;
                if (percent >= 0f && percent <= 1f) {
                    return VastProgressTracker.percentage(trackingUrl, percent);
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
            return null;
        }
        long offsetMs = parseDurationMs(normalizedOffset);
        return offsetMs >= 0L ? VastProgressTracker.absolute(trackingUrl, offsetMs) : null;
    }

    private long parseDurationMs(String value) {
        String[] parts = value.split(":");
        if (parts.length != 3) {
            return -1L;
        }
        try {
            long hours = Long.parseLong(parts[0]);
            long minutes = Long.parseLong(parts[1]);
            float seconds = Float.parseFloat(parts[2]);
            if (hours < 0L || minutes < 0L || seconds < 0f) {
                return -1L;
            }
            return hours * 3_600_000L
                + minutes * 60_000L
                + Math.round(seconds * 1_000f);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private void addText(XmlPullParser parser, List<String> target)
        throws IOException, XmlPullParserException {
        String value = readText(parser);
        if (!value.isEmpty()) {
            target.add(value);
        }
    }

    private String readText(XmlPullParser parser) throws IOException, XmlPullParserException {
        StringBuilder builder = new StringBuilder();
        int startDepth = parser.getDepth();
        int eventType;
        while ((eventType = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.TEXT || eventType == XmlPullParser.CDSECT) {
                builder.append(parser.getText());
            } else if (eventType == XmlPullParser.END_TAG && parser.getDepth() == startDepth) {
                break;
            }
        }
        return builder.toString().trim();
    }

    private String localName(String name) {
        if (name == null) {
            return "";
        }
        int separator = name.indexOf(':');
        return separator >= 0 ? name.substring(separator + 1) : name;
    }

    private int parseInt(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
