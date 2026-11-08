package com.smart.android.ad_app
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.gson.Gson
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executors
class BackportEvidence(private val activity: Activity) {
 private val uploads=Executors.newFixedThreadPool(4)
 private val gson=Gson()
 fun event(id:String,kind:String,fields:Map<String,Any?> = emptyMap()) {
  val body=fields+mapOf("request_id" to id,"at_ms" to SystemClock.elapsedRealtime())
  val dir=File(activity.filesDir,"backport/$id").apply{mkdirs()}
  synchronized(this) { File(dir,"events.jsonl").appendText(gson.toJson(mapOf("kind" to kind,"body" to body))+"\n") }
  uploads.execute {
   repeat(3) {
    var c:HttpURLConnection?=null
    try {
     c=URL("https://127.0.0.1:19446/$kind").openConnection(Proxy.NO_PROXY) as HttpURLConnection
     c.requestMethod="POST";c.connectTimeout=10000;c.readTimeout=10000;c.doOutput=true;c.setRequestProperty("Content-Type","application/json")
     c.outputStream.use{it.write(gson.toJson(body).toByteArray())}
     if(c.responseCode==200)return@execute
    } catch(error:Exception){ synchronized(this) { File(dir,"upload-errors.txt").appendText(kind+":"+error.toString()+"\n") } } finally {c?.disconnect()}
   }
  }
 }
 fun terminal(id:String,outcome:String) {
  event(id,"terminal",mapOf("outcome" to outcome))
  Handler(Looper.getMainLooper()).postDelayed({event(id,"cleanup",mapOf("observed" to true))},1800)
 }
 fun installObservers(onReport:(String,String)->Unit = {_,_->}) {
  Hq008AdReporter.debugReportObserver={phase,payload,_ ->
   if(phase=="request") {
    val id=payload["request_id"].toString();val type=payload["event_type"].toString()
    event(id,"report",mapOf("event_type" to type,"payload" to payload));onReport(id,type)
   }
  }
  Hq008ConsentLogReporter.debugUploadObserver={phase,payload,_ ->
   if(phase=="request") {
    val id=payload["request_id"].toString()
    val trace=com.google.gson.JsonParser.parseString(payload["ad_log"].toString()).asJsonObject
    trace.getAsJsonArray("steps")?.forEach { step ->
     val s=step.asJsonObject
     if(s.get("eventType")?.asString in listOf("IMA_RUNTIME_AUDIT","TCL_RUNTIME_AUDIT"))
      event(id,"audit",mapOf("audit" to s.get("adLog")))
    }
    event(id,"app_audit",mapOf("payload" to payload))
   }
  }
 }
 fun begin(id:String) {
  Hq008ConsentLogReporter.report("CMP_GATE_START","backport_validation=true")
  Hq008ConsentLogReporter.bindAuthorizationRequestId(Hq008ConsentLogReporter.activeRequestId(),id)
  Hq008ConsentLogReporter.updatePopupLogEnabled(true,id)
  event(id,"test_started",mapOf("sdkInt" to android.os.Build.VERSION.SDK_INT,"apk_sha256" to
   java.security.MessageDigest.getInstance("SHA-256").digest(File(activity.applicationInfo.sourceDir).readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}))
 }
}
