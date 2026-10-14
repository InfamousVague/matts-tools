package com.mattssoftware.xray

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * X-ray's one method on the app's channel (lib/xray/xray.dart `Xray`): the argument is a map whose `do` says what is
 * asked, so the shared door (tools/Shell.kt) has one line for X-ray and no more. Anything that reads a package runs
 * off the main thread and is answered back on it. The last reading of a screen is already in memory
 * ([XrayAccess.capture]) and is answered at once.
 */
object Door {
    fun answer(activity: Activity, call: MethodCall, result: MethodChannel.Result) {
        when (val ask = call.argument<String>("do")) {
            "apps" -> work(activity, result) { apps(activity) }
            "anatomy" -> {
                val pkg = call.argument<String>("package") ?: return result.error("frames", "no package asked for", null)
                work(activity, result) { Anatomy.of(activity, pkg) }
            }
            "state" -> result.success(
                mapOf(
                    "on" to Switched.xray(activity), "running" to XrayAccess.running,
                    "dump" to (activity.checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED),
                    "capture" to XrayAccess.capture?.summary(),
                ),
            )
            "capture" -> result.success(XrayAccess.capture?.toMap())
            "ask" -> {
                // Straight to the setting, with the line saying what to switch on (the brief: "if there is a
                // setting that needs turned on the app should prompt immediatly when tapping").
                Toast.makeText(activity, XrayTile.ASK, Toast.LENGTH_LONG).show()
                runCatching { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    .onFailure { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                result.success(null)
            }
            "private" -> {
                // The tree and exploded pages show another app's boxes, and X-ray reads even a screen its app keeps out of
                // screenshots: while they are up the window is secure, so no screenshot, recording, cast or Recents
                // picture holds them. Recents pictures are written to disk, and only this keeps them out on Android 10
                // to 12L (the brief: "Nothing of another app's screen is written to disk").
                if (call.argument<Boolean>("on") == true) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                result.success(null)
            }
            "forget" -> { XrayAccess.forget(); result.success(null) }
            else -> result.error("frames", "no X-ray call named $ask", null)
        }
    }

    private fun work(activity: Activity, result: MethodChannel.Result, read: () -> Any?) {
        Thread {
            val answer = try { read() } catch (e: Exception) { mapOf("error" to "X-ray could not read that: ${e.javaClass.simpleName}.") }
            activity.runOnUiThread { result.success(answer) }
        }.start()
    }

    /** Every app that can be opened, by name, Matt's Tools among them: X-ray can be pointed at itself. */
    private fun apps(activity: Activity): List<Map<String, String>> {
        val pm: PackageManager = activity.packageManager
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launch, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }.sortedBy { it.second.lowercase() }
            .map { mapOf("package" to it.first, "label" to it.second) }
    }
}
