package com.smart.android.ad_app.google.ima.local.signal.module;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Local-cache-only zzie/zzhx orchestration for the no-extra-reporting candidate.
 * Entry eligibility, configuration and allowed fallback still use actual state.
 * Only a real cached module may be initialized; no remote transport exists here.
 * An unsupported selected program is explicit and never becomes compatibility.
 */
public final class V2ModuleCoordinator implements AutoCloseable {
 public static final String SOURCE_POLICY="LOCAL_VERIFIED_CACHE_ONLY_NO_GASS_TXN3";
 public interface Flags {
  boolean newProvider();
  /** Real source readiness for the registry branch captured by that provider. */
  boolean capturedMetadataRegistryUsable();
 }
 public interface Loader {
  /** Prepare local live sources before loading the actual cached module. */
  default void prepare()throws Exception{}
  /** Verify/load the actual cached module, retaining certificate/profile/ABI gates. */
  boolean initialize(V2ModuleCacheReader.Descriptor actualDescriptor)throws Exception;
  boolean ready();
  V2ModuleCacheReader.Descriptor activeDescriptor();
  void close();
 }
 public interface Events {void event(int code,Object detail);}
 public static final class UnsupportedModule extends Exception {public UnsupportedModule(String message){super(message);}public UnsupportedModule(String message,Throwable cause){super(message,cause);}}
 public static final class ModuleUnavailable extends IllegalStateException {public ModuleUnavailable(String message){super(message);}}
 public enum Mode {COMPAT,V2,V2_NOT_READY,UNSUPPORTED_V2,UNAVAILABLE}
 public static final class Selection {public final Mode mode;public final String reason;public final V2ModuleCacheReader.Descriptor descriptor;Selection(Mode mode,String reason,V2ModuleCacheReader.Descriptor descriptor){this.mode=mode;this.reason=reason;this.descriptor=descriptor;}}
 private final int selector;private final Flags flags;private final V2ModuleCacheReader reader;private final Loader loader;private final Events events;
 private final CountDownLatch initializationDone=new CountDownLatch(1);
 private volatile boolean initializedOnce,closed;private volatile UnsupportedModule unsupported;
 private volatile String initializationStatus="not attempted; remote acquisition and background warm-up are disabled by "+SOURCE_POLICY;
 private volatile Boolean primaryCachePresent;
 public V2ModuleCoordinator(int actualAbiSelector,Flags actualFlags,V2ModuleCacheReader cacheReader,Loader actualIndependentLoader,Events events){if(actualFlags==null||cacheReader==null||actualIndependentLoader==null)throw new NullPointerException("actual local module sources required");selector=actualAbiSelector;flags=actualFlags;reader=cacheReader;loader=actualIndependentLoader;this.events=events;}
 private static boolean allowed(int selector){return selector==3||selector==5||selector==6||selector==7||selector==8;}
 private void event(int code,Object detail){if(events!=null)events.event(code,detail);}
 private V2ModuleCacheReader.Descriptor primary(){if(!allowed(selector))return null;return reader.primary(flags.newProvider());}
 /** Original entry eligibility still requires actual new-provider metadata,
  * the exact pcam.jar filename and pcbc; cache creation is never simulated. */
 public boolean primaryEligibleForEntry(){return reader.eligibleNew();}
 public Selection selectForEntry(final int configuredMode,final boolean actualAllowFallback){return selectForEntry(configuredMode,actualAllowFallback,null);}
 public Selection selectForEntry(final int configuredMode,final boolean actualAllowFallback,Runnable actualCompatibilityConstructor){return selectForEntry(configuredMode,actualAllowFallback,actualCompatibilityConstructor,null);}
 public Selection selectForEntry(final int configuredMode,final boolean actualAllowFallback,Runnable actualCompatibilityConstructor,V2ModuleCacheReader actualEntryReader){return selectForEntry(configuredMode,actualAllowFallback,actualCompatibilityConstructor,actualEntryReader,null);}
 /** Retained source signature: a supplied background callback is deliberately
  * never invoked under this candidate's explicit cache-only source policy. */
 public Selection selectForEntry(final int configuredMode,final boolean actualAllowFallback,Runnable actualCompatibilityConstructor,V2ModuleCacheReader actualEntryReader,Runnable actualBackgroundWarm){
  if(closed)throw new IllegalStateException("coordinator disposed");
  boolean eligible=configuredMode==3&&(actualEntryReader==null?primaryEligibleForEntry():actualEntryReader.eligibleNew());
  boolean initial=V2EntrySelection.initiallyTryV2(configuredMode,actualAllowFallback,eligible);
  if(initial){
   initializeOnce();
   if(unsupported!=null)return selection(Mode.UNSUPPORTED_V2,unsupported.toString()+"; "+SOURCE_POLICY+"; no substitution or remote refresh");
   if(loader.ready())return selection(Mode.V2,"actual selected cached primary initialized; "+SOURCE_POLICY);
   if(!actualAllowFallback)return selection(Mode.V2_NOT_READY,"actual V2 selected and fallback prohibited; "+initializationStatus+"; "+SOURCE_POLICY);
  }
  String compatibilityReason=initial?"actual V2 cache initialization not ready and original fallback allowed; "+initializationStatus:configuredMode==3?"actual new-provider primary cache not eligible (metadata/registry or pcam.jar/pcbc unavailable); original allowFallback=true selected compatibility":"actual configured mode "+configuredMode+" selected compatibility";
  if(!initial&&!initializedOnce)initializationStatus=compatibilityReason+"; local V2 initialization not requested; "+SOURCE_POLICY;
  try{if(actualCompatibilityConstructor!=null)actualCompatibilityConstructor.run();}
  finally{if(!initial)initializationDone.countDown();}
  if(!initial&&V2EntrySelection.backgroundWarmAfterCompat(configuredMode))event(6101,"background module warm-up blocked by "+SOURCE_POLICY);
  return selection(Mode.COMPAT,compatibilityReason+"; "+SOURCE_POLICY);
 }
 public Selection entryFailure(boolean actualCompatibilityDelegatePresent,String reason){initializationDone.countDown();return selection(actualCompatibilityDelegatePresent?Mode.COMPAT:Mode.UNAVAILABLE,reason+"; "+SOURCE_POLICY+"; no remote recovery");}
 private Selection selection(Mode mode,String reason){return new Selection(mode,reason,loader.activeDescriptor());}
 public synchronized void initializeOnce(){
  if(closed)throw new IllegalStateException("coordinator disposed");if(initializedOnce)return;initializedOnce=true;
  try{
   loader.prepare();V2ModuleCacheReader.Descriptor descriptor=primary();primaryCachePresent=descriptor!=null;
   if(descriptor==null){initializationStatus="no usable real primary cache for selected provider/ABI; remote acquisition disabled";event(4013,initializationStatus);}
   else if(loader.initialize(descriptor))initializationStatus="real cached module initialized after certificate/profile/ABI verification";
   else{initializationStatus="real cached module rejected or not ready after original certificate/program/ABI/backend gates; remote recovery disabled";event(6102,initializationStatus);}
  }catch(UnsupportedModule unknown){unsupported=unknown;initializationStatus="actual selected cached module is unsupported: "+unknown.getMessage();event(4000,unknown);}
  catch(Exception failure){initializationStatus="local cached-module initialization failed: "+failure.getClass().getName()+"; remote recovery disabled";event(4010,failure);}
  finally{initializationDone.countDown();}
 }
 /** No executor, remote transport, periodic job or cache writer is retained.
  * Call sites may keep their original refresh hook, but it has no send path. */
 public void maybeRefresh(){if(!closed)event(6101,"module refresh blocked by "+SOURCE_POLICY);}
 public boolean vmReady(){return loader.ready();}
 public boolean refreshRunning(){return false;}
 public long initializationLatchRemaining(){return initializationDone.getCount();}
 public long lastUpdateSeconds(){return 0L;}
 public UnsupportedModule unsupportedModule(){return unsupported;}
 public String initializationStatus(){return initializationStatus;}
 public Boolean primaryCachePresent(){return primaryCachePresent;}
 /** Every local outcome, including no-cache, failed or skipped initialization,
  * releases the latch; readiness remains a separate truthful result. */
 public boolean awaitInitialAttempt(long actualDeadlineMillis)throws InterruptedException{return initializationDone.await(actualDeadlineMillis,TimeUnit.MILLISECONDS)&&loader.ready();}
 @Override public synchronized void close(){if(closed)return;closed=true;initializationDone.countDown();loader.close();}
}
