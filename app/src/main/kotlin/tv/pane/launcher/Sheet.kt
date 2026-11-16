package tv.pane.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.TextUtils
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/** One line in a sheet: glyph or colour swatch, label, optional value on the right. */
class RowView(ctx: Context, var glyph: Int, var text: String, value: String = "") : FocusView(ctx) {
    private val paint = Ui.textPaint(13.5f, Ui.medium)
    private val valuePaint = Ui.textPaint(12.5f, Ui.regular, 0xC0FFFFFF.toInt())
    private val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    var value = value
        set(v) { field = v; describe(); invalidate() }
    var checked = false
        set(v) { field = v; describe(); invalidate() }
    private var swatch: Glass.Preset? = null

    init {
        lift = false
        outlineProvider = null
        radius = Ui.dpf(10f)
        describe()
    }

    override fun onFocusChanged(gain: Boolean, direction: Int, prev: android.graphics.Rect?) {
        super.onFocusChanged(gain, direction, prev)
        (parent as? RowList)?.focusMoved(this, gain)
    }

    fun swatch(p: Glass.Preset): RowView { swatch = p; return this }

    private fun describe() {
        contentDescription = buildString {
            append(text)
            if (value.isNotEmpty()) append(", ").append(value)
            if (checked) append(", selected")
        }
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val fm = paint.fontMetrics
        val h = maxOf(Ui.dp(40f), (fm.descent - fm.ascent + Ui.dpf(16f)).toInt())
        setMeasuredDimension(MeasureSpec.getSize(wSpec), h + pad * 2)
    }

    override fun onDraw(c: Canvas) {
        var x = body.left + Ui.dpf(12f)
        val g = Ui.dpf(18f)
        val sw = swatch
        if (sw != null) {
            swatchPaint.shader = LinearGradient(x, body.centerY() - g / 2, x + g, body.centerY() + g / 2, sw.glow1, sw.b, Shader.TileMode.CLAMP)
            Ui.tmpRect.set(x, body.centerY() - g / 2f, x + g, body.centerY() + g / 2f)
            c.drawRoundRect(Ui.tmpRect, Ui.dpf(5f), Ui.dpf(5f), swatchPaint)
            x += g + Ui.dpf(12f)
        } else if (glyph != 0) {
            Glyph.draw(c, glyph, x, body.centerY() - g / 2f, g)
            x += g + Ui.dpf(12f)
        }
        var right = body.right - Ui.dpf(12f)
        val fm = paint.fontMetrics
        val base = body.centerY() - (fm.ascent + fm.descent) / 2f
        if (checked) {
            Glyph.draw(c, Glyph.CHECK, right - g, body.centerY() - g / 2f, g)
            right -= g + Ui.dpf(8f)
        }
        if (value.isNotEmpty()) {
            val vw = valuePaint.measureText(value)
            c.drawText(value, right - vw, base, valuePaint)
            right -= vw + Ui.dpf(10f)
        }
        val shown = TextUtils.ellipsize(text, android.text.TextPaint(paint), right - x, TextUtils.TruncateAt.END).toString()
        c.drawText(shown, x, base, paint)
    }
}

/**
 * Holds the rows and tracks the one focus highlight, which glides from row to row on a spring.
 * While it moves only a single rounded rect is redrawn; the rows themselves sit in the panel's
 * GPU layer and are not touched.
 */
class RowList(ctx: Context) : LinearLayout(ctx) {
    private var current: RowView? = null
    private var placed = false
    private var shown = false
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x38FFFFFF }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = Ui.dpf(2.2f); color = -0x1
    }
    private val rect = android.graphics.RectF()
    var overlay: View? = null
    private val y = Spring(0f, 520f, 0.86f) { overlay?.invalidate() }.also { it.restDelta = 0.5f }

    init {
        orientation = VERTICAL
        clipChildren = false; clipToPadding = false
    }

    fun focusMoved(row: RowView, gain: Boolean) {
        if (gain) {
            current = row
            // First appearance (or returning from the text field) places it; after that it glides.
            if (!shown || !placed) { y.snap(row.top.toFloat()); placed = row.height > 0 } else y.to(row.top.toFloat())
            shown = true
        } else if (current === row) {
            // Focus usually just moved to another row (loss arrives before gain), so only hide
            // if nothing in the list picked it up.
            post { if (current === row && !row.isFocused) { shown = false; overlay?.invalidate() } }
        }
        overlay?.invalidate()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val c = current
        if (c != null && c.parent === this) {
            if (!placed) { y.snap(c.top.toFloat()); placed = true } else if (!y.isRunning) y.snap(c.top.toFloat())
        } else if (c != null) { current = null; shown = false }
    }

    /** Drawn by the sheet's overlay, in screen coordinates, so the panel's layer stays untouched. */
    fun drawHighlight(c: Canvas) {
        val row = current ?: return
        if (!shown) return
        getLocationOnScreen(Ui.loc)
        val p = row.pad.toFloat()
        val rad = Ui.dpf(10f)
        val ox = Ui.loc[0].toFloat()
        val oy = Ui.loc[1].toFloat()
        rect.set(ox + p, oy + y.value + p, ox + width - p, oy + y.value + row.height - p)
        c.drawRoundRect(rect, rad, rad, fill)
        c.drawRoundRect(rect, rad, rad, ring)
    }
}

/**
 * Modal glass panel on the right edge. Used for every menu so there is one focus model:
 * focus is trapped inside, Back closes, and the rest of the screen is hidden from TalkBack.
 */
class Sheet(private val host: MainActivity, val title: String, widthDp: Float = 300f, atTop: Boolean = false) : FrameLayout(host) {
    val rows: RowList = RowList(host)
    val panel: LinearLayout
    private var restore: View? = null
    private var closing = false
    var onClosed: (() -> Unit)? = null

    private val anim = Spring(0f, 320f, 0.9f) { f ->
        panel.alpha = f.coerceIn(0f, 1f)
        panel.translationX = Ui.dpf(36f) * (1f - f)
        glow.alpha = f.coerceIn(0f, 1f)
        glow.invalidate()
    }
    /** Transparent overlay above the panel that draws only the focus highlight. */
    private val glow = object : View(host) {
        override fun onDraw(c: Canvas) {
            // Keep the highlight inside the visible list when a long list is scrolled.
            scroller.getLocationOnScreen(Ui.loc)
            c.clipRect(0, Ui.loc[1] - Ui.dp(6f), width, Ui.loc[1] + scroller.height + Ui.dp(6f))
            rows.drawHighlight(c)
        }
    }
    private val scroller: ScrollView

    init {
        clipChildren = false
        isFocusable = false
        accessibilityPaneTitle = title
        panel = object : LinearLayout(host) {
            init { setWillNotDraw(false); orientation = VERTICAL; clipChildren = false; clipToPadding = false }
            override fun onDraw(c: Canvas) {
                // The panel sits in a full-screen overlay, so its layout position is its screen
                // position; using that keeps the glass crop right while it slides in on a layer.
                Glass.drawPanel(c, width.toFloat(), height.toFloat(), Ui.dpf(22f), left.toFloat(), top.toFloat(), true)
            }
        }
        val p = Ui.dp(12f)
        panel.setPadding(p, Ui.dp(14f), p, p)
        panel.addView(HeaderView(host, title).apply { setPadding(0, 0, 0, 0) },
            LinearLayout.LayoutParams(-1, -2).apply { leftMargin = Ui.dp(14f); bottomMargin = Ui.dp(4f) })
        scroller = ScrollView(host).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false; clipToPadding = false
            isFocusable = false
            addView(rows, ViewGroup.LayoutParams(-1, -2))
        }
        panel.addView(scroller, LinearLayout.LayoutParams(-1, -2))
        val m = Ui.dp(28f)
        addView(panel, LayoutParams(Ui.dp(widthDp), -2, Gravity.END or (if (atTop) Gravity.TOP else Gravity.CENTER_VERTICAL)).apply {
            rightMargin = Ui.dp(40f); topMargin = m; bottomMargin = m
        })
        addView(glow, LayoutParams(-1, -1))
        glow.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        rows.overlay = glow
        scroller.setOnScrollChangeListener { _, _, _, _, _ -> glow.invalidate() }
        // The panel is static while open, so it lives in one GPU layer for its whole life:
        // sliding it in, and moving the highlight over it, never re-renders its contents.
        panel.setLayerType(LAYER_TYPE_HARDWARE, null)
        anim.onEnd = {
            if (closing) {
                (parent as? ViewGroup)?.removeView(this)
                onClosed?.invoke()
            }
        }
    }

    fun row(glyph: Int, text: String, value: String = "", action: (RowView) -> Unit): RowView {
        val r = RowView(host, glyph, text, value)
        r.setOnClickListener { action(r) }
        rows.addView(r, LinearLayout.LayoutParams(-1, -2))
        return r
    }

    fun note(text: String) {
        rows.addView(HeaderView(host, text), LinearLayout.LayoutParams(-1, -2).apply { leftMargin = Ui.dp(14f) })
    }

    fun show(focusIndex: Int = 0) {
        restore = host.currentFocus
        host.setModal(this)
        host.root.addView(this, LayoutParams(-1, -1))
        var n = 0
        for (i in 0 until rows.childCount) {
            val v = rows.getChildAt(i)
            if (v is RowView) {
                if (n == focusIndex) { v.requestFocus(); break }
                n++
            }
        }
        anim.to(1f)
    }

    fun dismiss(restoreFocus: Boolean = true) {
        if (closing) return
        closing = true
        host.setModal(null)
        if (restoreFocus) {
            host.refocus(restore)
        }
        if (anim.value <= 0f) { (parent as? ViewGroup)?.removeView(this); onClosed?.invoke() } else anim.to(0f)
    }

    /** Focus never leaves the sheet while it is open. */
    override fun focusSearch(focused: View?, direction: Int): View? {
        return FocusFinder.getInstance().findNextFocus(this, focused, direction) ?: focused
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) dismiss()
            return true
        }
        return super.dispatchKeyEvent(e)
    }
}
