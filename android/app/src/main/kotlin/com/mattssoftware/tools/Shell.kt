package com.mattssoftware.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import com.mattssoftware.amphetamine.AwakeService
import com.mattssoftware.amphetamine.AwakeTile
import com.mattssoftware.amphetamine.BrightTile
import com.mattssoftware.amphetamine.ChargeJob
import com.mattssoftware.dock.DockAccess
import com.mattssoftware.dock.DockService
import com.mattssoftware.dock.Phones
import com.mattssoftware.dock.Sources
import com.mattssoftware.frames.FramesTile
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The way to a page of the app from outside its own pages: a notification, a tile, a tap on the dock. */
object Shell {
    /**
     * Opens the app at [page], by the name the Flutter side knows it ("frames", "amphetamine", "toasty",
     * "settings/dock"; lib/shell/app_shell.dart). The page is in the intent's data too, so that two of these for
     * different pages are different intents to Android, which otherwise keeps one pending intent for both.
     */
    fun intent(context: Context, page: String): Intent =
        Intent(context, HomeActivity::class.java).setData(Uri.parse("tools:$page")).putExtra("page", page)
}

/**
 * The app's one activity: every page is Flutter's (lib/), under one bar with its back and forward. It is Frames'
 * door to the phone ([com.mattssoftware.frames.MainActivity], whose channel this shares) with the other tools'
 * settings added to it: what each stands at (`tools`), and one way to change any of them (`set`), which goes through
 * whatever the tool's own Kotlin page called when it changed that setting, so a service already running takes it up
 * as it did then. The dock's page, Amphetamine's and Toasty's were activities of their own, drawn here in Kotlin;
 * their settings are pages of Settings now and their preferences are the same ones.
 *
 * The first time the app is opened it goes to the setting-up ([SetupActivity]) over this.
 */
class HomeActivity : com.mattssoftware.frames.MainActivity() {
    private val kept by lazy { getSharedPreferences("tools", Context.MODE_PRIVATE) }
    private val dock by lazy { com.mattssoftware.dock.Prefs(this) }
    private val awake by lazy { com.mattssoftware.amphetamine.Prefs(this) }

    /** The page the app was opened at, until Flutter has asked for it. */
    private var page: String? = null
    /** The dock was switched on with no leave to draw over apps: the setting is open, and it is on when they come back. */
    private var askingDock = false
    /** A session was asked for with nothing to hold the screen with: it starts when one has been allowed. */
    private var granting = false
    /** One of the phone's own settings was opened from a page: the dock is made again on coming back. */
    private var away = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (state == null) page = pageOf(intent)
        if (!kept.getBoolean("setUp", false)) startActivity(Intent(this, SetupActivity::class.java))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pageOf(intent)?.let { door?.invokeMethod("open", it) }
    }

    /** Where an intent asks to land: its own page, or for a tile held down in the pull-down, that tile's settings. */
    private fun pageOf(intent: Intent): String? {
        intent.getStringExtra("page")?.let { return it }
        if (intent.action != "android.service.quicksettings.action.QS_TILE_PREFERENCES") return null
        val tile = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME)
        return when (tile?.className) {
            FramesTile::class.java.name -> "settings/frames"
            com.mattssoftware.xray.XrayTile::class.java.name -> "settings/xray"
            AwakeTile::class.java.name, BrightTile::class.java.name -> "settings/amphetamine"
            else -> "settings"
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the setting the dock sent them to: it is on, as they asked.
        if (askingDock) { askingDock = false; away = true; if (Settings.canDrawOverlays(this)) dock.on = true }
        if (away) { away = false; runCatching { DockService.apply(this) } }
        // Amphetamine's tile, tapped with neither permission, comes here with "grant": the setting is opened at
        // once, and the session starts by itself as the person comes back from allowing it.
        val allowed = Settings.canDrawOverlays(this) || Settings.System.canWrite(this)
        if (intent.getBooleanExtra("grant", false)) {
            intent.removeExtra("grant")
            if (!allowed) askToHold()
        } else if (granting && allowed) {
            granting = false
            if (!awake.on) AwakeService.set(this, true)
        }
    }

    private fun askToHold() {
        granting = true
        Toast.makeText(this, "Allow Amphetamine to appear on top, then come back", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    override fun answer(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "tools" -> result.success(tools())
            "set" -> result.success(set(call.argument<String>("name")!!, call.argument<Any>("value")))
            "openSetting" -> { openSetting(call.arguments as String); result.success(null) }
            "apps" -> Thread { val all = apps(); runOnUiThread { result.success(all) } }.start()
            "launch" -> { result.success(page); page = null }
            "xray" -> com.mattssoftware.xray.Door.answer(this, call, result)
            else -> super.answer(call, result)
        }
    }

    private fun screen(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) windowManager.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        else resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    /** What every tool's settings stand at, read from the tools' own preferences (lib/tools.dart `ToolsState`). */
    private fun tools(): Map<String, Any?> {
        val (wide, tall) = screen()
        val overlay = Settings.canDrawOverlays(this)
        val camera = DockService.hole(this, dock, wide, tall)
        val (dx, dy, _) = dock.nudge(wide, tall)
        val names = awake.apps.mapNotNull { runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }.getOrNull() }.sorted()
        val info = packageManager.getPackageInfo(packageName, 0)
        return mapOf(
            "dock" to mapOf(
                "on" to (dock.on && overlay), "overlay" to overlay,
                "camera" to (camera != null), "cameraAt" to camera?.let { "${it.left}, ${it.top} to ${it.right}, ${it.bottom}" },
                "guess" to (Phones.hole(wide, tall)?.measured == false && dx == 0 && dy == 0),
                "beside" to DockService.docked(dock, wide, tall, camera), "taps" to (DockAccess.instance != null),
                "side" to (camera?.let { DockService.side(dock, wide, tall, it) } ?: "left"),
                "vertical" to dock.vertical, "blur" to dock.blur, "heat" to dock.heat, "fahrenheit" to dock.fahrenheit,
                "awake" to dock.awake, "frames" to dock.frames,
            ),
            "awake" to mapOf(
                "on" to awake.on, "forMs" to (if (awake.on) android.os.SystemClock.elapsedRealtime() - awake.since else 0L), "why" to awake.why,
                "bright" to awake.bright, "endOnBattery" to awake.endOnBattery, "batteryFloor" to awake.batteryFloor,
                "endUnplugged" to awake.endUnplugged, "endFolded" to awake.endFolded, "startCharging" to awake.startCharging,
                "apps" to awake.apps.toList(), "appNames" to names, "seesApps" to AwakeService.canSeeApps(this),
                "method" to awake.method, "overlay" to overlay, "system" to Settings.System.canWrite(this),
            ),
            "granted" to mapOf(
                "notifications" to Granted.notifications(this), "overlay" to overlay, "usage" to Granted.usage(this), "access" to Granted.access(this),
                "write" to Settings.System.canWrite(this), "dump" to Granted.dump(this), "secure" to com.mattssoftware.frames.Access.secure(this),
                "xray" to Granted.xray(this),
            ),
            "build" to mapOf(
                "version" to (info.versionName ?: ""), "code" to info.longVersionCode, "package" to packageName,
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}", "android" to "${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})",
                "installed" to SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(info.lastUpdateTime)),
            ),
            "celsius" to Sources.celsius(this)?.toDouble(),
        )
    }

    /**
     * Changes one setting, the way the tool's own page did: the same preference, and then whatever that page called
     * so the tool takes it up at once. Every change to the dock ends in [DockService.apply], which makes its window
     * again; Amphetamine's brightness and its way of holding go to the session through [AwakeService]. Answers
     * "asked" where a permission was wanted first and its setting has been opened.
     */
    private fun set(name: String, value: Any?): String? {
        val (wide, tall) = screen()
        fun flag() = value as Boolean
        when (name) {
            "dock.on" -> {
                // Asked for at once, and on when they come back.
                if (flag() && !Settings.canDrawOverlays(this)) { askingDock = true; overlaySetting(); return "asked" }
                dock.on = flag()
            }
            "dock.mode" -> dock.mode(wide, tall, value as String)
            "dock.side" -> dock.side(wide, tall, value as String)
            "dock.nudge" -> {
                // Lining the frame up with the hole: where the hole is taken to be moves a few pixels at a time.
                val step = (value as List<*>).map { (it as Number).toInt() }
                val (dx, dy, grow) = dock.nudge(wide, tall)
                dock.nudge(wide, tall, dx + step[0], dy + step[1], (grow + step[2]).coerceAtLeast(-20))
            }
            "dock.vertical" -> dock.vertical = flag()
            "dock.blur" -> dock.blur = flag()
            "dock.heat" -> dock.heat = flag()
            "dock.fahrenheit" -> dock.fahrenheit = flag()
            "dock.awake" -> dock.awake = flag()
            "dock.frames" -> dock.frames = flag()
            "awake.session" -> {
                if (flag() && !Settings.canDrawOverlays(this) && !Settings.System.canWrite(this)) { askToHold(); return "asked" }
                AwakeService.set(this, flag())
            }
            "awake.bright" -> AwakeService.bright(this, flag())
            "awake.endOnBattery" -> awake.endOnBattery = flag()
            "awake.batteryFloor" -> awake.batteryFloor = (value as Number).toInt().coerceIn(5, 50)
            "awake.endUnplugged" -> awake.endUnplugged = flag()
            "awake.endFolded" -> awake.endFolded = flag()
            "awake.startCharging" -> { awake.startCharging = flag(); ChargeJob.arm(this) }
            "awake.apps" -> {
                val had = awake.apps
                val chosen = (value as List<*>).map { it as String }.toSet()
                awake.apps = chosen
                // The first app chosen, and no leave to know which app is in front: the setting for it is opened.
                if (had.isEmpty() && chosen.isNotEmpty() && !AwakeService.canSeeApps(this)) openSetting("usage")
                AwakeService.watch(this)
            }
            "awake.method" -> {
                awake.method = value as String
                if (value == "overlay" && !Settings.canDrawOverlays(this)) openSetting("overlay")
                if (value == "system" && !Settings.System.canWrite(this)) openSetting("write")
                // A session already on takes the other way of holding at once.
                if (awake.on) AwakeService.bright(this, awake.bright)
            }
            else -> throw IllegalArgumentException("no setting called $name")
        }
        if (name.startsWith("dock.")) runCatching { DockService.apply(this) }
        return null
    }

    private fun overlaySetting() {
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            .onFailure { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
    }

    /** One of the phone's own settings screens, or the app's walk through them. */
    private fun openSetting(which: String) {
        val open = when (which) {
            "overlay" -> { away = true; overlaySetting(); return }
            "usage" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "write" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
            "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            "setup" -> Intent(this, SetupActivity::class.java)
            else -> throw IllegalArgumentException("no setting called $which")
        }
        away = true
        runCatching { startActivity(open) }.onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    /** Every app that can be opened, by name: what Amphetamine's "while these apps are in front" chooses among. */
    private fun apps(): List<Map<String, String>> {
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launch, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
            .map { mapOf("package" to it.first, "label" to it.second) }
    }
}
