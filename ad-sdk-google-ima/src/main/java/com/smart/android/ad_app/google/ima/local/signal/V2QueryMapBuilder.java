package com.smart.android.ad_app.google.ima.local.signal;

import java.util.HashMap;
import java.util.Map;

/** Independent zzjd.zzb + zzou.zzc query map assembly for pinned IMA 3.35.1.
 * Inputs must be the independent runtime's real primitive/lifecycle providers;
 * the SDK's completed map or signal result is never a generation input.
 */
public final class V2QueryMapBuilder {
    private V2QueryMapBuilder() {}
    public static final class Options {
        public final String clientVersion;
        public final boolean googlePlayServicesAvailable;
        public final boolean shouldGetAdvertisingId;
        public Options(String version,boolean gms,boolean advertisingId) {
            if(version==null)throw new NullPointerException("clientVersion");
            clientVersion=version;googlePlayServicesAvailable=gms;shouldGetAdvertisingId=advertisingId;
        }
    }
    public static final class AdvertisingId {
        public final String id;
        public final int source;
        public final boolean limited;
        public AdvertisingId(String id,int source,boolean limited){
            if(id==null)throw new NullPointerException("AdsId DTO getter returns a nonnull string");
            this.id=id;this.source=source;this.limited=limited;
        }
        public static AdvertisingId unresolved(){return new AdvertisingId("",2,false);}
    }
    /** Getter order below matches source order; providers preserve real task snapshots. */
    public interface Sources {
        String gassDigestOrUnresolvedE();
        AdvertisingId advertisingIdOrUnresolved();
        boolean userPresentBroadcastState();
        Throwable captureThrowable();
        boolean timingTrackerPresent();
        long queryCurrentUptime(); long queryPreviousUptime();
        long viewCurrentUptime(); long viewPreviousUptime();
        long viewWithViewCurrentUptime(); long viewWithViewPreviousUptime();
        long clickCurrentUptime(); long clickPreviousUptime();
        boolean networkTrackerPresent(); long networkType();
        boolean vpnTrackerPresent(); long activeVpnDuration(); long consumeCompletedVpnDuration();
    }
    public static Map<String,Object> buildQueryMap(Options options,Sources sources,Object invocationContext) {
        Map<String,Object> result=new HashMap<>();
        // zzjd.zze snapshots zzok.zzb before inserting v/gms.
        String integrity=sources.gassDigestOrUnresolvedE();
        result.put("v",options.clientVersion);
        result.put("gms",options.googlePlayServicesAvailable);
        result.put("int",integrity);
        result.put("up",sources.userPresentBroadcastState());
        result.put("t",sources.captureThrowable());
        if(sources.timingTrackerPresent()) {
            result.put("tcq",sources.queryCurrentUptime());
            result.put("tpq",sources.queryPreviousUptime());
            result.put("tcv",sources.viewCurrentUptime());
            result.put("tpv",sources.viewPreviousUptime());
            result.put("tchv",sources.viewWithViewCurrentUptime());
            result.put("tphv",sources.viewWithViewPreviousUptime());
            result.put("tcc",sources.clickCurrentUptime());
            result.put("tpc",sources.clickPreviousUptime());
        }
        // zzjd.zzb snapshots zzok.zza exactly once; it does not wait for completion.
        AdvertisingId advertising=sources.advertisingIdOrUnresolved();
        result.put("gai",options.shouldGetAdvertisingId);
        result.put("did",advertising.id);
        result.put("dst",advertising.source);
        result.put("doo",advertising.limited);
        if(sources.networkTrackerPresent())result.put("nt",sources.networkType());
        if(sources.vpnTrackerPresent()) {
            result.put("vs",sources.activeVpnDuration());
            result.put("vf",sources.consumeCompletedVpnDuration());
        }
        result.put("f","q");result.put("ctx",invocationContext);result.put("aid",null);
        return result;
    }
}
