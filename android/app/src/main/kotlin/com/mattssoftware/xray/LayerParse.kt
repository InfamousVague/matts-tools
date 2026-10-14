package com.mattssoftware.xray

/**
 * The compositor's layers for the app in front, from what `dumpsys SurfaceFlinger` prints: the names, ids, parents and
 * z of `--list`, and the places of the whole dump. Nothing here touches Android, so it is tried against what phones
 * printed (src/test). Layers.kt runs the two dumps and hands the text in; the text goes as soon as this has read it.
 *
 * From Android 15 `--list` prints each layer as `RequestedLayerState{<name> parentId=<id> relativeParentId=<id> z=<n>}`
 * in no order that means anything, so the order is rebuilt here from parents, relative parents and z (facts.md 8a);
 * Android 11 to 14 print the names alone, in z order. Places are in the whole dump's "Composition list", whose bounds
 * read left, top, bottom, right, or else in the HWC table's "Disp Frame (LTRB)", whose names are cut short with
 * `[...]` and so are matched by the `#id` at their end (facts.md 8b).
 */
object LayerParse {
    /** One line of `--list`. [name] is whole, the letters and digits before it too: the phone answers nothing without them. */
    class Layer(val id: Int, val name: String, val parent: Int?, val relative: Int?, val z: Int)

    /**
     * One of the app's layers. [kind] is "activity", "input sink", "window", "view root" (the layer the window's frames
     * are on: `VRI-` on Android 17), "bounds", "surface holder", "surface", "background", "letterbox", "popup" or
     * "layer"; [words] says it for a person. [order] counts from the bottom among the app's layers. [place] is null
     * where the phone does not give one.
     */
    class AppLayer(
        val id: Int, val name: String, val kind: String, val words: String, val parent: Int?, val relative: Int?, val z: Int,
        val order: Int, val place: XrayTree.Area?,
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "id" to id, "name" to name, "kind" to kind, "words" to words, "z" to z, "order" to order,
            "l" to place?.l, "t" to place?.t, "r" to place?.r, "b" to place?.b,
        )
    }

    // The name ends at its #id, where the fields begin (a name may hold spaces, braces and a # of its own).
    private val line = Regex("""^(.*?#(\d+))(?=\s+(?:parentId=|relativeParentId=|mirrorId=|!handle|z=|layerStack=)|\s*$)""")
    private val parentOf = Regex("""\sparentId=(\d+)""")
    private val relativeOf = Regex("""\srelativeParentId=(\d+)""")
    private val zOf = Regex("""\sz=(-?\d+)""")
    private val hex = Regex("""^[0-9a-f]{5,8} """)

    fun list(text: String): List<Layer> = text.lineSequence().mapNotNull { raw ->
        val inner = raw.trim().let { if (it.startsWith("RequestedLayerState{")) it.removePrefix("RequestedLayerState{").removeSuffix("}") else it }
        val m = line.find(inner) ?: return@mapNotNull null
        val rest = inner.substring(m.range.last + 1)
        Layer(
            m.groupValues[2].toIntOrNull() ?: return@mapNotNull null, m.groupValues[1],
            parentOf.find(rest)?.groupValues?.get(1)?.toIntOrNull(), relativeOf.find(rest)?.groupValues?.get(1)?.toIntOrNull(),
            zOf.find(rest)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
        )
    }.toList()

    private val composed = Regex("""^\s+Layer \[(\d+)\]""")
    // Android's patterns (ICU) want a closing bracket or brace escaped where the JVM's do not.
    private val bounds = Regex("""(?<![A-Za-z])bounds=\{(-?\d+),(-?\d+),(-?\d+),(-?\d+)\}""")

    /** Places from the whole dump's "Composition list (bottom to top)" (Android 15 and later), by layer id. */
    fun composition(full: String): Map<Int, XrayTree.Area> {
        val places = HashMap<Int, XrayTree.Area>()
        var inside = false
        var at: Int? = null
        for (l in full.lineSequence()) {
            if (l.startsWith("Composition list")) { inside = true; continue }
            if (!inside) continue
            if (l.isNotEmpty() && !l[0].isWhitespace() && !l.startsWith("LayerStack")) { inside = false; at = null; continue }
            composed.find(l)?.let { at = it.groupValues[1].toInt(); return@let }
            val id = at ?: continue
            bounds.find(l)?.let { b ->
                // Printed left, top, bottom, right.
                val (left, top, bottom, right) = b.destructured
                places.putIfAbsent(id, XrayTree.Area(left.toInt(), top.toInt(), right.toInt(), bottom.toInt()))
                at = null
            }
        }
        return places
    }

    private val endId = Regex("""#(\d+)\s*$""")

    /** Places from each display's HWC table, `Disp Frame (LTRB)`, by the id at the end of each name. */
    fun hwc(full: String): Map<Int, XrayTree.Area> {
        val places = HashMap<Int, XrayTree.Area>()
        var column = -1
        var at: Int? = null
        for (l in full.lineSequence()) {
            if (l.contains("Disp Frame (LTRB)")) { column = l.split('|').indexOfFirst { it.contains("Disp Frame") }; at = null; continue }
            if (column < 0) continue
            if (l.isNotEmpty() && !l[0].isWhitespace() && !l.startsWith("-")) { column = -1; continue }
            if (!l.contains('|')) { at = if (l.startsWith("-")) null else endId.find(l)?.groupValues?.get(1)?.toIntOrNull(); continue }
            val id = at ?: continue
            val n = l.split('|').getOrNull(column)?.trim()?.split(Regex("""\s+"""))?.mapNotNull { it.toIntOrNull() } ?: continue
            if (n.size == 4) places.putIfAbsent(id, XrayTree.Area(n[0], n[1], n[2], n[3]))
            at = null
        }
        return places
    }

    /** Every place the whole dump gives: the composition list where it has one, the HWC table otherwise. */
    fun places(full: String): Map<Int, XrayTree.Area> = hwc(full) + composition(full)

    /**
     * The layers of [pkg]'s activity in front, bottom to top. Where the list has parents, they are the activity's
     * layer and everything under it but the keyboard's container (it hangs under the activity the keyboard types
     * into); where it has none (Android 11 to 14), the layers named for the package.
     */
    fun of(list: String, pkg: String, places: Map<Int, XrayTree.Area>, flutter: Boolean): List<AppLayer> {
        val layers = list(list)
        if (layers.isEmpty()) return emptyList()
        val byId = layers.associateBy { it.id }
        val (order, shown) = order(layers, byId)
        val named = layers.filter { kindOf(it, pkg) != null }

        val chosen: Set<Int> = if (layers.none { it.parent != null }) named.map { it.id }.toSet() else {
            val children = layers.filter { it.parent != null }.groupBy { it.parent!! }
            fun under(id: Int, into: MutableSet<Int>) {
                if (!into.add(id)) return
                for (c in children[id].orEmpty()) if (!c.name.startsWith("ImeContainer")) under(c.id, into)
            }
            val activities = named.filter { kindOf(it, pkg) == "activity" }
            val front = activities.filter { it.id in shown }.maxByOrNull { order[it.id] ?: -1 } ?: activities.maxByOrNull { order[it.id] ?: -1 }
            mutableSetOf<Int>().also { into ->
                if (front != null) under(front.id, into) else named.forEach { under(it.id, into) }
            }
        }

        // A SurfaceView's own layer holds its BLAST layer where it has one; on its own, it is the surface.
        val blast = layers.filter { it.id in chosen && it.name.contains("(BLAST)") }.map { hexOf(it.name) }.toSet()
        return layers.filter { it.id in chosen }.sortedBy { order[it.id] ?: Int.MAX_VALUE }.mapIndexed { i, l ->
            var kind = kindOf(l, pkg) ?: when {
                l.name.contains("PopupWindow") -> "popup"
                l.name.startsWith("Letterbox") -> "letterbox"
                else -> "layer"
            }
            if (kind == "surface" && !l.name.contains("(BLAST)") && hexOf(l.name) in blast) kind = "surface holder"
            AppLayer(l.id, l.name, kind, words(kind, flutter), l.parent, l.relative, l.z, i, places[l.id])
        }
    }

    private fun hexOf(name: String) = hex.find(name)?.value?.trim()

    /** What a layer named for [pkg] is, or null where its name is not the package's. */
    private fun kindOf(l: Layer, pkg: String): String? {
        val name = l.name
        if (name.startsWith("ActivityRecord{")) return "activity".takeIf { name.contains(" $pkg/") }
        val bare = if (hex.containsMatchIn(name)) name.substringAfter(' ') else name
        return when {
            bare.startsWith("ActivityRecordInputSink $pkg/") -> "input sink"
            bare.startsWith("Background for ") && bare.contains("SurfaceView[$pkg/") -> "background"
            bare.startsWith("SurfaceView[$pkg/") || bare.startsWith("SurfaceView - $pkg/") -> "surface"
            bare.startsWith("VRI-$pkg/") -> "view root"
            bare.startsWith("Bounds for - $pkg/") -> "bounds"
            // The window's own layer carries the letters and digits; the one under it without them holds its frames (Android 12 to 16).
            bare.startsWith("$pkg/") -> if (bare == name) "view root" else "window"
            else -> null
        }
    }

    fun words(kind: String, flutter: Boolean) = when (kind) {
        "activity" -> "Activity"
        "input sink" -> "Input sink: takes touches that miss the windows"
        "window" -> "Window"
        "view root" -> "View root: the window's frames"
        "bounds" -> "Bounds the surface is cut to"
        "surface holder" -> "Holds the surface under the window"
        "surface" -> if (flutter) "Flutter surface" else "A surface of its own, such as video or a game"
        "background" -> "Background behind the surface"
        "letterbox" -> "Letterbox the system draws round the app"
        "popup" -> "Popup window"
        else -> "Another layer of the app"
    }

    /**
     * Where each layer is drawn, bottom to top, from the displays down: a layer sits with its relative parent where it
     * has one, else with its parent; among those, by z and then by id, below its parent when z is under 0. Also which
     * layers hang from a display at all (the rest are off the screen). Without parents the list's own order is kept.
     */
    private fun order(layers: List<Layer>, byId: Map<Int, Layer>): Pair<Map<Int, Int>, Set<Int>> {
        val at = HashMap<Int, Int>()
        if (layers.none { it.parent != null }) { layers.forEachIndexed { i, l -> at.putIfAbsent(l.id, i) }; return at to at.keys }
        fun drawParent(l: Layer) = l.relative?.takeIf { it in byId } ?: l.parent?.takeIf { it in byId }
        val children = HashMap<Int, MutableList<Layer>>()
        val roots = ArrayList<Layer>()
        for (l in layers) drawParent(l)?.let { children.getOrPut(it) { ArrayList() } += l } ?: roots.add(l)
        val sort = compareBy<Layer>({ it.z }, { it.id })
        children.values.forEach { it.sortWith(sort) }
        var next = 0
        fun visit(l: Layer) {
            if (l.id in at) return
            at[l.id] = -1
            val kids = children[l.id].orEmpty()
            kids.filter { it.z < 0 }.forEach { visit(it) }
            at[l.id] = next++
            kids.filter { it.z >= 0 }.forEach { visit(it) }
        }
        roots.filter { it.name.startsWith("Display ") }.sortedWith(sort).forEach { visit(it) }
        val shown = at.keys.toSet()
        roots.forEach { visit(it) }
        // A loop of parents (never seen) leaves layers unreached: they go on top, in the list's order.
        layers.forEach { visit(it) }
        return at to shown
    }

    /** The layers of [layers] with a place at [x], [y], top first. */
    private fun at(layers: List<AppLayer>, x: Int, y: Int) = layers.filter { it.place?.has(x, y) == true }.sortedByDescending { it.order }

    /**
     * The topmost of [layers] with a place at [x], [y]; or, where [after] is the id of one of them, the one under it,
     * and the topmost again under the last. A window's frames, its Flutter surface and the surface's background often
     * fill the same place, so a tap that always took the topmost could never reach the ones beneath.
     */
    fun pick(layers: List<AppLayer>, x: Int, y: Int, after: Int? = null): AppLayer? {
        val here = at(layers, x, y)
        val i = here.indexOfFirst { it.id == after }
        return if (i < 0) here.firstOrNull() else here[(i + 1) % here.size]
    }

    /** Where [layer] is among the layers at [x], [y]: which, from the top, and of how many; null where it is not there. */
    fun here(layers: List<AppLayer>, layer: AppLayer, x: Int, y: Int): Pair<Int, Int>? {
        val here = at(layers, x, y)
        val i = here.indexOfFirst { it.id == layer.id }
        return if (i < 0) null else (i + 1) to here.size
    }

    /** What the overlay's card says of [layer], one of [of], and, where others share its spot, which of them it is ([here]). */
    fun card(layer: AppLayer, of: Int, here: Pair<Int, Int>? = null): List<String> = listOfNotNull(
        layer.words,
        layer.name,
        layer.place?.let { "${it.l}, ${it.t}, ${it.width} by ${it.height}" } ?: "Place not given",
        "${layer.order + 1} of $of from the bottom, z ${layer.z}",
        here?.takeIf { it.second > 1 }?.let { (at, count) -> "$at of $count here, from the top: tap again for the one under it" },
    )
}
