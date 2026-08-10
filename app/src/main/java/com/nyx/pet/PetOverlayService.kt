package com.nyx.pet

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nyx.pet.db.NyxDatabase
import com.nyx.pet.model.PetMood
import com.nyx.pet.model.SkillStep
import com.nyx.pet.player.PlaybackOverlayService
import com.nyx.pet.recorder.RecordingOverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Phase 1 + Phase 6: Nyx's body.
 * Draws a small draggable bubble that floats above every app, with built-in
 * (no external asset files) animations that reflect what Nyx is doing:
 * idle breathing/blinking, a fast pulse while recording, a spin while running
 * a skill, a bounce on success, a shake on error.
 * A tap (not a drag) opens a small menu: Record New Skill / My Skills.
 */
class PetOverlayService : Service() {

    companion object {
        /** Other services (Recorder, Playback) call PetOverlayService.instance?.setMood(...) directly. */
        var instance: PetOverlayService? = null

        private const val COLOR_IDLE = "#B98CFF"
        private const val COLOR_RECORDING = "#FF5C5C"
        private const val COLOR_RUNNING = "#5CD6FF"
        private const val COLOR_SUCCESS = "#5CFF7A"
        private const val COLOR_ERROR = "#FF5C5C"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var petView: View
    private lateinit var params: WindowManager.LayoutParams
    private val scope = CoroutineScope(Dispatchers.Main)

    private var currentMood = PetMood.IDLE
    private var loopAnimator: AnimatorSet? = null
    private val blinkHandler = Handler(Looper.getMainLooper())
    private var blinkRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundWithNotification()
        setupPetView()
        setMood(PetMood.IDLE)
    }

    private fun startForegroundWithNotification() {
        val channelId = "nyx_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Nyx Pet", NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Nyx is watching over your screen")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(1, notification)
    }

    private fun setupPetView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        petView = TextView(this).apply {
            text = "🐾"
            textSize = 32f
            setBackgroundColor(Color.parseColor(COLOR_IDLE))
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        windowManager.addView(petView, params)
        attachDragBehavior()
    }

    private fun overlayDialogType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

    // ---------------------------------------------------------------------
    // Mood / animation system — everything here is built with ObjectAnimator,
    // no image files or Lottie assets needed.
    // ---------------------------------------------------------------------

    /** Called by RecordingOverlayService / PlaybackOverlayService to reflect what Nyx is doing. */
    fun setMood(mood: PetMood) {
        currentMood = mood
        stopLoopingAnimation()
        if (!::petView.isInitialized) return

        when (mood) {
            PetMood.IDLE -> {
                petView.setBackgroundColor(Color.parseColor(COLOR_IDLE))
                startBreathing()
                scheduleNextBlink()
            }
            PetMood.RECORDING -> {
                petView.setBackgroundColor(Color.parseColor(COLOR_RECORDING))
                startPulse(650L)
            }
            PetMood.RUNNING -> {
                petView.setBackgroundColor(Color.parseColor(COLOR_RUNNING))
                startSpin()
            }
            PetMood.SUCCESS -> {
                petView.setBackgroundColor(Color.parseColor(COLOR_SUCCESS))
                playBouncePop { setMood(PetMood.IDLE) }
            }
            PetMood.ERROR -> {
                petView.setBackgroundColor(Color.parseColor(COLOR_ERROR))
                playShake { setMood(PetMood.IDLE) }
            }
        }
    }

    private fun stopLoopingAnimation() {
        loopAnimator?.cancel()
        loopAnimator = null
        blinkRunnable?.let { blinkHandler.removeCallbacks(it) }
        if (::petView.isInitialized) {
            petView.scaleX = 1f
            petView.scaleY = 1f
            petView.rotation = 0f
            petView.translationX = 0f
        }
    }

    /** Slow, gentle scale pulse — Nyx "breathing" while idle. */
    private fun startBreathing() {
        val scaleX = ObjectAnimator.ofFloat(petView, "scaleX", 1f, 1.06f).apply {
            duration = 1400; repeatMode = ObjectAnimator.REVERSE; repeatCount = ObjectAnimator.INFINITE
        }
        val scaleY = ObjectAnimator.ofFloat(petView, "scaleY", 1f, 1.06f).apply {
            duration = 1400; repeatMode = ObjectAnimator.REVERSE; repeatCount = ObjectAnimator.INFINITE
        }
        loopAnimator = AnimatorSet().apply { playTogether(scaleX, scaleY); start() }
    }

    /** A quick vertical squish every few seconds, like a blink. Only while idle. */
    private fun scheduleNextBlink() {
        val runnable = Runnable {
            if (currentMood == PetMood.IDLE && ::petView.isInitialized) {
                ObjectAnimator.ofFloat(petView, "scaleY", 1f, 0.15f, 1f).apply {
                    duration = 180
                }.start()
            }
            scheduleNextBlink()
        }
        blinkRunnable = runnable
        blinkHandler.postDelayed(runnable, Random.nextLong(3000, 6000))
    }

    /** Faster scale pulse — signals "recording in progress." */
    private fun startPulse(durationMs: Long) {
        val scaleX = ObjectAnimator.ofFloat(petView, "scaleX", 1f, 1.15f).apply {
            duration = durationMs; repeatMode = ObjectAnimator.REVERSE; repeatCount = ObjectAnimator.INFINITE
        }
        val scaleY = ObjectAnimator.ofFloat(petView, "scaleY", 1f, 1.15f).apply {
            duration = durationMs; repeatMode = ObjectAnimator.REVERSE; repeatCount = ObjectAnimator.INFINITE
        }
        loopAnimator = AnimatorSet().apply { playTogether(scaleX, scaleY); start() }
    }

    /** Continuous rotation — signals "actively working." */
    private fun startSpin() {
        val rotate = ObjectAnimator.ofFloat(petView, "rotation", 0f, 360f).apply {
            duration = 1000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        loopAnimator = AnimatorSet().apply { play(rotate); start() }
    }

    /** One-shot pop, then calls back (used to auto-return to idle). */
    private fun playBouncePop(onEnd: () -> Unit) {
        val scaleX = ObjectAnimator.ofFloat(petView, "scaleX", 1f, 1.35f, 1f)
        val scaleY = ObjectAnimator.ofFloat(petView, "scaleY", 1f, 1.35f, 1f)
        AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            duration = 350
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { onEnd() }
            })
            start()
        }
    }

    /** One-shot horizontal wobble, then calls back (used to auto-return to idle). */
    private fun playShake(onEnd: () -> Unit) {
        ObjectAnimator.ofFloat(petView, "translationX", 0f, -20f, 20f, -20f, 20f, 0f).apply {
            duration = 400
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { onEnd() }
            })
            start()
        }
    }

    // ---------------------------------------------------------------------

    /** Lets you drag Nyx anywhere on screen with a finger, or tap it to open the menu. */
    private fun attachDragBehavior() {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var downX = 0f
        var downY = 0f
        val tapMovementThreshold = 20f // pixels; below this = a tap, not a drag

        petView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    downX = event.rawX
                    downY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(petView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val movedX = kotlin.math.abs(event.rawX - downX)
                    val movedY = kotlin.math.abs(event.rawY - downY)
                    if (movedX < tapMovementThreshold && movedY < tapMovementThreshold) {
                        showMainMenu()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** Tapping Nyx opens this: Record New Skill, or view/manage what's already taught. */
    private fun showMainMenu() {
        val dialog = Dialog(this).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) window?.setType(overlayDialogType())
            setTitle("Nyx")
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }
        layout.addView(Button(this).apply {
            text = "🔴  Record New Skill"
            setOnClickListener {
                dialog.dismiss()
                startService(Intent(this@PetOverlayService, RecordingOverlayService::class.java))
            }
        })
        layout.addView(Button(this).apply {
            text = "📋  My Skills"
            setOnClickListener {
                dialog.dismiss()
                showMySkillsDialog()
            }
        })
        dialog.setContentView(layout)
        dialog.show()
    }

    /** Lists every saved skill with its step count, with Run and Delete. */
    private fun showMySkillsDialog() {
        scope.launch {
            val skills = withContext(Dispatchers.IO) {
                NyxDatabase.get(this@PetOverlayService).skillDao().getAll()
            }
            if (skills.isEmpty()) {
                Toast.makeText(this@PetOverlayService, "No skills taught yet — tap Nyx and choose Record New Skill", Toast.LENGTH_LONG).show()
                return@launch
            }
            val stepListType = object : TypeToken<List<SkillStep>>() {}.type
            val labels = skills.map { skill ->
                val stepCount = try {
                    (Gson().fromJson<List<SkillStep>>(skill.stepsJson, stepListType)).size
                } catch (e: Exception) { 0 }
                "${skill.name}  ($stepCount steps)"
            }.toTypedArray()

            val dialog = Dialog(this@PetOverlayService).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) window?.setType(overlayDialogType())
                setTitle("My Skills")
            }
            val listView = ListView(this@PetOverlayService).apply {
                adapter = ArrayAdapter(this@PetOverlayService, android.R.layout.simple_list_item_1, labels)
                setOnItemClickListener { _, _, position, _ ->
                    dialog.dismiss()
                    showSkillActionsDialog(skills[position].id, skills[position].name)
                }
            }
            dialog.setContentView(listView)
            dialog.show()
        }
    }

    private fun showSkillActionsDialog(skillId: Long, skillName: String) {
        val dialog = Dialog(this).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) window?.setType(overlayDialogType())
            setTitle(skillName)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }
        layout.addView(Button(this).apply {
            text = "▶  Run Skill"
            setOnClickListener {
                dialog.dismiss()
                val intent = Intent(this@PetOverlayService, PlaybackOverlayService::class.java)
                intent.putExtra(PlaybackOverlayService.EXTRA_SKILL_ID, skillId)
                startService(intent)
            }
        })
        layout.addView(Button(this).apply {
            text = "🗑 Delete"
            setOnClickListener {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        NyxDatabase.get(this@PetOverlayService).skillDao().delete(skillId)
                    }
                    Toast.makeText(this@PetOverlayService, "Deleted '$skillName'", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
        })
        dialog.setContentView(layout)
        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLoopingAnimation()
        instance = null
        if (::petView.isInitialized) windowManager.removeView(petView)
    }
}
