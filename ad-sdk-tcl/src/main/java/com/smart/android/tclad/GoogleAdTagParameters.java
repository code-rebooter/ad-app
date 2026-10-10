package com.smart.android.tclad;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import okhttp3.HttpUrl;

/** Runtime parameters for direct Google Ad Manager VAST requests without IMA. */
public final class GoogleAdTagParameters {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Map<String, String> values;
    private GoogleAdTagParameters(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
    /** Call once per new ad opportunity, then reuse that URL for the same request. */
    public String applyTo(String adTagUrl) {
        HttpUrl original = HttpUrl.get(adTagUrl);
        HttpUrl.Builder result = original.newBuilder();
        for (String[] entry : new String[][]{{"env", "vp"}, {"gdfp_req", "1"}, {"output", "vast"}})
            if (original.queryParameter(entry[0]) == null || original.queryParameter(entry[0]).isEmpty())
                result.setQueryParameter(entry[0], entry[1]);
        long correlator;
        do { correlator = RANDOM.nextLong() & Long.MAX_VALUE; } while (correlator == 0);
        result.setQueryParameter("correlator", Long.toString(correlator));
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getValue() == null) result.removeAllQueryParameters(entry.getKey());
            else result.setQueryParameter(entry.getKey(), entry.getValue());
        }
        return result.build().toString();
    }
    public static final class Builder {
        private final Map<String, String> values = new LinkedHashMap<>();
        public Builder setApplication(String packageName, String appName) {
            if (packageName != null && !packageName.isEmpty()) values.put("msid", packageName);
            if (appName != null && !appName.isEmpty()) values.put("an", appName);
            return this;
        }
        public Builder setAutoplay(boolean autoplay) { values.put("vpa", autoplay ? "auto" : "click"); return this; }
        public Builder setMuted(boolean muted) { values.put("vpmute", muted ? "1" : "0"); return this; }
        /** Supply the actual Android advertising ID and its limit-ad-tracking flag together. */
        public Builder setAndroidAdvertisingId(String id, boolean limited) {
            values.put("idtype", "adid"); values.put("is_lat", limited ? "1" : "0");
            values.put("rdid", limited || id == null || id.isEmpty() ? null : id);
            return this;
        }
        /** Clear stale identifiers when host advertising-ID collection failed. */
        public Builder clearAdvertisingId() {
            values.put("rdid", null); values.put("idtype", null); values.put("is_lat", null); return this;
        }
        /** Known GDPR applicability and current TCF consent; empty consent clears stale values. */
        public Builder setGdprConsent(boolean applies, String consent) {
            values.put("gdpr", applies ? "1" : "0");
            values.put("gdpr_consent", applies && consent != null && !consent.isEmpty() ? consent : null);
            return this;
        }
        public Builder setAdditionalConsent(String consent) {
            values.put("addtl_consent", consent == null || consent.isEmpty() ? null : consent); return this;
        }
        public Builder setNonPersonalizedAds(boolean value) { values.put("npa", value ? "1" : "0"); return this; }
        public Builder setPageUrl(String value) { if (value != null && !value.isEmpty()) values.put("url", value); return this; }
        public GoogleAdTagParameters build() { return new GoogleAdTagParameters(values); }
    }
}
