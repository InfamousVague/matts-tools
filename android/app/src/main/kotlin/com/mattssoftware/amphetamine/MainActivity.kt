package com.mattssoftware.amphetamine

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * The one screen: the switch and how long it has been on, full brightness, what ends a session, what starts
 * one, how the screen is held, and the permissions each of those needs, asked for where it is wanted.
 */
class MainActivity : Activity() {
    private val prefs by lazy { Prefs(this) }
    private lateinit var page: LinearLayout
    private val changed = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = draw()
    }

    private val ink = 0xFF0D0D0D.toInt()
    private val paper = 0xFFF5F5F5.toInt()
    private val quiet = 0xFF9A9A9A.toInt()
    private val card = 0xFF171717.toInt()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(40)) }
        setContentView(ScrollView(this).apply { addView(page); fitsSystemWindows = true })
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /** A tap that found no permission: the setting is open, and the session starts when it has been allowed. */
    private var granting = false

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent) }

    private fun askToHold() {
        granting = true
        android.widget.Toast.makeText(this, "Allow Amphetamine to appear on top, then come back", android.widget.Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    override fun onResume() {
        super.onResume()
        val allowed = Settings.canDrawOverlays(this) || Settings.System.canWrite(this)
        if (intent.getBooleanExtra("grant", false)) {
            intent.removeExtra("grant")
            if (!allowed) askToHold()
        } else if (granting && allowed) {
            granting = false
            if (!prefs.on) AwakeService.set(this, true)
        }
        registerReceiver(changed, IntentFilter(AwakeService.CHANGED), RECEIVER_NOT_EXPORTED)
        draw()
    }

    override fun onPause() { unregisterReceiver(changed); super.onPause() }

    private fun draw() {
        page.removeAllViews()
        val on = prefs.on
        val overlay = Settings.canDrawOverlays(this)
        val system = Settings.System.canWrite(this)

        // ---- the switch ----
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(if (on) paper else card) }
            setPadding(dp(24), dp(36), dp(24), dp(36))
            setOnClickListener {
                if (!overlay && !system) askToHold() else AwakeService.set(this@MainActivity, !on)
            }
        }
        hero.addView(TextView(this).apply {
            text = if (on) "Awake" else "Asleep"; textSize = 34f; setTextColor(if (on) ink else paper)
            typeface = com.mattssoftware.tools.Ink.bold(context); gravity = Gravity.CENTER
        })
        if (on) hero.addView(Chronometer(this).apply {
            base = prefs.since; textSize = 22f; setTextColor(ink); gravity = Gravity.CENTER
            fontFeatureSettings = "tnum"; start()
        })
        hero.addView(TextView(this).apply {
            text = when {
                !on -> "Tap to keep the screen on"
                prefs.why == "charger" -> "On since it was plugged in. Tap to stop"
                prefs.why == "app" -> "On for the app in front. Tap to stop"
                else -> "The screen will not sleep. Tap to stop"
            }
            textSize = 14f; setTextColor(if (on) 0xFF4A4A4A.toInt() else quiet); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0)
        })
        page.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (!overlay && !system) note("Amphetamine needs one of two permissions before it can hold the screen: see \"How the screen is held\" below.")

        // ---- brightness ----
        heading("While awake")
        switchRow("Full brightness", "Also on its own tile in the pull-down.", prefs.bright) { AwakeService.bright(this, it) }

        // ---- what ends it ----
        heading("End a session")
        switchRow("When the battery is low", "At ${prefs.batteryFloor}% and not charging.", prefs.endOnBattery) { prefs.endOnBattery = it; draw() }
        if (prefs.endOnBattery) page.addView(SeekBar(this).apply {
            max = 45; progress = prefs.batteryFloor - 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) { if (user) prefs.batteryFloor = value + 5 }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = draw()
            })
        })
        switchRow("When unplugged", "Only a session that began on the charger.", prefs.endUnplugged) { prefs.endUnplugged = it }
        switchRow("When the phone is folded shut", "A session begun on the cover screen is left alone.", prefs.endFolded) { prefs.endFolded = it }

        // ---- what starts it ----
        heading("Start one by itself")
        switchRow("When plugged in", "Begins as the charger goes in.", prefs.startCharging) { prefs.startCharging = it; ChargeJob.arm(this) }
        val seen = AwakeService.canSeeApps(this)
        val names = prefs.apps.mapNotNull { runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }.getOrNull() }.sorted()
        row("While these apps are in front", if (names.isEmpty()) "None chosen." else names.joinToString(", ")) { pickApps() }
        if (prefs.apps.isNotEmpty() && !seen) action("Allow usage access", "Needed to know which app is in front.") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        // ---- how ----
        heading("How the screen is held")
        choice("An unseen window", "Over every app; changes nothing of the phone's. " + if (overlay) "Allowed." else "Needs \"appear on top\".",
            prefs.method == "overlay") {
            prefs.method = "overlay"
            if (!overlay) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            rehold(); draw()
        }
        choice("The phone's own settings", "Sets the screen timeout to its longest and puts it back after. " + if (system) "Allowed." else "Needs \"modify system settings\".",
            prefs.method == "system") {
            prefs.method = "system"
            if (!system) startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            rehold(); draw()
        }

        note("Add the Amphetamine tile from the pull-down's edit button, and the pill from the home screen's widgets. Version ${packageManager.getPackageInfo(packageName, 0).versionName}.")
    }

    /** A session already on takes the other way of holding at once. */
    private fun rehold() { if (prefs.on) AwakeService.bright(this, prefs.bright) }

    private fun pickApps() {
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launch, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
        val chosen = prefs.apps.toMutableSet()
        val checked = BooleanArray(apps.size) { apps[it].first in chosen }
        AlertDialog.Builder(this).setTitle("Keep awake while in front")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, which, isOn ->
                if (isOn) chosen.add(apps[which].first) else chosen.remove(apps[which].first)
            }
            .setPositiveButton("Done") { _, _ ->
                prefs.apps = chosen
                if (chosen.isNotEmpty() && !AwakeService.canSeeApps(this)) startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                AwakeService.watch(this)
                draw()
            }.setNegativeButton("Cancel", null).show()
    }

    // ---- the page's parts ----

    private fun heading(words: String) = page.addView(TextView(this).apply {
        text = words.uppercase(); textSize = 12f; letterSpacing = 0.08f; setTextColor(quiet)
        typeface = com.mattssoftware.tools.Ink.medium(context); setPadding(dp(4), dp(28), 0, dp(8))
    })

    private fun note(words: String) = page.addView(TextView(this).apply {
        text = words; textSize = 13f; setTextColor(quiet); setPadding(dp(4), dp(20), dp(4), 0); setLineSpacing(0f, 1.25f)
    })

    private fun cell(title: String, hint: String, trailing: View?, press: (() -> Unit)?): LinearLayout {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(card) }
            setPadding(dp(18), dp(14), dp(14), dp(14))
            if (press != null) setOnClickListener { press() }
        }
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        words.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(paper) })
        words.addView(TextView(this).apply { text = hint; textSize = 13f; setTextColor(quiet); setPadding(0, dp(2), 0, 0) })
        line.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) line.addView(trailing)
        page.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        return line
    }

    private fun switchRow(title: String, hint: String, on: Boolean, set: (Boolean) -> Unit) {
        val switch = Switch(this).apply { isChecked = on; setOnCheckedChangeListener { _, value -> set(value) } }
        cell(title, hint, switch) { switch.toggle() }
    }

    private fun row(title: String, hint: String, press: () -> Unit) = cell(title, hint, TextView(this).apply { text = "›"; textSize = 22f; setTextColor(quiet) }, press)
    private fun action(title: String, hint: String, press: () -> Unit) = cell(title, hint, TextView(this).apply { text = "Allow"; textSize = 14f; setTextColor(paper) }, press)
    private fun choice(title: String, hint: String, picked: Boolean, press: () -> Unit) =
        cell(title, hint, TextView(this).apply { text = if (picked) "●" else "○"; textSize = 18f; setTextColor(if (picked) paper else quiet) }, press)
}
