package com.siyu.musique.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.siyu.musique.core.network.SMALL_COVER_HEIGHT
import com.siyu.musique.core.network.SMALL_COVER_WIDTH
import com.siyu.musique.core.network.sizedCoverUrl
import com.siyu.musique.model.Track
import com.siyu.musique.model.formatDuration

/** The cover size in song rows, shared with the divider inset below. */
internal const val SONG_ROW_COVER_SIZE_DP: Int = 56

/** The horizontal padding of every song row, shared with the divider insets. */
internal const val SONG_ROW_HORIZONTAL_PADDING_DP: Int = 16

/** The vertical padding of a regular song row. */
internal const val SONG_ROW_VERTICAL_PADDING_DP: Int = 12

/** The tighter vertical padding of a compact song row (part rows in the queue sheet). */
internal const val SONG_ROW_COMPACT_VERTICAL_PADDING_DP: Int = 8

/** The gap between the cover and the text in a regular song row. */
internal const val SONG_ROW_COVER_GAP_DP: Int = 12

/** The hairline divider inset: row padding + cover + gap, so dividers start at the text. */
internal const val SONG_ROW_DIVIDER_START_DP: Int =
    SONG_ROW_HORIZONTAL_PADDING_DP + SONG_ROW_COVER_SIZE_DP + SONG_ROW_COVER_GAP_DP

/** Extra start padding for part rows nested under their video, in the queue sheet and the expandable lists. */
internal const val PART_ROW_INDENT_DP: Int = 32

/** The hairline divider inset after a part row: the indent plus the row's own start padding, so dividers start at the part text. */
internal const val PART_ROW_DIVIDER_START_DP: Int =
    PART_ROW_INDENT_DP + SONG_ROW_HORIZONTAL_PADDING_DP

/**
 * One flat song row, shared by the search results, the playlist
 * detail screen, and the queue sheet: a small square cover with
 * slightly rounded corners, title + artist, duration on the
 * right, and a caller-supplied trailing icon action (add on
 * search, remove on playlist detail). The current track's title
 * is bold. The caller draws the hairline divider between rows,
 * except the playlist detail screen, which shows no dividers.
 * A [compact] row skips the cover entirely and tightens the
 * vertical padding: the indented part rows in the queue sheet,
 * where the video's cover already shows on the parent row.
 * While [loading] is true the cover dims under a small white
 * spinner, so a parts lookup in flight reads as loading instead
 * of a frozen row; compact rows have no cover to dim.
 * [onLongClick] is null for rows with no long-press action; rows
 * that support it (playlist entries) enter selection mode.
 * [leading] draws a caller-supplied control before the cover
 * (the selection checkbox on playlist entries); [highlighted]
 * paints the whole row in the light grey used for checked and
 * dragged rows, against the clean white of the rest.
 */
@Composable
fun SongRow(
    track: Track,
    isCurrent: Boolean,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    loading: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    highlighted: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (highlighted) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    Color.Transparent
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(
                horizontal = SONG_ROW_HORIZONTAL_PADDING_DP.dp,
                vertical = if (compact) {
                    SONG_ROW_COMPACT_VERTICAL_PADDING_DP.dp
                } else {
                    SONG_ROW_VERTICAL_PADDING_DP.dp
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(modifier = Modifier.width(SONG_ROW_COVER_GAP_DP.dp))
        }
        if (!compact) {
            Box(
                modifier = Modifier
                    .size(SONG_ROW_COVER_SIZE_DP.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (track.cover.isNotEmpty()) {
                    AsyncImage(
                        model = sizedCoverUrl(track.cover, SMALL_COVER_WIDTH, SMALL_COVER_HEIGHT),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = track.title.firstOrNull()?.toString() ?: "?",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (loading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.3f)),
                    )
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                }
            }
            Spacer(modifier = Modifier.width(SONG_ROW_COVER_GAP_DP.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
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
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = formatDuration(track.duration),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(4.dp))
        trailing()
    }
}
