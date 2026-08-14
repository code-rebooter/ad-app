package com.smart.android.adsdk.internal;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

final class VmapParser {
    private final VastParser vastParser = new VastParser();

    VmapAdSchedule parse(String xml) throws VastLoadException {
        if (xml == null || xml.trim().isEmpty()) {
            throw new VastLoadException("VMAP response was empty");
        }

        List<VmapAdBreak> breaks = new ArrayList<>();
        try {
            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(new StringReader(xml));
            int eventType;
            while ((eventType = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (eventType != XmlPullParser.START_TAG) {
                    continue;
                }
                if ("AdBreak".equalsIgnoreCase(localName(parser.getName()))) {
                    breaks.add(readAdBreak(parser));
                }
            }
        } catch (IOException | XmlPullParserException error) {
            throw new VastLoadException("Unable to parse VMAP XML", error);
        }
        return new VmapAdSchedule(breaks);
    }

    private VmapAdBreak readAdBreak(XmlPullParser parser)
        throws IOException, XmlPullParserException, VastLoadException {
        int adBreakDepth = parser.getDepth();
        VmapTimeOffset timeOffset = parseTimeOffset(parser.getAttributeValue(null, "timeOffset"));
        String breakId = parser.getAttributeValue(null, "breakId");
        String breakType = parser.getAttributeValue(null, "breakType");
        String adTagUrl = null;
        VastParsedResponse inlineVast = null;
        List<String> breakStartTrackers = new ArrayList<>();
        List<String> breakEndTrackers = new ArrayList<>();
        List<String> errorTrackers = new ArrayList<>();

        int eventType;
        while ((eventType = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.END_TAG && parser.getDepth() == adBreakDepth) {
                break;
            }
            if (eventType != XmlPullParser.START_TAG) {
                continue;
            }

            String tag = localName(parser.getName());
            if ("AdSource".equalsIgnoreCase(tag)) {
                AdSourceData adSourceData = readAdSource(parser);
                if (adSourceData.adTagUrl != null && !adSourceData.adTagUrl.isEmpty()) {
                    adTagUrl = adSourceData.adTagUrl;
                }
                if (adSourceData.inlineVast != null) {
                    inlineVast = adSourceData.inlineVast;
                }
            } else if ("Tracking".equalsIgnoreCase(tag)) {
                String event = parser.getAttributeValue(null, "event");
                String trackingUrl = readText(parser);
                addBreakTracker(event, trackingUrl, breakStartTrackers, breakEndTrackers, errorTrackers);
            }
        }

        return new VmapAdBreak(
            timeOffset,
            breakId,
            breakType,
            adTagUrl,
            inlineVast,
            breakStartTrackers,
            breakEndTrackers,
            errorTrackers
        );
    }

    private AdSourceData readAdSource(XmlPullParser parser)
        throws IOException, XmlPullParserException, VastLoadException {
        int adSourceDepth = parser.getDepth();
        String adTagUrl = null;
        VastParsedResponse inlineVast = null;

        int eventType;
        while ((eventType = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.END_TAG && parser.getDepth() == adSourceDepth) {
                break;
            }
            if (eventType != XmlPullParser.START_TAG) {
                continue;
            }

            String tag = localName(parser.getName());
            if ("AdTagURI".equalsIgnoreCase(tag)) {
                String value = readText(parser);
                if (!value.isEmpty()) {
                    adTagUrl = value;
                }
            } else if ("VASTData".equalsIgnoreCase(tag)
                || "VASTAdData".equalsIgnoreCase(tag)) {
                String vastXml = readInnerXml(parser);
                if (!vastXml.trim().isEmpty()) {
                    inlineVast = vastParser.parse(vastXml);
                }
            }
        }
        return new AdSourceData(adTagUrl, inlineVast);
    }

    private void addBreakTracker(
        String event,
        String trackingUrl,
        List<String> breakStartTrackers,
        List<String> breakEndTrackers,
        List<String> errorTrackers
    ) {
        if (event == null || trackingUrl == null || trackingUrl.isEmpty()) {
            return;
        }
        switch (event.toLowerCase()) {
            case "breakstart":
                breakStartTrackers.add(trackingUrl);
                break;
            case "breakend":
                breakEndTrackers.add(trackingUrl);
                break;
            case "error":
                errorTrackers.add(trackingUrl);
                break;
            default:
                break;
        }
    }

    private VmapTimeOffset parseTimeOffset(String value) {
        if (value == null || value.trim().isEmpty() || "start".equalsIgnoreCase(value.trim())) {
            return VmapTimeOffset.start();
        }
        String normalized = value.trim();
        if ("end".equalsIgnoreCase(normalized)) {
            return VmapTimeOffset.end();
        }
        if (normalized.endsWith("%")) {
            try {
                float percent = Float.parseFloat(normalized.substring(0, normalized.length() - 1)) / 100f;
                return VmapTimeOffset.percentage(percent);
            } catch (NumberFormatException ignored) {
                return VmapTimeOffset.start();
            }
        }
        long offsetMs = parseDurationMs(normalized);
        return offsetMs >= 0L ? VmapTimeOffset.absolute(offsetMs) : VmapTimeOffset.unscheduled();
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

    private String readInnerXml(XmlPullParser parser) throws IOException, XmlPullParserException {
        StringBuilder builder = new StringBuilder();
        int startDepth = parser.getDepth();
        int eventType;
        while ((eventType = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                appendStartTag(builder, parser);
            } else if (eventType == XmlPullParser.TEXT) {
                builder.append(escapeXml(parser.getText()));
            } else if (eventType == XmlPullParser.CDSECT) {
                builder.append("<![CDATA[").append(parser.getText()).append("]]>");
            } else if (eventType == XmlPullParser.END_TAG) {
                if (parser.getDepth() == startDepth) {
                    break;
                }
                builder.append("</").append(parser.getName()).append('>');
            }
        }
        return builder.toString();
    }

    private void appendStartTag(StringBuilder builder, XmlPullParser parser) {
        builder.append('<').append(parser.getName());
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            builder.append(' ')
                .append(parser.getAttributeName(i))
                .append("=\"")
                .append(escapeXml(parser.getAttributeValue(i)))
                .append('"');
        }
        builder.append('>');
    }

    private String escapeXml(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    private String localName(String name) {
        if (name == null) {
            return "";
        }
        int separator = name.indexOf(':');
        return separator >= 0 ? name.substring(separator + 1) : name;
    }

    private static final class AdSourceData {
        private final String adTagUrl;
        private final VastParsedResponse inlineVast;

        AdSourceData(String adTagUrl, VastParsedResponse inlineVast) {
            this.adTagUrl = adTagUrl;
            this.inlineVast = inlineVast;
        }
    }
}
