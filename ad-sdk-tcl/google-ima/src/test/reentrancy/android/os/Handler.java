package android.os;

import java.util.ArrayList;
import java.util.List;

/** Deterministic queue for the compiled SDK's actual pending-request code. */
public final class Handler {
    public final List<Runnable> pending = new ArrayList<>();
    public Handler(Looper ignored) { }
    public boolean post(Runnable task) { pending.add(task); return true; }
    public boolean postDelayed(Runnable task, long delayMs) { pending.add(task); return true; }
    public void removeCallbacks(Runnable task) { pending.remove(task); }
}
