package com.vr3d.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Estado de la descarga, compartido entre el servicio y la pantalla. */
object DownloadState {
    @Volatile var running = false
    @Volatile var progress = 0f // -1 = tamaño desconocido
    @Volatile var message = ""
    @Volatile var error: String? = null
    @Volatile var cancelledByUser = false
    @Volatile var resultUri: Uri? = null
    @Volatile var resultName = ""
    @Volatile var downloader: VideoDownloader? = null
    @Volatile var finishedCount = 0
}

/** Descarga en un servicio en primer plano: sigue con la pantalla apagada o fuera de la app. */
class DownloadService : Service() {

    companion object {
        private const val CH = "vr3d_download"
        private const val ID = 4103
        private const val A_START = "com.vr3d.player.DOWNLOAD_START"
        private const val A_CANCEL = "com.vr3d.player.DOWNLOAD_CANCEL"

        fun start(ctx: Context, opt: DlOption) {
            val i = Intent(ctx, DownloadService::class.java)
            i.action = A_START
            i.putExtra("label", opt.label)
            i.putExtra("url", opt.url)
            i.putExtra("audio", opt.audioUrl)
            i.putExtra("hls", opt.hls)
            i.putExtra("file", opt.fileName)
            i.putExtra("ref", opt.referer)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun cancel() {
            DownloadState.cancelledByUser = true
            DownloadState.downloader?.cancelled = true
        }
    }

    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == A_CANCEL) {
            cancel()
            return START_NOT_STICKY
        }
        if (intent?.action != A_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (DownloadState.running) return START_NOT_STICKY

        val opt = DlOption(
            intent.getStringExtra("label") ?: "",
            intent.getStringExtra("url") ?: "",
            intent.getStringExtra("audio"),
            intent.getBooleanExtra("hls", false),
            intent.getStringExtra("file") ?: "video.mp4",
            0L,
            intent.getStringExtra("ref")
        )

        DownloadState.running = true
        DownloadState.progress = 0f
        DownloadState.message = "Conectando…"
        DownloadState.error = null
        DownloadState.cancelledByUser = false
        DownloadState.resultUri = null
        DownloadState.resultName = ""

        val n = buildNotification(opt.fileName, 0f, "Conectando…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, n)
        }

        val dl = VideoDownloader(this)
        DownloadState.downloader = dl
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vr3d:download")
        wl.acquire(6 * 60 * 60 * 1000L)

        Thread {
            var tmp: java.io.File? = null
            try {
                val (file, name) = dl.download(opt) { f, m ->
                    DownloadState.progress = f
                    DownloadState.message = m
                    val now = System.currentTimeMillis()
                    if (now - lastNotify > 1000L) {
                        lastNotify = now
                        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(ID, buildNotification(opt.fileName, f, m))
                    }
                }
                tmp = file
                DownloadState.message = "Guardando en Películas…"
                val uri = Storage.saveVideo(this, file, name, Storage.mimeFor(name))
                Library.touch(this, uri.toString(), name)
                DownloadState.resultUri = uri
                DownloadState.resultName = name
                DownloadState.progress = 1f
                DownloadState.message = "¡Listo! Guardado como " + name
                finishNotification("Descarga terminada", name, uri, name)
            } catch (e: Cancelled) {
                DownloadState.message = "Cancelado"
            } catch (e: Throwable) {
                val msg = e.message ?: e.javaClass.simpleName
                DownloadState.error = msg
                DownloadState.message = "Error: " + msg
                finishNotification("La descarga falló", msg, null, "")
            } finally {
                tmp?.delete()
                if (wl.isHeld) wl.release()
                DownloadState.downloader = null
                DownloadState.running = false
                DownloadState.finishedCount++
                stopForeground(STOP_FOREGROUND_DETACH)
                if (DownloadState.cancelledByUser) {
                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(ID)
                }
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            val ch = NotificationChannel(CH, "Descargas", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    @Suppress("DEPRECATION")
    private fun builder(): Notification.Builder {
        ensureChannel()
        return if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else Notification.Builder(this)
    }

    private fun immutable(): Int = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

    private fun openApp(): PendingIntent {
        val i = Intent(this, DownloadActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 20, i, immutable() or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun buildNotification(name: String, frac: Float, msg: String): Notification {
        val cancel = PendingIntent.getService(
            this, 21, Intent(this, DownloadService::class.java).setAction(A_CANCEL),
            immutable() or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = builder()
        b.setSmallIcon(android.R.drawable.stat_sys_download)
        b.setContentTitle("Descargando video")
        b.setContentText(name + " · " + msg)
        b.setContentIntent(openApp())
        b.setOngoing(true)
        b.setOnlyAlertOnce(true)
        b.setShowWhen(false)
        if (frac >= 0f) b.setProgress(1000, (frac * 1000f).toInt().coerceIn(0, 1000), false) else b.setProgress(0, 0, true)
        b.addAction(Notification.Action.Builder(null, "Cancelar", cancel).build())
        return b.build()
    }

    private fun finishNotification(title: String, text: String, uri: Uri?, name: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val b = builder()
        b.setSmallIcon(android.R.drawable.stat_sys_download_done)
        b.setContentTitle(title)
        b.setContentText(text)
        b.setAutoCancel(true)
        b.setOngoing(false)
        if (uri != null) {
            val i = Intent(this, PlayerActivity::class.java)
            i.data = uri
            i.putExtra("name", name)
            b.setContentIntent(PendingIntent.getActivity(this, 22, i, immutable() or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            b.setContentIntent(openApp())
        }
        nm.notify(ID, b.build())
    }

    override fun onDestroy() {
        DownloadState.downloader?.cancelled = true
        super.onDestroy()
    }
}
