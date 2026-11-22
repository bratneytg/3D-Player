package com.vr3d.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Puente entre la notificación y el reproductor (que vive en PlayerActivity). */
object PlaybackBridge {
    @Volatile var toggle: (() -> Unit)? = null
    @Volatile var close: (() -> Unit)? = null
}

/**
 * Servicio en primer plano que mantiene viva la app mientras se reproduce, para que el audio
 * siga sonando con la pantalla apagada o al cambiar de app. Muestra una notificación con pausa y cerrar.
 */
class PlaybackService : Service() {

    companion object {
        private const val CH = "vr3d_playback"
        private const val ID = 4101
        private const val A_START = "com.vr3d.player.PLAY_START"
        private const val A_TOGGLE = "com.vr3d.player.PLAY_TOGGLE"
        private const val A_CLOSE = "com.vr3d.player.PLAY_CLOSE"

        fun start(ctx: Context, title: String, playing: Boolean) {
            val i = Intent(ctx, PlaybackService::class.java)
            i.action = A_START
            i.putExtra("title", title)
            i.putExtra("playing", playing)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PlaybackService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            A_TOGGLE -> {
                PlaybackBridge.toggle?.invoke()
                return START_NOT_STICKY
            }
            A_CLOSE -> {
                PlaybackBridge.close?.invoke()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val title = intent?.getStringExtra("title") ?: "VR3D Player"
        val playing = intent?.getBooleanExtra("playing", true) ?: true
        val n = buildNotification(title, playing)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(ID, n)
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(title: String, playing: Boolean): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            val ch = NotificationChannel(CH, "Reproducción", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val immutable = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val open = Intent(this, PlayerActivity::class.java)
        open.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val piOpen = PendingIntent.getActivity(this, 0, open, immutable or PendingIntent.FLAG_UPDATE_CURRENT)
        val piToggle = PendingIntent.getService(
            this, 1, Intent(this, PlaybackService::class.java).setAction(A_TOGGLE), immutable or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val piClose = PendingIntent.getService(
            this, 2, Intent(this, PlaybackService::class.java).setAction(A_CLOSE), immutable or PendingIntent.FLAG_UPDATE_CURRENT
        )

        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else Notification.Builder(this)
        b.setSmallIcon(android.R.drawable.ic_media_play)
        b.setContentTitle(title)
        b.setContentText(if (playing) "Reproduciendo en segundo plano" else "En pausa")
        b.setContentIntent(piOpen)
        b.setOngoing(true)
        b.setOnlyAlertOnce(true)
        b.setShowWhen(false)
        @Suppress("DEPRECATION")
        run {
            b.addAction(
                Notification.Action.Builder(
                    null, if (playing) "Pausa" else "Reproducir", piToggle
                ).build()
            )
            b.addAction(Notification.Action.Builder(null, "Cerrar", piClose).build())
        }
        return b.build()
    }
}
