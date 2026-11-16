package tv.pane.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider

object Ui {
    var d = 2f
    /** Scaled-pixel factor: follows the system font scale. */
    var sd = 2f
    fun init(ctx: Context) {
        val m = ctx.resources.displayMetrics
        d = m.density; sd = m.scaledDensity
    }
    fun dp(v: Float) = (v * d + 0.5f).toInt()
    fun dpf(v: Float) = v * d
    fun sp(v: Float) = v * sd

    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    fun textPaint(sizeSp: Float, face: Typeface = regular, color: Int = Color.WHITE) =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = sp(sizeSp); typeface = face; this.color = color
        }

    val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val tmpRect = RectF()
    val loc = IntArray(2)
}

/**
 * Base for everything the remote can focus. Focus is shown by scale, lift and brightness
 * (unfocused items are dimmed), so it stays obvious with reduce motion on too.
 *
 * Only scale and Z are animated, both render-thread properties, with a hardware layer while
 * the spring runs, so a focus change never re-records a display list per frame.
 */
abstract class FocusView(ctx: Context) : View(ctx) {
    /** Space around the content reserved for the focus ring. */
    val pad = Ui.dp(5f)
    protected var radius = Ui.dpf(12f)
    protected var focusScale = 0.085f
    /** Opacity when not focused. Focus is shown by brightness, size and lift, not a border. */
    protected var dim = 1f
        set(v) { field = v; alpha = if (isFocused) 1f else v }

    /** Lets alpha be applied per draw call instead of through an off-screen buffer. */
    override fun hasOverlappingRendering() = false

    /** False for list rows: they share one gliding highlight instead of lifting individually. */
    protected var lift = true
    /** Content rect (inside [pad]) used for ring, outline and pivot. */
    protected val body = RectF()

    private val spring = Spring(0f, 300f, 0.72f) { f ->
        val s = 1f + focusScale * f
        scaleX = s; scaleY = s
        translationZ = Ui.dpf(10f) * f
        if (dim < 1f) alpha = dim + (1f - dim) * f.coerceIn(0f, 1f)
    }.also {
        it.onStart = { setLayerType(LAYER_TYPE_HARDWARE, null) }
        it.onEnd = { setLayerType(LAYER_TYPE_NONE, null) }
    }

    init {
        isFocusable = true
        isClickable = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(body.left.toInt(), body.top.toInt(), body.right.toInt(), body.bottom.toInt(), radius)
                o.alpha = 0.55f
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutBody(w, h)
        pivotX = body.centerX(); pivotY = body.centerY()
        invalidateOutline()
    }

    protected open fun layoutBody(w: Int, h: Int) {
        body.set(pad.toFloat(), pad.toFloat(), (w - pad).toFloat(), (h - pad).toFloat())
    }

    override fun onFocusChanged(gain: Boolean, direction: Int, prev: android.graphics.Rect?) {
        super.onFocusChanged(gain, direction, prev)
        if (lift) spring.to(if (gain) 1f else 0f)
        invalidate()
    }

    /** Glass panel behind [body], cropped from the blurred wallpaper at our screen position. */
    protected fun drawGlass(c: Canvas, extra: Int = 0) {
        getLocationOnScreen(Ui.loc)
        val s = scaleX
        // Items in the dock keep the crop from the dock's resting position (see Dock.onDraw).
        val ride = (parent as? Dock)?.translationY ?: 0f
        c.save()
        c.translate(body.left, body.top)
        Glass.drawPanel(c, body.width(), body.height(), radius, Ui.loc[0] + body.left * s, Ui.loc[1] + body.top * s - ride, false, extra)
        c.restore()
    }

    private var longHandled = false

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Remotes without a usable long-press can open the context menu with the Menu key.
        if (keyCode == KeyEvent.KEYCODE_MENU && isLongClickable) {
            performLongClick(); return true
        }
        // Some remotes (and adb) deliver a long press as a flagged repeat instead of a held key.
        if (event.isLongPress && isLongClickable && !longHandled &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            longHandled = true
            performLongClick()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (longHandled) {
            longHandled = false
            isPressed = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.Button"
}

/** Small original line glyphs, drawn with paths so there are no icon assets at all. */
object Glyph {
    const val SEARCH = 1; const val SLIDERS = 2; const val GEAR = 3; const val WIFI = 4
    const val WIRED = 5; const val OFFLINE = 6; const val INPUT = 7; const val STAR = 8
    const val CHECK = 9; const val IMAGE = 10; const val EYE_OFF = 11; const val MOVE = 12
    const val INFO = 13; const val TRASH = 14; const val BACKUP = 15; const val ANTENNA = 16
    const val GRID = 17

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val r = RectF()

    private val cache = HashMap<Int, android.graphics.Bitmap>()

    /**
     * Draws [glyph] in a box of [size] px whose top-left is ([x], [y]). Each glyph is rasterised
     * once and then drawn as a tiny bitmap: stroking paths every frame was a measurable cost.
     */
    fun draw(c: Canvas, glyph: Int, x: Float, y: Float, size: Float) {
        val s = size.toInt()
        val key = (glyph shl 12) or s
        var b = cache[key]
        if (b == null) {
            b = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
            render(Canvas(b), glyph, s.toFloat())
            cache[key] = b
        }
        c.drawBitmap(b, x, y, null)
    }

    private fun render(c: Canvas, glyph: Int, size: Float) {
        val x = 0f; val y = 0f
        p.color = Color.WHITE
        p.strokeWidth = size * 0.09f
        p.style = Paint.Style.STROKE
        val u = size / 24f
        c.save()
        c.translate(x, y)
        when (glyph) {
            SEARCH -> {
                c.drawCircle(10.5f * u, 10.5f * u, 6f * u, p)
                c.drawLine(15f * u, 15f * u, 20f * u, 20f * u, p)
            }
            SLIDERS -> {
                for (i in 0..2) {
                    val yy = (6f + i * 6f) * u
                    c.drawLine(3f * u, yy, 21f * u, yy, p)
                    p.style = Paint.Style.FILL
                    c.drawCircle((if (i == 1) 9f else 15f) * u, yy, 2.4f * u, p)
                    p.style = Paint.Style.STROKE
                }
            }
            GEAR -> {
                c.drawCircle(12f * u, 12f * u, 3.2f * u, p)
                c.drawCircle(12f * u, 12f * u, 7f * u, p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val cs = Math.cos(a).toFloat(); val sn = Math.sin(a).toFloat()
                    c.drawLine((12f + 7f * cs) * u, (12f + 7f * sn) * u, (12f + 10f * cs) * u, (12f + 10f * sn) * u, p)
                }
            }
            WIFI, OFFLINE -> {
                for (i in 0..2) {
                    val rad = (4.5f + i * 4.5f) * u
                    r.set(12f * u - rad, 19f * u - rad, 12f * u + rad, 19f * u + rad)
                    c.drawArc(r, 225f, 90f, false, p)
                }
                p.style = Paint.Style.FILL
                c.drawCircle(12f * u, 19f * u, 1.4f * u, p)
                p.style = Paint.Style.STROKE
                if (glyph == OFFLINE) c.drawLine(4f * u, 4f * u, 20f * u, 21f * u, p)
            }
            WIRED -> {
                r.set(9f * u, 3f * u, 15f * u, 9f * u); c.drawRoundRect(r, u, u, p)
                r.set(3f * u, 15f * u, 9f * u, 21f * u); c.drawRoundRect(r, u, u, p)
                r.set(15f * u, 15f * u, 21f * u, 21f * u); c.drawRoundRect(r, u, u, p)
                c.drawLine(12f * u, 9f * u, 12f * u, 12f * u, p)
                c.drawLine(6f * u, 12f * u, 18f * u, 12f * u, p)
                c.drawLine(6f * u, 12f * u, 6f * u, 15f * u, p)
                c.drawLine(18f * u, 12f * u, 18f * u, 15f * u, p)
            }
            INPUT -> {
                r.set(3f * u, 5f * u, 21f * u, 17f * u); c.drawRoundRect(r, 2f * u, 2f * u, p)
                c.drawLine(8f * u, 21f * u, 16f * u, 21f * u, p)
                c.drawLine(9f * u, 11f * u, 15f * u, 11f * u, p)
                c.drawLine(12.5f * u, 8.5f * u, 15f * u, 11f * u, p)
                c.drawLine(12.5f * u, 13.5f * u, 15f * u, 11f * u, p)
            }
            ANTENNA -> {
                c.drawLine(12f * u, 10f * u, 12f * u, 21f * u, p)
                c.drawLine(7f * u, 21f * u, 17f * u, 21f * u, p)
                for (i in 0..1) {
                    val rad = (4f + i * 4f) * u
                    r.set(12f * u - rad, 9f * u - rad, 12f * u + rad, 9f * u + rad)
                    c.drawArc(r, 200f, 140f, false, p)
                }
            }
            GRID -> {
                p.style = Paint.Style.FILL
                for (gy in 0..2) for (gx in 0..2) c.drawCircle((5.5f + gx * 6.5f) * u, (5.5f + gy * 6.5f) * u, 1.9f * u, p)
                p.style = Paint.Style.STROKE
            }
            STAR -> {
                path.reset()
                for (i in 0 until 10) {
                    val a = Math.toRadians(-90.0 + i * 36.0)
                    val rad = if (i % 2 == 0) 9f else 4f
                    val px = (12f + rad * Math.cos(a).toFloat()) * u
                    val py = (12.5f + rad * Math.sin(a).toFloat()) * u
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close(); c.drawPath(path, p)
            }
            CHECK -> {
                path.reset(); path.moveTo(5f * u, 12.5f * u); path.lineTo(10f * u, 17.5f * u); path.lineTo(19f * u, 7f * u)
                c.drawPath(path, p)
            }
            IMAGE -> {
                r.set(3f * u, 5f * u, 21f * u, 19f * u); c.drawRoundRect(r, 2f * u, 2f * u, p)
                path.reset(); path.moveTo(6f * u, 16f * u); path.lineTo(10.5f * u, 11f * u); path.lineTo(14f * u, 14.5f * u)
                path.lineTo(16f * u, 12.5f * u); path.lineTo(19f * u, 16f * u)
                c.drawPath(path, p)
                c.drawCircle(16f * u, 9f * u, 1.3f * u, p)
            }
            EYE_OFF -> {
                r.set(3f * u, 6f * u, 21f * u, 18f * u); c.drawOval(r, p)
                c.drawCircle(12f * u, 12f * u, 2.6f * u, p)
                c.drawLine(5f * u, 4f * u, 19f * u, 20f * u, p)
            }
            MOVE -> {
                c.drawLine(4f * u, 12f * u, 20f * u, 12f * u, p)
                c.drawLine(12f * u, 4f * u, 12f * u, 20f * u, p)
                path.reset()
                path.moveTo(9.5f * u, 6.5f * u); path.lineTo(12f * u, 4f * u); path.lineTo(14.5f * u, 6.5f * u)
                path.moveTo(9.5f * u, 17.5f * u); path.lineTo(12f * u, 20f * u); path.lineTo(14.5f * u, 17.5f * u)
                path.moveTo(6.5f * u, 9.5f * u); path.lineTo(4f * u, 12f * u); path.lineTo(6.5f * u, 14.5f * u)
                path.moveTo(17.5f * u, 9.5f * u); path.lineTo(20f * u, 12f * u); path.lineTo(17.5f * u, 14.5f * u)
                c.drawPath(path, p)
            }
            INFO -> {
                c.drawCircle(12f * u, 12f * u, 9f * u, p)
                c.drawLine(12f * u, 11f * u, 12f * u, 16.5f * u, p)
                p.style = Paint.Style.FILL; c.drawCircle(12f * u, 7.8f * u, 1.2f * u, p)
            }
            TRASH -> {
                c.drawLine(4f * u, 7f * u, 20f * u, 7f * u, p)
                c.drawLine(9.5f * u, 4f * u, 14.5f * u, 4f * u, p)
                path.reset(); path.moveTo(6.5f * u, 7f * u); path.lineTo(7.5f * u, 20f * u); path.lineTo(16.5f * u, 20f * u); path.lineTo(17.5f * u, 7f * u)
                c.drawPath(path, p)
            }
            BACKUP -> {
                c.drawLine(12f * u, 4f * u, 12f * u, 15f * u, p)
                path.reset(); path.moveTo(7.5f * u, 10.5f * u); path.lineTo(12f * u, 15f * u); path.lineTo(16.5f * u, 10.5f * u)
                path.moveTo(4f * u, 16f * u); path.lineTo(4f * u, 20f * u); path.lineTo(20f * u, 20f * u); path.lineTo(20f * u, 16f * u)
                c.drawPath(path, p)
            }
        }
        c.restore()
    }
}
