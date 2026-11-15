package com.siyu.musique.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.siyu.musique.data.AppCache
import com.siyu.musique.data.AudioFileDownloader
import com.siyu.musique.data.DownloadStore
import com.siyu.musique.data.InMemoryAppCache
import com.siyu.musique.data.InMemoryDownloadStore
import com.siyu.musique.data.InMemoryPlaybackModeStore
import com.siyu.musique.data.InMemoryPlaybackStateStore
import com.siyu.musique.data.InMemoryPlaylistStore
import com.siyu.musique.data.InMemoryQualityPreferenceStore
import com.siyu.musique.data.NoOpAudioFileDownloader
import com.siyu.musique.data.PlaybackModeStore
import com.siyu.musique.data.PlaybackStateStore
import com.siyu.musique.data.PlaylistStore
import com.siyu.musique.data.QualityPreferenceStore
import com.siyu.musique.data.SearchRepository
import com.siyu.musique.data.defaultDownloadsDir
import com.siyu.musique.player.NoOpPlayerEngine
import com.siyu.musique.player.PlayerEngine
import com.siyu.musique.ui.theme.MusiqueIcons
import com.siyu.musique.ui.theme.MusiqueTheme
import java.io.File

/**
 * App navigation: a single Activity hosting a NavHost inside a
 * Column shell. The three top-level tabs are "home" (the
 * playlists), "search", and "settings"; "player" is the player
 * page, "log" is the debug log screen, "playlists" lists the
 * playlists (unreachable since the home tab shows them inline,
 * kept for now), and "playlist/{id}" is one playlist's detail
 * screen. The ViewModel is shared between the screens and is
 * built with the repository, player engine, and stores the
 * Activity hands in. Playlist ids are UUIDs or "default" (or
 * "downloads" for the synthetic playlist), so they are safe to
 * carry in a route.
 *
 * The shell owns one shared mini player bar and the bottom tab
 * bar, below the NavHost: the tab bar shows only on the three
 * tab destinations, and the mini bar shows on the tab
 * destinations, the playlist detail, and the playlists screen,
 * but never on the player page or the log screen. No Scaffold:
 * the screens pad themselves for the safe drawing insets.
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
    downloadStore: DownloadStore = InMemoryDownloadStore(),
    audioFileDownloader: AudioFileDownloader = NoOpAudioFileDownloader,
    downloadsDir: File = defaultDownloadsDir(),
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
            downloadStore,
            audioFileDownloader,
            downloadsDir,
        ),
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showTabs = currentRoute in TAB_ROUTES
    val showMiniBar = showTabs ||
        currentRoute == "playlists" ||
        currentRoute == "playlist/{playlistId}"

    val currentTrack by viewModel.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val progress by viewModel.playbackProgress.collectAsStateWithLifecycle()
    val resolvedQualityLabel by viewModel.resolvedQualityLabel.collectAsStateWithLifecycle()

    MusiqueTheme {
        // The Surface paints the white background: without it the
        // screens are transparent and the window background shows
        // through, which is why the app looked black.
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = modifier.fillMaxSize(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    NavHost(
                        navController = navController,
                        startDestination = "home",
                    ) {
                        composable("home") {
                            HomeScreen(
                                viewModel = viewModel,
                                onOpenPlaylistDetail = { playlistId ->
                                    navController.navigate("playlist/$playlistId")
                                },
                            )
                        }
                        composable("search") {
                            SearchScreen(viewModel = viewModel)
                        }
                        composable("settings") {
                            SettingsScreen(
                                viewModel = viewModel,
                                onOpenLog = { navController.navigate("log") },
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
                        composable("playlists") {
                            PlaylistsScreen(
                                viewModel = viewModel,
                                onOpenDetail = { playlistId ->
                                    navController.navigate("playlist/$playlistId")
                                },
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
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                }

                if (showMiniBar) {
                    MiniPlayerBar(
                        track = currentTrack,
                        isPlaying = isPlaying,
                        playerState = playerState,
                        progress = progress,
                        onTogglePlayPause = { viewModel.togglePlayPause() },
                        onRetry = { viewModel.retryPlayback() },
                        onOpenPlayer = { navController.navigate("player") },
                        onPrevious = { viewModel.playPrevious() },
                        onNext = { viewModel.playNext() },
                        qualityLabel = resolvedQualityLabel,
                    )
                }

                if (showTabs) {
                    NavigationBar {
                        TAB_ITEMS.forEach { item ->
                            NavigationBarItem(
                                selected = currentRoute == item.route,
                                onClick = {
                                    navController.navigate(item.route) {
                                        // One copy of each tab, with
                                        // its state saved and
                                        // restored: the search text
                                        // and results survive
                                        // switching away and back.
                                        popUpTo(navController.graph.startDestinationId) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = item.label,
                                    )
                                },
                                label = { Text(item.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    // No pill indicator: the tint
                                    // change alone marks the
                                    // selected tab, black on grey.
                                    selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unselectedIconColor =
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor =
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                    indicatorColor = Color.Transparent,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One bottom tab: its route, label, and icon. */
private data class TabItem(val route: String, val label: String, val icon: ImageVector)

/** The three bottom tabs, in order. */
private val TAB_ITEMS: List<TabItem> = listOf(
    TabItem("home", "Home", MusiqueIcons.Home),
    TabItem("search", "Search", MusiqueIcons.Search),
    TabItem("settings", "Settings", MusiqueIcons.Settings),
)

/** The routes that show the tab bar. */
private val TAB_ROUTES: Set<String> = TAB_ITEMS.mapTo(mutableSetOf()) { it.route }
