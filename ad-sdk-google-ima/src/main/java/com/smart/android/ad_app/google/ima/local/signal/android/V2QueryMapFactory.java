package com.smart.android.ad_app.google.ima.local.signal.android;

import android.content.Context;
import com.smart.android.ad_app.google.ima.local.signal.V2QueryMapBuilder;
import java.util.Map;
import java.util.concurrent.Executor;

/** Own one factory for the source lifetime, before independent native init.
 * RPC should return the real Map object from query(), keeping Context/Throwable
 * objects as live Java handles rather than serializing/reconstructing them.
 */
public final class V2QueryMapFactory implements AutoCloseable {
    private final V2QueryMapBuilder.Options options;
    private final V2QueryLiveSources sources;
    private final String constructorProvenance;
    public V2QueryMapFactory(Context actualSdkContext,int effectiveSdk,Executor executor,
            V2QueryMapBuilder.Options options,boolean timingFlag,boolean networkFlag,
            boolean vpnFlag,String constructorProvenance) {
        if(constructorProvenance==null||constructorProvenance.isEmpty())
            throw new IllegalArgumentException("actual upstream options/flag provenance is required");
        this.options=options;this.constructorProvenance=constructorProvenance;
        sources=new V2QueryLiveSources(actualSdkContext,effectiveSdk,executor,options,
                                      timingFlag,networkFlag,vpnFlag);
    }
    public Map<String,Object> query(Context invocationContext,boolean currentTimingFlag) {
        if(invocationContext==null)throw new NullPointerException("actual request context");
        beforeQuery(currentTimingFlag);
        return buildMapOnly(invocationContext);
    }
    /** zzhx updates VPN and timing before maybeRefresh and before map reads. */
    public void beforeQuery(boolean currentTimingFlag){sources.beforeQuery(currentTimingFlag);}
    public Map<String,Object> buildMapOnly(Context invocationContext){if(invocationContext==null)throw new NullPointerException("actual request context");return V2QueryMapBuilder.buildQueryMap(options,sources,invocationContext);}
    public V2QueryLiveSources liveSources(){return sources;}
    public String constructorProvenance(){return constructorProvenance;}
    @Override public void close(){sources.close();}
}
