package com.tal.carscreen.car

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tal.carscreen.Mirror
import com.tal.carscreen.Prefs
import com.tal.carscreen.TouchService

/**
 * The screen Android Auto shows. The whole background is our surface: the phone screen is
 * mirrored into it, and taps / drags on it are forwarded to the phone.
 */
class MirrorScreen(carContext: CarContext) : Screen(carContext) {

    private val main = Handler(Looper.getMainLooper())
    private val stateListener: () -> Unit = { invalidate() }

    // Drags arrive as many small steps; we add them up and replay one swipe on the phone.
    private var accX = 0f
    private var accY = 0f
    private val flushScroll = Runnable {
        val dx = accX
        val dy = accY
        accX = 0f
        accY = 0f
        TouchService.instance?.swipeByCar(null, dx, dy, 250)
    }

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            val s = surfaceContainer.surface ?: return
            Mirror.setCarSurface(s, surfaceContainer.width, surfaceContainer.height, surfaceContainer.dpi)
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            Mirror.setCarSurface(null, 0, 0, 0)
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {}
        override fun onStableAreaChanged(stableArea: Rect) {}

        override fun onClick(x: Float, y: Float) {
            TouchService.instance?.tapAtCar(x, y)
        }

        override fun onScroll(distanceX: Float, distanceY: Float) {
            // distance = previous - current, so the finger moved by -distance.
            accX -= distanceX
            accY -= distanceY
            main.removeCallbacks(flushScroll)
            main.postDelayed(flushScroll, 120)
        }

        override fun onFling(velocityX: Float, velocityY: Float) {
            main.removeCallbacks(flushScroll)
            accX = 0f
            accY = 0f
            TouchService.instance?.swipeByCar(null, velocityX * 0.15f, velocityY * 0.15f, 100)
        }

        override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {}
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                Mirror.addListener(stateListener)
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                main.removeCallbacks(flushScroll)
                Mirror.removeListener(stateListener)
                Mirror.setCarSurface(null, 0, 0, 0)
            }
        })
    }

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder().setTitle("בית").setOnClickListener {
                    val t = TouchService.instance
                    if (t == null) toast("הפעל את שירות הנגישות בטלפון") else t.openCarHome()
                }.build()
            )
            .addAction(
                Action.Builder().setTitle("חזור").setOnClickListener {
                    TouchService.instance?.back() ?: toast("הפעל את שירות הנגישות בטלפון")
                }.build()
            )
            .apply {
                // Profile switch (only when there is more than one and the guard is on).
                val profiles = Prefs.profiles(carContext)
                if (Prefs.parkedOnly(carContext) && profiles.size > 1) {
                    val active = Prefs.activeProfile(carContext)
                    addAction(
                        Action.Builder().setTitle(active.name).setOnClickListener {
                            if (Mirror.moving) {
                                toast("אפשר להחליף פרופיל רק כשהרכב עומד")
                            } else {
                                val next = (Prefs.activeIndex(carContext) + 1) % profiles.size
                                Prefs.setActiveIndex(carContext, next)
                                val p = profiles[next]
                                toast("${p.name}: מסתיר מעל ${p.kmh} קמ\"ש")
                                Mirror.notifyChanged()
                            }
                        }.build()
                    )
                }
            }
            .build()

        // The PAN button lets you drag (scroll) on the car screen.
        val mapStrip = ActionStrip.Builder().addAction(Action.PAN).build()

        val builder = NavigationTemplate.Builder()
            .setActionStrip(strip)
            .setMapActionStrip(mapStrip)
            .setPanModeListener { }

        when {
            !Mirror.projecting -> builder.setNavigationInfo(
                MessageInfo.Builder("פתח את CarScreen בטלפון")
                    .setText("לחץ \"התחל שיקוף\" ובחר \"כל המסך\"")
                    .build()
            )
            TouchService.instance == null -> builder.setNavigationInfo(
                MessageInfo.Builder("המגע לא פעיל")
                    .setText("בטלפון: הגדרות › נגישות › CarScreen – מגע")
                    .build()
            )
        }
        return builder.build()
    }

    private fun toast(msg: String) = CarToast.makeText(carContext, msg, CarToast.LENGTH_LONG).show()
}
