package com.siyu.musique.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
 * Playlists screen: a large "Playlists" title, a plus icon button
 * at the top right to create a new playlist, and flat rows with a
 * small stacked-covers thumbnail, the playlist name, the song
 * count, and a chevron. Tapping a row opens the detail screen. The
 * default playlist always exists; creating a playlist asks for its
 * name in a dialog, and blank names are rejected.
 */
@Composable
fun PlaylistsScreen(
    viewModel: SearchViewModel,
    onOpenDetail: (String) -> Unit,
    onOpenPlayer: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val currentTrack by viewModel.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val progress by viewModel.playbackProgress.collectAsStateWithLifecycle()

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
                .padding(start = 8.dp, top = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MusiqueIcons.ChevronLeft,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Playlists",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    newName = ""
                    showCreateDialog = true
                },
            ) {
                Icon(
                    imageVector = MusiqueIcons.AddCircleOutline,
                    contentDescription = "Create playlist",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(playlists, key = { _, playlist -> playlist.id }) { index, playlist ->
                PlaylistRow(
                    playlist = playlist,
                    onClick = { onOpenDetail(playlist.id) },
                )
                if (index < playlists.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 92.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }

        val track = currentTrack
        if (track != null) {
            MiniPlayerBar(
                track = track,
                isPlaying = isPlaying,
                playerState = playerState,
                progress = progress,
                onTogglePlayPause = { viewModel.togglePlayPause() },
                onRetry = { viewModel.retryPlayback() },
                onOpenPlayer = onOpenPlayer,
            )
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
                        tint = MaterialTheme.colorScheme.primary,
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

@Composable
private fun PlaylistRow(
    playlist: Playlist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StackedCovers(playlist)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = songCountLabel(playlist.trackCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = MusiqueIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The stacked-covers thumbnail: up to three of the first tracks'
 * covers, offset down and right so they read as a stack, the first
 * track's cover on top. A playlist with no covers gets a plain grey
 * square.
 */
@Composable
private fun StackedCovers(
    playlist: Playlist,
    modifier: Modifier = Modifier,
) {
    val covers = playlist.tracks.mapNotNull { it.cover.ifEmpty { null } }.take(3)
    Box(modifier = modifier.size(64.dp)) {
        if (covers.isEmpty()) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        } else {
            covers.indices.reversed().forEach { index ->
                AsyncImage(
                    model = covers[index],
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .offset((index * 8).dp, (index * 8).dp)
                        .size(48.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
            }
        }
    }
}
