package app.vitranslator.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Play inside the app": the game from one of the TV's HDMI inputs, played by this app (Android's
 * TV Input Framework: a TvView tuned to the input's passthrough channel), with the PC or phone app's
 * TV page (TvPage) over it. For the TVs where "Display over other apps" can't be allowed (no page for
 * it, or one that refuses): this needs no permission. The webOS app works this way.
 *
 * Back shows the panel (the input, Back to the game, Leave); Back again leaves.
 */
class PlayerActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var tv: TvView
    private var page: TvPage? = null
    private lateinit var message: TextView
    private lateinit var panel: LinearLayout
    private lateinit var status: TextView
    private lateinit var input: Button
    private lateinit var resume: Button
    private lateinit var leave: Button
    private lateinit var hint: TextView
    private var inputs: List<Inputs.Hdmi> = emptyList()
    private var playing: Inputs.Hdmi? = null
    private var state = Pc.State.UNREACHABLE
    private val hidePanel = Runnable { panel(false) }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Lang.wrap(base))
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        prefs = Prefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        tv = TvView(this).apply {
            // (the remote is for the panel; the game is played with the console's controller)
            isFocusable = false
            isFocusableInTouchMode = false
            setCallback(picture)
        }
        root.addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        page = TvPage(this, null, { s ->
            state = s
            showStatus()
        }, {
            // (removed in the PC or phone app: back to the code)
            prefs.disconnect()
            finish()
        })
        root.addView(page, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // no picture, or no HDMI input at all: in the middle
        message = Look.text(this, "", 20f, 400, Look.WARN).apply {
            background = Look.card(this@PlayerActivity)
            setPadding(dp(32), dp(22), dp(32), dp(22))
            visibility = View.GONE
        }
        root.addView(message, FrameLayout.LayoutParams(dp(720), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            // (above the panel)
            bottomMargin = dp(120)
        })

        // the panel, at the bottom: the connection, the input, Back to the game, Leave
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Look.card(this@PlayerActivity)
            setPadding(dp(28), dp(18), dp(28), dp(14))
            clipToPadding = false
            visibility = View.GONE
        }
        status = Look.text(this, "", 18f, 650, Look.TEXT).apply { compoundDrawablePadding = dp(12) }
        panel.addView(status, wrap())
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isBaselineAligned = false
            setPadding(dp(12), dp(8), dp(12), dp(8))
            clipToPadding = false
        }
        input = Look.button(this, "").apply { setOnClickListener { pickInput() } }
        resume = Look.button(this, getString(R.string.back_to_game)).apply { setOnClickListener { if (playing == null) pickInput() else panel(false) } }
        leave = Look.button(this, getString(R.string.leave)).apply { setOnClickListener { finish() } }
        for (b in listOf(input, resume, leave)) buttons.addView(b, wrap().apply { setMargins(dp(8), 0, dp(8), 0) })
        panel.addView(buttons, wrap().apply { topMargin = dp(10) })
        hint = Look.text(this, getString(R.string.back_again), 13f, 400, Look.MUTED)
        panel.addView(hint, wrap().apply { topMargin = dp(4) })
        root.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(36)
        })

        setContentView(root)

        // Back: the panel, then leave. From Android 16 (for an app made for it) Back comes only this
        // way; before, as the remote's Back key (onKeyDown), and this one is never called.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        }
    }

    override fun onStart() {
        super.onStart()
        // (the overlay window would draw the translations a second time, over this screen)
        OverlayService.stop(this)
        inputs = Inputs.passthrough(this)
        if (inputs.isEmpty()) {
            // a stick or box plugged into a TV: nothing to play here
            say(getString(R.string.no_hdmi))
            for (v in listOf(status, input, resume, hint)) v.visibility = View.GONE
            panel(true)
            return
        }
        for (v in listOf(status, input, resume, hint)) v.visibility = View.VISIBLE
        page?.start()
        showStatus()
        val saved = inputs.firstOrNull { it.id == prefs.playInput }
        when {
            saved != null -> tune(saved)
            inputs.size == 1 -> tune(inputs[0])
            else -> {
                // which one is the console's: asked once, then remembered
                panel(true)
                pickInput()
            }
        }
    }

    override fun onStop() {
        main.removeCallbacks(hidePanel)
        // (only the app in front may play an input)
        playing = null
        tv.reset()
        page?.stop()
        // back to the overlay, where it's allowed (the home screen, another app, the TV's own input)
        if (prefs.connected && Compat.canDrawOverlays(this)) OverlayService.start(this)
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        page?.destroy()
        page = null
        super.onDestroy()
    }

    private fun tune(h: Inputs.Hdmi) {
        playing = h
        prefs.playInput = h.id
        input.text = getString(R.string.hdmi_input, h.label)
        message.visibility = View.GONE
        try {
            tv.tune(h.id, TvContract.buildChannelUriForPassthroughInput(h.id))
        } catch (e: RuntimeException) {
            // (this TV doesn't let an app play its inputs)
            noPicture()
        }
    }

    /** What the TV tells about the input's picture. */
    private val picture = object : TvView.TvInputCallback() {
        override fun onConnectionFailed(inputId: String) = noPicture()

        override fun onDisconnected(inputId: String) = noPicture()

        override fun onVideoAvailable(inputId: String) {
            message.visibility = View.GONE
        }

        override fun onVideoUnavailable(inputId: String, reason: Int) {
            // (tuning and buffering pass by themselves; sound only is still the game)
            when (reason) {
                TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING,
                TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING,
                TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY -> Unit
                else -> noPicture()
            }
        }
    }

    private fun noPicture() {
        val h = playing ?: return
        say(getString(R.string.no_picture, h.label))
        if (panel.visibility != View.VISIBLE) panel(true)
    }

    private fun say(s: String) {
        message.text = s
        message.visibility = View.VISIBLE
    }

    private fun showStatus() {
        val pc = prefs.pcName
        val (color, text) = when (state) {
            Pc.State.OK -> Look.NEON to getString(R.string.connected_to, pc)
            Pc.State.OVERLAYS_OFF -> Look.WARN to getString(R.string.overlays_off, pc)
            else -> Look.ORANGE to getString(R.string.looking_for, pc)
        }
        status.text = text
        status.setCompoundDrawablesRelativeWithIntrinsicBounds(Look.dot(this, color), null, null, null)
    }

    /** Which input is the console's: the TV's HDMI inputs by their names on it. */
    private fun pickInput() {
        val names = inputs.map { it.label }.toTypedArray()
        val checked = inputs.indexOfFirst { it.id == playing?.id }
        // (the title is the button's "Input: …" without its value: every language has "label: value")
        val title = getString(R.string.hdmi_input, "").trimEnd(' ', ':', '：')
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setSingleChoiceItems(names, checked) { d, which ->
                d.dismiss()
                tune(inputs[which])
                panel(false)
            }
            .show()
    }

    /** The panel shown (the remote on Back to the game) or hidden (the game alone). */
    private fun panel(show: Boolean) {
        main.removeCallbacks(hidePanel)
        if (!show && playing == null) return // (nothing to go back to: it stays)
        panel.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            (if (resume.visibility == View.VISIBLE) resume else leave).requestFocus()
            if (playing != null) main.postDelayed(hidePanel, PANEL_MS)
        }
    }

    private fun back() {
        if (panel.visibility == View.VISIBLE) finish() else panel(true)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // (the remote in use: the panel stays a while longer)
        if (panel.visibility == View.VISIBLE && playing != null) {
            main.removeCallbacks(hidePanel)
            main.postDelayed(hidePanel, PANEL_MS)
        }
        return super.dispatchKeyEvent(event)
    }

    // (Back as a key: where Android still sends it so, before Android 16 or Android 13. From them, the
    // callback registered in onCreate gets it instead; this app has no AndroidX for one way for all.)
    @SuppressLint("GestureBackNavigation")
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.repeatCount == 0) back()
            return true
        }
        // the remote's OK, menu or arrows while the game plays: the panel
        if (panel.visibility != View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    panel(true)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun dp(v: Int) = Look.dp(this, v.toFloat())

    companion object {
        /** the panel hides by itself after this long without the remote */
        private const val PANEL_MS = 12_000L

        private fun wrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
}
