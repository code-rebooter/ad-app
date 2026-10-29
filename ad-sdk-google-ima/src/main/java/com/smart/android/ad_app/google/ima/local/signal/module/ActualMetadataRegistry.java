package com.smart.android.ad_app.google.ima.local.signal.module;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.WeakHashMap;

/** The pinned zzadk.zza/zzb choice, without an IMA/protobuf dependency.
 *
 * zzlf is a nonextendable five-field message. Its parser requires a non-null
 * registry even though these getters never consult an extension entry. The
 * ps:er=true branch supplies the built-in empty registry. The false branch is
 * lazy: a genuine no-provider result is null, and null is not cached.
 *
 * The fixed IMA 3.35.1 artifact contains no generated loader or service provider.
 * This source checks the ACTUAL runtime ClassLoader rather than assuming that
 * artifact inventory describes a future application. Present external providers
 * are explicit unsupported until their implementation is independently ported;
 * they are never treated as absence, never replaced with synthetic registries.
 */
public final class ActualMetadataRegistry {
 public static final String GENERATED_LOADER="com.google.protobuf.BlazeGeneratedExtensionRegistryLiteLoader";
 public static final String SERVICE_NAME="com.google.ads.interactivemedia.v3.internal.zzads";
 private static final String SERVICE_RESOURCE="META-INF/services/"+SERVICE_NAME;
 private static final Map<ClassLoader,WeakReference<DynamicState>> STATES=new WeakHashMap<ClassLoader,WeakReference<DynamicState>>();
 private ActualMetadataRegistry(){}
 /** Deliberately outside zzpd's RuntimeException catch: do not turn unknown
  * executable extension code into the original null-registry parse failure. */
 public static final class UnsupportedRegistryExtension extends Error {
  public UnsupportedRegistryExtension(String message){super(message);}
 }
 /** No discovery is performed here. Call available only after a real metadata
  * String has been decoded from valid hex, immediately before parsing it. */
 public static Resolution forActualClassLoader(boolean capturedPsEr,ClassLoader actualLoader){
  if(actualLoader==null)throw new NullPointerException("Actual application ClassLoader required");
  DynamicState state;
  synchronized(STATES){WeakReference<DynamicState> ref=STATES.get(actualLoader);state=ref==null?null:ref.get();if(state==null){state=new DynamicState(new ClasspathSource(actualLoader));STATES.put(actualLoader,new WeakReference<DynamicState>(state));}}
  return new Resolution(capturedPsEr,state);
 }
 public static final class Resolution {
  public final boolean capturedPsEr;
  private final DynamicState state;
  Resolution(boolean flag,DynamicState state){capturedPsEr=flag;this.state=state;}
  public boolean available(){return capturedPsEr||state.get()!=null;}
  /** Observes existing state only: never triggers discovery or parsing. */
  public Map<String,Object> snapshot(){Map<String,Object> out=state.snapshot();out.put("capturedPsEr",capturedPsEr);out.put("selectedSource",capturedPsEr?"original-empty-registry":"actual-classloader-dynamic-registry");return out;}
 }
 /** Package scope only for isolated algorithm fixtures. Runtime always uses
  * ClasspathSource; callers cannot inject a purported successful registry. */
 interface Source {Object discover();Map<String,Object> snapshot();}
 static final class DynamicState {
  private final Source source;private volatile Object positive;private long attempts;
  DynamicState(Source source){this.source=source;}
  Object get(){Object value=positive;if(value!=null)return value;synchronized(this){value=positive;if(value!=null)return value;attempts++;value=source.discover();positive=value;return value;}}
  synchronized Map<String,Object> snapshot(){Map<String,Object> out=new LinkedHashMap<String,Object>();out.put("dynamicDiscoveryAttempts",attempts);out.put("positiveResultCached",positive!=null);out.put("discovery",source.snapshot());return out;}
 }
 private static final class ClasspathSource implements Source {
  private final ClassLoader loader;private String status="not-requested";private final List<String> resources=new ArrayList<String>(),providers=new ArrayList<String>();
  ClasspathSource(ClassLoader loader){this.loader=loader;}
  public Object discover(){
   resources.clear();providers.clear();
   try{
    // initialize=false is sufficient for the supported ABSENT case and avoids
    // executing unknown extension code. Present-loader semantics stay explicit.
    Class.forName(GENERATED_LOADER,false,loader);status="unsupported-generated-loader-present";
    throw new UnsupportedRegistryExtension("Actual ClassLoader contains "+GENERATED_LOADER+"; its provider code has not been independently ported");
   }catch(ClassNotFoundException absent){/* Original zzads enters ServiceLoader. */}
   try{
    Enumeration<URL> files=loader.getResources(SERVICE_RESOURCE);
    while(files.hasMoreElements()){
     URL file=files.nextElement();resources.add(file.toString());
     BufferedReader reader=new BufferedReader(new InputStreamReader(file.openStream(),"UTF-8"));
     try{String line;int number=0;while((line=reader.readLine())!=null){number++;int hash=line.indexOf('#');if(hash>=0)line=line.substring(0,hash);line=line.trim();if(line.length()==0)continue;
      if(line.indexOf(' ')>=0||line.indexOf('\t')>=0||!providerName(line))throw new UnsupportedRegistryExtension("Actual registry service descriptor requires an unported error path: "+file+":"+number+": "+line);
      if(!providers.contains(line))providers.add(line);
     }}finally{reader.close();}
    }
   }catch(IOException failure){status="service-resource-io-failure";throw new ServiceConfigurationError(SERVICE_NAME+": Error reading configuration file",failure);}
   if(!providers.isEmpty()){status="unsupported-service-provider-present";throw new UnsupportedRegistryExtension("Actual ClassLoader declares registry service providers "+providers+"; their code has not been independently ported");}
   status="no-generated-loader-and-no-service-provider";return null;
  }
  private static boolean providerName(String text){int cp=text.codePointAt(0);if(!Character.isJavaIdentifierStart(cp))return false;for(int pos=Character.charCount(cp);pos<text.length();pos+=Character.charCount(cp)){cp=text.codePointAt(pos);if(cp!='.'&&!Character.isJavaIdentifierPart(cp))return false;}return true;}
  public Map<String,Object> snapshot(){Map<String,Object> out=new LinkedHashMap<String,Object>();out.put("status",status);out.put("loaderClass",loader.getClass().getName());out.put("generatedLoader",GENERATED_LOADER);out.put("serviceResource",SERVICE_RESOURCE);out.put("observedServiceResources",new ArrayList<String>(resources));out.put("observedProviderNames",new ArrayList<String>(providers));return out;}
 }
}
