package com.smart.android.ad_app.google.ima.local.signal.module;
import java.io.*;
/** Source-driven cache selection, preserving the old/new provider distinction. */
public final class V2ModuleCacheReader {
 static final Object NEW_PROVIDER_LOCK=new Object();
 public interface Preferences {String getString(String name,String fallback);}
 public interface RegistrySource {boolean available();}
 private final Preferences preferences;private final File pccache;private final int selector;private final RegistrySource registry;
 public V2ModuleCacheReader(Preferences preferences,File actualContextPccache,int selector){this(preferences,actualContextPccache,selector,true);}
 public V2ModuleCacheReader(Preferences preferences,File actualContextPccache,int selector,final boolean actualRegistryAvailable){this(preferences,actualContextPccache,selector,new RegistrySource(){public boolean available(){return actualRegistryAvailable;}});}
 public V2ModuleCacheReader(Preferences preferences,File actualContextPccache,int selector,RegistrySource actualRegistry){if(preferences==null||actualContextPccache==null||actualRegistry==null)throw new NullPointerException("actual sources");this.preferences=preferences;this.pccache=actualContextPccache;this.selector=selector;this.registry=actualRegistry;}
 private V2ModuleMetadata metadataNew(){String text=preferences.getString("LATMTD"+(selector-1),null);if(text==null)return null;try{byte[] bytes=V2ModuleMetadata.unhex(text);if(!registry.available())return null;return V2ModuleMetadata.parse(bytes);}catch(V2ModuleMetadata.Malformed malformed){return null;}catch(RuntimeException invalid){return null;}}
 public Descriptor primary(boolean newProvider){return newProvider?primaryNew():primaryOld();}
 public Descriptor primaryNew(){synchronized(NEW_PROVIDER_LOCK){V2ModuleMetadata m=metadataNew();return m==null?null:descriptor(m,false);}}
 public boolean eligibleNew(){synchronized(NEW_PROVIDER_LOCK){V2ModuleMetadata m=metadataNew();if(m==null)return false;File dir=new File(new File(pccache,Integer.toString(selector-1)),m.moduleId);return new File(dir,"pcam.jar").exists()&&new File(dir,"pcbc").exists();}}
 public Descriptor primaryOld(){V2ModuleMetadata m=V2ModuleMetadata.fromOldCacheHexOrNull(preferences.getString("LATMTD"+(selector-1),null));if(m==null)return null;Descriptor d=descriptor(m,true);return d.pcam.exists()&&d.pcbc.exists()?d:null;}
 private Descriptor descriptor(V2ModuleMetadata m,boolean old){File suffix=new File(pccache,Integer.toString(selector-1));if(old&&!suffix.exists())suffix.mkdir();if(old&&m.moduleId.isEmpty())throw new NullPointerException("old zzox.zzb returned null for empty module id");File dir=new File(suffix,m.moduleId);if(old&&!dir.exists())dir.mkdirs();File pcam=new File(dir,"pcam.jar");if(!pcam.exists())pcam=new File(dir,"pcam");return new Descriptor(m,pcam,new File(dir,"pcbc"),new File(dir,"pcopt"));}
 public static final class Descriptor{
  public final V2ModuleMetadata metadata;public final File pcam,pcbc,pcopt;private byte[] cachedProgram;
  Descriptor(V2ModuleMetadata m,File pcam,File pcbc,File pcopt){metadata=m;this.pcam=pcam;this.pcbc=pcbc;this.pcopt=pcopt;}
  /** An IOException leaves cache null, so a later call retries; success returns a clone. */
  public byte[] programBytes(){if(cachedProgram==null){try(InputStream in=new FileInputStream(pcbc);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);cachedProgram=out.toByteArray();}catch(IOException unavailable){cachedProgram=null;}}return cachedProgram==null?null:cachedProgram.clone();}
 }
}
