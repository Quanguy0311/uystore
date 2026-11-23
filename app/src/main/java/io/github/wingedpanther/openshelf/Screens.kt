@file:OptIn(ExperimentalFoundationApi::class)

package io.github.wingedpanther.openshelf

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.widthIn
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import androidx.compose.ui.window.DialogProperties

enum class Tab(val label: Int) { Discover(R.string.tab_discover), Categories(R.string.tab_categories), Search(R.string.tab_search), Mine(R.string.tab_mine), Settings(R.string.tab_settings) }

class FocusHandoff {
    val target = FocusRequester()
    var pending by mutableStateOf(true)
}

@Composable
fun OpenShelf() {
    val activity = androidx.activity.compose.LocalActivity.current
    var tab by remember { mutableStateOf(Tab.Discover) }
    var page by remember { mutableStateOf<String?>(null) }
    var wallId by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var room by remember { mutableStateOf<String?>(null) }
    var lastRoom by remember { mutableStateOf<String?>(null) }
    var lastInRoom by remember { mutableStateOf<String?>(null) }
    val roomGrid = remember(room) { LazyGridState() }
    var roomSort by remember(room) { mutableIntStateOf(0) }
    var tabsFocused by remember { mutableStateOf(false) }
    val tabRequesters = remember { Tab.entries.associateWith { FocusRequester() } }
    val handoff = remember { FocusHandoff() }
    val ctx = LocalContext.current
    LaunchedEffect(Device.tick) {
        Catalog.load(ctx)
        if (Prefs.autoCheckSelf) SelfUpdate.check(ctx)
        CatalogUpdate.check(ctx)
    }
    if (Catalog.apps.isEmpty()) {
        Box(Modifier.fillMaxSize().background(C.Wall), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(C.mark), null, Modifier.size(34.dp))
                Spacer(Modifier.width(10.dp))
                T("Open Shelf", 24.sp, weight = FontWeight.SemiBold, tight = true)
            }
        }
        return
    }

    val updates = remember(Device.tick, SelfUpdate.app) { updateCount(ctx) }
    LaunchedEffect(updates, Catalog.apps) {
        val ready = shelfApps(ctx).filter { (a, i) -> a.pkg != ctx.packageName && i.versionCode < a.versionCode && (a.certSha256 == null || i.certs.isEmpty() || a.certSha256 in i.certs) }
        val picks = Catalog.trending().take(8).ifEmpty { Catalog.added().take(8) }
        withContext(Dispatchers.IO) { HomeRow.sync(ctx, ready, picks) }
    }
    LaunchedEffect(Links.pending, Catalog.apps) {
        val link = Links.pending ?: return@LaunchedEffect
        when (link.host) {
            "app" -> link.lastPathSegment?.let { id -> if (Catalog.byId(id) != null) { room = null; page = id } }
            "mine" -> { page = null; room = null; tab = Tab.Mine }
        }
        handoff.pending = true
        Links.pending = null
    }
    val openApp: (String) -> Unit = { page = it; handoff.pending = true }
    val openRoom: (String) -> Unit = { room = it; lastRoom = it; lastInRoom = null; handoff.pending = true }
    val closePage = {
        if (page != null) page = null else room = null
        handoff.pending = true
    }

    BackHandler {
        when {
            page != null || room != null -> closePage()
            !tabsFocused -> runCatching { tabRequesters.getValue(tab).requestFocus() }
            else -> activity?.finish()
        }
    }

    Box(Modifier.fillMaxSize().background(C.Wall)) {
        Box(Modifier.fillMaxSize().padding(top = 70.dp)) {
            val app = page?.let { Catalog.byId(it) ?: ManualLinks.byId(it) ?: Direct.byId(it) }
            val r = room
            if (app != null) {
                AppPage(app, openApp, handoff)
            } else if (r != null) {
                RoomPage(r, roomGrid, roomSort, { roomSort = it }, lastInRoom, { lastInRoom = it }, openApp, handoff)
            } else when (tab) {
                Tab.Discover -> Discover(wallId, { wallId = it }, openApp, openRoom, handoff)
                Tab.Categories -> Categories(lastRoom, openRoom, handoff)
                Tab.Search -> SearchScreen(query, { query = it }, openApp, handoff)
                Tab.Mine -> MyApps(openApp, { tab = Tab.Discover; handoff.pending = true }, handoff)
                Tab.Settings -> SettingsScreen(openApp, handoff)
            }
        }
        TopBar(
            tab = tab,
            deep = page != null || room != null,
            backLabel = if (page != null) room?.let(::roomName) ?: str(tab.label) else str(tab.label),
            onBack = closePage,
            requesters = tabRequesters,
            onTab = { page = null; room = null; tab = it },
            onTabsFocus = { tabsFocused = it },
            updates = updates,
        )
    }

    val selfApp = SelfUpdate.app
    var selfDismissed by remember(selfApp?.version) { mutableStateOf(false) }
    if (selfApp != null && !selfDismissed && SelfUpdate.shouldPrompt(ctx)) {
        SelfUpdateDialog(
            app = selfApp,
            currentVersion = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty(),
            onUpdate = { selfDismissed = true; Installer.start(ctx, selfApp) },
            onLater = { selfDismissed = true; SelfUpdate.ignore(ctx, selfApp.version) },
        )
    }

    if (Lock.pending.isNotEmpty()) {
        PinDialog(str(R.string.pin_enter), onClose = { Lock.pending.clear() }) { Lock.release() }
    }

    LaunchedEffect(handoff.pending, page, room, tab) {
        if (handoff.pending) {
            delay(60)
            runCatching { handoff.target.requestFocus() }
            handoff.pending = false
        }
    }
}

@Composable
private fun SelfUpdateDialog(app: App, currentVersion: String, onUpdate: () -> Unit, onLater: () -> Unit) {
    val updateRequester = remember { FocusRequester() }
    Dialog(onDismissRequest = onLater) {
        Column(
            Modifier.width(440.dp).shadow(16.dp, RoundedCornerShape(18.dp))
                .background(C.Mat, RoundedCornerShape(18.dp)).padding(28.dp),
        ) {
            Image(painterResource(C.mark), null, Modifier.size(40.dp))
            T(str(R.string.self_update_title), 22.sp, Modifier.padding(top = 16.dp),
                weight = FontWeight.SemiBold, tight = true)
            T(str(R.string.self_update_body, currentVersion, app.versionName ?: app.version),
                14.5.sp, Modifier.padding(top = 8.dp), color = C.Ink2)
            Row(
                Modifier.padding(top = 22.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill(str(R.string.self_update_later), onLater)
                Spacer(Modifier.width(10.dp))
                Pill(str(R.string.action_update), onUpdate, solid = true,
                    icon = Icons.Download, requester = updateRequester)
            }
            LaunchedEffect(Unit) { runCatching { updateRequester.requestFocus() } }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun TopBar(
    tab: Tab,
    deep: Boolean,
    backLabel: String,
    onBack: () -> Unit,
    requesters: Map<Tab, FocusRequester>,
    onTab: (Tab) -> Unit,
    onTabsFocus: (Boolean) -> Unit,
    updates: Int,
) {
    Box(Modifier.fillMaxWidth().height(64.dp)) {
        if (deep) {
            Focusable(onBack, Modifier.padding(start = 44.dp, top = 18.dp)) { f ->
                Row(
                    Modifier
                        .lift(f, 1.06f, CircleShape, rest = 0.dp)
                        .background(if (f) C.accent else Color.Transparent, CircleShape)
                        .height(28.dp)
                        .padding(start = 8.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Ico(Icons.ChevLeft, 15.dp, if (f) C.OnAccent else C.Stone)
                    Spacer(Modifier.width(5.dp))
                    T(backLabel, 13.5.sp, color = if (f) C.OnAccent else C.Stone, weight = FontWeight.Medium)
                }
            }
        } else {
            Row(Modifier.padding(start = 48.dp, top = 21.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(C.mark), null, Modifier.size(21.dp))
                Spacer(Modifier.width(7.dp))
                T("Open Shelf", 15.5.sp, weight = FontWeight.SemiBold, tight = true)
            }
        }
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 17.dp)
                .background(C.Bar, CircleShape)
                .border(1.dp, C.Hair, CircleShape)
                .padding(3.dp)
                .onFocusChanged { onTabsFocus(it.hasFocus) }
                .focusProperties { enter = { requesters.getValue(tab) } }
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Tab.entries.forEach { t ->
                Focusable(
                    onClick = { onTab(t) },
                    requester = requesters.getValue(t),
                    onFocus = { if (!deep) onTab(t) },
                ) { f ->
                    val selected = t == tab
                    Row(
                        Modifier
                            .lift(f, 1.06f, CircleShape, rest = 0.dp)
                            .background(if (f) C.accent else if (selected) C.Selected else Color.Transparent, CircleShape)
                            .height(28.dp)
                            .padding(horizontal = 15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        T(str(t.label), 13.5.sp, color = if (f) C.OnAccent else if (selected) C.Ink else C.Stone, weight = FontWeight.Medium)
                        if (t == Tab.Mine && updates > 0) {
                            Spacer(Modifier.width(6.dp))
                            Box(Modifier.height(17.dp).widthIn(min = 17.dp).background(if (f) C.OnAccent else C.accent, CircleShape).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                                T(if (updates > 9) "9+" else "$updates", 10.5.sp, color = if (f) C.accent else C.OnAccent, weight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
        Clock(Modifier.align(Alignment.TopEnd).padding(end = 48.dp, top = 23.dp))
    }
}

@Composable
private fun Clock(modifier: Modifier) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(15_000) } }
    T(SimpleDateFormat("HH:mm", Locale.ROOT).format(now), 13.5.sp, modifier, color = C.Stone, weight = FontWeight.Medium)
}

@Composable
private fun Discover(wallId: String, onWall: (String) -> Unit, openApp: (String) -> Unit, openRoom: (String) -> Unit, handoff: FocusHandoff) {
    val ctx = LocalContext.current
    val tick = Device.tick
    val first = remember(Prefs.hideStreams) { Catalog.visible.firstOrNull { it.art.banner != null && it.art.wall != null && it.runsHere } ?: Catalog.apps.first() }
    val wall = Catalog.byId(wallId) ?: first
    var browsing by remember { mutableStateOf(false) }
    val heroH by animateDpAsState(if (browsing) 150.dp else 268.dp, tween(320), label = "hero")
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 48.dp).height(heroH)) {
            AnimatedContent(wall, transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(200)) }, label = "wall") { a ->
                Box(Modifier.size(heroH * 16f / 9f, heroH).shadow(8.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))) {
                    ArtImage(a.art.wall, a, 960)
                }
            }
            Spacer(Modifier.width(36.dp))
            Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                AnimatedContent(wall, transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(160)) }, label = "label") { a ->
                    Column {
                        T(a.name, 32.sp, weight = FontWeight.SemiBold, tight = true, maxLines = 1)
                        T(str(R.string.by_maker, a.maker), 13.5.sp, Modifier.padding(top = 4.dp), color = C.Stone)
                        AnimatedVisibility(!browsing, enter = expandVertically(tween(320)) + fadeIn(tween(320)), exit = shrinkVertically(tween(320)) + fadeOut(tween(160))) {
                            Column {
                                T(a.note, 15.5.sp, Modifier.padding(top = 11.dp).width(330.dp), color = C.Ink2, maxLines = 2)
                                Box(Modifier.padding(top = 15.dp).fillMaxWidth().height(1.dp).background(C.Hair))
                                Column(Modifier.padding(top = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Fact(str(R.string.fact_version), "${L.wrap(a.version)}, ${L.wrap(Fmt.date(a.date))}")
                                    Fact(str(R.string.fact_licence), a.licence ?: str(R.string.licence_unknown))
                                    Fact(str(R.string.fact_stars), Fmt.count(a.stars))
                                }
                            }
                        }
                    }
                }
                Row(Modifier.padding(top = 13.dp).offset(x = (-4).dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Pill(str(R.string.view_app), { openApp(wall.id) }, solid = true, requester = handoff.target)
                    InstallButton(wall, solid = false)
                }
            }
        }
        val tv = remember(Prefs.hideStreams) { Catalog.visible.filter { it.art.banner != null && it.runsHere }.take(40) }
        val recent = remember(Prefs.hideStreams) { Catalog.visible.sortedByDescending { it.date }.take(40) }
        val starred = remember(Prefs.hideStreams) { Catalog.visible.take(40) }
        val fresh = remember(Prefs.hideStreams) { Catalog.added().take(40) }
        val trending = remember(Prefs.hideStreams) { Catalog.trending().take(40) }
        val packs = Catalog.packs
        var pack by remember { mutableStateOf<Pack?>(null) }
        val rooms = remember(Prefs.hideStreams) { roomsOf() }
        fun meta(a: App) = status(ctx, a)
        @Suppress(
