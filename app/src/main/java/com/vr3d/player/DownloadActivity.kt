package com.vr3d.player

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** Descarga videos de internet pegando el enlace (archivo directo o stream HLS), eligiendo la calidad. */
class DownloadActivity : Activity() {

    private lateinit var etUrl: EditText
    private lateinit var tvInfo: TextView
    private lateinit var optionsBox: LinearLayout
    private lateinit var btnAnalyze: Button
    private lateinit var btnDownload: Button
    private lateinit var btnLive: Button
    private lateinit var btnCancel: Button
    private lateinit var btnPlay: Button
    private lateinit var btnConvert: Button
    private lateinit var progress: ProgressBar
    private lateinit var tvStatus: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var options: List<DlOption> = emptyList()
    private val optionButtons = ArrayList<Button>()
    private var selected = 0
    private var running = false
    private var analyzing = false
    private var seenFinished = DownloadState.finishedCount
    private var startedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val pad = Ui.dp(this, 18)
        col.setPadding(pad, Ui.dp(this, 28), pad, pad)
        scroll.addView(col)

        col.addView(Ui.text(this, "Descargar de internet", 22f, Ui.TXT, true))
        col.addView(
            Ui.text(
                this,
                "Pega el enlace de una página web con un video, o el enlace directo (.mp4, .mkv, .webm… o .m3u8). " +
                    "La app busca los videos de la página y te deja elegir cuál y con qué calidad. " +
                    "Se descarga el archivo original, sin perder calidad. Solo descarga videos que tengas derecho a guardar. " +
                    "Los videos protegidos (YouTube, plataformas de pago…) no se pueden descargar.",
                13f, Ui.MUT
            ),
            Ui.lp(-1, -2, top = Ui.dp(this, 4), bottom = Ui.dp(this, 16))
        )

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = Ui.rounded(this, Ui.CARD, 18, Ui.LINE)
        card.setPadding(pad, pad, pad, pad)

        etUrl = EditText(this)
        etUrl.hint = "https://…"
        etUrl.setHintTextColor(Ui.MUT)
        etUrl.setTextColor(Ui.TXT)
        etUrl.textSize = 14f
        etUrl.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        etUrl.setSingleLine(true)
        card.addView(etUrl, Ui.lp(-1, -2))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val btnPaste = Ui.button(this, "Pegar", false) { paste() }
        btnAnalyze = Ui.button(this, "Analizar enlace", true) { analyze() }
        row.addView(btnPaste, Ui.lp(0, -2, right = Ui.dp(this, 8)).also { it.weight = 1f })
        row.addView(btnAnalyze, Ui.lp(0, -2).also { it.weight = 2f })
        card.addView(row, Ui.lp(-1, -2, top = Ui.dp(this, 10)))

        tvInfo = Ui.text(this, "", 13f, Ui.MUT)
        card.addView(tvInfo, Ui.lp(-1, -2, top = Ui.dp(this, 12)))

        optionsBox = LinearLayout(this)
        optionsBox.orientation = LinearLayout.VERTICAL
        card.addView(optionsBox, Ui.lp(-1, -2, top = Ui.dp(this, 6)))

        btnDownload = Ui.button(this, "Descargar", true) { startDownload() }
        btnDownload.visibility = View.GONE
        card.addView(btnDownload, Ui.lp(-1, -2, top = Ui.dp(this, 12)))

        btnLive = Ui.button(this, "👓  Ver ahora sin descargar (modo VR)", false) { startLive() }
        btnLive.visibility = View.GONE
        card.addView(btnLive, Ui.lp(-1, -2, top = Ui.dp(this, 10)))
        col.addView(card, Ui.lp(-1, -2))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 1000
        progress.progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACC)
        progress.visibility = View.GONE
        col.addView(progress, Ui.lp(-1, Ui.dp(this, 10), top = Ui.dp(this, 16)))

        tvStatus = Ui.text(this, "", 14f, Ui.TXT)
        col.addView(tvStatus, Ui.lp(-1, -2, top = Ui.dp(this, 8)))

        btnCancel = Ui.button(this, "Cancelar", false) { DownloadService.cancel() }
        btnCancel.visibility = View.GONE
        col.addView(btnCancel, Ui.lp(-1, -2, top = Ui.dp(this, 10)))

        btnPlay = Ui.button(this, "▶  Ver el video", true) {
            val u = DownloadState.resultUri
            if (u != null) {
                val i = Intent(this, PlayerActivity::class.java)
                i.data = u
                i.putExtra("name", DownloadState.resultName)
                startActivity(i)
            }
        }
        btnPlay.visibility = View.GONE
        col.addView(btnPlay, Ui.lp(-1, -2, top = Ui.dp(this, 10)))

        btnConvert = Ui.button(this, "Convertir este video a 3D", false) {
            val u = DownloadState.resultUri
            if (u != null) {
                val i = Intent(this, ConvertActivity::class.java)
                i.data = u
                i.putExtra("name", DownloadState.resultName)
                startActivity(i)
            }
        }
        btnConvert.visibility = View.GONE
        col.addView(btnConvert, Ui.lp(-1, -2, top = Ui.dp(this, 10)))

        setContentView(scroll)

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41)
        }
    }

    private fun paste() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (t.isNullOrEmpty()) {
            tvInfo.text = "El portapapeles está vacío."
        } else {
            etUrl.setText(t)
            etUrl.setSelection(t.length)
        }
    }

    private fun analyze() {
        if (analyzing || running) return
        val url = etUrl.text.toString().trim()
        if (url.isEmpty()) {
            tvInfo.setTextColor(Ui.ERR)
            tvInfo.text = "Pega primero un enlace."
            return
        }
        analyzing = true
        btnAnalyze.isEnabled = false
        btnDownload.visibility = View.GONE
        btnLive.visibility = View.GONE
        optionsBox.removeAllViews()
        optionButtons.clear()
        tvInfo.setTextColor(Ui.MUT)
        tvInfo.text = "Analizando enlace y buscando videos…"
        Thread {
            try {
                val res = VideoDownloader(this).analyze(url)
                runOnUiThread { showOptions(res) }
            } catch (e: Throwable) {
                val msg = e.message ?: e.javaClass.simpleName
                runOnUiThread {
                    tvInfo.setTextColor(Ui.ERR)
                    tvInfo.text = msg
                    analyzing = false
                    btnAnalyze.isEnabled = true
                }
            }
        }.start()
    }

    private fun showOptions(res: DlAnalysis) {
        analyzing = false
        btnAnalyze.isEnabled = true
        options = res.options
        selected = 0
        tvInfo.setTextColor(Ui.MUT)
        tvInfo.text = res.note
        optionsBox.removeAllViews()
        optionButtons.clear()
        for ((i, o) in options.withIndex()) {
            val b = Ui.button(this, o.label, false) {
                selected = i
                refreshOptionButtons()
            }
            optionButtons.add(b)
            optionsBox.addView(b, Ui.lp(-1, -2, top = Ui.dp(this, 6)))
        }
        refreshOptionButtons()
        btnDownload.visibility = View.VISIBLE
        btnDownload.isEnabled = !running
        btnLive.visibility = View.VISIBLE
    }

    private fun refreshOptionButtons() {
        for ((i, b) in optionButtons.withIndex()) {
            val o = options[i]
            b.text = (if (i == selected) "●  " else "○  ") + o.label
            b.alpha = if (i == selected) 1f else 0.65f
        }
    }

    /** Reproduce el video en streaming, sin guardarlo ni convertirlo: pantalla 2D adaptada al visor Cardboard. */
    private fun startLive() {
        if (options.isEmpty()) return
        val opt = options[selected]
        // Si el audio va aparte, se usa la lista maestra para que el reproductor junte audio y video.
        val url = if (opt.hls && opt.audioUrl != null) (opt.sourceUrl ?: opt.url) else opt.url
        val i = Intent(this, PlayerActivity::class.java)
        i.data = android.net.Uri.parse(url)
        i.putExtra("name", opt.fileName)
        i.putExtra("live", true)
        i.putExtra("vr", true)
        i.putExtra("referer", opt.referer)
        startActivity(i)
    }

    private fun startDownload() {
        if (running || options.isEmpty()) return
        val opt = options[selected]
        DownloadService.start(this, opt)
        seenFinished = DownloadState.finishedCount
        startedAt = System.currentTimeMillis()
        setProgress(0f, "Conectando…")
        showRunning()
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    private fun showRunning() {
        running = true
        btnDownload.isEnabled = false
        btnAnalyze.isEnabled = false
        btnPlay.visibility = View.GONE
        btnConvert.visibility = View.GONE
        btnCancel.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun finishUi() {
        running = false
        btnCancel.visibility = View.GONE
        btnDownload.isEnabled = true
        btnAnalyze.isEnabled = true
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setProgress(frac: Float, msg: String) {
        progress.isIndeterminate = frac < 0f
        if (frac >= 0f) progress.progress = (frac * 1000f).toInt()
        tvStatus.setTextColor(Ui.TXT)
        tvStatus.text = msg
    }

    private fun refreshFromState() {
        if (DownloadState.running) {
            if (!running) showRunning()
            setProgress(DownloadState.progress, DownloadState.message)
            return
        }
        if (running && DownloadState.finishedCount == seenFinished &&
            System.currentTimeMillis() - startedAt > 10_000L
        ) {
            finishUi()
            tvStatus.setTextColor(Ui.ERR)
            tvStatus.text = "No se pudo iniciar la descarga en segundo plano."
            return
        }
        if (running && DownloadState.finishedCount != seenFinished) {
            seenFinished = DownloadState.finishedCount
            finishUi()
            progress.isIndeterminate = false
            val err = DownloadState.error
            if (err != null) {
                tvStatus.setTextColor(Ui.ERR)
                tvStatus.text = "Error: " + err
            } else if (DownloadState.resultUri != null) {
                progress.progress = 1000
                tvStatus.setTextColor(Ui.TXT)
                tvStatus.text = DownloadState.message + "\nEstá en Películas/VR3D y en tus recientes."
                btnPlay.visibility = View.VISIBLE
                btnConvert.visibility = View.VISIBLE
            } else {
                tvStatus.setTextColor(Ui.TXT)
                tvStatus.text = "Cancelado"
            }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            refreshFromState()
            if (running || DownloadState.running) handler.postDelayed(this, 400)
        }
    }

    override fun onResume() {
        super.onResume()
        if (DownloadState.running) {
            showRunning()
            refreshFromState()
            handler.removeCallbacks(poll)
            handler.post(poll)
        } else if (DownloadState.finishedCount != seenFinished) {
            running = true
            refreshFromState()
        }
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        super.onPause()
    }
}
