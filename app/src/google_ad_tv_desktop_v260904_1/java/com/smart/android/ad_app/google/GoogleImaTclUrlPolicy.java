package com.smart.android.ad_app.google;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Keep this request's useful data without advertising the stopped IMA player. */
public final class GoogleImaTclUrlPolicy {
    private static final Set<String> DATA = new HashSet<>(Arrays.asList(
        "correlator", "vpa", "vpmute", "rdid", "idtype", "is_lat", "an", "msid", "url",
        "ctv", "hl", "uach", "ms", "gdpr", "gdpr_consent", "addtl_consent", "gpp", "gpp_sid",
        "us_privacy", "npa", "tfcd", "tfua", "ltd", "rdp", "ppt"
    ));
    private static final Set<String> IMA = new HashSet<>(Arrays.asList(
        "sdkv", "js", "sdk_apis", "omid_p", "osd", "sdki", "ptt", "vo", "sdr", "frm",
        "eid", "sid", "dlt", "idt", "dt", "eo_id", "eo_id_ts", "pvsid", "scor", "is_amp",
        "u_so", "mv", "impl"
    ));
    private static final Set<String> CURRENT_ONLY = new HashSet<>(Arrays.asList(
        "rdid", "idtype", "is_lat", "ms", "uach"
    ));
    private static final Set<String> OVERRIDDEN = new HashSet<>(Arrays.asList(
        "output", "vpa", "vpmute", "ctv", "msid"
    ));
    private GoogleImaTclUrlPolicy() {}

    public static String apply(String original, String ima, boolean muted, boolean television, String packageName) {
        List<String> source = pairs(original);
        Set<String> sourceKeys = new HashSet<>();
        for (String pair : source) sourceKeys.add(key(pair));
        List<String> result = new ArrayList<>();
        Set<String> present = new HashSet<>();
        boolean limited = "1".equals(value(pairs(ima), "is_lat"));
        for (String pair : pairs(ima)) {
            String key = key(pair);
            if (blocked(key) || OVERRIDDEN.contains(key)) continue;
            if (!sourceKeys.contains(key) && !DATA.contains(key)) continue;
            if ("rdid".equals(key) && (limited || decodedValue(pair).isEmpty())) continue;
            result.add(pair);
            present.add(key);
        }
        // Preserve backend inventory/custom parameters if Core omitted them.
        // Device IDs and opaque per-request signals must never fall back to stale input.
        for (String pair : source) {
            String key = key(pair);
            if (!present.contains(key) && !blocked(key) && !OVERRIDDEN.contains(key)
                    && !CURRENT_ONLY.contains(key)) {
                result.add(pair);
            }
        }
        String format = rawValue(source, "output");
        result.add("output=" + (format == null || format.isEmpty() ? "vast" : format));
        result.add("vpa=auto");
        result.add("vpmute=" + (muted ? "1" : "0"));
        if (television) result.add("ctv=1");
        if (packageName != null && !packageName.isEmpty()) result.add("msid=" + encode(packageName));
        int hash = ima.indexOf('#');
        String fragment = hash < 0 ? "" : ima.substring(hash);
        String head = hash < 0 ? ima : ima.substring(0, hash);
        int question = head.indexOf('?');
        if (question >= 0) head = head.substring(0, question);
        StringBuilder query = new StringBuilder();
        for (String pair : result) {
            if (query.length() > 0) query.append('&');
            query.append(pair);
        }
        return head + "?" + query + fragment;
    }

    private static boolean blocked(String key) {
        String name = key.startsWith("dc_") ? key.substring(3) : key;
        return IMA.contains(name) || name.startsWith("atv_");
    }
    private static List<String> pairs(String url) {
        int question = url.indexOf('?');
        int hash = url.indexOf('#');
        if (question < 0 || hash >= 0 && hash < question) return new ArrayList<>();
        String query = url.substring(question + 1, hash < 0 ? url.length() : hash);
        List<String> result = new ArrayList<>();
        for (String pair : query.split("&", -1)) if (!pair.isEmpty()) result.add(pair);
        return result;
    }
    private static String key(String pair) {
        int eq = pair.indexOf('=');
        return decode(eq < 0 ? pair : pair.substring(0, eq));
    }
    private static String decodedValue(String pair) {
        int eq = pair.indexOf('=');
        return eq < 0 ? "" : decode(pair.substring(eq + 1));
    }
    private static String value(List<String> pairs, String key) {
        for (String pair : pairs) if (key(pair).equals(key)) return decodedValue(pair);
        return null;
    }
    private static String rawValue(List<String> pairs, String key) {
        for (String pair : pairs) if (key(pair).equals(key)) {
            int eq = pair.indexOf('=');
            return eq < 0 ? "" : pair.substring(eq + 1);
        }
        return null;
    }
    private static String decode(String value) {
        try { return URLDecoder.decode(value, StandardCharsets.UTF_8.name()); }
        catch (Exception ignored) { return value; }
    }
    private static String encode(String value) {
        try { return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name()); }
        catch (Exception error) { throw new IllegalArgumentException(error); }
    }
}
