package com.mattssoftware.xray

/**
 * The live tree: what is on the screen of the app in front, as boxes, each read by the inspector for its kind of app
 * (native views, Jetpack Compose, React Native, Flutter). The service (XrayAccess.kt) hands in the window's root as a
 * [Raw] and gets back the boxes; the overlay (Overlay.kt) peels, picks and writes the card from them. Nothing here
 * touches Android, so it is tried against dumped trees on a computer (src/test).
 *
 * What is kept of a box's text is as much as tells boxes apart and no more: 40 characters on one line. A node that
 * says it is a password is never asked for its text or its description, so what is typed in a field that hides it is
 * never read (the brief: "What is typed in a field that hides it is never read or shown").
 */
object XrayTree {
    // AccessibilityWindowInfo's window types, by number, so this file needs nothing of Android.
    const val APPLICATION = 1
    const val INPUT_METHOD = 2
    const val SYSTEM = 3
    const val ACCESSIBILITY_OVERLAY = 4

    /** At most this many boxes and this deep: past either the reading stops and says it was cut short. */
    const val MOST = 3000
    const val DEEPEST = 64
    /** Characters of a box's text kept. */
    const val WORDS = 40

    class Area(val l: Int, val t: Int, val r: Int, val b: Int) {
        val width get() = r - l
        val height get() = b - t
        fun has(x: Int, y: Int) = x >= l && x < r && y >= t && y < b
    }

    /** A node of the tree as the service is given it (AccessibilityNodeInfo, in XrayAccess.kt). */
    interface Raw {
        val cls: String?
        val id: String?
        val password: Boolean
        /** Never called for a node whose [password] is true. */
        fun text(): CharSequence?
        /** Never called for a node whose [password] is true. */
        fun desc(): CharSequence?
        fun bounds(): Area
        val order: Int
        /** What can be done to it, as words: "tapped", "long pressed", "scrolled", "checked", "typed into". */
        val can: List<String>
        val visible: Boolean
        val count: Int
        fun child(i: Int): Raw?
    }

    /**
     * What the package of the app in front is built with, from its file names alone (Anatomy.kt `kinds`): it decides
     * which inspector may read a box, since the tree alone cannot tell Compose from native for certain.
     */
    class Kinds(val flutter: Boolean, val reactNative: Boolean, val compose: Boolean, val others: List<String> = emptyList()) {
        companion object {
            private val lib = Regex("""^lib/[^/]+/([^/]+\.so)$""")

            fun of(names: Iterable<String>): Kinds {
                val libs = HashSet<String>()
                var flutterAssets = false; var bundle = false; var compose = false; var www = false; var capacitor = false
                for (n in names) {
                    lib.find(n)?.let { libs += it.groupValues[1] }
                    if (n.startsWith("assets/flutter_assets/")) flutterAssets = true
                    if (n == "assets/index.android.bundle") bundle = true
                    if (n == "META-INF/androidx.compose.ui_ui.version") compose = true
                    if (n == "assets/www/cordova.js") www = true
                    if (n == "assets/capacitor.config.json") capacitor = true
                }
                val others = listOfNotNull(
                    "Unity".takeIf { "libunity.so" in libs },
                    ".NET".takeIf { "libmonodroid.so" in libs || "libassembly-store.so" in libs },
                    "Capacitor".takeIf { capacitor },
                    "Cordova".takeIf { www && !capacitor },
                )
                return Kinds(
                    flutter = "libflutter.so" in libs || flutterAssets,
                    reactNative = bundle || "libreactnative.so" in libs || "libreactnativejni.so" in libs,
                    compose = compose,
                    others = others,
                )
            }
        }
    }

    /**
     * One box. [inspector] is "native", "compose" (Compose, likely), "react-native" or "flutter"; [kind] is "view",
     * "semantics node" (Flutter's) or "flutter view" (the host Flutter draws in). [testId] is a React Native testID.
     */
    class Box(
        val i: Int, val parent: Int?, val depth: Int, val kind: String, val inspector: String,
        val cls: String?, val id: String?, val testId: String?, val label: String?, val area: Area, val order: Int,
        val can: List<String>, val password: Boolean, val visible: Boolean,
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "i" to i, "parent" to parent, "depth" to depth, "kind" to kind, "inspector" to inspector, "cls" to cls, "id" to id,
            "testId" to testId, "label" to label, "l" to area.l, "t" to area.t, "r" to area.r, "b" to area.b, "order" to order,
            "can" to can, "password" to password, "visible" to visible,
        )
    }

    class Reading(val boxes: List<Box>, val cut: Boolean) {
        /** The inspectors that read some box, in the order the page names them. */
        val inspectors: List<String>
            get() {
                val used = boxes.map { it.inspector }.toSet()
                return listOf("native" to "Native", "compose" to "Jetpack Compose, likely", "react-native" to "React Native", "flutter" to "Flutter")
                    .filter { it.first in used }.map { it.second }
            }
    }

    /** A box's few words: its text, or else its description, on one line and cut short. Null where there are none. */
    fun label(text: CharSequence?, desc: CharSequence?): String? {
        val words = listOf(text, desc).map { it?.toString()?.replace(Regex("""\s+"""), " ")?.trim() ?: "" }.firstOrNull { it.isNotEmpty() } ?: return null
        return if (words.length <= WORDS) words else words.take(WORDS - 3) + "..."
    }

    // The node as first read, before an inspector is chosen for it.
    private class Read(
        val parent: Int?, val depth: Int, val cls: String?, val id: String?, val hasText: Boolean, val label: String?, val area: Area,
        val order: Int, val can: List<String>, val password: Boolean, val visible: Boolean,
    ) { val children = ArrayList<Int>() }

    // The classes Flutter's bridge gives its semantics nodes (facts.md 4d; the engine's BaseRoleConfigurator.java and
    // RoleConfiguratorFactory.java, which add ListView, RadioGroup and MenuItem), and Compose its nodes (4b).
    private val flutterClasses = setOf(
        "android.view.View", "android.widget.Button", "android.widget.EditText", "android.widget.ImageView", "android.widget.SeekBar",
        "android.widget.ScrollView", "android.widget.HorizontalScrollView", "android.widget.RadioButton", "android.widget.CheckBox",
        "android.widget.Switch", "android.widget.ProgressBar", "android.widget.Spinner", "android.widget.ListView",
        "android.widget.RadioGroup", "android.view.MenuItem",
    )
    private val composeClasses = setOf(
        "android.view.View", "android.widget.Button", "android.widget.EditText", "android.widget.ImageView", "android.widget.SeekBar",
        "android.widget.ScrollView", "android.widget.HorizontalScrollView", "android.widget.RadioButton", "android.widget.CheckBox",
        "android.widget.Switch", "android.widget.ProgressBar", "android.widget.Spinner", "android.widget.TextView", "android.widget.NumberPicker",
    )
    private val composeHosts = setOf("android.view.ViewGroup", "androidx.compose.ui.platform.ComposeView", "androidx.compose.ui.platform.AndroidComposeView")

    private fun prefixed(id: String?) = id != null && id.contains(":id/")

    /** Reads the tree under [root], depth first, as boxes with the inspector for each. */
    fun read(root: Raw, kinds: Kinds, most: Int = MOST, deepest: Int = DEEPEST): Reading {
        val nodes = ArrayList<Read>()
        var cut = false
        fun walk(raw: Raw, parent: Int?, depth: Int) {
            if (nodes.size >= most) { cut = true; return }
            val password = raw.password
            // Of a password node nothing is asked but its class, id, place and what can be done to it.
            val text = if (password) null else raw.text()
            val desc = if (password) null else raw.desc()
            val at = nodes.size
            nodes += Read(parent, depth, raw.cls, raw.id?.ifEmpty { null }, !text.isNullOrBlank(), if (password) null else label(text, desc), raw.bounds(),
                raw.order, raw.can, password, raw.visible)
            parent?.let { nodes[it].children += at }
            if (raw.count > 0 && depth >= deepest) { cut = true; return }
            for (i in 0 until raw.count) {
                if (nodes.size >= most) { cut = true; return }
                raw.child(i)?.let { walk(it, at, depth + 1) }
            }
        }
        walk(root, null, 0)

        val inspector = arrayOfNulls<String>(nodes.size)
        val kind = Array(nodes.size) { "view" }

        // Flutter: the host is told by itself and its child, not by every node under it, since one ordinary node (a
        // filled text field, whose value Flutter puts in text, or a list) must not unmake the whole screen. The host is
        // a FrameLayout with no id of a package whose children are Flutter's root semantics node: an android.view.View
        // with no id of a package and no text (Flutter puts a label in the description). Under it, a node of the
        // bridge's classes with no id of a package is a semantics node; anything else is a native view Flutter embeds,
        // and it and what is under it stay native.
        if (kinds.flutter) {
            fun root(c: Int) = nodes[c].let { n -> n.cls == "android.view.View" && !prefixed(n.id) && !n.hasText }
            fun mark(i: Int) {
                for (c in nodes[i].children) {
                    val n = nodes[c]
                    if (n.cls !in flutterClasses || prefixed(n.id)) continue
                    inspector[c] = "flutter"; kind[c] = "semantics node"; mark(c)
                }
            }
            for (i in nodes.indices) {
                val n = nodes[i]
                if (inspector[i] != null || n.cls != "android.widget.FrameLayout" || n.children.isEmpty() || prefixed(n.id)) continue
                if (n.children.all { root(it) }) { kind[i] = "flutter view"; inspector[i] = "native"; mark(i) }
            }
        }

        // React Native: everything below android:id/content.
        if (kinds.reactNative) {
            fun mark(i: Int) { for (c in nodes[i].children) { if (inspector[c] == null) inspector[c] = "react-native"; mark(c) } }
            nodes.indices.filter { nodes[it].id == "android:id/content" }.forEach { mark(it) }
        }

        // Compose, likely: no id of a package, a class Compose gives, under a host with no id. The host is a
        // ComposeView on the emulator's Settings (Android 16), and android.view.ViewGroup by Compose's own source.
        if (kinds.compose) {
            fun underHost(i: Int): Boolean {
                var p = nodes[i].parent
                while (p != null) { val n = nodes[p]; if (n.cls in composeHosts && n.id == null) return true; p = n.parent }
                return false
            }
            for (i in nodes.indices) {
                val n = nodes[i]
                if (inspector[i] == null && !prefixed(n.id) && n.cls in composeClasses && underHost(i)) inspector[i] = "compose"
            }
        }

        val boxes = nodes.mapIndexed { i, n ->
            val who = inspector[i] ?: "native"
            // A testID is an id with no package:id/ in front, on a React Native box (React Native 0.64 and later).
            val testId = if (who == "react-native" && n.id != null && !prefixed(n.id)) n.id else null
            Box(i, n.parent, n.depth, kind[i], who, n.cls, n.id, testId, n.label, n.area, n.order, n.can, n.password, n.visible)
        }
        return Reading(boxes, cut)
    }

    /**
     * Peel: every depth ([depth] null), down to a depth, or only one depth. Fewer and More step it one at a time and
     * Only switches between down-to and only; [words] is the line under the bar.
     */
    class Peel(val depth: Int? = null, val only: Boolean = false) {
        fun shows(box: Box) = depth == null || (if (only) box.depth == depth else box.depth <= depth)
        fun fewer(deepest: Int) = Peel(((depth ?: deepest) - 1).coerceAtLeast(0), only)
        fun more(deepest: Int): Peel {
            val now = depth ?: return this
            return if (now + 1 > deepest) (if (only) Peel(deepest, true) else Peel()) else Peel(now + 1, only)
        }
        fun flip(deepest: Int) = if (only) Peel(depth, false) else Peel(depth ?: 0, true).let { if (it.depth!! > deepest) Peel(deepest, true) else it }
        fun words(deepest: Int) = when {
            depth == null -> "All depths"
            only -> "Only depth $depth of $deepest"
            else -> "Down to depth $depth of $deepest"
        }
    }

    /** The innermost visible box at [x], [y] among [boxes]: the deepest, then the smallest, then the last drawn. */
    fun pick(boxes: List<Box>, x: Int, y: Int): Box? =
        boxes.filter { it.visible && it.area.has(x, y) }
            .sortedWith(compareByDescending<Box> { it.depth }.thenBy { it.area.width.toLong() * it.area.height }.thenByDescending { it.i })
            .firstOrNull()

    /** What the card says of [box], at most six lines. [surface]: the compositor showed the app's Flutter surface. */
    fun card(box: Box, kinds: Kinds, surface: Boolean = false): List<String> {
        val lines = ArrayList<String>()
        lines += when {
            box.kind == "flutter view" && surface -> "Flutter view: it draws to the Flutter surface"
            box.kind == "flutter view" -> "Flutter view: it draws to a surface of its own"
            box.inspector == "flutter" -> "Flutter, semantics node"
            box.inspector == "react-native" && box.testId != null -> "React Native, testID ${box.testId}"
            box.inspector == "react-native" -> "React Native, ${box.cls ?: "a view"}"
            box.inspector == "compose" -> "Jetpack Compose, likely: ${box.cls ?: "a node"}"
            else -> "Native, ${box.cls ?: "a view"}"
        }
        val name = listOfNotNull(box.id?.takeIf { box.testId == null }, box.label).joinToString(", ")
        if (name.isNotEmpty()) lines += name
        lines += "${box.area.l}, ${box.area.t}, ${box.area.width} by ${box.area.height}"
        lines += "Depth ${box.depth}"
        if (box.can.isNotEmpty()) lines += "Can be " + box.can.let { if (it.size == 1) it[0] else it.dropLast(1).joinToString(", ") + " and " + it.last() }
        if (box.inspector == "flutter" || box.kind == "flutter view") lines += "Widget names are not in a release build"
        else if (kinds.others.isNotEmpty()) lines += "The package is built with ${kinds.others.joinToString(" and ")} too"
        return lines.take(6)
    }

    /** A window as Android lists them, topmost first. */
    data class Win(val id: Int, val type: Int)

    /**
     * The app in front: the topmost application window. Never the keyboard's (its suggestions can show what is being
     * typed), never a system window (the shade, the lock screen), never an accessibility overlay (X-ray's own).
     */
    fun front(windows: List<Win>): Win? = windows.firstOrNull { it.type == APPLICATION }

    /**
     * Where a window of X-ray's controls, wanted at [at], may sit on a screen [wide] by [tall]: on the screen, under the
     * status bar's strip ([top]), above the navigation bar ([bottom]), and [margin] clear of each of [keepOut] (the
     * camera's cut-out, and the Dock's frame round it), which bars the whole band along the edge it is nearest, so the
     * controls never land under the camera whatever way the phone is turned.
     */
    fun keep(at: Area, wide: Int, tall: Int, top: Int, bottom: Int, keepOut: List<Area>, margin: Int): Pair<Int, Int> {
        var minX = 0; var maxX = wide - at.width
        var minY = top; var maxY = tall - bottom - at.height
        for (k in keepOut) {
            val edges = listOf(k.t, k.l, wide - k.r, tall - k.b)
            when (edges.indexOf(edges.min())) {
                0 -> minY = maxOf(minY, k.b + margin)
                1 -> minX = maxOf(minX, k.r + margin)
                2 -> maxX = minOf(maxX, k.l - margin - at.width)
                else -> maxY = minOf(maxY, k.t - margin - at.height)
            }
        }
        // Where it cannot fit both ways, the top and the left win: never under the status bar or the camera.
        val x = at.l.coerceAtMost(maxX).coerceAtLeast(minX)
        val y = at.t.coerceAtMost(maxY).coerceAtLeast(minY)
        return x to y
    }
}
