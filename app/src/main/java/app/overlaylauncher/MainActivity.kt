package app.overlaylauncher

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** Settings: pick the "home" app, and check that the key service is enabled. */
class MainActivity : Activity() {

    private var picking = false

    override fun onResume() {
        super.onResume()
        if (picking) showPicker() else showSettings()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (picking) {
            picking = false
            showSettings()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun showSettings() {
        val homePackage = Prefs.homePackage(this)
        val homeLabel = labelFor(homePackage)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(96), dp(48), dp(96), dp(48))
        }
        column.addView(text("Pilot", 32f, Color.WHITE))
        column.addView(
            text(
                "Short press Home: open $homeLabel.   Hold Home: apps list.",
                16f,
                0xFFB0B6C3.toInt(),
            ),
        )
        column.addView(spacer(24))

        val serviceOn = isServiceEnabled()
        column.addView(
            text(
                if (serviceOn) "Service is running" else "Service is off. Turn on \"Pilot\" in Accessibility.",
                18f,
                if (serviceOn) 0xFF7BD88F.toInt() else 0xFFFFB454.toInt(),
            ),
        )
        val accessibilityButton = button("Open accessibility settings") {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        column.addView(accessibilityButton)
        column.addView(spacer(24))

        val homeButton = button("Home app: $homeLabel") {
            picking = true
            showPicker()
        }
        column.addView(homeButton)
        column.addView(
            CheckBox(this).apply {
                text = "Restart the home app on every Home press"
                textSize = 18f
                setTextColor(Color.WHITE)
                isChecked = Prefs.restartHomeApp(this@MainActivity)
                setOnCheckedChangeListener { _, checked -> Prefs.setRestartHomeApp(this@MainActivity, checked) }
            },
        )
        column.addView(
            text(
                "Off: Home returns to the app as you left it. On: the app starts fresh, so it always lands on its home screen.",
                14f,
                0xFF8A91A0.toInt(),
            ),
        )

        setContentView(root(column))
        // Land on the most useful control: fix the service first, otherwise change the home app.
        (if (serviceOn) homeButton else accessibilityButton).requestFocus()
    }

    private fun showPicker() {
        val apps = loadLaunchableApps(this, excludeSelf = true)
        val list = ListView(this).apply {
            divider = null
            adapter = PickerAdapter(apps)
            setOnItemClickListener { _, _, position, _ ->
                Prefs.setHomePackage(this@MainActivity, apps[position].packageName)
                picking = false
                showSettings()
            }
            val current = apps.indexOfFirst { it.packageName == Prefs.homePackage(this@MainActivity) }
            if (current >= 0) setSelection(current)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(96), dp(48), dp(96), dp(24))
            addView(text("Choose the home app", 32f, Color.WHITE))
            addView(spacer(16))
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root(column))
        list.requestFocus()
    }

    private inner class PickerAdapter(private val apps: List<AppEntry>) : BaseAdapter() {
        private val selectedPackage = Prefs.homePackage(this@MainActivity)

        override fun getCount() = apps.size
        override fun getItem(position: Int) = apps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val app = apps[position]
            return LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
                addView(
                    ImageView(this@MainActivity).apply { setImageDrawable(app.icon) },
                    LinearLayout.LayoutParams(dp(48), dp(48)),
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = if (app.packageName == selectedPackage) "${app.label}  (current)" else app.label
                        setTextColor(Color.WHITE)
                        textSize = 20f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(dp(16), 0, 0, 0)
                    },
                )
            }
        }
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(this, HomeKeyService::class.java).flattenToString()
        return enabled.split(':').any { it == me }
    }

    private fun labelFor(packageName: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        "$packageName (not installed)"
    }

    private fun root(content: View) = LinearLayout(this).apply {
        setBackgroundColor(0xFF101216.toInt())
        addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 18f
        setOnClickListener { onClick() }
    }

    private fun spacer(heightDp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
