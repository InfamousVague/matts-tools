package com.mattssoftware.xray

/**
 * What an app is made of, worked out from the names and sizes of the entries in its package files and a few small
 * facts read beside them (Anatomy.kt does the reading). Nothing here touches Android, so it can be tried against
 * listings on a computer (src/test). Every claim names the file it rests on, and what the package cannot say is said,
 * not guessed: no framework version is read out of a file name that does not hold one.
 */
object AnatomyParse {
    /** One entry of a package file's directory: [file] is the .apk it is in (base or a split). */
    class Entry(val name: String, val size: Long, val packed: Long, val file: String)

    /** What is known beside the entries. Null where it could not be read or is not there. */
    class Extra(
        val packageName: String,
        val versionName: String?,
        val versionCode: Long,
        val minSdk: Int,
        val targetSdk: Int,
        val compileSdk: Int?,
        /** The manifest's `io.flutter.embedding.android.EnableImpeller`, where it has one. */
        val impeller: Boolean?,
        /** The first bytes of `assets/index.android.bundle`. */
        val bundleHead: ByteArray?,
        /** The text of `flutter_assets/FontManifest.json`. */
        val fontManifest: String?,
        /** The text of `META-INF/androidx.compose.ui_ui.version`. */
        val composeVersion: String?,
        /** The package files that were opened, and their sizes. */
        val files: List<Pair<String, Long>>,
        /** Package files that Android would not let be read. */
        val unreadable: List<String>,
        /** Library packages the app uses, whose insides are not read. */
        val libraryPackages: Int,
    )

    class Evidence(val name: String, val size: Long?)
    class Detail(val label: String, val value: String, val size: Long? = null)
    class Kind(val name: String, val evidence: List<Evidence>, val details: List<Detail>)
    class Lib(val abi: String, val files: List<Evidence>)
    class Assets(val kind: String, val count: Int, val bytes: Long)

    class Result(
        val extra: Extra,
        val entries: Int,
        val kinds: List<Kind>,
        val libs: List<Lib>,
        val assets: List<Assets>,
        val dexCount: Int,
        val dexBytes: Long,
        val cannot: List<String>,
    ) {
        /** "Flutter and native", "Native", "React Native, Expo and native". */
        val headline: String
            get() {
                val names = kinds.map { if (it.name == "Native") "native" else it.name }
                val first = names.first().replaceFirstChar { it.uppercase() }
                val all = listOf(first) + names.drop(1)
                return if (all.size == 1) all[0] else all.dropLast(1).joinToString(", ") + " and " + all.last()
            }

        /** The shape the page reads over the channel (lib/xray/xray.dart `Anatomy`). */
        fun toMap(): Map<String, Any?> = mapOf(
            "package" to extra.packageName,
            "version" to extra.versionName,
            "versionCode" to extra.versionCode,
            "headline" to headline,
            "kinds" to kinds.map { k ->
                mapOf(
                    "name" to k.name,
                    "evidence" to k.evidence.map { mapOf("name" to it.name, "size" to it.size) },
                    "details" to k.details.map { mapOf("label" to it.label, "value" to it.value, "size" to it.size) },
                )
            },
            "libs" to libs.map { l -> mapOf("abi" to l.abi, "total" to l.files.sumOf { it.size ?: 0L }, "files" to l.files.map { mapOf("name" to it.name, "size" to it.size) }) },
            "assets" to assets.map { mapOf("kind" to it.kind, "count" to it.count, "bytes" to it.bytes) },
            "dex" to mapOf("count" to dexCount, "bytes" to dexBytes),
            "sdk" to mapOf("min" to extra.minSdk, "target" to extra.targetSdk, "compile" to extra.compileSdk),
            "apk" to mapOf("files" to extra.files.map { mapOf("name" to it.first, "size" to it.second) }, "entries" to entries),
            "cannot" to cannot,
        )
    }

    /** The first 8 bytes of a Hermes bytecode file: facebook/hermes `BytecodeFileFormat.h`, 0x1F1903C103BC1FC6, little-endian. */
    val hermesMagic = byteArrayOf(0xC6.toByte(), 0x1F, 0xBC.toByte(), 0x03, 0xC1.toByte(), 0x03, 0x19, 0x1F)

    fun isHermes(head: ByteArray?): Boolean = head != null && head.size >= 8 && (0 until 8).all { head[it] == hermesMagic[it] }

    private val lib = Regex("""^lib/([^/]+)/([^/]+\.so)$""")
    private val dex = Regex("""^classes\d*\.dex$""")
    private val family = Regex(""""family"\s*:\s*"([^"]*)"""")

    private val images = setOf("png", "jpg", "jpeg", "webp", "gif", "svg", "ico", "bmp", "avif")
    private val fonts = setOf("ttf", "otf", "woff", "woff2", "ttc")
    private val media = setOf("mp3", "ogg", "wav", "m4a", "aac", "flac", "mp4", "webm", "mkv", "3gp", "mov")
    private val shaders = setOf("frag", "vert", "spv", "glsl", "shader")
    private val web = setOf("html", "htm", "js", "mjs", "css", "map")
    private val data = setOf("json", "xml", "bin", "db", "sqlite", "pb", "proto", "txt", "csv", "dat", "pak", "prof", "profm", "yaml", "yml", "properties", "arsc", "bundle", "z")

    /** The kind an asset is by its file name's ending. */
    fun assetKind(name: String): String {
        val ext = name.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return when (ext) {
            in images -> "Images"
            in fonts -> "Fonts"
            in media -> "Audio and video"
            in shaders -> "Shaders"
            in web -> "Web pages"
            in data -> "Data"
            else -> "Other"
        }
    }

    /** The family names a Flutter `FontManifest.json` lists, in order, each once. */
    fun fontFamilies(manifest: String?): List<String> = family.findAll(manifest ?: "").map { it.groupValues[1] }.distinct().toList()

    fun parse(entries: List<Entry>, extra: Extra): Result {
        val names = entries.map { it.name }.toSet()
        fun has(name: String) = name in names
        fun hasPrefix(prefix: String) = entries.any { it.name.startsWith(prefix) }
        fun ev(vararg wanted: String) = entries.filter { it.name in wanted }.map { Evidence(it.name, it.size) }
        fun libs(file: String) = entries.filter { lib.matches(it.name) && it.name.substringAfterLast('/') == file }
        val cannot = mutableListOf<String>()

        // ---- native libraries, by ABI ----
        val byAbi = entries.mapNotNull { e -> lib.find(e.name)?.let { it.groupValues[1] to e } }
            .groupBy({ it.first }, { it.second })
            .map { (abi, list) -> Lib(abi, list.sortedByDescending { it.size }.map { Evidence(it.name.substringAfterLast('/'), it.size) }) }
            .sortedBy { it.abi }

        // ---- assets, by kind ----
        val assetEntries = entries.filter { it.name.startsWith("assets/") }
        val assets = assetEntries.groupBy { assetKind(it.name) }.map { (kind, list) -> Assets(kind, list.size, list.sumOf { it.size }) }
            .toMutableList()
        val res = entries.filter { it.name.startsWith("res/") || it.name == "resources.arsc" }
        if (res.isNotEmpty()) assets += Assets("Resources", res.size, res.sumOf { it.size })
        assets.sortByDescending { it.bytes }

        val dexes = entries.filter { dex.matches(it.name) }

        // ---- what it is built with ----
        val kinds = mutableListOf<Kind>()

        val flutterEngine = entries.filter { lib.matches(it.name) && it.name.endsWith("/libflutter.so") }
        if (flutterEngine.isNotEmpty() || hasPrefix("assets/flutter_assets/")) {
            val details = mutableListOf<Detail>()
            for (e in flutterEngine) details += Detail("Engine", "libflutter.so in ${lib.find(e.name)!!.groupValues[1]}", e.size)
            for (e in entries.filter { lib.matches(it.name) && it.name.endsWith("/libapp.so") }) details += Detail("Compiled app", "libapp.so in ${lib.find(e.name)!!.groupValues[1]}", e.size)
            details += Detail(
                "Build",
                when {
                    has("assets/flutter_assets/kernel_blob.bin") -> "Debug: the Dart code is in kernel_blob.bin"
                    libs("libapp.so").isNotEmpty() -> "Release or profile: the Dart code is compiled into libapp.so"
                    else -> "Not shown by the package"
                },
            )
            details += Detail(
                "Impeller",
                when (extra.impeller) {
                    true -> "Switched on in the manifest"
                    false -> "Switched off in the manifest"
                    null -> "Not switched off in the manifest. Recent Flutter versions draw with Impeller unless told not to, and the package does not say which version built it"
                },
            )
            val families = fontFamilies(extra.fontManifest)
            if (families.isNotEmpty()) details += Detail("Fonts", families.joinToString(", "))
            val packages = entries.mapNotNull { Regex("""^assets/flutter_assets/packages/([^/]+)/""").find(it.name)?.groupValues?.get(1) }.distinct().sorted()
            if (packages.isNotEmpty()) details += Detail("Packages with assets", packages.joinToString(", "))
            val own = entries.filter { it.name.startsWith("assets/flutter_assets/") }
            details += Detail("Flutter assets", "${own.size} files", own.sumOf { it.size })
            kinds += Kind("Flutter", flutterEngine.map { Evidence(it.name, it.size) }.ifEmpty { listOf(Evidence("assets/flutter_assets/", null)) }, details)
            cannot += "Which Flutter version built it: nothing in the package says."
            cannot += "Which Dart packages it uses: only the packages that bring assets of their own show."
            cannot += "Widget names: they are not in a release build."
        }

        val bundle = entries.firstOrNull { it.name == "assets/index.android.bundle" }
        val rnLibs = listOf("libreactnative.so", "libreactnativejni.so").flatMap { libs(it) }
        if (bundle != null || rnLibs.isNotEmpty()) {
            val hermesLib = (libs("libhermes.so") + libs("libhermesvm.so"))
            val details = mutableListOf<Detail>()
            details += if (bundle != null) Detail("Bundle", "assets/index.android.bundle", bundle.size) else Detail("Bundle", "Not in the package's assets")
            val hermes = isHermes(extra.bundleHead)
            details += Detail(
                "Hermes",
                when {
                    hermes && hermesLib.isNotEmpty() -> "Yes: the bundle is Hermes bytecode and ${hermesLib.first().name.substringAfterLast('/')} is in the package"
                    hermes -> "Yes: the bundle is Hermes bytecode"
                    hermesLib.isNotEmpty() -> "Yes: ${hermesLib.first().name.substringAfterLast('/')} is in the package"
                    extra.bundleHead != null -> "No: the bundle is not Hermes bytecode and no Hermes library is in the package"
                    else -> "Not shown by the package"
                },
            )
            val newArch = libs("libhermesvm.so").isNotEmpty()
            details += Detail("New architecture", if (newArch) "Yes: libhermesvm.so is React Native 0.82 or later, which runs only the new architecture" else "Not shown by the package")
            if (libs("libreactnative.so").isNotEmpty()) details += Detail("React Native", "0.76 or later: libreactnative.so is in the package")
            else if (libs("libreactnativejni.so").isNotEmpty()) details += Detail("React Native", "Before 0.76: its libraries are separate files")
            val expo = has("assets/app.config") || libs("libexpo-modules-core.so").isNotEmpty()
            details += Detail("Expo", if (expo) "Yes: ${if (has("assets/app.config")) "assets/app.config" else "libexpo-modules-core.so"} is in the package" else "No Expo file in the package")
            kinds += Kind("React Native", ev("assets/index.android.bundle") + rnLibs.map { Evidence(it.name, it.size) }, details)
            if (!newArch) cannot += "Whether it is on React Native's new architecture: no file says so before version 0.82."
            cannot += "The React Native version: no file in the package says it."
            cannot += "Component names: they are not in a release build."
        }

        val unity = libs("libunity.so")
        if (unity.isNotEmpty()) {
            val details = mutableListOf<Detail>()
            details += Detail("Engine", "libunity.so", unity.first().size)
            details += Detail("IL2CPP", if (libs("libil2cpp.so").isNotEmpty()) "Yes: libil2cpp.so holds the C# scripts" else "No libil2cpp.so in the package", libs("libil2cpp.so").firstOrNull()?.size)
            kinds += Kind("Unity", (unity + libs("libmain.so") + libs("libil2cpp.so")).map { Evidence(it.name, it.size) }, details)
        }

        val dotnet = libs("libmonodroid.so")
        if (dotnet.isNotEmpty() || libs("libassembly-store.so").isNotEmpty()) {
            val details = mutableListOf<Detail>()
            for (e in dotnet) details += Detail("Host", "libmonodroid.so", e.size)
            for (e in libs("libassembly-store.so")) details += Detail("Assemblies", "libassembly-store.so", e.size)
            kinds += Kind(".NET", (dotnet + libs("libassembly-store.so")).map { Evidence(it.name, it.size) }, details)
        }

        val www = entries.filter { it.name.startsWith("assets/www/") }
        if (www.isNotEmpty()) {
            kinds += Kind("Cordova", ev("assets/www/cordova.js").ifEmpty { listOf(Evidence("assets/www/", null)) }, listOf(Detail("Web files", "${www.size} files in assets/www", www.sumOf { it.size })))
        }

        val capacitor = has("assets/capacitor.config.json")
        if (capacitor) {
            val pub = entries.filter { it.name.startsWith("assets/public/") }
            kinds += Kind("Capacitor", ev("assets/capacitor.config.json"), listOf(Detail("Web files", "${pub.size} files in assets/public", pub.sumOf { it.size })))
        }

        // Native is the shell of every app, so it is always there; Compose is told from its version files.
        val compose = entries.filter { it.name.startsWith("META-INF/androidx.compose.") && it.name.endsWith(".version") }
        val ui = compose.firstOrNull { it.name == "META-INF/androidx.compose.ui_ui.version" }
        val nativeDetails = mutableListOf<Detail>()
        nativeDetails += Detail("Android views", "Every app has them: its windows and system dialogs are views")
        if (compose.isNotEmpty()) {
            val version = extra.composeVersion?.trim()?.takeIf { it.isNotEmpty() && it.length <= 24 }
            nativeDetails += Detail(
                "Jetpack Compose",
                "In the package: ${compose.size} Compose version files${if (ui != null) ", androidx.compose.ui_ui" else ""}${if (version != null && ui != null) " $version" else ""}. A version file says the library is there, not that a screen uses it",
            )
        } else {
            nativeDetails += Detail("Jetpack Compose", "Not in the package's version files")
            cannot += "Compose can still be there: an app may leave out its version files."
        }
        kinds += Kind("Native", dexes.map { Evidence(it.name, it.size) }, nativeDetails)

        cannot += "Classes inside the dex files: they are not read."
        if (kinds.size == 1) cannot += "Web view shells are not told apart from other apps: no single file names one."
        if (extra.libraryPackages > 0) cannot += "${extra.libraryPackages} library package${if (extra.libraryPackages == 1) "" else "s"} this app uses: what is inside is not counted."
        for (name in extra.unreadable) cannot += "Android would not let Matt's Tools read $name."

        return Result(extra, entries.size, kinds, byAbi, assets, dexes.size, dexes.sumOf { it.size }, cannot)
    }
}
