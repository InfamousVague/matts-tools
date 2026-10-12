package com.mattssoftware.dock

import com.mattssoftware.tools.R

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

/** What the person chose, and where they left the dock on each shape of screen. */
class Prefs(context: Context) {
    private val kept = context.getSharedPreferences("dock", Context.MODE_PRIVATE)
    private fun flag(name: String, usual: Boolean) = kept.getBoolean(name, usual)
    private fun set(name: String, v: Boolean) = kept.edit().putBoolean(name, v).apply()
    var on: Boolean get() = flag("on", true); set(v) = set("on", v)
    var heat: Boolean get() = flag("heat", true); set(v) = set("heat", v)
    var awake: Boolean get() = flag("awake", true); set(v) = set("awake", v)
    var frames: Boolean get() = flag("frames", true); set(v) = set("frames", v)
    /** One part under another, where it floats. */
    var vertical: Boolean get() = flag("vertical", false); set(v) = set("vertical", v)
    /** The screen behind it blurred, where it floats and the phone can. */
    var blur: Boolean get() = flag("blur", true); set(v) = set("blur", v)

    /** On a screen [wide] by [tall]: "float", or "island" to sit against the camera. A Fold has two screens. */
    fun mode(wide: Int, tall: Int): String = kept.getString("mode:$wide:$tall", "float") ?: "float"
    fun mode(wide: Int, tall: Int, v: String) = kept.edit().putString("mode:$wide:$tall", v).apply()
    /** Where the dock's parts stand about the camera on this screen ("left", "right", "both"); null where it was never chosen. */
    fun side(wide: Int, tall: Int): String? = kept.getString("side:$wide:$tall", null)
    fun side(wide: Int, tall: Int, v: String) = kept.edit().putString("side:$wide:$tall", v).apply()
    /** How far the camera's hole was moved from where it is kept, and how much larger it was made: x, y, size, in pixels. */
    fun nudge(wide: Int, tall: Int): Triple<Int, Int, Int> = Triple(kept.getInt("holeX:$wide:$tall", 0), kept.getInt("holeY:$wide:$tall", 0), kept.getInt("holeGrow:$wide:$tall", 0))
    fun nudge(wide: Int, tall: Int, dx: Int, dy: Int, grow: Int) = kept.edit().putInt("holeX:$wide:$tall", dx).putInt("holeY:$wide:$tall", dy).putInt("holeGrow:$wide:$tall", grow).apply()

    /** Folded down to the mark alone. */
    var collapsed: Boolean get() = flag("collapsed", false); set(v) = set("collapsed", v)
    var fahrenheit: Boolean
        get() = flag("fahrenheit", Locale.getDefault().country in setOf("US", "BS", "KY", "LR", "PW", "FM", "MH"))
        set(v) = set("fahrenheit", v)

    /** Where the dock was left on a screen [wide] by [tall], or null where it never was. A Fold has two. */
    fun place(wide: Int, tall: Int): Pair<Int, Int>? =
        if (kept.contains("x:$wide:$tall")) kept.getInt("x:$wide:$tall", 0) to kept.getInt("y:$wide:$tall", 0) else null
    fun place(wide: Int, tall: Int, x: Int, y: Int) = kept.edit().putInt("x:$wide:$tall", x).putInt("y:$wide:$tall", y).apply()
}

/** What the dock shows, and where each comes from: the phone itself, and the app's other tools. */
object Sources {
    /** The battery's temperature: the one reading Android gives every app. */
    fun celsius(context: Context): Float? {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE) null else tenths / 10f
    }

    class Awake(val on: Boolean, val since: Long)
    /** Amphetamine's session: whether one is on, and since when on the clock that counts while asleep. */
    fun awake(context: Context): Awake = com.mattssoftware.amphetamine.Prefs(context).let { Awake(it.on, it.since) }
    fun toggleAwake(context: Context) {
        com.mattssoftware.amphetamine.AwakeService.set(context, !com.mattssoftware.amphetamine.Prefs(context).on)
    }

    class Rate(val fps: Int, val jankPct: Float, val app: String)
    /** What Frames is measuring now, or null while it measures nothing. */
    fun frames(context: Context): Rate? = com.mattssoftware.frames.ProfilerService.live
        ?.takeIf { com.mattssoftware.frames.ProfilerService.running }?.let { Rate(it.fps, it.jankPct, it.app) }

    /** Days, hours, minutes and seconds run together from the largest that is not nothing: "42s", "5m12s", "1h30m05s". */
    fun span(ms: Long): String {
        val total = ms.coerceAtLeast(0L) / 1000L
        val d = total / 86_400L; val h = total % 86_400L / 3_600L; val m = total % 3_600L / 60L; val s = total % 60L
        return when {
            d > 0 -> "${d}d${h}h%02dm".format(m)
            h > 0 -> "${h}h%02dm%02ds".format(m, s)
            m > 0 -> "${m}m%02ds".format(s)
            else -> "${s}s"
        }
    }
}

/**
 * Where a phone's front camera is on its screen, for the phones that do not say. Android tells an app the cut-out of
 * the screen it is on, and that is asked first ([DockService.hole]); a Fold's inner screen reports none, so it is
 * kept here. Pixels of the screen as it stands upright: left, top, right, bottom of the hole itself.
 *
 * To add a phone: its model as `Build.MODEL` begins, the screen's size, and the hole. `measured` is false for a
 * place worked out from pictures and not yet lined up on the phone (the dock's page has arrows to line it up; what
 * they come to is in the app's preferences as `hole:<wide>:<tall>`).
 */
object Phones {
    /** [side]: where the dock's parts stand about the camera on this screen: "left", "right", or "both" with the camera in the middle. */
    class Hole(val model: String, val wide: Int, val tall: Int, val rect: Rect, val measured: Boolean, val side: String = "left")

    val all = listOf(
        // Galaxy Z Fold 8, shut: the cover screen. From the phone's own cut-out (2026-10-12): 70 px wide about the
        // middle (589 to 659), ending 104 px down.
        // The camera is in the middle there, so the dock is about it, its parts shared out to either side (Matt,
        // 2026-10-12: "center the dock and put icons on the left and right automatically to disperse things").
        Hole("SM-F971", 1248, 1972, Rect(589, 34, 659, 104), true, side = "both"),
        // Galaxy Z Fold 8, open: the inner screen, which reports no cut-out. A first place (1806 to 1866) put the
        // frame's end through the middle of the hole (Matt, 2026-10-12: "it's only covering half of the camera's
        // punch"), so the hole's middle is about 1882 across. Not yet lined up exactly, nor up and down.
        Hole("SM-F971", 2448, 1848, Rect(1852, 32, 1912, 92), false),
    )

    fun hole(wide: Int, tall: Int): Hole? = all.firstOrNull { Build.MODEL.startsWith(it.model) && it.wide == wide && it.tall == tall }
}

/**
 * The dock: one small pill over whatever is on screen. In it, side by side or one under another, Matt's Software's
 * mark (a tap folds the dock down to it), the phone's temperature beside a flame, how long Amphetamine has kept the
 * screen on beside its pill (a tap switches it), and the rate Frames is measuring.
 *
 * It floats where it was dragged to, the screen behind it blurred; or, on a screen whose camera's place is known, it
 * sits against the camera with the hole inside its frame, as an island does.
 *
 * Its window is a dialog's, of the kind drawn over other apps: only a window of that sort can blur what is behind it
 * within its own rounded shape (`Window.setBackgroundBlurRadius`).
 */
class DockService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("dock").apply { start() }
    private val work by lazy { Handler(thread.looper) }
    private val prefs by lazy { Prefs(this) }
    private val base: Context by lazy {
        if (Build.VERSION.SDK_INT >= 30) {
            val display = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
            createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else this
    }
    private val windows by lazy { base.getSystemService(WINDOW_SERVICE) as WindowManager }
    private var dialog: Dialog? = null
    /** Beside the camera: the plain window's own place, and the window manager it was added through. */
    private var plain: WindowManager.LayoutParams? = null
    private var host: WindowManager? = null
    private var pill: LinearLayout? = null
    private val parts = LinkedHashMap<String, Pair<LinearLayout, TextView>>()
    private var brand: LinearLayout? = null
    private var chevron: ImageView? = null
    /** What each part last had to say, or null for nothing: kept so folding and unfolding need not ask again. */
    private var said: Map<String, String?> = emptyMap()
    private var island: Rect? = null
    /** Beside the camera with parts to both sides: the two halves, the camera's room between them. */
    private var halves: Pair<LinearLayout, LinearLayout>? = null
    /** True once the dock has been drawn with what it has to say: its filling up as it starts is not an opening. */
    private var settled = false
    private var x = 0; private var y = 0
    private fun dp(v: Float) = (v * base.resources.displayMetrics.density).roundToInt()

    private val tick = object : Runnable {
        override fun run() {
            val power = getSystemService(POWER_SERVICE) as PowerManager
            if (power.isInteractive) work.post { read() }
            main.postDelayed(this, 1_000L - System.currentTimeMillis() % 1_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel("dock", "Dock", NotificationManager.IMPORTANCE_MIN).apply { description = "Android asks for this while the dock is on screen."; setShowBadge(false) })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!prefs.on || !Settings.canDrawOverlays(this)) { hide(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        // Dated long ago, so that among the status bar's icons, newest first, it is the last.
        val note = Notification.Builder(this, "dock").setSmallIcon(R.drawable.ic_dock).setContentTitle("Matt's Software Dock")
            .setContentText("Showing. Tap to change what is in it.").setOngoing(true).setShowWhen(false)
            .setWhen(System.currentTimeMillis() - 43_200_000L).setContentIntent(open).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, note)
        // Made again each time it is asked for: its look may have been changed on the page.
        hide(); show()
        main.removeCallbacks(tick); main.post(tick)
        return START_STICKY
    }

    // ---- the pill ----

    private fun screen(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) windows.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        else base.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    private fun show() {
        if (pill != null) return
        val (wide, tall) = screen()
        val camera = if (prefs.mode(wide, tall) == "island") hole(base, prefs, wide, tall) else null
        island = camera
        // Beside the camera, and the accessibility service on: the window is that service's, of the kind drawn ABOVE
        // the status bar, which is the only kind a tap reaches there (the status bar takes every touch on its strip
        // from a window drawn over apps). Its views are made in the service's own context.
        val access = if (camera != null) DockAccess.instance else null
        val ctx: Context = access ?: base
        val down = prefs.vertical && camera == null
        // The frame's clearance round the camera. (Matt, 2026-10-12: "just one more pixel of padding around the camera".)
        val gap = dp(6f) + 2
        val side = if (camera == null) "left" else side(prefs, wide, tall, camera)
        val toLeft = side != "right"
        val both = side == "both"

        val row = LinearLayout(ctx).apply {
            orientation = if (down) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (down) Gravity.START else Gravity.CENTER_VERTICAL
            if (camera == null) setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
            // Beside the camera: room at the right end for the hole itself, and no less tall than the hole and its frame.
            else {
                if (both) setPadding(0, 0, 0, 0)
                else if (toLeft) setPadding(dp(12f), 0, camera.width() + gap * 2, 0) else setPadding(camera.width() + gap * 2, 0, dp(12f), 0)
                minimumHeight = camera.height() + gap * 2
            }
        }
        // Matt's Software's mark and a chevron: a tap folds the dock down to the mark, and another opens it again.
        val arrow = ImageView(ctx).apply { setImageResource(R.drawable.ic_chevron); setColorFilter(0xB3F5F5F5.toInt()) }
        val mark = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2f), 0, dp(2f), 0)
            val picture = ctx.getDrawable(R.drawable.brand)!!
            addView(ImageView(ctx).apply { setImageDrawable(picture) }, LinearLayout.LayoutParams(dp(15f) * picture.intrinsicWidth / picture.intrinsicHeight, dp(15f)))
            addView(arrow, LinearLayout.LayoutParams(dp(15f), dp(15f)).apply { marginStart = dp(3f) })
        }
        // Beside the camera there is no mark and no folding: only what it has to say.
        if (camera == null) { brand = mark; chevron = arrow; row.addView(mark) }
        // To both sides: a half on each side of the camera's room, kept as wide as each other so the camera is the middle.
        val shares = if (both && camera != null) {
            fun half() = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(6f), 0, dp(6f), 0) }
            val left = half(); val right = half()
            row.addView(left, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            row.addView(View(ctx), LinearLayout.LayoutParams(camera.width() + gap * 2, camera.height() + gap * 2))
            row.addView(right, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            left to right
        } else null
        halves = shares
        for ((name, icon) in listOf("heat" to R.drawable.ic_flame, "awake" to R.drawable.ic_pill, "frames" to R.drawable.ic_frames)) {
            val words = TextView(ctx).apply {
                textSize = 13f; setTextColor(0xFFF5F5F5.toInt()); typeface = com.mattssoftware.tools.Ink.medium(context)
                fontFeatureSettings = "tnum"; includeFontPadding = false; setPadding(dp(4f), 0, 0, 0); maxLines = 1
            }
            val part = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
                if (down) setPadding(dp(2f), dp(8f), dp(2f), 0) else setPadding(dp(7f), 0, dp(7f), 0)
                addView(ImageView(ctx).apply { setImageResource(icon); setColorFilter(0xFFF5F5F5.toInt()) }, LinearLayout.LayoutParams(dp(15f), dp(15f)))
                addView(words)
            }
            parts[name] = part to words
            (shares?.first ?: row).addView(part)
        }

        val wanted = camera == null && prefs.blur
        val radius = if (down) dp(20f).toFloat() else dp(999f).toFloat()
        // Android's own blur behind a window, where the phone has it; a Samsung has it switched off and a blur of
        // its own instead, asked for on the view.
        val own = wanted && Build.VERSION.SDK_INT >= 31 && windows.isCrossWindowBlurEnabled
        val samsung = wanted && !own && samsungBlur(row, radius)
        val blurs = own || samsung
        // Beside the camera it is black, so the hole is lost in it; floating, a dark glass over the blur.
        val back = Glass(radius, if (camera != null) 0xFF000000.toInt() else if (blurs) 0x990D0D0D.toInt() else 0xE60D0D0D.toInt(), dp(1f).toFloat(), camera != null)
        var made: Dialog? = null
        if (camera == null) made = Dialog(base, android.R.style.Theme_Translucent_NoTitleBar).also { d ->
            d.setCancelable(false)
            d.setContentView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            d.window!!.apply {
                setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setBackgroundDrawable(back)
                setWindowAnimations(0)
                setElevation(0f)
                decorView.setPadding(0, 0, 0, 0)
                if (own) setBackgroundBlurRadius(dp(18f))
                setGravity(Gravity.TOP or Gravity.LEFT)
                attributes = attributes.apply {
                    title = "Matt's Software Dock"
                    if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
                }
            }
        }
        // Beside the camera: a plain window with the black body as the view's own background.
        val at = if (camera == null) null else WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            if (access != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            title = "Matt's Software Dock"
            gravity = Gravity.TOP or if (both) Gravity.LEFT else if (toLeft) Gravity.RIGHT else Gravity.LEFT
            if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
        }
        if (camera != null) row.background = back
        if (camera != null) {
            // Its end a frame's width past the hole, and its middle on the hole's: it grows away from the hole. To both
            // sides, its middle across is the hole's too.
            x = if (both) camera.centerX() - (camera.width() + gap * 2) / 2 else if (toLeft) wide - camera.right - gap else camera.left - gap
            row.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                val atY = (camera.centerY() - (bottom - top) / 2).coerceAtLeast(0)
                val atX = if (both) camera.centerX() - (right - left) / 2 else x
                if (atY != y || atX != x) { y = atY; x = atX; move() }
            }
            y = (camera.centerY() - (camera.height() + gap * 2) / 2).coerceAtLeast(0)
        } else {
            val kept = prefs.place(wide, tall)
            // The first time: under the status bar on the right, clear of a page's title. (Kept in from the edge as it is laid out.)
            x = kept?.first ?: wide; y = kept?.second ?: dp(56f)
            // Wider or narrower than it was (opened, folded, a longer time): it keeps to the side of the screen it is
            // on, growing away from the nearer edge, and never past either.
            row.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, _, oldRight, _ ->
                val now = right - left; val before = oldRight - oldLeft
                var toX = x
                if (settled && before > 0 && now != before && x + before / 2 > wide / 2) toX = x + before - now
                toX = toX.coerceIn(0, (wide - now).coerceAtLeast(0))
                val toY = y.coerceIn(0, (tall - (bottom - top)).coerceAtLeast(0))
                if (toX != x || toY != y) { x = toX; y = toY; move(); prefs.place(wide, tall, x, y) }
            }
            drag(row)
        }
        if (camera != null) row.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_UP) tapped(e.x, e.y); true }
        dialog = made; pill = row; plain = at
        host = if (at == null) null else (ctx.getSystemService(WINDOW_SERVICE) as WindowManager)
        move()
        runCatching { if (made != null) made.show() else host!!.addView(row, at) }.onFailure { dialog = null; pill = null; plain = null; host = null }
        draw()
    }

    /**
     * One UI's blur behind a view (`View.semSetBlurInfo`), which is not in Android's own kit and so is reached by
     * name. False where the phone has none, or will not give it.
     */
    private fun samsungBlur(view: View, radius: Float): Boolean = runCatching {
        val builder = Class.forName("android.view.SemBlurInfo\$Builder")
        val made = builder.getConstructor(Int::class.javaPrimitiveType).newInstance(0)
        builder.getMethod("setRadius", Int::class.javaPrimitiveType).invoke(made, 120)
        runCatching { builder.getMethod("setBackgroundCornerRadius", Float::class.javaPrimitiveType).invoke(made, radius) }
        val info = builder.getMethod("build").invoke(made)
        View::class.java.getMethod("semSetBlurInfo", Class.forName("android.view.SemBlurInfo")).invoke(view, info)
        true
    }.onFailure { android.util.Log.i("Dock", "no Samsung blur: $it") }.onSuccess { android.util.Log.i("Dock", "Samsung blur asked for") }.getOrDefault(false)

    private fun move() {
        dialog?.window?.let { w -> w.attributes = w.attributes.apply { this.x = this@DockService.x; this.y = this@DockService.y } }
        plain?.let { at -> at.x = x; at.y = y; pill?.takeIf { it.isAttachedToWindow }?.let { row -> runCatching { host?.updateViewLayout(row, at) } } }
    }

    private fun hide() {
        dialog?.let { runCatching { it.dismiss() } }
        if (plain != null) pill?.let { row -> runCatching { host?.removeViewImmediate(row) } }
        plain = null; host = null
        dialog = null; pill = null; parts.clear(); brand = null; chevron = null; halves = null; settled = false
    }

    /** Dragged, it moves and stays; tapped, the part under the finger answers. */
    private fun drag(row: LinearLayout) {
        val slop = ViewConfiguration.get(base).scaledTouchSlop
        var downX = 0f; var downY = 0f; var fromX = 0; var fromY = 0; var moved = false
        row.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; fromX = x; fromY = y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && hypot(e.rawX - downX, e.rawY - downY) > slop) moved = true
                    if (moved) {
                        val (wide, tall) = screen()
                        x = (fromX + (e.rawX - downX).toInt()).coerceIn(0, (wide - view.width).coerceAtLeast(0))
                        y = (fromY + (e.rawY - downY).toInt()).coerceIn(0, (tall - view.height).coerceAtLeast(0))
                        move()
                    }
                }
                MotionEvent.ACTION_UP -> if (moved) screen().let { (wide, tall) -> prefs.place(wide, tall, x, y) } else tapped(e.x, e.y)
            }
            true
        }
    }

    private fun tapped(x: Float, y: Float) {
        fun under(v: View): Boolean {
            // A part may be inside one of the two halves: its place in the dock is its half's and its own.
            val within = (v.parent as? View)?.takeIf { it !== pill }
            val left = v.left + (within?.left ?: 0)
            return v.visibility == View.VISIBLE && x >= left && x <= left + v.width && y >= 0 && y <= (pill?.height ?: 0)
        }
        if (brand?.let { under(it) } == true) { prefs.collapsed = !prefs.collapsed; draw(); return }
        when (parts.entries.firstOrNull { under(it.value.first) }?.key) {
            "awake" -> work.post { Sources.toggleAwake(this); main.postDelayed({ work.post { read() } }, 400L) }
            "frames" -> open(com.mattssoftware.frames.MainActivity::class.java)
            "heat" -> open(com.mattssoftware.tools.ToastyActivity::class.java)
        }
    }

    private fun open(page: Class<*>) { runCatching { startActivity(Intent(this, page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

    // ---- what it says ----

    /** Off the main thread: the other apps are asked across processes. */
    private fun read() {
        val heat = if (prefs.heat) Sources.celsius(this)?.let { c -> if (prefs.fahrenheit) "${(c * 9f / 5f + 32f).roundToInt()}°F" else "${c.roundToInt()}°C" } else null
        val awake = if (prefs.awake) Sources.awake(this) else null
        val rate = if (prefs.frames) Sources.frames(this) else null
        val words = mapOf(
            "heat" to heat,
            "awake" to awake?.let { if (it.on) Sources.span(SystemClock.elapsedRealtime() - it.since) else "off" },
            "frames" to rate?.let { "${it.fps} fps" },
            "awake:off" to if (awake?.on == false) "off" else null,
        )
        main.post { said = words; draw(); pill?.post { pill?.post { settled = true } } }
    }

    /** On the main thread: each part with what it last had to say, or, folded, the mark alone with its chevron turned. */
    private fun draw() {
        val folded = prefs.collapsed && island == null
        val down = pill?.orientation == LinearLayout.VERTICAL
        chevron?.rotation = (if (down) 90f else 0f) + if (folded) 180f else 0f
        for ((name, part) in parts) {
            val say = said[name]
            part.first.visibility = if (say == null || folded) View.GONE else View.VISIBLE
            if (say != null && part.second.text.toString() != say) part.second.text = say
            if (name == "awake") part.first.alpha = if (said["awake:off"] != null) 0.5f else 1f
        }
        halves?.let { share(it.first, it.second) }
    }

    /**
     * To both sides of the camera: the parts that have something to say, in their order, each given to whichever
     * side is the narrower so far, and the two sides then made as wide as each other.
     */
    private fun share(left: LinearLayout, right: LinearLayout) {
        val free = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val toLeft = ArrayList<View>(); val toRight = ArrayList<View>()
        var leftWide = 0; var rightWide = 0
        for ((_, part) in parts) {
            val view = part.first
            if (view.visibility != View.VISIBLE) continue
            view.measure(free, free)
            if (leftWide <= rightWide) { toLeft.add(view); leftWide += view.measuredWidth } else { toRight.add(view); rightWide += view.measuredWidth }
        }
        fun now(group: LinearLayout) = (0 until group.childCount).map { group.getChildAt(it) }.filter { it.visibility == View.VISIBLE }
        if (now(left) != toLeft || now(right) != toRight) {
            for ((_, part) in parts) (part.first.parent as? ViewGroup)?.removeView(part.first)
            toLeft.forEach { left.addView(it) }; toRight.forEach { right.addView(it) }
            // Those with nothing to say wait in the left half, unseen.
            for ((_, part) in parts) if (part.first.parent == null) left.addView(part.first)
        }
        val wide = maxOf(leftWide, rightWide) + left.paddingLeft + left.paddingRight
        if (left.minimumWidth != wide) { left.minimumWidth = wide; right.minimumWidth = wide }
    }

    /** The Fold opened or shut, or the phone turned: made again for that screen, with its place and its look there. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.postDelayed({ if (pill != null) { hide(); show() } }, 350L)
    }

    override fun onDestroy() { main.removeCallbacksAndMessages(null); hide(); thread.quitSafely(); super.onDestroy() }

    companion object {
        fun apply(context: Context) {
            val intent = Intent(context, DockService::class.java)
            if (Prefs(context).on && Settings.canDrawOverlays(context)) context.startForegroundService(intent) else context.stopService(intent)
        }

        /**
         * Where the dock's parts stand about [camera] on this screen: what was chosen on the page; or else what is
         * kept for the phone; or else the camera's left, unless it is so near the left edge that there is no room.
         */
        fun side(prefs: Prefs, wide: Int, tall: Int, camera: Rect): String =
            prefs.side(wide, tall) ?: Phones.hole(wide, tall)?.side ?: if (camera.left <= wide * 3 / 10) "right" else "left"

        /**
         * The camera's hole on a screen [wide] by [tall], upright: what the phone says of its own cut-out, or where
         * it says nothing what is kept for the phone ([Phones]); then moved and sized by the page's arrows.
         */
        fun hole(context: Context, prefs: Prefs, wide: Int, tall: Int): Rect? {
            val known = Phones.hole(wide, tall)?.rect ?: run {
                if (Build.VERSION.SDK_INT < 30) return null
                val windows = context.getSystemService(WINDOW_SERVICE) as WindowManager
                val cut = windows.maximumWindowMetrics.windowInsets.displayCutout?.boundingRects?.firstOrNull { it.top <= 0 && it.width() < wide / 3 } ?: return null
                // The cut-out runs from the screen's top edge to under the hole: the hole is its lower end, as tall as wide.
                Rect(cut.left, (cut.bottom - cut.width()).coerceAtLeast(0), cut.right, cut.bottom)
            }
            val (dx, dy, grow) = prefs.nudge(wide, tall)
            return Rect(known.left + dx - grow, known.top + dy - grow, known.right + dx + grow, known.bottom + dy + grow)
        }
    }
}

/**
 * The dock's body: a dark fill in a rounded shape with a border as glass has one, a hairline that catches the light
 * along the top and nearly goes out toward the bottom, and a second, fainter line of light just inside the top edge.
 * [quiet] is the plainer border of the dock beside the camera, where it should read as part of the screen's edge.
 */
class Glass(private val radius: Float, private val fill: Int, private val line: Float, private val quiet: Boolean) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val box = android.graphics.RectF()

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        val round = radius.coerceAtMost(minOf(b.width(), b.height()) / 2f)
        box.set(b)
        paint.shader = null; paint.style = android.graphics.Paint.Style.FILL; paint.color = fill
        canvas.drawRoundRect(box, round, round, paint)
        // The rim: bright at the top, nearly gone at the bottom.
        val half = line / 2f
        box.set(b.left + half, b.top + half, b.right - half, b.bottom - half)
        paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = line
        paint.shader = android.graphics.LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            if (quiet) intArrayOf(0x24FFFFFF, 0x12FFFFFF, 0x0AFFFFFF) else intArrayOf(0x66FFFFFF, 0x24FFFFFF, 0x0FFFFFFF),
            floatArrayOf(0f, 0.45f, 1f), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRoundRect(box, round - half, round - half, paint)
        if (!quiet) {
            // The light inside the top edge, gone a third of the way down.
            box.set(b.left + line * 1.5f, b.top + line * 1.5f, b.right - line * 1.5f, b.bottom - line * 1.5f)
            paint.shader = android.graphics.LinearGradient(0f, b.top.toFloat(), 0f, b.top + b.height() * 0.38f,
                0x1FFFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRoundRect(box, round - line * 1.5f, round - line * 1.5f, paint)
        }
    }

    /** Its shape, which is what the blur behind the window is cut to: without it the blur is the window's whole square. */
    override fun getOutline(outline: android.graphics.Outline) {
        outline.setRoundRect(bounds, radius.coerceAtMost(minOf(bounds.width(), bounds.height()) / 2f))
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

class Boot : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { runCatching { DockService.apply(context) } }
}

/** The one screen: the dock on or off, and what is in it. */
class MainActivity : Activity() {
    private val prefs by lazy { Prefs(this) }
    private lateinit var page: LinearLayout
    private var asking = false
    private val paper = 0xFFF5F5F5.toInt(); private val quiet = 0xFF9A9A9A.toInt(); private val card = 0xFF171717.toInt()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(40)) }
        setContentView(android.widget.ScrollView(this).apply { fitsSystemWindows = true; addView(page) })
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        // Back from the setting it sent them to: it is on, as they asked.
        if (asking) { asking = false; if (Settings.canDrawOverlays(this)) prefs.on = true }
        DockService.apply(this)
        draw()
    }

    private fun change(set: () -> Unit) { set(); DockService.apply(this); draw() }

    private fun draw() {
        page.removeAllViews()
        page.addView(text("Dock", 28f, paper, true).apply { setPadding(dp(4), 0, 0, dp(12)) })
        val allowed = Settings.canDrawOverlays(this)
        row("Show the dock", if (prefs.on && allowed) "On" else "Off") {
            if (prefs.on && allowed) change { prefs.on = false }
            else if (allowed) change { prefs.on = true }
            else {
                // Asked for at once, and on when they come back.
                asking = true
                Toast.makeText(this, "Allow the dock to display over other apps", Toast.LENGTH_LONG).show()
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                    .onFailure { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
            }
        }
        page.addView(text("HOW IT LOOKS", 12f, quiet, false).apply { letterSpacing = 0.08f; setPadding(dp(4), dp(24), 0, dp(4)) })
        val (wide, tall) = if (Build.VERSION.SDK_INT >= 30) windowManager.maximumWindowMetrics.bounds.let { it.width() to it.height() } else resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        val camera = DockService.hole(this, prefs, wide, tall)
        val beside = prefs.mode(wide, tall) == "island" && camera != null
        if (camera == null) row("On this screen", "Floating") { Toast.makeText(this, "Where this screen's camera is, is not known", Toast.LENGTH_LONG).show() }
        else row("On this screen", if (beside) "Beside the camera" else "Floating") { change { prefs.mode(wide, tall, if (beside) "float" else "island") } }
        if (beside) {
            row("Taps beside the camera", if (DockAccess.instance != null) "On" else "Off: needs Accessibility") {
                Toast.makeText(this, "Switch on Matt's Tools under Installed apps", Toast.LENGTH_LONG).show()
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            val side = DockService.side(prefs, wide, tall, camera!!)
            row("Its parts", mapOf("left" to "Left of the camera", "right" to "Right of the camera", "both" to "Both sides, camera in the middle")[side] ?: side) {
                change { prefs.side(wide, tall, mapOf("left" to "right", "right" to "both", "both" to "left")[side] ?: "left") }
            }
            // Lining the frame up with the hole: the arrows move where the hole is taken to be, a pixel or five at a time.
            val (dx, dy, grow) = prefs.nudge(wide, tall)
            val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((label, step) in listOf("←" to Triple(-3, 0, 0), "→" to Triple(3, 0, 0), "↑" to Triple(0, -3, 0), "↓" to Triple(0, 3, 0), "−" to Triple(0, 0, -2), "+" to Triple(0, 0, 2)))
                keys.addView(text(label, 20f, paper, true).apply {
                    gravity = Gravity.CENTER; background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(card) }; setPadding(0, dp(12), 0, dp(12))
                    setOnClickListener { change { prefs.nudge(wide, tall, dx + step.first, dy + step.second, (grow + step.third).coerceAtLeast(-20)) } }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
            page.addView(keys, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            page.addView(text("Move the frame until the camera sits in the middle of its end. Camera taken to be at ${camera.left}, ${camera.top} to ${camera.right}, ${camera.bottom}" + (if (Phones.hole(wide, tall)?.measured == false && dx == 0 && dy == 0) ": a first guess for this screen, which does not say where its camera is." else "."), 13f, quiet, false).apply { setPadding(dp(4), dp(8), dp(4), 0) })
        } else {
            row("Direction", if (prefs.vertical) "Down" else "Across") { change { prefs.vertical = !prefs.vertical } }
            row("Blur what is behind it", if (prefs.blur) "On" else "Off") { change { prefs.blur = !prefs.blur } }
        }
        page.addView(text("IN IT", 12f, quiet, false).apply { letterSpacing = 0.08f; setPadding(dp(4), dp(24), 0, dp(4)) })
        row("Temperature", if (prefs.heat) "On" else "Off") { change { prefs.heat = !prefs.heat } }
        row("Degrees", if (prefs.fahrenheit) "Fahrenheit" else "Celsius") { change { prefs.fahrenheit = !prefs.fahrenheit } }
        row("Time awake, from Amphetamine", if (prefs.awake) "On" else "Off") { change { prefs.awake = !prefs.awake } }
        row("Rate, from Frames while it measures", if (prefs.frames) "On" else "Off") { change { prefs.frames = !prefs.frames } }
        page.addView(text("Floating, drag the dock to wherever you want it; it stays there. Each screen of a folding phone keeps its own place and its own look, so set the other one with the phone the other way. A tap on Matt's Software's mark folds it down to the mark, and another opens it. A tap on the pill switches Amphetamine on or off. A tap on the flame opens this page.\n\nThe temperature is the battery's, the one reading Android gives every app.", 13f, quiet, false).apply { setPadding(dp(4), dp(16), dp(4), 0) })
    }

    private fun text(words: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = words; textSize = size; setTextColor(color); if (bold) typeface = com.mattssoftware.tools.Ink.bold(context); setLineSpacing(0f, 1.2f)
    }

    private fun row(head: String, value: String, press: () -> Unit) {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(card) }; setPadding(dp(18), dp(18), dp(18), dp(18)); setOnClickListener { press() }
        }
        line.addView(text(head, 16f, paper, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(text(value, 15f, quiet, false))
        page.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }
}
