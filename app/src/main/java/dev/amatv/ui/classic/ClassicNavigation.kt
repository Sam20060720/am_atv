package dev.amatv.ui.classic

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.i18n.destinationLabel
import dev.amatv.integration.CatalogPresentationCapabilities
import dev.amatv.integration.MusicConnectionState
import dev.amatv.model.Destination
import dev.amatv.ui.FocusMotionStyle
import dev.amatv.ui.FocusSurface
import dev.amatv.ui.LocalContentFocusEntry
import dev.amatv.ui.LocalFocusMotionStyle
import dev.amatv.ui.automationId

/** The original top navigation, backed by the current browse host and actions. */
@Composable
internal fun ClassicNavigation(
    selected: Destination,
    capabilities: CatalogPresentationCapabilities,
    hasCurrentTrack: Boolean,
    connectionState: MusicConnectionState,
    lowPerformanceMode: Boolean,
    navigationFocused: Boolean,
    menuRequester: FocusRequester,
    focusRequesters: Map<Destination, FocusRequester>,
    onNavigationFocused: () -> Unit,
    onDestination: (Destination) -> Unit,
    onEnterContent: () -> Unit,
    registerExpandedBackHandler: ((() -> Boolean)?) -> Unit,
    canFocusMenu: () -> Boolean = { true },
    modifier: Modifier = Modifier,
) {
    val primary = remember(capabilities) {
        buildList {
            add(Destination.Home)
            add(Destination.Browse)
            if (capabilities.supports(Destination.Sing)) add(Destination.Sing)
            if (capabilities.supports(Destination.Videos)) add(Destination.Videos)
            if (capabilities.supports(Destination.Radio)) add(Destination.Radio)
            add(Destination.Library)
            add(Destination.Search)
        }
    }
    val utility = remember(hasCurrentTrack) {
        buildList {
            if (hasCurrentTrack) add(Destination.NowPlaying)
            add(Destination.Account)
        }
    }
    val destinations = primary + utility
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var focusedDestination by remember { mutableStateOf<Destination?>(null) }
    var homeFocusRequested by remember { mutableStateOf(false) }
    val latestBackRegistration by rememberUpdatedState(registerExpandedBackHandler)
    val latestRequesters by rememberUpdatedState(focusRequesters)
    val expandedBack = remember {
        {
            if (focusedDestination == Destination.Home) false
            else {
                homeFocusRequested = true
                runCatching { latestRequesters.getValue(Destination.Home).requestFocus() }
                true
            }
        }
    }
    SideEffect { latestBackRegistration(expandedBack) }
    DisposableEffect(Unit) { onDispose { latestBackRegistration(null) } }
    LaunchedEffect(navigationFocused) {
        if (!navigationFocused) homeFocusRequested = false
    }
    LaunchedEffect(navigationFocused, selected, homeFocusRequested) {
        if (navigationFocused) {
            withFrameNanos { }
            val target = if (homeFocusRequested) Destination.Home else selected
            runCatching { focusRequesters.getValue(target).requestFocus() }
        }
    }

    CompositionLocalProvider(
        LocalContentFocusEntry provides null,
        // The selected page keeps its white fill. Actual remote focus inherits
        // the shared browse frame, including its visibility and blur cleanup.
        LocalFocusMotionStyle provides if (lowPerformanceMode) FocusMotionStyle.Immediate else FocusMotionStyle.Spring,
    ) {
        Row(
            modifier = modifier.fillMaxWidth()
                .padding(start = 40.dp, end = 40.dp, top = 24.dp)
                .automationId("classic-navigation")
                .focusProperties { onEnter = { if (!canFocusMenu()) cancelFocusChange() } }
                .focusGroup(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The pill keeps its original width when it fits. Scrolling only
            // constrains long translations or additional supported destinations.
            Row(
                Modifier.weight(1f, fill = false).padding(end = 16.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color.White.copy(alpha = 0.055f))
                    .horizontalScroll(rememberScrollState())
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                primary.forEach { destination ->
                    ClassicNavigationItem(destination, selected, connectionState, destinations,
                        isRtl, menuRequester, focusRequesters,
                        onFocused = {
                            focusedDestination = it
                            onNavigationFocused()
                        },
                        onFocusLost = {
                            if (focusedDestination == destination) focusedDestination = null
                        },
                        onDestination = onDestination, onEnterContent = onEnterContent,
                    )
                }
            }
            Row(
                Modifier.clip(RoundedCornerShape(28.dp))
                    .background(Color.White.copy(alpha = 0.055f)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                utility.forEach { destination ->
                    ClassicNavigationItem(destination, selected, connectionState, destinations,
                        isRtl, menuRequester, focusRequesters,
                        onFocused = {
                            focusedDestination = it
                            onNavigationFocused()
                        },
                        onFocusLost = {
                            if (focusedDestination == destination) focusedDestination = null
                        },
                        onDestination = onDestination, onEnterContent = onEnterContent,
                    )
                }
            }
        }
    }
}

@Composable
private fun ClassicNavigationItem(
    destination: Destination,
    selected: Destination,
    connectionState: MusicConnectionState,
    destinations: List<Destination>,
    isRtl: Boolean,
    menuRequester: FocusRequester,
    focusRequesters: Map<Destination, FocusRequester>,
    onFocused: (Destination) -> Unit,
    onFocusLost: () -> Unit,
    onDestination: (Destination) -> Unit,
    onEnterContent: () -> Unit,
) {
    val isSelected = destination == selected
    val index = destinations.indexOf(destination)
    val previous = destinations.getOrNull(index - 1)?.let(focusRequesters::getValue) ?: FocusRequester.Cancel
    val next = destinations.getOrNull(index + 1)?.let(focusRequesters::getValue) ?: FocusRequester.Cancel
    FocusSurface(
        onClick = { onDestination(destination) },
        modifier = Modifier.automationId("nav-${destination.name.lowercase()}")
            .focusRequester(focusRequesters.getValue(destination))
            .then(if (isSelected) Modifier.focusRequester(menuRequester) else Modifier)
            .onFocusChanged {
                if (it.isFocused) onFocused(destination) else onFocusLost()
            }
            .focusProperties {
                up = FocusRequester.Cancel
                down = FocusRequester.Cancel
                if (isRtl) { right = previous; left = next }
                else { left = previous; right = next }
            }
            .onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                if (key.keyCode != KeyEvent.KEYCODE_DPAD_DOWN) false
                else {
                    if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) onEnterContent()
                    true
                }
            }.height(36.dp),
        cornerRadius = 22.dp,
        focusedScale = 1.02f,
        focusedLift = 0.dp,
        focusedElevation = 0.dp,
        showBorder = false,
        padding = PaddingValues(horizontal = when (destination) {
            Destination.Search -> 12.dp
            Destination.NowPlaying, Destination.Account -> 14.dp
            else -> 16.dp
        }),
        background = if (isSelected) Color.White.copy(alpha = 0.9f) else Color.Transparent,
        focusedBackground = if (isSelected) Color.White.copy(alpha = 0.96f) else Color.White.copy(alpha = 0.13f),
        hoveredBackground = if (isSelected) Color.White.copy(alpha = 0.96f) else Color.White.copy(alpha = 0.13f),
    ) { focused ->
        val tint = if (isSelected) Color(0xFF17191A) else if (focused) Color.White else Color.White.copy(alpha = 0.65f)
        Box(Modifier.height(36.dp), contentAlignment = Alignment.Center) {
            if (destination == Destination.Search) {
                Icon(Icons.Rounded.Search, destinationLabel(destination), Modifier.size(20.dp), tint = tint)
            } else {
                val label = if (destination == Destination.Account) {
                    stringResource(if (connectionState == MusicConnectionState.SignedIn) R.string.app_account else R.string.app_sign_in)
                } else destinationLabel(destination)
                Text(label, color = tint, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
