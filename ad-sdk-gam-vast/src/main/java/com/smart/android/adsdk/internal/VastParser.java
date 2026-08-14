package com.smart.android.adsdk.internal;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

final class VastParser {

    VastParsedResponse parse(String xml) throws VastLoadException {
        if (xml == null || xml.trim().isEmpty()) {
            throw new VastLoadException("VAST response was empty");
        }

        Document document = parseDocument(xml);
        Element root = document.getDocumentElement();
        List<Element> adElements = directChildren(root, "Ad");
        if (adElements.isEmpty()) {
            throw new VastLoadException(
                "VAST_NO_ADS_AFTER_WRAPPER (303): VAST response contained no playable ad",
                303,
                Collections.emptyList()
            );
        }

        VastParsedResponse wrapperResponse = null;
        boolean hasUnsupportedInteractiveCreative = false;
        boolean hasUnsupportedMediaFile = false;
        boolean hasUnexecutedInteractiveCreativeFile = false;
        List<String> accumulatedErrorTrackers = new ArrayList<>();
        List<SequencedAd> sequencedAds = new ArrayList<>();
        for (Element adElement : adElements) {
            Element inlineElement = firstDirectChild(adElement, "InLine");
            if (inlineElement != null) {
                InlineParseResult inlineResult = parseInline(inlineElement);
                accumulatedErrorTrackers.addAll(inlineResult.errorTrackers);
                if (inlineResult.hasUnsupportedInteractiveCreative) {
                    hasUnsupportedInteractiveCreative = true;
                }
                if (inlineResult.hasUnsupportedMediaFile) {
                    hasUnsupportedMediaFile = true;
                }
                if (inlineResult.hasUnexecutedInteractiveCreativeFile) {
                    hasUnexecutedInteractiveCreativeFile = true;
                }
                if (inlineResult.adBreak != null) {
                    int sequence = parseSequence(adElement.getAttribute("sequence"));
                    if (sequence > 0) {
                        sequencedAds.add(SequencedAd.inline(sequence, inlineResult.adBreak));
                    } else if (wrapperResponse == null && adElements.size() == 1) {
                        return VastParsedResponse.inline(inlineResult.adBreak);
                    } else if (sequencedAds.isEmpty()) {
                        return VastParsedResponse.inline(inlineResult.adBreak);
                    }
                }
            }

            Element wrapperElement = firstDirectChild(adElement, "Wrapper");
            if (wrapperElement != null) {
                VastParsedResponse parsedWrapper = parseWrapper(wrapperElement);
                accumulatedErrorTrackers.addAll(parsedWrapper.getErrorTrackers());
                int sequence = parseSequence(adElement.getAttribute("sequence"));
                if (sequence > 0) {
                    sequencedAds.add(SequencedAd.wrapper(sequence, parsedWrapper));
                } else if (wrapperResponse == null) {
                    wrapperResponse = parsedWrapper;
                }
            }
        }

        if (!sequencedAds.isEmpty()) {
            Collections.sort(sequencedAds, new Comparator<SequencedAd>() {
                @Override
                public int compare(SequencedAd left, SequencedAd right) {
                    return Integer.compare(left.sequence, right.sequence);
                }
            });
            return VastParsedResponse.sequencedSources(toSources(sequencedAds));
        }
        if (wrapperResponse != null) {
            return wrapperResponse;
        }
        if (hasUnexecutedInteractiveCreativeFile) {
            throw new VastLoadException(
                "VAST interactive creative file did not execute",
                409,
                accumulatedErrorTrackers
            );
        }
        if (hasUnsupportedInteractiveCreative) {
            throw new VastLoadException(
                "VAST unsupported interactive creative",
                403,
                accumulatedErrorTrackers
            );
        }
        if (hasUnsupportedMediaFile) {
            throw new VastLoadException(
                "VAST media file type is not supported",
                403,
                accumulatedErrorTrackers
            );
        }
        throw new VastLoadException(
            "VAST_NO_ADS_AFTER_WRAPPER (303): VAST response contained no playable ad",
            303,
            accumulatedErrorTrackers
        );
    }

    private InlineParseResult parseInline(Element inlineElement) {
        List<String> impressions = textValues(directChildren(inlineElement, "Impression"));
        List<String> errorTrackers = textValues(directChildren(inlineElement, "Error"));
        List<VastVerificationResource> verificationResources = new ArrayList<>();
        List<String> verificationNotExecutedTrackers = new ArrayList<>();
        parseVerificationResources(
            inlineElement,
            verificationResources,
            verificationNotExecutedTrackers
        );
        LinearCreative extensionTrackers = parseKnownExtensionTrackers(inlineElement);

        boolean hasUnsupportedInteractiveCreative = false;
        boolean hasUnsupportedMediaFile = false;
        boolean hasUnexecutedInteractiveCreativeFile = false;
        List<VastAd> ads = new ArrayList<>();
        for (Element linearElement : linearCreatives(inlineElement)) {
            LinearCreative creative = parseLinear(linearElement);
            creative.prepend(extensionTrackers);
            if (creative.hasUnsupportedInteractiveCreative) {
                hasUnsupportedInteractiveCreative = true;
            }
            if (creative.hasUnexecutedInteractiveCreativeFile) {
                hasUnexecutedInteractiveCreativeFile = true;
            }
            if (creative.hasUnsupportedMediaFile()) {
                hasUnsupportedMediaFile = true;
            }
            List<VastMediaFile> selectedMediaFiles = selectMediaFiles(creative.mediaFiles);
            if (selectedMediaFiles.isEmpty()) {
                continue;
            }
            VastMediaFile selectedMediaFile = selectedMediaFiles.get(0);
            VastAd ad = new VastAd(
                selectedMediaFile.getUrl(),
                selectedMediaFile.getPlayerMimeType(),
                selectedMediaFiles,
                mergeLists(impressions, creative.impressionTrackers),
                creative.creativeViewTrackers,
                creative.loadedTrackers,
                creative.startTrackers,
                creative.firstQuartileTrackers,
                creative.midpointTrackers,
                creative.thirdQuartileTrackers,
                creative.completeTrackers,
                creative.muteTrackers,
                creative.unmuteTrackers,
                creative.pauseTrackers,
                creative.resumeTrackers,
                creative.skipTrackers,
                creative.progressTrackers,
                creative.skipOffsetMs,
                creative.skipOffsetPercent,
                creative.clickThroughUrl,
                creative.clickTrackingUrls,
                creative.customClickUrls,
                verificationResources,
                verificationNotExecutedTrackers,
                viewableImpressionTrackers(inlineElement, "Viewable"),
                viewableImpressionTrackers(inlineElement, "NotViewable"),
                viewableImpressionTrackers(inlineElement, "ViewUndetermined"),
                errorTrackers,
                creative.hasUnsupportedInteractiveCreative,
                creative.hasUnexecutedInteractiveCreativeFile
            );
            ads.add(ad);
        }
        return new InlineParseResult(
            ads.isEmpty() ? null : new VastAdBreak(ads),
            errorTrackers,
            hasUnsupportedInteractiveCreative,
            hasUnsupportedMediaFile,
            hasUnexecutedInteractiveCreativeFile
        );
    }

    private VastParsedResponse parseWrapper(Element wrapperElement) {
        String wrapperUrl = firstDirectText(wrapperElement, "VASTAdTagURI");
        if (wrapperUrl.isEmpty()) {
            wrapperUrl = firstDirectText(wrapperElement, "AdTagURI");
        }
        List<String> impressions = textValues(directChildren(wrapperElement, "Impression"));
        List<String> errorTrackers = textValues(directChildren(wrapperElement, "Error"));
        List<VastVerificationResource> verificationResources = new ArrayList<>();
        List<String> verificationNotExecutedTrackers = new ArrayList<>();
        parseVerificationResources(
            wrapperElement,
            verificationResources,
            verificationNotExecutedTrackers
        );

        LinearCreative mergedCreative = new LinearCreative();
        for (Element linearElement : linearCreatives(wrapperElement)) {
            mergedCreative.merge(parseLinear(linearElement));
        }
        mergedCreative.prepend(parseKnownExtensionTrackers(wrapperElement));
        return VastParsedResponse.wrapper(
            wrapperUrl,
            mergeLists(impressions, mergedCreative.impressionTrackers),
            mergedCreative.creativeViewTrackers,
            mergedCreative.loadedTrackers,
            mergedCreative.startTrackers,
            mergedCreative.firstQuartileTrackers,
            mergedCreative.midpointTrackers,
            mergedCreative.thirdQuartileTrackers,
            mergedCreative.completeTrackers,
            mergedCreative.muteTrackers,
            mergedCreative.unmuteTrackers,
            mergedCreative.pauseTrackers,
            mergedCreative.resumeTrackers,
            mergedCreative.skipTrackers,
            mergedCreative.progressTrackers,
            mergedCreative.skipOffsetMs,
            mergedCreative.skipOffsetPercent,
            mergedCreative.clickThroughUrl,
            mergedCreative.clickTrackingUrls,
            mergedCreative.customClickUrls,
            verificationResources,
            verificationNotExecutedTrackers,
            viewableImpressionTrackers(wrapperElement, "Viewable"),
            viewableImpressionTrackers(wrapperElement, "NotViewable"),
            viewableImpressionTrackers(wrapperElement, "ViewUndetermined"),
            errorTrackers,
            parseBooleanAttribute(wrapperElement, "followAdditionalWrappers", true),
            parseBooleanAttribute(wrapperElement, "allowMultipleAds", false)
        );
    }

    private LinearCreative parseKnownExtensionTrackers(Element adOwnerElement) {
        LinearCreative creative = new LinearCreative();
        for (Element extensionsElement : directChildren(adOwnerElement, "Extensions")) {
            for (Element extensionElement : directChildren(extensionsElement, "Extension")) {
                for (Element customTrackingElement : directChildren(extensionElement, "CustomTracking")) {
                    for (Element trackingElement : directChildren(customTrackingElement, "Tracking")) {
                        addExtensionTracker(
                            extensionElement.getAttribute("type"),
                            trackingElement.getAttribute("event"),
                            textValue(trackingElement),
                            creative
                        );
                    }
                }
            }
        }
        return creative;
    }

    private void addExtensionTracker(
        String extensionType,
        String event,
        String trackingUrl,
        LinearCreative creative
    ) {
        if (trackingUrl == null || trackingUrl.isEmpty() || event == null) {
            return;
        }
        if ("show_ad".equalsIgnoreCase(event)) {
            creative.impressionTrackers.add(trackingUrl);
            return;
        }
        if ("loaded".equalsIgnoreCase(event)
            && "video_ad_loaded".equalsIgnoreCase(extensionType)) {
            creative.loadedTrackers.add(trackingUrl);
        }
    }

    private LinearCreative parseLinear(Element linearElement) {
        LinearCreative creative = new LinearCreative();
        creative.skipOffsetMs = parseSkipOffsetMs(linearElement.getAttribute("skipoffset"));
        creative.skipOffsetPercent = parseSkipOffsetPercent(linearElement.getAttribute("skipoffset"));

        Element trackingEvents = firstDirectChild(linearElement, "TrackingEvents");
        if (trackingEvents != null) {
            for (Element trackingElement : directChildren(trackingEvents, "Tracking")) {
                addTracker(
                    trackingElement.getAttribute("event"),
                    trackingElement.getAttribute("offset"),
                    textValue(trackingElement),
                    creative
                );
            }
        }

        Element videoClicks = firstDirectChild(linearElement, "VideoClicks");
        if (videoClicks != null) {
            creative.clickThroughUrl = firstDirectText(videoClicks, "ClickThrough");
            creative.clickTrackingUrls.addAll(textValues(directChildren(videoClicks, "ClickTracking")));
            creative.customClickUrls.addAll(textValues(directChildren(videoClicks, "CustomClick")));
        }

        Element mediaFiles = firstDirectChild(linearElement, "MediaFiles");
        if (mediaFiles != null) {
            for (Element interactiveElement : directChildren(mediaFiles, "InteractiveCreativeFile")) {
                if (!textValue(interactiveElement).isEmpty()) {
                    creative.hasUnsupportedInteractiveCreative = true;
                    creative.hasUnexecutedInteractiveCreativeFile = true;
                }
            }
            for (Element mediaFileElement : directChildren(mediaFiles, "MediaFile")) {
                String mediaUrl = textValue(mediaFileElement);
                if (mediaUrl.isEmpty()) {
                    continue;
                }
                VastMediaFile mediaFile = new VastMediaFile(
                    mediaUrl,
                    mediaFileElement.getAttribute("type"),
                    mediaFileElement.getAttribute("apiFramework"),
                    parseInt(mediaFileElement.getAttribute("width")),
                    parseInt(mediaFileElement.getAttribute("height")),
                    parseInt(mediaFileElement.getAttribute("bitrate"))
                );
                if (mediaFile.isInteractive()) {
                    creative.hasUnsupportedInteractiveCreative = true;
                } else {
                    creative.hasMediaFile = true;
                }
                creative.mediaFiles.add(mediaFile);
            }
        }
        return creative;
    }

    private void parseVerificationResources(
        Element root,
        List<VastVerificationResource> resources,
        List<String> verificationNotExecutedTrackers
    ) {
        Element adVerifications = firstDirectChild(root, "AdVerifications");
        if (adVerifications == null) {
            return;
        }
        for (Element verificationElement : directChildren(adVerifications, "Verification")) {
            String vendor = verificationElement.getAttribute("vendor");
            for (Element resourceElement : directChildren(verificationElement, "JavaScriptResource")) {
                String resourceUrl = textValue(resourceElement);
                if (!resourceUrl.isEmpty()) {
                    resources.add(new VastVerificationResource(
                        vendor,
                        resourceElement.getAttribute("apiFramework"),
                        resourceUrl
                    ));
                }
            }
            for (Element resourceElement : directChildren(verificationElement, "ExecutableResource")) {
                String resourceUrl = textValue(resourceElement);
                if (!resourceUrl.isEmpty()) {
                    resources.add(new VastVerificationResource(
                        vendor,
                        resourceElement.getAttribute("apiFramework"),
                        resourceUrl
                    ));
                }
            }
            Element trackingEvents = firstDirectChild(verificationElement, "TrackingEvents");
            if (trackingEvents != null) {
                for (Element trackingElement : directChildren(trackingEvents, "Tracking")) {
                    if ("verificationnotexecuted".equalsIgnoreCase(trackingElement.getAttribute("event"))) {
                        String trackingUrl = textValue(trackingElement);
                        if (!trackingUrl.isEmpty()) {
                            verificationNotExecutedTrackers.add(trackingUrl);
                        }
                    }
                }
            }
        }
    }

    private List<String> viewableImpressionTrackers(Element adOwnerElement, String trackerName) {
        List<String> trackers = new ArrayList<>();
        for (Element viewableImpression : directChildren(adOwnerElement, "ViewableImpression")) {
            trackers.addAll(textValues(directChildren(viewableImpression, trackerName)));
        }
        return trackers;
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

    private List<Element> linearCreatives(Element adOwnerElement) {
        List<Element> linearElements = new ArrayList<>();
        for (Element creativesElement : directChildren(adOwnerElement, "Creatives")) {
            for (Element creativeElement : directChildren(creativesElement, "Creative")) {
                Element linearElement = firstDirectChild(creativeElement, "Linear");
                if (linearElement != null) {
                    linearElements.add(linearElement);
                }
            }
        }
        return linearElements;
    }

    private void addTracker(
        String event,
        String offset,
        String trackingUrl,
        LinearCreative creative
    ) {
        if (trackingUrl == null || trackingUrl.isEmpty() || event == null) {
            return;
        }
        switch (event.toLowerCase()) {
            case "creativeview":
                creative.creativeViewTrackers.add(trackingUrl);
                break;
            case "loaded":
                creative.loadedTrackers.add(trackingUrl);
                break;
            case "start":
                creative.startTrackers.add(trackingUrl);
                break;
            case "firstquartile":
                creative.firstQuartileTrackers.add(trackingUrl);
                break;
            case "midpoint":
                creative.midpointTrackers.add(trackingUrl);
                break;
            case "thirdquartile":
                creative.thirdQuartileTrackers.add(trackingUrl);
                break;
            case "complete":
                creative.completeTrackers.add(trackingUrl);
                break;
            case "mute":
                creative.muteTrackers.add(trackingUrl);
                break;
            case "unmute":
                creative.unmuteTrackers.add(trackingUrl);
                break;
            case "pause":
                creative.pauseTrackers.add(trackingUrl);
                break;
            case "resume":
                creative.resumeTrackers.add(trackingUrl);
                break;
            case "skip":
                creative.skipTrackers.add(trackingUrl);
                break;
            case "progress":
                VastProgressTracker progressTracker = parseProgressTracker(trackingUrl, offset);
                if (progressTracker != null) {
                    creative.progressTrackers.add(progressTracker);
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

    private long parseSkipOffsetMs(String value) {
        if (value == null || value.trim().isEmpty() || value.trim().endsWith("%")) {
            return -1L;
        }
        return parseDurationMs(value.trim());
    }

    private float parseSkipOffsetPercent(String value) {
        if (value == null || value.trim().isEmpty()) {
            return -1f;
        }
        String normalized = value.trim();
        if (!normalized.endsWith("%")) {
            return -1f;
        }
        try {
            float percent = Float.parseFloat(normalized.substring(0, normalized.length() - 1)) / 100f;
            return percent >= 0f && percent <= 1f ? percent : -1f;
        } catch (NumberFormatException ignored) {
            return -1f;
        }
    }

    private Document parseDocument(String xml) throws VastLoadException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            disableExternalEntities(factory);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException | java.io.IOException error) {
            throw new VastLoadException(
                "Unable to parse VAST XML",
                error,
                100,
                Collections.emptyList()
            );
        }
    }

    private void disableExternalEntities(DocumentBuilderFactory factory) {
        trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        factory.setExpandEntityReferences(false);
    }

    private void trySetFeature(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (ParserConfigurationException ignored) {
        }
    }

    private List<Element> descendants(Element element, String name) {
        List<Element> elements = new ArrayList<>();
        NodeList nodeList = element.getElementsByTagName("*");
        for (int i = 0; i < nodeList.getLength(); i++) {
            Node node = nodeList.item(i);
            if (node instanceof Element && name.equalsIgnoreCase(localName(node.getNodeName()))) {
                elements.add((Element) node);
            }
        }
        return elements;
    }

    private List<Element> directChildren(Element element, String name) {
        List<Element> elements = new ArrayList<>();
        NodeList nodeList = element.getChildNodes();
        for (int i = 0; i < nodeList.getLength(); i++) {
            Node node = nodeList.item(i);
            if (node instanceof Element && name.equalsIgnoreCase(localName(node.getNodeName()))) {
                elements.add((Element) node);
            }
        }
        return elements;
    }

    private Element firstDirectChild(Element element, String name) {
        List<Element> children = directChildren(element, name);
        return children.isEmpty() ? null : children.get(0);
    }

    private Element firstDescendant(Element element, String name) {
        List<Element> elements = descendants(element, name);
        return elements.isEmpty() ? null : elements.get(0);
    }

    private List<String> textValues(List<Element> elements) {
        List<String> values = new ArrayList<>();
        for (Element element : elements) {
            String value = textValue(element);
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    private <T> List<T> mergeLists(List<T> first, List<T> second) {
        List<T> merged = new ArrayList<>();
        if (first != null) {
            merged.addAll(first);
        }
        if (second != null) {
            merged.addAll(second);
        }
        return merged;
    }

    private String firstText(Element element, String name) {
        Element target = firstDescendant(element, name);
        return target == null ? "" : textValue(target);
    }

    private String firstDirectText(Element element, String name) {
        Element target = firstDirectChild(element, name);
        return target == null ? "" : textValue(target);
    }

    private String textValue(Element element) {
        return element == null ? "" : element.getTextContent().trim();
    }

    private String localName(String name) {
        if (name == null) {
            return "";
        }
        int separator = name.indexOf(':');
        return separator >= 0 ? name.substring(separator + 1) : name;
    }

    private int parseInt(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private int parseSequence(String value) {
        int sequence = parseInt(value);
        return sequence > 0 ? sequence : -1;
    }

    private boolean parseBooleanAttribute(Element element, String name, boolean defaultValue) {
        String value = element.getAttribute(name);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        String normalized = value.trim();
        if ("1".equals(normalized)) {
            return true;
        }
        if ("0".equals(normalized)) {
            return false;
        }
        return Boolean.parseBoolean(normalized);
    }

    private List<VastAdSource> toSources(List<SequencedAd> sequencedAds) {
        List<VastAdSource> sources = new ArrayList<>();
        for (SequencedAd sequencedAd : sequencedAds) {
            sources.add(sequencedAd.source);
        }
        return sources;
    }

    private static final class InlineParseResult {
        private final VastAdBreak adBreak;
        private final List<String> errorTrackers;
        private final boolean hasUnsupportedInteractiveCreative;
        private final boolean hasUnsupportedMediaFile;
        private final boolean hasUnexecutedInteractiveCreativeFile;

        InlineParseResult(
            VastAdBreak adBreak,
            List<String> errorTrackers,
            boolean hasUnsupportedInteractiveCreative,
            boolean hasUnsupportedMediaFile,
            boolean hasUnexecutedInteractiveCreativeFile
        ) {
            this.adBreak = adBreak;
            this.errorTrackers = errorTrackers;
            this.hasUnsupportedInteractiveCreative = hasUnsupportedInteractiveCreative;
            this.hasUnsupportedMediaFile = hasUnsupportedMediaFile;
            this.hasUnexecutedInteractiveCreativeFile = hasUnexecutedInteractiveCreativeFile;
        }
    }

    private static final class SequencedAd {
        private final int sequence;
        private final VastAdSource source;

        private SequencedAd(int sequence, VastAdSource source) {
            this.sequence = sequence;
            this.source = source;
        }

        static SequencedAd inline(int sequence, VastAdBreak adBreak) {
            return new SequencedAd(sequence, VastAdSource.inline(sequence, VastParsedResponse.inline(adBreak)));
        }

        static SequencedAd wrapper(int sequence, VastParsedResponse wrapperResponse) {
            return new SequencedAd(sequence, VastAdSource.wrapper(sequence, wrapperResponse));
        }
    }

    private static final class LinearCreative {
        private final List<String> creativeViewTrackers = new ArrayList<>();
        private final List<String> impressionTrackers = new ArrayList<>();
        private final List<String> loadedTrackers = new ArrayList<>();
        private final List<String> startTrackers = new ArrayList<>();
        private final List<String> firstQuartileTrackers = new ArrayList<>();
        private final List<String> midpointTrackers = new ArrayList<>();
        private final List<String> thirdQuartileTrackers = new ArrayList<>();
        private final List<String> completeTrackers = new ArrayList<>();
        private final List<String> muteTrackers = new ArrayList<>();
        private final List<String> unmuteTrackers = new ArrayList<>();
        private final List<String> pauseTrackers = new ArrayList<>();
        private final List<String> resumeTrackers = new ArrayList<>();
        private final List<String> skipTrackers = new ArrayList<>();
        private final List<VastProgressTracker> progressTrackers = new ArrayList<>();
        private final List<String> clickTrackingUrls = new ArrayList<>();
        private final List<String> customClickUrls = new ArrayList<>();
        private final List<VastMediaFile> mediaFiles = new ArrayList<>();
        private long skipOffsetMs = -1L;
        private float skipOffsetPercent = -1f;
        private String clickThroughUrl;
        private boolean hasUnsupportedInteractiveCreative;
        private boolean hasUnexecutedInteractiveCreativeFile;
        private boolean hasMediaFile;

        private void prepend(LinearCreative other) {
            if (other == null) {
                return;
            }
            creativeViewTrackers.addAll(0, other.creativeViewTrackers);
            impressionTrackers.addAll(0, other.impressionTrackers);
            loadedTrackers.addAll(0, other.loadedTrackers);
            startTrackers.addAll(0, other.startTrackers);
            firstQuartileTrackers.addAll(0, other.firstQuartileTrackers);
            midpointTrackers.addAll(0, other.midpointTrackers);
            thirdQuartileTrackers.addAll(0, other.thirdQuartileTrackers);
            completeTrackers.addAll(0, other.completeTrackers);
            muteTrackers.addAll(0, other.muteTrackers);
            unmuteTrackers.addAll(0, other.unmuteTrackers);
            pauseTrackers.addAll(0, other.pauseTrackers);
            resumeTrackers.addAll(0, other.resumeTrackers);
            skipTrackers.addAll(0, other.skipTrackers);
            progressTrackers.addAll(0, other.progressTrackers);
            clickTrackingUrls.addAll(0, other.clickTrackingUrls);
            customClickUrls.addAll(0, other.customClickUrls);
            mediaFiles.addAll(0, other.mediaFiles);
            if (skipOffsetMs < 0L) {
                skipOffsetMs = other.skipOffsetMs;
            }
            if (skipOffsetPercent < 0f) {
                skipOffsetPercent = other.skipOffsetPercent;
            }
            if (clickThroughUrl == null) {
                clickThroughUrl = other.clickThroughUrl;
            }
            hasUnsupportedInteractiveCreative =
                hasUnsupportedInteractiveCreative || other.hasUnsupportedInteractiveCreative;
            hasUnexecutedInteractiveCreativeFile =
                hasUnexecutedInteractiveCreativeFile || other.hasUnexecutedInteractiveCreativeFile;
            hasMediaFile = hasMediaFile || other.hasMediaFile;
        }

        private void merge(LinearCreative other) {
            creativeViewTrackers.addAll(other.creativeViewTrackers);
            impressionTrackers.addAll(other.impressionTrackers);
            loadedTrackers.addAll(other.loadedTrackers);
            startTrackers.addAll(other.startTrackers);
            firstQuartileTrackers.addAll(other.firstQuartileTrackers);
            midpointTrackers.addAll(other.midpointTrackers);
            thirdQuartileTrackers.addAll(other.thirdQuartileTrackers);
            completeTrackers.addAll(other.completeTrackers);
            muteTrackers.addAll(other.muteTrackers);
            unmuteTrackers.addAll(other.unmuteTrackers);
            pauseTrackers.addAll(other.pauseTrackers);
            resumeTrackers.addAll(other.resumeTrackers);
            skipTrackers.addAll(other.skipTrackers);
            progressTrackers.addAll(other.progressTrackers);
            clickTrackingUrls.addAll(other.clickTrackingUrls);
            customClickUrls.addAll(other.customClickUrls);
            mediaFiles.addAll(other.mediaFiles);
            if (skipOffsetMs < 0L) {
                skipOffsetMs = other.skipOffsetMs;
            }
            if (skipOffsetPercent < 0f) {
                skipOffsetPercent = other.skipOffsetPercent;
            }
            if (clickThroughUrl == null) {
                clickThroughUrl = other.clickThroughUrl;
            }
            hasUnsupportedInteractiveCreative =
                hasUnsupportedInteractiveCreative || other.hasUnsupportedInteractiveCreative;
            hasUnexecutedInteractiveCreativeFile =
                hasUnexecutedInteractiveCreativeFile || other.hasUnexecutedInteractiveCreativeFile;
            hasMediaFile = hasMediaFile || other.hasMediaFile;
        }

        private boolean hasUnsupportedMediaFile() {
            return hasMediaFile && selectPlayableMediaFiles(mediaFiles).isEmpty();
        }
    }

    private static List<VastMediaFile> selectPlayableMediaFiles(List<VastMediaFile> mediaFiles) {
        List<VastMediaFile> playable = new ArrayList<>();
        for (VastMediaFile mediaFile : mediaFiles) {
            if (mediaFile.isPlayable()) {
                playable.add(mediaFile);
            }
        }
        return playable;
    }
}
