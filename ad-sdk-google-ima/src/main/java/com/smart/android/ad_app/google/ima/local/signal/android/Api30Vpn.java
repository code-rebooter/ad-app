package com.smart.android.ad_app.google.ima.local.signal.android;
import android.app.AppOpsManager;import java.util.concurrent.Executor;
/** Keeps newer listener classes outside the API23 source owner's fields and startup. */
final class Api30Vpn {
 interface Events {void onActive(boolean active);}
 static Object start(AppOpsManager manager,Executor executor,final Events events){AppOpsManager.OnOpActiveChangedListener listener=new AppOpsManager.OnOpActiveChangedListener(){@Override public void onOpActiveChanged(String op,int uid,String pkg,boolean active){events.onActive(active);}};manager.startWatchingActive(new String[]{"android:establish_vpn_service","android:establish_vpn_manager"},executor,listener);return listener;}
 static void stop(AppOpsManager manager,Object listener){manager.stopWatchingActive((AppOpsManager.OnOpActiveChangedListener)listener);}
}
