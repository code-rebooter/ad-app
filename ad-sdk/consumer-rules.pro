-keepattributes *Annotation*

# Silent consent flow reflects SDK 4.0.0 internal form fields.
-keep class com.google.android.gms.internal.consent_sdk.** { *; }
