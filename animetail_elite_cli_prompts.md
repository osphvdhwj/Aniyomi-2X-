# Animetail Elite Module — CLI AI Prompt Pack
> Feed each prompt **in order** to your local CLI AI (Aider, Claude Code, Gemini CLI, etc.)
> The AI has root access and full access to the project repo.
> Each prompt is self-contained. Paste it verbatim. Do not skip prompts — later ones depend on earlier ones.

---

## HOW TO USE

```
# Clone / open the project first
cd /path/to/Animetail-Elite-Module

# Then feed prompts one by one:
aider --model claude-sonnet-4-6   # or whichever model you use
# → paste Prompt 1, let it finish, then paste Prompt 2, etc.
```

**Root is required** for prompts that touch `/data/data/` paths, `su` commands, or `pm install`.
Each prompt tells the AI exactly which files to touch and what the acceptance criteria is.

---

---

# BLOCK A — CRITICAL BUG FIXES
> These are blocking bugs. Nothing else works correctly until these are done.
> Do Block A before anything else.

---

## PROMPT A-1 — Fix the Prefs Key Mismatch (The #1 Breaking Bug)

```
You are working on an Android LSPosed module project located in the current directory.

PROBLEM:
SettingsActivity.kt saves all user preferences to a SharedPreferences file named "mod_prefs".
ModuleMain.kt reads preferences from a SharedPreferences file named "elite_mod_prefs".
These are two completely different files. This means EVERY setting the user changes in the
Settings screen is silently ignored by the hook. Nothing works as intended.

TASK:
1. Open app/src/main/java/com/dark/animetailv2/module/SettingsActivity.kt
2. Find every occurrence of the string "mod_prefs" in that file.
3. Replace ALL occurrences of "mod_prefs" with "elite_mod_prefs".
4. Also find the forceSync() method. It currently copies "mod_prefs.xml" to the target.
   Update it to copy "elite_mod_prefs.xml" instead. The target path variable `targetFile`
   should point to "$targetDir/elite_mod_prefs.xml".
5. Verify ModuleMain.kt already uses "elite_mod_prefs" everywhere — do not change it.
6. Save the file.

ACCEPTANCE CRITERIA:
- SettingsActivity.kt contains zero occurrences of the string "mod_prefs" after the edit.
- The string "elite_mod_prefs" appears in both SettingsActivity.kt and ModuleMain.kt.
- The forceSync() command copies "elite_mod_prefs.xml", not "mod_prefs.xml".
- No other logic is changed.
```

---

## PROMPT A-2 — Fix the onPause Speed Restore (Stuck-Speed Bug)

```
You are working on an Android LSPosed module at app/src/main/java/com/dark/animetailv2/module/ModuleMain.kt

PROBLEM:
When a user is holding down to activate 2x speed and then receives a phone call, presses
the Home button, or pulls down the notification shade, the app goes to onPause/onStop.
The speed stays stuck at the hold speed permanently because there is no hook for these
lifecycle events. The user has to force-kill Animetail to fix it.

TASK:
Inside the `handleLoadPackage` function, after the existing `dispatchTouchEvent` hook,
add a new hook that targets Activity.onPause.

The hook should:
1. Only fire for the class "eu.kanade.tachiyomi.ui.player.PlayerActivity".
2. In the `afterHookedMethod` callback, check if `isHolding` is true.
3. If `isHolding` is true:
   a. Set `isHolding = false`
   b. Remove any pending longPressRunnable callbacks from `handler`
   c. Set `longPressRunnable = null`
   d. Get the mpv instance via XposedHelpers.callMethod(activity, "getMpv")
   e. If mpv is not null, call setPropertyDouble("speed", savedSpeed) to restore speed
   f. Call hideSpeedOverlay() to hide the glass pill
4. If `isHolding` is false, do nothing — let the method proceed normally.

Use the same XposedHelpers.findAndHookMethod pattern already used in the file.
The method signature to hook is: Activity::class.java, "onPause", with an XC_MethodHook.

ACCEPTANCE CRITERIA:
- A new hook exists for Activity.onPause in handleLoadPackage.
- It checks the package name before doing anything.
- It only restores speed when isHolding is true.
- It does not interfere with normal pause behavior.
- No existing hooks are modified.
```

---

## PROMPT A-3 — Fix the hookInstaller Activity Cast Crash

```
You are working on app/src/main/java/com/dark/animetailv2/module/ModuleMain.kt

PROBLEM:
The hookInstaller() function contains this line:
    val activity = param.thisObject as? Activity // Might be null in service
The comment says "might be null" but the very next line does:
    val prefs = activity?.getSharedPreferences(...) ?: return
This means the silent auto-update feature silently returns every single time because
installers run in a background service, not an Activity. The `activity` is ALWAYS null here.
The feature has never worked.

TASK:
Rewrite the hookInstaller() function to get the SharedPreferences context correctly:

1. Replace the `activity` variable with a `context` variable using:
   val context = param.thisObject as? Context ?: return
   (android.content.Service extends Context, so this cast will succeed)

2. Update the prefs line to use `context` instead of `activity`:
   val prefs = context.getSharedPreferences("elite_mod_prefs", Context.MODE_PRIVATE)
   Remove the `?: return` null check since context is already guaranteed non-null above.

3. Update the `service` variable — it is currently:
   val service = XposedHelpers.getObjectField(param.thisObject, "service") as Context
   Change this to simply use `context` directly since param.thisObject IS the service.
   Remove the old `service` variable declaration entirely.

4. Replace all uses of `service` in the function body with `context`.

5. The Runtime.getRuntime().exec() line that uses "su" — keep it exactly as-is.

ACCEPTANCE CRITERIA:
- hookInstaller() no longer casts param.thisObject to Activity.
- It casts to Context instead, which works for both Activity and Service subclasses.
- The prefs are read from the same "elite_mod_prefs" file.
- The su exec command is unchanged.
- The function compiles without errors.
```

---

## PROMPT A-4 — Fix the longPressRunnable Memory Leak

```
You are working on app/src/main/java/com/dark/animetailv2/module/ModuleMain.kt

PROBLEM:
When longPressRunnable fires (the 400ms long-press activates), the variable is never
set to null. On the next touch, ACTION_DOWN sets a new runnable and ACTION_UP tries
to remove callbacks on the NEW runnable, but the OLD runnable already ran and may
cause a double speed-change or double hideSpeedOverlay() call. Also isHolding state
can leak between player restarts since these are instance fields.

TASK:
1. In the `ACTION_DOWN` branch of the dispatchTouchEvent hook, inside the Runnable
   that fires after the hold delay, add `longPressRunnable = null` as the FIRST line
   inside the Runnable body (before setting isHolding = true).

2. In the `ACTION_UP / ACTION_CANCEL` branch, after `longPressRunnable?.let {
   handler.removeCallbacks(it) }`, add `longPressRunnable = null`.

3. At the very start of `ACTION_DOWN`, before the isPaused check, reset the gesture
   state by adding:
       if (!isHolding) {
           longPressRunnable?.let { handler.removeCallbacks(it) }
           longPressRunnable = null
       }
   This ensures stale runnables are always cleared at the start of a new touch.

4. Do not change any other logic.

ACCEPTANCE CRITERIA:
- longPressRunnable is set to null in three places: when it fires, when it is cancelled
  on ACTION_UP, and defensively at the start of ACTION_DOWN.
- No behavior change for normal use — this is purely defensive cleanup.
```

---

---

# BLOCK B — GESTURE ENGINE REWRITE
> Replaces the flat isHolding boolean with a proper state machine.
> Must be done before adding Double-Tap, Brightness, or Volume gestures.

---

## PROMPT B-1 — Add the GestureMode State Machine

```
You are working on app/src/main/java/com/dark/animetailv2/module/ModuleMain.kt

TASK:
We are adding a gesture classification system so the player can distinguish between
speed-hold, brightness swipe, volume swipe, and double-tap gestures without them
interfering with each other.

Step 1 — Add a new enum class at the top level of ModuleMain.kt (outside the class body,
before the class declaration):

    enum class GestureMode {
        IDLE,
        LONG_PRESS_PENDING,
        SPEED_HOLD,
        BRIGHTNESS_SWIPE,
        VOLUME_SWIPE,
        DOUBLE_TAP_PENDING,
        SKIP_FIRED
    }

Step 2 — In the ModuleMain class body, replace the existing instance fields:
    private var isHolding = false
    private var initialDragAxis = 0f
    private var currentSpeedIndex = -1
    private var savedSpeed = 1.0

With this expanded set of fields:
    private var gestureMode = GestureMode.IDLE
    private var initialX = 0f
    private var initialY = 0f
    private var initialDragAxis = 0f
    private var currentSpeedIndex = -1
    private var savedSpeed = 1.0
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var screenWidth = 0

Step 3 — In the dispatchTouchEvent hook's ACTION_DOWN branch, replace the line:
    val isPaused = isPlayerPaused(activity)
with:
    if (screenWidth == 0) {
        screenWidth = activity.resources.displayMetrics.widthPixels
    }
    initialX = event.x
    initialY = event.y
    val isPaused = isPlayerPaused(activity)

Step 4 — Replace every existing reference to `isHolding` in the dispatchTouchEvent
hook with `gestureMode == GestureMode.SPEED_HOLD`. Specifically:
  - Where `isHolding = true` is set → change to `gestureMode = GestureMode.SPEED_HOLD`
  - Where `isHolding = false` is set → change to `gestureMode = GestureMode.IDLE`
  - Where `if (!isHolding)` is checked → change to `if (gestureMode != GestureMode.SPEED_HOLD)`
  - Where `if (isHolding)` is checked → change to `if (gestureMode == GestureMode.SPEED_HOLD)`

Step 5 — In the onPause hook added in Prompt A-2, replace `isHolding` references with
`gestureMode` checks the same way.

Step 6 — Make sure the file still compiles. Run:
    ./gradlew :app:compileDebugKotlin
and fix any compilation errors before finishing.

ACCEPTANCE CRITERIA:
- GestureMode enum exists in the file.
- isHolding field is completely removed.
- gestureMode field of type GestureMode exists.
- The file compiles without errors.
- Existing behavior is unchanged — SPEED_HOLD is functionally equivalent to the old isHolding=true.
```

---

## PROMPT B-2 — Add Zone Classification to the Gesture Engine

```
You are working on app/src/main/java/com/dark/animetailv2/module/ModuleMain.kt

CONTEXT:
The GestureMode enum and new fields were added in the previous prompt.
The screen is now divided into three zones based on the X coordinate of the initial touch:
  - Left 35% of screen width → BRIGHTNESS zone
  - Right 35% of screen width → VOLUME zone
  - Middle 30% → SPEED zone (existing behavior)

TASK:
Modify the dispatchTouchEvent hook's ACTION_DOWN branch to set the gesture mode based
on which zone the touch started in. The zone is determined at ACTION_DOWN and locked for
the entire gesture duration.

1. After `initialX = event.x` and `initialY = event.y`, add this zone classification:
    val zone = when {
        initialX < screenWidth * 0.35f -> "BRIGHTNESS"
        initialX > screenWidth * 0.65f -> "VOLUME"
        else -> "SPEED"
    }

2. The existing long-press runnable logic (that sets up the hold speed) should ONLY run
   when zone == "SPEED". Wrap the entire longPressRunnable setup block in:
    if (zone == "SPEED" && !isPaused) { ... }

3. For BRIGHTNESS and VOLUME zones, when the user begins a vertical swipe on ACTION_MOVE,
   we want to handle them differently (that comes in the next prompt). For now, when
   zone is BRIGHTNESS or VOLUME, simply set:
    gestureMode = GestureMode.BRIGHTNESS_SWIPE   // for BRIGHTNESS zone
    gestureMode = GestureMode.VOLUME_SWIPE        // for VOLUME zone
   Set this immediately on ACTION_DOWN (not delayed) when the zone is not SPEED.
   Store the zone determination in a new instance field: `private var activeZone = "SPEED"`
   Set `activeZone = zone` in ACTION_DOWN.

4. In the ACTION_MOVE branch, add a guard at the very top:
    if (gestureMode == GestureMode.BRIGHTNESS_SWIPE ||
        gestureMode == GestureMode.VOLUME_SWIPE) return
   This prevents the speed drag logic from firing when the user is swiping in
   brightness/volume zones. (The actual brightness/volume logic comes in Prompt C-2.)

5. In ACTION_UP/ACTION_CANCEL, add a reset:
    activeZone = "SPEED"
   alongside the existing gestureMode = IDLE reset.

ACCEPTANCE CRITERIA:
- Touching the left 35% of screen no longer triggers speed hold, even on a long press.
- Touching the right 35% of screen no longer triggers speed hold.
- Touching the middle 30% works exactly as before.
- activeZone field exists and is reset on every ACTION_UP/CANCEL.
- File compiles without errors.
```

---

---

# BLOCK C — NEW PLAYER FEATURES

---

## PROMPT C-1 — Double-Tap to Skip (Left = Rewind, Right = Forward)

```
You are working on app/src/main/java/com/dark/animetailv2/module/ModuleMain.kt

CONTEXT:
The GestureMode enum and zone classification system exist from Block B.
The screen is divided: left 35% = BRIGHTNESS zone, right 35% = VOLUME zone, middle = SPEED.
We want double-tap-to-skip to work on the LEFT and RIGHT zones only.
Left zone double-tap = rewind. Right zone double-tap = forward.
The skip duration is configurable from SharedPreferences key "skip_duration" (default "10").

TASK:

1. Modify the ACTION_DOWN branch. After setting activeZone, add double-tap detection:
   - Get current time: `val now = System.currentTimeMillis()`
   - If `activeZone != "SPEED"` and `now - lastTapTime < 300` and the tap is within
     100px of `lastTapX` horizontally:
       → This is a double-tap. Cancel any pending longPressRunnable. Set gestureMode =
         GestureMode.SKIP_FIRED. Determine direction: if activeZone == "BRIGHTNESS"
         (left zone) → rewind, else → forward.
       → Read skip duration: `val skipSec = prefs.getString("skip_duration", "10")?.toIntOrNull() ?: 10`
       → Get mpv instance. Fire the seek command:
         `XposedHelpers.callMethod(mpv, "command", arrayOf("seek", if(rewind) "-$skipSec" else "+$skipSec"))`
       → Show the overlay: showSkipOverlay(activity, if(rewind) "⏪ −${skipSec}s" else "⏩ +${skipSec}s", rewind)
       → Consume the event: `param.result = true`
   - Update `lastTapTime = now` and `lastTapX = event.x` regardless of double-tap outcome.

2. Add a new method `showSkipOverlay(activity: Activity, text: String, isLeft: Boolean)`:
   - This should show a pill similar to the speed pill but positioned on the LEFT edge
     (if isLeft) or RIGHT edge (if not isLeft) of the screen, vertically centered.
   - Use the existing glassPill widget but position it: if isLeft, set gravity to
     Gravity.CENTER_VERTICAL or Gravity.START with leftMargin = 40px; if isRight,
     Gravity.CENTER_VERTICAL or Gravity.END with rightMargin = 40px.
   - Set the text and call the existing showSpeedOverlay logic (or duplicate it for the
     skip pill). Auto-hide after 600ms using `handler.postDelayed({ hideSpeedOverlay() }, 600)`.
   - For simplicity, you can reuse the existing glassPill and just reposition its
     LayoutParams gravity before showing it, then restore it to TOP|CENTER_HORIZONTAL
     after hiding.

3. In ACTION_MOVE: if gestureMode == SKIP_FIRED, set `param.result = true` and return
   to consume all move events after a skip.

4. In ACTION_UP/CANCEL: if gestureMode == SKIP_FIRED, set `param.result = true` and
   reset gestureMode to IDLE.

5. In SettingsActivity.kt, add a new input field:
   - Label: "Double-Tap Skip Duration (seconds):"
   - EditText hint: "Min 5, Max 30"
   - Current value: prefs.getString("skip_duration", "10")
   - Save key: "skip_duration"
   - Add it after the drag sensitivity input, before the save button.
   - On save, coerce value to range 5..30 before saving.

ACCEPTANCE CRITERIA:
- Double-tapping the left side rewinds by the configured seconds.
- Double-tapping the right side fast-forwards.
- A skip pill appears on the correct edge and auto-hides after 600ms.
- Single taps in those zones pass through normally (the longPressRunnable for speed
  is already blocked in those zones from B-2, so single taps do nothing special).
- Settings screen has the skip duration field.
```

---

## PROMPT C-2 — Brightness and Volume Gesture Control

```
You are working on app/src/main/java/com/dark/animetailv2/module/ModuleMain.kt

CONTEXT:
Zone classification exists. gestureMode is set to BRIGHTNESS_SWIPE or VOLUME_SWIPE
when the user touches the left or right 35% of the screen respectively.
The ACTION_MOVE branch currently has a guard that returns early for these modes.
Now we need to replace that early return with actual brightness/volume handling.

TASK:

1. Add two new instance fields:
    private var brightnessSideBar: LinearLayout? = null
    private var volumeSideBar: LinearLayout? = null
    private var sideBarText: TextView? = null

2. Add a new method `createSidePill(activity: Activity)` that creates a single
   reusable side pill widget (it can appear on left or right):
   - A narrow, tall LinearLayout (width: 56dp, height: 180dp)
   - GradientDrawable with cornerRadius 28dp, color #66000000, stroke #33FFFFFF
   - Contains: a TextView for the icon (☀ or 🔊) at top center, and a TextView
     for the percentage below it.
   - Store it as a class-level field `sidePill: LinearLayout?`
   - Store the icon TextView as `sidePillIcon: TextView?`
   - Store the value TextView as `sidePillValue: TextView?`
   - Add it to the root DecorView with FrameLayout.LayoutParams, initially GONE.
   Call `createSidePill(activity)` inside the existing `createGlassUI()` method.

3. Add two helper methods:
   a. `showSidePill(icon: String, value: String, isLeft: Boolean)`:
      - Sets sidePillIcon text and sidePillValue text.
      - Repositions the pill's LayoutParams gravity:
        if isLeft → Gravity.START or Gravity.CENTER_VERTICAL, leftMargin = 16dp
        if right  → Gravity.END or Gravity.CENTER_VERTICAL, rightMargin = 16dp
      - Sets visibility = VISIBLE and animates alpha to 1f with duration 150ms.
   b. `hideSidePill()`:
      - Animates alpha to 0f over 300ms, then sets visibility GONE.

4. Add a helper method `getScreenBrightness(activity: Activity): Float`:
    val lp = activity.window.attributes
    return if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness

5. Add a helper method `getVolumePercent(activity: Activity): Int`:
    val am = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
    return ((cur.toFloat() / max) * 100).toInt()

6. In the ACTION_MOVE branch, REPLACE the early return guard for BRIGHTNESS_SWIPE
   and VOLUME_SWIPE with actual handling:

    if (gestureMode == GestureMode.BRIGHTNESS_SWIPE) {
        val dy = initialY - event.y  // positive = swipe up = increase
        val delta = dy / activity.resources.displayMetrics.heightPixels
        val newBrightness = (getScreenBrightness(activity) + delta * 1.5f).coerceIn(0.01f, 1.0f)
        val lp = activity.window.attributes
        lp.screenBrightness = newBrightness
        activity.window.attributes = lp
        initialY = event.y  // update so delta is incremental, not absolute
        val percent = (newBrightness * 100).toInt()
        handler.post { showSidePill("☀", "$percent%", true) }
        param.result = true
        return
    }

    if (gestureMode == GestureMode.VOLUME_SWIPE) {
        val dy = initialY - event.y
        val am = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val threshold = activity.resources.displayMetrics.heightPixels / (max.toFloat())
        // Accumulate fractional movement — only change volume when moved threshold pixels
        val volumeSteps = (dy / threshold).toInt()
        if (volumeSteps != 0) {
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val newVol = (cur + volumeSteps).coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
            initialY = event.y
            val percent = ((newVol.toFloat() / max) * 100).toInt()
            handler.post { showSidePill("🔊", "$percent%", false) }
        }
        param.result = true
        return
    }

7. In ACTION_UP/CANCEL, add:
    if (gestureMode == GestureMode.BRIGHTNESS_SWIPE ||
        gestureMode == GestureMode.VOLUME_SWIPE) {
        handler.postDelayed({ hideSidePill() }, 800)
    }

8. In SettingsActivity.kt, add two new toggle switches in the Features card area:
   - "Brightness Gesture (Left Swipe)", key "brightness_gesture", default true
   - "Volume Gesture (Right Swipe)", key "volume_gesture", default true

9. In the ACTION_MOVE handler, check these prefs before processing:
   - Read prefs in ACTION_DOWN and store `val bGesture = prefs.getBoolean("brightness_gesture", true)`
     and `val vGesture = prefs.getBoolean("volume_gesture", true)` alongside the other prefs reads.
   - In the BRIGHTNESS_SWIPE handler, wrap it in `if (bGesture)`.
   - In the VOLUME_SWIPE handler, wrap it in `if (vGesture)`.

ACCEPTANCE CRITERIA:
- Swiping up/down on the left 35% of the player changes screen brightness.
- Swiping up/down on the right 35% changes media volume.
- A side pill shows the current value with the correct icon on the correct edge.
- The pill auto-hides 800ms after the finger lifts.
- Both gestures have on/off toggles in Settings.
- Speed hold still works in the middle 30%.
- File compiles without errors.
```

---

## PROMPT C-3 — Haptic Feedback

```
You are working on app/src/main/java/com/dark/animetailv2/module/ModuleMain.kt

TASK:
Add haptic feedback at key gesture events. Haptics should be off by default and controlled
by a toggle in SharedPreferences key "haptic_feedback" (default false).

1. Add a helper method `vibrate(activity: Activity, durationMs: Long)`:

    private fun vibrate(activity: Activity, durationMs: Long) {
        val prefs = activity.getSharedPreferences("elite_mod_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("haptic_feedback", false)) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val effect = VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                vm.defaultVibrator.vibrate(effect)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val v = activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Throwable) { /* silently fail if vibrator unavailable */ }
    }

2. Add the required imports at the top of the file:
    import android.os.VibrationEffect
    import android.os.Vibrator
    import android.os.VibratorManager

3. Call vibrate() at these points:
   a. When speed hold ACTIVATES (inside the longPressRunnable, right after isHolding is set
      to SPEED_HOLD): `vibrate(activity, 35)`
   b. When speed hold RELEASES (in ACTION_UP when gestureMode was SPEED_HOLD, before
      restoring speed): create a double-pulse using:
        vibrate(activity, 30)
        handler.postDelayed({ vibrate(activity, 20) }, 50)
   c. When a double-tap skip fires (right after the seek command): `vibrate(activity, 50)`
   d. When dragging through speed steps and the index changes (in ACTION_MOVE, only when
      newIndex != currentSpeedIndex — add a check before updating currentSpeedIndex):
        if (newIndex != currentSpeedIndex) vibrate(activity, 8)

4. In SettingsActivity.kt, add a new toggle switch:
   - Label: "📳 Haptic Feedback"
   - Key: "haptic_feedback"
   - Default: false (off by default to respect user preferences)

ACCEPTANCE CRITERIA:
- haptic_feedback toggle exists in Settings.
- When toggle is OFF, no vibration occurs under any circumstances.
- When ON: 35ms pulse on hold start, double-pulse on hold end, 50ms on skip, 8ms on
  each speed index change while dragging.
- Works on API 26-30 (Vibrator) and API 31+ (VibratorManager).
- File compiles without errors.
```

---

## PROMPT C-4 — Per-Show Speed Memory

```
You are working on app/src/main/java/com/dark/animetailv2/module/ModuleMain.kt

CONTEXT:
Currently speed resets to the last-held speed every time the player opens, or inherits
whatever mpv's default is. Users want the speed they set for a show to persist per-show.

TASK:

1. Add a new method `getAnimeId(activity: Activity): String`:
    private fun getAnimeId(activity: Activity): String {
        return try {
            val viewModel = XposedHelpers.callMethod(activity, "getViewModel")
            val anime = XposedHelpers.callMethod(viewModel, "getAnime")
            val id = XposedHelpers.callMethod(anime, "getId") as? Long ?: return "unknown"
            id.toString()
        } catch (e: Throwable) { "unknown" }
    }

2. Add a new method `loadSavedSpeedForAnime(activity: Activity)`:
    private fun loadSavedSpeedForAnime(activity: Activity) {
        try {
            val animeId = getAnimeId(activity)
            if (animeId == "unknown") return
            val prefs = activity.getSharedPreferences("elite_mod_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("per_show_speed", true)) return
            val speedJson = prefs.getString("speed_memory", "{}") ?: "{}"
            val speedMap = org.json.JSONObject(speedJson)
            if (speedMap.has(animeId)) {
                val speed = speedMap.getDouble(animeId)
                val mpv = XposedHelpers.callMethod(activity, "getMpv") ?: return
                XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", speed)
                handler.postDelayed({
                    showSpeedOverlay("↺ ${speed}x")
                    handler.postDelayed({ hideSpeedOverlay() }, 1200)
                }, 800) // delay so the player UI is ready
            }
        } catch (e: Throwable) { XposedBridge.log("EliteMod: Speed memory load error: ${e.message}") }
    }

3. Add a new method `saveSpeedForAnime(activity: Activity, speed: Double)`:
    private fun saveSpeedForAnime(activity: Activity, speed: Double) {
        try {
            val animeId = getAnimeId(activity)
            if (animeId == "unknown") return
            val prefs = activity.getSharedPreferences("elite_mod_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("per_show_speed", true)) return
            val speedJson = prefs.getString("speed_memory", "{}") ?: "{}"
            val speedMap = org.json.JSONObject(speedJson)
            speedMap.put(animeId, speed)
            prefs.edit().putString("speed_memory", speedMap.toString()).apply()
        } catch (e: Throwable) {}
    }

4. In the PlayerActivity.onCreate hook (the afterHookedMethod that calls createGlassUI),
   add a call to `loadSavedSpeedForAnime(activity)` after `createGlassUI(activity)`.

5. In the dispatchTouchEvent hook, in the ACTION_UP/CANCEL branch where speed hold
   releases and `savedSpeed` is restored — after the mpv.setPropertyDouble call,
   add: `saveSpeedForAnime(activity, savedSpeed)`

6. Also hook a speed change that happens outside of hold mode: after any call to
   `XposedHelpers.callMethod(mpv, "setPropertyDouble", "speed", ...)` in the PiP
   broadcast receiver, call saveSpeedForAnime with the new speed value.

7. In SettingsActivity.kt, add a new toggle switch:
   - Label: "↺ Remember Speed Per Show"
   - Key: "per_show_speed"
   - Default: true

ACCEPTANCE CRITERIA:
- When a user changes speed during normal playback, it is remembered per anime ID.
- When that anime is opened again, the saved speed is automatically applied.
- A brief "↺ 1.5x" pill appears after the player loads to confirm the restored speed.
- The feature has an on/off toggle in Settings.
- Per-show speed data is stored in the "speed_memory" JSON key in SharedPreferences.
- File compiles without errors.
```

---

## PROMPT C-5 — Sleep Timer with Fade

```
You are working on app/src/main/java/com/dark/animetailv2/module/ModuleMain.kt

TASK:
Add a sleep timer that auto-pauses playback after a configurable delay.

1. Add new instance fields:
    private var sleepTimerRunnable: Runnable? = null
    private var sleepTimerEndTime = 0L

2. Add a method `startSleepTimer(activity: Activity, minutes: Int)`:
    - Cancels any existing sleepTimerRunnable.
    - Calculates `sleepTimerEndTime = System.currentTimeMillis() + minutes * 60_000L`
    - Creates a countdown that fires every second via handler.postDelayed.
    - In the last 60 seconds, calls `showSpeedOverlay("😴 ${remainingSeconds}s")` each
      second so the user sees a countdown in the existing glass pill.
    - When time runs out:
        a. Hide the overlay.
        b. Get mpv and call setPropertyBoolean("pause", true) to pause.
        c. Optionally fade the video: animate the DecorView alpha from 1f to 0f over
           8000ms, then restore alpha to 1f after the pause.

3. Add a method `cancelSleepTimer()`:
    - Cancels sleepTimerRunnable, sets it to null, sets sleepTimerEndTime = 0L.
    - Hides the overlay if it was showing the countdown.

4. Add a method `showSleepTimerPicker(activity: Activity)`:
    - Creates an AlertDialog (or a simple popup) with four options:
      "15 minutes", "30 minutes", "45 minutes", "60 minutes", "Cancel timer"
    - Selecting an option calls startSleepTimer(activity, selectedMinutes).
    - "Cancel timer" calls cancelSleepTimer().
    - Use android.app.AlertDialog.Builder — no external library needed.

5. In the PlayerActivity.onCreate afterHookedMethod, add a three-finger tap detector:
   - In the dispatchTouchEvent hook, detect a three-finger tap:
     `if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount == 3)`
   - Show the sleep timer picker dialog when this fires.

6. In SettingsActivity.kt, add a new section header "SLEEP TIMER" and a note TextView:
   - Text: "Activate with 3-finger tap while watching"
   - No input needed — the timer is always available via gesture.

ACCEPTANCE CRITERIA:
- Three-finger tap shows a timer picker dialog.
- Selecting a duration starts the countdown.
- Last 60 seconds shows a countdown in the glass pill.
- Video fades out and pauses when time runs out.
- Cancelling the timer stops the countdown and hides the overlay.
- The feature works reliably without crashing on re-entry.
```

---

---

# BLOCK D — SETTINGS UI OVERHAUL

---

## PROMPT D-1 — Full Dark Theme and Card Layout for SettingsActivity

```
You are working on app/src/main/java/com/dark/animetailv2/module/SettingsActivity.kt

TASK:
Completely rewrite the visual layout of SettingsActivity to a dark-themed card-based UI.
Keep ALL existing settings functionality — do not remove any switches or inputs.
Only change the visual presentation.

COLOR CONSTANTS — add these as companion object properties in the class:
    val BG_COLOR = Color.parseColor("#0D0D0F")
    val CARD_COLOR = Color.parseColor("#1A1A22")
    val CARD_STROKE = Color.parseColor("#2A2A38")
    val ACCENT_VIOLET = Color.parseColor("#7C6FE0")
    val ACCENT_TEAL = Color.parseColor("#4DD9B8")
    val TEXT_PRIMARY = Color.parseColor("#F0F0F8")
    val TEXT_SECONDARY = Color.parseColor("#7A7A9A")
    val DANGER_RED = Color.parseColor("#E05C6F")

1. Set the window background:
   window.decorView.setBackgroundColor(BG_COLOR)

2. Replace the existing flat LinearLayout with this structure:
   ScrollView → LinearLayout(vertical, padding 16dp) containing:
   a. A header view: custom drawn View or LinearLayout containing:
      - App name: "Animetail Elite Mod" in TEXT_PRIMARY, 20sp, bold
      - Version badge: "v1.2 · LSPosed" in TEXT_SECONDARY, 12sp
      - A violet horizontal line (height 2dp, color ACCENT_VIOLET) below it
      - Bottom margin 24dp
   b. Multiple "cards" — each card is a function `createCard(title: String, vararg views: View): LinearLayout`
      that creates a LinearLayout with:
        - GradientDrawable background: CARD_COLOR fill, cornerRadius 12dp,
          setStroke(1dp, CARD_STROKE)
        - Padding: 20dp all sides
        - A section header TextView at top: title in TEXT_SECONDARY, 11sp,
          letter spacing 0.12f, ALL CAPS
        - All passed views added below the header
        - Margin bottom: 16dp

3. Group existing settings into these cards by calling createCard():
   - Card "GESTURE ENGINE": horizontal_drag switch, hold_delay input, drag_sensitivity input
   - Card "SPEED CONTROL": hold_speed input, speed_sequence input, per_show_speed switch
   - Card "SKIP & SEEK": skip_duration input
   - Card "GESTURES": brightness_gesture switch, volume_gesture switch
   - Card "SYSTEM": screenshot_bypass switch, silent_update switch, haptic_feedback switch
   - Card "PIP & PLAYER": pip_2x switch

4. Style all Switch widgets:
   - Text color: TEXT_PRIMARY
   - Set thumbTintList to a ColorStateList: checked=ACCENT_TEAL, unchecked=TEXT_SECONDARY
   - Set trackTintList: checked=color with 40% alpha of ACCENT_TEAL, unchecked=CARD_STROKE

5. Style all EditText inputs:
   - Background: a GradientDrawable with color #252530, cornerRadius 8dp, stroke(1dp, CARD_STROKE)
   - Text color: TEXT_PRIMARY
   - Hint color: TEXT_SECONDARY
   - Padding: 12dp all sides
   - Text size: 14sp

6. Replace the existing save button with a styled version:
   - Background: GradientDrawable fill ACCENT_TEAL, cornerRadius 10dp
   - Text: "SYNC TO ANIMETAIL" in Color.BLACK, bold, 15sp
   - Padding: 16dp top and bottom
   - On click: flash animation (alpha 0.5f → 1.0f over 200ms), then show Toast

7. Add a "Reset to Defaults" button below the save button:
   - Background: transparent with stroke(1dp, DANGER_RED), cornerRadius 10dp
   - Text: "Reset to Defaults" in DANGER_RED
   - On click: show an AlertDialog "Reset all settings to defaults?" with OK/Cancel.
     On OK: clear the prefs and repopulate all fields with defaults, then show Toast.

8. Add a sync status footer at the very bottom:
   - A TextView showing "Last synced: [timestamp]" or "Never synced" in TEXT_SECONDARY, 12sp.
   - Read the timestamp from prefs key "last_sync_time" (a Long stored as String).
   - Update it in forceSync() after a successful sync:
       prefs.edit().putString("last_sync_time", System.currentTimeMillis().toString()).apply()
   - Format the timestamp as relative time: "3 seconds ago", "2 minutes ago", etc.

ACCEPTANCE CRITERIA:
- Dark background #0D0D0F fills the entire screen.
- All settings are grouped in visually distinct rounded cards.
- Violet and teal accents are visible.
- Save button is teal, reset button is outlined red.
- Sync timestamp shows in the footer.
- All existing settings still function identically.
- No crashes on launch.
```

---

## PROMPT D-2 — Input Validation with Coercion

```
You are working on app/src/main/java/com/dark/animetailv2/module/SettingsActivity.kt

TASK:
Add input validation to all numeric fields. Values out of range should be automatically
coerced (clamped) to the valid range when the save button is pressed, and a small
helper text should appear beneath the field when the entered value is invalid.

1. Add a helper method `validateAndCoerce(input: EditText, min: Double, max: Double,
   fallback: Double, helperText: TextView): Double`:
   - Parse input.text.toString().toDoubleOrNull().
   - If null: set helperText.text = "Invalid — using ${fallback}", helperText.visibility = VISIBLE,
     return fallback.
   - If value < min: set helperText.text = "Too low — clamped to ${min}", visibility = VISIBLE,
     input.setText(min.toString()), return min.
   - If value > max: set helperText.text = "Too high — clamped to ${max}", visibility = VISIBLE,
     input.setText(max.toString()), return max.
   - Otherwise: helperText.visibility = GONE, return value.

2. For each numeric input field, add a small helperText TextView immediately below it
   (inside the same card, outside the EditText):
   - Text size: 12sp
   - Color: Color.parseColor("#E05C6F") (red/danger)
   - Visibility: GONE by default

3. In the save button's onClick handler, replace direct string reads with validated values:
   - Hold Delay: min=100, max=5000, fallback=400
   - Hold Speed: min=0.1, max=10.0, fallback=2.0
   - Drag Sensitivity: min=20, max=500, fallback=100
   - Skip Duration: min=5, max=30, fallback=10
   - Speed Sequence: validate that it parses to at least one valid Double; if parsing
     completely fails, reset to "0.5, 1.0, 2.0, 3.5" and show a Toast.

4. Text color for helper texts should match DANGER_RED from the companion object.

ACCEPTANCE CRITERIA:
- Entering "abc" in Hold Delay shows red helper text "Invalid — using 400" and saves 400.
- Entering "50000" in Hold Delay shows "Too high — clamped to 5000" and saves 5000.
- Valid values show no helper text.
- Speed sequence "garbage" triggers a Toast and resets to default.
- All coerced values are actually saved correctly.
```

---

---

# BLOCK E — SYSTEM-LEVEL FEATURES

---

## PROMPT E-1 — Download Notification Progress Ring

```
You are working on app/src/main/java/com/dark/animetailv2/module/ModuleMain.kt

TASK:
Intercept Animetail's download progress notifications and inject a circular progress
ring into the notification's large icon bitmap.

1. In handleLoadPackage, find the class responsible for download notifications.
   Try these class names in order until one resolves (use findClassIfExists):
   - "eu.kanade.tachiyomi.data.download.anime.AnimeDownloadNotifier"
   - "eu.kanade.tachiyomi.data.notification.NotificationHandler"
   Hook the method that sends progress updates. Look for a method named "onProgressChange"
   or "progressNotification" or any method that takes an Int progress parameter (0-100).

2. Alternatively, use a broader hook: hook NotificationManager.notify(Int, Notification)
   on any call where notification.extras contains a key matching "download" or "progress".
   Filter by checking if the notification's channel ID contains "download" (case-insensitive).

3. When a download progress notification is intercepted:
   a. Extract the progress value (0-100) from the notification extras or from the
      method parameter.
   b. If progress < 0 or > 100, skip.
   c. Create a 128x128 Bitmap using Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).
   d. Draw on it with Canvas:
      - Fill with Color.TRANSPARENT.
      - Draw a dark circle background: Paint with color #1A1A22, fill style, drawCircle.
      - Draw the progress arc: Paint with color #4DD9B8 (teal), stroke width 10f, no fill,
        using drawArc with sweepAngle = (progress / 100f) * 360f, startAngle = -90f.
      - Draw the progress percentage text in the center: Paint with color WHITE,
        textSize 32f, textAlign CENTER. Use drawText("${progress}%").
   e. Set this bitmap as the notification's large icon using reflection on
      Notification.Builder or by modifying the notification before it is posted.

4. Add a settings toggle:
   - Label: "📥 Download Progress Ring"
   - Key: "download_progress_ring"
   - Default: true

ACCEPTANCE CRITERIA:
- When downloading an extension or episode, the notification large icon shows a
  circular ring that fills from 0% to 100%.
- The ring is teal (#4DD9B8) on a dark background with white percentage text.
- The toggle in Settings turns this on/off.
- Normal (non-download) notifications are unaffected.
```

---

## PROMPT E-2 — Auto DND While Watching

```
You are working on app/src/main/java/com/dark/animetailv2/module/ModuleMain.kt

TASK:
Automatically enable Do Not Disturb mode when the player opens and restore it when
the player closes.

1. Add two new instance fields:
    private var previousDndFilter = NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    private var dndChangedByModule = false

2. In the PlayerActivity.onCreate hook's afterHookedMethod, add DND activation:
    val prefs = activity.getSharedPreferences("elite_mod_prefs", Context.MODE_PRIVATE)
    if (prefs.getBoolean("auto_dnd", false)) {
        val nm = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.isNotificationPolicyAccessGranted) {
            previousDndFilter = nm.currentInterruptionFilter
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
            dndChangedByModule = true
        }
    }

3. Hook PlayerActivity.onDestroy with an afterHookedMethod that restores DND:
    val prefs = activity.getSharedPreferences("elite_mod_prefs", Context.MODE_PRIVATE)
    if (prefs.getBoolean("auto_dnd", false) && dndChangedByModule) {
        val nm = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.isNotificationPolicyAccessGranted &&
            previousDndFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
            nm.setInterruptionFilter(previousDndFilter)
        }
        dndChangedByModule = false
    }

4. In SettingsActivity.kt, add a toggle:
   - Label: "🔕 Auto DND While Watching"
   - Key: "auto_dnd"
   - Default: false
   - Add a note TextView below it: "Requires Do Not Disturb permission in system settings"
     in TEXT_SECONDARY color, 12sp.

ACCEPTANCE CRITERIA:
- When auto_dnd is ON, opening the player activates INTERRUPTION_FILTER_ALARMS.
- Closing the player restores exactly the DND mode that was active before.
- If the app doesn't have DND access permission, it silently skips (no crash, no toast).
- dndChangedByModule prevents restoring DND if the module didn't change it.
- Works correctly even if the user changes DND manually while watching.
```

---

## PROMPT E-3 — Cover Art Palette — Dynamic Notification Color

```
You are working on app/src/main/java/com/dark/animetailv2/module/ModuleMain.kt

TASK:
Extract the dominant color from each anime/manga's cover art and apply it to that
show's update/episode notifications so each show has its own notification color.

1. Add a in-memory cache field:
    private val coverColorCache = mutableMapOf<Long, Int>()

2. Add a method `extractDominantColor(bitmap: Bitmap): Int`:
   Since Palette API may not be available without adding a dependency, implement a
   manual dominant color extractor:
    - Scale the bitmap down to 32x32 using Bitmap.createScaledBitmap for performance.
    - Sample every 4th pixel.
    - Group pixels by hue (compute HSV with Color.colorToHSV).
    - Return the color of the most common hue bucket.
    - If extraction fails, return Color.parseColor("#7C6FE0") as fallback.

3. Hook NotificationManager.notify(String, Int, Notification) and
   NotificationManager.notify(Int, Notification) using a broad hook on the class level.

4. In the hook, before the notification is posted:
   a. Get the notification's `extras` Bundle.
   b. Look for a key that contains anime/manga title or ID (try "anime_id", "manga_id",
      "series_id", or inspect the extras keys by logging them first).
   c. If a series ID is found and the cache has a color for it, set the notification
      color: use reflection to set the `color` field on the Notification object directly:
        XposedHelpers.setIntField(param.args.last(), "color", cachedColor)
   d. If the ID is in the cache → use cached color.
   e. If not in cache → the color will be populated when the cover is loaded (step 5).

5. Hook the image loading for library covers. Look for a class like
   "eu.kanade.tachiyomi.ui.manga.MangaScreenModel" or anime equivalent that loads
   cover bitmaps. Hook the method that receives the loaded Bitmap.
   When a bitmap is received alongside an anime/manga ID:
   - Call extractDominantColor(bitmap) asynchronously (use a background thread via
     Thread { } to avoid blocking UI).
   - Store result in coverColorCache[animeId] = color.

6. Add a settings toggle:
   - Label: "🎨 Per-Show Notification Color"
   - Key: "cover_color_notif"
   - Default: true

ACCEPTANCE CRITERIA:
- After an anime's cover loads at least once, its notifications appear tinted with
  its cover's dominant color instead of the default accent.
- The color is cached in memory (not disk) — it is re-extracted on next app launch.
- The toggle in Settings disables this without affecting other notification behavior.
- Does not crash if the cover bitmap is null or the notification has no series ID.
```

---

---

# BLOCK F — OPEN FROM FILE MANAGER (FULL FEATURE)
> This is the largest feature. It requires its own new files plus hooks in ModuleMain.

---

## PROMPT F-1 — Create the Intent Forwarder Activity

```
You are working on the Android LSPosed module project.

CONTEXT:
Animetail's PlayerActivity is NOT registered as an ACTION_VIEW handler for video files,
so it never appears in Android's "Open with" share sheet. We solve this by creating
a lightweight forwarder Activity IN OUR MODULE that IS registered for video/audio MIME
types, catches the intent, and redirects it to Animetail's PlayerActivity.

TASK:

1. Create a new file:
   app/src/main/java/com/dark/animetailv2/module/MediaForwarderActivity.kt

   Content:
    package com.dark.animetailv2.module

    import android.app.Activity
    import android.content.ComponentName
    import android.content.Intent
    import android.net.Uri
    import android.os.Bundle

    class MediaForwarderActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            val incomingUri: Uri? = intent?.data
            if (incomingUri == null) { finish(); return }

            // Store the URI in a static field so the hook in ModuleMain can read it
            PendingMediaIntent.uri = incomingUri
            PendingMediaIntent.mimeType = intent?.type ?: ""
            PendingMediaIntent.isFromExternal = true

            // Launch Animetail
            val forwardIntent = Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(
                    "com.dark.animetailv2",
                    "eu.kanade.tachiyomi.ui.player.PlayerActivity"
                )
                data = incomingUri
                type = intent?.type
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(forwardIntent)
            } catch (e: Exception) {
                // PlayerActivity not available — launch the app's main activity instead
                val mainIntent = packageManager.getLaunchIntentForPackage("com.dark.animetailv2")
                mainIntent?.let { startActivity(it) }
            }
            finish()
        }
    }

2. Create a new file:
   app/src/main/java/com/dark/animetailv2/module/PendingMediaIntent.kt

   Content:
    package com.dark.animetailv2.module

    import android.net.Uri

    object PendingMediaIntent {
        @Volatile var uri: Uri? = null
        @Volatile var mimeType: String = ""
        @Volatile var isFromExternal: Boolean = false

        fun consume(): Uri? {
            val u = uri
            uri = null
            isFromExternal = false
            return u
        }
    }

3. In app/src/main/AndroidManifest.xml, add the MediaForwarderActivity declaration
   inside the <application> tag. Add it AFTER the existing SettingsActivity entry:

    <activity
        android:name=".MediaForwarderActivity"
        android:exported="true"
        android:theme="@android:style/Theme.Translucent.NoTitleBar"
        android:taskAffinity="com.dark.animetailv2.module.forward"
        android:excludeFromRecents="true">

        <intent-filter>
            <action android:name="android.intent.action.VIEW" />
            <category android:name="android.intent.category.DEFAULT" />
            <data android:mimeType="video/*" />
        </intent-filter>

        <intent-filter>
            <action android:name="android.intent.action.VIEW" />
            <category android:name="android.intent.category.DEFAULT" />
            <data android:mimeType="audio/*" />
        </intent-filter>

        <intent-filter>
            <action android:name="android.intent.action.VIEW" />
            <category android:name="android.intent.category.DEFAULT" />
            <data android:mimeType="application/x-matroska" />
        </intent-filter>

        <intent-filter>
            <action android:name="android.intent.action.VIEW" />
            <category android:name="android.intent.category.DEFAULT" />
            <data android:scheme="content" />
            <data android:mimeType="video/*" />
        </intent-filter>

        <intent-filter>
            <action android:name="android.intent.action.VIEW" />
            <category android:name="android.intent.category.DEFAULT" />
            <data android:scheme="file" />
            <data android:mimeType="video/*" />
        </intent-filter>
    </activity>

ACCEPTANCE CRITERIA:
- MediaForwarderActivity.kt compiles without errors.
- PendingMediaIntent.kt compiles without errors.
- The AndroidManifest has MediaForwarderActivity declared with all five intent-filter blocks.
- The module's own APK now appears in Android's "Open with" dialog for .mkv, .mp4, .avi files.
- Tapping "Animetail Elite" in the share sheet launches Animetail (not a crash).
```

---

## PROMPT F-2 — Hook PlayerActivity to Show the Save Dialog

```
You are working on app/src/main/java/com/dark/animetailv2/module/ModuleMain.kt

CONTEXT:
PendingMediaIntent.isFromExternal will be true when the player was opened via our
MediaForwarderActivity from a file manager. The PlayerActivity.onCreate hook already
exists. We need to intercept that moment and show a bottom-sheet-style dialog asking
the user whether to "Just Play" or "Save to Library".

TASK:

1. In the existing PlayerActivity.onCreate afterHookedMethod, at the TOP (before
   createGlassUI and loadSavedSpeedForAnime), add:

    if (PendingMediaIntent.isFromExternal) {
        val externalUri = PendingMediaIntent.consume() ?: return
        handler.postDelayed({
            showSaveDialog(activity, externalUri)
        }, 500) // small delay so the player UI is fully initialized
        return@afterHookedMethod  // skip normal initialization for now
    }

   Wait — actually don't return. Let the player initialize normally so it starts
   buffering the file in the background. Just show the dialog ON TOP of it:

    if (PendingMediaIntent.isFromExternal) {
        val externalUri = PendingMediaIntent.consume() ?: return
        handler.postDelayed({
            showSaveDialog(activity, externalUri)
        }, 500)
        // do NOT return — let player initialize normally
    }

2. Add a method `showSaveDialog(activity: Activity, uri: Uri)`:
   This creates a dialog that slides up from the bottom:
   a. Create a Dialog with a translucent window style.
   b. Set gravity to Gravity.BOTTOM, width = MATCH_PARENT, height = WRAP_CONTENT.
   c. Set window dim amount 0.5f.
   d. Animate entry: start from translationY = +500f, animate to 0f over 350ms with
      DecelerateInterpolator.

   DIALOG CONTENT (create programmatically, no XML layout):
   - Root LinearLayout: vertical, padding 24dp, background: GradientDrawable with
     color #1A1A22, cornerRadii topLeft=24dp, topRight=24dp.

   - File info row:
       • 🎬 TextView (emoji, 28sp) + vertical LinearLayout:
           - File name: extracted from uri via getFileName(uri) method
           - File details: MIME type + file size if available

   - Divider line: height 1dp, color #2A2A38, margin vertical 16dp

   - "Save to Library?" label: 14sp, color #7A7A9A, centered

   - Anime name input field (EditText):
       • Hint: "Anime name (e.g. Attack on Titan)"
       • Pre-filled: call parseAnimeTitle(getFileName(uri)) (see Prompt F-3)
       • Style: rounded dark input matching SettingsActivity style

   - Episode & Season row (horizontal): two inputs side by side
       • "Episode" EditText, pre-filled from parseEpisode(filename)
       • "Season" EditText, pre-filled from parseSeason(filename)

   - Button row (horizontal, margin top 20dp):
       • "Just Play" button: outlined, text #7A7A9A, no fill — calls:
           dialog.dismiss()
           // player already running, do nothing else
       • "Save & Open" button: fill #7C6FE0, text WHITE — calls:
           saveToLibrary(activity, uri, animeName, episode, season)
           dialog.dismiss()

3. Add a method `getFileName(uri: Uri): String`:
   Try to get the display name from ContentResolver:
    val cursor = activity.contentResolver.query(uri, null, null, null, null)
    cursor?.use { if (it.moveToFirst()) return it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME)) }
   Fall back to uri.lastPathSegment ?: "Unknown file"

ACCEPTANCE CRITERIA:
- When a video file is opened from a file manager, the player opens AND a bottom sheet
  slides up over it.
- The sheet shows the filename and pre-filled anime name / episode / season fields.
- "Just Play" dismisses the sheet and plays the file immediately.
- "Save & Open" is wired up (actual save logic comes in the next prompt).
- The dialog slides in from the bottom with animation.
- The player continues buffering behind the dialog.
```

---

## PROMPT F-3 — Filename Parser and Library Save

```
You are working on app/src/main/java/com/dark/animetailv2/module/ModuleMain.kt

TASK:
Add the filename parser and the library save logic for the "Open from File Manager" feature.

1. Add a method `parseAnimeTitle(filename: String): String`:
   - Remove file extension: filename.substringBeforeLast(".")
   - Remove content in square brackets: replace Regex("\\[.*?\\]") with ""
   - Remove common quality/encoding tags using a list of patterns:
       listOf("1080p", "720p", "480p", "4K", "2160p", "x264", "x265", "HEVC", "AVC",
              "BluRay", "BDRip", "WEBRip", "WEB-DL", "HDTV", "HDR", "SDR", "AAC",
              "MP3", "FLAC", "DTS", "10bit", "8bit")
     For each tag: replace it with "" (case-insensitive).
   - Remove season/episode markers: replace Regex("S\\d{1,2}E\\d{1,3}", RegexOption.IGNORE_CASE) with ""
   - Replace dots, underscores, hyphens with spaces: replace(Regex("[._-]"), " ")
   - Trim and collapse multiple spaces: replace(Regex("\\s+"), " ").trim()
   - Title-case the result: split by space, capitalize each word, rejoin.
   - Return the cleaned title. If empty, return "Unknown Anime".

2. Add a method `parseEpisode(filename: String): String`:
   - Try to match patterns in this order:
     a. S\d\dE(\d{1,3}) → returns the episode number
     b. Episode\s*(\d{1,3}) (case-insensitive) → returns the number
     c. Ep\s*(\d{1,3}) (case-insensitive) → returns the number
     d. \b(\d{3,4})\b → last resort, a standalone 3-4 digit number
   - Return the matched group as a String, or "" if nothing matched.

3. Add a method `parseSeason(filename: String): String`:
   - Match S(\d{1,2})E\d (case-insensitive) → return season number.
   - Or match Season\s*(\d{1,2}) (case-insensitive).
   - Return "" if not found.

4. Add a method `saveToLibrary(activity: Activity, uri: Uri, animeName: String,
   episode: String, season: String)`:
   This creates a synthetic library entry without copying the file.
   a. Resolve the file path from the URI:
       val path = if (uri.scheme == "content") {
           // Get the actual file path via ContentResolver
           activity.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
               ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
               ?: uri.toString()
       } else uri.path ?: uri.toString()

   b. Look up Animetail's database via reflection:
       val app = activity.application
       val dbHandler = try {
           XposedHelpers.callMethod(app, "getDatabase")
       } catch(e: Throwable) {
           XposedBridge.log("EliteMod: Cannot access DB: ${e.message}")
           return
       }

   c. If database reflection fails, fall back to a simpler approach: create a symlink
      in Animetail's local source directory using root:
       val localDir = "/storage/emulated/0/Animetail/local/anime/$animeName"
       val linkPath = "$localDir/$animeName - Episode ${episode.ifEmpty{"1"}}.${path.substringAfterLast(".")}"
       Runtime.getRuntime().exec(arrayOf("su", "-c",
           "mkdir -p \"$localDir\" && ln -sf \"$path\" \"$linkPath\""
       )).waitFor()
       Toast.makeText(activity, "Linked to Library: $animeName", Toast.LENGTH_LONG).show()

   d. Show a success toast: "✓ Added to Library"

5. Also add subtitle auto-detection: before showing the dialog, scan the same directory
   as the video file for subtitle files with matching names:
    private fun findMatchingSubtitles(uri: Uri, activity: Activity): List<String> {
        val dir = File(uri.path ?: return emptyList()).parentFile ?: return emptyList()
        val baseName = File(uri.path!!).nameWithoutExtension
        val subExtensions = listOf("srt", "ass", "ssa", "vtt", "sub")
        return dir.listFiles()
            ?.filter { it.nameWithoutExtension.startsWith(baseName) &&
                       it.extension.lowercase() in subExtensions }
            ?.map { it.absolutePath } ?: emptyList()
    }
   If subtitles are found, show a small pill in the dialog:
   "+ 2 subtitles found" in ACCENT_TEAL color below the file info row.

ACCEPTANCE CRITERIA:
- parseAnimeTitle("[SubGroup] Attack on Titan S04E28 1080p.mkv")
  returns "Attack On Titan" (no brackets, no quality tags, title-cased).
- parseEpisode("One.Piece.Episode.1105.mkv") returns "1105".
- parseSeason("Jujutsu.Kaisen.S02E24.mkv") returns "2".
- saveToLibrary creates a symlink in the local anime folder (requires root).
- Subtitle detection shows the count if .srt/.ass files exist alongside the video.
- "Save & Open" button now works end-to-end.
```

---

---

# BLOCK G — PROGUARD AND BUILD FIXES

---

## PROMPT G-1 — Update ProGuard Rules

```
You are working on app/proguard-rules.pro

TASK:
Add all missing ProGuard keep rules for the new features added in Blocks B through F.
APPEND the following rules to the END of the file. Do NOT remove any existing rules.

--- RULES TO APPEND ---

# Gesture Engine — Vibration APIs
-keep class android.os.VibrationEffect { *; }
-keep class android.os.Vibrator { *; }
-keep class android.os.VibratorManager { *; }

# Sleep Timer — Dialog
-keep class android.app.AlertDialog { *; }
-keep class android.app.AlertDialog$Builder { *; }

# PiP APIs
-keep class android.app.PictureInPictureParams { *; }
-keep class android.app.PictureInPictureParams$Builder { *; }
-keep class android.app.RemoteAction { *; }

# Notification APIs
-keep class android.app.NotificationManager { *; }
-keep class android.service.notification.StatusBarNotification { *; }

# Audio and brightness
-keep class android.media.AudioManager { *; }
-keep class android.view.WindowManager$LayoutParams {
    float screenBrightness;
}

# JSON for speed memory
-keep class org.json.JSONObject { *; }

# Kotlin metadata — required for reflection hooks to resolve overloads
-keepattributes RuntimeVisibleAnnotations
-keep class kotlin.Metadata { *; }

# Window and UI
-keep class android.view.Window { *; }
-keep class android.view.ViewGroup { *; }

# Content resolver for file open feature
-keep class android.provider.OpenableColumns { *; }
-keep class android.provider.MediaStore$MediaColumns { *; }

# Our new classes
-keep class com.dark.animetailv2.module.PendingMediaIntent { *; }
-keep class com.dark.animetailv2.module.MediaForwarderActivity { *; }
-keep class com.dark.animetailv2.module.GestureMode { *; }
```

---

## PROMPT G-2 — Fix Build Configuration

```
You are working on the root build files of the Android project.

TASK:
Update the project's build configuration to ensure everything compiles correctly
with the new features.

1. Open app/build.gradle.kts. Update the following:
   a. Change `minSdk = 23` to `minSdk = 26`
      (Required for VibrationEffect API used in haptic feedback)
   b. Change `versionCode = 3` to `versionCode = 4`
   c. Change `versionName = "1.2"` to `versionName = "2.0"`
   d. In the release buildType, verify `signingConfig = signingConfigs.getByName("debug")`
      is present (this keeps CI builds working without a keystore).

2. Open app/src/main/AndroidManifest.xml. Add these permissions inside the <manifest>
   tag, BEFORE the <application> tag:

    <uses-permission android:name="android.permission.VIBRATE" />
    <uses-permission android:name="android.permission.ACCESS_NOTIFICATION_POLICY" />

   (VIBRATE for haptic feedback; ACCESS_NOTIFICATION_POLICY for the DND feature)

3. Open .github/workflows/build.yml. Verify the Gradle command is:
    run: gradle assembleRelease
   If it says `./gradlew assembleRelease`, change it back to `gradle assembleRelease`
   since the workflow uses the setup-gradle action.
   Also update the artifact name from "Animetail-Elite-Module" to "Animetail-Elite-v2".

4. Run `./gradlew :app:assembleRelease` and fix any compilation errors that appear.
   Common errors to watch for:
   - Unresolved reference: VibratorManager → needs `import android.os.VibratorManager`
   - Unresolved reference: GestureMode → needs to be in the same file or imported
   - Type mismatch on gestureMode comparisons → ensure enum is used correctly

ACCEPTANCE CRITERIA:
- minSdk is 26.
- Version is 2.0 (code 4).
- VIBRATE and ACCESS_NOTIFICATION_POLICY permissions are in the manifest.
- The project builds successfully with `gradle assembleRelease`.
- The output APK is in app/build/outputs/apk/release/.
```

---

---

# FINAL VERIFICATION PROMPT

---

## PROMPT Z — End-to-End Verification Checklist

```
You are working on the complete Animetail Elite Module project.
All previous prompts (A-1 through G-2) have been applied.
Perform a final verification pass.

RUN THESE CHECKS:

1. COMPILATION:
   Run: ./gradlew :app:compileReleaseKotlin
   There must be ZERO errors. Fix any that appear before continuing.

2. PREFS KEY CHECK:
   Run: grep -rn "mod_prefs" app/src/
   Expected result: ZERO occurrences. If any found, replace with "elite_mod_prefs".

3. isHolding CHECK:
   Run: grep -n "isHolding" app/src/main/java/com/dark/animetailv2/module/ModuleMain.kt
   Expected result: ZERO occurrences. If any found, replace with gestureMode comparisons.

4. MANIFEST CHECK:
   Run: grep -c "intent-filter" app/src/main/AndroidManifest.xml
   Expected result: at least 6 (1 for SettingsActivity + 5 for MediaForwarderActivity).

5. PERMISSIONS CHECK:
   Run: grep "uses-permission" app/src/main/AndroidManifest.xml
   Expected: VIBRATE and ACCESS_NOTIFICATION_POLICY are both present.

6. PROGUARD CHECK:
   Run: grep -c "keep" app/proguard-rules.pro
   Expected: at least 20 -keep rules.

7. FULL BUILD:
   Run: gradle assembleRelease
   Expected: BUILD SUCCESSFUL with output APK in app/build/outputs/apk/release/

8. FILE COUNT CHECK:
   Run: find app/src/main/java/com/dark/animetailv2/module/ -name "*.kt" | sort
   Expected files:
   - ModuleMain.kt
   - SettingsActivity.kt
   - MediaForwarderActivity.kt
   - PendingMediaIntent.kt
   All four must exist.

9. SETTINGS KEYS AUDIT:
   Run: grep -o '"[a-z_]*"' app/src/main/java/com/dark/animetailv2/module/SettingsActivity.kt | sort -u
   Expected keys present: elite_mod_prefs, horizontal_drag, silent_update, pip_2x,
   screenshot_bypass, hold_speed, speed_sequence, hold_delay, drag_sensitivity,
   skip_duration, brightness_gesture, volume_gesture, haptic_feedback, per_show_speed,
   auto_dnd, download_progress_ring, cover_color_notif

   Run: grep -o '"[a-z_]*"' app/src/main/java/com/dark/animetailv2/module/ModuleMain.kt | sort -u
   Verify the same keys appear in ModuleMain.kt reads.
   If any key is in one file but not the other, flag it and fix the mismatch.

10. REPORT:
    Print a summary table:
    | Check | Result | Notes |
    |---|---|---|
    | Compilation | PASS/FAIL | |
    | Prefs key | PASS/FAIL | |
    | isHolding removed | PASS/FAIL | |
    | Manifest intents | PASS/FAIL | count |
    | Permissions | PASS/FAIL | |
    | ProGuard rules | PASS/FAIL | count |
    | Full build | PASS/FAIL | APK size |
    | File count | PASS/FAIL | files present |
    | Settings keys match | PASS/FAIL | any mismatches |

If any check FAILS, fix the issue before marking it PASS.
The session is complete only when all 9 checks are PASS.
```

---

*End of Animetail Elite Module CLI Prompt Pack — v2.0*
*Total prompts: 18 (A-1 through G-2) + 1 verification (Z)*
*Estimated implementation time: 3-5 hours with a fast model on good hardware*
