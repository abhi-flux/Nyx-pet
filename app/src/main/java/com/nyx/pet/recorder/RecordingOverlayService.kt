package com.nyx.pet.recorder

import android.app.Dialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
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
 * Phase 3: Skill Recorder.
 * Shows a small always-on-top control bar with 5 buttons. Each button press
 * either records a step immediately (Wait) or captures your next real
 * interaction (Tap / Type / App) and performs it live via NyxAccessibilityService,
 * so recording a skill feels the same as just using your phone normally.
 */
class RecordingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var controlBar: LinearLayout
    private lateinit var barParams: WindowManager.LayoutParams
    private val steps = mutableListOf<SkillStep>()
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showControlBar()
        Toast.makeText(this, "Recording started. Build your skill step by step.", Toast.LENGTH_LONG).show()
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

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

    private fun showControlBar() {
        controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#EE1A1A2E"))
            setPadding(12, 12, 12, 12)
        }
        controlBar.addView(makeButton("Tap") { startTapCapture() })
        controlBar.addView(makeButton("Type") { showTypeDialog() })
        controlBar.addView(makeButton("Wait") { addWaitStep() })
        controlBar.addView(makeButton("App") { showAppPickerDialog() })
        controlBar.addView(makeButton("Save") { showSaveDialog() })

        barParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 80
        }
        windowManager.addView(controlBar, barParams)
    }

    private fun makeButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            setOnClickListener { action() }
        }
    }

    /** Next real touch anywhere on screen becomes a TAP step, and is actually performed. */
    private fun startTapCapture() {
        controlBar.visibility = View.GONE
        val capture = View(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        capture.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val x = event.rawX
                val y = event.rawY
                windowManager.removeView(capture)
                steps.add(SkillStep(type = StepType.TAP, x = x, y = y))
                NyxAccessibilityService.instance?.tapAt(x, y)
                controlBar.visibility = View.VISIBLE
                Toast.makeText(this, "Tap step added (${x.toInt()}, ${y.toInt()})", Toast.LENGTH_SHORT).show()
            }
            true
        }
        windowManager.addView(capture, params)
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
        Toast.makeText(this, "1 second wait step added", Toast.LENGTH_SHORT).show()
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
                dialog.dismiss()
            }
        }
        dialog.setContentView(listView)
        dialog.show()
    }

    private fun showSaveDialog() {
        if (steps.isEmpty()) {
            Toast.makeText(this, "Add at least one step before saving", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply { hint = "Skill name e.g. search_and_log" }
        val dialog = Dialog(this).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            setTitle("Save Skill")
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
        if (::controlBar.isInitialized) {
            try { windowManager.removeView(controlBar) } catch (e: Exception) { /* already removed */ }
        }
    }
}
