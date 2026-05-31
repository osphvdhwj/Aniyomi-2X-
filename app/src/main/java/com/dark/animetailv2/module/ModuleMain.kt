package com.dark.animetailv2.module

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Rational
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.*
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.io.File
import java.util.*

class ModuleMain : IXposedHookLoadPackage {

    private var isHolding = false
    private var initialDragX = 0f
    private var initialDragY = 0f
    private var startingSpeedIndex = -1
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
            XposedBridge.log("EliteMod: Initializing v2.3 Polish")

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
                        
                        val prefs = activity.getSharedPreferences("elite_mod_prefs", Context.MODE_PRIVATE)
                        loadSavedSpeedForAnime(activity, prefs)

                        val filter = IntentFilter()
                        filter.addAction("ELITE_MOD_PIP_PREV")
                        filter.addAction("ELITE_MOD_PIP_NEXT")
                        filter.addAction("ELITE_MOD_PIP_BWD")
                        filter.addAction("ELITE_MOD_PIP_FWD")

                        val receiver = object : BroadcastReceiver() {
                            override fun onReceive(context: Context?, intent: Intent?) {
                                val viewModel = try { XposedHelpers.callMethod(activity, "getViewModel") } catch (e: Throwable) { null } ?: return
                                val mpv = try { XposedHelpers.callMethod(activity, "getMpv") } catch (e: Throwable) { null } ?: return
                                
                                when (intent?.action) {
                                    "ELITE_MOD_PIP_PREV" -> XposedHelpers.callMethod(viewModel, "changeEpisode", false, false)
                                    "ELITE_MOD_PIP_NEXT" -> XposedHelpers.callMethod(viewModel, "changeEpisode", true, false)
                                    "ELITE_MOD_PIP_BWD" -> XposedHelpers.callMethod(mpv, "command", arrayOf("seek", "-10"))
                                    "ELITE_MOD_PIP_FWD" -> XposedHelpers.callMethod(mpv, "command", arrayOf("seek", "10"))
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

                // SAFE PiP Button Re-Ordering and Smartness
                XposedHelpers.findAndHookMethod(playerActivityClass, "createPipParams", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
                        val activity = param.thisObject as Activity
                        
                        try {
                            val viewModel = XposedHelpers.callMethod(activity, "getViewModel")
                            val hasNext = try {
                                val flow = XposedHelpers.getObjectField(viewModel, "hasNextEpisode")
                                XposedHelpers.callMethod(flow, "getValue") as? Boolean ?: false
                            } catch(e: Throwable) { false }
                            val hasPrev = try {
                                val flow = XposedHelpers.getObjectField(viewModel, "hasPreviousEpisode")
                                XposedHelpers.callMethod(flow, "getValue") as? Boolean ?: false
                            } catch(e: Throwable) { false }

                            val builder = android.app.PictureInPictureParams.Builder()
                            val actions = ArrayList<RemoteAction>()

                            // 1. Previous Episode (Order: Left)
                            val intentPrev = Intent("ELITE_MOD_PIP_PREV").apply { `package` = activity.packageName }
                            val piPrev = PendingIntent.getBroadcast(activity, 101, intentPrev, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                            val iconPrev = Icon.createWithResource("android", android.R.drawable.ic_media_previous)
                            val actionPrev = RemoteAction(iconPrev, "Previous", "Prev Episode", piPrev)
                            actionPrev.isEnabled = hasPrev
                            actions.add(actionPrev)

                            // 2. Seek Back -10
                            val piBwd = PendingIntent.getBroadcast(activity, 102, Intent("ELITE_MOD_PIP_BWD").apply { `package` = activity.packageName }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                            actions.add(RemoteAction(Icon.createWithResource("android", android.R.drawable.ic_media_rew), "-10s", "Seek Back", piBwd))

                            // 3. Seek Forward +10
                            val piFwd = PendingIntent.getBroadcast(activity, 103, Intent("ELITE_MOD_PIP_FWD").apply { `package` = activity.packageName }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                            actions.add(RemoteAction(Icon.createWithResource("android", android.R.drawable.ic_media_ff), "+10s", "Seek Fwd", piFwd))

                            // 4. Next Episode (Order: Right)
                            val intentNext = Intent("ELITE_MOD_PIP_NEXT").apply { `package` = activity.packageName }
                            val piNext = PendingIntent.getBroadcast(activity, 104, intentNext, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                            val iconNext = Icon.createWithResource("android", android.R.drawable.ic_media_next)
                            val actionNext = RemoteAction(iconNext, "Next", "Next Episode", piNext)
                            actionNext.isEnabled = hasNext
                            actions.add(actionNext)

                            builder.setActions(actions)
                            param.result = builder.build()
                        } catch (e: Throwable) {}
                    }
                })
            }

            // 3. Universal Gesture Hook (v2.3 Polish)
            XposedHelpers.findAndHookMethod(Activity::class.java, "dispatchTouchEvent", MotionEvent::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    if (activity.javaClass.name != "eu.kanade.tachiyomi.ui.player.PlayerActivity") return
                    
                    val event = param.args[0] as MotionEvent
                    val prefs = activity.getSharedPreferences("elite_mod_prefs", Context.MODE_PRIVATE)
                    val mpv = try { XposedHelpers.callMethod(activity, "getMpv") } catch (e: Throwable) { null } ?: return

                    // PiP Double Tap Skip
                    if (activity.isInPictureInPictureMode) {
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            val now = System.currentTimeMillis()
                            if (now - lastTapTime < 300) {
                                val width = activity.window.decorView.width
                                val rewind = event.x < width / 2
                                XposedHelpers.callMethod(mpv, "command", arrayOf("seek", if(rewind) "-10" else "10"))
                                lastTapTime = 0L
                                param.result = true
                                return
                            }
                            lastTapTime = now
                        }
                        // Allow Hold for 2x in PiP if OS allows
                    }

                    val isHorizontal = prefs.getBoolean("horizontal_drag", true)
                    val speeds = getSpeeds(prefs)
                    val sensitivity = try { (prefs.getString("drag_sensitivity", "100") ?: "100").toDouble() } catch(e: Exception) { 100.0 }
                    val holdDelay = try { (prefs.getString("hold_delay", "400") ?: "400").toLong() } catch(e: Exception) { 400L }

                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            longPressRunnable?.let { handler.removeCallbacks(it) }
                            initialDragX = event.x
                            initialDragY = event.y
                            
                            val isPaused = isPlayerPaused(activity)
                            if (!isPaused) {
                                longPressRunnable = Runnable {
                                    isHolding = true
                                    savedSpeed = XposedHelpers.callMethod(mpv, "getPropertyDouble", "speed") as? Double ?: 1.0
                                    val holdSpeedStr = prefs.getString("hold_speed", "2.0")
                                    val holdSpeed = holdSpeedStr?.toDoubleOrNull() ?: 2.0
                                    
                                    XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", holdSpeed)
                                    startingSpeedIndex = speeds.indices.minByOrNull { Math.abs(speeds[it] - holdSpeed) } ?: 0
                                    initialDragX = event.x // Reset anchor on hold start
                                    initialDragY = event.y
                                    
                                    showSpeedOverlay("${holdSpeed}x", prefs)

                                    val cancelEvent = MotionEvent.obtain(event)
                                    cancelEvent.action = MotionEvent.ACTION_CANCEL
                                    XposedBridge.invokeOriginalMethod(param.method, param.thisObject, arrayOf(cancelEvent))
                                    cancelEvent.recycle()
                                }
                                handler.postDelayed(longPressRunnable!!, holdDelay)
                            }
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (!isHolding) {
                                if (Math.abs(event.x - initialDragX) > 80 || Math.abs(event.y - initialDragY) > 80) {
                                    longPressRunnable?.let { handler.removeCallbacks(it) }
                                }
                                return
                            }
                            
                            val deltaX = event.x - initialDragX
                            val deltaY = event.y - initialDragY
                            
                            // STRICT DIRECTIONAL CHECK & DEADZONE
                            val delta = if (isHorizontal) deltaX else deltaY
                            val otherDelta = if (isHorizontal) deltaY else deltaX
                            
                            if (Math.abs(delta) < 60) return // Deadzone
                            if (Math.abs(otherDelta) > Math.abs(delta) * 0.8) return // Ignore diagonal/wrong axis
                            
                            val indexShift = (delta / sensitivity).toInt()
                            var newIndex = startingSpeedIndex + indexShift
                            newIndex = newIndex.coerceIn(0, speeds.size - 1)

                            val selectedSpeed = speeds[newIndex]
                            val currentSpeed = XposedHelpers.callMethod(mpv, "getPropertyDouble", "speed") as? Double ?: 1.0
                            
                            if (Math.abs(selectedSpeed - currentSpeed) > 0.01) {
                                XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", selectedSpeed)
                                showSpeedOverlay(buildSequenceText(speeds, newIndex), prefs)
                            }
                            param.result = true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            longPressRunnable?.let { handler.removeCallbacks(it) }
                            if (isHolding) {
                                isHolding = false
                                XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", savedSpeed)
                                saveSpeedForAnime(activity, savedSpeed, prefs)
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
        sb.append(current).append("x") // Removed brackets and >>
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
                    setColor(Color.parseColor("#44000000")) 
                    setStroke(2, Color.parseColor("#22FFFFFF"))
                }
                background = shape
                setPadding(60, 20, 60, 20)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    topMargin = 20 // Closer to top
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

    private fun showSpeedOverlay(text: String, prefs: android.content.SharedPreferences) {
        handler.post {
            val scaleVal = (prefs.getString("pill_scale", "100")?.toFloatOrNull() ?: 100f) / 100f
            pillText?.text = text
            glassPill?.apply {
                visibility = View.VISIBLE
                scaleX = scaleVal
                scaleY = scaleVal
                if (text.contains("  ")) {
                    animate().alpha(1f).scaleX(scaleVal * 1.3f).scaleY(scaleVal * 1.1f)
                        .setInterpolator(OvershootInterpolator()).setDuration(250).start()
                } else {
                    animate().alpha(1f).scaleX(scaleVal).scaleY(scaleVal).setDuration(200).start()
                }
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

    private fun loadSavedSpeedForAnime(activity: Activity, prefs: android.content.SharedPreferences) {
        val animeId = getAnimeId(activity)
        if (animeId == "unknown") return
        if (!prefs.getBoolean("per_show_speed", true)) return
        val speedMap = JSONObject(prefs.getString("speed_memory", "{}") ?: "{}")
        if (speedMap.has(animeId)) {
            val speed = speedMap.getDouble(animeId)
            val mpv = try { XposedHelpers.callMethod(activity, "getMpv") } catch (e: Throwable) { null } ?: return
            XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", speed)
            handler.postDelayed({ showSpeedOverlay("↺ ${speed}x", prefs); handler.postDelayed({ hideSpeedOverlay() }, 1200) }, 800)
        }
    }

    private fun saveSpeedForAnime(activity: Activity, speed: Double, prefs: android.content.SharedPreferences) {
        val animeId = getAnimeId(activity)
        if (animeId == "unknown") return
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
                    val installedStep = XposedHelpers.getStaticObjectField(XposedHelpers.findClass("eu.kanade.tachiyomi.extension.InstallStep", lpparam.classLoader), "Installed")
                    XposedHelpers.callMethod(param.thisObject, "continueQueue", installedStep)
                    param.result = null
                } catch (e: Exception) {}
            }
        })
    }
}
