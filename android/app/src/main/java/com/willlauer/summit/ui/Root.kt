package com.willlauer.summit.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.willlauer.summit.BuildConfig
import com.willlauer.summit.data.Status
import kotlinx.coroutines.launch

@Composable
fun SummitRoot(app: AppState) {
    val nav = rememberNavController()
    app.nav = nav
    val mapUi = remember { MapUi() }
    val searchUi = remember { SearchUi() }
    val libUi = remember { LibraryUi() }
    val lib by app.repo.lib.collectAsStateWithLifecycle()
    val status by app.repo.status.collectAsStateWithLifecycle()
    val ps by app.player.state.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { app.repo.messages.collect { snack.currentSnackbarData?.dismiss(); launch { snack.showSnackbar(it) } } }

    CompositionLocalProvider(LocalApp provides app, LocalMapUi provides mapUi) {
        Box(Modifier.fillMaxSize().background(C.Bg).pointerInput(Unit) {
            awaitPointerEventScope { while (true) { awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial); Landing.lastTouch = System.currentTimeMillis() } }
        }) {
            Backdrop(snow = lib == null)
            if (lib == null) { ConnectScreen(status); LandingScreen(); SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()); return@Box }
            val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val bottomPad = navBar + 62.dp + (if (ps.current != null) 66.dp else 0.dp) + (if (Keys.target != null && !Keys.inDialog) Keys.height else 0.dp)
            // Back at the top level (nothing left to go back to) does nothing: Summit stays open, it's the home screen.
            BackHandler(true) {}
            // After the glitch cut from the landing page: the app jitters into place (steps of shrinking sideways jolts).
            val jolt = remember { androidx.compose.animation.core.Animatable(1f) }
            LaunchedEffect(Landing.jolt) { if (Landing.jolt > 0) { jolt.snapTo(0f); jolt.animateTo(1f, androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.LinearEasing)) } }
            NavHost(nav, startDestination = Tab.Map.route, modifier = Modifier.graphicsLayer {
                    val j = jolt.value
                    if (j < 1f) {
                        val step = (j * 6).toInt()
                        val n = kotlin.math.sin(step * 78.233f) * 43758.547f
                        translationX = ((n - kotlin.math.floor(n)) - .5f) * 2f * (1f - j) * 6.dp.toPx()
                        alpha = if (step == 1) .85f else 1f
                    }
                },
                enterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(240)) + androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(280)) { it / 14 } },
                exitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180)) },
                popEnterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(240)) + androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(280)) { -it / 14 } },
                popExitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180)) + androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(240)) { it / 14 } }) {
                composable(Tab.Map.route) { MapScreen(mapUi, bottomPad) }
                composable(Tab.Search.route) { SearchScreen(searchUi, bottomPad) }
                composable(Tab.Library.route) { LibraryScreen(libUi, bottomPad) }
                composable(Tab.Playlists.route) { PlaylistsScreen(bottomPad) }
                composable("album/{name}") { AlbumScreen(android.net.Uri.decode(it.arguments?.getString("name") ?: ""), bottomPad) }
                composable("artist/{name}") { ArtistScreen(android.net.Uri.decode(it.arguments?.getString("name") ?: ""), bottomPad) }
                composable("playlist/{id}") { PlaylistScreen(it.arguments?.getString("id")?.toIntOrNull() ?: -1, bottomPad) }
                composable("similar/{chain}") { e -> SimilarScreen(e.arguments?.getString("chain").orEmpty().split(',').mapNotNull { it.toIntOrNull() }.ifEmpty { listOf(0) }, bottomPad) }
                composable("settings") { SettingsScreen(bottomPad) }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, C.Bg.copy(alpha = .92f), C.Bg), startY = 0f, endY = 80f))) {
                MiniPlayer()
                if (ps.current != null) Spacer(Modifier.height(6.dp))
                BottomTabs(app)
            }
            AnimatedVisibility(app.nowPlaying, enter = slideInVertically { it }, exit = slideOutVertically { it }) { NowPlaying() }
            // Summit's keyboard for the focused field (dialogs show their own: KeyDialog)
            AnimatedVisibility(Keys.target != null && !Keys.inDialog, Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(androidx.compose.animation.core.tween(220)) { it }, exit = slideOutVertically(androidx.compose.animation.core.tween(180)) { it }) { SummitKeyboard() }
            BackHandler(app.nowPlaying) { app.nowPlaying = false }
            SheetHost()
            DialogHost()
            LandingScreen()
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPad)) { d ->
                Snackbar(d, containerColor = C.Raised2, contentColor = C.Text, shape = Px)
            }
        }
    }
}

@Composable
private fun BottomTabs(app: AppState) {
    Row(Modifier.fillMaxWidth().snowCap(5, depth = 2).navigationBarsPadding().height(62.dp), verticalAlignment = Alignment.CenterVertically) {
        val items = listOf(Tab.Map to PI.Compass, Tab.Search to PI.Search, Tab.Library to PI.Library, Tab.Playlists to PI.Queue)
        items.forEach { (t, icon) ->
            val on = app.tab == t
            Column(Modifier.weight(1f).fillMaxHeight().clickable(remember { MutableInteractionSource() }, null) { app.openTab(t) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (Neon) {  // the desktop's lit nav item: blue sign underglow and edge, ice-white glowing label
                    Box(Modifier.clip(Px).background(if (on) Brush.verticalGradient(listOf(Color.Transparent, C.Sign.copy(alpha = .30f))) else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)))
                        .drawBehind { if (on) drawRect(C.Sign, androidx.compose.ui.geometry.Offset(0f, size.height - 2.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx())) }
                        .padding(horizontal = 18.dp, vertical = 4.dp)) {
                        Icon(icon, t.label, tint = if (on) C.Tube else C.Muted)
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(t.label.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.6.sp,
                        shadow = if (on) androidx.compose.ui.graphics.Shadow(C.Tube, androidx.compose.ui.geometry.Offset.Zero, 14f) else null), color = if (on) C.Tube else C.Muted)
                } else {
                Box(Modifier.clip(Pill).background(if (on) C.Cyan.copy(alpha = .16f) else Color.Transparent).padding(horizontal = 18.dp, vertical = 4.dp)) {
                    Icon(icon, t.label, tint = if (on) C.Glacier else C.Muted)
                }
                Spacer(Modifier.height(2.dp))
                Text(t.label, style = MaterialTheme.typography.labelSmall, color = if (on) C.Text else C.Muted)
                }
            }
        }
    }
}

/** First run, or the Mac can't be reached and nothing is cached: where is Summit? */
@Composable
fun ConnectScreen(status: Status) {
    val app = LocalApp.current
    var url by remember { mutableStateOf(app.repo.serverUrl) }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("SUMMIT", style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(8f, androidx.compose.ui.unit.TextUnitType.Sp)), color = C.Glacier)
        Spacer(Modifier.height(10.dp))
        when (status) {
            Status.Loading, Status.Ready, Status.Local -> { CircularProgressIndicator(strokeWidth = 2.dp); Spacer(Modifier.height(12.dp)); Text("Loading your library…", color = C.Muted) }
            is Status.Failed, is Status.Offline -> {
                Text("Can't reach Summit on your Mac", style = MaterialTheme.typography.titleMedium)
                Text((status as? Status.Failed)?.message ?: "", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                CardPanel()  // a music player: the synced card is all it needs
                Spacer(Modifier.height(20.dp))
                ServerField(url) { url = it }
                Spacer(Modifier.height(12.dp))
                PillButton("Connect", PI.Link, { app.repo.serverUrl = url; app.repo.refresh() }, primary = true)
                Spacer(Modifier.height(18.dp))
                Text("Use the address from `tailscale serve` on the Mac (https://<mac>.<tailnet>.ts.net), with the Summit server running (summit service install).",
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ServerField(url: String, onChange: (String) -> Unit) {
    PixelField(url, onChange, Modifier.fillMaxWidth(), label = "Server address", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
}

@Composable
fun SettingsScreen(bottomPad: androidx.compose.ui.unit.Dp) {
    val app = LocalApp.current
    val status by app.repo.status.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf(app.repo.serverUrl) }
    var serverVersion by remember { mutableStateOf<String?>(null) }
    val local = status == Status.Local
    LaunchedEffect(status) { serverVersion = if (local) null else runCatching { app.repo.api.version() }.getOrElse { "not reachable" } }
    Column(Modifier.fillMaxSize().padding(bottom = bottomPad)) {
        Header("Settings", back = true)
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp)) {  // small screens (the JM21)
            WifiPanel()
            Spacer(Modifier.height(24.dp))
            UpdatePanel()
            Spacer(Modifier.height(24.dp))
            AppsPanel()
            Spacer(Modifier.height(24.dp))
            if (local) {  // a synced card: no server; Wi-Fi sync with the Mac above (data/Card.kt, data/Lan.kt)
                CardPanel()
            } else {
            Text("Summit server", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            ServerField(url) { url = it }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillButton("Save & reload", PI.Refresh, { app.repo.serverUrl = url; app.repo.refresh() }, primary = true)
                Spacer(Modifier.width(12.dp))
                Text(when (val s = status) { Status.Ready -> "Connected"; Status.Local -> "Card"; Status.Loading -> "Connecting…"; is Status.Offline -> "Offline: ${s.message}"; is Status.Failed -> s.message },
                    style = MaterialTheme.typography.bodySmall, color = if (status == Status.Ready) C.Green else C.Muted)
            }
            Spacer(Modifier.height(24.dp))
            CardPanel()
            Spacer(Modifier.height(24.dp))
            StreamingPicker()
            Spacer(Modifier.height(24.dp))
            Text("Reach your Mac from anywhere with Tailscale: run `tailscale serve --bg 8766` on the Mac and use the https://….ts.net address it prints. "
                + "Over USB during development, `adb reverse tcp:8766 tcp:8766` makes http://127.0.0.1:8766 work.", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
            Text("Version", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("App ${BuildConfig.VERSION_NAME}" + if (local) "" else "\nMac ${serverVersion ?: "…"}", style = MaterialTheme.typography.bodySmall, color = C.Muted)
        }
    }
}
