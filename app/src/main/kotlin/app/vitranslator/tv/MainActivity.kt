package app.vitranslator.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The only screen, minimal: not connected, it shows the code to type in VI Translator on the PC
 * (which connects this TV); connected, it shows the connection, "Show on" and Disconnect.
 */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs

    private lateinit var waiting: LinearLayout
    private lateinit var connected: LinearLayout
    private lateinit var permission: LinearLayout
    private lateinit var code: TextView
    private lateinit var thisTv: TextView
    private lateinit var status: TextView
    private lateinit var showOn: Button
    private lateinit var disconnect: Button
    private lateinit var allow: Button
    private lateinit var play: Button
    private lateinit var playHere: Button
    private lateinit var playWhy: TextView
    private lateinit var oldWebView: LinearLayout
    private lateinit var language: Button
    private lateinit var update: Button
    private var inputs: List<Inputs.Input> = emptyList()
    /** The HDMI inputs the game can play from inside the app (none on a stick or a box). */
    private var hdmi: List<Inputs.Hdmi> = emptyList()
    private var resumed = false
    /** Whether "Display over other apps" was allowed at the last look (null: not looked yet). */
    private var wasAllowed: Boolean? = null

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            if (resumed) main.postDelayed(this, STATUS_MS)
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Lang.wrap(base))
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        prefs = Prefs(this)

        val root = FrameLayout(this).apply { background = Look.background() }
        // (scrolls when the cards don't fit: an old TV with no setting for the permission shows both)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        val centre = FrameLayout(this)
        scroll.addView(centre, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val col = column().apply { setPadding(0, dp(24), 0, dp(24)) }
        centre.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // the brand: logo and name in the sunset gradient, and the header's sunset line
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brand.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(64), dp(64)))
        brand.addView(Look.text(this, "VI TRANSLATOR", 44f, 800, Look.TEXT).also { Look.sunset(it) })
        col.addView(brand, wrap())
        col.addView(Look.line(this), LinearLayout.LayoutParams(dp(520), dp(3)).apply {
            topMargin = dp(10)
            bottomMargin = dp(34)
        })

        // not connected: the code
        waiting = column()
        waiting.addView(Look.text(this, getString(R.string.enter_code), 22f, 400, Look.TEXT), wrap())
        code = Look.text(this, "", 96f, 800, Look.NEON).apply {
            Look.neon(this, Look.NEON)
            letterSpacing = 0.12f
            textDirection = View.TEXT_DIRECTION_LTR
        }
        waiting.addView(code, wrap())
        waiting.addView(Look.text(this, getString(R.string.where_on_pc), 16f, 400, Look.MUTED), wrap())
        thisTv = Look.text(this, "", 14f, 400, Look.MUTED).apply { setPadding(0, dp(18), 0, 0) }
        waiting.addView(thisTv, wrap())
        col.addView(waiting, wrap())

        // connected: the connection, Show on, Disconnect
        connected = column()
        status = Look.text(this, "", 26f, 650, Look.TEXT).apply { compoundDrawablePadding = dp(14) }
        connected.addView(status, wrap())
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            // (lined up by their boxes, not their text: by text, the row pushes them past its own bottom)
            isBaselineAligned = false
            // (room for the focused button, drawn a little bigger)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            clipToPadding = false
        }
        showOn = Look.button(this, "").apply { setOnClickListener { pickShowOn() } }
        disconnect = Look.button(this, getString(R.string.disconnect)).apply { setOnClickListener { disconnect() } }
        // (an option where the overlay works too: on a TV with HDMI inputs)
        play = Look.button(this, getString(R.string.play_inside)).apply { setOnClickListener { playInside() } }
        // (side gaps only: a top margin on a button in a centred row is counted twice and pushes it down)
        buttons.addView(showOn, wrap().apply { setMargins(dp(10), 0, dp(10), 0) })
        buttons.addView(play, wrap().apply { setMargins(dp(10), 0, dp(10), 0) })
        buttons.addView(disconnect, wrap().apply { setMargins(dp(10), 0, dp(10), 0) })
        connected.addView(buttons, wrap().apply { topMargin = dp(30) })
        col.addView(connected, wrap())

        // "Display over other apps" not allowed yet
        permission = column().apply {
            background = Look.card(this@MainActivity)
            setPadding(dp(28), dp(18), dp(28), dp(18))
        }
        permission.addView(Look.text(this, getString(R.string.permission_needed), 17f, 400, Look.WARN), wrap())
        val ways = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isBaselineAligned = false
            setPadding(dp(12), dp(8), dp(12), dp(8))
            clipToPadding = false
        }
        allow = Look.button(this, getString(R.string.allow)).apply { setOnClickListener { askPermission() } }
        // or no permission at all: the game inside the app, from the TV's HDMI input
        playHere = Look.button(this, getString(R.string.play_inside)).apply { setOnClickListener { playInside() } }
        ways.addView(allow, wrap().apply { setMargins(dp(10), 0, dp(10), 0) })
        ways.addView(playHere, wrap().apply { setMargins(dp(10), 0, dp(10), 0) })
        permission.addView(ways, wrap().apply { topMargin = dp(4) })
        // what Play inside the app is; or, on a device with no HDMI input, why there's only the overlay
        playWhy = Look.text(this, "", 14f, 400, Look.TEXT).apply {
            setPadding(0, dp(4), 0, 0)
            maxWidth = dp(820)
        }
        permission.addView(playWhy, wrap())
        val adb = getString(R.string.adb_hint, adbCommand)
        permission.addView(Look.text(this, adb, 13f, 400, Look.MUTED).apply { setPadding(0, dp(12), 0, 0) }, wrap())
        col.addView(permission, wrap().apply { topMargin = dp(30) })

        // a web engine too old to draw the PC's TV page (an old TV whose WebView was never updated):
        // what to update, how, and its page on Google Play
        val engine = Compat.webView(this)
        oldWebView = column().apply {
            background = Look.card(this@MainActivity)
            setPadding(dp(28), dp(18), dp(28), dp(18))
            visibility = if (engine.tooOld) View.VISIBLE else View.GONE
        }
        oldWebView.addView(Look.text(this, getString(R.string.webview_old), 17f, 400, Look.WARN), wrap())
        oldWebView.addView(Look.text(this, getString(R.string.webview_how), 14f, 400, Look.TEXT).apply { setPadding(0, dp(8), 0, 0) }, wrap())
        // (the versions, which need no words: this TV's, and the one the page needs)
        oldWebView.addView(Look.text(this, "Android System WebView ${engine.major} \u2192 ${Compat.MIN_WEBVIEW}+", 13f, 400, Look.MUTED).apply {
            textDirection = View.TEXT_DIRECTION_LTR
            setPadding(0, dp(6), 0, 0)
        }, wrap())
        oldWebView.addView(Look.button(this, getString(R.string.open_play)).apply { setOnClickListener { openWebViewStore(engine) } }, wrap().apply { topMargin = dp(12) })
        col.addView(oldWebView, wrap().apply { topMargin = dp(30) })


        // the app's language: the TV's own, or one of the desktop app's. Compact, in the top corner
        // (out of the way, on both screens): a globe and the language in use.
        language = Look.button(this, "").apply {
            setOnClickListener { pickLanguage() }
            text = GLOBE + "  " + (Lang.chosenName(this@MainActivity) ?: getString(R.string.language_tv))
            contentDescription = getString(R.string.language)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            minHeight = dp(44)
            minimumHeight = dp(44)
            setPadding(dp(20), 0, dp(20), 0)
        }
        root.addView(FrameLayout(this).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            clipToPadding = false
            addView(language)
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(20)
            marginEnd = dp(28)
        })

        // a newer version on the app's releases page (Updater): one press, in the other top corner
        update = Look.button(this, "").apply {
            setOnClickListener { Updater.available?.let { r -> Updater.install(this@MainActivity, r) { main.post { showUpdate() } } } }
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            minHeight = dp(44)
            minimumHeight = dp(44)
            setPadding(dp(20), 0, dp(20), 0)
            visibility = View.GONE
        }
        root.addView(FrameLayout(this).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            clipToPadding = false
            addView(update)
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(20)
            marginStart = dp(28)
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        inputs = Inputs.hdmi(this)
        hdmi = Inputs.passthrough(this)
        render()
        main.post(tick)
        // (the code listener starts a moment after: the code shows as soon as it's there)
        main.postDelayed({ refresh() }, 250)
        showUpdate()
        Updater.check(this) { main.post { showUpdate() } }
    }

    /** The update card: shown while a newer version is there, with how the update is going. */
    private fun showUpdate() {
        val r = Updater.available
        update.visibility = if (r == null) View.GONE else View.VISIBLE
        if (r == null) return
        update.text = UP + "  " + when (Updater.state) {
            Updater.State.DOWNLOADING, Updater.State.INSTALLING -> getString(R.string.update_downloading)
            Updater.State.FAILED -> getString(R.string.update_failed)
            else -> getString(R.string.update_available, r.version)
        }
        update.isEnabled = Updater.state != Updater.State.DOWNLOADING && Updater.state != Updater.State.INSTALLING
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        main.removeCallbacks(tick)
        // the PC is often on another input of this TV: keep waiting a few minutes, with the same code
        if (!prefs.connected) PairService.linger(this)
    }

    /** Which part shows, for whether this TV is connected; the code listener runs only while not. */
    private fun render() {
        val on = prefs.connected
        waiting.visibility = if (on) View.GONE else View.VISIBLE
        connected.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            PairService.stop(this)
            showOn.visibility = if (inputs.isEmpty()) View.GONE else View.VISIBLE
            showOn.text = getString(R.string.show_on, showOnLabel())
            play.visibility = if (hdmi.isEmpty()) View.GONE else View.VISIBLE
            // (not allowed: the Allow button first, or the player sees "connected" and nothing over the game)
            if (!Compat.canDrawOverlays(this)) allow.requestFocus()
            else if (!disconnect.hasFocus() && !showOn.hasFocus() && !play.hasFocus()) (if (inputs.isEmpty()) disconnect else showOn).requestFocus()
        } else {
            thisTv.text = getString(R.string.this_tv, PairServer.deviceName(this))
            PairService.start(this)
            code.text = spaced(PairService.code)
        }
        refresh()
    }

    /** The connection light and text, the permission card, and the overlay kept running. */
    private fun refresh() {
        val allowed = Compat.canDrawOverlays(this)
        permission.visibility = if (allowed) View.GONE else View.VISIBLE
        // the way without the permission: once connected (it plays the game with the translations over it)
        playHere.visibility = if (hdmi.isNotEmpty() && prefs.connected) View.VISIBLE else View.GONE
        playWhy.visibility = if (prefs.connected) View.VISIBLE else View.GONE
        playWhy.text = getString(if (hdmi.isEmpty()) R.string.no_hdmi else R.string.play_inside_why)
        playWhy.setTextColor(if (hdmi.isEmpty()) Look.WARN else Look.TEXT)
        // just found not allowed (opened, back from Settings without allowing, or revoked meanwhile):
        // the remote on Allow. Only then, so it can still be moved to the other buttons.
        if (allowed != wasAllowed) {
            wasAllowed = allowed
            if (!allowed) allow.requestFocus()
            else if (prefs.connected && connected.visibility == View.VISIBLE) (if (inputs.isEmpty()) disconnect else showOn).requestFocus()
        }
        if (!prefs.connected) {
            // (the PC removed this TV meanwhile: back to a code)
            if (connected.visibility == View.VISIBLE) render()
            val c = spaced(PairService.code)
            if (!c.contentEquals(code.text)) code.text = c
            return
        }
        if (waiting.visibility == View.VISIBLE) {
            render()
            return
        }
        if (allowed && !OverlayService.running) OverlayService.start(this)
        val pc = prefs.pcName
        val (color, text) = if (!allowed) Look.WARN to getString(if (hdmi.isEmpty()) R.string.connected_not_allowed else R.string.connected_not_allowed_or_play, pc) else when (OverlayService.state) {
            Pc.State.OK -> Look.NEON to getString(R.string.connected_to, pc)
            Pc.State.OVERLAYS_OFF -> Look.WARN to getString(R.string.overlays_off, pc)
            else -> Look.ORANGE to getString(R.string.looking_for, pc)
        }
        status.text = text
        // (the warning in its colour, and smaller: it's long, and the permission card shows below it)
        status.setTextColor(if (allowed) Look.TEXT else Look.WARN)
        status.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (allowed) 26f else 20f)
        status.setCompoundDrawablesRelativeWithIntrinsicBounds(Look.dot(this, color), null, null, null)
    }

    private fun disconnect() {
        val pc = prefs.pc
        val token = prefs.token
        Thread { Pc.forget(pc, token) }.start()
        prefs.disconnect()
        OverlayService.stop(this)
        render()
    }

    /** Show on: a list like the language one, every input first, then each HDMI input by its name on the TV. */
    private fun pickShowOn() {
        val names = arrayOf(getString(R.string.all_inputs)) + inputs.map { it.label }
        val checked = inputs.indexOfFirst { it.deviceId == prefs.showOn } + 1
        // (the title is the button's "Show on: …" without its value: every language has "label: value")
        val title = getString(R.string.show_on, "").trimEnd(' ', ':', '：')
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setSingleChoiceItems(names, checked) { d, which ->
                d.dismiss()
                prefs.showOn = if (which == 0) "" else inputs[which - 1].deviceId
                showOn.text = getString(R.string.show_on, showOnLabel())
            }
            .show()
    }

    private fun showOnLabel(): String = inputs.firstOrNull { it.deviceId == prefs.showOn }?.label ?: getString(R.string.all_inputs)

    /** The language list: the TV's own first, then each language by its own name. */
    private fun pickLanguage() {
        val names = arrayOf(getString(R.string.language_tv)) + Lang.ALL.map { it.name }
        val now = prefs.language
        val checked = if (now.isEmpty()) 0 else Lang.ALL.indexOfFirst { it.tag == now } + 1
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.language)
            .setSingleChoiceItems(names, checked) { d, which ->
                d.dismiss()
                val tag = if (which == 0) "" else Lang.ALL[which - 1].tag
                if (tag == prefs.language) return@setSingleChoiceItems
                prefs.language = tag
                // (back to the TV's own: the system's locale, which the app's default had been changed from)
                @Suppress("DEPRECATION")
                if (tag.isEmpty()) java.util.Locale.setDefault(android.content.res.Resources.getSystem().configuration.locale)
                // the overlay's notification in the new language: its service made again
                if (prefs.connected && OverlayService.running) {
                    OverlayService.stop(this)
                    OverlayService.start(this)
                }
                recreate()
            }
            .show()
    }

    private fun askPermission() {
        // (before Android 6.0 the permission comes with the install: this is never asked there)
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (ignored: ActivityNotFoundException) {
                // no such page on this TV: only adb can allow it
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setMessage(getString(R.string.no_settings_page) + "\n\n" + adbCommand)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    /** Play inside the app: the game from an HDMI input, in this app, with the translations over it. */
    private fun playInside() {
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    /** The web engine's page on Google Play; without Google Play, how to update it. */
    private fun openWebViewStore(engine: Compat.WebViewInfo) {
        val id = Compat.webViewStoreId(engine)
        for (u in listOf("market://details?id=$id", "https://play.google.com/store/apps/details?id=$id")) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                return
            } catch (ignored: ActivityNotFoundException) {
                // (no Google Play, or no browser: the next way)
            }
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setMessage(getString(R.string.webview_how))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Allows "Display over other apps" from a computer, on a TV with no page for it. */
    private val adbCommand get() = "adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow"

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun dp(v: Int) = Look.dp(this, v.toFloat())

    companion object {
        private const val STATUS_MS = 1500L

        /** 🌐 */
        private const val UP = "⬆"
        private const val GLOBE = "🌐"

        private fun wrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        /** "482913" as "482 913" (easier to read from the sofa). */
        private fun spaced(c: String?) = if (c == null || c.length != 6) "" else "${c.substring(0, 3)} ${c.substring(3)}"
    }
}
