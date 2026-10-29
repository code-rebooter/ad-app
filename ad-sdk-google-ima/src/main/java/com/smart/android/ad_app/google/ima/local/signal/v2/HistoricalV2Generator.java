package com.smart.android.ad_app.google.ima.local.signal.v2;

import android.content.Context;
import android.os.Bundle;
import com.smart.android.ad_app.google.ima.local.signal.vm.ModifiedUtf8;
import com.smart.android.ad_app.google.ima.local.signal.vm.ReflectionSources;
import com.smart.android.ad_app.google.ima.local.signal.vm.V2Vm;
import com.smart.android.ad_app.google.ima.local.signal.vm.Value;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/** Small source-only entry for the explicitly identified historical ARM64 V2 program.
 * No IMA, original DroidGuard Java/native module, RPC, trace capture or SDK result
 * is used to compute output. This does not select or replace a live SDK module.
 * The caller supplies its real constructor bindings and independently built map.
 */
public final class HistoricalV2Generator {
    public static final String PROGRAM_SHA256="77963067c455702b15337d4ea7ab2ead4a44945556f952900af274e335ecbc39";
    public static final String TABLE_SHA256="e0d820fd3cbdf0664a20ca24e2644d26750195e36ba0461788549c477046bc15";
    public static final int PROGRAM_BYTES=18372;
    private static final int MAX_PHASE_STEPS=50000;
    private HistoricalV2Generator() {}

    public static final class Result {
        private final byte[] raw;
        public final String ms;
        public final long initializationSteps,generationSteps;
        public final boolean runtimeGatePassed;
        private Result(byte[] raw,long initial,long generated,boolean gate) {
            this.raw=raw==null?null:raw.clone();ms=raw==null?null:V2MsEnvelope.encode(raw);
            initializationSteps=initial;generationSteps=generated;runtimeGatePassed=gate;
        }
        public byte[] rawXss(){return raw==null?null:raw.clone();}
    }

    /** Entropy is a real upstream primitive, never a captured signal/URL field.
     * The historical selected branch overwrites its constructor nonce in pcbc.
     * SecureRandom is not claimed to be the native arc4random implementation.
     */
    public static Result generateWithFreshEntropy(byte[] genuinePcbc,byte[] genuineTable,
            Context context,Bundle constructorBundle,Object independentReceiver,
            Map<String,Object> independentlyBuiltQueryMap,int runtimeEnvironment)throws Exception {
        byte[] entropy=new SecureRandom().generateSeed(32);
        return generate(genuinePcbc,genuineTable,context,constructorBundle,independentReceiver,
                independentlyBuiltQueryMap,entropy,runtimeEnvironment);
    }

    public static Result generate(byte[] genuinePcbc,byte[] genuineTable,Context context,
            Bundle constructorBundle,Object independentReceiver,Map<String,Object> independentlyBuiltQueryMap,
            byte[] genuineEntropy32,int runtimeEnvironment)throws Exception {
        if(genuinePcbc==null||genuineTable==null||context==null||constructorBundle==null||independentReceiver==null||independentlyBuiltQueryMap==null||genuineEntropy32==null)
            throw new NullPointerException("Actual source program, constructor bindings, query map and entropy are required");
        byte[] program=genuinePcbc.clone(),tableBytes=genuineTable.clone(),entropy=genuineEntropy32.clone();
        if(program.length!=PROGRAM_BYTES||!PROGRAM_SHA256.equals(sha256(program)))
            throw new IllegalArgumentException("Historical pcbc identity mismatch; active modules are never silently replaced");
        if(tableBytes.length!=1024||!TABLE_SHA256.equals(sha256(tableBytes)))
            throw new IllegalArgumentException("Historical opcode table identity mismatch");
        if(entropy.length!=32)throw new IllegalArgumentException("Exactly 32 genuine entropy bytes are required");
        int[] table=new int[256];for(int i=0;i<table.length;i++)for(int j=0;j<4;j++)table[i]|=(tableBytes[4*i+j]&255)<<(8*j);
        Map<Integer,Value> slots=new HashMap<Integer,Value>();
        slots.put(1,Value.object(constructorBundle));slots.put(3,Value.object(context));
        // These are symbolic local addresses for pointer-shaped ISA values,
        // not process addresses and not native-library entry points to execute.
        slots.put(7,Value.scalar(4,0x800000));slots.put(10,Value.scalar(0,0x748));
        for(int i:new int[]{14,19,21,23,34,38,39})slots.put(i,Value.object(null));
        byte[] prefix=new byte[8];System.arraycopy(entropy,28,prefix,0,4);System.arraycopy(entropy,24,prefix,4,4);
        slots.put(16,Value.bytes(prefix));slots.put(24,Value.bytes(program));
        slots.put(28,Value.bytes(ModifiedUtf8.encode("msa-r")));slots.put(42,Value.object(independentReceiver));
        V2Vm vm=new V2Vm(program,table,slots,0x700000,new ReflectionSources(context.getClass().getClassLoader()),runtimeEnvironment);
        executeToEnd(vm);long initialized=vm.steps;
        // xssNative145e4 returns null unless its constructor environment is2.
        if(runtimeEnvironment!=2)return new Result(null,initialized,0,false);
        Value entry=vm.get(36);if(entry.kind!=0)throw new IllegalStateException("Invalid generation entry type");
        long before=vm.steps;
        if(entry.scalar!=0){
            if(!vm.enterXss(independentlyBuiltQueryMap,table,new String[0]))throw new IllegalStateException("xss runtime gate disagrees with constructor input");
            executeToEnd(vm);
        }
        // Native4a798 exports slot11 bytes after normal return, including its
        // entry-zero case. Empty bytes retain their actual value, with no fallback.
        Value output=vm.get(11);if(output.kind!=1)throw new IllegalStateException("xss output slot11 is not a byte string");
        return new Result(output.bytes,initialized,vm.steps-before,true);
    }
    private static void executeToEnd(V2Vm vm)throws Exception {
        int steps=0;while(vm.position()<vm.reader.length()){
            if(++steps>MAX_PHASE_STEPS)throw new IllegalStateException("Pinned historical phase exceeded the explicit execution bound");
            vm.step();
        }
    }
    private static String sha256(byte[] value)throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(value);StringBuilder out=new StringBuilder(64);
        for(byte b:digest){out.append(Character.forDigit((b>>>4)&15,16));out.append(Character.forDigit(b&15,16));}return out.toString();
    }
}
