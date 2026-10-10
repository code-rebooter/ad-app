package com.smart.android.tclad;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Host-supplied fields of the TCL 2.8.02 WATERFALL JSON request. */
public final class RequestParams {
    private final Map<String, String> values;
    private final Map<String, Object> typedValues;
    private RequestParams(Map<String, Object> source) {
        typedValues = Collections.unmodifiableMap(new LinkedHashMap<>(source));
        Map<String, String> strings = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) strings.put(entry.getKey(), entry.getValue().toString());
        values = Collections.unmodifiableMap(strings);
    }
    /** String view retained for existing custom providers. */
    public Map<String, String> getValues() { return values; }
    /** Preserves JSON number types and the original field capitalization. */
    public Map<String, Object> getTypedValues() { return typedValues; }
    public static final class Builder {
        private final Map<String, Object> values = new LinkedHashMap<>();
        public Builder() {
            for (String key : new String[]{"appCat", "appDomain", "area", "channelName", "contentEpisode",
                "contentGenre", "contentKeywords", "contentLanguage", "contentLength", "contentNetwork",
                "contentProducerName", "contentRating", "contentSeries", "contentSeason", "contentCat",
                "contentTitle", "contentId", "coppa", "dnum", "device", "deviceLanguage", "gdprConsent",
                "gdpr", "schain", "TUID", "mediaCp", "appStoreUrl", "deviceMake", "deviceModel", "gdprSource"})
                values.put(key, "");
            for (String key : new String[]{"contentLivestream", "ProductionQuality", "podSequence", "dnt"})
                values.put(key, 0);
        }
        private Builder put(String key, Object value) {
            if (value != null) values.put(key, value);
            return this;
        }
        public Builder setAppCat(String v) { return put("appCat", v); }
        public Builder setAppDomain(String v) { return put("appDomain", v); }
        public Builder setChannelName(String v) { return put("channelName", v); }
        public Builder setContentLanguage(String v) { return put("contentLanguage", v); }
        public Builder setContentTitle(String v) { return put("contentTitle", v); }
        public Builder setDevice(String v) { return put("device", v); }
        public Builder setDeviceLanguage(String v) { return put("deviceLanguage", v); }
        public Builder setDeviceMake(String v) { return put("deviceMake", v); }
        public Builder setDeviceModel(String v) { return put("deviceModel", v); }
        public Builder setArea(String v) { return put("area", v); }
        public Builder setGdpr(String v) { return put("gdpr", v); }
        public Builder setGdprConsent(String v) { return put("gdprConsent", v); }
        public Builder setGdprSource(String v) { return put("gdprSource", v); }
        public Builder setContentEpisode(String v) { return put("contentEpisode", v); }
        public Builder setContentGenre(String v) { return put("contentGenre", v); }
        public Builder setContentKeywords(String v) { return put("contentKeywords", v); }
        public Builder setContentLength(String v) { return put("contentLength", v); }
        public Builder setContentLivestream(Integer v) { return put("contentLivestream", v); }
        public Builder setContentNetwork(String v) { return put("contentNetwork", v); }
        public Builder setContentProducerName(String v) { return put("contentProducerName", v); }
        public Builder setContentRating(String v) { return put("contentRating", v); }
        public Builder setContentSeries(String v) { return put("contentSeries", v); }
        public Builder setContentSeason(String v) { return put("contentSeason", v); }
        public Builder setContentCat(String v) { return put("contentCat", v); }
        public Builder setContentId(String v) { return put("contentId", v); }
        public Builder setCoppa(String v) { return put("coppa", v); }
        public Builder setDnum(String v) { return put("dnum", v); }
        public Builder setProductionQuality(Integer v) { return put("ProductionQuality", v); }
        public Builder setPodSequence(Integer v) { return put("podSequence", v); }
        public Builder setSchain(String v) { return put("schain", v); }
        public Builder setTUID(String v) { return put("TUID", v); }
        public Builder setDnt(Integer v) { return put("dnt", v); }
        public Builder setMediaCp(String v) { return put("mediaCp", v); }
        public Builder setAppStoreUrl(String v) { return put("appStoreUrl", v); }
        public RequestParams build() { return new RequestParams(values); }
    }
}
