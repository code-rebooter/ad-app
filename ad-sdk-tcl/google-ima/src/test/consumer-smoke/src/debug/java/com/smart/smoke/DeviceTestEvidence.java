package com.smart.smoke;

import android.content.Context;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Locale;

final class DeviceTestEvidence {
    private static String apkHash;
    static synchronized String apkSha256(Context context) {
        if (apkHash != null) return apkHash;
        try (FileInputStream input = new FileInputStream(context.getApplicationInfo().sourceDir)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = new byte[65536]; int count;
            while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) hex.append(String.format(Locale.US, "%02x", value & 255));
            return apkHash = hex.toString();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
