package com.smart.android.adsdk.internal;

final class VastVerificationResource {
    private final String vendor;
    private final String apiFramework;
    private final String resourceUrl;

    VastVerificationResource(String vendor, String apiFramework, String resourceUrl) {
        this.vendor = vendor;
        this.apiFramework = apiFramework;
        this.resourceUrl = resourceUrl;
    }

    String getVendor() {
        return vendor;
    }

    String getApiFramework() {
        return apiFramework;
    }

    String getResourceUrl() {
        return resourceUrl;
    }
}
