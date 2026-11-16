package com.siyu.musique.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siyu.musique.R
import com.siyu.musique.core.logging.MusiqueLog
import com.siyu.musique.ui.theme.MusiqueIcons

/**
 * Debug log screen: the last ~300 log lines, oldest first,
 * monospace, auto-scrolled to the bottom. The copy icon puts every
 * line on the clipboard; the trash icon drops the buffer. The same
 * lines go to logcat under the tag "Musique". Log lines never
 * contain full play URLs, cookies, tickets, or keys: hosts, ids,
 * counts, and error kinds only.
 *
 * The screen reads the shared logger's buffer directly, with no
 * ViewModel indirection, and writes one line on every entry, so a
 * working logger can never show a blank screen; the header always
 * shows the buffered count, so an empty buffer reads as "0 lines
 * buffered" instead of a blank screen.
 *
 * The lines render in a plain vertically scrollable Column, not a
 * LazyColumn: on one device the LazyColumn showed nothing while the
 * header reported buffered lines, and 300 lines max is cheap enough
 * for a debug screen.
 */
@Composable
fun LogScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lines by MusiqueLog.shared.buffer.snapshot.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    // Once per entry: prove the logger is alive by writing into the
    // very buffer this screen shows.
    LaunchedEffect(Unit) {
        MusiqueLog.i("Log", "log screen opened")
    }

    LaunchedEffect(lines.size) {
        scrollState.scrollTo(Int.MAX_VALUE)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // Opaque: during the predictive back gesture the
            // outgoing page slides over the incoming one, and a
            // transparent page would show both at once.
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MusiqueIcons.ChevronLeft,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = {
                    clipboard.setText(
                        AnnotatedString(lines.joinToString("\n") { it.format() }),
                    )
                },
            ) {
                Icon(
                    imageVector = MusiqueIcons.ContentCopy,
                    contentDescription = stringResource(R.string.copy_log),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = { viewModel.clearLogs() }) {
                Icon(
                    imageVector = MusiqueIcons.Delete,
                    contentDescription = stringResource(R.string.clear_log),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Text(
            text = pluralStringResource(R.plurals.lines_buffered, lines.size, lines.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        if (lines.isEmpty()) {
            Text(
                text = stringResource(R.string.no_log_lines),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp),
            ) {
                lines.forEach { line ->
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
