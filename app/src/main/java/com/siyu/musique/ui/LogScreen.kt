package com.siyu.musique.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siyu.musique.core.logging.MusiqueLog

/**
 * Debug log screen (Step 3): the last ~300 log lines, oldest first,
 * monospace, auto-scrolled to the bottom. Copy puts every line on
 * the clipboard; Clear drops the buffer. The same lines go to
 * logcat under the tag "Musique". Log lines never contain full play
 * URLs, cookies, tickets, or keys: hosts, ids, counts, and error
 * kinds only.
 *
 * The screen reads the shared logger's buffer directly, with no
 * ViewModel indirection, and writes one line on every entry, so a
 * working logger can never show a blank screen; the header always
 * shows the buffered count, so an empty buffer reads as "0 lines
 * buffered" instead of a blank screen.
 */
@Composable
fun LogScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lines by MusiqueLog.shared.buffer.snapshot.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()

    // Once per entry: prove the logger is alive by writing into the
    // very buffer this screen shows.
    LaunchedEffect(Unit) {
        MusiqueLog.i("Log", "log screen opened")
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.scrollToItem(lines.lastIndex)
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
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            TextButton(onClick = onBack) {
                Text("Back")
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    clipboard.setText(
                        AnnotatedString(lines.joinToString("\n") { it.format() }),
                    )
                },
            ) {
                Text("Copy")
            }
            TextButton(onClick = { viewModel.clearLogs() }) {
                Text("Clear")
            }
        }

        Text(
            text = "${lines.size} lines buffered",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        if (lines.isEmpty()) {
            Text(
                text = "No log lines yet. Search or play something, then come back.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            ) {
                items(lines) { line ->
                    Text(
                        text = line.format(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}
