package io.github.wingedpanther.openshelf

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat

object Links {
    var pending by mutableStateOf<Uri?>(null)

    fun app(id: String): Uri = Uri.parse("openshelf://app/$id")
    val mine: Uri = Uri.parse("openshelf://mine")
}

object HomeRow {
    private const val MAX = 12

    @android.annotation.SuppressLint("RestrictedApi")
    fun sync(ctx: Context, updates: List<Pair<App, Installed>>, picks: List<App>) {
        if (Build.VERSION.SDK_INT < 26 || !ctx.packageManager.hasSystemFeature("android.software.leanback")) return
        runCatching {
            val helper = PreviewChannelHelper(ctx)
            val prefs = ctx.getSharedPreferences("channel", Context.MODE_PRIVATE)
            var id = prefs.getLong("id", -1L)
            if (id == -1L || runCatching { helper.getPreviewChannel(id) }.getOrNull() == null) {
                val channel = PreviewChannel.Builder()
                    .setDisplayName("Open Shelf")
                    .setAppLinkIntentUri(Links.mine)
                    .setLogo(BitmapFactory.decodeResource(ctx.resources, R.mipmap.ic_launcher))
                    .build()
                id = helper.publishDefaultChannel(channel)
                prefs.edit().putLong("id", id).apply()
            }
            ctx.contentResolver.delete(TvContractCompat.buildPreviewProgramsUriForChannel(id), null, null)
            val shown = updates.map { (a, i) -> a to str(R.string.channel_update, i.versionName ?: "?", a.versionName ?: a.version) } +
                picks.filter { p -> updates.none { it.first.id == p.id } }.map { it to it.note }
            shown.take(MAX).forEachIndexed { n, (a, line) ->
                val art = a.art.tile ?: a.art.wall ?: a.art.icon
                val program = PreviewProgram.Builder()
                    .setChannelId(id)
                    .setType(TvContractCompat.PreviewPrograms.TYPE_TV_SERIES)
                    .setTitle(a.name)
                    .setDescription(line)
                    .setIntentUri(Links.app(a.id))
                    .setInternalProviderId(a.id)
                    .setWeight(MAX - n)
                    .apply {
                        if (art != null) {
                            setPosterArtUri(Uri.parse(art))
                            setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                        }
                    }
                    .build()
                helper.publishPreviewProgram(program)
            }
        }
    }
}
