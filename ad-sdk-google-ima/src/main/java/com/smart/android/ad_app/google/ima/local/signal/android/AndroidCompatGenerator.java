package com.smart.android.ad_app.google.ima.local.signal.android;

import android.content.Context;
import com.smart.android.ad_app.google.ima.local.Mode1PayloadBuilder.Inputs;
import com.smart.android.ad_app.google.ima.local.Mode1PayloadBuilder.Task;
import com.smart.android.ad_app.google.ima.local.signal.CompatMode1Runtime;
import com.smart.android.ad_app.google.ima.local.signal.ResolvedFlags;
import com.smart.android.ad_app.google.ima.local.signal.PrimitiveObserver;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Independent compatible delegate. Shared getters/watchers/manager belong to
 * ProcessScope; every native entry has its own delegate/runtime and journal. */
public final class AndroidCompatGenerator implements AutoCloseable {
    private final ProcessScope scope;
    private final CompatMode1Runtime runtime;
    private final boolean ownsScope;
    private boolean closed;
    public final Future<?> flagsInitialization;
    private AndroidCompatGenerator(ProcessScope scope,CompatMode1Runtime runtime,boolean ownsScope) {
        this.scope=scope;this.runtime=runtime;this.ownsScope=ownsScope;flagsInitialization=scope.flagsInitialization;
    }
    /** Routes read-only diagnostics per collecting entry without sharing a delegate.
     * Async sources must capture currentObserver before leaving the task thread. */
    static final class ObserverRouter implements AndroidCompatLiveSources.ScopedObserver {
        private final PrimitiveObserver initial;
        private final ThreadLocal<PrimitiveObserver> local=new ThreadLocal<PrimitiveObserver>();
        ObserverRouter(PrimitiveObserver initial){this.initial=initial==null?PrimitiveObserver.NONE:initial;}
        public PrimitiveObserver currentObserver(){PrimitiveObserver value=local.get();return value==null?initial:value;}
        void enter(PrimitiveObserver value){local.set(value==null?PrimitiveObserver.NONE:value);}
        void leave(){local.remove();}
        public void onEvent(PrimitiveObserver.Event event){currentObserver().onEvent(event);}
    }
    public static final class ProcessScope implements AutoCloseable {
        private final AndroidCompatLiveSources sources;
        private final ImaLocalFlagsProvider flags;
        private final CompatMode1Runtime.ProcessState process;
        private final ExecutorService executor;
        private final ObserverRouter observer;
        public final Future<?> flagsInitialization;
        private boolean closed;
        private long createdEntries;
        private ProcessScope(AndroidCompatLiveSources sources,ImaLocalFlagsProvider flags,CompatMode1Runtime.ProcessState process,ExecutorService executor,Future<?> initialization,ObserverRouter observer){this.sources=sources;this.flags=flags;this.process=process;this.executor=executor;this.flagsInitialization=initialization;this.observer=observer;}
        public static ProcessScope open(Context context,ImaLocalFlagsProvider.IntSource effectiveSdk,boolean forceTvMode,CompatMode1Runtime.ProcessState process)throws Exception{return open(context,effectiveSdk,forceTvMode,process,PrimitiveObserver.NONE);}
        public static ProcessScope open(Context context,ImaLocalFlagsProvider.IntSource effectiveSdk,boolean forceTvMode,CompatMode1Runtime.ProcessState process,PrimitiveObserver initialObserver)throws Exception {
            if(context==null||process==null)throw new IllegalArgumentException("actual Context and process state required");
            if(android.os.Build.VERSION.SDK_INT<23)throw new CompatMode1Runtime.UnsupportedBranch("this source fork requires the application minimum actual API23; effectiveSdk is a separate input");
            ExecutorService executor=Executors.newCachedThreadPool();AndroidCompatLiveSources sources=null;ImaLocalFlagsProvider flags=null;
            try {
                // Original zzig.zzr is static synchronized: baseline and zzjj/getter
                // objects initialize once before any per-entry compatible delegate.
                process.initializeBaseline(System.currentTimeMillis());
                ObserverRouter router=new ObserverRouter(initialObserver);
                sources=new AndroidCompatLiveSources(context,effectiveSdk,router);
                flags=new ImaLocalFlagsProvider(context,effectiveSdk,forceTvMode,sources.lifecycleProvider());
                Future<?> initialization=flags.initializeAsync(executor);
                sources.prepareManager(executor);
                ResolvedFlags.resolve(flags);
                return new ProcessScope(sources,flags,process,executor,initialization,router);
            }catch(Throwable failure){
                try{if(sources!=null)sources.close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
                try{if(flags!=null)flags.close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
                try{executor.shutdownNow();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}rethrow(failure);throw new AssertionError();
            }
        }
        public synchronized AndroidCompatGenerator newEntry(PrimitiveObserver actualObserver)throws Exception{return newEntry(actualObserver,false);}
        private synchronized AndroidCompatGenerator newEntry(PrimitiveObserver actualObserver,boolean owned)throws Exception {
            if(closed)throw new IllegalStateException("compatible process scope closed");
            final PrimitiveObserver sink=actualObserver==null?PrimitiveObserver.NONE:actualObserver;
            CompatMode1Runtime.Sources borrowed=new CompatMode1Runtime.Sources(){
                public long currentTimeMillis(){return sources.currentTimeMillis();}
                public int androidSdk(){return sources.androidSdk();}
                public String packageName(){return sources.packageName();}
                public void initialize(ResolvedFlags f,ExecutorService e)throws Exception{observer.enter(sink);try{sources.initialize(f,e);}finally{observer.leave();}}
                public void beforeRequest(ResolvedFlags f)throws Exception{sources.beforeRequest(f);}
                public long[] consumeNetworkTimes(){return sources.consumeNetworkTimes();}
                public boolean screenRuntimeFlag(){return sources.screenRuntimeFlag();}
                public boolean networkProviderPresent(){return sources.networkProviderPresent();}
                public Map<String,Object> historySnapshot(){return sources.historySnapshot();}
                public boolean methodAvailable(Task t){return sources.methodAvailable(t);}
                public void collect(Task t,Inputs input,ResolvedFlags f)throws Exception{observer.enter(sink);try{sources.collect(t,input,f);}finally{observer.leave();}}
                public void close(){} // Shared source lifetime is owned by ProcessScope.
            };
            CompatMode1Runtime runtime=new CompatMode1Runtime(borrowed,process,ResolvedFlags.resolve(flags),executor,false,sink);createdEntries++;
            return new AndroidCompatGenerator(this,runtime,owned);
        }
        public synchronized Map<String,Object> snapshot(){return PrimitiveObserver.fields("closed",closed,"identity",System.identityHashCode(this),"sourcesIdentity",System.identityHashCode(sources),"managerExecutorIdentity",System.identityHashCode(executor),"createdEntries",createdEntries,"sourceHistory",PrimitiveObserver.freeze(sources.historySnapshot()));}
        public ResolvedFlags currentFlags(){return ResolvedFlags.resolve(flags);}
        @Override public synchronized void close()throws Exception{if(closed)return;closed=true;Throwable failure=null;try{sources.close();}catch(Throwable e){failure=e;}try{flags.close();}catch(Throwable e){if(failure==null)failure=e;else failure.addSuppressed(e);}try{executor.shutdownNow();}catch(Throwable e){if(failure==null)failure=e;else failure.addSuppressed(e);}if(failure!=null)rethrow(failure);}
    }
    public static AndroidCompatGenerator create(Context context,ImaLocalFlagsProvider.IntSource effectiveSdk,boolean forceTvMode,CompatMode1Runtime.ProcessState process)throws Exception{return create(context,effectiveSdk,forceTvMode,process,PrimitiveObserver.NONE);}
    public static AndroidCompatGenerator create(Context context,ImaLocalFlagsProvider.IntSource effectiveSdk,boolean forceTvMode,CompatMode1Runtime.ProcessState process,PrimitiveObserver observer)throws Exception {
        ProcessScope scope=ProcessScope.open(context,effectiveSdk,forceTvMode,process,observer);try{return scope.newEntry(observer,true);}catch(Throwable failure){try{scope.close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}rethrow(failure);throw new AssertionError();}
    }
    /** Isolated diagnostic convenience; production uses one shared ProcessScope. */
    public static AndroidCompatGenerator create(Context context,final int effectiveSdk,final String provenance,boolean forceTvMode)throws Exception{return create(context,new ImaLocalFlagsProvider.IntSource(){public int get(){return effectiveSdk;}public String provenance(){return provenance;}},forceTvMode,new CompatMode1Runtime.ProcessState());}
    public static AndroidCompatGenerator createObserved(Context context,final int effectiveSdk,final String provenance,boolean forceTvMode,PrimitiveObserver observer)throws Exception{return create(context,new ImaLocalFlagsProvider.IntSource(){public int get(){return effectiveSdk;}public String provenance(){return provenance;}},forceTvMode,new CompatMode1Runtime.ProcessState(),observer);}
    private static void rethrow(Throwable failure)throws Exception{if(failure instanceof Exception)throw (Exception)failure;if(failure instanceof Error)throw (Error)failure;throw new RuntimeException(failure);}
    public ResolvedFlags currentFlags(){return scope.currentFlags();}
    public CompatMode1Runtime.Result generate()throws Exception {if(closed)throw new IllegalStateException("compatible entry closed");return runtime.generate(currentFlags());}
    public Map<String,Object> processSnapshot(){return scope.snapshot();}
    @Override public synchronized void close()throws Exception{if(closed)return;closed=true;try{runtime.close();}finally{if(ownsScope)scope.close();}}
}
