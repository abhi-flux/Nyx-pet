package com.nyx.pet

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Phase 0/1/2: Home screen.
 * Job: get the two permissions Nyx needs, then launch the overlay service.
 *   1. "Draw over other apps"   -> lets the pet float on screen
 *   2. Accessibility Service    -> lets the pet tap/type on your behalf
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        val overlayBtn = findViewById<Button>(R.id.btnOverlayPermission)
        val accessBtn = findViewById<Button>(R.id.btnAccessibilityPermission)
        val launchBtn = findViewById<Button>(R.id.btnLaunchNyx)

        overlayBtn.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        accessBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        launchBtn.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                startService(Intent(this, PetOverlayService::class.java))
                statusText.text = "Nyx is on screen. Minimize this app."
            } else {
                statusText.text = "Grant 'Draw over other apps' first."
            }
        }
    }
}
