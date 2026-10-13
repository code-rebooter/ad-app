package android.os;

/** JVM fixture: these unit tests execute off Android's main thread. */
public final class Looper {
    private static final Looper MAIN = new Looper();
    private Looper() {}
    public static Looper myLooper() { return null; }
    public static Looper getMainLooper() { return MAIN; }
}
