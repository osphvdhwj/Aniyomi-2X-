package com.dark.animetailv2.module

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.OpenableColumns
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
    private var collapseRunnable: Runnable? = null
    
    private var glassPill: LinearLayout? = null
    private var pillText: TextView? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!::handler.isInitialized) {
            handler = Handler(Looper.getMainLooper())
        }
        
        if (lpparam.packageName != "com.dark.animetailv2") return
        
        try {
            XposedBridge.log("EliteMod: Initializing v2.6.1 Definitive")

            // 1. Screenshot Bypass
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

            // 2. Speed Button Overrider
            val speedBtnLambda = "eu.kanade.tachiyomi.ui.player.controls.BottomLeftPlayerControlsKt\$\$ExternalSyntheticLambda0"
            XposedHelpers.findAndHookMethod(speedBtnLambda, "invoke", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val classId = XposedHelpers.getIntField(param.thisObject, "\$r8\$classId")
                    if (classId != 0) return 

                    param.result = Unit.INSTANCE 

                    val currentSpeed = XposedHelpers.getFloatField(param.thisObject, "f$0")
                    val onSpeedChange = XposedHelpers.getObjectField(param.thisObject, "f$1") as Function1<Float, Unit>
                    val playerPrefs = XposedHelpers.getObjectField(param.thisObject, "f$2")
                    val context = XposedHelpers.getObjectField(playerPrefs, "preferenceStore")
                        .let { XposedHelpers.getObjectField(it, "context") as Context }
                    
                    val prefs = context.getSharedPreferences("elite_mod_prefs", Context.MODE_PRIVATE)
                    val raw = prefs.getString("button_cycle_sequence", "0.25, 0.5, 1.0, 1.25, 1.5, 1.75, 2.0") ?: ""
                    val list = raw.split(",").mapNotNull { it.trim().toFloatOrNull() }.sorted()
                    
                    if (list.isEmpty()) return

                    var idx = list.indices.minByOrNull { Math.abs(list[it] - currentSpeed) } ?: 0
                    idx = (idx + 1) % list.size
                    val nextSpeed = list[idx]

                    onSpeedChange.invoke(nextSpeed)
                    
                    val store = XposedHelpers.getObjectField(playerPrefs, "preferenceStore")
                    val pref = XposedHelpers.callMethod(store, "getFloat", "pref_player_speed", 1.0f)
                    XposedHelpers.callMethod(pref, "set", nextSpeed)
                }
            })

            // 3. Player Hooks
            val playerActivityClass = XposedHelpers.findClassIfExists("eu.kanade.tachiyomi.ui.player.PlayerActivity", lpparam.classLoader)
            if (playerActivityClass != null) {
                
                XposedHelpers.findAndHookMethod(playerActivityClass, "onCreate", Bundle::class.java, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as Activity
                        val prefs = activity.getSharedPreferences("elite_mod_prefs", Context.MODE_PRIVATE)
                        createGlassUI(activity, prefs)
                        loadSavedSpeedForAnime(activity, prefs)

                        if (PendingMediaIntent.isFromExternal) {
                            val uri = PendingMediaIntent.consume()
                            if (uri != null) handler.postDelayed({ showSaveDialog(activity, uri) }, 500)
                        }

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

                XposedHelpers.findAndHookMethod(playerActivityClass, "onPause", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (isHolding) {
                            isHolding = false; longPressRunnable?.let { handler.removeCallbacks(it) }; longPressRunnable = null
                            val activity = param.thisObject as Activity
                            val mpv = try { XposedHelpers.callMethod(activity, "getMpv") } catch (e: Throwable) { null }
                            if (mpv != null) {
                                XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", savedSpeed)
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
                        val originalParams = param.result as? android.app.PictureInPictureParams
                        
                        try {
                            val viewModel = XposedHelpers.callMethod(activity, "getViewModel")
                            val hasNext = XposedHelpers.callMethod(XposedHelpers.getObjectField(viewModel, "hasNextEpisode"), "getValue") as? Boolean ?: false
                            val hasPrev = XposedHelpers.callMethod(XposedHelpers.getObjectField(viewModel, "hasPreviousEpisode"), "getValue") as? Boolean ?: false

                            // Preserve existing actions
                            val originalActions = if (originalParams != null) {
                                XposedHelpers.callMethod(originalParams, "getActions") as? List<RemoteAction> ?: emptyList()
                            } else emptyList()
                            
                            val actions = ArrayList<RemoteAction>(originalActions)
                            
                            val piPrev = PendingIntent.getBroadcast(activity, 101, Intent("ELITE_MOD_PIP_PREV").apply { `package` = activity.packageName }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                            val actionPrev = RemoteAction(Icon.createWithResource("android", android.R.drawable.ic_media_previous), "Prev", "Prev Episode", piPrev)
                            actionPrev.isEnabled = hasPrev
                            actions.add(actionPrev)

                            val piBwd = PendingIntent.getBroadcast(activity, 102, Intent("ELITE_MOD_PIP_BWD").apply { `package` = activity.packageName }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                            actions.add(RemoteAction(Icon.createWithResource("android", android.R.drawable.ic_media_rew), "-10s", "Rewind", piBwd))

                            val piFwd = PendingIntent.getBroadcast(activity, 103, Intent("ELITE_MOD_PIP_FWD").apply { `package` = activity.packageName }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                            actions.add(RemoteAction(Icon.createWithResource("android", android.R.drawable.ic_media_ff), "+10s", "Forward", piFwd))

                            val piNext = PendingIntent.getBroadcast(activity, 104, Intent("ELITE_MOD_PIP_NEXT").apply { `package` = activity.packageName }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                            val actionNext = RemoteAction(Icon.createWithResource("android", android.R.drawable.ic_media_next), "Next", "Next Episode", piNext)
                            actionNext.isEnabled = hasNext
                            actions.add(actionNext)

                            param.result = android.app.PictureInPictureParams.Builder().setActions(actions).build()
                        } catch (e: Throwable) {}
                    }
                })
            }

            // 4. Universal Gesture Hook
            XposedHelpers.findAndHookMethod(Activity::class.java, "dispatchTouchEvent", MotionEvent::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    if (activity.javaClass.name != "eu.kanade.tachiyomi.ui.player.PlayerActivity") return
                    val event = param.args[0] as MotionEvent
                    val prefs = activity.getSharedPreferences("elite_mod_prefs", Context.MODE_PRIVATE)
                    val mpv = try { XposedHelpers.callMethod(activity, "getMpv") } catch (e: Throwable) { null } ?: return

                    if (activity.isInPictureInPictureMode) {
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            val now = System.currentTimeMillis()
                            if (now - lastTapTime < 300) {
                                // Double Tap Play/Pause in PiP
                                XposedHelpers.callMethod(mpv, "command", arrayOf("cycle", "pause"))
                                lastTapTime = 0L; param.result = true; return
                            }
                            lastTapTime = now
                        }
                        // Allow hold logic in PiP too
                    }

                    val isHorizontal = prefs.getBoolean("horizontal_drag", true)
                    val speeds = getSpeeds(prefs)
                    val sensitivity = try { (prefs.getString("drag_sensitivity", "100") ?: "100").toDouble() } catch(e: Exception) { 100.0 }
                    val holdDelay = try { (prefs.getString("hold_delay", "400") ?: "400").toLong() } catch(e: Exception) { 400L }

                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            longPressRunnable?.let { handler.removeCallbacks(it) }; initialDragX = event.x; initialDragY = event.y
                            if (!isPlayerPaused(activity)) {
                                longPressRunnable = Runnable {
                                    isHolding = true; savedSpeed = XposedHelpers.callMethod(mpv, "getPropertyDouble", "speed") as? Double ?: 1.0
                                    val holdSpeed = prefs.getString("hold_speed", "2.0")?.toDoubleOrNull() ?: 2.0
                                    XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", holdSpeed)
                                    startingSpeedIndex = speeds.indices.minByOrNull { Math.abs(speeds[it] - holdSpeed) } ?: 0
                                    initialDragX = event.x; initialDragY = event.y; showSpeedOverlay("${holdSpeed}x", prefs)
                                    val cancelEvent = MotionEvent.obtain(event); cancelEvent.action = MotionEvent.ACTION_CANCEL
                                    XposedBridge.invokeOriginalMethod(param.method, param.thisObject, arrayOf(cancelEvent)); cancelEvent.recycle()
                                }
                                handler.postDelayed(longPressRunnable!!, holdDelay)
                            }
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (!isHolding) {
                                if (Math.abs(event.x - initialDragX) > 60 || Math.abs(event.y - initialDragY) > 60) {
                                    longPressRunnable?.let { handler.removeCallbacks(it) }
                                }
                                return
                            }
                            val dx = event.x - initialDragX; val dy = event.y - initialDragY
                            val mainDelta = if (isHorizontal) dx else dy; val crossDelta = if (isHorizontal) dy else dx
                            if (Math.abs(mainDelta) < 50 || Math.abs(crossDelta) * 2.5 > Math.abs(mainDelta)) return 
                            val indexShift = (mainDelta / sensitivity).toInt()
                            var newIndex = (startingSpeedIndex + indexShift).coerceIn(0, speeds.size - 1)
                            if (Math.abs(speeds[newIndex] - (XposedHelpers.callMethod(mpv, "getPropertyDouble", "speed") as Double)) > 0.01) {
                                XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", speeds[newIndex])
                                showSpeedOverlay(buildSequenceText(speeds, newIndex, prefs), prefs)
                                startCollapseTimer(speeds[newIndex], prefs) 
                            }
                            param.result = true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            longPressRunnable?.let { handler.removeCallbacks(it) }; collapseRunnable?.let { handler.removeCallbacks(it) }
                            if (isHolding) {
                                isHolding = false; XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", savedSpeed)
                                hideSpeedOverlay(); param.result = true
                            }
                        }
                    }
                }
            })
        } catch (e: Throwable) { XposedBridge.log("EliteMod: Critical error: ${e.message}") }
    }

    private fun startCollapseTimer(speed: Double, prefs: android.content.SharedPreferences) {
        collapseRunnable?.let { handler.removeCallbacks(it) }
        collapseRunnable = Runnable { showSpeedOverlay("${speed}x", prefs) }
        handler.postDelayed(collapseRunnable!!, 750) 
    }

    private fun getSpeeds(prefs: android.content.SharedPreferences): List<Double> {
        return (prefs.getString("speed_sequence", "0.1, 0.5, 1.0, 2.0, 3.5, 4.0, 6.0, 10.0") ?: "").split(",").mapNotNull { it.trim().toDoubleOrNull() }.sorted()
    }

    private fun isPlayerPaused(activity: Activity) = try {
        XposedHelpers.callMethod(XposedHelpers.getObjectField(XposedHelpers.callMethod(activity, "getViewModel"), "paused"), "getValue") as? Boolean ?: false
    } catch (e: Throwable) { false }

    private fun buildSequenceText(speeds: List<Double>, index: Int, prefs: android.content.SharedPreferences): String {
        if (!prefs.getBoolean("show_expansion", true)) return "${speeds[index]}x"
        val sb = StringBuilder()
        if (index > 1) sb.append(".. ")
        if (index > 0) sb.append("${speeds[index-1]}  ")
        sb.append(speeds[index]).append("x")
        if (index < speeds.size - 1) sb.append("  ${speeds[index+1]}")
        if (index < speeds.size - 2) sb.append(" ..")
        return sb.toString()
    }

    private fun createGlassUI(activity: Activity, prefs: android.content.SharedPreferences) {
        handler.post {
            val root = activity.window.decorView as ViewGroup
            val margin = (prefs.getString("pill_margin", "15")?.toIntOrNull() ?: 15).coerceIn(5, 100)
            glassPill = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; visibility = View.GONE; alpha = 0f
                val shape = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 100f; setColor(Color.parseColor("#44000000")); setStroke(2, Color.parseColor("#22FFFFFF")) }
                background = shape; setPadding(60, 20, 60, 20)
                layoutParams = FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = margin }
                val icon = ImageView(activity).apply { setImageResource(android.R.drawable.ic_media_ff); setColorFilter(Color.WHITE); layoutParams = LinearLayout.LayoutParams(50, 50).apply { rightMargin = 20 } }
                addView(icon)
                pillText = TextView(activity).apply { setTextColor(Color.WHITE); textSize = 17f; setTypeface(null, Typeface.BOLD) }
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
                visibility = View.VISIBLE; scaleX = scaleVal; scaleY = scaleVal
                if (text.contains("  ")) { animate().alpha(1f).scaleX(scaleVal * 1.25f).scaleY(scaleVal * 1.1f).setInterpolator(OvershootInterpolator()).setDuration(250).start() }
                else { animate().alpha(1f).scaleX(scaleVal).scaleY(scaleVal).setDuration(200).start() }
                bringToFront()
            }
        }
    }

    private fun hideSpeedOverlay() { handler.post { glassPill?.animate()?.alpha(0f)?.scaleX(0.8f)?.scaleY(0.8f)?.setDuration(400)?.withEndAction { glassPill?.visibility = View.GONE }?.start() } }

    private fun getAnimeId(activity: Activity) = try { XposedHelpers.callMethod(XposedHelpers.callMethod(XposedHelpers.callMethod(activity, "getViewModel"), "getAnime"), "getId").toString() } catch (e: Throwable) { "unknown" }

    private fun loadSavedSpeedForAnime(activity: Activity, prefs: android.content.SharedPreferences) {
        val animeId = getAnimeId(activity); if (animeId == "unknown") return
        if (!prefs.getBoolean("per_show_speed", true)) return
        val speedMap = JSONObject(prefs.getString("speed_memory", "{}") ?: "{}")
        if (speedMap.has(animeId)) {
            val speed = speedMap.getDouble(animeId); val mpv = try { XposedHelpers.callMethod(activity, "getMpv") } catch (e: Throwable) { null } ?: return
            XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", speed); handler.postDelayed({ showSpeedOverlay("↺ ${speed}x", prefs); handler.postDelayed({ hideSpeedOverlay() }, 1200) }, 800)
        }
    }

    private fun saveSpeedForAnime(activity: Activity, speed: Double, prefs: android.content.SharedPreferences) {
        val animeId = getAnimeId(activity); if (animeId == "unknown") return
        if (!prefs.getBoolean("per_show_speed", true)) return
        val speedMap = JSONObject(prefs.getString("speed_memory", "{}") ?: "{}"); speedMap.put(animeId, speed); prefs.edit().putString("speed_memory", speedMap.toString()).apply()
    }

    private fun showSaveDialog(activity: Activity, uri: Uri) {
        val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 60, 60, 60); val gd = GradientDrawable().apply { setColor(Color.parseColor("#1A1A22")); setCornerRadii(floatArrayOf(60f, 60f, 60f, 60f, 0f, 0f, 0f, 0f)) }; background = gd }
        val filename = getFileName(activity, uri); root.addView(TextView(activity).apply { text = "🎬 Add this to Animetail?"; setTextColor(Color.WHITE); textSize = 18f; setTypeface(null, Typeface.BOLD) })
        root.addView(TextView(activity).apply { text = filename; setTextColor(Color.parseColor("#7A7A9A")); textSize = 14f; setPadding(0, 10, 0, 30) })
        
        val btnRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        btnRow.addView(Button(activity).apply { text = "NO"; setTextColor(Color.WHITE); background = null; setOnClickListener { dialog.dismiss() }; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        btnRow.addView(Button(activity).apply { text = "YES"; val gd = GradientDrawable().apply { setColor(Color.parseColor("#4DD9B8")); cornerRadius = 20f }; background = gd; setTextColor(Color.BLACK); setOnClickListener { injectToAnimetail(activity, uri, filename); dialog.dismiss() }; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        
        root.addView(btnRow)
        dialog.setContentView(root); dialog.window?.setGravity(Gravity.BOTTOM); dialog.window?.setLayout(-1, -2); dialog.show()
    }

    private fun injectToAnimetail(activity: Activity, uri: Uri, filename: String) {
        val name = filename.substringBeforeLast(".")
        val path = uri.path ?: uri.toString()
        val localDir = "/storage/emulated/0/Animetail/local/anime/$name"
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "mkdir -p \"$localDir\" && ln -sf \"$path\" \"$localDir/$filename\"")).waitFor()
            val intent = activity.packageManager.getLaunchIntentForPackage("com.dark.animetailv2")
            activity.startActivity(intent)
            Toast.makeText(activity, "Added to Library!", Toast.LENGTH_LONG).show()
        } catch(e: Exception) {}
    }

    private fun getFileName(c: Context, uri: Uri): String {
        c.contentResolver.query(uri, null, null, null, null)?.use { if (it.moveToFirst()) return it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME)) }
        return uri.lastPathSegment ?: "Unknown file"
    }

    private fun hookInstaller(installerClass: Class<*>, entryClassName: String, lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(installerClass, "processEntry", entryClassName, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val context = param.thisObject as? Context ?: return
                val prefs = context.getSharedPreferences("elite_mod_prefs", Context.MODE_PRIVATE); if (!prefs.getBoolean("silent_update", true)) return
                val uri = XposedHelpers.getObjectField(param.args[0], "uri") as Uri
                try { val inputStream = context.contentResolver.openInputStream(uri) ?: return; val tempFile = File(context.cacheDir, "temp_mod_ext.apk")
                    inputStream.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }; Runtime.getRuntime().exec(arrayOf("su", "-c", "pm install -r ${tempFile.absolutePath}")).waitFor()
                    tempFile.delete(); val installedStep = XposedHelpers.getStaticObjectField(XposedHelpers.findClass("eu.kanade.tachiyomi.extension.InstallStep", lpparam.classLoader), "Installed")
                    XposedHelpers.callMethod(param.thisObject, "continueQueue", installedStep); param.result = null
                } catch (e: Exception) {}
            }
        })
    }
}
