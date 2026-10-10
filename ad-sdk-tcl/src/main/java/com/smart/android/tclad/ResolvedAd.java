package com.smart.android.tclad;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.tcl.ff.component.media.processor.address.data.MixedWaterfallVastUrlInfo;

/** Input at TCL's successful ad-tag response boundary. Contains no VAST processing. */
public final class ResolvedAd {
    private final String json;
    private ResolvedAd(String json) {
        MixedWaterfallVastUrlInfo parsed = new Gson().fromJson(json, MixedWaterfallVastUrlInfo.class);
        if (parsed == null || parsed.getData() == null || parsed.getData().getBeaconType() != 2)
            throw new IllegalArgumentException("The isolated runtime accepts only the TCL client VAST branch (beaconingType=2)");
        this.json = json;
    }
    public static ResolvedAd fromTclResponse(String responseJson) {
        if (responseJson == null) throw new IllegalArgumentException("responseJson is required");
        return new ResolvedAd(responseJson);
    }
    public MixedWaterfallVastUrlInfo toOriginalResponse() {
        return new Gson().fromJson(json, MixedWaterfallVastUrlInfo.class);
    }
    public String toJson() { return json; }
    public static final class Builder {
        private final JsonObject data = new JsonObject();
        /** Selects the verified TCL client VAST engine (beaconingType=2). */
        public Builder(String adUrl) {
            data.addProperty("adsUrl", adUrl);
            data.addProperty("beaconingType", 2);
            data.addProperty("adType", 1);
            data.addProperty("adPlatformCode", "0");
            data.addProperty("adUnitID", "");
            data.addProperty("guaranteeLink", "");
        }
        public Builder setAdUnitId(String value) { data.addProperty("adUnitID", value); return this; }
        public Builder setAdPlatformCode(String value) { data.addProperty("adPlatformCode", value); return this; }
        public Builder setGuaranteeLink(String value) { data.addProperty("guaranteeLink", value); return this; }
        public ResolvedAd build() {
            JsonObject response = new JsonObject();
            response.addProperty("code", "100000");
            response.add("data", data.deepCopy());
            return new ResolvedAd(response.toString());
        }
    }
}
