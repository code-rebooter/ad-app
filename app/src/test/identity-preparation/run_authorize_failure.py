from pathlib import Path
import os
if os.environ.get("JAVA_HOME"):
 os.environ["PATH"]=str(Path(os.environ["JAVA_HOME"])/"bin")+os.pathsep+os.environ.get("PATH","")
import subprocess,os,sys,hashlib
root=Path(__file__).resolve().parents[4];here=root/'build/identity-preparation-regression';here.mkdir(parents=True,exist_ok=True);r=here/'authorize-failure';r.mkdir(exist_ok=True)
source=root/'app/src/hq008/java/com/smart/android/ad_app/Hq008SdkAuthorizeClient.kt';print('SOURCE',source,hashlib.sha256(source.read_bytes()).hexdigest(),flush=True)
files={
'Context.kt':'''package android.content
open class Context {val applicationContext:Context get()=this;val packageName="test.app";val resources=android.content.res.Resources();val contentResolver=Any();fun getSystemService(s:String)=android.view.WindowManager();companion object{const val WINDOW_SERVICE="window"} }
''',
'Resources.kt':'''package android.content.res
class Resources {val displayMetrics=android.util.DisplayMetrics()}
''',
'Display.kt':'''package android.util
class DisplayMetrics {var widthPixels=1920;var heightPixels=1080}
''',
'Windows.kt':'''package android.view
class WindowManager {val defaultDisplay=Display()}
class Display {fun getRealMetrics(m:android.util.DisplayMetrics){} }
''',
'Settings.kt':'''package android.provider
object Settings {object Secure {const val ANDROID_ID="android_id";fun getString(r:Any,k:String):String?="0011223344556677"} }
''',
'Keep.kt':'''package androidx.annotation
annotation class Keep
''',
'Suppress.kt':'''package android.annotation
annotation class SuppressLint(vararg val value:String)
''',
'Ext.kt':'''package com.speed.ext
fun getMacAddress():String?="00:11:22:33:44:55"
''',
'Network.kt':'''package com.speed.net
object NetworkHelper {var called=false;fun <T> makeRequest(url:String,method:com.speed.net.enum.RequestMethod,params:Map<String,Any>,isEncryted:Boolean,useDomainSwitch:Boolean,onResult:(T?,Throwable?)->Unit){called=true;onResult(null,null)} }
''',
'Method.kt':'''package com.speed.net.enum
enum class RequestMethod {POST}
''',
'Stubs.kt':'''package com.smart.android.ad_app
import android.content.Context
object AdLocalLog {fun i(t:String,m:String){};fun e(t:String,m:String,e:Throwable?=null){} }
object Hq008ApiConfig {const val FIXED_BASE_URL="https://backend.test/"}
object Hq008ConsentLogReporter {fun activeRequestId()="test";fun isActiveRequest(s:String)=false;fun bindAuthorizationRequestId(a:String,b:String){};fun report(eventType:String,eventMessage:String){} }
class HaierUserAgentReportCollector {fun collect(c:Context):Map<String,String> = emptyMap()}
object HaierUserAgentAuthorizeFields {fun build(channelType:String,fallbackEffectiveUa:String,collect:()->Map<String,String>)=collect()}
object Hq008XhsxAarRuntimeBridge {fun getEffectiveUserAgent()="ua"}
object Hq008ApiRequestFields {fun appendTo(body:MutableMap<String,Any>,context:Context,googleAuthorization:Boolean){throw IllegalStateException("Cannot commit TCL advertising identity")}}
object HaierBuildIdentityNormalizer {fun manufacturer()="make";fun model()="model";fun androidVersion()="14"}
object BuildConfig {const val AD_CHANNEL_TYPE="GOOGLE";const val VERSION_CODE=1}
object AdPlaybackPolicy {fun resolveCallbackTimeoutMs(t:Long?)=180000L}
''',
'Proof.kt':'''package com.smart.android.ad_app
fun main(args:Array<String>){var delivered=false;var thrown:Throwable?=null;try{Hq008SdkAuthorizeClient.request(android.content.Context(),"channel","id"){dto,error->delivered=true;println("callback error=$error")}}catch(t:Throwable){thrown=t};println("strict RDID failure: escaped=${thrown?.javaClass?.simpleName}, callbackDelivered=$delivered, networkCalled=${com.speed.net.NetworkHelper.called}");if(args.contains("--expect-handled")){check(thrown==null && delivered && !com.speed.net.NetworkHelper.called)}else{check(thrown is IllegalStateException && !delivered)} }
'''}
for n,s in files.items():(r/n).write_text(s)
base=Path(os.environ.get('GRADLE_USER_HOME',str(Path.home()/'.gradle')))/'caches/modules-2/files-2.1';jars=[]
for group,artifact,version in [('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.21'),('org.jetbrains.kotlin','kotlin-stdlib','2.2.21'),('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),('org.jetbrains.kotlin','kotlin-script-runtime','2.2.21'),('org.jetbrains.kotlin','kotlin-daemon-embeddable','2.2.21'),('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),('org.jetbrains','annotations','23.0.0'),('com.google.code.gson','gson','2.13.2')]:jars+=list((base/group/artifact/version).glob('*/'+artifact+'-'+version+'.jar'))
cp=os.pathsep.join(map(str,jars));subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(r/'classes')]+list(map(str,[r/n for n in files]+[source])),check=True)
subprocess.run(['java','-cp',str(r/'classes')+os.pathsep+cp,'com.smart.android.ad_app.ProofKt']+['--expect-handled']+sys.argv[1:],check=True)
