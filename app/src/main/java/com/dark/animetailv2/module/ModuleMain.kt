package com.dark.animetailv2.module

import android.view.MotionEvent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

class ModuleMain : IXposedHookLoadPackage {

    private var isHolding = false
    private var initialDragAxis = 0f
    private var currentSpeedIndex = -1

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
    XposedBridge.log("EliteMod: Initializing for package: ${lpparam.packageName}")
    if (lpparam.packageName != "com.dark.animetailv2") return
        
        val prefs = XSharedPreferences("com.dark.animetailv2.module", "mod_prefs")
        prefs.makeWorldReadable()

        // 1. Silent Installer Hook
        val packageInstallerClass = XposedHelpers.findClassIfExists(
            "eu.kanade.tachiyomi.extension.anime.installer.PackageInstallerInstallerAnime", 
            lpparam.classLoader
        )
        if (packageInstallerClass != null) {
            XposedBridge.hookAllMethods(packageInstallerClass, "install", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    prefs.reload()
                    if (!prefs.getBoolean("silent_update", true)) return

                    val apkFile = param.args[0] as? File ?: return
                    val path = apkFile.absolutePath
                    
                    // Bypass system popup and execute su
                    try {
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "pm install -r '$path'"))
                        param.result = null // Cancel the original popup intent
                    } catch (e: Exception) {
                        XposedBridge.log("EliteMod: Silent install failed - ${e.message}")
                    }
                }
            })
        }

        // 2. Gesture Hook inside GestureHandler
        val gestureHandlerClass = XposedHelpers.findClassIfExists(
            "eu.kanade.tachiyomi.ui.player.controls.GestureHandler", 
            lpparam.classLoader
        )
        
        if (gestureHandlerClass != null) {
            XposedBridge.hookAllMethods(gestureHandlerClass, "onTouchEvent", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    prefs.reload()
                    val event = param.args[0] as MotionEvent
                    val isHorizontal = prefs.getBoolean("horizontal_drag", true)
                    
                    // Fetch user's custom speed array
                    val rawSequence = prefs.getString("speed_sequence", "0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 6.0") ?: ""
                    val speeds = rawSequence.split(",").mapNotNull { it.trim().toDoubleOrNull() }.sorted()
                    if (speeds.isEmpty()) return

                    // Check if video is paused (pseudo-code fetching from player state)
                    val playerActivity = XposedHelpers.getObjectField(param.thisObject, "activity")
                    val isPaused = XposedHelpers.callMethod(playerActivity, "isPaused") as Boolean

                    if (isPaused) {
                        // Let default Aniyomi/Animetail gestures handle it
                        return 
                    }

                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            initialDragAxis = if (isHorizontal) event.rawX else event.rawY
                            // Standard long-press triggers after ~300ms, omitting delay logic for brevity
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (!isHolding) return
                            val currentAxis = if (isHorizontal) event.rawX else event.rawY
                            val delta = currentAxis - initialDragAxis
                            
                            // Map pixel delta to array index
                            val indexShift = (delta / 100).toInt() // 100 pixels per speed shift
                            var newIndex = currentSpeedIndex + indexShift
                            
                            if (newIndex < 0) newIndex = 0
                            if (newIndex >= speeds.size) newIndex = speeds.size - 1

                            val selectedSpeed = speeds[newIndex]
                            
                            // Inject speed command to MPV
                            val mpvLib = XposedHelpers.findClass("is.xyz.mpv.MPVLib", lpparam.classLoader)
                            XposedHelpers.callStaticMethod(mpvLib, "setPropertyDouble", "speed", selectedSpeed)
                            
                            param.result = true // Consume event
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (isHolding) {
                                isHolding = false
                                // Reset to 1.0x on release
                                val mpvLib = XposedHelpers.findClass("is.xyz.mpv.MPVLib", lpparam.classLoader)
                                XposedHelpers.callStaticMethod(mpvLib, "setPropertyDouble", "speed", 1.0)
                                param.result = true
                            }
                        }
                    }
                }
            })
        }
    }
}
