package com.siyu.musique.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siyu.musique.model.Track
import com.siyu.musique.ui.theme.MusiqueIcons

/**
 * Playlist detail screen: a back chevron and action icons on
 * top, then flat song rows like the search rows, with no title
 * text. Tapping a row expands a collection to its parts and plays
 * a single video, with the playlist as the queue; tapping a part
 * plays that part.
 * The multi-select button at the top turns on selection mode:
 * checkboxes appear on the rows, tapping a row toggles its
 * checkbox instead of playing, and a trash icon at the top is
 * visible for the whole mode, greyed until at least one song is
 * checked, then black to remove all selected songs at once.
 * Long-pressing a row enters selection mode with that row
 * checked, so the trash icon is usable right away. Tapping the
 * button again leaves selection mode and clears the selection.
 * User-created playlists get an edit icon to rename the playlist
 * and a trash icon to delete the whole playlist, both hidden
 * while selection mode is on; the default playlist and the
 * Downloads playlist cannot be renamed or deleted, so neither
 * icon shows for them. Removing songs from the
 * Downloads playlist deletes the downloaded files as well. An
 * empty playlist shows a short empty message, except Downloads,
 * which shows nothing when empty. The shared mini player
 * bar sits below this screen, owned by the app shell.
 */
@Composable
fun PlaylistDetailScreen(
    viewModel: SearchViewModel,
    playlistId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val downloadRecords by viewModel.downloadRecords.collectAsStateWithLifecycle()
    val currentTrack by viewModel.currentTrack.collectAsStateWithLifecycle()
    val videoParts by viewModel.videoParts.collectAsStateWithLifecycle()
    val partsLoadingIds by viewModel.partsLoadingIds.collectAsStateWithLifecycle()

    val playlist = playlists.firstOrNull { it.id == playlistId }

    /** Whether selection mode is on: checkboxes show and row taps toggle them. */
    var selecting by remember { mutableStateOf(false) }

    /** Keys of the songs checked for removal, or empty when nothing is selected. */
    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }

    /** Bvids whose parts are expanded in the list. Expansion hides while selecting. */
    var expandedVideoIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    /** Whether the rename dialog is open. */
    var showRenameDialog by remember { mutableStateOf(false) }

    /** The name typed in the rename dialog, prefilled with the current name on open. */
    var renameName by remember { mutableStateOf("") }

    /** The entry whose row tap is waiting on a parts load to decide between expanding and playing. */
    var pendingTapTrack by remember { mutableStateOf<Track?>(null) }

    // A row tap on an entry with unknown parts waits for the
    // load: a collection expands, a single video plays (unless
    // selection mode took over meanwhile, which drops the tap).
    // The parts are fetched once and cached, so later taps
    // expand, collapse, and play without another lookup. A
    // failed load leaves the tap pending until another tap
    // supersedes or retries it.
    LaunchedEffect(videoParts, pendingTapTrack, expandedVideoIds, selecting, playlists) {
        val pending = pendingTapTrack ?: return@LaunchedEffect
        val parts = videoParts[pending.id] ?: return@LaunchedEffect
        pendingTapTrack = null
        if (parts.size > 1) {
            expandedVideoIds = expandedVideoIds + pending.id
        } else if (!selecting) {
            val current = playlists.firstOrNull { it.id == playlistId }
                ?: return@LaunchedEffect
            viewModel.playFromPlaylist(pending, current)
        }
    }

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
            if (!selecting && playlist != null && !playlist.isDefault && !playlist.isDownloads) {
                IconButton(
                    onClick = {
                        renameName = playlist.name
                        showRenameDialog = true
                    },
                ) {
                    Icon(
                        imageVector = MusiqueIcons.Edit,
                        contentDescription = "Rename playlist",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
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
            if (playlist != null && selecting) {
                IconButton(
                    enabled = selectedKeys.isNotEmpty(),
                    onClick = {
                        if (playlist.isDownloads) {
                            // Deleting a download removes every
                            // file and record for the song, at
                            // every downloaded quality.
                            downloadRecords
                                .filter { it.track.key in selectedKeys }
                                .forEach { viewModel.deleteDownload(it) }
                        } else {
                            viewModel.removeTracksFromPlaylist(playlist.id, selectedKeys)
                        }
                        selectedKeys = emptySet()
                    },
                ) {
                    Icon(
                        imageVector = MusiqueIcons.Delete,
                        contentDescription = "Remove selected songs",
                        tint = if (selectedKeys.isNotEmpty()) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
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
            if (playlist.tracks.isEmpty() && !playlist.isDownloads) {
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
            } else if (playlist.tracks.isNotEmpty()) {
                // Flattened rows: each entry, plus its parts right
                // under it when expanded and known to have more
                // than one. Selection mode shows entries only:
                // removal works on whole entries, never on parts.
                val rows = remember(playlist.tracks, videoParts, expandedVideoIds, selecting) {
                    buildList {
                        playlist.tracks.forEach { track ->
                            add(DetailListRow.Video(track))
                            val parts = videoParts[track.id]
                            if (!selecting && track.id in expandedVideoIds &&
                                parts != null && parts.size > 1
                            ) {
                                parts.forEach { add(DetailListRow.Part(it)) }
                            }
                        }
                    }
                }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(
                        rows,
                        key = { _, row -> row.key },
                    ) { index, listRow ->
                        when (listRow) {
                            is DetailListRow.Video -> {
                                val track = listRow.track
                                val expanded = track.id in expandedVideoIds
                                SongRow(
                                    track = track,
                                    // Rows are videos: match by bvid, since the
                                    // row's cid may still be empty while the
                                    // playing track's cid was filled by details.
                                    isCurrent = track.id == currentTrack?.id,
                                    // A parts lookup in flight dims the cover
                                    // under a spinner instead of a frozen row.
                                    loading = track.id in partsLoadingIds,
                                    // Long-pressing enters selection mode
                                    // with this row checked, so the trash
                                    // icon is usable right away.
                                    onLongClick = {
                                        selecting = true
                                        selectedKeys = if (track.key in selectedKeys) {
                                            selectedKeys - track.key
                                        } else {
                                            selectedKeys + track.key
                                        }
                                    },
                                    onClick = {
                                        if (selecting) {
                                            selectedKeys = if (track.key in selectedKeys) {
                                                selectedKeys - track.key
                                            } else {
                                                selectedKeys + track.key
                                            }
                                        } else {
                                            // Tapping a row expands a collection
                                            // and plays a single video. Unknown
                                            // parts load first; the pending tap
                                            // above decides once they land.
                                            val known = videoParts[track.id]
                                            when {
                                                known != null && known.size > 1 -> {
                                                    expandedVideoIds = if (expanded) {
                                                        expandedVideoIds - track.id
                                                    } else {
                                                        expandedVideoIds + track.id
                                                    }
                                                }
                                                known != null -> {
                                                    viewModel.playFromPlaylist(track, playlist)
                                                }
                                                else -> {
                                                    pendingTapTrack = track
                                                    viewModel.loadVideoParts(track)
                                                }
                                            }
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
                            }
                            is DetailListRow.Part -> {
                                // A single part: tapping plays it with
                                // the playlist as the queue.
                                SongRow(
                                    track = listRow.track,
                                    isCurrent = listRow.track.key == currentTrack?.key,
                                    onClick = {
                                        viewModel.playFromPlaylist(listRow.track, playlist)
                                    },
                                    trailing = {},
                                    modifier = Modifier.padding(
                                        start = PART_ROW_INDENT_DP.dp,
                                    ),
                                    compact = true,
                                )
                            }
                        }
                        if (index < rows.lastIndex) {
                            val dividerStartDp = if (listRow is DetailListRow.Part) {
                                PART_ROW_DIVIDER_START_DP
                            } else {
                                SONG_ROW_DIVIDER_START_DP
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(
                                    start = dividerStartDp.dp,
                                ),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    // Rename dialog, from the edit icon in the header. Mirrors
    // the create-playlist dialog: the field starts with the
    // current name, blank names are rejected, duplicate names
    // are allowed.
    if (showRenameDialog && playlist != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename playlist") },
            text = {
                TextField(
                    value = renameName,
                    onValueChange = { renameName = it },
                    placeholder = { Text("Playlist name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                IconButton(
                    enabled = renameName.isNotBlank(),
                    onClick = {
                        viewModel.renamePlaylist(playlist.id, renameName)
                        showRenameDialog = false
                    },
                ) {
                    Icon(
                        imageVector = MusiqueIcons.Check,
                        contentDescription = "Rename",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            dismissButton = {
                IconButton(onClick = { showRenameDialog = false }) {
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
 * One row of the playlist detail's flattened list: an entry, or
 * one of an expanded entry's parts. Part keys are prefixed so a
 * part row never collides with an entry row's key.
 */
private sealed interface DetailListRow {
    val track: Track
    val key: String

    data class Video(override val track: Track) : DetailListRow {
        override val key: String get() = track.key
    }

    data class Part(override val track: Track) : DetailListRow {
        override val key: String get() = "part:${track.key}"
    }
}
