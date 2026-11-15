package com.siyu.musique.ui

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siyu.musique.ui.theme.MusiqueIcons
import com.siyu.musique.ui.theme.MusiqueScriptFamily

/**
 * Home tab: the script "Musique" wordmark centered at the top, a
 * "Playlists" header row with a plus icon to create a playlist,
 * and the playlist cards (the default playlist, the Downloads
 * playlist, then the user playlists, in that order). Tapping a
 * card opens its detail screen. No search field and no header
 * buttons: search lives on its own tab, and settings lives on
 * its own tab too. The shared mini player bar and the tab bar
 * sit below this screen, owned by the app shell.
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Text(
            text = "Musique",
            fontFamily = MusiqueScriptFamily,
            fontSize = 40.sp,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Playlists",
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
                            contentDescription = "Create playlist",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
            items(playlists, key = { playlist -> playlist.id }) { playlist ->
                PlaylistRow(
                    playlist = playlist,
                    onClick = { onOpenPlaylistDetail(playlist.id) },
                )
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
            title = { Text("New playlist") },
            text = {
                TextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    placeholder = { Text("Playlist name") },
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
                        contentDescription = "Create",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            dismissButton = {
                IconButton(onClick = { showCreatePlaylistDialog = false }) {
                    Icon(
                        imageVector = MusiqueIcons.Clear,
                        contentDescription = "Cancel",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
        )
    }
}
