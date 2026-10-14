package com.mattssoftware.xray

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.FileDescriptor
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One reading of the app in front, held in memory until the next one, until X-ray is closed or forgotten, or until
 * the screen goes off. Never written anywhere. [picked] is the box chosen with Select, by its place in [reading].
 */
class Capture(
    val pkg: String, val label: String, val at: Long, val width: Int, val height: Int, val rotation: Int,
    val reading: XrayTree.Reading, val kinds: XrayTree.Kinds, val window: Int,
) {
    @Volatile var picked: Int? = null

    /** What X-ray's page shows of it before it is opened (lib/xray/xray.dart `CaptureSummary`). */
    fun summary(): Map<String, Any?> = mapOf(
        "package" to pkg, "label" to label, "count" to reading.boxes.size, "windows" to 1, "inspectors" to reading.inspectors,
        "at" to at, "picked" to picked, "cut" to reading.cut,
    )

    /** The whole of it, for the page's tree (package 4). Layers come with the compositor's reader. */
    fun toMap(): Map<String, Any?> = mapOf(
        "package" to pkg, "label" to label, "at" to at, "width" to width, "height" to height, "rotation" to rotation,
        "boxes" to reading.boxes.map { it.toMap() }, "inspectors" to reading.inspectors, "others" to kinds.others, "cut" to reading.cut,
        "layers" to null, "layersWhy" to null, "picked" to picked,
    )
}

/**
 * X-ray's own accessibility service, separate from the Dock's (dock/DockAccess.kt, which reads nothing and says so).
 * Switched on, it is idle: it asks for no events, keeps no cache and reads nothing (res/xml/xray_access.xml declares
 * only that it may read a window, which cannot be asked for later). Started from its tile ([XrayTile]) it asks for the
 * events that say the screen changed, reads the topmost application window on a thread of its own a few times a
 * second at most, and has [Overlay] draw the boxes over the app. Close, the screen going off, the lock screen, the
 * switch going off and an update all end that and drop what was read.
 *
 * The log (tag "X-ray") and `dumpsys activity service .../com.mattssoftware.xray.XrayAccess` say counts and times
 * only, never a word, an id, a class or a name of what was on the screen.
 */
class XrayAccess : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var work: Handler? = null
    private var overlay: Overlay? = null

    /** Started from the tile and drawing. */
    var on = false
        private set

    // Counts for the log and dumpsys: events and readings while on, and while idle since it last stopped.
    private var events = 0
    private var readings = 0
    private var idleEvents = 0
    private var idleSince = System.currentTimeMillis()
    private var lastRead = 0L
    private var waiting = false
    private var reading = false
    private var again = false
    private var appWindow = -1
    private var idsCounted = false

    private val screenOff = object : BroadcastReceiver() {
        // The screen going off is a Close, so the power button always gets a person out.
        override fun onReceive(context: Context, intent: Intent) { if (on) stop(false) else capture = null }
    }

    override fun onServiceConnected() {
        instance = this
        idle()
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(screenOff, filter)
        // The tile sent them here to switch X-ray on: it starts now, over whatever is in front. Cleared on every
        // connect, used or not, so a rebind after an update or a crash never starts X-ray by itself.
        val prefs = Prefs(this)
        val asked = prefs.startWhenOn
        prefs.startWhenOn = 0L
        Log.i(TAG, "connected")
        if (asked > 0 && System.currentTimeMillis() - asked in 0..START_WITHIN) start()
        tile()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!on) { idleEvents += 1; return }
        events += 1
        // Changes inside a window count only from the window being read; a window coming or going always does.
        val type = event.eventType
        if ((type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || type == AccessibilityEvent.TYPE_VIEW_SCROLLED) && event.windowId != appWindow) return
        schedule(200L)
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!on) return
        overlay?.layout()
        schedule(200L)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        end()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        end()
        super.onDestroy()
    }

    private fun end() {
        stop(false)
        capture = null
        runCatching { unregisterReceiver(screenOff) }
        if (instance === this) instance = null
        tile()
    }

    // ---- idle and running ----

    /** Asks for nothing: no events, no flags, and from Android 13 no cache of nodes (facts.md 2a-2c). */
    private fun idle() {
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = 0
            flags = AccessibilityServiceInfo.DEFAULT
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 150L
        }
        if (Build.VERSION.SDK_INT >= 33) setCacheEnabled(false)
    }

    private fun running() {
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or AccessibilityEvent.TYPE_VIEW_SCROLLED
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 150L
        }
        if (Build.VERSION.SDK_INT >= 33) setCacheEnabled(true)
    }

    /** Starts reading the app in front and drawing over it. Nothing over the lock screen. */
    fun start() {
        if (on) return
        if ((getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) { Log.i(TAG, "not started: the phone is locked"); return }
        Log.i(TAG, "idle since ${clock(idleSince)}: $idleEvents events, 0 readings")
        on = true
        events = 0; readings = 0; idsCounted = false; appWindow = -1
        running()
        thread = HandlerThread("xray-read").also { it.start() }
        work = Handler(thread!!.looper)
        overlay = Overlay(this).also { it.show() }
        // Started from the tile: the pull-down is closed so the app in front is what is read.
        if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        Log.i(TAG, "started")
        schedule(300L)
        tile()
    }

    /** Stops reading and removes what was drawn. [keep] keeps the last reading for X-ray's page (Open). */
    fun stop(keep: Boolean) {
        if (!keep) capture = null
        if (!on) return
        on = false
        main.removeCallbacks(readNow)
        waiting = false; again = false
        overlay?.hide(); overlay = null
        thread?.quitSafely(); thread = null; work = null
        idle()
        Log.i(TAG, "stopped: $events events, $readings readings while on")
        idleSince = System.currentTimeMillis(); idleEvents = 0
        tile()
    }

    /** Open: what was drawn goes, reading stops, the reading is kept, and X-ray's page opens on it. */
    fun open() {
        stop(true)
        runCatching { startActivity(com.mattssoftware.tools.Shell.intent(this, "xray").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.i(TAG, "open: ${it.javaClass.simpleName}") }
    }

    private fun tile() { runCatching { TileService.requestListeningState(this, ComponentName(this, XrayTile::class.java)) } }

    // ---- reading ----

    private val readNow = Runnable { waiting = false; read() }

    /** One reading [delay] ms from now, and at most four a second. */
    private fun schedule(delay: Long) {
        if (!on || waiting) return
        if (reading) { again = true; return }
        waiting = true
        val soonest = lastRead + 250L - SystemClock.uptimeMillis()
        main.postDelayed(readNow, maxOf(delay, soonest))
    }

    private fun read() {
        val handler = work ?: return
        if (!on) return
        reading = true
        lastRead = SystemClock.uptimeMillis()
        handler.post {
            val result = runCatching { take() }
            main.post {
                reading = false
                if (!on) return@post
                val got = result.getOrNull()
                when {
                    result.isFailure -> Log.i(TAG, "capture failed: ${result.exceptionOrNull()?.javaClass?.simpleName}")
                    got === LOCKED -> { stop(false); return@post }
                    got is Capture -> {
                        readings += 1
                        // A box picked before keeps its pick where the same box is still there.
                        capture?.let { old -> old.picked?.let { p -> got.picked = same(old.reading.boxes.getOrNull(p), got.reading.boxes) } }
                        capture = got
                        appWindow = got.window
                        overlay?.draw(got)
                    }
                }
                if (again) { again = false; schedule(0L) }
            }
        }
    }

    private fun same(box: XrayTree.Box?, boxes: List<XrayTree.Box>): Int? = box?.let { b ->
        boxes.firstOrNull { it.cls == b.cls && it.id == b.id && it.area.l == b.area.l && it.area.t == b.area.t && it.area.r == b.area.r && it.area.b == b.area.b }?.i
    }

    /** On the reading thread: the topmost application window's tree, or [LOCKED] where the lock screen is up. */
    private fun take(): Any? {
        if ((getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) return LOCKED
        val started = SystemClock.uptimeMillis()
        val all = windows
        val front = XrayTree.front(all.map { XrayTree.Win(it.id, it.type) }) ?: return null
        val window = all.first { it.id == front.id }
        val root = window.root ?: return null
        val pkg = root.packageName?.toString() ?: return null
        val kinds = Anatomy.kinds(this, pkg)
        val ids = intArrayOf(0, 0)
        val reading = XrayTree.read(Node(root, ids), kinds)
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        val (wide, tall, turn) = screen()
        Log.i(TAG, "capture: ${reading.boxes.size} boxes, 1 window, ${SystemClock.uptimeMillis() - started} ms${if (reading.cut) ", cut short" else ""}")
        if (!idsCounted) { idsCounted = true; Log.i(TAG, "ids: ${ids[0]} empty, ${ids[1]} null") }
        return Capture(pkg, label, System.currentTimeMillis(), wide, tall, turn, reading, kinds, window.id)
    }

    private fun screen(): Triple<Int, Int, Int> {
        val windows = getSystemService(WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION") val turn = windows.defaultDisplay.rotation
        if (Build.VERSION.SDK_INT >= 30) return windows.maximumWindowMetrics.bounds.let { Triple(it.width(), it.height(), turn) }
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") windows.defaultDisplay.getRealMetrics(m)
        return Triple(m.widthPixels, m.heightPixels, turn)
    }

    /** A node as the tree reader asks of it; [ids] counts ids that came back empty and missing (counts only). */
    private class Node(private val n: AccessibilityNodeInfo, private val ids: IntArray) : XrayTree.Raw {
        override val cls get() = n.className?.toString()
        override val id: String? get() = n.viewIdResourceName.also { if (it == null) ids[1] += 1 else if (it.isEmpty()) ids[0] += 1 }
        override val password get() = n.isPassword
        override fun text(): CharSequence? = n.text
        override fun desc(): CharSequence? = n.contentDescription
        override fun bounds(): XrayTree.Area = Rect().also { n.getBoundsInScreen(it) }.let { XrayTree.Area(it.left, it.top, it.right, it.bottom) }
        override val order get() = n.drawingOrder
        override val can: List<String>
            get() = listOfNotNull(
                "tapped".takeIf { n.isClickable }, "long pressed".takeIf { n.isLongClickable }, "scrolled".takeIf { n.isScrollable },
                "checked".takeIf { n.isCheckable }, "typed into".takeIf { n.isEditable },
            )
        override val visible get() = n.isVisibleToUser
        override val count get() = n.childCount
        override fun child(i: Int): XrayTree.Raw? = n.getChild(i)?.let { Node(it, ids) }
    }

    override fun dump(fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {
        if (on) writer.println("X-ray: on, $events events, $readings readings while on")
        else writer.println("X-ray: idle since ${clock(idleSince)}: $idleEvents events, 0 readings")
        writer.println("capture: ${capture?.reading?.boxes?.size ?: 0} boxes held")
    }

    companion object {
        private const val TAG = "X-ray"
        /** How long after the tile sent someone to switch X-ray on it still starts when the switch comes on. */
        private const val START_WITHIN = 5 * 60 * 1000L
        private val LOCKED = Any()

        @Volatile var instance: XrayAccess? = null
        /** The last reading, or null. Read by X-ray's page through xray/Door.kt. */
        @Volatile var capture: Capture? = null

        val running get() = instance?.on == true

        /** The page's "Forget it": the reading goes. */
        fun forget() { capture = null }

        private fun clock(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))
    }
}
