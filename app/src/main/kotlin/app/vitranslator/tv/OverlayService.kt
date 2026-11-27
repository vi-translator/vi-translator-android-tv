package app.vitranslator.tv

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.WindowManager

/**
 * The translations over what the TV shows (the game on an HDMI input): the PC app's TV page
 * (TvPage) in a transparent window on top of everything. The window can't be focused or touched,
 * so the remote keeps working the TV and the input under it.
 *
 * Runs while this TV is connected and "Display over other apps" is allowed. It also hides the
 * window when "Show on" names another input. (Where that can't be allowed, the game plays inside
 * the app instead: PlayerActivity.)
 */
class OverlayService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var windows: WindowManager
    private var page: TvPage? = null
    private val watchInput = Runnable { watchInput() }

    override fun onCreate() {
        super.onCreate()
        windows = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    // (its notification in the app's language)
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Lang.wrap(base))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // (in the foreground at once, as Android asks of a service started this way)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), Compat.immutable)
        Compat.foreground(this, 1, CHANNEL, getString(R.string.notify_channel), getString(R.string.app_name), getString(R.string.notify_text), open)
        val prefs = Prefs(this)
        if (!prefs.connected || !Compat.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val p = page ?: TvPage(this, NOTE_FOR_MS, { s -> state = s }, {
            Prefs(this).disconnect()
            stopSelf()
        }).also { p ->
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Compat.overlayWindowType,
                Compat.overlayWindowFlags,
                PixelFormat.TRANSLUCENT,
            )
            windows.addView(p, lp)
            page = p
            running = true
        }
        p.start()
        main.removeCallbacks(watchInput)
        main.post(watchInput)
        return START_STICKY
    }

    /** "Show on": the window only while the chosen input is on screen. */
    private fun watchInput() {
        val p = page ?: return
        val v = if (Inputs.showing(contentResolver, Prefs(this).showOn)) View.VISIBLE else View.INVISIBLE
        if (p.visibility != v) p.visibility = v
        main.postDelayed(watchInput, INPUT_MS)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        page?.let { p ->
            try {
                windows.removeView(p)
            } catch (ignored: IllegalArgumentException) {
                // (not added)
            }
            p.destroy()
        }
        page = null
        running = false
        state = Pc.State.UNREACHABLE
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "overlay"

        /** how often the input on screen is looked at */
        private const val INPUT_MS = 400L

        /** a note over the TV picture (the PC or phone gone quiet, an old web engine) stays this long */
        private const val NOTE_FOR_MS = 10 * 60_000L

        /** For the screen: is the overlay up, and how the PC answered last. */
        @Volatile
        var running = false

        @Volatile
        var state = Pc.State.UNREACHABLE

        fun start(c: Context) {
            Compat.startForegroundService(c, Intent(c, OverlayService::class.java))
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, OverlayService::class.java))
        }
    }
}
