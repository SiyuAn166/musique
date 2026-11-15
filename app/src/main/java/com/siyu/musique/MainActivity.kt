package com.siyu.musique

import android.os.Bundle
import androidx.activity.ComponentActivity
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
        enableEdgeToEdge()
        AndroidLogSink.install()
        MusiqueLog.i("App", "Musique started")
        val repository = BiliDependencies.createSearchRepository(applicationContext)
        val playerEngine = BiliDependencies.createPlayerEngine(applicationContext)
        setContent {
            MusiqueApp(repository = repository, playerEngine = playerEngine)
        }
    }
}
