package com.nyx.pet

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Phase 2: Nyx's hands.
 * This service is what lets Nyx tap/swipe/type at exact coordinates,
 * on top of ANY app, once you enable it in Settings > Accessibility.
 *
 * Phase 3 (Skill Recorder) will log gestures performed here.
 * Phase 4 (Skill Trigger) will call tapAt()/typeText() to replay a saved skill.
 *
 * Keep a static reference so other classes (PetOverlayService, SkillEngine)
 * can command it directly.
 */
class NyxAccessibilityService : AccessibilityService() {

    companion object {
        var instance: NyxAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Phase 3: read on-screen content here to confirm a skill step succeeded
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /** Taps at an exact (x, y) point on screen. */
    fun tapAt(x: Float, y: Float, onDone: (() -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onDone?.invoke()
            }
        }, null)
    }

    /** Swipes from one point to another (for scrolling, dragging). */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /** Types text into whatever input field is currently focused. */
    fun typeIntoFocusedField(text: String): Boolean {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val arguments = android.os.Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
            )
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }
}
