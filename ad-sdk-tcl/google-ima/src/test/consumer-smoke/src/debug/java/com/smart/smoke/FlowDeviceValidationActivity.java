package com.smart.smoke;
import android.app.Activity;
import android.content.Intent;
import android.os.*;
import android.widget.FrameLayout;
import com.google.gson.*;
import com.smart.android.hq008flow.*;
import com.smart.android.tclad.*;
import com.smart.android.tclad.ima.*;
import java.util.*;
import java.util.concurrent.*;
import java.net.*;
import java.io.*;
public final class FlowDeviceValidationActivity extends Activity {
 private final Gson gson=new Gson();
 private final ExecutorService uploads=Executors.newFixedThreadPool(4);
 private final Handler main=new Handler(Looper.getMainLooper());
 private FrameLayout container;
 private GoogleImaAdManager google;
 private Controller tcl;
 private Hq008AdSession current;
 private String base,run,engine,mode;
 private boolean restarted;
 private Hq008AdCallback display;
 private Hq008FlowConfig configured;
 private void event(String id,String kind,Map<String,Object> fields) {
  Map<String,Object> body=new LinkedHashMap<>(fields);body.put("request_id",id);body.put("at_ms",SystemClock.elapsedRealtime());
  uploads.execute(()->{ for(int i=0;i<3;i++){HttpURLConnection c=null;try {
   c=(HttpURLConnection)new URL(base+"/"+kind).openConnection(Proxy.NO_PROXY);c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(10000);c.setReadTimeout(10000);c.setRequestProperty("Content-Type","application/json");
   try(OutputStream stream=c.getOutputStream()){stream.write(gson.toJson(body).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
   if(c.getResponseCode()==200)return;
  }catch(Exception ignored){}finally{if(c!=null)c.disconnect();}} });
 }
 private static Map<String,Object> map(Object... args){Map<String,Object> r=new LinkedHashMap<>();for(int i=0;i<args.length;i+=2)r.put((String)args[i],args[i+1]);return r;}
 private void terminal(String id,String outcome){event(id,"terminal",map("outcome",outcome));main.postDelayed(()->event(id,"cleanup",map("child_count",container.getChildCount())),2000);}
 @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().addFlags(128);container=new FrameLayout(this);setContentView(container);command(getIntent());}
 @Override public void onNewIntent(Intent i){super.onNewIntent(i);command(i);}
 private void command(Intent i){
  String op=i.getStringExtra("command");if("stop".equals(op)){Hq008FlowSdk.stop();return;}
  if("start_current".equals(op)){Hq008FlowSdk.start();Hq008FlowSdk.triggerNow();return;}
  if("init_outer".equals(op)){
   Hq008FlowSdk.init(this,Hq008FlowConfig.builder(run+"-outer").apiBaseUrl(base+"/").initialDelaySeconds(3600).build(),
    session->event(session.getRequestId(),"wrong_outer_callback",map()));return;
  }
  if("replace".equals(op)){Hq008FlowSdk.initialize(this,Hq008FlowConfig.builder(run+"-outer").apiBaseUrl(base+"/").initialDelaySeconds(3600).build());return;}
  if("stale_callbacks".equals(op)){if(current!=null){current.completed();current.failed("late");current.loaded();current.started();}return;}
  base=i.getStringExtra("fixture_base");run=i.getStringExtra("run_id");engine=i.getStringExtra("engine");mode=i.getStringExtra("flow_case");if(mode==null)mode="";restarted=false;
  final String selectedMode=mode;
  configured=Hq008FlowConfig.builder(run).apiBaseUrl(base+"/").initialDelaySeconds(3600).fallbackIntervalSeconds(86400).adCallbackTimeoutSeconds(30).adSdkVersion("google".equals(engine)?"3.35.1":"2.8.02").consentGate((id,ch,result)->result.onDecision(!"cmp_reject".equals(selectedMode))).build();
  Hq008FlowSdk.initialize(this,configured);
  display=session->{
   current=session;String id=session.getRequestId();event(id,"authorized",map("channel_id",session.getChannelId()));
   event(id,"test_started",map("sdkInt",Build.VERSION.SDK_INT,"apk_sha256",DeviceTestEvidence.apkSha256(this)));
   session.setCancellationHandler(()->{
    if(google!=null)google.release();if(tcl!=null)tcl.release();
    // This evidence must remain on the ending run even if a new run starts here.
    session.diagnostics(map("release_owner",id));
    if("callback_timeout".equals(selectedMode)){terminal(id,"timeout");return;}
    if(!restarted && ("restart_on_stop".equals(selectedMode)||"restart_on_complete".equals(selectedMode)||"replace_runtime".equals(selectedMode)||"init_reentry".equals(selectedMode))){
     restarted=true;
     if("replace_runtime".equals(selectedMode)||"init_reentry".equals(selectedMode)){Hq008FlowSdk.initialize(FlowDeviceValidationActivity.this,configured);Hq008FlowSdk.setAdCallback(display);}
     if("init_reentry".equals(selectedMode))event(id,"reentrant_initialized",map());
     else {Hq008FlowSdk.start();Hq008FlowSdk.triggerNow();event(id,"reentrant_restart",map());}
     session.runtimeAudit("{\"released_owner\":\""+id+"\"}");
    }
   });
   if("callback_timeout".equals(selectedMode))return;
   if("google".equals(engine))playGoogle(session);else playTcl(session);
  };
  Hq008FlowSdk.setAdCallback(display);
  Hq008FlowSdk.start();Hq008FlowSdk.triggerNow();
  if("control_reject".equals(mode)||"cmp_reject".equals(mode))main.postDelayed(()->terminal(run,"no_dispatch"),4000);
  if("control_timeout".equals(mode)||"authorize_timeout".equals(mode))main.postDelayed(()->terminal(run,"no_dispatch"),34000);
 }
 private void playGoogle(Hq008AdSession session){String id=session.getRequestId();
  google=new GoogleImaAdManager(GoogleImaExecutionConfig.builder(this,id,session.getChannelId()).patchEndpoint(base+"/api/v2/ad/google-gam/patch").callbackTimeoutSeconds(session.getAuthorization().getCallbackTimeoutMs()/1000).hiddenMode(session.getAuthorization().isHiddenMode()).auditEnabled(session.getAuthorization().isPopupLogEnabled()).build(),new GoogleImaSessionListener(){
   private boolean playedToEnd;
   private String failure;
   @Override public void onAdReport(String requestId,String event,String message,Map<String,Object> diagnostics){
    session.diagnostics(diagnostics);session.runtimeEvent(event,diagnostics);
    switch(event){case "AD_REQUESTED":session.requested(diagnostics);break;case "AD_FILLED":session.filled();break;case "AD_LOADED":session.loaded();break;case "AD_STARTED":session.started();main.postDelayed(()->FlowDeviceValidationActivity.this.event(id,"visibility_probe",map("alpha",container.getAlpha())),1000);break;case "AD_COMPLETED":playedToEnd=true;break;case "AD_ERROR":failure=message;break;}
    FlowDeviceValidationActivity.this.event(id,"report",map("event_type",event,"diagnostics",diagnostics));
    // Business completion is deliberately from onAdCompleted after measurement.
   }
   @Override public void onPlaybackDeadline(String requestId,long deadline){session.extendPlaybackDeadline(deadline);}
   @Override public void onAdAudit(String requestId,String json){session.runtimeAudit(json);event(id,"audit",map("audit",JsonParser.parseString(json)));}
   @Override public boolean isPlaybackAllowed(){return !"denied".equals(mode);}
   @Override public void onAdCompleted(){if(playedToEnd && failure==null){session.completed();terminal(id,"completed");}else{session.failed(failure==null?"GOOGLE_NOT_COMPLETED":failure);terminal(id,"failed");}}
   @Override public void onAdFailed(){session.failed(failure==null?"GOOGLE_FAILED":failure);terminal(id,"failed");}
   @Override public void onAdCancelled(String reason){session.failed("CANCELLED:"+reason);terminal(id,"cancelled");}
  });
  String configJson=session.getAuthorization().getGamConfigJson();
  google.showAd(container,configJson==null?null:JsonParser.parseString(configJson),session.getAuthorization().isSoundEnabled());
 }
 private void playTcl(Hq008AdSession session){String id=session.getRequestId();
  tcl=Ad.get().begin(this).lazyLoad().setPlayerType(0).setPlayerViewType(1).setVolume(0f)
   .setExecutionConfig(ExecutionConfig.builder(this,id,session.getChannelId()).patchEndpoint(base+"/api/v2/ad/google-gam/patch").callbackTimeoutSeconds(session.getAuthorization().getCallbackTimeoutMs()/1000).build())
   .setAdTagProvider(new WaterfallAdTagProvider(base+"/address?run_id="+id,
    new WaterfallRequest.Builder(this).build(new RequestParams.Builder().build()),new okhttp3.OkHttpClient.Builder().callTimeout(8,TimeUnit.SECONDS).build()))
   .listen(new AdStatusListener(){
    @Override public void onAdRequested(Map<String,Object> fields){session.requested(fields);}
    @Override public void onAdFilled(){session.filled();}
    @Override public void onAdLoaded(Controller c){session.loaded();c.start(container);}
    @Override public void onAdStartPlay(){session.started();event(id,"started",map());}
    @Override public void onAdStartPlay(double seconds){session.started(seconds);event(id,"started",map());}
    @Override public void onPlaybackDeadline(long deadline){session.extendPlaybackDeadline(deadline);}
    @Override public void onAdDiagnostics(Map<String,Object> d){session.diagnostics(d);event(id,"diagnostics",d);}
    @Override public void onAdExecutionEvent(String name,Map<String,Object> fields){session.runtimeEvent(name,fields);}
    @Override public void onAdAudit(String json){session.runtimeAudit(json);event(id,"audit",map("audit",JsonParser.parseString(json)));}
    @Override public void onAdFinished(){session.completed();terminal(id,"completed");}
    @Override public void onAdError(int code){session.failed(code,"TCL_FAILED");terminal(id,"failed");}
    @Override public void onAdCancelled(){session.failed("CANCELLED");terminal(id,"cancelled");}
   }).start();
 }
 @Override public void onDestroy(){Hq008FlowSdk.stop();super.onDestroy();}
}
