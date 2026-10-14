package com.mattssoftware.xray

import com.mattssoftware.xray.XrayTree.Area
import com.mattssoftware.xray.XrayTree.Kinds
import com.mattssoftware.xray.XrayTree.Raw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The live tree's reader against trees in uiautomator's form. Five were dumped from the emulator (Android 16): the
 * Settings app's main page and its list of all apps (a Compose screen), Matt's Tools' first page (the Kotlin-drawn
 * page of the 1.0.0 build then installed, not today's Flutter one), its Frames page (a Flutter page in a native
 * shell) and Chrome's first-run page; no one's private screen. The React Native, Compose and password trees are
 * written by hand from facts.md 4a-4c and say so at their tops. They stand in for what an accessibility service is
 * given, node by node.
 */
class XrayTreeTest {
    /** A node of a uiautomator dump, as the service's node would answer. */
    private class Node(private val e: Element, private val textAsked: MutableList<String>? = null) : Raw {
        private val kids = e.childNodes.let { list -> (0 until list.length).map { list.item(it) }.filterIsInstance<Element>().filter { it.tagName == "node" } }
        override val cls get() = e.getAttribute("class").ifEmpty { null }
        override val id get() = e.getAttribute("resource-id").ifEmpty { null }
        override val password get() = e.getAttribute("password") == "true"
        override fun text(): CharSequence? { textAsked?.add(e.getAttribute("resource-id")); return e.getAttribute("text") }
        override fun desc(): CharSequence? = e.getAttribute("content-desc")
        override fun bounds(): Area {
            val n = Regex("""-?\d+""").findAll(e.getAttribute("bounds")).map { it.value.toInt() }.toList()
            return Area(n[0], n[1], n[2], n[3])
        }
        override val order get() = e.getAttribute("drawing-order").toIntOrNull() ?: 0
        override val can: List<String>
            get() = listOfNotNull(
                "tapped".takeIf { e.getAttribute("clickable") == "true" },
                "long pressed".takeIf { e.getAttribute("long-clickable") == "true" },
                "scrolled".takeIf { e.getAttribute("scrollable") == "true" },
                "checked".takeIf { e.getAttribute("checkable") == "true" },
            )
        override val visible get() = true
        override val count get() = kids.size
        override fun child(i: Int): Raw? = kids.getOrNull(i)?.let { Node(it, textAsked) }
    }

    private fun root(file: String, asked: MutableList<String>? = null): Raw {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(javaClass.classLoader!!.getResourceAsStream("xray/$file"))
        val first = doc.documentElement.getElementsByTagName("node").item(0) as Element
        return Node(first, asked)
    }

    private val native = Kinds(flutter = false, reactNative = false, compose = false)
    private val flutter = Kinds(flutter = true, reactNative = false, compose = false)

    // ---- each kind of app ----

    @Test fun settingsIsAllNative() {
        // The package carries Compose's version files, but nothing on this page is under a Compose host.
        val r = XrayTree.read(root("settings-uiautomator.xml"), Kinds(flutter = false, reactNative = false, compose = true))
        assertEquals(63, r.boxes.size)
        assertTrue(r.boxes.all { it.inspector == "native" })
        assertEquals(52, r.boxes.count { it.id?.contains(":id/") == true })
        assertEquals(listOf("Native"), r.inspectors)
        assertFalse(r.cut)
        assertEquals(0, r.boxes[0].depth)
        assertNull(r.boxes[0].parent)
    }

    @Test fun theFramesPageIsAFlutterViewInANativeShell() {
        val r = XrayTree.read(root("tools-frames-uiautomator.xml"), flutter)
        assertEquals(18, r.boxes.size)
        assertEquals(4, r.boxes.count { it.inspector == "native" })
        assertEquals(14, r.boxes.count { it.inspector == "flutter" })
        val host = r.boxes.single { it.kind == "flutter view" }
        assertEquals("android.widget.FrameLayout", host.cls)
        assertEquals(3, host.depth)
        assertEquals(listOf("Native", "Flutter"), r.inspectors)
        val diagnostics = r.boxes.single { it.label == "Diagnostics" }
        assertEquals("android.widget.Button", diagnostics.cls)
        assertTrue("tapped" in diagnostics.can)
        assertEquals("semantics node", diagnostics.kind)
        // Flutter leaves drawing order at 0: depth comes from the tree.
        assertEquals(9, diagnostics.depth)
        assertEquals("Flutter view: it draws to a surface of its own", XrayTree.card(host, flutter).first())
        val card = XrayTree.card(diagnostics, flutter)
        assertEquals("Flutter, semantics node", card[0])
        assertTrue(card.contains("Widget names are not in a release build"))
        // No box is given a widget's name.
        assertTrue(r.boxes.none { b -> listOf("Scaffold", "Text", "ElevatedButton", "Widget").any { b.cls?.contains(it) == true } })
    }

    @Test fun withoutFlutterInThePackageTheFramesPageReadsAsNative() {
        val r = XrayTree.read(root("tools-frames-uiautomator.xml"), native)
        assertTrue(r.boxes.all { it.inspector == "native" })
    }

    @Test fun mattsToolsFirstPageOfTheOldBuildIsNativeThoughThePackageHasFlutter() {
        val r = XrayTree.read(root("tools-first-uiautomator.xml"), flutter)
        assertEquals(29, r.boxes.size)
        assertTrue(r.boxes.all { it.inspector == "native" })
    }

    @Test fun chromeFirstRunIsNativeWithTenIds() {
        val r = XrayTree.read(root("chrome-uiautomator.xml"), native)
        assertEquals(22, r.boxes.size)
        assertTrue(r.boxes.all { it.inspector == "native" })
        assertEquals(10, r.boxes.count { it.id != null })
    }

    @Test fun reactNativeViewsShowTheirTestIdsAndKeepThePlatformsClasses() {
        val rn = Kinds(flutter = false, reactNative = true, compose = false)
        val r = XrayTree.read(root("react-native-by-hand.xml"), rn)
        // The shell above android:id/content and android:id/content itself are the activity's.
        assertEquals(3, r.boxes.count { it.inspector == "native" })
        assertEquals(r.boxes.size - 3, r.boxes.count { it.inspector == "react-native" })
        val login = r.boxes.single { it.testId == "login_button" }
        assertEquals("android.widget.Button", login.cls)
        assertEquals("React Native, testID login_button", XrayTree.card(login, rn)[0])
        val title = r.boxes.first { it.label == "Sign in" }
        assertNull(title.testId)
        assertEquals("React Native, android.widget.TextView", XrayTree.card(title, rn)[0])
        assertTrue(r.boxes.none { it.cls?.startsWith("com.facebook") == true })
        assertEquals(listOf("Native", "React Native"), r.inspectors)
        // Read as native where the package does not say React Native: the testID is then only an id.
        assertTrue(XrayTree.read(root("react-native-by-hand.xml"), native).boxes.all { it.inspector == "native" && it.testId == null })
    }

    @Test fun composeIsLikelyOnlyWhereThePackageSaysCompose() {
        val compose = Kinds(flutter = false, reactNative = false, compose = true)
        val r = XrayTree.read(root("compose-by-hand.xml"), compose)
        val likely = r.boxes.filter { it.inspector == "compose" }
        assertEquals(listOf("android.view.View", "android.widget.TextView", "android.view.View", "android.widget.EditText", "android.widget.Button"), likely.map { it.cls })
        // The native toolbar beside the host, with its id, stays native; the host itself is a view.
        assertEquals("native", r.boxes.single { it.id == "com.example.compose:id/toolbar_title" }.inspector)
        assertEquals("native", r.boxes.single { it.cls == "android.view.ViewGroup" }.inspector)
        assertEquals(listOf("Native", "Jetpack Compose, likely"), r.inspectors)
        assertEquals("Jetpack Compose, likely: android.widget.Button", XrayTree.card(likely.last(), compose)[0])
        assertTrue(XrayTree.read(root("compose-by-hand.xml"), native).boxes.all { it.inspector == "native" })
    }

    @Test fun settingsAllAppsListIsComposeUnderAComposeViewHost() {
        // Dumped from the emulator (Settings, Apps, See all apps): a real Compose screen, whose host reports
        // androidx.compose.ui.platform.ComposeView rather than android.view.ViewGroup.
        val compose = Kinds(flutter = false, reactNative = false, compose = true)
        val r = XrayTree.read(root("settings-all-apps-uiautomator.xml"), compose)
        assertEquals(41, r.boxes.size)
        assertEquals("native", r.boxes.single { it.cls == "androidx.compose.ui.platform.ComposeView" }.inspector)
        // Everything under the host: 19 views, 15 texts and 3 buttons.
        assertEquals(37, r.boxes.count { it.inspector == "compose" })
        assertEquals(listOf("Native", "Jetpack Compose, likely"), r.inspectors)
    }

    // ---- what is kept of text ----

    @Test fun aPasswordNodeIsNeverAskedForItsTextAndNothingOfItIsKept() {
        val asked = mutableListOf<String>()
        val r = XrayTree.read(root("password-by-hand.xml", asked), native)
        val pin = r.boxes.single { it.id == "com.example.bank:id/pin" }
        assertTrue(pin.password)
        assertNull(pin.label)
        assertFalse("com.example.bank:id/pin" in asked)
        assertTrue(r.boxes.none { it.label?.contains("secret") == true })
        assertTrue(XrayTree.card(pin, native).none { it.contains("secret") })
    }

    @Test fun aPasswordNodeThatWouldThrowOnTextIsReadWithoutIt() {
        val pin = object : Raw {
            override val cls = "android.widget.EditText"
            override val id: String? = null
            override val password = true
            override fun text(): CharSequence = throw AssertionError("a password node was asked for its text")
            override fun desc(): CharSequence = throw AssertionError("a password node was asked for its description")
            override fun bounds() = Area(0, 0, 10, 10)
            override val order = 0
            override val can = emptyList<String>()
            override val visible = true
            override val count = 0
            override fun child(i: Int): Raw? = null
        }
        assertNull(XrayTree.read(pin, native).boxes.single().label)
    }

    @Test fun longTextIsCutAtFortyCharactersOnOneLine() {
        val r = XrayTree.read(root("password-by-hand.xml"), native)
        val greeting = r.boxes.single { it.id == "com.example.bank:id/greeting" }.label!!
        assertEquals(40, greeting.length)
        assertFalse(greeting.contains('\n'))
        assertTrue(greeting.startsWith("Welcome back to the bank"))
        assertTrue(greeting.endsWith("..."))
        // Where there is no text, the description is the label; where both are empty, none.
        assertEquals("Remember me", r.boxes.single { it.id == "com.example.bank:id/remember" }.label)
        assertNull(XrayTree.label("  ", ""))
        assertEquals("Read frame timings Not yet: the first", XrayTree.label("", "Read frame timings\nNot yet: the first"))
    }

    // ---- peel, pick and the card ----

    @Test fun peelStepsDownToADepthAndToOnlyOne() {
        val boxes = XrayTree.read(root("tools-frames-uiautomator.xml"), flutter).boxes
        val deepest = boxes.maxOf { it.depth }
        assertEquals(9, deepest)
        var peel = XrayTree.Peel()
        assertEquals("All depths", peel.words(deepest))
        assertEquals(18, boxes.count { peel.shows(it) })
        peel = peel.fewer(deepest)
        assertEquals("Down to depth 8 of 9", peel.words(deepest))
        assertEquals(boxes.count { it.depth <= 8 }, boxes.count { peel.shows(it) })
        peel = peel.fewer(deepest).fewer(deepest)
        assertEquals("Down to depth 6 of 9", peel.words(deepest))
        peel = peel.flip(deepest)
        assertEquals("Only depth 6 of 9", peel.words(deepest))
        assertTrue(boxes.filter { peel.shows(it) }.all { it.depth == 6 })
        peel = peel.more(deepest).more(deepest).more(deepest).more(deepest)
        assertEquals("Only depth 9 of 9", peel.words(deepest))
        peel = peel.flip(deepest).more(deepest)
        assertEquals("All depths", peel.words(deepest))
        // Never below depth 0.
        var low = XrayTree.Peel()
        repeat(20) { low = low.fewer(deepest) }
        assertEquals("Down to depth 0 of 9", low.words(deepest))
    }

    @Test fun aTapPicksTheInnermostVisibleBoxAndNothingOutsideEveryBox() {
        val r = XrayTree.read(root("settings-uiautomator.xml"), native)
        // The "Network & internet" row's title in the sample: android:id/title at [210,670][646,741].
        val title = XrayTree.pick(r.boxes, 400, 705)!!
        assertEquals("android:id/title", title.id)
        assertEquals("Native, android.widget.TextView", XrayTree.card(title, native)[0])
        assertTrue(XrayTree.card(title, native).none { it.startsWith("Can be") })
        // Its right end, outside the text: the row itself, which can be tapped.
        val row = XrayTree.pick(r.boxes, 1225, 705)!!
        assertEquals("android.widget.LinearLayout", row.cls)
        assertTrue(XrayTree.card(row, native).any { it.startsWith("Can be tapped") })
        assertNull(XrayTree.pick(r.boxes, 5000, 5000))
        // Only among what is shown: with depth 0 alone, the window's frame.
        assertEquals(0, XrayTree.pick(r.boxes.filter { it.depth == 0 }, 400, 705)!!.depth)
    }

    @Test fun theCardSaysPlaceDepthAndWhatCanBeDone() {
        val r = XrayTree.read(root("tools-frames-uiautomator.xml"), flutter)
        val card = XrayTree.card(r.boxes.single { it.label == "Diagnostics" }, flutter)
        assertEquals(listOf("Flutter, semantics node", "Diagnostics", "48, 1319, 1152 by 129", "Depth 9", "Can be tapped", "Widget names are not in a release build"), card)
        assertTrue(card.size <= 6)
    }

    @Test fun theReadingStopsAtThreeThousandBoxesAndSaysSo() {
        // A node with as many children as asked for, each a leaf.
        class Wide(val n: Int) : Raw {
            override val cls = "android.widget.LinearLayout"
            override val id: String? = null
            override val password = false
            override fun text(): CharSequence? = null
            override fun desc(): CharSequence? = null
            override fun bounds() = Area(0, 0, 100, 100)
            override val order = 0
            override val can = emptyList<String>()
            override val visible = true
            override val count = n
            override fun child(i: Int): Raw? = Wide(0)
        }
        val r = XrayTree.read(Wide(5000), native)
        assertEquals(3000, r.boxes.size)
        assertTrue(r.cut)
        // And at depth 64.
        class Deep(val d: Int) : Raw by Wide(0) { override val count = 1; override fun child(i: Int): Raw? = Deep(d + 1) }
        val deep = XrayTree.read(Deep(0), native)
        assertEquals(65, deep.boxes.size)
        assertTrue(deep.cut)
    }

    // ---- which window, and which app ----

    @Test fun theAppInFrontIsTheTopmostApplicationWindowNeverTheKeyboardOrASystemWindow() {
        fun w(id: Int, type: Int) = XrayTree.Win(id, type)
        // Listed as Android lists them, topmost first: X-ray's own overlay, the keyboard, the status bar, the app.
        val windows = listOf(w(7, XrayTree.ACCESSIBILITY_OVERLAY), w(6, XrayTree.INPUT_METHOD), w(5, XrayTree.SYSTEM), w(4, XrayTree.APPLICATION), w(3, XrayTree.APPLICATION))
        assertEquals(4, XrayTree.front(windows)!!.id)
        assertNull(XrayTree.front(listOf(w(6, XrayTree.INPUT_METHOD), w(5, XrayTree.SYSTEM))))
        assertNull(XrayTree.front(emptyList()))
    }

    @Test fun whatAPackageIsBuiltWithIsReadFromItsFileNames() {
        fun names(file: String) = javaClass.classLoader!!.getResource("xray/$file")!!.readText().lines()
            .mapNotNull { line -> if (line.startsWith("#")) null else Regex("""(\S+)\s*$""").find(line)?.groupValues?.get(1) }
        val tools = Kinds.of(names("apk-tools-unzip-l.txt"))
        assertTrue(tools.flutter); assertFalse(tools.reactNative); assertFalse(tools.compose)
        val settings = Kinds.of(names("apk-settings-unzip-l.txt"))
        assertFalse(settings.flutter); assertTrue(settings.compose)
        val rn = Kinds.of(names("apk-react-native-076-by-hand.txt"))
        assertTrue(rn.reactNative); assertFalse(rn.flutter)
        assertEquals(listOf("Unity"), Kinds.of(names("apk-unity-by-hand.txt")).others)
        assertEquals(listOf(".NET"), Kinds.of(names("apk-dotnet-by-hand.txt")).others)
        assertEquals(emptyList<String>(), Kinds.of(names("apk-chrome-unzip-l.txt")).others)
    }

    @Test fun anOtherKindIsSaidOnTheCardOfANativeBox() {
        val unity = Kinds(flutter = false, reactNative = false, compose = false, others = listOf("Unity"))
        val r = XrayTree.read(root("chrome-uiautomator.xml"), unity)
        assertTrue(XrayTree.card(r.boxes[0], unity).contains("The package is built with Unity too"))
    }

    // ---- where the controls may sit ----

    /** The emulator: 1248 by 1972, a 128 px status bar, the camera's cut-out at 492, 0 to 610, 128, the gesture strip from 1909. */
    private val cutout = Area(492, 0, 610, 128)
    private val margin = 21

    @Test fun theBarIsKeptOutOfTheStatusBarAndClearOfTheCamera() {
        val (x, y) = XrayTree.keep(Area(300, 10, 1035, 400), 1248, 1972, 128, 63, listOf(cutout), margin)
        assertEquals(300, x)
        assertEquals(128 + margin, y)
        // Off the screen to the right and below: back on it, above the gesture strip.
        val (x2, y2) = XrayTree.keep(Area(1000, 1900, 1735, 2290), 1248, 1972, 128, 63, listOf(cutout), margin)
        assertEquals(1248 - 735, x2)
        assertEquals(1972 - 63 - 390, y2)
        // Somewhere free: left where it is.
        assertEquals(100 to 900, XrayTree.keep(Area(100, 900, 835, 1290), 1248, 1972, 128, 63, listOf(cutout), margin))
    }

    @Test fun turnedOnItsSideTheCameraIsAtTheLeftAndTheBarStaysClearOfIt() {
        // Turned a quarter: 1972 by 1248, the cut-out down the left edge at 0, 492 to 128, 610, the status bar 64 px.
        val side = Area(0, 492, 128, 610)
        val (x, y) = XrayTree.keep(Area(0, 400, 735, 790), 1972, 1248, 64, 0, listOf(side), margin)
        assertEquals(128 + margin, x)
        assertEquals(400, y)
        // Kept clear of a frame round the camera too, such as the Dock's (here 0 to 154 tall).
        val (_, y2) = XrayTree.keep(Area(300, 10, 1035, 400), 1248, 1972, 128, 63, listOf(cutout, Area(277, 0, 825, 154)), margin)
        assertEquals(154 + margin, y2)
    }

    // ---- whose switch is on ----

    @Test fun aServiceIsOnOnlyByItsWholeName() {
        val pkg = "com.mattssoftware.tools"
        val xray = "com.mattssoftware.xray.XrayAccess"
        val dock = "com.mattssoftware.dock.DockAccess"
        val both = "$pkg/$dock:$pkg/$xray"
        assertTrue(Switched.has(both, pkg, xray)); assertTrue(Switched.has(both, pkg, dock))
        assertTrue(Switched.has("$pkg/$dock", pkg, dock)); assertFalse(Switched.has("$pkg/$dock", pkg, xray))
        assertTrue(Switched.has("$pkg/$xray", pkg, xray)); assertFalse(Switched.has("$pkg/$xray", pkg, dock))
        assertFalse(Switched.has(null, pkg, xray)); assertFalse(Switched.has("", pkg, xray))
        // Android's short form, where the class is under the package's own name.
        assertTrue(Switched.has("$pkg/.Inside", pkg, "$pkg.Inside"))
        // A stray prefix of the app's name is not a service.
        assertFalse(Switched.has("$pkg/", pkg, xray))
        assertFalse(Switched.has("$pkg/com.mattssoftware.xray.XrayAccessOld", pkg, xray))
        assertFalse(Switched.has("com.example.other/$xray", pkg, xray))
    }
}
