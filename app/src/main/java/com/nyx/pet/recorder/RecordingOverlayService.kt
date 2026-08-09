package com.nyx.pet.recorder

import android.app.Dialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.nyx.pet.NyxAccessibilityService
import com.nyx.pet.db.NyxDatabase
import com.nyx.pet.db.SkillEntity
import com.nyx.pet.model.SkillStep
import com.nyx.pet.model.StepType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 3 (v2): Skill Recorder.
 *
 * Redesigned after real-device testing found the full-screen invisible "next
 * touch anywhere" catcher was unreliable (froze the screen on some devices/OEMs).
 * Now Tap capture uses a visible DRAGGABLE reticle + explicit Confirm button
 * instead of intercepting arbitrary touches — no full-screen blocking layer,
 * nothing can get stuck, and it's easier to be precise.
 */
class RecordingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var panel: LinearLayout
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var stepCountLabel: TextView
    private val steps = mutableListOf<SkillStep>()
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showPanel()
        toast("Recording started. Use the buttons to add steps.")
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun startForegroundNotification() {
        val channelId = "nyx_recording"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Nyx Recording", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Nyx is recording a skill")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(2, notification)
    }

    /** Small vertical panel, top-left, one button per row — can never overflow off-screen. */
    private fun showPanel() {
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F01A1A2E"))
                cornerRadius = 24f
            }
            setPadding(20, 16, 20, 16)
        }

        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        titleRow.addView(TextView(this).apply {
            text = "Recording"
            setTextColor(Color.parseColor("#B98CFF"))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(Button(this).apply {
            text = "✕"
            textSize = 12f
            setOnClickListener { cancelRecording() }
        })
        panel.addView(titleRow)

        stepCountLabel = TextView(this).apply {
            text = "Steps recorded: 0"
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(0, 4, 0, 8)
        }
        panel.addView(stepCountLabel)

        panel.addView(fullWidthButton("👆  Add Tap") { startTapCapture() })
        panel.addView(fullWidthButton("🔀  Add Swipe") { startSwipeCapture() })
        panel.addView(fullWidthButton("⌨️  Add Type") { showTypeDialog() })
        panel.addView(fullWidthButton("⏱  Add Wait (1s)") { addWaitStep() })
        panel.addView(fullWidthButton("📱  Add Open App") { showAppPickerDialog() })
        panel.addView(fullWidthButton("💾  Save Skill") { showSaveDialog() })

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 100
        }
        windowManager.addView(panel, panelParams)
    }

    private fun fullWidthButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 6 }
            setOnClickListener { action() }
        }
    }

    private fun refreshStepCount() {
        stepCountLabel.text = "Steps recorded: ${steps.size}"
    }

    /**
     * Tap capture v2: shows a small draggable red reticle. Drag it to the exact
     * spot you want, then press Confirm. No full-screen invisible layer, so
     * nothing else on screen is ever blocked.
     */
    private fun startTapCapture() {
        panel.visibility = View.GONE

        val reticle = TextView(this).apply {
            text = "◎"
            textSize = 34f
            setTextColor(Color.parseColor("#FF5C5C"))
        }
        val reticleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 300
            y = 600
        }
        windowManager.addView(reticle, reticleParams)

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        reticle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = reticleParams.x
                    startY = reticleParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    reticleParams.x = startX + (event.rawX - touchX).toInt()
                    reticleParams.y = startY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(reticle, reticleParams)
                    true
                }
                else -> false
            }
        }

        val confirmRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F01A1A2E"))
                cornerRadius = 20f
            }
            setPadding(16, 10, 16, 10)
        }
        val confirmParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 140
        }

        confirmRow.addView(Button(this).apply {
            text = "Cancel"
            setOnClickListener {
                windowManager.removeView(reticle)
                windowManager.removeView(confirmRow)
                panel.visibility = View.VISIBLE
            }
        })
        confirmRow.addView(Button(this).apply {
            text = "✓ Confirm Tap Here"
            setOnClickListener {
                // Reticle's on-screen center point
                val x = reticleParams.x + (reticle.width / 2f)
                val y = reticleParams.y + (reticle.height / 2f)
                windowManager.removeView(reticle)
                windowManager.removeView(confirmRow)
                steps.add(SkillStep(type = StepType.TAP, x = x, y = y))
                NyxAccessibilityService.instance?.tapAt(x, y)
                refreshStepCount()
                toast("Tap step added at (${x.toInt()}, ${y.toInt()})")
                panel.visibility = View.VISIBLE
            }
        })
        windowManager.addView(confirmRow, confirmParams)
    }

    /**
     * Swipe capture: shows two draggable markers — green (start) and red (end).
     * Drag both into position, press Confirm, and Nyx performs the swipe live
     * and records it as a step. Same freeze-proof approach as Tap capture.
     */
    private fun startSwipeCapture() {
        panel.visibility = View.GONE

        fun makeMarker(symbol: String, color: String, startXpx: Int, startYpx: Int): Pair<TextView, WindowManager.LayoutParams> {
            val marker = TextView(this).apply {
                text = symbol
                textSize = 34f
                setTextColor(Color.parseColor(color))
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = startXpx
                y = startYpx
            }
            return marker to params
        }

        fun makeDraggable(view: TextView, params: WindowManager.LayoutParams) {
            var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
            view.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x; startY = params.y
                        touchX = event.rawX; touchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (event.rawX - touchX).toInt()
                        params.y = startY + (event.rawY - touchY).toInt()
                        windowManager.updateViewLayout(view, params)
                        true
                    }
                    else -> false
                }
            }
        }

        val (startMarker, startParams) = makeMarker("◉", "#5CFF7A", 250, 500)   // green = start
        val (endMarker, endParams) = makeMarker("◉", "#FF5C5C", 250, 1000)      // red = end
        windowManager.addView(startMarker, startParams)
        windowManager.addView(endMarker, endParams)
        makeDraggable(startMarker, startParams)
        makeDraggable(endMarker, endParams)

        val confirmRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F01A1A2E"))
                cornerRadius = 20f
            }
            setPadding(16, 10, 16, 10)
        }
        val confirmParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 140
        }

        fun cleanup() {
            windowManager.removeView(startMarker)
            windowManager.removeView(endMarker)
            windowManager.removeView(confirmRow)
            panel.visibility = View.VISIBLE
        }

        confirmRow.addView(TextView(this).apply {
            text = "Green = start, Red = end"
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(0, 0, 16, 0)
        })
        confirmRow.addView(Button(this).apply {
            text = "Cancel"
            setOnClickListener { cleanup() }
        })
        confirmRow.addView(Button(this).apply {
            text = "✓ Confirm Swipe"
            setOnClickListener {
                val x1 = startParams.x + (startMarker.width / 2f)
                val y1 = startParams.y + (startMarker.height / 2f)
                val x2 = endParams.x + (endMarker.width / 2f)
                val y2 = endParams.y + (endMarker.height / 2f)
                cleanup()
                steps.add(SkillStep(type = StepType.SWIPE, x = x1, y = y1, x2 = x2, y2 = y2, durationMs = 300))
                NyxAccessibilityService.instance?.swipe(x1, y1, x2, y2, 300)
                refreshStepCount()
                toast("Swipe step added")
            }
        })
        windowManager.addView(confirmRow, confirmParams)
    }

    private fun showTypeDialog() {
        val input = EditText(this).apply { hint = "Text to type" }
        val dialog = Dialog(this).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            setTitle("Add Type Step")
            setContentView(LinearLayout(this@RecordingOverlayService).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 24, 32, 8)
                addView(input)
                addView(Button(this@RecordingOverlayService).apply {
                    text = "Add"
                    setOnClickListener {
                        val text = input.text.toString()
                        if (text.isNotBlank()) {
                            steps.add(SkillStep(type = StepType.TYPE, text = text))
                            NyxAccessibilityService.instance?.typeIntoFocusedField(text)
                            refreshStepCount()
                            toast("Type step added: \"$text\"")
                        }
                        dismiss()
                    }
                })
            })
        }
        dialog.show()
    }

    private fun addWaitStep() {
        steps.add(SkillStep(type = StepType.WAIT, delayMs = 1000))
        refreshStepCount()
        Toast.makeText(
            this,
            "Wait step added — when this skill replays, Nyx will pause 1 second here before the next step",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showAppPickerDialog() {
        val pm = packageManager
        val launchableApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { pm.getApplicationLabel(it).toString() }

        val labels = launchableApps.map { pm.getApplicationLabel(it).toString() }.toTypedArray()

        val dialog = Dialog(this).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            setTitle("Pick an app to open")
        }
        val listView = ListView(this).apply {
            adapter = ArrayAdapter(this@RecordingOverlayService, android.R.layout.simple_list_item_1, labels)
            setOnItemClickListener { _, _, position, _ ->
                val pkg = launchableApps[position].packageName
                steps.add(SkillStep(type = StepType.OPEN_APP, packageName = pkg))
                pm.getLaunchIntentForPackage(pkg)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(it)
                }
                refreshStepCount()
                toast("Open App step added: ${labels[position]}")
                dialog.dismiss()
            }
        }
        dialog.setContentView(listView)
        dialog.show()
    }

    private fun showSaveDialog() {
        if (steps.isEmpty()) {
            toast("Add at least one step before saving")
            return
        }
        val input = EditText(this).apply { hint = "Skill name e.g. search_and_log" }
        val dialog = Dialog(this).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            setTitle("Save Skill (${steps.size} steps)")
            setContentView(LinearLayout(this@RecordingOverlayService).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 24, 32, 8)
                addView(input)
                addView(Button(this@RecordingOverlayService).apply {
                    text = "Save & Stop Recording"
                    setOnClickListener {
                        val name = input.text.toString().ifBlank { "unnamed_skill_${System.currentTimeMillis()}" }
                        saveSkill(name)
                        dismiss()
                        stopSelf()
                    }
                })
            })
        }
        dialog.show()
    }

    private fun cancelRecording() {
        toast("Recording cancelled — nothing saved")
        stopSelf()
    }

    private fun saveSkill(name: String) {
        val json = Gson().toJson(steps)
        val stepCount = steps.size
        scope.launch {
            withContext(Dispatchers.IO) {
                NyxDatabase.get(this@RecordingOverlayService).skillDao()
                    .insert(SkillEntity(name = name, stepsJson = json))
            }
            Toast.makeText(
                this@RecordingOverlayService,
                "Saved skill '$name' with $stepCount steps",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::panel.isInitialized) {
            try { windowManager.removeView(panel) } catch (e: Exception) { /* already removed */ }
        }
    }
}
