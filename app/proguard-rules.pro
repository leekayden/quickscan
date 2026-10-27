# ML Kit loads several classes reflectively (ComponentDiscovery → registrars,
# native wrapper init), which R8 strips without explicit keeps.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.android.gms.**
