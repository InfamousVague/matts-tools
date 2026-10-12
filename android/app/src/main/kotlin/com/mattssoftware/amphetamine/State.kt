package com.mattssoftware.amphetamine

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock

/** What Amphetamine does as the app starts: its notification channels, the charger's job, a session taken up again. */
object AwakeStart {
    fun run(context: Context) {
        AwakeService.channel(context)
        ChargeJob.arm(context)
        val prefs = Prefs(context)
        if (prefs.apps.isNotEmpty() || prefs.on) AwakeService.watch(context)
        // No session on, yet something of the phone's is still as a session left it (the phone was restarted, or the
        // app was ended, with one on): put back now.
        if (!prefs.on) SystemHold.restore(context)
    }
}

/**
 * The "system" way of holding the screen: the phone's own timeout and brightness changed, and put back. What they
 * were is kept on the phone, so whoever notices a session is over can put them back: the service, the app as it
 * starts, the phone as it starts, a tap on the tile with no service running.
 */
object SystemHold {
    /** What the timeout is set to while held. Never taken for the person's own. */
    private const val HELD = Int.MAX_VALUE
    private const val USUAL = 30_000

    fun keep(context: Context, bright: Boolean) {
        if (!android.provider.Settings.System.canWrite(context)) return
        val prefs = Prefs(context); val cr = context.contentResolver
        val now = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, USUAL)
        // The person's own timeout, remembered whenever it is seen: a held value read back is ours, not theirs.
        if (now != HELD) prefs.usualTimeout = now
        if (prefs.savedTimeout < 0) prefs.savedTimeout = if (now != HELD) now else prefs.usualTimeout
        android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, HELD)
        if (bright) {
            if (prefs.savedBrightness < 0) {
                prefs.savedBrightness = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128)
                prefs.savedMode = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, 1)
            }
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, 255)
        } else restoreBrightness(context)
    }

    fun restoreBrightness(context: Context) {
        val prefs = Prefs(context)
        if (prefs.savedBrightness < 0 || !android.provider.Settings.System.canWrite(context)) return
        android.provider.Settings.System.putInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, prefs.savedBrightness)
        android.provider.Settings.System.putInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, prefs.savedMode.coerceAtLeast(0))
        prefs.savedBrightness = -1; prefs.savedMode = -1
    }

    fun restore(context: Context) {
        restoreBrightness(context)
        if (!android.provider.Settings.System.canWrite(context)) return
        val prefs = Prefs(context); val cr = context.contentResolver
        val now = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, USUAL)
        // Put back what was kept; and a timeout still held with nothing kept is ours all the same.
        val back = when { prefs.savedTimeout in 0 until HELD -> prefs.savedTimeout; now == HELD -> prefs.usualTimeout; else -> -1 }
        if (back >= 0) android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, back)
        prefs.savedTimeout = -1
    }
}

/** What the person chose, and whether a session is on, kept on the phone. */
class Prefs(context: Context) {
    private val kept: SharedPreferences = context.getSharedPreferences("awake", Context.MODE_PRIVATE)

    /** When the session began, on the clock that counts while asleep; 0 with none on. */
    var since: Long
        get() = kept.getLong("since", 0L).let { if (it > SystemClock.elapsedRealtime()) 0L else it }
        set(v) = kept.edit().putLong("since", v).apply()
    val on get() = since > 0L

    /** Why it is on: the person's tap, the charger, or an app in front. */
    var why: String
        get() = kept.getString("why", "tap") ?: "tap"
        set(v) = kept.edit().putString("why", v).apply()

    var bright: Boolean
        get() = kept.getBoolean("bright", false)
        set(v) = kept.edit().putBoolean("bright", v).apply()

    /** "overlay" (an unseen window) or "system" (the phone's own timeout and brightness). */
    var method: String
        get() = kept.getString("method", "overlay") ?: "overlay"
        set(v) = kept.edit().putString("method", v).apply()

    var batteryFloor: Int
        get() = kept.getInt("batteryFloor", 20)
        set(v) = kept.edit().putInt("batteryFloor", v).apply()
    var endOnBattery: Boolean
        get() = kept.getBoolean("endOnBattery", true)
        set(v) = kept.edit().putBoolean("endOnBattery", v).apply()
    var endUnplugged: Boolean
        get() = kept.getBoolean("endUnplugged", true)
        set(v) = kept.edit().putBoolean("endUnplugged", v).apply()
    var endFolded: Boolean
        get() = kept.getBoolean("endFolded", true)
        set(v) = kept.edit().putBoolean("endFolded", v).apply()
    var startCharging: Boolean
        get() = kept.getBoolean("startCharging", false)
        set(v) = kept.edit().putBoolean("startCharging", v).apply()

    /** Whether this session began on the charger: only such a one ends when it comes out. */
    var beganCharging: Boolean
        get() = kept.getBoolean("beganCharging", false)
        set(v) = kept.edit().putBoolean("beganCharging", v).apply()

    var apps: Set<String>
        get() = kept.getStringSet("apps", emptySet()) ?: emptySet()
        set(v) = kept.edit().putStringSet("apps", v).apply()

    /** The phone's own settings as they were, to put back (the "system" method). */
    var savedTimeout: Int
        get() = kept.getInt("savedTimeout", -1)
        set(v) = kept.edit().putInt("savedTimeout", v).apply()
    /** The person's own timeout as last seen, for when nothing better was kept. */
    var usualTimeout: Int
        get() = kept.getInt("usualTimeout", 30_000)
        set(v) = kept.edit().putInt("usualTimeout", v).apply()
    var savedBrightness: Int
        get() = kept.getInt("savedBrightness", -1)
        set(v) = kept.edit().putInt("savedBrightness", v).apply()
    var savedMode: Int
        get() = kept.getInt("savedMode", -1)
        set(v) = kept.edit().putInt("savedMode", v).apply()
}

/** How long, in the words a tile or a line says: "12 min", "1 h 05". */
fun awakeFor(since: Long, seconds: Boolean = false): String = span(SystemClock.elapsedRealtime() - since, seconds)

/**
 * A length of time as days, hours, minutes and seconds run together, from its largest unit that is not nothing:
 * "42s", "5m12s", "1h30m5s", "1d2h3m4s". Without [seconds], for words that are only looked at now and then, it stops
 * at the minutes ("1h30m"; under a minute, "0m").
 */
fun span(ms: Long, seconds: Boolean = true): String {
    val total = ms.coerceAtLeast(0L) / 1000L
    val parts = listOf(total / 86_400L to "d", total % 86_400L / 3_600L to "h", total % 3_600L / 60L to "m", total % 60L to "s")
        .let { if (seconds) it else it.dropLast(1) }
    val from = parts.indexOfFirst { it.first > 0L }.let { if (it < 0) parts.size - 1 else it }
    return parts.drop(from).joinToString("") { "${it.first}${it.second}" }
}

/**
 * A length of time as two digits and a unit, for the status bar's one small square: only its largest unit, whole
 * and never rounded up ("02" s, "41" m, "01" h, "03" d).
 */
fun twoDigits(ms: Long): Pair<String, String> {
    val seconds = ms.coerceAtLeast(0L) / 1000L
    return when {
        seconds < 60L -> "%02d".format(seconds) to "s"
        seconds < 3_600L -> "%02d".format(seconds / 60L) to "m"
        seconds < 86_400L -> "%02d".format(seconds / 3_600L) to "h"
        else -> "%02d".format((seconds / 86_400L).coerceAtMost(99L)) to "d"
    }
}
