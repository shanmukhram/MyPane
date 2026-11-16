package tv.pane.launcher

import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup

/** Round glass button holding one glyph. */
class CircleView(ctx: Context, private val glyph: Int, description: String) : FocusView(ctx) {
    private val size = Ui.dp(50f)
    init {
        radius = size / 2f
        focusScale = 0.13f
        dim = 0.7f
        contentDescription = description
    }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(size + pad * 2, size + pad * 2)
    override fun onDraw(c: Canvas) {
        drawGlass(c, if (isFocused) 0x59FFFFFF else 0x14FFFFFF)
        val g = Ui.dpf(22f)
        Glyph.draw(c, glyph, body.centerX() - g / 2f, body.centerY() - g / 2f, g)
    }
}

/**
 * The home dock: a glass shelf along the bottom with the round buttons and the user's chosen
 * apps. Children are laid out in a row; LEFT/RIGHT walk the row, UP goes to the top bar and
 * DOWN into the app rows that continue below it.
 */
class Dock(ctx: Context) : ViewGroup(ctx) {
    var above: () -> View? = { null }
    var below: (View) -> View? = { null }
    /** Called whenever something in the dock takes focus. */
    var onFocus: (View) -> Unit = {}
    private val inner = Ui.dp(9f)
    private val gap = Ui.dp(4f)

    init {
        setWillNotDraw(false)
        clipChildren = false; clipToPadding = false
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }

    fun setItems(items: List<View>) {
        var i = childCount - 1
        while (i >= 0) {
            if (!items.contains(getChildAt(i))) removeViewAt(i)
            i--
        }
        // Child order is focus/reading order.
        for ((k, v) in items.withIndex()) {
            if (v.parent == null) addView(v, k)
            else if (indexOfChild(v) != k) { removeView(v); addView(v, k) }
        }
        requestLayout()
    }

    private fun shelfHeight() = maxChildBody() + inner * 2

    private fun maxChildBody(): Int {
        var h = 0
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val bh = if (v is TileView) v.bodyH else v.measuredHeight - (v as FocusView).pad * 2
            if (bh > h) h = bh
        }
        return h
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        var w = inner * 2
        var h = 0
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            v.measure(0, 0)
            w += v.measuredWidth + if (i > 0) gap else 0
            if (v.measuredHeight > h) h = v.measuredHeight
        }
        setMeasuredDimension(w, h + inner * 2)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var x = inner
        val shelf = shelfHeight()
        for (i in 0 until childCount) {
            val v = getChildAt(i) as FocusView
            // Centre every item's body on the shelf (tiles also carry label space below).
            val bodyH = if (v is TileView) v.bodyH else v.measuredHeight - v.pad * 2
            val y = (shelf - bodyH) / 2 - v.pad
            v.layout(x, y, x + v.measuredWidth, y + v.measuredHeight)
            x += v.measuredWidth + gap
        }
    }

    override fun onDraw(c: Canvas) {
        // Sample the wallpaper from the dock's resting place, not from wherever the page has
        // scrolled it to, so its colour never shifts while it rides up and down.
        getLocationOnScreen(Ui.loc)
        Glass.drawPanel(c, width.toFloat(), shelfHeight().toFloat(), Ui.dpf(26f), Ui.loc[0].toFloat(), Ui.loc[1] - translationY)
    }

    override fun requestChildFocus(child: View, focused: View) {
        super.requestChildFocus(child, focused)
        onFocus(focused)
    }

    override fun focusSearch(focused: View?, direction: Int): View? {
        val i = indexOfChild(focused)
        if (i < 0 || focused == null) return super.focusSearch(focused, direction)
        return when (direction) {
            View.FOCUS_LEFT -> if (i > 0) getChildAt(i - 1) else focused
            View.FOCUS_RIGHT -> if (i < childCount - 1) getChildAt(i + 1) else focused
            View.FOCUS_UP -> above() ?: focused
            View.FOCUS_DOWN -> below(focused) ?: focused
            else -> focused
        }
    }
}
