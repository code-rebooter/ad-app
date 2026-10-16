package com.smart.android.adsdk.universal.internal;

import com.google.gson.*;

/** Immutable registration values from the key configuration endpoint. */
final class TclIdentity {
    final String packageName, appName, versionName, signatureMd5, appKey, partnerName;
    final int versionCode, projectId;
    private TclIdentity(JsonObject data) {
        packageName = string(data, "package_name"); appName = string(data, "app_name");
        versionName = string(data, "version_name"); appKey = string(data, "app_key");
        partnerName = string(data, "partner_name"); signatureMd5 = string(data, "signature_md5");
        if (!signatureMd5.matches("[0-9a-fA-F]{32}")) throw new IllegalArgumentException("Invalid TCL signature_md5");
        versionCode = integer(data, "version_code", 0); projectId = integer(data, "project_id", 1);
    }
    static TclIdentity from(JsonObject data) {
        return new TclIdentity(data);
    }
    String cacheKey() {
        JsonObject values = new JsonObject();
        values.addProperty("package_name", packageName); values.addProperty("app_name", appName);
        values.addProperty("version_name", versionName); values.addProperty("version_code", versionCode);
        values.addProperty("signature_md5", signatureMd5.toLowerCase(java.util.Locale.ROOT));
        values.addProperty("app_key", appKey); values.addProperty("project_id", projectId);
        values.addProperty("partner_name", partnerName);
        return KeyConfigStore.cacheKey(values.toString());
    }
    boolean sameValues(TclIdentity other) {
        return other != null && packageName.equals(other.packageName) && appName.equals(other.appName)
            && versionName.equals(other.versionName) && versionCode == other.versionCode
            && signatureMd5.equalsIgnoreCase(other.signatureMd5) && appKey.equals(other.appKey)
            && projectId == other.projectId && partnerName.equals(other.partnerName);
    }
    private static String string(JsonObject obj, String name) {
        JsonElement value = obj.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().trim().isEmpty())
            throw new IllegalArgumentException("Missing TCL configuration: " + name);
        return value.getAsString();
    }
    private static int integer(JsonObject obj, String name, int minimum) {
        JsonElement value = obj.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Missing TCL configuration: " + name);
        int number = value.getAsBigDecimal().intValueExact();
        if (number < minimum) throw new IllegalArgumentException("Invalid TCL configuration: " + name);
        return number;
    }
}
