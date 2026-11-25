package com.tal.carscreen

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Owns the screen-capture session. Whenever the car hands us a surface, the phone screen
 * is mirrored straight onto it. Also runs the parked-only guard from the phone's GPS speed.
 */
class MirrorService : Service() {

    companion object {
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val ACTION_STOP = "com.tal.carscreen.STOP"
        private const val CHANNEL = "mirror"
        private const val NOTIF_ID = 1

        // Speed and hold time come from the active profile (Prefs). This one is fixed:
        private const val NO_FIX_RELEASE_MS = 20_000L

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, MirrorService::class.java)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, MirrorService::class.java).setAction(ACTION_STOP))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null

    private var locationManager: LocationManager? = null
    private var guardOn = false
    private var lastKmh = 0f
    private var lastFixAt = 0L
    private var slowSince = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            main.post { stopSelf() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 14+: the foreground service must be running before getMediaProjection().
        goForeground()

        releaseProjection()
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data)
        if (proj == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        proj.registerCallback(projectionCallback, main)
        projection = proj

        Mirror.service = this
        Mirror.projecting = true
        TouchService.instance?.onMirroringChanged(true)
        // Every new session starts with the speed limit on, even if it was removed last time.
        Prefs.setParkedOnly(this, true)
        startGuard()
        updateDisplay()
        Mirror.notifyChanged()
        return START_NOT_STICKY
    }

    /** Point the mirror at the current car surface (or detach it when the car is gone). */
    fun updateDisplay() {
        val proj = projection ?: return
        val surface = Mirror.carSurface
        val vd = display
        if (surface == null) {
            vd?.surface = null
            return
        }
        val w = Mirror.carWidth
        val h = Mirror.carHeight
        val dpi = Mirror.carDpi
        if (vd == null) {
            // Created once per capture session (Android 14+ allows only one).
            display = proj.createVirtualDisplay(
                "CarScreen", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, main
            )
        } else {
            vd.resize(w, h, dpi)
            vd.surface = surface
        }
    }

    // --- Foreground notification -------------------------------------------

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "שיקוף לרכב", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("CarScreen משקף את המסך לרכב")
            .setContentText("הקש כדי לפתוח את ההגדרות")
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "עצור שיקוף", stopIntent).build())
            .setOngoing(true)
            .build()

        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (hasLocation()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        startForeground(NOTIF_ID, n, type)
    }

    // --- Parked-only guard -------------------------------------------------

    private fun hasLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastFixAt = SystemClock.elapsedRealtime()
            lastKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
            evaluate()
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val ticker = object : Runnable {
        override fun run() {
            evaluate()
            main.postDelayed(this, 2_000)
        }
    }

    private fun startGuard() {
        if (guardOn || !Prefs.parkedOnly(this) || !hasLocation()) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, locationListener, Looper.getMainLooper())
        } catch (e: Exception) {
            return
        }
        locationManager = lm
        guardOn = true
        lastFixAt = SystemClock.elapsedRealtime()
        main.post(ticker)
    }

    /** Apply a change of the parked-only setting during a running session. */
    fun refreshGuard() {
        if (Prefs.parkedOnly(this)) startGuard() else stopGuard()
        Mirror.notifyChanged()
    }

    private fun stopGuard() {
        main.removeCallbacks(ticker)
        locationManager?.removeUpdates(locationListener)
        locationManager = null
        guardOn = false
        setMoving(false)
    }

    private fun evaluate() {
        val p = Prefs.activeProfile(this)
        val movingKmh = p.kmh.toFloat()
        val stoppedKmh = maxOf(2f, movingKmh * 0.4f)
        val holdMs = p.holdSec * 1_000L
        val now = SystemClock.elapsedRealtime()
        when {
            lastKmh > movingKmh && now - lastFixAt < NO_FIX_RELEASE_MS -> {
                slowSince = 0L
                setMoving(true)
            }
            // GPS lost (e.g. underground parking) while last known speed was low → treat as parked.
            now - lastFixAt >= NO_FIX_RELEASE_MS && lastKmh <= movingKmh -> setMoving(false)
            lastKmh < stoppedKmh -> {
                if (slowSince == 0L) slowSince = now
                if (now - slowSince >= holdMs) setMoving(false)
            }
        }
    }

    private fun setMoving(v: Boolean) {
        if (Mirror.moving == v) return
        Mirror.moving = v
        TouchService.instance?.setBlocked(v && Mirror.projecting)
        if (v) pauseMedia()
        Mirror.notifyChanged()
    }

    private fun pauseMedia() {
        val am = getSystemService(AudioManager::class.java) ?: return
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
    }

    // --- Teardown ----------------------------------------------------------

    private fun releaseProjection() {
        display?.release()
        display = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
    }

    override fun onDestroy() {
        stopGuard()
        Prefs.setParkedOnly(this, true)
        releaseProjection()
        if (Mirror.service === this) Mirror.service = null
        Mirror.projecting = false
        TouchService.instance?.onMirroringChanged(false)
        Mirror.notifyChanged()
        super.onDestroy()
    }
}
