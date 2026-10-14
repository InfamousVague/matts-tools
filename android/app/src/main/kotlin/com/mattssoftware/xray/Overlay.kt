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
 * What X-ray draws over the app in front, as three windows of its accessibility service (the kind drawn above the
 * status bar, and trusted, so touches pass through them to the app): "X-ray outlines", the boxes of the last reading
 * outlined and coloured by depth over the whole screen, "X-ray controls", a small bar that can be moved, and "X-ray
 * card", what the picked box is, beside the bar. The card takes no touch, so with Select off only the bar itself
 * stands between a finger and the app. The bar never covers the whole screen (the brief: "a small bar of controls
 * that never covers the whole screen"), stays out of the status bar's strip and clear of the camera, and Close on it
 * always works.
 *
 * With Select off, the outlines take no touch at all and the app works as if X-ray were not there; with Select on, a
 * tap picks the innermost box there, and the window stops short of the status bar and the navigation bar, on whatever
 * edge it stands, so the shade, home, back and recents still work.
 * With Layers on, the compositor's surfaces for the app (Layers.kt) are outlined instead, coloured by their order, and
 * a tap picks the topmost of them.
 */
class Overlay(private val service: XrayAccess) {
    private val windows = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = Prefs(service)
    private fun dp(v: Float) = (v * service.resources.displayMetrics.density).roundToInt()

    private var outlines: Outlines? = null
    private var outlineAt: WindowManager.LayoutParams? = null
    private var controls: LinearLayout? = null
    private var controlsAt: WindowManager.LayoutParams? = null
    private var card: TextView? = null
    private var cardAt: WindowManager.LayoutParams? = null
    private var words: TextView? = null
    private var selectButton: TextView? = null
    private var onlyButton: TextView? = null
    private var layersButton: TextView? = null
    private var peelButtons: List<TextView> = emptyList()

    private var capture: Capture? = null
    private var peel = XrayTree.Peel()
    private var select = false
    /** The layer picked with Layers on, by its id. */
    private var pickedLayer: Int? = null
    /** Where the bar's top left corner is on the screen. */
    private var barX = 0
    private var barY = 0
    /** Being dragged by its grip: it follows the finger, and is put back clear of the camera when let go. */
    private var dragging = false

    // ---- the screen ----

    /**
     * The screen, and the phone's own bars along its four edges ([top] the status bar, the navigation bar at [bottom],
     * or at [left] or [right] where three buttons stand at the side of a turned phone).
     */
    private class Screen(val wide: Int, val tall: Int, val top: Int, val bottom: Int, val left: Int, val right: Int, val keepOut: List<XrayTree.Area>)

    private fun screen(): Screen {
        val wide: Int; val tall: Int; var top = 0; var bottom = 0; var left = 0; var right = 0
        val cuts = ArrayList<Rect>()
        if (Build.VERSION.SDK_INT >= 30) {
            val m = windows.maximumWindowMetrics
            wide = m.bounds.width(); tall = m.bounds.height()
            val bars = m.windowInsets.getInsets(WindowInsets.Type.systemBars())
            top = bars.top; bottom = bars.bottom; left = bars.left; right = bars.right
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
        // A navigation bar at a side edge: the bar keeps off it as off the camera.
        if (left > 0) keepOut += XrayTree.Area(0, 0, left, tall)
        if (right > 0) keepOut += XrayTree.Area(wide - right, 0, wide, tall)
        // The Dock beside the camera reaches past the cut-out by its frame (dock/Dock.kt `gap`), and on a screen
        // whose cut-out is taller than the hole it is pushed down further: the bar keeps clear of that frame too.
        runCatching {
            com.mattssoftware.dock.DockService.hole(service, com.mattssoftware.dock.Prefs(service), wide, tall)?.let { h ->
                val gap = dp(6f) + 2
                val y = (h.centerY() - (h.height() + gap * 2) / 2).coerceAtLeast(0)
                keepOut += XrayTree.Area(h.left - gap, y, h.right + gap, y + h.height() + gap * 2)
            }
        }
        return Screen(wide, tall, top, bottom, left, right, keepOut)
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

        // The card takes no touch: a tap on it goes to what is under it, the app or, with Select on, the outlines.
        val k = makeCard()
        val kAt = params("X-ray card", WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, width(s), ViewGroup.LayoutParams.WRAP_CONTENT)
        kAt.x = barX; kAt.y = barY
        k.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
        runCatching { windows.addView(k, kAt) }.onFailure { hide(); return }
        card = k; cardAt = kAt
        refresh()
    }

    fun hide() {
        outlines?.let { v -> runCatching { windows.removeViewImmediate(v) } }
        controls?.let { v -> runCatching { windows.removeViewImmediate(v) } }
        card?.let { v -> runCatching { windows.removeViewImmediate(v) } }
        outlines = null; outlineAt = null; controls = null; controlsAt = null; card = null; cardAt = null; words = null
        capture = null
    }

    /** The screen turned or changed size: the bar is put back on it, and the outlines' window fitted again. */
    fun layout() {
        val s = screen()
        controlsAt?.let { it.width = width(s) }
        cardAt?.let { at -> at.width = width(s); card?.let { v -> runCatching { windows.updateViewLayout(v, at) } } }
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
        if (service.layersOn) { refreshLayers(); return }
        val c = capture
        val deepest = deepest()
        // A box Peel no longer draws is no longer picked: its card would stand for nothing on the screen.
        c?.let { it.picked = XrayTree.shown(it.picked, it.reading.boxes, peel) }
        outlines?.let { v ->
            v.marks = c?.reading?.boxes?.filter { it.visible && peel.shows(it) }?.map { it.area to it.depth } ?: emptyList()
            v.picked = c?.picked?.let { c.reading.boxes.getOrNull(it) }?.let { it.area to it.depth }
            v.invalidate()
        }
        words?.text = if (c == null) "Reading the app in front..." else peel.words(deepest)
        style(selectButton, select)
        style(onlyButton, peel.only)
        style(layersButton, false)
        peelButtons.forEach { it.isEnabled = true; it.alpha = 1f }
        val box = c?.picked?.let { c.reading.boxes.getOrNull(it) }
        card?.let { v ->
            if (box == null) v.visibility = View.GONE
            else { v.text = XrayTree.card(box, c.kinds, c.surface).joinToString("\n"); v.visibility = View.VISIBLE }
        }
        place()
    }

    /** Layers on: the app's surfaces with a place, coloured by their order; the card says the one picked, or why there are none. */
    private fun refreshLayers() {
        val read = capture?.layers
        val layers = read?.layers.orEmpty()
        val placed = layers.filter { it.place != null }
        val picked = layers.firstOrNull { it.id == pickedLayer }
        outlines?.let { v ->
            v.marks = placed.map { it.place!! to it.order }
            v.picked = picked?.place?.let { it to picked.order }
            v.invalidate()
        }
        words?.text = when {
            read == null -> "Reading the compositor..."
            read.why != null -> "Layers not available"
            else -> "Layers: ${layers.size} of this app, ${placed.size} with a place"
        }
        style(selectButton, select)
        style(onlyButton, false)
        style(layersButton, true)
        // Peel steps through the tree's depths, which are not drawn while Layers is on.
        peelButtons.forEach { it.isEnabled = false; it.alpha = 0.4f }
        card?.let { v ->
            val lines = when {
                read?.why != null -> listOf(read.why, Layers.command(service))
                picked != null -> LayerParse.card(picked, layers.size)
                else -> null
            }
            if (lines == null) v.visibility = View.GONE else { v.text = lines.joinToString("\n"); v.visibility = View.VISIBLE }
        }
        place()
    }

    private fun picked(x: Int, y: Int) {
        val c = capture ?: return
        if (service.layersOn) { pickedLayer = c.layers?.layers?.let { LayerParse.pick(it, x, y) }?.id; refresh(); return }
        val box = XrayTree.pick(c.reading.boxes.filter { peel.shows(it) }, x, y)
        c.picked = box?.i
        refresh()
    }

    private fun touchable() {
        val v = outlines ?: return
        val at = outlineAt ?: return
        if (select) {
            // Stops short of the phone's bars on every edge, so the shade, home, back and recents still reach the system
            // while Select is on, wherever the navigation bar stands.
            val s = screen()
            at.flags = at.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            at.x = s.left; at.y = s.top
            at.width = s.wide - s.left - s.right
            at.height = s.tall - s.top - s.bottom
        } else {
            at.flags = at.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            at.x = 0; at.y = 0
            at.width = ViewGroup.LayoutParams.MATCH_PARENT
            at.height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        runCatching { windows.updateViewLayout(v, at) }
    }

    /**
     * Puts the controls where the bar was left: the card above the bar, or below it when the bar is in the upper half,
     * and the two together kept on the screen, out of the status bar's strip and clear of the camera (XrayTree.kt `keep`).
     */
    private fun place() {
        if (dragging) return
        val root = controls ?: return
        val at = controlsAt ?: return
        val k = card ?: return
        val s = screen()
        val barH = root.height.takeIf { it > 0 } ?: return
        val above = barY + barH / 2 > s.tall / 2
        val cardH = if (k.visibility == View.VISIBLE && k.height > 0) k.height + dp(8f) else 0
        val w = at.width.takeIf { it > 0 } ?: width(s)
        val top = if (above) barY - cardH else barY
        val (x, y) = XrayTree.keep(XrayTree.Area(barX, top, barX + w, top + barH + cardH), s.wide, s.tall, s.top, s.bottom + dp(8f), s.keepOut, dp(8f))
        barX = x; barY = if (above) y + cardH else y
        if (at.x != barX || at.y != barY) {
            at.x = barX; at.y = barY
            runCatching { windows.updateViewLayout(root, at) }
        }
        stick(above, barH)
    }

    /** The card beside the bar: above it, or below it, 8 dp away. */
    private fun stick(above: Boolean, barH: Int) {
        val at = controlsAt ?: return
        val k = card ?: return
        val kAt = cardAt ?: return
        val x = at.x
        val y = if (above) at.y - dp(8f) - k.height else at.y + barH + dp(8f)
        if (kAt.x != x || kAt.y != y) {
            kAt.x = x; kAt.y = y
            runCatching { windows.updateViewLayout(k, kAt) }
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
    private fun body(radius: Float) = GradientDrawable().apply { cornerRadius = radius; setColor(Ink.CARD); setStroke(1, Ink.LINE) }

    private fun makeControls(): LinearLayout {
        val b = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL; background = body(dp(18f).toFloat()); setPadding(dp(8f), dp(8f), dp(10f), dp(10f))
        }
        val first = row()
        val grip = Grip(service, dp(1.5f).toFloat())
        drag(grip)
        first.addView(grip, LinearLayout.LayoutParams(dp(20f), dp(40f)))
        selectButton = button("Select") { select = !select; touchable(); refresh() }
        first.addView(selectButton, cell())
        layersButton = button("Layers") { service.layers(!service.layersOn); pickedLayer = null; refresh() }
        first.addView(layersButton, cell())
        first.addView(button("Open") { service.open() }, cell())
        first.addView(button("Close") { service.stop(false) }, cell())
        b.addView(first)

        val second = row().apply { setPadding(0, dp(6f), 0, 0) }
        second.addView(text("Peel", Ink.QUIET).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4f), 0, 0, 0) }, LinearLayout.LayoutParams(dp(20f) + dp(40f), dp(40f)))
        val fewer = button("Fewer") { peel = peel.fewer(deepest()); refresh() }
        val more = button("More") { peel = peel.more(deepest()); refresh() }
        onlyButton = button("Only") { peel = peel.flip(deepest()); refresh() }
        peelButtons = listOf(fewer, more, onlyButton!!)
        peelButtons.forEach { second.addView(it, cell()) }
        b.addView(second)

        words = text("Reading the app in front...", Ink.QUIET).apply { setPadding(dp(4f), dp(10f), dp(4f), dp(2f)) }
        b.addView(words)
        return b
    }

    private fun makeCard() = text("").apply {
        background = body(dp(16f).toFloat()); setPadding(dp(14f), dp(12f), dp(14f), dp(12f)); setLineSpacing(0f, 1.2f); maxLines = 12
        visibility = View.GONE
    }

    /** Dragged by its grip, the bar moves and stays where it is left; let go, it is put back clear of the camera. */
    private fun drag(grip: View) {
        var downX = 0f; var downY = 0f; var fromX = 0; var fromY = 0; var tall = 0
        grip.setOnTouchListener { _, e ->
            val at = controlsAt ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dragging = true; downX = e.rawX; downY = e.rawY; fromX = at.x; fromY = at.y; tall = screen().tall }
                MotionEvent.ACTION_MOVE -> {
                    at.x = fromX + (e.rawX - downX).roundToInt(); at.y = fromY + (e.rawY - downY).roundToInt()
                    controls?.let { runCatching { windows.updateViewLayout(it, at) } }
                    controls?.let { stick(at.y + it.height / 2 > tall / 2, it.height) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    barX = at.x; barY = at.y
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

    /**
     * The outlines: a dark halo under a coloured line round each place, the colour by its depth (or a layer's order),
     * the picked one washed.
     */
    private class Outlines(context: Context) : View(context) {
        var marks: List<Pair<XrayTree.Area, Int>> = emptyList()
        var picked: Pair<XrayTree.Area, Int>? = null
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
            picked?.let { (a, depth) ->
                wash.color = (COLOURS[depth % COLOURS.size] and 0x00FFFFFF) or 0x40000000
                canvas.drawRect(a.l.toFloat(), a.t.toFloat(), a.r.toFloat(), a.b.toFloat(), wash)
            }
            val inset = line.strokeWidth / 2f
            for ((a, depth) in marks) {
                val l = a.l + inset; val t = a.t + inset; val r = a.r - inset; val bt = a.b - inset
                if (r <= l || bt <= t) continue
                canvas.drawRect(l, t, r, bt, halo)
                line.color = COLOURS[depth % COLOURS.size]
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
