package com.siyu.musique.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siyu.musique.model.formatDuration

/**
 * Player page (Step 0 placeholder). Shows the current track and the
 * play/pause, previous, and next controls wired to the same
 * ViewModel as the search screen. There is no sound yet (Step 3)
 * and no seekable progress yet (Step 4); the progress line is a
 * static placeholder.
 */
@Composable
fun PlayerScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentTrack by viewModel.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val track = currentTrack

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) {
                Text("Back")
            }
        }
        Spacer(modifier = Modifier.height(48.dp))
        if (track == null) {
            Text(
                text = "Nothing playing yet. Go back and tap a track.",
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            Text(
                text = track.title,
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            // Progress placeholder: real position tracking arrives in Step 4.
            Text(
                text = "0:00 / ${formatDuration(track.duration)} (progress comes in Step 4)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(32.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { viewModel.playPrevious() }) {
                    Text("Previous")
                }
                Button(onClick = { viewModel.togglePlayPause() }) {
                    Text(if (isPlaying) "Pause" else "Play")
                }
                Button(onClick = { viewModel.playNext() }) {
                    Text("Next")
                }
            }
        }
    }
}
