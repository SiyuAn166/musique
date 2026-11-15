package com.siyu.musique.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.siyu.musique.data.SearchRepository
import com.siyu.musique.ui.theme.MusiqueTheme

/**
 * App navigation: a single Activity hosting a two-destination
 * NavHost. "search" is the start destination; "player" is the player
 * page. More destinations arrive in later steps; the ViewModel is
 * shared between the two screens and is built with the repository
 * the Activity hands in.
 */
@Composable
fun MusiqueApp(repository: SearchRepository, modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val viewModel: SearchViewModel = viewModel(
        factory = SearchViewModelFactory(repository),
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
                )
            }
            composable("player") {
                PlayerScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
