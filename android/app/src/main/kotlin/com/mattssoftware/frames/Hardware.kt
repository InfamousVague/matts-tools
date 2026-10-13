package com.mattssoftware.frames

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Debug
import android.os.SystemClock
import android.os.health.HealthStats
import android.os.health.ProcessHealthStats
import android.os.health.SystemHealthManager
import android.os.health.UidHealthStats
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What the app in front cost the phone while it was measured: processor, memory, battery and data. Everything here
 * is what an ordinary app holding DUMP and usage access may read of another app on Android 14 to 16; where the
 * system keeps a number from such an app, the run says "not available" with the reason, and never 0.
 *
 * Where each comes from, and what was tried and is closed (checked against Android 16's sources and its security
 * policy, system/sepolicy):
 *
 *  - Processor, the whole phone: how long each core sat idle (/sys/devices/system/cpu/cpuN/cpuidle/stateK/time, the
 *    same part of sysfs the cores' clock speed is read from). /proc/stat is closed to apps since Android 8.
 *  - Processor, the app: the system's own count of the app's processor time, through SystemHealthManager
 *    .takeUidSnapshot, which wants android.permission.BATTERY_STATS for another app (given from a computer, like
 *    DUMP). Android keeps that count only while on battery, and brings it up to date when apps come and go and no
 *    more often than every five seconds, so it is an average for the run and not a line over time. /proc/<pid> of
 *    another app is hidden (hidepid) and closed by policy, an app's cgroup likewise, and `dumpsys cpuinfo` is a
 *    service an app may not find.
 *  - Memory: the system's last sample of each of the app's processes, from `dumpsys activity processes <package>`
 *    (PSS, or RSS on phones that stopped sampling PSS). The system samples an app in front ten seconds after it
 *    comes up and then about once a minute. `dumpsys meminfo` is a service an app may not find, and
 *    ActivityManager.getProcessMemoryInfo answers zeros for another app.
 *  - Battery: the phone's whole draw, from BatteryManager's current and the battery's voltage each second, counted
 *    only while unplugged. What one app alone drew is the system's estimate (BatteryStatsManager), which is closed
 *    to apps; none is made up here.
 *  - Data: the app's bytes over Wi-Fi and mobile from NetworkStatsManager.queryDetailsForUid (usage access is
 *    enough), read as a running total at the start and the end. The system brings its totals up to date for a
 *    caller at most every fifteen seconds. TrafficStats answers "unsupported" for another app.
 *
 * Every read is made on Frames' own thread (never the main one), one heavy read at most in a second, a read that
 * would come late is skipped and not queued, and each kind's cost is timed and said in the log as the run closes.
 */
class Hardware(private val context: Context) {
    /** What one kind of read cost: how many, how long by the clock, how long on this thread's processor. */
    class Cost { var reads = 0; var wallNs = 0L; var worstNs = 0L; var cpuNs = 0L
        fun json() = JSONObject().apply {
            put("reads", reads); put("avgMs", round2(if (reads == 0) 0.0 else wallNs / 1e6 / reads)); put("worstMs", round2(worstNs / 1e6)); put("cpuMs", round2(cpuNs / 1e6))
        }
        fun words(name: String) = "$name ${reads}x ${"%.1f".format(if (reads == 0) 0.0 else wallNs / 1e6 / reads)} ms (worst ${"%.1f".format(worstNs / 1e6)}, cpu ${"%.1f".format(cpuNs / 1e6)} in all)"
    }

    private class Bytes(val wifiRx: Long, val wifiTx: Long, val mobileRx: Long?, val mobileTx: Long?, val mobileWhy: String?)

    // ---- one run's state ----
    private var on = false
    private var pkg = ""
    private var uid = -1
    private var startedAt = 0L
    private var opened = 0L
    private var k = 0
    private var lastTick = 0L
    private var lastHeavy = 0L
    private var skipped = 0
    private val costs = LinkedHashMap<String, Cost>()

    private val idle = Idle()
    private var idleFirst: Pair<Long, Long>? = null
    private var idleLast: Pair<Long, Long>? = null
    private val cpuSeries = Series()

    private var cpuStart: LongArray? = null
    private var cpuStartAt = 0L
    private var cpuWhy: String? = null

    private val memSeries = Series(120)
    private var memKind = ""
    private var memProcesses = 0
    private var memWhy: String? = null
    private var memReads = 0
    private var memLastRead = 0L
    private var memDue = false

    private var drawSum = 0.0; private var powerSum = 0.0; private var mahSum = 0.0
    private var unplugged = 0; private var plugged = 0
    private var rawSign = 0; private var unit = ""
    private var capacity: Int? = null
    private var levelStart = -1; private var levelEnd = -1
    private var chargeStart: Long? = null
    private var lastBatteryAt = 0L

    private var netStart: Bytes? = null
    private var netWhy: String? = null
    private var netFrom = 0L
    private val netSeries = JSONArray()

    private inline fun <T> timed(kind: String, read: () -> T): T {
        val wall = System.nanoTime(); val cpu = Debug.threadCpuTimeNanos()
        try { return read() } finally {
            val took = System.nanoTime() - wall
            costs.getOrPut(kind) { Cost() }.apply { reads += 1; wallNs += took; if (took > worstNs) worstNs = took; cpuNs += Debug.threadCpuTimeNanos() - cpu }
        }
    }

    /** A run begins: what is there to read on this phone is found out, and the running totals are taken. */
    fun open(session: Session, enabled: Boolean) {
        on = enabled
        pkg = session.pkg; startedAt = session.startedAt; opened = SystemClock.elapsedRealtime()
        k = 0; lastTick = 0L; lastHeavy = 0L; skipped = 0; costs.clear()
        idleFirst = null; idleLast = null; cpuSeries.t.clear(); cpuSeries.v.clear()
        cpuStart = null; cpuWhy = null
        memSeries.t.clear(); memSeries.v.clear(); memKind = ""; memProcesses = 0; memWhy = null; memReads = 0; memLastRead = opened; memDue = false
        drawSum = 0.0; powerSum = 0.0; mahSum = 0.0; unplugged = 0; plugged = 0; rawSign = 0; unit = ""; capacity = null; levelStart = -1; levelEnd = -1; chargeStart = null; lastBatteryAt = 0L
        netStart = null; netWhy = null; netFrom = startedAt - 6 * 3_600_000L
        while (netSeries.length() > 0) netSeries.remove(0)
        if (!on) return
        uid = runCatching { context.packageManager.getApplicationInfo(pkg, 0).uid }.getOrDefault(-1)

        idleFirst = timed("idle") { idle.read() }; idleLast = idleFirst
        if (uid < 0) { cpuWhy = "the app's id on this phone could not be found"; netWhy = cpuWhy }
        else {
            when (val got = timed("health") { cpuMs(uid) }) {
                is LongArray -> { cpuStart = got; cpuStartAt = SystemClock.elapsedRealtime() }
                else -> cpuWhy = got as String
            }
            when (val got = timed("data") { bytes(uid) }) {
                is Bytes -> netStart = got
                else -> netWhy = got as String
            }
        }
        lastHeavy = SystemClock.elapsedRealtime()
        Trace.add("hardware for $pkg (uid $uid): cores' idle time ${if (idle.cores == 0) "not readable" else "from ${idle.files} files on ${idle.cores} cores"}, " +
            "its processor time ${cpuWhy?.let { "not available ($it)" } ?: "counted"}, its data ${netWhy?.let { "not available ($it)" } ?: "counted"}")
    }

    /**
     * Each second of a run. [battery] is the battery's standing report (already asked for by the caller), [rawCurrent]
     * its current as the phone gave it. [busy] says another heavy read (the temperatures) was made this second.
     */
    fun second(battery: Intent?, rawCurrent: Int, busy: Boolean) {
        if (!on) return
        val now = SystemClock.elapsedRealtime()
        val late = lastTick != 0L && now - lastTick > 1_600L
        lastTick = now
        k += 1
        val t = ((now - opened) / 1000).toInt()

        // The battery: nothing new is asked of the phone for this.
        val isPlugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        var mv = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        if (mv in 1..99) mv *= 1000
        if (level >= 0) { if (levelStart < 0) levelStart = level; levelEnd = level }
        val ma = HardwareParse.milliamps(rawCurrent)
        if (unit.isEmpty() && rawCurrent != 0) unit = HardwareParse.unitOf(rawCurrent)
        val span = if (lastBatteryAt == 0L) 1.0 else ((now - lastBatteryAt) / 1000.0).coerceIn(0.2, 5.0)
        lastBatteryAt = now
        if (isPlugged) plugged += 1 else {
            // Unplugged, the current can only be leaving the battery: its size is the draw, whichever sign the phone gives it.
            unplugged += 1
            if (rawSign == 0 && kotlin.math.abs(ma) > 5) rawSign = if (ma > 0) 1 else -1
            val draw = kotlin.math.abs(ma).toDouble()
            drawSum += draw; mahSum += draw * span / 3600.0
            if (mv > 0) powerSum += draw * mv / 1000.0
        }
        if (k == 1) timed("battery") {
            val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val charge = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).toLong()
            if (charge > 0L) { chargeStart = charge; capacity = HardwareParse.capacityMah(charge, level) }
        }

        // The cores, every other second: a few dozen small files.
        if (k % 2 == 0 && idle.cores > 0) {
            val before = idleLast
            val read = timed("idle") { idle.read() }
            if (read != null && before != null) {
                HardwareParse.busyPct(read.first - before.first, (read.second - before.second) / 1000, idle.cores)?.let { cpuSeries.add(t, it) }
            }
            if (read != null) { idleLast = read; if (idleFirst == null) idleFirst = read }
        }

        // One heavy read at most in a second, and none in a second that started late or just after another.
        if (k >= 12 && (k - 12) % MEMORY_EVERY == 0) memDue = true
        val dataDue = k % DATA_EVERY == 0 && netStart != null
        if (!memDue && !dataDue) return
        if (busy || late || now - lastHeavy < 900L) { if (dataDue) skipped += 1; return }
        if (memDue) { memDue = false; memory(t) }
        else timed("data") { bytes(uid) }.let { got -> val first = netStart; if (got is Bytes && first != null) netSeries.put(JSONArray().put(t).put(got.rx() - first.rx()).put(got.tx() - first.tx())) }
        lastHeavy = SystemClock.elapsedRealtime()
    }

    private fun Bytes.rx() = wifiRx + (mobileRx ?: 0L)
    private fun Bytes.tx() = wifiTx + (mobileTx ?: 0L)

    private fun memory(t: Int) {
        val now = SystemClock.elapsedRealtime()
        val dump = timed("memory") { Shell.dumpsys("activity", "processes", pkg) }
        memReads += 1
        val procs = HardwareParse.processes(dump)
        val got = HardwareParse.memory(procs)
        val since = now - memLastRead
        memLastRead = now
        if (got == null) {
            if (memSeries.size == 0) memWhy = if (procs.isEmpty()) "the phone would not list the app's processes (${dump.trim().lineSequence().firstOrNull().orEmpty().take(80).ifEmpty { "it said nothing" }})"
                else "the phone had not sampled the app's memory yet"
            return
        }
        // Only a sample the system took since the last look counts: the same one read twice is one reading.
        val age = got.youngestMs
        if (age != null && age > since + 1_500L && memSeries.size > 0) return
        if (age != null && age > now - opened + 5_000L) { if (memSeries.size == 0) memWhy = "the phone had not sampled the app's memory during the run"; return }
        memWhy = null; memKind = got.kind; memProcesses = maxOf(memProcesses, got.processes)
        val mb = got.kb / 1024f
        memSeries.add(t, mb)
    }

    /**
     * The run is over: the last totals are taken and everything is written into the session. [reads] is false where
     * there is no thread to ask the phone on (the service is being ended by the system): what is in hand is written.
     */
    fun close(session: Session, reads: Boolean = true) {
        if (!on) { session.hardware = JSONObject().put("v", VERSION).put("off", true); return }
        val now = SystemClock.elapsedRealtime()
        val lasted = ((now - opened) / 1000.0).coerceAtLeast(1.0)
        val out = JSONObject().put("v", VERSION).put("seconds", lasted.toInt())

        // Processor.
        val cpu = JSONObject().put("cores", Runtime.getRuntime().availableProcessors())
        val first = idleFirst; val last = idleLast
        val whole = if (first != null && last != null && last.second > first.second) HardwareParse.busyPct(last.first - first.first, (last.second - first.second) / 1000, idle.cores) else null
        if (whole != null) {
            cpu.put("devicePct", round1(whole)); cpuSeries.peakOver(3)?.let { cpu.put("devicePeakPct", round1(maxOf(it, whole))) }
        } else cpu.put("deviceWhy", if (idle.cores == 0) "this phone does not show how long its cores sit idle" else "the run was too short to read the cores twice")
        val start = cpuStart
        if (!reads && cpuWhy == null) cpuWhy = "measuring was ended by the phone before the last reading"
        if (start != null && cpuWhy == null) {
            val end = timed("health") { cpuMs(uid) }
            // Two counts, each brought up to date at its own times: the one that moved further is nearer the truth.
            val used = if (end is LongArray) maxOf(end[0] - start[0], end[1] - start[1]) else -1L
            val wallMs = (SystemClock.elapsedRealtime() - cpuStartAt).coerceAtLeast(1L)
            when {
                end !is LongArray -> cpuWhy = end as String
                unplugged == 0 && plugged > 0 -> cpuWhy = "the phone counts an app's processor time only on battery, and it was plugged in throughout"
                used < 0L -> cpuWhy = "the phone's count was reset during the run"
                used == 0L -> cpuWhy = "the phone had counted none for the app by the end of the run"
                else -> {
                    // Counted only while unplugged: over those seconds where some of the run was plugged in.
                    val over = if (plugged > 0 && unplugged > 0) wallMs * unplugged / (plugged + unplugged) else wallMs
                    val pct = used * 100.0 / over.coerceAtLeast(1L)
                    cpu.put("appMs", used); cpu.put("appPct", round1(pct)); cpu.put("appOfDevicePct", round1(pct / Runtime.getRuntime().availableProcessors()))
                    if (plugged > 0) cpu.put("appPartly", true)
                }
            }
        }
        cpuWhy?.let { cpu.put("appWhy", it) }
        out.put("cpu", cpu)

        // Memory.
        val memory = JSONObject()
        val avg = memSeries.average()
        if (avg != null) { memory.put("avgMb", round1(avg)); memory.put("peakMb", round1(memSeries.peak() ?: avg)); memory.put("kind", memKind); memory.put("samples", memSeries.size); memory.put("processes", memProcesses) }
        else memory.put("why", memWhy ?: "the run ended before the phone sampled the app's memory (it does so ten seconds after an app comes up, then about once a minute)")
        out.put("memory", memory)

        // Battery.
        val battery = JSONObject().put("pluggedSeconds", plugged).put("unpluggedSeconds", unplugged)
        if (levelStart >= 0) { battery.put("levelStart", levelStart); battery.put("levelEnd", levelEnd) }
        if (unit.isNotEmpty()) battery.put("reportedIn", unit)
        if (rawSign != 0) battery.put("dischargeReads", if (rawSign > 0) "positive" else "negative")
        if (unplugged > 0 && drawSum > 0.0) {
            val draw = drawSum / unplugged
            battery.put("avgDrawMa", round1(draw)); battery.put("usedMah", round2(mahSum))
            if (powerSum > 0.0) battery.put("avgPowerMw", round1(powerSum / unplugged))
            capacity?.let { battery.put("capacityMah", it); battery.put("pctPerHour", round1(draw * 100.0 / it)) }
            if (plugged > 0) battery.put("charging", true)
            if (reads) timed("battery") {
                val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val charge = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).toLong()
                val begun = chargeStart
                if (plugged == 0 && begun != null && charge in 1..begun) battery.put("counterMah", round2((begun - charge) / 1000.0))
            }
        } else battery.put("why", if (plugged > 0) "the phone was plugged in throughout, so what the battery took or gave says nothing of the app" else "the phone gave no current for its battery").put("charging", plugged > 0)
        out.put("battery", battery)

        // Data.
        val data = JSONObject()
        val begun = netStart
        if (!reads && netWhy == null) netWhy = "measuring was ended by the phone before the last reading"
        if (begun != null && netWhy == null) {
            when (val end = timed("data") { bytes(uid) }) {
                is Bytes -> {
                    val wifiRx = (end.wifiRx - begun.wifiRx).coerceAtLeast(0L); val wifiTx = (end.wifiTx - begun.wifiTx).coerceAtLeast(0L)
                    data.put("wifiRxBytes", wifiRx); data.put("wifiTxBytes", wifiTx)
                    var rx = wifiRx; var tx = wifiTx
                    if (end.mobileRx != null && begun.mobileRx != null && end.mobileTx != null && begun.mobileTx != null) {
                        val mobileRx = (end.mobileRx - begun.mobileRx).coerceAtLeast(0L); val mobileTx = (end.mobileTx - begun.mobileTx).coerceAtLeast(0L)
                        data.put("mobileRxBytes", mobileRx); data.put("mobileTxBytes", mobileTx); rx += mobileRx; tx += mobileTx
                    } else data.put("mobileWhy", end.mobileWhy ?: begun.mobileWhy ?: "not given")
                    data.put("rxBytes", rx); data.put("txBytes", tx)
                    data.put("rxPerMin", Math.round(rx * 60.0 / lasted)); data.put("txPerMin", Math.round(tx * 60.0 / lasted))
                    // The system brings its totals up to date for a caller at most every fifteen seconds.
                    if (lasted < 20.0) data.put("coarse", true)
                }
                else -> netWhy = end as String
            }
        }
        netWhy?.let { data.put("why", it) }
        out.put("data", data)

        out.put("series", JSONObject().apply {
            if (cpuSeries.size > 0) put("cpu", points(cpuSeries))
            if (memSeries.size > 0) put("memoryMb", points(memSeries))
            if (netSeries.length() > 0) put("data", netSeries)
        })
        out.put("cost", JSONObject().also { all -> costs.forEach { (kind, cost) -> all.put(kind, cost.json()) } })
        if (skipped > 0) out.put("skipped", skipped)
        session.hardware = out

        fun said(o: JSONObject, key: String, why: String, words: (JSONObject) -> String) = if (o.has(key)) words(o) else "not available (${o.optString(why)})"
        Trace.add("hardware of $pkg: phone's processor " + said(cpu, "devicePct", "deviceWhy") { "${it.optDouble("devicePct")}% (at most ${it.optDouble("devicePeakPct")}%)" } +
            "; app's processor " + said(cpu, "appPct", "appWhy") { "${it.optDouble("appPct")}% of a core, ${it.optLong("appMs")} ms" } +
            "; memory " + said(memory, "avgMb", "why") { "${it.optDouble("avgMb")} MB ${it.optString("kind")}, at most ${it.optDouble("peakMb")}, ${it.optInt("samples")} samples of ${it.optInt("processes")} processes, $memReads reads" } +
            "; battery " + said(battery, "avgDrawMa", "why") { "${it.optDouble("avgDrawMa")} mA, ${it.optDouble("avgPowerMw")} mW, ${it.opt("pctPerHour") ?: "?"}% an hour" } +
            " (reported in ${unit.ifEmpty { "?" }}, discharge reads ${if (rawSign == 0) "?" else if (rawSign > 0) "positive" else "negative"}, $plugged s plugged in)" +
            "; data " + said(data, "rxBytes", "why") { "${it.optLong("rxBytes")} bytes down, ${it.optLong("txBytes")} up${if (it.has("mobileWhy")) ", mobile not available (${it.optString("mobileWhy")})" else ""}" })
        Trace.add("hardware cost: " + costs.entries.joinToString("; ") { it.value.words(it.key) } + "; $skipped skipped")
    }

    private fun points(series: Series) = JSONArray().also { all -> for (i in 0 until series.size) all.put(JSONArray().put(series.t[i]).put(round1(series.v[i]))) }

    /** The app's processor time so far in milliseconds as the system counts it (see the other [cpuMs]), or the reason it will not say. */
    private fun cpuMs(uid: Int): Any {
        if (context.checkSelfPermission("android.permission.BATTERY_STATS") != PackageManager.PERMISSION_GRANTED) return NEEDS_BATTERY_STATS
        return runCatching {
            val stats: HealthStats = context.getSystemService(SystemHealthManager::class.java).takeUidSnapshot(uid)
            cpuMs(stats) as Any
        }.getOrElse { "the phone refused: ${it.javaClass.simpleName} ${it.message.orEmpty().take(80)}" }
    }

    /** One app's totals so far over Wi-Fi and mobile since [netFrom], or the reason there are none. */
    private fun bytes(uid: Int): Any {
        val manager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager ?: return "the phone has no network statistics"
        // To well past now, so the bucket being filled is taken whole and not in proportion.
        val until = System.currentTimeMillis() + 4 * 3_600_000L
        fun total(type: Int): Pair<Long, Long> {
            var rx = 0L; var tx = 0L
            val stats = manager.queryDetailsForUid(type, null, netFrom, until, uid)
            try { val bucket = NetworkStats.Bucket(); while (stats.hasNextBucket()) { stats.getNextBucket(bucket); rx += bucket.rxBytes; tx += bucket.txBytes } } finally { stats.close() }
            return rx to tx
        }
        @Suppress("DEPRECATION") val wifi = runCatching { total(android.net.ConnectivityManager.TYPE_WIFI) }
            .getOrElse { return "the phone refused: ${it.javaClass.simpleName} ${it.message.orEmpty().take(80)}" }
        @Suppress("DEPRECATION") val mobile = runCatching { total(android.net.ConnectivityManager.TYPE_MOBILE) }
        return Bytes(wifi.first, wifi.second, mobile.getOrNull()?.first, mobile.getOrNull()?.second,
            mobile.exceptionOrNull()?.let { "${it.javaClass.simpleName} ${it.message.orEmpty().take(60)}" })
    }

    /**
     * Tries each read once against Frames itself and says what came back and what it cost: for the diagnostics page,
     * where it shows what this phone will give before a run depends on it. Not to be called on the main thread.
     */
    fun probe(): String {
        val own = context.packageName; val me = android.os.Process.myUid()
        fun cost(kind: String) = costs[kind]?.let { "${"%.1f".format(it.worstNs / 1e6)} ms" } ?: "?"
        costs.clear(); netFrom = System.currentTimeMillis() - 6 * 3_600_000L
        val a = timed("idle") { idle.read() }
        val dump = timed("memory") { Shell.dumpsys("activity", "processes", own) }
        val procs = HardwareParse.processes(dump); val mem = HardwareParse.memory(procs)
        val net = timed("data") { bytes(me) }
        val health = timed("health") { runCatching { cpuMs(context.getSystemService(SystemHealthManager::class.java).takeMyUidSnapshot()) as Any }.getOrElse { it.toString() } }
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val raw = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val charge = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val sticky = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return listOf(
            "Cores' idle time: " + (if (a == null) "not readable" else "${idle.files} files on ${idle.cores} cores") + ", ${cost("idle")}",
            "Memory (Frames' own processes): " + (mem?.let { "${it.kb / 1024} MB ${it.kind} of ${it.processes}, sampled ${it.youngestMs?.let { ms -> "${ms / 1000}s ago" } ?: "at an unknown time"}" }
                ?: if (procs.isEmpty()) "no processes listed; it said: ${dump.trim().take(160).ifEmpty { "nothing" }}" else "${procs.size} processes listed, none sampled yet") + ", ${cost("memory")}",
            "Data (Frames' own): " + (if (net is Bytes) "Wi-Fi ${net.wifiRx} down ${net.wifiTx} up, mobile ${net.mobileRx?.let { "$it down ${net.mobileTx} up" } ?: "not available (${net.mobileWhy})"}" else "not available ($net)") + ", ${cost("data")}",
            "Processor time (Frames' own): " + (if (health is LongArray) "${health[0]} ms by process, ${health[1]} ms for the app" else "$health") + ", ${cost("health")}",
            "Another app's processor time: " + if (context.checkSelfPermission("android.permission.BATTERY_STATS") == PackageManager.PERMISSION_GRANTED) "allowed" else "not allowed ($NEEDS_BATTERY_STATS)",
            "Battery: current $raw as given (${HardwareParse.milliamps(raw)} mA, taken as ${HardwareParse.unitOf(raw)}), charge counter $charge, " +
                "${sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)} mV, ${if ((sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0) "plugged in" else "on battery"}",
        ).joinToString("\n")
    }

    /** Every core's idle time, added together. */
    private class Idle {
        private val all: List<List<File>> = (0 until Runtime.getRuntime().availableProcessors()).map { core ->
            File("/sys/devices/system/cpu/cpu$core/cpuidle").listFiles { f -> f.name.startsWith("state") }.orEmpty().map { File(it, "time") }.filter { it.canRead() }
        }.filter { it.isNotEmpty() }
        val cores get() = all.size
        val files get() = all.sumOf { it.size }

        /** Microseconds idle so far over all cores, and the clock (nanoseconds) it was read at; null where any file would not read. */
        fun read(): Pair<Long, Long>? {
            if (all.isEmpty()) return null
            var sum = 0L
            for (core in all) for (file in core) sum += runCatching { file.readText().trim().toLong() }.getOrNull() ?: return null
            return sum to System.nanoTime()
        }
    }

    companion object {
        const val VERSION = 1
        const val MEMORY_EVERY = 15
        const val DATA_EVERY = 60
        const val NEEDS_BATTERY_STATS = "needs android.permission.BATTERY_STATS, given once from a computer"
        fun round1(v: Number) = Math.round(v.toDouble() * 10) / 10.0
        fun round2(v: Number) = Math.round(v.toDouble() * 100) / 100.0

        /** An app's processor time in a health snapshot, two ways: its processes' own counts added up, and the count for the app as a whole. */
        fun cpuMs(stats: HealthStats): LongArray {
            var byProcess = 0L
            if (stats.hasStats(UidHealthStats.STATS_PROCESSES)) for (p in stats.getStats(UidHealthStats.STATS_PROCESSES).values) {
                if (p.hasMeasurement(ProcessHealthStats.MEASUREMENT_USER_TIME_MS)) byProcess += p.getMeasurement(ProcessHealthStats.MEASUREMENT_USER_TIME_MS)
                if (p.hasMeasurement(ProcessHealthStats.MEASUREMENT_SYSTEM_TIME_MS)) byProcess += p.getMeasurement(ProcessHealthStats.MEASUREMENT_SYSTEM_TIME_MS)
            }
            var whole = 0L
            if (stats.hasMeasurement(UidHealthStats.MEASUREMENT_USER_CPU_TIME_MS)) whole += stats.getMeasurement(UidHealthStats.MEASUREMENT_USER_CPU_TIME_MS)
            if (stats.hasMeasurement(UidHealthStats.MEASUREMENT_SYSTEM_CPU_TIME_MS)) whole += stats.getMeasurement(UidHealthStats.MEASUREMENT_SYSTEM_CPU_TIME_MS)
            return longArrayOf(byProcess, whole)
        }
    }
}
