package com.siyu.musique

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.siyu.musique.data.BiliDependencies
import com.siyu.musique.ui.MusiqueApp

/**
 * Single Activity for the whole app, per the v0.1 design.
 * All screens are Compose destinations inside [MusiqueApp].
 * Step 1 builds the real Bilibili repository once here and hands it
 * to the app; the bootstrap starts in the background from the
 * repository and never blocks the first screen.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = BiliDependencies.createSearchRepository(applicationContext)
        setContent {
            MusiqueApp(repository = repository)
        }
    }
}
