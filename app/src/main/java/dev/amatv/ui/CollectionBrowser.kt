package dev.amatv.ui

import dev.amatv.R
import dev.amatv.integration.LibraryPage
import dev.amatv.integration.CollectionShuffleSample
import dev.amatv.model.CatalogKind
import dev.amatv.model.CatalogOpenAction
import dev.amatv.model.CatalogResourceRef
import dev.amatv.model.Track
import dev.amatv.model.openAction
import dev.amatv.playback.CollectionPlaybackContinuation
import dev.amatv.playback.CollectionPlaybackRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

internal data class CollectionBrowserState(
    val collection: Track? = null,
    val tracks: List<Track> = emptyList(),
    val loading: Boolean = false,
    val error: Int? = null,
    val artists: List<Track> = emptyList(),
    val next: String? = null,
    /** Server relationship total, when returned; it can include non-song resources. */
    val total: Int? = null,
    val loaded: Boolean = false,
    val loadingMore: Boolean = false,
    val preparing: Boolean = false,
) {
    val complete: Boolean get() = loaded && next == null
}

/** Browsing fetches one page; ordinary Play transfers its continuation to playback. */
internal class CollectionBrowser(
    private val scope: CoroutineScope,
    private val load: suspend (Track, String?) -> LibraryPage,
    private val loadArtists: (suspend (Track) -> List<Track>)? = null,
    private val loadMetadata: (suspend (Track) -> Track?)? = null,
    private val loadShuffleSample: (suspend (Track, Int) -> CollectionShuffleSample?)? = null,
) {
    private data class Preparation(
        val visit: Long,
        val index: Int,
        val shuffle: Boolean,
        val onReady: (CollectionPlaybackRequest) -> Unit,
        val fallbackSample: CollectionShuffleSample? = null,
    )
    private val mutableState = MutableStateFlow(CollectionBrowserState())
    val state = mutableState.asStateFlow()
    private var artistsJob: Job? = null
    private var artistsLoaded = false
    private var metadataJob: Job? = null
    private var metadataLoaded = false
    private var job: Job? = null
    private var generation = 0L
    private var requestGeneration = 0L
    private var artistsGeneration = 0L
    private var metadataGeneration = 0L
    private var visible = true
    private var preparation: Preparation? = null
    private val visited = mutableSetOf<String>()
    private var rawCount = 0
    private var rawPositionsValid = true
    private val songRawIndices = mutableListOf<Int>()

    fun open(collection: Track) {
        require(collection.openAction == CatalogOpenAction.Collection)
        generation++
        cancelRequests()
        visited.clear()
        rawCount = 0; rawPositionsValid = true; songRawIndices.clear()
        artistsLoaded = false
        metadataLoaded = false
        mutableState.value = CollectionBrowserState(collection)
        if (visible) { requestPage(); requestArtists(); requestMetadata() }
    }

    fun more() {
        val current = mutableState.value
        if (!visible || !current.loaded || current.next == null || current.error != null || current.preparing) return
        requestPage()
    }

    fun retry() {
        if (!visible) return
        val current = mutableState.value
        if (current.complete) {
            if (current.error != null) current.collection?.let(::open)
        } else requestPage()
    }

    /** Reliable raw totals permit one fair sampled occurrence before buffering the tail. */
    fun preparePlayback(index: Int, shuffle: Boolean, onReady: (CollectionPlaybackRequest) -> Unit): Boolean {
        val current = mutableState.value
        if (!visible || current.collection == null || index !in current.tracks.indices) return false
        if (preparation?.let { it.index == index && it.shuffle == shuffle } == true) return false
        if (!shuffle) {
            // Stop a browse request before transferring its committed cursor.
            // An old response must not append the same page afterwards.
            cancelPageRequest()
            onReady(playbackRequest(index, false))
            return true
        }
        if (!current.complete && current.collection.catalogKind == CatalogKind.Playlist &&
            current.total?.let { it > 0 } == true && loadShuffleSample != null) {
            cancelPageRequest()
            preparation = Preparation(generation, index, true, onReady)
            mutableState.value = mutableState.value.copy(preparing = true, error = null)
            requestShuffleSample(checkNotNull(current.total))
            return true
        }
        // Shuffle keeps ownership here until the full population is loaded.
        // Reuse an in-flight browse page rather than cancelling and fetching
        // that same cursor again; its loop observes this preparation below.
        preparation = Preparation(generation, index, shuffle, onReady)
        mutableState.value = mutableState.value.copy(preparing = true, error = null)
        if (current.complete) {
            val ready = preparation
            preparation = null
            mutableState.value = mutableState.value.copy(preparing = false)
            if (ready != null && ready.visit == generation && visible) ready.onReady(playbackRequest(index, shuffle))
        } else requestPage()
        return true
    }

    private fun requestShuffleSample(total: Int) {
        val collection = mutableState.value.collection ?: return
        val sampler = loadShuffleSample ?: return
        val intent = preparation ?: return
        val visit = generation
        val request = ++requestGeneration
        val samplingJob = scope.launch(start = CoroutineStart.LAZY) {
            var ready: CollectionPlaybackRequest? = null
            var fallback = false
            try {
                val sample = sampler(collection, total)
                currentCoroutineContext().ensureActive()
                if (visit != generation || request != requestGeneration || !visible) return@launch
                if (sample == null || sample.track == null) {
                    fallback = true
                    preparation = intent.copy(fallbackSample = sample)
                } else ready = CollectionPlaybackRequest(collection, listOf(sample.track), 0, true,
                    CollectionPlaybackContinuation.sampledShuffle(collection, sample, load))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (visit == generation && request == requestGeneration) {
                    mutableState.value = mutableState.value.copy(error = R.string.browse_tracks_error_detail)
                }
            } finally {
                if (visit == generation && request == requestGeneration) {
                    job = null
                    if (!fallback) preparation = null
                    mutableState.value = mutableState.value.copy(preparing = fallback, loadingMore = false)
                }
            }
            if (visit == generation && request == requestGeneration && visible) {
                ready?.let(intent.onReady)
                if (fallback) requestPage()
            }
        }
        job = samplingJob
        samplingJob.start()
    }

    fun cancelPreparation() {
        if (preparation == null) return
        cancelPageRequest()
    }

    private fun cancelPageRequest() {
        preparation = null
        requestGeneration++
        job?.cancel(); job = null
        mutableState.value = mutableState.value.copy(loading = false, loadingMore = false, preparing = false)
    }

    private fun playbackRequest(index: Int, shuffle: Boolean, fallbackSample: CollectionShuffleSample? = null): CollectionPlaybackRequest {
        val current = mutableState.value
        val collection = checkNotNull(current.collection)
        val start = if (!shuffle) index else fallbackSample?.let { sample ->
            check(rawPositionsValid && rawCount == sample.total && current.total == sample.total) { "Collection changed during shuffle preparation" }
            songRawIndices.indexOf(sample.rawIndex).takeIf { it >= 0 } ?: current.tracks.indices.random()
        } ?: current.tracks.indices.random()
        return CollectionPlaybackRequest(collection, current.tracks.toList(), start, shuffle,
            current.next?.let { next ->
                check(!shuffle)
                CollectionPlaybackContinuation(collection, next, visited.toSet(), current.total, load)
            })
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (!value) cancelRequests()
        else {
            if (!mutableState.value.loaded && mutableState.value.error == null) requestPage()
            requestArtists()
            requestMetadata()
        }
    }

    fun close() {
        generation++
        cancelRequests()
        visited.clear()
        rawCount = 0; rawPositionsValid = true; songRawIndices.clear()
        mutableState.value = CollectionBrowserState()
    }

    private fun cancelRequests() {
        requestGeneration++
        artistsGeneration++
        metadataGeneration++
        preparation = null
        job?.cancel(); job = null
        artistsJob?.cancel(); artistsJob = null
        metadataJob?.cancel(); metadataJob = null
        mutableState.value = mutableState.value.copy(loading = false, loadingMore = false, preparing = false)
    }

    private fun requestPage() {
        val collection = mutableState.value.collection ?: return
        if (!visible || job != null || mutableState.value.complete) return
        val visit = generation
        val request = ++requestGeneration
        mutableState.value = mutableState.value.copy(loading = !mutableState.value.loaded,
            loadingMore = mutableState.value.loaded, error = null)
        val loadingJob = scope.launch(start = CoroutineStart.LAZY) {
            var ready: Preparation? = null
            try {
                do {
                    val current = mutableState.value
                    val page = load(collection, if (current.loaded) current.next else null)
                    currentCoroutineContext().ensureActive()
                    if (visit != generation || request != requestGeneration || !visible) return@launch
                    check(page.requestedPage !in visited && page.nextPage != page.requestedPage &&
                        (page.nextPage == null || page.nextPage !in visited))
                    visited += page.requestedPage
                    if (rawPositionsValid) {
                        val count = page.rawItemCount
                        rawPositionsValid = count != null && count >= 0 && page.itemRawOffsets.size == page.items.size &&
                            rawCount.toLong() + count <= Int.MAX_VALUE &&
                            page.itemRawOffsets.all { it in 0 until count } && page.itemRawOffsets.zipWithNext().all { (a, b) -> a < b }
                        if (rawPositionsValid) {
                            songRawIndices += page.itemRawOffsets.map { rawCount + it }
                            rawCount += checkNotNull(count)
                        }
                    }
                    // Do not merge by recording/resource ID: playlist occurrences repeat.
                    mutableState.value = mutableState.value.copy(
                        tracks = mutableState.value.tracks + page.items, next = page.nextPage,
                        total = page.total ?: mutableState.value.total, loaded = true,
                        loading = false, loadingMore = preparation != null && page.nextPage != null,
                        error = null,
                    )
                    if (mutableState.value.complete) {
                        ready = preparation
                        preparation = null
                        break
                    }
                    // Cached/immediate pages must still leave a cancellation
                    // point for Back/Cancel and publish visible progress.
                    if (preparation != null) yield()
                } while (preparation != null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (visit == generation && request == requestGeneration) {
                    preparation = null
                    mutableState.value = mutableState.value.copy(error = R.string.browse_tracks_error_detail)
                }
            } finally {
                if (visit == generation && request == requestGeneration) {
                    job = null
                    mutableState.value = mutableState.value.copy(loading = false, loadingMore = false, preparing = false)
                }
            }
            if (visit == generation && request == requestGeneration && visible) {
                ready?.takeIf { it.visit == generation && it.index in mutableState.value.tracks.indices }
                    ?.let { intent ->
                        val prepared = try { playbackRequest(intent.index, intent.shuffle, intent.fallbackSample) }
                        catch (_: IllegalStateException) {
                            mutableState.value = mutableState.value.copy(error = R.string.browse_tracks_error_detail)
                            null
                        }
                        prepared?.let(intent.onReady)
                    }
            }
        }
        job = loadingJob
        loadingJob.start()
    }

    private fun requestMetadata() {
        val collection = mutableState.value.collection ?: return
        val loader = loadMetadata ?: return
        if (!visible || metadataJob != null || metadataLoaded) return
        val visit = generation
        val request = ++metadataGeneration
        val loadingJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val loaded = loader(collection)
                currentCoroutineContext().ensureActive()
                if (visit == generation && request == metadataGeneration && visible) {
                    metadataLoaded = true
                    // Enrichment owns presentation only. The original Library
                    // identity, playback provenance and queue entries stay intact.
                    if (loaded != null && loaded.catalogKind == collection.catalogKind &&
                        CatalogResourceRef.fromTrack(loaded) == CatalogResourceRef.fromTrack(collection)) {
                        mutableState.value = mutableState.value.copy(collection = collection.copy(
                            title = loaded.title.ifBlank { collection.title },
                            artist = loaded.artist.ifBlank { collection.artist },
                            artworkUrl = loaded.artworkUrl ?: collection.artworkUrl,
                            motionArtworkUrl = loaded.motionArtworkUrl ?: collection.motionArtworkUrl,
                            motionPreviewUrl = loaded.motionPreviewUrl ?: collection.motionPreviewUrl,
                            motionArtworkVariants = loaded.motionArtworkVariants.ifEmpty { collection.motionArtworkVariants },
                            description = loaded.description ?: collection.description,
                            url = loaded.url ?: collection.url,
                        ))
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (visit == generation && request == metadataGeneration) metadataLoaded = true
            } finally {
                if (visit == generation && request == metadataGeneration) metadataJob = null
            }
        }
        metadataJob = loadingJob
        loadingJob.start()
    }

    private fun requestArtists() {
        val collection = mutableState.value.collection ?: return
        val loader = loadArtists ?: return
        if (!visible || artistsJob != null || artistsLoaded || collection.catalogKind != CatalogKind.Album) return
        val visit = generation
        val request = ++artistsGeneration
        val loadingJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val artists = loader(collection)
                currentCoroutineContext().ensureActive()
                if (visit == generation && request == artistsGeneration && visible) {
                    artistsLoaded = true
                    mutableState.value = mutableState.value.copy(artists = artists)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (visit == generation && request == artistsGeneration) artistsLoaded = true
            } finally {
                if (visit == generation && request == artistsGeneration) artistsJob = null
            }
        }
        artistsJob = loadingJob
        loadingJob.start()
    }
}
