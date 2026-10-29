package com.smart.android.ad_app.google.ima.local.signal.android;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Pure local base-APK digest input, independent of GASS and its attest state.
 * The algorithm is R69 GMS 23.48.16 aaxb.b(File): SHA-256 over all sourceDir
 * bytes, then vtq.b(byte[]) lower-case hex. This does not emulate GMS cache,
 * GASS protobuf/status, retries, appdigest feature flags or task completion.
 * Must be scheduled on a caller-owned worker rather than the Android UI thread.
 */
public final class LocalAppDigestSource {
    private LocalAppDigestSource() {}

    /** Read the real caller APK path. Split APKs are not concatenated: the
     * audited aaxb.c file branch hashes PackageInfo.applicationInfo.sourceDir.
     */
    public static String currentBaseApk(Context actualContext)
            throws IOException, NoSuchAlgorithmException {
        if (actualContext == null) throw new NullPointerException("Actual Context required");
        ApplicationInfo info = actualContext.getApplicationInfo();
        if (info == null || info.sourceDir == null || info.sourceDir.isEmpty())
            throw new IOException("Actual base APK sourceDir unavailable");
        return hashFile(new File(info.sourceDir));
    }

    /** No Binder service, PackageManager checksum provider, network, Google
     * client, cached historical signal or invented success/attestation status.
     * A read/change failure is explicit and never represented as a success.
     */
    public static String hashFile(File actualBaseApk)
            throws IOException, NoSuchAlgorithmException {
        if (actualBaseApk == null) throw new NullPointerException("Actual APK file required");
        if (!actualBaseApk.isFile() || !actualBaseApk.canRead())
            throw new IOException("Actual base APK is not a readable file");
        long expectedLength = actualBaseApk.length();
        long expectedModified = actualBaseApk.lastModified();
        MessageDigest digest = null;
        NoSuchAlgorithmException last = null;
        for (int attempt = 0; attempt < 2 && digest == null; attempt++) {
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException unavailable) { last = unavailable; }
        }
        if (digest == null) throw last;
        byte[] buffer = new byte[0x4000];
        long consumed = 0;
        try (FileInputStream input = new FileInputStream(actualBaseApk)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
                consumed += count;
            }
        }
        if (consumed != expectedLength || actualBaseApk.length() != expectedLength
                || actualBaseApk.lastModified() != expectedModified)
            throw new IOException("Base APK changed during local digest computation");
        return lowerHex(digest.digest());
    }

    private static String lowerHex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 255;
            chars[i * 2] = alphabet[value >>> 4];
            chars[i * 2 + 1] = alphabet[value & 15];
        }
        return new String(chars);
    }
}
