package com.smart.android.ad_app
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import com.google.gson.JsonParser
class AppRuntimeValidationActivity:Activity() {
 private lateinit var host:FrameLayout
 private lateinit var evidence:BackportEvidence
 private var active=""
 private var request:Intent?=null
 private var pendingTrace:String?=null
 override fun onCreate(state:Bundle?) {
  super.onCreate(state);window.addFlags(128);host=FrameLayout(this);setContentView(host)
  evidence=BackportEvidence(this)
  evidence.installObservers { id,type ->
   if(id==pendingTrace && type=="AD_REQUESTED") {
    pendingTrace=null;evidence.begin(id)
   }
   val i=request
   if(id==active && type==i?.getStringExtra("cancel_on_report")) {
    GoogleImaAdManager.destroyAd(id)
    evidence.event(id,"callback_cancel_requested")
    evidence.terminal(id,"cancelled")
   }
   if(id==active && type=="AD_COMPLETED" && i!=null) {
    if(i.getBooleanExtra("release_on_completion",false)) GoogleImaAdManager.destroyAd(id)
    if(i.getBooleanExtra("replace_on_completion",false)) {
     val next=Intent(i).putExtra("run_id",id+"-next").putExtra("replace_on_completion",false)
     // Keep the old trace open until the deferred manager actually starts.
     command(next,beginTrace=false)
     if(i.getBooleanExtra("cancel_queued",false)) {
      GoogleImaAdManager.destroyAd(id+"-next")
      evidence.event(id,"queued_cancel_requested",mapOf("cancelled_id" to id+"-next"))
     }
    }
   }
  }
  Hq008ConsentLogReporter.addDebugTraceListener { type,message ->
   evidence.event(active,"trace",mapOf("event_type" to type,"message" to message))
  }
  val h=android.os.Handler(android.os.Looper.getMainLooper())
  h.post(object:Runnable { override fun run() {
   try {
    val current=GoogleImaAdManager.javaClass.getDeclaredField("current").apply{isAccessible=true}.get(GoogleImaAdManager)
    if(current!=null) {
     val diagnostics=current.javaClass.getDeclaredMethod("diagnostics").apply{isAccessible=true}.invoke(current)
     evidence.event(active,"player_snapshot",mapOf("diagnostics" to diagnostics))
    }
   }catch(_:Exception){}
   h.postDelayed(this,3000)
  }})
  host.post{command(intent)}
 }
 override fun onNewIntent(intent:Intent){super.onNewIntent(intent);command(intent)}
 private fun command(i:Intent,beginTrace:Boolean=true) {
  when(i.getStringExtra("command")) {
   "cancel","release_twice"->{GoogleImaAdManager.destroyAd(active);GoogleImaAdManager.destroyAd(active);evidence.terminal(active,"cancelled");return}
   "release_previous"->{GoogleImaAdManager.destroyAd(i.getStringExtra("previous_id"));return}
  }
  val id=i.getStringExtra("run_id")?:error("run_id required")
  active=id;request=Intent(i)
  if(beginTrace)evidence.begin(id) else pendingTrace=id
  val config=JsonParser.parseString(i.getStringExtra("gam_config")?:"{}")
  GoogleImaAdManager.showAd(host,id,i.getBooleanExtra("physical",false),i.getLongExtra("callback_ms",180000),config,
   "BACKPORT_APP",{evidence.event(id,"started")},{evidence.terminal(id,"failed")},{evidence.terminal(id,"completed")})
 }
 override fun onDestroy(){GoogleImaAdManager.destroyAd(active);super.onDestroy()}
}
