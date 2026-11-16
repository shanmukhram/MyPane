package tv.pane.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AppEntry(
    val pkg: String,
    val component: ComponentName,
    val label: String,
    val hasBanner: Boolean,
    val isGame: Boolean,
    val isSystem: Boolean,
    /** Changes when the app updates; part of the icon disk-cache key. */
    val stamp: Long,
)

/** Everything the user can change. Saved as one small JSON file, which is also the backup format. */
object Store {
    var wallpaper = "g:0"
    var reduceMotion = false
    /** The dock is pre-filled once with well-known streaming apps; after that it is the user's. */
    var seeded = false
    val favorites = ArrayList<String>()
    val order = ArrayList<String>()
    val hidden = HashSet<String>()
    private var file: File? = null

    fun init(ctx: Context) {
        val f = File(ctx.filesDir, "config.json")
        file = f
        if (f.exists()) try {
            fromJson(f.readText())
        } catch (_: Exception) {
        }
    }

    fun save() {
        try {
            file?.writeText(toJson())
        } catch (_: Exception) {
        }
    }

    fun toJson(): String {
        val o = JSONObject()
        o.put("version", 1)
        o.put("wallpaper", wallpaper)
        o.put("reduceMotion", reduceMotion)
        o.put("seeded", seeded)
        o.put("favorites", JSONArray(favorites))
        o.put("order", JSONArray(order))
        o.put("hidden", JSONArray(hidden.sorted()))
        return o.toString(2)
    }

    fun fromJson(text: String) {
        val o = JSONObject(text)
        wallpaper = o.optString("wallpaper", "g:0")
        reduceMotion = o.optBoolean("reduceMotion", false)
        seeded = o.optBoolean("seeded", false)
        readList(o.optJSONArray("favorites"), favorites)
        readList(o.optJSONArray("order"), order)
        val h = ArrayList<String>()
        readList(o.optJSONArray("hidden"), h)
        hidden.clear(); hidden.addAll(h)
    }

    private fun readList(a: JSONArray?, into: ArrayList<String>) {
        into.clear()
        if (a == null) return
        for (i in 0 until a.length()) {
            val s = a.optString(i)
            if (s.isNotEmpty() && !into.contains(s)) into.add(s)
        }
    }
}

object Apps {
    /** All launchable apps by package. */
    val all = HashMap<String, AppEntry>()
    const val DOCK_MAX = 6
    /** Test only (`am start ... --ei stress 150`): pad the list with clones to stress memory. */
    var stress = 0

    fun load(ctx: Context) {
        val pm = ctx.packageManager
        all.clear()
        // TV (leanback) entries win over phone-style launcher entries for the same package.
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, ctx.packageName)
        collect(pm, Intent.CATEGORY_LAUNCHER, ctx.packageName)
        if (stress > 0) {
            val real = all.values.toList()
            for (i in 0 until stress) {
                val r = real[i % real.size]
                val pkg = "${r.pkg}#$i"
                all[pkg] = AppEntry(pkg, r.component, "${r.label} $i", r.hasBanner, false, r.isSystem, r.stamp)
            }
        }
        // New installs are appended to the saved order; removed apps drop out of it.
        var changed = Store.order.retainAll { all.containsKey(it) }
        val fresh = all.values.filter { !Store.order.contains(it.pkg) }.sortedBy { it.label.lowercase() }
        for (e in fresh) {
            Store.order.add(e.pkg); changed = true
        }
        if (Store.favorites.retainAll { all.containsKey(it) }) changed = true
        if (!Store.seeded) {
            Store.seeded = true; changed = true
            if (Store.favorites.isEmpty()) {
                val known = arrayOf(
                    "com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom",
                    "in.startv.hotstar", "com.jio.media.stb.ondemand", "com.sonyliv", "com.graymatrix.did",
                    "com.spotify.tv.android",
                )
                for (k in known) if (all.containsKey(k) && Store.favorites.size < DOCK_MAX) Store.favorites.add(k)
            }
        }
        if (changed) Store.save()
    }

    private fun collect(pm: PackageManager, category: String, self: String) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        val list: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)
        for (ri in list) {
            val ai = ri.activityInfo ?: continue
            val pkg = ai.packageName
            if (pkg == self || all.containsKey(pkg)) continue
            val app = ai.applicationInfo
            // System packages that only expose a phone-style entry are internal stubs
            // (WebView, vendor helpers), not apps anyone launches from a TV.
            if (category == Intent.CATEGORY_LAUNCHER && (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            val stamp = try {
                pm.getPackageInfo(pkg, 0).lastUpdateTime
            } catch (_: Exception) {
                0L
            }
            @Suppress("DEPRECATION")
            val game = app.category == ApplicationInfo.CATEGORY_GAME || (app.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            all[pkg] = AppEntry(
                pkg, ComponentName(pkg, ai.name),
                ri.loadLabel(pm).toString().trim(),
                ai.banner != 0 || app.banner != 0,
                game,
                (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                stamp,
            )
        }
    }

    /** Apps shown in the home dock. */
    fun favorites(): List<AppEntry> = Store.favorites.mapNotNull { all[it] }.filter { !Store.hidden.contains(it.pkg) }.take(DOCK_MAX)

    /** Everything shown in the apps panel (dock apps included). */
    fun visible(): List<AppEntry> = Store.order.mapNotNull { all[it] }.filter { !Store.hidden.contains(it.pkg) }

    fun hidden(): List<AppEntry> = Store.hidden.mapNotNull { all[it] }.sortedBy { it.label.lowercase() }
}
