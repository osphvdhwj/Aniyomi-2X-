package com.dark.animetailv2.module

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.*
import android.util.Rational
import android.view.*
import android.view.animation.OvershootInterpolator
import android.widget.*
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.io.File

class ModuleMain : IXposedHookLoadPackage {

    private var isHolding = false
    private var initialDragAxis = 0f
    private var currentSpeedIndex = -1
    private var savedSpeed = 1.0
    private var lastTapTime = 0L
    
    private lateinit var handler: Handler
    private var longPressRunnable: Runnable? = null
    
    private var glassPill: LinearLayout? = null
    private var pillText: TextView? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!::handler.isInitialized) {
            handler = Handler(Looper.getMainLooper())
        }
        
        if (lpparam.packageName != "com.dark.animetailv2") return
        
        try {
            XposedBridge.log("EliteMod: Core Rewrite v2.1 Starting")

            // 1. Screenshot Bypass (Global)
            XposedHelpers.findAndHookMethod(Window::class.java, "setFlags", Int::class.java, Int::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val flags = param.args[0] as Int
                    val mask = param.args[1] as Int
                    val activity = (param.thisObject as? Window)?.context as? Activity
                    val prefs = activity?.getSharedPreferences("elite_mod_prefs", Context.MODE_PRIVATE)
                    if (prefs?.getBoolean("screenshot_bypass", true) ?: true) {
                        if (mask and WindowManager.LayoutParams.FLAG_SECURE != 0) {
                            param.args[0] = flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
                        }
                    }
                }
            })

            // 2. Player Hooks
            val playerActivityClass = XposedHelpers.findClassIfExists("eu.kanade.tachiyomi.ui.player.PlayerActivity", lpparam.classLoader)
            if (playerActivityClass != null) {
                
                XposedHelpers.findAndHookMethod(playerActivityClass, "onCreate", Bundle::class.java, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as Activity
                        createGlassUI(activity)
                        loadSavedSpeedForAnime(activity)

                        val filter = IntentFilter()
                        filter.addAction("ELITE_MOD_PIP_CYCLE")
                        filter.addAction("ELITE_MOD_PIP_FWD")
                        filter.addAction("ELITE_MOD_PIP_BWD")

                        val receiver = object : BroadcastReceiver() {
                            override fun onReceive(context: Context?, intent: Intent?) {
                                val mpv = try { XposedHelpers.callMethod(activity, "getMpv") } catch (e: Throwable) { null } ?: return
                                val prefs = activity.getSharedPreferences("elite_mod_prefs", Context.MODE_PRIVATE)
                                when (intent?.action) {
                                    "ELITE_MOD_PIP_CYCLE" -> {
                                        val speeds = getSpeeds(prefs)
                                        val current = XposedHelpers.callMethod(mpv, "getPropertyDouble", "speed") as? Double ?: 1.0
                                        var idx = speeds.indices.minByOrNull { Math.abs(speeds[it] - current) } ?: 0
                                        idx = (idx + 1) % speeds.size
                                        val next = speeds[idx]
                                        XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", next)
                                        saveSpeedForAnime(activity, next)
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

                XposedHelpers.findAndHookMethod(playerActivityClass, "onPause", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (isHolding) {
                            isHolding = false
                            longPressRunnable?.let { handler.removeCallbacks(it) }
                            longPressRunnable = null
                            val activity = param.thisObject as Activity
                            val mpv = try { XposedHelpers.callMethod(activity, "getMpv") } catch (e: Throwable) { null }
                            if (mpv != null) {
                                XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", savedSpeed)
                                saveSpeedForAnime(activity, savedSpeed)
                            }
                            hideSpeedOverlay()
                        }
                    }
                })

                // PiP Button Append
                XposedHelpers.findAndHookMethod(playerActivityClass, "createPipParams", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
                        val activity = param.thisObject as Activity
                        val originalParams = param.result as? android.app.PictureInPictureParams ?: return
                        
                        try {
                            val originalActions = XposedHelpers.callMethod(originalParams, "getActions") as? List<RemoteAction> ?: emptyList()
                            val newActions = ArrayList<RemoteAction>(originalActions)
                            
                            val piCycle = PendingIntent.getBroadcast(activity, 101, Intent("ELITE_MOD_PIP_CYCLE").apply { `package` = activity.packageName }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                            val piBwd = PendingIntent.getBroadcast(activity, 102, Intent("ELITE_MOD_PIP_BWD").apply { `package` = activity.packageName }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                            val piFwd = PendingIntent.getBroadcast(activity, 103, Intent("ELITE_MOD_PIP_FWD").apply { `package` = activity.packageName }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

                            newActions.add(RemoteAction(Icon.createWithResource("android", android.R.drawable.ic_media_ff), "Speed", "Cycle Speed", piCycle))
                            newActions.add(RemoteAction(Icon.createWithResource("android", android.R.drawable.ic_media_previous), "-10s", "Rewind", piBwd))
                            newActions.add(RemoteAction(Icon.createWithResource("android", android.R.drawable.ic_media_next), "+10s", "Forward", piFwd))

                            val builder = android.app.PictureInPictureParams.Builder()
                            builder.setActions(newActions)
                            
                            val rect = try { XposedHelpers.callMethod(originalParams, "getSourceRectHint") as? Rect } catch(e: Throwable) { null }
                            if (rect != null) builder.setSourceRectHint(rect)
                            
                            val ratio = try { XposedHelpers.callMethod(originalParams, "getAspectRatio") as? Rational } catch(e: Throwable) { null }
                            if (ratio != null) builder.setAspectRatio(ratio)

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                val autoPip = try { XposedHelpers.callMethod(originalParams, "isAutoEnterEnabled") as? Boolean ?: false } catch(e: Throwable) { false }
                                builder.setAutoEnterEnabled(autoPip)
                            }

                            param.result = builder.build()
                        } catch (e: Throwable) {}
                    }
                })
            }

            // 3. Universal Gesture Hook (Simplified, Full Screen)
            XposedHelpers.findAndHookMethod(Activity::class.java, "dispatchTouchEvent", MotionEvent::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    if (activity.javaClass.name != "eu.kanade.tachiyomi.ui.player.PlayerActivity") return
                    
                    val event = param.args[0] as MotionEvent
                    val prefs = activity.getSharedPreferences("elite_mod_prefs", Context.MODE_PRIVATE)
                    
                    val isHorizontal = prefs.getBoolean("horizontal_drag", true)
                    val speeds = getSpeeds(prefs)
                    val sensitivity = try { (prefs.getString("drag_sensitivity", "100") ?: "100").toDouble() } catch(e: Exception) { 100.0 }
                    val holdDelay = try { (prefs.getString("hold_delay", "400") ?: "400").toLong() } catch(e: Exception) { 400L }

                    val mpv = try { XposedHelpers.callMethod(activity, "getMpv") } catch (e: Throwable) { null } ?: return

                    if (activity.isInPictureInPictureMode) {
                        // Double Tap to Skip in PiP
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            val now = System.currentTimeMillis()
                            if (now - lastTapTime < 300) {
                                val width = activity.window.decorView.width
                                val skipSec = try { (prefs.getString("skip_duration", "10") ?: "10").toInt() } catch(e: Exception) { 10 }
                                val rewind = event.x < width / 2
                                XposedHelpers.callMethod(mpv, "command", arrayOf("seek", if(rewind) "-$skipSec" else "+$skipSec"))
                                vibrate(activity, 50)
                                showSpeedOverlay(if(rewind) "⏪ −${skipSec}s" else "⏩ +${skipSec}s")
                                lastTapTime = 0L
                                param.result = true
                                return
                            }
                            lastTapTime = now
                        }
                        return
                    }

                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            longPressRunnable?.let { handler.removeCallbacks(it) }
                            initialDragAxis = if (isHorizontal) event.x else event.y
                            
                            val isPaused = isPlayerPaused(activity)
                            if (!isPaused) {
                                longPressRunnable = Runnable {
                                    isHolding = true
                                    savedSpeed = XposedHelpers.callMethod(mpv, "getPropertyDouble", "speed") as? Double ?: 1.0
                                    val holdSpeed = prefs.getString("hold_speed", "2.0")?.toDoubleOrNull() ?: 2.0
                                    XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", holdSpeed)
                                    vibrate(activity, 35)
                                    currentSpeedIndex = speeds.indices.minByOrNull { Math.abs(speeds[it] - holdSpeed) } ?: -1
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

                            if (newIndex != currentSpeedIndex) {
                                vibrate(activity, 8)
                                val selectedSpeed = speeds[newIndex]
                                XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", selectedSpeed)
                                showSpeedOverlay(buildSequenceText(speeds, newIndex))
                            }
                            currentSpeedIndex = newIndex
                            param.result = true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            longPressRunnable?.let { handler.removeCallbacks(it) }
                            if (isHolding) {
                                isHolding = false
                                XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", savedSpeed)
                                saveSpeedForAnime(activity, savedSpeed)
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

    private fun getSpeeds(prefs: android.content.SharedPreferences): List<Double> {
        val raw = prefs.getString("speed_sequence", "0.1, 0.5, 1.0, 2.0, 3.5, 4.0, 6.0, 10.0") ?: ""
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
        if (index > 1) sb.append(".. ")
        if (index > 0) sb.append("${speeds[index-1]}  ")
        sb.append(current).append("x") // Removed brackets
        if (index < speeds.size - 1) sb.append("  ${speeds[index+1]}")
        if (index < speeds.size - 2) sb.append(" ..")
        return sb.toString()
    }

    private fun createGlassUI(activity: Activity) {
        handler.post {
            val root = activity.window.decorView as ViewGroup
            glassPill = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                visibility = View.GONE
                alpha = 0f
                val shape = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 100f
                    setColor(Color.parseColor("#44000000")) // Liquid Glass
                    setStroke(2, Color.parseColor("#22FFFFFF"))
                }
                background = shape
                setPadding(60, 20, 60, 20)
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    topMargin = 50
                }
                val icon = ImageView(activity).apply {
                    setImageResource(android.R.drawable.ic_media_ff)
                    setColorFilter(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(50, 50).apply { rightMargin = 20 }
                }
                addView(icon)
                pillText = TextView(activity).apply {
                    setTextColor(Color.WHITE)
                    textSize = 17f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                addView(pillText)
            }
            root.addView(glassPill)
        }
    }

    private fun showSpeedOverlay(text: String) {
        handler.post {
            pillText?.text = text
            glassPill?.apply {
                visibility = View.VISIBLE
                animate().alpha(1f).scaleX(if (text.contains("  ")) 1.3f else 1.0f).scaleY(if (text.contains("  ")) 1.1f else 1.0f)
                    .setInterpolator(OvershootInterpolator()).setDuration(250).start()
                bringToFront()
            }
        }
    }

    private fun hideSpeedOverlay() {
        handler.post {
            glassPill?.animate()?.alpha(0f)?.scaleX(0.8f)?.scaleY(0.8f)?.setDuration(400)
                ?.withEndAction { glassPill?.visibility = View.GONE }?.start()
        }
    }

    private fun vibrate(activity: Activity, durationMs: Long) {
        val prefs = activity.getSharedPreferences("elite_mod_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("haptic_feedback", false)) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val v = activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Throwable) {}
    }

    private fun getAnimeId(activity: Activity): String {
        return try {
            val viewModel = XposedHelpers.callMethod(activity, "getViewModel")
            val anime = XposedHelpers.callMethod(viewModel, "getAnime")
            (XposedHelpers.callMethod(anime, "getId") as Long).toString()
        } catch (e: Throwable) { "unknown" }
    }

    private fun loadSavedSpeedForAnime(activity: Activity) {
        val animeId = getAnimeId(activity)
        if (animeId == "unknown") return
        val prefs = activity.getSharedPreferences("elite_mod_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("per_show_speed", true)) return
        val speedMap = JSONObject(prefs.getString("speed_memory", "{}") ?: "{}")
        if (speedMap.has(animeId)) {
            val speed = speedMap.getDouble(animeId)
            val mpv = try { XposedHelpers.callMethod(activity, "getMpv") } catch (e: Throwable) { null } ?: return
            XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", speed)
            handler.postDelayed({ showSpeedOverlay("↺ ${speed}x"); handler.postDelayed({ hideSpeedOverlay() }, 1200) }, 800)
        }
    }

    private fun saveSpeedForAnime(activity: Activity, speed: Double) {
        val animeId = getAnimeId(activity)
        if (animeId == "unknown") return
        val prefs = activity.getSharedPreferences("elite_mod_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("per_show_speed", true)) return
        val speedMap = JSONObject(prefs.getString("speed_memory", "{}") ?: "{}")
        speedMap.put(animeId, speed)
        prefs.edit().putString("speed_memory", speedMap.toString()).apply()
    }

    private fun hookInstaller(installerClass: Class<*>, entryClassName: String, lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(installerClass, "processEntry", entryClassName, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val context = param.thisObject as? Context ?: return
                val prefs = context.getSharedPreferences("elite_mod_prefs", Context.MODE_PRIVATE)
                if (!prefs.getBoolean("silent_update", true)) return
                val entry = param.args[0]
                val uri = XposedHelpers.getObjectField(entry, "uri") as Uri
                try {
                    val inputStream = context.contentResolver.openInputStream(uri) ?: return
                    val tempFile = File(context.cacheDir, "temp_mod_ext.apk")
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
