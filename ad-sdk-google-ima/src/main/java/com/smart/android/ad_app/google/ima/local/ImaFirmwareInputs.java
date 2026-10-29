package com.smart.android.ad_app.google.ima.local;

import android.webkit.WebView;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

/** Uses the host's existing shipped-firmware policy before source values are encoded.
 * It does not change system Build fields, GMS, consent, permission or playback state.
 */
public final class ImaFirmwareInputs {
    public static final String POLICY = "existing-host-firmware-policy-before-generation-v4";
    private static final String HOST = "com.smart.android.ad_app.Hq008XhsxAarRuntimeBridge";
    private static volatile Bindings bindings;
    private ImaFirmwareInputs() {}

    private static final class Bindings {
        final Class<?> host;
        final Method normalizeUrl;
        Bindings() throws ReflectiveOperationException {
            host = Class.forName(HOST, true, ImaFirmwareInputs.class.getClassLoader());
            for (String name : new String[]{"getAndroidVersionRelease", "getAndroidDeviceModel",
                    "getAndroidBuildId", "getAndroidBuildFingerprint", "getAndroidBrand", "getAndroidManufacturer",
                    "getAndroidDevice", "getAndroidProduct", "getAndroidHardware",
                    "getEffectiveUserAgent"}) {
                Method method = host.getMethod(name);
                if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != String.class)
                    throw new NoSuchMethodException(name);
            }
            host.getMethod("getAndroidSdkInt");
            host.getMethod("correctAndroidClientHints", Map.class);
            host.getMethod("getAndroidSystemProperty", String.class, String.class);
            Class<?> urls = Class.forName("com.smart.android.ad_app.HaierAarUaPayloadNormalizerKt",
                    true, ImaFirmwareInputs.class.getClassLoader());
            normalizeUrl = urls.getMethod("normalizeAarUrlUa", String.class, String.class,
                    String.class, String.class, String.class);
        }
    }
    private static Bindings bindings() {
        Bindings value = bindings;
        if (value != null) return value;
        synchronized (ImaFirmwareInputs.class) {
            value = bindings;
            if (value == null) {
                try { value = new Bindings(); }
                catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Current host firmware bridge/URL policy is required", failure);
                }
                bindings = value;
            }
            return value;
        }
    }
    public static void requireHostPolicy() { bindings(); fingerprint(); }
    private static Object host(String name, Class<?>[] types, Object... args) {
        try { return bindings().host.getMethod(name, types).invoke(null, args); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("Host firmware getter failed: " + name, cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Host firmware getter unavailable: " + name, failure);
        }
    }
    private static String string(String name) { return (String) host(name, new Class<?>[0]); }
    public static int sdkInt() { return ((Number) host("getAndroidSdkInt", new Class<?>[0])).intValue(); }
    public static String release() { return string("getAndroidVersionRelease"); }
    public static String model() { return string("getAndroidDeviceModel"); }
    public static String buildId() { return string("getAndroidBuildId"); }
    public static String fingerprint() { return string("getAndroidBuildFingerprint"); }
    public static String brand() { return string("getAndroidBrand"); }
    public static String manufacturer() { return string("getAndroidManufacturer"); }
    public static String device() { return string("getAndroidDevice"); }
    public static String product() { return string("getAndroidProduct"); }
    public static String hardware() { return string("getAndroidHardware"); }
    public static String userAgent() { return string("getEffectiveUserAgent"); }

    /** Call on the UI thread immediately before the owned browser is observed. */
    public static void prepareBrowser(WebView browser) {
        String effective = userAgent();
        if (!effective.equals(browser.getSettings().getUserAgentString()))
            browser.getSettings().setUserAgentString(effective);
    }
    /** Preserve absent/failed metadata; the host owns firmware model/release corrections. */
    @SuppressWarnings("unchecked")
    public static Map<String,Object> uach(Map<String,Object> observed) {
        if (observed == null) return null;
        return (Map<String,Object>) host("correctAndroidClientHints", new Class<?>[]{Map.class}, observed);
    }

    /** Restrict reflective correction to the same firmware fields as the host policy. */
    public static Object readField(Field field, Object receiver) throws IllegalAccessException {
        if (Modifier.isStatic(field.getModifiers())) {
            String owner = field.getDeclaringClass().getName(), name = field.getName();
            if (owner.equals("android.os.Build")) {
                if (name.equals("MODEL")) return model();
                if (name.equals("ID")) return buildId();
                if (name.equals("FINGERPRINT")) return fingerprint();
                if (name.equals("BRAND")) return brand();
                if (name.equals("MANUFACTURER")) return manufacturer();
                if (name.equals("DEVICE")) return device();
                if (name.equals("PRODUCT")) return product();
                if (name.equals("HARDWARE")) return hardware();
            } else if (owner.equals("android.os.Build$VERSION")) {
                if (name.equals("RELEASE")) return release();
                if (name.equals("SDK_INT")) return sdkInt();
            }
        }
        return field.get(receiver);
    }
    /** Other reflection executes unchanged; advertising ID collection is removed at its owners. */
    public static Object invoke(Method method, Object receiver, Object[] args)
            throws IllegalAccessException, InvocationTargetException {
        String owner = method.getDeclaringClass().getName();
        // A module can use Field.get via Method.invoke instead of the VM's static-field opcode.
        if (owner.equals("java.lang.reflect.Field") && method.getName().equals("get")
                && receiver instanceof Field && args != null && args.length == 1) {
            try { return readField((Field) receiver, args[0]); }
            catch (IllegalAccessException | RuntimeException failure) {
                throw new InvocationTargetException(failure);
            }
        }
        // Preserve the same correction when Method.invoke is itself invoked reflectively.
        if (owner.equals("java.lang.reflect.Method") && method.getName().equals("invoke")
                && receiver instanceof Method && args != null && args.length == 2
                && (args[1] == null || args[1] instanceof Object[])) {
            try { return invoke((Method) receiver, args[0], (Object[]) args[1]); }
            catch (IllegalAccessException | InvocationTargetException | RuntimeException failure) {
                throw new InvocationTargetException(failure);
            }
        }
        if (Modifier.isStatic(method.getModifiers()) && args != null) {
            if (owner.equals("android.os.SystemProperties") && method.getName().equals("get")
                    && method.getReturnType() == String.class && (args.length == 1 || args.length == 2)
                    && method.getParameterTypes()[0] == String.class) {
                return host("getAndroidSystemProperty", new Class<?>[]{String.class, String.class},
                        args[0], args.length == 2 ? args[1] : "");
            }
            if (owner.equals("java.lang.System") && method.getName().equals("getProperty")
                    && args.length >= 1 && args.length <= 2 && "http.agent".equals(args[0]))
                return userAgent();
        }
        return method.invoke(receiver, args);
    }
    /** IdentifierInfo owns rdid/idtype/is_lat; this only applies firmware URL corrections. */
    public static String prepareTag(String original) {
        String corrected;
        try { corrected = (String) bindings().normalizeUrl.invoke(null, original,
                userAgent(), release(), model(), buildId()); }
        catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Host firmware URL correction failed", failure);
        }
        return corrected;
    }
}
