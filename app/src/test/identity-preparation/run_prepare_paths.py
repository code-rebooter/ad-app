"""Compile current preparation/cleanup method bodies verbatim with narrow JVM collaborators.
The before variants reconstruct only this repair's missing catch/ownership boundary; no production file is changed.
"""
from pathlib import Path
import os
if os.environ.get("JAVA_HOME"):
 os.environ["PATH"]=str(Path(os.environ["JAVA_HOME"])/"bin")+os.pathsep+os.environ.get("PATH","")
import hashlib,subprocess,os
root=Path(__file__).resolve().parents[4];here=root/'build/identity-preparation-regression';here.mkdir(parents=True,exist_ok=True);out=here/'prepare-paths';out.mkdir(exist_ok=True)
def method(s,marker):
 start=s.index(marker)
 if marker.endswith('isActive(') or marker.endswith('isRunning('):return s[start:s.index('\n\n',start)]
 pos=s.index('{',start);depth=0
 for i in range(pos,len(s)):
  depth+=(s[i]=='{')-(s[i]=='}')
  if depth==0:return s[start:i+1]
 raise ValueError(marker)
tclfile=root/'app/src/hq008/java/com/smart/android/ad_app/AdManagerImpl.kt';imafile=root/'app/src/googleImaRuntime/java/com/smart/android/ad_app/GoogleImaAdManager.kt'
tcl=tclfile.read_text();ima=imafile.read_text()
for p in [tclfile,imafile]:print('SOURCE',p,'sha256='+hashlib.sha256(p.read_bytes()).hexdigest(),flush=True)
# Exactly the current TCL preparation block, with its ordinary production cleanup functions.
a=tcl.index('            // Preparation may fail before a native controller exists');b=tcl.index('            if (!isInitialized())',a);after=tcl[a:b]
call_a=after.index('                request.runtime =');call_b=after.index('\n            } catch (error: Exception)');before=after[call_a:call_b]+'\n            currentRequest = request\n            armTimeout(request)\n'
helpers='\n'.join(method(tcl,'    private fun '+name+'(') for name in ['isActive','releaseNative','releaseController','cancelTimers','clearActiveRequestState','failRequest'])
prefix='''package com.smart.android.ad_app
import android.os.Handler
import android.os.SystemClock
import java.lang.ref.WeakReference
import com.smart.android.ad_app.AdLocalLog as Log
object TclProbe {
private const val TAG="probe"
private val mainHandler=Handler()
private val requestOwner=Hq008PlaybackRequestOwner()
private val controllers=Hq008PlaybackControllers<NativeController> {it}
private var currentRequest:PendingShowRequest?=null
private var pendingRequest:PendingShowRequest?=null
private var observed:PendingShowRequest?=null
private var newer:PendingShowRequest?=null
private var errors=0
private var started=0
private var runtimeClosed=0
private fun onMain(action:()->Unit)=action()
private fun isInitialized()=true
private fun resetInitializationAttempt(){}
private fun gamPatchEnabled(g:Any?)=false
private fun onRuntimeEvent(r:PendingShowRequest,e:TclRuntimeEvent){}
private fun armTimeout(r:PendingShowRequest){r.timeoutRunnable=Runnable{};mainHandler.postDelayed(r.timeoutRunnable!!,100)}
private fun begin(id:String):PendingShowRequest {val lease=controllers.begin{it.release()};return PendingShowRequest(id,"channel",id,requestOwner.begin(0,100),lease,{errors++})}
private class PendingShowRequest(val requestId:String,val channelId:String,val adId:String?,val lifecycle:Hq008PlaybackRequestOwner.Ticket,val controllerLease:Hq008PlaybackControllers.Lease<NativeController>,val adError:(()->Unit)?) {
 val playback=Playback();var runtime:TclRuntimeSession?=null;var timeoutRunnable:Runnable?=null;var watchdogRunnable:Runnable?=null;var nativeReleased=false
 fun markTerminal()=requestOwner.finish(lifecycle,SystemClock.elapsedRealtime())
 fun buildCompletionDiagnostics():Map<String,Any?> = emptyMap()
}
private class Playback {fun finish(t:Long,r:String){}}
private class NativeController {fun release(){}}
private class Audit {fun record(t:String,at:Long,f:Map<String,Any?>){}}
private class TclRuntimeSession {val audit=Audit()}
private class TclRuntimeEvent
private object TclRuntimeBridge {var fail=true;var replace=false;var throwAfterReplace=false
 fun create(id:String,ch:String,sound:Boolean,p:Playback,patchEnabled:Boolean,onRequestResult:(Map<String,Any?>)->Unit,listener:(TclRuntimeEvent)->Unit):TclRuntimeSession {
  if(replace){val old=currentRequest!!;old.markTerminal();clearActiveRequestState(old);releaseController(old,"replaced");newer=begin("new");currentRequest=newer}
  if(fail || throwAfterReplace)throw IllegalStateException("identity unavailable")
  return TclRuntimeSession()
 }
 fun snapshot(s:TclRuntimeSession):Map<String,Any?> = emptyMap()
 fun stopNetwork(s:TclRuntimeSession){}
 fun close(s:TclRuntimeSession,r:String,p:Map<String,Any?>){runtimeClosed++}
}
'''
def prepare(name,block):return 'private fun '+name+'()=onMain {\n val request=begin("old");observed=request;val requestId=request.requestId;val soundEnabled=false;val gamConfig:Any?=null;val createdAtMs=0L;val effectiveCallbackTimeoutMs=100L;val requestedWidth=320;val requestedHeight=180\n'+block+' started++\n}\n'
proof='''fun run(case:String){
 var escaped:Throwable?=null
 TclRuntimeBridge.fail=case.contains("fail")
 TclRuntimeBridge.replace=case.contains("replace")
 TclRuntimeBridge.throwAfterReplace=case.contains("throw")
 try{if(case.startsWith("before"))prepareBefore() else prepareAfter()}catch(t:Throwable){escaped=t}
 mainHandler.drain()
 val req=observed!!;val leaseField=controllers.javaClass.getDeclaredField("active").apply{isAccessible=true};val leaseActive=leaseField.get(controllers)
 println("TCL $case escaped=${escaped?.javaClass?.simpleName}, ticketFinished=${req.lifecycle.finishedAtMs!=null}, leaseActive=${leaseActive!=null}, errors=$errors, started=$started, runtimeClosed=$runtimeClosed")
 when(case){
 "before_fail"->check(escaped!=null && req.lifecycle.finishedAtMs==null && leaseActive!=null && errors==0)
 "after_fail"->check(escaped==null && req.lifecycle.finishedAtMs!=null && leaseActive==null && currentRequest==null && errors==1 && started==0 && mainHandler.size()==0)
 "after_ok"->check(escaped==null && req.lifecycle.finishedAtMs==null && currentRequest===req && errors==0 && started==1)
 "after_replace"->check(escaped==null && req.lifecycle.finishedAtMs!=null && currentRequest===newer && requestOwner.isActive(newer!!.lifecycle) && leaseActive===newer!!.controllerLease && runtimeClosed==1 && errors==0 && started==0)
 "after_replace_throw"->check(escaped==null && req.lifecycle.finishedAtMs!=null && currentRequest===newer && requestOwner.isActive(newer!!.lifecycle) && leaseActive===newer!!.controllerLease && errors==0 && started==0)
 }
}
}
'''
(out/'TclProbe.kt').write_text(prefix+prepare('prepareBefore',before)+prepare('prepareAfter',after)+helpers+'\n'+proof)
# IMA: verbatim current parameter preparation and fail/release/close guards.
a=ima.index('        val parameters = try {');b=ima.index('        val patch = if',a);ima_after=ima[a:b]
start=ima_after.index('            linkedMapOf');end=ima_after.index('\n        } catch (error: Exception)');ima_before='        val parameters = '+ima_after[start:end].lstrip()+'\n'
ima_helpers='\n'.join(method(ima,'    private fun '+name+'(') for name in ['fail','close','releasePlayer','finishRuntimeAudit','isActive','isRunning','beginFinishing'])
terminal=method(ima,'internal class GoogleImaTerminalGate')
ima_prefix='''package com.smart.android.ad_app
import android.os.Handler
import android.os.SystemClock
import com.speed.ext.getMacAddress
import java.lang.ref.WeakReference
object ImaProbe {
private val main=Handler()
private var current:Session?=null
private var errors=0
private var plays=0
private var reports=0
private var reason=""
private var timerCancels=0
private var newSession:Session?=null
private class Container {val context=Context();var removals=0;fun removeAllViews(){removals++}}
private class Context {val applicationContext get()=this}
private class Player {var releases=0;fun capturePlayerSnapshot(s:String){};fun release(){releases++}}
private class Patch {fun cancel(){}}
private class RuntimeAudit {var finishes=0;fun finish(r:String){finishes++}}
private class Session(val id:String,val onError:(()->Unit)?){val adId=id;val channelId="channel";val reportedSoundEnabled=true;val container=WeakReference(Container());var player:Player?=Player();var backendPatch:Patch?=null;var active=true;val terminalGate=GoogleImaTerminalGate();val finishing get()=terminalGate.isFinishing;fun onComplete(){};var finishedAt:Long?=null;var lastErrorDetails:Map<String,String> = emptyMap();var completionRelease:Runnable?=null;var runtimeAuditFinished=false;val runtimeAudit=RuntimeAudit();fun diagnostics():Map<String,Any?> = emptyMap()}
private fun finalizeStallDurations(s:Session,n:Long){}
private fun cancelSessionTimers(s:Session){timerCancels++}
private fun trace(s:Session,t:String,d:String=""){}
private object Hq008ApiRequestFields {var fail=true;var replace=false;fun appendTo(m:MutableMap<String,Any>,context:Context,googleAuthorization:Boolean){if(replace){val old=current!!;close(old);newSession=Session("new"){errors++};current=newSession};if(fail)throw IllegalStateException("identity unavailable")}}
private object Hq008AdReporter {object Message {const val TIMEOUT="timeout";const val REQUEST_ERROR="request_error"};fun reportError(id:String,ad:String?,hidden:Boolean,code:Int?,message:String,diagnostics:Map<String,Any?>){reports++;reason=diagnostics["reason"].toString()}}
private object AdDisplayConfig {fun isHiddenMode()=true}
private object AdPlaybackPolicy {fun isTimeoutReason(s:String)=false}
'''
def ima_prepare(name,block):return 'private fun '+name+'(session:Session){val container=session.container.get()!!\n'+block+'plays++\n}\n'
ima_proof='''fun run(case:String){
 Hq008ApiRequestFields.fail=case.contains("fail") || case.contains("replace")
 Hq008ApiRequestFields.replace=case.contains("replace")
 val session=Session("old"){errors++};current=session;val player=session.player!!;val container=session.container.get()!!
 var escaped:Throwable?=null;try{if(case.startsWith("before"))prepareBefore(session)else prepareAfter(session)}catch(t:Throwable){escaped=t}
 println("IMA $case escaped=${escaped?.javaClass?.simpleName}, playerReleases=${player.releases}, auditFinishes=${session.runtimeAudit.finishes}, errors=$errors, plays=$plays, reason=$reason")
 when(case){
 "before_fail"->check(escaped!=null && player.releases==0 && errors==0 && current===session)
 "after_fail"->check(escaped==null && player.releases==1 && session.runtimeAudit.finishes==1 && errors==1 && plays==0 && current==null && !session.active && container.removals==1 && reason=="request_prepare_failed")
 "after_ok"->check(escaped==null && player.releases==0 && errors==0 && plays==1 && current===session)
 "after_replace"->check(escaped==null && current===newSession && newSession!!.active && newSession!!.player!!.releases==0 && newSession!!.container.get()!!.removals==0 && errors==0 && plays==0)
 }
}
}
'''
(out/'ImaProbe.kt').write_text(ima_prefix+ima_prepare('prepareBefore',ima_before)+ima_prepare('prepareAfter',ima_after)+ima_helpers+'\n'+ima_proof+'\n'+terminal)
files={
'Android.kt':'''package android.os
class Handler {private val queued=mutableListOf<Runnable>();fun post(r:Runnable){queued+=r};fun postDelayed(r:Runnable,t:Long){queued+=r};fun removeCallbacks(r:Runnable){queued.remove(r)};fun drain(){val q=queued.toList();queued.clear();q.forEach{it.run()}};fun size()=queued.size}
object SystemClock {fun elapsedRealtime()=100L}
''',
'Stubs.kt':'''package com.smart.android.ad_app
object AdLocalLog {fun i(t:String,m:String){};fun e(t:String,m:String,e:Throwable?=null){};fun w(t:String,m:String,e:Throwable?=null){}}
object Hq008SdkRequestReports {fun resumePending(){}}
object Hq008ConsentLogReporter {fun report(eventType:String,eventMessage:String,requestId:String?=null){}}
object AdDisplayConfig {fun isHiddenMode()=true}
object Hq008AdReporter {object Message {const val REQUEST_ERROR="request_error"};fun reportRequested(requestId:String,adId:String?,hiddenMode:Boolean,containerWidth:Int,containerHeight:Int,extra:Map<String,Any?>,durable:Boolean){};fun reportError(requestId:String,adId:String?,hiddenMode:Boolean,errorCode:Int?,errorMessage:String,extra:Map<String,Any?>,durable:Boolean){} }
object BuildConfig {const val VERSION_CODE=1;const val VERSION_NAME="1"}
''',
'Ext.kt':'''package com.speed.ext
fun getMacAddress():String?="mac"
''',
'Proof.kt':'''package com.smart.android.ad_app
fun main(args:Array<String>){if(args[0]=="tcl")TclProbe.run(args[1])else ImaProbe.run(args[1])}
'''}
for n,s in files.items():(out/n).write_text(s)
base=Path(os.environ.get('GRADLE_USER_HOME',str(Path.home()/'.gradle')))/'caches/modules-2/files-2.1';jars=[]
for group,artifact,version in [('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.21'),('org.jetbrains.kotlin','kotlin-stdlib','2.2.21'),('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),('org.jetbrains.kotlin','kotlin-script-runtime','2.2.21'),('org.jetbrains.kotlin','kotlin-daemon-embeddable','2.2.21'),('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),('org.jetbrains','annotations','23.0.0')]:jars+=list((base/group/artifact/version).glob('*/'+artifact+'-'+version+'.jar'))
cp=os.pathsep.join(map(str,jars));sources=list(out.glob('*.kt'))+[root/'app/src/main/java/com/smart/android/ad_app'/n for n in ['Hq008PlaybackRequestOwner.kt','Hq008PlaybackControllers.kt']]
subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(out/'classes')]+list(map(str,sources)),check=True)
for engine,cases in [('tcl',['before_fail','after_fail','after_ok','after_replace','after_replace_throw']),('ima',['before_fail','after_fail','after_ok','after_replace'])]:
 for case in cases:subprocess.run(['java','-cp',str(out/'classes')+os.pathsep+cp,'com.smart.android.ad_app.ProofKt',engine,case],check=True)
