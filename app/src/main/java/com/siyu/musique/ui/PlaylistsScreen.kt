package com.siyu.musique.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.siyu.musique.model.Playlist
import com.siyu.musique.ui.theme.MusiqueIcons

/** "1 song" / "N songs", shared by the playlist rows and the detail header. */
internal fun songCountLabel(count: Int): String =
    if (count == 1) "1 song" else "$count songs"

/**
 * Playlists screen: no title, a back icon at the top left and a
 * plus icon button at the top right to create a new playlist, and
 * flat rows with a
 * small stacked-covers thumbnail, the playlist name, the song
 * count, and a chevron. Tapping a row opens the detail screen. The
 * default playlist always exists; creating a playlist asks for its
 * name in a dialog, and blank names are rejected.
 */
@Composable
fun PlaylistsScreen(
    viewModel: SearchViewModel,
    onOpenDetail: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MusiqueIcons.ChevronLeft,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = {
                    newName = ""
                    showCreateDialog = true
                },
            ) {
                Icon(
                    imageVector = MusiqueIcons.Add,
                    contentDescription = "Create playlist",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(playlists, key = { playlist -> playlist.id }) { playlist ->
                PlaylistRow(
                    playlist = playlist,
                    onClick = { onOpenDetail(playlist.id) },
                )
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New playlist") },
            text = {
                TextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("Playlist name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                IconButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        viewModel.createPlaylist(newName)
                        showCreateDialog = false
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
                IconButton(onClick = { showCreateDialog = false }) {
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

/**
 * One playlist as a card: a light-grey card with a single square
 * cover, the name, and the song count. Tapping the card opens the
 * playlist. Shared by the home playlists section and the Playlists
 * screen.
 */
@Composable
internal fun PlaylistRow(
    playlist: Playlist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlaylistCover(playlist)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = songCountLabel(playlist.trackCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The playlist cover: the first track's cover, or a grey square
 * with a queue icon when the playlist has no covers yet.
 */
@Composable
private fun PlaylistCover(
    playlist: Playlist,
    modifier: Modifier = Modifier,
) {
    val cover = playlist.tracks.firstNotNullOfOrNull { it.cover.ifEmpty { null } }
    if (cover != null) {
        AsyncImage(
            model = cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
    } else {
        Box(
            modifier = modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.outlineVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MusiqueIcons.QueueMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
