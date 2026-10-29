package com.smart.android.ad_app.google.ima.local.signal.v2;

import android.content.Context;
import android.os.Bundle;
import com.smart.android.ad_app.google.ima.local.signal.android.DroidGuardLocalReceiver;
import com.smart.android.ad_app.google.ima.local.signal.android.PinnedStandaloneHealth;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Pinned standalone SDK entry: empty constructor Bundle, proven health branch,
 * one independently executed resident VM, real receiver bindings and close.
 * Does not select a module or use bundled historical bytes as a live substitute.
 */
public final class PinnedStandaloneBackend implements AutoCloseable {
    private static final AtomicLong TOKENS=new AtomicLong(1);
    private final DroidGuardLocalReceiver receiver;
    private final PinnedStandaloneHealth health;
    private HistoricalV2Engine engine;
    private boolean closed;
    private PinnedStandaloneBackend(DroidGuardLocalReceiver receiver,PinnedStandaloneHealth health){this.receiver=receiver;this.health=health;}
    public static PinnedStandaloneBackend open(Context context,byte[] actualSelectedProgram,byte[] independentlyRecoveredPinnedTable,byte[] genuineArc4Entropy32)throws Exception {
        if(context==null)throw new NullPointerException("Actual constructor Context");
        final Bundle bundle=new Bundle();
        final DroidGuardLocalReceiver receiver=new DroidGuardLocalReceiver(context,"msa-r",actualSelectedProgram,null,bundle,2);
        final PinnedStandaloneHealth health=PinnedStandaloneHealth.currentSdkEmptyBundle(bundle,2);
        final PinnedStandaloneBackend backend=new PinnedStandaloneBackend(receiver,health);
        boolean opened=false;
        try {
            health.beforeVmPhase(1);
            try{backend.engine=HistoricalV2Engine.open(actualSelectedProgram,independentlyRecoveredPinnedTable,context,bundle,receiver,genuineArc4Entropy32,2,new HistoricalV2Engine.CloseAction(){public void closeReceiver(){receiver.close();}});}
            finally{health.afterVmPhase();}
            long token=TOKENS.getAndIncrement();if(token==0)token=TOKENS.getAndIncrement();final long actualToken=token;
            receiver.bindIndependentVm(new DroidGuardLocalReceiver.VmEntries(){
                private void check(long state){if(state!=actualToken)throw new IllegalStateException("Receiver belongs to a different independent VM");}
                public long initialize(DroidGuardLocalReceiver r,Context c,String flow,byte[] p,Object a,Bundle b,int fd,int env){throw new UnsupportedOperationException("Pinned owner initializes once through open; external receiver reinitialization is outside the SDK URL entry");}
                public void close(long state,String[] values){check(state);try{backend.engine.closeNativeState();}catch(Exception failure){throw new IllegalStateException("Actual independent close failed",failure);}}
                public int status(long state){check(state);return health.status();}
                public void event(long state,Map values){check(state);throw new UnsupportedOperationException("External touch/event entry is not used by the current URL-only bridge");}
                public byte[] snapshot(long state,String[] values,Bundle extra){check(state);throw new UnsupportedOperationException("Current standalone SDK uses xss(null,map), not the alternate ss entry");}
                public byte[] extendedSnapshot(long state,String[] values,Map extra){check(state);if(values.length!=0)throw new UnsupportedOperationException("Current SDK xss first argument is null");try{return backend.query(extra).rawXss();}catch(Exception failure){throw new IllegalStateException("Actual independent xss failed",failure);}}
            },actualToken);
            if(receiver.lcs()!=0)throw new IllegalStateException("Actual constructor health rejected");
            opened=true;return backend;
        }finally{if(!opened){try{if(backend.engine!=null)backend.engine.close();else receiver.close();}finally{health.close();}}}
    }
    public synchronized HistoricalV2Engine.Result query(Map<String,Object> independentlyBuiltMap)throws Exception {
        if(closed)throw new IllegalStateException("Standalone backend disposed");
        health.beforeVmPhase(3);try{return engine.query(independentlyBuiltMap);}finally{health.afterVmPhase();}
    }
    public synchronized int status(){if(closed)throw new IllegalStateException("Standalone backend disposed");return receiver.lcs();}
    public long initializationStepCount(){return engine.initializationStepCount();}
    public long completedQueryCount(){return engine.completedQueryCount();}
    public long closeStepCount(){return engine.closeStepCount();}
    public synchronized boolean isClosed(){return closed;}
    @Override public synchronized void close()throws Exception {
        if(closed)return;
        health.beforeVmPhase(5);
        try{engine.close();}finally{health.afterVmPhase();health.close();closed=true;}
    }
}
