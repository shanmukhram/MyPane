package tv.pane.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager

/** Physical TV inputs (HDMI, AV, antenna). Switching hands off to the TV's own live-TV app. */
object Inputs {
    class Item(val id: String, val label: String, val glyph: Int, val state: String, val passthrough: Boolean, val sort: Int)

    fun list(ctx: Context): List<Item> {
        val out = ArrayList<Item>()
        try {
            val tim = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return out
            for (info in tim.tvInputList) {
                val type = info.type
                // Skip "virtual" tuner inputs that streaming apps register; keep real hardware.
                val hardware = info.isPassthroughInput || info.serviceInfo.packageName.startsWith("com.mediatek")
                if (!hardware || info.isHidden(ctx)) continue
                val label = (info.loadCustomLabel(ctx) ?: info.loadLabel(ctx) ?: "Input").toString()
                val state = when (tim.getInputState(info.id)) {
                    TvInputManager.INPUT_STATE_CONNECTED -> "connected"
                    TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> "standby"
                    else -> "not connected"
                }
                val glyph = if (type == TvInputInfo.TYPE_TUNER) Glyph.ANTENNA else Glyph.INPUT
                val sort = when (type) { TvInputInfo.TYPE_HDMI -> 0; TvInputInfo.TYPE_TUNER -> 2; else -> 1 }
                out.add(Item(info.id, label, glyph, state, info.isPassthroughInput, sort))
            }
        } catch (_: Throwable) {
        }
        out.sortWith(compareBy({ it.sort }, { it.label }))
        // Inputs that share a name (two tuners) get numbered so they can be told apart by ear.
        val counts = out.groupingBy { it.label }.eachCount()
        val seen = HashMap<String, Int>()
        return out.map {
            if ((counts[it.label] ?: 0) > 1) {
                val n = (seen[it.label] ?: 0) + 1
                seen[it.label] = n
                Item(it.id, "${it.label} $n", it.glyph, it.state, it.passthrough, it.sort)
            } else it
        }
    }

    fun open(ctx: Context, id: String, passthrough: Boolean): Boolean {
        val uri = if (passthrough) TvContract.buildChannelUriForPassthroughInput(id) else TvContract.buildChannelsUriForInput(id)
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: Throwable) {
        }
        // Fallback: the stock MediaTek live-TV app, which shows its own source list.
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .setComponent(ComponentName("com.mediatek.wwtv.tvcenter", "com.mitv.livetv.nav.TurnkeyUiMainActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return true
        } catch (_: Throwable) {
        }
        return false
    }
}
