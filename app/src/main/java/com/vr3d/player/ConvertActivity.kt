package com.vr3d.player

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.File

/** Convierte un video 2D normal en 3D (lado a lado) con IA, dentro del propio móvil. */
class ConvertActivity : Activity() {

    private lateinit var tvPicked: TextView
    private lateinit var tvStrength: TextView
    private lateinit var btnQuality: Button
    private lateinit var btnStart: Button
    private lateinit var btnCancel: Button
    private lateinit var btnPlay: Button
    private lateinit var progress: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var seekStrength: SeekBar

    private var picked: Uri? = null
    private var pickedName = "video"
    private var highQuality = false
    private var running = false
    private var converter: VideoConverter? = null
    private var resultUri: Uri? = null
    private var resultName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val pad = Ui.dp(this, 18)
        col.setPadding(pad, Ui.dp(this, 28), pad, pad)
        scroll.addView(col)

        col.addView(Ui.text(this, "Convertir 2D → 3D", 22f, Ui.TXT, true))
        col.addView(
            Ui.text(this, "La IA estima la profundidad de cada escena y crea el video 3D lado a lado. Todo ocurre en tu móvil, sin internet.", 13f, Ui.MUT),
            Ui.lp(-1, -2, top = Ui.dp(this, 4), bottom = Ui.dp(this, 16))
        )

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = Ui.rounded(this, Ui.CARD, 18, Ui.LINE)
        card.setPadding(pad, pad, pad, pad)

        tvPicked = Ui.text(this, "Ningún video elegido", 14f, Ui.MUT)
        val btnPick = Ui.button(this, "🎬  Elegir video", false) { pickVideo() }
        card.addView(btnPick, Ui.lp(-1, -2))
        card.addView(tvPicked, Ui.lp(-1, -2, top = Ui.dp(this, 8), bottom = Ui.dp(this, 14)))

        tvStrength = Ui.text(this, "", 14f, Ui.TXT)
        card.addView(tvStrength)
        seekStrength = SeekBar(this)
        seekStrength.max = 16 // 0.4 .. 2.0
        seekStrength.progress = 6 // 1.0
        seekStrength.progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACC)
        seekStrength.thumbTintList = android.content.res.ColorStateList.valueOf(Ui.ACC)
        seekStrength.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = refreshTexts()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        card.addView(seekStrength, Ui.lp(-1, -2, bottom = Ui.dp(this, 10)))

        btnQuality = Ui.button(this, "", false) {
            highQuality = !highQuality
            refreshTexts()
        }
        card.addView(btnQuality, Ui.lp(-1, -2, bottom = Ui.dp(this, 14)))

        btnStart = Ui.button(this, "Convertir a 3D", true) { start() }
        btnStart.isEnabled = false
        card.addView(btnStart, Ui.lp(-1, -2))
        col.addView(card, Ui.lp(-1, -2))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 1000
        progress.progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACC)
        progress.visibility = android.view.View.GONE
        col.addView(progress, Ui.lp(-1, Ui.dp(this, 10), top = Ui.dp(this, 16)))

        tvStatus = Ui.text(this, "", 14f, Ui.TXT)
        tvStatus.gravity = Gravity.START
        col.addView(tvStatus, Ui.lp(-1, -2, top = Ui.dp(this, 8)))

        btnCancel = Ui.button(this, "Cancelar", false) { converter?.cancelled = true }
        btnCancel.visibility = android.view.View.GONE
        col.addView(btnCancel, Ui.lp(-1, -2, top = Ui.dp(this, 10)))

        btnPlay = Ui.button(this, "▶  Ver el video 3D", true) {
            val u = resultUri
            if (u != null) {
                val i = Intent(this, PlayerActivity::class.java)
                i.data = u
                i.putExtra("name", resultName)
                startActivity(i)
            }
        }
        btnPlay.visibility = android.view.View.GONE
        col.addView(btnPlay, Ui.lp(-1, -2, top = Ui.dp(this, 10)))

        col.addView(
            Ui.text(
                this,
                "Consejos: deja la pantalla encendida y la app abierta mientras convierte. " +
                    "Rápido (480p) es lo más ágil; Normal (720p) tarda varias veces más. " +
                    "Se usa mucha batería: conviene tener el móvil cargando.",
                12f, Ui.MUT
            ),
            Ui.lp(-1, -2, top = Ui.dp(this, 18))
        )

        setContentView(scroll)
        refreshTexts()
    }

    private fun strength(): Float = 0.4f + seekStrength.progress * 0.1f

    private fun refreshTexts() {
        val s = strength()
        val label = if (s < 0.8f) "Suave" else if (s < 1.3f) "Normal" else if (s < 1.7f) "Fuerte" else "Extremo"
        tvStrength.text = "Intensidad del 3D: " + label + " · " + String.format("%.1f", s)
        btnQuality.text = if (highQuality) "Calidad: Normal (720p) · más lento" else "Calidad: Rápido (480p)"
    }

    @Suppress("DEPRECATION")
    private fun pickVideo() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "video/*"
        startActivityForResult(i, 21)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 21 && resultCode == RESULT_OK) {
            val u = data?.data ?: return
            picked = u
            pickedName = queryName(u)
            tvPicked.text = pickedName
            tvPicked.setTextColor(Ui.TXT)
            btnStart.isEnabled = !running
            btnPlay.visibility = android.view.View.GONE
            tvStatus.text = ""
        }
    }

    private fun queryName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (n != null) return n
                }
            }
        } catch (e: Exception) {
            // sin nombre
        }
        return "video"
    }

    private fun setProgress(frac: Float, msg: String) {
        progress.progress = (frac * 1000f).toInt()
        tvStatus.setTextColor(Ui.TXT)
        tvStatus.text = msg
    }

    private fun start() {
        val uri = picked ?: return
        if (running) return
        running = true
        btnStart.isEnabled = false
        btnPlay.visibility = android.view.View.GONE
        btnCancel.visibility = android.view.View.VISIBLE
        progress.visibility = android.view.View.VISIBLE
        progress.progress = 0
        setProgress(0f, "Preparando…")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val conv = VideoConverter(this)
        converter = conv
        val s = strength()
        val hq = highQuality
        val stem = pickedName.substringBeforeLast('.', pickedName).replace(Regex("[^\\w\\- ]"), "").trim().ifEmpty { "video" }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vr3d:convert")
        wl.acquire(6 * 60 * 60 * 1000L)

        Thread {
            val tmp = File(cacheDir, "convert_" + System.currentTimeMillis() + ".mp4")
            try {
                val res = conv.convert(uri, tmp, s, hq) { f, m -> runOnUiThread { setProgress(f, m) } }
                val finalName = stem + "_3D_LR.mp4"
                val outUri = saveToMovies(tmp, finalName)
                Library.touch(this, outUri.toString(), finalName)
                runOnUiThread {
                    resultUri = outUri
                    resultName = finalName
                    progress.progress = 1000
                    tvStatus.setTextColor(Ui.TXT)
                    tvStatus.text = "¡Listo! Guardado en Películas/VR3D como " + finalName + "\n" +
                        res.width + "×" + res.height + " · " + String.format("%.0f", res.seconds) + " s" +
                        (if (res.audio) "" else " · sin audio (el original no era AAC)")
                    btnPlay.visibility = android.view.View.VISIBLE
                    finishUi()
                }
            } catch (e: Cancelled) {
                runOnUiThread {
                    tvStatus.text = "Cancelado"
                    finishUi()
                }
            } catch (e: Throwable) {
                val msg = (e.message ?: e.javaClass.simpleName)
                runOnUiThread {
                    tvStatus.setTextColor(Ui.ERR)
                    tvStatus.text = "Error: " + msg
                    finishUi()
                }
            } finally {
                tmp.delete()
                if (wl.isHeld) wl.release()
            }
        }.start()
    }

    private fun finishUi() {
        running = false
        btnCancel.visibility = android.view.View.GONE
        btnStart.isEnabled = picked != null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun saveToMovies(file: File, displayName: String): Uri {
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
        converter?.cancelled = true
        super.onDestroy()
    }
}
