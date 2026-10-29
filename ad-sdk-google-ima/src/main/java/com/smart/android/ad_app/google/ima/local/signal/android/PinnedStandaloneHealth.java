package com.smart.android.ad_app.google.ima.local.signal.android;

import android.os.Bundle;

/** Exact health source for the current SDK's empty standalone constructor Bundle.
 * The pinned native constructor allocates a monitor with previousStatus=0.
 * It reads a persistent previous status only if Bundle.getString("vc") and
 * getString("pc") both return nonnull. Current zzpf passes new Bundle(), so
 * neither the file reader nor the mapped active-state byte exists on this path.
 * This models that proven absence; it does not assert success for other inputs.
 */
public final class PinnedStandaloneHealth {
    public static final String ORIGINAL_NATIVE_SHA256=
        "4a0b82932d3088faaeab360f8345c6bdc19c58ea1539e6269b113b7c9113d950";
    private boolean closed;
    private PinnedStandaloneHealth() {}
    public static PinnedStandaloneHealth currentSdkEmptyBundle(Bundle actualConstructorBundle,int actualEnvironment) {
        if(actualConstructorBundle==null)throw new NullPointerException("Actual constructor Bundle required");
        if(actualEnvironment!=2)throw new UnsupportedOperationException("Only the current standalone environment is covered");
        if(!actualConstructorBundle.isEmpty())throw new UnsupportedOperationException("Current SDK contract requires its genuine empty Bundle; persistent vc/pc status is a separate native branch");
        // Preserve the actual native source getter on this proven branch.
        if(actualConstructorBundle.getString("vc")!=null)throw new IllegalStateException("Empty Bundle changed during constructor");
        return new PinnedStandaloneHealth();
    }
    /** Original lcsNative returns the monitor's constructor-initialized status. */
    public synchronized int status(){if(closed)throw new IllegalStateException("Native state was disposed");return 0;}
    /** There is no mapped active byte to update on this exact constructor path. */
    public synchronized void beforeVmPhase(int originalPhase){if(closed)throw new IllegalStateException("Native state was disposed");if(originalPhase<1||originalPhase>6)throw new IllegalArgumentException("Original phase byte");}
    public synchronized void afterVmPhase(){if(closed)throw new IllegalStateException("Native state was disposed");}
    public synchronized void close(){closed=true;}
    public synchronized boolean isClosed(){return closed;}
}
