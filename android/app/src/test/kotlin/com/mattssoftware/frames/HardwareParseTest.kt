package com.mattssoftware.frames

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reading of the phone's reports, tried against text written out from the formats in Android 16's sources
 * (ProcessList.dumpProcessesLSP, ProcessRecord.dump, ProcessProfileRecord.dumpPss, DebugUtils.sizeValueToString,
 * TimeUtils.formatDuration). They are not captures from a phone.
 */
class HardwareParseTest {
    private fun text(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun sizesAsAndroidWritesThem() {
        assertEquals(152L * 1024, HardwareParse.sizeKb("152MB"))
        assertEquals(850L, HardwareParse.sizeKb("850KB"))
        assertEquals(Math.round(1.2 * 1024 * 1024), HardwareParse.sizeKb("1.2GB"))
        assertEquals(Math.round(9.4 * 1024), HardwareParse.sizeKb("9.4MB"))
        assertEquals(0L, HardwareParse.sizeKb("0.00"))
        assertEquals(1L, HardwareParse.sizeKb("512"))
        // A phone whose language writes a comma for the point.
        assertEquals(Math.round(3.1 * 1024), HardwareParse.sizeKb("3,1MB"))
        assertNull(HardwareParse.sizeKb("lots"))
        assertNull(HardwareParse.sizeKb(""))
    }

    @Test fun lengthsOfTimeAsAndroidWritesThem() {
        assertEquals(25_612L, HardwareParse.durationMs("-25s612ms"))
        assertEquals(62_003L, HardwareParse.durationMs("-1m2s3ms"))
        assertEquals(3_605_000L, HardwareParse.durationMs("+1h0m5s0ms"))
        assertEquals(90_000_000L, HardwareParse.durationMs("-1d1h0m0s0ms"))
        assertEquals(0L, HardwareParse.durationMs("0"))
        assertNull(HardwareParse.durationMs("--"))
        assertNull(HardwareParse.durationMs("soon"))
    }

    @Test fun anAppsProcessesWhereThePhoneSamplesPss() {
        val procs = HardwareParse.processes(text("processes_pss.txt"))
        // The one not running (pid 0) and the isolated one (listed without its memory) are not there.
        assertEquals(listOf(5678, 5701), procs.map { it.pid })
        assertEquals(listOf("com.example.reader", "com.example.reader:sync"), procs.map { it.name })
        assertEquals(10234, procs[0].uid)
        assertEquals(152L * 1024, procs[0].pssKb); assertEquals(302L * 1024, procs[0].rssKb); assertEquals(25_612L, procs[0].ageMs)
        assertEquals(850L, procs[1].pssKb); assertEquals(62_003L, procs[1].ageMs)
        val memory = HardwareParse.memory(procs)!!
        assertEquals("pss", memory.kind)
        assertEquals(152L * 1024 + 850, memory.kb)
        assertEquals(2, memory.processes)
        assertEquals(25_612L, memory.youngestMs)
    }

    @Test fun aLineFurtherDownTheReportIsNotAProcesss() {
        // "mTotalMemory lastPss=1.9GB" after the processes belongs to none of them.
        val procs = HardwareParse.processes(text("processes_pss.txt"))
        assertTrue(procs.none { it.pssKb == HardwareParse.sizeKb("1.9GB") })
    }

    @Test fun anAppsProcessesWhereThePhoneSamplesOnlyRss() {
        val procs = HardwareParse.processes(text("processes_rss.txt"))
        assertEquals(1, procs.size)
        assertNull(procs[0].pssKb)
        val memory = HardwareParse.memory(procs)!!
        assertEquals("rss", memory.kind)
        assertEquals(Math.round(1.2 * 1024 * 1024), memory.kb)
        assertEquals(8_040L, memory.youngestMs)
    }

    @Test fun whatThePhoneSaysInsteadIsNoReadingAndNoCrash() {
        for (said in listOf(
            "",
            "Permission Denial: can't dump ActivityManager from from pid=31337, uid=10411 due to missing android.permission.DUMP permission",
            "Can't find service: activity",
            "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n  (nothing)\n",
            "  *APP* UID x ProcessRecord{zz nope}\n    lastPss=152MB",
            "    lastPss=152MB lastRss=302MB",
        )) {
            assertTrue(said, HardwareParse.processes(said).isEmpty())
            assertNull(HardwareParse.memory(HardwareParse.processes(said)))
        }
        // Listed, never sampled: processes, and no memory.
        val unsampled = HardwareParse.processes("  *APP* UID 10001 ProcessRecord{abc 77:com.a/u0a1}\n    pid=77\n    lastPssTime=-- pssProcState=2\n    lastPss=0.00 lastRss=0.00\n")
        assertEquals(1, unsampled.size)
        assertNull(HardwareParse.memory(unsampled))
        // A size it cannot make out leaves that number unknown, and the other stands.
        val odd = HardwareParse.processes("  *APP* UID 10001 ProcessRecord{abc 77:com.a/u0a1}\n    lastPss=?? lastRss=40MB\n")
        assertNull(odd[0].pssKb); assertEquals(40L * 1024, odd[0].rssKb)
        assertEquals("rss", HardwareParse.memory(odd)!!.kind)
    }

    @Test fun howBusyTheCoresWere() {
        // Eight cores over two seconds, idle for twelve core-seconds of the sixteen: a quarter busy.
        assertEquals(25f, HardwareParse.busyPct(12_000_000, 2_000_000, 8)!!, 0.01f)
        assertEquals(100f, HardwareParse.busyPct(0, 2_000_000, 8)!!, 0.01f)
        // A core that slept through one look and woke in the next reads over: kept at nothing.
        assertEquals(0f, HardwareParse.busyPct(17_000_000, 2_000_000, 8)!!, 0.01f)
        assertNull(HardwareParse.busyPct(1, 0, 8))
        assertNull(HardwareParse.busyPct(1, 1, 0))
        assertNull(HardwareParse.busyPct(-5, 1_000, 8))
    }

    @Test fun theBatterysCurrentInEitherUnit() {
        assertEquals(-412, HardwareParse.milliamps(-412_000)); assertEquals("µA", HardwareParse.unitOf(-412_000))
        assertEquals(412, HardwareParse.milliamps(412)); assertEquals("mA", HardwareParse.unitOf(412))
        assertEquals(-1800, HardwareParse.milliamps(-1800))
        assertEquals(0, HardwareParse.milliamps(0))
    }

    @Test fun theBatterysFullCharge() {
        assertEquals(4400, HardwareParse.capacityMah(2_200_000, 50))
        assertNull(HardwareParse.capacityMah(0, 50))
        assertNull(HardwareParse.capacityMah(2_200_000, 5))
        // A counter that is not microamp hours gives a battery no phone has.
        assertNull(HardwareParse.capacityMah(2_200, 50))
    }

    @Test fun aLineOfReadingsStaysSmall() {
        val series = Series(4)
        for (i in 0 until 4) series.add(i * 2, 10f * (i + 1))
        assertEquals(4, series.size)
        series.add(8, 50f)
        // Five became three: pairs averaged, the odd one kept, each under its first time.
        assertEquals(listOf(0, 4, 8), series.t.toList())
        assertEquals(listOf(15f, 35f, 50f), series.v.toList())
        assertEquals(50f, series.peak()!!, 0.01f)
        assertEquals(42.5f, series.peakOver(2)!!, 0.01f)
        assertNotNull(series.average())
        assertNull(Series().average()); assertNull(Series().peakOver(3))
    }
}
