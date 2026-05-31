package com.dark.animetailv2.module

import android.app.Activity
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
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
    
    private var speedOverlay: TextView? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!::handler.isInitialized) {
            handler = Handler(Looper.getMainLooper())
        }
        
        if (lpparam.packageName != "com.dark.animetailv2") return
        
        try {
            XposedBridge.log("EliteMod: Initializing for Animetail")

            val prefs = XSharedPreferences("com.dark.animetailv2.module", "mod_prefs")
            prefs.makeWorldReadable()
            prefs.reload()

            // 1. Silent Installer Hook
            val animeInstallerClass = XposedHelpers.findClassIfExists("eu.kanade.tachiyomi.extension.anime.installer.PackageInstallerInstallerAnime", lpparam.classLoader)
            if (animeInstallerClass != null) {
                hookInstaller(animeInstallerClass, "eu.kanade.tachiyomi.extension.anime.installer.InstallerAnime\$Entry", lpparam, prefs)
            }
            val mangaInstallerClass = XposedHelpers.findClassIfExists("eu.kanade.tachiyomi.extension.manga.installer.PackageInstallerInstallerManga", lpparam.classLoader)
            if (mangaInstallerClass != null) {
                hookInstaller(mangaInstallerClass, "eu.kanade.tachiyomi.extension.manga.installer.InstallerManga\$Entry", lpparam, prefs)
            }

            // 2. Player Hooks
            val playerActivityClass = XposedHelpers.findClassIfExists("eu.kanade.tachiyomi.ui.player.PlayerActivity", lpparam.classLoader)
            if (playerActivityClass != null) {
                
                // UI Injection: Create the Sleek Speed Overlay
                XposedHelpers.findAndHookMethod(playerActivityClass, "onCreate", Bundle::class.java, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as Activity
                        val root = activity.window.decorView as ViewGroup
                        
                        speedOverlay = TextView(activity).apply {
                            setTextColor(Color.WHITE)
                            textSize = 16f
                            gravity = Gravity.CENTER
                            visibility = View.GONE
                            
                            val shape = GradientDrawable().apply {
                                shape = GradientDrawable.RECTANGLE
                                cornerRadius = 50f
                                setColor(Color.parseColor("#AA000000")) // Semi-transparent black
                            }
                            background = shape
                            setPadding(40, 20, 40, 20)
                            
                            val layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply {
                                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                                topMargin = 100
                            }
                            this.layoutParams = layoutParams
                        }
                        root.addView(speedOverlay)

                        // PiP Receiver
                        val filter = IntentFilter("ELITE_MOD_PIP_2X")
                        val receiver = object : BroadcastReceiver() {
                            override fun onReceive(context: Context?, intent: Intent?) {
                                val mpv = XposedHelpers.callMethod(activity, "getMpv") ?: return
                                val currentSpeed = XposedHelpers.callMethod(mpv, "getPropertyDouble", "speed") as? Double ?: 1.0
                                val newSpeed = if (currentSpeed >= 2.0) 1.0 else 2.0
                                XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", newSpeed)
                                showSpeedOverlay("${newSpeed}x")
                            }
                        }
                        activity.registerReceiver(receiver, filter)
                    }
                })

                // Auto-2x when entering PiP
                XposedHelpers.findAndHookMethod(playerActivityClass, "onPictureInPictureModeChanged", Boolean::class.java, "android.content.res.Configuration", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val isPip = param.args[0] as Boolean
                        if (isPip) {
                            prefs.reload()
                            if (prefs.getBoolean("pip_2x", false)) {
                                val activity = param.thisObject as Activity
                                val mpv = XposedHelpers.callMethod(activity, "getMpv") ?: return
                                XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", 2.0)
                            }
                        }
                    }
                })

                // Add "2x" Button to PiP
                XposedHelpers.findAndHookMethod(playerActivityClass, "createPipParams", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
                        val activity = param.thisObject as Activity
                        val result = param.result ?: return
                        try {
                            val actions = XposedHelpers.callMethod(result, "getActions") as? MutableList<RemoteAction> ?: ArrayList()
                            val intent = Intent("ELITE_MOD_PIP_2X")
                            val pendingIntent = PendingIntent.getBroadcast(activity, 99, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                            val icon = Icon.createWithResource("android", android.R.drawable.ic_media_ff)
                            actions.add(RemoteAction(icon, "Toggle 2x", "Toggle 2x Speed", pendingIntent))
                        } catch (e: Throwable) {}
                    }
                })
            }

            // 3. Universal Gesture Hook (Activity.dispatchTouchEvent)
            XposedHelpers.findAndHookMethod(Activity::class.java, "dispatchTouchEvent", MotionEvent::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    if (activity.javaClass.name != "eu.kanade.tachiyomi.ui.player.PlayerActivity") return
                    
                    val event = param.args[0] as MotionEvent
                    prefs.reload()
                    val isHorizontal = prefs.getBoolean("horizontal_drag", true)
                    val rawSequence = prefs.getString("speed_sequence", "0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 6.0") ?: "0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 6.0"
                    val speeds = rawSequence.split(",").mapNotNull { it.trim().toDoubleOrNull() }.sorted()
                    if (speeds.isEmpty()) return

                    val mpv = try { XposedHelpers.callMethod(activity, "getMpv") } catch (e: Throwable) { null } ?: return

                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            initialDragAxis = if (isHorizontal) event.x else event.y
                            val isPaused = try {
                                val viewModel = XposedHelpers.callMethod(activity, "getViewModel")
                                val pausedFlow = XposedHelpers.getObjectField(viewModel, "paused")
                                XposedHelpers.callMethod(pausedFlow, "getValue") as? Boolean ?: false
                            } catch (e: Throwable) { false }

                            if (!isPaused) {
                                longPressRunnable = Runnable {
                                    isHolding = true
                                    savedSpeed = XposedHelpers.callMethod(mpv, "getPropertyDouble", "speed") as? Double ?: 1.0
                                    val holdSpeed = prefs.getString("hold_speed", "2.0")?.toDoubleOrNull() ?: 2.0
                                    XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", holdSpeed)
                                    currentSpeedIndex = speeds.indices.minByOrNull { Math.abs(speeds[it] - holdSpeed) } ?: -1
                                    
                                    showSpeedOverlay("${holdSpeed}x >>")

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
                                if (Math.abs(currentAxis - initialDragAxis) > 50) {
                                    longPressRunnable?.let { handler.removeCallbacks(it) }
                                }
                                return
                            }
                            
                            val delta = currentAxis - initialDragAxis
                            val indexShift = (delta / 100).toInt()
                            var newIndex = currentSpeedIndex + indexShift
                            
                            if (newIndex < 0) newIndex = 0
                            if (newIndex >= speeds.size) newIndex = speeds.size - 1

                            val selectedSpeed = speeds[newIndex]
                            XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", selectedSpeed)
                            showSpeedOverlay("${selectedSpeed}x")
                            param.result = true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            longPressRunnable?.let { handler.removeCallbacks(it) }
                            if (isHolding) {
                                isHolding = false
                                XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", savedSpeed)
                                hideSpeedOverlay()
                                param.result = true
                            }
                        }
                    }
                }
            })
        } catch (e: Throwable) {
            XposedBridge.log("EliteMod: Critical error: ${e.message}")
        }
    }

    private fun showSpeedOverlay(text: String) {
        handler.post {
            speedOverlay?.apply {
                this.text = text
                visibility = View.VISIBLE
                alpha = 1f
            }
        }
    }

    private fun hideSpeedOverlay() {
        handler.post {
            speedOverlay?.animate()?.alpha(0f)?.setDuration(300)?.withEndAction {
                speedOverlay?.visibility = View.GONE
            }?.start()
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
                    inputStream.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "pm install -r ${tempFile.absolutePath}")).waitFor()
                    tempFile.delete()
                    val installStepClass = XposedHelpers.findClass("eu.kanade.tachiyomi.extension.InstallStep", lpparam.classLoader)
                    val installedStep = XposedHelpers.getStaticObjectField(installStepClass, "Installed")
                    XposedHelpers.callMethod(param.thisObject, "continueQueue", installedStep)
                    param.result = null
                } catch (e: Exception) { XposedBridge.log("EliteMod: Silent install failed - ${e.message}") }
            }
        })
    }
}
