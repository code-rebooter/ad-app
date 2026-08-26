package androidx.preference;

import android.content.Context;
import android.content.SharedPreferences;

public final class PreferenceManager {
    private PreferenceManager() {
    }

    public static SharedPreferences getDefaultSharedPreferences(Context context) {
        Context appContext = context.getApplicationContext();
        if (appContext == null) {
            appContext = context;
        }
        return appContext.getSharedPreferences(
            appContext.getPackageName() + "_preferences",
            Context.MODE_PRIVATE
        );
    }

    public static String getDefaultSharedPreferencesName(Context context) {
        Context appContext = context.getApplicationContext();
        if (appContext == null) {
            appContext = context;
        }
        return appContext.getPackageName() + "_preferences";
    }
}
