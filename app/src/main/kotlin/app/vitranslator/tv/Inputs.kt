package app.vitranslator.tv

import android.content.ContentResolver
import android.content.Context
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.provider.Settings

/**
 * The TV's HDMI inputs, and which one is on screen now, for "Show on": the overlay only over one
 * input (the console), not over the TV's home screen, apps or other inputs.
 *
 * Android has no public way for an app to know the input on screen. TCL TVs keep it in two system
 * settings an app can read: the input's device id ("tcl_sys_tv_input_current_device_id") and
 * whether an input is on screen at all ("sys.livetv.act_state", 0 on the home screen or in an
 * app). On a TV without them, "Show on" isn't offered and the overlay shows everywhere.
 */
object Inputs {
    /** An HDMI input: its device id (as in the system setting) and its name on this TV ("HDMI 3", "PS5"). */
    data class Input(val deviceId: String, val label: String)

    private val HW = Regex("/HW(\\d+)$")

    /** The HDMI inputs, in the TV's order; empty when this TV can't say which one is on screen. */
    fun hdmi(ctx: Context): List<Input> {
        if (current(ctx.contentResolver) == null) return emptyList()
        val tim = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return emptyList()
        return tim.tvInputList
            // (the HDMI ports themselves, not the devices behind them that HDMI-CEC lists too)
            .filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null }
            .mapNotNull { info ->
                HW.find(info.id)?.let { m -> Input(m.groupValues[1], label(ctx, info)) }
            }
    }

    /** An HDMI input that can be played inside the app: its TV input id and its name on this TV. */
    data class Hdmi(val id: String, val label: String)

    /**
     * The HDMI inputs this app can play itself (a TvView tuned to the input's passthrough channel,
     * "Play inside the app"), in the TV's order. Empty on a device with no TV inputs: a stick or a
     * box plugged into a TV (Chromecast, Mi Stick, Fire TV) has no HDMI input of its own.
     */
    fun passthrough(ctx: Context): List<Hdmi> {
        val all = try {
            (ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager)?.tvInputList
        } catch (e: RuntimeException) {
            null // (no TV input service on this device, or it failed)
        } ?: return emptyList()
        val hdmi = all.filter { it.isPassthroughInput && it.type == TvInputInfo.TYPE_HDMI }
        // the HDMI ports themselves, not the devices behind them that HDMI-CEC lists too (with
        // the port as their parent); those alone when the TV lists no port
        val ports = hdmi.filter { it.parentId == null }.ifEmpty { hdmi }
        return ports.map { Hdmi(it.id, label(ctx, it)) }
    }

    /** The input's name: the one the user gave it on the TV ("PS5"), else the TV's ("HDMI 3"). */
    private fun label(ctx: Context, info: TvInputInfo): String = try {
        info.loadCustomLabel(ctx)?.toString()?.takeIf { it.isNotBlank() } ?: info.loadLabel(ctx)?.toString()?.takeIf { it.isNotBlank() } ?: "HDMI"
    } catch (e: RuntimeException) {
        "HDMI"
    }

    /** The input on screen now: its device id, "" when none (home screen, an app), null when unknown. */
    fun current(cr: ContentResolver): String? = try {
        val act = Settings.Global.getString(cr, "sys.livetv.act_state")
        val device = Settings.Secure.getString(cr, "tcl_sys_tv_input_current_device_id")
        when {
            act == null || device == null -> null
            act.trim() == "1" -> device.trim()
            else -> ""
        }
    } catch (e: RuntimeException) {
        null // (not readable on this TV)
    }

    /** Should the overlay show now, for "Show on" = [showOn] ("" = everywhere)? */
    fun showing(cr: ContentResolver, showOn: String): Boolean {
        if (showOn.isEmpty()) return true
        val now = current(cr)
        return now == null || now == showOn
    }
}
