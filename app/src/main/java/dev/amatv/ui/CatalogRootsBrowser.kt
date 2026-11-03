package dev.amatv.ui

import dev.amatv.integration.CatalogPagesApi
import dev.amatv.integration.mergeLibraryPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A real catalog collection endpoint; it does not manufacture a resource ID for the root. */
internal class CatalogRootsBrowser(
    private val scope: CoroutineScope,
    private val api: CatalogPagesApi,
    val type: String = "genres",
) {
    init { require(type in setOf("genres", "station-genres")) }
    private val mutableState = MutableStateFlow(ArtistSectionState())
    val state = mutableState.asStateFlow()
    private var visible = false
    private var job: Job? = null
    private val seenPages = mutableSetOf<String>()

    fun ensure() {
        val state = mutableState.value
        if (!state.loaded && !state.loading && !state.failed) request(false)
    }
    fun more() = request(true)
    fun retry() = request(mutableState.value.loaded)
    fun reload() {
        cancel(); seenPages.clear()
        mutableState.value = mutableState.value.copy(loaded = false, next = null, failed = false)
        request(false)
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (value) ensure() else cancel()
    }
    fun close() { visible = false; cancel(); seenPages.clear() }

    private fun request(append: Boolean) {
        val before = mutableState.value
        if (!visible || before.loading || (append && before.next == null)) return
        mutableState.value = before.copy(loading = true, failed = false)
        job = scope.launch {
            try {
                val page = api.rootPage(type, if (append) before.next else null)
                currentCoroutineContext().ensureActive()
                if (!append) seenPages.clear()
                check(page.requestedPage !in seenPages && page.nextPage != page.requestedPage && page.nextPage !in seenPages)
                seenPages.add(page.requestedPage)
                mutableState.value = mutableState.value.copy(
                    items = if (append) mergeLibraryPage(before.items, page.items) else page.items,
                    next = page.nextPage, total = page.total ?: before.total, loaded = true, loading = false, failed = false,
                )
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                mutableState.value = mutableState.value.copy(loading = false, failed = true)
            }
            finally { if (job === currentCoroutineContext()[Job]) job = null }
        }
    }

    private fun cancel() {
        job?.cancel(); job = null
        mutableState.value = mutableState.value.copy(loading = false)
    }
}
