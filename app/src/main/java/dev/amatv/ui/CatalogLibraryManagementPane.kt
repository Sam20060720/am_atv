package dev.amatv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.integration.*
import dev.amatv.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class ManagementPage { Actions, Name, Order, Entry, Folders, Delete }

/** Content of the existing action sheet: no second window or independent menu style. */
@Composable
internal fun CatalogLibraryManagementPane(
    item: Track, services: MusicServices, manager: CatalogLibraryManagement,
    firstRequester: FocusRequester, onChanged: () -> Unit, onDeleted: () -> Unit,
    onBusyChanged: (Boolean) -> Unit, onBackHandlerChanged: ((() -> Boolean)?) -> Unit, onCancel: () -> Unit,
) {
    key(item.resourceId, services.authorizationCacheScope()) {
        CatalogLibraryManagementContent(item, services, manager, firstRequester, onChanged, onDeleted,
            onBusyChanged, onBackHandlerChanged, onCancel)
    }
}

@Composable
private fun CatalogLibraryManagementContent(
    item: Track, services: MusicServices, manager: CatalogLibraryManagement,
    firstRequester: FocusRequester, onChanged: () -> Unit, onDeleted: () -> Unit,
    onBusyChanged: (Boolean) -> Unit, onBackHandlerChanged: ((() -> Boolean)?) -> Unit, onCancel: () -> Unit,
) {
    val menuMetrics = tvMenuMetrics()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    var page by remember { mutableStateOf(if (item.resourceType == "library-songs") ManagementPage.Delete else ManagementPage.Actions) }
    var metadata by remember { mutableStateOf<ManagedLibraryResource?>(null) }
    var snapshot by remember { mutableStateOf<ManagedPlaylistSnapshot?>(null) }
    var order by remember { mutableStateOf(emptyList<Int>()) }
    var selectedOccurrence by remember { mutableIntStateOf(-1) }
    var name by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf(false) }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    val loadGeneration = remember { longArrayOf(0L) }
    var pendingRead by remember { mutableStateOf<(suspend () -> Boolean)?>(null) }
    var pendingDone by remember { mutableStateOf<(() -> Unit)?>(null) }
    var focusedId by remember { mutableStateOf<String?>(null) }
    var lastFocusVisit by remember { mutableStateOf<String?>(null) }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val rootFolder = remember { Track("apple:p.playlistsroot", "", "", "", 0L, emptyList(), emptyList(),
        catalogKind = CatalogKind.Page, resourceId = "p.playlistsroot", resourceType = "library-playlist-folders") }
    var folders by remember { mutableStateOf(emptyList<Track>()) }
    var folderStack by remember { mutableStateOf(listOf(rootFolder)) }
    var folderNext by remember { mutableStateOf<String?>(null) }
    val folderPages = remember { mutableSetOf<String>() }
    fun cancelLoad() { loadGeneration[0]++; loadJob?.cancel(); loadJob = null; loading = false }
    fun navigate(target: ManagementPage) {
        cancelLoad()
        if (page == ManagementPage.Order && target != ManagementPage.Order) conflict = false
        page = target; focusedId = null; failed = false
    }
    fun loadMetadata() {
        cancelLoad(); val ticket = loadGeneration[0]; loading = true; failed = false
        loadJob = scope.launch {
            try { val loaded = manager.resource(item); if (ticket == loadGeneration[0]) metadata = loaded }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (ticket == loadGeneration[0]) failed = true }
            finally { if (ticket == loadGeneration[0]) loading = false }
        }
    }
    fun loadOrder() {
        navigate(ManagementPage.Order); val ticket = loadGeneration[0]; loading = true; conflict = false
        selectedOccurrence = -1; lastFocusVisit = null
        loadJob = scope.launch {
            try { val loaded = manager.playlist(item); if (ticket == loadGeneration[0]) {
                snapshot = loaded; order = loaded.entries.indices.toList()
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (ticket == loadGeneration[0]) failed = true }
            finally { if (ticket == loadGeneration[0]) loading = false }
        }
    }
    fun loadFolders(append: Boolean) {
        val folder = folderStack.last(); val cursor = if (append) folderNext else null
        if (append && (loading || cursor == null)) return
        cancelLoad(); val ticket = loadGeneration[0]; loading = true; failed = false
        if (!append) { folders = emptyList(); folderNext = null; folderPages.clear() }
        loadJob = scope.launch {
            try {
                val loaded = services.catalogPages.sectionPage(CatalogResourceRef(checkNotNull(folder.resourceId), "library-playlist-folders"),
                    CatalogResourceSection("children", resourceTypes = setOf("library-playlist-folders", "library-playlists"), pageSize = 100), cursor)
                if (ticket != loadGeneration[0]) return@launch
                check(folderPages.add(loaded.requestedPage) && loaded.nextPage !in folderPages)
                val children = loaded.items.filter { it.resourceType == "library-playlist-folders" && it.resourceId != item.resourceId }
                folders = if (append) mergeLibraryPage(folders, children) else children
                folderNext = loaded.nextPage
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (ticket == loadGeneration[0]) failed = true }
            finally { if (ticket == loadGeneration[0]) loading = false }
        }
    }
    fun back(): Boolean {
        if (busy) return true
        if (pendingRead != null) return false
        if (item.resourceType == "library-songs") { cancelLoad(); return false }
        if (page == ManagementPage.Actions) { cancelLoad(); return false }
        keyboard?.hide()
        if (page == ManagementPage.Folders && folderStack.size > 1) {
            folderStack = folderStack.dropLast(1); loadFolders(false)
        } else navigate(if (page == ManagementPage.Entry) ManagementPage.Order else ManagementPage.Actions)
        return true
    }
    fun write(check: suspend () -> Boolean, done: () -> Unit, operation: suspend () -> Boolean) {
        if (busy || pendingRead != null || conflict) return
        busy = true; failed = false; pendingRead = check; pendingDone = done
        scope.launch {
            try {
                if (operation()) { pendingRead = null; pendingDone = null; onChanged(); done() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: LibraryManagementUnconfirmed) { /* Only a read may resolve this operation. */ }
            catch (_: LibraryPlaylistConflict) { pendingRead = null; pendingDone = null; conflict = true }
            catch (_: Exception) { pendingRead = null; pendingDone = null; failed = true }
            finally { busy = false }
        }
    }
    fun rowModifier(id: String, first: Boolean = false): Modifier {
        val requester = if (first) firstRequester else requesters.getOrPut(id) { FocusRequester() }
        requesters[id] = requester
        return Modifier.automationId("library-management-$id").focusRequester(requester)
            .onFocusChanged { if (it.isFocused) focusedId = id }
    }
    LaunchedEffect(Unit) { loadMetadata() }
    val focusVisit = "$page:${if (page == ManagementPage.Folders) folderStack.last().resourceId else ""}"
    LaunchedEffect(focusVisit, loading, busy, pendingRead != null, failed, conflict) {
        if (loading || busy) return@LaunchedEffect
        val occurrenceRow = if (page == ManagementPage.Order && selectedOccurrence in order) "entry-$selectedOccurrence" else null
        if (lastFocusVisit != focusVisit) {
            val index = if (occurrenceRow != null) order.indexOf(selectedOccurrence) + 1 else 0
            listState.scrollToItem(index.coerceAtLeast(0)); lastFocusVisit = focusVisit
        }
        repeat(3) {
            withFrameNanos { }
            val target = requesters[focusedId ?: occurrenceRow] ?: firstRequester
            if (runCatching { target.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
        runCatching { firstRequester.requestFocus() }
    }
    SideEffect { onBusyChanged(busy); onBackHandlerChanged(::back) }
    DisposableEffect(Unit) { onDispose { onBusyChanged(false); onBackHandlerChanged(null) } }
    val caps = manager.capabilities
    val folder = item.resourceType == "library-playlist-folders"
    val song = item.resourceType == "library-songs"
    val editable = metadata != null && metadata?.id != "p.playlistsroot" &&
        if (folder) metadata?.canDelete != false else metadata?.canEdit != false
    val deletable = metadata != null && metadata?.canDelete != false && metadata?.id != "p.playlistsroot"
    val rootOptions = buildList {
        if (!song && editable && (if (folder) caps.updateFolderMetadata else caps.updatePlaylistMetadata))
            add(CatalogSheetAction("rename", stringResource(R.string.library_management_rename)) { name = metadata?.name.orEmpty(); navigate(ManagementPage.Name) })
        if (!song && editable && !folder && caps.replaceOrReorderPlaylistTracks)
            add(CatalogSheetAction("order", stringResource(R.string.library_management_order)) { loadOrder() })
        if (!song && deletable && if (folder) caps.movePlaylistFolders else caps.moveLibraryResources)
            add(CatalogSheetAction("move", stringResource(R.string.library_management_move)) {
                navigate(ManagementPage.Folders); folderStack = listOf(rootFolder); loadFolders(false)
            })
        if (!song && deletable && (if (folder) caps.deleteFolder else caps.deletePlaylist))
            add(CatalogSheetAction("delete", stringResource(if (folder) R.string.library_management_delete_folder else R.string.library_management_delete_playlist)) { navigate(ManagementPage.Delete) })
    }
    if (page == ManagementPage.Name && pendingRead == null) {
        Column(Modifier.fillMaxWidth().padding(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TvTextField(name, { name = it.take(200) }, rowModifier("name", true), enabled = !busy,
                singleLine = true, label = stringResource(R.string.library_management_name),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }))
            CatalogSheetRow(stringResource(R.string.library_management_save_name), rowModifier("save-name"),
                enabled = !busy && editable && name.trim().isNotEmpty() && name.trim() != metadata?.name, icon = Icons.Rounded.Check) {
                val target = name.trim(); keyboard?.hide()
                write({ manager.resource(item).name == target }, {
                    metadata = metadata?.copy(name = target); navigate(ManagementPage.Actions)
                }) { manager.rename(item, target) }
            }
            if (failed) Text(stringResource(R.string.library_management_failed), color = Color(0xFFFFB6B8), fontSize = 11.sp)
        }
        return
    }
    LazyColumn(state = listState, modifier = Modifier.requiredWidth(menuMetrics.width).heightIn(max = 280.dp)
        .automationId("library-management-list"), contentPadding = PaddingValues(horizontal = menuMetrics.padding, vertical = menuMetrics.viewportPadding),
        verticalArrangement = Arrangement.spacedBy(menuMetrics.rowSpacing)) {
        if (busy || loading && (page != ManagementPage.Folders || folders.isEmpty()))
            item("loading") { AppleLoadingIndicator(Modifier.padding(10.dp).size(16.dp)) }
        if (pendingRead != null) {
            item("pending") { Text(stringResource(R.string.library_management_pending), color = Color.White.copy(alpha = .65f),
                fontSize = 11.sp, modifier = Modifier.padding(10.dp)) }
            item("check") { CatalogSheetRow(stringResource(R.string.library_management_check), rowModifier("check", true),
                enabled = !busy, icon = Icons.Rounded.Refresh) {
                val check = pendingRead ?: return@CatalogSheetRow
                busy = true
                scope.launch {
                    try { if (check()) { val done = pendingDone; pendingRead = null; pendingDone = null; onChanged(); done?.invoke() } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed = true }
                    finally { busy = false }
                }
            } }
        } else if (conflict || failed && (page != ManagementPage.Folders || folders.isEmpty())) {
            item("error") { Text(stringResource(if (conflict) R.string.library_management_conflict else R.string.library_management_failed),
                color = Color(0xFFFFB6B8), fontSize = 11.sp, modifier = Modifier.padding(10.dp)) }
            item("retry") { CatalogSheetRow(stringResource(R.string.actions_retry), rowModifier("retry", true), icon = Icons.Rounded.Refresh) {
                when (page) { ManagementPage.Order -> loadOrder(); ManagementPage.Folders -> loadFolders(folderNext != null)
                    else -> { navigate(if (song) ManagementPage.Delete else ManagementPage.Actions); loadMetadata() } }
            } }
        } else if (!loading || page == ManagementPage.Folders && folders.isNotEmpty()) when (page) {
            ManagementPage.Actions -> {
                if (rootOptions.isEmpty()) item("read-only") { Text(stringResource(R.string.library_management_read_only),
                    color = Color.White.copy(alpha = .65f), fontSize = 11.sp, modifier = Modifier.padding(10.dp)) }
                itemsIndexed(rootOptions, key = { _, action -> action.id }) { index, action ->
                    CatalogSheetRow(action.label, rowModifier(action.id, index == 0), enabled = !busy, submenu = true, onClick = action.onClick)
                }
            }
            ManagementPage.Delete -> {
                item("warning") { Text(stringResource(if (song) R.string.library_management_remove_song_confirm else R.string.library_management_delete_confirm, metadata?.name.orEmpty()),
                    color = Color.White.copy(alpha = .8f), fontSize = 12.sp, modifier = Modifier.padding(10.dp)) }
                item("cancel") { CatalogSheetRow(stringResource(R.string.library_management_cancel), rowModifier("cancel", true), icon = Icons.Rounded.Close) {
                    if (song) onCancel() else navigate(ManagementPage.Actions)
                } }
                item("confirm-delete") { CatalogSheetRow(stringResource(if (song) R.string.library_management_remove_song else if (folder) R.string.library_management_delete_folder else R.string.library_management_delete_playlist),
                    rowModifier("confirm-delete"), enabled = deletable && !busy, icon = Icons.Rounded.Delete) {
                    var pending: PendingLibraryDeletion? = null
                    write({ pending?.let { manager.confirmDeletion(it) } == true }, onDeleted) {
                        val prepared = manager.prepareDeletion(item)
                        pending = prepared
                        manager.submitDeletion(prepared)
                        manager.confirmDeletion(prepared)
                    }
                } }
            }
            ManagementPage.Order -> {
                val original = snapshot
                if (original != null) {
                    item("save-order") { CatalogSheetRow(stringResource(R.string.library_management_save_order), rowModifier("save-order", true),
                        enabled = !busy && order != original.entries.indices.toList(), icon = Icons.Rounded.Check) {
                        val desiredOrder = order.toList(); val desired = desiredOrder.map(original.entries::get)
                        val identity = desired.map { it.type to it.id }
                        write({ manager.playlist(item).entries.map { it.type to it.id } == identity }, {
                            snapshot = original.copy(entries = desired); order = desired.indices.toList(); navigate(ManagementPage.Actions)
                        }) { manager.reorder(item, original, desiredOrder) }
                    } }
                    itemsIndexed(order, key = { _, occurrence -> occurrence }) { index, occurrence ->
                        val entry = original.entries[occurrence]
                        CatalogSheetRow(stringResource(R.string.library_management_entry, index + 1,
                            entry.title.ifBlank { stringResource(R.string.library_management_unavailable) }), rowModifier("entry-$occurrence"), submenu = true) {
                            selectedOccurrence = occurrence; navigate(ManagementPage.Entry)
                        }
                    }
                }
            }
            ManagementPage.Entry -> {
                val index = order.indexOf(selectedOccurrence)
                for ((position, delta) in listOf(-1, 1).withIndex()) item("move-entry-$delta") {
                    CatalogSheetRow(stringResource(if (delta < 0) R.string.actions_queue_move_up else R.string.actions_queue_move_down),
                        rowModifier("move-entry-$delta", position == 0), enabled = !busy && index >= 0 && index + delta in order.indices,
                        icon = if (delta < 0) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward) {
                        if (index >= 0 && index + delta in order.indices) order = order.toMutableList().apply { add(index + delta, removeAt(index)) }
                    }
                }
            }
            ManagementPage.Folders -> {
                val target = folderStack.last()
                item("move-here") { CatalogSheetRow(stringResource(R.string.library_management_move_here,
                    target.title.ifBlank { stringResource(R.string.library_management_root) }), rowModifier("move-here", true), icon = Icons.Rounded.Folder,
                    enabled = !busy && target.resourceId != item.resourceId) {
                    write({ manager.parent(item) == target.resourceId }, { navigate(ManagementPage.Actions) }) { manager.move(item, target) }
                } }
                itemsIndexed(folders, key = { _, value -> checkNotNull(value.resourceId) }) { _, targetFolder ->
                    CatalogSheetRow(targetFolder.title, rowModifier("folder-${targetFolder.resourceId}"), icon = Icons.Rounded.Folder, submenu = true) {
                        folderStack = folderStack + targetFolder; focusedId = null; loadFolders(false)
                    }
                }
                if (folderNext != null || failed) item("more-folders") {
                    CatalogSheetRow(stringResource(if (failed) R.string.actions_retry else R.string.actions_load_more),
                        rowModifier("more-folders"), enabled = !loading, icon = if (failed) Icons.Rounded.Refresh else Icons.Rounded.MoreHoriz) { loadFolders(true) }
                }
            }
            ManagementPage.Name -> Unit
        }
    }
}
