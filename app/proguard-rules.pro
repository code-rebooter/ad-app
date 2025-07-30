# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile


# Viewbinding 如果用的反射就会有混淆问题，需要加入混淆
-keep class * implements androidx.viewbinding.ViewBinding {
    *;
}
-keep class com.smart.android.ad_app.bean.** { *; }


-keep class com.tcl.ff.component.vastad.**{*;}
#xstream
-keep class com.thoughtworks.xstream.**{*;}

#Sad1.0.9的混淆
-keep class com.seraphic.ad.** { *; }
-keep interface com.seraphic.ad.** { *; }
-keepclassmembers class com.seraphic.ad.** { *; }