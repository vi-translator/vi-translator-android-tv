package app.vitranslator.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * What differs between Android versions, so the app runs on every Android TV it supports: from
 * Android 7.0 (API 24, app/build.gradle.kts) to today's Google TV. (It was written for Android 5.0,
 * API 21, the first Android TV, and still handles those versions.)
 */
object Compat {
    /** Drawing over other apps: allowed by the user from Android 6.0; granted at install before. */
    fun canDrawOverlays(c: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(c)

    /** The window type for drawing over other apps (Android 8.0 replaced the old one). */
    @Suppress("DEPRECATION")
    val overlayWindowType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

    /**
     * The overlay window's flags. Until Android 12 it lets touches through (FLAG_NOT_TOUCHABLE). From
     * Android 12, Android caps the opacity of such a window at 0.8 (touches through an overlay more
     * opaque than that are blocked), so the game showed through everything drawn: there the window
     * takes touches instead. A TV has none; the remote's keys still go to the input under it
     * (FLAG_NOT_FOCUSABLE).
     */
    val overlayWindowFlags: Int
        get() = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            (if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)

    /** A service that shows a notification while it runs (started in the foreground from Android 8.0). */
    fun startForegroundService(c: Context, i: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i) else c.startService(i)
    }

    /**
     * The oldest web engine (Chrome / Android System WebView major version) that draws the PC app's
     * TV page: its drawing code uses modern JavaScript and CSS (flex gap: Chrome 84).
     */
    const val MIN_WEBVIEW = 84

    /** The web engine pages are drawn with: its package (to update it from) and its major version (0: unknown). */
    data class WebViewInfo(val pkg: String?, val major: Int) {
        /** Known, and older than the TV page needs. */
        val tooOld: Boolean get() = major in 1 until MIN_WEBVIEW
    }

    /**
     * This TV's web engine: the package Android draws web pages with (from Android 8.0 it says
     * which; before, the first of the usual ones installed), its version from the package, else
     * from the engine's user agent ("… Chrome/95.0…").
     */
    fun webView(c: Context): WebViewInfo {
        val p = webViewPackage(c)
        val fromPkg = p?.versionName?.let { MAJOR.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        val major = if (fromPkg > 0) fromPkg else try {
            Regex("""Chrome/(\d+)""").find(WebSettings.getDefaultUserAgent(c))?.groupValues?.get(1)?.toInt() ?: 0
        } catch (e: Exception) {
            0
        }
        return WebViewInfo(p?.packageName, major)
    }

    private val MAJOR = Regex("""^(\d+)\.""")

    private fun webViewPackage(c: Context): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WebView.getCurrentWebViewPackage()
        } else {
            // (Android 7: Chrome when it's there and on, else the WebView)
            listOf("com.android.chrome", "com.google.android.webview", "com.android.webview").firstNotNullOfOrNull { name ->
                try {
                    c.packageManager.getPackageInfo(name, 0).takeIf { it.applicationInfo?.enabled != false }
                } catch (e: PackageManager.NameNotFoundException) {
                    null
                }
            }
        }
    } catch (e: Exception) {
        null // (no web engine at all, or it failed to load)
    }

    /** The web engine's page on Google Play (the TV's own engine when it's there, else Google's). */
    fun webViewStoreId(info: WebViewInfo): String =
        info.pkg?.takeIf { it == "com.google.android.webview" || it == "com.android.chrome" || it.startsWith("com.google.android.") }
            ?: "com.google.android.webview"

    /** PendingIntent flags: immutable where Android knows the flag (6.0 and later). */
    val immutable: Int get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    /** The service's ongoing notification (channels from Android 8.0). */
    fun foreground(s: Service, id: Int, channel: String, channelName: String, title: String, text: String, open: PendingIntent) {
        val nm = s.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(channel, channelName, NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(s, channel)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(s)
        }
        val n = b.setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            s.startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            s.startForeground(id, n)
        }
    }
}
