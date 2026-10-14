package com.mattssoftware.xray

import com.mattssoftware.xray.AnatomyParse.Entry
import com.mattssoftware.xray.AnatomyParse.Extra
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The anatomy reader against listings. Three are `unzip -l` of real packages from the emulator (Matt's Tools,
 * Settings, Chrome; .new-tool-pr/samples), and the rest are written by hand from what facts.md section 7 says each
 * framework puts in its package (the files say where each name comes from). They are file listings only: no screen.
 */
class AnatomyParseTest {
    private val row = Regex("""^\s*(\d+)\s+\d\d-\d\d-\d{4}\s+\d\d:\d\d\s+(.+)$""")
    private val plain = Regex("""^(\d+) (.+)$""")

    /** A listing, in `unzip -l`'s form or the by-hand form (`size name`). */
    private fun list(file: String): List<Entry> =
        javaClass.classLoader!!.getResource("xray/$file")!!.readText().lines().mapNotNull { line ->
            if (line.startsWith("#")) return@mapNotNull null
            val m = row.find(line) ?: plain.find(line) ?: return@mapNotNull null
            Entry(m.groupValues[2].trim(), m.groupValues[1].toLong(), m.groupValues[1].toLong(), "base.apk")
        }

    private fun extra(
        impeller: Boolean? = null, bundleHead: ByteArray? = null, fonts: String? = null, compose: String? = null,
        unreadable: List<String> = emptyList(), libraries: Int = 0,
    ) = Extra("com.example.app", "1.0", 3, 29, 36, 36, impeller, bundleHead, fonts, compose, listOf("base.apk" to 1L), unreadable, libraries)

    private fun AnatomyParse.Result.kind(name: String) = kinds.first { it.name == name }
    private fun AnatomyParse.Result.names() = kinds.map { it.name }
    private fun AnatomyParse.Kind.detail(label: String) = details.first { it.label == label }

    @Test fun mattsToolsIsFlutterAndNative() {
        val entries = list("apk-tools-unzip-l.txt")
        val fonts = """[{"family":"Inter","fonts":[{"asset":"a.ttf"}]},{"family":"packages/glacier/Inter","fonts":[]}]"""
        val r = AnatomyParse.parse(entries, extra(fonts = fonts))
        assertEquals(listOf("Flutter", "Native"), r.names())
        assertEquals("Flutter and native", r.headline)
        assertEquals(155, r.entries)
        val engine = r.kind("Flutter").detail("Engine")
        assertEquals(11_747_864L, engine.size)
        assertEquals("libflutter.so in arm64-v8a", engine.value)
        assertEquals(3_867_528L, r.kind("Flutter").detail("Compiled app").size)
        assertTrue(r.kind("Flutter").detail("Build").value.startsWith("Release"))
        assertEquals("lib/arm64-v8a/libflutter.so", r.kind("Flutter").evidence.single().name)
        assertEquals(1, r.dexCount)
        assertEquals(6_326_472L, r.dexBytes)
        assertEquals("glacier", r.kind("Flutter").detail("Packages with assets").value)
        assertEquals("Inter, packages/glacier/Inter", r.kind("Flutter").detail("Fonts").value)
        assertEquals(3, r.assets.first { it.kind == "Fonts" }.count)
        assertEquals(2, r.assets.first { it.kind == "Shaders" }.count)
        assertEquals(listOf("arm64-v8a"), r.libs.map { it.abi })
        assertEquals(listOf("libflutter.so", "libapp.so"), r.libs.single().files.map { it.name })
        assertTrue(r.kind("Native").detail("Jetpack Compose").value.startsWith("Not in"))
    }

    @Test fun impellerIsSaidAsTheManifestSaysIt() {
        val entries = list("apk-tools-unzip-l.txt")
        assertTrue(AnatomyParse.parse(entries, extra()).kind("Flutter").detail("Impeller").value.startsWith("Not switched off"))
        assertEquals("Switched off in the manifest", AnatomyParse.parse(entries, extra(impeller = false)).kind("Flutter").detail("Impeller").value)
        assertEquals("Switched on in the manifest", AnatomyParse.parse(entries, extra(impeller = true)).kind("Flutter").detail("Impeller").value)
    }

    @Test fun aDebugFlutterBuildIsSaidToBeDebug() {
        val entries = list("apk-tools-unzip-l.txt").filter { !it.name.endsWith("libapp.so") } + Entry("assets/flutter_assets/kernel_blob.bin", 900, 900, "base.apk")
        assertTrue(AnatomyParse.parse(entries, extra()).kind("Flutter").detail("Build").value.startsWith("Debug"))
    }

    @Test fun flutterSaysWhatItCannotSay() {
        val r = AnatomyParse.parse(list("apk-tools-unzip-l.txt"), extra())
        assertTrue(r.cannot.any { it.startsWith("Which Flutter version") })
        assertTrue(r.cannot.any { it.startsWith("Widget names") })
    }

    @Test fun settingsIsNativeWithComposeInThePackage() {
        val r = AnatomyParse.parse(list("apk-settings-unzip-l.txt"), extra(compose = "1.9.0\n"))
        assertEquals(listOf("Native"), r.names())
        assertEquals("Native", r.headline)
        assertTrue(r.libs.isEmpty())
        assertEquals(3985, r.entries)
        assertEquals(3, r.dexCount)
        val compose = r.kind("Native").detail("Jetpack Compose").value
        assertTrue(compose, compose.startsWith("In the package:"))
        assertTrue(compose, compose.contains("androidx.compose.ui_ui 1.9.0"))
    }

    @Test fun chromeIsNativeWithSixDexFilesAndNoFramework() {
        val r = AnatomyParse.parse(list("apk-chrome-unzip-l.txt"), extra())
        assertEquals(listOf("Native"), r.names())
        assertEquals(6, r.dexCount)
        assertEquals(listOf("arm64-v8a", "armeabi-v7a"), r.libs.map { it.abi })
        assertEquals("libelements.so", r.libs.first().files.first().name)
        assertTrue(r.cannot.any { it.startsWith("Web view shells") })
    }

    @Test fun reactNativeBeforeEightyTwoSaysHermesButNotTheArchitecture() {
        val r = AnatomyParse.parse(list("apk-react-native-076-by-hand.txt"), extra(bundleHead = AnatomyParse.hermesMagic))
        assertEquals(listOf("React Native", "Native"), r.names())
        val rn = r.kind("React Native")
        assertEquals(1_500_000L, rn.detail("Bundle").size)
        assertTrue(rn.detail("Hermes").value.startsWith("Yes"))
        assertEquals("Not shown by the package", rn.detail("New architecture").value)
        assertTrue(rn.detail("React Native").value.startsWith("0.76 or later"))
        assertTrue(rn.detail("Expo").value.startsWith("No"))
        assertTrue(r.cannot.any { it.startsWith("Whether it is on React Native's new architecture") })
    }

    @Test fun reactNativeEightyTwoWithExpoIsOnTheNewArchitecture() {
        val r = AnatomyParse.parse(list("apk-react-native-082-expo-by-hand.txt"), extra(bundleHead = AnatomyParse.hermesMagic))
        val rn = r.kind("React Native")
        assertTrue(rn.detail("New architecture").value.startsWith("Yes"))
        assertTrue(rn.detail("Expo").value.startsWith("Yes"))
        assertFalse(r.cannot.any { it.startsWith("Whether it is on React Native's new architecture") })
    }

    @Test fun aBundleThatIsNotHermesAndHasNoHermesLibraryIsNotHermes() {
        val entries = list("apk-react-native-076-by-hand.txt").filter { !it.name.contains("hermes") }
        val plainJs = "var a".toByteArray()
        assertTrue(AnatomyParse.parse(entries, extra(bundleHead = plainJs)).kind("React Native").detail("Hermes").value.startsWith("No"))
        assertEquals("Not shown by the package", AnatomyParse.parse(entries, extra(bundleHead = null)).kind("React Native").detail("Hermes").value)
    }

    @Test fun theHermesMagicNumberIsEightBytesLittleEndian() {
        assertTrue(AnatomyParse.isHermes(byteArrayOf(0xC6.toByte(), 0x1F, 0xBC.toByte(), 0x03, 0xC1.toByte(), 0x03, 0x19, 0x1F, 0x00)))
        assertFalse(AnatomyParse.isHermes(byteArrayOf(0x1F, 0x19, 0x03, 0xC1.toByte(), 0x03, 0xBC.toByte(), 0x1F, 0xC6.toByte())))
        assertFalse(AnatomyParse.isHermes(byteArrayOf(0xC6.toByte(), 0x1F)))
        assertFalse(AnatomyParse.isHermes(null))
    }

    @Test fun unityTellsIl2cppFromItsLibrary() {
        val r = AnatomyParse.parse(list("apk-unity-by-hand.txt"), extra())
        assertEquals(listOf("Unity", "Native"), r.names())
        assertEquals(24_000_000L, r.kind("Unity").detail("Engine").size)
        assertTrue(r.kind("Unity").detail("IL2CPP").value.startsWith("Yes"))
        val mono = AnatomyParse.parse(list("apk-unity-by-hand.txt").filter { !it.name.contains("il2cpp") }, extra())
        assertTrue(mono.kind("Unity").detail("IL2CPP").value.startsWith("No"))
    }

    @Test fun dotnetCordovaAndCapacitorAreToldByTheirFiles() {
        assertEquals(listOf(".NET", "Native"), AnatomyParse.parse(list("apk-dotnet-by-hand.txt"), extra()).names())
        val cordova = AnatomyParse.parse(list("apk-cordova-by-hand.txt"), extra())
        assertEquals(listOf("Cordova", "Native"), cordova.names())
        assertEquals("3 files in assets/www", cordova.kind("Cordova").detail("Web files").value)
        val capacitor = AnatomyParse.parse(list("apk-capacitor-by-hand.txt"), extra())
        assertEquals(listOf("Capacitor", "Native"), capacitor.names())
        assertEquals("2 files in assets/public", capacitor.kind("Capacitor").detail("Web files").value)
    }

    @Test fun anAppOfTwoThingsHasBothInTheHeadline() {
        val entries = list("apk-react-native-076-by-hand.txt") + list("apk-unity-by-hand.txt")
        assertEquals("React Native, Unity and native", AnatomyParse.parse(entries, extra()).headline)
    }

    @Test fun assetsAreCountedByKind() {
        assertEquals("Images", AnatomyParse.assetKind("assets/a/b.PNG"))
        assertEquals("Fonts", AnatomyParse.assetKind("assets/fonts/x.ttf"))
        assertEquals("Web pages", AnatomyParse.assetKind("assets/www/index.html"))
        assertEquals("Other", AnatomyParse.assetKind("assets/LICENSE"))
        val r = AnatomyParse.parse(list("apk-cordova-by-hand.txt"), extra())
        assertEquals(40_000L + 900 + 15_000, r.assets.filter { it.kind == "Web pages" || it.kind == "Other" }.sumOf { it.bytes })
    }

    @Test fun fontFamiliesComeOutOfTheManifestEachOnce() {
        assertEquals(listOf("Inter", "Mono"), AnatomyParse.fontFamilies("""[{"family": "Inter"},{"family":"Mono"},{"family":"Inter"}]"""))
        assertTrue(AnatomyParse.fontFamilies(null).isEmpty())
        assertTrue(AnatomyParse.fontFamilies("not json").isEmpty())
    }

    @Test fun anEmptyListSaysNativeAndWhatCouldNotBeRead() {
        val r = AnatomyParse.parse(emptyList(), extra(unreadable = listOf("base.apk")))
        assertEquals(0, r.entries)
        assertEquals(listOf("Native"), r.names())
        assertTrue(r.cannot.contains("Android would not let Matt's Tools read base.apk."))
    }

    @Test fun theMapIsWhatThePageReads() {
        val m = AnatomyParse.parse(list("apk-tools-unzip-l.txt"), extra()).toMap()
        assertEquals("Flutter and native", m["headline"])
        val dex = m["dex"] as Map<*, *>
        assertEquals(1, dex["count"])
        assertEquals(6_326_472L, dex["bytes"])
        assertNull((m["sdk"] as Map<*, *>)["unknown"])
        assertEquals(29, (m["sdk"] as Map<*, *>)["min"])
    }
}
