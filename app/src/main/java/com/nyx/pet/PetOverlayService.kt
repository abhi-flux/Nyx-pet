package com.nyx.pet

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
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
 * Draws a small draggable custom character (real artwork, 7 poses in
 * res/drawable: pet_idle, pet_blink, pet_recording, pet_running, pet_success,
 * pet_error, pet_drag) that floats above every app. The same ObjectAnimator
 * system from the first Phase 6 pass drives movement (breathing scale, pulse,
 * bob, bounce, shake) — it now animates the real art instead of a plain shape,
 * and mood changes also swap which pose image is showing.
 * A tap (not a drag) opens a small menu: Record New Skill / My Skills.
 */
class PetOverlayService : Service() {

    companion object {
        /** Other services (Recorder, Playback) call PetOverlayService.instance?.setMood(...) directly. */
        var instance: PetOverlayService? = null
    }

    private lateinit var windowManager: WindowManager
    private lateinit var petView: ImageView
    private lateinit var params: WindowManager.LayoutParams
    private val scope = CoroutineScope(Dispatchers.Main)

    private var currentMood = PetMood.IDLE
    private var loopAnimator: AnimatorSet? = null
    private val blinkHandler = Handler(Looper.getMainLooper())
    private var blinkRunnable: Runnable? = null
    private var isDragging = false

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

        val sizePx = (92 * resources.displayMetrics.density).toInt()
        petView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.pet_idle)
            layoutParams = ViewGroup.LayoutParams(sizePx, sizePx)
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
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
    // Mood / animation system — ObjectAnimator moves the real sprite art
    // (scale/rotation/translation), and setMood also swaps which pose PNG
    // is showing. No Lottie or frame-by-frame animation needed.
    // ---------------------------------------------------------------------

    private fun moodDrawable(mood: PetMood): Int = when (mood) {
        PetMood.IDLE -> R.drawable.pet_idle
        PetMood.RECORDING -> R.drawable.pet_recording
        PetMood.RUNNING -> R.drawable.pet_running
        PetMood.SUCCESS -> R.drawable.pet_success
        PetMood.ERROR -> R.drawable.pet_error
    }

    /** Called by RecordingOverlayService / PlaybackOverlayService to reflect what Nyx is doing. */
    fun setMood(mood: PetMood) {
        currentMood = mood
        stopLoopingAnimation()
        if (!::petView.isInitialized) return
        if (!isDragging) petView.setImageResource(moodDrawable(mood))

        when (mood) {
            PetMood.IDLE -> {
                startBreathing()
                scheduleNextBlink()
            }
            PetMood.RECORDING -> {
                startPulse(650L)
            }
            PetMood.RUNNING -> {
                startBob()
            }
            PetMood.SUCCESS -> {
                playBouncePop { setMood(PetMood.IDLE) }
            }
            PetMood.ERROR -> {
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
            petView.translationY = 0f
        }
    }

    /** Slow, gentle scale pulse — Nyx "breathing" while idle. */
    private fun startBreathing() {
        val scaleX = ObjectAnimator.ofFloat(petView, "scaleX", 1f, 1.05f).apply {
            duration = 1400; repeatMode = ObjectAnimator.REVERSE; repeatCount = ObjectAnimator.INFINITE
        }
        val scaleY = ObjectAnimator.ofFloat(petView, "scaleY", 1f, 1.05f).apply {
            duration = 1400; repeatMode = ObjectAnimator.REVERSE; repeatCount = ObjectAnimator.INFINITE
        }
        loopAnimator = AnimatorSet().apply { playTogether(scaleX, scaleY); start() }
    }

    /** Swaps to the real pet_blink.png briefly every few seconds, then back. Only while idle. */
    private fun scheduleNextBlink() {
        val runnable = Runnable {
            if (currentMood == PetMood.IDLE && ::petView.isInitialized && !isDragging) {
                petView.setImageResource(R.drawable.pet_blink)
                Handler(Looper.getMainLooper()).postDelayed({
                    if (currentMood == PetMood.IDLE && !isDragging) {
                        petView.setImageResource(R.drawable.pet_idle)
                    }
                }, 180)
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

    /** Bouncy up/down bob — signals "actively running a skill." (A full spin looked wrong on real art.) */
    private fun startBob() {
        val bob = ObjectAnimator.ofFloat(petView, "translationY", 0f, -18f).apply {
            duration = 380
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        loopAnimator = AnimatorSet().apply { play(bob); start() }
    }

    /** One-shot pop, then calls back (used to auto-return to idle). */
    private fun playBouncePop(onEnd: () -> Unit) {
        val scaleX = ObjectAnimator.ofFloat(petView, "scaleX", 1f, 1.3f, 1f)
        val scaleY = ObjectAnimator.ofFloat(petView, "scaleY", 1f, 1.3f, 1f)
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
        ObjectAnimator.ofFloat(petView, "translationX", 0f, -18f, 18f, -18f, 18f, 0f).apply {
            duration = 400
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { onEnd() }
            })
            start()
        }
    }

    // ---------------------------------------------------------------------

    /** Lets you drag Nyx anywhere on screen with a finger (swapping to pet_drag.png while doing so), or tap it to open the menu. */
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

                    val movedX = kotlin.math.abs(event.rawX - downX)
                    val movedY = kotlin.math.abs(event.rawY - downY)
                    if (!isDragging && (movedX > tapMovementThreshold || movedY > tapMovementThreshold)) {
                        isDragging = true
                        petView.setImageResource(R.drawable.pet_drag)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val movedX = kotlin.math.abs(event.rawX - downX)
                    val movedY = kotlin.math.abs(event.rawY - downY)
                    if (isDragging) {
                        isDragging = false
                        petView.setImageResource(moodDrawable(currentMood))
                    }
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
