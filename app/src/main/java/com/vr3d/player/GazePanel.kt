package com.vr3d.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Panel de controles para el modo VR. Se dibuja en un bitmap que el renderizador coloca en el espacio 3D;
 * se activa mirándolo fijamente (sin tocar la pantalla).
 */
class GazePanel {
    companion object {
        const val W = 1024
        const val H = 256
        const val NONE = 0
        const val BACK = 1
        const val PLAY = 2
        const val FWD = 3
        const val CENTER = 4
        const val EXIT = 5
        const val SEEK = 6

        private const val BTN_Y0 = 112f
        private const val BTN_Y1 = 236f
        private const val BTN_W = 176f
        private const val BTN_GAP = 16f
        private const val BTN_X0 = 40f
        private const val BAR_X0 = 170f
        private const val BAR_X1 = 854f
        private const val BAR_Y = 52f
    }

    val bitmap: Bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    private fun btnX(i: Int): Float = BTN_X0 + i * (BTN_W + BTN_GAP)

    /** u, v en píxeles del bitmap. Devuelve el botón (o la barra de progreso) bajo ese punto. */
    fun hit(u: Float, v: Float): Int {
        if (v in BTN_Y0..BTN_Y1) {
            for (i in 0 until 5) {
                val x0 = btnX(i)
                if (u >= x0 && u <= x0 + BTN_W) return i + 1
            }
        }
        if (v in 10f..96f && u >= BAR_X0 - 20f && u <= BAR_X1 + 20f) return SEEK
        return NONE
    }

    fun seekFraction(u: Float): Float = ((u - BAR_X0) / (BAR_X1 - BAR_X0)).coerceIn(0f, 1f)

    fun draw(hover: Int, hoverU: Float, playing: Boolean, posMs: Long, durMs: Long) {
        bitmap.eraseColor(Color.TRANSPARENT)

        // fondo
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#FF101018")
        rect.set(0f, 0f, W.toFloat(), H.toFloat())
        canvas.drawRoundRect(rect, 36f, 36f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = Color.parseColor("#FF3A3A52")
        rect.set(2f, 2f, W - 2f, H - 2f)
        canvas.drawRoundRect(rect, 34f, 34f, paint)

        // tiempos
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#FFECEBF5")
        paint.textSize = 32f
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(Ui.fmtTime(posMs), 34f, BAR_Y + 11f, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(Ui.fmtTime(durMs), W - 34f, BAR_Y + 11f, paint)

        // barra de progreso
        paint.color = Color.parseColor("#FF333348")
        rect.set(BAR_X0, BAR_Y - 9f, BAR_X1, BAR_Y + 9f)
        canvas.drawRoundRect(rect, 9f, 9f, paint)
        val frac = if (durMs > 0L) (posMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f) else 0f
        val fillX = BAR_X0 + (BAR_X1 - BAR_X0) * frac
        paint.color = Color.parseColor("#FF8B5CF6")
        rect.set(BAR_X0, BAR_Y - 9f, fillX, BAR_Y + 9f)
        canvas.drawRoundRect(rect, 9f, 9f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(fillX, BAR_Y, 15f, paint)
        if (hover == SEEK) {
            val hx = hoverU.coerceIn(BAR_X0, BAR_X1)
            paint.color = Color.parseColor("#FF22D3EE")
            canvas.drawCircle(hx, BAR_Y, 13f, paint)
        }

        // botones
        for (i in 0 until 5) {
            val id = i + 1
            val x0 = btnX(i)
            val hot = hover == id
            paint.style = Paint.Style.FILL
            paint.color = if (hot) (if (id == EXIT) Color.parseColor("#FFB4343C") else Color.parseColor("#FF8B5CF6"))
            else Color.parseColor("#FF2A2A3E")
            rect.set(x0, BTN_Y0, x0 + BTN_W, BTN_Y1)
            canvas.drawRoundRect(rect, 28f, 28f, paint)

            val cx = x0 + BTN_W / 2f
            val cy = (BTN_Y0 + BTN_Y1) / 2f
            paint.color = Color.WHITE
            when (id) {
                BACK -> label("-10 s", cx, cy, 46f)
                FWD -> label("+10 s", cx, cy, 46f)
                CENTER -> label("Centrar", cx, cy, 40f)
                EXIT -> label("Salir", cx, cy, 44f)
                PLAY -> {
                    if (playing) {
                        rect.set(cx - 26f, cy - 30f, cx - 8f, cy + 30f)
                        canvas.drawRoundRect(rect, 4f, 4f, paint)
                        rect.set(cx + 8f, cy - 30f, cx + 26f, cy + 30f)
                        canvas.drawRoundRect(rect, 4f, 4f, paint)
                    } else {
                        path.reset()
                        path.moveTo(cx - 20f, cy - 34f)
                        path.lineTo(cx + 34f, cy)
                        path.lineTo(cx - 20f, cy + 34f)
                        path.close()
                        canvas.drawPath(path, paint)
                    }
                }
            }
        }
    }

    private fun label(text: String, cx: Float, cy: Float, size: Float) {
        paint.style = Paint.Style.FILL
        paint.textSize = size
        paint.textAlign = Paint.Align.CENTER
        val ty = cy - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, cx, ty, paint)
    }
}
