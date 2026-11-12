package app.overlaylauncher

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

/** [banner] is the TV launcher banner (320x180dp), which many apps don't ship. */
class AppEntry(val label: String, val packageName: String, val icon: Drawable, val banner: Drawable?)

/** TV-only apps have no phone LAUNCHER entry, only a LEANBACK one. */
fun launchIntentFor(context: Context, packageName: String): Intent? =
    context.packageManager.getLeanbackLaunchIntentForPackage(packageName)
        ?: context.packageManager.getLaunchIntentForPackage(packageName)

/**
 * The device's own home screen, addressed explicitly. A plain HOME intent would resolve to us
 * when we're the default home app and loop forever.
 */
fun stockHomeIntent(context: Context): Intent? {
    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val stock = context.packageManager.queryIntentActivities(home, 0)
        .firstOrNull { it.activityInfo.packageName != context.packageName }
        ?: return null
    return home.setClassName(stock.activityInfo.packageName, stock.activityInfo.name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Apps that show up in a TV or phone launcher, sorted by name. */
fun loadLaunchableApps(context: Context, excludeSelf: Boolean): List<AppEntry> {
    val pm = context.packageManager
    val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
    val phone = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return (pm.queryIntentActivities(leanback, 0) + pm.queryIntentActivities(phone, 0))
        .distinctBy { it.activityInfo.packageName }
        .filter { !excludeSelf || it.activityInfo.packageName != context.packageName }
        .map {
            AppEntry(
                label = it.loadLabel(pm).toString(),
                packageName = it.activityInfo.packageName,
                icon = it.loadIcon(pm),
                banner = it.activityInfo.loadBanner(pm),
            )
        }
        .sortedBy { it.label.lowercase() }
}
