package dev.amatv.playback

import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import dev.amatv.integration.PlaybackSnapshot
import dev.amatv.playback.PlaybackRuntimeOwner.PreparationPhase
import dev.amatv.playback.PlaybackRuntimeOwner.PreparationRequest
import dev.amatv.playback.PlaybackRuntimeOwner.PreparationState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class PlaybackRuntimePreparationTest {
    private val song = Track("song", "Song", "Artist", "Album", 180_000L,
        emptyList(), emptyList(), catalogId = "song", catalogKind = CatalogKind.Song)
    private fun request(serial: Long = 1L, session: Long = 10L, entry: Long = 20L) =
        PreparationRequest(serial, session, entry, song, 37_000L)

    @Test fun coldLyricSelectionCarriesItsAbsoluteCursorThroughDelayedCatalogResolution() = runBlocking {
        val lyric = request().copy(track = song.copy(durationMs = 0L), positionMs = 45_000L)
        val resolved = CompletableDeferred<Track>()
        val preparedPositions = mutableListOf<Long>()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            prepareRuntimeSelection(lyric, resolve = { resolved.await() },
                prepare = { _, position, entry ->
                    assertEquals(lyric.entryId, entry)
                    preparedPositions += position
                    9L
                }, isCurrent = { true })
        }
        assertTrue(preparedPositions.isEmpty())
        resolved.complete(song)
        assertEquals(9L, pending.await()?.generation)
        assertEquals(listOf(45_000L), preparedPositions)
    }

    @Test fun aNewLyricTargetOnTheSameOccurrenceSupersedesAnInflightColdPreparation() = runBlocking {
        val first = request().copy(positionMs = 12_000L)
        var selected = first
        val oldEngine = CompletableDeferred<Long>()
        val positions = mutableListOf<Long>()
        val old = async(start = CoroutineStart.UNDISPATCHED) {
            prepareRuntimeSelection(first, resolve = { it },
                prepare = { _, position, _ -> positions += position; oldEngine.await() },
                isCurrent = { it == selected })
        }
        selected = first.copy(serial = first.serial + 1L, positionMs = 81_000L)
        val latest = prepareRuntimeSelection(selected, resolve = { it },
            prepare = { _, position, _ -> positions += position; 2L }, isCurrent = { it == selected })
        oldEngine.complete(1L)
        assertNull(old.await())
        assertEquals(2L, latest?.generation)
        assertEquals(listOf(12_000L, 81_000L), positions)
    }

    @Test fun lateResolutionCannotPrepareAnOlderOccurrenceOfTheSameSong() = runBlocking {
        val original = request()
        var selected = original
        val resolved = CompletableDeferred<Track>()
        var nativeCalls = 0
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            prepareRuntimeSelection(original, resolve = { resolved.await() },
                prepare = { _, _, _ -> nativeCalls++; 1L },
                isCurrent = { it.matches(selected.serial, selected.sessionId, selected.entryId) })
        }
        selected = request(serial = 2L, entry = 21L)
        resolved.complete(song)
        assertNull(pending.await())
        assertEquals(0, nativeCalls)
    }

    @Test fun lateNativeCompletionCannotReplaceTheNewerRequest() = runBlocking {
        val original = request()
        var selected = original
        val nativeResult = CompletableDeferred<Long>()
        val preparedEntries = mutableListOf<Long>()
        val old = async(start = CoroutineStart.UNDISPATCHED) {
            prepareRuntimeSelection(original, resolve = { it },
                prepare = { _, cursor, entry ->
                    assertEquals(37_000L, cursor)
                    preparedEntries += entry
                    nativeResult.await()
                }, isCurrent = { it.matches(selected.serial, selected.sessionId, selected.entryId) })
        }
        selected = request(serial = 2L, entry = 21L)
        val current = prepareRuntimeSelection(selected, resolve = { it },
            prepare = { _, _, entry -> preparedEntries += entry; 2L },
            isCurrent = { it.matches(selected.serial, selected.sessionId, selected.entryId) })
        nativeResult.complete(1L)
        assertNull(old.await())
        assertEquals(2L, current?.generation)
        assertEquals(listOf(20L, 21L), preparedEntries)
    }

    @Test fun replacementSessionInvalidatesEvenAnIdenticalEntryId() = runBlocking {
        val original = request()
        var session = original.sessionId
        val resolved = CompletableDeferred<Track>()
        var nativeCalls = 0
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            prepareRuntimeSelection(original, resolve = { resolved.await() },
                prepare = { _, _, _ -> nativeCalls++; 1L },
                isCurrent = { it.matches(original.serial, session, original.entryId) })
        }
        session++
        resolved.complete(song)
        assertNull(pending.await())
        assertEquals(0, nativeCalls)
    }

    @Test fun sameOccurrenceReprepareInvalidatesTheEarlierSerial() = runBlocking {
        val original = request()
        var serial = original.serial
        val nativeResult = CompletableDeferred<Long>()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            prepareRuntimeSelection(original, resolve = { it }, prepare = { _, _, _ -> nativeResult.await() },
                isCurrent = { it.matches(serial, original.sessionId, original.entryId) })
        }
        serial++
        nativeResult.complete(1L)
        assertNull(pending.await())
    }

    @Test fun pauseCancelsEvenAResolverThatIgnoresCoroutineCancellation() = runBlocking {
        val resolved = CompletableDeferred<Track>()
        var nativeCalls = 0
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            prepareRuntimeSelection(request(),
                resolve = { withContext(NonCancellable) { resolved.await() } },
                prepare = { _, _, _ -> nativeCalls++; 1L }, isCurrent = { true })
        }
        pending.cancel()
        resolved.complete(song)
        pending.join()
        assertTrue(pending.isCancelled)
        assertEquals(0, nativeCalls)
    }

    @Test fun cancelledNativeCompletionCannotBeCommitted() = runBlocking {
        val nativeResult = CompletableDeferred<Long>()
        var commits = 0
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            prepareRuntimeSelection(request(), resolve = { it },
                prepare = { _, _, _ -> withContext(NonCancellable) { nativeResult.await() } },
                isCurrent = { true })?.also { commits++ }
        }
        pending.cancel()
        nativeResult.complete(1L)
        pending.join()
        assertTrue(pending.isCancelled)
        assertEquals(0, commits)
    }

    @Test fun retryWaitChecksCurrentIntentBeforeStartingAnyIo() = runBlocking {
        var playing = true
        val retryDeadline = CompletableDeferred<Unit>()
        var resolveCalls = 0
        var nativeCalls = 0
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            prepareRuntimeSelection(request(), resolve = { resolveCalls++; it },
                prepare = { _, _, _ -> nativeCalls++; 1L }, isCurrent = { playing },
                awaitRetry = { retryDeadline.await() })
        }
        playing = false
        retryDeadline.complete(Unit)
        assertNull(pending.await())
        assertEquals(0, resolveCalls)
        assertEquals(0, nativeCalls)
    }

    @Test fun unchangedSelectionCanFinishTheSameRetryAfterUiLeaseReplacement() = runBlocking {
        val selection = request()
        val retryDeadline = CompletableDeferred<Unit>()
        var waitCalls = 0
        var nativeCalls = 0
        var oldUiCalls = 0
        var newUiCalls = 0
        var consumer: (() -> Unit)? = { oldUiCalls++ }
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            val prepared = prepareRuntimeSelection(selection, resolve = { it },
                prepare = { _, _, _ -> nativeCalls++; 1L },
                isCurrent = { it.matches(selection.serial, selection.sessionId, selection.entryId) },
                awaitRetry = { waitCalls++; retryDeadline.await() })
            if (prepared != null) consumer?.invoke()
            prepared
        }
        consumer = null // Detach only removes the callback; identity and deadline survive.
        assertEquals(1, waitCalls)
        assertEquals(0, nativeCalls)
        consumer = { newUiCalls++ }
        retryDeadline.complete(Unit)
        assertEquals(1L, pending.await()?.generation)
        assertEquals(1, waitCalls)
        assertEquals(1, nativeCalls)
        assertEquals(0, oldUiCalls)
        assertEquals(1, newUiCalls)
    }

    @Test fun pauseKeepsAReadyGenerationButInvalidatesInflightAndRetryGenerations() {
        val ready = PreparationState(PreparationPhase.Ready, request(), generation = 7L)
        assertSame(ready, ready.cancelled(clearReady = false))
        for (phase in listOf(PreparationPhase.Preparing, PreparationPhase.Retrying)) {
            val paused = PreparationState(phase, request(), retryAtElapsedRealtimeMs = 50_000L)
                .cancelled(clearReady = false)
            assertFalse(paused.isPending)
            assertEquals(0L, paused.generation)
            assertEquals(37_000L, paused.request?.positionMs)
            assertNull(paused.retryAtElapsedRealtimeMs)
        }
        assertNull(ready.cancelled(clearReady = true).request)
        assertEquals(0L, ready.cancelled(clearReady = true).generation)
    }

    private fun pausedSnapshot(entryId: Long? = 20L) = PlaybackSnapshot(
        currentTrack = song, positionMs = 37_000L, durationMs = song.durationMs,
        isPlaying = false, isBuffering = false, canSkipNext = true, canSkipPrevious = false,
        capturedAtElapsedRealtimeMs = 0L, requestGeneration = 7L, appQueueEntryId = entryId,
    )

    @Test fun pausedReadyEngineCanSeekWithoutReusingItsCancelledCommandSerial() {
        val paused = PreparationState(PreparationPhase.Ready, request(serial = 1L), generation = 7L)
            .cancelled(clearReady = false)
        assertFalse(checkNotNull(paused.request).matches(2L, 10L, 20L))
        assertTrue(paused.canSeek(10L, 20L, pausedSnapshot()))
        assertTrue(paused.canSeek(10L, 20L, pausedSnapshot(entryId = null)))
    }

    @Test fun aNativeQueueHandoffCanSeekItsObservedOccurrenceOnTheSameEngine() {
        val prepared = PreparationState(PreparationPhase.Ready, request(entry = 20L), generation = 7L)
        assertTrue(prepared.canSeek(10L, 21L, pausedSnapshot(entryId = 21L)))
        assertFalse(prepared.canSeek(10L, 21L, pausedSnapshot(entryId = null)))
    }

    @Test fun coldOrReplacedEnginesStillRequirePreparationForALyricSeek() {
        val prepared = PreparationState(PreparationPhase.Ready, request(), generation = 7L)
        val snapshot = pausedSnapshot()
        assertFalse(prepared.canSeek(11L, 20L, snapshot))
        assertFalse(prepared.canSeek(10L, 21L, snapshot))
        assertFalse(prepared.canSeek(10L, 20L, snapshot.copy(requestGeneration = 8L)))
        assertFalse(prepared.canSeek(10L, 20L, snapshot.copy(isPrepared = false)))
        assertFalse(prepared.canSeek(10L, 20L, snapshot.copy(canSeek = false)))
        assertFalse(prepared.canSeek(10L, 20L, snapshot.copy(isEnded = true)))
        assertFalse(prepared.canSeek(10L, 20L, snapshot.copy(errorMessage = "unavailable")))
        assertFalse(prepared.canSeek(10L, 20L, null))
        assertFalse(prepared.copy(phase = PreparationPhase.Preparing).canSeek(10L, 20L, snapshot))
    }
}
