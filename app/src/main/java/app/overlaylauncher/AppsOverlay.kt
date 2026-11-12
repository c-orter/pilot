package app.overlaylauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.AdapterView
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
    private val onSettings: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = AppsAdapter()

    private var root: Root? = null
    private var panel: ViewGroup? = null
    private var grid: GridView? = null
    private var nameView: TextView? = null
    private var hintView: TextView? = null
    private var menu: View? = null
    private var menuPosition = 0
    private var apps = mutableListOf<AppEntry>()

    /** The position whose tile currently wears the focus look, or -1 before the first selection. */
    private var lookPosition = -1

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

    private val idleDismiss = Runnable { dismiss() }

    private val resetHint = Runnable {
        if (movingIndex >= 0) showHint(MOVE_HINT, ACCENT) else showHint(DEFAULT_HINT, HINT_GRAY)
    }

    /** Shows [message] in the hint line for a moment, so an action has visible feedback. */
    fun flash(message: String) {
        showHint(message, ACCENT)
        handler.removeCallbacks(resetHint)
        handler.postDelayed(resetHint, FLASH_MS)
    }

    private fun showHint(text: String, color: Int) {
        hintView?.apply {
            this.text = text
            setTextColor(color)
        }
    }

    private fun resetIdle() {
        handler.removeCallbacks(idleDismiss)
        handler.postDelayed(idleDismiss, IDLE_MS)
    }

    fun show() {
        if (root != null) return
        lookPosition = -1
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
        nameView?.text = apps.firstOrNull()?.label
        resetIdle()
        playEntrance(view)
    }

    fun dismiss() {
        val view = root ?: return
        handler.removeCallbacksAndMessages(null)
        root = null
        menu = null
        movingIndex = -1
        swallowSelectUp = false
        // The window may already be gone (screen off, another app); that is fine.
        runCatching { windowManager.removeView(view) }
    }

    /** The scrim fades in and the tiles bloom outward from the focused one. */
    private fun playEntrance(view: View) {
        if (!ValueAnimator.areAnimatorsEnabled()) return

        ValueAnimator.ofInt(0, 255).apply {
            duration = SCRIM_MS
            addUpdateListener { view.background.alpha = it.animatedValue as Int }
            start()
        }
        panel?.apply {
            getChildAt(0).fadeIn(HEADER_MS)
            getChildAt(childCount - 1).fadeIn(FOOTER_MS, FOOTER_DELAY_MS)
        }

        // The tiles only exist after the first layout, so set their start state just before the first draw.
        val gridView = grid ?: return
        gridView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (gridView.childCount == 0) return true
                gridView.viewTreeObserver.removeOnPreDrawListener(this)
                lookPosition = 0
                for (i in 0 until gridView.childCount) bloom(gridView.getChildAt(i), i)
                return true
            }
        })
    }

    private fun View.fadeIn(duration: Long, delay: Long = 0) {
        alpha = 0f
        animate().alpha(1f).setStartDelay(delay).setDuration(duration).start()
    }

    /**
     * Grows tile [index] in to its resting look (the first tile is focused), starting later
     * the further it is from the focused first tile.
     */
    private fun bloom(tile: View, index: Int) {
        setLook(tile, index, focused = index == 0, animate = false)
        val scale = tile.scaleX
        val alpha = tile.alpha
        tile.alpha = 0f
        tile.scaleX = TILE_FROM_SCALE
        tile.scaleY = TILE_FROM_SCALE
        tile.animate().alpha(alpha).scaleX(scale).scaleY(scale)
            .setStartDelay((index % GRID_COLUMNS + index / GRID_COLUMNS) * TILE_STEP_MS)
            .setDuration(TILE_MS).setInterpolator(EASE_OUT_EXPO).start()
    }

    private fun handleKey(event: KeyEvent): Boolean {
        resetIdle()
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
        showHint(MOVE_HINT, ACCENT)
        adapter.notifyDataSetChanged()
        grid?.setSelection(position)
        refreshLooksSoon()
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
        refreshLooksSoon()
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
        showHint(DEFAULT_HINT, HINT_GRAY)
        adapter.notifyDataSetChanged()
        grid?.setSelection(finalIndex)
        refreshLooksSoon()
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
            // The focused tile grows past its cell, into this panel's own padding, which is clipped by default.
            clipChildren = false
            clipToPadding = false
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
                addView(iconButton(R.drawable.ic_settings, "Settings", onSettings))
            },
        )
        val gridView = GridView(context).apply {
            numColumns = GRID_COLUMNS
            horizontalSpacing = dp(16)
            verticalSpacing = dp(16)
            isFocusable = true
            // A grid view also clips its own drawing to its bounds, which would slice the edge of a grown tile.
            clipChildren = false
            clipToPadding = false
            // The grid remembers its selection, but only wears the focus look while it actually has focus.
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    // The grid picks its new selection after this callback, so read it afterwards.
                    post { showFocus(selectedItemPosition) }
                } else if (menu == null) {
                    // The long-press menu takes focus too, and its app should stay highlighted.
                    showFocus(-1)
                }
            }
            // Each tile draws its own focus ring, so that it can scale with the tile.
            selector = ColorDrawable(Color.TRANSPARENT)
            adapter = this@AppsOverlay.adapter
            // Banners often carry no readable name, so spell out what is focused.
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    showFocus(position)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        grid = gridView
        column.addView(gridView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        nameView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 24f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        hintView = TextView(context).apply {
            text = DEFAULT_HINT
            setTextColor(HINT_GRAY)
            textSize = 16f
        }
        column.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, 0)
                addView(nameView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(hintView)
            },
        )
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
            // The name line says what is focused, so it names the icon while one is.
            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) nameView?.text = description }
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginStart = dp(8) }
        }

    /** Moves the focus look to [position] (-1 for none) and, for a real tile, names it in the footer. */
    private fun showFocus(position: Int) {
        if (position >= 0) nameView?.text = apps.getOrNull(position)?.label
        if (position != lookPosition) {
            lookPosition = position
            refreshLooks(animate = true)
        }
    }

    private fun refreshLooksSoon() {
        grid?.post { refreshLooks(animate = false) }
    }

    private fun refreshLooks(animate: Boolean) {
        val gridView = grid ?: return
        for (i in 0 until gridView.childCount) {
            val position = gridView.firstVisiblePosition + i
            setLook(gridView.getChildAt(i), position, position == lookPosition, animate)
        }
    }

    /**
     * The focused tile grows and wears a two-tone ring; the others dim a little. A plain white ring
     * disappears on a light banner, so the ring is white outside with a dark line just inside it,
     * and the dimming makes the focused banner stand out even where its colours match its surroundings.
     */
    private fun setLook(tile: View, position: Int, focused: Boolean, animate: Boolean) {
        tile.foreground = when {
            position == movingIndex -> moveForeground()
            focused -> focusForeground()
            else -> null
        }
        val scale = if (focused) FOCUS_SCALE else 1f
        val alpha = if (focused) 1f else DIM_ALPHA
        if (animate) {
            tile.animate().scaleX(scale).scaleY(scale).alpha(alpha)
                .setStartDelay(0).setDuration(FOCUS_MS).setInterpolator(EASE_OUT_EXPO).start()
        } else {
            tile.scaleX = scale
            tile.scaleY = scale
            tile.alpha = alpha
        }
    }

    private fun ring(color: Int, widthDp: Int, radiusDp: Int) = GradientDrawable().apply {
        setStroke(dp(widthDp), color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun focusForeground() = LayerDrawable(arrayOf(ring(Color.WHITE, 3, 12), ring(0xE6101216.toInt(), 2, 9)))
        .apply { setLayerInset(1, dp(3), dp(3), dp(3), dp(3)) }

    /** The tile being dragged in move mode: an accent outline and tint. */
    private fun moveForeground() = ring(ACCENT, 4, 12).apply { setColor(0x335B8CFF) }

    private fun focusRing() = ring(Color.WHITE, 3, 12).apply { setColor(0x40FFFFFF) }

    private fun focusBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focusRing())
    }

    private inner class AppsAdapter : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(position: Int) = apps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val app = apps[position]
            return Tile(context).apply {
                contentDescription = app.label
                outlineProvider = tileOutline
                clipToOutline = true
                if (app.banner != null) {
                    addView(
                        ImageView(context).apply {
                            setImageDrawable(app.banner)
                            scaleType = ImageView.ScaleType.CENTER_CROP
                        },
                    )
                } else {
                    setBackgroundColor(0xFF1D2330.toInt())
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            addView(
                                ImageView(context).apply { setImageDrawable(app.icon) },
                                LinearLayout.LayoutParams(dp(48), dp(48)),
                            )
                            addView(
                                TextView(context).apply {
                                    text = app.label
                                    setTextColor(Color.WHITE)
                                    textSize = 14f
                                    maxLines = 1
                                    ellipsize = TextUtils.TruncateAt.END
                                    gravity = Gravity.CENTER
                                    setPadding(dp(8), dp(6), dp(8), 0)
                                },
                            )
                        },
                    )
                }
                setLook(this, position, position == lookPosition, animate = false)
            }
        }
    }

    private val tileOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
        }
    }

    /** A 16:9 app tile, the shape of a TV launcher banner. */
    private class Tile(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY))
        }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    /** Routes every key through the overlay first so Back, long-press and move mode work. */
    private class Root(context: Context, private val onKey: (KeyEvent) -> Boolean) : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean = onKey(event) || super.dispatchKeyEvent(event)
    }

    private companion object {
        const val GRID_COLUMNS = 5
        const val LONG_PRESS_MS = 500L
        const val IDLE_MS = 60_000L

        // Entrance: tiles bloom outward from the focused one, a diagonal step every 28 ms (338 ms in all).
        const val SCRIM_MS = 140L
        const val HEADER_MS = 160L
        const val FOOTER_MS = 140L
        const val FOOTER_DELAY_MS = 200L
        const val TILE_MS = 170L
        const val TILE_STEP_MS = 28L
        const val TILE_FROM_SCALE = 0.88f
        val EASE_OUT_EXPO = PathInterpolator(0.16f, 1f, 0.3f, 1f)

        // Focus: the focused tile grows, the others dim.
        const val FOCUS_SCALE = 1.06f
        const val DIM_ALPHA = 0.78f
        const val FOCUS_MS = 120L

        const val FLASH_MS = 2500L
        const val ACCENT = 0xFF5B8CFF.toInt()
        const val HINT_GRAY = 0xFF9AA0A6.toInt()
        const val DEFAULT_HINT = "OK open   ·   hold OK for options"
        const val MOVE_HINT = "Arrow keys move the app   ·   Enter to drop   ·   Back to cancel"
    }
}
