package com.smart.android.ad_app.google.ima.local.signal.android;
import java.security.SecureRandom;

/** IMA-owned process entropy with no JNI or external library dependency. */
public final class ImaLocalEntropy {
 private static final SecureRandom RANDOM=new SecureRandom();
 private ImaLocalEntropy(){}
 public static synchronized byte[] sample32(){byte[] result=new byte[32];RANDOM.nextBytes(result);return result;}
}
