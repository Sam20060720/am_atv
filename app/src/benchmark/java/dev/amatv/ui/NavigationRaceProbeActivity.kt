package dev.amatv.ui

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.amatv.integration.*
import dev.amatv.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Benchmark-only real screens/controllers with tester-owned loopback HTTP responses. */
class NavigationRaceProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(intent.getBooleanExtra("allow_navigation_fixture", false))
        val endpoint = URI(checkNotNull(intent.getStringExtra("fixture_url")))
        require(endpoint.scheme == "http" && endpoint.host == "127.0.0.1" && endpoint.port in 1024..65535 &&
            endpoint.rawPath.isNullOrEmpty() && endpoint.rawQuery == null && endpoint.rawUserInfo == null)
        val scenario = checkNotNull(intent.getStringExtra("scenario"))
        require(scenario in setOf("insights", "resource", "resource-modal", "search", "collection", "home", "home-resource", "radio"))
        setContent { AmAtvTheme { FixtureScreen(scenario, endpoint.toString()) } }
    }

    @Composable private fun FixtureScreen(scenario: String, endpoint: String) {
        val scope = rememberCoroutineScope()
        val parser = remember { AppleMusicApi("us", DeveloperTokenClient("")) { null } }
        val first = remember { FocusRequester() }
        val menu = remember { FocusRequester() }
        val contentEntry = remember { ContentFocusEntry() }
        val capabilities = remember { CatalogPresentationCapabilities(NativeMediaCapabilities()) }
        var closed by remember { mutableStateOf(false) }
        var modal by remember { mutableStateOf(false) }
        var homeItems by remember { mutableStateOf<List<Track>>(emptyList()) }
        var homeTarget by remember { mutableStateOf<String?>(null) }
        val homeRetention = remember { HomeShelfRetention() }
        val insights = remember {
            ListeningInsightsBrowser(scope, AccountInsightsApi({ "us" }, { path, _ -> fetch(endpoint, path) },
                parser::parseResourceItem, Dispatchers.Default))
        }
        val pages = remember {
            CatalogPagesApi({ "us" }, { false }, { path, _ -> fetch(endpoint, path) },
                parser::parseResourceItem, Dispatchers.Default)
        }
        val source = remember {
            checkNotNull(parser.parseResourceItem(JSONObject(if (scenario == "collection")
                """{"id":"fixture-album","type":"albums","attributes":{"name":"Fixture album","artistName":"Fixture"}}"""
                else """{"id":"fixture-resource","type":"apple-curators","attributes":{"name":"Fixture resource"}}""")))
        }
        val resource = remember {
            if (scenario.startsWith("resource")) CatalogPageBrowser(scope, source, pages::profile, pages::sectionPage) else null
        }
        val search = remember {
            SearchBrowser(scope, { _, _, _, _ -> page(fetch(endpoint, "/search"), parser) }, { CatalogSearchSuggestions() })
        }
        val collection = remember {
            CollectionBrowser(scope, { _, _ -> page(fetch(endpoint, "/collection"), parser) })
        }
        DisposableEffect(Unit) { onDispose { insights.close(); resource?.close(); search.dispose(); collection.close() } }
        LaunchedEffect(closed) {
            if (closed) { insights.close(); resource?.close(); search.dispose(); collection.close() }
            else when (scenario) {
                "insights" -> insights.setVisible(true)
                "search" -> search.submit("navigation fixture")
                "collection" -> collection.open(source)
                "home", "home-resource", "radio" -> homeItems = page(fetch(endpoint, "/home"), parser).items
            }
        }
        LaunchedEffect(closed, scenario) {
            repeat(8) {
                withFrameNanos { }
                if (runCatching { (if (closed) menu else first).requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            }
        }
        BackHandler(enabled = !closed && !modal && homeTarget == null) { closed = true }
        BackHandler(enabled = homeTarget != null) {
            homeTarget = null
            scope.launch {
                repeat(8) {
                    withFrameNanos { }
                    if (contentEntry.requestFocus(first)) return@launch
                }
            }
        }
        Column(Modifier.fillMaxSize().background(Color(0xFF111113)).automationId("navigation-fixture-root")
            .onPreviewKeyEvent { event ->
                if (scenario == "resource-modal" && !closed && !modal && event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU) {
                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) modal = true
                    true
                } else false
            }.padding(20.dp)) {
            FocusSurface({}, Modifier.focusRequester(menu).automationId(if (closed) "fixture-closed" else "fixture-menu")) {
                Text(if (closed) "Fixture closed" else "Fixture menu", color = Color.White)
            }
            Spacer(Modifier.height(12.dp))
            if (!closed) Box(Modifier.weight(1f).fillMaxWidth()) {
                CompositionLocalProvider(LocalContentFocusEntry provides contentEntry,
                    LocalFocusTargetsEnabled provides !modal, LocalFocusFrameEnabled provides false,
                    LocalBrowseContentVisible provides true, LocalBrowseMotionEnabled provides false) {
                    when (scenario) {
                        "insights" -> ListeningInsightsScreen(insights, true, first, menu, capabilities,
                            onBack = { closed = true }, onOpen = { _, _ -> error("Fixture cannot open media") })
                        "resource", "resource-modal" -> CatalogResourceScreen(source, checkNotNull(resource), true,
                            first, menu, capabilities, onOpen = { _, _ -> error("Fixture cannot open media") },
                            onOpenLink = { error("Fixture cannot open resources") })
                        "search" -> CatalogSearchScreen(search, emptyList(), false, capabilities, first, menu,
                            onOpen = { _, _ -> error("Fixture cannot open media") }, onSignIn = {})
                        "collection" -> {
                            val state by collection.state.collectAsState()
                            CollectionScreen(state, source, false, first, menu,
                                onRetry = collection::retry, onLoadMore = collection::more,
                                onPlay = { _, _ -> error("Fixture cannot play media") })
                        }
                        "home", "home-resource", "radio" -> Box(Modifier.fillMaxSize()) {
                            val shelfId = when (scenario) {
                                "home" -> "recent-tracks:recent-tracks"
                                "home-resource" -> "fixture-public"
                                else -> "fixture-radio"
                            }
                            CompositionLocalProvider(LocalHomeShelfRetention provides homeRetention,
                                LocalFocusTargetsEnabled provides (homeTarget == null)) {
                                HomeScreen(if (scenario == "radio") Destination.Radio else Destination.Home, source,
                                    homeShelves = listOf(ContentShelf(shelfId, "Fixture shelf", homeItems,
                                        hasMore = scenario == "home",
                                        seeAll = source.takeIf { scenario == "home-resource" })),
                                    connectionState = MusicConnectionState.SignedIn,
                                    onOpenInsights = { homeTarget = it.source.name },
                                    onOpenGenres = { homeTarget = "StationGenres" },
                                    onOpenTrack = { item, _ ->
                                        check(item.resourceId == "fixture-resource" && item.resourceType == "apple-curators")
                                        homeTarget = "Resource"
                                    }, firstContentFocusRequester = first, navigationFocusRequester = menu,
                                    modifier = Modifier.then(if (homeTarget != null) Modifier.clearAndSetSemantics {} else Modifier)
                                        .graphicsLayer { alpha = if (homeTarget == null) 1f else 0f },
                                    searchContent = { _, _ -> })
                            }
                            if (homeTarget != null) CompositionLocalProvider(LocalContentFocusEntry provides null) {
                                val targetFocus = remember { FocusRequester() }
                                LaunchedEffect(Unit) { withFrameNanos { }; targetFocus.requestFocus() }
                                FocusSurface({}, Modifier.focusRequester(targetFocus).automationId("fixture-home-target")) {
                                    Text(checkNotNull(homeTarget), color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (modal) Dialog(onDismissRequest = {
            modal = false
            scope.launch { withFrameNanos { }; runCatching { menu.requestFocus() } }
        }) {
            val modalFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { withFrameNanos { }; modalFocus.requestFocus() }
            FocusSurface({}, Modifier.background(Color.DarkGray).focusRequester(modalFocus).automationId("fixture-modal-action")) {
                Text("Fixture modal", color = Color.White)
            }
        }
    }

    private suspend fun fetch(endpoint: String, path: String): JSONObject = withContext(Dispatchers.IO) {
        require(path.startsWith('/') && !path.startsWith("//"))
        val connection = URL(endpoint + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 2_000
            connection.readTimeout = 10_000
            check(connection.responseCode == 200)
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    private fun page(root: JSONObject, parser: AppleMusicApi): LibraryPage {
        val data = root.getJSONArray("data")
        return LibraryPage((0 until data.length()).mapNotNull { parser.parseResourceItem(data.getJSONObject(it)) },
            null, "/fixture", data.length())
    }
}
