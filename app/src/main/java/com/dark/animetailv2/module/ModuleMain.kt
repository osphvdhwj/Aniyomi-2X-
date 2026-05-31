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

    // PREFERENCE KEYS
    private val PREF_HOLD_SPEED = "hold_speed"
    private val PREF_SEQUENCE = "speed_sequence"
    private val PREF_HOLD_DELAY = "hold_delay"
    private val PREF_SENSITIVITY = "drag_sensitivity"
    private val PREF_PIP_2X = "pip_2x"

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!::handler.isInitialized) {
            handler = Handler(Looper.getMainLooper())
        }
        
        if (lpparam.packageName != "com.dark.animetailv2") return
        
        try {
            XposedBridge.log("EliteMod: Initializing v1.2 Extreme")

            // REFINED PREFS LOADING (Try to bypass XSharedPreferences cache issues)
            val prefs = XSharedPreferences("com.dark.animetailv2.module", "mod_prefs")
            prefs.makeWorldReadable()
            
            // 1. Installer Hooks
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
                
                XposedHelpers.findAndHookMethod(playerActivityClass, "onCreate", Bundle::class.java, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as Activity
                        createOverlay(activity)

                        val filter = IntentFilter()
                        filter.addAction("ELITE_MOD_PIP_CYCLE")
                        filter.addAction("ELITE_MOD_PIP_FWD")
                        filter.addAction("ELITE_MOD_PIP_BWD")

                        val receiver = object : BroadcastReceiver() {
                            override fun onReceive(context: Context?, intent: Intent?) {
                                val mpv = try { XposedHelpers.callMethod(activity, "getMpv") } catch (e: Throwable) { null } ?: return
                                when (intent?.action) {
                                    "ELITE_MOD_PIP_CYCLE" -> {
                                        prefs.reload()
                                        val speeds = getSpeeds(prefs)
                                        val current = XposedHelpers.callMethod(mpv, "getPropertyDouble", "speed") as? Double ?: 1.0
                                        var idx = speeds.indices.minByOrNull { Math.abs(speeds[it] - current) } ?: 0
                                        idx = (idx + 1) % speeds.size
                                        val next = speeds[idx]
                                        XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", next)
                                        showSpeedOverlay("${next}x")
                                    }
                                    "ELITE_MOD_PIP_FWD" -> XposedHelpers.callMethod(mpv, "command", arrayOf("seek", "10"))
                                    "ELITE_MOD_PIP_BWD" -> XposedHelpers.callMethod(mpv, "command", arrayOf("seek", "-10"))
                                }
                            }
                        }
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            activity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                        } else {
                            activity.registerReceiver(receiver, filter)
                        }
                    }
                })

                // Ultimate PiP Enhancement: Force Custom Actions
                XposedHelpers.findAndHookMethod(playerActivityClass, "createPipParams", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
                        val activity = param.thisObject as Activity
                        
                        try {
                            val builder = android.app.PictureInPictureParams.Builder()
                            val actions = ArrayList<RemoteAction>()

                            // 1. Cycle Speed Button
                            val piCycle = PendingIntent.getBroadcast(activity, 101, Intent("ELITE_MOD_PIP_CYCLE").apply { `package` = activity.packageName }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                            actions.add(RemoteAction(Icon.createWithResource("android", android.R.drawable.ic_media_ff), "Speed", "Cycle Speed", piCycle))

                            // 2. Seek -10
                            val piBwd = PendingIntent.getBroadcast(activity, 102, Intent("ELITE_MOD_PIP_BWD").apply { `package` = activity.packageName }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                            actions.add(RemoteAction(Icon.createWithResource("android", android.R.drawable.ic_media_previous), "-10s", "Seek Back", piBwd))

                            // 3. Seek +10
                            val piFwd = PendingIntent.getBroadcast(activity, 103, Intent("ELITE_MOD_PIP_FWD").apply { `package` = activity.packageName }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                            actions.add(RemoteAction(Icon.createWithResource("android", android.R.drawable.ic_media_next), "+10s", "Seek Forward", piFwd))

                            builder.setActions(actions)
                            param.result = builder.build()
                        } catch (e: Throwable) {
                            XposedBridge.log("EliteMod: PiP Builder Error: ${e.message}")
                        }
                    }
                })
            }

            // 3. Universal Gesture Hook (Full Screen)
            XposedHelpers.findAndHookMethod(Activity::class.java, "dispatchTouchEvent", MotionEvent::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    if (activity.javaClass.name != "eu.kanade.tachiyomi.ui.player.PlayerActivity") return
                    
                    val event = param.args[0] as MotionEvent
                    
                    // CRITICAL: Reload prefs to ensure settings take effect
                    prefs.reload()
                    val isHorizontal = prefs.getBoolean("horizontal_drag", true)
                    val speeds = getSpeeds(prefs)
                    val sensitivity = try { (prefs.getString(PREF_SENSITIVITY, "100") ?: "100").toDouble() } catch(e: Exception) { 100.0 }
                    val holdDelay = try { (prefs.getString(PREF_HOLD_DELAY, "400") ?: "400").toLong() } catch(e: Exception) { 400L }

                    val mpv = try { XposedHelpers.callMethod(activity, "getMpv") } catch (e: Throwable) { null } ?: return

                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            initialDragAxis = if (isHorizontal) event.x else event.y
                            val isPaused = isPlayerPaused(activity)

                            if (!isPaused) {
                                longPressRunnable = Runnable {
                                    isHolding = true
                                    savedSpeed = XposedHelpers.callMethod(mpv, "getPropertyDouble", "speed") as? Double ?: 1.0
                                    val holdSpeedStr = prefs.getString(PREF_HOLD_SPEED, "2.0")
                                    val holdSpeed = holdSpeedStr?.toDoubleOrNull() ?: 2.0
                                    
                                    XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", holdSpeed)
                                    currentSpeedIndex = speeds.indices.minByOrNull { Math.abs(speeds[idx(it, speeds)] - holdSpeed) } ?: -1
                                    
                                    showSpeedOverlay("${holdSpeed}x")

                                    val cancelEvent = MotionEvent.obtain(event)
                                    cancelEvent.action = MotionEvent.ACTION_CANCEL
                                    XposedBridge.invokeOriginalMethod(param.method, param.thisObject, arrayOf(cancelEvent))
                                    cancelEvent.recycle()
                                }
                                handler.postDelayed(longPressRunnable!!, holdDelay)
                            }
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val currentAxis = if (isHorizontal) event.x else event.y
                            if (!isHolding) {
                                if (Math.abs(currentAxis - initialDragAxis) > 80) {
                                    longPressRunnable?.let { handler.removeCallbacks(it) }
                                }
                                return
                            }
                            
                            val delta = currentAxis - initialDragAxis
                            val indexShift = (delta / sensitivity).toInt()
                            var newIndex = currentSpeedIndex + indexShift
                            
                            if (newIndex < 0) newIndex = 0
                            if (newIndex >= speeds.size) newIndex = speeds.size - 1

                            val selectedSpeed = speeds[newIndex]
                            XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", selectedSpeed)
                            
                            showSpeedOverlay(buildSequenceText(speeds, newIndex))
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

    private fun idx(i: Int, list: List<Double>) = i // Helper

    private fun getSpeeds(prefs: XSharedPreferences): List<Double> {
        val raw = prefs.getString(PREF_SEQUENCE, "0.1, 0.5, 1.0, 2.0, 3.5, 4.0, 6.0, 10.0") ?: ""
        return raw.split(",").mapNotNull { it.trim().toDoubleOrNull() }.sorted()
    }

    private fun isPlayerPaused(activity: Activity): Boolean {
        return try {
            val viewModel = XposedHelpers.callMethod(activity, "getViewModel")
            val pausedFlow = XposedHelpers.getObjectField(viewModel, "paused")
            XposedHelpers.callMethod(pausedFlow, "getValue") as? Boolean ?: false
        } catch (e: Throwable) { false }
    }

    private fun buildSequenceText(speeds: List<Double>, index: Int): String {
        val current = speeds[index]
        val sb = StringBuilder()
        
        // Expansion UI Logic
        if (index > 1) sb.append(".. ")
        if (index > 0) sb.append("${speeds[index-1]}  ")
        
        sb.append("[").append(current).append("x]")
        
        if (index < speeds.size - 1) sb.append("  ${speeds[index+1]}")
        if (index < speeds.size - 2) sb.append(" ..")
        
        return sb.toString()
    }

    private fun createOverlay(activity: Activity) {
        handler.post {
            val root = activity.window.decorView as ViewGroup
            speedOverlay = TextView(activity).apply {
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.CENTER
                visibility = View.GONE
                
                val shape = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 100f
                    // LIQUID GLASS EFFECT
                    setColor(Color.parseColor("#33000000")) 
                    setStroke(2, Color.parseColor("#22FFFFFF"))
                }
                background = shape
                setPadding(50, 15, 50, 15)
                
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    topMargin = 60 // Higher placement
                }
            }
            root.addView(speedOverlay)
        }
    }

    private fun showSpeedOverlay(text: String) {
        handler.post {
            speedOverlay?.apply {
                this.text = text
                visibility = View.VISIBLE
                alpha = 1f
                bringToFront()
                // Expansion animation
                val isExp = text.contains("..") || text.contains(" ")
                animate().scaleX(if (isExp) 1.25f else 1.0f).setDuration(150).start()
            }
        }
    }

    private fun hideSpeedOverlay() {
        handler.post {
            speedOverlay?.animate()?.alpha(0f)?.scaleX(1.0f)?.setDuration(400)?.withEndAction {
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
                    val tempFile = File(service.cacheDir, "temp_mod_ext.apk")
                    inputStream.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "pm install -r ${tempFile.absolutePath}")).waitFor()
                    tempFile.delete()
                    val installStepClass = XposedHelpers.findClass("eu.kanade.tachiyomi.extension.InstallStep", lpparam.classLoader)
                    val installedStep = XposedHelpers.getStaticObjectField(installStepClass, "Installed")
                    XposedHelpers.callMethod(param.thisObject, "continueQueue", installedStep)
                    param.result = null
                } catch (e: Exception) {}
            }
        })
    }
}
