package com.smart.android.adsdk.internal;

import android.util.Xml;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

final class VastParser {

    VastParsedResponse parse(String xml) throws VastLoadException {
        if (xml == null || xml.trim().isEmpty()) {
            throw new VastLoadException("VAST response was empty");
        }

        List<String> impressions = new ArrayList<>();
        List<String> startTrackers = new ArrayList<>();
        List<String> firstQuartileTrackers = new ArrayList<>();
        List<String> midpointTrackers = new ArrayList<>();
        List<String> thirdQuartileTrackers = new ArrayList<>();
        List<String> completeTrackers = new ArrayList<>();
        List<String> errorTrackers = new ArrayList<>();
        List<VastMediaFile> mediaFiles = new ArrayList<>();
        String wrapperUrl = null;

        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new StringReader(xml));
            int eventType;
            while ((eventType = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (eventType != XmlPullParser.START_TAG) {
                    continue;
                }

                String tag = localName(parser.getName());
                if ("Impression".equalsIgnoreCase(tag)) {
                    addText(parser, impressions);
                } else if ("Error".equalsIgnoreCase(tag)) {
                    addText(parser, errorTrackers);
                } else if ("VASTAdTagURI".equalsIgnoreCase(tag)
                    || "AdTagURI".equalsIgnoreCase(tag)) {
                    wrapperUrl = readText(parser);
                } else if ("Tracking".equalsIgnoreCase(tag)) {
                    String event = parser.getAttributeValue(null, "event");
                    String trackingUrl = readText(parser);
                    addTracker(
                        event,
                        trackingUrl,
                        startTrackers,
                        firstQuartileTrackers,
                        midpointTrackers,
                        thirdQuartileTrackers,
                        completeTrackers
                    );
                } else if ("MediaFile".equalsIgnoreCase(tag)) {
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

        VastMediaFile selectedMediaFile = selectMediaFile(mediaFiles);
        if (selectedMediaFile != null) {
            return VastParsedResponse.inline(new VastAd(
                selectedMediaFile.getUrl(),
                impressions,
                startTrackers,
                firstQuartileTrackers,
                midpointTrackers,
                thirdQuartileTrackers,
                completeTrackers,
                errorTrackers
            ));
        }
        if (wrapperUrl != null && !wrapperUrl.isEmpty()) {
            return VastParsedResponse.wrapper(
                wrapperUrl,
                impressions,
                startTrackers,
                firstQuartileTrackers,
                midpointTrackers,
                thirdQuartileTrackers,
                completeTrackers,
                errorTrackers
            );
        }
        throw new VastLoadException(
            "VAST_NO_ADS_AFTER_WRAPPER (303): VAST response contained no playable ad"
        );
    }

    private VastMediaFile selectMediaFile(List<VastMediaFile> mediaFiles) {
        VastMediaFile selected = null;
        for (VastMediaFile mediaFile : mediaFiles) {
            if (!mediaFile.isPlayable()) {
                continue;
            }
            if (selected == null || mediaFile.score() > selected.score()) {
                selected = mediaFile;
            }
        }
        return selected;
    }

    private void addTracker(
        String event,
        String trackingUrl,
        List<String> startTrackers,
        List<String> firstQuartileTrackers,
        List<String> midpointTrackers,
        List<String> thirdQuartileTrackers,
        List<String> completeTrackers
    ) {
        if (trackingUrl == null || trackingUrl.isEmpty() || event == null) {
            return;
        }
        switch (event.toLowerCase()) {
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
            default:
                break;
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
