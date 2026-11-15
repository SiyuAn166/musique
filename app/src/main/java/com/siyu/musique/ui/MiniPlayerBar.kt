package com.siyu.musique.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.siyu.musique.model.Track
import com.siyu.musique.model.formatDuration

/**
 * Mini player bar (Step 3). Tapping the bar opens the player page;
 * the button toggles real play/pause, and shows Retry while the
 * player is in the error state. The second line names the player
 * state while loading, paused, or failed; otherwise it shows the
 * artist and duration. Real progress and seek arrive in Step 4.
 */
@Composable
fun MiniPlayerBar(
    track: Track,
    isPlaying: Boolean,
    playerState: PlayerUiState,
    onTogglePlayPause: () -> Unit,
    onRetry: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusLine = when (playerState) {
        PlayerUiState.Fetching, PlayerUiState.Buffering -> "Loading…"
        PlayerUiState.Paused -> "Paused"
        is PlayerUiState.Error -> "Playback error"
        else -> "${track.artist} · ${formatDuration(track.duration)}"
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenPlayer)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
                Text(
                    text = statusLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (playerState is PlayerUiState.Error) {
                TextButton(onClick = onRetry) {
                    Text("Retry")
                }
            } else {
                TextButton(onClick = onTogglePlayPause) {
                    Text(if (isPlaying) "Pause" else "Play")
                }
            }
        }
    }
}
