package android.os;

/** JVM fixture: these unit tests execute off Android's main thread. */
public final class Looper {
    private static final Looper MAIN = new Looper();
    private static final ThreadLocal<Boolean> ON_MAIN = new ThreadLocal<>();
    private Looper() {}
    public static Looper myLooper() { return Boolean.TRUE.equals(ON_MAIN.get()) ? MAIN : null; }
    public static Looper getMainLooper() { return MAIN; }
    public static void runOnMain(Runnable task) {
        Boolean previous = ON_MAIN.get();
        ON_MAIN.set(true);
        try { task.run(); }
        finally { if (previous == null) ON_MAIN.remove(); else ON_MAIN.set(previous); }
    }
}
