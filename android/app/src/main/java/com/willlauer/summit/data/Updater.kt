package com.willlauer.summit.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.willlauer.summit.BuildConfig
import com.willlauer.summit.SummitApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request

@Serializable
data class ApkInfo(val versionCode: Int = 0, val versionName: String = "", val bytes: Long = 0, val profile: Boolean = false)

data class UpdateState(val available: ApkInfo? = null, val installing: Boolean = false, val progress: Float = 0f, val note: String = "")

/**
 * Updates over Wi-Fi: the paired Mac (data/Lan.kt) serves its newest Android build (GET /api/app/version, /api/app/apk);
 * a newer one shows in Settings › Updates and installs through Android's package installer. The first time, Android
 * asks to allow installs from Summit; after that it's one tap.
 */
object Updater {
    val state = MutableStateFlow(UpdateState())
    private val json = Json { ignoreUnknownKeys = true }

    /** Ask the Mac whether it has a newer build (quietly; after each Wi-Fi sync and from Settings). */
    suspend fun check(app: SummitApp): ApkInfo? = withContext(Dispatchers.IO) {
        if (!Lan.paired) return@withContext null
        val info = runCatching {
            app.http.newCall(Request.Builder().url("${Lan.url}/api/app/version").build()).execute().use { r ->
                if (r.isSuccessful) json.decodeFromString<ApkInfo>(r.body.string()) else null
            }
        }.getOrNull()
        val newer = info?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        state.value = state.value.copy(available = newer, note = if (info != null && newer == null) "Up to date" else state.value.note)
        newer
    }

    /** Download the Mac's build and hand it to the package installer (the app restarts as the new version). */
    suspend fun install(app: SummitApp) = withContext(Dispatchers.IO) {
        val info = state.value.available ?: return@withContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            state.value = state.value.copy(note = "Allow installs from Summit, then tap Install again")
            app.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return@withContext
        }
        state.value = state.value.copy(installing = true, progress = 0f, note = "Downloading ${info.versionName}…")
        try {
            val pi = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                app.http.newCall(Request.Builder().url("${Lan.url}/api/app/apk").build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                    val total = r.body.contentLength().takeIf { it > 0 } ?: info.bytes
                    s.openWrite("summit.apk", 0, total).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var done = 0L
                        r.body.byteStream().use { inp ->
                            while (true) {
                                val n = inp.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n
                                if (total > 0) state.value = state.value.copy(progress = done.toFloat() / total)
                            }
                        }
                        s.fsync(out)
                    }
                }
                // The compile profile next to the APK: Android compiles the app at install instead of interpreting it.
                if (info.profile) runCatching {
                    app.http.newCall(Request.Builder().url("${Lan.url}/api/app/profile").build()).execute().use { r ->
                        if (r.isSuccessful) { val bytes = r.body.bytes(); s.openWrite("summit.dm", 0, bytes.size.toLong()).use { out -> out.write(bytes); s.fsync(out) } }
                    }
                }
                state.value = state.value.copy(note = "Installing…")
                val cb = PendingIntent.getBroadcast(app, id, Intent(app, InstallReceiver::class.java).setPackage(app.packageName),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                s.commit(cb.intentSender)
            }
        } catch (e: Exception) {
            state.value = state.value.copy(installing = false, note = "Update failed: ${e.message}")
        }
    }
}

/** The package installer's answer: ask the user when Android needs a confirmation, else report the result. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.state.value = UpdateState(note = "Updated")
            else -> Updater.state.value = Updater.state.value.copy(installing = false,
                note = "Update failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown"}")
        }
    }
}
