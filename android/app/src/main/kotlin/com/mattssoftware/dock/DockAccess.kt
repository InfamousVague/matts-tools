package com.mattssoftware.dock

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Why the dock is an accessibility service at all: beside the camera it is in the status bar's strip, and the status
 * bar takes every touch there from a window drawn over apps. A window of an accessibility service is drawn ABOVE the
 * status bar, so a tap reaches it. That is all this is for: it asks for no events and reads nothing of the screen
 * (res/xml/dock_access.xml), and [DockService] draws through it when it is on.
 */
class DockAccess : AccessibilityService() {
    override fun onServiceConnected() { instance = this; runCatching { DockService.apply(this) } }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; runCatching { DockService.apply(this) }; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }

    companion object {
        @Volatile var instance: DockAccess? = null

        /** Switched on in the phone's settings, whether or not it has started yet. */
        fun enabled(context: Context): Boolean =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains("${context.packageName}/") == true
    }
}
