package com.mattssoftware.frames

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * The readout: the rate now, the run's jank and the last frames as bars, drawn in a corner over the app being
 * measured. Touches go through it, which Android allows only of a window no more than four fifths opaque.
 */
class Hud(context: Context) {
    private val base: Context = if (Build.VERSION.SDK_INT >= 30) {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    } else context
    private val windows = base.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: HudView? = null
    private var corner = -1

    fun set(now: ProfilerService.Live, at: Int) {
        if (view != null && at != corner) hide()
        if (view == null) {
            val made = HudView(base)
            val d = base.resources.displayMetrics.density
            val params = WindowManager.LayoutParams(
                (176 * d).toInt(), (88 * d).toInt(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = (if (at == 0 || at == 3) Gravity.START else Gravity.END) or (if (at < 2) Gravity.TOP else Gravity.BOTTOM)
                x = (10 * d).toInt(); y = (48 * d).toInt(); alpha = 0.8f; title = "Frames readout"
            }
            runCatching { windows.addView(made, params) }
                .onSuccess { view = made; corner = at; Trace.add("readout shown") }
                .onFailure { Trace.add("readout could not be shown: ${it.message}") }
        }
        view?.show(now)
    }

    fun hide() { view?.let { runCatching { windows.removeView(it) } }; view = null }
}

class HudView(context: Context) : View(context) {
    private var now: ProfilerService.Live? = null
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5F5F5.toInt(); textSize = 17 * d; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); fontFeatureSettings = "tnum" }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB0B0B0.toInt(); textSize = 10.5f * d; fontFeatureSettings = "tnum" }

    fun show(live: ProfilerService.Live) { now = live; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val live = now ?: return
        val w = width.toFloat(); val h = height.toFloat()
        fill.color = 0xFF0D0D0D.toInt()
        canvas.drawRoundRect(0f, 0f, w, h, 14 * d, 14 * d, fill)
        val pad = 10 * d
        canvas.drawText("${live.fps} fps", pad, 24 * d, big)
        val jank = "${"%.1f".format(live.jankPct)}% jank"
        canvas.drawText(jank, w - pad - small.measureText(jank), 16 * d, small)
        val heat = "${live.hz.toInt()} Hz · ${"%.0f".format(live.tempC)}°"
        canvas.drawText(heat, w - pad - small.measureText(heat), 29 * d, small)
        val under = "${live.screen.take(18)} · worst ${"%.0f".format(live.worstMs)} ms"
        canvas.drawText(under, pad, 41 * d, small)

        // The last frames, oldest on the left: a bar's height is its time, red when it missed a refresh.
        val period = 1000f / live.hz.coerceAtLeast(1f)
        val top = 48 * d; val bottom = h - 8 * d; val room = bottom - top
        val bars = live.recent; if (bars.isEmpty()) return
        val each = (w - pad * 2) / 120f
        val tallest = period * 4f
        for (i in bars.indices) {
            val v = bars[i]
            fill.color = if (v > period * 1.5f) 0xFFFF7A7A.toInt() else 0xFFF5F5F5.toInt()
            val x = pad + (120 - bars.size + i) * each
            canvas.drawRect(x, bottom - room * (v / tallest).coerceIn(0.06f, 1f), x + each * 0.8f, bottom, fill)
        }
        fill.color = 0x66F5F5F5
        val line = bottom - room * (period / tallest)
        canvas.drawRect(pad, line, w - pad, line + d * 0.75f, fill)
    }
}

/** A row of bars: counts or times, with words under them or a line across at [mark]; a bar past [hot] is red. */
class Bars(context: Context, private val values: FloatArray, private val labels: List<String>?, private val mark: Float?, private val hot: Float?) : View(context) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val words = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9A9A9A.toInt(); textSize = 10.5f * d; textAlign = Paint.Align.CENTER; fontFeatureSettings = "tnum" }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) = setMeasuredDimension(MeasureSpec.getSize(widthSpec), ((if (labels == null) 96 else 132) * d).toInt())

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return
        val w = width.toFloat()
        val foot = if (labels == null) 0f else 34 * d
        val head = if (labels == null) 4 * d else 16 * d
        val bottom = height - foot; val room = bottom - head
        val tallest = maxOf(values.max(), (mark ?: 0f) * 1.3f, 0.001f)
        val each = w / values.size
        val gap = if (each > 6 * d) 3 * d else if (each > 2 * d) d * 0.5f else 0f
        for (i in values.indices) {
            val v = values[i]
            fill.color = if (hot != null && v > hot) 0xFFFF7A7A.toInt() else 0xFFF5F5F5.toInt()
            val x = i * each
            val tall = if (v <= 0f) 0f else (room * v / tallest).coerceAtLeast(d)
            canvas.drawRoundRect(x + gap, bottom - tall, x + each - gap, bottom, minOf(4 * d, each / 3), minOf(4 * d, each / 3), fill)
            if (labels != null) {
                canvas.drawText(v.toInt().toString(), x + each / 2, bottom - tall - 4 * d, words)
                canvas.drawText(labels[i], x + each / 2, bottom + 16 * d, words)
            }
        }
        if (mark != null) { fill.color = 0x80FF7A7A.toInt(); val y = bottom - room * mark / tallest; canvas.drawRect(0f, y, w, y + d, fill) }
    }
}
