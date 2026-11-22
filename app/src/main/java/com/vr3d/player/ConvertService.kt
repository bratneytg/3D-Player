package com.vr3d.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import java.io.File

/** Estado de la conversión, compartido entre el servicio y la pantalla. */
object ConvertState {
    @Volatile var running = false
    @Volatile var progress = 0f
    @Volatile var message = ""
    @Volatile var error: String? = null
    @Volatile var cancelledByUser = false
    @Volatile var resultUri: Uri? = null
    @Volatile var resultName = ""
    @Volatile var summary = ""
    @Volatile var sourceName = ""
    @Volatile var converter: VideoConverter? = null
    /** Aumenta cada vez que termina una conversión, para que la pantalla sepa que hay algo nuevo. */
    @Volatile var finishedCount = 0
}

/**
 * Convierte el video en un servicio en primer plano: sigue funcionando con la pantalla apagada
 * o si sales de la app, y muestra el progreso en una notificación.
 */
class ConvertService : Service() {

    companion object {
        private const val CH = "vr3d_convert"
        private const val ID = 4102
        private const val A_START = "com.vr3d.player.CONVERT_START"
        private const val A_CANCEL = "com.vr3d.player.CONVERT_CANCEL"

        fun start(ctx: Context, uri: Uri, name: String, strength: Float, highQuality: Boolean) {
            val i = Intent(ctx, ConvertService::class.java)
            i.action = A_START
            i.data = uri
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            i.putExtra("name", name)
            i.putExtra("strength", strength)
            i.putExtra("hq", highQuality)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun cancel(ctx: Context) {
            ConvertState.cancelledByUser = true
            ConvertState.converter?.cancelled = true
        }
    }

    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == A_CANCEL) {
            ConvertState.cancelledByUser = true
            ConvertState.converter?.cancelled = true
            return START_NOT_STICKY
        }
        val uri = intent?.data
        if (intent?.action != A_START || uri == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (ConvertState.running) return START_NOT_STICKY

        val name = intent.getStringExtra("name") ?: "video"
        val strength = intent.getFloatExtra("strength", 1f)
        val hq = intent.getBooleanExtra("hq", false)

        ConvertState.running = true
        ConvertState.progress = 0f
        ConvertState.message = "Preparando…"
        ConvertState.error = null
        ConvertState.cancelledByUser = false
        ConvertState.resultUri = null
        ConvertState.resultName = ""
        ConvertState.summary = ""
        ConvertState.sourceName = name

        val n = buildNotification(name, 0f, "Preparando…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, n)
        }

        val conv = VideoConverter(this)
        ConvertState.converter = conv
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vr3d:convert")
        wl.acquire(6 * 60 * 60 * 1000L)
        val stem = name.substringBeforeLast('.', name).replace(Regex("[^\\w\\- ]"), "").trim().ifEmpty { "video" }

        Thread {
            val tmp = File(cacheDir, "convert_" + System.currentTimeMillis() + ".mp4")
            try {
                val res = conv.convert(uri, tmp, strength, hq) { f, m ->
                    ConvertState.progress = f
                    ConvertState.message = m
                    val now = System.currentTimeMillis()
                    if (now - lastNotify > 1000L) {
                        lastNotify = now
                        notifyProgress(name, f, m)
                    }
                }
                val finalName = stem + "_3D_LR.mp4"
                val outUri = saveToMovies(tmp, finalName)
                Library.touch(this, outUri.toString(), finalName)
                ConvertState.resultUri = outUri
                ConvertState.resultName = finalName
                ConvertState.progress = 1f
                ConvertState.summary = "¡Listo! Guardado como " + finalName + "\n" +
                    res.width + "×" + res.height + " · " + String.format("%.0f", res.seconds) + " s" +
                    (if (res.audio) "" else " · sin audio (el original no era AAC)")
                ConvertState.message = ConvertState.summary
                finishNotification("Conversión terminada", finalName, outUri, finalName)
            } catch (e: Cancelled) {
                ConvertState.message = "Cancelado"
            } catch (e: Throwable) {
                val msg = e.message ?: e.javaClass.simpleName
                ConvertState.error = msg
                ConvertState.message = "Error: " + msg
                finishNotification("La conversión falló", msg, null, "")
            } finally {
                tmp.delete()
                if (wl.isHeld) wl.release()
                ConvertState.converter = null
                ConvertState.running = false
                ConvertState.finishedCount++
                if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH) else stopForeground(false)
                if (ConvertState.cancelledByUser) {
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
            val ch = NotificationChannel(CH, "Conversión a 3D", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun openApp(): PendingIntent {
        val immutable = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val i = Intent(this, ConvertActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 10, i, immutable or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @Suppress("DEPRECATION")
    private fun builder(): Notification.Builder {
        ensureChannel()
        return if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else Notification.Builder(this)
    }

    private fun buildNotification(name: String, frac: Float, msg: String): Notification {
        val immutable = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val cancel = PendingIntent.getService(
            this, 11, Intent(this, ConvertService::class.java).setAction(A_CANCEL),
            immutable or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = builder()
        b.setSmallIcon(android.R.drawable.stat_sys_upload)
        b.setContentTitle("Convirtiendo a 3D")
        b.setContentText(name + " · " + msg)
        b.setContentIntent(openApp())
        b.setOngoing(true)
        b.setOnlyAlertOnce(true)
        b.setShowWhen(false)
        b.setProgress(1000, (frac * 1000f).toInt().coerceIn(0, 1000), false)
        b.addAction(Notification.Action.Builder(null, "Cancelar", cancel).build())
        return b.build()
    }

    private fun notifyProgress(name: String, frac: Float, msg: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ID, buildNotification(name, frac, msg))
    }

    private fun finishNotification(title: String, text: String, uri: Uri?, name: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val b = builder()
        b.setSmallIcon(android.R.drawable.stat_sys_upload_done)
        b.setContentTitle(title)
        b.setContentText(text)
        b.setAutoCancel(true)
        b.setOngoing(false)
        if (uri != null) {
            val immutable = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            val i = Intent(this, PlayerActivity::class.java)
            i.data = uri
            i.putExtra("name", name)
            b.setContentIntent(PendingIntent.getActivity(this, 12, i, immutable or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            b.setContentIntent(openApp())
        }
        nm.notify(ID, b.build())
    }

    private fun saveToMovies(file: File, displayName: String): Uri {
        if (Build.VERSION.SDK_INT < 29) {
            // Android 9 o anterior: se guarda en la carpeta de la app (no hace falta permiso)
            val base = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
            val dir = File(base, "VR3D")
            dir.mkdirs()
            val dest = File(dir, displayName)
            file.inputStream().use { input -> dest.outputStream().use { out -> input.copyTo(out) } }
            return Uri.fromFile(dest)
        }
        val values = ContentValues()
        values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VR3D")
        values.put(MediaStore.Video.Media.IS_PENDING, 1)
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("No se pudo crear el archivo en Películas")
        val os = contentResolver.openOutputStream(uri) ?: throw IllegalStateException("No se pudo escribir el archivo")
        os.use { out -> file.inputStream().use { it.copyTo(out) } }
        val done = ContentValues()
        done.put(MediaStore.Video.Media.IS_PENDING, 0)
        contentResolver.update(uri, done, null, null)
        return uri
    }

    override fun onDestroy() {
        // Si el sistema detiene el servicio, se cancela la conversión en curso.
        ConvertState.converter?.cancelled = true
        super.onDestroy()
    }
}
