package com.smart.android.ad_app.google.ima.local.signal;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Independent input selection with explicit no-GASS policy. Original direct
 * Android checksum and helper fallback remain; former GASS digest reads use a
 * genuine local APK Future, never a fabricated service/attestation response.
 * Local computation is not claimed to reproduce private GMS cache or timing.
 */
public final class CompatAppDigestResolver {
    public interface DirectChecksum { String queryAndWait(ResolvedFlags flags) throws Exception; }
    public static final class Selection {
        public final boolean allowLocalApkHash,overridePrimary;
        public final String suppliedDigest,resolvedPrimary;
        Selection(boolean allow,String supplied,boolean override,String primary) {
            allowLocalApkHash=allow;suppliedDigest=supplied;overridePrimary=override;resolvedPrimary=primary;
        }
    }
    private final boolean helperInitialized;
    private final Future<String> helper;
    private final Future<String> localAppDigest;
    private final DirectChecksum directChecksum;
    private final int effectiveSdk;
    private final PrimitiveObserver observer;
    public CompatAppDigestResolver(boolean helperInitialized,Future<String> helper,Future<String> localAppDigest,DirectChecksum directChecksum,int effectiveSdk) {
        this(helperInitialized,helper,localAppDigest,directChecksum,effectiveSdk,PrimitiveObserver.NONE);
    }
    public CompatAppDigestResolver(boolean helperInitialized,Future<String> helper,Future<String> localAppDigest,DirectChecksum directChecksum,int effectiveSdk,PrimitiveObserver observer) {
        this.observer=observer==null?PrimitiveObserver.NONE:observer;
        if(helperInitialized&&helper==null)throw new IllegalArgumentException("selected helper needs genuine future");
        this.helperInitialized=helperInitialized;this.helper=helper;this.localAppDigest=localAppDigest;this.directChecksum=directChecksum;this.effectiveSdk=effectiveSdk;
    }
    public boolean usesHelper() { return helperInitialized; }
    public Selection select(ResolvedFlags flags) throws Exception {
        if(helperInitialized)return helperSelection(flags);
        // Fixed zzjx bytecode proves i=3 (the null/empty helper test is constant).
        // Its reachable branch never uses the decompiler's i=4/i=5 fallbacks.
        String supplied=null;
        if(flags.zzmj_zzd) {
            if(directChecksum==null)throw new CompatMode1Runtime.UnsupportedBranch("direct checksum source selected but unavailable");
            supplied=directChecksum.queryAndWait(flags);
        }
        if(flags.zzmj_zze&&flags.zzjj_zzp&&empty(supplied))supplied=awaitLocalApkUnbounded();
        if(observer!=PrimitiveObserver.NONE)PrimitiveObserver.emit(observer,"direct_app_arguments_selected",com.smart.android.ad_app.google.ima.local.Mode1PayloadBuilder.Task.APP_INFO,PrimitiveObserver.fields("actualSuppliedDigest",supplied,"allowLocalApkHash",!flags.zzai_zzf));
        return new Selection(!flags.zzai_zzf,supplied,false,null);
    }
    private Selection helperSelection(ResolvedFlags f) throws Exception {
        String primary="E";
        int timeout=f.zzmj_zzq?f.zzmj_zzr:f.zzai_zza;
        try {
            Boolean initialDoneRead=null;
            boolean eligible=f.zzjj_zzp&&localAppDigest!=null;
            if(eligible&&effectiveSdk>=31){initialDoneRead=localAppDigest.isDone();eligible=initialDoneRead;}
            eligible=eligible&&!f.zzai_zzg;
            if(observer!=PrimitiveObserver.NONE)PrimitiveObserver.emit(observer,"helper_local_apk_gate_inputs",com.smart.android.ad_app.google.ima.local.Mode1PayloadBuilder.Task.APP_INFO,PrimitiveObserver.fields("source","local-sourceDir-SHA256; GASS disabled","originalBranchGmsGate",f.zzjj_zzp,"futurePresent",localAppDigest!=null,"initialDoneRead",initialDoneRead,"effectiveSdk",effectiveSdk,"originalSkipSourceGate",f.zzai_zzg,"timeoutMillis",timeout));
            if(eligible) {
                String actual=localAppDigest.get(timeout,TimeUnit.MILLISECONDS);
                if(observer!=PrimitiveObserver.NONE)PrimitiveObserver.emit(observer,"helper_local_apk_wait_returned",com.smart.android.ad_app.google.ima.local.Mode1PayloadBuilder.Task.APP_INFO,PrimitiveObserver.fields("source","local-sourceDir-SHA256; no GASS response","actualResult",actual));
                if(!empty(actual))primary=actual;
            }
        } catch(InterruptedException failure){observeWaitFailure("timed_local_apk",failure);}
        catch(ExecutionException failure){observeWaitFailure("timed_local_apk",failure);}
        catch(TimeoutException failure){observeWaitFailure("timed_local_apk",failure);}
        if("E".equals(primary)) {
            try {
                // The pinned zztg Future checks interruption before even a done result;
                // Java CompletableFuture does not, so preserve that entry check here.
                if(Thread.interrupted())throw new InterruptedException();
                String actual=helper.get(timeout,TimeUnit.MILLISECONDS);
                if(observer!=PrimitiveObserver.NONE)PrimitiveObserver.emit(observer,"helper_checksum_wait_returned",com.smart.android.ad_app.google.ima.local.Mode1PayloadBuilder.Task.APP_INFO,PrimitiveObserver.fields("actualResult",actual));
                if(!empty(actual))primary=actual;
            } catch(ClassCastException failure){observeWaitFailure("timed_helper",failure);}
            catch(InterruptedException failure){observeWaitFailure("timed_helper",failure);}
            catch(ExecutionException failure){observeWaitFailure("timed_helper",failure);}
            catch(TimeoutException failure){observeWaitFailure("timed_helper",failure);}
        }
        // n(Context,false,empty) still collects metadata; outer zzc() replaces
        // only primary after this actual async-selection result is available.
        if(observer!=PrimitiveObserver.NONE)PrimitiveObserver.emit(observer,"helper_primary_source_selected",com.smart.android.ad_app.google.ima.local.Mode1PayloadBuilder.Task.APP_INFO,PrimitiveObserver.fields("actualPrimary",primary));
        return new Selection(false,"",true,primary);
    }
    private String awaitLocalApkUnbounded() {
        try {
            String actual=localAppDigest==null?null:localAppDigest.get();
            if(observer!=PrimitiveObserver.NONE)PrimitiveObserver.emit(observer,"unbounded_local_apk_wait_returned",com.smart.android.ad_app.google.ima.local.Mode1PayloadBuilder.Task.APP_INFO,PrimitiveObserver.fields("source","local-sourceDir-SHA256; no GASS response","actualResult",actual));
            return empty(actual)?null:actual;
        }catch(InterruptedException failure){observeWaitFailure("unbounded_local_apk",failure);return null;}
        catch(ExecutionException failure){observeWaitFailure("unbounded_local_apk",failure);return null;}
    }
    private void observeWaitFailure(String source,Throwable failure) {
        if(observer!=PrimitiveObserver.NONE)PrimitiveObserver.emit(observer,"actual_future_wait_failure",com.smart.android.ad_app.google.ima.local.Mode1PayloadBuilder.Task.APP_INFO,PrimitiveObserver.fields("source",source,"failureClass",failure.getClass().getName()));
    }
    private static boolean empty(String value){return value==null||value.isEmpty();}
}
