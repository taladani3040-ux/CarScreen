package com.tal.carscreen

import android.content.Context
import android.graphics.Point
import android.graphics.PointF
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import kotlin.math.min

/**
 * Shared state between the three moving parts, all on the main thread:
 *  - the Android Auto screen (gives us the car's drawing surface and its touches),
 *  - MirrorService (owns the screen capture and the parking guard),
 *  - TouchService (injects taps into the phone).
 */
object Mirror {

    // --- Car side -----------------------------------------------------------
    var carSurface: Surface? = null
        private set
    var carWidth = 0
        private set
    var carHeight = 0
        private set
    var carDpi = 160
        private set

    // --- Phone side ---------------------------------------------------------
    var service: MirrorService? = null
    var projecting = false
    var moving = false        // set by the parking guard

    private val listeners = mutableSetOf<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    fun notifyChanged() { listeners.toList().forEach { it() } }

    fun setCarSurface(surface: Surface?, w: Int, h: Int, dpi: Int) {
        carSurface = surface
        carWidth = w
        carHeight = h
        if (dpi > 0) carDpi = dpi
        service?.updateDisplay()
        notifyChanged()
    }

    val carConnected get() = carSurface != null

    /** Current phone screen size in pixels, following rotation. */
    @Suppress("DEPRECATION")
    fun phoneSize(ctx: Context): Point {
        val dm = ctx.getSystemService(DisplayManager::class.java)
        val p = Point()
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealSize(p)
        return p
    }

    /**
     * The phone picture is scaled to fit the car screen and centred (letterboxed).
     * Converts a point on the car screen to the matching point on the phone,
     * or null if the tap landed in the black bars.
     */
    fun carToPhone(ctx: Context, x: Float, y: Float): PointF? {
        if (carWidth <= 0 || carHeight <= 0) return null
        val p = phoneSize(ctx)
        val scale = min(carWidth.toFloat() / p.x, carHeight.toFloat() / p.y)
        val offX = (carWidth - p.x * scale) / 2f
        val offY = (carHeight - p.y * scale) / 2f
        val px = (x - offX) / scale
        val py = (y - offY) / scale
        if (px < 0 || py < 0 || px > p.x || py > p.y) return null
        return PointF(px, py)
    }

    /** Car-pixel distance to phone-pixel distance. */
    fun carToPhoneScale(ctx: Context): Float {
        if (carWidth <= 0 || carHeight <= 0) return 1f
        val p = phoneSize(ctx)
        val scale = min(carWidth.toFloat() / p.x, carHeight.toFloat() / p.y)
        return 1f / scale
    }
}
