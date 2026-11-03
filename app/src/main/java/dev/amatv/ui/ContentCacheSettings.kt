package dev.amatv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.amatv.R
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.integration.ContentCache
import dev.amatv.integration.ContentCacheStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ContentCacheSettings(cache: ContentCache, navigationFocus: FocusRequester? = null) {
    val context = LocalContext.current.applicationContext
    val settings by cache.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var storage by remember { mutableStateOf<ContentCacheStorage?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    val diskFocus = remember { FocusRequester() }
    val prefetchFocus = remember { FocusRequester() }
    val clearFocus = remember { FocusRequester() }
    LaunchedEffect(settings) {
        storage = withContext(Dispatchers.IO) { cache.storage(ArtworkImages.diskBytes(context)) }
    }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.browse_cache_title), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.CenterStart) {
        val current = storage
        if (current == null) AppleLoadingIndicator(Modifier.size(18.dp))
        else Text(
            stringResource(R.string.browse_cache_storage, storageSize(current.usedBytes), storageSize(current.availableBytes)),
            color = Color.White.copy(alpha = 0.62f), fontSize = 14.sp,
        )
    }
    if (storage?.storageLow == true) {
        Text(stringResource(R.string.browse_cache_storage_low), color = Color(0xFFFFC5A1), fontSize = 14.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CacheButton(
            label = stringResource(if (settings.enabled) R.string.browse_cache_disk_on else R.string.browse_cache_disk_off),
            onClick = { cache.setEnabled(!settings.enabled) },
            modifier = Modifier.width(240.dp).focusRequester(diskFocus).focusProperties {
                left = navigationFocus ?: FocusRequester.Cancel; right = prefetchFocus; down = clearFocus
            },
        )
        CacheButton(
            label = stringResource(if (settings.prefetchNextTwo) R.string.browse_cache_prefetch_on else R.string.browse_cache_prefetch_off),
            onClick = { cache.setPrefetch(!settings.prefetchNextTwo) },
            modifier = Modifier.width(360.dp).focusRequester(prefetchFocus).focusProperties {
                left = diskFocus; right = FocusRequester.Cancel; down = clearFocus
            },
        )
    }
    CacheButton(
        label = if (settings.clearing) stringResource(R.string.browse_cache_clearing) else stringResource(R.string.browse_cache_clear),
        onClick = {
            if (!settings.clearing) scope.launch {
                error = null
                try { cache.clear { ArtworkImages.clear(context) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = R.string.browse_cache_clear_error }
            }
        },
        modifier = Modifier.width(290.dp).focusRequester(clearFocus).focusProperties {
            up = diskFocus; left = navigationFocus ?: FocusRequester.Cancel; right = FocusRequester.Cancel; down = FocusRequester.Cancel
        },
    )
    error?.let { Text(stringResource(it), color = Color(0xFFFFB0BA), fontSize = 14.sp) }
}

@Composable
private fun CacheButton(label: String, onClick: () -> Unit, modifier: Modifier) {
    FocusSurface(
        onClick = onClick, modifier = modifier.height(46.dp), cornerRadius = 23.dp,
        background = Color.White.copy(alpha = 0.12f), focusedBackground = Color.White.copy(alpha = 0.28f),
        padding = PaddingValues(horizontal = 18.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun storageSize(bytes: Long): String = if (bytes >= 1024L * 1024 * 1024) {
    stringResource(R.string.browse_cache_gigabytes, bytes / (1024.0 * 1024 * 1024))
} else stringResource(R.string.browse_cache_megabytes, bytes / (1024 * 1024))
