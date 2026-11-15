package com.siyu.musique

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.siyu.musique.core.logging.MusiqueLog
import com.siyu.musique.data.AndroidLogSink
import com.siyu.musique.data.BiliDependencies
import com.siyu.musique.ui.MusiqueApp

/**
 * Single Activity for the whole app, per the v0.1 design.
 * All screens are Compose destinations inside [MusiqueApp].
 * Step 1 builds the real Bilibili repository once here and hands it
 * to the app; the bootstrap starts in the background from the
 * repository and never blocks the first screen. Step 3 also builds
 * the ExoPlayer engine once here and installs the logcat sink, so
 * every log line lands in logcat (tag "Musique") and on the in-app
 * log screen.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is forced light, so the system bars always use dark
        // icons, even on phones set to dark mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        AndroidLogSink.install()
        MusiqueLog.i("App", "Musique started")
        val repository = BiliDependencies.createSearchRepository(applicationContext)
        val audioCache = BiliDependencies.createAudioCache(applicationContext)
        val playerEngine = BiliDependencies.createPlayerEngine(applicationContext, audioCache)
        val appCache = BiliDependencies.createAppCache(applicationContext, audioCache)
        val qualityPreferenceStore =
            BiliDependencies.createQualityPreferenceStore(applicationContext)
        val playlistStore = BiliDependencies.createPlaylistStore(applicationContext)
        val playbackModeStore = BiliDependencies.createPlaybackModeStore(applicationContext)
        val playbackStateStore = BiliDependencies.createPlaybackStateStore(applicationContext)
        val downloadStore = BiliDependencies.createDownloadStore(applicationContext)
        val audioFileDownloader = BiliDependencies.createAudioFileDownloader()
        val downloadsDir = BiliDependencies.downloadsDir(applicationContext)
        val installedVersion = BiliDependencies.installedVersion(applicationContext)
        val updateChecker = BiliDependencies.createUpdateChecker(installedVersion.versionCode)
        val updateApkInstaller = BiliDependencies.createUpdateApkInstaller(applicationContext)
        val updatesDir = BiliDependencies.updatesDir(applicationContext)
        setContent {
            MusiqueApp(
                repository = repository,
                playerEngine = playerEngine,
                appCache = appCache,
                qualityPreferenceStore = qualityPreferenceStore,
                playlistStore = playlistStore,
                playbackModeStore = playbackModeStore,
                playbackStateStore = playbackStateStore,
                downloadStore = downloadStore,
                audioFileDownloader = audioFileDownloader,
                downloadsDir = downloadsDir,
                updateChecker = updateChecker,
                updateApkInstaller = updateApkInstaller,
                installedVersionName = installedVersion.versionName,
                updatesDir = updatesDir,
            )
        }
    }
}
