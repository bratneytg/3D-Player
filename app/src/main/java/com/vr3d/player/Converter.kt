package com.vr3d.player

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class Cancelled : Exception("Cancelado")

data class ConvResult(val frames: Int, val seconds: Float, val width: Int, val height: Int, val audio: Boolean)

/** Depth Anything V2 Small (ONNX, int8) ejecutado en el móvil. Salida: profundidad inversa (mayor = más cerca). */
class DepthEstimator(ctx: Context, width: Int, height: Int, depthRes: Int) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    val iw: Int
    val ih: Int
    var outW: Int = 0
        private set
    var outH: Int = 0
        private set

    init {
        val shortSide = max(14, (depthRes / 14.0).roundToInt() * 14)
        val longSide = if (width >= height)
            max(14, (width.toDouble() / height * shortSide / 14.0).roundToInt() * 14)
        else
            max(14, (height.toDouble() / width * shortSide / 14.0).roundToInt() * 14)
        if (width >= height) {
            ih = shortSide
            iw = longSide
        } else {
            iw = shortSide
            ih = longSide
        }
        val bytes: ByteArray = ctx.assets.open("depth_model.onnx").use { it.readBytes() }
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(min(4, max(2, Runtime.getRuntime().availableProcessors())))
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        session = env.createSession(bytes, opts)
        inputName = session.inputNames.iterator().next()
    }

    /** frame: ARGB de sw x sh. Devuelve el mapa de profundidad (outW x outH). */
    fun estimate(frame: IntArray, sw: Int, sh: Int): FloatArray {
        val n = ih * iw
        val fb: FloatBuffer = ByteBuffer.allocateDirect(4 * 3 * n).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val mean0 = 0.485f
        val mean1 = 0.456f
        val mean2 = 0.406f
        val inv0 = 1f / (0.229f * 255f)
        val inv1 = 1f / (0.224f * 255f)
        val inv2 = 1f / (0.225f * 255f)
        for (yy in 0 until ih) {
            var fy = (yy + 0.5f) * sh / ih - 0.5f
            if (fy < 0f) fy = 0f
            if (fy > sh - 1f) fy = sh - 1f
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, sh - 1)
            val wy = fy - y0
            for (xx in 0 until iw) {
                var fx = (xx + 0.5f) * sw / iw - 0.5f
                if (fx < 0f) fx = 0f
                if (fx > sw - 1f) fx = sw - 1f
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, sw - 1)
                val wx = fx - x0
                val p00 = frame[y0 * sw + x0]
                val p10 = frame[y0 * sw + x1]
                val p01 = frame[y1 * sw + x0]
                val p11 = frame[y1 * sw + x1]
                val r = lerp2((p00 shr 16) and 255, (p10 shr 16) and 255, (p01 shr 16) and 255, (p11 shr 16) and 255, wx, wy)
                val g = lerp2((p00 shr 8) and 255, (p10 shr 8) and 255, (p01 shr 8) and 255, (p11 shr 8) and 255, wx, wy)
                val b = lerp2(p00 and 255, p10 and 255, p01 and 255, p11 and 255, wx, wy)
                val o = yy * iw + xx
                fb.put(o, r * inv0 - mean0 / 0.229f)
                fb.put(n + o, g * inv1 - mean1 / 0.224f)
                fb.put(2 * n + o, b * inv2 - mean2 / 0.225f)
            }
        }
        fb.rewind()
        val tensor = OnnxTensor.createTensor(env, fb, longArrayOf(1, 3, ih.toLong(), iw.toLong()))
        try {
            val results = session.run(mapOf(inputName to tensor))
            try {
                val out = results.get(0) as OnnxTensor
                val shp = out.info.shape
                val h = shp[shp.size - 2].toInt()
                val w = shp[shp.size - 1].toInt()
                val fbo = out.floatBuffer
                val arr = FloatArray(fbo.remaining())
                fbo.get(arr)
                if (arr.size != w * h) throw IllegalStateException("Salida inesperada del modelo de IA")
                outW = w
                outH = h
                return arr
            } finally {
                results.close()
            }
        } finally {
            tensor.close()
        }
    }

    private fun lerp2(a: Int, b: Int, c: Int, d: Int, wx: Float, wy: Float): Float {
        val top = a + (b - a) * wx
        val bot = c + (d - c) * wx
        return top + (bot - top) * wy
    }

    fun close() {
        try {
            session.close()
        } catch (e: Exception) {
            // nada
        }
    }
}

/**
 * Convierte un video 2D en 3D (lado a lado) dentro del móvil:
 * MediaCodec (decodifica) -> IA de profundidad -> síntesis de las vistas izquierda/derecha -> MediaCodec (codifica H.264).
 * El audio AAC original se copia sin recomprimir.
 */
class VideoConverter(private val ctx: Context) {
    @Volatile
    var cancelled: Boolean = false

    companion object {
        const val CONVERGENCE = 0.4f
        const val DISPARITY_FRAC = 0.03f
        const val MAX_FPS = 30f
        const val SCENE_CUT = 35f
    }

    fun convert(
        src: Uri,
        out: File,
        strength: Float,
        highQuality: Boolean,
        progress: (Float, String) -> Unit
    ): ConvResult {
        val t0 = System.currentTimeMillis()
        val ex = MediaExtractor()
        var aex: MediaExtractor? = null
        var dec: MediaCodec? = null
        var enc: MediaCodec? = null
        var mux: MediaMuxer? = null
        var depth: DepthEstimator? = null
        var muxStarted = false
        try {
            ex.setDataSource(ctx, src, null)
            var vTrack = -1
            var aTrack = -1
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (vTrack < 0 && mime.startsWith("video/")) vTrack = i
                if (aTrack < 0 && mime == "audio/mp4a-latm") aTrack = i
            }
            if (vTrack < 0) throw IllegalStateException("El archivo no contiene video")
            val vFmt = ex.getTrackFormat(vTrack)
            val vMime = vFmt.getString(MediaFormat.KEY_MIME) ?: throw IllegalStateException("Video sin formato")
            val srcW = vFmt.getInteger(MediaFormat.KEY_WIDTH)
            val srcH = vFmt.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = if (vFmt.containsKey(MediaFormat.KEY_ROTATION)) vFmt.getInteger(MediaFormat.KEY_ROTATION) else 0
            val durationUs = if (vFmt.containsKey(MediaFormat.KEY_DURATION)) vFmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val srcFps = readFps(vFmt)
            val fps = min(srcFps, MAX_FPS)
            val minDeltaUs = if (srcFps > MAX_FPS + 1f) (1_000_000f / MAX_FPS * 0.9f).toLong() else 0L

            val dispW = if (rotation % 180 == 0) srcW else srcH
            val dispH = if (rotation % 180 == 0) srcH else srcW
            val shortSide = if (highQuality) 720 else 480
            val scale = min(1f, shortSide.toFloat() / min(dispW, dispH).toFloat())
            val w = max(16, ((dispW * scale / 16f).roundToInt()) * 16)
            val h = max(16, ((dispH * scale / 16f).roundToInt()) * 16)
            val w2 = 2 * w
            val bt709Out = h >= 720
            val bt709In = dispH >= 720
            val stride = if (highQuality) 2 else 3
            val depthRes = if (highQuality) 322 else 224
            val halfPx = strength * w * DISPARITY_FRAC / 2f
            val dilatePx = halfPx * max(CONVERGENCE, 1f - CONVERGENCE)

            progress(0f, "Cargando modelo de IA…")
            val dEst = DepthEstimator(ctx, w, h, depthRes)
            depth = dEst

            // ---- salida: codificador + muxer
            val encFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w2, h)
            encFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val bitrate = (w2.toLong() * h * fps * 0.12f).toLong().coerceIn(2_000_000L, 20_000_000L).toInt()
            encFmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            encFmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps.roundToInt())
            encFmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            enc = encoder

            val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux = muxer
            var audioOut = -1
            if (aTrack >= 0) {
                val ae = MediaExtractor()
                ae.setDataSource(ctx, src, null)
                ae.selectTrack(aTrack)
                aex = ae
                audioOut = muxer.addTrack(ae.getTrackFormat(aTrack))
            }
            var videoOut = -1
            val binfo = MediaCodec.BufferInfo()

            fun drain(endOfStream: Boolean) {
                var spins = 0
                while (true) {
                    val idx = encoder.dequeueOutputBuffer(binfo, if (endOfStream) 10_000L else 0L)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!endOfStream) return
                        spins++
                        if (spins > 600) return
                    } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoOut = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxStarted = true
                    } else if (idx >= 0) {
                        val buf = encoder.getOutputBuffer(idx)
                        if ((binfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) binfo.size = 0
                        if (buf != null && binfo.size > 0 && muxStarted) {
                            buf.position(binfo.offset)
                            buf.limit(binfo.offset + binfo.size)
                            muxer.writeSampleData(videoOut, buf, binfo)
                        }
                        encoder.releaseOutputBuffer(idx, false)
                        if ((binfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                    }
                }
            }

            val sbs = IntArray(w2 * h)
            var framesOut = 0
            var lastLog = System.currentTimeMillis()

            fun encodeFrame(ptsUs: Long) {
                var idx = -1
                while (true) {
                    if (cancelled) throw Cancelled()
                    idx = encoder.dequeueInputBuffer(10_000L)
                    if (idx >= 0) break
                    drain(false)
                }
                val img = encoder.getInputImage(idx)
                    ?: throw IllegalStateException("Este móvil no admite el formato de codificación necesario")
                fillImage(img, sbs, w2, h, bt709Out)
                encoder.queueInputBuffer(idx, 0, w2 * h * 3 / 2, ptsUs, 0)
                drain(false)
            }

            // ---- estado de la IA
            var prevDepth: FloatArray? = null
            var dW = 0
            var dH = 0
            var hasRange = false
            var sLo = 0f
            var sHi = 1f
            var prevThumb: IntArray? = null
            val pending = ArrayList<IntArray>()
            val pendingPts = ArrayList<Long>()
            var index = 0

            fun normalize(d: FloatArray, cut: Boolean): FloatArray {
                var mn = Float.MAX_VALUE
                var mx = -Float.MAX_VALUE
                for (v in d) {
                    if (v < mn) mn = v
                    if (v > mx) mx = v
                }
                if (mx - mn < 1e-6f) return FloatArray(d.size) { 0.5f }
                val bins = 256
                val hist = IntArray(bins)
                val sc = (bins - 1) / (mx - mn)
                for (v in d) hist[((v - mn) * sc).toInt()]++
                val lowCount = (d.size * 0.02f).toInt()
                val highCount = (d.size * 0.98f).toInt()
                var acc = 0
                var lo = mn
                var hi = mx
                var gotLo = false
                for (i in 0 until bins) {
                    acc += hist[i]
                    if (!gotLo && acc > lowCount) {
                        lo = mn + i / sc
                        gotLo = true
                    }
                    if (acc >= highCount) {
                        hi = mn + (i + 1) / sc
                        break
                    }
                }
                if (!hasRange || cut) {
                    sLo = lo
                    sHi = hi
                    hasRange = true
                } else {
                    sLo = 0.8f * sLo + 0.2f * lo
                    sHi = 0.8f * sHi + 0.2f * hi
                }
                val span = max(sHi - sLo, 1e-6f)
                val dn = FloatArray(d.size)
                for (i in d.indices) dn[i] = ((d[i] - sLo) / span).coerceIn(0f, 1f)
                val blurred = blur3(dn, dW, dH)
                val rs = max(1, (min(dilatePx, 48f) * dW / w).roundToInt())
                return dilateH(blurred, dW, dH, rs)
            }

            fun emit(frame: IntArray, d: FloatArray, pts: Long) {
                renderSbs(frame, w, h, d, dW, dH, halfPx, sbs)
                encodeFrame(pts)
                framesOut++
                val now = System.currentTimeMillis()
                if (now - lastLog > 700L) {
                    lastLog = now
                    val rate = framesOut * 1000f / max(1L, now - t0).toFloat()
                    val frac = if (durationUs > 0L) (pts.toFloat() / durationUs).coerceIn(0f, 0.99f) else 0f
                    var eta = ""
                    if (durationUs > 0L && rate > 0f) {
                        val total = durationUs / 1_000_000f * fps
                        eta = " · quedan ~" + max(0, ((total - framesOut) / rate).toInt()) + " s"
                    }
                    progress(frac, String.format("Convirtiendo (%.1f fps)%s", rate, eta))
                }
            }

            fun handleFrame(frame: IntArray, pts: Long) {
                if (index % stride != 0) {
                    pending.add(frame)
                    pendingPts.add(pts)
                    index++
                    return
                }
                val thumb = thumbOf(frame, w, h)
                val pt = prevThumb
                val cut = pt != null && meanAbsDiff(thumb, pt) > SCENE_CUT
                prevThumb = thumb
                val raw = dEst.estimate(frame, w, h)
                dW = dEst.outW
                dH = dEst.outH
                val cur = normalize(raw, cut)
                val pdRaw = prevDepth
                val pd: FloatArray = if (pdRaw != null && pdRaw.size == cur.size) pdRaw else cur
                val sameAsCur = pd === cur
                val n = pending.size
                for (i in 0 until n) {
                    val t = (i + 1f) / (n + 1f)
                    val d: FloatArray = if (sameAsCur) {
                        cur
                    } else if (cut) {
                        if (t < 0.5f) pd else cur
                    } else {
                        blend(pd, cur, t)
                    }
                    emit(pending[i], d, pendingPts[i])
                }
                pending.clear()
                pendingPts.clear()
                emit(frame, cur, pts)
                prevDepth = cur
                index++
            }

            // ---- decodificador
            ex.selectTrack(vTrack)
            vFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val decoder = MediaCodec.createDecoderByType(vMime)
            decoder.configure(vFmt, null, null, 0)
            decoder.start()
            dec = decoder
            val dinfo = MediaCodec.BufferInfo()
            var inputDone = false
            var lastPts = Long.MIN_VALUE
            val frameBuf = IntArray(w * h)

            progress(0f, "Convirtiendo…")
            while (true) {
                if (cancelled) throw Cancelled()
                if (!inputDone) {
                    val ii = decoder.dequeueInputBuffer(10_000L)
                    if (ii >= 0) {
                        val ib = decoder.getInputBuffer(ii)!!
                        val size = ex.readSampleData(ib, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(ii, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(ii, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = decoder.dequeueOutputBuffer(dinfo, 10_000L)
                if (oi >= 0) {
                    val eos = (dinfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val img = decoder.getOutputImage(oi)
                    val pts = dinfo.presentationTimeUs
                    val keep = img != null && (lastPts == Long.MIN_VALUE || pts - lastPts >= minDeltaUs)
                    if (keep && img != null) {
                        if (img.format != ImageFormat.YUV_420_888) {
                            throw IllegalStateException("Formato de video no admitido (¿HDR de 10 bits?)")
                        }
                        imageToArgb(img, frameBuf, w, h, rotation, bt709In)
                        lastPts = pts
                        handleFrame(frameBuf.copyOf(), pts)
                    }
                    decoder.releaseOutputBuffer(oi, false)
                    if (eos) break
                }
            }

            // cola final: fotogramas que quedaron esperando a la IA
            val pdEnd = prevDepth
            if (pdEnd != null) {
                for (i in 0 until pending.size) emit(pending[i], pdEnd, pendingPts[i])
            }
            pending.clear()
            pendingPts.clear()

            if (framesOut == 0) throw IllegalStateException("No se pudo leer ningún fotograma del video")

            // fin del codificador
            var qi = -1
            while (true) {
                qi = encoder.dequeueInputBuffer(10_000L)
                if (qi >= 0) break
                drain(false)
            }
            encoder.queueInputBuffer(qi, 0, 0, max(lastPts, 0L), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            if (!muxStarted) throw IllegalStateException("El codificador no produjo video")

            // audio original (AAC) sin recomprimir
            val ae = aex
            if (ae != null && audioOut >= 0) {
                progress(0.99f, "Copiando audio…")
                val abuf = ByteBuffer.allocate(1 shl 20)
                val ainfo = MediaCodec.BufferInfo()
                while (true) {
                    val sz = ae.readSampleData(abuf, 0)
                    if (sz < 0) break
                    ainfo.set(
                        0, sz, ae.sampleTime,
                        if ((ae.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    )
                    muxer.writeSampleData(audioOut, abuf, ainfo)
                    ae.advance()
                }
            }
            muxer.stop()
            muxStarted = false
            progress(1f, "Listo")
            return ConvResult(framesOut, (System.currentTimeMillis() - t0) / 1000f, w2, h, aTrack >= 0)
        } finally {
            try { dec?.stop() } catch (e: Exception) { /* nada */ }
            try { dec?.release() } catch (e: Exception) { /* nada */ }
            try { enc?.stop() } catch (e: Exception) { /* nada */ }
            try { enc?.release() } catch (e: Exception) { /* nada */ }
            if (muxStarted) {
                try { mux?.stop() } catch (e: Exception) { /* nada */ }
            }
            try { mux?.release() } catch (e: Exception) { /* nada */ }
            try { ex.release() } catch (e: Exception) { /* nada */ }
            try { aex?.release() } catch (e: Exception) { /* nada */ }
            depth?.close()
        }
    }

    // ------------------------------------------------------------------ utilidades

    private fun readFps(f: MediaFormat): Float {
        if (!f.containsKey(MediaFormat.KEY_FRAME_RATE)) return 30f
        return try {
            f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
        } catch (e: Exception) {
            try {
                f.getFloat(MediaFormat.KEY_FRAME_RATE)
            } catch (e2: Exception) {
                30f
            }
        }
    }

    private fun clamp255(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    /** Imagen YUV420 del decodificador -> ARGB de w x h (con rotación y reescalado). */
    private fun imageToArgb(img: Image, dst: IntArray, w: Int, h: Int, rotation: Int, bt709: Boolean) {
        val crop = img.cropRect
        val cw = crop.width()
        val ch = crop.height()
        val dispW = if (rotation % 180 == 0) cw else ch
        val dispH = if (rotation % 180 == 0) ch else cw
        val planes = img.planes
        val yBuf = planes[0].buffer
        val yRow = planes[0].rowStride
        val yPix = planes[0].pixelStride
        val uBuf = planes[1].buffer
        val uRow = planes[1].rowStride
        val uPix = planes[1].pixelStride
        val vBuf = planes[2].buffer
        val vRow = planes[2].rowStride
        val vPix = planes[2].pixelStride
        for (y in 0 until h) {
            val v = (((y + 0.5f) * dispH / h).toInt()).coerceIn(0, dispH - 1)
            for (x in 0 until w) {
                val u = (((x + 0.5f) * dispW / w).toInt()).coerceIn(0, dispW - 1)
                var sx: Int
                var sy: Int
                when (rotation) {
                    90 -> { sx = v; sy = ch - 1 - u }
                    180 -> { sx = cw - 1 - u; sy = ch - 1 - v }
                    270 -> { sx = cw - 1 - v; sy = u }
                    else -> { sx = u; sy = v }
                }
                sx = sx.coerceIn(0, cw - 1)
                sy = sy.coerceIn(0, ch - 1)
                val px = crop.left + sx
                val py = crop.top + sy
                val yy = (yBuf.get(py * yRow + px * yPix).toInt() and 255) - 16
                val cI = py shr 1
                val cX = px shr 1
                val uu = (uBuf.get(cI * uRow + cX * uPix).toInt() and 255) - 128
                val vv = (vBuf.get(cI * vRow + cX * vPix).toInt() and 255) - 128
                val r: Int
                val g: Int
                val b: Int
                if (bt709) {
                    r = (298 * yy + 459 * vv + 128) shr 8
                    g = (298 * yy - 55 * uu - 136 * vv + 128) shr 8
                    b = (298 * yy + 541 * uu + 128) shr 8
                } else {
                    r = (298 * yy + 409 * vv + 128) shr 8
                    g = (298 * yy - 100 * uu - 208 * vv + 128) shr 8
                    b = (298 * yy + 516 * uu + 128) shr 8
                }
                dst[y * w + x] = (0xFF shl 24) or (clamp255(r) shl 16) or (clamp255(g) shl 8) or clamp255(b)
            }
        }
    }

    /** ARGB -> imagen YUV420 de entrada del codificador. */
    private fun fillImage(img: Image, argb: IntArray, w: Int, h: Int, bt709: Boolean) {
        val planes = img.planes
        val yBuf = planes[0].buffer
        val yRow = planes[0].rowStride
        val yPix = planes[0].pixelStride
        val uBuf = planes[1].buffer
        val uRow = planes[1].rowStride
        val uPix = planes[1].pixelStride
        val vBuf = planes[2].buffer
        val vRow = planes[2].rowStride
        val vPix = planes[2].pixelStride
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                val c = argb[base + x]
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val b = c and 255
                val yy = if (bt709) ((47 * r + 157 * g + 16 * b + 128) shr 8) + 16
                else ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuf.put(y * yRow + x * yPix, yy.toByte())
                if ((y and 1) == 0 && (x and 1) == 0) {
                    val uu: Int
                    val vv: Int
                    if (bt709) {
                        uu = ((-26 * r - 87 * g + 113 * b + 128) shr 8) + 128
                        vv = ((112 * r - 102 * g - 10 * b + 128) shr 8) + 128
                    } else {
                        uu = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                        vv = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    }
                    uBuf.put((y shr 1) * uRow + (x shr 1) * uPix, clamp255(uu).toByte())
                    vBuf.put((y shr 1) * vRow + (x shr 1) * vPix, clamp255(vv).toByte())
                }
            }
        }
    }

    private fun thumbOf(frame: IntArray, w: Int, h: Int): IntArray {
        val t = IntArray(32 * 18)
        for (ty in 0 until 18) {
            for (tx in 0 until 32) {
                val x = ((tx + 0.5f) * w / 32f).toInt().coerceIn(0, w - 1)
                val y = ((ty + 0.5f) * h / 18f).toInt().coerceIn(0, h - 1)
                val c = frame[y * w + x]
                t[ty * 32 + tx] = (((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8
            }
        }
        return t
    }

    private fun meanAbsDiff(a: IntArray, b: IntArray): Float {
        var s = 0L
        for (i in a.indices) s += Math.abs(a[i] - b[i])
        return s.toFloat() / a.size
    }

    private fun blend(a: FloatArray, b: FloatArray, t: Float): FloatArray {
        val o = FloatArray(a.size)
        for (i in a.indices) o[i] = a[i] + (b[i] - a[i]) * t
        return o
    }

    /** Suavizado 3x3 (caja) del mapa de profundidad. */
    private fun blur3(d: FloatArray, w: Int, h: Int): FloatArray {
        val tmp = FloatArray(d.size)
        val out = FloatArray(d.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val l = d[row + max(0, x - 1)]
                val c = d[row + x]
                val r = d[row + min(w - 1, x + 1)]
                tmp[row + x] = (l + c + r) / 3f
            }
        }
        for (y in 0 until h) {
            val up = max(0, y - 1) * w
            val mid = y * w
            val dn = min(h - 1, y + 1) * w
            for (x in 0 until w) {
                out[mid + x] = (tmp[up + x] + tmp[mid + x] + tmp[dn + x]) / 3f
            }
        }
        return out
    }

    /** Expande en horizontal los objetos cercanos (máximo en una ventana) para evitar "fantasmas" en sus bordes. */
    private fun dilateH(d: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val out = FloatArray(d.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var m = 0f
                val x0 = max(0, x - r)
                val x1 = min(w - 1, x + r)
                for (k in x0..x1) {
                    val v = d[row + k]
                    if (v > m) m = v
                }
                out[row + x] = m
            }
        }
        return out
    }

    /** Genera las vistas izquierda y derecha desplazando cada píxel según su profundidad. */
    private fun renderSbs(frame: IntArray, w: Int, h: Int, depth: FloatArray, dw: Int, dh: Int, halfPx: Float, out: IntArray) {
        val w2 = 2 * w
        val xi = IntArray(w)
        val xf = FloatArray(w)
        for (x in 0 until w) {
            var fx = (x + 0.5f) * dw / w - 0.5f
            if (fx < 0f) fx = 0f
            if (fx > dw - 1f) fx = dw - 1f
            val i = min(max(fx.toInt(), 0), max(dw - 2, 0))
            xi[x] = i
            xf[x] = fx - i
        }
        for (y in 0 until h) {
            var fy = (y + 0.5f) * dh / h - 0.5f
            if (fy < 0f) fy = 0f
            if (fy > dh - 1f) fy = dh - 1f
            val j = min(max(fy.toInt(), 0), max(dh - 2, 0))
            val wy = fy - j
            val r0 = j * dw
            val r1 = min(j + 1, dh - 1) * dw
            val rowOff = y * w
            val outOff = y * w2
            for (x in 0 until w) {
                val i = xi[x]
                val i1 = min(i + 1, dw - 1)
                val a = xf[x]
                val d0 = depth[r0 + i] * (1f - a) + depth[r0 + i1] * a
                val d1 = depth[r1 + i] * (1f - a) + depth[r1 + i1] * a
                val dpt = d0 * (1f - wy) + d1 * wy
                val disp = (dpt - CONVERGENCE) * halfPx
                out[outOff + x] = sampleRow(frame, rowOff, w, x - disp)
                out[outOff + w + x] = sampleRow(frame, rowOff, w, x + disp)
            }
        }
    }

    private fun sampleRow(f: IntArray, rowOff: Int, w: Int, fx0: Float): Int {
        var fx = fx0
        if (fx < 0f) fx = 0f
        if (fx > w - 1f) fx = w - 1f
        val i = fx.toInt()
        val a = fx - i
        val p = f[rowOff + i]
        if (a < 0.02f || i >= w - 1) return p
        val q = f[rowOff + i + 1]
        val r = (((p shr 16) and 255) * (1f - a) + ((q shr 16) and 255) * a).toInt()
        val g = (((p shr 8) and 255) * (1f - a) + ((q shr 8) and 255) * a).toInt()
        val b = ((p and 255) * (1f - a) + (q and 255) * a).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
