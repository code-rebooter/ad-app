# The URL-only bridge calls the pinned native message/data ABI directly.
-keep class com.smart.android.ad_app.google.GoogleImaUrlBridge { *; }
-keep class com.google.ads.interactivemedia.v3.impl.JavaScriptMessage { *; }
-keep class com.google.ads.interactivemedia.v3.impl.JavaScriptMessage$* { *; }
-keep class com.google.ads.interactivemedia.v3.impl.data.zzbu { *; }
-keep class com.google.ads.interactivemedia.v3.impl.data.zzby { *; }
-keep class com.google.ads.interactivemedia.v3.impl.data.zzbx { *; }

-keep class com.smart.android.ad_app.google.GoogleTclTrackingBridge { *; }

-keep class com.smart.android.ad_app.google.GoogleTclFillBridge { *; }
