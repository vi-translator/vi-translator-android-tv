package app.vitranslator.tv

import android.content.Context
import java.util.UUID

/**
 * This TV's settings: the PC it's connected to (its address and the key it gave this TV), and which
 * input the overlay shows on.
 */
class Prefs(c: Context) {
    private val p = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** This app's own id: the PC knows this TV by it (connected again, it replaces the old key). */
    val id: String
        get() = p.getString("id", null)?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString().also { p.edit().putString("id", it).apply() }

    val connected: Boolean get() = pc.isNotEmpty() && token.isNotEmpty()

    /** The PC app's home-network address, "192.168.1.20:8791" ("" when not connected). */
    val pc: String get() = p.getString("pc", "") ?: ""

    val token: String get() = p.getString("token", "") ?: ""

    val pcName: String get() = p.getString("pc_name", "") ?: ""

    fun connect(pc: String, token: String, pcName: String) {
        p.edit().putString("pc", pc).putString("token", token).putString("pc_name", pcName).apply()
    }

    /** The PC moved to another address (the router gave it a new one). */
    fun setPc(pc: String) {
        p.edit().putString("pc", pc).apply()
    }

    fun disconnect() {
        p.edit().remove("pc").remove("token").remove("pc_name").apply()
    }

    /** The input the overlay shows on (its TV input device id), or "" for every input and app. */
    var showOn: String
        get() = p.getString("show_on", "") ?: ""
        set(deviceId) = p.edit().putString("show_on", deviceId).apply()

    /** The HDMI input last played inside the app (its TV input id), or "" for none yet. */
    var playInput: String
        get() = p.getString("play_input", "") ?: ""
        set(inputId) = p.edit().putString("play_input", inputId).apply()

    /** The app's language (a tag from Lang.ALL), or "" for the TV's own. */
    var language: String
        get() = p.getString("language", "") ?: ""
        set(tag) = p.edit().putString("language", tag).apply()

    companion object {
        private val IPV4 = Regex("(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}")

        /** "ip:port", an IPv4 address on a home network and a port; null otherwise. */
        fun address(s: String?): String? {
            if (s == null) return null
            val colon = s.lastIndexOf(':')
            if (colon < 0) return null
            val host = s.substring(0, colon)
            val port = s.substring(colon + 1).toIntOrNull() ?: return null
            if (port !in 1..65535 || !IPV4.matches(host)) return null
            val (a, b) = host.split('.').map { it.toInt() }
            val home = a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
            return if (home) "$host:$port" else null
        }
    }
}
