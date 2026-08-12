package com.smart.android.adsdk.internal;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

final class ManifestAdConfig {
    private static final String CHANNEL_ID_KEY =
        "com.smart.android.adsdk.CHANNEL_ID";
    private static final int GET_META_DATA_FLAG = 128;

    private final String channelId;

    private ManifestAdConfig(String channelId) {
        this.channelId = channelId;
    }

    static ManifestAdConfig read(Context context) {
        Bundle metadata = readMetadata(context);
        String channelId = readRequiredValue(metadata, CHANNEL_ID_KEY, "adChannelId");
        return new ManifestAdConfig(channelId);
    }

    String getChannelId() {
        return channelId;
    }

    @SuppressWarnings("deprecation")
    private static Bundle readMetadata(Context context) {
        try {
            ApplicationInfo applicationInfo;
            PackageManager packageManager = context.getPackageManager();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                applicationInfo = packageManager.getApplicationInfo(
                    context.getPackageName(),
                    PackageManager.ApplicationInfoFlags.of(GET_META_DATA_FLAG)
                );
            } else {
                applicationInfo = packageManager.getApplicationInfo(
                    context.getPackageName(),
                    GET_META_DATA_FLAG
                );
            }
            return applicationInfo.metaData == null ? Bundle.EMPTY : applicationInfo.metaData;
        } catch (PackageManager.NameNotFoundException error) {
            throw new IllegalArgumentException("Unable to read host manifest metadata", error);
        }
    }

    private static String readRequiredValue(
        Bundle metadata,
        String metadataName,
        String placeholderName
    ) {
        Object value = metadata.get(metadataName);
        String normalized = value == null ? "" : value.toString().trim();
        if (normalized.isEmpty() || isUnresolvedPlaceholder(normalized)) {
            throw new IllegalArgumentException(
                "Missing manifest placeholder " + placeholderName
            );
        }
        return normalized;
    }

    private static boolean isUnresolvedPlaceholder(String value) {
        return value.startsWith("${") && value.endsWith("}");
    }
}
