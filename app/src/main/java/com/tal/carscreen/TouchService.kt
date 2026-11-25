package com.tal.carscreen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Accessibility service that:
 *  - replays car-screen taps / swipes on the phone (dispatchGesture, no root needed),
 *  - presses Back / opens the car home screen,
 *  - forces the phone to landscape while mirroring so the picture fills the car screen,
 *  - covers the screen (and so the car screen) while driving.
 */
class TouchService : AccessibilityService() {

    companion object {
        var instance: TouchService? = null
            private set
    }

    private var rotationView: View? = null
    private var blockView: View? = null

    override fun onServiceConnected() {
        instance = this
        if (Mirror.projecting) onMirroringChanged(true)
        Mirror.notifyChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        removeRotation()
        setBlocked(false)
        instance = null
        Mirror.notifyChanged()
        return super.onUnbind(intent)
    }

    // --- Gestures ----------------------------------------------------------

    fun tapAtCar(x: Float, y: Float) {
        val p = Mirror.carToPhone(this, x, y) ?: return
        val path = Path().apply { moveTo(p.x, p.y) }
        dispatch(path, 0, 60)
    }

    /** Swipe on the phone by a distance measured in car pixels, starting from the car point (or screen centre). */
    fun swipeByCar(startCar: PointF?, dxCar: Float, dyCar: Float, durationMs: Long) {
        val size = Mirror.phoneSize(this)
        val k = Mirror.carToPhoneScale(this)
        val start = startCar?.let { Mirror.carToPhone(this, it.x, it.y) }
            ?: PointF(size.x / 2f, size.y / 2f)
        val endX = clamp(start.x + dxCar * k, 1f, size.x - 2f)
        val endY = clamp(start.y + dyCar * k, 1f, size.y - 2f)
        if (abs(endX - start.x) < 4 && abs(endY - start.y) < 4) return
        val path = Path().apply {
            moveTo(start.x, start.y)
            lineTo(endX, endY)
        }
        dispatch(path, 0, durationMs)
    }

    private fun dispatch(path: Path, startMs: Long, durationMs: Long) {
        if (blockView != null) return
        val stroke = GestureDescription.StrokeDescription(path, startMs, max(1L, durationMs))
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun clamp(v: Float, lo: Float, hi: Float) = min(max(v, lo), hi)

    fun back() {
        if (blockView == null) performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun openCarHome() {
        if (blockView != null) return
        val i = Intent(this, CarLauncherActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(i)
    }

    // --- Landscape while mirroring ----------------------------------------

    fun onMirroringChanged(active: Boolean) {
        if (active && Prefs.forceLandscape(this)) addRotation() else removeRotation()
        if (!active) setBlocked(false) else if (Mirror.moving) setBlocked(true)
    }

    private fun overlayType(): Int =
        if (Settings.canDrawOverlays(this)) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    private fun addRotation() {
        if (rotationView != null) return
        val wm = getSystemService(WindowManager::class.java)
        val lp = WindowManager.LayoutParams(
            1, 1, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            screenOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        val v = View(this)
        try {
            wm.addView(v, lp)
            rotationView = v
        } catch (_: Exception) {
        }
    }

    private fun removeRotation() {
        val v = rotationView ?: return
        try { getSystemService(WindowManager::class.java).removeView(v) } catch (_: Exception) {}
        rotationView = null
    }

    // --- Parked-only cover -------------------------------------------------

    fun setBlocked(blocked: Boolean) {
        val wm = getSystemService(WindowManager::class.java)
        if (blocked && blockView == null) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.BLACK)
                addView(TextView(context).apply {
                    text = "הצפייה זמינה רק כשהרכב עומד"
                    textSize = 28f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                })
                addView(TextView(context).apply {
                    text = "המסך יחזור כשתעצור"
                    textSize = 18f
                    setTextColor(Color.LTGRAY)
                    gravity = Gravity.CENTER
                    setPadding(0, 24, 0, 0)
                })
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.OPAQUE
            )
            try {
                wm.addView(box, lp)
                blockView = box
            } catch (_: Exception) {
            }
        } else if (!blocked && blockView != null) {
            try { wm.removeView(blockView) } catch (_: Exception) {}
            blockView = null
        }
    }
}
