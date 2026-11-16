package tv.pane.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import java.io.File
import java.io.FileOutputStream

/**
 * Tile artwork. Every bitmap is rendered at exactly the size it is drawn at, kept in a
 * byte-capped LRU, and written to a disk cache so later starts never have to open other
 * apps' resources. Decoding happens on one low-priority thread.
 */
object Icons {
    private const val MAX_BYTES = 6 * 1024 * 1024

    interface Target {
        fun onArt(pkg: String, bmp: Bitmap)
    }

    var bannerW = 304; var bannerH = 171; var iconPx = 112
    private val lru = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {}
    private var bytes = 0
    private val waiting = HashMap<String, ArrayList<Target>>()
    /** Bitmaps loaded since the last frame; the root view draws them once to warm the GPU. */
    val fresh = ArrayList<Bitmap>()
    var onFresh: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private var worker: Handler? = null
    private var dir: File? = null
    private var pm: PackageManager? = null

    fun init(ctx: Context) {
        if (worker != null) return
        pm = ctx.packageManager
        dir = File(ctx.cacheDir, "art").apply { mkdirs() }
        // Leftovers from a stress-test run.
        if (Apps.stress == 0) dir?.listFiles()?.forEach { if (it.name.contains('#')) it.delete() }
        val t = HandlerThread("art", Process.THREAD_PRIORITY_BACKGROUND)
        t.start()
        worker = Handler(t.looper)
    }

    /** Returns the bitmap if cached, otherwise loads it and calls [target] on the main thread. */
    fun get(e: AppEntry, target: Target): Bitmap? {
        lru[e.pkg]?.let { return it }
        val list = waiting[e.pkg]
        if (list != null) {
            if (!list.contains(target)) list.add(target)
            return null
        }
        waiting[e.pkg] = arrayListOf(target)
        worker?.post {
            val bmp = load(e)
            main.post {
                val targets = waiting.remove(e.pkg)
                if (bmp != null) {
                    put(e.pkg, bmp)
                    fresh.add(bmp); onFresh?.invoke()
                    targets?.forEach { it.onArt(e.pkg, bmp) }
                }
            }
        }
        return null
    }

    fun cancel(pkg: String, target: Target) {
        waiting[pkg]?.remove(target)
    }

    private fun put(pkg: String, bmp: Bitmap) {
        lru.put(pkg, bmp)?.let { bytes -= it.allocationByteCount }
        bytes += bmp.allocationByteCount
        val it = lru.entries.iterator()
        while (bytes > MAX_BYTES && it.hasNext()) {
            val old = it.next()
            if (old.key == pkg) continue
            bytes -= old.value.allocationByteCount
            it.remove()
        }
    }

    /** Drops every cached bitmap (memory pressure, or the home screen went to the background). */
    fun trim() {
        lru.clear()
        bytes = 0
    }

    fun cachedBytes() = bytes

    fun forget(pkg: String) {
        lru.remove(pkg)?.let { bytes -= it.allocationByteCount }
        dir?.listFiles()?.forEach { if (it.name.startsWith("$pkg@")) it.delete() }
    }

    private fun load(e: AppEntry): Bitmap? {
        val f = File(dir, "${e.pkg}@${e.stamp}.png")
        if (f.exists()) {
            // GPU-only bitmap: no second copy on the native heap once it is on screen.
            val o = BitmapFactory.Options()
            o.inPreferredConfig = Bitmap.Config.HARDWARE
            try {
                BitmapFactory.decodeFile(f.path, o)?.let { return it }
            } catch (_: Throwable) {
            }
        }
        val p = pm ?: return null
        try {
            val d: Drawable
            val w: Int
            val h: Int
            if (e.hasBanner) {
                val info = p.getActivityInfo(e.component, 0)
                d = info.loadBanner(p) ?: info.applicationInfo.loadBanner(p) ?: info.loadIcon(p)
                w = bannerW; h = bannerH
            } else {
                d = p.getActivityIcon(e.component)
                w = iconPx; h = iconPx
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            // Centre-crop so odd-sized banners still fill the tile.
            val iw = d.intrinsicWidth
            val ih = d.intrinsicHeight
            if (iw > 0 && ih > 0) {
                val s = maxOf(w / iw.toFloat(), h / ih.toFloat())
                val dw = (iw * s).toInt()
                val dh = (ih * s).toInt()
                d.setBounds((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh)
            } else d.setBounds(0, 0, w, h)
            d.draw(c)
            try {
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } catch (_: Exception) {
            }
            val hw = try { bmp.copy(Bitmap.Config.HARDWARE, false) } catch (_: Throwable) { null }
            if (hw != null) { bmp.recycle(); return hw }
            return bmp
        } catch (_: Throwable) {
            return null
        }
    }
}
