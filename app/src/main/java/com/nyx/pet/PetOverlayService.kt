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
import android.widget.TextView
import androidx.core.app.NotificationCompat

/**
 * Phase 1: Nyx's body.
 * Draws a small draggable bubble that floats above every app.
 * Phase 6 will swap the plain TextView for real sprite/lottie animations.
 * Phase 4 will make tapping the pet open the skill/trigger menu.
 */
class PetOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var petView: View
    private lateinit var params: WindowManager.LayoutParams

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

    /** Lets you drag Nyx anywhere on screen with a finger. */
    private fun attachDragBehavior() {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        petView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(petView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Phase 4: a plain tap (no drag) here opens the skill menu
                    true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::petView.isInitialized) windowManager.removeView(petView)
    }
}
