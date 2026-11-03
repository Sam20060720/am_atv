package dev.amatv.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import dev.amatv.integration.CatalogPresentationCapabilities
import dev.amatv.integration.NativeMediaCapabilities
import dev.amatv.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Benchmark APK only: real Library/resource composables, deterministic local rows, no account or writes. */
class LibraryFixtureActivity : ComponentActivity() {
    private fun row(id: String, title: String, kind: CatalogKind, type: String) = Track(
        "apple:$id", title, "Fixture artist", "Fixture album", 180_000L,
        // Real AppleMusicApi rows always supply a valid fallback gradient,
        // even without artwork. Preserve that renderer input contract here.
        listOf(Color(0xFF38104E), Color(0xFFA41E62), Color(0xFFE17665)), emptyList(),
        catalogKind = kind, resourceId = id, resourceType = type,
    )

    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val folders = listOf(
            row("p.fixture-z", "Zulu Folder", CatalogKind.Page, "library-playlist-folders"),
            row("p.fixture-a", "Alpha Empty Folder", CatalogKind.Page, "library-playlist-folders"),
            row("p.fixture-list", "Middle Playlist", CatalogKind.Playlist, "library-playlists"),
        )
        val songs = (0 until 2600).reversed().map { index ->
            val title = "${('A'.code + index / 100).toChar()} ${if (index == 1250) "Needle" else "Song"} ${index.toString().padStart(4, '0')}"
            row("i.fixture-$index", title, CatalogKind.Song, "library-songs")
        }
        val data = mapOf(
            LibrarySection.Recent.shelfId to songs.take(12),
            LibrarySection.Songs.shelfId to songs,
            LibrarySection.Albums.shelfId to songs.take(1200).mapIndexed { index, track ->
                track.copy(id = "apple:l.fixture-$index", resourceId = "l.fixture-$index", resourceType = "library-albums", catalogKind = CatalogKind.Album)
            },
            LibrarySection.Artists.shelfId to emptyList(),
            LibrarySection.Playlists.shelfId to folders,
        )
        val delayMillis = intent.getLongExtra("page_delay_ms", 30L).coerceIn(0, 1000)
        setContentView(ComposeView(this).apply { setContent { AmAtvTheme {
            val scope = rememberCoroutineScope()
            var shelves by remember { mutableStateOf<List<ContentShelf>>(emptyList()) }
            var pages by remember { mutableStateOf<Map<String, LibraryShelfPagination>>(emptyMap()) }
            val jobs = remember { mutableMapOf<String, Job>() }
            var reads by remember { mutableIntStateOf(0) }
            var opened by remember { mutableStateOf("") }
            var menuTarget by remember { mutableStateOf("") }
            var visits by remember { mutableStateOf<List<Track>>(emptyList()) }
            val saveable = rememberSaveableStateHolder()
            val first = remember { FocusRequester() }
            val menu = remember { FocusRequester() }
            fun load(id: String) {
                val existing = shelves.firstOrNull { it.id == id }
                val all = data[id] ?: return
                if (jobs[id]?.isActive == true || (existing != null && existing.items.size == all.size)) return
                val oldPage = pages[id] ?: LibraryShelfPagination(hasMore = true)
                pages = pages + (id to oldPage.copy(loading = true))
                reads++
                jobs[id] = scope.launch {
                    delay(delayMillis)
                    val count = ((existing?.items?.size ?: 0) + 100).coerceAtMost(all.size)
                    val items = all.take(count)
                    shelves = shelves.filterNot { it.id == id } + ContentShelf(id, id, items)
                    pages = pages + (id to LibraryShelfPagination(count < all.size, total = all.size, loadedPages = oldPage.loadedPages + 1))
                }
            }
            fun open(item: Track) {
                opened = item.resourceId.orEmpty()
                if (item.catalogKind == CatalogKind.Page) visits = visits + item
            }
            BackHandler(visits.isNotEmpty()) { visits = visits.dropLast(1) }
            val current = visits.lastOrNull()
            CompositionLocalProvider(LocalCatalogActionMenu provides { item -> menuTarget = libraryResourceKey(item) }) {
                Column(Modifier.fillMaxSize().background(Color(0xFF15151B))) {
                    Row(Modifier.fillMaxWidth().height(32.dp)) {
                        FocusSurface({ if (visits.isNotEmpty()) visits = visits.dropLast(1) },
                            Modifier.width(70.dp).height(30.dp).focusRequester(menu).automationId("library-fixture-menu")) {
                            Text("Back", color = Color.White)
                        }
                        Text(JSONObject().put("fixture", "local-library-2600").put("reads", reads)
                            .put("songs", shelves.firstOrNull { it.id == LibrarySection.Songs.shelfId }?.items?.size ?: 0)
                            .put("opened", opened).put("menu", menuTarget).put("depth", visits.size)
                            .put("page", current?.resourceId.orEmpty())
                            .put("ime_visible", WindowInsets.isImeVisible).toString(),
                            color = Color.White, modifier = Modifier.automationId("library-fixture-report"))
                    }
                    Box(Modifier.weight(1f)) {
                        if (current == null) saveable.SaveableStateProvider("library") {
                            LibraryScreen(shelves, pages, ::load, ::load, { item, _ -> open(item) }, first, menu)
                        } else key(current.resourceId) {
                            val browser = remember {
                                CatalogPageBrowser(scope, current, { ref ->
                                    delay(120)
                                    CatalogResourceProfile(ref, current, current.title, null, null, null, null, null,
                                        emptyList(), listOf(CatalogResourceSection("children", resourceTypes = setOf("library-playlist-folders", "library-playlists"))))
                                }, { ref, _, _ ->
                                    delay(350)
                                    val items = when (ref.id) {
                                        "p.fixture-z" -> listOf(row("p.fixture-nested", "Nested Folder", CatalogKind.Page, "library-playlist-folders"))
                                        "p.fixture-nested" -> listOf(row("p.fixture-leaf", "Leaf Playlist", CatalogKind.Playlist, "library-playlists"))
                                        else -> emptyList()
                                    }
                                    CatalogResourcePage(items, null, "/fixture/${ref.id}/children", items.size)
                                })
                            }
                            DisposableEffect(browser) { onDispose { browser.close() } }
                            saveable.SaveableStateProvider("folder:${current.resourceId}") {
                                CatalogResourceScreen(current, browser, true, first, menu,
                                    CatalogPresentationCapabilities(NativeMediaCapabilities()), { item, _ -> open(item) }, {})
                            }
                        }
                    }
                }
            }
            LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
        } } })
    }
}
