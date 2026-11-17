package com.siyu.musique.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siyu.musique.R
import com.siyu.musique.ui.theme.MusiqueIcons

/** First-frame estimate for the floating title's height, until it is measured: the 48dp button plus the row's vertical padding. */
private const val TITLE_HEIGHT_ESTIMATE_DP = 64

/**
 * Home tab: the playlist cards (the default playlist, the
 * Downloads playlist, then the user playlists, in that order),
 * under a floating "Playlists" title row with a plus icon to
 * create a playlist. The title floats above the list instead of
 * scrolling with it, so it never gets pushed up out of view;
 * the list reserves the title's height at the top and slides
 * underneath it. Tapping a card opens its detail screen;
 * long-pressing a custom playlist opens its rename and delete
 * menu. No search field and no header buttons: search lives on
 * its own tab, and settings lives on its own tab too. The shared
 * mini player bar and the tab bar sit below this screen, owned
 * by the app shell.
 */
@Composable
fun HomeScreen(
    viewModel: SearchViewModel,
    onOpenPlaylistDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()

    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    // The floating title's measured height: the list reserves
    // this much space at the top so the first card starts below
    // the title and scrolls underneath it. The estimate covers
    // the first frame, before the title has been measured.
    var titleHeight by remember { mutableStateOf(TITLE_HEIGHT_ESTIMATE_DP.dp) }
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxSize()
            // Opaque: during the predictive back gesture the
            // outgoing page slides over the incoming one, and a
            // transparent page would show both at once.
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = titleHeight),
        ) {
            items(playlists, key = { playlist -> playlist.id }) { playlist ->
                PlaylistRowWithMenu(
                    playlist = playlist,
                    viewModel = viewModel,
                    onClick = { onOpenPlaylistDetail(playlist.id) },
                )
            }
        }
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onSizeChanged { size ->
                    titleHeight = with(density) { size.height.toDp() }
                },
            color = MaterialTheme.colorScheme.background,
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.playlists_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = {
                        newPlaylistName = ""
                        showCreatePlaylistDialog = true
                    },
                ) {
                    Icon(
                        imageVector = MusiqueIcons.Add,
                        contentDescription = stringResource(R.string.create_playlist),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }

    // Create playlist dialog, from the plus icon on the playlists
    // header. Blank names are rejected, same as the playlists
    // screen's dialog.
    if (showCreatePlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(36.dp),
            title = { Text(stringResource(R.string.new_playlist_title)) },
            text = {
                TextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    placeholder = { Text(stringResource(R.string.playlist_name_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(50.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.onSurface,
                        focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                IconButton(
                    enabled = newPlaylistName.isNotBlank(),
                    onClick = {
                        viewModel.createPlaylist(newPlaylistName)
                        showCreatePlaylistDialog = false
                    },
                ) {
                    Icon(
                        imageVector = MusiqueIcons.Check,
                        contentDescription = stringResource(R.string.create),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            dismissButton = {
                IconButton(onClick = { showCreatePlaylistDialog = false }) {
                    Icon(
                        imageVector = MusiqueIcons.Clear,
                        contentDescription = stringResource(R.string.cancel),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
        )
    }
}
