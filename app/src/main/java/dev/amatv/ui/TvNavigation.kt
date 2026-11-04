package dev.amatv.ui

import dev.amatv.integration.CatalogPresentationCapabilities
import android.view.KeyEvent
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.draw.dropShadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.GraphicsLayer
import android.text.format.DateFormat
import java.util.Date
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
import dev.amatv.R
import dev.amatv.i18n.destinationLabel
import dev.amatv.integration.MusicConnectionState
import dev.amatv.model.Destination
import kotlinx.coroutines.delay

/** TV 17 SidebarMenu: a page pill opens a floating panel, without moving the page.
 * Geometry is half the shipped 1920×1080 design coordinates. See official-evidence.md.
 */
@Composable
internal fun TvNavigation(
    selected: Destination,
    capabilities: CatalogPresentationCapabilities,
    expanded: Boolean,
    hasCurrentTrack: Boolean,
    connectionState: MusicConnectionState,
    lowPerformanceMode: Boolean,
    autoExpandOnFocus: Boolean,
    canFocusMenu: () -> Boolean = { true },
    menuRequester: FocusRequester,
    focusRequesters: Map<Destination, FocusRequester>,
    onExpandedChange: (Boolean) -> Unit,
    onDestination: (Destination) -> Unit,
    onEnterContent: () -> Unit,
    backdropLayer: GraphicsLayer? = null,
    registerExpandedBackHandler: ((() -> Boolean)?) -> Unit = {},
    onPanelVisibleChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val enterContentKey = if (isRtl) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
    val destinations = remember(hasCurrentTrack, capabilities) {
        buildList {
            add(Destination.Search)
            add(Destination.Home)
            add(Destination.Browse)
            if (capabilities.supports(Destination.Sing)) add(Destination.Sing)
            if (capabilities.supports(Destination.Videos)) add(Destination.Videos)
            if (capabilities.supports(Destination.Radio)) add(Destination.Radio)
            add(Destination.Library)
            if (hasCurrentTrack) add(Destination.NowPlaying)
        }
    }
    val panelHeight = (30f + 17f + destinations.size * 33f + (destinations.size - 1) * 5f + 24f).dp
    val density = LocalDensity.current
    val panelWidth = 179.dp
    var pillFocused by remember { mutableStateOf(false) }
    var focusedDestination by remember { mutableStateOf<Destination?>(null) }
    var homeFocusRequested by remember { mutableStateOf(false) }
    var panelVisible by remember { mutableStateOf(expanded) }
    val latestExpandedChange by rememberUpdatedState(onExpandedChange)
    val latestBackRegistration by rememberUpdatedState(registerExpandedBackHandler)
    val latestPanelVisibility by rememberUpdatedState(onPanelVisibleChange)
    // The host can receive Back before the expanded panel has obtained focus.
    // Keep that intent until close so the opening effect cannot restore selected.
    val expandedBack = remember {
        {
            if (focusedDestination == Destination.Home) false
            else {
                homeFocusRequested = true
                runCatching { focusRequesters.getValue(Destination.Home).requestFocus() }
                true
            }
        }
    }
    SideEffect {
        latestBackRegistration(expandedBack)
        latestPanelVisibility(expanded || panelVisible)
    }
    DisposableEffect(Unit) {
        onDispose { latestBackRegistration(null); latestPanelVisibility(false) }
    }
    val expansion = animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        // Luna spring(target, damping=25, stiffness=185, mass=1).
        animationSpec = if (lowPerformanceMode) androidx.compose.animation.core.snap()
            else spring(dampingRatio = 0.919f, stiffness = 185f),
        label = "sidebar-expansion",
        finishedListener = { value -> if (value == 0f) panelVisible = false },
    )
    val menuLabel = stringResource(R.string.browse_navigation_menu)
    val pageLabel = destinationLabel(selected)
    val titleStyle = TextStyle(
        fontSize = 14.5.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = LocalTextStyle.current.fontFamily,
    )
    val titleWidth = rememberTextMeasurer().measure(pageLabel, titleStyle).size.width
    val pillWidth = with(LocalDensity.current) { titleWidth.toDp() } + 69.dp
    val menuState = stringResource(
        if (expanded) R.string.browse_navigation_expanded else R.string.browse_navigation_collapsed,
    )
    LaunchedEffect(pillFocused, expanded, autoExpandOnFocus, selected) {
        if (pillFocused && !expanded && autoExpandOnFocus) {
            delay(200)
            latestExpandedChange(true)
        }
    }
    LaunchedEffect(expanded) {
        if (expanded) panelVisible = true
        if (!expanded) {
            homeFocusRequested = false
            focusedDestination = null
        }
    }
    LaunchedEffect(expanded, selected, homeFocusRequested) {
        if (expanded) {
            withFrameNanos { }
            val target = if (homeFocusRequested) Destination.Home else selected
            runCatching { focusRequesters.getValue(target).requestFocus() }
        }
    }
    // The page stays mounted. Only this overlay draws while the spring moves.
    Box(modifier.fillMaxSize().focusProperties {
        // Check at the actual focus transaction, including direct requestFocus
        // calls from a content row. A target's cached canFocus property alone
        // cannot enforce this per-input boundary before recomposition.
        onEnter = { if (!canFocusMenu()) cancelFocusChange() }
    }.focusGroup()) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.Black.copy(alpha = 0.08f * expansion.value))
        }
        // SidebarMenu's panel shadow is separate from its material. Keep a
        // stable mask and transform its layer while the panel opens.
        Box(Modifier.align(if (isRtl) Alignment.TopEnd else Alignment.TopStart).width(panelWidth).height(panelHeight).graphicsLayer {
            // This layer draws only the shadow. Modulation preserves its
            // outsets while alpha changes, avoiding Auto's clipped buffer.
            compositingStrategy = CompositingStrategy.ModulateAlpha
            val p = expansion.value.coerceIn(0f, 1f)
            val inset = (29.5f - 12f * p).dp.toPx()
            val width = (pillWidth.value + (panelWidth.value - pillWidth.value) * p).dp.toPx()
            scaleX = width / panelWidth.toPx()
            scaleY = (30f + (panelHeight.value - 30f) * p) / panelHeight.value
            translationX = if (isRtl) panelWidth.toPx() - width - inset else inset
            translationY = inset
            alpha = p
            transformOrigin = TransformOrigin(0f, 0f)
        }.dropShadow(RoundedCornerShape(26.dp)) {
            radius = with(density) { 24.dp.toPx() }
            offset = Offset(0f, with(density) { 2.dp.toPx() })
            color = Color.Black
            alpha = 0.4f
        })
        if (backdropLayer != null && Build.VERSION.SDK_INT >= 31) {
            FrostedPanel(
                source = backdropLayer,
                expandedBounds = with(density) {
                    val x = if (isRtl) backdropLayer.size.width - (17.5.dp + panelWidth).toPx() else 17.5.dp.toPx()
                    Rect(x, 17.5.dp.toPx(), x + panelWidth.toPx(), (17.5.dp + panelHeight).toPx())
                },
                animatedBounds = {
                    with(density) {
                        val p = expansion.value.coerceIn(0f, 1f)
                        val inset = (29.5f - 12f * p).dp.toPx()
                        val w = (pillWidth.value + (panelWidth.value - pillWidth.value) * p).dp.toPx()
                        val h = (30f + (panelHeight.value - 30f) * p).dp.toPx()
                        val x = if (isRtl) backdropLayer.size.width - inset - w else inset
                        Rect(x, inset, x + w, inset + h)
                    }
                },
                animatedRadius = { with(density) { (15f + 11f * expansion.value.coerceIn(0f, 1f)).dp.toPx() } },
                opacity = { expansion.value.coerceIn(0f, 1f) },
            )
        }
        Box(
            Modifier.fillMaxSize().drawWithCache {
                onDrawBehind {
                    val p = expansion.value.coerceIn(0f, 1f)
                    // Keep both endpoint tints and blend continuously; mounting
                    // or removing the blur must not switch the panel's color.
                    val material = !lowPerformanceMode && Build.VERSION.SDK_INT >= 31
                    val panel = Color(0xFF2C2C2E).copy(alpha = if (material) 1f - 0.28f * p else 1f)
                    val inset = (29.5f - 12f * p).dp.toPx()
                    val width = (pillWidth.value + (panelWidth.value - pillWidth.value) * p).dp.toPx()
                    val height = (30f + (panelHeight.value - 30f) * p).dp.toPx()
                    val radius = (15f + 11f * p).dp.toPx()
                    val origin = Offset(if (isRtl) size.width - inset - width else inset, inset)
                    val bounds = Size(width, height)
                    drawRoundRect(panel, topLeft = origin, size = bounds, cornerRadius = CornerRadius(radius), alpha = 0.45f + 0.55f * p)
                    drawRoundRect(
                        Color.White.copy(alpha = 0.08f), topLeft = origin, size = bounds,
                        cornerRadius = CornerRadius(radius), style = Stroke(0.5.dp.toPx()),
                    )
                }
            },
        )
        CompositionLocalProvider(
            LocalContentFocusEntry provides null,
            LocalFocusFrameEnabled provides false,
            LocalFocusMotionStyle provides if (lowPerformanceMode) FocusMotionStyle.Immediate else FocusMotionStyle.Television,
        ) {
            FocusSurface(
                onClick = { onExpandedChange(true) },
                modifier = Modifier
                    .padding(start = 29.5.dp, top = 29.5.dp)
                    .width(pillWidth).height(30.dp)
                    .automationId("nav-menu")
                    .semantics {
                        contentDescription = menuLabel
                        stateDescription = menuState
                    }
                    .focusRequester(menuRequester)
                    .focusProperties { canFocus = !expanded }
                    .onFocusChanged { pillFocused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) false
                        else when (event.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_DOWN -> { onExpandedChange(true); true }
                            enterContentKey -> { onEnterContent(); true }
                            else -> false
                        }
                    }
                    .graphicsLayer { alpha = 1f - expansion.value.coerceIn(0f, 1f) },
                cornerRadius = 15.dp,
                focusedScale = 1f,
                showBorder = false,
                background = Color.Transparent,
                focusedBackground = Color.White.copy(alpha = 0.12f),
                hoveredBackground = Color.White.copy(alpha = 0.06f),
                padding = PaddingValues(horizontal = 12.dp),
            ) { focused ->
                Row(
                    Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(Icons.Rounded.ChevronLeft, null, Modifier.size(12.dp).graphicsLayer { scaleX = if (isRtl) -1f else 1f }, tint = Color.White.copy(alpha = 0.7f))
                    Icon(destinationIcon(selected), null, Modifier.size(17.dp), tint = Color.White)
                    Text(
                        pageLabel, Modifier.weight(1f), color = Color.White,
                        fontSize = 14.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (expanded || panelVisible) {
                CompositionLocalProvider(LocalTelevisionFocusTimings provides TelevisionFocusTimings(0, 0),
                    LocalFocusTargetsEnabled provides (LocalFocusTargetsEnabled.current && expanded)) {
                Column(
                    Modifier
                        .padding(start = 27.dp, top = 28.5.dp)
                        .width(160.dp).height(panelHeight - 22.dp)
                        .automationId("nav-panel")
                        .graphicsLayer {
                            alpha = expansion.value.coerceIn(0f, 1f)
                            translationX = (1f - expansion.value) * (if (isRtl) 8.dp.toPx() else -8.dp.toPx())
                        }
                        .drawWithCache {
                            val outline = Path()
                            onDrawWithContent {
                                // The contents stay mounted for the closing
                                // spring, but must stay inside its shrinking
                                // platter instead of fading across the page.
                                val p = expansion.value.coerceIn(0f, 1f)
                                val inset = (29.5f - 12f * p).dp.toPx()
                                val width = (pillWidth.value + (panelWidth.value - pillWidth.value) * p).dp.toPx()
                                val height = (30f + (panelHeight.value - 30f) * p).dp.toPx()
                                val shift = (1f - p) * (if (isRtl) 8.dp.toPx() else -8.dp.toPx())
                                val left = if (isRtl) (27.dp + 160.dp).toPx() - inset - width - shift
                                    else inset - 27.dp.toPx() - shift
                                val top = inset - 28.5.dp.toPx()
                                outline.reset()
                                outline.addRoundRect(RoundRect(Rect(left, top, left + width, top + height),
                                    CornerRadius((15f + 11f * p).dp.toPx())))
                                clipPath(outline) { this@onDrawWithContent.drawContent() }
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            val key = event.nativeKeyEvent
                            when {
                                key.action == KeyEvent.ACTION_DOWN && key.keyCode == enterContentKey -> { onEnterContent(); true }
                                else -> false
                            }
                        },
                ) {
                    val context = LocalContext.current
                    var clockLabel by remember { mutableStateOf(DateFormat.getTimeFormat(context).format(Date())) }
                    LaunchedEffect(context) {
                        while (true) {
                            clockLabel = DateFormat.getTimeFormat(context).format(Date())
                            delay(60_000L)
                        }
                    }
                    FocusSurface(
                        onClick = { onDestination(Destination.Account) },
                        modifier = Modifier.fillMaxWidth().height(30.dp)
                            .automationId("nav-account")
                            .focusRequester(focusRequesters.getValue(Destination.Account))
                            .onFocusChanged {
                                if (it.isFocused) focusedDestination = Destination.Account
                                else if (focusedDestination == Destination.Account) focusedDestination = null
                            }
                            .focusProperties {
                                up = FocusRequester.Cancel
                                if (isRtl) right = FocusRequester.Cancel else left = FocusRequester.Cancel
                                down = focusRequesters.getValue(Destination.Search)
                            },
                        cornerRadius = 15.dp, focusedScale = 1f,
                        background = Color.Transparent,
                        focusedBackground = Color.White.copy(alpha = 0.22f),
                        hoveredBackground = Color.White.copy(alpha = 0.10f),
                        padding = PaddingValues(horizontal = 7.dp),
                    ) {
                        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.Person, null, Modifier.size(23.dp), Color.White.copy(alpha = 0.8f))
                            Text(stringResource(if (connectionState == MusicConnectionState.SignedIn)
                                R.string.app_account else R.string.app_sign_in),
                                color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp,
                                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(clockLabel, color = Color.White.copy(alpha = 0.55f), fontSize = 9.sp, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(17.dp))
                    destinations.forEachIndexed { index, destination ->
                        val current = destination == selected
                        FocusSurface(
                            onClick = { onDestination(destination) },
                            modifier = Modifier.fillMaxWidth().height(33.dp)
                                .automationId("nav-${destination.name.lowercase()}")
                                .focusRequester(focusRequesters.getValue(destination))
                                .onFocusChanged {
                                    if (it.isFocused) focusedDestination = destination
                                    else if (focusedDestination == destination) focusedDestination = null
                                }
                                .focusProperties {
                                    if (isRtl) right = FocusRequester.Cancel else left = FocusRequester.Cancel
                                    if (index == 0) up = focusRequesters.getValue(Destination.Account)
                                    else up = focusRequesters.getValue(destinations[index - 1])
                                    if (index == destinations.lastIndex) down = FocusRequester.Cancel
                                    else down = focusRequesters.getValue(destinations[index + 1])
                                },
                            cornerRadius = 16.5.dp, focusedScale = 1f,
                            background = if (current) Color.White.copy(alpha = 0.10f) else Color.Transparent,
                            focusedBackground = Color.White.copy(alpha = 0.90f),
                            hoveredBackground = Color.White.copy(alpha = 0.12f),
                            padding = PaddingValues(horizontal = 13.dp),
                        ) { _ ->
                            val tint = focusSurfaceContentColor(Color.White.copy(alpha = 0.8f), Color(0xFF202022))
                            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(destinationIcon(destination), null, Modifier.size(18.dp), tint)
                                Text(
                                    destinationLabel(destination),
                                    color = tint, fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (index != destinations.lastIndex) Spacer(Modifier.height(5.dp))
                    }

                }
                }
            }
        }
    }
}

private fun destinationIcon(destination: Destination): ImageVector = when (destination) {
    Destination.Home -> Icons.Rounded.Home
    Destination.Browse -> Icons.Rounded.GridView
    Destination.Sing -> Icons.Rounded.Mic
    Destination.Videos -> Icons.Rounded.OndemandVideo
    Destination.Radio -> Icons.Rounded.Podcasts
    Destination.Library -> Icons.Rounded.LibraryMusic
    Destination.Search -> Icons.Rounded.Search
    Destination.NowPlaying -> Icons.Rounded.PlayCircle
    else -> Icons.Rounded.Person
}
