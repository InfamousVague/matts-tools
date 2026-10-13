package com.mattssoftware.frames

import com.mattssoftware.tools.R

import android.app.AppOpsManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews

class Prefs(context: Context) {
    private val kept = context.getSharedPreferences("frames", Context.MODE_PRIVATE)
    /** A name for what is about to be done ("scroll the feed"), kept on every session until changed. */
    var scenario: String
        get() = kept.getString("scenario", "") ?: ""
        set(v) = kept.edit().putString("scenario", v).apply()
    /** Measuring was on when the app was last running: it is taken up again as the app starts (an update ends it). */
    var measuring: Boolean
        get() = kept.getBoolean("measuring", false)
        set(v) = kept.edit().putBoolean("measuring", v).apply()
    /** The readout drawn over the app being measured, and which corner it sits in (0 top left, clockwise). */
    var hud: Boolean
        get() = kept.getBoolean("hud", false)
        set(v) = kept.edit().putBoolean("hud", v).apply()
    var corner: Int
        get() = kept.getInt("corner", 0)
        set(v) = kept.edit().putInt("corner", v).apply()
}

/** Whether the two permissions a computer gives once are held. */
object Access {
    fun dump(context: Context) = context.checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED
    fun usage(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }
    fun ready(context: Context) = dump(context) && usage(context)
    fun secure(context: Context) = context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED
}

class FramesTile : TileService() {
    override fun onStartListening() = draw()
    override fun onClick() {
        if (!Access.ready(this)) {
            val open = com.mattssoftware.tools.Shell.intent(this, "frames").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (android.os.Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 9, open, PendingIntent.FLAG_IMMUTABLE))
            else @Suppress("DEPRECATION") startActivityAndCollapse(open)
            return
        }
        val on = !ProfilerService.running
        ProfilerService.set(this, on)
        qsTile?.apply { state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; subtitle = if (on) "Measuring" else "Off"; updateTile() }
    }

    private fun draw() {
        val now = ProfilerService.live
        qsTile?.apply {
            state = if (ProfilerService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Frames"
            subtitle = when { !ProfilerService.running -> "Off"; now == null -> "Measuring"; else -> "${now.fps} fps · ${"%.0f".format(now.jankPct)}% jank" }
            updateTile()
        }
    }
}

class Toggle : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ProfilerService.running && !Access.ready(context)) {
            context.startActivity(com.mattssoftware.tools.Shell.intent(context, "frames").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
        }
        ProfilerService.set(context, !ProfilerService.running)
    }
    companion object {
        fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 1, Intent(context, Toggle::class.java), PendingIntent.FLAG_IMMUTABLE)
    }
}

class PillWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)
    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PillWidget::class.java))
            if (ids.isEmpty()) return
            val on = ProfilerService.running
            val now = ProfilerService.live
            val views = RemoteViews(context.packageName, R.layout.widget_frames)
            views.setInt(R.id.pill, "setBackgroundResource", if (on) R.drawable.pill_bg_on else R.drawable.pill_bg)
            views.setInt(R.id.icon, "setColorFilter", if (on) 0xFF0D0D0D.toInt() else 0xFFF5F5F5.toInt())
            views.setTextColor(R.id.words, if (on) 0xFF0D0D0D.toInt() else 0xB3F5F5F5.toInt())
            views.setTextViewText(R.id.words, when { !on -> "Not measuring"; now == null -> "Measuring"; else -> "${now.fps} fps · ${"%.1f".format(now.jankPct)}% jank" })
            views.setOnClickPendingIntent(R.id.pill, Toggle.pending(context))
            manager.updateAppWidget(ids, views)
        }
    }
}
