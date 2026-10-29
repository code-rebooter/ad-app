package com.smart.android.ad_app.google.ima.local.signal.android;
import android.content.Context;import android.content.SharedPreferences;import android.os.Build;
import com.smart.android.ad_app.google.ima.local.signal.CompatMode1Runtime;import com.smart.android.ad_app.google.ima.local.signal.FlagsInputs;import com.smart.android.ad_app.google.ima.local.signal.PrimitiveObserver;
import com.smart.android.ad_app.google.ima.local.signal.module.*;import com.smart.android.ad_app.google.ima.local.signal.V2QueryMapBuilder;import com.smart.android.ad_app.google.ima.local.signal.v2.HistoricalV2Engine;import com.smart.android.ad_app.google.ima.local.signal.v2.PinnedStandaloneBackend;import com.smart.android.ad_app.google.ima.local.envelope.EnvelopeFormat;import com.smart.android.ad_app.google.ima.local.envelope.kernel.FixedTransform;
import java.io.*;import java.security.*;import java.util.*;import java.util.concurrent.*;
/** Android assembly retaining pinned zzie selection with an explicit local-only
 * module source policy. Owns Context/preferences/flags/cache/compat runtime.
 * Never uses historical captured module bytes as a replacement for current cache.
 * Only the verified actual ARM64 pin uses the independently proven empty-Bundle
 * health/backend. Unknown selected module code stops explicitly.
 */
public final class ImaLocalMsOwner implements AutoCloseable {
 public static final class Configuration {
  public final int mode;public final boolean allowFallback;public final String provenance,actualSdkOsArch,architectureProvenance;
  public Configuration(int actualMode,boolean actualAllowFallback,String actualConfigurationProvenance,String actualSdkOsArch,String architectureProvenance){if(actualMode<1||actualMode>3)throw new UnsupportedOperationException("Unimplemented original configuration enum "+actualMode);if(actualConfigurationProvenance==null||actualConfigurationProvenance.isEmpty()||architectureProvenance==null||architectureProvenance.isEmpty())throw new IllegalArgumentException("Actual config and architecture getter provenance required");mode=actualMode;allowFallback=actualAllowFallback;provenance=actualConfigurationProvenance;this.actualSdkOsArch=actualSdkOsArch;this.architectureProvenance=architectureProvenance;}
  /** Original zzge.call -> zzl.zze(3); zzm constructor leaves zzf=true. */
  public static Configuration pinnedGoogleUrlOnly(String actualSdkOsArch,String architectureProvenance){return new Configuration(3,true,"pinned IMA3.35.1 zzge.call:zzl.zze(3); zzm default zzf=true -> zzl()",actualSdkOsArch,architectureProvenance);}
 }
 public interface EntropySource {byte[] sample32()throws Exception;}
 private static final EntropySource LOCAL_SECURE_RANDOM=new EntropySource(){public byte[] sample32(){return ImaLocalEntropy.sample32();}};
 public static final class Result {
  public final String ms;public final V2ModuleCoordinator.Mode selectedMode;public final CompatMode1Runtime.Result compat;public final HistoricalV2Engine.Result v2;public final byte[][] freshPadding;
  private Result(String ms,CompatMode1Runtime.Result result,byte[][] padding){this.ms=ms;selectedMode=V2ModuleCoordinator.Mode.COMPAT;compat=result;freshPadding=padding;v2=null;}
  private Result(HistoricalV2Engine.Result result){ms=result.ms;selectedMode=V2ModuleCoordinator.Mode.V2;v2=result;compat=null;freshPadding=new byte[0][];}
  private Result(String ms,V2ModuleCoordinator.Mode mode){this.ms=ms;selectedMode=mode;compat=null;v2=null;freshPadding=new byte[0][];}
  public byte[] rawPayload(){return compat!=null?compat.payload.clone():v2==null?null:v2.rawXss();}
 }
 /** Explicit process ownership mirrors the original static zzhx: entry close
  * never tears down the process module backend, live sources; remote module refresh is disabled. */
 public static final class ProcessScope implements AutoCloseable {
  private final Context context;private final Configuration initialConfig;private final ImaLocalFlagsProvider.IntSource sdk;private final boolean forceTv;
  private final ImaLocalFlagsProvider flags;private final ExecutorService executor;private final V2AbiSelector.Result abi;
  private final V2ModuleCoordinator coordinator;private final SelectionLoader loader;private final List<Map<String,Object>> events;
  private final V2ModuleCacheReader.Preferences store;private final File cache;private final CapturedRegistry registry;
  private boolean closed;private long createdEntries;private AndroidCompatGenerator.ProcessScope compatibleProcess;
  private ProcessScope(Context context,Configuration config,ImaLocalFlagsProvider.IntSource sdk,boolean forceTv,ImaLocalFlagsProvider flags,ExecutorService executor,V2AbiSelector.Result abi,V2ModuleCoordinator coordinator,SelectionLoader loader,List<Map<String,Object>> events,V2ModuleCacheReader.Preferences store,File cache,CapturedRegistry registry){this.context=context;initialConfig=config;this.sdk=sdk;this.forceTv=forceTv;this.flags=flags;this.executor=executor;this.abi=abi;this.coordinator=coordinator;this.loader=loader;this.events=events;this.store=store;this.cache=cache;this.registry=registry;}
  public static ProcessScope open(Context context,ImaLocalFlagsProvider.IntSource sdk,boolean forceTv,Configuration config,PrimitiveObserver observer)throws Exception{return ImaLocalMsOwner.processScope(context,sdk,forceTv,config,observer);}
  public static ProcessScope open(Context context,ImaLocalFlagsProvider.IntSource sdk,boolean forceTv,Configuration config,PrimitiveObserver observer,EntropySource entropy)throws Exception{return ImaLocalMsOwner.processScope(context,sdk,forceTv,config,observer,entropy);}
  public synchronized ImaLocalMsOwner newEntry(CompatMode1Runtime.ProcessState process,PrimitiveObserver observer)throws Exception{return newEntry(initialConfig,process,observer);}
  /** Every zzie has a fresh fixed delegate and its own compatibility constructor.
   * Entry cache eligibility captures ps:er now; shared cache loading preserves
   * the separately captured registry from first process construction. */
  public synchronized ImaLocalMsOwner newEntry(Configuration config,CompatMode1Runtime.ProcessState process,PrimitiveObserver observer)throws Exception{
   if(closed)throw new IllegalStateException("process scope disposed");if(config==null||process==null)throw new NullPointerException("actual entry inputs");
   final PrimitiveObserver actualObserver=observer==null?PrimitiveObserver.NONE:observer;
   final ActualMetadataRegistry.Resolution entryRegistry=ActualMetadataRegistry.forActualClassLoader((Boolean)flags.read("zzmj.zzb"),context.getClassLoader());
   V2ModuleCacheReader entryReader=new V2ModuleCacheReader(store,cache,abi.selector,new V2ModuleCacheReader.RegistrySource(){public boolean available(){return entryRegistry.available();}});
   final AndroidCompatGenerator[] selectedCompat=new AndroidCompatGenerator[1];
   final Runnable compatibleConstructor=new Runnable(){public void run(){try{selectedCompat[0]=compatibleEntry(process,actualObserver);}catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new IllegalStateException("Actual compatibility constructor failed",failure);}}};
   try{
    V2ModuleCoordinator.Selection selection;
    try{selection=coordinator.selectForEntry(config.mode,config.allowFallback,compatibleConstructor,entryReader);}
    catch(NullPointerException failure){if(config.allowFallback){if(selectedCompat[0]!=null)selectedCompat[0].close();compatibleConstructor.run();}record(events,"entry",2031,failure);selection=coordinator.entryFailure(selectedCompat[0]!=null,"original entry NullPointerException policy");}
    createdEntries++;return new ImaLocalMsOwner(this,config,selection,selectedCompat[0],actualObserver,entryRegistry,false);
   }catch(Throwable failure){if(selectedCompat[0]!=null)try{selectedCompat[0].close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}rethrow(failure);throw new AssertionError();}
  }
  private AndroidCompatGenerator compatibleEntry(CompatMode1Runtime.ProcessState process,PrimitiveObserver observer)throws Exception{if(compatibleProcess==null)compatibleProcess=AndroidCompatGenerator.ProcessScope.open(context,sdk,forceTv,process,observer);return compatibleProcess.newEntry(observer);}
  public synchronized Map<String,Object> snapshot(){Map<String,Object> out=new LinkedHashMap<String,Object>();out.put("processScopeIdentity",System.identityHashCode(this));out.put("closed",closed);out.put("createdEntries",createdEntries);out.put("moduleRegistry",registry.snapshot());out.put("moduleLoader",loader.snapshot());out.put("compatibilityProcess",compatibleProcess==null?null:compatibleProcess.snapshot());out.put("moduleSourcePolicy",V2ModuleCoordinator.SOURCE_POLICY);out.put("moduleRemoteTransportPresent",false);out.put("moduleRemoteRefreshAllowed",false);out.put("moduleBackgroundWarmAllowed",false);out.put("moduleInitializationStatus",coordinator.initializationStatus());out.put("modulePrimaryCachePresent",coordinator.primaryCachePresent());out.put("moduleAttemptLatch",coordinator.initializationLatchRemaining());out.put("moduleRefreshRunning",coordinator.refreshRunning());out.put("moduleLastUpdateSeconds",coordinator.lastUpdateSeconds());return out;}
  @Override public synchronized void close()throws Exception{if(closed)return;closed=true;try{coordinator.close();}finally{try{if(compatibleProcess!=null)compatibleProcess.close();}finally{try{flags.close();}finally{executor.shutdownNow();}}}}
 }
 private static ProcessScope processSingleton;
 /** Production entry: one native/module owner for the whole Android process.
  * Later settings/Context arguments are ignored, as in original static zzhx.zzb. */
 public static synchronized ProcessScope processScope(Context context,ImaLocalFlagsProvider.IntSource sdk,boolean forceTv,Configuration config,PrimitiveObserver observer)throws Exception{return processScope(context,sdk,forceTv,config,observer,LOCAL_SECURE_RANDOM);}
 public static synchronized ProcessScope processScope(Context context,ImaLocalFlagsProvider.IntSource sdk,boolean forceTv,Configuration config,PrimitiveObserver observer,EntropySource entropy)throws Exception{if(processSingleton==null)processSingleton=openProcessScope(context,sdk,forceTv,config,observer,entropy);return processSingleton;}
 /** Isolated scope for disposable diagnostic components. Applications normally
  * use processScope rather than opening one scope for each ad request. */
 public static ProcessScope openProcessScope(Context actualContext,ImaLocalFlagsProvider.IntSource effectiveSdk,boolean forceTvMode,Configuration config,PrimitiveObserver observer)throws Exception{return openProcessScope(actualContext,effectiveSdk,forceTvMode,config,observer,LOCAL_SECURE_RANDOM);}
 public static ProcessScope openProcessScope(Context actualContext,ImaLocalFlagsProvider.IntSource effectiveSdk,boolean forceTvMode,Configuration config,PrimitiveObserver observer,EntropySource actualEntropySource)throws Exception{
  if(actualContext==null||effectiveSdk==null||config==null||actualEntropySource==null)throw new NullPointerException("Actual Android inputs required");Context application=actualContext.getApplicationContext();final Context context=application==null?actualContext:application;final ExecutorService executor=Executors.newCachedThreadPool();final List<Map<String,Object>> events=Collections.synchronizedList(new ArrayList<Map<String,Object>>());ImaLocalFlagsProvider flags=null;V2ModuleCoordinator coordinator=null;SelectionLoader loader=null;
  try{
   flags=new ImaLocalFlagsProvider(context,effectiveSdk,forceTvMode,new FlagsInputs.Provider(){public Object read(String symbol){throw new FlagsInputs.MissingSourceException("Module flag owner has no compatibility-task state for "+symbol);}public String provenance(String symbol){return null;}});flags.initialize();final ImaLocalFlagsProvider actualFlags=flags;
   final CapturedRegistry registry=new CapturedRegistry(context,actualFlags);final V2ModuleCacheReader.RegistrySource registrySource=registry;
   final V2AbiSelector.Result abi=V2AbiSelector.select(context,config.actualSdkOsArch);final SharedPreferences prefs=context.getSharedPreferences("ima_local_vm_state",0);V2ModuleCacheReader.Preferences store=new V2ModuleCacheReader.Preferences(){public String getString(String k,String fallback){return prefs.getString(k,fallback);}};
   File cache=context.getDir("ima_local_module_cache",0);V2ModuleCacheReader reader=new V2ModuleCacheReader(store,cache,abi.selector,registrySource);
   final ModuleCertificateVerifier certificates=new ModuleCertificateVerifier();
   loader=new SelectionLoader(context,actualFlags,effectiveSdk,executor,certificates,actualEntropySource,abi.selector,events,observer==null?PrimitiveObserver.NONE:observer,registry);
   coordinator=new V2ModuleCoordinator(abi.selector,new V2ModuleCoordinator.Flags(){public boolean newProvider(){return(Boolean)actualFlags.read("zzmj.zza");}public boolean capturedMetadataRegistryUsable(){throw new AssertionError("Registry resolution is lazy inside the cache reader");}},reader,loader,new V2ModuleCoordinator.Events(){public void event(int code,Object detail){record(events,"coordinator",code,detail);}});
   record(events,"module-source-policy",6100,V2ModuleCoordinator.SOURCE_POLICY);
   return new ProcessScope(context,config,effectiveSdk,forceTvMode,flags,executor,abi,coordinator,loader,events,store,cache,registry);
  }catch(Throwable failure){try{if(coordinator!=null)coordinator.close();else if(loader!=null)loader.close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}try{if(flags!=null)flags.close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}executor.shutdownNow();rethrow(failure);throw new AssertionError();}
 }
 private final ProcessScope scope;private final Context context;private final Configuration config;private final V2ModuleCoordinator.Selection selection;private final PrimitiveObserver observer;private final ActualMetadataRegistry.Resolution entryRegistry;private final SecureRandom random=new SecureRandom();private AndroidCompatGenerator compat;private boolean ownsScope,closed;
 private ImaLocalMsOwner(ProcessScope scope,Configuration config,V2ModuleCoordinator.Selection selection,AndroidCompatGenerator compat,PrimitiveObserver observer,ActualMetadataRegistry.Resolution entryRegistry,boolean ownsScope){this.scope=scope;context=scope.context;this.config=config;this.selection=selection;this.compat=compat;this.observer=observer;this.entryRegistry=entryRegistry;this.ownsScope=ownsScope;}
 public static ImaLocalMsOwner create(Context actualContext,ImaLocalFlagsProvider.IntSource effectiveSdk,boolean forceTvMode,Configuration config)throws Exception{return create(actualContext,effectiveSdk,forceTvMode,config,new CompatMode1Runtime.ProcessState(),PrimitiveObserver.NONE);}
 public static ImaLocalMsOwner create(Context actualContext,ImaLocalFlagsProvider.IntSource effectiveSdk,boolean forceTvMode,Configuration config,CompatMode1Runtime.ProcessState process,PrimitiveObserver observer)throws Exception{return create(actualContext,effectiveSdk,forceTvMode,config,process,observer,LOCAL_SECURE_RANDOM);}
 /** Standalone diagnostic convenience. This entry owns its isolated scope;
  * multi-request production sessions must use processScope().newEntry(). */
 public static ImaLocalMsOwner create(Context context,ImaLocalFlagsProvider.IntSource sdk,boolean forceTv,Configuration config,CompatMode1Runtime.ProcessState process,PrimitiveObserver observer,EntropySource entropy)throws Exception{ProcessScope scope=openProcessScope(context,sdk,forceTv,config,observer,entropy);try{ImaLocalMsOwner owner=scope.newEntry(process,observer);owner.ownsScope=true;return owner;}catch(Throwable failure){try{scope.close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}rethrow(failure);throw new AssertionError();}}
 private static void rethrow(Throwable failure)throws Exception{if(failure instanceof Exception)throw(Exception)failure;if(failure instanceof Error)throw(Error)failure;throw new IllegalStateException(failure);}
 /** Captured at first actual module singleton construction, after live query
  * source construction and before its first primary-cache parse. */
 private static final class CapturedRegistry implements V2ModuleCacheReader.RegistrySource {
  private final Context context;private final ImaLocalFlagsProvider flags;private ActualMetadataRegistry.Resolution resolution;
  CapturedRegistry(Context context,ImaLocalFlagsProvider flags){this.context=context;this.flags=flags;}
  synchronized void capture(){if(resolution==null)resolution=ActualMetadataRegistry.forActualClassLoader((Boolean)flags.read("zzmj.zzb"),context.getClassLoader());}
  public synchronized boolean available(){capture();return resolution.available();}
  synchronized Map<String,Object> snapshot(){if(resolution!=null)return resolution.snapshot();Map<String,Object> out=new LinkedHashMap<String,Object>();out.put("captured",false);out.put("reason","shared module singleton has not been constructed");return out;}
 }
 /** Actual cache/module inputs only. Pinned code constants are never inserted
  * into cache and an ARM64 native module is not accepted by a 32-bit process. */
 private static final class SelectionLoader implements V2ModuleCoordinator.Loader {
  private final Context context;private final ImaLocalFlagsProvider flags;private final ImaLocalFlagsProvider.IntSource sdk;private final Executor executor;private final ModuleCertificateVerifier certificates;private final EntropySource entropy;private final int abi;private final List<Map<String,Object>> events;private final PrimitiveObserver observer;private final CapturedRegistry registry;
  private V2QueryMapFactory sources;private PinnedStandaloneBackend backend;private V2ModuleCacheReader.Descriptor active;private volatile Map<String,Object> lastMap;private boolean prepared,closed;
  SelectionLoader(Context context,ImaLocalFlagsProvider flags,ImaLocalFlagsProvider.IntSource sdk,Executor executor,ModuleCertificateVerifier certificates,EntropySource entropy,int abi,List<Map<String,Object>> events,PrimitiveObserver observer,CapturedRegistry registry){this.registry=registry;this.context=context;this.flags=flags;this.sdk=sdk;this.executor=executor;this.certificates=certificates;this.entropy=entropy;this.abi=abi;this.events=events;this.observer=observer;}
  public synchronized void prepare(){if(closed)throw new IllegalStateException("module loader disposed");if(prepared)return;
   String version=(String)flags.read("zzm.zze");boolean gai=(Boolean)flags.read("zzif.zza");
   // Original zznv registered gms default is true on this exact zzge/zzm path.
   sources=new V2QueryMapFactory(context,sdk.get(),executor,new V2QueryMapBuilder.Options(version,true,gai),(Boolean)flags.read("zzmj.zzp"),(Boolean)flags.read("zzmj.zzx"),(Boolean)flags.read("zzmj.zzy"),"pinned zzge/zzm/zzif and real flag getters; zznv.gms registered true; query uses zzjd.zzb not its view-only vst map");registry.capture();prepared=true;record(events,"query-sources-prepared",-1,null);
  }
  public synchronized boolean initialize(V2ModuleCacheReader.Descriptor descriptor)throws Exception{
   if(closed)throw new IllegalStateException("module loader disposed");prepare();if(!certificates.verify(descriptor.pcam,Build.TYPE))return false;byte[] program=descriptor.programBytes();if(program==null)return false;
   if(!PinnedModuleProfile.supports(descriptor.pcam,program))throw new V2ModuleCoordinator.UnsupportedModule("Actual selected module id="+descriptor.metadata.moduleId+", jarSha256="+PinnedModuleProfile.sha256(descriptor.pcam)+", programSha256="+PinnedModuleProfile.sha256(program)+" is not independently implemented");
   if(abi!=6||!android.os.Process.is64Bit()){record(events,"module-abi-incompatible",4000,"Pinned actual module has only ARM64 native code; actual selector="+abi+", actualProcess64="+android.os.Process.is64Bit());return false;}
   byte[] table=PinnedModuleProfile.recoveredTable(),random=entropy.sample32();if(random==null||random.length!=32)throw new IllegalStateException("Actual platform entropy must contain32 bytes");
   PrimitiveObserver.emit(observer,"v2_constructor_primitives",null,0,PrimitiveObserver.fields("actualModuleId",descriptor.metadata.moduleId,"actualSelectedProgramSha256",PinnedModuleProfile.sha256(program),"recoveredCodeTableSha256",PinnedModuleProfile.TABLE_SHA256,"actualEntropy32",random,"entropySource",entropy==LOCAL_SECURE_RANDOM?"IMA-local java.security.SecureRandom 32 bytes":"explicit caller-supplied EntropySource.sample32","actualRuntimeEnvironment",2,"actualConstructorBundleEmpty",true));
   PinnedStandaloneBackend replacement=PinnedStandaloneBackend.open(context,program,table,random);boolean accepted=false;try{if(replacement.status()!=0)return false;PinnedStandaloneBackend previous=backend;if(previous!=null)previous.close();backend=replacement;active=descriptor;accepted=true;record(events,"selected-pinned-module",3000,descriptor.metadata.moduleId);return true;}finally{if(!accepted)replacement.close();}
  }
  public V2QueryEntry.Outcome<HistoricalV2Engine.Result> query(final V2ModuleCoordinator coordinator,final PrimitiveObserver queryObserver)throws Exception{
   return V2QueryEntry.query(new V2QueryEntry.Runtime<HistoricalV2Engine.Result>(){
    public void beforeQuery(){synchronized(SelectionLoader.this){if(closed)throw new IllegalStateException("module loader disposed");prepare();sources.beforeQuery((Boolean)flags.read("zzmj.zzp"));}}
    // Original zzhx.zzf calls zzo before acquiring the chosen delegate monitor.
    // Never take coordinator.updateLock while holding the loader monitor.
    public void maybeRefresh(){coordinator.maybeRefresh();}
    public V2QueryEntry.Backend<HistoricalV2Engine.Result> activeBackend(){
     final PinnedStandaloneBackend chosen;synchronized(SelectionLoader.this){if(closed)throw new IllegalStateException("module loader disposed");chosen=backend;}
     return chosen==null?null:new V2QueryEntry.Backend<HistoricalV2Engine.Result>(){public HistoricalV2Engine.Result query()throws Exception{synchronized(chosen){
      // Refresh may have selected and closed this old delegate after selection;
      // original Dg.xss with state0 returns null, not an invented Unsupported.
      if(chosen.isClosed())return null;
      Map<String,Object> map=sources.buildMapOnly(context);lastMap=new HashMap<String,Object>(map);PrimitiveObserver.emit(queryObserver,"v2_query_map_primitives",null,chosen.completedQueryCount()+1,PrimitiveObserver.fields("actualQueryMap",safeQueryMap(lastMap)));
      try{return chosen.query(map);}catch(com.smart.android.ad_app.google.ima.local.signal.vm.V2Vm.UnsupportedInstruction unknown){throw new V2ModuleCoordinator.UnsupportedModule("Actual selected VM query reached an unported instruction",unknown);}catch(UnsupportedOperationException unknown){throw new V2ModuleCoordinator.UnsupportedModule("Actual selected VM query reached unported helper code",unknown);}catch(IllegalStateException unknown){throw new V2ModuleCoordinator.UnsupportedModule("Actual independent VM state/recovery is not proven for this failure",unknown);}catch(IllegalArgumentException unknown){throw new V2ModuleCoordinator.UnsupportedModule("Actual independent VM argument/type failure is not proven",unknown);}catch(Exception failure){record(events,"query-xss",2007,failure);return null;}
     }}};
    }
   });
  }
  public synchronized boolean ready(){return !closed&&backend!=null&&!backend.isClosed();}public synchronized V2ModuleCacheReader.Descriptor activeDescriptor(){return active;}
  public synchronized Map<String,Object> snapshot(){Map<String,Object> out=new LinkedHashMap<String,Object>();out.put("sourcesPrepared",prepared);out.put("backendPresent",backend!=null);out.put("actualModuleId",active==null?null:active.metadata.moduleId);out.put("pinnedJarSha256",PinnedModuleProfile.JAR_SHA256);out.put("pinnedProgramSha256",PinnedModuleProfile.PROGRAM_SHA256);out.put("recoveredCodeTableSha256",PinnedModuleProfile.TABLE_SHA256);out.put("entropySource",entropy==LOCAL_SECURE_RANDOM?"IMA-local java.security.SecureRandom 32 bytes":"explicit caller-supplied EntropySource.sample32; caller must supply genuine source provenance");if(backend!=null){out.put("initializationSteps",backend.initializationStepCount());out.put("completedQueries",backend.completedQueryCount());out.put("backendClosed",backend.isClosed());if(!backend.isClosed())out.put("actualBoundLcs",backend.status());}if(lastMap!=null)out.put("lastQueryMapPrimitives",safeQueryMap(lastMap));return out;}
  private Map<String,Object> safeQueryMap(Map<String,Object> map){Map<String,Object> values=new LinkedHashMap<String,Object>();for(Map.Entry<String,Object> e:map.entrySet()){Object value=e.getValue();if(value instanceof Context){Context c=(Context)value;Map<String,Object> identity=new LinkedHashMap<String,Object>();identity.put("actualClass",c.getClass().getName());identity.put("packageName",c.getPackageName());identity.put("identity",System.identityHashCode(c));identity.put("sameConstructorContext",c==context);value=identity;}else if(value instanceof Throwable){StringWriter stack=new StringWriter();((Throwable)value).printStackTrace(new PrintWriter(stack));value=stack.toString();}values.put(e.getKey(),value);}return values;}
  public synchronized void close(){if(closed)return;closed=true;try{if(backend!=null)backend.close();}catch(Exception failure){throw new IllegalStateException("Actual resident close failed",failure);}finally{if(sources!=null)sources.close();}}
 }
 private static void record(List<Map<String,Object>> events,String source,int code,Object detail){Map<String,Object> e=new LinkedHashMap<String,Object>();e.put("source",source);e.put("event",code);e.put("wallMillis",System.currentTimeMillis());e.put("detail",detail instanceof Map?detail:detail==null?null:String.valueOf(detail));events.add(e);}
 public synchronized Result generate()throws Exception{
  if(closed)throw new IllegalStateException("owner disposed");
  if(selection.mode==V2ModuleCoordinator.Mode.V2_NOT_READY)throw new V2ModuleCoordinator.ModuleUnavailable(selection.reason);
  if(selection.mode==V2ModuleCoordinator.Mode.V2){V2QueryEntry.Outcome<HistoricalV2Engine.Result> result=scope.loader.query(scope.coordinator,observer);return !result.backendPresent?new Result("",selection.mode):result.value==null?new Result(null,selection.mode):new Result(result.value);}
  if(selection.mode==V2ModuleCoordinator.Mode.UNAVAILABLE)return new Result("",selection.mode);
  if(selection.mode!=V2ModuleCoordinator.Mode.COMPAT)throw new V2ModuleCoordinator.UnsupportedModule(selection.reason);
  CompatMode1Runtime.Result result=compat.generate();byte[][] padding=new byte[(result.payload.length+254)/255][];for(int i=0;i<padding.length;i++){padding[i]=new byte[255-Math.min(255,result.payload.length-i*255)];if(padding[i].length>0)random.nextBytes(padding[i]);}return new Result(EnvelopeFormat.encode(result.payload,padding,FixedTransform::transform),result,padding);
 }
 public V2ModuleCoordinator.Mode selectedMode(){return selection.mode;}
 public boolean awaitInitialModuleAttempt(long actualCallerDeadlineMillis)throws InterruptedException{return scope.coordinator.awaitInitialAttempt(actualCallerDeadlineMillis);}
 public synchronized Map<String,Object> snapshot(){Map<String,Object> out=new LinkedHashMap<String,Object>();out.put("contextPackageName",context.getPackageName());out.put("configurationEnum",config.mode);out.put("allowFallback",config.allowFallback);out.put("configurationProvenance",config.provenance);out.put("architectureProvenance",config.architectureProvenance);out.put("abiSelector",scope.abi.selector);out.put("abiSource",scope.abi.source);out.put("selectedMode",selection.mode.name());out.put("selectionReason",selection.reason);out.put("entryClosed",closed);out.put("entryOwnsIsolatedScope",ownsScope);out.put("processScopeIdentity",System.identityHashCode(scope));out.put("newProviderFlag",scope.flags.read("zzmj.zza"));out.put("newProviderFlagOrigin",scope.flags.provenance("zzmj.zza"));out.put("registryFlag",scope.flags.read("zzmj.zzb"));out.put("registryFlagOrigin",scope.flags.provenance("zzmj.zzb"));out.put("entryRegistry",entryRegistry.snapshot());out.putAll(scope.snapshot());out.put("unsupportedSelectedModule",scope.coordinator.unsupportedModule()==null?null:scope.coordinator.unsupportedModule().toString());synchronized(scope.events){out.put("moduleEvents",new ArrayList<Map<String,Object>>(scope.events));}return out;}
 @Override public synchronized void close()throws Exception{if(closed)return;closed=true;try{if(compat!=null)compat.close();}finally{compat=null;if(ownsScope)scope.close();}}
}
