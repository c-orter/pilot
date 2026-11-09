package app.overlaylauncher

import android.content.Context

/** User settings, shared by the settings screen and the key service. */
object Prefs {
    const val DEFAULT_HOME_PACKAGE = "com.nuvio.tv"

    private const val FILE = "settings"
    private const val KEY_HOME_PACKAGE = "home_package"
    private const val KEY_RESTART_HOME_APP = "restart_home_app"
    private const val KEY_APP_ORDER = "app_order"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun homePackage(context: Context): String =
        prefs(context).getString(KEY_HOME_PACKAGE, DEFAULT_HOME_PACKAGE) ?: DEFAULT_HOME_PACKAGE

    fun setHomePackage(context: Context, packageName: String) =
        prefs(context).edit().putString(KEY_HOME_PACKAGE, packageName).apply()

    /** Whether a short Home press restarts the home app so it always lands on its start screen. */
    fun restartHomeApp(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RESTART_HOME_APP, true)

    fun setRestartHomeApp(context: Context, restart: Boolean) =
        prefs(context).edit().putBoolean(KEY_RESTART_HOME_APP, restart).apply()

    /** Package names in the order the user arranged them in the overlay grid. */
    fun appOrder(context: Context): List<String> =
        prefs(context).getString(KEY_APP_ORDER, "").orEmpty().split('\n').filter { it.isNotEmpty() }

    fun setAppOrder(context: Context, packages: List<String>) =
        prefs(context).edit().putString(KEY_APP_ORDER, packages.joinToString("\n")).apply()
}
