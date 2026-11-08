package com.smart.android.ad_app.google.ima.local.signal.android;
import android.content.Context;import android.content.pm.PackageManager;import android.content.pm.ApkChecksum;import java.util.List;import java.security.cert.Certificate;
/** API31 types exist only in this lazily used bridge, after the original SDK/codename guards. */
@android.annotation.TargetApi(31)
final class Api31Checksums {
 interface Events {void onType8(byte[] bytes);void onUnavailable(boolean listPresent);}
 static void request(Context context,List<Certificate> certs,final Events events)throws PackageManager.NameNotFoundException,java.security.cert.CertificateEncodingException {
  context.getPackageManager().requestChecksums(context.getPackageName(),false,android.content.pm.Checksum.TYPE_WHOLE_SHA256,certs,new PackageManager.OnChecksumsReadyListener(){public void onChecksumsReady(List<ApkChecksum> list){if(list!=null)try{for(ApkChecksum item:list)if(item.getType()==android.content.pm.Checksum.TYPE_WHOLE_SHA256){events.onType8(item.getValue());return;}}catch(Throwable ignored){}events.onUnavailable(list!=null);}});
 }
}
