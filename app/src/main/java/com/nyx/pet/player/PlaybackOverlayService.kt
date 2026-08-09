package com.nyx.pet.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nyx.pet.NyxAccessibilityService
import com.nyx.pet.db.NyxDatabase
import com.nyx.pet.model.SkillStep
import com.nyx.pet.model.StepType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 4: Skill Trigger Engine.
 * Loads a saved skill, decodes its JSON steps, and replays them in order via
 * NyxAccessibilityService — the same "hands" built in Phase 2. Shows a small
 * status bar with live progress and a Stop button so a run can always be
 * aborted mid-way.
 *
 * Timing note: real apps load at unpredictable speed. A fixed WAIT step you
 * recorded once (say, 1s) might not always be enough on a slower run — a
 * small fixed buffer is added after every OPEN_APP step for this reason.
 * If replay proves flaky in practice, the next improvement is waiting for
 * actual window-changed accessibility events instead of fixed delays.
 */
class PlaybackOverlayService : Service() {

    companion object {
        const val EXTRA_SKILL_ID = "skill_id"
        private const val INTER_STEP_DELAY_MS = 400L
        private const val APP_LAUNCH_BUFFER_MS = 1200L
    }

    private lateinit var windowManager: WindowManager
    private lateinit var statusBar: LinearLayout
    private lateinit var statusLabel: TextView
    private lateinit var barParams: WindowManager.LayoutParams
    private val scope = CoroutineScope(Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showStatusBar()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val skillId = intent?.getLongExtra(EXTRA_SKILL_ID, -1L) ?: -1L
        if (skillId == -1L) {
            stopSelf()
        } else {
            runSkill(skillId)
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "nyx_playback"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Nyx Playback", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Nyx is running a skill")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(3, notification)
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

    private fun showStatusBar() {
        statusBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F01A1A2E"))
                cornerRadius = 20f
            }
            setPadding(20, 12, 20, 12)
        }
        statusLabel = TextView(this).apply {
            text = "Starting…"
            setTextColor(Color.parseColor("#B98CFF"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusBar.addView(statusLabel)
        statusBar.addView(Button(this).apply {
            text = "⏹ Stop"
            textSize = 11f
            setOnClickListener { stopRun("Stopped by user") }
        })

        barParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 60
        }
        windowManager.addView(statusBar, barParams)
    }

    private fun runSkill(skillId: Long) {
        job?.cancel()
        job = scope.launch {
            val skill = withContext(Dispatchers.IO) {
                NyxDatabase.get(this@PlaybackOverlayService).skillDao().getById(skillId)
            }
            if (skill == null) {
                stopRun("Skill not found")
                return@launch
            }
            if (NyxAccessibilityService.instance == null) {
                Toast.makeText(
                    this@PlaybackOverlayService,
                    "Enable Nyx's Accessibility service first (Settings > Accessibility)",
                    Toast.LENGTH_LONG
                ).show()
                stopRun(null)
                return@launch
            }

            val stepListType = object : TypeToken<List<SkillStep>>() {}.type
            val steps: List<SkillStep> = try {
                Gson().fromJson(skill.stepsJson, stepListType)
            } catch (e: Exception) {
                stopRun("Couldn't read saved steps")
                return@launch
            }

            for ((index, step) in steps.withIndex()) {
                statusLabel.text = "${skill.name}: step ${index + 1}/${steps.size}"
                when (step.type) {
                    StepType.TAP -> {
                        NyxAccessibilityService.instance?.tapAt(step.x ?: 0f, step.y ?: 0f)
                    }
                    StepType.SWIPE -> {
                        NyxAccessibilityService.instance?.swipe(
                            step.x ?: 0f, step.y ?: 0f,
                            step.x2 ?: 0f, step.y2 ?: 0f,
                            step.durationMs
                        )
                    }
                    StepType.TYPE -> {
                        NyxAccessibilityService.instance?.typeIntoFocusedField(step.text ?: "")
                    }
                    StepType.WAIT -> {
                        delay(step.delayMs)
                    }
                    StepType.OPEN_APP -> {
                        step.packageName?.let { pkg ->
                            packageManager.getLaunchIntentForPackage(pkg)?.let { launchIntent ->
                                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(launchIntent)
                            }
                        }
                        delay(APP_LAUNCH_BUFFER_MS) // let the app actually finish loading
                    }
                }
                delay(INTER_STEP_DELAY_MS)
            }

            Toast.makeText(this@PlaybackOverlayService, "Finished: ${skill.name}", Toast.LENGTH_SHORT).show()
            stopRun(null)
        }
    }

    private fun stopRun(reason: String?) {
        job?.cancel()
        reason?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        if (::statusBar.isInitialized) {
            try { windowManager.removeView(statusBar) } catch (e: Exception) { /* already removed */ }
        }
    }
}
