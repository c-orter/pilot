package app.overlaylauncher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Registered as a HOME app so that when Android asks for "home" itself (waking from sleep, boot,
 * the system's own Home presses) it lands here and we forward to the chosen home app. Has no UI.
 */
class HomeRelayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = launchIntentFor(this, Prefs.homePackage(this))
            ?.apply { if (Prefs.restartHomeApp(this@HomeRelayActivity)) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK) }
            ?: stockHomeIntent(this)
        try {
            if (intent != null) startActivity(intent)
        } catch (e: Exception) {
            Log.w("OverlayLauncher", "home relay could not start $intent", e)
            stockHomeIntent(this)?.let { runCatching { startActivity(it) } }
        }
        finish()
    }
}
