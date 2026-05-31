# Keep Xposed entry point
-keep public class com.dark.animetailv2.module.ModuleMain {
    public <init>();
}

# Keep Xposed API
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**

# Keep Kotlin metadata
-keepattributes RuntimeVisibleAnnotations
-keep class kotlin.Metadata { *; }

# Keep UI and System classes used by reflection
-keep class android.app.AlertDialog { *; }
-keep class android.media.AudioManager { *; }
-keep class android.app.NotificationManager { *; }
-keep class android.view.animation.OvershootInterpolator { *; }
-keep class android.app.RemoteAction { *; }
-keep class android.app.PictureInPictureParams { *; }
-keep class android.app.PendingIntent { *; }

# Audio and brightness
-keep class android.view.WindowManager$LayoutParams {
    float screenBrightness;
}

# JSON for speed memory
-keep class org.json.JSONObject { *; }

# Content resolver for file open feature
-keep class android.provider.OpenableColumns { *; }
-keep class android.provider.MediaStore$MediaColumns { *; }

# Our new classes
-keep class com.dark.animetailv2.module.PendingMediaIntent { *; }
-keep class com.dark.animetailv2.module.MediaForwarderActivity { *; }
-keep class com.dark.animetailv2.module.GestureMode { *; }

# Optimize
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*
