package com.smart.android.ad_app.google.ima.local.signal.android;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentMap;

/** Independent Java helper receiver for the pinned six-argument constructor.
 * This class is not the original DroidGuard class and has no native library.
 * Native entry wrappers require the independent VM callback when state!=0;
 * unbound entries stop explicitly instead of producing invented results.
 */
public final class DroidGuardLocalReceiver {
    public interface VmEntries {
        long initialize(DroidGuardLocalReceiver receiver,Context context,String flow,byte[] program,Object argument,Bundle bundle,int fd,int environment);
        void close(long state,String[] values);int status(long state);void event(long state,Map values);
        byte[] snapshot(long state,String[] values,Bundle extra);byte[] extendedSnapshot(long state,String[] values,Map extra);
    }
    private Context context;private String flow;private byte[] program;private Object argument;
    private Bundle bundle;private ParcelFileDescriptor descriptor;private final int environment;
    private long state;private int runStatus;private Object storedBundle,storedData;
    private DroidGuardHelperState.GraphicsResult graphics;
    private DroidGuardSensorHistory acceleration,rotation;
    private VmEntries entries;
    private final DroidGuardHelperState fileHelpers=new DroidGuardHelperState();
    public DroidGuardLocalReceiver(Context context,String flow,byte[] program,Object argument,Bundle bundle,int environment){
        this.context=context;this.flow=flow;this.program=program;this.argument=argument;this.bundle=bundle;
        this.descriptor=bundle==null?null:(ParcelFileDescriptor)bundle.getParcelable("fd");
        this.environment=environment>=0&&environment<=2?environment:0;
    }
    public synchronized void bindIndependentVm(VmEntries entries,long actualStateToken){this.entries=entries;state=actualStateToken;}
    private VmEntries bound(){if(entries==null)throw new UnsupportedOperationException("Independent VM callback is not bound; no native result is fabricated");return entries;}
    public static void entryHelperNative(long state){throw new UnsupportedOperationException("Independent VM entryHelper callback must be wired before this entry is used");}
    public int gr(){return runStatus;}
    public Object rb(){return storedBundle;}public Object rd(){return storedData;}
    public void wb(Object value){storedBundle=value;}public void wd(Object value){storedData=value;}
    public String h(){DroidGuardHelperState.GraphicsResult value=graphics;return value==null?null:value.description;}
    public String m(){DroidGuardHelperState.GraphicsResult value=graphics;return value==null?null:value.renderer;}
    public long i(){DroidGuardHelperState.GraphicsResult value=graphics;return value==null?0:value.first;}
    public long j(){DroidGuardHelperState.GraphicsResult value=graphics;return value==null?0:value.second;}
    public void n(){graphics=null;}
    public String k()throws Exception{return fileHelpers.k();}
    public void l(byte[] input){float[] values=DroidGuardHelperState.graphicsInputs(input);if(values==null){graphics=null;return;}g(values);}
    public void g(float[] values){
        if(values.length!=12){graphics=null;return;}
        try {
            graphics=new DroidGuardHelperState.GraphicsResult(0,0,"GPU not queried.","GPU not queried.");
            synchronized(DroidGuardLocalReceiver.class){try{graphics=DroidGuardGraphics.capture(values);}catch(DroidGuardGraphics.GlFailure genuineGraphicsFailure){/* original typed graphics failure retains initialized result state */}}
        }catch(Exception genuineFailure){graphics=null;}
    }
    public synchronized boolean larl(){return acceleration!=null&&acceleration.restart();}
    @SuppressWarnings("rawtypes") public synchronized boolean lasr(SensorManager manager,Sensor sensor,Queue queue,ConcurrentMap data){acceleration=new DroidGuardSensorHistory(manager,sensor,queue,data,false);return larl();}
    public synchronized boolean rvrl(){return rotation!=null&&rotation.restart();}
    @SuppressWarnings("rawtypes") public synchronized boolean rvsr(SensorManager manager,Sensor sensor,Queue queue,ConcurrentMap data){rotation=new DroidGuardSensorHistory(manager,sensor,queue,data,true);return rvrl();}
    public synchronized void sss(){if(rotation!=null)rotation.flush();if(acceleration!=null)acceleration.flush();}
    /** The exact current SDK six-arg constructor leaves its touch-queue helper null. */
    @SuppressWarnings("rawtypes") public void he2(Map values){throw new NullPointerException("touch queue helper is null in the six-argument constructor");}
    @SuppressWarnings("rawtypes") public void wq(Queue values){throw new NullPointerException("touch queue helper is null in the six-argument constructor");}
    public int lcs(){long value=state;return value==0?0:bound().status(value);}
    @SuppressWarnings("rawtypes") public synchronized void he(Map values){if(state!=0)bound().event(state,values);}
    public synchronized boolean init(){
        if(program==null)return false;
        if(state!=0){bound().close(state,new String[0]);state=0;}
        state=bound().initialize(this,context,flow,program,argument,bundle,descriptor==null?0:descriptor.getFd(),environment);
        if(environment!=2||lcs()==0)return state!=0;close();return false;
    }
    @SuppressWarnings("rawtypes") public synchronized byte[] xss(Map first,Map second){return state==0?null:bound().extendedSnapshot(state,strings(first),second);}
    @SuppressWarnings("rawtypes") public synchronized byte[] ss(Map values){
        if(state==0)return null;Bundle extra=null;
        if(values!=null&&values.containsKey("_seigd")){
            String text=(String)values.remove("_seigd");
            if(text!=null&&!text.isEmpty()){
                Parcel parcel=Parcel.obtain();
                try{try{byte[] bytes=Base64.decode(text,0);parcel.unmarshall(bytes,0,bytes.length);parcel.setDataPosition(0);extra=parcel.readBundle(DroidGuardLocalReceiver.class.getClassLoader());if(extra==null)extra=decodeError("Decoded string cannot be parsed.");}catch(RuntimeException error){extra=decodeError(error.toString());}}finally{parcel.recycle();}
            }
        }
        return bound().snapshot(state,strings(values),extra);
    }
    @SuppressWarnings("rawtypes") private static String[] strings(Map values){if(values==null)return new String[0];String[] result=new String[values.size()*2];int i=0;for(Object item:values.entrySet()){Map.Entry entry=(Map.Entry)item;result[i++]=(String)entry.getKey();result[i++]=(String)entry.getValue();}return result;}
    private static Bundle decodeError(String text){Bundle value=new Bundle();value.putInt("vs",1);if(text!=null)value.putString("e",text);return value;}
    @SuppressWarnings("rawtypes") public byte[] run(Map values){runStatus=29;try{init();return ss(values);}finally{close();runStatus=0;}}
    public synchronized void close(){
        if(state!=0){bound().close(state,new String[0]);state=0;}
        if(rotation!=null){rotation.close();rotation=null;}if(acceleration!=null){acceleration.close();acceleration=null;}
        wb(null);program=null;graphics=null;context=null;flow=null;argument=null;bundle=null;
        if(descriptor!=null){try{descriptor.close();}catch(Throwable ignored){}descriptor=null;}storedData=null;
    }
}
