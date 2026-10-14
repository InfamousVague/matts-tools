package com.mattssoftware.xray

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import com.mattssoftware.frames.FrameStats
import com.mattssoftware.frames.Shell

/**
 * Reads the compositor's layers for the app in front, through what Frames already runs (frames/Core.kt): its list
 * and the whole dump of SurfaceFlinger. Frames' `Trace`, which logs layer names, is not used. The whole dump is taken
 * at most once a second, on X-ray's reading thread or once on Open; its text is read by LayerParse.kt and dropped, and
 * only the app's layers are kept, in the capture. Android gives the dump to an app only once a computer has granted
 * it `android.permission.DUMP`, as for Frames; without that, [Read.why] says so.
 */
object Layers {
    /** The app's layers bottom to top, or null with [why] saying why there are none to be had. */
    class Read(val pkg: String, val layers: List<LayerParse.AppLayer>?, val why: String?)

    const val WHY = "Not available: Android lets an app read the compositor only after a computer says so, once."
    fun command(c: Context) = "adb shell pm grant ${c.packageName} android.permission.DUMP"

    private var last: Read? = null
    private var lastAt = 0L
    /** Whole dumps taken, for the log's counts. */
    var reads = 0
        private set

    @Synchronized fun read(c: Context, pkg: String, flutter: Boolean): Read {
        if (c.checkSelfPermission("android.permission.DUMP") != PackageManager.PERMISSION_GRANTED) return Read(pkg, null, WHY)
        val now = SystemClock.uptimeMillis()
        last?.let { if (it.pkg == pkg && now - lastAt < 1000L) return it }
        val list = FrameStats.rawList()
        // A phone that refuses says so in words where the list would be; the list always has the display's own layers.
        if (!list.contains('#')) return Read(pkg, null, WHY).also { last = it; lastAt = now }
        val places = LayerParse.places(Shell.dumpsys("SurfaceFlinger"))
        val layers = LayerParse.of(list, pkg, places, flutter)
        reads += 1
        if (reads == 1) Log.i("X-ray", "layers: ${layers.size} of the app's, ${places.size} places, ${SystemClock.uptimeMillis() - now} ms")
        return Read(pkg, layers, null).also { last = it; lastAt = now }
    }

    /** X-ray closed: nothing of the compositor is held. */
    @Synchronized fun forget() { last = null; reads = 0 }
}
