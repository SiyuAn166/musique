package com.siyu.musique.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siyu.musique.R
import com.siyu.musique.model.Track
import com.siyu.musique.model.moved
import com.siyu.musique.ui.theme.MusiqueIcons
import kotlinx.coroutines.delay

/**
 * Playlist detail screen: a back chevron on top, then flat song
 * rows like the search rows, with no title text and no dividers
 * between rows. Tapping a row expands a collection to its parts
 * and plays a single video, with the playlist as the queue;
 * tapping a part plays that part.
 * Long-pressing a row enters selection mode with that row
 * checked, and a contextual action bar floats above the page:
 * the tab bar's background color, a back button that leaves the
 * mode, the selected count in the middle, a delete icon on the
 * right, and a second row with a select-all checkbox and its
 * label. Floating it keeps the list still: triggering selection
 * never pushes the rows down, and the list reserves the height
 * the bar adds over the plain header, so the bar never covers
 * a row either. Circular checkboxes appear on the left of the rows, tapping a row toggles its checkbox instead
 * of playing, and a checked row paints the darker container
 * grey, distinct from the bar's grey, against the clean white
 * of the rest. Deleting asks first, through a confirmation
 * dialog; removing songs from the Downloads playlist deletes the
 * downloaded files as well.
 * The right side of every row in the default and user playlists
 * holds only a drag handle: dragging it reorders the songs, and
 * the new order is saved when the drag ends. The Downloads
 * playlist has no handle, its order comes from the download
 * records. Renaming and deleting a playlist live on the playlist
 * list's long-press menu, not on this screen. An empty playlist
 * shows a short empty message, except Downloads, which shows
 * nothing when empty. The shared mini player bar floats above
 * this screen, owned by the app shell.
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

    /** Whether the delete confirmation dialog is open. */
    var showDeleteDialog by remember { mutableStateOf(false) }

    /** The entry whose row tap is waiting on a parts load to decide between expanding and playing. */
    var pendingTapTrack by remember { mutableStateOf<Track?>(null) }

    // Drag-to-reorder state. A drag works on a local copy of the
    // entry order and commits it to the ViewModel only when the
    // drag ends, so a cancelled drag leaves the saved order
    // untouched.
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val edgeScrollThresholdPx = with(density) { EDGE_SCROLL_THRESHOLD_DP.dp.toPx() }

    /** Key of the row being dragged, or null when no drag is running. */
    var draggingKey by remember { mutableStateOf<String?>(null) }

    /** The dragged row's vertical offset from its laid-out position, in pixels. */
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    /** The entry order as the drag rearranges it, or null outside a drag. */
    var dragOrder by remember { mutableStateOf<List<Track>?>(null) }

    /** Edge auto-scroll direction while dragging: -1 up, 1 down, 0 still. */
    var edgeScrollDirection by remember { mutableIntStateOf(0) }

    // While a drag holds the row near an edge, the list scrolls
    // under it so the row can travel past the visible rows. One
    // loop, restarted when the direction changes: scrolling from
    // the drag events themselves would queue up faster than the
    // list can apply.
    LaunchedEffect(draggingKey, edgeScrollDirection) {
        if (draggingKey == null || edgeScrollDirection == 0) return@LaunchedEffect
        while (true) {
            listState.dispatchRawDelta(edgeScrollDirection * EDGE_SCROLL_PX_PER_TICK)
            delay(EDGE_SCROLL_TICK_MS)
        }
    }

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

    // The contextual action bar floats above the page instead of
    // sitting in the column: triggering selection mode must not
    // push the list down. The list permanently reserves the
    // height the bar adds over the plain header, so the bar
    // never covers a row either: when it appears it fills
    // exactly the reserved space, and nothing moves. Both
    // heights are measured at runtime; the estimates cover the
    // first frames.
    var headerHeight by remember { mutableStateOf(HEADER_HEIGHT_ESTIMATE_DP.dp) }
    var cabHeight by remember { mutableStateOf(CAB_HEIGHT_ESTIMATE_DP.dp) }
    val listTopPadding = (cabHeight - headerHeight).coerceAtLeast(0.dp)
    Box(
        modifier = modifier
            .fillMaxSize()
            // Opaque: during the predictive back gesture the
            // outgoing page slides over the incoming one, and a
            // transparent page would show both at once.
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp)
                    .onSizeChanged { size ->
                        headerHeight = with(density) { size.height.toDp() }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = MusiqueIcons.ChevronLeft,
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            }

            if (playlist == null) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.playlist_not_found),
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
                            text = stringResource(R.string.empty_playlist),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (playlist.tracks.isNotEmpty()) {
                    // Flattened rows: each entry, plus its parts right
                    // under it when expanded and known to have more
                    // than one. Selection mode shows entries only:
                    // removal works on whole entries, never on parts.
                    // A drag shows its local order instead of the
                    // saved one, and collapses every expansion, so
                    // the row index is the entry index throughout.
                    val displayTracks = dragOrder ?: playlist.tracks
                    val rows = remember(displayTracks, videoParts, expandedVideoIds, selecting) {
                        buildList {
                            displayTracks.forEach { track ->
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
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(top = listTopPadding),
                    ) {
                        itemsIndexed(
                            rows,
                            key = { _, row -> row.key },
                        ) { _, listRow ->
                            when (listRow) {
                                is DetailListRow.Video -> {
                                    val track = listRow.track
                                    val expanded = track.id in expandedVideoIds
                                    val isDragging = draggingKey == track.key
                                    SongRow(
                                        track = track,
                                        // Rows are videos: match by bvid, since the
                                        // row's cid may still be empty while the
                                        // playing track's cid was filled by details.
                                        isCurrent = track.id == currentTrack?.id,
                                        // A parts lookup in flight dims the cover
                                        // under a spinner instead of a frozen row.
                                        loading = track.id in partsLoadingIds,
                                        // Checked rows and the dragged row
                                        // paint the light grey highlight.
                                        highlighted = track.key in selectedKeys || isDragging,
                                        leading = if (selecting) {
                                            {
                                                RoundCheckbox(
                                                    checked = track.key in selectedKeys,
                                                    onCheckedChange = { checked ->
                                                        selectedKeys = if (checked) {
                                                            selectedKeys + track.key
                                                        } else {
                                                            selectedKeys - track.key
                                                        }
                                                    },
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                        // Long-pressing enters selection mode
                                        // with this row checked, so the delete
                                        // icon is usable right away.
                                        onLongClick = {
                                            if (draggingKey == null) {
                                                selecting = true
                                                selectedKeys = if (track.key in selectedKeys) {
                                                    selectedKeys - track.key
                                                } else {
                                                    selectedKeys + track.key
                                                }
                                            }
                                        },
                                        onClick = {
                                            if (draggingKey != null) return@SongRow
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
                                            if (!playlist.isDownloads && !selecting) {
                                                DragHandle(
                                                    onDragStart = {
                                                        draggingKey = track.key
                                                        dragOffsetY = 0f
                                                        dragOrder = playlist.tracks
                                                        expandedVideoIds = emptySet()
                                                        edgeScrollDirection = 0
                                                    },
                                                    onDrag = dragHandler@{ dragAmountY ->
                                                        val key = draggingKey ?: return@dragHandler
                                                        val order = dragOrder ?: return@dragHandler
                                                        dragOffsetY += dragAmountY
                                                        val fromIndex = order.indexOfFirst { it.key == key }
                                                        if (fromIndex < 0) return@dragHandler
                                                        val layoutInfo = listState.layoutInfo
                                                        val draggedInfo = layoutInfo.visibleItemsInfo
                                                            .firstOrNull { it.key == key }
                                                            ?: return@dragHandler
                                                        val draggedCenter = draggedInfo.offset +
                                                            dragOffsetY + draggedInfo.size / 2
                                                        // The row under the dragged row's
                                                        // center trades places with it; the
                                                        // offset gives back the traded row's
                                                        // height, so the dragged row stays
                                                        // under the finger.
                                                        val target = layoutInfo.visibleItemsInfo
                                                            .firstOrNull { info ->
                                                                info.key != key &&
                                                                    draggedCenter >= info.offset &&
                                                                    draggedCenter <= info.offset + info.size
                                                            }
                                                        if (target != null && target.index != fromIndex) {
                                                            dragOrder = order.moved(fromIndex, target.index)
                                                            dragOffsetY += if (target.index > fromIndex) {
                                                                -target.size.toFloat()
                                                            } else {
                                                                target.size.toFloat()
                                                            }
                                                        }
                                                        // Near an edge the list scrolls
                                                        // under the held row.
                                                        edgeScrollDirection = when {
                                                            draggedCenter <
                                                                layoutInfo.viewportStartOffset +
                                                                edgeScrollThresholdPx -> -1
                                                            draggedCenter >
                                                                layoutInfo.viewportEndOffset -
                                                                edgeScrollThresholdPx -> 1
                                                            else -> 0
                                                        }
                                                    },
                                                    onDragEnd = {
                                                        val order = dragOrder
                                                        draggingKey = null
                                                        dragOffsetY = 0f
                                                        dragOrder = null
                                                        edgeScrollDirection = 0
                                                        if (order != null) {
                                                            viewModel.reorderPlaylistTracks(
                                                                playlist.id,
                                                                order.map { it.key },
                                                            )
                                                        }
                                                    },
                                                )
                                            }
                                        },
                                        modifier = Modifier
                                            .animateItem()
                                            .graphicsLayer {
                                                translationY = if (isDragging) dragOffsetY else 0f
                                                shadowElevation = if (isDragging) {
                                                    with(density) { DRAGGED_ROW_ELEVATION_DP.dp.toPx() }
                                                } else {
                                                    0f
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
                                        modifier = Modifier
                                            .animateItem()
                                            .padding(
                                                start = PART_ROW_INDENT_DP.dp,
                                            ),
                                        compact = true,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }
        if (selecting && playlist != null) {
            // Contextual action bar, floating above the page: the
            // tab bar's background, a back button that leaves
            // selection mode, the selected count in the middle,
            // the delete icon on the right, and a select-all row
            // of its own. The shadow sets it apart from the list
            // scrolling underneath it.
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        cabHeight = with(density) { size.height.toDp() }
                    },
                color = MaterialTheme.colorScheme.surfaceContainer,
                shadowElevation = 4.dp,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = {
                                selecting = false
                                selectedKeys = emptySet()
                            },
                        ) {
                            Icon(
                                imageVector = MusiqueIcons.ChevronLeft,
                                contentDescription = stringResource(R.string.exit_selection),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Text(
                            text = pluralStringResource(
                                R.plurals.selected_songs,
                                selectedKeys.size,
                                selectedKeys.size,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            enabled = selectedKeys.isNotEmpty(),
                            onClick = { showDeleteDialog = true },
                        ) {
                            Icon(
                                imageVector = MusiqueIcons.Delete,
                                contentDescription = stringResource(R.string.delete_selected),
                                tint = if (selectedKeys.isNotEmpty()) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    val allSelected = playlist.tracks.isNotEmpty() &&
                        selectedKeys.size == playlist.tracks.size
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedKeys = if (allSelected) {
                                    emptySet()
                                } else {
                                    playlist.tracks.mapTo(mutableSetOf()) { it.key }
                                }
                            }
                            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RoundCheckbox(checked = allSelected, onCheckedChange = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(
                                if (allSelected) {
                                    R.string.deselect_all
                                } else {
                                    R.string.select_all
                                },
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }

    // Delete confirmation: removing songs from a playlist, or
    // songs and their files from Downloads, asks first. The
    // selection clears and selection mode stays on, as before.
    if (showDeleteDialog && playlist != null) {
        val count = selectedKeys.size
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(36.dp),
            title = {
                Text(
                    stringResource(
                        if (playlist.isDownloads) {
                            R.string.delete_downloads_title
                        } else {
                            R.string.delete_songs_title
                        },
                    ),
                )
            },
            text = {
                Text(
                    pluralStringResource(
                        if (playlist.isDownloads) {
                            R.plurals.delete_downloads_message
                        } else {
                            R.plurals.delete_songs_message
                        },
                        count,
                        count,
                    ),
                )
            },
            confirmButton = {
                IconButton(
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
                        showDeleteDialog = false
                    },
                ) {
                    Icon(
                        imageVector = MusiqueIcons.Check,
                        contentDescription = stringResource(R.string.delete_selected),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            dismissButton = {
                IconButton(onClick = { showDeleteDialog = false }) {
                    Icon(
                        imageVector = MusiqueIcons.Clear,
                        contentDescription = stringResource(R.string.cancel),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
        )
    }
}

/**
 * The drag handle on a playlist row: the three-line hamburger.
 * Dragging it starts a reorder drag; the row itself keeps its
 * tap and long-press behavior, so multi-select and sorting stay
 * apart.
 * The callbacks are read through updated state because the
 * gesture block below runs once per handle: a stale closure
 * would drag with the playlist order from the first
 * composition.
 */
@Composable
private fun DragHandle(
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    Box(
        modifier = modifier
            .size(40.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { currentOnDragStart() },
                    onDragCancel = { currentOnDragEnd() },
                    onDragEnd = { currentOnDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        currentOnDrag(dragAmount.y)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MusiqueIcons.DragHandle,
            contentDescription = stringResource(R.string.drag_to_reorder),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** How close to an edge a dragged row must get before the list auto-scrolls. */
private const val EDGE_SCROLL_THRESHOLD_DP: Int = 56

/** First-frame estimates for the header and the floating action bar, until both are measured. */
private const val HEADER_HEIGHT_ESTIMATE_DP: Int = 56
private const val CAB_HEIGHT_ESTIMATE_DP: Int = 92

/** Pixels scrolled per auto-scroll tick while a drag holds near an edge. */
private const val EDGE_SCROLL_PX_PER_TICK: Float = 16f

/** Milliseconds between auto-scroll ticks. */
private const val EDGE_SCROLL_TICK_MS: Long = 16L

/** Elevation of the dragged row, so it reads as lifted off the list. */
private const val DRAGGED_ROW_ELEVATION_DP: Int = 8

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
