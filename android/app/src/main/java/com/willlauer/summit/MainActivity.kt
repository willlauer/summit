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
        // Started as the home screen with a fresh process (the player powering on): the landing page first.
        if (savedInstanceState == null && intent?.hasCategory(Intent.CATEGORY_HOME) == true && app.freshStart) com.willlauer.summit.ui.Landing.show = true
        app.freshStart = false
        setContent { SummitTheme { SummitRoot(remember { AppState(app.repo, app.player, app.scope) }) } }
    }

    // Debug builds only, for screen recordings: adb shell am start -n com.willlauer.summit/.MainActivity --ef showcase_turn 0.5
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (BuildConfig.DEBUG && intent.hasExtra("showcase_turn")) GalaxyView.showcaseTurn = intent.getFloatExtra("showcase_turn", 0f)
    }

    // The landing page also comes up when the screen wakes, if Summit was on screen when it went off: it is set as
    // the screen turns off, so waking shows it straight away (no flash of the app first).
    private val screenOff = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) { com.willlauer.summit.ui.Landing.show = true }
    }
    override fun onStart() {
        super.onStart()
        registerReceiver(screenOff, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF))
    }
    override fun onStop() {
        runCatching { unregisterReceiver(screenOff) }
        if (!(getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive) com.willlauer.summit.ui.Landing.show = true  // stopped by the screen going off
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        val app = application as SummitApp
        app.player.connect()
        // Pick up playlist edits made on the desktop; retry if the Mac was unreachable.
        // Also reload once "All files access" was just granted for the card (data/Card.kt).
        val cardGranted = !com.willlauer.summit.data.Card.state.value.access && com.willlauer.summit.data.Card.hasAccess()
        lifecycleScope.launch {
            val s = app.repo.status.value
            if ((s is Status.Ready || s is Status.Local) && !cardGranted) app.repo.syncPlaylists() else app.repo.load()
        }
    }
}
