package com.vr3d.player

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Estilo visual compartido (el mismo que la app web VR3D Studio). */
object Ui {
    val BG: Int = Color.parseColor("#0B0B12")
    val CARD: Int = Color.parseColor("#15151F")
    val LINE: Int = Color.parseColor("#262636")
    val TXT: Int = Color.parseColor("#ECEBF5")
    val MUT: Int = Color.parseColor("#9A98B3")
    val ACC: Int = Color.parseColor("#8B5CF6")
    val ACC2: Int = Color.parseColor("#6D5AE6")
    val ERR: Int = Color.parseColor("#F87171")

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(ctx: Context, fill: Int, radiusDp: Int, stroke: Int = 0): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.setColor(fill)
        d.cornerRadius = dp(ctx, radiusDp).toFloat()
        if (stroke != 0) d.setStroke(dp(ctx, 1), stroke)
        return d
    }

    fun accent(ctx: Context, radiusDp: Int): GradientDrawable {
        val d = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(ACC, ACC2))
        d.cornerRadius = dp(ctx, radiusDp).toFloat()
        return d
    }

    fun text(ctx: Context, s: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        return t
    }

    /** primary = botón morado; si no, botón gris oscuro con borde. */
    fun button(ctx: Context, label: String, primary: Boolean, onClick: () -> Unit): Button {
        val b = Button(ctx)
        b.text = label
        b.isAllCaps = false
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        b.setTextColor(Color.WHITE)
        b.stateListAnimator = null
        b.minHeight = dp(ctx, 48)
        b.minimumHeight = dp(ctx, 48)
        b.setPadding(dp(ctx, 14), 0, dp(ctx, 14), 0)
        b.background = if (primary) accent(ctx, 14) else rounded(ctx, Color.parseColor("#1C1C2A"), 14, LINE)
        b.setOnClickListener { onClick() }
        return b
    }

    fun lp(w: Int, h: Int, top: Int = 0, bottom: Int = 0, left: Int = 0, right: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h)
        p.setMargins(left, top, right, bottom)
        return p
    }

    fun fmtTime(ms: Long): String {
        if (ms < 0) return "--:--"
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
    }
}
