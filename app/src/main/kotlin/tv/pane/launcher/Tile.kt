package tv.pane.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils

/**
 * One app tile. A single View that draws everything itself: no nested layout, no
 * ImageView/TextView, so 150+ tiles stay cheap. Artwork is only held while [active].
 */
class TileView(ctx: Context) : FocusView(ctx), Icons.Target {
    var entry: AppEntry? = null
        private set
    var title = ""
        private set
    var moving = false
        set(v) { field = v; invalidate() }
    /** True for tiles in the home dock (as opposed to the apps panel). */
    var inDock = false
    var bodyW = tileW
    var bodyH = tileH

    private var art: Bitmap? = null
    private var artShader: BitmapShader? = null
    private var active = false
    private var shown = ""
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val mtx = Matrix()

    companion object {
        var tileW = 320; var tileH = 180
        val label = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        var labelH = 0
        fun metrics() {
            label.textSize = Ui.sp(11.5f)
            label.typeface = Ui.medium
            val fm = label.fontMetrics
            labelH = (fm.descent - fm.ascent + Ui.dpf(4f)).toInt()
        }
    }

    init {
        dim = 0.5f
    }

    fun bind(e: AppEntry, dock: Boolean, w: Int = tileW) {
        val changed = entry?.pkg != e.pkg || entry?.stamp != e.stamp
        if (inDock != dock) requestLayout()
        entry = e; inDock = dock
        title = e.label
        contentDescription = e.label
        focusScale = if (dock) 0.13f else 0.085f
        if (w != bodyW) {
            bodyW = w; bodyH = w * 9 / 16; shown = ""
            requestLayout()
        }
        if (changed) {
            art = null; artShader = null; shown = ""
            if (active) request()
        }
        invalidate()
    }

    /** Called as tiles come in and out of range. */
    fun setActive(a: Boolean) {
        if (a == active) return
        active = a
        if (a) request() else {
            entry?.let { Icons.cancel(it.pkg, this) }
            art = null; artShader = null
            // Re-record so no display list keeps pointing at a bitmap that may be freed.
            invalidate()
        }
    }

    /** Glass tiles show a crop of the wallpaper, so they redraw when the grid scrolls. */
    fun isGlass() = entry?.hasBanner != true || art == null

    private fun request() {
        val e = entry ?: return
        Icons.get(e, this)?.let { setArt(it) }
    }

    override fun onArt(pkg: String, bmp: Bitmap) {
        if (active && entry?.pkg == pkg) setArt(bmp)
    }

    private fun setArt(b: Bitmap) {
        art = b
        artShader = if (entry?.hasBanner == true) BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) else null
        invalidate()
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        // Dock tiles carry no name: the artwork says it, and TalkBack reads the description.
        setMeasuredDimension(bodyW + pad * 2, bodyH + pad * 2 + if (inDock) 0 else labelH)
    }

    override fun layoutBody(w: Int, h: Int) {
        body.set(pad.toFloat(), pad.toFloat(), (pad + bodyW).toFloat(), (pad + bodyH).toFloat())
    }

    override fun onDraw(c: Canvas) {
        val a = art
        val sh = artShader
        if (a != null && sh != null) {
            mtx.setScale(body.width() / a.width, body.height() / a.height)
            mtx.postTranslate(body.left, body.top)
            sh.setLocalMatrix(mtx)
            artPaint.shader = sh
            c.drawRoundRect(body, radius, radius, artPaint)
            c.save(); c.translate(body.left, body.top)
            Glass.drawEdge(c, body.width(), body.height(), radius)
            c.restore()
        } else {
            drawGlass(c)
            if (a != null) {
                artPaint.shader = null
                // Icons are rendered for full-size tiles; shrink them with the tile in the dock.
                val s = minOf(1f, body.height() * 0.62f / a.height)
                c.save()
                c.translate(body.centerX(), body.centerY())
                c.scale(s, s)
                c.drawBitmap(a, -a.width / 2f, -a.height / 2f, artPaint)
                c.restore()
            }
        }
        if (!inDock) {
            if (shown.isEmpty()) shown = TextUtils.ellipsize(title, label, body.width() + pad * 2f, TextUtils.TruncateAt.END).toString()
            label.color = -0x1
            val tw = label.measureText(shown)
            c.drawText(shown, body.centerX() - tw / 2f, body.bottom + Ui.dpf(5f) - label.fontMetrics.ascent, label)
        }
        if (moving) {
            val g = Ui.dpf(26f)
            Ui.ringPaint.style = Paint.Style.FILL
            Ui.ringPaint.color = 0xB0000000.toInt()
            c.drawCircle(body.centerX(), body.centerY(), g * 0.85f, Ui.ringPaint)
            Glyph.draw(c, Glyph.MOVE, body.centerX() - g / 2f, body.centerY() - g / 2f, g)
        }
    }
}
