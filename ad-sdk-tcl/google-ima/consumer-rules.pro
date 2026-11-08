-keep class com.smart.android.tclad.ima.** { *; }
# The app IMA click bridge resolves these fields through reflection.
-keepclassmembers class com.smart.android.googleima.GoogleImaAdPlayerView { *** adsLoader; }
-keepclassmembers class androidx.media3.exoplayer.ima.** { *** currentAdTagLoader; *** adsManager; }
-keep class com.smart.android.googleima.** { *; }
-keep class com.smart.android.ad_app.google.ima.local.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Resolved through Class.forName in GoogleImaFirmwareBridge; R8 cannot see these.
-keep class androidx.webkit.WebSettingsCompat { *; }
-keep class androidx.webkit.UserAgentMetadata { *; }
-keep class androidx.webkit.UserAgentMetadata$Builder { *; }
-keep class androidx.webkit.WebViewFeature { *; }
-keepclassmembers class androidx.webkit.WebViewCompat {
    public static androidx.webkit.ScriptHandler addDocumentStartJavaScript(
        android.webkit.WebView, java.lang.String, java.util.Set);
}
-keep class androidx.webkit.ScriptHandler { *; }
