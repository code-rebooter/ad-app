package com.smart.android.adsdk.internal;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;

final class DeviceInfo {
    final String packageName;
    final String versionName;
    final long versionCode;
    final String androidId;
    final String uuid;
    final String mac;
    final String localIp;
    final String userAgent;
    final String make;
    final String model;
    final String osVersion;
    final String language;
    final int screenWidth;
    final int screenHeight;
    final int realScreenWidth;
    final int realScreenHeight;

    DeviceInfo(
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
        int screenHeight,
        int realScreenWidth,
        int realScreenHeight
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
        this.realScreenWidth = realScreenWidth;
        this.realScreenHeight = realScreenHeight;
    }

    static DeviceInfo empty() {
        return new DeviceInfo(
            "",
            "",
            0L,
            "",
            "00000000-0000-4000-8000-000000000000",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            0,
            0,
            0,
            0
        );
    }

    @SuppressLint("HardwareIds")
    static DeviceInfo collect(Context context) {
        Context appContext = context.getApplicationContext();
        if (appContext == null) {
            appContext = context;
        }
        PackageInfo packageInfo = readPackageInfo(appContext);
        String rawAndroidId = Settings.Secure.getString(
            appContext.getContentResolver(),
            Settings.Secure.ANDROID_ID
        );
        if (rawAndroidId == null) {
            rawAndroidId = "";
        }
        long hostVersionCode = versionCode(packageInfo);
        String versionName = packageInfo.versionName == null ? "" : packageInfo.versionName;
        int resourceWidth = appContext.getResources().getDisplayMetrics().widthPixels;
        int resourceHeight = appContext.getResources().getDisplayMetrics().heightPixels;
        int[] realScreenSize = resolveRealScreenSize(appContext);
        return new DeviceInfo(
            appContext.getPackageName(),
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
            Locale.getDefault().toString().replace("_", "-"),
            resourceWidth,
            resourceHeight,
            realScreenSize[0],
            realScreenSize[1]
        );
    }

    private static PackageInfo readPackageInfo(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to read host package information", error);
        }
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo packageInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return packageInfo.getLongVersionCode();
        }
        return packageInfo.versionCode;
    }

    private static String androidIdToUuid(String value) {
        if (value == null
            || value.trim().isEmpty()
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
        String base32 = sourceBuilder.substring(0, 32);
        return base32.substring(0, 8)
            + "-" + base32.substring(8, 12)
            + "-4" + base32.substring(12, 15)
            + "-8" + base32.substring(16, 19)
            + "-" + base32.substring(20, 32);
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
        }
        return "";
    }

    private static String resolveMacAddress() {
        String mac = readInterfaceAddress("eth0");
        if (isValidMacAddress(mac)) {
            return mac;
        }
        mac = getMacFromNetworkInterface("eth0");
        if (isValidMacAddress(mac)) {
            return mac;
        }
        mac = readInterfaceAddress("wlan0");
        if (isValidMacAddress(mac)) {
            return mac;
        }
        mac = getMacFromNetworkInterface("wlan0");
        return isValidMacAddress(mac) ? mac : "";
    }

    private static String readInterfaceAddress(String interfaceName) {
        File file = new File("/sys/class/net/" + interfaceName + "/address");
        if (!file.exists()) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String value = reader.readLine();
            if (value == null) {
                return "";
            }
            String normalized = value.trim().toUpperCase(Locale.US);
            return normalized.length() >= 17 ? normalized.substring(0, 17) : normalized;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String getMacFromNetworkInterface(String interfaceName) {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return "";
            }
            for (NetworkInterface networkInterface : Collections.list(interfaces)) {
                if (!interfaceName.equalsIgnoreCase(networkInterface.getName())) {
                    continue;
                }
                byte[] address = networkInterface.getHardwareAddress();
                if (address == null || address.length == 0) {
                    return "";
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
        }
        return "";
    }

    @SuppressWarnings("deprecation")
    private static int[] resolveRealScreenSize(Context context) {
        try {
            WindowManager windowManager = (WindowManager) context.getSystemService(
                Context.WINDOW_SERVICE
            );
            if (windowManager != null && windowManager.getDefaultDisplay() != null) {
                DisplayMetrics metrics = new DisplayMetrics();
                windowManager.getDefaultDisplay().getRealMetrics(metrics);
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    return new int[]{metrics.widthPixels, metrics.heightPixels};
                }
            }
        } catch (Exception ignored) {
        }
        return new int[]{
            context.getResources().getDisplayMetrics().widthPixels,
            context.getResources().getDisplayMetrics().heightPixels
        };
    }

    private static boolean isValidMacAddress(String mac) {
        if (mac == null || mac.isEmpty()) {
            return false;
        }
        if ("02:00:00:00:00:00".equalsIgnoreCase(mac)
            || "00:00:00:00:00:00".equalsIgnoreCase(mac)
            || "FF:FF:FF:FF:FF:FF".equalsIgnoreCase(mac)) {
            return false;
        }
        return mac.matches("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}");
    }

    private static String zeroUuid() {
        return "00000000-0000-4000-8000-000000000000";
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
