package com.siyu.musique.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siyu.musique.model.Track
import com.siyu.musique.ui.theme.MusiqueIcons
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Load the next page when the last visible row is this close to the end of the list. */
private const val LOAD_MORE_THRESHOLD = 3

/** How long the add-to-playlist notice stays up. */
private const val ADD_NOTICE_DURATION_MS: Long = 2000L

/** How far the add notice floats above the bottom of the search content: the shared mini player bar sits just below this screen, so a small offset floats the pill just above it. */
private const val ADD_NOTICE_BOTTOM_OFFSET_DP: Int = 24

/**
 * Search tab: a light-grey capsule search field at the top and
 * flat result rows with hairline dividers, nothing else. Search
 * triggers on the keyboard search action and on tapping the
 * magnifier icon. Typing shows a suggestion dropdown from
 * Bilibili's suggest API under the field; tapping a suggestion
 * searches it. Clearing the field empties the search, and so
 * does system Back while a search is showing.
 * Tapping a row plays it (cid fill + fresh play URL
 * first); the plus icon on a row opens the playlist chooser, and
 * adding shows a short popup notice (a different one when the
 * track is already in the playlist).
 * Search errors show a distinct message per failure kind with a
 * retry icon button; player errors show their own message with a
 * retry icon button above the mini player bar. The shared mini
 * player bar and the tab bar sit below this screen, owned by
 * the app shell.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    modifier: Modifier = Modifier,
) {
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val currentTrack by viewModel.currentTrack.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val videoParts by viewModel.videoParts.collectAsStateWithLifecycle()

    /** Bvids whose parts are expanded in the results list. */
    var expandedVideoIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var queryField by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }

    /** The track waiting for a playlist choice, or null when the chooser is closed. */
    var chooserTrack by remember { mutableStateOf<Track?>(null) }

    /** Short-lived notice after adding a track, or null when hidden. */
    var addNotice by remember { mutableStateOf<String?>(null) }
    var addNoticeDismissJob by remember { mutableStateOf<Job?>(null) }
    val noticeScope = rememberCoroutineScope()

    /**
     * Applies a field value: suggestions follow typing, and
     * clearing the field empties the search at once, with no
     * extra tap on search. The field keeps a
     * [TextFieldValue] (not a plain String) so programmatic
     * changes can place the cursor, e.g. at the end after a
     * suggestion tap. Every value is clamped into bounds
     * first: the IME can hand back a selection past the end
     * of the text when its composing span desyncs from a
     * programmatic replacement (pinyin composition plus a
     * suggestion tap), and an out-of-range selection crashes
     * the text layout on the next focus change.
     */
    fun applyQueryField(newValue: TextFieldValue) {
        val safeValue = sanitizeFieldValue(newValue)
        queryField = safeValue
        viewModel.onQueryChanged(safeValue.text)
        if (safeValue.text.isBlank()) viewModel.search("")
    }

    // System Back clears the field while a search is showing (or
    // text is typed); a Back on a clean search tab pops back to
    // the Home tab as usual.
    BackHandler(
        enabled = searchState != SearchUiState.Idle || queryField.text.isNotBlank(),
    ) {
        applyQueryField(TextFieldValue(""))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        TextField(
            value = queryField,
            onValueChange = { applyQueryField(it) },
            placeholder = { Text("Search songs") },
            leadingIcon = {
                IconButton(onClick = { viewModel.search(queryField.text) }) {
                    Icon(
                        imageVector = MusiqueIcons.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            trailingIcon = {
                if (queryField.text.isNotEmpty()) {
                    IconButton(onClick = { applyQueryField(TextFieldValue("")) }) {
                        Icon(
                            imageVector = MusiqueIcons.Clear,
                            contentDescription = "Clear search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(50.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.onSurface,
                focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = { viewModel.search(queryField.text) },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Suggestion dropdown: a floating card under the field.
        // Tapping a suggestion fills the field (cursor at the end)
        // and searches it; the dropdown hides on search, on clear,
        // and on blank.
        if (suggestions.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    suggestions.forEach { suggestion ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    queryField = sanitizeFieldValue(
                                        TextFieldValue(
                                            text = suggestion,
                                            selection = TextRange(suggestion.length),
                                        ),
                                    )
                                    viewModel.search(suggestion)
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = MusiqueIcons.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        when (val state = searchState) {
            SearchUiState.Idle -> {
                // No query yet: the field above is the whole
                // screen until the user searches.
                Box(modifier = Modifier.weight(1f))
            }
            SearchUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            is SearchUiState.Empty -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No songs found for \"${state.query}\".",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            is SearchUiState.Error -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.message)
                        Spacer(modifier = Modifier.height(16.dp))
                        IconButton(onClick = { viewModel.retry() }) {
                            Icon(
                                imageVector = MusiqueIcons.Refresh,
                                contentDescription = "Retry search",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            is SearchUiState.Success -> {
                val listState = rememberLazyListState()
                // Load the next page when the user scrolls near the
                // end. distinctUntilChanged keeps sitting at the
                // bottom to one load; scrolling away and back
                // re-arms the trigger. The ViewModel guards the
                // rest (no more pages, already loading).
                LaunchedEffect(listState) {
                    snapshotFlow {
                        val layout = listState.layoutInfo
                        val lastVisible =
                            layout.visibleItemsInfo.lastOrNull()?.index ?: -1
                        lastVisible >= 0 &&
                            lastVisible >= layout.totalItemsCount - LOAD_MORE_THRESHOLD
                    }
                        .distinctUntilChanged()
                        .collect { nearEnd ->
                            if (nearEnd) viewModel.loadMore()
                        }
                }
                // Flattened rows: each video, plus its parts right
                // under it when expanded and known to have more
                // than one. A video's parts load on the first
                // expand tap and stay cached.
                val rows = remember(state.tracks, videoParts, expandedVideoIds) {
                    buildList {
                        state.tracks.forEach { track ->
                            add(SearchListRow.Video(track))
                            val parts = videoParts[track.id]
                            if (track.id in expandedVideoIds &&
                                parts != null && parts.size > 1
                            ) {
                                parts.forEach { add(SearchListRow.Part(it)) }
                            }
                        }
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                ) {
                    itemsIndexed(rows, key = { _, row -> row.key }) { index, listRow ->
                        when (listRow) {
                            is SearchListRow.Video -> {
                                val track = listRow.track
                                val parts = videoParts[track.id]
                                val expanded = track.id in expandedVideoIds
                                SongRow(
                                    track = track,
                                    // Rows are videos: stay highlighted while any
                                    // part of the video plays, not just part one.
                                    isCurrent = track.id == currentTrack?.id,
                                    onClick = { viewModel.play(track) },
                                    trailing = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            // The chevron shows until the video
                                            // is known to have a single part;
                                            // tapping it loads the parts on
                                            // first use and toggles the rows.
                                            if (parts == null || parts.size > 1) {
                                                IconButton(onClick = {
                                                    viewModel.loadVideoParts(track)
                                                    expandedVideoIds = if (expanded) {
                                                        expandedVideoIds - track.id
                                                    } else {
                                                        expandedVideoIds + track.id
                                                    }
                                                }) {
                                                    Icon(
                                                        imageVector = if (expanded) {
                                                            MusiqueIcons.ChevronDown
                                                        } else {
                                                            MusiqueIcons.ChevronRight
                                                        },
                                                        contentDescription = if (expanded) {
                                                            "Collapse parts"
                                                        } else {
                                                            "Expand parts"
                                                        },
                                                        tint = MaterialTheme.colorScheme
                                                            .onSurfaceVariant,
                                                    )
                                                }
                                            }
                                            IconButton(onClick = { chooserTrack = track }) {
                                                Icon(
                                                    imageVector = MusiqueIcons.Add,
                                                    contentDescription = "Add to playlist",
                                                    tint = MaterialTheme.colorScheme.onSurface,
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                            is SearchListRow.Part -> {
                                // A single part: tapping plays it, the
                                // add button adds just this part.
                                SongRow(
                                    track = listRow.track,
                                    isCurrent = listRow.track.key == currentTrack?.key,
                                    onClick = { viewModel.play(listRow.track) },
                                    trailing = {
                                        IconButton(onClick = { chooserTrack = listRow.track }) {
                                            Icon(
                                                imageVector = MusiqueIcons.Add,
                                                contentDescription = "Add to playlist",
                                                tint = MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                    },
                                    modifier = Modifier.padding(
                                        start = PART_ROW_INDENT_DP.dp,
                                    ),
                                    compact = true,
                                )
                            }
                        }
                        if (index < rows.lastIndex) {
                            val dividerStartDp = if (listRow is SearchListRow.Part) {
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
                    if (state.loadingMore) {
                        item(key = "loading-more") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Player error row: a failed cid fill, URL resolve, or
        // playback shows its message here with a retry icon button.
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
                IconButton(onClick = { viewModel.retryPlayback() }) {
                    Icon(
                        imageVector = MusiqueIcons.Refresh,
                        contentDescription = "Retry playback",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    // Add notice: a small black pill floating above the mini
    // player bar for a couple of seconds after a track is added.
    // Tapping outside dismisses it early.
    val notice = addNotice
    if (notice != null) {
        val noticeOffsetPx = with(LocalDensity.current) {
            ADD_NOTICE_BOTTOM_OFFSET_DP.dp.roundToPx()
        }
        Popup(
            alignment = Alignment.BottomCenter,
            offset = IntOffset(0, -noticeOffsetPx),
            onDismissRequest = { addNotice = null },
        ) {
            Surface(
                color = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(20.dp),
                shadowElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = MusiqueIcons.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.surface,
                    )
                }
            }
        }
    }

    // Playlist chooser: tapping a playlist adds the track, shows a
    // short notice (a different one when the track is already
    // present), and closes. The Downloads playlist is not
    // offered: it fills from downloads alone.
    val pending = chooserTrack
    if (pending != null) {
        ModalBottomSheet(
            onDismissRequest = { chooserTrack = null },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            ) {
                Text(
                    text = "Add to playlist",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                playlists.filterNot { it.isDownloads }.forEach { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val added = viewModel.addTrackToPlaylist(playlist.id, pending)
                                chooserTrack = null
                                addNoticeDismissJob?.cancel()
                                addNotice = if (added) "添加成功" else "已在歌单中"
                                addNoticeDismissJob = noticeScope.launch {
                                    delay(ADD_NOTICE_DURATION_MS)
                                    addNotice = null
                                }
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                            )
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
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

/**
 * Clamps a field value's selection and composition into the
 * bounds of its text. The IME can return a value whose
 * selection extends past the end of the text (its composing
 * span desynced from a programmatic replacement), and the
 * text layout throws on the next focus change when it tries
 * to measure that offset. Returns the value unchanged when it
 * is already in bounds.
 */
private fun sanitizeFieldValue(value: TextFieldValue): TextFieldValue {
    val length = value.text.length
    val selection = TextRange(
        value.selection.start.coerceIn(0, length),
        value.selection.end.coerceIn(0, length),
    )
    val composition = value.composition?.let {
        TextRange(it.start.coerceIn(0, length), it.end.coerceIn(0, length))
    }
    return if (selection == value.selection && composition == value.composition) {
        value
    } else {
        value.copy(selection = selection, composition = composition)
    }
}

/**
 * One row of the search results' flattened list: a video, or one
 * of an expanded video's parts. Part keys are prefixed so a part
 * row never collides with a video row's key.
 */
private sealed interface SearchListRow {
    val track: Track
    val key: String

    data class Video(override val track: Track) : SearchListRow {
        override val key: String get() = track.key
    }

    data class Part(override val track: Track) : SearchListRow {
        override val key: String get() = "part:${track.key}"
    }
}
