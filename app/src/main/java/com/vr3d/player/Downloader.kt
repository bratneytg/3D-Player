package com.vr3d.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.ByteBuffer

/** Una opción de descarga (una calidad). */
class DlOption(
    val label: String,
    val url: String,
    val audioUrl: String?,
    val hls: Boolean,
    val fileName: String,
    val sizeBytes: Long,
    val referer: String? = null
)

class DlAnalysis(val options: List<DlOption>, val note: String)

/**
 * Descarga videos desde un enlace directo a un archivo (sin recompresión: es el archivo original, sin pérdida)
 * o desde un stream HLS (.m3u8) sin cifrar, con elección de calidad.
 */
class VideoDownloader(private val ctx: Context) {
    @Volatile
    var cancelled = false
    private var referer: String? = null

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
        private val VIDEO_EXT = setOf("mp4", "m4v", "mkv", "webm", "mov", "3gp", "avi", "ts")

        fun cleanName(raw: String): String =
            raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').ifEmpty { "video" }
    }

    // ------------------------------------------------------------------ red

    private fun open(url: String, method: String = "GET"): HttpURLConnection {
        var cur = url
        for (hop in 0 until 8) {
            val conn = URL(cur).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.requestMethod = method
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "*/*")
            referer?.let { conn.setRequestProperty("Referer", it) }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw IllegalStateException("Redirección sin destino")
                conn.disconnect()
                cur = HlsParser.resolve(cur, loc)
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IllegalStateException("El servidor respondió con el error " + code)
            }
            return conn
        }
        throw IllegalStateException("Demasiadas redirecciones")
    }

    private fun readText(url: String): String {
        val c = open(url)
        try {
            val bytes = c.inputStream.use { it.readBytes() }
            return String(bytes, Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }

    // ------------------------------------------------------------------ análisis

    fun analyze(input: String): DlAnalysis {
        val url = input.trim()
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            throw IllegalArgumentException("El enlace debe empezar por http:// o https://")
        }
        referer = null
        return analyzeUrl(url, true)
    }

    private fun analyzeUrl(url: String, allowPage: Boolean): DlAnalysis {
        val path = url.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase()
        val baseName = cleanName(
            URLDecoder.decode(path.substringAfterLast('/').substringBeforeLast('.', "video"), "UTF-8")
        )

        if (ext == "m3u8") return analyzeHls(url, baseName)

        val conn = open(url)
        try {
            val type = (conn.contentType ?: "").lowercase()
            val len = conn.contentLengthLong
            if (type.contains("mpegurl")) {
                conn.disconnect()
                return analyzeHls(url, baseName)
            }
            val looksVideo = type.startsWith("video/") || ext in VIDEO_EXT ||
                ((type.contains("octet-stream")) && ext in VIDEO_EXT)
            if (type.contains("html") && allowPage) {
                val body = readLimited(conn, 4_000_000)
                conn.disconnect()
                return analyzePage(url, String(body, Charsets.UTF_8))
            }
            if (type.startsWith("text/") || type.contains("html") || !looksVideo) {
                throw IllegalArgumentException(
                    "Ese enlace no es un archivo de video. Pega el enlace de una página con el video, " +
                        "el enlace directo al archivo (.mp4, .mkv, .webm…) o una lista .m3u8."
                )
            }
            val fileExt = if (ext in VIDEO_EXT) ext else when {
                type.contains("webm") -> "webm"
                type.contains("matroska") -> "mkv"
                type.contains("quicktime") -> "mov"
                else -> "mp4"
            }
            val sizeTxt = if (len > 0) " · " + fmtSize(len) else ""
            val opt = DlOption("Original, sin pérdida de calidad" + sizeTxt, url, null, false, "$baseName.$fileExt", len, referer)
            return DlAnalysis(listOf(opt), "Es un archivo de video directo: solo hay una calidad (la original). Se descarga tal cual, sin recomprimir.")
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(conn: HttpURLConnection, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        conn.inputStream.use { input ->
            while (out.size() < max) {
                val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    /** Busca en el código de una página web los enlaces a video y los analiza uno por uno. */
    private fun analyzePage(pageUrl: String, html: String): DlAnalysis {
        val found = PageScan.findVideoLinks(pageUrl, html)
        if (found.isEmpty()) {
            throw IllegalArgumentException(
                "No encontré ningún video descargable en esa página. Puede que el reproductor lo cargue de forma " +
                    "protegida o con scripts; en ese caso necesitas el enlace directo del archivo."
            )
        }
        referer = pageUrl // muchas webs solo sirven el video si se pide desde su propia página
        val all = ArrayList<DlOption>()
        var n = 0
        for (u in found) {
            if (cancelled) break
            try {
                val a = analyzeUrl(u, false)
                n++
                for (o in a.options) {
                    all.add(DlOption("Video " + n + " · " + o.label, o.url, o.audioUrl, o.hls, o.fileName, o.sizeBytes, pageUrl))
                }
            } catch (e: Exception) {
                // este enlace no era un video descargable; se ignora
            }
        }
        if (all.isEmpty()) {
            throw IllegalArgumentException(
                "Encontré enlaces en la página pero ninguno se pudo descargar (pueden estar protegidos o cifrados)."
            )
        }
        return DlAnalysis(all, "Encontré " + n + (if (n == 1) " video" else " videos") + " en la página. Elige cuál y con qué calidad.")
    }

    private fun analyzeHls(url: String, baseName: String): DlAnalysis {
        val text = readText(url)
        if (!text.contains("#EXTM3U")) throw IllegalArgumentException("La lista .m3u8 no es válida")
        if (!HlsParser.isMaster(text)) {
            val m = HlsParser.parseMedia(text, url)
            checkMedia(m)
            val opt = DlOption("Única calidad disponible" + durTxt(m.durationSec), url, null, true, "$baseName.mp4", 0L, referer)
            return DlAnalysis(listOf(opt), "Stream HLS: se descargan todos los trozos y se unen en un solo archivo.")
        }
        val master = HlsParser.parseMaster(text, url)
        if (master.variants.isEmpty()) throw IllegalArgumentException("La lista no contiene calidades")
        val list = ArrayList<DlOption>()
        for ((idx, v) in master.variants.withIndex()) {
            val res = if (v.height > 0) v.height.toString() + "p" else "calidad " + (idx + 1)
            val rate = String.format("%.1f Mbps", v.bandwidth / 1_000_000.0)
            val tag = if (idx == 0) " · la mejor" else ""
            val audio = v.audioGroup?.let { master.audioGroups[it] }
            list.add(DlOption(res + " · " + rate + tag, v.url, audio, true, baseName + "_" + (if (v.height > 0) v.height.toString() + "p" else "q" + (idx + 1)) + ".mp4", 0L, referer))
        }
        return DlAnalysis(list, "Stream HLS con " + list.size + " calidades. Elige una; las más bajas ocupan menos espacio y datos.")
    }

    private fun checkMedia(m: HlsParser.Media) {
        if (m.encrypted) throw IllegalArgumentException("Este stream está cifrado y no se puede descargar.")
        if (m.live) throw IllegalArgumentException("Es una emisión en directo; solo se pueden descargar videos completos.")
        if (m.segments.isEmpty()) throw IllegalArgumentException("La lista no contiene fragmentos de video")
    }

    private fun durTxt(sec: Double): String {
        if (sec <= 0.0) return ""
        val s = sec.toLong()
        return " · " + Ui.fmtTime(s * 1000L)
    }

    private fun fmtSize(b: Long): String =
        if (b >= 1_000_000_000L) String.format("%.2f GB", b / 1e9) else String.format("%.0f MB", b / 1e6)

    // ------------------------------------------------------------------ descarga

    /** Descarga la opción elegida a un archivo temporal y devuelve (archivo, nombre final). */
    fun download(opt: DlOption, progress: (Float, String) -> Unit): Pair<File, String> {
        referer = opt.referer
        return if (opt.hls) downloadHls(opt, progress) else downloadDirect(opt, progress)
    }

    private fun checkSpace(needBytes: Long) {
        val free = ctx.cacheDir.usableSpace
        if (needBytes > 0 && free < needBytes + 50_000_000L) {
            throw IllegalStateException("No hay espacio suficiente: faltan " + fmtSize(needBytes + 50_000_000L - free))
        }
    }

    private fun downloadDirect(opt: DlOption, progress: (Float, String) -> Unit): Pair<File, String> {
        val tmp = File(ctx.cacheDir, "dl_" + System.currentTimeMillis() + ".part")
        val conn = open(opt.url)
        try {
            val total = conn.contentLengthLong
            checkSpace(total)
            var done = 0L
            val t0 = System.currentTimeMillis()
            var lastReport = 0L
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        if (cancelled) throw Cancelled()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 400L) {
                            lastReport = now
                            val speed = done / 1e6 / max1((now - t0) / 1000.0)
                            val frac = if (total > 0) done.toFloat() / total.toFloat() else -1f
                            val of = if (total > 0) " de " + fmtSize(total) else ""
                            progress(frac, "Descargando " + fmtSize(done) + of + String.format(" · %.1f MB/s", speed))
                        }
                    }
                }
            }
            if (total > 0 && done < total) throw IllegalStateException("La descarga se cortó antes de terminar")
            progress(1f, "Descarga completa")
            return Pair(tmp, opt.fileName)
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun max1(v: Double): Double = if (v < 0.001) 0.001 else v

    private fun fetchToFile(url: String, out: FileOutputStream, buf: ByteArray): Long {
        var total = 0L
        val c = open(url)
        try {
            c.inputStream.use { input ->
                while (true) {
                    if (cancelled) throw Cancelled()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                }
            }
        } finally {
            c.disconnect()
        }
        return total
    }

    /** Baja una lista de medios (init + fragmentos) a un único archivo, avanzando de [from] a [to] en el progreso. */
    private fun downloadMedia(
        playlistUrl: String, target: File, label: String, from: Float, to: Float, progress: (Float, String) -> Unit
    ) {
        val m = HlsParser.parseMedia(readText(playlistUrl), playlistUrl)
        checkMedia(m)
        val buf = ByteArray(128 * 1024)
        val all = ArrayList<String>()
        if (m.initUrl != null) all.add(m.initUrl)
        all.addAll(m.segments)
        var bytes = 0L
        FileOutputStream(target).use { out ->
            for ((i, u) in all.withIndex()) {
                if (cancelled) throw Cancelled()
                bytes += fetchToFile(u, out, buf)
                if (i % 3 == 0 || i == all.size - 1) {
                    val f = from + (to - from) * (i + 1) / all.size.toFloat()
                    progress(f, label + " " + (i + 1) + "/" + all.size + " · " + fmtSize(bytes))
                }
                if (i == 4) {
                    // con unos pocos trozos se estima el tamaño final para avisar si no cabe
                    checkSpace((bytes.toDouble() / 5.0 * all.size).toLong())
                }
            }
        }
    }

    private fun downloadHls(opt: DlOption, progress: (Float, String) -> Unit): Pair<File, String> {
        val stamp = System.currentTimeMillis()
        val vRaw = File(ctx.cacheDir, "hls_v_$stamp.bin")
        val aRaw = File(ctx.cacheDir, "hls_a_$stamp.bin")
        val outMp4 = File(ctx.cacheDir, "hls_out_$stamp.mp4")
        try {
            val hasAudio = opt.audioUrl != null
            downloadMedia(opt.url, vRaw, "Descargando video", 0f, if (hasAudio) 0.8f else 0.92f, progress)
            if (hasAudio) downloadMedia(opt.audioUrl!!, aRaw, "Descargando audio", 0.8f, 0.92f, progress)
            progress(0.94f, "Uniendo en un archivo…")
            return try {
                remux(vRaw, if (hasAudio) aRaw else null, outMp4)
                Pair(outMp4, opt.fileName)
            } catch (e: Exception) {
                // Si no se pudo convertir a mp4, se entrega el material tal cual (se reproduce igual).
                outMp4.delete()
                val ts = File(ctx.cacheDir, "hls_ts_$stamp.ts")
                if (!vRaw.renameTo(ts)) throw e
                Pair(ts, opt.fileName.substringBeforeLast('.') + ".ts")
            }
        } finally {
            vRaw.delete()
            aRaw.delete()
        }
    }

    /** Copia las pistas (sin recodificar, sin pérdida) a un mp4 con los tiempos empezando en cero. */
    private fun remux(video: File, audio: File?, out: File) {
        val vEx = MediaExtractor()
        var aEx: MediaExtractor? = null
        var mux: MediaMuxer? = null
        var started = false
        try {
            vEx.setDataSource(video.absolutePath)
            var vTrack = -1
            var aTrackSame = -1
            for (i in 0 until vEx.trackCount) {
                val mime = vEx.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (vTrack < 0 && mime.startsWith("video/")) vTrack = i
                if (aTrackSame < 0 && mime.startsWith("audio/")) aTrackSame = i
            }
            if (vTrack < 0) throw IllegalStateException("El stream no contiene video")
            val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux = m

            val vOut = m.addTrack(vEx.getTrackFormat(vTrack))
            var aOut = -1
            var audioEx: MediaExtractor? = null
            var audioTrack = -1
            if (audio != null) {
                val ae = MediaExtractor()
                ae.setDataSource(audio.absolutePath)
                aEx = ae
                for (i in 0 until ae.trackCount) {
                    val mime = ae.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        audioTrack = i
                        break
                    }
                }
                if (audioTrack >= 0) {
                    audioEx = ae
                    aOut = m.addTrack(ae.getTrackFormat(audioTrack))
                }
            } else if (aTrackSame >= 0) {
                audioEx = vEx
                audioTrack = aTrackSame
                aOut = m.addTrack(vEx.getTrackFormat(aTrackSame))
            }
            m.start()
            started = true

            copyTrack(vEx, vTrack, m, vOut)
            if (audioEx != null && aOut >= 0) copyTrack(audioEx, audioTrack, m, aOut)
        } finally {
            try {
                if (started) mux?.stop()
            } catch (e: Exception) {
            }
            try {
                mux?.release()
            } catch (e: Exception) {
            }
            vEx.release()
            aEx?.release()
        }
    }

    private fun copyTrack(ex: MediaExtractor, track: Int, mux: MediaMuxer, outTrack: Int) {
        ex.selectTrack(track)
        ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val buf = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var offset = Long.MIN_VALUE
        while (true) {
            if (cancelled) throw Cancelled()
            buf.clear()
            val n = ex.readSampleData(buf, 0)
            if (n < 0) break
            val t = ex.sampleTime
            if (offset == Long.MIN_VALUE) offset = t
            info.offset = 0
            info.size = n
            info.presentationTimeUs = if (t - offset < 0) 0L else t - offset
            info.flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            mux.writeSampleData(outTrack, buf, info)
            ex.advance()
        }
        ex.unselectTrack(track)
    }
}
