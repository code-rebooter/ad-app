package com.smart.android.ad_app.google.ima.local;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Process;
import android.provider.Settings;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.UUID;

/** Shares the independent runtime's persisted Advertising ID and the existing TCL UID. */
public final class ImaSharedAdvertisingIdentity {
    private static final boolean CREATE_AD_ID_BY_CRYPTO_HASH = false;
    private static final boolean ENFORCE_UUID_VERSION_4 = false;
    private static final boolean ENABLE_AD_ID_PERMISSION_CHECK = true;
    private static final int AD_ID_PERMISSION_CHECK_MIN_OS = 14;
    private static final int AD_ID_PERMISSION_CHECK_MIN_TARGET_SDK = 33;
    private static final int GMS_ZERO_OUT_AD_ID_MIN_OS = 21;
    private static final boolean ENABLE_GMS_ZERO_OUT_AD_ID = false;
    private static final boolean ENABLE_CLEAR_AD_ID_WHEN_GLOBAL_LAT = false;

    private static final String SETTINGS = "independent_local_adid_settings_v2";
    private static final String PER_APP_LAT = "independent_local_per_app_lat_v1";
    private static final String CRYPTO = "independent_local_adid_crypto_v1";
    private static final String AD_ID = "adid_key";
    private static final String FAKE_AD_ID = "fake_adid_key";
    private static final String ENABLE_DEBUG = "enable_debug_logging";
    private static final String USING_CERT = "using_cert";
    private static final String ENABLE_LAT = "enable_limit_ad_tracking";
    private static final String RESET_COUNT = "adid_reset_count";
    private static final String DEBUG_SUFFIX = "10ca1ad1abe1";
    private static final String AD_ID_PERMISSION = "com.google.android.gms.permission.AD_ID";
    private static final String PRIVILEGED_AD_ID_PERMISSION =
            "android.permission.ACCESS_PRIVILEGED_AD_ID";
    private static final String PRIVILEGED_AD_ID_COMPAT_PERMISSION =
            "android.permission.ACCESS_PRIVILEGED_AD_ID_COMPAT";
    private static final String ZERO_ID = "00000000-0000-0000-0000-000000000000";

    private static final String TCL_SETTINGS = "google_tcl_ad";
    private static final String TCL_AID = "aid";

    private static final String PRIVATE_KEY = "p256_private_key";
    private static final String PUBLIC_KEY = "p256_public_key";

    private static final Object ADVERTISING_LOCK = new Object();
    private static final Object TCL_LOCK = new Object();

    private ImaSharedAdvertisingIdentity() {}

    public static final class AdvertisingInfo {
        public final String id;
        public final boolean limitedAdTracking;

        AdvertisingInfo(String actualId, boolean actualLimited) {
            id = actualId;
            limitedAdTracking = actualLimited;
        }
    }

    public static AdvertisingInfo readAdvertising(Context supplied) {
        Context context = applicationContext(supplied);
        synchronized (ADVERTISING_LOCK) {
            SharedPreferences settings = context.getSharedPreferences(
                    SETTINGS,
                    Context.MODE_PRIVATE
            );
            boolean globalLimited = readGlobalLat(settings);
            boolean perAppLimited = context.getSharedPreferences(
                    PER_APP_LAT,
                    Context.MODE_PRIVATE
            ).getBoolean(Integer.toString(Process.myUid()), false);
            boolean limited = globalLimited || perAppLimited;
            if (mustReturnZeroId(context, limited)) {
                return new AdvertisingInfo(ZERO_ID, limited);
            }
            boolean debug = isDebugIdEnabled(context, settings);
            String stored = settings.getString(debug ? FAKE_AD_ID : AD_ID, "");
            String id = stored == null || stored.isEmpty()
                    ? reset(context, settings, debug)
                    : stored;
            return new AdvertisingInfo(id, limited);
        }
    }

    public static String readOrCreateTclId(Context supplied) {
        Context context = applicationContext(supplied);
        synchronized (TCL_LOCK) {
            SharedPreferences settings = context.getSharedPreferences(
                    TCL_SETTINGS,
                    Context.MODE_PRIVATE
            );
            String stored = settings.getString(TCL_AID, "");
            if (stored != null && !stored.trim().isEmpty()) return stored;
            String generated = UUID.randomUUID().toString();
            settings.edit().putString(TCL_AID, generated).apply();
            return generated;
        }
    }

    public static boolean isUsable(String id) {
        return id != null && !id.trim().isEmpty() && !ZERO_ID.equalsIgnoreCase(id.trim());
    }

    private static Context applicationContext(Context supplied) {
        if (supplied == null) throw new IllegalArgumentException("context == null");
        Context application = supplied.getApplicationContext();
        return application == null ? supplied : application;
    }

    private static boolean readGlobalLat(SharedPreferences settings) {
        if (!settings.contains(ENABLE_LAT)) {
            settings.edit().putBoolean(ENABLE_LAT, false).apply();
        }
        return settings.getBoolean(ENABLE_LAT, false);
    }

    private static boolean mustReturnZeroId(Context context, boolean limited) {
        int sdk = ImaFirmwareInputs.sdkInt();
        if (limited && ENABLE_GMS_ZERO_OUT_AD_ID && sdk >= GMS_ZERO_OUT_AD_ID_MIN_OS) {
            return true;
        }
        if (!ENABLE_AD_ID_PERMISSION_CHECK || sdk < AD_ID_PERMISSION_CHECK_MIN_OS) {
            return false;
        }
        if (context.getApplicationInfo() == null ||
                context.getApplicationInfo().targetSdkVersion <
                        AD_ID_PERMISSION_CHECK_MIN_TARGET_SDK) {
            return false;
        }
        PackageManager packages = context.getPackageManager();
        String packageName = context.getPackageName();
        boolean privileged = packages.checkPermission(
                PRIVILEGED_AD_ID_PERMISSION,
                packageName
        ) == PackageManager.PERMISSION_GRANTED || packages.checkPermission(
                PRIVILEGED_AD_ID_COMPAT_PERMISSION,
                packageName
        ) == PackageManager.PERMISSION_GRANTED;
        return !privileged && packages.checkPermission(
                AD_ID_PERMISSION,
                packageName
        ) != PackageManager.PERMISSION_GRANTED;
    }

    private static boolean isDebugIdEnabled(
            Context context,
            SharedPreferences settings
    ) {
        boolean developer;
        try {
            developer = Settings.Global.getInt(
                    context.getContentResolver(),
                    "development_settings_enabled",
                    0
            ) != 0;
        } catch (RuntimeException failure) {
            developer = false;
        }
        return developer && settings.getBoolean(ENABLE_DEBUG, false);
    }

    private static String reset(
            Context context,
            SharedPreferences settings,
            boolean debug
    ) {
        String fakeId = debug ? debugId() : "";
        settings.edit().remove(AD_ID).remove(FAKE_AD_ID).apply();
        clearCrypto(context);
        boolean generatedByCertificate = false;
        String normalId;
        if (CREATE_AD_ID_BY_CRYPTO_HASH) {
            try {
                normalId = readOrCreateCrypto(context);
                generatedByCertificate = true;
            } catch (Exception failure) {
                normalId = UUID.randomUUID().toString();
            }
        } else {
            normalId = UUID.randomUUID().toString();
        }
        settings.edit().putInt(
                RESET_COUNT,
                settings.getInt(RESET_COUNT, 0) + 1
        ).apply();
        settings.edit()
                .putBoolean(ENABLE_DEBUG, debug)
                .putBoolean(USING_CERT, generatedByCertificate)
                .putString(AD_ID, normalId)
                .putString(FAKE_AD_ID, fakeId)
                .apply();
        if (ENABLE_CLEAR_AD_ID_WHEN_GLOBAL_LAT && settings.getBoolean(ENABLE_LAT, false)) {
            settings.edit().remove(AD_ID).remove(FAKE_AD_ID).apply();
        }
        return debug ? fakeId : normalId;
    }

    private static String debugId() {
        String random = UUID.randomUUID().toString();
        return random.substring(0, random.length() - DEBUG_SUFFIX.length()) + DEBUG_SUFFIX;
    }

    private static void clearCrypto(Context context) {
        context.getSharedPreferences(CRYPTO, Context.MODE_PRIVATE).edit().clear().commit();
    }

    private static String readOrCreateCrypto(Context context) throws Exception {
        SharedPreferences preferences = context.getSharedPreferences(
                CRYPTO,
                Context.MODE_PRIVATE
        );
        KeyPair pair = loadCrypto(preferences);
        if (pair == null) pair = generateCrypto(preferences);
        ECPublicKey publicKey = (ECPublicKey) pair.getPublic();
        byte[] compressed = new byte[33];
        compressed[0] = publicKey.getW().getAffineY().testBit(0) ? (byte) 0x03 : (byte) 0x02;
        byte[] x = publicKey.getW().getAffineX().toByteArray();
        int sourceOffset = Math.max(0, x.length - 32);
        int length = Math.min(32, x.length - sourceOffset);
        System.arraycopy(x, sourceOffset, compressed, 33 - length, length);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(compressed);
        byte[] uuidBytes = new byte[16];
        System.arraycopy(digest, 16, uuidBytes, 0, 16);
        if (ENFORCE_UUID_VERSION_4) {
            uuidBytes[6] = (byte) ((uuidBytes[6] & 0x0f) | 0x40);
            uuidBytes[8] = (byte) ((uuidBytes[8] & 0x3f) | 0x80);
        }
        ByteBuffer buffer = ByteBuffer.wrap(uuidBytes);
        return new UUID(buffer.getLong(), buffer.getLong()).toString();
    }

    private static KeyPair loadCrypto(SharedPreferences preferences) throws Exception {
        String privateText = preferences.getString(PRIVATE_KEY, null);
        String publicText = preferences.getString(PUBLIC_KEY, null);
        if (privateText == null || publicText == null) return null;
        KeyFactory factory = KeyFactory.getInstance("EC");
        return new KeyPair(
                factory.generatePublic(new X509EncodedKeySpec(
                        Base64.decode(publicText, Base64.DEFAULT)
                )),
                factory.generatePrivate(new PKCS8EncodedKeySpec(
                        Base64.decode(privateText, Base64.DEFAULT)
                ))
        );
    }

    private static KeyPair generateCrypto(SharedPreferences preferences) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        boolean stored = preferences.edit()
                .putString(PRIVATE_KEY, Base64.encodeToString(
                        pair.getPrivate().getEncoded(),
                        Base64.NO_WRAP
                ))
                .putString(PUBLIC_KEY, Base64.encodeToString(
                        pair.getPublic().getEncoded(),
                        Base64.NO_WRAP
                ))
                .commit();
        if (!stored) {
            throw new IllegalStateException("Failed to store the shared P-256 identity keyset");
        }
        return pair;
    }
}
