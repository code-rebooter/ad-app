package com.smart.android.ad_app.google.ima.local.signal.module;
import java.io.*;import java.security.*;import java.util.*;
/** Exact pinned zzno signer policy after independent complete APK-v2 verification. */
public final class ModuleCertificateVerifier {
 private static final byte[] RELEASE=V2ModuleMetadata.unhex("3d7a1223019aa39d9ea0e3436ab7c0896bfb4fb679f4de5fe7c23f326c8f994a");
 private static final byte[] DEBUG=V2ModuleMetadata.unhex("92f3de46ad2b6115d410ca83e4c78381f91166bb7487b12bf3783a37e3945f53");
 private final byte[] release,debug;
 public ModuleCertificateVerifier(){this(RELEASE,DEBUG);}
 /** Explicit constructor makes the trust input visible to source-contract audits. */
 public ModuleCertificateVerifier(byte[] actualReleaseDigest,byte[] actualDebugDigest){release=actualReleaseDigest.clone();debug=actualDebugDigest.clone();}
 public boolean verify(File module,String actualBuildType)throws GeneralSecurityException{
  List<List<byte[]>> signers;try{signers=ApkV2SignatureVerifier.verify(module);}catch(ApkV2SignatureVerifier.NotSigned e){throw new GeneralSecurityException("Package is not signed",e);}catch(IOException e){throw new GeneralSecurityException("Failed to verify signatures",e);}catch(RuntimeException e){throw new GeneralSecurityException("Failed to verify signatures",e);}
  if(signers.size()!=1)throw new GeneralSecurityException("APK has more than one signature.");byte[] hash=MessageDigest.getInstance("SHA-256").digest(signers.get(0).get(0));return Arrays.equals(release,hash)||!"user".equals(actualBuildType)&&Arrays.equals(debug,hash);
 }
}
