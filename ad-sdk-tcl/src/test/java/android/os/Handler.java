package android.os;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Deterministic main queue for exercising synchronous host reentry without a device. */
public final class Handler {
    private static final class Pending {
        final Handler owner;
        final Runnable task;
        Pending(Handler owner, Runnable task) { this.owner = owner; this.task = task; }
    }
    private static final ArrayDeque<Pending> immediate = new ArrayDeque<>();
    private static final List<Pending> delayed = new ArrayList<>();
    public Handler(Looper looper) {}
    public boolean post(Runnable task) { immediate.add(new Pending(this, task)); return true; }
    public boolean postDelayed(Runnable task, long delayMs) { delayed.add(new Pending(this, task)); return true; }
    public void removeCallbacks(Runnable task) {
        immediate.removeIf(item -> item.owner == this && item.task == task);
        delayed.removeIf(item -> item.owner == this && item.task == task);
    }
    public static void runPending() {
        int remaining = 1_000;
        while (!immediate.isEmpty()) {
            if (--remaining == 0) throw new AssertionError("Main queue did not settle");
            Looper.runOnMain(immediate.remove().task);
        }
    }
    public static int pendingCount() { return immediate.size() + delayed.size(); }
    public static void reset() { immediate.clear(); delayed.clear(); }
}
