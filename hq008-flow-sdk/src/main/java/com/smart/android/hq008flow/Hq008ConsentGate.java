package com.smart.android.hq008flow;

/** Host CMP decision. Invoked before authorize unless the server explicitly sets skip_cmp=true. */
public interface Hq008ConsentGate {
    void evaluate(String requestId, String channelId, Result result);
    interface Result { void onDecision(boolean canContinue); }
}
