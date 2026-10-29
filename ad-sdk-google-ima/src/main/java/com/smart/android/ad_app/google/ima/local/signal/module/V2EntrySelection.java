package com.smart.android.ad_app.google.ima.local.signal.module;
/** The original zzie.run initial branch, separated from the engine/cache work. */
public final class V2EntrySelection {
 private V2EntrySelection(){}
 public static boolean initiallyTryV2(int configuredMode,boolean allowFallback,boolean actualPrimaryJarAndProgramExist){return configuredMode==3&&(actualPrimaryJarAndProgramExist||!allowFallback);}
 public static boolean backgroundWarmAfterCompat(int configuredMode){return configuredMode==3;}
 public static boolean chooseCompatAfterV2Attempt(boolean actualVmReady,boolean allowFallback){return !actualVmReady&&allowFallback;}
}
