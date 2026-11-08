package android.os;

/** Stable elapsed time for the main-queue lifecycle tests. */
public final class SystemClock {
    private SystemClock() {}
    public static long elapsedRealtime() { return 1_000L; }
}
