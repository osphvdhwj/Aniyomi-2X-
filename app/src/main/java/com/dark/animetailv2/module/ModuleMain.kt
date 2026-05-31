package com.dark.animetailv2.module

import android.app.Activity
import android.app.Dialog
import android.app.PendingIntent
import android.app.RemoteAction
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

enum class GestureMode {
    IDLE,
    LONG_PRESS_PENDING,
    SPEED_HOLD,
    BRIGHTNESS_SWIPE,
    VOLUME_SWIPE,
    DOUBLE_TAP_PENDING,
    SKIP_FIRED
}

class ModuleMain : IXposedHookLoadPackage {

    private var gestureMode = GestureMode.IDLE
    private var initialX = 0f
    private var initialY = 0f
    private var initialDragAxis = 0f
    private var currentSpeedIndex = -1
    private var savedSpeed = 1.0
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var screenWidth = 0
    private var activeZone = "SPEED"
    
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
            XposedBridge.log("EliteMod: Core Rewrite v2.0 Starting")

            // 1. Screenshot Bypass (Global)
            XposedHelpers.findAndHookMethod(Window::class.java, "setFlags", Int::class.java, Int::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val flags = param.args[0] as Int
                    val mask = param.args[1] as Int
                    
                    val activity = (param.thisObject as? Window)?.context as? Activity
                    val prefs = activity?.getSharedPreferences("elite_mod_prefs", Context.MODE_PRIVATE)
                    val bypass = prefs?.getBoolean("screenshot_bypass", true) ?: true

                    if (bypass && (mask and WindowManager.LayoutParams.FLAG_SECURE != 0)) {
                        param.args[0] = flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
                    }
                }
            })

            // 2. Installer Hooks
            val animeInstallerClass = XposedHelpers.findClassIfExists("eu.kanade.tachiyomi.extension.anime.installer.PackageInstallerInstallerAnime", lpparam.classLoader)
            if (animeInstallerClass != null) {
                hookInstaller(animeInstallerClass, "eu.kanade.tachiyomi.extension.anime.installer.InstallerAnime\$Entry", lpparam)
            }
            val mangaInstallerClass = XposedHelpers.findClassIfExists("eu.kanade.tachiyomi.extension.manga.installer.PackageInstallerInstallerManga", lpparam.classLoader)
            if (mangaInstallerClass != null) {
                hookInstaller(mangaInstallerClass, "eu.kanade.tachiyomi.extension.manga.installer.InstallerManga\$Entry", lpparam)
            }

            // 3. Player & PiP Hooks
            val playerActivityClass = XposedHelpers.findClassIfExists("eu.kanade.tachiyomi.ui.player.PlayerActivity", lpparam.classLoader)
            if (playerActivityClass != null) {
                
                XposedHelpers.findAndHookMethod(playerActivityClass, "onCreate", Bundle::class.java, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as Activity
                        createGlassUI(activity)
                        loadSavedSpeedForAnime(activity)
                        
                        if (PendingMediaIntent.isFromExternal) {
                            val externalUri = PendingMediaIntent.consume() ?: return
                            handler.postDelayed({
                                showSaveDialog(activity, externalUri)
                            }, 500)
                        }

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
                        if (gestureMode == GestureMode.SPEED_HOLD) {
                            gestureMode = GestureMode.IDLE
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

                // SAFE PiP Button Append
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
                        } catch (e: Throwable) {
                            XposedBridge.log("EliteMod: PiP Append Failure: ${e.message}")
                        }
                    }
                })
            }

            // 4. Universal Full-Screen Gesture Hook
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

                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            if (gestureMode != GestureMode.SPEED_HOLD) {
                                longPressRunnable?.let { handler.removeCallbacks(it) }
                                longPressRunnable = null
                            }

                            if (screenWidth == 0) {
                                screenWidth = activity.resources.displayMetrics.widthPixels
                            }
                            initialX = event.x
                            initialY = event.y
                            initialDragAxis = if (isHorizontal) event.x else event.y
                            
                            activeZone = when {
                                initialX < screenWidth * 0.35f -> "BRIGHTNESS"
                                initialX > screenWidth * 0.65f -> "VOLUME"
                                else -> "SPEED"
                            }

                            val isPaused = isPlayerPaused(activity)

                            if (activeZone == "SPEED" && !isPaused) {
                                longPressRunnable = Runnable {
                                    longPressRunnable = null
                                    gestureMode = GestureMode.SPEED_HOLD
                                    savedSpeed = XposedHelpers.callMethod(mpv, "getPropertyDouble", "speed") as? Double ?: 1.0
                                    val holdSpeedStr = prefs.getString("hold_speed", "2.0")
                                    val holdSpeed = holdSpeedStr?.toDoubleOrNull() ?: 2.0
                                    
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
                            } else if (activeZone == "BRIGHTNESS") {
                                gestureMode = GestureMode.BRIGHTNESS_SWIPE
                            } else if (activeZone == "VOLUME") {
                                gestureMode = GestureMode.VOLUME_SWIPE
                            }
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (gestureMode == GestureMode.BRIGHTNESS_SWIPE || gestureMode == GestureMode.VOLUME_SWIPE) return
                            
                            val currentAxis = if (isHorizontal) event.x else event.y
                            if (gestureMode != GestureMode.SPEED_HOLD) {
                                if (Math.abs(currentAxis - initialDragAxis) > 80) {
                                    longPressRunnable?.let { handler.removeCallbacks(it) }
                                    longPressRunnable = null
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
                            longPressRunnable = null
                            
                            if (gestureMode == GestureMode.SPEED_HOLD) {
                                gestureMode = GestureMode.IDLE
                                XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", savedSpeed)
                                saveSpeedForAnime(activity, savedSpeed)
                                vibrate(activity, 30)
                                handler.postDelayed({ vibrate(activity, 20) }, 50)
                                hideSpeedOverlay()
                                param.result = true
                            }
                            gestureMode = GestureMode.IDLE
                            activeZone = "SPEED"
                        }
                    }
                }
            })
        } catch (e: Throwable) {
            XposedBridge.log("EliteMod: CRITICAL ERROR: ${e.message}")
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
        sb.append("[").append(current).append("x]")
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
                    setColor(Color.parseColor("#66000000")) 
                    setStroke(3, Color.parseColor("#33FFFFFF"))
                }
                background = shape
                setPadding(60, 20, 60, 20)
                
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
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
                
                if (Build.VERSION.SDK_INT >= 21) {
                    elevation = 25f
                }
            }
            root.addView(glassPill)
        }
    }

    private fun showSpeedOverlay(text: String) {
        handler.post {
            pillText?.text = text
            glassPill?.apply {
                visibility = View.VISIBLE
                animate().alpha(1f).scaleX(if (text.contains("[")) 1.3f else 1.0f).scaleY(if (text.contains("[")) 1.15f else 1.0f)
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
            val id = XposedHelpers.callMethod(anime, "getId") as? Long ?: return "unknown"
            id.toString()
        } catch (e: Throwable) { "unknown" }
    }

    private fun loadSavedSpeedForAnime(activity: Activity) {
        val animeId = getAnimeId(activity)
        if (animeId == "unknown") return
        val prefs = activity.getSharedPreferences("elite_mod_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("per_show_speed", true)) return
        val speedMap = JSONObject(prefs.getString("speed_memory", "{}"))
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
        val speedMap = JSONObject(prefs.getString("speed_memory", "{}"))
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

    private fun showSaveDialog(activity: Activity, uri: Uri) {
        val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 60, 60, 60)
            val gd = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1A22"))
                setCornerRadii(floatArrayOf(60f, 60f, 60f, 60f, 0f, 0f, 0f, 0f))
            }
            background = gd
        }

        val filename = getFileName(activity, uri)
        root.addView(TextView(activity).apply { text = "🎬 $filename"; setTextColor(Color.WHITE); textSize = 18f; setTypeface(null, Typeface.BOLD) })

        root.addView(View(activity).apply { layoutParams = LinearLayout.LayoutParams(-1, 2).apply { setMargins(0, 30, 0, 30) }; setBackgroundColor(Color.parseColor("#2A2A38")) })

        val animeInput = createDialogInput(activity, "Anime Name", parseAnimeTitle(filename))
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        val epInput = createDialogInput(activity, "Episode", parseEpisode(filename)).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        val seaInput = createDialogInput(activity, "Season", parseSeason(filename)).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        
        row.addView(epInput)
        row.addView(seaInput)
        root.addView(animeInput)
        root.addView(row)

        val btnRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 40, 0, 0) }
        btnRow.addView(Button(activity).apply { text = "Just Play"; setTextColor(Color.parseColor("#7A7A9A")); background = null; setOnClickListener { dialog.dismiss() } })
        btnRow.addView(Button(activity).apply { text = "Save & Open"; val gd = GradientDrawable().apply { setColor(Color.parseColor("#7C6FE0")); cornerRadius = 20f }; background = gd; setTextColor(Color.WHITE); setOnClickListener { saveToLibrary(activity, uri, animeInput.text.toString(), epInput.text.toString(), seaInput.text.toString()); dialog.dismiss() } })

        dialog.setContentView(root)
        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.window?.setLayout(-1, -2)
        dialog.show()
    }

    private fun createDialogInput(c: Context, hint: String, text: String) = EditText(c).apply {
        setHint(hint)
        setText(text)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.parseColor("#7A7A9A"))
        setPadding(30, 30, 30, 30)
        val gd = GradientDrawable().apply { setColor(Color.parseColor("#252530")); cornerRadius = 15f }
        background = gd
    }

    private fun getFileName(c: Context, uri: Uri): String {
        c.contentResolver.query(uri, null, null, null, null)?.use { if (it.moveToFirst()) return it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME)) }
        return uri.lastPathSegment ?: "Unknown file"
    }

    private fun parseAnimeTitle(f: String): String {
        var t = f.substringBeforeLast(".").replace(Regex("\\[.*?\\]"), "").replace(Regex("S\\d+E\\d+", RegexOption.IGNORE_CASE), "")
        listOf("1080p", "720p", "480p", "4K", "x264", "x265", "HEVC", "BluRay").forEach { t = t.replace(it, "", true) }
        return t.replace(Regex("[._-]"), " ").replace(Regex("\\s+"), " ").trim().split(" ").joinToString(" ") { it.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() } }.ifEmpty { "Unknown Anime" }
    }

    private fun parseEpisode(f: String) = Regex("S\\d+E(\\d+)", RegexOption.IGNORE_CASE).find(f)?.groupValues?.get(1) ?: Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE).find(f)?.groupValues?.get(1) ?: Regex("Ep\\s*(\\d+)", RegexOption.IGNORE_CASE).find(f)?.groupValues?.get(1) ?: Regex("\\b(\\d{2,4})\\b").find(f)?.groupValues?.get(1) ?: ""

    private fun parseSeason(f: String) = Regex("S(\\d+)", RegexOption.IGNORE_CASE).find(f)?.groupValues?.get(1) ?: Regex("Season\\s*(\\d+)", RegexOption.IGNORE_CASE).find(f)?.groupValues?.get(1) ?: ""

    private fun saveToLibrary(activity: Activity, uri: Uri, name: String, ep: String, sea: String) {
        val path = if (uri.scheme == "content") activity.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: uri.toString() else uri.path ?: uri.toString()
        val localDir = "/storage/emulated/0/Animetail/local/anime/$name"
        val linkPath = "$localDir/$name - Episode ${ep.ifEmpty{"1"}}.${path.substringAfterLast(".")}"
        try { Runtime.getRuntime().exec(arrayOf("su", "-c", "mkdir -p \"$localDir\" && ln -sf \"$path\" \"$linkPath\"")).waitFor()
            Toast.makeText(activity, "Linked to Library: $name", Toast.LENGTH_LONG).show() } catch(e: Exception) {}
    }
}
