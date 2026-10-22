package com.willlauer.summit

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import com.willlauer.summit.data.Status
import com.willlauer.summit.map.GalaxyView
import com.willlauer.summit.ui.AppState
import com.willlauer.summit.ui.LookState
import com.willlauer.summit.ui.Looks
import com.willlauer.summit.ui.SummitRoot
import com.willlauer.summit.ui.SummitTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(0), navigationBarStyle = SystemBarStyle.dark(0))
        val app = application as SummitApp
        LookState.set(Looks.byKey(app.repo.lookKey))
        setContent { SummitTheme { SummitRoot(remember { AppState(app.repo, app.player, app.scope) }) } }
    }

    // Debug builds only, for screen recordings: adb shell am start -n com.willlauer.summit/.MainActivity --ef showcase_turn 0.5
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (BuildConfig.DEBUG && intent.hasExtra("showcase_turn")) GalaxyView.showcaseTurn = intent.getFloatExtra("showcase_turn", 0f)
    }

    override fun onResume() {
        super.onResume()
        val app = application as SummitApp
        app.player.connect()
        // Pick up playlist edits made on the desktop; retry if the Mac was unreachable.
        lifecycleScope.launch { if (app.repo.status.value is Status.Ready) app.repo.syncPlaylists() else app.repo.load() }
    }
}
