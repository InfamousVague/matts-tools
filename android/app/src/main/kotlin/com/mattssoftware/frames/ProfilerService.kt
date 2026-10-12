package com.mattssoftware.frames

import com.mattssoftware.tools.R

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.app.ActivityManager
import android.net.TrafficStats
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.os.PowerManager
import android.service.quicksettings.TileService
import android.view.Display
import org.json.JSONObject

/**
 * Measuring. While it runs, once a second it asks which app is in front and reads that app's newest frames; a
 * stay in an app is a session, kept under the app and the build it was, and closed when another app comes up.
 */
class ProfilerService : Service() {
    private val thread = HandlerThread("frames").apply { start() }
    private val work by lazy { Handler(thread.looper) }
    private val store by lazy { Store(this) }
    private var session: Session? = null
    private var front: String? = null
    private var lastVsync = 0L
    private var layer: String? = null
    private var quiet = 0
    private var lookedAt = System.currentTimeMillis() - 10_000L
    /** The app's screen in front (its activity), by its short name. */
    private var screen = ""
    private val screenOf = HashMap<String, String>()
    private val recent = ArrayDeque<Float>()
    private var rx = 0L; private var tx = 0L
    private var heat = Thermal.Now(null, null, null)
    private var ticks = 0
    private var missing = false
    private val main = Handler(Looper.getMainLooper())
    private val hud by lazy { Hud(this) }
    private val prefs by lazy { Prefs(this) }

    private val tick = object : Runnable {
        override fun run() {
            runCatching { second() }
            work.postDelayed(this, 1_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "off") { stop(); return START_NOT_STICKY }
        running = true
        show("Waiting for an app", null)
        work.removeCallbacks(tick); work.post(tick)
        tell(this)
        return START_STICKY
    }

    private fun stop() {
        running = false
        work.removeCallbacks(tick)
        main.post { hud.hide() }
        work.post { close(); live = null; tell(this) ; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    private fun second() {
        val now = System.currentTimeMillis()
        val usage = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usage.queryEvents(lookedAt, now); lookedAt = now - 500L
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) { events.getNextEvent(event); if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) { front = event.packageName; screenOf[event.packageName] = event.className.orEmpty().substringAfterLast('.') } }
        val pkg = front
        val power = getSystemService(POWER_SERVICE) as PowerManager
        if (pkg == null || pkg == packageName || !power.isInteractive || pkg in SKIP) { close(); live = null; main.post { hud.hide() }; show("Waiting for an app", null); tell(this); return }
        if (session?.pkg != pkg) { close(); open(pkg) }
        val current = session ?: return
        val at = screenOf[pkg].orEmpty().ifEmpty { "?" }
        if (at != screen) { screen = at; Trace.add("screen $at") }

        // The window that is drawing: kept while it keeps drawing, looked for again when it goes quiet.
        var read = layer?.let { FrameStats.read(it) }
        if (read == null || read.frames.none { it.vsyncNs > lastVsync }) {
            quiet += 1
            if (layer == null || quiet >= 2) {
                val was = layer
                read = FrameStats.newest(pkg) ?: read; layer = read?.layer; quiet = 0
                if (layer != was) Trace.add("window ${layer ?: "none"}")
                if (layer == null && !missing) { missing = true; Trace.add("no window of $pkg is drawing, or the compositor gave none (${FrameStats.layersOf(pkg).size} listed)") }
                if (layer != null) missing = false
            }
        } else quiet = 0
        val fresh = read?.frames.orEmpty().filter { it.vsyncNs > lastVsync }
        if (read != null && read.periodMs > 0f) current.refreshHz = 1000f / read.periodMs
        if (fresh.isNotEmpty()) {
            lastVsync = fresh.last().vsyncNs
            fresh.forEach { current.add(it, screen); recent.addLast(it.totalMs) }
            while (recent.size > 120) recent.removeFirst()
            current.activeSeconds += 1
            current.screens[screen]?.let { it.seconds += 1 }
        }
        current.endedAt = now
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val ma = (getSystemService(BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).let { if (kotlin.math.abs(it) > 20_000) it / 1000 else it }
        if (current.startTempC == 0f) current.startTempC = temp
        current.endTempC = temp
        current.thermalWorst = maxOf(current.thermalWorst, power.currentThermalStatus)
        current.currentMaSum += ma; current.currentSamples += 1
        // The phone's side of it: memory left, traffic, how hard the cores are clocked, and its own temperatures.
        val memory = ActivityManager.MemoryInfo().also { (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        val rxNow = TrafficStats.getTotalRxBytes(); val txNow = TrafficStats.getTotalTxBytes()
        val rxKb = if (rx == 0L) 0L else ((rxNow - rx) / 1024).coerceAtLeast(0L); val txKb = if (tx == 0L) 0L else ((txNow - tx) / 1024).coerceAtLeast(0L)
        rx = rxNow; tx = txNow
        val cpu = Cpu.pct()
        if (ticks++ % 5 == 0) heat = Thermal.read()
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val worst = fresh.maxOfOrNull { it.totalMs } ?: 0f
        current.seconds.put(JSONObject().apply {
            put("t", (now - current.startedAt) / 1000); put("frames", fresh.size)
            put("worstMs", worst.toDouble()); put("tempC", temp.toDouble()); put("ma", ma)
            put("screen", screen); put("hz", Math.round(current.refreshHz)); put("thermal", power.currentThermalStatus)
            put("availMb", memory.availMem / 1_048_576L); if (memory.lowMemory) put("lowMemory", true)
            put("rxKb", rxKb); put("txKb", txKb); if (level >= 0) put("batteryPct", level)
            cpu?.let { put("cpuFreqPct", it) }
            heat.skin?.let { put("skinC", it.toDouble()) }; heat.cpu?.let { put("cpuC", it.toDouble()) }; heat.gpu?.let { put("gpuC", it.toDouble()) }
        })

        // What shows: the last few seconds' rate, and the session's jank so far.
        val lately = (0 until current.seconds.length()).toList().takeLast(3).map { current.seconds.getJSONObject(it).getInt("frames") }
        val fpsNow = if (lately.isEmpty()) 0 else lately.sum() / lately.size
        val stats = current.stats()
        val now2 = Live(current.label, fpsNow, stats.jankPct, stats.frames, current.refreshHz, screen, worst, heat.skin ?: temp, recent.toFloatArray())
        live = now2
        debug = Debug(pkg, layer, read?.frames?.size ?: 0, read?.periodMs ?: 0f, fresh.size)
        val readout = prefs.hud && Settings.canDrawOverlays(this); val corner = prefs.corner
        main.post { if (readout && running) hud.set(now2, corner) else hud.hide() }
        show(current.label, live)
        tell(this)
    }

    private fun open(pkg: String) {
        val info = runCatching { packageManager.getPackageInfo(pkg, 0) }.getOrNull()
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        val display = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        session = Session(pkg, label, info?.versionName ?: "?", info?.longVersionCode ?: 0L, System.currentTimeMillis(),
            Prefs(this).scenario, display?.refreshRate ?: 60f)
        // Frames the compositor already holds were shown before this stay: only what comes after counts.
        val first = FrameStats.newest(pkg)
        layer = first?.layer; quiet = 0
        lastVsync = first?.frames?.lastOrNull()?.vsyncNs ?: 0L
        session?.startNano = System.nanoTime()
        recent.clear(); screen = ""; missing = false
        Trace.add("opened $pkg ${info?.versionName ?: "?"}, window ${layer ?: "none yet"}")
    }

    private fun close() {
        session?.let {
            store.save(it)
            Trace.add("closed ${it.pkg}: ${it.times.size} frames, " + if (it.times.size < Store.MIN_FRAMES) "too few to keep" else "kept")
        }
        session = null
    }

    private fun show(title: String, now: Live?) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, "measuring").setSmallIcon(R.drawable.ic_frames).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open).setContentTitle(if (now == null) "Frames is measuring" else "$title · ${now.fps} fps")
            .setContentText(now?.let { "${"%.1f".format(it.jankPct)}% janky of ${it.frames} frames at ${it.hz.toInt()} Hz" } ?: title)
            .setCategory(Notification.CATEGORY_STATUS)
            .addAction(Notification.Action.Builder(null, "Stop", Toggle.pending(this)).build())
        val note = builder.build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, note)
    }

    override fun onDestroy() { running = false; main.post { hud.hide() }; work.removeCallbacksAndMessages(null); close(); thread.quitSafely(); super.onDestroy() }

    class Live(val app: String, val fps: Int, val jankPct: Float, val frames: Int, val hz: Float,
        val screen: String = "", val worstMs: Float = 0f, val tempC: Float = 0f, val recent: FloatArray = FloatArray(0))
    /** What the last second's read was made of: for the diagnostics page. */
    class Debug(val pkg: String, val layer: String?, val held: Int, val periodMs: Float, val fresh: Int)

    companion object {
        @Volatile var running = false
        @Volatile var live: Live? = null
        @Volatile var debug: Debug? = null
        /** The launcher and the system's own surfaces are not what is being measured. */
        val SKIP = setOf("com.android.systemui", "com.sec.android.app.launcher", "com.google.android.apps.nexuslauncher", "com.android.launcher3")
        const val CHANGED = "com.mattssoftware.frames.CHANGED"

        fun channel(context: Context) {
            (context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel("measuring", "Measuring", NotificationManager.IMPORTANCE_LOW).apply { description = "Shows while frames are being measured, with the rate now." })
        }

        fun set(context: Context, on: Boolean) {
            val intent = Intent(context, ProfilerService::class.java).setAction(if (on) "on" else "off")
            if (on) context.startForegroundService(intent) else if (running) context.startService(intent)
        }

        fun tell(context: Context) {
            TileService.requestListeningState(context, ComponentName(context, FramesTile::class.java))
            PillWidget.refresh(context)
            context.sendBroadcast(Intent(CHANGED).setPackage(context.packageName))
        }
    }
}
