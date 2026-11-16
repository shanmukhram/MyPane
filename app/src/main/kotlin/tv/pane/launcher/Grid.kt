package tv.pane.launcher

import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs

class HeaderView(ctx: Context, val text: String) : View(ctx) {
    private val paint = Ui.textPaint(13f, Ui.medium, 0xD9FFFFFF.toInt())
    init {
        contentDescription = text
        isAccessibilityHeading = true
    }
    override fun onMeasure(w: Int, h: Int) {
        val fm = paint.fontMetrics
        setMeasuredDimension(MeasureSpec.getSize(w), (fm.descent - fm.ascent + Ui.dpf(10f)).toInt())
    }
    override fun onDraw(c: Canvas) {
        c.drawText(text, 0f, Ui.dpf(4f) - paint.fontMetrics.ascent, paint)
    }
}

class Section(val title: String, val tiles: List<TileView>, val cols: Int = 5) {
    lateinit var header: HeaderView
}

/**
 * The scrolling content: sections of tiles in a fixed column grid.
 *
 * It is laid out at full content height and scrolled with translationY (a render-thread
 * property) on a spring. D-pad navigation is explicit by row/column instead of geometric
 * search, so focus order is predictable for TalkBack and never skips a short last row.
 */
class Grid(ctx: Context) : ViewGroup(ctx) {
    var onScroll: ((Float) -> Unit)? = null
    /** Where the grid's top edge sits on screen when the page is not scrolled. */
    var layoutTop = 0
    var screenH = 1080
    /** Where UP goes from the first row. */
    var above: () -> View? = { null }

    private val sections = ArrayList<Section>()
    private val rows = ArrayList<List<TileView>>()
    private val side = Ui.dp(48f)
    private val gap = Ui.dp(16f)
    private var contentH = 0

    private val scroll = Spring(0f, 190f, 1f) { y ->
        translationY = -y
        onScroll?.invoke(y)
        updateActive()
        refreshGlass()
    }.also { it.restDelta = 0.5f }

    init {
        clipChildren = false
        clipToPadding = false
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }

    fun setSections(list: List<Section>) {
        val keep = HashSet<View>()
        for (s in list) {
            // Reuse a header with the same title so TalkBack focus/order is stable.
            val old = sections.firstOrNull { it.title == s.title }
            s.header = old?.header ?: HeaderView(context, s.title)
            keep.add(s.header); keep.addAll(s.tiles)
        }
        var i = childCount - 1
        while (i >= 0) {
            val v = getChildAt(i)
            if (!keep.contains(v)) removeViewAt(i)
            i--
        }
        for (s in list) {
            if (s.header.parent == null) addView(s.header)
            for (t in s.tiles) if (t.parent == null) addView(t)
        }
        sections.clear(); sections.addAll(list)
        rows.clear()
        for (s in list) {
            var k = 0
            while (k < s.tiles.size) {
                rows.add(s.tiles.subList(k, minOf(k + s.cols, s.tiles.size)))
                k += s.cols
            }
        }
        requestLayout()
    }

    fun firstTile(): TileView? = rows.firstOrNull()?.firstOrNull()

    /** The tile in the first row closest (horizontally, on screen) to [from]. */
    fun nearestInFirstRow(from: View): View? {
        val row = rows.firstOrNull() ?: return null
        from.getLocationOnScreen(Ui.loc)
        val cx = Ui.loc[0] + from.width / 2
        return row.minByOrNull { abs(it.left + it.width / 2 - cx) }
    }
    fun allTiles(): Sequence<TileView> = rows.asSequence().flatten()

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        var y = 0
        for (s in sections) {
            if (s.tiles.isEmpty()) continue
            s.header.measure(MeasureSpec.makeMeasureSpec(w - side * 2, MeasureSpec.EXACTLY), 0)
            y += s.header.measuredHeight
            var n = 0
            var rowH = 0
            for (t in s.tiles) {
                t.measure(0, 0)
                rowH = t.measuredHeight
                n++
            }
            y += ((n + s.cols - 1) / s.cols) * rowH + Ui.dp(10f)
        }
        contentH = y + Ui.dp(40f)
        setMeasuredDimension(w, contentH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = 0
        for (s in sections) {
            if (s.tiles.isEmpty()) {
                s.header.layout(0, 0, 0, 0); continue
            }
            s.header.layout(side, y, side + s.header.measuredWidth, y + s.header.measuredHeight)
            y += s.header.measuredHeight
            var i = 0
            var rowH = 0
            for (tile in s.tiles) {
                val col = i % s.cols
                if (col == 0 && i > 0) y += rowH
                rowH = tile.measuredHeight
                val x = side - tile.pad + col * (TileView.tileW + gap)
                tile.layout(x, y, x + tile.measuredWidth, y + tile.measuredHeight)
                i++
            }
            y += rowH + Ui.dp(10f)
        }
        updateActive()
        // Content may have shrunk (uninstall, hide): keep the scroll position legal.
        val max = maxScroll()
        if (scroll.target > max) scroll.to(max)
    }

    private fun maxScroll() = maxOf(0, layoutTop + contentH - screenH).toFloat()

    // ---- scrolling -------------------------------------------------------------------------

    override fun requestChildFocus(child: View, focused: View) {
        super.requestChildFocus(child, focused)
        reveal(focused)
    }

    fun reveal(v: View) {
        // Keep the focused row at a steady height on screen, like a tracking shot.
        val want = layoutTop + v.top - screenH * 0.30f
        scroll.to(want.coerceIn(0f, maxScroll()))
    }

    fun scrollToTop() = scroll.to(0f)
    fun scrollY2() = scroll.value

    /** Hand artwork only to tiles on or near the screen; everything else holds no bitmap. */
    fun updateActive() {
        val lo = minOf(scroll.value, scroll.target) - layoutTop - screenH * 0.35f
        val hi = maxOf(scroll.value, scroll.target) - layoutTop + screenH * 1.35f
        for (r in rows) {
            val first = r[0]
            val on = shown && first.bottom > lo && first.top < hi
            for (t in r) t.setActive(on)
        }
    }

    private var shown = true

    /** Home went to the background: drop all artwork references so the cache can be freed. */
    fun setShown(s: Boolean) {
        shown = s
        updateActive()
    }

    fun refreshGlass() {
        val lo = scroll.value - layoutTop - 300
        val hi = scroll.value - layoutTop + screenH + 300
        for (r in rows) {
            val first = r[0]
            if (first.bottom < lo || first.top > hi) continue
            for (t in r) if (t.isGlass()) t.invalidate()
        }
    }

    // ---- focus -----------------------------------------------------------------------------

    override fun focusSearch(focused: View?, direction: Int): View? {
        val ri = rows.indexOfFirst { it.contains(focused) }
        if (ri < 0 || focused == null) return super.focusSearch(focused, direction)
        val row = rows[ri]
        val ci = row.indexOf(focused)
        return when (direction) {
            View.FOCUS_LEFT -> if (ci > 0) row[ci - 1] else focused
            View.FOCUS_RIGHT -> if (ci < row.size - 1) row[ci + 1] else focused
            View.FOCUS_UP -> if (ri == 0) above() ?: focused else nearest(rows[ri - 1], focused)
            View.FOCUS_DOWN -> if (ri == rows.size - 1) focused else nearest(rows[ri + 1], focused)
            else -> super.focusSearch(focused, direction)
        }
    }

    private fun nearest(row: List<TileView>, from: View): View {
        val cx = from.left + from.width / 2
        var best: View = row[0]
        var bd = Int.MAX_VALUE
        for (t in row) {
            val d = abs(t.left + t.width / 2 - cx)
            if (d < bd) { bd = d; best = t }
        }
        return best
    }
}
