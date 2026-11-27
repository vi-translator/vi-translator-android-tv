package app.vitranslator.tv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The translations: the PC or phone app's TV page (transparent, the text where it was read), and
 * what keeps it going. Drawn over the game by the overlay window (OverlayService), or over the HDMI
 * picture inside the app (PlayerActivity).
 *
 * It also: notices when the PC app removed this TV ([onRemoved]); finds the PC again when it got a
 * new address on the home network; loads the page again when it went quiet; and shows a small note
 * when the PC or phone stopped answering (an iPhone suspends VI Translator when another app, like PS
 * Remote Play, comes to the front) or when this TV's web engine is too old to draw the page.
 *
 * [noteFor]: how long a note stays on screen (the overlay is over everything, apps too: it isn't
 * left there all evening); null for as long as it applies.
 */
@SuppressLint("ViewConstructor")
class TvPage(
    ctx: Context,
    private val noteFor: Long?,
    private val onState: (Pc.State) -> Unit,
    private val onRemoved: () -> Unit,
) : FrameLayout(ctx) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = Prefs(ctx)
    private val web: WebView
    private val waitingNote: TextView
    private val oldNote: TextView
    private val oldWebView = Compat.webView(ctx).tooOld
    private var url: String? = null
    private var unreachable = 0
    private var finding = false
    private var startedAt = 0L

    /** When the page last said it's alive (it does on every answer from the PC), and whether it ever did. */
    @Volatile
    private var aliveAt = 0L

    @Volatile
    private var pinged = false

    /** When the PC or phone was last heard from (the page or this app's own asking), whether ever, and whether the page talks. */
    @Volatile
    private var heardAt = 0L

    @Volatile
    private var heard = false

    @Volatile
    private var talks = false

    private val retry = Runnable { load() }
    private val check = Runnable { check() }
    private val notes = Runnable { notes() }

    init {
        web = page(ctx)
        addView(web, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // the notes: small, in the top corner, away from the subtitles
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        waitingNote = note(ctx, ctx.getString(R.string.waiting_source))
        oldNote = note(ctx, ctx.getString(R.string.webview_old) + "\n" + ctx.getString(R.string.webview_how))
        col.addView(waitingNote)
        col.addView(oldNote, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Look.dp(ctx, 10f)
        })
        addView(col, LayoutParams(Look.dp(ctx, 440f), LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = Look.dp(ctx, 28f)
            marginEnd = Look.dp(ctx, 36f)
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun page(ctx: Context): WebView {
        val w = WebView(ctx)
        w.setBackgroundColor(Color.TRANSPARENT)
        w.isFocusable = false
        w.isFocusableInTouchMode = false
        // (where the window takes touches, the page never reacts to them)
        @SuppressLint("ClickableViewAccessibility")
        w.setOnTouchListener { _, _ -> true }
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        w.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, req: WebResourceRequest, error: WebResourceError) {
                if (req.isForMainFrame) failed()
            }

            override fun onReceivedHttpError(view: WebView, req: WebResourceRequest, res: WebResourceResponse) {
                if (req.isForMainFrame) failed()
            }

            // the page never goes anywhere else; reloading itself (the PC app restarted, maybe updated)
            // it may: blocked, it would stop and show nothing until the TV app restarts
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest) = req.url.toString() != url
        }
        // the page says it's alive on every answer from the PC: one that went quiet is loaded again
        w.addJavascriptInterface(object {
            @JavascriptInterface
            fun alive() {
                val now = SystemClock.elapsedRealtime()
                aliveAt = now
                pinged = true
                talks = true
                heardAt = now
                heard = true
            }
        }, "VITTV")
        return w
    }

    private fun note(ctx: Context, s: String) = Look.text(ctx, s, 15f, 400, Look.TEXT).apply {
        background = Look.card(ctx)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(Look.dp(ctx, 18f), Look.dp(ctx, 10f), Look.dp(ctx, 18f), Look.dp(ctx, 10f))
        setCompoundDrawablesRelativeWithIntrinsicBounds(Look.dot(ctx, Look.WARN), null, null, null)
        compoundDrawablePadding = Look.dp(ctx, 12f)
        visibility = View.GONE
    }

    /** Starts (or goes on with) the page of the PC this TV is connected to, and asking how it is. */
    fun start() {
        if (startedAt == 0L) startedAt = SystemClock.elapsedRealtime()
        if (prefs.pc.isEmpty()) return
        show(Pc.pageUrl(prefs.pc, prefs.token))
        main.removeCallbacks(check)
        main.post(check)
        main.removeCallbacks(notes)
        main.post(notes)
    }

    /** Stops the page (nothing loaded, nothing asked) until [start]. */
    fun stop() {
        main.removeCallbacksAndMessages(null)
        url = null
        web.loadUrl("about:blank")
        waitingNote.visibility = View.GONE
        oldNote.visibility = View.GONE
        startedAt = 0L
    }

    fun destroy() {
        main.removeCallbacksAndMessages(null)
        url = null
        web.destroy()
    }

    private fun show(pageUrl: String) {
        if (pageUrl != url) {
            url = pageUrl
            load()
        }
    }

    private fun load() {
        main.removeCallbacks(retry)
        val u = url ?: return
        val (pc, token) = prefs.pc to prefs.token
        // only once the PC answers this TV as connected: an error page (its text) never shows over the game
        Thread {
            val s = Pc.ask(pc, token, 2000, 4000)
            main.post {
                if (u != url) return@post
                if (s == Pc.State.OK || s == Pc.State.OVERLAYS_OFF) {
                    val now = SystemClock.elapsedRealtime()
                    aliveAt = now
                    heardAt = now
                    heard = true
                    web.loadUrl(u)
                } else {
                    failed()
                }
            }
        }.start()
    }

    /** The page isn't there: nothing on screen (not an error page), and try again a little later. */
    private fun failed() {
        if (url == null) return
        web.loadUrl("about:blank")
        main.removeCallbacks(retry)
        main.postDelayed(retry, RETRY_MS)
    }

    /** How the PC is: removed this TV (disconnect), or gone a while (look for it at a new address). */
    private fun check() {
        val pc = prefs.pc
        val token = prefs.token
        if (pc.isEmpty()) {
            onRemoved()
            return
        }
        Thread {
            val s = Pc.ask(pc, token, 2000, 4000)
            main.post {
                if (url == null) return@post // (stopped meanwhile)
                onState(s)
                if (s == Pc.State.REMOVED) {
                    onRemoved()
                    return@post
                }
                val answers = s == Pc.State.OK || s == Pc.State.OVERLAYS_OFF
                if (answers) {
                    heardAt = SystemClock.elapsedRealtime()
                    heard = true
                }
                unreachable = if (s == Pc.State.UNREACHABLE) unreachable + 1 else 0
                // The PC answers, but the page has gone quiet (it stopped asking it): load it again. Only
                // while it's on screen (a hidden page may be paused) and once it has said it's alive (a PC
                // app from before that never does).
                val quiet = SystemClock.elapsedRealtime() - aliveAt > QUIET_MS
                if (answers && pinged && quiet && visibility == View.VISIBLE) {
                    pinged = false
                    load()
                }
                if (unreachable >= 3 && !finding) findPc(pc, token)
                main.postDelayed(check, CHECK_MS)
            }
        }.start()
    }

    private fun findPc(pc: String, token: String) {
        finding = true
        val port = pc.substringAfterLast(':').toInt()
        Thread {
            val found = Pc.find(context, port, token)
            main.post {
                finding = false
                if (url != null && found != null && found != pc) {
                    prefs.setPc(found)
                    unreachable = 0
                    show(Pc.pageUrl(found, token))
                }
            }
        }.start()
    }

    /**
     * The notes. "Waiting for VI Translator": the PC or phone answered, then went quiet longer than
     * it ever does while it runs (the page asks it every few seconds; a PC app whose page doesn't say
     * so is asked by this app every [CHECK_MS]). "Too old": while the page may not draw at all.
     */
    private fun notes() {
        val now = SystemClock.elapsedRealtime()
        val limit = noteFor ?: Long.MAX_VALUE
        val silent = now - heardAt - (if (talks) WAIT_TALKS_MS else WAIT_MS)
        val waiting = heard && silent > 0 && silent < limit
        val old = oldWebView && now - startedAt < limit
        waitingNote.visibility = if (waiting) View.VISIBLE else View.GONE
        oldNote.visibility = if (old) View.VISIBLE else View.GONE
        main.postDelayed(notes, NOTES_MS)
    }

    companion object {
        /** after the page couldn't be loaded (the PC off, the app closed) */
        private const val RETRY_MS = 5000L

        /** how often the PC is asked how it is */
        private const val CHECK_MS = 10_000L

        /** a page on screen that hasn't asked the PC for this long is loaded again */
        private const val QUIET_MS = 20_000L

        /** no word from the PC or phone this long, while its page says it's alive on every answer (at most 6 s apart) */
        private const val WAIT_TALKS_MS = 12_000L

        /** the same, for a PC app whose page doesn't say so (this app asks every CHECK_MS, an answer may take 6 s) */
        private const val WAIT_MS = 25_000L

        /** how often the notes are looked at */
        private const val NOTES_MS = 1000L
    }
}
