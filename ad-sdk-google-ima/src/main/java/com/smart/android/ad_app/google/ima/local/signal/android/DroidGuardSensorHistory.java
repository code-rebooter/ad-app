package com.smart.android.ad_app.google.ima.local.signal.android;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.ConcurrentMap;

/** Native-free sensor collection using genuine platform callbacks. */
final class DroidGuardSensorHistory implements SensorEventListener {
    private final SensorManager manager;private final Sensor sensor;private final Queue<Object> queue;
    private final ConcurrentMap<String,Object> data;private final Handler handler=new Handler(Looper.getMainLooper());
    private final int sampling,queueLimit;private final long lifetime;private final boolean immediate,collectorImmediate,rotation,queueEvents;
    private final float alpha;private final double rounding;
    private int state=1,errorCount,mismatchCount;private Throwable failure;private Runnable stop;
    private float[] previous,rotationBaseline;private Stats[] stats;private final Object collectorLock=new Object();
    @SuppressWarnings("unchecked") DroidGuardSensorHistory(SensorManager manager,Sensor sensor,Queue queue,ConcurrentMap data,boolean rotation){
        this.manager=manager;this.sensor=sensor;this.queue=queue;this.data=data;this.rotation=rotation;
        // Java evaluates new collector(...) before the listener constructor.
        float candidate=(Float)get("sla",1f);alpha=candidate>0&&candidate<=1?candidate:1f;
        collectorImmediate=(Boolean)get("slum",false);queueEvents=(Integer)get("slq",0)!=0;rounding=(Double)get("slr",0d);
        sampling=(Integer)get("sls",0);lifetime=(Long)get("sll",0L);queueLimit=(Integer)get("slq",0);immediate=(Boolean)get("slum",false);resetStats();
    }
    private static boolean finite(float value){return !Float.isInfinite(value)&&!Float.isNaN(value);}
    private Object get(String key,Object fallback){return data.containsKey(key)?data.get(key):fallback;}
    private void resetStats(){stats=new Stats[rotation?3:4];for(int i=0;i<stats.length;i++)stats[i]=new Stats();}
    private synchronized void reset(){synchronized(collectorLock){previous=null;rotationBaseline=null;data.clear();queue.clear();resetStats();}errorCount=0;mismatchCount=0;failure=null;}
    synchronized boolean restart(){reset();if(state==1)state=manager.registerListener(this,sensor,sampling)?2:1;
        if(stop==null)stop=()->close();handler.removeCallbacks(stop);handler.postDelayed(stop,lifetime);return state!=1;}
    synchronized void close(){manager.unregisterListener(this);reset();state=1;}
    synchronized void flush(){if(immediate)return;synchronized(collectorLock){publishStats();}publishErrors();}
    private synchronized void publishErrors(){if(errorCount>0)data.put("ecc",errorCount);if(mismatchCount>0)data.put("mtc",mismatchCount);if(failure!=null)data.put("eci",failure);if(errorCount>0||mismatchCount>0||failure!=null)data.put("st",sensor.getType());}
    private void publishStats(){String[] prefixes=rotation?new String[]{"ovz","ovx","ovy"}:new String[]{"lam","lax","lay","laz"};for(int i=0;i<stats.length;i++)stats[i].publish(data,prefixes[i]);}
    @Override public void onSensorChanged(SensorEvent event){boolean failed=true;
        try{if(event.sensor.getType()==sensor.getType()){
            int size=queue.size();if(size==queueLimit&&queueLimit>0)queue.remove();
            synchronized(collectorLock){
            float[] values=event.values.clone();
            if(previous!=null){float[] smooth=new float[values.length];for(int i=0;i<values.length;i++)smooth[i]=values[i]*alpha+(1-alpha)*previous[i];values=smooth;}
            previous=values;values=values.clone();
            if(rotation){float[] matrix=new float[16];SensorManager.getRotationMatrixFromVector(matrix,values);values=new float[3];SensorManager.getOrientation(matrix,values);for(int i=0;i<3;i++)values[i]=(float)Math.toDegrees(values[i]);}
            for(int i=0;i<values.length;i++)if(!finite(values[i]))throw new IllegalArgumentException(String.format("%d: values[%d] is unusable = %f",event.timestamp,i,values[i]));
            if(rounding!=0){float[] rounded=new float[values.length];for(int i=0;i<values.length;i++)rounded[i]=finite(values[i])?(float)(Math.round(values[i]/rounding)*rounding):values[i];values=rounded;}
            record(event.timestamp,values);
            if(queueEvents){ArrayList<Object> item=new ArrayList<>();item.add(rotation?193:65);item.add(values);item.add(event.timestamp);item.add(event.accuracy);queue.add(item);}
            if(collectorImmediate)publishStats();
            }failed=false;
        }else mismatchCount++;}catch(Throwable actualFailure){errorCount++;failure=actualFailure;}
        if(immediate&&failed)publishErrors();
    }
    private void record(long timestamp,float[] values){if(rotation){if(rotationBaseline==null){rotationBaseline=values.clone();return;}float[] base=rotationBaseline.clone();for(int i=0;i<3;i++)stats[i].add(timestamp,values[i]-base[i]);}
        else{float sum=0;for(float value:values)sum+=value*value;stats[0].add(timestamp,(float)Math.sqrt(sum));for(int i=0;i<3;i++)stats[i+1].add(timestamp,values[i]);}}
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}
    private static final class Stats {
        int count;float mean,sumSquares,min=1e7f,max=-1e7f;long minTime,maxTime;
        void add(long time,float value){count++;float delta=value-mean;mean+=delta/count;sumSquares+=delta*(value-mean);if(min>value){min=value;minTime=time;}if(max<value){max=value;maxTime=time;}}
        void publish(ConcurrentMap<String,Object> data,String prefix){if(count==0)return;data.put(prefix+"c",count);data.put(prefix+"n",min);data.put(prefix+"x",max);data.put(prefix+"e",mean);data.put(prefix+"v",count>1?(float)Math.sqrt(sumSquares/(count-1)):0f);data.put(prefix+"tn",minTime);data.put(prefix+"tx",maxTime);}
    }
}
