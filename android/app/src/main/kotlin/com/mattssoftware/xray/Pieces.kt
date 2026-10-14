package com.mattssoftware.xray

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/** The little X-ray keeps on disk: where the bar was left, and when the tile last sent someone to switch it on. Nothing of any screen. */
class Prefs(context: Context) {
    private val kept = context.getSharedPreferences("xray", Context.MODE_PRIVATE)
    var startWhenOn: Long
        get() = kept.getLong("startWhenOn", 0L)
        set(v) = kept.edit().putLong("startWhenOn", v).apply()
    var barX: Int?
        get() = if (kept.contains("barX")) kept.getInt("barX", 0) else null
        set(v) = kept.edit().putInt("barX", v ?: 0).apply()
    var barY: Int?
        get() = if (kept.contains("barY")) kept.getInt("barY", 0) else null
        set(v) = kept.edit().putInt("barY", v ?: 0).apply()
}

/**
 * X-ray's quick settings tile, which starts and stops it over the app in front: opening X-ray's own page would put
 * Matt's Tools in front instead. With X-ray's switch off it goes straight to Accessibility with the line saying what
 * to switch on, and X-ray starts as the switch comes on (the brief: "if there is a setting that needs turned on the app
 * should prompt immediatly when tapping"). Over the lock screen it starts nothing: the phone is unlocked first.
 */
class XrayTile : TileService() {
    override fun onStartListening() = draw()

    override fun onClick() {
        if (isLocked) { unlockAndRun { act() }; return }
        act()
    }

    private fun act() {
        val service = XrayAccess.instance
        if (service == null || !Switched.xray(this)) {
            Prefs(this).startWhenOn = System.currentTimeMillis()
            Toast.makeText(this, ASK, Toast.LENGTH_LONG).show()
            val open = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 12, open, PendingIntent.FLAG_IMMUTABLE))
            else @Suppress("DEPRECATION") startActivityAndCollapse(open)
            return
        }
        if (service.on) service.stop(false) else service.start()
        draw()
    }

    private fun draw() {
        val on = XrayAccess.running
        qsTile?.apply {
            state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "X-ray"
            subtitle = when { on -> "Drawing"; XrayAccess.instance == null -> "Switch on"; else -> "Off" }
            updateTile()
        }
    }

    companion object {
        /** The line shown as the setting opens, the same from the tile and from X-ray's page (xray/Door.kt `ask`). */
        const val ASK = "Switch on Matt's Tools: X-ray under Installed apps (Downloaded apps on some phones), then come back"
    }
}

/**
 * Whether an accessibility service is switched on, by its whole name. Android keeps the switched-on services as one
 * string of `package/class` names joined by ':', a class under the package's own name shortened to `package/.Class`.
 * Matching the app's name alone (`com.mattssoftware.tools/`) would say the Dock's service is on when only X-ray's is,
 * and the other way round, now that the app has two.
 */
object Switched {
    fun has(list: String?, pkg: String, cls: String): Boolean = list.orEmpty().split(':').any { entry ->
        val at = entry.trim().indexOf('/')
        if (at <= 0) return@any false
        val owner = entry.trim().substring(0, at)
        val named = entry.trim().substring(at + 1)
        owner == pkg && (if (named.startsWith(".")) owner + named else named) == cls
    }

    fun on(context: Context, cls: String) = has(Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), context.packageName, cls)
    /** X-ray's own switch, whether or not the service has started yet. */
    fun xray(context: Context) = on(context, XrayAccess::class.java.name)
}
