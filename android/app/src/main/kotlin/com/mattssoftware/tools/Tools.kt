package com.mattssoftware.tools

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mattssoftware.amphetamine.AwakeStart
import com.mattssoftware.dock.DockAccess
import com.mattssoftware.dock.DockService
import com.mattssoftware.frames.ProfilerService

/**
 * Matt's Tools: the dock, Amphetamine, Frames and Toasty in one app. Each was an app of its own and keeps its own
 * package here (`com.mattssoftware.dock`, `.amphetamine`, `.frames`); this package is what holds them together: the
 * start, the app's one activity with every tool's settings behind it (Shell.kt), and the setting-up.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AwakeStart.run(this)
        ProfilerService.channel(this)
        runCatching { DockService.apply(this) }
        // Measuring that an update or a restart of the app cut short is taken up again.
        runCatching { if (com.mattssoftware.frames.Prefs(this).measuring && com.mattssoftware.frames.Access.ready(this)) ProfilerService.set(this, true) }
    }
}

/**
 * After an update, and after the phone starts: measuring that was on is taken up again. It is done here and not as
 * the app starts, because Android refuses a foreground service started from the background ("startForegroundService()
 * not allowed") except from a few places, and the receiver of these two broadcasts is one of them. Started from
 * [App], Frames stayed off after every update while still saying it was measuring.
 */
class Boot : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { if (com.mattssoftware.frames.Prefs(context).measuring && com.mattssoftware.frames.Access.ready(context)) ProfilerService.set(context, true) }
    }
}

/** The type and the colours every page of the app is set in: Ghost.md's, ink and paper and Inter. */
object Ink {
    const val INK = 0xFF0D0D0D.toInt()
    const val PAPER = 0xFFF5F5F5.toInt()
    const val QUIET = 0xFF9A9A9A.toInt()
    const val CARD = 0xFF171717.toInt()
    const val LINE = 0x1FFFFFFF
    const val GOOD = 0xFF7BD88F.toInt()

    private var inter: Typeface? = null
    private fun face(context: Context, weight: Int): Typeface {
        val base = inter ?: (runCatching { context.resources.getFont(R.font.inter) }.getOrNull() ?: Typeface.DEFAULT).also { inter = it }
        return Typeface.create(base, weight, false)
    }
    fun regular(context: Context) = face(context, 400)
    fun medium(context: Context) = face(context, 500)
    fun bold(context: Context) = face(context, 650)
}

/** What each permission stands at, for the page of tools and the setting-up. */
object Granted {
    fun notifications(c: Context) = Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun overlay(c: Context) = Settings.canDrawOverlays(c)
    /** The Dock's own service, by its whole name: X-ray's switch being on says nothing of the Dock's taps. */
    fun access(c: Context) = com.mattssoftware.xray.Switched.on(c, DockAccess::class.java.name)
    /** X-ray's own service, by its whole name. */
    fun xray(c: Context) = com.mattssoftware.xray.Switched.xray(c)
    fun usage(c: Context): Boolean {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
    }
    fun dump(c: Context) = c.checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED
}

/** A page's parts, shared by the pages of this package. */
abstract class Page : Activity() {
    protected lateinit var page: LinearLayout
    protected fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(40)) }
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; isVerticalScrollBarEnabled = false; addView(page) })
    }

    protected fun text(words: String, size: Float, color: Int = Ink.PAPER, weight: Int = 400) = TextView(this).apply {
        text = words; textSize = size; setTextColor(color); setLineSpacing(0f, 1.25f)
        typeface = when { weight >= 600 -> Ink.bold(context); weight >= 500 -> Ink.medium(context); else -> Ink.regular(context) }
    }
    protected fun title(words: String) = page.addView(text(words, 30f, Ink.PAPER, 650).apply { letterSpacing = -0.02f; setPadding(dp(4), 0, 0, dp(6)) })
    protected fun heading(words: String) = page.addView(text(words.uppercase(), 12f, Ink.QUIET, 500).apply { letterSpacing = 0.08f; setPadding(dp(4), dp(26), 0, dp(6)) })
    protected fun note(words: String) = page.addView(text(words, 14f, Ink.QUIET).apply { setPadding(dp(4), dp(8), dp(4), dp(4)) })
    protected fun row(head: String, value: String, press: (() -> Unit)?) {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Ink.CARD); setStroke(1, Ink.LINE) }
            setPadding(dp(18), dp(17), dp(18), dp(17)); if (press != null) setOnClickListener { press() }
        }
        line.addView(text(head, 16f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(text(value, 15f, Ink.QUIET))
        page.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }
    protected fun button(words: String, solid: Boolean, press: () -> Unit) = page.addView(text(words, 15f, if (solid) Ink.INK else Ink.PAPER, 500).apply {
        gravity = Gravity.CENTER; setPadding(dp(16), dp(15), dp(16), dp(15)); setOnClickListener { press() }
        background = GradientDrawable().apply { cornerRadius = dp(999).toFloat(); if (solid) setColor(Ink.PAPER) else { setColor(0); setStroke(dp(1), 0x33FFFFFF) } }
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
}

/**
 * The setting-up: one thing a page, in order, each with what it is for and a button that opens the phone's own
 * setting for it. Coming back with it given moves on by itself. Nothing here is needed to look round the app, so
 * every page can be passed over.
 */
class SetupActivity : Page() {
    private class Step(val title: String, val why: String, val button: String, val optional: Boolean, val done: (Context) -> Boolean, val open: (SetupActivity) -> Unit, val more: String = "")
    private val steps by lazy {
        listOf(
            Step("Notifications", "Android asks every tool that keeps running to show a notification while it does: the dock, a keep-awake session, a measuring run.", "Allow notifications", false,
                { Granted.notifications(it) }, { if (Build.VERSION.SDK_INT >= 33) it.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }),
            Step("Display over other apps", "The dock floats over whatever is on screen, Frames can draw its readout over the app it measures, and Amphetamine holds the screen on with a window you cannot see.", "Open the setting", false,
                { Granted.overlay(it) }, { a -> a.settings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${a.packageName}"))) }),
            Step("Which app is in front", "Frames files its measurements under the app they were of, and Amphetamine can stay on only while apps you choose are in front. Find Matt's Tools in the list and allow it.", "Open the setting", false,
                { Granted.usage(it) }, { a -> a.settings(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }),
            Step("Taps on the dock beside the camera", "Only if you dock it beside the camera. The status bar takes every touch on its strip, and the one kind of window Android draws above it is an accessibility service's. This one asks for no events and reads nothing on the screen. Under Installed apps, switch on Matt's Tools.", "Open Accessibility", true,
                { Granted.access(it) }, { a -> a.settings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                "If the switch is greyed out, the phone is holding back an app it did not install from a store: open Settings, Apps, Matt's Tools, the three dots at the top, Allow restricted settings, then come back."),
            Step("X-ray", "Only for X-ray. To show what an app's screen is made of, X-ray reads it through an accessibility service of its own, separate from the dock's, and only while you have started it from its tile. It never reads password fields and writes nothing down. Under Installed apps (Downloaded apps on some phones), switch on Matt's Tools: X-ray.", "Open Accessibility", true,
                { Granted.xray(it) }, { a -> a.settings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                "If the switch is greyed out, the phone is holding back an app it did not install from a store: open Settings, Apps, Matt's Tools, the three dots at the top, Allow restricted settings, then come back."),
            Step("Frame timings, from a computer", "For Frames, and for X-ray's compositor layers. Android lets an app read another app's frame timings after a computer says so, once. With the phone plugged in or on wireless debugging:", "I have run it", true,
                { Granted.dump(it) }, { it.draw() },
                "adb shell pm grant com.mattssoftware.tools android.permission.DUMP"),
        )
    }
    private var at = 0
    private var waiting = false
    private val kept by lazy { getSharedPreferences("tools", Context.MODE_PRIVATE) }

    fun settings(intent: Intent) { waiting = true; runCatching { startActivity(intent) }.onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) } }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // Shown once by itself; after that it is opened from the page of tools.
        kept.edit().putBoolean("setUp", true).apply()
        // Opened again later: at the first thing still to do.
        at = steps.indexOfFirst { !it.done(this) }.let { if (it < 0) 0 else it }
    }

    override fun onResume() {
        super.onResume()
        // Back from the phone's settings with it given: on to the next.
        if (waiting && steps[at].done(this) && at < steps.size - 1) at += 1
        waiting = false
        draw()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        if (steps[at].done(this) && at < steps.size - 1) at += 1
        draw()
    }

    @Deprecated("Deprecated in Java") override fun onBackPressed() { if (at > 0) { at -= 1; draw() } else finishSetup() }

    private fun finishSetup() {
        kept.edit().putBoolean("setUp", true).apply()
        runCatching { DockService.apply(this) }
        finish()
    }

    fun draw() {
        page.removeAllViews()
        val step = steps[at]
        val done = step.done(this)
        page.addView(text("${at + 1} of ${steps.size}" + if (step.optional) " · optional" else "", 13f, Ink.QUIET, 500).apply { letterSpacing = 0.06f; setPadding(dp(4), 0, 0, dp(10)) })
        // Where it has got to: a line of marks, filled for what is given.
        val marks = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), 0, 0, dp(22)) }
        for ((i, each) in steps.withIndex()) marks.addView(View(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(if (each.done(context)) Ink.PAPER else if (i == at) 0x66F5F5F5 else 0x26F5F5F5) }
        }, LinearLayout.LayoutParams(dp(28), dp(4)).apply { rightMargin = dp(6) })
        page.addView(marks)
        title(step.title)
        note(step.why)
        if (step.more.isNotEmpty()) {
            val box = LinearLayout(this).apply { background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Ink.CARD); setStroke(1, Ink.LINE) }; setPadding(dp(16), dp(14), dp(16), dp(14)) }
            box.addView(text(step.more, 13.5f, if (step.more.startsWith("adb ")) Ink.PAPER else Ink.QUIET).apply { if (step.more.startsWith("adb ")) typeface = Typeface.MONOSPACE; setTextIsSelectable(true) })
            page.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        page.addView(text(if (done) "Given" else "Not given yet", 14f, if (done) Ink.GOOD else Ink.QUIET, 500).apply { setPadding(dp(4), dp(18), 0, dp(2)) })
        val last = at == steps.size - 1
        if (!done) button(step.button, true) { step.open(this) }
        button(if (last) "Finish" else if (done) "Next" else "Not now", done) { if (last) finishSetup() else { at += 1; draw() } }
    }
}
