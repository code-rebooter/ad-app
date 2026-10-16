package com.smart.android.adsdk.universal.internal;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import com.tcl.ff.component.overseabase.base.util.GlobalContext;
import com.tcl.ff.component.uniplayer.UniPlayerSDK;
import com.tcl.ff.component.uniplayer.cpsdk.GlobalConfig;
import com.tcl.uniplayer.tuniplayer.TUniConfigFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/** Two independent native configuration singletons; one serially owned native playback engine. */
final class NativeRuntime {
    private static String provider;
    private static final Map<String,Object> configurations = new HashMap<>();
    private static final Map<String,Boolean> initialized = new HashMap<>();
    private static final Map<String,boolean[]> reportSwitches = new HashMap<>();
    private static final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
    private static boolean playerInitialized;
    private static String consentScope;
    private NativeRuntime() {}
    static synchronized void enter(Context context, String target) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Native entry must be on main thread");
        if (target.equals(provider)) return;
        VendorUserAgent.effective();
        GlobalContext.setAppContext(context.getApplicationContext());
        Class<?> fetcher = com.tcl.ff.component.overseabasebusiness.AdConfigFetcher.class;
        Class<?> initialization = com.tcl.ff.component.vastad.Initialization.class;
        var switches = com.tcl.ff.component.overseabase.base.util.AdReportSwitchConfigManager.Companion
            .getInstance().getAdReportSwitchConfig();
        if (provider != null) {
            reportSwitches.put(provider, new boolean[]{switches.getPrivacyAgreed(), switches.getUxpEnabled(), switches.getErrorStatisticsEnabled()});
            configurations.put(provider, OriginalBoundary.field(null, fetcher, "b"));
            initialized.put(provider, com.tcl.ff.component.vastad.Initialization.isHasInit());
        }
        provider = null;
        NativePolicy.enter(target);
        boolean[] saved = reportSwitches.get(target);
        switches.setPrivacyAgreed(saved != null && saved[0]);
        switches.setUxpEnabled(saved != null && saved[1]);
        switches.setErrorStatisticsEnabled(saved != null && saved[2]);
        OriginalBoundary.field(null, fetcher, "b", configurations.get(target));
        OriginalBoundary.field(null, initialization, "a", Boolean.TRUE.equals(initialized.get(target)));
        Object fetcherInstance = com.tcl.ff.component.overseabasebusiness.AdConfigFetcher.get();
        java.io.File directory = new java.io.File(context.getFilesDir(), "universal_tcl_registration/" + TclIdentityBridge.getRegistrationCacheKey());
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create native configuration cache");
        OriginalBoundary.field(fetcherInstance, fetcher, "g", new java.io.File(directory, "configs.0").getAbsolutePath());
        if ("tcl".equals(target) && !directory.getAbsolutePath().equals(consentScope)) {
            GlobalContext.setAppContext(context.getApplicationContext());
            Class<?> processorType = com.tcl.ff.component.oversea.model.CMPConsentDataProcessor.class;
            Object companion = OriginalBoundary.field(null, processorType, "a");
            try {
                Object processor = companion.getClass().getDeclaredMethod("a").invoke(companion);
                OriginalBoundary.field(null, processorType, "c", new java.io.File(directory, "consent.0").getAbsolutePath());
                processorType.getMethod("b").invoke(processor);
                OriginalBoundary.field(processor, processorType, "k", "");
                OriginalBoundary.field(processor, processorType, "l", 0L);
                OriginalBoundary.field(processor, processorType, "m", 0L);
                OriginalBoundary.field(processor, processorType, "n", -1);
                OriginalBoundary.field(processor, processorType, "o", null);
                OriginalBoundary.field(processor, processorType, "p", null);
                OriginalBoundary.field(processor, processorType, "q", false);
                processorType.getMethod("p").invoke(processor);
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("TCL CMP 2.8.02 ABI mismatch", failure); }
            consentScope = directory.getAbsolutePath();
        }
        provider = target;
    }
    static boolean installRegistration(Context context, KeyConfiguration previous, KeyConfiguration next) {
        if (previous != null && previous.sameRegistration(next)) return true;
        // Check before taking the worker's monitor so a canceled initialization cannot block the UI.
        if (!RegistrationWork.isIdle()) return false;
        synchronized (NativeRuntime.class) {
            if (!RegistrationWork.isIdle()) return false;
            for (Object fetcher : configurations.values()) if (isLoading(fetcher)) return false;
            Class<?> fetcherType = com.tcl.ff.component.overseabasebusiness.AdConfigFetcher.class;
            if (isLoading(OriginalBoundary.field(null, fetcherType, "b"))) return false;
            boolean identityChanged = previous == null || !previous.tclIdentity.sameValues(next.tclIdentity);
            if (identityChanged) {
                configurations.clear(); initialized.clear(); reportSwitches.clear();
                OriginalBoundary.field(null, fetcherType, "b", null);
                OriginalBoundary.field(null, com.tcl.ff.component.vastad.Initialization.class, "a", false);
                provider = null; consentScope = null;
                com.smart.android.adsdk.universal.internal.tclcmp.TclCmpManager.resetForRegistration();
            } else if (!previous.tclChannelId.equals(next.tclChannelId)) {
                com.smart.android.adsdk.universal.internal.tclcmp.TclCmpManager.resetForRegistration();
            }
            TclIdentityBridge.install(next.tclIdentity);
            return true;
        }
    }
    private static boolean isLoading(Object fetcher) {
        return fetcher != null && Boolean.TRUE.equals(OriginalBoundary.field(fetcher, fetcher.getClass(), "d"));
    }
    interface FailureCallback { void onFailure(Throwable failure); }
    static void initializeGoogle(Context context, Runnable ready, FailureCallback failed) {
        Cancellable work = RegistrationWork.begin();
        worker.execute(() -> {
            try {
                synchronized (NativeRuntime.class) {
                    if (!NativePolicy.isGoogle()) throw new IllegalStateException("Google native initialization was canceled");
                    initializePlayer(context);
                    if (!com.tcl.ff.component.vastad.Initialization.isHasInit()) {
                        try (var input = context.getAssets().open("tcl-original-waterfall-2.8.02.json"); var out = new java.io.ByteArrayOutputStream()) {
                            byte[] buffer = new byte[8192]; for (int n; (n = input.read(buffer)) != -1;) out.write(buffer, 0, n);
                            OriginalBoundary.configure(out.toString("UTF-8"));
                        }
                        com.tcl.ff.component.vastad.Initialization.init(context,
                            new com.tcl.ff.component.overseabase.base.constant.AdReportSwitchConfig(false, false, false), null);
                        if (!com.tcl.ff.component.vastad.Initialization.isHasInit()) throw new IllegalStateException("Original TCL Google initialization did not complete");
                    }
                }
                ready.run();
            } catch (Exception | LinkageError failure) { failed.onFailure(failure); }
            finally { work.cancel(); }
        });
    }
    private static void initializePlayer(Context context) {
        if (playerInitialized) return;
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Initialize UniPlayer on a worker thread");
        Context app = context.getApplicationContext();
        if (!(app instanceof Application)) throw new IllegalArgumentException("Application context required");
        GlobalConfig config = new GlobalConfig(); config.setLogEnable(SdkLog.isEnabled());
        HashMap<String,Object> options = new HashMap<>(); options.put("WHETHER_PARSE_TCL_ADS", Boolean.TRUE); config.setMaps(options);
        com.tcl.uniplayer.tuniplayer.b nativeConfig = (com.tcl.uniplayer.tuniplayer.b) UniPlayerSDK.getSDKConfigFactory(TUniConfigFactory.class).createPlayerConfig(app);
        nativeConfig.a((Application)app, config, false, null); playerInitialized = true;
    }
}
