package com.mattssoftware.frames

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's screens are Flutter's (lib/), drawn with the Glacier kit. This is Frames' part of their door to the
 * phone (the activity itself is [com.mattssoftware.tools.HomeActivity], which adds the other tools' settings): whether
 * measuring is on and what it reads now, the switches, an app's icon, the export, and the diagnostics. The runs
 * themselves Flutter reads from their files (Store).
 */
open class MainActivity : FlutterActivity() {
    /** The channel itself, once the engine is up: for telling Flutter a page was asked for (tools/Shell.kt). */
    protected var door: MethodChannel? = null
    private val store by lazy { Store(this) }
    private val prefs by lazy { Prefs(this) }
    private var exporting: List<Session> = emptyList()
    private var exported: MethodChannel.Result? = null
    private var grantingHud = false

    override fun onResume() {
        super.onResume()
        // Back from the setting the readout sent them to: it is on, as they asked.
        if (grantingHud) { grantingHud = false; if (Settings.canDrawOverlays(this)) prefs.hud = true }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        door = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "frames").also {
            it.setMethodCallHandler { call, result -> runCatching { answer(call, result) }.onFailure { result.error("frames", it.message, null) } }
        }
    }

    protected open fun answer(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "state" -> result.success(state())
            "toggle" -> { ProfilerService.set(this, !ProfilerService.running); result.success(null) }
            "scenario" -> { prefs.scenario = (call.arguments as String).trim(); result.success(null) }
            "corner" -> { prefs.corner = call.arguments as Int; result.success(null) }
            "hud" -> result.success(readout())
            "icon" -> { val pkg = call.arguments as String; Thread { val png = icon(pkg); runOnUiThread { result.success(png) } }.start() }
            "animation" -> {
                val scale = (call.arguments as Double).toFloat()
                for (key in listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")) Settings.Global.putFloat(contentResolver, key, scale)
                result.success(null)
            }
            "awake" -> { Settings.Global.putInt(contentResolver, "stay_on_while_plugged_in", if (call.arguments as Boolean) 7 else 0); result.success(null) }
            "diagnostics" -> result.success(diagnostics())
            "probe" -> Thread {
                val list = FrameStats.rawList()
                val names = list.lineSequence().count { it.contains('#') }
                val heat = Thermal.read()
                Trace.add("probe: $names windows listed")
                val words = "The compositor listed $names windows (${list.length} characters)." +
                    (if (names == 0) "\nIt said: ${list.trim().take(200).ifEmpty { "nothing" }}" else "") +
                    "\nTemperatures: skin ${heat.skin ?: "none"}, processor ${heat.cpu ?: "none"}, graphics ${heat.gpu ?: "none"}"
                runOnUiThread { result.success(words) }
            }.start()
            "delete" -> {
                val pkg = call.argument<String>("package")!!; val at = call.argument<Number>("startedAt")?.toLong()
                if (at == null) store.deleteAll(pkg) else File(File(File(filesDir, "sessions"), pkg), "$at.json").delete()
                result.success(null)
            }
            "export" -> {
                val pkg = call.argument<String>("package"); val at = call.argument<Number>("startedAt")?.toLong()
                exporting = (if (pkg == null) store.all() else store.sessions(pkg)).filter { at == null || it.startedAt == at }
                exported?.success(false); exported = result
                val name = if (pkg == null) "frames-all" else if (at == null) "frames-$pkg" else "frames-$pkg-run"
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                    .putExtra(Intent.EXTRA_TITLE, "$name-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.json"), 7)
            }
            else -> result.notImplemented()
        }
    }

    private fun state(): Map<String, Any?> {
        val now = ProfilerService.live
        return mapOf(
            "running" to ProfilerService.running,
            "dump" to Access.dump(this), "usage" to Access.usage(this), "overlay" to Settings.canDrawOverlays(this), "secure" to Access.secure(this),
            "hud" to (prefs.hud && Settings.canDrawOverlays(this)), "corner" to prefs.corner % 4, "scenario" to prefs.scenario,
            "sessions" to File(filesDir, "sessions").absolutePath, "package" to packageName,
            "animation" to Settings.Global.getFloat(contentResolver, "animator_duration_scale", 1f).toDouble(),
            "awake" to (Settings.Global.getInt(contentResolver, "stay_on_while_plugged_in", 0) != 0),
            "live" to now?.let { mapOf("app" to it.app, "fps" to it.fps, "jankPct" to it.jankPct.toDouble(), "frames" to it.frames, "hz" to it.hz.toDouble(), "screen" to it.screen, "recent" to it.recent.map { v -> v.toDouble() }) },
        )
    }

    /** The readout on or off. With no leave to draw over apps, the setting opens at once and it is on when they come back. */
    private fun readout(): String {
        if (prefs.hud && Settings.canDrawOverlays(this)) { prefs.hud = false; return "off" }
        if (Settings.canDrawOverlays(this)) { prefs.hud = true; return "on" }
        grantingHud = true
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            .onFailure { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
        return "asked"
    }

    private fun icon(pkg: String): ByteArray? = runCatching {
        val drawable = packageManager.getApplicationIcon(pkg)
        val bitmap = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, 144, 144); drawable.draw(Canvas(bitmap))
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }.getOrNull()

    private fun diagnostics(): String {
        @Suppress("DEPRECATION") val display = windowManager.defaultDisplay
        val now = ProfilerService.debug
        val modes = display.supportedModes.map { "${it.physicalWidth}x${it.physicalHeight} at ${it.refreshRate.toInt()}" }.distinct().joinToString(", ")
        return listOf(
            "Frames ${packageManager.getPackageInfo(packageName, 0).versionName} on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})",
            "Read frame timings: ${if (Access.dump(this)) "allowed" else "NOT allowed"}",
            "Know the app in front: ${if (Access.usage(this)) "allowed" else "NOT allowed"}",
            "Draw over apps: ${if (Settings.canDrawOverlays(this)) "allowed" else "not allowed"}",
            "Change developer settings: ${if (Access.secure(this)) "allowed" else "not allowed"}",
            "Screen: ${"%.1f".format(display.refreshRate)} Hz now; modes $modes",
            "Core clock speed: ${Cpu.pct()?.let { "$it% of full" } ?: "not readable here"}",
            "Measuring: ${if (ProfilerService.running) "yes" else "no"}",
            if (now == null) "No read yet" else "Last read: ${now.pkg}\nwindow ${now.layer ?: "none"}\n${now.held} frames held, ${now.fresh} new, refresh ${"%.2f".format(now.periodMs)} ms",
            "", Trace.text().ifEmpty { "Nothing done yet. Switch measuring on and open an app." },
        ).joinToString("\n")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7) return
        val uri = data?.data
        val done = resultCode == RESULT_OK && uri != null && runCatching {
            val text = store.export(exporting, "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        }.isSuccess
        exported?.success(done); exported = null
    }
}
