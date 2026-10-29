package com.smart.android.ad_app.google.ima.local.signal.v2;

import android.content.Context;
import android.os.Bundle;
import com.smart.android.ad_app.google.ima.local.signal.vm.ModifiedUtf8;
import com.smart.android.ad_app.google.ima.local.signal.vm.ReflectionSources;
import com.smart.android.ad_app.google.ima.local.signal.vm.V2Vm;
import com.smart.android.ad_app.google.ima.local.signal.vm.Value;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/** One initialized historical VM, serialized real query calls, explicit close.
 * This implements the normal query path, not the native watchdog/lcs protocol.
 * No thread, deadline, retry, fallback, classloader transport or source substitution
 * is hidden in this engine. The owner coordinates backend refresh and fallback.
 */
public final class HistoricalV2Engine implements AutoCloseable {
    public interface QueryMapFactory {
        /** Called on the query caller's thread while the engine lock is held. */
        Map<String,Object> build(Context actualConstructorContext)throws Exception;
    }
    public interface CloseAction {
        /** Optional receiver/helper cleanup supplied by its actual owner. */
        void closeReceiver()throws Exception;
    }
    public static final class Result {
        private final byte[] raw;
        public final String ms;
        public final long initializationSteps,generationSteps,querySequence;
        public final boolean runtimeGatePassed;
        private Result(byte[] raw,long init,long generated,long sequence,boolean gate) {
            this.raw=raw==null?null:raw.clone();ms=raw==null?null:V2MsEnvelope.encode(raw);
            initializationSteps=init;generationSteps=generated;querySequence=sequence;runtimeGatePassed=gate;
        }
        public byte[] rawXss(){return raw==null?null:raw.clone();}
    }
    private final Object facadeLock=new Object();
    private final Object receiverLock;
    private Context context;
    private V2Vm vm;
    private int[] initialTable;
    private final int runtimeEnvironment;
    private final long initializationSteps;
    private long completedQueries,closeSteps;
    private boolean closed,closing,faulted;
    private CloseAction closeAction;
    private HistoricalV2Engine(Context context,Object receiver,V2Vm vm,int[] table,int environment,long initialized,CloseAction cleanup) {
        this.context=context;receiverLock=receiver;this.vm=vm;initialTable=table.clone();runtimeEnvironment=environment;initializationSteps=initialized;closeAction=cleanup;
    }
    /** Open is a module-creation/refresh operation; call query repeatedly on its result.
     * No automatic fallback to this historical program is permitted by identity checks.
     */
    public static HistoricalV2Engine open(byte[] genuinePcbc,byte[] genuineTable,Context context,
            Bundle actualConstructorBundle,Object actualIndependentReceiver,byte[] genuineEntropy32,
            int runtimeEnvironment,CloseAction receiverCleanup)throws Exception {
        if(genuinePcbc==null||genuineTable==null||context==null||actualConstructorBundle==null||actualIndependentReceiver==null||genuineEntropy32==null)
            throw new NullPointerException("Actual source code, constructor bindings and entropy are required");
        byte[] program=genuinePcbc.clone(),tableBytes=genuineTable.clone(),entropy=genuineEntropy32.clone();
        if(program.length!=HistoricalV2Generator.PROGRAM_BYTES||!HistoricalV2Generator.PROGRAM_SHA256.equals(sha256(program)))
            throw new IllegalArgumentException("Historical pcbc identity mismatch");
        if(tableBytes.length!=1024||!HistoricalV2Generator.TABLE_SHA256.equals(sha256(tableBytes)))
            throw new IllegalArgumentException("Historical opcode table identity mismatch");
        if(entropy.length!=32)throw new IllegalArgumentException("32 genuine entropy bytes required");
        int[] table=new int[256];for(int i=0;i<256;i++)for(int j=0;j<4;j++)table[i]|=(tableBytes[4*i+j]&255)<<(8*j);
        Map<Integer,Value> slots=new HashMap<Integer,Value>();
        slots.put(1,Value.object(actualConstructorBundle));slots.put(3,Value.object(context));
        slots.put(7,Value.scalar(4,0x800000));slots.put(10,Value.scalar(0,0x748));
        for(int index:new int[]{14,19,21,23,34,38,39})slots.put(index,Value.object(null));
        byte[] prefix=new byte[8];System.arraycopy(entropy,28,prefix,0,4);System.arraycopy(entropy,24,prefix,4,4);
        slots.put(16,Value.bytes(prefix));slots.put(24,Value.bytes(program));
        slots.put(28,Value.bytes(ModifiedUtf8.encode("msa-r")));slots.put(42,Value.object(actualIndependentReceiver));
        V2Vm vm=new V2Vm(program,table,slots,0x700000,new ReflectionSources(context.getClass().getClassLoader()),runtimeEnvironment);
        synchronized(actualIndependentReceiver){executeToEnd(vm);}
        return new HistoricalV2Engine(context,actualIndependentReceiver,vm,table,runtimeEnvironment,vm.steps,receiverCleanup);
    }
    /** This overload preserves the SDK order: lock, snapshot map sources, run xss. */
    public Result query(QueryMapFactory actualSources)throws Exception {
        synchronized(facadeLock) {
            Context actualContext;synchronized(receiverLock){ensureUsable();actualContext=context;}
            if(actualSources==null)throw new NullPointerException("query sources");
            Map<String,Object> map=actualSources.build(actualContext);
            return query(map);
        }
    }
    /** Snapshot overload for callers that already collected real input atoms.
     * The map remains a real Java object; it is not replaced with a replay result.
     */
    public Result query(Map<String,Object> independentlyBuiltQueryMap)throws Exception {
      synchronized(receiverLock){
        ensureUsable();if(independentlyBuiltQueryMap==null)throw new NullPointerException("query map");
        if(runtimeEnvironment!=2)return new Result(null,initializationSteps,0,++completedQueries,false);
        long before=vm.steps;
        try {
            Value entry=vm.get(36);if(entry.kind!=0)throw new IllegalStateException("Generation entry kind");
            if(entry.scalar!=0) {
                if(!vm.enterXss(independentlyBuiltQueryMap,initialTable,new String[0]))throw new IllegalStateException("Runtime gate disagreement");
                executeToEnd(vm);
            }
            Value raw=vm.get(11);if(raw.kind!=1)throw new IllegalStateException("Generation output kind");
            return new Result(raw.bytes,initializationSteps,vm.steps-before,++completedQueries,true);
        } catch(Exception error) {faulted=true;throw error;}
          catch(Error error) {faulted=true;throw error;}
      }
    }
    private void ensureUsable() {
        if(closed)throw new IllegalStateException("Engine is closed");
        if(faulted)throw new IllegalStateException("A source/VM failure left unported native recovery state; explicitly close and refresh the backend");
    }
    public boolean isClosed(){synchronized(receiverLock){return closed;}}
    public boolean isFaulted(){synchronized(receiverLock){return faulted;}}
    public long initializationStepCount(){return initializationSteps;}
    public long completedQueryCount(){synchronized(receiverLock){return completedQueries;}}
    public long closeStepCount(){synchronized(receiverLock){return closeSteps;}}
    /** Façade close order matches zzou.close -> synchronized receiver.close.
     * Receiver-native callbacks use closeNativeState(), which never takes façadeLock.
     */
    public void close()throws Exception {
        synchronized(facadeLock){synchronized(receiverLock){closeInternal(true);}}
    }
    /** Binding point for an independently implemented receiver's native-close callback.
     * It runs the original mode1 VM entry, and leaves Java helper cleanup to that
     * receiver's own close() body. It never acquires façadeLock in reverse order.
     */
    public void closeNativeState()throws Exception {
        synchronized(receiverLock){closeInternal(false);}
    }
    private void closeInternal(boolean cleanOwnedReceiver)throws Exception {
        if(closed||closing)return;closing=true;CloseAction cleanup=cleanOwnedReceiver?closeAction:null;
        try {
            if(vm!=null&&!faulted) {
                long before=vm.steps;
                if(vm.enterClose(initialTable))executeToEnd(vm);
                closeSteps=vm.steps-before;
            }
        } finally {
            closed=true;closing=false;vm=null;initialTable=null;context=null;closeAction=null;
            if(cleanup!=null)cleanup.closeReceiver();
        }
    }
    private static void executeToEnd(V2Vm vm)throws Exception {
        int count=0;while(vm.position()<vm.reader.length()) {
            if(++count>50000)throw new IllegalStateException("Pinned phase exceeded explicit execution bound");
            // Original synchronized xss has no Java interrupt/deadline check here.
            // Do not consume the caller's interrupt flag or invent an abort token.
            vm.step();
        }
    }
    private static String sha256(byte[] value)throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(value);StringBuilder out=new StringBuilder(64);
        for(byte b:digest){out.append(Character.forDigit((b>>>4)&15,16));out.append(Character.forDigit(b&15,16));}return out.toString();
    }
}
