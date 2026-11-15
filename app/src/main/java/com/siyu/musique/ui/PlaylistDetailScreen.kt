package com.siyu.musique.ui

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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siyu.musique.ui.theme.MusiqueIcons

/**
 * Playlist detail screen: a back chevron, the playlist name as a
 * large title, the song count, and flat song rows like the search
 * rows. Tapping a song plays it with the playlist as the queue.
 * The multi-select button at the top turns on selection mode:
 * checkboxes appear on the rows, tapping a row toggles its
 * checkbox instead of playing, and while any song is selected a
 * minus icon at the top removes all selected songs at once.
 * Tapping the button again leaves selection mode and clears the
 * selection. User-created playlists get a trash icon to delete
 * the whole playlist; the default playlist cannot be deleted. An
 * empty playlist shows a short empty message.
 */
@Composable
fun PlaylistDetailScreen(
    viewModel: SearchViewModel,
    playlistId: String,
    onOpenPlayer: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val currentTrack by viewModel.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val progress by viewModel.playbackProgress.collectAsStateWithLifecycle()
    val resolvedQualityLabel by viewModel.resolvedQualityLabel.collectAsStateWithLifecycle()

    val playlist = playlists.firstOrNull { it.id == playlistId }

    /** Whether selection mode is on: checkboxes show and row taps toggle them. */
    var selecting by remember { mutableStateOf(false) }

    /** Keys of the songs checked for removal, or empty when nothing is selected. */
    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }

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
            if (playlist != null && playlist.tracks.isNotEmpty()) {
                IconButton(
                    onClick = {
                        selecting = !selecting
                        if (!selecting) selectedKeys = emptySet()
                    },
                ) {
                    Icon(
                        imageVector = MusiqueIcons.PlaylistAddCheck,
                        contentDescription = if (selecting) {
                            "Done selecting"
                        } else {
                            "Select songs"
                        },
                        tint = if (selecting) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            if (playlist != null && !playlist.isDefault) {
                IconButton(
                    onClick = {
                        viewModel.deletePlaylist(playlist.id)
                        onBack()
                    },
                ) {
                    Icon(
                        imageVector = MusiqueIcons.Delete,
                        contentDescription = "Delete playlist",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (playlist != null && selectedKeys.isNotEmpty()) {
                IconButton(
                    onClick = {
                        viewModel.removeTracksFromPlaylist(playlist.id, selectedKeys)
                        selectedKeys = emptySet()
                    },
                ) {
                    Icon(
                        imageVector = MusiqueIcons.Remove,
                        contentDescription = "Remove selected songs",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        if (playlist == null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Playlist not found.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = songCountLabel(playlist.trackCount),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (playlist.tracks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No songs yet. Add songs from search.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(
                        playlist.tracks,
                        key = { _, track -> track.key },
                    ) { index, track ->
                        SongRow(
                            track = track,
                            // Rows are videos: match by bvid, since the
                            // row's cid may still be empty while the
                            // playing track's cid was filled by details.
                            isCurrent = track.id == currentTrack?.id,
                            onClick = {
                                if (selecting) {
                                    selectedKeys = if (track.key in selectedKeys) {
                                        selectedKeys - track.key
                                    } else {
                                        selectedKeys + track.key
                                    }
                                } else {
                                    viewModel.playFromPlaylist(track, playlist)
                                }
                            },
                            trailing = {
                                if (selecting) {
                                    Checkbox(
                                        checked = track.key in selectedKeys,
                                        onCheckedChange = { checked ->
                                            selectedKeys = if (checked) {
                                                selectedKeys + track.key
                                            } else {
                                                selectedKeys - track.key
                                            }
                                        },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = MaterialTheme.colorScheme.onSurface,
                                            uncheckedColor =
                                                MaterialTheme.colorScheme.onSurfaceVariant,
                                            checkmarkColor = MaterialTheme.colorScheme.surface,
                                        ),
                                    )
                                }
                            },
                        )
                        if (index < playlist.tracks.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(
                                    start = SONG_ROW_DIVIDER_START_DP.dp,
                                ),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
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
                onPrevious = { viewModel.playPrevious() },
                onNext = { viewModel.playNext() },
                qualityLabel = resolvedQualityLabel,
            )
        }
    }
}
