package com.mattssoftware.frames

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.ceil

/** One frame: when it reached the screen, and how long after the one before. */
class Frame(val vsyncNs: Long, val totalMs: Float)

/**
 * Reads frames from the compositor: `dumpsys SurfaceFlinger --latency <layer>` gives, for the last 127 frames a
 * window showed, when each was wanted on screen, when it got there, and when it was ready, in nanoseconds, under a
 * first line that is one refresh of the screen. (Android's own per-app timings, `dumpsys gfxinfo`, are closed to an
 * ordinary app by the system's security policy even with the permission; the compositor's are not. They also see
 * what gfxinfo cannot: a game or a video drawing to a surface of its own.)
 *
 * A frame's time is how long after the frame before it reached the screen: the cadence the eye sees. A frame
 * the app only began after a pause (it was wanted well after the last one showed) starts a new run and is not a
 * slow frame; its time is how late it was against when it was wanted, plus one refresh.
 */
object FrameStats {
    class Read(val layer: String, val periodMs: Float, val frames: List<Frame>)

    private const val PENDING = Long.MAX_VALUE

    private fun dumpsys(vararg args: String): String = Shell.dumpsys("SurfaceFlinger", *args)

    /** What the compositor's list says, as it came: for the diagnostics page. */
    fun rawList(): String = dumpsys("--list")

    /** The windows the compositor holds for [pkg], by the names `--latency` takes. */
    fun layersOf(pkg: String): List<String> = layerNames(dumpsys("--list"), pkg)

    fun layerNames(list: String, pkg: String): List<String> = list.lineSequence().mapNotNull { raw ->
        // "RequestedLayerState{5b0b98d SurfaceView[com.app/com.app.Main]@0(BLAST)#9394 parentId=9393}", or the bare name
        // on older phones. The letters and digits before a name are part of it: `--latency` asked for the name
        // without them answers with nothing, which was Ghost.md (a Flutter app, drawn to a surface of its own)
        // reading no frames at all. So the whole name is kept, and only looked at without them.
        val name = raw.trim().removePrefix("RequestedLayerState{").substringBefore(" parentId").removeSuffix("}")
        val bare = if (Regex("^[0-9a-f]{5,8} ").containsMatchIn(name)) name.substringAfter(' ') else name
        // Android 17 keeps a window's frames on a layer of the view root's own, under the window's: "VRI-com.app/
        // com.app.Main$_19800#7520". The window's own layer is listed still and holds none. All are offered, and the
        // one with the newest frames is taken ([newest]).
        name.takeIf { bare.contains('#') && (bare.startsWith("$pkg/") || bare.startsWith("VRI-$pkg/") || bare.startsWith("SurfaceView[$pkg/") || bare.startsWith("SurfaceView - $pkg/")) }
    }.distinct().toList()

    fun read(layer: String): Read = parse(layer, dumpsys("--latency", layer))

    fun parse(layer: String, dump: String): Read {
        val lines = dump.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val periodNs = lines.firstOrNull()?.toLongOrNull() ?: 16_666_666L
        val rows = lines.drop(1).mapNotNull { line ->
            val cells = line.split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
            if (cells.size < 3 || cells[1] == 0L || cells[1] == PENDING || cells[0] == PENDING) null else cells
        }.sortedBy { it[1] }
        val frames = ArrayList<Frame>()
        for (i in rows.indices) {
            val wanted = rows[i][0]; val shown = rows[i][1]
            val before = if (i == 0) null else rows[i - 1][1]
            if (before == null) continue
            val ns = if (wanted - before < periodNs * 2) shown - before else (shown - wanted).coerceAtLeast(0L) + periodNs
            if (ns > 0L) frames.add(Frame(shown, ns / 1_000_000f))
        }
        return Read(layer, periodNs / 1_000_000f, frames)
    }

    /** The window of [pkg] that is drawing now: of its layers, the one whose newest frame is newest. */
    fun newest(pkg: String): Read? = layersOf(pkg).map { read(it) }.filter { it.frames.isNotEmpty() }.maxByOrNull { it.frames.last().vsyncNs }
}

/** The system's own reports, as text. One it will not give an app comes back as its refusal. */
object Shell {
    fun dumpsys(vararg args: String): String = runCatching {
        val process = ProcessBuilder(listOf("dumpsys") + args).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor()
        text
    }.getOrDefault("")
}

/** What Frames did lately, newest last: which window it took, what it kept, what went wrong. Read on the diagnostics page. */
object Trace {
    private val lines = ArrayDeque<String>()
    private val clock = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    @Synchronized fun add(line: String) { android.util.Log.i("Frames", line); lines.addLast("${clock.format(java.util.Date())}  $line"); while (lines.size > 200) lines.removeFirst() }
    @Synchronized fun text(): String = lines.joinToString("\n")
}

/** The phone's own temperatures, where it reports them: `dumpsys thermalservice`, the hottest of each kind. */
object Thermal {
    class Now(val cpu: Float?, val gpu: Float?, val skin: Float?)
    private val row = Regex("Temperature\\{mValue=([-0-9.]+), mType=(\\d+)")
    fun read(): Now {
        val hottest = HashMap<Int, Float>()
        for (m in row.findAll(Shell.dumpsys("thermalservice"))) {
            val v = m.groupValues[1].toFloatOrNull() ?: continue
            val type = m.groupValues[2].toInt()
            if (v > (hottest[type] ?: -999f)) hottest[type] = v
        }
        return Now(hottest[0], hottest[1], hottest[3])
    }
}

/** How fast the processor's cores are clocked, as a share of their fastest: not load, but what the governor asked for. */
object Cpu {
    private val cores = (0 until Runtime.getRuntime().availableProcessors()).map { File("/sys/devices/system/cpu/cpu$it/cpufreq") }
    private fun number(file: File): Long? = runCatching { file.readText().trim().toLong() }.getOrNull()
    fun pct(): Int? {
        var sum = 0.0; var n = 0
        for (core in cores) {
            val now = number(File(core, "scaling_cur_freq")) ?: continue
            val max = number(File(core, "cpuinfo_max_freq")) ?: continue
            if (max > 0) { sum += now.toDouble() / max; n += 1 }
        }
        return if (n == 0) null else (sum * 100 / n).toInt()
    }
}

/** What a run of frames comes to. Times in milliseconds; [periodMs] is one refresh of the screen it ran on. */
class Stats(val frames: Int, val activeSeconds: Int, val periodMs: Float, times: FloatArray) {
    private val sorted = times.sortedArray()
    private fun pct(p: Double): Float = if (sorted.isEmpty()) 0f else sorted[(ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
    val p50 = pct(0.50); val p90 = pct(0.90); val p95 = pct(0.95); val p99 = pct(0.99)
    val worst = sorted.lastOrNull() ?: 0f

    /** Frames drawn a second, over the seconds in which anything was drawn: a still screen draws nothing and is not slow. */
    val fps: Float = if (activeSeconds == 0) 0f else frames.toFloat() / activeSeconds
    /** What the slowest hundredth ran at, said as a rate. */
    val low1Fps: Float = if (p99 <= 0f) 0f else (1000f / p99).coerceAtMost(1000f / periodMs)
    /** Missed a refresh: a gap of a period and a half or more between frames. */
    val janky = sorted.count { it > periodMs * 1.5f }
    /** Missed two or more: seen as a stutter. */
    val slow = sorted.count { it > periodMs * 2.5f }
    /** Over 700 ms, the platform's own line for a frozen frame. */
    val frozen = sorted.count { it > 700f }
    val jankPct: Float = if (frames == 0) 0f else janky * 100f / frames
    /** Frames by how many refreshes each missed: none, one, two or three, four to seven, eight or more. */
    val missed: List<Pair<String, Int>> = listOf(
        "0" to sorted.count { it <= periodMs * 1.5f },
        "1" to sorted.count { it > periodMs * 1.5f && it <= periodMs * 2.5f },
        "2-3" to sorted.count { it > periodMs * 2.5f && it <= periodMs * 4.5f },
        "4-7" to sorted.count { it > periodMs * 4.5f && it <= periodMs * 8.5f },
        "8+" to sorted.count { it > periodMs * 8.5f },
    )

    fun json() = JSONObject().apply {
        put("frames", frames); put("activeSeconds", activeSeconds); put("periodMs", periodMs.toDouble())
        put("fps", fps.toDouble()); put("low1Fps", low1Fps.toDouble())
        put("p50", p50.toDouble()); put("p90", p90.toDouble()); put("p95", p95.toDouble()); put("p99", p99.toDouble()); put("worst", worst.toDouble())
        put("janky", janky); put("slow", slow); put("frozen", frozen); put("jankPct", jankPct.toDouble())
        put("missedRefreshes", JSONObject().also { all -> missed.forEach { all.put(it.first, it.second) } })
    }
}

/** One stay in one app while measuring: which build, when, the screen and the phone's state, and every frame's time. */
class Session(
    val pkg: String, val label: String, val versionName: String, val versionCode: Long,
    val startedAt: Long, val scenario: String, var refreshHz: Float,
) {
    var endedAt: Long = startedAt
    val times = ArrayList<Float>()
    /** A point a second: frames drawn, the worst of them, and the phone's temperature and draw. */
    val seconds = JSONArray()
    var activeSeconds = 0
    var startTempC = 0f; var endTempC = 0f
    var thermalWorst = 0
    var currentMaSum = 0L; var currentSamples = 0
    val periodMs get() = 1000f / refreshHz.coerceAtLeast(1f)

    /** One screen of the app (an activity): its share of the frames. */
    class Screen { var frames = 0; var janky = 0; var slow = 0; var worst = 0f; var seconds = 0 }
    val screens = LinkedHashMap<String, Screen>()
    /** Every slow frame, up to [MAX_EVENTS]: when in the run, how long, on which screen. */
    val events = JSONArray()
    /** The clock the compositor stamps frames with, as the run began. */
    var startNano = 0L
    /** What the app cost the phone (Hardware.kt): written as the run closes. Null on a run from before it was kept. */
    var hardware: JSONObject? = null

    fun add(frame: Frame, screen: String) {
        times.add(frame.totalMs)
        val on = screens.getOrPut(screen) { Screen() }
        on.frames += 1
        if (frame.totalMs > on.worst) on.worst = frame.totalMs
        if (frame.totalMs > periodMs * 1.5f) on.janky += 1
        if (frame.totalMs > periodMs * 2.5f) {
            on.slow += 1
            if (events.length() < MAX_EVENTS) events.put(JSONObject().apply {
                put("t", ((frame.vsyncNs - startNano) / 1_000_000L).coerceAtLeast(0L)); put("ms", (frame.totalMs * 10).toInt() / 10.0)
                put("screen", screen); put("kind", if (frame.totalMs > 700f) "frozen" else "slow")
            })
        }
    }

    fun stats() = Stats(times.size, activeSeconds, periodMs, times.toFloatArray())

    fun json(withFrames: Boolean) = JSONObject().apply {
        put("package", pkg); put("app", label); put("versionName", versionName); put("versionCode", versionCode)
        put("startedAt", startedAt); put("endedAt", endedAt); put("scenario", scenario); put("refreshHz", refreshHz.toDouble())
        put("startTempC", startTempC.toDouble()); put("endTempC", endTempC.toDouble()); put("thermalWorst", thermalWorst)
        put("avgCurrentMa", if (currentSamples == 0) 0 else currentMaSum / currentSamples)
        put("stats", stats().json())
        put("screens", JSONArray().also { all -> screens.forEach { (name, s) -> all.put(JSONObject().apply {
            put("name", name); put("frames", s.frames); put("janky", s.janky); put("slow", s.slow)
            put("worstMs", s.worst.toDouble()); put("seconds", s.seconds); put("jankPct", if (s.frames == 0) 0.0 else s.janky * 100.0 / s.frames)
        }) } })
        put("slowFrames", events)
        put("seconds", seconds)
        hardware?.let { put("hardware", it) }
        if (withFrames) put("frameMs", JSONArray().also { all -> times.forEach { all.put((it * 100).toInt() / 100.0) } })
    }

    companion object {
        const val MAX_EVENTS = 500
        fun from(o: JSONObject): Session = Session(
            o.getString("package"), o.optString("app"), o.optString("versionName"), o.optLong("versionCode"),
            o.getLong("startedAt"), o.optString("scenario"), o.optDouble("refreshHz", 60.0).toFloat(),
        ).apply {
            endedAt = o.optLong("endedAt", startedAt)
            startTempC = o.optDouble("startTempC", 0.0).toFloat(); endTempC = o.optDouble("endTempC", 0.0).toFloat()
            thermalWorst = o.optInt("thermalWorst")
            hardware = o.optJSONObject("hardware")
            o.optJSONArray("seconds")?.let { for (i in 0 until it.length()) seconds.put(it.get(i)) }
            o.optJSONArray("slowFrames")?.let { for (i in 0 until it.length()) events.put(it.get(i)) }
            o.optJSONArray("screens")?.let { for (i in 0 until it.length()) { val s = it.getJSONObject(i); screens[s.optString("name")] = Screen().apply {
                frames = s.optInt("frames"); janky = s.optInt("janky"); slow = s.optInt("slow"); worst = s.optDouble("worstMs", 0.0).toFloat(); seconds = s.optInt("seconds")
            } } }
            activeSeconds = o.optJSONObject("stats")?.optInt("activeSeconds") ?: 0
            o.optJSONArray("frameMs")?.let { for (i in 0 until it.length()) times.add(it.getDouble(i).toFloat()) }
            val ma = o.optLong("avgCurrentMa"); if (ma != 0L) { currentMaSum = ma; currentSamples = 1 }
        }
    }
}

/** The sessions kept on the phone: a file each, under the app measured. */
class Store(context: Context) {
    private val root = File(context.filesDir, "sessions").apply { mkdirs() }

    fun save(session: Session) {
        if (session.times.size < MIN_FRAMES) return
        val dir = File(root, session.pkg).apply { mkdirs() }
        File(dir, "${session.startedAt}.json").writeText(session.json(true).toString())
    }

    fun packages(): List<String> = root.list()?.toList().orEmpty()
    fun sessions(pkg: String): List<Session> = File(root, pkg).listFiles().orEmpty()
        .mapNotNull { runCatching { Session.from(JSONObject(it.readText())) }.getOrNull() }.sortedByDescending { it.startedAt }
    fun all(): List<Session> = packages().flatMap { sessions(it) }.sortedByDescending { it.startedAt }
    fun delete(session: Session) { File(File(root, session.pkg), "${session.startedAt}.json").delete() }
    fun deleteAll(pkg: String) { File(root, pkg).deleteRecursively() }

    /** Everything, as one document a chart on a computer can read. */
    fun export(sessions: List<Session>, device: String): String = JSONObject().apply {
        put("format", "frames/v2"); put("exportedAt", System.currentTimeMillis()); put("device", device)
        put("notes", "Times are milliseconds. A frame time is the gap since the frame before reached the screen. Janky is a gap of 1.5 refresh periods or more (a missed refresh), slow 2.5 or more, frozen past 700 ms. fps counts only seconds in which frames were drawn. missedRefreshes counts frames by how many refreshes each missed. screens are the app's activities. slowFrames lists each slow or frozen frame: t is milliseconds into the run. In seconds: hz is the refresh rate, availMb the memory the phone had free, rxKb and txKb the whole phone's network traffic in that second, cpuFreqPct the cores' clock speed as a share of their fastest (not load), skinC, cpuC and gpuC the phone's own temperatures, thermal its throttling status from 0 to 6. A reading the phone would not give is left out. Since frames/v2 a session may have hardware (absent on older runs; {off: true} where it was switched off): what the app cost the phone. Each part has its numbers, or a why saying the phone would not give them; a missing number is never 0. cpu: devicePct and devicePeakPct are the whole phone's processor use over its cores (from the cores' idle time; the peak is over six seconds), appPct the app's own processor time as a percent of one core and appOfDevicePct of all of them, appMs that time (the system counts it only on battery, so appPartly marks a run partly plugged in). memory: avgMb and peakMb over the system's own samples of the app's processes (kind pss or rss; isolated processes such as a browser's renderers are not in it). battery: the whole phone's draw while unplugged, avgDrawMa, avgPowerMw, usedMah, and pctPerHour against capacityMah (worked out from the charge counter and the level); charging says some of the run was plugged in; reportedIn and dischargeReads say how this phone gives its current. data: the app's own bytes, rxBytes and txBytes, Wi-Fi and mobile apart where given, and per minute; coarse marks a run too short for the system's totals to have caught up. series holds cpu as [seconds into the run, percent], memoryMb as [seconds, MB], data as [seconds, bytes down so far, bytes up so far]. cost is what the reading itself took, by kind: reads, avgMs, worstMs, and cpuMs on Frames' own thread.")
        put("sessions", JSONArray().also { all -> sessions.forEach { all.put(it.json(true)) } })
    }.toString(2)

    companion object { const val MIN_FRAMES = 30 }
}

/** Sessions of one build put together: the sum of their frames, and how the runs spread. */
class AppBuild(val versionName: String, val versionCode: Long, val scenario: String, val sessions: List<Session>) {
    val stats: Stats = Stats(
        sessions.sumOf { it.times.size }, sessions.sumOf { it.activeSeconds },
        sessions.map { it.periodMs }.average().toFloat(), sessions.flatMap { it.times }.toFloatArray(),
    )
    private val jank = sessions.map { it.stats().jankPct }
    /** The least and most janky run: a difference between builds smaller than this spread is noise. */
    val jankLow = jank.minOrNull() ?: 0f
    val jankHigh = jank.maxOrNull() ?: 0f
    val name get() = "$versionName ($versionCode)" + if (scenario.isEmpty()) "" else " · $scenario"
}

fun buildsOf(sessions: List<Session>): List<AppBuild> = sessions.groupBy { Triple(it.versionName, it.versionCode, it.scenario) }
    .map { (key, runs) -> AppBuild(key.first, key.second, key.third, runs) }.sortedByDescending { it.sessions.maxOf { s -> s.startedAt } }
