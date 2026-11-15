package com.siyu.musique.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.siyu.musique.data.AppCache
import com.siyu.musique.data.InMemoryAppCache
import com.siyu.musique.data.InMemoryPlaybackModeStore
import com.siyu.musique.data.InMemoryPlaybackStateStore
import com.siyu.musique.data.InMemoryPlaylistStore
import com.siyu.musique.data.InMemoryQualityPreferenceStore
import com.siyu.musique.data.PlaybackModeStore
import com.siyu.musique.data.PlaybackStateStore
import com.siyu.musique.data.PlaylistStore
import com.siyu.musique.data.QualityPreferenceStore
import com.siyu.musique.data.SearchRepository
import com.siyu.musique.player.NoOpPlayerEngine
import com.siyu.musique.player.PlayerEngine
import com.siyu.musique.ui.theme.MusiqueTheme

/**
 * App navigation: a single Activity hosting a NavHost. "search" is
 * the start destination; "player" is the player page; "log" is the
 * debug log screen; "settings" is the settings screen; "playlists"
 * lists the playlists; "playlist/{id}" is one playlist's detail
 * screen. The ViewModel is shared between
 * the screens and is built with the repository, player engine, and
 * stores the Activity hands in. Playlist ids are UUIDs or "default",
 * so they are safe to carry in a route.
 */
@Composable
fun MusiqueApp(
    repository: SearchRepository,
    playerEngine: PlayerEngine = NoOpPlayerEngine,
    appCache: AppCache = InMemoryAppCache(),
    qualityPreferenceStore: QualityPreferenceStore = InMemoryQualityPreferenceStore(),
    playlistStore: PlaylistStore = InMemoryPlaylistStore(),
    playbackModeStore: PlaybackModeStore = InMemoryPlaybackModeStore(),
    playbackStateStore: PlaybackStateStore = InMemoryPlaybackStateStore(),
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val viewModel: SearchViewModel = viewModel(
        factory = SearchViewModelFactory(
            repository,
            playerEngine,
            qualityPreferenceStore,
            playlistStore,
            playbackModeStore,
            appCache,
            playbackStateStore,
        ),
    )

    MusiqueTheme {
        // The Surface paints the white background: without it the
        // screens are transparent and the window background shows
        // through, which is why the app looked black.
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = modifier.fillMaxSize(),
        ) {
            NavHost(
                navController = navController,
                startDestination = "search",
            ) {
            composable("search") {
                SearchScreen(
                    viewModel = viewModel,
                    onOpenPlayer = { navController.navigate("player") },
                    onOpenPlaylists = { navController.navigate("playlists") },
                    onOpenLog = { navController.navigate("log") },
                    onOpenSettings = { navController.navigate("settings") },
                )
            }
            composable("player") {
                PlayerScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable("log") {
                LogScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable("settings") {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable("playlists") {
                PlaylistsScreen(
                    viewModel = viewModel,
                    onOpenDetail = { playlistId ->
                        navController.navigate("playlist/$playlistId")
                    },
                    onOpenPlayer = { navController.navigate("player") },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "playlist/{playlistId}",
                arguments = listOf(
                    navArgument("playlistId") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val playlistId = backStackEntry.arguments?.getString("playlistId")
                    ?: return@composable
                PlaylistDetailScreen(
                    viewModel = viewModel,
                    playlistId = playlistId,
                    onOpenPlayer = { navController.navigate("player") },
                    onBack = { navController.popBackStack() },
                )
            }
            }
        }
    }
}
