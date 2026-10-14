package com.mattssoftware.xray

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * X-ray's one method on the app's channel (lib/xray/xray.dart `Xray`): the argument is a map whose `do` says what is
 * asked, so the shared door (tools/Shell.kt) has one line for X-ray and no more. Anything that reads a package runs
 * off the main thread and is answered back on it.
 */
object Door {
    fun answer(activity: Activity, call: MethodCall, result: MethodChannel.Result) {
        when (val ask = call.argument<String>("do")) {
            "apps" -> work(activity, result) { apps(activity) }
            "anatomy" -> {
                val pkg = call.argument<String>("package") ?: return result.error("frames", "no package asked for", null)
                work(activity, result) { Anatomy.of(activity, pkg) }
            }
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
