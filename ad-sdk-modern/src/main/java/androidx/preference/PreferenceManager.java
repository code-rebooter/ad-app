package androidx.preference;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;
import java.lang.reflect.Method;

public final class PreferenceManager {
    private static final int SYSTEM_UID = 1000;

    private PreferenceManager() {
    }

    public static SharedPreferences getDefaultSharedPreferences(Context context) {
        return storageContext(context).getSharedPreferences(
            getDefaultSharedPreferencesName(context),
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

    private static Context storageContext(Context context) {
        Context appContext = context.getApplicationContext();
        if (appContext == null) {
            appContext = context;
        }
        if (Process.myUid() == SYSTEM_UID
            && Build.VERSION.SDK_INT >= 24
            && !appContext.isDeviceProtectedStorage()) {
            try {
                Context deviceContext = createDeviceProtectedStorageContext(appContext);
                if (deviceContext != null && deviceContext.isDeviceProtectedStorage()) {
                    return deviceContext;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return appContext;
    }

    private static Context createDeviceProtectedStorageContext(Context context) {
        try {
            Method method = Context.class.getDeclaredMethod("createDeviceProtectedStorageContext");
            method.setAccessible(true);
            return (Context) method.invoke(context);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("createDeviceProtectedStorageContext unavailable", error);
        }
    }
}
