package com.dark.animetailv2.module

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
    private var savedSpeed = 1.0
    
    private lateinit var handler: Handler
    private var longPressRunnable: Runnable? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!::handler.isInitialized) {
            handler = Handler(Looper.getMainLooper())
        }
        
        XposedBridge.log("EliteMod: Loaded package: ${lpparam.packageName}")
        
        if (lpparam.packageName != "com.dark.animetailv2") return
        
        try {
            XposedBridge.log("EliteMod: Initializing for Animetail process: ${lpparam.processName}")

            val prefs = XSharedPreferences("com.dark.animetailv2.module", "mod_prefs")
            prefs.makeWorldReadable()
            prefs.reload()

            // 1. Silent Installer Hook (Anime)
            val animeInstallerClass = XposedHelpers.findClassIfExists(
                "eu.kanade.tachiyomi.extension.anime.installer.PackageInstallerInstallerAnime", 
                lpparam.classLoader
            )
            if (animeInstallerClass != null) {
                hookInstaller(animeInstallerClass, "eu.kanade.tachiyomi.extension.anime.installer.InstallerAnime\$Entry", lpparam, prefs)
            }

            // 2. Silent Installer Hook (Manga)
            val mangaInstallerClass = XposedHelpers.findClassIfExists(
                "eu.kanade.tachiyomi.extension.manga.installer.PackageInstallerInstallerManga", 
                lpparam.classLoader
            )
            if (mangaInstallerClass != null) {
                hookInstaller(mangaInstallerClass, "eu.kanade.tachiyomi.extension.manga.installer.InstallerManga\$Entry", lpparam, prefs)
            }

            // 3. Gesture Hook (Universal via PlayerActivity)
            val playerActivityClass = XposedHelpers.findClassIfExists(
                "eu.kanade.tachiyomi.ui.player.PlayerActivity", 
                lpparam.classLoader
            )
            
            if (playerActivityClass != null) {
                XposedHelpers.findAndHookMethod(playerActivityClass, "dispatchTouchEvent", MotionEvent::class.java, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val event = param.args[0] as MotionEvent
                        val activity = param.thisObject as Activity
                        
                        prefs.reload()
                        val isHorizontal = prefs.getBoolean("horizontal_drag", true)
                        val rawSequence = prefs.getString("speed_sequence", "0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 6.0") ?: "0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 6.0"
                        val speeds = rawSequence.split(",").mapNotNull { it.trim().toDoubleOrNull() }.sorted()
                        if (speeds.isEmpty()) return

                        val mpv = XposedHelpers.callMethod(activity, "getMpv") ?: return

                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                initialDragAxis = if (isHorizontal) event.x else event.y

                                // Only trigger custom hold gesture if NOT paused
                                val viewModel = XposedHelpers.callMethod(activity, "getViewModel")
                                val pausedFlow = XposedHelpers.getObjectField(viewModel, "paused")
                                val isPaused = XposedHelpers.callMethod(pausedFlow, "getValue") as? Boolean ?: false

                                if (!isPaused) {
                                    longPressRunnable = Runnable {
                                        isHolding = true
                                        savedSpeed = XposedHelpers.callMethod(mpv, "getPropertyDouble", "speed") as? Double ?: 1.0

                                        // Default to 2.0x on hold start if not specified otherwise
                                        val holdSpeed = prefs.getString("hold_speed", "2.0")?.toDoubleOrNull() ?: 2.0
                                        XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", holdSpeed)

                                        // Find closest index in sequence for drag mapping
                                        currentSpeedIndex = speeds.indices.minByOrNull { Math.abs(speeds[it] - holdSpeed) } ?: -1

                                        activity.runOnUiThread {
                                            android.widget.Toast.makeText(activity, "Hold Speed Activated: ${holdSpeed}x", android.widget.Toast.LENGTH_SHORT).show()
                                        }

                                        // Synthesize ACTION_CANCEL to underlying views to prevent UI from getting "stuck"
                                        val cancelEvent = MotionEvent.obtain(event)
                                        cancelEvent.action = MotionEvent.ACTION_CANCEL
                                        XposedBridge.invokeOriginalMethod(param.method, param.thisObject, arrayOf(cancelEvent))
                                        cancelEvent.recycle()
                                    }
                                    handler.postDelayed(longPressRunnable!!, 500)
                                }
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val currentAxis = if (isHorizontal) event.x else event.y
                                if (!isHolding) {
                                    // If moved too much before long press, cancel it
                                    if (Math.abs(currentAxis - initialDragAxis) > 20) {
                                        longPressRunnable?.let { handler.removeCallbacks(it) }
                                    }
                                    return
                                }
                                
                                val delta = currentAxis - initialDragAxis
                                val indexShift = (delta / 150).toInt() // 150 pixels per speed shift
                                var newIndex = currentSpeedIndex + indexShift
                                
                                if (newIndex < 0) newIndex = 0
                                if (newIndex >= speeds.size) newIndex = speeds.size - 1

                                val selectedSpeed = speeds[newIndex]
                                XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", selectedSpeed)
                                
                                param.result = true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                longPressRunnable?.let { handler.removeCallbacks(it) }
                                if (isHolding) {
                                    isHolding = false
                                    XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", savedSpeed)
                                    param.result = true
                                }
                            }
                        }
                    }
                })
            }
        } catch (e: Throwable) {
            XposedBridge.log("EliteMod: Critical error during initialization: ${e.message}")
            XposedBridge.log(e)
        }
    }

    private fun hookInstaller(installerClass: Class<*>, entryClassName: String, lpparam: XC_LoadPackage.LoadPackageParam, prefs: XSharedPreferences) {
        XposedHelpers.findAndHookMethod(installerClass, "processEntry", entryClassName, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                prefs.reload()
                if (!prefs.getBoolean("silent_update", true)) return

                val entry = param.args[0]
                val uri = XposedHelpers.getObjectField(entry, "uri") as Uri
                val service = XposedHelpers.getObjectField(param.thisObject, "service") as Context
                
                try {
                    val inputStream = service.contentResolver.openInputStream(uri) ?: return
                    val tempFile = File(service.cacheDir, "temp_extension.apk")
                    inputStream.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    
                    // Grant root access for installation
                    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "pm install -r ${tempFile.absolutePath}"))
                    process.waitFor()
                    
                    // Cleanup
                    tempFile.delete()

                    // Notify success
                    val installStepClass = XposedHelpers.findClass("eu.kanade.tachiyomi.extension.InstallStep", lpparam.classLoader)
                    val installedStep = XposedHelpers.getStaticObjectField(installStepClass, "Installed")
                    XposedHelpers.callMethod(param.thisObject, "continueQueue", installedStep)
                    
                    param.result = null
                } catch (e: Exception) {
                    XposedBridge.log("EliteMod: Silent install failed - ${e.message}")
                }
            }
        })
    }
}
