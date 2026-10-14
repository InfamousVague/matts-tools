package com.mattssoftware.xray

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.mattssoftware.tools.Ink
import kotlin.math.roundToInt

/**
 * What X-ray draws over the app in front, as two windows of its accessibility service (the kind drawn above the
 * status bar, and trusted, so touches pass through them to the app): "X-ray outlines", the boxes of the last reading
 * outlined and coloured by depth over the whole screen, and "X-ray controls", a small bar that can be moved, with the
 * card of the picked box. The bar never covers the whole screen (the brief: "a small bar of controls that never covers
 * the whole screen"), stays out of the status bar's strip and clear of the camera, and Close on it always works.
 *
 * With Select off, the outlines take no touch at all and the app works as if X-ray were not there; with Select on, a
 * tap picks the innermost box there, and the window stops short of the navigation bar so home and back still work.
 */
class Overlay(private val service: XrayAccess) {
    private val windows = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = Prefs(service)
    private fun dp(v: Float) = (v * service.resources.displayMetrics.density).roundToInt()

    private var outlines: Outlines? = null
    private var outlineAt: WindowManager.LayoutParams? = null
    private var controls: LinearLayout? = null
    private var controlsAt: WindowManager.LayoutParams? = null
    private var bar: LinearLayout? = null
    private var card: TextView? = null
    private var words: TextView? = null
    private var selectButton: TextView? = null
    private var onlyButton: TextView? = null

    private var capture: Capture? = null
    private var peel = XrayTree.Peel()
    private var select = false
    /** Where the bar's top left corner is on the screen. */
    private var barX = 0
    private var barY = 0
    /** Being dragged by its grip: it follows the finger, and is put back clear of the camera when let go. */
    private var dragging = false

    // ---- the screen ----

    private class Screen(val wide: Int, val tall: Int, val top: Int, val bottom: Int, val keepOut: List<XrayTree.Area>)

    private fun screen(): Screen {
        val wide: Int; val tall: Int; var top = 0; var bottom = 0
        val cuts = ArrayList<Rect>()
        if (Build.VERSION.SDK_INT >= 30) {
            val m = windows.maximumWindowMetrics
            wide = m.bounds.width(); tall = m.bounds.height()
            top = m.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
            bottom = m.windowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom
            m.windowInsets.displayCutout?.boundingRects?.let { cuts += it }
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") windows.defaultDisplay.getRealMetrics(m)
            wide = m.widthPixels; tall = m.heightPixels
            fun dimen(name: String) = service.resources.getIdentifier(name, "dimen", "android").takeIf { it != 0 }?.let { service.resources.getDimensionPixelSize(it) } ?: 0
            top = dimen("status_bar_height"); bottom = dimen("navigation_bar_height")
            @Suppress("DEPRECATION") windows.defaultDisplay.cutout?.boundingRects?.let { cuts += it }
        }
        val keepOut = cuts.filter { !it.isEmpty }.map { XrayTree.Area(it.left, it.top, it.right, it.bottom) }.toMutableList()
        // The Dock beside the camera reaches past the cut-out by its frame (dock/Dock.kt `gap`), and on a screen
        // whose cut-out is taller than the hole it is pushed down further: the bar keeps clear of that frame too.
        runCatching {
            com.mattssoftware.dock.DockService.hole(service, com.mattssoftware.dock.Prefs(service), wide, tall)?.let { h ->
                val gap = dp(6f) + 2
                val y = (h.centerY() - (h.height() + gap * 2) / 2).coerceAtLeast(0)
                keepOut += XrayTree.Area(h.left - gap, y, h.right + gap, y + h.height() + gap * 2)
            }
        }
        return Screen(wide, tall, top, bottom, keepOut)
    }

    // ---- the windows ----

    private fun params(title: String, flags: Int, width: Int, height: Int) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        this.title = title
        gravity = Gravity.TOP or Gravity.LEFT
        if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
    }

    fun show() {
        if (outlines != null) return
        val s = screen()
        val lines = Outlines(service).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        val linesAt = params("X-ray outlines", WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        lines.onTap = { x, y -> picked(x, y) }
        runCatching { windows.addView(lines, linesAt) }.onFailure { return }
        outlines = lines; outlineAt = linesAt

        val root = makeControls()
        val at = params("X-ray controls", WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, width(s), ViewGroup.LayoutParams.WRAP_CONTENT)
        // Where it was left, or the bottom middle above the navigation bar; put back on the screen by the same rule.
        barX = prefs.barX ?: ((s.wide - width(s)) / 2)
        barY = prefs.barY ?: s.tall
        at.x = barX; at.y = barY
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
        runCatching { windows.addView(root, at) }.onFailure { hide(); return }
        controls = root; controlsAt = at
        refresh()
    }

    fun hide() {
        outlines?.let { v -> runCatching { windows.removeViewImmediate(v) } }
        controls?.let { v -> runCatching { windows.removeViewImmediate(v) } }
        outlines = null; outlineAt = null; controls = null; controlsAt = null; bar = null; card = null; words = null
        capture = null
    }

    /** The screen turned or changed size: the bar is put back on it, and the outlines' window fitted again. */
    fun layout() {
        val s = screen()
        controlsAt?.let { it.width = width(s) }
        touchable()
        place()
    }

    private fun width(s: Screen) = minOf(dp(280f), s.wide - dp(16f))

    /** A new reading: drawn, with Peel kept within its depths and the card for the box still picked. */
    fun draw(c: Capture) {
        capture = c
        val deepest = deepest()
        peel.depth?.let { if (it > deepest) peel = XrayTree.Peel(deepest, peel.only) }
        refresh()
    }

    private fun deepest() = capture?.reading?.boxes?.maxOfOrNull { it.depth } ?: 0

    private fun refresh() {
        val c = capture
        val deepest = deepest()
        outlines?.let { v ->
            v.boxes = c?.reading?.boxes?.filter { it.visible && peel.shows(it) } ?: emptyList()
            v.picked = c?.picked?.let { c.reading.boxes.getOrNull(it) }
            v.invalidate()
        }
        words?.text = if (c == null) "Reading the app in front..." else peel.words(deepest)
        style(selectButton, select)
        style(onlyButton, peel.only)
        val box = c?.picked?.let { c.reading.boxes.getOrNull(it) }
        card?.let { v ->
            if (box == null) v.visibility = View.GONE
            else { v.text = XrayTree.card(box, c.kinds).joinToString("\n"); v.visibility = View.VISIBLE }
        }
        place()
    }

    private fun picked(x: Int, y: Int) {
        val c = capture ?: return
        val box = XrayTree.pick(c.reading.boxes.filter { peel.shows(it) }, x, y)
        c.picked = box?.i
        refresh()
    }

    private fun touchable() {
        val v = outlines ?: return
        val at = outlineAt ?: return
        if (select) {
            // Stops short of the navigation bar, so home and back still reach the system while Select is on.
            val s = screen()
            at.flags = at.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            at.height = s.tall - s.bottom
        } else {
            at.flags = at.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            at.height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        runCatching { windows.updateViewLayout(v, at) }
    }

    /**
     * Puts the controls where the bar was left: the card above the bar, or below it when the bar is in the upper half,
     * and the whole kept on the screen, out of the status bar's strip and clear of the camera (XrayTree.kt `keep`).
     */
    private fun place() {
        if (dragging) return
        val root = controls ?: return
        val at = controlsAt ?: return
        val b = bar ?: return
        val k = card ?: return
        val s = screen()
        val barH = b.height.takeIf { it > 0 } ?: return
        val above = barY + barH / 2 > s.tall / 2
        val want = if (above) 0 else 1
        if (root.indexOfChild(k) != want) { root.removeView(k); root.addView(k, if (above) 0 else 1, cardParams(above)) ; return }
        val cardH = if (k.visibility == View.VISIBLE) k.height + dp(8f) else 0
        val w = at.width.takeIf { it > 0 } ?: width(s)
        val top = if (above) barY - cardH else barY
        val (x, y) = XrayTree.keep(XrayTree.Area(barX, top, barX + w, top + barH + cardH), s.wide, s.tall, s.top, s.bottom + dp(8f), s.keepOut, dp(8f))
        barX = x; barY = if (above) y + cardH else y
        if (at.x != x || at.y != y) {
            at.x = x; at.y = y
            runCatching { windows.updateViewLayout(root, at) }
        }
    }

    // ---- the bar ----

    private fun text(words: String, color: Int = Ink.PAPER, medium: Boolean = false) = TextView(service).apply {
        text = words; textSize = 14f; setTextColor(color); includeFontPadding = false
        typeface = if (medium) Ink.medium(service) else Ink.regular(service)
    }

    private fun button(words: String, press: () -> Unit) = text(words, medium = true).apply {
        gravity = Gravity.CENTER; minHeight = dp(40f); maxLines = 1; setPadding(dp(4f), 0, dp(4f), 0)
        background = GradientDrawable().apply { cornerRadius = dp(12f).toFloat(); setColor(0); setStroke(dp(1f), 0x33FFFFFF) }
        setOnClickListener { press() }
    }

    private fun style(v: TextView?, on: Boolean) {
        v ?: return
        v.setTextColor(if (on) Ink.INK else Ink.PAPER)
        (v.background as? GradientDrawable)?.setColor(if (on) Ink.PAPER else 0)
    }

    private fun row() = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun cell() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4f) }
    private fun cardParams(above: Boolean) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        if (above) bottomMargin = dp(8f) else topMargin = dp(8f)
    }
    private fun body(radius: Float) = GradientDrawable().apply { cornerRadius = radius; setColor(Ink.CARD); setStroke(1, Ink.LINE) }

    private fun makeControls(): LinearLayout {
        val root = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        val b = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL; background = body(dp(18f).toFloat()); setPadding(dp(8f), dp(8f), dp(10f), dp(10f))
        }
        val first = row()
        val grip = Grip(service, dp(1.5f).toFloat())
        drag(grip)
        first.addView(grip, LinearLayout.LayoutParams(dp(20f), dp(40f)))
        selectButton = button("Select") { select = !select; touchable(); refresh() }
        first.addView(selectButton, cell())
        // The compositor's surfaces come with the next part of X-ray; until then the button is there and says no.
        first.addView(button("Layers") {}.apply { isEnabled = false; alpha = 0.4f }, cell())
        first.addView(button("Open") { service.open() }, cell())
        first.addView(button("Close") { service.stop(false) }, cell())
        b.addView(first)

        val second = row().apply { setPadding(0, dp(6f), 0, 0) }
        second.addView(text("Peel", Ink.QUIET).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4f), 0, 0, 0) }, LinearLayout.LayoutParams(dp(20f) + dp(40f), dp(40f)))
        second.addView(button("Fewer") { peel = peel.fewer(deepest()); refresh() }, cell())
        second.addView(button("More") { peel = peel.more(deepest()); refresh() }, cell())
        onlyButton = button("Only") { peel = peel.flip(deepest()); refresh() }
        second.addView(onlyButton, cell())
        b.addView(second)

        words = text("Reading the app in front...", Ink.QUIET).apply { setPadding(dp(4f), dp(10f), dp(4f), dp(2f)) }
        b.addView(words)

        card = text("").apply {
            background = body(dp(16f).toFloat()); setPadding(dp(14f), dp(12f), dp(14f), dp(12f)); setLineSpacing(0f, 1.2f); maxLines = 12
            visibility = View.GONE
        }
        root.addView(card, cardParams(true))
        root.addView(b)
        bar = b
        return root
    }

    /** Dragged by its grip, the bar moves and stays where it is left; let go, it is put back clear of the camera. */
    private fun drag(grip: View) {
        var downX = 0f; var downY = 0f; var fromX = 0; var fromY = 0
        grip.setOnTouchListener { _, e ->
            val at = controlsAt ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dragging = true; downX = e.rawX; downY = e.rawY; fromX = at.x; fromY = at.y }
                MotionEvent.ACTION_MOVE -> {
                    at.x = fromX + (e.rawX - downX).roundToInt(); at.y = fromY + (e.rawY - downY).roundToInt()
                    controls?.let { runCatching { windows.updateViewLayout(it, at) } }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val cardH = card?.takeIf { it.visibility == View.VISIBLE && controls?.indexOfChild(it) == 0 }?.let { it.height + dp(8f) } ?: 0
                    barX = at.x; barY = at.y + cardH
                    dragging = false
                    place()
                    prefs.barX = barX; prefs.barY = barY
                }
            }
            true
        }
    }

    /** The grip: three short lines, drawn, so no character stands in for an icon. */
    private class Grip(context: Context, stroke: Float) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.QUIET; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND }
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val half = width * 0.22f; val step = height * 0.12f
            for (i in -1..1) canvas.drawLine(cx - half, cy + i * step, cx + half, cy + i * step, paint)
        }
    }

    /** The outlines: a dark halo under a coloured line round each box, the colour by depth, the picked box washed. */
    private class Outlines(context: Context) : View(context) {
        var boxes: List<XrayTree.Box> = emptyList()
        var picked: XrayTree.Box? = null
        var onTap: ((Int, Int) -> Unit)? = null
        private val density = context.resources.displayMetrics.density
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.5f * density; color = 0x8C000000.toInt() }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density }
        private val wash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val here = IntArray(2)

        override fun onDraw(canvas: Canvas) {
            // Boxes are in the screen's own places; the window may not start at the screen's corner.
            getLocationOnScreen(here)
            canvas.save()
            canvas.translate(-here[0].toFloat(), -here[1].toFloat())
            picked?.let { p ->
                wash.color = (COLOURS[p.depth % COLOURS.size] and 0x00FFFFFF) or 0x40000000
                canvas.drawRect(p.area.l.toFloat(), p.area.t.toFloat(), p.area.r.toFloat(), p.area.b.toFloat(), wash)
            }
            val inset = line.strokeWidth / 2f
            for (b in boxes) {
                val l = b.area.l + inset; val t = b.area.t + inset; val r = b.area.r - inset; val bt = b.area.b - inset
                if (r <= l || bt <= t) continue
                canvas.drawRect(l, t, r, bt, halo)
                line.color = COLOURS[b.depth % COLOURS.size]
                canvas.drawRect(l, t, r, bt, line)
            }
            canvas.restore()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) onTap?.invoke(event.rawX.roundToInt(), event.rawY.roundToInt())
            return true
        }

        companion object {
            // Six that hold up over light and dark apps alike.
            val COLOURS = intArrayOf(0xFF4FC3F7.toInt(), 0xFFFFB74D.toInt(), 0xFF81C784.toInt(), 0xFFF06292.toInt(), 0xFFBA68C8.toInt(), 0xFFFFF176.toInt())
        }
    }
}
