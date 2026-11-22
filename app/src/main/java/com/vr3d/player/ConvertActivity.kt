package com.vr3d.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

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
    private var quality = 0
    private var running = false
    private val handler = Handler(Looper.getMainLooper())
    private var seenFinished = ConvertState.finishedCount
    private var startedAt = 0L
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
            quality = (quality + 1) % 3
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

        btnCancel = Ui.button(this, "Cancelar", false) { ConvertService.cancel(this) }
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
                "La conversión sigue en segundo plano: puedes apagar la pantalla o usar otras apps " +
                    "(verás el progreso en la notificación). " +
                    "Rápido (480p) es lo más ágil; Normal (720p) tarda varias veces más y Alta (1080p) bastante más aún; mejor con el móvil cargando. " +
                    "Se usa mucha batería: conviene tener el móvil cargando.",
                12f, Ui.MUT
            ),
            Ui.lp(-1, -2, top = Ui.dp(this, 18))
        )

        setContentView(scroll)
        refreshTexts()
        intent.data?.let { u ->
            picked = u
            pickedName = intent.getStringExtra("name") ?: queryName(u)
            tvPicked.text = pickedName
            tvPicked.setTextColor(Ui.TXT)
            btnStart.isEnabled = true
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 31)
        }
    }

    private fun strength(): Float = 0.4f + seekStrength.progress * 0.1f

    private fun refreshTexts() {
        val s = strength()
        val label = if (s < 0.8f) "Suave" else if (s < 1.3f) "Normal" else if (s < 1.7f) "Fuerte" else "Extremo"
        tvStrength.text = "Intensidad del 3D: " + label + " · " + String.format("%.1f", s)
        btnQuality.text = when (quality) {
            2 -> "Calidad: Alta (1080p) · mucho más lento"
            1 -> "Calidad: Normal (720p) · más lento"
            else -> "Calidad: Rápido (480p)"
        }
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
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // no todos los proveedores lo permiten; el servicio recibe además un permiso temporal
            }
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
        if (ConvertState.running) return
        ConvertService.start(this, uri, pickedName, strength(), quality)
        // El estado real lo marca el servicio; mientras arranca se muestra "Preparando…".
        seenFinished = ConvertState.finishedCount
        startedAt = System.currentTimeMillis()
        progress.progress = 0
        setProgress(0f, "Preparando…")
        showRunning()
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    private fun showRunning() {
        running = true
        btnStart.isEnabled = false
        btnPlay.visibility = android.view.View.GONE
        btnCancel.visibility = android.view.View.VISIBLE
        progress.visibility = android.view.View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun finishUi() {
        running = false
        btnCancel.visibility = android.view.View.GONE
        btnStart.isEnabled = picked != null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Lee el estado del servicio y lo muestra. */
    private fun refreshFromState() {
        if (ConvertState.running) {
            if (!running) showRunning()
            setProgress(ConvertState.progress, ConvertState.message)
            tvPicked.text = ConvertState.sourceName
            return
        }
        if (running && ConvertState.finishedCount == seenFinished &&
            System.currentTimeMillis() - startedAt > 10_000L
        ) {
            // El servicio no llegó a arrancar
            finishUi()
            tvStatus.setTextColor(Ui.ERR)
            tvStatus.text = "No se pudo iniciar la conversión en segundo plano."
            return
        }
        if (running && ConvertState.finishedCount != seenFinished) {
            seenFinished = ConvertState.finishedCount
            // Acaba de terminar
            finishUi()
            val err = ConvertState.error
            val res = ConvertState.resultUri
            if (err != null) {
                tvStatus.setTextColor(Ui.ERR)
                tvStatus.text = "Error: " + err
            } else if (res != null) {
                resultUri = res
                resultName = ConvertState.resultName
                progress.progress = 1000
                tvStatus.setTextColor(Ui.TXT)
                tvStatus.text = ConvertState.summary
                btnPlay.visibility = android.view.View.VISIBLE
            } else {
                tvStatus.setTextColor(Ui.TXT)
                tvStatus.text = "Cancelado"
            }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            refreshFromState()
            if (running || ConvertState.running) handler.postDelayed(this, 400)
        }
    }

    override fun onResume() {
        super.onResume()
        // Si había una conversión en marcha (o terminó mientras estabas fuera), se recupera el estado.
        if (ConvertState.running) {
            showRunning()
            refreshFromState()
            handler.removeCallbacks(poll)
            handler.post(poll)
        } else if (ConvertState.finishedCount != seenFinished) {
            running = true
            refreshFromState()
        }
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        super.onPause()
    }
}
