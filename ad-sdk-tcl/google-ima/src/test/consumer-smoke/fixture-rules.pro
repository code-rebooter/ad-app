# Passive cleanup inspection used only by this validation host.
-keepclassmembers class com.smart.android.tclad.Controller {
    private boolean nativeReleased;
    private com.tcl.ff.component.vastad.Controller original;
    private static com.smart.android.tclad.Controller owner;
}
-keepclassmembers class com.smart.android.tclad.ima.GoogleImaAdManager {
    private com.smart.android.tclad.ima.GoogleImaAdManager$Session current;
}

# Actual-volume controls and passive probes used only by the audio R8 fixture.
-keepclassmembers class com.smart.android.googleima.GoogleImaAdPlayerView { *** player; }
-keepclassmembers class androidx.media3.** {
    void setVolume(float);
    float getVolume();
    void setPlayWhenReady(boolean);
}
