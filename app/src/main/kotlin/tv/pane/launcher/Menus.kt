package tv.pane.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import java.io.File

/** Every menu in the launcher. All of them are [Sheet]s, so they share focus and TalkBack behaviour. */
class Menus(private val a: MainActivity) {

    private fun onOff(b: Boolean) = if (b) "On" else "Off"

    fun app(tile: TileView, e: AppEntry) {
        val s = Sheet(a, e.label)
        val fav = Store.favorites.contains(e.pkg)
        s.row(0, "Open") { s.dismiss(); a.launch(e) }
        s.row(Glyph.STAR, if (fav) "Remove from dock" else "Add to dock") {
            if (!fav && Apps.favorites().size >= Apps.DOCK_MAX) {
                a.toast("The dock holds ${Apps.DOCK_MAX} apps. Remove one first.")
                return@row
            }
            if (fav) Store.favorites.remove(e.pkg) else Store.favorites.add(e.pkg)
            Store.save()
            s.dismiss(false)
            a.rebuild()
            a.refocus(tile)
        }
        s.row(Glyph.MOVE, "Move") { s.dismiss(); a.startMove(tile) }
        s.row(Glyph.EYE_OFF, "Hide") {
            val group = tile.parent as? android.view.ViewGroup
            var next = group?.focusSearch(tile, View.FOCUS_RIGHT)
            if (next === tile) next = group?.focusSearch(tile, View.FOCUS_LEFT)
            Store.hidden.add(e.pkg)
            Store.save()
            s.dismiss(false)
            a.rebuild()
            a.refocus(next)
            a.toast("${e.label} hidden. Unhide it in Pane settings.")
        }
        s.row(Glyph.INFO, "App info") {
            s.dismiss()
            a.open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${e.pkg}")))
        }
        if (!e.isSystem) s.row(Glyph.TRASH, "Uninstall") {
            s.dismiss()
            a.open(Intent(Intent.ACTION_DELETE, Uri.parse("package:${e.pkg}")))
        }
        s.show()
    }

    fun inputs() {
        val s = Sheet(a, "TV inputs")
        val list = Inputs.list(a)
        if (list.isEmpty()) s.row(0, "No inputs found") { s.dismiss() }
        for (item in list) s.row(item.glyph, item.label, item.state) {
            s.dismiss()
            if (!Inputs.open(a, item.id, item.passthrough)) a.toast("No TV app can open this input")
        }
        s.show()
    }

    fun settings(focus: Int = 0) {
        val s = Sheet(a, "Pane settings")
        s.row(Glyph.IMAGE, "Wallpaper") { s.dismiss(); wallpaper() }
        s.row(Glyph.MOVE, "Reduce motion", onOff(Store.reduceMotion)) { r ->
            Store.reduceMotion = !Store.reduceMotion
            Motion.reduce = Store.reduceMotion
            Store.save(); r.value = onOff(Store.reduceMotion)
        }
        s.row(Glyph.EYE_OFF, "Hidden apps", Store.hidden.size.toString()) { s.dismiss(); hidden() }
        s.row(Glyph.BACKUP, "Back up settings") { backup() }
        s.row(Glyph.BACKUP, "Restore settings") { s.dismiss(); restore() }
        s.row(Glyph.GEAR, "Accessibility settings") { s.dismiss(); a.open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        s.show(focus)
    }

    fun wallpaper() {
        val s = Sheet(a, "Wallpaper")
        val rows = ArrayList<RowView>()
        Glass.presets.forEachIndexed { i, p ->
            val key = "g:$i"
            val r = s.row(0, p.name) {
                a.setWallpaper(key)
                rows.forEachIndexed { k, row -> row.checked = k == i }
            }.swatch(p)
            r.checked = Store.wallpaper == key
            rows.add(r)
        }
        val photo = s.row(Glyph.IMAGE, "Photo from storage") { s.dismiss(); photos() }
        photo.checked = Store.wallpaper.startsWith("p:")
        s.show(maxOf(0, rows.indexOfFirst { it.checked }))
    }

    private fun photos() = a.withStorage {
        val found = ArrayList<File>()
        val roots = ArrayList<File>()
        val sd = Environment.getExternalStorageDirectory()
        for (n in arrayOf("Pictures", "Download", "DCIM")) roots.add(File(sd, n))
        // USB sticks and SD cards show up as extra volumes under /storage.
        File("/storage").listFiles()?.forEach { if (it.name != "emulated" && it.name != "self") roots.add(it) }
        for (r in roots) scan(r, 0, found)
        found.sortByDescending { it.lastModified() }
        val s = Sheet(a, "Choose a photo", 380f)
        if (found.isEmpty()) {
            s.note("No photos found")
            s.row(0, "Copy an image to the TV's Pictures or Download folder, or plug in a USB drive.") { s.dismiss() }
        }
        for (f in found) s.row(Glyph.IMAGE, f.name, f.parentFile?.name ?: "") {
            a.setWallpaper("p:" + f.path)
            s.dismiss()
        }
        s.show()
    }

    private fun scan(dir: File, depth: Int, out: ArrayList<File>) {
        if (out.size >= 80 || depth > 3) return
        val list = dir.listFiles() ?: return
        for (f in list) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) scan(f, depth + 1, out)
            else {
                val n = f.name.lowercase()
                if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")) out.add(f)
            }
            if (out.size >= 80) return
        }
    }

    private fun hidden() {
        val s = Sheet(a, "Hidden apps")
        val list = Apps.hidden()
        if (list.isEmpty()) s.row(0, "Nothing is hidden") { s.dismiss() }
        else s.note("Select an app to show it again")
        for (e in list) s.row(Glyph.EYE_OFF, e.label) { r ->
            Store.hidden.remove(e.pkg)
            Store.save()
            a.rebuild()
            r.value = "Shown"
            r.isEnabled = false
        }
        s.show()
    }

    private fun backupFile() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "pane-backup.json")

    private fun backup() = a.withStorage {
        try {
            val f = backupFile()
            f.parentFile?.mkdirs()
            f.writeText(Store.toJson())
            a.toast("Saved to Download/pane-backup.json")
        } catch (e: Exception) {
            a.toast("Backup failed: ${e.message}")
        }
    }

    private fun restore() = a.withStorage {
        try {
            Store.fromJson(backupFile().readText())
            Store.save()
            a.applyStore()
            a.toast("Settings restored")
        } catch (e: Exception) {
            a.toast("No backup found in Download/pane-backup.json")
        }
    }

    fun search(initial: String) {
        // Anchored to the top so results stay visible above the on-screen keyboard.
        val s = Sheet(a, "Search apps", 380f, true)
        val edit = EditText(a).apply {
            background = null
            setTextColor(-0x1)
            setHintTextColor(0x99FFFFFF.toInt())
            hint = "Type an app name"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(Ui.dp(14f), Ui.dp(8f), Ui.dp(14f), Ui.dp(8f))
        }
        s.rows.addView(edit, LinearLayout.LayoutParams(-1, -2))
        val imm = a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        fun results(q: String) {
            while (s.rows.childCount > 1) s.rows.removeViewAt(1)
            val query = q.trim().lowercase()
            if (query.isEmpty()) return
            val all = Apps.all.values.filter { !Store.hidden.contains(it.pkg) && it.label.lowercase().contains(query) }
            val hits = all.sortedWith(compareBy({ !it.label.lowercase().startsWith(query) }, { it.label.lowercase() })).take(5)
            if (hits.isEmpty()) s.note("No apps match")
            for (e in hits) s.row(0, e.label) {
                imm.hideSoftInputFromWindow(edit.windowToken, 0)
                s.dismiss(); a.launch(e)
            }
            edit.announceForAccessibility("${hits.size} results")
        }
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a1: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a1: Int, b: Int, c: Int) {}
            override fun afterTextChanged(t: Editable?) = results(t?.toString() ?: "")
        })
        edit.setOnEditorActionListener { _, _, _ ->
            imm.hideSoftInputFromWindow(edit.windowToken, 0)
            if (s.rows.childCount > 1) (s.rows.getChildAt(1) as? RowView)?.requestFocus()
            true
        }
        s.onClosed = { imm.hideSoftInputFromWindow(a.root.windowToken, 0) }
        s.show(-1)
        edit.requestFocus()
        if (initial.isNotEmpty()) {
            edit.setText(initial); edit.setSelection(initial.length)
        } else edit.post { imm.showSoftInput(edit, 0) }
    }
}
