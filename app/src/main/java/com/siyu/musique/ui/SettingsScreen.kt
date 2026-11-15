package com.siyu.musique.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siyu.musique.ui.theme.MusiqueIcons
import java.util.Locale

/**
 * Settings tab: the cache size (audio + covers) with a clear
 * button, and the debug log entry. No title, no back chevron:
 * it is a top-level tab. The size is measured on every entry;
 * clearing re-measures so the freed size shows at once. The
 * shared mini player bar and the tab bar sit below this screen,
 * owned by the app shell.
 */
@Composable
fun SettingsScreen(
    viewModel: SearchViewModel,
    onOpenLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cacheSizeBytes by viewModel.cacheSizeBytes.collectAsStateWithLifecycle()
    val clearingCache by viewModel.clearingCache.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadCacheSize()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Cache",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = cacheSizeBytes?.let { formatCacheSize(it) } ?: "…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(
                onClick = { viewModel.clearCache() },
                enabled = !clearingCache && (cacheSizeBytes ?: 0L) > 0L,
            ) {
                Icon(
                    imageVector = MusiqueIcons.Delete,
                    contentDescription = "Clear cache",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenLog)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Debug log",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = MusiqueIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Human cache size: bytes, KB, or MB with one decimal. */
private fun formatCacheSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
