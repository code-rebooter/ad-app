package com.smart.android.ad_app.google.ima.local.signal.android;

import android.app.UiModeManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.ConditionVariable;
import android.os.StrictMode;
import com.smart.android.ad_app.google.ima.local.signal.FlagsInputs;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.IdentityHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** IMA-local flag reader. State stays in the host app and never reads a GMS package. */
public final class ImaLocalFlagsProvider implements FlagsInputs.Provider, AutoCloseable,
        SharedPreferences.OnSharedPreferenceChangeListener {
    public interface IntSource { int get(); String provenance(); }
    private final Context appContext;
    private final FlagsInputs.Provider taskContextState;
    private final Object initLock=new Object();
    private final Set<SharedPreferences> listenedPreferences=Collections.newSetFromMap(new IdentityHashMap<SharedPreferences,Boolean>());
    private boolean listenersClosed;
    private final ConditionVariable ready=new ConditionVariable();
    private final Map<String,String> origins=new ConcurrentHashMap<>();
    private final boolean preventAppHash;
    private final String preventAppHashOrigin;
    private volatile boolean initialized, initializing, forceDefaults;
    private volatile SharedPreferences preferences;
    private volatile JSONObject configuration=new JSONObject();
    private volatile Throwable initializationFailure;
    private volatile Future<?> initializationFuture;
    private volatile String preferenceOrigin="uninitialized";
    private volatile String bootstrapOrigin="uninitialized";

    public ImaLocalFlagsProvider(Context context, IntSource effectiveSdk, boolean forceTvMode,
                                FlagsInputs.Provider taskContextState) {
        Objects.requireNonNull(context,"context");
        Context app=context.getApplicationContext();
        appContext=app!=null?app:context;
        this.taskContextState=Objects.requireNonNull(taskContextState,"taskContextState");
        Objects.requireNonNull(effectiveSdk,"effectiveSdk");
        String origin=effectiveSdk.provenance();
        if (origin==null || origin.trim().isEmpty())
            throw new FlagsInputs.MissingSourceException("Effective patched SDK provenance missing");
        int sdk=effectiveSdk.get();
        if (sdk<0) throw new FlagsInputs.MissingSourceException("Invalid effective patched SDK");
        boolean tv=false;
        if (sdk<30) {
            if (forceTvMode) tv=true;
            else {
                UiModeManager mode=(UiModeManager)context.getSystemService(Context.UI_MODE_SERVICE);
                tv=mode!=null && mode.getCurrentModeType()==4;
            }
        }
        preventAppHash=sdk<30 && tv;
        preventAppHashOrigin="zzge.call/zzel.zzd:effective-sdk="+origin+
                ";explicit-forceTvMode="+forceTvMode+";UiModeManager-if-required";
    }

    /** Mirrors zzji -> zzmj.zza -> zzmh.zzc. Reuse one provider per IMA-local lifecycle. */
    public synchronized Future<?> initializeAsync(ExecutorService executor) {
        Objects.requireNonNull(executor,"executor");
        if (initializationFuture==null || (initializationFuture.isDone() && !initialized))
            initializationFuture=executor.submit(new Runnable() { public void run() { initialize(); } });
        return initializationFuture;
    }

    public void initialize() {
        withDisk(new Callable<Object>() { public Object call() { initializeWithDisk(); return null; } });
    }

    private void initializeWithDisk() {
        if (initialized) return;
        synchronized(initLock) {
            if (initialized) return;
            initializing=true;
            initializationFailure=null;
            try {
                bootstrapOrigin="app-private/ima_local_flags";
                preferenceOrigin=bootstrapOrigin;
                preferences=openFlags(appContext);
                if (preferences!=null && !listenersClosed) {
                    preferences.registerOnSharedPreferenceChangeListener(this);
                    listenedPreferences.add(preferences);
                }
                reloadConfiguration(preferences);
                initialized=true;
            } catch (RuntimeException failure) {
                initializationFailure=failure; throw failure;
            } catch (Error failure) {
                initializationFailure=failure; throw failure;
            } finally { initializing=false; ready.open(); }
        }
    }

    private SharedPreferences openFlags(Context context) {
        if (context==null) return null;
        try { return context.getSharedPreferences("ima_local_flags",0); }
        catch (IllegalStateException unavailable) { return null; }
    }
    private void reloadConfiguration(final SharedPreferences prefs) {
        if (prefs==null) return;
        withDisk(new Callable<Object>() { public Object call() {
            String raw=prefs.getString("flag_configuration","{}");
            try { configuration=new JSONObject(raw); }
            catch (JSONException ignored) { /* Existing cached JSON is retained, as in zzmh.zze. */ }
            return null;
        } });
    }
    @Override public void onSharedPreferenceChanged(SharedPreferences prefs,String key) {
        if ("flag_configuration".equals(key)) reloadConfiguration(prefs);
    }

    @Override public Object read(final String symbol) {
        FlagsInputs.Definition definition=FlagsInputs.DEFINITIONS.get(symbol);
        if (definition!=null) return readFlag(definition);
        Object value;
        String origin;
        switch(symbol) {
            case "zzif.zza": value=Boolean.FALSE; origin="zzge.call:zzl.zzb(false)->zzm.zzj"; break;
            case "zzm.zze": value="a.3.35.1"; origin="zzge.call:zzl.zzd(a.3.35.1)"; break;
            case "zzai.zza": value=Integer.valueOf(5000); origin="zzai.<init>:registered-local-constructor"; break;
            case "zzai.zzf": value=Boolean.valueOf(preventAppHash); origin=preventAppHashOrigin; break;
            case "zzai.zzg": case "zzai.zzh":
                value=Boolean.FALSE; origin="zzge.call:default-zzai-no-setter-for-this-field"; break;
            case "zzjj.zzp": case "zzjj.zzq": case "zzjj.zzr": case "zzjj.zzs":
                value=taskContextState.read(symbol); origin=taskContextState.provenance(symbol);
                if (!(value instanceof Boolean) || origin==null || origin.trim().isEmpty())
                    throw new FlagsInputs.MissingSourceException("Missing real task-context state: "+symbol);
                break;
            default: throw new FlagsInputs.MissingSourceException("Unsupported source symbol: "+symbol);
        }
        origins.put(symbol,origin); return value;
    }

    private Object readFlag(final FlagsInputs.Definition definition) {
        if (!ready.block(5000L)) {
            synchronized(initLock) {
                if (!initializing) throw new IllegalStateException("Flags.initialize() was not called!");
            }
        }
        if (!initialized || preferences==null || forceDefaults) {
            synchronized(initLock) {
                if (!initialized || preferences==null || forceDefaults)
                    return registeredDefault(definition,forceDefaults?"crash-or-init-reset-policy":"not-ready-or-no-preferences");
            }
        }
        JSONObject json=configuration;
        if (json.has(definition.key)) {
            Object value;
            try {
                if (definition.type==Boolean.class) value=Boolean.valueOf(json.getBoolean(definition.key));
                else if (definition.type==Integer.class) value=Integer.valueOf(json.getInt(definition.key));
                else if (definition.type==Long.class) value=Long.valueOf(json.getLong(definition.key));
                else value=json.optString(definition.key,(String)definition.registeredDefault);
            } catch (JSONException invalidOverride) { return registeredDefault(definition,"invalid-flag_configuration-value"); }
            origins.put(definition.symbol,preferenceOrigin+":flag_configuration-json:"+definition.key);
            return value;
        }
        return withDisk(new Callable<Object>() { public Object call() {
            SharedPreferences prefs=preferences;
            Object value;
            if (definition.type==Boolean.class) value=Boolean.valueOf(prefs.getBoolean(definition.key,(Boolean)definition.registeredDefault));
            else if (definition.type==Integer.class) value=Integer.valueOf(prefs.getInt(definition.key,(Integer)definition.registeredDefault));
            else if (definition.type==Long.class) value=Long.valueOf(prefs.getLong(definition.key,(Long)definition.registeredDefault));
            else value=prefs.getString(definition.key,(String)definition.registeredDefault);
            origins.put(definition.symbol,prefs.contains(definition.key)?preferenceOrigin+":raw-key:"+definition.key:
                    "registered-default:no-json-or-raw-key:"+definition.key);
            return value;
        } });
    }
    private Object registeredDefault(FlagsInputs.Definition definition,String reason) {
        origins.put(definition.symbol,"registered-default:"+reason+":"+definition.key);
        return definition.registeredDefault;
    }
    @Override public String provenance(String symbol) { return origins.get(symbol); }
    public Map<String,Object> readiness() {
        Map<String,Object> state=new LinkedHashMap<>();
        state.put("initialized",initialized); state.put("initializing",initializing);
        state.put("forceDefaults",forceDefaults); state.put("preferencesAvailable",preferences!=null);
        state.put("preferenceOrigin",preferenceOrigin); state.put("bootstrapOrigin",bootstrapOrigin);
        state.put("initializationFailure",initializationFailure==null?null:initializationFailure.getClass().getName());
        return Collections.unmodifiableMap(state);
    }
    /** Dispose only this IMA-local provider's registrations; leave values/future intact. */
    @Override public void close() {
        synchronized(initLock) {
            listenersClosed=true;
            RuntimeException first=null;
            for (SharedPreferences prefs:listenedPreferences) {
                try { prefs.unregisterOnSharedPreferenceChangeListener(this); }
                catch (RuntimeException failure) { if (first==null) first=failure; }
            }
            listenedPreferences.clear();
            if (first!=null) throw first;
        }
    }
    private static <T> T withDisk(Callable<T> operation) {
        StrictMode.ThreadPolicy old=StrictMode.getThreadPolicy();
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder(old).permitDiskReads().permitDiskWrites().build());
        try { return operation.call(); }
        catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException(failure); }
        finally { StrictMode.setThreadPolicy(old); }
    }
}
