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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siyu.musique.ui.theme.MusiqueIcons

/**
 * Downloads screen: a back chevron, the song count, and flat
 * song rows like the playlist rows. The
 * rows come from the stored download records, so the list shows
 * the downloaded songs with their stored metadata, one row per
 * song. Tapping a song plays it with the downloads list as the
 * queue, preferring the downloaded file over the network. The
 * trash icon on a row removes that song's download: every file
 * and record for it goes. An empty list shows a short empty
 * message.
 */
@Composable
fun DownloadsScreen(
    viewModel: SearchViewModel,
    onOpenPlayer: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val downloadRecords by viewModel.downloadRecords.collectAsStateWithLifecycle()
    val currentTrack by viewModel.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val progress by viewModel.playbackProgress.collectAsStateWithLifecycle()
    val resolvedQualityLabel by viewModel.resolvedQualityLabel.collectAsStateWithLifecycle()

    /** One row per song, in download order: a song downloaded at two qualities lists once. */
    val tracks = downloadRecords.map { it.track }.distinctBy { it.key }

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
        }

        Text(
            text = songCountLabel(tracks.size),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (tracks.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No downloads yet. Download songs from the player page.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(
                    tracks,
                    key = { _, track -> track.key },
                ) { index, track ->
                    SongRow(
                        track = track,
                        // Rows are videos: match by bvid, since the
                        // row's cid may still be empty while the
                        // playing track's cid was filled by details.
                        isCurrent = track.id == currentTrack?.id,
                        onClick = { viewModel.playFromDownloads(track) },
                        trailing = {
                            IconButton(
                                onClick = {
                                    downloadRecords
                                        .filter { it.track.key == track.key }
                                        .forEach { viewModel.deleteDownload(it) }
                                },
                            ) {
                                Icon(
                                    imageVector = MusiqueIcons.Delete,
                                    contentDescription = "Delete download",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        },
                    )
                    if (index < tracks.lastIndex) {
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

        MiniPlayerBar(
            track = currentTrack,
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
