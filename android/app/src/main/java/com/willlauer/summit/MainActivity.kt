package com.willlauer.summit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import com.willlauer.summit.data.Status
import com.willlauer.summit.ui.AppState
import com.willlauer.summit.ui.SummitRoot
import com.willlauer.summit.ui.SummitTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(0), navigationBarStyle = SystemBarStyle.dark(0))
        val app = application as SummitApp
        setContent { SummitTheme { SummitRoot(remember { AppState(app.repo, app.player, app.scope) }) } }
    }

    override fun onResume() {
        super.onResume()
        val app = application as SummitApp
        app.player.connect()
        // Pick up playlist edits made on the desktop; retry if the Mac was unreachable.
        lifecycleScope.launch { if (app.repo.status.value is Status.Ready) app.repo.syncPlaylists() else app.repo.load() }
    }
}
