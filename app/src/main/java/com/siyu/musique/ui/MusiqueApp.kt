package com.siyu.musique.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.siyu.musique.data.SearchRepository
import com.siyu.musique.player.NoOpPlayerEngine
import com.siyu.musique.player.PlayerEngine
import com.siyu.musique.ui.theme.MusiqueTheme

/**
 * App navigation: a single Activity hosting a three-destination
 * NavHost. "search" is the start destination; "player" is the player
 * page; "log" is the debug log screen. More destinations arrive in
 * later steps; the ViewModel is shared between the screens and is
 * built with the repository and player engine the Activity hands in.
 */
@Composable
fun MusiqueApp(
    repository: SearchRepository,
    playerEngine: PlayerEngine = NoOpPlayerEngine,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val viewModel: SearchViewModel = viewModel(
        factory = SearchViewModelFactory(repository, playerEngine),
    )

    MusiqueTheme {
        NavHost(
            navController = navController,
            startDestination = "search",
            modifier = modifier,
        ) {
            composable("search") {
                SearchScreen(
                    viewModel = viewModel,
                    onOpenPlayer = { navController.navigate("player") },
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
        }
    }
}
