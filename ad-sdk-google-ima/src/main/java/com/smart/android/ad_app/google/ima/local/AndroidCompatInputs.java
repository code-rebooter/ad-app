package com.smart.android.ad_app.google.ima.local;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Calendar;
import java.util.Random;

/**
 * Platform-source portion of the audited IMA3.35.1 compatibility module.
 * Reads the actual supplied Context/system values. Does not rewrite identities,
 * invoke IMA, make HTTP requests, implement the GMS client, or select producers.
 * Task gates, caches, deadlines and presence handling belong to the caller.
 * Each getter follows its matching embedded-module method; platform errors are
 * not converted to invented values when the original module would throw.
 */
public final class AndroidCompatInputs {
    private AndroidCompatInputs() {}
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static final class AppInfo {
        public String apkSha256 = "E";
        public long versionCode = -1;
        public String versionName = "E";
        public String packageName = "E";
        public String installerPackage = "E";
    }

    public static final class ProcessInfo {
        public final long markedRandom;
        public final long importance;
        private ProcessInfo(long markedRandom, long importance) {
            this.markedRandom = markedRandom;
            this.importance = importance;
        }
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; ++i) {
            int value = bytes[i] & 255;
            out[2 * i] = HEX[value >>> 4];
            out[2 * i + 1] = HEX[value & 15];
        }
        return new String(out);
    }

    // Embedded n.a: knownDigest comes from the caller's genuine source result.
    @SuppressWarnings("deprecation")
    public static AppInfo appInfo(Context context, boolean allowLocalApkHash,
                                  String knownDigest) {
        AppInfo result = new AppInfo();
        if (knownDigest != null && !knownDigest.isEmpty()) {
            result.apkSha256 = knownDigest;
        } else if (allowLocalApkHash) {
            try {
                File file;
                boolean canHash;
                if (Build.VERSION.SDK_INT >= 8) {
                    file = new File(context.getPackageResourcePath());
                    canHash = readable(file);
                } else {
                    String name = context.getPackageName();
                    file = new File("/data/app/" + name + ".apk");
                    canHash = readable(file);
                    if (!canHash) {
                        file = new File("/data/app_private/" + name + ".apk");
                        canHash = readable(file);
                        if (!canHash) {
                            file = new File("/system/app/" + name + ".apk");
                            canHash = readable(file);
                        }
                    }
                }
                if (canHash) {
                    byte[] buffer = new byte[16384];
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    FileInputStream stream = new FileInputStream(file);
                    try {
                        int count;
                        while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
                    } catch (Throwable failure) {
                        // Embedded n.h closes before rethrow; close failure can replace failure.
                        stream.close();
                        throw failure;
                    }
                    stream.close();
                    result.apkSha256 = hex(digest.digest());
                }
            } catch (Exception ignored) {}
        }
        try {
            PackageInfo info;
            try {
                info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            } catch (PackageManager.NameNotFoundException absent) {
                info = null;
            }
            if (info != null) {
                result.versionCode = info.versionCode;
                result.versionName = info.versionName != null ? info.versionName : "";
            }
        } catch (Exception ignored) {}
        try {
            String name = context.getPackageName();
            String installer = context.getPackageManager().getInstallerPackageName(name);
            result.packageName = name;
            result.installerPackage = installer == null ? "unknown" : installer;
        } catch (Exception ignored) {}
        return result;
    }

    private static boolean readable(File file) {
        try { return file.exists() && file.canRead(); }
        catch (SecurityException denied) { return false; }
    }

    // q.a, aa.a, ae.a, ak.a.
    public static long unixSeconds() { return System.currentTimeMillis() / 1000; }
    public static String osRelease() { return com.smart.android.ad_app.google.ima.local.ImaFirmwareInputs.release(); }
    public static long[] screenPixels(Context context) {
        try {
            DisplayMetrics metrics = new DisplayMetrics();
            Display display = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
            try { display.getRealMetrics(metrics); }
            catch (NoSuchMethodError oldApi) { display.getMetrics(metrics); }
            return new long[] {metrics.widthPixels, metrics.heightPixels};
        } catch (UnsupportedOperationException unsupported) { return null; }
    }
    public static long timezoneMinutesBiased() {
        Calendar now = Calendar.getInstance();
        return (now.get(Calendar.ZONE_OFFSET) + now.get(Calendar.DST_OFFSET)) / 60000 + 1440L;
    }

    // ag.a. A null signatures array intentionally is not turned into null.
    @SuppressWarnings("deprecation")
    public static String firstSignerSha1Hex(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 64);
            if (info.signatures.length > 0) {
                return hex(MessageDigest.getInstance("SHA-1").digest(info.signatures[0].toByteArray()));
            }
            return null;
        } catch (PackageManager.NameNotFoundException | UnsupportedOperationException
                 | NoSuchAlgorithmException unavailable) { return null; }
    }

    // o.a: temperature uses the original raw integer (normally tenths Celsius).
    public static int[] battery(Context context) {
        Intent state = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int temperature = state.getIntExtra("temperature", Integer.MIN_VALUE);
        int status;
        switch (state.getIntExtra("status", 1)) {
            case 2: status = 2; break;
            case 3: case 4: status = 1; break;
            case 5: status = 3; break;
            default: status = 0;
        }
        int level = state.getIntExtra("level", -1);
        double scale = state.getIntExtra("scale", -1);
        int percent = level >= 0 && scale > 0 ? (int) Math.round((level / scale) * 100.0) : -1;
        return new int[] {status, percent, temperature};
    }

    // z.a.
    public static long orientation(Context context) {
        switch (context.getResources().getConfiguration().orientation) {
            case 1: return 1;
            case 2: return 3;
            case 3: return 20;
            default: return -1;
        }
    }

    // ab.a: original DEX retains importance=-1 and foreground=false on no match.
    public static ProcessInfo process(Context context, boolean matchCurrentPid) {
        String name = context.getPackageName();
        long importance = -1;
        boolean foreground = false;
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningAppProcessInfo item : manager.getRunningAppProcesses()) {
            if ((matchCurrentPid && Process.myPid() == item.pid)
                    || (!matchCurrentPid && name.equals(item.processName))) {
                importance = item.importance;
                foreground = importance == 100 || importance == 200;
                break;
            }
        }
        long random = new Random().nextInt(64) + 1;
        return new ProcessInfo(foreground ? random | 16L : random & -17L, importance);
    }

    // p.a and u.a: use real platform values, retaining the audited predicates.
    public static long availableProcessors() { return Runtime.getRuntime().availableProcessors(); }
    public static long environmentMarker() {
        long result = 0;
        try { if (new File("/system/app/Superuser.apk").exists()) result = 1; }
        catch (Exception ignored) {}
        if (com.smart.android.ad_app.google.ima.local.ImaFirmwareInputs.model().equals("google_sdk") || com.smart.android.ad_app.google.ima.local.ImaFirmwareInputs.model().equals("Emulator")
                || com.smart.android.ad_app.google.ima.local.ImaFirmwareInputs.model().equals("sdk")
                || com.smart.android.ad_app.google.ima.local.ImaFirmwareInputs.hardware().equals("goldfish")
                || com.smart.android.ad_app.google.ima.local.ImaFirmwareInputs.hardware().equals("ranchu")) result = 2;
        return result;
    }

    // an.a and m.a: null sticky intents and missing Settings are caller failures.
    public static boolean usbConnected(Context context) {
        return context.registerReceiver(null, new IntentFilter("android.hardware.usb.action.USB_STATE"))
                .getExtras().getBoolean("connected");
    }
    public static boolean adbEnabled(Context context) throws Settings.SettingNotFoundException {
        if (Build.VERSION.SDK_INT >= 17)
            return Settings.Global.getInt(context.getContentResolver(), "adb_enabled") == 1;
        if (Build.VERSION.SDK_INT >= 3)
            return Settings.Secure.getInt(context.getContentResolver(), "adb_enabled") == 1;
        throw new Settings.SettingNotFoundException("adb_enabled");
    }

    // ai.a and af.a.
    public static long[] clocks() {
        return new long[] {SystemClock.elapsedRealtime(), SystemClock.uptimeMillis()};
    }
    @SuppressWarnings("deprecation")
    public static long screenLockBits(Context context, boolean sourceFlag) {
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        long result = !((PowerManager) context.getSystemService(Context.POWER_SERVICE)).isScreenOn() ? 2 : 0;
        if (keyguard != null && ((Build.VERSION.SDK_INT >= 28 && keyguard.isKeyguardLocked())
                || (Build.VERSION.SDK_INT < 28 && keyguard.inKeyguardRestrictedInputMode()))) result |= 1;
        return sourceFlag ? result : result | 4;
    }

    // y.a: caller supplies genuine timing inputs from its network observer.
    public static long[] network(NetworkCapabilities network, long firstTime, long secondTime) {
        long type = -1;
        if (network != null) {
            if (network.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) type = 2;
            else if (network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) type = 1;
            else if (network.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) type = 0;
        }
        return new long[] {type, firstTime, secondTime};
    }

    // ac.a, v.a and l.a.
    public static boolean httpProxyPropertyPresent() {
        String proxy = System.getProperty("http.proxyHost");
        return proxy != null && proxy.length() > 0;
    }
    public static boolean hasLauncherActivity(Context context) {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setPackage(context.getPackageName());
        return !context.getPackageManager().queryIntentActivities(intent, 64).isEmpty();
    }
    public static String compatibilityModuleVersion() { return "1.460000000"; }
}
