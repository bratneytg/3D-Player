package com.vr3d.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Pantalla principal: abrir un video, convertir 2D→3D y lista de recientes (todo sin internet). */
class MainActivity : Activity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagLog.install(this)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val pad = Ui.dp(this, 18)
        col.setPadding(pad, Ui.dp(this, 28), pad, pad)
        scroll.addView(col)

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val logo = TextView(this)
        logo.text = "3D"
        logo.setTextColor(android.graphics.Color.WHITE)
        logo.textSize = 15f
        logo.setTypeface(logo.typeface, android.graphics.Typeface.BOLD)
        logo.gravity = Gravity.CENTER
        logo.background = Ui.accent(this, 12)
        header.addView(logo, LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)))
        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titles.addView(Ui.text(this, "VR3D Player", 20f, Ui.TXT, true))
        titles.addView(Ui.text(this, "Reproductor 3D / VR · funciona sin conexión", 12f, Ui.MUT))
        header.addView(titles, Ui.lp(-2, -2, left = Ui.dp(this, 12)))
        col.addView(header, Ui.lp(-1, -2, bottom = Ui.dp(this, 20)))

        col.addView(Ui.button(this, "📂  Abrir video 3D", true) { openVideo() }, Ui.lp(-1, -2, bottom = Ui.dp(this, 10)))
        col.addView(
            Ui.button(this, "✨  Convertir video 2D → 3D con IA", false) {
                startActivity(Intent(this, ConvertActivity::class.java))
            },
            Ui.lp(-1, -2, bottom = Ui.dp(this, 10))
        )
        col.addView(
            Ui.button(this, "⬇️  Descargar video de internet", false) {
                startActivity(Intent(this, DownloadActivity::class.java))
            },
            Ui.lp(-1, -2)
        )

        col.addView(
            Ui.text(this, "Recientes", 15f, Ui.MUT, true),
            Ui.lp(-1, -2, top = Ui.dp(this, 26), bottom = Ui.dp(this, 8))
        )
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        col.addView(list, Ui.lp(-1, -2))

        // Botón pequeño y apartado: genera un informe de errores para enviarlo.
        val rep = Ui.text(this, "🐞 Generar informe de errores", 12f, Ui.MUT)
        rep.gravity = Gravity.CENTER
        rep.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8))
        rep.background = Ui.rounded(this, Color.TRANSPARENT, 10, Ui.LINE)
        rep.setOnClickListener { DiagLog.share(this) }
        col.addView(rep, Ui.lp(-2, -2, top = Ui.dp(this, 60), bottom = Ui.dp(this, 10)).also { it.gravity = Gravity.CENTER_HORIZONTAL })

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        list.removeAllViews()
        val items = Library.load(this)
        if (items.isEmpty()) {
            list.addView(Ui.text(this, "Aún no has abierto ningún video.", 14f, Ui.MUT))
            return
        }
        for (e in items) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.background = Ui.rounded(this, Ui.CARD, 14, Ui.LINE)
            val p = Ui.dp(this, 14)
            row.setPadding(p, p, p, p)
            row.addView(Ui.text(this, e.name, 15f, Ui.TXT, true))
            val sub = if (e.positionMs > 3000L) "Continuar en " + Ui.fmtTime(e.positionMs) else "Sin empezar"
            row.addView(Ui.text(this, sub, 12f, Ui.MUT))
            row.setOnClickListener {
                val i = Intent(this, PlayerActivity::class.java)
                i.data = android.net.Uri.parse(e.uri)
                i.putExtra("name", e.name)
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(i)
            }
            row.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setMessage("¿Quitar «" + e.name + "» de la lista? (el archivo no se borra)")
                    .setPositiveButton("Quitar") { _, _ ->
                        Library.remove(this, e.uri)
                        refreshList()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
                true
            }
            list.addView(row, Ui.lp(-1, -2, bottom = Ui.dp(this, 8)))
        }
    }

    @Suppress("DEPRECATION")
    private fun openVideo() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "video/*"
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(i, 11)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 11 && resultCode == RESULT_OK) {
            val u = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // algunos proveedores no permiten acceso persistente
            }
            var name = "video"
            try {
                contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getString(0)
                        if (n != null) name = n
                    }
                }
            } catch (e: Exception) {
                // sin nombre
            }
            val i = Intent(this, PlayerActivity::class.java)
            i.data = u
            i.putExtra("name", name)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        }
    }
}
