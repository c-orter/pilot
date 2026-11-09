package app.overlaylauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Things the overlay asks the service to do for an app, beyond opening it. */
enum class AppAction { FORCE_CLOSE, APP_SETTINGS, UNINSTALL }

/**
 * Full-screen overlay with a D-pad navigable grid of launchable apps.
 * Enter opens an app, holding Enter opens a menu (open, move, force close, app settings, uninstall).
 */
class AppsOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onLaunch: (String) -> Unit,
    private val onAppAction: (AppAction, AppEntry) -> Unit,
    private val onTvHome: () -> Unit,
    private val onSettings: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = AppsAdapter()

    private var root: Root? = null
    private var panel: ViewGroup? = null
    private var grid: GridView? = null
    private var hint: TextView? = null
    private var menu: View? = null
    private var menuPosition = 0
    private var apps = mutableListOf<AppEntry>()

    // Move mode: movingIndex is the moved app's current slot, or -1 when not moving.
    private var movingIndex = -1
    private var moveStart = 0
    private var moveOrigin: List<AppEntry> = emptyList()

    private var longPressFired = false
    private var swallowSelectUp = false

    private val openMenu = Runnable {
        longPressFired = true
        val position = grid?.selectedItemPosition ?: return@Runnable
        apps.getOrNull(position)?.let { showMenu(it, position) }
    }

    val isShowing: Boolean get() = root != null

    fun show() {
        if (root != null) return
        // The home app is already one short press away, so it stays out of the list.
        val homePackage = Prefs.homePackage(context)
        val order = Prefs.appOrder(context)
        apps = loadLaunchableApps(context, excludeSelf = true)
            .filter { it.packageName != homePackage }
            .sortedBy { app -> order.indexOf(app.packageName).takeIf { it >= 0 } ?: Int.MAX_VALUE }
            .toMutableList()

        val view = Root(context, ::handleKey).apply {
            // Nearly opaque: the app underneath must not compete with the labels.
            setBackgroundColor(0xF5101216.toInt())
            addView(buildPanel())
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        windowManager.addView(view, params)
        root = view
        grid?.apply {
            requestFocus()
            setSelection(0)
        }
        playEntrance(view)
    }

    fun dismiss() {
        val view = root ?: return
        handler.removeCallbacks(openMenu)
        root = null
        menu = null
        movingIndex = -1
        swallowSelectUp = false
        windowManager.removeView(view)
    }

    /** Scrim fades in, header drops in, then the apps rise into place one after another. */
    private fun playEntrance(view: View) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(180).start()

        panel?.getChildAt(0)?.apply {
            alpha = 0f
            translationY = -dp(16).toFloat()
            animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        }

        // Children only exist after the first layout, so set their start state just before the first draw.
        val gridView = grid ?: return
        gridView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (gridView.childCount == 0) return true
                gridView.viewTreeObserver.removeOnPreDrawListener(this)
                for (i in 0 until gridView.childCount) {
                    gridView.getChildAt(i).apply {
                        alpha = 0f
                        translationY = dp(28).toFloat()
                        scaleX = 0.9f
                        scaleY = 0.9f
                        animate()
                            .alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                            .setStartDelay(60L + i * 28L)
                            .setDuration(300)
                            .setInterpolator(OvershootInterpolator(0.8f))
                            .start()
                    }
                }
                return true
            }
        })
    }

    private fun handleKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (!down) {
                    when {
                        movingIndex >= 0 -> endMove(commit = false)
                        menu != null -> closeMenu()
                        else -> dismiss()
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> return handleSelectKey(event)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            -> if (movingIndex >= 0) {
                if (down) moveBy(event.keyCode)
                return true
            }
        }
        return false
    }

    /** Enter on the grid: a tap opens the app, a hold (timed, like Home) opens the menu. */
    private fun handleSelectKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        // The release that ends the long press must not activate whatever is now focused.
        if (!down && swallowSelectUp) {
            swallowSelectUp = false
            return true
        }
        if (movingIndex >= 0) {
            if (!down) endMove(commit = true)
            return true
        }
        val grid = grid
        if (menu != null || grid == null || !grid.hasFocus()) return false
        if (down) {
            if (event.repeatCount == 0) {
                longPressFired = false
                handler.postDelayed(openMenu, LONG_PRESS_MS)
            }
        } else {
            handler.removeCallbacks(openMenu)
            if (!longPressFired) apps.getOrNull(grid.selectedItemPosition)?.let { onLaunch(it.packageName) }
        }
        return true
    }

    private fun startMove(position: Int) {
        movingIndex = position
        moveStart = position
        moveOrigin = apps.toList()
        hint?.visibility = View.VISIBLE
        adapter.notifyDataSetChanged()
        grid?.setSelection(position)
    }

    private fun moveBy(keyCode: Int) {
        val from = movingIndex
        val to = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> from - 1
            KeyEvent.KEYCODE_DPAD_RIGHT -> from + 1
            KeyEvent.KEYCODE_DPAD_UP -> from - GRID_COLUMNS
            else -> from + GRID_COLUMNS
        }.coerceIn(0, apps.size - 1)
        if (to == from) return
        apps.add(to, apps.removeAt(from))
        movingIndex = to
        adapter.notifyDataSetChanged()
        grid?.setSelection(to)
    }

    private fun endMove(commit: Boolean) {
        val finalIndex = if (commit) {
            Prefs.setAppOrder(context, apps.map { it.packageName })
            movingIndex
        } else {
            apps = moveOrigin.toMutableList()
            moveStart
        }
        movingIndex = -1
        hint?.visibility = View.GONE
        adapter.notifyDataSetChanged()
        grid?.setSelection(finalIndex)
    }

    private fun showMenu(app: AppEntry, position: Int) {
        val rootView = root ?: return
        menuPosition = position
        swallowSelectUp = true

        val entries = listOf(
            "Open" to { onLaunch(app.packageName) },
            "Move" to { startMove(position) },
            "Force close" to { onAppAction(AppAction.FORCE_CLOSE, app) },
            "App settings" to { onAppAction(AppAction.APP_SETTINGS, app) },
            "Uninstall" to { onAppAction(AppAction.UNINSTALL, app) },
        )
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = GradientDrawable().apply {
                setColor(0xFF1D2330.toInt())
                cornerRadius = dp(16).toFloat()
            }
            addView(
                TextView(context).apply {
                    text = app.label
                    setTextColor(Color.WHITE)
                    textSize = 22f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(16), 0, dp(16), dp(12))
                },
            )
            entries.forEach { (label, action) ->
                addView(
                    TextView(context).apply {
                        text = label
                        setTextColor(if (label == "Uninstall") 0xFFFF8A8A.toInt() else Color.WHITE)
                        textSize = 20f
                        isFocusable = true
                        isClickable = true
                        background = focusBackground()
                        setPadding(dp(16), dp(12), dp(16), dp(12))
                        setOnClickListener {
                            closeMenu()
                            action()
                        }
                    },
                )
            }
        }
        val layer = FrameLayout(context).apply {
            setBackgroundColor(0xB3000000.toInt())
            addView(card, FrameLayout.LayoutParams(dp(380), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        rootView.addView(layer)
        menu = layer
        // Keep D-pad focus inside the menu while it is open.
        panel?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        card.getChildAt(1).requestFocus()
    }

    private fun closeMenu() {
        val layer = menu ?: return
        root?.removeView(layer)
        menu = null
        panel?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        grid?.apply {
            requestFocus()
            setSelection(menuPosition)
        }
    }

    private fun buildPanel(): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(32), dp(48), dp(32))
        }
        column.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(24))
                addView(
                    TextView(context).apply {
                        text = "Apps"
                        setTextColor(Color.WHITE)
                        textSize = 28f
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(iconButton(R.drawable.ic_tv_home, "TV Home", onTvHome))
                addView(iconButton(R.drawable.ic_settings, "Settings", onSettings))
            },
        )
        val gridView = GridView(context).apply {
            numColumns = GRID_COLUMNS
            horizontalSpacing = dp(16)
            verticalSpacing = dp(16)
            isFocusable = true
            selector = focusRing()
            adapter = this@AppsOverlay.adapter
        }
        grid = gridView
        column.addView(gridView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        hint = TextView(context).apply {
            text = "Arrow keys move the app   ·   Enter to drop   ·   Back to cancel"
            setTextColor(ACCENT)
            textSize = 16f
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
        }
        column.addView(hint)
        panel = column
        return column
    }

    private fun iconButton(drawableRes: Int, description: String, onClick: () -> Unit) =
        ImageView(context).apply {
            setImageResource(drawableRes)
            contentDescription = description
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isFocusable = true
            isClickable = true
            background = focusBackground()
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginStart = dp(8) }
        }

    private fun focusRing() = GradientDrawable().apply {
        setColor(0x40FFFFFF)
        setStroke(dp(3), Color.WHITE)
        cornerRadius = dp(12).toFloat()
    }

    private fun focusBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focusRing())
    }

    private inner class AppsAdapter : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(position: Int) = apps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val app = apps[position]
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(8), dp(16), dp(8), dp(16))
                if (position == movingIndex) {
                    background = GradientDrawable().apply {
                        setColor(0x665B8CFF)
                        cornerRadius = dp(12).toFloat()
                    }
                }
                addView(
                    ImageView(context).apply { setImageDrawable(app.icon) },
                    LinearLayout.LayoutParams(dp(72), dp(72)),
                )
                addView(
                    TextView(context).apply {
                        text = app.label
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER
                        setPadding(0, dp(8), 0, 0)
                    },
                )
            }
        }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    /** Routes every key through the overlay first so Back, long-press and move mode work. */
    private class Root(context: Context, private val onKey: (KeyEvent) -> Boolean) : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean = onKey(event) || super.dispatchKeyEvent(event)
    }

    private companion object {
        const val GRID_COLUMNS = 6
        const val LONG_PRESS_MS = 500L
        const val ACCENT = 0xFF5B8CFF.toInt()
    }
}
