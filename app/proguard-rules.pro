# Keep Xposed entry point
-keep public class com.dark.animetailv2.module.ModuleMain {
    public <init>();
}

# Keep Xposed API
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**

# Keep Kotlin metadata if needed (optional for Xposed but good for stability)
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Optimize even more
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*
