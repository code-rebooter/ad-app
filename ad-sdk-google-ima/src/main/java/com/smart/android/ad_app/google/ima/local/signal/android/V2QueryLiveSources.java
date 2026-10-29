package com.smart.android.ad_app.google.ima.local.signal.android;

import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import com.smart.android.ad_app.google.ima.local.signal.V2QueryMapBuilder;
import java.util.concurrent.Executor;

/** Independent real primitive/lifecycle sources for the V2 query map.
 * No IMA map, payload or completed signal is read. Helpers are selected by the
 * genuine constructor flags. Local APK digest and advertising tasks start once and are
 * observed without waiting when the map is assembled. GASS service IPC and its
 * attestation/telemetry side effects are disabled, an explicit local source policy.
 */
public final class V2QueryLiveSources implements V2QueryMapBuilder.Sources,AutoCloseable {
    private final Context context;
    private final boolean timingPresent,networkPresent,vpnPresent;
    private boolean closed;
    private volatile boolean userPresent=true;
    private volatile boolean localDigestSuccessful,advertisingSuccessful;
    private String localDigest;
    private V2QueryMapBuilder.AdvertisingId advertising;
    private Throwable localDigestFailure,advertisingFailure;
    private final Object networkLock=new Object();
    private NetworkCapabilities networkCapabilities;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private AppOpsManager appOps;
    private Object vpnListener;
    private long vpnStart=0,vpnEnd=0,vpnCompleted=-1;
    private boolean vpnActive;
    private long tcq=-1,tpq=-1,tcv=-1,tpv=-1,tchv=-1,tphv=-1,tcc=-1,tpc=-1;
    private boolean receiverRegistered;
    private final BroadcastReceiver screenReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context ignored,Intent intent){
            if(Intent.ACTION_USER_PRESENT.equals(intent.getAction()))userPresent=true;
            else if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction()))userPresent=false;
        }
    };
    public V2QueryLiveSources(Context sdkContext,int effectiveSdk,Executor executor,
            V2QueryMapBuilder.Options options,boolean timingFlag,boolean networkFlag,boolean vpnFlag) {
        if(sdkContext==null||executor==null||options==null)throw new NullPointerException("actual context/executor/options required");
        context=sdkContext;timingPresent=timingFlag;networkPresent=networkFlag;vpnPresent=vpnFlag;
        try {
        // zzhx creates network/VPN/timing helpers before starting zzok tasks.
        if(networkPresent)initializeNetwork(effectiveSdk);
        if(vpnPresent)initializeVpn(effectiveSdk,executor);
        // The pinned IMA URL path disables this map field. Keep any unexpected
        // branch local and unresolved rather than invoking AdvertisingIdClient.
        advertising=V2QueryMapBuilder.AdvertisingId.unresolved();advertisingSuccessful=true;
        // This task is an actual local file computation, not a GASS response.
        // Pending/read failure retains the unresolved map input "E". No empty
        // value, cache state or attestation success is invented.
        executor.execute(()->{
            try {
                String actual=LocalAppDigestSource.currentBaseApk(context);
                if(actual==null||actual.isEmpty())throw new IllegalStateException("Local APK digest unavailable");
                localDigest=actual;localDigestSuccessful=true;
            }catch(Throwable failure){localDigestFailure=failure;if(failure instanceof InterruptedException)Thread.currentThread().interrupt();}
        });
        // zzjc is created after the two asynchronous tasks have been scheduled.
        IntentFilter filter=new IntentFilter();filter.addAction(Intent.ACTION_USER_PRESENT);filter.addAction(Intent.ACTION_SCREEN_OFF);
        context.registerReceiver(screenReceiver,filter);receiverRegistered=true;
        }catch(RuntimeException|Error failure){try{close();}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    private void initializeNetwork(int effectiveSdk) {
        connectivity=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(connectivity==null||effectiveSdk<24)return;
        networkCallback=new ConnectivityManager.NetworkCallback(){
            @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities capabilities){synchronized(networkLock){networkCapabilities=capabilities;}}
            @Override public void onLost(Network network){synchronized(networkLock){networkCapabilities=null;}}
        };
        try{Api24Network.register(connectivity,networkCallback);}catch(RuntimeException failure){synchronized(networkLock){networkCapabilities=null;}networkCallback=null;}
    }
    private void initializeVpn(int effectiveSdk,Executor executor) {
        if(effectiveSdk<30)return;
        try {
            appOps=(AppOpsManager)context.getSystemService(Context.APP_OPS_SERVICE);
            vpnListener=Api30Vpn.start(appOps,executor,new Api30Vpn.Events(){public void onActive(boolean active){if(active){vpnStart=System.currentTimeMillis();vpnActive=true;return;}long now=System.currentTimeMillis();if(vpnStart>0&&now>=vpnStart)vpnCompleted=now-vpnStart;vpnActive=false;}});
        }catch(NoSuchMethodError|IllegalArgumentException ignored){vpnListener=null;}
    }
    /** Call at zzhx.zzf-equivalent entry, before module work and map creation. */
    public void beforeQuery(boolean currentTimingFlag) {
        if(vpnPresent&&vpnActive)vpnEnd=System.currentTimeMillis();
        if(currentTimingFlag) {
            if(!timingPresent)throw new NullPointerException("timing tracker absent at original constructor");
            tpq=tcq;tcq=SystemClock.uptimeMillis();
        }
    }
    /** Actual analogous view/click calls can share the same tracker lifetime. */
    public void beforeClick(boolean currentTimingFlag){if(vpnPresent&&vpnActive)vpnEnd=System.currentTimeMillis();if(currentTimingFlag){if(!timingPresent)throw new NullPointerException("timing tracker absent");tpc=tcc;tcc=SystemClock.uptimeMillis();}}
    public void beforeView(boolean currentTimingFlag,boolean hasView){if(vpnPresent&&vpnActive)vpnEnd=System.currentTimeMillis();if(currentTimingFlag){if(!timingPresent)throw new NullPointerException("timing tracker absent");tpv=tcv;tcv=SystemClock.uptimeMillis();tphv=tchv;tchv=hasView?tcv:-1;}}
    /** Legacy map-builder method name; this implementation explicitly uses
     * local APK bytes and does not claim a successful GASS transaction. */
    public String gassDigestOrUnresolvedE(){return localAppDigestOrUnresolvedE();}
    public String localAppDigestOrUnresolvedE(){return localDigestSuccessful?localDigest:"E";}
    public String appDigestSourceProvenance(){return "actual ApplicationInfo.sourceDir; whole-file SHA-256; GASS txn1 disabled";}
    public V2QueryMapBuilder.AdvertisingId advertisingIdOrUnresolved(){return advertisingSuccessful?advertising:V2QueryMapBuilder.AdvertisingId.unresolved();}
    public boolean userPresentBroadcastState(){return userPresent;}
    public Throwable captureThrowable(){return new Throwable();}
    public boolean timingTrackerPresent(){return timingPresent;}
    public long queryCurrentUptime(){return tcq;}public long queryPreviousUptime(){return tpq;}
    public long viewCurrentUptime(){return tcv;}public long viewPreviousUptime(){return tpv;}
    public long viewWithViewCurrentUptime(){return tchv;}public long viewWithViewPreviousUptime(){return tphv;}
    public long clickCurrentUptime(){return tcc;}public long clickPreviousUptime(){return tpc;}
    public boolean networkTrackerPresent(){return networkPresent;}
    public long networkType(){synchronized(networkLock){NetworkCapabilities value=networkCapabilities;if(value!=null){if(value.hasTransport(4))return 2;if(value.hasTransport(1))return 1;if(value.hasTransport(0))return 0;}return -1;}}
    public boolean vpnTrackerPresent(){return vpnPresent;}
    public long activeVpnDuration(){return vpnActive?vpnEnd-vpnStart:-1;}
    public long consumeCompletedVpnDuration(){long result=vpnCompleted;vpnCompleted=-1;return result;}
    /** GASS was never called; local success must not masquerade as GASS success. */
    public boolean gassTaskSuccessful(){return false;}
    public Throwable gassTaskFailure(){return null;}
    public boolean gassTaskStarted(){return false;}
    public boolean localAppDigestTaskSuccessful(){return localDigestSuccessful;}
    public Throwable localAppDigestTaskFailure(){return localDigestFailure;}
    public boolean advertisingTaskSuccessful(){return advertisingSuccessful;}
    public Throwable advertisingTaskFailure(){return advertisingFailure;}
    @Override public synchronized void close(){
        if(closed)return;closed=true;RuntimeException failure=null;
        if(receiverRegistered){try{context.unregisterReceiver(screenReceiver);}catch(RuntimeException error){failure=error;}finally{receiverRegistered=false;}}
        if(connectivity!=null&&networkCallback!=null){try{connectivity.unregisterNetworkCallback(networkCallback);}catch(RuntimeException error){if(failure==null)failure=error;else failure.addSuppressed(error);}finally{networkCallback=null;}}
        if(appOps!=null&&vpnListener!=null){try{Api30Vpn.stop(appOps,vpnListener);}catch(RuntimeException error){if(failure==null)failure=error;else failure.addSuppressed(error);}finally{vpnListener=null;}}
        if(failure!=null)throw failure;
    }
}
