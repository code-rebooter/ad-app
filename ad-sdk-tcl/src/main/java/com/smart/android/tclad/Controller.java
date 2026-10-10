package com.smart.android.tclad;

import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.List;

/** Public handle. All post-adurl operations and callbacks are delegated to the original controller. */
public final class Controller {
    private interface Operation { void run(com.tcl.ff.component.vastad.Controller controller); }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Operation> waiting = new ArrayList<>();
    private com.tcl.ff.component.vastad.Controller original;
    private boolean releasedBeforeEntry;
    private AdTagProvider.Cancellable upstream;
    Controller() {}
    void upstream(AdTagProvider.Cancellable call) { upstream = call; if (releasedBeforeEntry && call != null) call.cancel(); }
    boolean cancelledBeforeEntry() { return releasedBeforeEntry; }
    void bind(com.tcl.ff.component.vastad.Controller value) {
        original = value;
        if (releasedBeforeEntry) { value.release(); return; }
        List<Operation> pending = new ArrayList<>(waiting);
        waiting.clear();
        for (Operation operation : pending) operation.run(value);
    }
    private void dispatch(Operation operation) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> dispatch(operation)); return; }
        if (original != null) operation.run(original);
        else if (!releasedBeforeEntry) waiting.add(operation);
    }
    public void start(ViewGroup container) { dispatch(c -> c.start(container)); }
    public void preparePlayer() { dispatch(com.tcl.ff.component.vastad.Controller::preparePlayer); }
    public void resume() { dispatch(com.tcl.ff.component.vastad.Controller::resume); }
    public void pause() { dispatch(com.tcl.ff.component.vastad.Controller::pause); }
    public void setVolume(float value) { dispatch(c -> c.setVolume(value)); }
    public void stop(ViewGroup container) { dispatch(c -> c.stop(container)); }
    public void lifeCycle(Object owner) { dispatch(c -> c.lifeCycle(owner)); }
    public void release() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::release); return; }
        if (original != null) original.release();
        else { releasedBeforeEntry = true; waiting.clear(); if (upstream != null) upstream.cancel(); }
    }
}
