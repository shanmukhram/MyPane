package tv.pane.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import java.io.File
import java.io.FileOutputStream

/**
 * Glass without live blur (API 29 has no RenderEffect).
 *
 * The wallpaper is rendered once into [sharp]; a small copy is blurred once into [blur].
 * Both are cached on disk and only rebuilt when the wallpaper changes. A panel draws the
 * crop of a pre-tinted copy of [blur] that sits behind it (a shader matrix, no per-frame
 * blur) and a 1 px highlight edge.
 */
object Glass {
    class Preset(val name: String, val a: Int, val b: Int, val glow1: Int, val glow2: Int)

    val presets = arrayOf(
        Preset("Midnight", 0xFF0B1026.toInt(), 0xFF1B2A52.toInt(), 0xFF3F6BD9.toInt(), 0xFF7A3FD0.toInt()),
        Preset("Aurora", 0xFF06141A.toInt(), 0xFF0E3B3A.toInt(), 0xFF27C9A0.toInt(), 0xFF3B6FE0.toInt()),
        Preset("Ember", 0xFF1A0B0B.toInt(), 0xFF45161A.toInt(), 0xFFE8663A.toInt(), 0xFFC02F6B.toInt()),
        Preset("Orchid", 0xFF140B22.toInt(), 0xFF361B55.toInt(), 0xFFB45BE0.toInt(), 0xFF4F6BE8.toInt()),
        Preset("Forest", 0xFF07130C.toInt(), 0xFF143524.toInt(), 0xFF4FB86A.toInt(), 0xFFB5C24A.toInt()),
        Preset("Dune", 0xFF17120B.toInt(), 0xFF3D2E1C.toInt(), 0xFFE0A552.toInt(), 0xFFD0623F.toInt()),
        Preset("Glacier", 0xFF0A1420.toInt(), 0xFF1D3F5C.toInt(), 0xFF6FC6F0.toInt(), 0xFFA9E3E0.toInt()),
        Preset("Graphite", 0xFF0C0D10.toInt(), 0xFF24272E.toInt(), 0xFF5A6272.toInt(), 0xFF3A4252.toInt()),
    )

    var sharp: Bitmap? = null
        private set
    var blur: Bitmap? = null
        private set
    /** Bumped whenever the bitmaps change, so views know to rebuild shaders. */
    var generation = 0
        private set

    private var screenW = 1920
    private var screenH = 1080
    private var lightShader: BitmapShader? = null
    private var darkShader: BitmapShader? = null
    private val m = Matrix()
    private val rect = RectF()

    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val warmPaint = Paint()
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val edgeShader = LinearGradient(
        0f, 0f, 0f, 1f,
        intArrayOf(0x8CFFFFFF.toInt(), 0x1FFFFFFF, 0x38FFFFFF),
        floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
    )
    private val edgeMatrix = Matrix()

    fun init(w: Int, h: Int) {
        screenW = w; screenH = h
    }

    // ---- wallpaper -------------------------------------------------------------------------

    /** Loads the cached wallpaper for [key], rebuilding it if the cache is missing or stale. */
    fun load(ctx: Context, key: String) {
        val p = prepare(ctx, key)
        apply(p.first, p.second)
    }

    /**
     * Fast path for startup: if the cache matches [key], applies only the small blurred copy
     * (a few ms) and returns true. The background then shows that until [apply] is called.
     */
    fun quick(ctx: Context, key: String): Boolean {
        val keyFile = File(ctx.filesDir, "wall.key")
        if (!keyFile.exists() || keyFile.readText() != key) return false
        val b = BitmapFactory.decodeFile(File(ctx.filesDir, "wall_blur.png").path) ?: return false
        sharp = null
        blur = b
        lightShader = BitmapShader(tinted(b, 0x24FFFFFF), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        darkShader = BitmapShader(tinted(b, 0x66101522), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        return true
    }

    /** Safe off the main thread: decodes or renders the pair (sharp, blurred) and caches it. */
    fun prepare(ctx: Context, key: String): Pair<Bitmap, Bitmap> {
        val dir = ctx.filesDir
        val keyFile = File(dir, "wall.key")
        val isPhoto = key.startsWith("p:")
        val sharpFile = File(dir, if (isPhoto) "wall_sharp.jpg" else "wall_sharp.png")
        val blurFile = File(dir, "wall_blur.png")
        var s: Bitmap? = null
        var b: Bitmap? = null
        if (keyFile.exists() && keyFile.readText() == key) {
            // GPU-only: a 1080p photo would otherwise also sit in RAM as 8 MB of pixels.
            val o = BitmapFactory.Options()
            o.inPreferredConfig = Bitmap.Config.HARDWARE
            s = try { BitmapFactory.decodeFile(sharpFile.path, o) } catch (_: Throwable) { null }
            b = BitmapFactory.decodeFile(blurFile.path)
        }
        if (s == null || b == null) {
            s = render(key)
            b = blurOf(s)
            try {
                FileOutputStream(sharpFile).use {
                    if (isPhoto) s.compress(Bitmap.CompressFormat.JPEG, 94, it) else s.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                FileOutputStream(blurFile).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                keyFile.writeText(key)
            } catch (_: Exception) {
            }
            try {
                s.copy(Bitmap.Config.HARDWARE, false)?.let { hw -> s.recycle(); s = hw }
            } catch (_: Throwable) {
            }
        }
        return Pair(s, b)
    }

    private fun tinted(src: Bitmap, tint: Int): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawColor(tint)
        return out
    }

    /** Main thread only. */
    fun apply(s: Bitmap, b: Bitmap) {
        sharp = s
        blur = b
        lightShader = BitmapShader(tinted(b, 0x24FFFFFF), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        darkShader = BitmapShader(tinted(b, 0x66101522), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        generation++
    }

    private fun render(key: String): Bitmap {
        if (key.startsWith("p:")) {
            photo(key.substring(2))?.let { return it }
        }
        val idx = key.substringAfter("g:", "0").toIntOrNull() ?: 0
        return gradient(presets[idx.coerceIn(0, presets.size - 1)], 480, 270)
    }

    fun gradient(p: Preset, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.DITHER_FLAG)
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), p.a, p.b, Shader.TileMode.CLAMP)
        c.drawPaint(paint)
        paint.shader = RadialGradient(
            w * 0.82f, h * 0.12f, w * 0.55f,
            (p.glow1 and 0xFFFFFF) or 0x99000000.toInt(), p.glow1 and 0xFFFFFF, Shader.TileMode.CLAMP
        )
        c.drawPaint(paint)
        paint.shader = RadialGradient(
            w * 0.12f, h * 0.95f, w * 0.5f,
            (p.glow2 and 0xFFFFFF) or 0x80000000.toInt(), p.glow2 and 0xFFFFFF, Shader.TileMode.CLAMP
        )
        c.drawPaint(paint)
        return bmp
    }

    /** Centre-cropped, downsampled photo, slightly darkened so white text stays readable. */
    private fun photo(path: String): Bitmap? {
        try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, o)
            if (o.outWidth <= 0) return null
            // Photos are kept at full panel resolution; gradients don't need it.
            val tw = screenW
            val th = screenH
            var sample = 1
            while (o.outWidth / (sample * 2) >= tw && o.outHeight / (sample * 2) >= th) sample *= 2
            o.inJustDecodeBounds = false
            o.inSampleSize = sample
            val src = BitmapFactory.decodeFile(path, o) ?: return null
            val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val scale = maxOf(tw / src.width.toFloat(), th / src.height.toFloat())
            val mm = Matrix()
            mm.setScale(scale, scale)
            mm.postTranslate((tw - src.width * scale) / 2f, (th - src.height * scale) / 2f)
            c.drawBitmap(src, mm, Paint(Paint.FILTER_BITMAP_FLAG))
            c.drawColor(0x26000000)
            src.recycle()
            return out
        } catch (_: Throwable) {
            return null
        }
    }

    private fun blurOf(src: Bitmap): Bitmap {
        val w = 240
        val h = 135
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        // Three box passes approximate a gaussian.
        repeat(3) {
            boxH(px, tmp, w, h, 9)
            boxV(tmp, px, w, h, 9)
        }
        val out = if (small.isMutable && small !== src) small else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            var sr = 0; var sg = 0; var sb = 0
            val row = y * w
            for (i in -r..r) {
                val p = src[row + i.coerceIn(0, w - 1)]
                sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255
            }
            for (x in 0 until w) {
                dst[row + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val pOut = src[row + (x - r).coerceIn(0, w - 1)]
                val pIn = src[row + (x + r + 1).coerceIn(0, w - 1)]
                sr += ((pIn shr 16) and 255) - ((pOut shr 16) and 255)
                sg += ((pIn shr 8) and 255) - ((pOut shr 8) and 255)
                sb += (pIn and 255) - (pOut and 255)
            }
        }
    }

    private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = src[i.coerceIn(0, h - 1) * w + x]
                sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255
            }
            for (y in 0 until h) {
                dst[y * w + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val pOut = src[(y - r).coerceIn(0, h - 1) * w + x]
                val pIn = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                sr += ((pIn shr 16) and 255) - ((pOut shr 16) and 255)
                sg += ((pIn shr 8) and 255) - ((pOut shr 8) and 255)
                sb += (pIn and 255) - (pOut and 255)
            }
        }
    }

    // ---- drawing ---------------------------------------------------------------------------

    /**
     * [shade] 0..1 cross-fades the wallpaper to its dark blurred version as the page scrolls
     * into the app rows. At 1 the wallpaper is fully covered, so it is not drawn at all.
     */
    fun drawBackground(c: Canvas, w: Int, h: Int, shade: Float = 0f) {
        val sh = darkShader
        val bl = blur
        if (shade >= 1f && sh != null && bl != null) {
            drawShade(c, w, h, sh, bl, 255)
            return
        }
        val s = sharp
        if (s == null) {
            // Sharp wallpaper still decoding: show the blurred copy rather than a blank screen.
            if (bl != null) {
                rect.set(0f, 0f, w.toFloat(), h.toFloat())
                c.drawBitmap(bl, null, rect, bgPaint)
            } else c.drawColor(0xFF0B0D14.toInt())
            return
        }
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        c.drawBitmap(s, null, rect, bgPaint)
        if (shade > 0f && sh != null && bl != null) drawShade(c, w, h, sh, bl, (shade * 255).toInt())
    }

    /**
     * Draws [bitmaps] and the scroll shade into a 2 px corner at 1/255 opacity. The GPU builds
     * a texture (and compiles the shade's shader) on first use; doing that here, while Home
     * is idle, keeps it out of the first scroll.
     */
    fun warm(c: Canvas, bitmaps: ArrayList<Bitmap>) {
        warmPaint.alpha = 1
        rect.set(0f, 0f, 2f, 2f)
        for (b in bitmaps) if (!b.isRecycled) c.drawBitmap(b, null, rect, warmPaint)
        bitmaps.clear()
        val sh = darkShader
        if (sh != null) {
            shadePaint.shader = sh
            shadePaint.alpha = 1
            c.drawRect(0f, 0f, 2f, 2f, shadePaint)
        }
    }

    private fun drawShade(c: Canvas, w: Int, h: Int, sh: BitmapShader, bl: Bitmap, alpha: Int) {
        m.setScale(screenW / bl.width.toFloat(), screenH / bl.height.toFloat())
        sh.setLocalMatrix(m)
        shadePaint.shader = sh
        shadePaint.alpha = alpha
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shadePaint)
    }

    /**
     * Draws a glass panel of [w] x [h] at the canvas origin. [screenX]/[screenY] is where that
     * origin sits on screen, which selects the crop of the blurred wallpaper.
     *
     * One fill pass: the tint is already baked into the blurred bitmap ([dark] picks the menu
     * variant). Drawing blur, tint and noise as separate passes cost several ms per frame on
     * this TV's GPU. [extra] adds a highlight tint for the one focused element.
     */
    fun drawPanel(
        c: Canvas, w: Float, h: Float, radius: Float,
        screenX: Float, screenY: Float, dark: Boolean = false, extra: Int = 0, edge: Boolean = true,
    ) {
        rect.set(0f, 0f, w, h)
        val sh = if (dark) darkShader else lightShader
        val b = blur
        if (b != null && sh != null) {
            m.setScale(screenW / b.width.toFloat(), screenH / b.height.toFloat())
            m.postTranslate(-screenX, -screenY)
            sh.setLocalMatrix(m)
            blurPaint.shader = sh
            c.drawRoundRect(rect, radius, radius, blurPaint)
        }
        if (extra != 0) {
            tintPaint.color = extra
            c.drawRoundRect(rect, radius, radius, tintPaint)
        }
        if (edge) drawEdge(c, w, h, radius)
    }

    /** 1 px light edge, brightest along the top like a lit glass rim. */
    fun drawEdge(c: Canvas, w: Float, h: Float, radius: Float) {
        edgeMatrix.setScale(1f, h)
        edgeShader.setLocalMatrix(edgeMatrix)
        edgePaint.shader = edgeShader
        edgePaint.strokeWidth = 1f
        rect.set(0.5f, 0.5f, w - 0.5f, h - 0.5f)
        c.drawRoundRect(rect, radius, radius, edgePaint)
    }
}
