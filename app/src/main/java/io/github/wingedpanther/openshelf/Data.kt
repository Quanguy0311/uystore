package io.github.wingedpanther.openshelf

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.StatFs
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Apk(val name: String, val abi: String, val url: String, val size: Long)
data class Release(val tag: String, val date: String)

data class AppArt(val banner: String?, val feature: String?, val icon: String?, val shots: List<String>) {
    val wall: String? get() = shots.firstOrNull() ?: feature ?: banner
    val tile: String? get() = banner ?: feature ?: shots.firstOrNull()
    val gallery: List<String> get() = (shots + listOfNotNull(feature, banner)).distinct()
}

data class App(
    val id: String,
    val repo: String,
    val name: String,
    val pkg: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int?,
    val room: String,
    val note: String,
    val desc: String,
    val maker: String,
    val stars: Int,
    val licence: String?,
    val archived: Boolean,
    val streams: Boolean,
    val version: String,
    val date: String,
    val certSha256: String?,
    val art: AppArt,
    val apks: List<Apk>,
    val releases: List<Release>,
    val forkOf: String?,
    val parentStars: Int,
    val added: String? = null,
    val basedOn: String? = null,
) {
    val runsHere: Boolean get() = (minSdk ?: 0) <= Build.VERSION.SDK_INT
}

object Catalog {
    var apps by mutableStateOf<List<App>>(emptyList())
        private set
    var generated: String = ""
        private set

    suspend fun load(ctx: Context) {
        if (apps.isNotEmpty()) return
        val parsed = withContext(Dispatchers.IO) {
            val root = JSONObject(ctx.assets.open("catalog.json").bufferedReader().use { it.readText() })
            generated = root.getString("generated")
            val list = root.getJSONArray("apps")
            (0 until list.length()).map { parse(list.getJSONObject(it)) }
        }
        apps = parsed
    }

    private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    private fun parse(o: JSONObject): App {
        val art = o.optJSONObject("art") ?: JSONObject()
        val shots = art.optJSONArray("shots")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        return App(
            id = o.getString("id"), repo = o.getString("repo"), name = o.getString("name"),
            pkg = o.getString("package"), versionCode = o.getLong("versionCode"), versionName = o.str("versionName"),
            minSdk = if (o.has("minSdk") && !o.isNull("minSdk")) o.getInt("minSdk") else null,
            room = o.getString("room"), note = o.getString("note"), desc = o.optString("desc"),
            maker = o.getString("maker"), stars = o.getInt("stars"), licence = o.str("licence"),
            archived = o.optBoolean("archived"),
            streams = STREAMS.containsMatchIn(listOf(o.getString("name"), o.getString("note"), o.optString("desc"), o.getString("repo"), o.getString("package"), o.optJSONArray("topics")?.toString().orEmpty()).joinToString(" ")),
            version = o.getString("version"), date = o.getString("date"),
            certSha256 = o.str("certSha256"),
            art = AppArt(art.str("banner"), art.str("feature"), art.str("icon"), shots),
            apks = o.getJSONArray("apks").objects().map { Apk(it.getString("name"), it.getString("abi"), it.getString("url"), it.getLong("size")) },
            releases = o.getJSONArray("releases").objects().map { Release(it.getString("tag"), it.getString("date")) },
            forkOf = o.str("forkOf"), parentStars = o.optInt("parentStars"), added = o.str("added"), basedOn = o.str("basedOn"),
        )
    }

    const val MIN_STARS = 10

    private val STREAMS = Regex("iptv|\\bm3u8?\\b|xtream|debrid|torbox|torrserv|stremio|cloudstream|live ?tv\\b|电视直播|直播源|tvbox", RegexOption.IGNORE_CASE)

    fun shown(a: App) = !(Prefs.hideStreams && a.streams)

    fun added(): List<App> {
        val since = LocalDate.parse(generated.take(10)).minusDays(60).toString()
        return visible.filter { (it.added ?: "") >= since }.sortedWith(compareByDescending<App> { it.added }.thenByDescending { it.stars })
    }

    val visible: List<App> get() = apps.filter { (it.stars >= MIN_STARS || it.parentStars >= MIN_STARS) && shown(it) }

    fun forksOf(a: App): List<App> = apps.filter { it.forkOf == a.repo }

    fun byRepo(repo: String): App? = apps.firstOrNull { it.repo == repo }

    fun sibling(a: App, i: Installed): App? =
        apps.firstOrNull { it.pkg == a.pkg && it.id != a.id && it.certSha256 != null && it.certSha256 in i.certs }

    fun byId(id: String): App? = apps.firstOrNull { it.id == id } ?: SelfUpdate.app?.takeIf { it.id == id }
}

object SelfUpdate {
    private const val REPO = "wingedpanther/openshelf"
    private const val CERT = "e574aa49fb0463eaf1229ce48b2ab201b86f806f38bda0e162a057e5b42355c2"
    private const val ICON = "https://raw.githubusercontent.com/$REPO/main/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"
    private const val EVERY = 12 * 60 * 60 * 1000L

    var app by mutableStateOf<App?>(null)
        private set

    fun ready(ctx: Context): Boolean = app?.let { a -> Device.installed(ctx, a.pkg)?.let { it.versionCode < a.versionCode } } == true

    fun shouldPrompt(ctx: Context): Boolean =
        ready(ctx) && app?.let { ignoredTag(ctx) != it.version } == true

    fun ignore(ctx: Context, tag: String) {
        selfPrefs(ctx).edit().putString("ignored", tag).apply()
    }

    private fun ignoredTag(ctx: Context): String? = selfPrefs(ctx).getString("ignored", null)

    private fun selfPrefs(ctx: Context) = ctx.getSharedPreferences("self", Context.MODE_PRIVATE)

    suspend fun check(ctx: Context) {
        val prefs = ctx.getSharedPreferences("self", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val fresh = now - prefs.getLong("at", 0) < EVERY
        val cached = prefs.getString("release", null)?.takeIf { fresh }?.let { it to prefs.getString("repo", null) }
        val (release, repo) = cached ?: withContext(Dispatchers.IO) {
            runCatching { fetchText("https://api.github.com/repos/$REPO/releases/latest") to fetchText("https://api.github.com/repos/$REPO") }.getOrNull()
        }?.also { (r, p) -> prefs.edit().putLong("at", now).putString("release", r).putString("repo", p).apply() } ?: return
        runCatching { app = parse(ctx, JSONObject(release), repo?.let { JSONObject(it) }) }
    }

    private fun parse(ctx: Context, rel: JSONObject, repo: JSONObject?): App? {
        val own = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val tag = rel.getString("tag_name")
        val assets = rel.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
        val date = rel.getString("published_at").take(10)
        val desc = repo?.optString("description").orEmpty()
        return App(
            id = "openshelf", repo = REPO, name = "Open Shelf", pkg = ctx.packageName,
            versionCode = own.longVersionCode + if (newer(tag.removePrefix("v"), own.versionName.orEmpty())) 1 else 0,
            versionName = tag.removePrefix("v"), minSdk = 28, room = "App stores", note = desc, desc = desc,
            maker = REPO.substringBefore('/'), stars = repo?.optInt("stargazers_count") ?: 0, licence = "GPL-3.0",
            archived = false, streams = false, version = tag, date = date, certSha256 = CERT,
            art = AppArt(null, null, ICON, emptyList()),
            apks = listOf(Apk(apk.getString("name"), "any", apk.getString("browser_download_url"), apk.getLong("size"))),
            releases = listOf(Release(tag, date)), forkOf = null, parentStars = 0,
        )
    }

    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

}

fun fetchText(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    return try {
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "OpenShelf (+https://github.com/wingedpanther/openshelf)")
        if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
        conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}

data class ReleaseInfo(val tag: String, val notes: String, val apks: List<Apk>)

object GitHub {
    private val releases = HashMap<String, ReleaseInfo>()
    private val shots = HashMap<String, List<String>>()
    private val skip = Regex("debug|unsigned|(^|[-_.])root[-_.]|geckoIncluded|test", RegexOption.IGNORE_CASE)
    private val tvHint = Regex("(^|[-_.])(tv|androidtv|atv|leanback|firetv)([-_.]|$)", RegexOption.IGNORE_CASE)
    private val phoneHint = Regex("(^|[-_.])(mobile|phone|wear|auto)([-_.]|$)", RegexOption.IGNORE_CASE)
    private val notShot = Regex(
        "shields\\.io|badge|/workflows/|codecov|logo|icon|favicon|banner|sponsor|buymeacoffee|ko-fi|paypal|liberapay|opencollective|" +
            "google-play|play\\.google|f-droid|fdroid|get-it-on|izzy|obtainium|star-history|contrib\\.rocks|readme-stats|visitor|emoji|\\.svg",
        RegexOption.IGNORE_CASE,
    )

    suspend fun release(repo: String, tag: String): ReleaseInfo? {
        val key = "$repo@$tag"
        releases[key]?.let { return it }
        val info = withContext(Dispatchers.IO) {
            runCatching {
                val o = JSONObject(fetchText("https://api.github.com/repos/$repo/releases/tags/${java.net.URLEncoder.encode(tag, "UTF-8")}"))
                val assets = o.getJSONArray("assets")
                val all = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .filter { it.getString("name").endsWith(".apk", true) && !skip.containsMatchIn(it.getString("name")) }
                    .map { Apk(it.getString("name"), abiOf(it.getString("name")), it.getString("browser_download_url"), it.getLong("size")) }
                val tv = all.filter { tvHint.containsMatchIn(it.name) }
                val apks = tv.ifEmpty { all.filterNot { phoneHint.containsMatchIn(it.name) }.ifEmpty { all } }
                ReleaseInfo(tag, plain(o.optString("body")), apks)
            }.getOrNull()
        } ?: return null
        releases[key] = info
        return info
    }

    suspend fun screenshots(repo: String): List<String> {
        shots[repo]?.let { return it }
        val found = withContext(Dispatchers.IO) {
            listOf("README.md", "readme.md", "Readme.md").firstNotNullOfOrNull { name ->
                runCatching { fetchText("https://raw.githubusercontent.com/$repo/HEAD/$name") }.getOrNull()
            }?.let { imagesIn(repo, it) }
        } ?: return emptyList()
        shots[repo] = found
        return found
    }

    private fun imagesIn(repo: String, md: String): List<String> {
        val markdown = Regex("!\\[[^\\]]*]\\(\\s*<?([^)\\s>]+)").findAll(md).map { it.range.first to it.groupValues[1] }
        val html = Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(md).map { it.range.first to it.groupValues[1] }
        return (markdown + html).sortedBy { it.first }.map { resolve(repo, it.second) }
            .filter { url ->
                !notShot.containsMatchIn(url) && (Regex("\\.(png|jpe?g|webp|gif)($|\\?)", RegexOption.IGNORE_CASE).containsMatchIn(url) ||
                    "user-attachments/assets" in url || "user-images.githubusercontent.com" in url)
            }
            .distinct().take(8).toList()
    }

    private fun resolve(repo: String, src: String): String {
        val blob = Regex("^https?://github\\.com/([^/]+/[^/]+)/(?:blob|raw)/(.+?)(\\?.*)?$").find(src)
        return when {
            blob != null -> "https://raw.githubusercontent.com/${blob.groupValues[1]}/${blob.groupValues[2]}"
            src.startsWith("http") -> src
            src.startsWith("//") -> "https:$src"
            else -> "https://raw.githubusercontent.com/$repo/HEAD/${src.removePrefix("./").removePrefix("/")}"
        }
    }

    private fun abiOf(name: String): String {
        val n = name.lowercase()
        listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86").firstOrNull { it in n }?.let { return it }
        return when {
            "arm64" in n || "aarch64" in n -> "arm64-v8a"
            "armv7" in n || "arm32" in n -> "armeabi-v7a"
            "universal" in n -> "universal"
            else -> "any"
        }
    }

    private fun plain(md: String): String {
        val lines = md.replace("\r", "").lineSequence().map { it.trim() }
            .filterNot { it.startsWith("<!--") || it.startsWith("![") || it.matches(Regex("^([-*_])\\1{2,}$")) || "Full Changelog" in it }
            .map { line ->
                line.replace(Regex("^#{1,6}\\s*"), "")
                    .replace(Regex("^[*+-]\\s+"), "• ")
                    .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")
                    .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
                    .replace(Regex("<[^>]+>"), "")
                    .replace(Regex("(\\*\\*|__|`)"), "")
                    .replace(Regex("https?://\\S+"), "")
                    .trim()
            }
        val out = mutableListOf<String>()
        for (l in lines) if (l.isNotEmpty() || (out.isNotEmpty() && out.last().isNotEmpty())) out.add(l)
        return out.joinToString("\n").trim()
    }
}

fun pickApk(app: App): Apk {
    for (abi in Build.SUPPORTED_ABIS) app.apks.firstOrNull { it.abi == abi }?.let { return it }
    return app.apks.firstOrNull { it.abi == "universal" } ?: app.apks.firstOrNull { it.abi == "any" } ?: app.apks.first()
}

object L {
    private lateinit var app: Context
    private var current by mutableStateOf<android.content.res.Resources?>(null)
    val res: android.content.res.Resources get() = current!!

    fun init(ctx: Context) {
        app = ctx.applicationContext
        use(Prefs.languages[Prefs.language])
    }

    fun use(tag: String) {
        val locale = if (tag.isEmpty()) android.content.res.Resources.getSystem().configuration.locales[0] else Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = android.content.res.Configuration(app.resources.configuration).apply { setLocale(locale) }
        current = app.createConfigurationContext(config).resources
    }

    fun name(tag: String): String =
        if (tag.isEmpty()) str(R.string.lang_match)
        else Locale.forLanguageTag(tag).let { it.getDisplayName(it).replaceFirstChar { c -> c.titlecase(it) } }
}

fun str(id: Int, vararg args: Any): String = L.res.getString(id, *args)
fun plural(id: Int, n: Int, vararg args: Any): String = L.res.getQuantityString(id, n, *args)

private val roomNames = mapOf(
    "Launchers" to R.string.room_launchers, "Screensavers" to R.string.room_screensavers, "VPN & network" to R.string.room_vpn,
    "Streaming" to R.string.room_streaming, "App stores" to R.string.room_stores, "Games" to R.string.room_games,
    "Music" to R.string.room_music, "Video" to R.string.room_video, "Smart home" to R.string.room_home,
    "Photos" to R.string.room_photos, "Reading" to R.string.room_reading, "Learning" to R.string.room_learning,
    "Chat & social" to R.string.room_chat, "AI" to R.string.room_ai, "Browsers" to R.string.room_browsers,
    "Files & downloads" to R.string.room_files, "Tools" to R.string.room_tools, "Other" to R.string.room_other,
)

fun roomName(room: String) = roomNames[room]?.let { str(it) } ?: room

fun abiLabel(abi: String): String = when (abi) {
    "arm64-v8a" -> str(R.string.abi_arm64)
    "armeabi-v7a" -> str(R.string.abi_arm32)
    "x86_64" -> str(R.string.abi_x86_64)
    "x86" -> "x86"
    else -> abi
}

private val androidNames = mapOf(
    21 to "5", 22 to "5.1", 23 to "6", 24 to "7", 25 to "7.1", 26 to "8", 27 to "8.1", 28 to "9",
    29 to "10", 30 to "11", 31 to "12", 32 to "12L", 33 to "13", 34 to "14", 35 to "15", 36 to "16",
)

fun androidName(sdk: Int) = "Android ${androidNames[sdk] ?: "API $sdk"}"

object Fmt {
    private fun fmt(pattern: Int, d: String) =
        LocalDate.parse(d.take(10)).format(DateTimeFormatter.ofPattern(str(pattern), Locale.getDefault()))
    fun date(d: String) = fmt(R.string.fmt_date, d)
    fun shortDate(d: String) = fmt(R.string.fmt_short_date, d)
    fun monthYear(d: String) = fmt(R.string.fmt_month_year, d)
    fun mb(bytes: Long): String = String.format(Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0)
    fun gb(bytes: Long): String = String.format(Locale.getDefault(), "%.1f GB", bytes / 1_000_000_000.0)
    fun count(n: Int): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(n)
    fun stars(n: Int): String = if (n >= 1000) String.format(Locale.getDefault(), "%.1fk", n / 1000.0).replace(Regex("[.,]0k$"), "k") else "$n"
}

object Images {
    private val memory = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val locks = HashMap<String, Mutex>()
    private lateinit var dir: File

    fun init(ctx: Context) { dir = File(ctx.cacheDir, "img").apply { mkdirs() } }

    fun cached(url: String, maxWidth: Int): ImageBitmap? = memory.get("$maxWidth|$url")

    suspend fun load(url: String, maxWidth: Int): ImageBitmap? {
        val key = "$maxWidth|$url"
        memory.get(key)?.let { return it }
        val lock = synchronized(locks) { locks.getOrPut(url) { Mutex() } }
        return lock.withLock {
            memory.get(key) ?: withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(dir, hash(url))
                    if (!file.exists()) download(url, file)
                    decode(file, maxWidth)?.also { memory.put(key, it) }
                }.getOrNull()
            }
        }
    }

    private fun download(url: String, file: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "OpenShelf (+https://github.com/wingedpanther/openshelf)")
        }
        try {
            if (conn.responseCode !in 200..299) return
            val part = File(file.path + ".part")
            conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
            part.renameTo(file)
        } finally {
            conn.disconnect()
        }
    }

    private fun decode(file: File, maxWidth: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.path, opts)?.asImageBitmap()
    }

    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

data class Installed(val versionCode: Long, val versionName: String?, val certs: Set<String>)

object Device {
    var tick by mutableIntStateOf(0)

    private var snapshotTick = -1
    private var snapshot: Map<String, Installed> = emptyMap()

    fun all(ctx: Context): Map<String, Installed> {
        if (snapshotTick != tick) {
            snapshot = ctx.packageManager.getInstalledPackages(PackageManager.GET_SIGNING_CERTIFICATES)
                .associate { it.packageName to Installed(it.longVersionCode, it.versionName, certs(it)) }
            snapshotTick = tick
        }
        return snapshot
    }

    fun installed(ctx: Context, pkg: String): Installed? = all(ctx)[pkg]

    fun installedAs(ctx: Context, a: App): Installed? = installed(ctx, a.pkg)?.takeIf { i ->
        a.certSha256 == null || i.certs.isEmpty() || a.certSha256 in i.certs || Catalog.sibling(a, i) == null
    }

    fun archiveCerts(ctx: Context, file: File): Set<String> {
        val pm = ctx.packageManager
        val pi = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
        val viaSigning = pi?.let { certs(it) }.orEmpty()
        if (viaSigning.isNotEmpty()) return viaSigning
        @Suppress("DEPRECATION")
        val old = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)
        @Suppress("DEPRECATION")
        return old?.signatures?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
    }

    private fun certs(pi: PackageInfo): Set<String> {
        val si = pi.signingInfo ?: return emptySet()
        val all = (si.apkContentsSigners.orEmpty().toList() + si.signingCertificateHistory.orEmpty().toList())
        return all.map { sha256(it.toByteArray()) }.toSet()
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    fun freeBytes(ctx: Context): Long = StatFs(ctx.filesDir.path).availableBytes
    fun totalBytes(ctx: Context): Long = StatFs(ctx.filesDir.path).totalBytes

    fun cacheDir(ctx: Context) = File(ctx.cacheDir, "apks")
    fun cacheBytes(ctx: Context): Long = cacheDir(ctx).listFiles()?.sumOf { it.length() } ?: 0L
    fun clearCache(ctx: Context) { cacheDir(ctx).listFiles()?.forEach { it.delete() } }
}

object Prefs {
    val accents = listOf(
        Triple(R.string.accent_sage, 0xFF3F6F55, 0xFF7DB592),
        Triple(R.string.accent_red, 0xFFC23B2E, 0xFFE8735F),
        Triple(R.string.accent_ochre, 0xFF9A6412, 0xFFE0A84A),
        Triple(R.string.accent_plum, 0xFF5B4B8A, 0xFFA597E0),
        Triple(R.string.accent_none, 0xFF8E8E93, 0xFFAEAEB2),
    )
    var accent by mutableIntStateOf(0)
        private set

    var hideStreams by mutableStateOf(true)
        private set

    var autoCheckSelf by mutableStateOf(true)
        private set

    var appSort by mutableIntStateOf(0)
        private set

    fun toggleStreams() {
        hideStreams = !hideStreams
        file.edit().putBoolean("hideStreams", hideStreams).apply()
    }

    fun toggleAutoCheck() {
        autoCheckSelf = !autoCheckSelf
        file.edit().putBoolean("autoCheckSelf", autoCheckSelf).apply()
    }

    fun nextAppSort() {
        appSort = (appSort + 1) % 3
        file.edit().putInt("appSort", appSort).apply()
    }

    val themes = listOf(R.string.theme_match, R.string.theme_light, R.string.theme_dark)
    var theme by mutableIntStateOf(0)
        private set
    var systemDark by mutableStateOf(false)
    val dark: Boolean get() = when (theme) { 1 -> false; 2 -> true; else -> systemDark }
    private lateinit var file: android.content.SharedPreferences

    fun init(ctx: Context) {
        file = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        accent = file.getInt("accent", 0).coerceIn(accents.indices)
        theme = file.getInt("theme", 0).coerceIn(themes.indices)
        hideStreams = file.getBoolean("hideStreams", true)
        autoCheckSelf = file.getBoolean("autoCheckSelf", true)
        appSort = file.getInt("appSort", 0).coerceIn(0..2)
        language = file.getInt("language", 0).coerceIn(languages.indices)
    }

    fun nextTheme() {
        theme = (theme + 1) % themes.size
        file.edit().putInt("theme", theme).apply()
    }

    val languages = listOf("", "en", "de", "zh-CN", "zh-TW", "ja", "es")
    var language by mutableIntStateOf(0)
        private set

    fun nextLanguage() {
        language = (language + 1) % languages.size
        file.edit().putInt("language", language).apply()
        L.use(languages[language])
    }

    fun nextAccent() {
        accent = (accent + 1) % accents.size
        file.edit().putInt("accent", accent).apply()
    }
}
