package fr.brume

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/**
 * Service de premier plan : garde le GPS actif écran verrouillé et dévoile la carte.
 * La notification permanente est ce qui autorise Android à le laisser tourner.
 */
class TrackingService : Service(), LocationListener {

    companion object {
        const val ACTION_STOP = "fr.brume.STOP"
        private const val CH_ID = "tracking"
        private const val NOTIF_ID = 1
        @Volatile var running = false
            private set

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, TrackingService::class.java))
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TrackingService::class.java))
        }
    }

    private lateinit var lm: LocationManager
    private var lastNotif = 0L
    private val listener: () -> Unit = {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotif > 5000) { lastNotif = now; updateNotif() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Fog.init(this)
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val ch = NotificationChannel(CH_ID, "Exploration en cours", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, buildNotif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, buildNotif())
            }
        } catch (e: Exception) {
            e.printStackTrace(); stopSelf(); return START_NOT_STICKY
        }
        if (!running) {
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            } catch (e: SecurityException) {
                stopSelf(); return START_NOT_STICKY
            } catch (e: IllegalArgumentException) {
                stopSelf(); return START_NOT_STICKY
            }
            running = true
            Fog.breakPath()
            Fog.addListener(listener)
            getSharedPreferences("brume", MODE_PRIVATE).edit().putBoolean("tracking", true).apply()
            Fog.notifyChanged()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { lm.removeUpdates(this) } catch (e: Exception) { }
        Fog.removeListener(listener)
        running = false
        getSharedPreferences("brume", MODE_PRIVATE).edit().putBoolean("tracking", false).apply()
        Fog.flushAsync()
        Fog.notifyChanged()
        super.onDestroy()
    }

    override fun onLocationChanged(loc: Location) {
        val acc = if (loc.hasAccuracy()) loc.accuracy else 0f
        val spd = if (loc.hasSpeed()) loc.speed.toDouble() else -1.0
        Fog.me = doubleArrayOf(loc.latitude, loc.longitude, acc.toDouble(), spd)
        if (acc <= Fog.maxAcc) {
            Fog.addFix(loc.latitude, loc.longitude, loc.elapsedRealtimeNanos / 1_000_000L)
            Geocoder.maybe(loc.latitude, loc.longitude)
        }
        Fog.notifyChanged()
    }

    // Obligatoires sur Android < 11 (méthodes non « default » à l'époque)
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) { Fog.breakPath() }

    private fun buildNotif(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Brume explore la carte")
            .setContentText(Fog.summary())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as Icon?, "Arrêter", stop).build())
            .build()
    }

    private fun updateNotif() {
        if (!running) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif())
        } catch (e: Exception) { }
    }
}
