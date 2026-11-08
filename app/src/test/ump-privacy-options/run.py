#!/usr/bin/env python3
"""Executes the exact production runner on a deterministic Android/UMP scheduler seam.
No network/device; real SDK and JavaScript semantics are covered by the device suite.
"""
from pathlib import Path
import json,hashlib,subprocess,os,sys
R=next(p for p in Path(__file__).resolve().parents if(p/'gradlew').is_file());O=Path(sys.argv[1]).resolve()if len(sys.argv)>1 else R/'build/ump-privacy-options-tests';O.mkdir(parents=True,exist_ok=True);S=O/'stubs';S.mkdir(exist_ok=True)
files={
'Os.kt':r'''package android.os
import java.util.PriorityQueue
object Clock {
 data class Task(val at:Long,val id:Long,val run:Runnable)
 var now=0L;private var next=0L
 val tasks=PriorityQueue<Task>(compareBy<Task>{it.at}.thenBy{it.id})
 fun reset(){tasks.clear();now=0;next=0}
 fun post(r:Runnable,delay:Long){tasks.add(Task(now+delay,++next,r))}
 fun remove(r:Runnable){tasks.removeIf{it.run===r}}
 fun advance(end:Long){var steps=0;while(tasks.isNotEmpty()&&tasks.peek().at<=end){check(++steps<100000);val t=tasks.poll();now=maxOf(now,t.at);t.run.run()};now=end}
}
object SystemClock { @JvmStatic fun uptimeMillis()=Clock.now }
class Looper {companion object {private val main=Looper();@JvmStatic fun getMainLooper()=main}}
class Handler(l:Looper){fun post(r:Runnable)=postDelayed(r,0);fun postDelayed(r:Runnable,d:Long):Boolean{Clock.post(r,d);return true};fun removeCallbacks(r:Runnable){Clock.remove(r)}}
''',
'View.kt':r'''package android.view
open class View {
 var alpha=1f;var isFocusable=true;var isFocusableInTouchMode=true;var parent:View?=null
 val rootView:View get()=parent?.rootView?:this
 open fun setBackgroundColor(c:Int){}
}
open class ViewGroup:View(){val children=mutableListOf<View>();val childCount:Int get()=children.size;fun getChildAt(i:Int)=children[i];fun add(v:View){v.parent=this;children.add(v)}}
class Window {var callback:Any?=null;var attributes=WindowManager.LayoutParams();val decorView=ViewGroup();fun setBackgroundDrawable(x:Any){};fun clearFlags(x:Int){};fun addFlags(x:Int){}}
class WindowManager {class LayoutParams {var alpha=1f;var dimAmount=1f;var windowAnimations=0;companion object{const val FLAG_DIM_BEHIND=2;const val FLAG_NOT_FOCUSABLE=8;const val FLAG_NOT_TOUCHABLE=16;const val FLAG_ALT_FOCUSABLE_IM=131072}}}
class Choreographer {
 fun interface FrameCallback {fun doFrame(frameTimeNanos:Long)}
 private val pending=java.util.IdentityHashMap<FrameCallback,Runnable>()
 fun postFrameCallback(cb:FrameCallback){val r=Runnable{pending.remove(cb);cb.doFrame(android.os.Clock.now*1000000)};pending[cb]=r;android.os.Clock.post(r,16-android.os.Clock.now%16)}
 fun removeFrameCallback(cb:FrameCallback){pending.remove(cb)?.let{android.os.Clock.remove(it)}}
 fun reset(){pending.clear()}
 companion object {private val instance=Choreographer();@JvmStatic fun getInstance()=instance}
}
class WindowManagerGlobal private constructor(){@JvmField val mViews=mutableListOf<View>();companion object{private val instance=WindowManagerGlobal();@JvmStatic fun getInstance()=instance}}
''',
'App.kt':r'''package android.app
import android.view.Window
open class Activity {val window=Window();var isFinishing=false;var isDestroyed=false;var transitionCalls=0;fun overridePendingTransition(a:Int,b:Int){transitionCalls++}}
class Dialog {val window=Window();fun dismiss(){}}
''',
'Color.kt':r'''package android.graphics
object Color {const val TRANSPARENT=0}
''',
'Drawable.kt':r'''package android.graphics.drawable
class ColorDrawable(x:Int)
''',
'Web.kt':r'''package android.webkit
import android.view.View
open class ConsoleMessage {fun message()=""}
class JsPromptResult {var confirmed:String?=null;fun confirm(v:String){confirmed=v}}
open class WebChromeClient {open fun onConsoleMessage(m:ConsoleMessage)=false;open fun onJsPrompt(v:WebView?,u:String?,m:String?,d:String?,r:JsPromptResult?)=false}
open class WebView:View(){val url="https://fundingchoicesmessages.google.com/";val originalUrl=url;var webChromeClient:WebChromeClient?=null;var evaluate:((String,(String?)->Unit)->Unit)?=null;fun evaluateJavascript(s:String,cb:(String?)->Unit){evaluate?.invoke(s,cb)}}
''',
'Ump.kt':r'''package com.google.android.ump
import android.app.Activity
class FormError(val message:String)
interface ConsentForm {fun show(a:Activity,cb:(FormError?)->Unit)}
object UserMessagingPlatform {var show:((Activity,(FormError?)->Unit)->Unit)?=null;@JvmStatic fun showPrivacyOptionsForm(a:Activity,cb:(FormError?)->Unit){show!!(a,cb)}}
''',
'Log.kt':r'''package com.smart.android.ad_app
import android.webkit.WebView
import android.webkit.JsPromptResult
object AdLocalLog {fun w(t:String,m:String){};fun i(t:String,m:String){};fun d(t:String,m:String){};fun e(t:String,m:String,e:Throwable){}}
object Hq008XhsxAarRuntimeBridge {fun prepareWebViewIdentity(v:WebView){}}
''',
'Cases.kt':r'''package com.smart.android.ad_app
import android.os.*
import android.app.Activity
import android.view.*
import android.webkit.WebView
import android.webkit.JsPromptResult
import com.google.android.ump.*
private val rows=mutableListOf<String>()
private class Rig(val loadAt:Long=0,val mode:String="normal",val dismissDelay:Long=75,val decision:GoogleUmpSilentConsentFormRunner.DecisionMode=GoogleUmpSilentConsentFormRunner.DecisionMode.REJECT) {
 val a=Activity();val results=mutableListOf<GoogleUmpSilentConsentFormRunner.Result>();var calls=0;var clicks=0;var shownAt=-1L;var resultAt=-1L;var firstEvalAt=-1L;var opaqueFirstDraw=false;var lastView:WebView?=null;var lateEffects=0;var last:((FormError?)->Unit)?=null;val scripts=mutableListOf<String>()
 init {Clock.reset();Choreographer.getInstance().reset();WindowManagerGlobal.getInstance().mViews.clear();UserMessagingPlatform.show={act,cb->
  calls++;last=cb
  when {
   mode=="throw"->throw IllegalStateException("SDK exploded")
   mode=="sync_success"->cb(null)
   mode=="other_error"->cb(FormError("not available"))
   mode=="loading_forever"||Clock.now<loadAt->cb(FormError("Privacy options form is being loading. Please try again later."))
   mode=="no_view"->Unit
   else->{shownAt=Clock.now;val root=ViewGroup();val web=WebView();lastView=web;root.add(web);WindowManagerGlobal.getInstance().mViews.add(root);Clock.post(Runnable{if(root.alpha*web.alpha>0)opaqueFirstDraw=true},16-Clock.now%16);web.evaluate={s,done->clicks++;if(firstEvalAt<0)firstEvalAt=Clock.now;scripts+=s;if(mode=="late_eval"){Clock.post(Runnable{val token=Regex("__yg_ump_action_fence_[0-9]+").find(s)!!.value;val answer=JsPromptResult();web.webChromeClient!!.onJsPrompt(web,"https://fundingchoicesmessages.google.com/",token,"",answer);if((answer.confirmed?.toLongOrNull()?:0)>0){lateEffects++;done("clicked:100:test")}else done("inactive")},20000)}else{done(if(mode=="no_button")"none"else"clicked:100:test");if(mode=="normal")Clock.post(Runnable{cb(null)},dismissDelay)}}}
  }
 }}
 fun start(){GoogleUmpSilentConsentFormRunner.showPrivacyOptionsAndApplyDecisionSilently(a,decision){results+=it;resultAt=Clock.now}}
 fun success(){check(results.size==1&&results[0].formError==null&&results[0].localErrorMessage==null){results.toString()}}
 fun error(s:String){check(results.size==1);check(results[0].localErrorMessage?.contains(s)==true||results[0].formError?.message?.contains(s)==true){results.toString()}}
 fun quiet(){check(Clock.tasks.isEmpty()){Clock.tasks.toString()};val n=calls;Clock.advance(90000);check(calls==n&&results.size==1)}
}
private fun case(n:String,f:()->Unit){try{f()}catch(e:Throwable){System.err.println("CASE_FAILED:"+n);throw e};rows+="{\"case\":\"$n\",\"passed\":true}"}
fun main(){
 case("warm form keeps 250ms initial click and cleanup"){val r=Rig();r.start();Clock.advance(249);check(r.clicks==0);Clock.advance(325);r.success();check(r.clicks==1);r.quiet()}
 case("preload 8 seconds outlives former five retries"){val r=Rig(8000);r.start();Clock.advance(9000);r.success();check(r.calls>6&&r.shownAt>=8000);r.quiet()}
 case("29 second preload still gets full original 15 second action budget"){val r=Rig(29000,dismissDelay=14000);r.start();Clock.advance(44000);r.success();check(r.resultAt>43000);r.quiet()}
 case("loading is bounded at 30 seconds and cancels retries"){val r=Rig(mode="loading_forever");r.start();Clock.advance(29999);check(r.results.isEmpty());Clock.advance(30000);r.error("readiness timed out");check(r.calls==40);r.quiet()}
 case("SDK accepts show but never attaches a view is bounded"){val r=Rig(mode="no_view");r.start();Clock.advance(30000);r.error("readiness timed out");check(r.calls==1);r.quiet()}
 case("nonloading SDK error is immediate not retried"){val r=Rig(mode="other_error");r.start();r.error("not available");check(r.resultAt==0L&&r.calls==1);r.quiet()}
 case("synchronous SDK exception cleans readiness tasks"){val r=Rig(mode="throw");r.start();r.error("SDK exploded");r.quiet()}
 case("synchronous dismiss does not install a dangling timeout"){val r=Rig(mode="sync_success");r.start();r.success();r.quiet()}
 case("late success after readiness timeout is ignored"){val r=Rig(mode="loading_forever");r.start();Clock.advance(30000);r.error("readiness timed out");r.last!!(null);check(r.results.size==1);r.quiet()}
 case("late loading error after timeout cannot schedule another show"){val r=Rig(mode="loading_forever");r.start();Clock.advance(30000);r.last!!(FormError("loading"));r.error("readiness timed out");r.quiet()}
 case("destroyed host stops waiting and every retry"){val r=Rig(mode="loading_forever");r.start();Clock.advance(1000);r.a.isDestroyed=true;Clock.advance(1120);r.error("destroyed");r.quiet()}
 case("finishing host never enters SDK show"){val r=Rig();r.a.isFinishing=true;r.start();r.error("destroyed");check(r.calls==0);r.quiet()}
 case("action timeout remains 15 seconds after delayed readiness"){val r=Rig(20000,mode="no_button");r.start();Clock.advance(36000);r.error("auto-click timed out");check(r.clicks>1&&r.resultAt-(r.firstEvalAt-250)==15000L);r.quiet()}
 case("duplicate dismiss callbacks deliver one terminal"){val r=Rig();r.start();Clock.advance(400);r.last!!(null);r.last!!(FormError("late"));r.success();r.quiet()}
 case("asynchronous attach is suppressed before first draw not next Handler poll"){val r=Rig(8000);r.start();Clock.advance(9000);r.success();check(!r.opaqueFirstDraw);r.quiet()}
 case("historical 747ms preload plus 14 second SDK acknowledgement does not spend action budget"){val r=Rig(747,dismissDelay=14400);r.start();Clock.advance(16000);r.success();check(r.resultAt>15000&&r.resultAt<15750);r.quiet()}
 case("unrelated root stays visible while the privacy surface is hidden"){val r=Rig(8000);val other=ViewGroup();WindowManagerGlobal.getInstance().mViews.add(other);r.start();Clock.advance(9000);r.success();check(other.alpha==1f);r.quiet()}
 case("queued renderer evaluation cannot mutate after the terminal timeout"){val r=Rig(mode="late_eval");r.start();Clock.advance(15000);r.error("auto-click timed out");Clock.advance(21000);check(r.lateEffects==0&&r.results.size==1);r.quiet()}
 case("native deadline rejects before the delayed timeout Runnable runs"){val r=Rig(mode="no_button");r.start();Clock.advance(300);val token=Regex("__yg_ump_action_fence_[0-9]+").find(r.scripts[0])!!.value;Clock.now=16000;val out=JsPromptResult();check(r.lastView!!.webChromeClient!!.onJsPrompt(r.lastView,"",token,"",out));check(out.confirmed=="0");Clock.advance(16000);r.error("auto-click timed out");r.quiet()}
 case("stale fence tokens are rejected without displaying a prompt"){val r=Rig(mode="no_button");r.start();Clock.advance(300);val token=Regex("__yg_ump_action_fence_[0-9]+").find(r.scripts[0])!!.value;val out=JsPromptResult();check(r.lastView!!.webChromeClient!!.onJsPrompt(r.lastView,"",token+"stale","",out));check(out.confirmed=="0");val ordinary=JsPromptResult();check(!r.lastView!!.webChromeClient!!.onJsPrompt(r.lastView,"","ordinary prompt","",ordinary));Clock.advance(16000);r.error("auto-click timed out");r.quiet()}
 case("already hidden surfaces do not issue window updates every frame"){val r=Rig(mode="no_button");r.start();Clock.advance(500);check(r.a.transitionCalls<12){r.a.transitionCalls};Clock.advance(16000);r.error("auto-click timed out");r.quiet()}
 case("initial form queued evaluation is fenced after its original timeout"){
  Clock.reset();Choreographer.getInstance().reset();WindowManagerGlobal.getInstance().mViews.clear();val a=Activity();val web=WebView();val results=mutableListOf<GoogleUmpSilentConsentFormRunner.Result>();var effects=0
  web.evaluate={s,done->Clock.post(Runnable{val token=Regex("__yg_ump_action_fence_[0-9]+").find(s)!!.value;val out=JsPromptResult();web.webChromeClient!!.onJsPrompt(web,"",token,"",out);if((out.confirmed?.toLongOrNull()?:0)>0)effects++;done("inactive")},20000)}
  val form=object:ConsentForm {fun zzc()=web;override fun show(a:Activity,cb:(FormError?)->Unit){}}
  GoogleUmpSilentConsentFormRunner.showAndApplyDecisionSilently(a,form,GoogleUmpSilentConsentFormRunner.DecisionMode.ACCEPT_ALL){results+=it};Clock.advance(15000);check(results.size==1&&results[0].localErrorMessage?.contains("auto-click timed out")==true);Clock.advance(22000);check(effects==0&&Clock.tasks.isEmpty())
 }
 case("initial synchronous SDK dismiss leaves no scheduled tasks"){
  Clock.reset();WindowManagerGlobal.getInstance().mViews.clear();val web=WebView();val a=Activity();var count=0
  val form=object:ConsentForm {fun zzc()=web;override fun show(a:Activity,cb:(FormError?)->Unit){cb(null)}}
  GoogleUmpSilentConsentFormRunner.showAndApplyDecisionSilently(a,form,GoogleUmpSilentConsentFormRunner.DecisionMode.ACCEPT_ALL){count++};check(count==1&&Clock.tasks.isEmpty())
 }
 case("accept and reject script decision substitution stays exact"){
  for(d in GoogleUmpSilentConsentFormRunner.DecisionMode.entries){val r=Rig(decision=d);r.start();Clock.advance(400);r.success();check(r.scripts.size==1&&r.scripts[0].contains(d.name)&&!r.scripts[0].contains("%%DECISION_MODE%%"));System.getProperty("ump.script.output")?.let{val f=java.io.File(it);f.mkdirs();java.io.File(f,"script-"+d.name+".js").writeText(r.scripts[0])};r.quiet()}
 }
 println("{\"passed\":true,\"cases\":"+rows.size+",\"results\":["+rows.joinToString(",")+"]}")
}
'''
}
for name,s in files.items():(S/name).write_text(s)
cache=Path.home()/'.gradle/caches/modules-2/files-2.1';jars=[]
for group,name,version in [('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.21'),('org.jetbrains.kotlin','kotlin-stdlib','2.2.21'),('org.jetbrains.kotlin','kotlin-script-runtime','2.2.21'),('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),('org.jetbrains','annotations','13.0')]:jars+=list((cache/group/name/version).glob('*/*.jar'))
stdlib=next(x for x in jars if x.name.startswith('kotlin-stdlib-'));java=Path(os.environ.get('JAVA_HOME','/Users/zy/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home'))/'bin/java';source=Path(os.environ.get('RUNNER_SOURCE',str(R/'app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/GoogleUmpSilentConsentFormRunner.kt'))).resolve();inputs=list(S.glob('*.kt'))+[source];(O/'inputs.json').write_text(json.dumps({str(p):hashlib.sha256(p.read_bytes()).hexdigest()for p in inputs},indent=2));cp=':'.join(map(str,jars));classes=O/'classes';classes.mkdir(exist_ok=True)
args=[str(java),'-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-jvm-target','17','-d',str(classes),*map(str,inputs)];r=subprocess.run(args,capture_output=True,text=True);(O/'compile.log').write_text(r.stdout+r.stderr)
if r.returncode:print(r.stdout+r.stderr);raise SystemExit(r.returncode)
r=subprocess.run([str(java),'-Dump.script.output='+str(O/'scripts'),'-cp',str(classes)+':'+str(stdlib),'com.smart.android.ad_app.CasesKt'],capture_output=True,text=True);(O/'run.log').write_text(r.stdout+r.stderr);print(r.stdout+r.stderr)
if r.returncode:raise SystemExit(r.returncode)
result=json.loads(r.stdout.strip().splitlines()[-1]);(O/'summary.json').write_text(json.dumps(result,indent=2))
