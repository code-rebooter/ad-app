package com.smart.android.tclad;

import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import com.smart.android.tclad.runtime.TclExecution;
import java.util.ArrayList;
import java.util.List;

/** Owns one native attempt and cancels its transport, playback and queued callbacks together. */
public final class Controller {
    private interface Operation { void run(com.tcl.ff.component.vastad.Controller controller); }
    private static Controller owner;
    private static long generation;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Operation> waiting = new ArrayList<>();
    private com.tcl.ff.component.vastad.Controller original;
    private boolean released;
    private boolean nativeReleased;
    private AdTagProvider.Cancellable upstream;
    private TclExecution execution;
    Controller() {}
    void begin(ExecutionConfig config, AdStatusListener listener, float volume) {
        if (released) return;
        long entry = ++generation;
        if (owner != null && owner != this) owner.release();
        if (entry != generation) { released = true; return; }
        owner = this;
        execution = new TclExecution(config, listener, volume, this::releaseNative, this::endExecution);
    }
    TclExecution execution() { return execution; }
    void upstream(AdTagProvider.Cancellable call) {
        upstream = call;
        if (cancelledBeforeEntry() && call != null) call.cancel();
    }
    boolean cancelledBeforeEntry() { return released || (execution != null && execution.isClosed()); }
    void bind(com.tcl.ff.component.vastad.Controller value) {
        if (value == null) return;
        if (cancelledBeforeEntry()) {
            if (owner == this || owner == null) value.release();
            return;
        }
        original = value;
        List<Operation> pending = new ArrayList<>(waiting);
        waiting.clear();
        for (Operation operation : pending) {
            if (cancelledBeforeEntry()) break;
            operation.run(value);
        }
    }
    private void dispatch(Operation operation) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> dispatch(operation)); return; }
        if (cancelledBeforeEntry()) return;
        if (original != null) operation.run(original);
        else waiting.add(operation);
    }
    public void start(ViewGroup container) { dispatch(c -> c.start(container)); }
    public void preparePlayer() { dispatch(com.tcl.ff.component.vastad.Controller::preparePlayer); }
    public void resume() { dispatch(com.tcl.ff.component.vastad.Controller::resume); }
    public void pause() { dispatch(com.tcl.ff.component.vastad.Controller::pause); }
    public void setVolume(float value) { dispatch(c -> c.setVolume(value)); }
    public void stop(ViewGroup container) { dispatch(c -> c.stop(container)); release(); }
    public void lifeCycle(Object owner) { dispatch(c -> c.lifeCycle(owner)); }
    private void releaseNative() {
        waiting.clear();
        if (upstream != null) { upstream.cancel(); upstream = null; }
        if (!nativeReleased && original != null) {
            nativeReleased = true;
            try { original.release(); }
            catch (RuntimeException error) { com.smart.android.tclad.logging.PropertyLog.w("TclAd", "Native release failed", error); }
        }
    }
    private void endExecution() { if (owner == this) owner = null; }
    public void release() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::release); return; }
        if (released) return;
        released = true;
        try {
            if (execution != null) execution.cancel();
        } finally {
            releaseNative();
            endExecution();
        }
    }
}
