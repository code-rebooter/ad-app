package com.smart.android.ad_app.google.ima.local.signal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One explicit resolution snapshot; managers and later requests may change their inputs. */
public final class ResolvedFlags {
    public final boolean zzmj_zzp, zzmj_zzq, zzmj_zzt, zzmj_zzx, zzmj_zzw, zzmj_zzA, zzmj_zzd, zzmj_zze, zzmj_zzv, zzmj_zzy, zzmj_zzz, zzif_zza, zzai_zzf, zzai_zzg, zzai_zzh, zzjj_zzp, zzjj_zzr, zzjj_zzs;
    public final int zzmj_zzr, zzai_zza;
    public final long zzmj_zzk;
    public final String zzm_zze, zzmj_zzf, zzmj_zzg;
    public final Map<String,String> provenance;
    public static ResolvedFlags resolve(FlagsInputs.Provider provider) { return new ResolvedFlags(provider); }
    private ResolvedFlags(FlagsInputs.Provider provider) {
        Map<String,String> origins=new LinkedHashMap<>();
        zzmj_zzp=FlagsInputs.require(provider,"zzmj.zzp",Boolean.class,origins);
        zzmj_zzq=FlagsInputs.require(provider,"zzmj.zzq",Boolean.class,origins);
        zzmj_zzt=FlagsInputs.require(provider,"zzmj.zzt",Boolean.class,origins);
        zzmj_zzx=FlagsInputs.require(provider,"zzmj.zzx",Boolean.class,origins);
        zzmj_zzw=FlagsInputs.require(provider,"zzmj.zzw",Boolean.class,origins);
        zzmj_zzA=FlagsInputs.require(provider,"zzmj.zzA",Boolean.class,origins);
        zzmj_zzd=FlagsInputs.require(provider,"zzmj.zzd",Boolean.class,origins);
        zzmj_zze=FlagsInputs.require(provider,"zzmj.zze",Boolean.class,origins);
        zzmj_zzv=FlagsInputs.require(provider,"zzmj.zzv",Boolean.class,origins);
        zzmj_zzy=FlagsInputs.require(provider,"zzmj.zzy",Boolean.class,origins);
        zzmj_zzz=FlagsInputs.require(provider,"zzmj.zzz",Boolean.class,origins);
        zzif_zza=FlagsInputs.require(provider,"zzif.zza",Boolean.class,origins);
        zzai_zzf=FlagsInputs.require(provider,"zzai.zzf",Boolean.class,origins);
        zzai_zzg=FlagsInputs.require(provider,"zzai.zzg",Boolean.class,origins);
        zzai_zzh=FlagsInputs.require(provider,"zzai.zzh",Boolean.class,origins);
        zzjj_zzp=FlagsInputs.require(provider,"zzjj.zzp",Boolean.class,origins);
        zzjj_zzr=FlagsInputs.require(provider,"zzjj.zzr",Boolean.class,origins);
        zzjj_zzs=FlagsInputs.require(provider,"zzjj.zzs",Boolean.class,origins);
        zzmj_zzr=FlagsInputs.require(provider,"zzmj.zzr",Integer.class,origins);
        zzai_zza=FlagsInputs.require(provider,"zzai.zza",Integer.class,origins);
        zzmj_zzk=FlagsInputs.require(provider,"zzmj.zzk",Long.class,origins);
        zzm_zze=FlagsInputs.require(provider,"zzm.zze",String.class,origins);
        zzmj_zzf=FlagsInputs.require(provider,"zzmj.zzf",String.class,origins);
        zzmj_zzg=FlagsInputs.require(provider,"zzmj.zzg",String.class,origins);
        provenance=Collections.unmodifiableMap(origins);
    }
}
