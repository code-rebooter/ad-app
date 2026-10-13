package android.text;

/** JVM fixture for the Android string checks used by the original HTTP requester. */
public final class TextUtils {
    private TextUtils() {}
    public static boolean isEmpty(CharSequence value) { return value == null || value.length() == 0; }
    public static boolean equals(CharSequence first, CharSequence second) {
        if (first == second) return true;
        if (first == null || second == null || first.length() != second.length()) return false;
        for (int i = 0; i < first.length(); i++) if (first.charAt(i) != second.charAt(i)) return false;
        return true;
    }
}
