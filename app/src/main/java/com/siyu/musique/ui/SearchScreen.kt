package com.siyu.musique.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.siyu.musique.model.Track
import com.siyu.musique.model.formatDuration

/**
 * Search screen (Step 3): search box, real Bilibili result list with
 * covers, mini player bar, and a log button. Tapping a row plays it
 * through ExoPlayer (cid fill + fresh play URL first). Search errors
 * show a distinct message per failure kind, with a Retry button;
 * player errors show their own message with a Retry button above the
 * mini player bar.
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpenPlayer: () -> Unit,
    onOpenLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val currentTrack by viewModel.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search songs") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { viewModel.search(query) }) {
                Text("Search")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onOpenLog) {
                Text("日志")
            }
        }

        when (val state = searchState) {
            SearchUiState.Idle -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Type a keyword and tap Search.")
                }
            }
            SearchUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            is SearchUiState.Empty -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No songs found for \"${state.query}\".")
                }
            }
            is SearchUiState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.message)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.retry() }) {
                            Text("Retry")
                        }
                    }
                }
            }
            is SearchUiState.Success -> {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(state.tracks, key = { it.key }) { track ->
                        TrackRow(
                            track = track,
                            isCurrent = track.key == currentTrack?.key,
                            onClick = { viewModel.play(track) },
                        )
                    }
                }
            }
        }

        // Player error row (Step 3): a failed cid fill, URL resolve,
        // or playback shows its message here with a Retry button.
        val errorState = playerState as? PlayerUiState.Error
        if (errorState != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = errorState.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { viewModel.retryPlayback() }) {
                    Text("Retry")
                }
            }
        }

        // Mini player bar (Step 3): shows the current track, the real
        // player state, and a play/pause toggle wired to ExoPlayer.
        // Real progress and seek come in Step 4.
        val track = currentTrack
        if (track != null) {
            MiniPlayerBar(
                track = track,
                isPlaying = isPlaying,
                playerState = playerState,
                onTogglePlayPause = { viewModel.togglePlayPause() },
                onRetry = { viewModel.retryPlayback() },
                onOpenPlayer = onOpenPlayer,
            )
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Cover (Step 1): Coil loads the Bilibili cover; tracks
        // without a cover URL keep the first-letter placeholder.
        if (track.cover.isNotEmpty()) {
            AsyncImage(
                model = track.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp),
            )
        } else {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = track.title.firstOrNull()?.toString() ?: "?",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            text = formatDuration(track.duration),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
