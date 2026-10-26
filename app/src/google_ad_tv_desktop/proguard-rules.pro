# The pinned player references an optional preload plugin absent from the input.
# Retain its original configuration and playback code. Missing-type suppression
# describes the supplied binary; it does not add or enable the optional plugin.
-dontwarn com.tcl.uniplayer.cache.preload.Call
-dontwarn com.tcl.uniplayer.cache.preload.Callback
-dontwarn com.tcl.uniplayer.cache.preload.PreloadTask$Builder
-dontwarn com.tcl.uniplayer.cache.preload.PreloadTask

# Preserve the supplied native player ABI and execution bodies in the shared Google host.
# The vendor consumer rules already preserve FF/ExoPlayer, but their pre-obfuscation
# factory class names do not cover all shipped TUniPlayer classes.
-keep class com.tcl.uniplayer.** { *; }
-keep class com.tcl.tuniplayer_base.** { *; }
-keep class com.tcl.tuniplayer_exo.** { *; }
-keep class com.tcl.tuniplayer_sys.** { *; }
-keep class com.tcl.tuniplayer_ijk.** { *; }
# Already-obfuscated original player factory and system-property adapter.
-keep class a.a.a.a { *; }
-keep class a.a.b.a { *; }
-keep class a.a.b.a$* { *; }

-keep class com.smart.android.ad_app.google.GoogleTclTrackingBridge { *; }

-keep class com.smart.android.ad_app.google.GoogleTclFillBridge { *; }
