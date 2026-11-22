package com.vr3d.player

import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.StatFs
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registro de errores y generador de informes para poder diagnosticar problemas sin ver la consola. */
object DiagLog {
    private const val FILE = "diag.log"
    private const val MAX_BYTES = 200_000
    private const val MAX_REPORT = 60_000

    @Volatile var glInfo: String = "(aún no se ha creado la pantalla de video)"
    @Volatile private var installed = false

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    private fun stamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    fun stack(e: Throwable): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        return sw.toString().take(6000)
    }

    @Synchronized
    fun add(ctx: Context, tag: String, msg: String) {
        try {
            val f = file(ctx)
            if (f.exists() && f.length() > MAX_BYTES) {
                val keep = f.readText().takeLast(MAX_BYTES / 2)
                f.writeText(keep)
            }
            f.appendText("[" + stamp() + "] " + tag + ": " + msg + "\n")
        } catch (e: Exception) {
            // el diagnóstico nunca debe romper la app
        }
    }

    /** Guarda cualquier cierre inesperado (en cualquier hilo, incluido el de la imagen) para el próximo informe. */
    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            add(app, "CIERRE INESPERADO", "hilo «" + t.name + "»\n" + stack(e))
            prev?.uncaughtException(t, e)
        }
    }

    fun setLastVideo(ctx: Context, uri: String, name: String) {
        ctx.getSharedPreferences("diag", Context.MODE_PRIVATE).edit().putString("uri", uri).putString("name", name).apply()
    }

    private fun lastVideo(ctx: Context): Pair<String, String>? {
        val p = ctx.getSharedPreferences("diag", Context.MODE_PRIVATE)
        val u = p.getString("uri", null) ?: return null
        return Pair(u, p.getString("name", "") ?: "")
    }

    /** Qué se puede saber de un video: si se deja abrir, formato, resolución, códec y si el móvil lo sabe decodificar. */
    fun describeVideo(ctx: Context, uriStr: String): String {
        val sb = StringBuilder()
        val uri = Uri.parse(uriStr)
        sb.append("Esquema: ").append(uri.scheme).append("\n")
        sb.append("Origen (authority): ").append(uri.authority).append("\n")
        sb.append("Nombre: ").append(uri.lastPathSegment).append("\n")
        try {
            sb.append("Tipo MIME: ").append(ctx.contentResolver.getType(uri)).append("\n")
        } catch (e: Exception) {
            sb.append("Tipo MIME: error ").append(e.javaClass.simpleName).append("\n")
        }
        if (uri.scheme == "content") {
            val persisted = ctx.contentResolver.persistedUriPermissions.any { it.uri == uri }
            sb.append("Permiso persistente guardado: ").append(persisted).append("\n")
        }
        try {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { sb.append("Se puede abrir el archivo: sí, tamaño ").append(it.statSize / 1_000_000).append(" MB\n") }
                ?: sb.append("Se puede abrir el archivo: no (el proveedor devolvió nada)\n")
        } catch (e: Exception) {
            sb.append("Se puede abrir el archivo: NO → ").append(e.javaClass.simpleName).append(": ").append(e.message).append("\n")
        }
        val mimes = ArrayList<String>()
        var maxW = 0
        var maxH = 0
        try {
            val ex = MediaExtractor()
            ex.setDataSource(ctx, uri, null)
            sb.append("Pistas: ").append(ex.trackCount).append("\n")
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: "?"
                sb.append("  · ").append(m)
                if (m.startsWith("video/")) {
                    mimes.add(m)
                    val w = if (f.containsKey(MediaFormat.KEY_WIDTH)) f.getInteger(MediaFormat.KEY_WIDTH) else 0
                    val h = if (f.containsKey(MediaFormat.KEY_HEIGHT)) f.getInteger(MediaFormat.KEY_HEIGHT) else 0
                    maxW = maxOf(maxW, w)
                    maxH = maxOf(maxH, h)
                    sb.append(" ").append(w).append("x").append(h)
                    if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) sb.append(" ").append(f.getInteger(MediaFormat.KEY_FRAME_RATE)).append("fps")
                    if (f.containsKey("bitrate")) sb.append(" ").append(f.getInteger("bitrate") / 1000).append("kbps")
                    if (f.containsKey(MediaFormat.KEY_PROFILE)) sb.append(" perfil=").append(f.getInteger(MediaFormat.KEY_PROFILE))
                    if (f.containsKey(MediaFormat.KEY_LEVEL)) sb.append(" nivel=").append(f.getInteger(MediaFormat.KEY_LEVEL))
                    if (f.containsKey("color-transfer")) sb.append(" transferencia-color=").append(f.getInteger("color-transfer"))
                }
                sb.append("\n")
            }
            ex.release()
        } catch (e: Exception) {
            sb.append("No se pudo leer el contenedor: ").append(e.javaClass.simpleName).append(": ").append(e.message).append("\n")
        }
        try {
            val r = MediaMetadataRetriever()
            r.setDataSource(ctx, uri)
            sb.append("Duración: ").append((r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000).append(" s")
            sb.append(" · giro: ").append(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION))
            sb.append(" · bitrate: ").append((r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L) / 1000).append(" kbps\n")
            r.release()
        } catch (e: Exception) {
            sb.append("Metadatos: error ").append(e.javaClass.simpleName).append(": ").append(e.message).append("\n")
        }
        for (m in mimes) sb.append(decoderLine(m, maxW, maxH)).append("\n")
        return sb.toString()
    }

    private fun decoderLine(mime: String, w: Int, h: Int): String {
        return try {
            val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
            if (infos.isEmpty()) return "Decodificador para $mime: NINGUNO (el móvil no puede reproducir este formato)"
            val parts = infos.map {
                val caps = it.getCapabilitiesForType(mime).videoCapabilities
                val okSize = caps?.isSizeSupported(w, h) ?: true
                it.name + (if (okSize) " (admite " + w + "x" + h + ")" else " (NO admite " + w + "x" + h + "; máx " + caps?.supportedWidths?.upper + "x" + caps?.supportedHeights?.upper + ")")
            }
            "Decodificadores para $mime: " + parts.joinToString("; ")
        } catch (e: Exception) {
            "Decodificadores para $mime: error ${e.javaClass.simpleName}"
        }
    }

    private fun device(ctx: Context): String {
        val sb = StringBuilder()
        var ver = "?"
        try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            ver = pi.versionName ?: "?"
        } catch (e: Exception) {
            // sin versión
        }
        sb.append("App: ").append(ctx.packageName).append(" ").append(ver).append("\n")
        sb.append("Móvil: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("Procesador: ").append(Build.SUPPORTED_ABIS.joinToString(",")).append("\n")
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        sb.append("Memoria RAM libre: ").append(mi.availMem / 1_000_000).append(" de ").append(mi.totalMem / 1_000_000).append(" MB · límite de la app: ")
            .append(am.memoryClass).append(" MB (grande: ").append(am.largeMemoryClass).append(" MB)\n")
        try {
            val st = StatFs(ctx.filesDir.path)
            sb.append("Espacio libre en el móvil: ").append(st.availableBytes / 1_000_000).append(" MB\n")
        } catch (e: Exception) {
            // sin dato
        }
        sb.append("Pantalla de video (OpenGL): ").append(glInfo).append("\n")
        return sb.toString()
    }

    /** Construye el informe completo. Puede tardar un poco: llamar fuera del hilo de la interfaz. */
    fun buildReport(ctx: Context, extra: String? = null): String {
        val sb = StringBuilder()
        sb.append("=== INFORME VR3D Player · ").append(stamp()).append(" ===\n\n")
        if (extra != null) sb.append("--- Qué pasó ---\n").append(extra).append("\n\n")
        sb.append("--- Móvil ---\n").append(device(ctx)).append("\n")
        val last = lastVideo(ctx)
        if (last != null) {
            sb.append("--- Último video abierto: «").append(last.second).append("» ---\n")
            sb.append(describeVideo(ctx, last.first)).append("\n")
        }
        val f = file(ctx)
        sb.append("--- Registro de errores (lo más reciente al final) ---\n")
        sb.append(if (f.exists()) f.readText().takeLast(35_000) else "(vacío)").append("\n")
        val s = sb.toString()
        return if (s.length > MAX_REPORT) s.substring(0, MAX_REPORT) else s
    }

    /** Genera el informe en segundo plano, lo copia al portapapeles y abre el menú de compartir. */
    fun share(a: Activity, extra: String? = null) {
        android.widget.Toast.makeText(a, "Generando informe…", android.widget.Toast.LENGTH_SHORT).show()
        val app = a.applicationContext
        Thread {
            val text = try {
                buildReport(app, extra)
            } catch (e: Throwable) {
                "No se pudo generar el informe completo: " + stack(e)
            }
            a.runOnUiThread {
                try {
                    val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Informe VR3D", text))
                } catch (e: Exception) {
                    // sin portapapeles
                }
                val i = Intent(Intent.ACTION_SEND)
                i.type = "text/plain"
                i.putExtra(Intent.EXTRA_SUBJECT, "Informe VR3D Player")
                i.putExtra(Intent.EXTRA_TEXT, text)
                a.startActivity(Intent.createChooser(i, "Enviar informe (también está copiado)"))
            }
        }.start()
    }

    fun clear(ctx: Context) {
        try { file(ctx).delete() } catch (e: Exception) { }
    }
}
