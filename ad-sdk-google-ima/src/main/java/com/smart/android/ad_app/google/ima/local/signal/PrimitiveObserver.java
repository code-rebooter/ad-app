package com.smart.android.ad_app.google.ima.local.signal;

import com.smart.android.ad_app.google.ima.local.Mode1PayloadBuilder.Inputs;
import com.smart.android.ad_app.google.ima.local.Mode1PayloadBuilder.Task;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import com.smart.android.ad_app.google.ima.local.portable.Base64Compat;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Optional read-only actual source observer. It has no generated-output input. */
public interface PrimitiveObserver {
    void onEvent(Event event);
    PrimitiveObserver NONE=new PrimitiveObserver(){public void onEvent(Event ignored){}};
    final class Event {
        public final String kind,task;
        public final long requestSequence,observedNanoTime;
        public final Map<String,Object> data;
        private Event(String kind,String task,long request,Map<String,Object> data) {
            this.kind=kind;this.task=task;requestSequence=request;observedNanoTime=System.nanoTime();
            @SuppressWarnings("unchecked") Map<String,Object> frozen=(Map<String,Object>)freeze(data);
            this.data=frozen;
        }
    }
    /** Scope tags source callbacks on a task thread without changing source values. */
    final class Scope {
        private static final ThreadLocal<Long> REQUEST=new ThreadLocal<Long>();
        private Scope(){}
        public static void enter(long request){REQUEST.set(request);}
        public static void leave(){REQUEST.remove();}
        public static long request(){Long id=REQUEST.get();return id==null?0:id;}
    }
    static Map<String,Object> fields(Object... pairs) {
        if((pairs.length&1)!=0)throw new IllegalArgumentException("key/value pairs required");
        Map<String,Object> out=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;
    }
    static void emit(PrimitiveObserver observer,String kind,Task task,long request,Map<String,Object> data) {
        if(observer==null||observer==NONE)return;
        // Snapshot before invoking the sink. A sink receives no mutable source DTO.
        Event event=new Event(kind,task==null?null:task.name(),request,data);
        try{observer.onEvent(event);}catch(RuntimeException observerFailure){/* observer must not steer collection or output */}
    }
    static void emit(PrimitiveObserver observer,String kind,Task task,Map<String,Object> data){emit(observer,kind,task,Scope.request(),data);}
    /** Only fields consumed by this task; unrelated Inputs default controls excluded. */
    static Map<String,Object> inputs(Task task,Inputs in) {
        String names;
        switch(task) {
            case APP_INFO:names="packageName appInfo appDigest";break;
            case WALL_CLOCK:names="wallTimeMillis baselineSeconds";break;
            case OS_RELEASE:names="osRelease";break;
            case DISPLAY:names="widthPixels heightPixels";break;
            case TIME_ZONE:names="zoneOffsetMillis dstOffsetMillis";break;
            case SIGNATURE:names="signingCertificateBytes signatureSha1Hex";break;
            case BATTERY:names="battery batteryResult";break;
            case ORIENTATION:names="orientation";break;
            case PROCESS:names="packageName ownPid processRandomDraw processes processMatchByPid";break;
            case PROCESSORS:names="availableProcessors";break;
            case BUILD_CHECK:names="superuserApkExists buildModel buildHardware";break;
            case USB:names="usbConnected";break;
            case ADB:names="adbEnabled";break;
            case CLOCKS:names="elapsedRealtimeMillis uptimeMillis";break;
            case SCREEN_FLAGS:names="screenOn keyguardLocked screenRuntimeFlag";break;
            case NETWORK:names="networkProviderPresent networkCapabilitiesPresent networkVpn networkWifi networkCellular networkTiming0 networkTiming1";break;
            case PROXY:names="httpProxyHost";break;
            case LAUNCHER:names="launcherAvailable";break;
            case MODULE_VERSION:return fields("sourceKind","pinned embedded-module constant","moduleVersion","1.460000000");
            default:throw new IllegalArgumentException(task.name());
        }
        Map<String,Object> out=new LinkedHashMap<String,Object>();
        for(String name:names.split(" "))try{out.put(name,Inputs.class.getField(name).get(in));}catch(ReflectiveOperationException impossible){throw new IllegalStateException(impossible);}
        @SuppressWarnings("unchecked") Map<String,Object> frozen=(Map<String,Object>)freeze(out);return frozen;
    }
    static Object freeze(Object value) {
        if(value==null||value instanceof String||value instanceof Number||value instanceof Boolean)return value;
        if(value instanceof Enum)return ((Enum<?>)value).name();
        if(value instanceof byte[])return Collections.unmodifiableMap(fields("type","byte[]","base64",Base64Compat.getEncoder().encodeToString((byte[])value)));
        if(value instanceof int[]){List<Object> out=new ArrayList<Object>();for(int v:(int[])value)out.add(v);return Collections.unmodifiableList(out);}
        if(value instanceof long[]){List<Object> out=new ArrayList<Object>();for(long v:(long[])value)out.add(v);return Collections.unmodifiableList(out);}
        if(value instanceof Map){Map<String,Object> out=new LinkedHashMap<String,Object>();for(Map.Entry<?,?> e:((Map<?,?>)value).entrySet())out.put(String.valueOf(e.getKey()),freeze(e.getValue()));return Collections.unmodifiableMap(out);}
        if(value instanceof Iterable){List<Object> out=new ArrayList<Object>();for(Object item:(Iterable<?>)value)out.add(freeze(item));return Collections.unmodifiableList(out);}
        Map<String,Object> out=new TreeMap<String,Object>();
        for(Field f:value.getClass().getFields())if(!Modifier.isStatic(f.getModifiers()))try{out.put(f.getName(),freeze(f.get(value)));}catch(IllegalAccessException impossible){throw new IllegalArgumentException("unsupported source snapshot class",impossible);}
        return Collections.unmodifiableMap(out);
    }
}
