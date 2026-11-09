package app.overlaylauncher

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Takes over the Home button: a short press opens the home app, a long press opens the apps overlay.
 * Home is always consumed, so the system never runs its own Home or long-press (Dashboard) action.
 */
class HomeKeyService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var longPressFired = false

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

    private val showOverlay = Runnable {
        longPressFired = true
        overlay.show()
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
            if (!longPressFired) onHomePressed()
        }
        return true
    }

    private fun onHomePressed() {
        if (overlay.isShowing) {
            // Close the overlay first, then give the app below a moment to become the active window again.
            overlay.dismiss()
            handler.postDelayed(::goHome, OVERLAY_SETTLE_MS)
        } else {
            goHome()
        }
    }

    /** Opens the home app, unless it is already sitting on its home screen. */
    private fun goHome() {
        if (!isOnHomeScreen()) launch(Prefs.homePackage(this))
    }

    /** Nuvio marks the sidebar's "Home" item as selected while its home screen is showing. */
    private fun isOnHomeScreen(): Boolean {
        if (Prefs.homePackage(this) != NUVIO_PACKAGE) return false
        val root = rootInActiveWindow ?: return false
        return root.packageName == NUVIO_PACKAGE && hasSelectedHome(root)
    }

    private fun hasSelectedHome(node: AccessibilityNodeInfo): Boolean {
        if (node.isSelected && node.contentDescription?.toString() == "Home") return true
        return (0 until node.childCount).any { i -> node.getChild(i)?.let(::hasSelectedHome) == true }
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

    private fun start(intent: Intent) {
        overlay.dismiss()
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** The escape hatch: the system's own home screen (we never register as a HOME app). */
    private fun openSystemHome() = start(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))

    private fun openSettings() = start(Intent(this, MainActivity::class.java))

    private fun runAppAction(action: AppAction, app: AppEntry) {
        val uri = Uri.parse("package:${app.packageName}")
        when (action) {
            // Android doesn't let normal apps force-stop others; this kills background processes only.
            AppAction.FORCE_CLOSE -> {
                (getSystemService(ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(app.packageName)
                Toast.makeText(this, "Asked Android to close ${app.label}", Toast.LENGTH_SHORT).show()
            }
            AppAction.APP_SETTINGS -> start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
            AppAction.UNINSTALL -> start(Intent(Intent.ACTION_DELETE, uri))
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacks(showOverlay)
        overlay.dismiss()
        return super.onUnbind(intent)
    }

    private companion object {
        const val TAG = "OverlayLauncher"
        const val NUVIO_PACKAGE = "com.nuvio.tv"
        const val LONG_PRESS_MS = 500L
        const val OVERLAY_SETTLE_MS = 150L
    }
}
