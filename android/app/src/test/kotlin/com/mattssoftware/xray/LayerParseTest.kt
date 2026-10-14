package com.mattssoftware.xray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compositor's layers for the app in front, against what phones printed. Two `dumpsys SurfaceFlinger --list` came
 * from Matt's Galaxy Z Fold 8 on Android 17 (the brief's samples, other people's apps named com.example.appN): a
 * Flutter app in front (Ghost.md) and a native one. The rest came from the emulator on Android 16, each a list and a
 * whole dump taken together: Matt's Tools' Frames page (a Flutter page), the Clock with a popup open, and Settings.
 * Places are tried only against the emulator: no whole dump from Android 17 is in the brief.
 */
class LayerParseTest {
    private fun text(file: String) = javaClass.classLoader!!.getResourceAsStream("xray/$file")!!.bufferedReader().readText()

    private fun of(list: String, pkg: String, full: String? = null, flutter: Boolean = false) =
        LayerParse.of(text(list), pkg, full?.let { LayerParse.places(text(it)) } ?: emptyMap(), flutter)

    private fun area(a: XrayTree.Area?) = a?.let { listOf(it.l, it.t, it.r, it.b) }

    // ---- the list ----

    @Test fun readsEachLineOfTheList() {
        val layers = LayerParse.list(text("fold8-android17-layers-flutter-in-front.txt"))
        assertEquals(272, layers.size)
        val holder = layers.single { it.id == 9393 }
        // The letters and digits before a name are part of it (the brief: asked for without them, the phone answers nothing).
        assertEquals("5b0b98d SurfaceView[com.mattssoftware.glyph/com.mattssoftware.glyph.MainActivity]@0", holder.name.substringBefore("#"))
        assertEquals(9392, holder.parent)
        assertEquals(9391, holder.relative)
        assertEquals(-2, holder.z)
        // A name with a # inside it, and one with spaces and braces.
        assertEquals(8373, layers.first().id)
        assertEquals(8372, layers.first().parent)
        assertEquals(2994, layers.single { it.name.startsWith("ActivityRecord{146991050") }.id)
        assertNull(layers.single { it.id == 32 }.parent)
    }

    // ---- the app's layers, Android 17 ----

    @Test fun foldFlutterInFront() {
        val app = of("fold8-android17-layers-flutter-in-front.txt", "com.mattssoftware.glyph", flutter = true)
        // Bottom to top, rebuilt from parents, relative parents and z: the list itself is in no such order.
        assertEquals(listOf(3008, 2994, 2995, 9395, 9393, 9394, 9391, 9392), app.map { it.id })
        assertEquals(listOf("input sink", "activity", "window", "background", "surface holder", "surface", "view root", "bounds"), app.map { it.kind })
        val surface = app.single { it.kind == "surface" }
        assertEquals("5b0b98d SurfaceView[com.mattssoftware.glyph/com.mattssoftware.glyph.MainActivity]@0(BLAST)#9394", surface.name)
        assertEquals("Flutter surface", surface.words)
        assertEquals("com.mattssoftware.glyph/com.mattssoftware.glyph.MainActivity\$_16409", app.single { it.kind == "view root" }.name.substringAfter("VRI-").substringBefore("#"))
        // The keyboard's container hangs under the app's activity while it is the keyboard's target: it is not the app's.
        assertTrue(app.none { it.name.contains("ImeContainer") || it.name.contains("InputMethod") })
        assertTrue(app.all { it.place == null })
        assertEquals((0 until 8).toList(), app.map { it.order })
    }

    @Test fun foldNativeInFront() {
        val app = of("fold8-android17-layers-native-in-front.txt", "com.example.app8")
        assertEquals(listOf(1501, 1498, 1514, 9182), app.map { it.id })
        assertEquals(listOf("input sink", "activity", "window", "view root"), app.map { it.kind })
        assertTrue(app.none { it.kind == "surface" })
        // The camera's surface is on the same list, and not the app's.
        assertTrue(app.none { it.name.contains("camera") })
    }

    @Test fun aSurfaceOfANativeAppIsNotCalledFlutter() {
        val app = of("fold8-android17-layers-flutter-in-front.txt", "com.mattssoftware.glyph", flutter = false)
        assertEquals("A surface of its own, such as video or a game", app.single { it.kind == "surface" }.words)
    }

    @Test fun noMatchGivesNone() {
        assertTrue(of("fold8-android17-layers-native-in-front.txt", "com.example.none").isEmpty())
        assertTrue(LayerParse.of("", "com.example.none", emptyMap(), false).isEmpty())
        // A prefix of another package's name is not that package.
        assertTrue(of("fold8-android17-layers-native-in-front.txt", "com.example.app").isEmpty())
    }

    // ---- the app's layers with their places, Android 16 ----

    @Test fun framesPageOnTheEmulator() {
        val app = of("tools-frames-sf-list.txt", "com.mattssoftware.tools", "tools-frames-sf-full.txt", flutter = true)
        // Only the activity in front: Matt's Tools' first page and its setting-up are on the list too, under it.
        assertEquals(listOf(791, 781, 782, 789, 787, 788, 783, 786), app.map { it.id })
        val surface = app.single { it.id == 788 }
        assertEquals("Flutter surface", surface.words)
        assertEquals(listOf(0, 0, 1248, 1972), area(surface.place))
        assertEquals(listOf(0, 0, 1248, 1972), area(app.single { it.id == 783 }.place))
        assertEquals("background", app.single { it.id == 789 }.kind)
        assertEquals(listOf(0, 0, 1248, 1972), area(app.single { it.id == 789 }.place))
        assertEquals("window", app.single { it.id == 782 }.kind)
        assertNull(app.single { it.id == 782 }.place)
        // Android 16 keeps the window's frames on a plain layer under it, where Android 17 has VRI-.
        assertEquals("view root", app.single { it.id == 783 }.kind)
    }

    @Test fun clockOnTheEmulator() {
        val app = of("clock-sf-list.txt", "com.google.android.deskclock", "clock-sf-full.txt")
        val window = app.single { it.id == 826 }
        assertEquals("window", window.kind)
        assertNull(window.place)
        val frames = app.single { it.id == 827 }
        assertEquals(826, frames.parent)
        assertEquals(listOf(0, 128, 1248, 1972), area(frames.place))
        // The popup's two layers have no package in their names: they are the app's by being under its window.
        assertEquals(listOf(833, 834), app.filter { it.kind == "popup" }.map { it.id })
        assertEquals(listOf(703, 1519, 1216, 1699), area(app.single { it.id == 834 }.place))
        assertEquals("letterbox", app.single { it.id == 820 }.kind)
        assertTrue(app.indexOfFirst { it.id == 820 } < app.indexOfFirst { it.id == 827 })
        assertTrue(app.indexOfFirst { it.id == 827 } < app.indexOfFirst { it.id == 834 })
        // Matt's Tools is on the same list behind the Clock and is not the Clock's.
        assertTrue(app.none { it.name.contains("mattssoftware") })
    }

    @Test fun settingsOnTheEmulator() {
        val app = of("settings-sf-list.txt", "com.android.settings", "settings-sf-full.txt")
        assertEquals("window", app.single { it.id == 752 }.kind)
        assertEquals(listOf(0, 0, 1248, 1972), area(app.single { it.id == 753 }.place))
    }

    // ---- places ----

    @Test fun compositionListBoundsAreLeftTopBottomRight() {
        val dump = "Composition list (bottom to top)\nLayerStack=0\n  Layer [5] a#5\n    visible reason= buffer=1\n    bounds={10,20,300,200} toDisplayTransform={ tx=10 ty=20 }\n    input{(0x0) touchableRegion={1,2,3,4}}\n\nInput list\n  Layer [6] b#6\n    bounds={0,0,9,9}\n"
        val places = LayerParse.composition(dump)
        assertEquals(listOf(10, 20, 200, 300), area(places[5]))
        // The input list is not where layers are composed.
        assertNull(places[6])
    }

    @Test fun hwcTableMatchesByTheIdThoughTheNameIsCut() {
        val hwc = LayerParse.hwc(text("tools-frames-sf-full.txt"))
        // Printed " 217b6d7 SurfaceView[com.mattssoftwar[...]ware.frames.MainActivity](BLAST)#788".
        assertEquals(listOf(0, 0, 1248, 1972), area(hwc[788]))
        assertEquals(listOf(0, 0, 1248, 1972), area(hwc[783]))
        assertEquals(listOf(277, 0, 825, 154), area(hwc[725]))
        assertEquals(listOf(0, 128, 1248, 1972), area(LayerParse.hwc(text("clock-sf-full.txt"))[827]))
    }

    @Test fun theCompositionListAndTheHwcTableAgree() {
        val full = text("tools-frames-sf-full.txt")
        val composition = LayerParse.composition(full)
        val hwc = LayerParse.hwc(full)
        for (id in listOf(788, 783, 725, 90, 99)) assertEquals("layer $id", area(hwc[id]), area(composition[id]))
    }

    @Test fun theHwcTableIsUsedWhereThereIsNoCompositionList() {
        val full = text("clock-sf-full.txt")
        val onlyHwc = full.substring(full.indexOf("HWC layers:") - 60)
        assertEquals(listOf(0, 128, 1248, 1972), area(LayerParse.places(onlyHwc)[827]))
        assertTrue(LayerParse.places("").isEmpty())
    }

    // ---- phones before Android 15 ----

    @Test fun bareNamesInZOrder() {
        // Written by hand from facts.md 8a, not captured: Android 11 to 14 print each layer's name alone, in z order.
        val list = "Display Root#0\n5b0b98d com.app/com.app.Main#12\ncom.app/com.app.Main#13\nSurfaceView - com.app/com.app.Main#14\nStatusBar#90\n"
        val app = LayerParse.of(list, "com.app", emptyMap(), true)
        assertEquals(listOf(12, 13, 14), app.map { it.id })
        assertEquals(listOf("window", "view root", "surface"), app.map { it.kind })
    }

    // ---- the overlay ----

    @Test fun pickTakesTheTopmostPlacedLayer() {
        val app = of("tools-frames-sf-list.txt", "com.mattssoftware.tools", "tools-frames-sf-full.txt", flutter = true)
        assertEquals(783, LayerParse.pick(app, 600, 900)?.id)
        assertNull(LayerParse.pick(app, 5000, 5000))
    }

    @Test fun tappingTheSameSpotAgainStepsDownThroughTheLayersThere() {
        // On the Frames page the view root, the Flutter surface and its background all fill the screen: a tap that
        // always took the topmost could never reach the surface.
        val app = of("tools-frames-sf-list.txt", "com.mattssoftware.tools", "tools-frames-sf-full.txt", flutter = true)
        val first = LayerParse.pick(app, 600, 900)
        val second = LayerParse.pick(app, 600, 900, first?.id)
        val third = LayerParse.pick(app, 600, 900, second?.id)
        assertEquals(listOf(783, 788, 789), listOf(first?.id, second?.id, third?.id))
        // Under the last, back to the top.
        assertEquals(783, LayerParse.pick(app, 600, 900, third?.id)?.id)
        // A layer picked that is not at the spot does not count: the topmost there is taken.
        assertEquals(783, LayerParse.pick(app, 600, 900, 782)?.id)
    }

    @Test fun cardSaysHowManyLayersAreAtTheSpot() {
        val app = of("tools-frames-sf-list.txt", "com.mattssoftware.tools", "tools-frames-sf-full.txt", flutter = true)
        val surface = app.single { it.id == 788 }
        assertEquals("2 of 3 here, from the top: tap again for the one under it", LayerParse.card(surface, app.size, LayerParse.here(app, surface, 600, 900)).last())
        // Alone at its spot, nothing is said of others.
        assertEquals(4, LayerParse.card(surface, app.size, LayerParse.here(listOf(surface), surface, 600, 900)).size)
    }

    @Test fun cardSaysKindNamePlaceAndOrder() {
        val app = of("tools-frames-sf-list.txt", "com.mattssoftware.tools", "tools-frames-sf-full.txt", flutter = true)
        val card = LayerParse.card(app.single { it.id == 788 }, app.size)
        assertEquals("Flutter surface", card[0])
        assertTrue(card[1].endsWith("#788"))
        assertEquals("0, 0, 1248 by 1972", card[2])
        assertEquals("6 of 8 from the bottom, z 0", card[3])
        assertEquals("Place not given", LayerParse.card(app.single { it.id == 782 }, app.size)[2])
    }
}
