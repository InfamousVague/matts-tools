package com.mattssoftware.amphetamine

import com.mattssoftware.tools.R

import android.app.Notification
import android.graphics.drawable.Icon
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.quicksettings.TileService
import android.content.ComponentName
import android.view.View
import android.view.WindowManager

/**
 * The session: while it runs, the screen does not sleep.
 *
 * Two ways of holding it, the person's choice (Prefs.method):
 * - "overlay": a one-pixel window over everything that asks the system to keep the screen on, and to draw at
 *   full brightness when that is wanted. Needs "display over other apps". Nothing of the phone's is changed.
 * - "system": the phone's own screen timeout set to its longest and its brightness to full, both put back when
 *   the session ends. Needs "modify system settings". The fallback when the overlay is refused.
 *
 * It is also the watcher for "keep awake while these apps are in front": with apps chosen it stays up, quiet,
 * and starts and ends a session as one of them comes and goes.
 */
class AwakeService : Service() {
    private val prefs by lazy { Prefs(this) }
    private val main = Handler(Looper.getMainLooper())
    private var window: View? = null
    private var hinge: SensorEventListener? = null
    private var watching = false

    private val power = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_DISCONNECTED -> {
                    ChargeJob.arm(context)
                    if (prefs.on && prefs.endUnplugged && prefs.beganCharging) end("Unplugged")
                }
                Intent.ACTION_POWER_CONNECTED -> if (!prefs.on && prefs.startCharging) begin("charger")
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) * 100 / intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    if (prefs.on && prefs.endOnBattery && !charging && level <= prefs.batteryFloor) end("Battery at $level%")
                }
            }
        }
    }

    /** Once a minute: the tile's and the notification's words; every two seconds while watching: who is in front. */
    private val tick = object : Runnable {
        override fun run() {
            if (watching) lookInFront()
            if (prefs.on && SystemClock.elapsedRealtime() / 60_000L != lastMinute) {
                lastMinute = SystemClock.elapsedRealtime() / 60_000L
                tell(this@AwakeService)
                show()
            }
            main.postDelayed(this, if (watching) 2_000L else 30_000L)
        }
    }
    private var lastMinute = 0L
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registerReceiver(power, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        })
        main.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ON -> begin(intent.getStringExtra("why") ?: "tap")
            ACTION_OFF -> end(null)
            ACTION_BRIGHT -> { prefs.bright = intent.getBooleanExtra("bright", !prefs.bright); if (prefs.on) hold() ; tell(this) }
            ACTION_WATCH, null -> Unit
        }
        watching = prefs.apps.isNotEmpty() && canSeeApps(this)
        if (!prefs.on && !watching) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        show()
        if (prefs.on) hold()
        return START_STICKY
    }

    private fun begin(why: String) {
        if (prefs.on) return
        prefs.since = SystemClock.elapsedRealtime()
        prefs.why = why
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        prefs.beganCharging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        hold()
        listenToHinge()
        show()
        tell(this)
    }

    private fun end(said: String?) {
        if (!prefs.on) return
        prefs.since = 0L
        notes().cancel(3)
        release()
        hinge?.let { (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(it) }
        hinge = null
        tell(this)
        if (said != null) notes().notify(2, Notification.Builder(this, CHANNEL_QUIET).setSmallIcon(R.drawable.ic_pill)
            .setContentTitle("Amphetamine ended").setContentText("$said. The screen sleeps as usual again.").setAutoCancel(true)
            .setContentIntent(open()).build())
        watching = prefs.apps.isNotEmpty() && canSeeApps(this)
        if (watching) show() else { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    // ---- holding the screen ----------------------------------------------------------------------------------

    private fun hold() {
        val overlay = prefs.method == "overlay" && Settings.canDrawOverlays(this)
        if (overlay) {
            restoreSystem()
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                screenBrightness = if (prefs.bright) 1f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                alpha = 0f
            }
            val had = window
            if (had == null) {
                val view = View(this)
                runCatching { wm.addView(view, params) }.onSuccess { window = view }
            } else runCatching { wm.updateViewLayout(had, params) }
        } else if (Settings.System.canWrite(this)) {
            dropWindow()
            SystemHold.keep(this, prefs.bright)
        }
    }

    private fun release() { dropWindow(); restoreSystem() }

    private fun dropWindow() {
        window?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        window = null
    }

    private fun restoreSystem() = SystemHold.restore(this)

    // ---- what ends it, what starts it -------------------------------------------------------------------------

    /** The Fold shut: its hinge at (about) nothing. The public sensor says 0, 90 or 180 on this phone. */
    private fun listenToHinge() {
        if (!prefs.endFolded || hinge != null) return
        val sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) ?: return
        var wasOpen = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val angle = event.values[0]
                if (angle > 45f) wasOpen = true
                // Only a closing ends it: a session begun on the cover screen, already shut, is left alone.
                if (angle < 10f && wasOpen && prefs.on && prefs.endFolded) end("Folded shut")
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        hinge = listener
    }

    private var front: String? = null
    private fun lookInFront() {
        val usage = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usage.queryEvents(now - 10_000L, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) front = event.packageName
        }
        val wanted = front != null && front in prefs.apps
        if (wanted && !prefs.on) begin("app")
        else if (!wanted && prefs.on && prefs.why == "app" && front != packageName) end(null)
    }

    // ---- what shows -------------------------------------------------------------------------------------------

    private fun notes() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    private fun open() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun show() {
        val on = prefs.on
        // One quiet notification with the pill: Android asks for it while the screen is held. How long it has been
        // on is the dock's to show (Matt's Software Dock asks through DockState), and the tile's.
        val builder = Notification.Builder(this, CHANNEL_QUIET)
            .setSmallIcon(R.drawable.ic_pill)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open())
            .setShowWhen(false)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        notes().cancel(3)
        if (on) {
            builder.setContentTitle("Awake for ${awakeFor(prefs.since)}")
                .setContentText(if (prefs.bright) "Screen held on at full brightness" else "Screen held on")
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(Notification.Action.Builder(null, "Stop", Toggle.pending(this, false)).build())
                .addAction(Notification.Action.Builder(null, if (prefs.bright) "Usual brightness" else "Full brightness", Toggle.bright(this)).build())
        } else {
            builder.setContentTitle("Amphetamine is watching for your apps")
                .setContentText("The screen stays on while one of them is in front.")
        }
        val note = builder.build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, note)
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        runCatching { unregisterReceiver(power) }
        notes().cancel(3)
        // Killed with a session on: nothing of the phone's is left changed.
        release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_ON = "on"
        const val ACTION_OFF = "off"
        const val ACTION_BRIGHT = "bright"
        const val ACTION_WATCH = "watch"
        const val CHANNEL_ON = "awake"
        const val CHANNEL_QUIET = "quiet"

        fun channel(context: Context) {
            val notes = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notes.createNotificationChannel(NotificationChannel(CHANNEL_ON, "Awake", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Shows while the screen is being kept on, with how long."
                setSound(null, null); enableVibration(false)
            })
            notes.createNotificationChannel(NotificationChannel(CHANNEL_QUIET, "Ended, and watching", NotificationManager.IMPORTANCE_LOW))
        }

        fun set(context: Context, on: Boolean, why: String = "tap") {
            val intent = Intent(context, AwakeService::class.java).setAction(if (on) ACTION_ON else ACTION_OFF).putExtra("why", why)
            if (on) context.startForegroundService(intent) else if (Prefs(context).on) context.startService(intent)
            // Off with no session to end (the phone restarted with one on): what it left changed is still put back.
            else { SystemHold.restore(context); tell(context) }
        }

        fun bright(context: Context, bright: Boolean) {
            val prefs = Prefs(context)
            if (prefs.on) context.startService(Intent(context, AwakeService::class.java).setAction(ACTION_BRIGHT).putExtra("bright", bright))
            else { prefs.bright = bright; tell(context) }
        }

        fun watch(context: Context) {
            runCatching { context.startForegroundService(Intent(context, AwakeService::class.java).setAction(ACTION_WATCH)) }
        }

        fun canSeeApps(context: Context): Boolean {
            val ops = context.getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
            return ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) == android.app.AppOpsManager.MODE_ALLOWED
        }

        /** Tells the tiles, the widget and the open screen that something changed. */
        fun tell(context: Context) {
            TileService.requestListeningState(context, ComponentName(context, AwakeTile::class.java))
            TileService.requestListeningState(context, ComponentName(context, BrightTile::class.java))
            PillWidget.refresh(context)
            context.sendBroadcast(Intent(CHANGED).setPackage(context.packageName))
        }
        const val CHANGED = "com.mattssoftware.amphetamine.CHANGED"
    }
}
