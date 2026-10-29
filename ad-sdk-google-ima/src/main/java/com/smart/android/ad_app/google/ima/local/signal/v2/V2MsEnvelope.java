package com.smart.android.ad_app.google.ima.local.signal.v2;

import com.smart.android.ad_app.google.ima.local.portable.Base64Compat;
import java.io.ByteArrayOutputStream;

/** Pinned zzou.zzi framing, independently written; no IMA dependency. */
public final class V2MsEnvelope {
    private V2MsEnvelope() {}
    private static void varint(ByteArrayOutputStream out,long value) {
        while ((value & ~0x7fL) != 0) {out.write(((int)value&0x7f)|0x80);value>>>=7;}
        out.write((int)value);
    }
    public static byte[] serialize(byte[] independentlyGeneratedRawXss) {
        if (independentlyGeneratedRawXss==null) throw new NullPointerException("raw xss result");
        byte[] raw=independentlyGeneratedRawXss.clone();
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(0x0a);varint(out,raw.length);out.write(raw,0,raw.length);
        out.write(0x20);out.write(4);
        return out.toByteArray();
    }
    public static String encode(byte[] independentlyGeneratedRawXss) {
        return Base64Compat.getUrlEncoder().withoutPadding().encodeToString(serialize(independentlyGeneratedRawXss));
    }
}
