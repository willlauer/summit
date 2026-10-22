package com.willlauer.summit.ui

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
        Box(Modifier.fillMaxSize().background(C.Bg)) {
            Backdrop(snow = lib == null)
            if (lib == null) { ConnectScreen(status); SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()); return@Box }
            val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val bottomPad = navBar + 62.dp + (if (ps.current != null) 66.dp else 0.dp)
            NavHost(nav, startDestination = Tab.Map.route) {
                composable(Tab.Map.route) { MapScreen(mapUi, bottomPad) }
                composable(Tab.Search.route) { SearchScreen(searchUi, bottomPad) }
                composable(Tab.Library.route) { LibraryScreen(libUi, bottomPad) }
                composable(Tab.Playlists.route) { PlaylistsScreen(bottomPad) }
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
            BackHandler(app.nowPlaying) { app.nowPlaying = false }
            SheetHost()
            DialogHost()
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
                Box(Modifier.clip(Pill).background(if (on) C.Cyan.copy(alpha = .16f) else Color.Transparent).padding(horizontal = 18.dp, vertical = 4.dp)) {
                    Icon(icon, t.label, tint = if (on) C.Glacier else C.Muted)
                }
                Spacer(Modifier.height(2.dp))
                Text(t.label, style = MaterialTheme.typography.labelSmall, color = if (on) C.Text else C.Muted)
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
            Status.Loading, Status.Ready -> { CircularProgressIndicator(strokeWidth = 2.dp); Spacer(Modifier.height(12.dp)); Text("Loading your library…", color = C.Muted) }
            is Status.Failed, is Status.Offline -> {
                Text("Can't reach Summit on your Mac", style = MaterialTheme.typography.titleMedium)
                Text((status as? Status.Failed)?.message ?: "", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
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
    LaunchedEffect(status) { serverVersion = runCatching { app.repo.api.version() }.getOrElse { "not reachable" } }
    Column(Modifier.fillMaxSize().padding(bottom = bottomPad)) {
        Header("Settings", back = true)
        Column(Modifier.padding(16.dp)) {
            Text("Summit server", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            ServerField(url) { url = it }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillButton("Save & reload", PI.Refresh, { app.repo.serverUrl = url; app.repo.refresh() }, primary = true)
                Spacer(Modifier.width(12.dp))
                Text(when (val s = status) { Status.Ready -> "Connected"; Status.Loading -> "Connecting…"; is Status.Offline -> "Offline: ${s.message}"; is Status.Failed -> s.message },
                    style = MaterialTheme.typography.bodySmall, color = if (status == Status.Ready) C.Green else C.Muted)
            }
            Spacer(Modifier.height(24.dp))
            Text("Look", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LookPicker()
            Spacer(Modifier.height(24.dp))
            Text("Reach your Mac from anywhere with Tailscale: run `tailscale serve --bg 8766` on the Mac and use the https://….ts.net address it prints. "
                + "Over USB during development, `adb reverse tcp:8766 tcp:8766` makes http://127.0.0.1:8766 work.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))
            Text("Version", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("App ${BuildConfig.VERSION_NAME}\nMac ${serverVersion ?: "…"}", style = MaterialTheme.typography.bodySmall, color = C.Muted)
        }
    }
}
