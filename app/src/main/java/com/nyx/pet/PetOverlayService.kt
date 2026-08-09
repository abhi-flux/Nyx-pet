package com.nyx.pet

import android.app.*
import android.content.Intent
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
import com.google.gson.reflect.TypeToken
import com.nyx.pet.db.NyxDatabase
import com.nyx.pet.model.SkillStep
import com.nyx.pet.player.PlaybackOverlayService
import com.nyx.pet.recorder.RecordingOverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 1: Nyx's body.
 * Draws a small draggable bubble that floats above every app.
 * Phase 6 will swap the plain TextView for real sprite/lottie animations.
 * A tap (not a drag) opens a small menu: Record New Skill / My Skills.
 * "Run" from My Skills is a placeholder until Phase 4 builds real replay.
 */
class PetOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var petView: View
    private lateinit var params: WindowManager.LayoutParams
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
        setupPetView()
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

        // Placeholder body — replace with an ImageView + sprite sheet in Phase 6
        petView = TextView(this).apply {
            text = "🐾"
            textSize = 32f
            setBackgroundColor(Color.parseColor("#B98CFF"))
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
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

    /** Lists every saved skill with its step count, with Run (placeholder) and Delete. */
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
        if (::petView.isInitialized) windowManager.removeView(petView)
    }
}

