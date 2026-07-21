package com.smart.android.hq008flow;

/** Implement this in the Activity, Fragment, or UI controller that can request an ad. */
public interface Hq008AdHost {
    /** Called on the main thread after flow-control and authorize both allow this request. */
    void onAdAuthorized(Hq008AdSession session);
}
