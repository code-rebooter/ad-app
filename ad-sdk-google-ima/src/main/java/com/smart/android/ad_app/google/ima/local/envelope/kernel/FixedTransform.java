package com.smart.android.ad_app.google.ima.local.envelope.kernel;

/** Fixed 256-byte transform only; no entropy, Android API, or SDK import. */
public final class FixedTransform {
    private FixedTransform() {}
    public static byte[] transform(byte[] framedBlock) {
        if (framedBlock == null || framedBlock.length != 256)
            throw new IllegalArgumentException("Exactly 256 framed input bytes required");
        byte[] input = framedBlock.clone();
        byte[] output = new byte[256];
        zzht state = new zzht();
        for (zzgu stage : state.zzcG) stage.zza(input, output);
        return output;
    }
}
