package com.siyu.musique.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
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
import com.siyu.musique.data.InMemoryLanguagePreferenceStore
import com.siyu.musique.data.InMemoryPlaylistStore
import com.siyu.musique.data.InMemoryQualityPreferenceStore
import com.siyu.musique.data.NoOpAudioFileDownloader
import com.siyu.musique.data.NoOpUpdateApkInstaller
import com.siyu.musique.data.NoOpUpdateChecker
import com.siyu.musique.data.LanguagePreferenceStore
import com.siyu.musique.data.PlaybackModeStore
import com.siyu.musique.data.PlaybackStateStore
import com.siyu.musique.data.PlaylistStore
import com.siyu.musique.data.QualityPreferenceStore
import com.siyu.musique.data.SearchRepository
import com.siyu.musique.data.UpdateApkInstaller
import com.siyu.musique.data.UpdateChecker
import com.siyu.musique.data.defaultDownloadsDir
import com.siyu.musique.data.defaultUpdatesDir
import com.siyu.musique.R
import com.siyu.musique.player.NoOpPlayerEngine
import com.siyu.musique.player.PlayerEngine
import com.siyu.musique.ui.theme.MusiqueIcons
import com.siyu.musique.ui.theme.MusiqueTheme
import java.io.File

/**
 * App navigation: a single Activity hosting a NavHost inside a
 * Column shell. The three top-level tabs are "home" (the
 * playlists), "search", and "settings"; "log" is the debug log
 * screen, "playlists" lists the playlists (unreachable since
 * the home tab shows them inline, kept for now), and
 * "playlist/{id}" is one playlist's detail screen. The player
 * page is not a destination: it is a full-screen overlay Box
 * above the shell, so the tab destination stays composed
 * underneath and shows through while the player slides down
 * on dismiss. The shell Column swallows touches while the
 * player is open, so taps on empty areas of the player never
 * reach the mini bar, the tab bar, or the page underneath; the
 * barrier sits on the shell, never on the overlay, because a
 * consuming barrier above the player would starve the
 * player's own gestures. The ViewModel is shared between the screens and is built with
 * the repository, player engine, and stores the Activity hands
 * in. Playlist ids are UUIDs or "default" (or "downloads" for
 * the synthetic playlist), so they are safe to carry in a route.
 *
 * The shell owns one shared mini player bar and the bottom tab
 * bar, below the NavHost: the tab bar shows only on the three
 * tab destinations, and the mini bar shows on the tab
 * destinations, the playlist detail, and the playlists screen,
 * but never on the log screen. Both bars hide while the player
 * sits at rest, so nothing shows through its translucent
 * background, and reappear for the dismiss drag, so the reveal
 * shows the whole page. No Scaffold: the screens pad
 * themselves for the safe drawing insets.
 */
@Composable
fun MusiqueApp(
    repository: SearchRepository,
    playerEngine: PlayerEngine = NoOpPlayerEngine,
    appCache: AppCache = InMemoryAppCache(),
    qualityPreferenceStore: QualityPreferenceStore = InMemoryQualityPreferenceStore(),
    languagePreferenceStore: LanguagePreferenceStore = InMemoryLanguagePreferenceStore(),
    playlistStore: PlaylistStore = InMemoryPlaylistStore(),
    playbackModeStore: PlaybackModeStore = InMemoryPlaybackModeStore(),
    playbackStateStore: PlaybackStateStore = InMemoryPlaybackStateStore(),
    downloadStore: DownloadStore = InMemoryDownloadStore(),
    audioFileDownloader: AudioFileDownloader = NoOpAudioFileDownloader,
    downloadsDir: File = defaultDownloadsDir(),
    updateChecker: UpdateChecker = NoOpUpdateChecker,
    updateApkInstaller: UpdateApkInstaller = NoOpUpdateApkInstaller,
    installedVersionName: String = "",
    updatesDir: File = defaultUpdatesDir(),
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
            updateChecker,
            updateApkInstaller,
            installedVersionName,
            updatesDir,
            languagePreferenceStore = languagePreferenceStore,
        ),
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Whether the player overlay is open. Plain UI state: it
    // need not survive process death, the mini bar reopens the
    // player.
    var playerOpen by remember { mutableStateOf(false) }

    // Whether the player is mid dismiss drag. The bars hide
    // while the player sits at rest and show while it moves,
    // so the reveal shows the whole page and nothing ghosts
    // through the translucent panel.
    var playerDragging by remember { mutableStateOf(false) }

    // A stable close callback: PlayerScreen keys its drag
    // detector on this lambda, so recreating it on every
    // recomposition would restart the gesture mid-drag.
    val closePlayer = remember {
        {
            playerOpen = false
            playerDragging = false
        }
    }

    val showTabs = currentRoute in TAB_ROUTES && (!playerOpen || playerDragging)
    val showMiniBar = (showTabs ||
        currentRoute == "playlists" ||
        currentRoute == "playlist/{playlistId}") && (!playerOpen || playerDragging)

    val currentTrack by viewModel.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val progress by viewModel.playbackProgress.collectAsStateWithLifecycle()
    val resolvedQualityLabel by viewModel.resolvedQualityLabel.collectAsStateWithLifecycle()

    // Guard against the keyboard's dismiss tap falling through
    // to the tab bar: when the keyboard window vanishes on
    // touch-down, the system re-delivers the gesture to the app
    // window below, and the Home tab sits exactly under the
    // dismiss chevron. Lifting the tab bar above the keyboard
    // does not help, because the bar snaps back down the moment
    // the keyboard hides. So tab taps are ignored while the
    // keyboard is visible and briefly after it hides; the
    // keyboard keeps covering the tab bar, as before.
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var imeHiddenAtMs by remember { mutableLongStateOf(0L) }
    var wasImeVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (wasImeVisible && !imeVisible) {
            imeHiddenAtMs = SystemClock.uptimeMillis()
        }
        wasImeVisible = imeVisible
    }

    // Back closes the player overlay instead of popping the nav
    // stack. The chevron button closes instantly too, with no
    // animation, like every other page switch.
    BackHandler(enabled = playerOpen) { closePlayer() }

    MusiqueTheme {
        // The Surface paints the white background: without it the
        // screens are transparent and the window background shows
        // through, which is why the app looked black.
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        // While the player is open, swallow every
                        // touch the player itself did not use, so
                        // nothing reaches the page, the mini bar,
                        // or the tab bar underneath. The overlay
                        // sits above the shell, so the player's
                        // own gestures run first and are never
                        // starved by this barrier.
                        .pointerInput(playerOpen) {
                            if (playerOpen) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent().changes.forEach { it.consume() }
                                    }
                                }
                            }
                        },
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        NavHost(
                            navController = navController,
                            startDestination = "home",
                            // No transition animations: page switches
                            // cut instantly instead of fading.
                            enterTransition = { EnterTransition.None },
                            exitTransition = { ExitTransition.None },
                            popEnterTransition = { EnterTransition.None },
                            popExitTransition = { ExitTransition.None },
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
                            onOpenPlayer = { playerOpen = true },
                            onPrevious = { viewModel.playPrevious() },
                            onNext = { viewModel.playNext() },
                            qualityLabel = resolvedQualityLabel,
                        )
                    }

                    if (showTabs) {
                        // A hand-rolled tab row instead of
                        // NavigationBar: the Material bar is 80dp
                        // tall, which eats too much of the screen.
                        // This one is 56dp with the same icons,
                        // labels, background, and selection tints.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(TAB_BAR_HEIGHT_DP.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .selectableGroup(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TAB_ITEMS.forEach { item ->
                                val selected = currentRoute == item.route
                                val label = stringResource(item.labelRes)
                                val tabTint = if (selected) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .selectable(
                                            selected = selected,
                                            onClick = {
                                                // A tab tap that lands
                                                // while the keyboard is
                                                // up, or within a beat
                                                // of it hiding, is the
                                                // keyboard's dismiss tap
                                                // falling through, not
                                                // a real tab tap.
                                                if (imeVisible ||
                                                    SystemClock.uptimeMillis() - imeHiddenAtMs <
                                                        IME_HIDE_TAP_GUARD_MS
                                                ) {
                                                    return@selectable
                                                }
                                                navController.navigate(item.route) {
                                                    // One copy of each
                                                    // tab, with its state
                                                    // saved and restored:
                                                    // the search text and
                                                    // results survive
                                                    // switching away and
                                                    // back.
                                                    popUpTo(navController.graph.startDestinationId) {
                                                        saveState = true
                                                    }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            },
                                            role = Role.Tab,
                                        )
                                        .padding(vertical = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = label,
                                        tint = tabTint,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = tabTint,
                                    )
                                }
                            }
                        }
                    }
                }
                // The player is a full-screen overlay above the
                // shell, not a navigation destination: the tab
                // destination stays composed underneath, so
                // dragging the player down reveals it instead of
                // a blank background. Opening and the chevron
                // close cut instantly, like every other page
                // switch; only the drag dismiss animates.
                if (playerOpen) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        PlayerScreen(
                            viewModel = viewModel,
                            onClose = closePlayer,
                            onDraggingChanged = { playerDragging = it },
                        )
                    }
                }
            }
        }
    }
}

/** The height of the bottom tab row: shorter than the 80dp Material default. */
private const val TAB_BAR_HEIGHT_DP: Int = 56

/** Tab taps are ignored for this long after the keyboard hides, so a dismiss tap falling through cannot switch tabs. */
private const val IME_HIDE_TAP_GUARD_MS: Long = 300L

/** One bottom tab: its route, its label's string resource, and its icon. */
private data class TabItem(val route: String, @param:StringRes val labelRes: Int, val icon: ImageVector)

/** The three bottom tabs, in order. */
private val TAB_ITEMS: List<TabItem> = listOf(
    TabItem("home", R.string.tab_home, MusiqueIcons.Home),
    TabItem("search", R.string.tab_search, MusiqueIcons.Search),
    TabItem("settings", R.string.tab_settings, MusiqueIcons.Settings),
)

/** The routes that show the tab bar. */
private val TAB_ROUTES: Set<String> = TAB_ITEMS.mapTo(mutableSetOf()) { it.route }
