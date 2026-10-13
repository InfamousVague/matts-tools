package com.mattssoftware.amphetamine

import com.mattssoftware.tools.R

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.View
import android.widget.RemoteViews

/** The tile in the pull-down: tap on, tap off; its second line says how long. */
class AwakeTile : TileService() {
    override fun onStartListening() = draw()
    override fun onClick() {
        val prefs = Prefs(this)
        val on = !prefs.on
        // With neither permission there is nothing to hold the screen with: the setting is opened at once, and the
        // session starts by itself as the person comes back from allowing it (tools/Shell.kt, "grant").
        if (on && !Settings.canDrawOverlays(this) && !Settings.System.canWrite(this)) {
            val open = com.mattssoftware.tools.Shell.intent(this, "amphetamine").putExtra("grant", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (android.os.Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 9, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            else @Suppress("DEPRECATION") startActivityAndCollapse(open)
            return
        }
        AwakeService.set(this, on)
        // Drawn at once as it will be: the service answers a moment later.
        qsTile?.apply { state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; subtitle = if (on) "On" else "Off"; updateTile() }
    }

    private fun draw() {
        val prefs = Prefs(this)
        qsTile?.apply {
            state = if (prefs.on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Amphetamine"
            subtitle = if (prefs.on) "On · ${awakeFor(prefs.since)}" else "Off"
            icon = Icon.createWithResource(this@AwakeTile, R.drawable.ic_pill)
            updateTile()
        }
    }
}

/** The second tile: full brightness for the session (kept as the choice for the next one too). */
class BrightTile : TileService() {
    override fun onStartListening() = draw()
    override fun onClick() {
        val bright = !Prefs(this).bright
        AwakeService.bright(this, bright)
        qsTile?.apply { state = if (bright) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; updateTile() }
    }

    private fun draw() {
        val prefs = Prefs(this)
        qsTile?.apply {
            state = if (prefs.bright) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Full brightness"
            subtitle = if (!prefs.bright) "Off" else if (prefs.on) "On" else "When awake"
            updateTile()
        }
    }
}

/** A press on the widget, or on the notification's words. */
class Toggle : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "bright" -> AwakeService.bright(context, !Prefs(context).bright)
            "off" -> AwakeService.set(context, false)
            else -> AwakeService.set(context, !Prefs(context).on)
        }
    }

    companion object {
        fun pending(context: Context, toggle: Boolean): PendingIntent = PendingIntent.getBroadcast(
            context, if (toggle) 1 else 2, Intent(context, Toggle::class.java).setAction(if (toggle) "toggle" else "off"), PendingIntent.FLAG_IMMUTABLE)
        fun bright(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 3, Intent(context, Toggle::class.java).setAction("bright"), PendingIntent.FLAG_IMMUTABLE)
    }
}

/** The pill on the home screen: asleep, or awake with the time running, as a timer's or a VPN's is. */
class PillWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PillWidget::class.java))
            if (ids.isEmpty()) return
            val prefs = Prefs(context)
            val views = RemoteViews(context.packageName, R.layout.widget_awake)
            views.setInt(R.id.pill, "setBackgroundResource", if (prefs.on) R.drawable.pill_bg_on else R.drawable.pill_bg)
            views.setInt(R.id.icon, "setColorFilter", if (prefs.on) 0xFF0D0D0D.toInt() else 0xFFF5F5F5.toInt())
            views.setViewVisibility(R.id.time, if (prefs.on) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.off, if (prefs.on) View.GONE else View.VISIBLE)
            // The chronometer counts by itself from when the session began: nothing has to wake to move it.
            if (prefs.on) views.setChronometer(R.id.time, prefs.since, null, true) else views.setChronometer(R.id.time, 0L, null, false)
            views.setOnClickPendingIntent(R.id.pill, Toggle.pending(context, true))
            manager.updateAppWidget(ids, views)
        }
    }
}

/**
 * "Start when charging". A manifest receiver may no longer hear the charger, so a job that needs it stands for
 * one: it runs when the phone is plugged in, starts a session if that is wanted, and is armed again.
 */
class ChargeJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val prefs = Prefs(this)
        if (prefs.startCharging && !prefs.on) runCatching { AwakeService.set(this, true, "charger") }
        return false
    }
    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        fun arm(context: Context) {
            val jobs = context.getSystemService(JobScheduler::class.java)
            if (!Prefs(context).startCharging) { jobs.cancel(7); return }
            // Armed only off the charger: armed on it, the job would run at once, and a session stopped by hand
            // while plugged in would start again.
            val battery = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if ((battery?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0) return
            jobs.schedule(JobInfo.Builder(7, ComponentName(context, ChargeJob::class.java)).setRequiresCharging(true).setPersisted(false).build())
        }
    }
}

/** After a restart no session is on (the clock it counted on began again), and the watcher comes back. */
class Boot : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        // A restart ends a session: the timeout and brightness it held are the phone's own again.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) { prefs.since = 0L; SystemHold.restore(context) }
        ChargeJob.arm(context)
        // An update ends the process with a session on: it is taken up again, so what says "on" is on.
        if (prefs.apps.isNotEmpty() || prefs.on) AwakeService.watch(context)
        AwakeService.tell(context)
    }
}
