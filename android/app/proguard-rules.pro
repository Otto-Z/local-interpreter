# JNI entry points: class and method names are referenced from C++.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.localinterpreter.tablet.llm.LlamaNative { *; }
-keep class com.localinterpreter.tablet.llm.LlamaNative$** { *; }
-keepclassmembers interface com.localinterpreter.tablet.llm.LlamaNative$TokenCallback {
    void onToken(java.lang.String);
}
-keep class com.localinterpreter.tablet.llm.LlamaNative$TokenCallback { *; }

# PDFBox-Android uses reflection for fonts and filters.
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.bouncycastle.**
-dontwarn javax.**
-dontwarn java.awt.**
