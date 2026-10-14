package com.mattssoftware.xray

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

/**
 * Reads what an app's package files hold: the directory of each .apk (base and splits), which names the files and
 * their sizes without reading them, and the few bytes of three small entries that tell more (the first bytes of a
 * React Native bundle, Flutter's font list, Compose's version). The libraries are looked for in the package files
 * and never in the native library directory, which is empty where Android loads them straight from the .apk
 * (AnatomyParse.kt says what it makes of it).
 *
 * One answer is kept, for the app last looked at, so the page can be left and come back; it is about a package, not
 * about anyone's screen. The log (tag "X-ray") says counts and times only.
 */
object Anatomy {
    private const val TAG = "X-ray"
    private var kept: Pair<String, Map<String, Any?>>? = null

    /** The anatomy of [pkg] as the page reads it, or a map with just `error` where nothing can be said. */
    @Synchronized
    fun of(context: Context, pkg: String): Map<String, Any?> {
        val pm = context.packageManager
        val info = try {
            if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
            else @Suppress("DEPRECATION") pm.getPackageInfo(pkg, PackageManager.GET_META_DATA)
        } catch (e: PackageManager.NameNotFoundException) {
            return mapOf("error" to "This app is not on the phone any more.")
        }
        val code = info.longVersionCode
        val key = "$pkg:$code:${info.lastUpdateTime}"
        kept?.let { if (it.first == key) return it.second }

        val app = info.applicationInfo ?: return mapOf("error" to "Android gave nothing about this app.")
        val started = System.nanoTime()
        val answer = read(app, info.versionName, code)
        Log.i(TAG, "anatomy: ${answer.second} entries in ${answer.third} files, ${(System.nanoTime() - started) / 1_000_000} ms")
        kept = key to answer.first
        return answer.first
    }

    private fun read(app: ApplicationInfo, versionName: String?, code: Long): Triple<Map<String, Any?>, Int, Int> {
        val paths = listOf(app.sourceDir) + (app.splitSourceDirs?.toList() ?: emptyList())
        val entries = ArrayList<AnatomyParse.Entry>()
        val files = ArrayList<Pair<String, Long>>()
        val unreadable = ArrayList<String>()
        var bundleHead: ByteArray? = null
        var fontManifest: String? = null
        var composeVersion: String? = null
        for (path in paths) {
            val name = File(path).name
            try {
                ZipFile(path).use { zip ->
                    files += name to File(path).length()
                    for (e in zip.entries()) entries += AnatomyParse.Entry(e.name, maxOf(e.size, 0), maxOf(e.compressedSize, 0), name)
                    fun small(entry: String, most: Int): ByteArray? = zip.getEntry(entry)?.let { e -> zip.getInputStream(e).use { head(it, most) } }
                    if (bundleHead == null) bundleHead = small("assets/index.android.bundle", 8)
                    if (fontManifest == null) fontManifest = small("assets/flutter_assets/FontManifest.json", 65536)?.toString(Charsets.UTF_8)
                    if (composeVersion == null) composeVersion = small("META-INF/androidx.compose.ui_ui.version", 64)?.toString(Charsets.UTF_8)
                }
            } catch (e: Exception) {
                unreadable += name
            }
        }
        val impeller = (app.metaData?.takeIf { it.containsKey(IMPELLER) })?.getBoolean(IMPELLER)
        val compile = if (Build.VERSION.SDK_INT >= 31) app.compileSdkVersion.takeIf { it > 0 } else null
        val extra = AnatomyParse.Extra(
            app.packageName, versionName, code, app.minSdkVersion, app.targetSdkVersion, compile, impeller, bundleHead, fontManifest, composeVersion,
            files, unreadable, app.sharedLibraryFiles?.size ?: 0,
        )
        val map = if (files.isEmpty()) mapOf("error" to "Android would not let Matt's Tools read this app's package: ${unreadable.joinToString(", ")}.")
            else AnatomyParse.parse(entries, extra).toMap()
        return Triple(map, entries.size, files.size)
    }

    private val kinds = LinkedHashMap<String, XrayTree.Kinds>()

    /**
     * What [pkg] is built with, for the live tree's inspectors (XrayTree.kt `Kinds`): from the names in its package
     * files only, so it costs one read of each directory. A few apps' answers are kept, by version, since the app in
     * front changes back and forth; an app that cannot be read is taken as native.
     */
    @Synchronized
    fun kinds(context: Context, pkg: String): XrayTree.Kinds {
        val none = XrayTree.Kinds(flutter = false, reactNative = false, compose = false)
        val info = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.getOrNull() ?: return none
        val key = "$pkg:${info.longVersionCode}:${info.lastUpdateTime}"
        kinds[key]?.let { return it }
        val app = info.applicationInfo ?: return none
        val names = ArrayList<String>()
        for (path in listOf(app.sourceDir) + (app.splitSourceDirs?.toList() ?: emptyList())) {
            runCatching { ZipFile(path).use { zip -> for (e in zip.entries()) names += e.name } }
        }
        val found = XrayTree.Kinds.of(names)
        kinds[key] = found
        while (kinds.size > 8) kinds.remove(kinds.keys.first())
        return found
    }

    /** Up to [most] bytes from the start of [input] (`readNBytes` is Android 13 and later). */
    private fun head(input: java.io.InputStream, most: Int): ByteArray {
        val buffer = ByteArray(most)
        var have = 0
        while (have < most) {
            val n = input.read(buffer, have, most - have)
            if (n < 0) break
            have += n
        }
        return buffer.copyOf(have)
    }

    private const val IMPELLER = "io.flutter.embedding.android.EnableImpeller"
}
