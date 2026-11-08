package com.smart.android.ad_app.google.ima.local.signal.android;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import com.smart.android.ad_app.google.ima.local.AndroidCompatInputs;
import com.smart.android.ad_app.google.ima.local.Mode1PayloadBuilder;
import com.smart.android.ad_app.google.ima.local.Mode1PayloadBuilder.Inputs;
import com.smart.android.ad_app.google.ima.local.Mode1PayloadBuilder.Task;
import com.smart.android.ad_app.google.ima.local.signal.CompatAppDigestResolver;
import com.smart.android.ad_app.google.ima.local.signal.CompatChecksumCertificates;
import com.smart.android.ad_app.google.ima.local.signal.CompatMode1Runtime;
import com.smart.android.ad_app.google.ima.local.signal.FlagsInputs;
import com.smart.android.ad_app.google.ima.local.signal.ResolvedFlags;
import com.smart.android.ad_app.google.ima.local.signal.PrimitiveObserver;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Random;
import java.util.Map;
import java.util.concurrent.Callable;
import com.smart.android.ad_app.google.ima.local.portable.SimplePromise;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Actual independent platform/GMS sources for mode1. No IMA classes are loaded.
 * It owns genuine broadcast/network/AppOps history and checksum operations.
 * GASS IPC is disabled; former GASS digest reads use an explicit local APK task. Must be initialized once on the process source lifetime. Effective
 * SDK is explicit so the application's real patch gate can be supplied unchanged.
 */
public final class AndroidCompatLiveSources implements CompatMode1Runtime.Sources {
    interface ScopedObserver extends PrimitiveObserver { PrimitiveObserver currentObserver(); }
    private final Context context;
    private final PrimitiveObserver observer;
    private final Context invocationContext;
    private final ImaLocalFlagsProvider.IntSource effectiveSdk;
    private volatile boolean googleAvailable;
    private boolean managerPrepared;
    private volatile boolean ready;
    private boolean initialized,closed,receiverRegistered;
    private volatile boolean userPresent=true;
    private boolean networkProviderPresent;
    private volatile NetworkCapabilities networkCapabilities;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private AppOpsManager appOps;
    private Object vpnListener;
    private final Object vpnLock=new Object();
    private long vpnStart=0,vpnEnd=0,vpnCompleted=-1;
    private boolean vpnActive=false;
    private boolean bookkeepingInitialized;
    private long previousMode1Uptime=-1,currentMode1Uptime=-1;
    private Future<String> localAppDigest;
    private CompatAppDigestResolver appDigest;
    private ExecutorService executor;
    private final BroadcastReceiver screenReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent intent) {
            if(Intent.ACTION_USER_PRESENT.equals(intent.getAction()))userPresent=true;
            else if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction()))userPresent=false;
        }
    };
    public AndroidCompatLiveSources(Context supplied,ImaLocalFlagsProvider.IntSource effectiveSdk) {
        this(supplied,effectiveSdk,PrimitiveObserver.NONE);
    }
    public AndroidCompatLiveSources(Context supplied,ImaLocalFlagsProvider.IntSource effectiveSdk,PrimitiveObserver observer) {
        this.observer=observer==null?PrimitiveObserver.NONE:observer;
        if(supplied==null||effectiveSdk==null||effectiveSdk.provenance()==null||effectiveSdk.provenance().isEmpty())throw new IllegalArgumentException("actual Context and effective SDK source with provenance required");
        invocationContext=supplied;Context app=supplied.getApplicationContext();context=app!=null?app:supplied;
        this.effectiveSdk=effectiveSdk;
        // zzjj constructor creates its screen-state receiver before the manager
        // starts executor/flags/local APK work. Preserve that observer start point.
        IntentFilter filter=new IntentFilter();filter.addAction(Intent.ACTION_USER_PRESENT);filter.addAction(Intent.ACTION_SCREEN_OFF);
        context.registerReceiver(screenReceiver,filter);receiverRegistered=true;
    }
    /** Called after scheduling flags init, before waiting for flags/module setup. */
    public synchronized void prepareManager(ExecutorService executor) {
        if(managerPrepared)return;
        this.executor=executor;
        boolean availability=false;
        try{availability=context.getPackageManager()
                .getApplicationInfo("com.google.android.gms",0).enabled;}
        catch(PackageManager.NameNotFoundException|RuntimeException ignored){}
        googleAvailable=availability;
        // The real GASS txn1 is intentionally never constructed or called.
        // A successful value is the current APK digest only, with no fabricated
        // GASS cache/attestation/status/retry response attached to it.
        localAppDigest=executor.submit(new Callable<String>(){public String call()throws Exception {
            String digest=LocalAppDigestSource.currentBaseApk(invocationContext);
            if(observer!=PrimitiveObserver.NONE)PrimitiveObserver.emit(observer,"local_apk_digest_task_completed",Task.APP_INFO,PrimitiveObserver.fields("source","actual ApplicationInfo.sourceDir; whole-file SHA-256; no GASS IPC","actualDigest",digest));
            return digest;
        }});
        managerPrepared=true;
    }
    /** Real lifecycle source for the flags provider; resolve active flags again after initialize. */
    public FlagsInputs.Provider lifecycleProvider() {
        return new FlagsInputs.Provider(){
            public Object read(String symbol) {
                if("zzjj.zzp".equals(symbol))return googleAvailable;
                if("zzjj.zzr".equals(symbol))return ready;
                if("zzjj.zzs".equals(symbol))return userPresent;
                return null;
            }
            public String provenance(String symbol) {
                if("zzjj.zzp".equals(symbol))return "local PackageManager GMS package present and enabled; no GMS API call";
                if("zzjj.zzr".equals(symbol))return "IMA-local compiled 19-task source registry initialized="+ready+"; no IMA Dex loader";
                if("zzjj.zzs".equals(symbol))return "IMA-local actual USER_PRESENT/SCREEN_OFF broadcast state, initialized true";
                return null;
            }
        };
    }
    public long currentTimeMillis(){return System.currentTimeMillis();}
    public int androidSdk(){return effectiveSdk.get();}
    public String packageName(){return invocationContext.getPackageName();}
    public boolean screenRuntimeFlag(){return userPresent;}
    public boolean networkProviderPresent(){return networkProviderPresent;}
    public boolean methodAvailable(Task task){return ready;}
    @Override public synchronized void initialize(final ResolvedFlags flags,ExecutorService executor)throws Exception {
        if(initialized)return;
        if(closed)throw new IllegalStateException("sources closed");
        if(flags.zzmj_zzv&&Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Task Context initialization must not be called from the UI thread.");
        prepareManager(executor);
        if(flags.zzmj_zzx)initializeNetwork();
        if(flags.zzmj_zzy&&executor!=null)initializeVpn(executor);
        bookkeepingInitialized=flags.zzmj_zzp;
        boolean helperSelected=flags.zzmj_zzq||flags.zzai_zzh;
        Future<String> helper=helperSelected?initializeHelperChecksum(flags):null;
        appDigest=new CompatAppDigestResolver(helperSelected,helper,localAppDigest,new CompatAppDigestResolver.DirectChecksum(){public String queryAndWait(ResolvedFlags current)throws Exception {return directChecksum(current);}},androidSdk(),observer);
        ready=true;initialized=true;
    }
    private void initializeNetwork() {
        // zzim.zzc returns a provider whenever Context is nonnull, even when the
        // manager/API prevents registration. Null capability is genuine state.
        networkProviderPresent=true;
        connectivity=(ConnectivityManager)invocationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(connectivity==null||androidSdk()<24)return;
        networkCallback=new ConnectivityManager.NetworkCallback(){
            @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities capabilities){networkCapabilities=capabilities;}
            @Override public void onLost(Network network){networkCapabilities=null;}
        };
        try{Api24Network.register(connectivity,networkCallback);}catch(RuntimeException ignored){networkCapabilities=null;networkCallback=null;}
    }
    private void initializeVpn(ExecutorService executor) {
        if(androidSdk()<30)return;
        try {
            appOps=(AppOpsManager)invocationContext.getSystemService(Context.APP_OPS_SERVICE);
            vpnListener=Api30Vpn.start(appOps,executor,new Api30Vpn.Events(){
                @Override public void onActive(boolean active) {
                    long now=System.currentTimeMillis();synchronized(vpnLock){
                        if(active){vpnStart=now;vpnActive=true;return;}
                        if(vpnStart>0&&now>=vpnStart)vpnCompleted=now-vpnStart;
                        vpnActive=false;
                    }
                }
            });
        }catch(IllegalArgumentException ignored){vpnListener=null;}
        catch(NoSuchMethodError ignored){vpnListener=null;}
    }
    @Override public void beforeRequest(ResolvedFlags flags)throws Exception {
        synchronized(vpnLock){if(vpnActive)vpnEnd=System.currentTimeMillis();}
        if(flags.zzmj_zzp) {
            if(!bookkeepingInitialized)throw new CompatMode1Runtime.UnsupportedBranch("bookkeeping flag enabled after initialization without original tracker; unsupported state transition");
            previousMode1Uptime=currentMode1Uptime;currentMode1Uptime=SystemClock.uptimeMillis();
        }
    }
    @Override public long[] consumeNetworkTimes() {
        synchronized(vpnLock){long duration=vpnCompleted;vpnCompleted=-1;return new long[]{vpnActive?vpnEnd-vpnStart:-1,duration};}
    }
    @Override public void collect(Task task,Inputs in,ResolvedFlags flags)throws Exception {
        if(!ready)throw new CompatMode1Runtime.UnsupportedBranch("independent source registry not initialized");
        try {
            switch(task) {
                case APP_INFO: {
                    CompatAppDigestResolver.Selection selected;
                    if(appDigest.usesHelper()) {
                        collectAppRaw(in,false,"");
                        if(observer!=PrimitiveObserver.NONE)PrimitiveObserver.emit(observer,"app_metadata_before_helper_wait",task,PrimitiveObserver.fields("inputs",PrimitiveObserver.inputs(task,in)));
                        selected=appDigest.select(flags);
                    } else {
                        selected=appDigest.select(flags);
                        collectAppRaw(in,selected.allowLocalApkHash,selected.suppliedDigest);
                    }
                    in.appDigest.overridePrimary=selected.overridePrimary;in.appDigest.resolvedPrimary=selected.resolvedPrimary;
                    break;
                }
                case WALL_CLOCK:in.wallTimeMillis=System.currentTimeMillis();break;
                case OS_RELEASE:in.osRelease=AndroidCompatInputs.osRelease();break;
                case DISPLAY:{long[] dimensions=AndroidCompatInputs.screenPixels(invocationContext);if(dimensions!=null){in.widthPixels=(int)dimensions[0];in.heightPixels=(int)dimensions[1];}break;}
                case TIME_ZONE:{Calendar now=Calendar.getInstance();in.zoneOffsetMillis=now.get(Calendar.ZONE_OFFSET);in.dstOffsetMillis=now.get(Calendar.DST_OFFSET);break;}
                case SIGNATURE:collectSignatureRaw(in);break;
                case BATTERY:collectBatteryRaw(in);break;
                case ORIENTATION:in.orientation=context.getResources().getConfiguration().orientation;break;
                case PROCESS:collectProcess(in);break;
                case PROCESSORS:in.availableProcessors=Runtime.getRuntime().availableProcessors();break;
                case BUILD_CHECK:
                    try{in.superuserApkExists=new File("/system/app/Superuser.apk").exists();}catch(Exception ignored){in.superuserApkExists=false;}
                    in.buildModel=com.smart.android.ad_app.google.ima.local.ImaFirmwareInputs.model();in.buildHardware=com.smart.android.ad_app.google.ima.local.ImaFirmwareInputs.hardware();break;
                case USB:in.usbConnected=AndroidCompatInputs.usbConnected(context);break;
                case ADB:in.adbEnabled=AndroidCompatInputs.adbEnabled(context);break;
                case CLOCKS:{long[] clocks=AndroidCompatInputs.clocks();in.elapsedRealtimeMillis=clocks[0];in.uptimeMillis=clocks[1];break;}
                case SCREEN_FLAGS:collectScreen(in);break;
                case NETWORK:collectNetwork(in);break;
                case PROXY:in.httpProxyHost=System.getProperty("http.proxyHost");break;
                case LAUNCHER:in.launcherAvailable=AndroidCompatInputs.hasLauncherActivity(context);break;
                case MODULE_VERSION:break; // pinned module local constant is in builder
                default:throw new CompatMode1Runtime.UnsupportedBranch("unimplemented source task "+task);
            }
        }catch(CompatMode1Runtime.UnsupportedBranch unsupported){throw unsupported;}
        catch(Throwable actualSourceFailure){throw new CompatMode1Runtime.InvocationFailure(actualSourceFailure,actualSourceFailure instanceof Settings.SettingNotFoundException);}
    }
    @Override public Map<String,Object> historySnapshot() {
        synchronized(vpnLock) {
            return PrimitiveObserver.fields("userPresentBroadcastState",userPresent,"networkProviderPresent",networkProviderPresent,"networkCapabilitiesPresent",networkCapabilities!=null,
                "vpnWatcherInitialized",vpnListener!=null,"vpnStartWallMillis",vpnStart,"vpnEndWallMillis",vpnEnd,"vpnCompletedDurationMillis",vpnCompleted,"vpnActive",vpnActive,
                "gassRequestPolicy","disabled-before-client-construction","gassInitialFuturePresent",false,"gassInitialFutureDone",false,"latestActualGassResponse",null,
                "appDigestSourcePolicy","local-sourceDir-SHA256-for-former-GASS-branches; original-direct-checksum-preserved","localAppDigestFuturePresent",localAppDigest!=null,"localAppDigestFutureDone",localAppDigest!=null&&localAppDigest.isDone(),
                "helperInitialized",appDigest!=null&&appDigest.usesHelper(),"bookkeepingInitialized",bookkeepingInitialized,"previousMode1Uptime",previousMode1Uptime,"currentMode1Uptime",currentMode1Uptime);
        }
    }
    @SuppressWarnings("deprecation") private void collectSignatureRaw(Inputs in) {
        try {
            PackageInfo info=invocationContext.getPackageManager().getPackageInfo(invocationContext.getPackageName(),64);
            if(info.signatures.length>0)in.signingCertificateBytes=info.signatures[0].toByteArray();
        }catch(PackageManager.NameNotFoundException unavailable){}
        catch(UnsupportedOperationException unavailable){}
    }
    private void collectBatteryRaw(Inputs in) {
        Intent state=context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(observer!=PrimitiveObserver.NONE)PrimitiveObserver.emit(observer,"battery_intent_read",Task.BATTERY,PrimitiveObserver.fields("intentPresent",state!=null));
        // Missing extras remain null in the observer; the frozen builder applies
        // the exact module defaults before its double/round calculation.
        Mode1PayloadBuilder.Battery battery=new Mode1PayloadBuilder.Battery();
        battery.status=state.hasExtra("status")?state.getIntExtra("status",1):null;
        battery.level=state.hasExtra("level")?state.getIntExtra("level",-1):null;
        battery.scale=state.hasExtra("scale")?state.getIntExtra("scale",-1):null;
        battery.temperature=state.hasExtra("temperature")?state.getIntExtra("temperature",Integer.MIN_VALUE):null;
        in.battery=battery;
    }
    /** Raw presence/source version of embedded n; no fabricated availability booleans. */
    private void collectAppRaw(Inputs in,boolean allowLocalHash,String knownDigest) {
        in.appInfo=new Mode1PayloadBuilder.AppInfo();in.appDigest=new Mode1PayloadBuilder.AppDigestInput();
        in.appDigest.suppliedDigest=knownDigest;in.appDigest.computeApkDigest=allowLocalHash;
        if((knownDigest==null||knownDigest.isEmpty())&&allowLocalHash)try{in.appDigest.apkSha256Bytes=hashModuleApk();}catch(Exception ignored){}
        try {
            PackageInfo info;
            try{info=invocationContext.getPackageManager().getPackageInfo(invocationContext.getPackageName(),0);}
            catch(PackageManager.NameNotFoundException missing){info=null;}
            if(info!=null){in.appInfo.packageInfoAvailable=true;in.appInfo.versionCode=(long)info.versionCode;in.appInfo.versionName=info.versionName;}
        }catch(Exception ignored){}
        try {
            String pkg=invocationContext.getPackageName();String installer=invocationContext.getPackageManager().getInstallerPackageName(pkg);
            in.packageName=pkg;in.appInfo.installerPackageName=installer;in.appInfo.installerReadSucceeded=true;
        }catch(Exception ignored){}
    }
    private byte[] hashModuleApk()throws Exception {
        File file=new File(invocationContext.getPackageResourcePath());
        try{if(!file.exists()||!file.canRead())return null;}catch(SecurityException denied){return null;}
        FileInputStream stream=new FileInputStream(file);
        try {MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] block=new byte[16384];for(int n=stream.read(block);n!=-1;n=stream.read(block))digest.update(block,0,n);return digest.digest();}
        finally{stream.close();}
    }
    private void collectProcess(Inputs in) {
        in.packageName=context.getPackageName();ActivityManager manager=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> actual=manager.getRunningAppProcesses();
        if(actual==null)throw new NullPointerException("actual running process list is null");
        in.processes=new ArrayList<Mode1PayloadBuilder.ProcessInfo>();
        for(ActivityManager.RunningAppProcessInfo p:actual)in.processes.add(new Mode1PayloadBuilder.ProcessInfo(p.pid,p.processName,p.importance));
        if(in.processMatchByPid)in.ownPid=Process.myPid();
        in.processRandomDraw=new Random().nextInt(64)+1;
    }
    @SuppressWarnings("deprecation") private void collectScreen(Inputs in) {
        KeyguardManager keyguard=(KeyguardManager)context.getSystemService(Context.KEYGUARD_SERVICE);
        in.screenOn=((PowerManager)context.getSystemService(Context.POWER_SERVICE)).isScreenOn();
        in.keyguardLocked=keyguard!=null&&(Build.VERSION.SDK_INT>=28?keyguard.isKeyguardLocked():keyguard.inKeyguardRestrictedInputMode());
    }
    private void collectNetwork(Inputs in) {
        in.networkProviderPresent=networkProviderPresent;NetworkCapabilities caps=networkCapabilities;
        in.networkCapabilitiesPresent=caps!=null;
        if(caps!=null){in.networkVpn=caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);if(!in.networkVpn){in.networkWifi=caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);if(!in.networkWifi)in.networkCellular=caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);}}
    }
    private Future<String> initializeHelperChecksum(final ResolvedFlags flags) {
        final SimplePromise<String> checksum=new SimplePromise<String>();
        if(androidSdk()<31)checksum.complete("");
        else try{requestChecksums(CompatChecksumCertificates.helper("user".equals(Build.TYPE)),checksum,"");}catch(Throwable failure){checksum.complete("");}
        final SimplePromise<String> result=new SimplePromise<String>();
        checksum.whenCompleteAsync((actual,error)->{
            if(error!=null){result.completeExceptionally(error);return;}
            if((actual!=null&&!actual.isEmpty())||flags.zzai_zzf){result.complete(actual);return;}
            executor.submit(new Runnable(){public void run(){try{result.complete(hashCurrentApk());}catch(Throwable failed){result.completeExceptionally(failed);}}});
        },executor);
        return result;
    }
    private String directChecksum(ResolvedFlags flags)throws Exception {
        try {
            List<Certificate> certs=CompatChecksumCertificates.direct(flags.zzmj_zzf,flags.zzmj_zzg,"user".equals(Build.TYPE));
            if(androidSdk()<=30&&!"S".equals(Build.VERSION.CODENAME))return null;
            SimplePromise<String> response=new SimplePromise<String>();requestChecksums(certs,response,null);
            if(Thread.interrupted())throw new InterruptedException();
            String actual=response.get();
            if(observer!=PrimitiveObserver.NONE)PrimitiveObserver.emit(observer,"direct_checksum_wait_returned",Task.APP_INFO,PrimitiveObserver.fields("actualResult",actual));
            return actual;
        }catch(PackageManager.NameNotFoundException ignored){return null;}
        catch(InterruptedException ignored){return null;}
        catch(ExecutionException ignored){return null;}
        catch(CertificateException ignored){return null;}
        catch(NoClassDefFoundError ignored){return null;}
    }
    private void requestChecksums(List<Certificate> certs,final SimplePromise<String> response,final String unavailableValue)throws PackageManager.NameNotFoundException,java.security.cert.CertificateEncodingException {
        final long checksumRequest=PrimitiveObserver.Scope.request();
        // Android may invoke checksum callbacks on a different binder/executor
        // thread; retain the initiating entry's sink, never its mutable routing.
        final PrimitiveObserver callbackObserver=observer instanceof ScopedObserver?((ScopedObserver)observer).currentObserver():observer;
        Api31Checksums.request(invocationContext,certs,new Api31Checksums.Events(){
            public void onType8(byte[] actualBytes) {
                if(callbackObserver!=PrimitiveObserver.NONE)PrimitiveObserver.emit(callbackObserver,"android_checksum_type8_returned",Task.APP_INFO,checksumRequest,PrimitiveObserver.fields("actualChecksumBytes",actualBytes,"unavailableValue",unavailableValue));
                response.complete(hex(actualBytes));
            }
            public void onUnavailable(boolean listPresent) {
                if(callbackObserver!=PrimitiveObserver.NONE)PrimitiveObserver.emit(callbackObserver,"android_checksum_unavailable",Task.APP_INFO,checksumRequest,PrimitiveObserver.fields("listPresent",listPresent,"actualFallback",unavailableValue));
                response.complete(unavailableValue);
            }
        });
    }
    private String hashCurrentApk()throws Exception {
        File file=new File(invocationContext.getPackageResourcePath());if(!file.exists()||!file.canRead())return "";
        try {
            FileInputStream stream=new FileInputStream(file);
            try{MessageDigest digest=MessageDigest.getInstance("SHA256");byte[] block=new byte[16384];for(int n=stream.read(block);n!=-1;n=stream.read(block))digest.update(block,0,n);return hex(digest.digest());}
            finally{stream.close();}
        }catch(IOException ignored){return "";}
        catch(UnsupportedOperationException ignored){return "";}
        catch(NoSuchAlgorithmException ignored){return "";}
    }
    private static String hex(byte[] input) {
        char[] out=new char[input.length*2],alphabet="0123456789abcdef".toCharArray();
        for(int i=0;i<input.length;i++){int b=input[i]&255;out[i*2]=alphabet[b>>>4];out[i*2+1]=alphabet[b&15];}return new String(out);
    }
    @Override public synchronized void close() {
        if(closed)return;closed=true;ready=false;Throwable first=null;
        if(receiverRegistered){try{context.unregisterReceiver(screenReceiver);}catch(Throwable e){first=e;}finally{receiverRegistered=false;}}
        if(connectivity!=null&&networkCallback!=null)try{connectivity.unregisterNetworkCallback(networkCallback);}catch(RuntimeException ignored){}catch(Throwable e){if(first==null)first=e;else first.addSuppressed(e);}
        if(appOps!=null&&vpnListener!=null)try{Api30Vpn.stop(appOps,vpnListener);}catch(RuntimeException ignored){}catch(Throwable e){if(first==null)first=e;else first.addSuppressed(e);}
        if(localAppDigest!=null)localAppDigest.cancel(true);
        if(first instanceof Error)throw (Error)first;if(first instanceof RuntimeException)throw (RuntimeException)first;if(first!=null)throw new IllegalStateException(first);
    }
}
