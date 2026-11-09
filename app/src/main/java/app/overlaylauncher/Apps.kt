package app.overlaylauncher

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

/** [banner] is the TV launcher banner (320x180dp), which many apps don't ship. */
class AppEntry(val label: String, val packageName: String, val icon: Drawable, val banner: Drawable?)

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
