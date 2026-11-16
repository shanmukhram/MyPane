package tv.pane.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import java.util.Date

class MainActivity : Activity() {

    /** Root draws the wallpaper itself, so no system wallpaper window is composited behind us. */
    inner class Root(ctx: Context) : FrameLayout(ctx) {
        init { setWillNotDraw(false); clipChildren = false }
        override fun onDraw(c: Canvas) {
            Glass.drawBackground(c, width, height, shade)
            if (Icons.fresh.isNotEmpty() || !warmed) { warmed = true; Glass.warm(c, Icons.fresh) }
        }
    }

    /** 0 on Home, 1 once the page has scrolled into the app rows. */
    private var shade = 0f
    private var warmed = false
    /** Dark gradient that the peeking app row sinks into until the page scrolls to it. */
    private lateinit var fade: View

    lateinit var root: Root
    lateinit var grid: Grid
    lateinit var bar: LinearLayout
    private lateinit var dock: Dock
    private lateinit var appsButton: CircleView
    private lateinit var inputsButton: CircleView
    private lateinit var clock: PillView
    private lateinit var net: PillView
    private lateinit var searchPill: PillView
    private val pool = HashMap<String, TileView>()
    private val dockPool = HashMap<String, TileView>()
    private val menus = Menus(this)
    private var modal: Sheet? = null
    private var lastDock: View? = null
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        Store.init(this)
        Motion.reduce = Store.reduceMotion
        val dm = resources.displayMetrics
        Glass.init(dm.widthPixels, dm.heightPixels)
        // A 1080p photo takes ~0.5 s to decode on this TV. Start from the tiny pre-blurred copy
        // so Home appears at once, and swap the sharp wallpaper in from a background thread.
        if (Glass.quick(this, Store.wallpaper)) {
            val key = Store.wallpaper
            Thread {
                val pair = Glass.prepare(this, key)
                runOnUiThread { if (key == Store.wallpaper) { Glass.apply(pair.first, pair.second); refreshAll() } }
            }.start()
        } else Glass.load(this, Store.wallpaper)
        TileView.metrics()
        TileView.tileW = Ui.dp(160f); TileView.tileH = Ui.dp(90f)
        Icons.bannerW = TileView.tileW; Icons.bannerH = TileView.tileH; Icons.iconPx = Ui.dp(56f)
        Icons.init(this)
        Apps.stress = intent?.getIntExtra("stress", 0) ?: 0
        Apps.load(this)

        root = Root(this)
        bar = LinearLayout(this).apply { clipChildren = false; gravity = Gravity.CENTER_VERTICAL }
        root.addView(bar, FrameLayout.LayoutParams(-1, -2).apply {
            topMargin = Ui.dp(20f); leftMargin = Ui.dp(43f); rightMargin = Ui.dp(43f)
        })
        buildBar()

        // One continuous page, the way a TV home screen scrolls: the dock sits over the
        // wallpaper, the first row of all apps peeks in below it, and DOWN glides the whole
        // page up. Nothing opens or fades in, so there is no full-screen transition to pay for.
        val screenH = dm.heightPixels
        grid = Grid(this)
        grid.screenH = screenH
        grid.layoutTop = screenH - Ui.dp(64f)
        root.addView(grid, FrameLayout.LayoutParams(-1, -2).apply { topMargin = grid.layoutTop })

        fade = View(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, 0xB3000000.toInt(), 0xF2000000.toInt())
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(fade, FrameLayout.LayoutParams(-1, Ui.dp(84f), Gravity.BOTTOM))

        dock = Dock(this)
        dock.above = { searchPill }
        dock.below = { from -> grid.nearestInFirstRow(from) }
        dock.onFocus = { v -> lastDock = v; grid.scrollToTop() }
        appsButton = CircleView(this, Glyph.GRID, "All apps")
        inputsButton = CircleView(this, Glyph.INPUT, "TV inputs")
        appsButton.setOnClickListener { grid.firstTile()?.requestFocus() }
        inputsButton.setOnClickListener { menus.inputs() }
        root.addView(dock, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = Ui.dp(72f)
        })
        grid.above = { lastDock?.takeIf { it.isAttachedToWindow } ?: appsButton }
        grid.onScroll = { y ->
            // Everything above the grid rides up with the page; the top bar fades out early.
            dock.translationY = -y
            val f = (1f - y / Ui.dpf(90f)).coerceIn(0f, 1f)
            bar.alpha = f
            bar.translationY = -y * 0.5f
            bar.visibility = if (f <= 0f) View.INVISIBLE else View.VISIBLE
            val fa = (1f - y / Ui.dpf(120f)).coerceIn(0f, 1f)
            fade.alpha = fa
            fade.visibility = if (fa <= 0f) View.INVISIBLE else View.VISIBLE
            val sh = (y / (screenH * 0.45f)).coerceIn(0f, 1f)
            if (sh != shade) { shade = sh; root.invalidate() }
        }

        Icons.onFresh = { root.invalidate() }
        setContentView(root)
        window.setBackgroundDrawable(null)
        rebuild()
        homeFocus()

        registerReceiver(packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
    }

    /** Focuses [v] if it is still on screen, otherwise the sensible default for the open layer. */
    fun refocus(v: View?) {
        if (v != null && v.isAttachedToWindow && v.isFocusable && v.requestFocus()) return
        homeFocus()
    }

    /** Puts focus on the first dock app (or the apps button if the dock is empty). */
    fun homeFocus() {
        val first = (0 until dock.childCount).map { dock.getChildAt(it) }.firstOrNull { it is TileView }
        (first ?: appsButton).requestFocus()
    }

    // ---- top bar -------------------------------------------------------------------------------

    private fun buildBar() {
        clock = PillView(this)
        net = PillView(this, Glyph.OFFLINE, "Offline")
        searchPill = PillView(this, Glyph.SEARCH, "Search")
        val pane = PillView(this, Glyph.SLIDERS, "Pane")
        val system = PillView(this, Glyph.GEAR, "Settings")
        clock.setOnClickListener { open(Intent(Settings.ACTION_DATE_SETTINGS)) }
        net.setOnClickListener { open(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        searchPill.setOnClickListener { onSearch("") }
        searchPill.contentDescription = "Search apps"
        pane.setOnClickListener { onPaneSettings() }
        pane.contentDescription = "Pane launcher settings"
        system.setOnClickListener { open(Intent(Settings.ACTION_SETTINGS)) }
        system.contentDescription = "System settings"
        bar.addView(clock, LinearLayout.LayoutParams(-2, -2))
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        for (p in arrayOf(net, searchPill, pane, system)) {
            bar.addView(p, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.dp(2f) })
        }
        for (i in 0 until bar.childCount) {
            val v = bar.getChildAt(i)
            // DOWN from anywhere in the bar lands in the dock.
            if (v is PillView) v.setOnKeyListener { _, code, ev ->
                if (code == KeyEvent.KEYCODE_DPAD_DOWN && ev.action == KeyEvent.ACTION_DOWN) {
                    homeFocus(); true
                } else false
            }
        }
        tick()
    }

    private fun tick() {
        val now = Date()
        val time = DateFormat.getTimeFormat(this).format(now)
        val date = DateFormat.format("EEE d MMM", now).toString()
        clock.set(time, date)
        clock.contentDescription = "$time, ${DateFormat.format("EEEE d MMMM", now)}"
    }

    private val ticker = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = tick()
    }

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
            val wired = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val label = if (wired) "Ethernet" else "Wi-Fi"
            runOnUiThread {
                net.set(if (online) label else "$label, no internet", "", if (wired) Glyph.WIRED else Glyph.WIFI)
                net.contentDescription = "Network: ${net.text}"
            }
        }
        override fun onLost(n: Network) {
            runOnUiThread { net.set("Offline", "", Glyph.OFFLINE); net.contentDescription = "Network: offline" }
        }
    }

    // ---- content -------------------------------------------------------------------------------

    private val packages = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            i.data?.schemeSpecificPart?.let { Icons.forget(it) }
            Apps.load(this@MainActivity)
            rebuild()
        }
    }

    private fun tileFor(e: AppEntry, dockTile: Boolean, width: Int): TileView {
        val t = (if (dockTile) dockPool else pool).getOrPut(e.pkg) {
            TileView(this).also { v ->
                v.setOnClickListener { launch(v.entry) }
                v.setOnLongClickListener { v.entry?.let { onAppMenu(v, it) }; true }
            }
        }
        t.bind(e, dockTile, width)
        return t
    }

    fun rebuild() {
        // Dock: round buttons, then up to six chosen apps, sized to fit.
        val favs = Apps.favorites()
        val w = Ui.dp(when {
            favs.size <= 4 -> 136f
            favs.size == 5 -> 124f
            else -> 110f
        })
        val items = ArrayList<View>()
        items.add(appsButton); items.add(inputsButton)
        for (e in favs) items.add(tileFor(e, true, w).also { it.setActive(started) })
        dock.setItems(items)
        dockPool.entries.removeAll { it.value.parent == null }

        // Apps panel: everything, games in their own section.
        val all = Apps.visible()
        val list = ArrayList<Section>()
        val apps = all.filter { !it.isGame }
        val games = all.filter { it.isGame }
        if (apps.isNotEmpty()) list.add(Section("Apps", apps.map { tileFor(it, false, TileView.tileW) }))
        if (games.isNotEmpty()) list.add(Section("Games", games.map { tileFor(it, false, TileView.tileW) }))
        grid.setSections(list)
        pool.entries.removeAll { it.value.parent == null }
        if (currentFocus == null && modal == null) homeFocus()
    }

    fun launch(e: AppEntry?) {
        e ?: return
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN).setComponent(e.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            )
        } catch (_: Exception) {
            toast("Can't open ${e.label}")
        }
    }

    fun open(i: Intent) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            toast("Not available on this TV")
        }
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun onAppMenu(tile: TileView, e: AppEntry) {
        // A held key can report the long press twice; one menu is enough.
        if (modal == null) menus.app(tile, e)
    }
    private fun onSearch(initial: String) = menus.search(initial)
    private fun onPaneSettings() = menus.settings()

    // ---- modal sheets --------------------------------------------------------------------------

    /** While a sheet is open the home content is unreachable by focus and hidden from TalkBack. */
    fun setModal(s: Sheet?) {
        modal = s
        blockFocus()
    }

    /** While a sheet is open, nothing behind it takes focus or is read by TalkBack. */
    private fun blockFocus() {
        fun set(v: ViewGroup, blocked: Boolean) {
            v.descendantFocusability = if (blocked) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
            v.importantForAccessibility =
                if (blocked) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
        val sheet = modal != null
        set(grid, sheet)
        set(bar, sheet)
        set(dock, sheet)
    }

    fun refreshAll() {
        fun walk(v: View) {
            v.invalidate()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
    }

    fun setWallpaper(key: String) {
        Store.wallpaper = key
        Store.save()
        // Photo decode and the one-off blur run off the main thread; only the swap is on it.
        Thread {
            val pair = Glass.prepare(this, key)
            runOnUiThread { Glass.apply(pair.first, pair.second); refreshAll() }
        }.start()
    }

    /** Re-applies everything after a restore. */
    fun applyStore() {
        Motion.reduce = Store.reduceMotion
        Apps.load(this)
        rebuild()
        setWallpaper(Store.wallpaper)
        homeFocus()
    }

    private var pendingStorage: (() -> Unit)? = null

    fun withStorage(then: () -> Unit) {
        val perm = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) then()
        else {
            pendingStorage = then
            requestPermissions(arrayOf(perm, android.Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        val p = pendingStorage
        pendingStorage = null
        if (results.isNotEmpty() && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) p?.invoke()
        else toast("Storage permission is needed for this")
    }

    // ---- move mode -----------------------------------------------------------------------------

    private var moving: TileView? = null

    fun startMove(tile: TileView) {
        moving = tile
        tile.moving = true
        tile.requestFocus()
        tile.announceForAccessibility("Moving ${tile.title}. Use the arrows, then press OK.")
    }

    private fun finishMove() {
        val t = moving ?: return
        t.moving = false
        moving = null
        Store.save()
        t.announceForAccessibility("${t.title} placed")
    }

    private fun moveBy(delta: Int) {
        val t = moving ?: return
        val e = t.entry ?: return
        val fav = t.inDock
        // The dock is a single row: only left and right mean anything there.
        if (fav && (delta > 1 || delta < -1)) return
        val shown = (if (fav) Apps.favorites() else Apps.visible().filter { it.isGame == e.isGame }).map { it.pkg }
        val from = shown.indexOf(e.pkg)
        val to = (from + delta).coerceIn(0, shown.size - 1)
        if (from < 0 || to == from) return
        val moved = ArrayList(shown)
        moved.add(to, moved.removeAt(from))
        // Write the new order back into the slots these apps already occupy in the full list.
        val backing = if (fav) Store.favorites else Store.order
        val slots = backing.indices.filter { shown.contains(backing[it]) }
        for ((k, slot) in slots.withIndex()) backing[slot] = moved[k]
        rebuild()
        if (!fav) grid.post { grid.reveal(t) }
        t.announceForAccessibility("Position ${to + 1} of ${shown.size}")
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (moving != null) {
            if (e.action == KeyEvent.ACTION_DOWN) when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> moveBy(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveBy(1)
                KeyEvent.KEYCODE_DPAD_UP -> moveBy(-5)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveBy(5)
            } else when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> finishMove()
            }
            return true
        }
        // Typing on a keyboard (or a phone remote app) starts a search straight away.
        if (modal == null && e.action == KeyEvent.ACTION_DOWN && !e.isCtrlPressed && !e.isAltPressed) {
            val ch = e.unicodeChar
            if (ch != 0 && Character.isLetterOrDigit(ch)) {
                menus.search(ch.toChar().toString())
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    // ---- lifecycle -----------------------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        started = true
        setArtShown(true)
        tick()
        registerReceiver(ticker, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).registerDefaultNetworkCallback(netCallback)
    }

    override fun onStop() {
        super.onStop()
        started = false
        unregisterReceiver(ticker)
        (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(netCallback)
    }

    override fun onDestroy() {
        unregisterReceiver(packages)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already home: back to the top.
        modal?.dismiss(false)
        finishMove()
        if (intent.hasExtra("stress")) {
            // Test hook: `am start -n tv.pane.launcher/.MainActivity --ei stress 150`
            Apps.stress = intent.getIntExtra("stress", 0)
            Apps.load(this)
            rebuild()
        }
        homeFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Home has nowhere to go back to: Back closes a menu, or returns from the app rows
        // to the dock.
        val m = modal
        if (m != null) m.dismiss() else homeFocus()
    }

    private fun setArtShown(on: Boolean) {
        for (t in dockPool.values) t.setActive(on)
        grid.setShown(on)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            // We're in the background: hold no artwork at all. It reloads from disk in ms.
            setArtShown(false)
            Icons.trim()
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            Icons.trim()
        }
    }
}
