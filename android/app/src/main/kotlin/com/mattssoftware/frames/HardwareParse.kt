package com.mattssoftware.frames

/**
 * The reading of what the phone says about an app's hardware use, kept apart from the asking (Hardware.kt) so it can
 * be tried against text on a computer (src/test). Nothing here touches Android. A reading that cannot be made out is
 * null, never a guess and never a crash.
 */
object HardwareParse {
    /** One process of an app, as `dumpsys activity processes <package>` lists it. Sizes in kilobytes. */
    class Proc(val pid: Int, val name: String, val uid: Int) {
        var pssKb: Long? = null
        var rssKb: Long? = null
        /** How long ago the system took that sample, in milliseconds; null where it never has. */
        var ageMs: Long? = null
    }

    /** What the processes come to together. [kind] is "pss" where the phone still samples it, else "rss". */
    class Memory(val kb: Long, val kind: String, val processes: Int, val youngestMs: Long?)

    private val header = Regex("""^\s*\*(?:APP|PERS)\* UID (\d+) ProcessRecord\{\S+ (\d+):([^/\s]+)/\S*\}""")
    private val field = Regex("""\b(lastPss|lastRss|lastPssTime|lastRssTime)=(\S+)""")
    private val piece = Regex("""(\d+)(ms|d|h|m|s)""")
    private val sized = Regex("""^(\d+(?:[.,]\d+)?)(KB|MB|GB|TB|PB)?$""")

    /**
     * A size as Android's DebugUtils.sizeValueToString writes it ("152MB", "1.2GB", "850KB", "0.00", "512"), in
     * kilobytes. The number is of bytes, cut to two or three figures: 152MB is somewhere from 151.5 to 152.5.
     */
    fun sizeKb(text: String): Long? {
        val m = sized.find(text.trim()) ?: return null
        val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val bytes = when (m.groupValues[2]) {
            "" -> n; "KB" -> n * 1024; "MB" -> n * 1024 * 1024; "GB" -> n * 1024 * 1024 * 1024
            "TB" -> n * 1024.0 * 1024 * 1024 * 1024; else -> n * 1024.0 * 1024 * 1024 * 1024 * 1024
        }
        return Math.round(bytes / 1024)
    }

    /**
     * A length of time as Android's TimeUtils.formatDuration writes it ("-12s345ms", "+1m2s3ms", "-1h0m5s0ms", "0"),
     * as milliseconds without its sign. "--" (never) is null.
     */
    fun durationMs(text: String): Long? {
        val t = text.trim()
        if (t == "0") return 0
        var sum = 0L; var any = false
        for (m in piece.findAll(t)) {
            val n = m.groupValues[1].toLongOrNull() ?: return null
            sum += n * when (m.groupValues[2]) { "d" -> 86_400_000L; "h" -> 3_600_000L; "m" -> 60_000L; "s" -> 1_000L; else -> 1L }
            any = true
        }
        return if (any) sum else null
    }

    /**
     * The processes of a package in `dumpsys activity processes <package>`: each under its line
     * "*APP* UID 10234 ProcessRecord{5b0b98d 5678:com.app/u0a234}", with "lastPss=152MB ... lastRss=302MB" some lines
     * down (Android 15 and later may give only lastRss), and "lastPssTime=-25s612ms" for when. A process not running
     * (pid 0) is left out. Isolated processes (a browser's renderers) are listed by Android without their memory and
     * so are not here.
     */
    fun processes(dump: String): List<Proc> {
        val all = ArrayList<Proc>()
        var current: Proc? = null
        for (line in dump.lineSequence()) {
            val head = header.find(line)
            if (head != null) {
                val pid = head.groupValues[2].toIntOrNull() ?: 0
                current = if (pid <= 0) null else Proc(pid, head.groupValues[3], head.groupValues[1].toIntOrNull() ?: -1).also { all.add(it) }
                continue
            }
            // A process's own lines are set in by four spaces; anything less is the next part of the report.
            if (line.isNotBlank() && !line.startsWith("    ")) { current = null; continue }
            val proc = current ?: continue
            for (m in field.findAll(line)) {
                val value = m.groupValues[2]
                when (m.groupValues[1]) {
                    "lastPss" -> proc.pssKb = sizeKb(value)
                    "lastRss" -> proc.rssKb = sizeKb(value)
                    else -> proc.ageMs = durationMs(value)
                }
            }
        }
        return all
    }

    /** The app's memory from its processes: PSS where any process has one, else RSS; null where none was sampled. */
    fun memory(procs: List<Proc>): Memory? {
        val pss = procs.filter { (it.pssKb ?: 0L) > 0L }
        val rss = procs.filter { (it.rssKb ?: 0L) > 0L }
        val (used, kind) = if (pss.isNotEmpty()) pss to "pss" else rss to "rss"
        if (used.isEmpty()) return null
        val kb = used.sumOf { (if (kind == "pss") it.pssKb else it.rssKb) ?: 0L }
        return Memory(kb, kind, used.size, used.mapNotNull { it.ageMs }.minOrNull())
    }

    /**
     * How busy the cores were, from how long they sat idle: [idleUs] is the growth of every core's idle time added
     * together, [wallUs] the time that passed, over [cores] cores. A core counts its idle time only as it wakes, so a
     * stretch can read a little over or under; it is kept between 0 and 100. Null where nothing can be said.
     */
    fun busyPct(idleUs: Long, wallUs: Long, cores: Int): Float? {
        if (wallUs <= 0L || cores <= 0 || idleUs < 0L) return null
        return ((1.0 - idleUs.toDouble() / (wallUs.toDouble() * cores)) * 100.0).coerceIn(0.0, 100.0).toFloat()
    }

    /**
     * The battery's current in milliamps from what the phone reports, which Android says is microamps and some
     * phones give in milliamps: a number past 20 000 either way is taken as microamps (no phone draws 20 amps, and one
     * with its screen on draws well over 20 mA). The sign is left as it came.
     */
    fun milliamps(raw: Int): Int = if (kotlin.math.abs(raw) > 20_000) raw / 1000 else raw
    fun unitOf(raw: Int): String = if (kotlin.math.abs(raw) > 20_000) "µA" else "mA"

    /** The battery's full charge in mAh, from what it holds now (µAh) and its level; null where that is not believable. */
    fun capacityMah(chargeUah: Long, levelPct: Int): Int? {
        if (chargeUah <= 0L || levelPct < 15 || levelPct > 100) return null
        val mah = chargeUah / 1000.0 * 100.0 / levelPct
        return if (mah in 1000.0..12000.0) mah.toInt() else null
    }
}

/**
 * A line of readings kept small: at most [max] points, each a time (seconds into the run) and a value. When full,
 * neighbours are averaged in pairs, so a long run keeps its shape at half the detail rather than losing its start.
 */
class Series(private val max: Int = 240) {
    val t = ArrayList<Int>()
    val v = ArrayList<Float>()
    val size get() = v.size

    fun add(at: Int, value: Float) {
        t.add(at); v.add(value)
        if (v.size <= max) return
        val nt = ArrayList<Int>(); val nv = ArrayList<Float>()
        var i = 0
        while (i < v.size) {
            if (i + 1 < v.size) { nt.add(t[i]); nv.add((v[i] + v[i + 1]) / 2f); i += 2 } else { nt.add(t[i]); nv.add(v[i]); i += 1 }
        }
        t.clear(); t.addAll(nt); v.clear(); v.addAll(nv)
    }

    fun average(): Float? = if (v.isEmpty()) null else v.sum() / v.size
    fun peak(): Float? = v.maxOrNull()

    /** The highest the value stood over [span] readings together: one reading alone may be a quirk of the counting. */
    fun peakOver(span: Int): Float? {
        if (v.isEmpty()) return null
        if (v.size <= span) return average()
        var best = 0f
        for (i in 0..v.size - span) { var s = 0f; for (j in i until i + span) s += v[j]; if (s / span > best) best = s / span }
        return best
    }
}
