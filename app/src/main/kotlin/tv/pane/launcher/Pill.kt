package tv.pane.launcher

import android.content.Context
import android.graphics.Canvas

/** Glass capsule button for the top bar: optional glyph, text, optional dimmer second text. */
class PillView(ctx: Context, var glyph: Int = 0, text: String = "", sub: String = "") : FocusView(ctx) {
    private val paint = Ui.textPaint(13f, Ui.medium)
    private val subPaint = Ui.textPaint(12f, Ui.regular, 0xB8FFFFFF.toInt())
    private val h = Ui.dp(34f)
    var text = text
        private set
    var sub = sub
        private set

    init {
        focusScale = 0.07f
        radius = h / 2f
        // Spoken label defaults to the visible text; callers may replace it.
        if (text.isNotEmpty()) contentDescription = text
    }

    fun set(text: String, sub: String = "", glyph: Int = this.glyph) {
        if (text == this.text && sub == this.sub && glyph == this.glyph) return
        this.text = text; this.sub = sub; this.glyph = glyph
        requestLayout(); invalidate()
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        var w = Ui.dpf(14f) * 2
        if (glyph != 0) w += Ui.dpf(18f)
        if (glyph != 0 && text.isNotEmpty()) w += Ui.dpf(8f)
        if (text.isNotEmpty()) w += paint.measureText(text)
        if (sub.isNotEmpty()) w += Ui.dpf(8f) + subPaint.measureText(sub)
        val fm = paint.fontMetrics
        // Grow with the system font scale instead of clipping text.
        val bh = maxOf(h, (fm.descent - fm.ascent + Ui.dpf(12f)).toInt())
        radius = bh / 2f
        setMeasuredDimension(w.toInt() + pad * 2, bh + pad * 2)
    }

    override fun onDraw(c: Canvas) {
        drawGlass(c, if (isFocused) 0x4DFFFFFF else 0)
        var x = body.left + Ui.dpf(14f)
        if (glyph != 0) {
            val g = Ui.dpf(18f)
            Glyph.draw(c, glyph, x, body.centerY() - g / 2f, g)
            x += g + Ui.dpf(8f)
        }
        val fm = paint.fontMetrics
        val base = body.centerY() - (fm.ascent + fm.descent) / 2f
        if (text.isNotEmpty()) {
            c.drawText(text, x, base, paint)
            x += paint.measureText(text) + Ui.dpf(8f)
        }
        if (sub.isNotEmpty()) {
            c.drawText(sub, x, base, subPaint)
        }
    }
}
