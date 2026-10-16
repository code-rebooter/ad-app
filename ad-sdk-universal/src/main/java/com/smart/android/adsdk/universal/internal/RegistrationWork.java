package com.smart.android.adsdk.universal.internal;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Native/CMP operations may outlive a canceled ad round. Registration waits for their completion. */
public final class RegistrationWork {
    private static final AtomicInteger count = new AtomicInteger();
    private RegistrationWork() {}
    public static Cancellable begin() {
        count.incrementAndGet();
        AtomicBoolean finished = new AtomicBoolean();
        return () -> { if (finished.compareAndSet(false, true)) count.decrementAndGet(); };
    }
    public static boolean isIdle() { return count.get() == 0; }
}
