package com.smart.android.adsdk.universal.internal;

import android.content.Context;
import android.provider.Settings;
import java.util.UUID;

final class IdentifierStore {
    private IdentifierStore() {}
    static synchronized String rdid(Context context) {
        var prefs = context.getSharedPreferences("google_tcl_ad", Context.MODE_PRIVATE);
        String stored = prefs.getString("aid", "");
        if (stored != null && !stored.isEmpty()) return stored;
        String value = UUID.randomUUID().toString(); prefs.edit().putString("aid", value).apply(); return value;
    }
    static String androidId(Context context) {
        try { String value = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID); return value == null ? "" : value; }
        catch (RuntimeException error) { return ""; }
    }
}
