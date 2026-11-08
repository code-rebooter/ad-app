package com.smart.android.tclad.internal;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import com.tcl.ff.component.overseabase.base.util.GlobalContext;
import com.tcl.ff.component.overseabase.base.util.Logger;
import com.tcl.ff.component.uniplayer.UniPlayerSDK;
import com.tcl.ff.component.uniplayer.cpsdk.GlobalConfig;
import com.tcl.uniplayer.tuniplayer.TUniConfigFactory;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Initializes the supplied original UniPlayer runtime with TCL's confirmed config. */
public final class NativeRuntime {
    private static volatile boolean initialized;
    private static String configuration;
    public static synchronized void setConfiguration(String json) {
        if (initialized) throw new IllegalStateException("Set the configuration snapshot before initialization");
        configuration = json;
    }
    public static synchronized void setReportSwitches(com.smart.android.tclad.AdReportSwitchConfig value) {
        // Compatibility API only: TCL SDK telemetry is unavailable in this profile.
    }
    private static final ExecutorService INIT = Executors.newSingleThreadExecutor();
    public interface FailureCallback { void onFailure(Throwable error); }
    private NativeRuntime() {}
    public static boolean isInitialized() { return initialized; }
    public static void initializeAsync(Context context, boolean logging, Runnable ready, FailureCallback failed) {
        Context app = context.getApplicationContext();
        INIT.execute(() -> {
            try { initialize(app, logging); }
            catch (RuntimeException | LinkageError error) { failed.onFailure(error); return; }
            ready.run();
        });
    }
    private static void requireIsolatedRuntime() {
        ClassLoader loader = NativeRuntime.class.getClassLoader();
        for (String component : new String[]{"base", "media", "player"}) {
            try (var input = loader.getResourceAsStream("META-INF/tcl-adurl-only/" + component + ".marker")) {
                if (input == null) throw new IllegalStateException("Unisolated TCL runtime: " + component);
                byte[] bytes = new byte[64]; int size = input.read(bytes);
                if (!"adurl-only-v4".equals(new String(bytes, 0, size, java.nio.charset.StandardCharsets.UTF_8)))
                    throw new IllegalStateException("Unexpected isolation profile: " + component);
            } catch (java.io.IOException error) { throw new IllegalStateException("Cannot verify isolation", error); }
        }
        for (String forbidden : new String[]{"com.tcl.ff.component.adsdkbi.DataReport", "com.tcl.ff.component.overseabase.database.LitePal",
            "com.tcl.tuniplayer_tracker.UniTrackerImpl",
            "com.tcl.ff.component.media.ima.GoogleImaInitiator",
            "com.tcl.ff.component.media.ima.GPalInitiator"}) {
            try { Class.forName(forbidden, false, loader); throw new IllegalStateException("Forbidden TCL implementation present: " + forbidden); }
            catch (ClassNotFoundException expected) { /* physically excluded */ }
        }
    }
    /** Original TUniConfigFactory requires a worker thread. Loading waits here before preparing views. */
    public static synchronized void initialize(Context context, boolean logging) {
        if (initialized) return;
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Initialize UniPlayer on a worker thread");
        Context app = context.getApplicationContext();
        if (!(app instanceof Application)) throw new IllegalArgumentException("Application context required");
        requireIsolatedRuntime();
        GlobalContext.setAppContext(app);
        Logger.setEnableLog(logging);
        GlobalConfig config = new GlobalConfig();
        config.setLogEnable(logging);
        HashMap<String, Object> options = new HashMap<>();
        options.put("WHETHER_PARSE_TCL_ADS", Boolean.TRUE);
        config.setMaps(options);
        com.tcl.uniplayer.tuniplayer.b nativeConfig = (com.tcl.uniplayer.tuniplayer.b)
            UniPlayerSDK.getSDKConfigFactory(TUniConfigFactory.class).createPlayerConfig(app);
        nativeConfig.a((Application) app, config, false, null);
        if (configuration == null) {
            try (var input = app.getAssets().open("tcl-original-waterfall-2.8.02.json");
                 var bytes = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                for (int n; (n = input.read(buffer)) >= 0;) bytes.write(buffer, 0, n);
                configuration = bytes.toString(java.nio.charset.StandardCharsets.UTF_8.name());
            } catch (java.io.IOException error) { throw new IllegalStateException("Missing pinned TCL configuration", error); }
        }
        com.smart.android.tclad.runtime.TclExecution.installHttpInterceptors();
        OriginalBoundary.configure(configuration);
        // These switches describe TCL SDK telemetry, not VAST-specified ad beacons.
        // The isolated runtime also removes the implementation; callers cannot re-enable it.
        var switches = new com.tcl.ff.component.overseabase.base.constant.AdReportSwitchConfig(false, false, false);
        com.tcl.ff.component.vastad.Initialization.init(app, switches, null);
        if (!com.tcl.ff.component.vastad.Initialization.isHasInit())
            throw new IllegalStateException("Original TCL initialization did not complete");
        initialized = true;
    }
}
