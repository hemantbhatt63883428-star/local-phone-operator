-keep class com.google.ai.edge.litertlm.** { *; }
-keepclasseswithmembers class * {
    @com.google.ai.edge.litertlm.Tool <methods>;
}
-dontwarn com.google.ai.edge.litertlm.**
