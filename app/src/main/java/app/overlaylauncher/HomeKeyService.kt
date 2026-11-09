package app.overlaylauncher

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * Takes over the Home button: a short press opens the home app, a long press opens the apps overlay.
 * Home is always consumed, so the system never runs its own Home or long-press (Dashboard) action.
 */
class HomeKeyService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var longPressFired = false
    private var foregroundPackage: String? = null

    private val overlay by lazy {
        AppsOverlay(
            context = this,
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager,
            onLaunch = ::launch,
            onAppAction = ::runAppAction,
            onTvHome = ::openSystemHome,
            onSettings = ::openSettings,
        )
    }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = overlay.dismiss()
    }

    private val showOverlay = Runnable {
        longPressFired = true
        try {
            overlay.show()
        } catch (e: Exception) {
            Log.w(TAG, "overlay failed to open", e)
        }
    }

    override fun onServiceConnected() {
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                longPressFired = false
                handler.postDelayed(showOverlay, LONG_PRESS_MS)
            }
        } else {
            handler.removeCallbacks(showOverlay)
            if (!longPressFired) {
                // Whatever goes wrong, Home is already consumed, so never let it throw out of here.
                runCatching { onHomePressed() }.onFailure { Log.w(TAG, "home press failed", it) }
            }
        }
        return true
    }

    private fun onHomePressed() {
        overlay.dismiss()
        val homePackage = Prefs.homePackage(this)
        if (foregroundPackage != homePackage) launch(homePackage)
    }

    /** Tracks which app is in front, and closes the overlay when a different one takes over. */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "android" || pkg.startsWith("com.android.systemui")) return
        if (pkg != foregroundPackage) {
            foregroundPackage = pkg
            overlay.dismiss()
        }
    }

    private fun launch(packageName: String) {
        // TV-only apps have no phone LAUNCHER entry, only a LEANBACK one.
        val intent = packageManager.getLeanbackLaunchIntentForPackage(packageName)
            ?: packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            // Never leave Home dead: if the chosen app is gone, fall back to the system home screen.
            Log.w(TAG, "no launch intent for $packageName")
            openSystemHome()
            return
        }
        // Single-top apps like Nuvio ignore intents while open, so clear the home app's task
        // to always land on its start screen.
        if (packageName == Prefs.homePackage(this) && Prefs.restartHomeApp(this)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        start(intent)
    }

    /** Starts [intent]; if the system refuses, falls back to the stock home screen so Home is never dead. */
    private fun start(intent: Intent, fallbackToHome: Boolean = true) {
        overlay.dismiss()
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "could not start $intent", e)
            if (fallbackToHome) openSystemHome() else Toast.makeText(this, "Could not open that", Toast.LENGTH_SHORT).show()
        }
    }

    /** The escape hatch: the system's own home screen (we never register as a HOME app). */
    private fun openSystemHome() =
        start(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), fallbackToHome = false)

    private fun openSettings() = start(Intent(this, MainActivity::class.java))

    private fun runAppAction(action: AppAction, app: AppEntry) {
        val uri = Uri.parse("package:${app.packageName}")
        when (action) {
            // Android doesn't let normal apps force-stop others; this kills background processes only.
            AppAction.FORCE_CLOSE -> {
                (getSystemService(ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(app.packageName)
                overlay.flash("Asked Android to close ${app.label}")
            }
            AppAction.APP_SETTINGS -> start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
            AppAction.UNINSTALL -> start(Intent(Intent.ACTION_DELETE, uri))
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacksAndMessages(null)
        overlay.dismiss()
        runCatching { unregisterReceiver(screenOff) }
        return super.onUnbind(intent)
    }

    private companion object {
        const val TAG = "OverlayLauncher"
        const val LONG_PRESS_MS = 500L
    }
}
