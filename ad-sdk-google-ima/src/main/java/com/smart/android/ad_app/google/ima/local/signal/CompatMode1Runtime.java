package com.smart.android.ad_app.google.ima.local.signal;

import com.smart.android.ad_app.google.ima.local.Mode1PayloadBuilder;
import com.smart.android.ad_app.google.ima.local.Mode1PayloadBuilder.Inputs;
import com.smart.android.ad_app.google.ima.local.Mode1PayloadBuilder.Task;
import com.smart.android.ad_app.google.ima.local.Mode1PayloadBuilder.Stage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Independent mode1 execution container. Source-domain calculations and protobuf
 * encoding are delegated to the frozen independent Mode1PayloadBuilder, never IMA.
 * A process scope owns caches/baseline/watches. It should live for the application
 * process, not be recreated for every request. Runtime input failures are reported;
 * unsupported source branches abort instead of inventing missing source values.
 */
public final class CompatMode1Runtime implements AutoCloseable {
    public interface Sources extends AutoCloseable {
        long currentTimeMillis();
        int androidSdk();
        String packageName();
        /** Attach real broadcasts/network watchers and start async sources once. */
        void initialize(ResolvedFlags initialFlags, ExecutorService executor) throws Exception;
        /** zzv(): update active VPN interval end before task construction. */
        void beforeRequest(ResolvedFlags flags) throws Exception;
        /** Construction-time snapshot consumed by the network task, including one-shot duration. */
        long[] consumeNetworkTimes();
        boolean screenRuntimeFlag();
        boolean networkProviderPresent();
        /** Read-only private history snapshot; never consumes timing or reads a generated output. */
        default Map<String,Object> historySnapshot(){return Collections.emptyMap();}
        /** Independent compiled getter availability; false models no resolved method. */
        boolean methodAvailable(Task task);
        /** Populate actual source-domain values only. Must not read a prior payload/ms/URL. */
        void collect(Task task, Inputs target, ResolvedFlags flags) throws Exception;
    }
    public static final class UnsupportedBranch extends Exception {
        public UnsupportedBranch(String message) { super(message); }
        public UnsupportedBranch(String message,Throwable cause) { super(message,cause); }
    }
    /** A real source invocation failure, equivalent to the pinned reflection target failure. */
    public static final class InvocationFailure extends Exception {
        public final boolean settingNotFound;
        public InvocationFailure(Throwable cause,boolean settingNotFound) {
            super(cause);this.settingNotFound=settingNotFound;
        }
    }
    public static final class TaskEvent {
        public final Task task;
        public volatile Stage stage=Stage.NOT_ENTERED;
        public volatile boolean cancelled;
        public volatile boolean prewriteCommitted,resultCommitted;
        public volatile String failureClass;
        public volatile long enteredNanoTime,finishedNanoTime;
        TaskEvent(Task task) { this.task=task; }
    }
    public static final class Result {
        public final byte[] payload;
        public final long baselineSeconds;
        public final List<Integer> fieldNumbers;
        public final Map<Task,TaskEvent> tasks;
        public final boolean outerWaitInterrupted;
        public final boolean unfinishedTasksAtSnapshot;
        Result(byte[] payload,long baseline,List<Integer> fields,Map<Task,TaskEvent> tasks,boolean interrupted,boolean unfinished) {
            this.payload=payload;this.baselineSeconds=baseline;
            this.fieldNumbers=Collections.unmodifiableList(fields);
            this.tasks=Collections.unmodifiableMap(tasks);
            this.outerWaitInterrupted=interrupted;this.unfinishedTasksAtSnapshot=unfinished;
        }
    }
    /** Explicit process lifetime. Pass the same instance to all sessions in one process. */
    public static final class ProcessState {
        private final ConcurrentHashMap<Task,AtomicReference<Inputs>> globalCaches=new ConcurrentHashMap<Task,AtomicReference<Inputs>>();
        private final ConcurrentHashMap<String,AtomicReference<Inputs>> appCache=new ConcurrentHashMap<String,AtomicReference<Inputs>>();
        private final ConcurrentHashMap<String,AtomicReference<Inputs>> signatureCache=new ConcurrentHashMap<String,AtomicReference<Inputs>>();
        private Long baselineSeconds;
        public synchronized long initializeBaseline(long wallMillis) {
            if(baselineSeconds==null)baselineSeconds=wallMillis/1000L;
            return baselineSeconds;
        }
        private AtomicReference<Inputs> cache(Task task,String pkg) {
            if(task==Task.APP_INFO)return packageRef(appCache,pkg);
            if(task==Task.SIGNATURE)return packageRef(signatureCache,pkg);
            if(task!=Task.OS_RELEASE&&task!=Task.TIME_ZONE&&task!=Task.PROCESSORS&&task!=Task.BUILD_CHECK)return null;
            AtomicReference<Inputs> fresh=new AtomicReference<Inputs>(),old=globalCaches.putIfAbsent(task,fresh);return old==null?fresh:old;
        }
        private static AtomicReference<Inputs> packageRef(ConcurrentHashMap<String,AtomicReference<Inputs>> map,String pkg) {
            AtomicReference<Inputs> fresh=new AtomicReference<Inputs>(),old=map.putIfAbsent(pkg,fresh);return old==null?fresh:old;
        }
    }
    private final Sources sources;
    private final ProcessState process;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final long baselineSeconds;
    private volatile boolean closed;
    private final PrimitiveObserver observer;
    private final AtomicLong requestSequence=new AtomicLong();
    public CompatMode1Runtime(Sources sources,ProcessState process,ResolvedFlags initialFlags) throws Exception {
        this(sources,process,initialFlags,Executors.newCachedThreadPool(),true);
    }
    public CompatMode1Runtime(Sources sources,ProcessState process,ResolvedFlags initialFlags,ExecutorService executor,boolean ownsExecutor) throws Exception {
        this(sources,process,initialFlags,executor,ownsExecutor,PrimitiveObserver.NONE);
    }
    public CompatMode1Runtime(Sources sources,ProcessState process,ResolvedFlags initialFlags,ExecutorService executor,boolean ownsExecutor,PrimitiveObserver observer) throws Exception {
        if(sources==null||process==null||initialFlags==null)throw new IllegalArgumentException("source/process/flags required");
        this.observer=observer==null?PrimitiveObserver.NONE:observer;
        this.sources=sources;this.process=process;this.executor=executor;this.ownsExecutor=ownsExecutor;
        // Matches zzig.zzr: baseline before task manager/watcher/helper construction.
        this.baselineSeconds=process.initializeBaseline(sources.currentTimeMillis());
        sources.initialize(initialFlags,executor);
        if(this.observer!=PrimitiveObserver.NONE) PrimitiveObserver.emit(this.observer,"runtime_initialized",null,0,PrimitiveObserver.fields("baselineSeconds",baselineSeconds,"initialFlags",initialFlags,"history",sources.historySnapshot()));
    }
    public Result generate(final ResolvedFlags flags) throws Exception {
        if(closed)throw new IllegalStateException("runtime closed");
        if(flags.zzif_zza)throw new UnsupportedBranch("advertising ID extension zzkb is outside current 19-task mode1 path; do not force false");
        final long request=requestSequence.incrementAndGet();
        if(observer!=PrimitiveObserver.NONE) PrimitiveObserver.emit(observer,"request_begin",null,request,PrimitiveObserver.fields("flags",flags,"baselineSeconds",baselineSeconds,"effectiveSdk",sources.androidSdk(),"packageName",sources.packageName(),"history",sources.historySnapshot()));
        sources.beforeRequest(flags);
        final Accumulator out=new Accumulator();
        Inputs metadata=new Inputs();metadata.sdkVersion=flags.zzm_zze;metadata.executorAvailable=false;
        out.merge(Mode1PayloadBuilder.build(metadata));
        final EnumMap<Task,TaskEvent> events=new EnumMap<Task,TaskEvent>(Task.class);
        if(executor==null)return result(out,events,false,false,request);
        if(!flags.zzjj_zzr) {
            Inputs unavailable=new Inputs();unavailable.moduleReady=false;
            out.merge(Mode1PayloadBuilder.build(unavailable));return result(out,events,false,false,request);
        }
        final boolean screenFlag=sources.screenRuntimeFlag();
        final long[] networkTimes=enabled(Task.NETWORK,flags)?sources.consumeNetworkTimes():new long[]{-1,-1};
        if(networkTimes==null||networkTimes.length!=2)throw new UnsupportedBranch("network timing provider must return exactly two genuine values, including actual -1 absence");
        if(observer!=PrimitiveObserver.NONE) PrimitiveObserver.emit(observer,"task_constructor_inputs",null,request,PrimitiveObserver.fields("screenRuntimeFlag",screenFlag,"networkTiming0",networkTimes[0],"networkTiming1",networkTimes[1],"networkTaskEnabled",enabled(Task.NETWORK,flags),"historyAfterTimingConsumption",sources.historySnapshot()));
        final AtomicReference<UnsupportedBranch> unsupported=new AtomicReference<UnsupportedBranch>();
        List<Callable<Void>> tasks=new ArrayList<Callable<Void>>();
        for(final Task task:Task.values()) {
            if(!enabled(task,flags))continue;
            final TaskEvent event=new TaskEvent(task);events.put(task,event);
            tasks.add(new Callable<Void>() { public Void call() throws Exception {
                PrimitiveObserver.Scope.enter(request);
                if(!sources.methodAvailable(task)){PrimitiveObserver.emit(observer,"method_unavailable",task,request,Collections.<String,Object>emptyMap());PrimitiveObserver.Scope.leave();return null;}
                event.enteredNanoTime=System.nanoTime();event.stage=Stage.ENTERED_NO_RESULT;
                Inputs pre=base(flags,screenFlag,networkTimes);pre.stage(task,Stage.ENTERED_NO_RESULT);
                commit(out,Mode1PayloadBuilder.build(pre),event,false,Stage.ENTERED_NO_RESULT,request);
                try {
                    Collected collected=collectWithCache(task,flags,screenFlag,networkTimes);
                    Inputs input=collected.input;
                    if(observer!=PrimitiveObserver.NONE) PrimitiveObserver.emit(observer,"task_collected",task,request,PrimitiveObserver.fields("cacheHit",collected.cacheHit,"inputs",PrimitiveObserver.inputs(task,input)));
                    Stage completion=completion(task,input);
                    input.stage(task,completion);
                    commit(out,Mode1PayloadBuilder.build(input),event,true,completion,request);
                } catch(UnsupportedBranch failure) {
                    unsupported.compareAndSet(null,failure);event.failureClass=failure.getClass().getName();throw failure;
                } catch(InvocationFailure failure) {
                    Stage stage=failure.settingNotFound&&task==Task.ADB?Stage.SETTING_NOT_FOUND:Stage.INVOCATION_TARGET_EXCEPTION;
                    Inputs failed=base(flags,screenFlag,networkTimes);failed.stage(task,stage);
                    event.failureClass=failure.getCause().getClass().getName();
                    if(observer!=PrimitiveObserver.NONE) PrimitiveObserver.emit(observer,"actual_invocation_failure",task,request,PrimitiveObserver.fields("failureClass",event.failureClass,"settingNotFound",failure.settingNotFound));
                    commit(out,Mode1PayloadBuilder.build(failed),event,true,stage,request);
                } catch(InterruptedException failure) {
                    // Cancellable future source waits throw from task body; original
                    // Future captures the exception. Already-written fields survive.
                    event.failureClass=failure.getClass().getName();throw failure;
                } catch(Exception failure) {
                    // Do not silently turn an unimplemented bridge or malformed input
                    // into an SDK failure. Only InvocationFailure models real getters.
                    UnsupportedBranch contract=new UnsupportedBranch("unmodeled source/handling for "+task,failure);
                    unsupported.compareAndSet(null,contract);event.failureClass=failure.getClass().getName();throw contract;
                } finally { event.finishedNanoTime=System.nanoTime();PrimitiveObserver.Scope.leave(); }
                return null;
            }});
        }
        boolean interrupted=false;List<Future<Void>> futures=null;
        try { futures=executor.invokeAll(tasks,flags.zzmj_zzk,TimeUnit.MILLISECONDS); }
        catch(InterruptedException failure) { interrupted=true; /* pinned zzt catches without rethrow/restore */ }
        if(futures!=null) {
            int i=0;for(TaskEvent event:events.values())event.cancelled=futures.get(i++).isCancelled();
        }
        if(unsupported.get()!=null)throw unsupported.get();
        boolean unfinished=false;for(TaskEvent e:events.values())if(e.enteredNanoTime!=0&&e.finishedNanoTime==0)unfinished=true;
        return result(out,events,interrupted,unfinished,request);
    }
    private Result result(Accumulator out,EnumMap<Task,TaskEvent> events,boolean interrupted,boolean unfinished,long request) {
        synchronized(out) {
            EnumMap<Task,TaskEvent> snapshot=snapshot(events);
            if(observer!=PrimitiveObserver.NONE) PrimitiveObserver.emit(observer,"request_snapshot",null,request,PrimitiveObserver.fields("outerWaitInterrupted",interrupted,"unfinishedTasksAtSnapshot",unfinished,"tasks",snapshot));
            return new Result(out.bytes(),baselineSeconds,new ArrayList<Integer>(out.fields.keySet()),snapshot,interrupted,unfinished);
        }
    }
    private void commit(Accumulator out,byte[] independentlyGenerated,TaskEvent event,boolean result,Stage stage,long request) {
        synchronized(out) {
            out.merge(independentlyGenerated);event.stage=stage;
            if(result)event.resultCommitted=true;else event.prewriteCommitted=true;
            if(observer!=PrimitiveObserver.NONE) PrimitiveObserver.emit(observer,result?"task_result_committed":"task_prewrite_committed",event.task,request,PrimitiveObserver.fields("stage",stage,"failureClass",event.failureClass));
        }
    }
    private static EnumMap<Task,TaskEvent> snapshot(EnumMap<Task,TaskEvent> source) {
        EnumMap<Task,TaskEvent> copy=new EnumMap<Task,TaskEvent>(Task.class);
        for(Map.Entry<Task,TaskEvent> item:source.entrySet()) {
            TaskEvent original=item.getValue(),event=new TaskEvent(original.task);
            event.stage=original.stage;event.cancelled=original.cancelled;event.failureClass=original.failureClass;event.prewriteCommitted=original.prewriteCommitted;event.resultCommitted=original.resultCommitted;
            event.enteredNanoTime=original.enteredNanoTime;event.finishedNanoTime=original.finishedNanoTime;copy.put(item.getKey(),event);
        }
        return copy;
    }
    private boolean enabled(Task task,ResolvedFlags f) {
        if(task==Task.NETWORK)return sources.androidSdk()>=24&&f.zzmj_zzx;
        if(task==Task.PROXY)return f.zzmj_zzw;
        if(task==Task.MODULE_VERSION)return f.zzmj_zzA;
        return true;
    }
    private Inputs base(ResolvedFlags f,boolean screen,long[] times) {
        Inputs in=new Inputs();in.androidSdk=sources.androidSdk();in.baselineSeconds=baselineSeconds;
        in.enableNetwork=f.zzmj_zzx;in.enableProxy=f.zzmj_zzw;in.enableModuleVersion=f.zzmj_zzA;
        in.screenRuntimeFlag=screen;in.processMatchByPid=f.zzmj_zzt;
        in.networkProviderPresent=sources.networkProviderPresent();in.networkTiming0=times[0];in.networkTiming1=times[1];
        return in;
    }
    private Collected collectWithCache(Task task,ResolvedFlags f,boolean screen,long[] times) throws Exception {
        String cachePackage=(task==Task.APP_INFO||task==Task.SIGNATURE)?sources.packageName():null;
        AtomicReference<Inputs> cache=process.cache(task,cachePackage);
        if(cache==null) {Inputs in=base(f,screen,times);sources.collect(task,in,f);return new Collected(in,false);}
        synchronized(cache) {
            Inputs previous=cache.get();if(validCache(task,previous))return new Collected(previous,true);
            Inputs in=base(f,screen,times);sources.collect(task,in,f);cache.set(in);return new Collected(in,false);
        }
    }
    private static final class Collected { final Inputs input;final boolean cacheHit;Collected(Inputs input,boolean hit){this.input=input;cacheHit=hit;} }
    private static boolean validCache(Task task,Inputs in) {
        if(in==null)return false;
        if(task==Task.APP_INFO) {
            if(in.appDigest==null)return false;
            String p=in.appDigest.overridePrimary?in.appDigest.resolvedPrimary:in.appDigest.suppliedDigest;
            if((p==null||p.isEmpty())&&in.appDigest.computeApkDigest&&in.appDigest.apkSha256Bytes!=null) {
                for(byte b:in.appDigest.apkSha256Bytes)if(b!=0)return true;return false;
            }
            return p!=null&&!p.isEmpty()&&!"E".equals(p)&&!"0000000000000000000000000000000000000000000000000000000000000000".equals(p);
        }
        if(task==Task.SIGNATURE)return in.signatureSha1Hex!=null||in.signingCertificateBytes!=null;
        if(task==Task.OS_RELEASE)return in.osRelease!=null;
        return true;
    }
    private static Stage completion(Task task,Inputs in) throws UnsupportedBranch {
        if(task==Task.DISPLAY&&(in.widthPixels==null||in.heightPixels==null))return Stage.RESULT_AVAILABLE;
        if(task==Task.SIGNATURE&&in.signatureSha1Hex==null&&in.signingCertificateBytes==null)return Stage.RESULT_AVAILABLE;
        if(task==Task.CLOCKS&&(in.elapsedRealtimeMillis==null||in.uptimeMillis==null))return Stage.RESULT_AVAILABLE;
        if(task==Task.OS_RELEASE&&in.osRelease==null)throw new UnsupportedBranch("null OS release after static cache assignment requires dedicated unchecked-failure adapter");
        return Stage.SUCCESS;
    }
    @Override public void close() throws Exception {
        closed=true;try{sources.close();}finally{if(ownsExecutor&&executor!=null)executor.shutdownNow();}
    }
    /** Merge own newly generated task fields; never parses captured payloads. */
    private static final class Accumulator {
        final TreeMap<Integer,byte[]> fields=new TreeMap<Integer,byte[]>();
        synchronized void merge(byte[] generated) {
            int[] cursor={0};while(cursor[0]<generated.length) {
                int start=cursor[0];long tag=varint(generated,cursor);int wire=(int)tag&7;
                if(wire==0)varint(generated,cursor);
                else if(wire==2) {long len=varint(generated,cursor);if(len<0||len>generated.length-cursor[0])throw new IllegalArgumentException("bad generated length");cursor[0]+=(int)len;}
                else throw new IllegalArgumentException("unsupported generated wire type");
                byte[] field=new byte[cursor[0]-start];System.arraycopy(generated,start,field,0,field.length);fields.put((int)(tag>>>3),field);
            }
        }
        synchronized byte[] bytes() {ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] b:fields.values())out.write(b,0,b.length);return out.toByteArray();}
        static long varint(byte[] bytes,int[] cursor) {
            long value=0;for(int shift=0;shift<70;shift+=7) {if(cursor[0]>=bytes.length)throw new IllegalArgumentException("truncated own field");int b=bytes[cursor[0]++]&255;value|=(long)(b&127)<<shift;if(b<128)return value;}throw new IllegalArgumentException("bad own varint");
        }
    }
}
