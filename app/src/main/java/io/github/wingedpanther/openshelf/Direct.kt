package io.github.wingedpanther.openshelf

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object Direct {
    private val apps = mutableStateMapOf<String, App>()

    fun id(repo: String) = "direct-${repo.replace('/', '_')}"

    fun byId(id: String): App? = apps[id]

    fun found(id: String, pkg: String, versionCode: Long) {
        apps[id]?.let { apps[id] = it.copy(pkg = pkg, versionCode = versionCode) }
    }

    fun repoOf(query: String): String? {
        val q = query.trim()
        if (!q.contains('/') || q.contains(' ')) return null
        return ManualLinks.parseRepo(q)
    }

    suspend fun lookup(repo: String): App? {
        Catalog.byRepo(repo)?.let { return it }
        apps[id(repo)]?.let { return it }
        val rel = GitHub.latest(repo) ?: return null
        if (rel.apks.isEmpty()) return null
        val info = withContext(Dispatchers.IO) { runCatching { JSONObject(fetchText("https://api.github.com/repos/$repo")) }.getOrNull() }
        val name = repo.substringAfter('/').replace(Regex("[-_]+"), " ").replaceFirstChar { it.titlecase() }
        val desc = info?.optString("description")?.takeIf { it.isNotBlank() && it != "null" }.orEmpty()
        val app = App(
            id = id(repo), repo = info?.optString("full_name")?.ifBlank { null } ?: repo, name = name, pkg = "",
            versionCode = Long.MAX_VALUE, versionName = rel.tag.removePrefix("v"), minSdk = null,
            room = "Other", note = desc.ifEmpty { str(R.string.direct_note) }, desc = desc, maker = repo.substringBefore('/'),
            stars = info?.optInt("stargazers_count") ?: 0, licence = info?.optJSONObject("license")?.optString("spdx_id")?.takeIf { it != "NOASSERTION" },
            archived = info?.optBoolean("archived") ?: false, streams = false,
            version = rel.tag, date = rel.date.ifEmpty { java.time.LocalDate.now().toString() }, certSha256 = null,
            art = AppArt(null, null, null, emptyList()), apks = rel.apks, releases = listOf(Release(rel.tag, rel.date)),
            forkOf = null, parentStars = 0,
        )
        apps[app.id] = app
        return app
    }
}
