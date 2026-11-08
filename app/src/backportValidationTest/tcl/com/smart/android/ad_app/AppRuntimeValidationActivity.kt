package com.smart.android.ad_app
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import com.tcl.ff.component.overseahttp.http.HttpRequester
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
class AppRuntimeValidationActivity:Activity() {
 private lateinit var host:FrameLayout
 private lateinit var evidence:BackportEvidence
 private var active=""
 override fun onCreate(state:Bundle?) {
  super.onCreate(state);window.addFlags(128);host=FrameLayout(this);setContentView(host)
  evidence=BackportEvidence(this);evidence.installObservers()
  val config=com.google.gson.Gson().fromJson(assets.open("tcl-original-waterfall-2.8.02.json").bufferedReader().use{it.readText()},com.tcl.ff.component.overseabasebusiness.bean.AdSystemConfig::class.java)
  val fetcher=com.tcl.ff.component.overseabasebusiness.AdConfigFetcher.get()
  fetcher.javaClass.getDeclaredField("e").apply{isAccessible=true}.set(fetcher,config)
  com.tcl.ff.component.vastad.Initialization.init(applicationContext,com.tcl.ff.component.overseabase.base.constant.AdReportSwitchConfig(),null)
  AdManagerImpl.init()
  val requester=HttpRequester.get()
  val f=requester.javaClass.getDeclaredField("c").apply{isAccessible=true}
  val client=f.get(requester) as OkHttpClient
  f.set(requester,client.newBuilder().addInterceptor{chain ->
   val req=chain.request()
   if(req.method=="POST") {
    evidence.event(active,"address_fixture")
    chain.proceed(req.newBuilder().url("https://127.0.0.1:19446/address?run_id=$active").build())
   } else chain.proceed(req)
  }.build())
  host.post{command(intent)}
 }
 override fun onNewIntent(intent:Intent){super.onNewIntent(intent);command(intent)}
 private fun command(i:Intent) {
  when(i.getStringExtra("command")) {
   "cancel","release_twice"->{AdManagerImpl.destroyAd(active);AdManagerImpl.destroyAd(active);evidence.terminal(active,"cancelled");return}
   "release_previous"->{AdManagerImpl.destroyAd(i.getStringExtra("previous_id"));return}
  }
  val id=i.getStringExtra("run_id")?:error("run_id required");active=id;evidence.begin(id)
  AdManagerImpl.showAd(host,id,i.getBooleanExtra("physical",false),i.getLongExtra("callback_ms",180000),null,"BACKPORT_APP",
   {evidence.event(id,"started")},{evidence.terminal(id,"failed")},{evidence.terminal(id,"completed")})
 }
 override fun onDestroy(){AdManagerImpl.destroyAd(active);super.onDestroy()}
}
