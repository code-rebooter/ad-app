package com.smart.android.hq008flow.internal;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.provider.Settings;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;

public final class DeviceInfo {
    public final String packageName;
    public final String versionName;
    public final long versionCode;
    public final String androidId;
    public final String uuid;
    public final String mac;
    public final String localIp;
    public final String userAgent;
    public final String make;
    public final String model;
    public final String osVersion;
    public final String language;
    public final int screenWidth;
    public final int screenHeight;

    private DeviceInfo(
            String packageName,
            String versionName,
            long versionCode,
            String androidId,
            String uuid,
            String mac,
            String localIp,
            String userAgent,
            String make,
            String model,
            String osVersion,
            String language,
            int screenWidth,
            int screenHeight
    ) {
        this.packageName = packageName;
        this.versionName = versionName;
        this.versionCode = versionCode;
        this.androidId = androidId;
        this.uuid = uuid;
        this.mac = mac;
        this.localIp = localIp;
        this.userAgent = userAgent;
        this.make = make;
        this.model = model;
        this.osVersion = osVersion;
        this.language = language;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
    }

    @SuppressLint("HardwareIds")
    @SuppressWarnings("deprecation")
    public static DeviceInfo collect(Context context) {
        PackageInfo packageInfo;
        try {
            packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to read host package information", error);
        }
        String rawAndroidId = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ANDROID_ID
        );
        if (rawAndroidId == null) {
            rawAndroidId = "";
        }
        long hostVersionCode;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            hostVersionCode = packageInfo.getLongVersionCode();
        } else {
            //noinspection deprecation
            hostVersionCode = packageInfo.versionCode;
        }
        String versionName = packageInfo.versionName == null ? "" : packageInfo.versionName;
        return new DeviceInfo(
                context.getPackageName(),
                versionName,
                hostVersionCode,
                rawAndroidId.isEmpty() ? "unknown_device" : rawAndroidId,
                androidIdToUuid(rawAndroidId),
                resolveMacAddress(),
                resolveLocalIp(),
                valueOrEmpty(System.getProperty("http.agent")),
                valueOrEmpty(Build.MANUFACTURER),
                valueOrEmpty(Build.MODEL),
                valueOrEmpty(Build.VERSION.RELEASE),
                Locale.getDefault().toLanguageTag(),
                context.getResources().getDisplayMetrics().widthPixels,
                context.getResources().getDisplayMetrics().heightPixels
        );
    }

    private static String androidIdToUuid(String value) {
        if (value == null || value.trim().isEmpty()
                || "9774d56d682f617c".equalsIgnoreCase(value.trim())) {
            return zeroUuid();
        }
        String hex = value.replaceAll("[^0-9a-fA-F]", "").toLowerCase(Locale.US);
        if (hex.isEmpty()) {
            return zeroUuid();
        }
        String reversed = new StringBuilder(hex).reverse().toString();
        StringBuilder sourceBuilder = new StringBuilder();
        while (sourceBuilder.length() < 32) {
            sourceBuilder.append(hex);
            if (sourceBuilder.length() < 32) {
                sourceBuilder.append(reversed);
            }
        }
        String source = sourceBuilder.substring(0, 32);
        return source.substring(0, 8)
                + "-" + source.substring(8, 12)
                + "-4" + source.substring(13, 16)
                + "-8" + source.substring(17, 20)
                + "-" + source.substring(20, 32);
    }

    private static String resolveLocalIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return "";
            }
            for (NetworkInterface networkInterface : Collections.list(interfaces)) {
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                for (InetAddress address : Collections.list(addresses)) {
                    if (!address.isLoopbackAddress() && address instanceof Inet4Address) {
                        return valueOrEmpty(address.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
            // Device information is best effort.
        }
        return "";
    }

    private static String resolveMacAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return "";
            }
            for (NetworkInterface networkInterface : Collections.list(interfaces)) {
                if (networkInterface.isLoopback()) {
                    continue;
                }
                byte[] address = networkInterface.getHardwareAddress();
                if (address == null || address.length == 0) {
                    continue;
                }
                StringBuilder builder = new StringBuilder();
                for (byte item : address) {
                    if (builder.length() > 0) {
                        builder.append(':');
                    }
                    builder.append(String.format(Locale.US, "%02X", item & 0xFF));
                }
                return builder.toString();
            }
        } catch (Exception ignored) {
            // MAC is optional for the backend contract.
        }
        return "";
    }

    private static String zeroUuid() {
        return "00000000-0000-4000-8000-000000000000";
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
