package dev.amatv.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackSleepTimerTest {
    @Test fun supportedMinuteChoicesUseElapsedDeadlinesAndExpireOnlyOnce() {
        for ((option, duration) in listOf(
            SleepTimerOption.Minutes15 to 900_000L,
            SleepTimerOption.Minutes30 to 1_800_000L,
            SleepTimerOption.Minutes45 to 2_700_000L,
            SleepTimerOption.Minutes60 to 3_600_000L,
        )) {
            var now = 10_000L
            val timer = PlaybackSleepTimer { now }
            assertTrue(timer.select(option, 41L))
            assertEquals(10_000L + duration, timer.state.value.deadlineElapsedRealtimeMs)
            now += duration - 1L
            assertEquals(1L, timer.state.value.remainingMs(now))
            assertFalse(timer.consumeDeadline())
            now++
            assertTrue(timer.consumeDeadline())
            assertEquals(SleepTimerOption.Off, timer.state.value.option)
            assertFalse(timer.consumeDeadline())
        }
    }

    @Test fun aMinuteTimerContinuesAcrossPauseAndDifferentQueueOccurrences() {
        var now = 0L
        val timer = PlaybackSleepTimer { now }
        timer.select(SleepTimerOption.Minutes15, 7L)
        // Playback pause has no timer mutation; elapsed time continues to count.
        now = 300_000L
        assertEquals(600_000L, timer.state.value.remainingMs(now))
        assertFalse(timer.consumeEntryChange(8L))
        assertFalse(timer.consumeTrackEnd(8L))
        assertEquals(SleepTimerOption.Minutes15, timer.state.value.option)
        now = 900_000L
        assertTrue(timer.consumeDeadline())
    }

    @Test fun selectingAnotherDurationReplacesTheOldDeadlineAndOffCancelsIt() {
        var now = 0L
        val timer = PlaybackSleepTimer { now }
        timer.select(SleepTimerOption.Minutes15, 1L)
        now = 400_000L
        timer.select(SleepTimerOption.Minutes30, 1L)
        now = 900_000L
        assertFalse(timer.consumeDeadline())
        assertEquals(1_300_000L, timer.state.value.remainingMs(now))
        assertTrue(timer.select(SleepTimerOption.Off, null))
        now = Long.MAX_VALUE
        assertFalse(timer.consumeDeadline())
        assertNull(timer.state.value.remainingMs(now))
    }

    @Test fun endOfTrackAcceptsOnlyTheArmedOccurrenceAndConsumesRepeatedEndEvents() {
        val timer = PlaybackSleepTimer { 0L }
        timer.select(SleepTimerOption.EndOfTrack, 17L)
        assertNull(timer.state.value.deadlineElapsedRealtimeMs)
        assertFalse(timer.consumeDeadline())
        assertFalse(timer.consumeTrackEnd(16L)) // A stale ended snapshot cannot stop this song.
        assertFalse(timer.consumeEntryChange(17L)) // Metadata refresh/re-entry is not a new occurrence.
        assertTrue(timer.consumeTrackEnd(17L))
        assertFalse(timer.consumeTrackEnd(17L))
        assertEquals(SleepTimerOption.Off, timer.state.value.option)
    }

    @Test fun endOfTrackStopsAtManualOrNativeHandoffEvenForARepeatedRecording() {
        val timer = PlaybackSleepTimer { 0L }
        timer.select(SleepTimerOption.EndOfTrack, 17L)
        // Equal recordings have distinct queue entry IDs. A following duplicate
        // must not be mistaken for the occurrence whose ending was requested.
        assertTrue(timer.consumeEntryChange(18L))
        assertFalse(timer.consumeEntryChange(19L))
        assertEquals(SleepTimerOption.Off, timer.state.value.option)
    }

    @Test fun clearingTheCurrentOccurrenceAlsoFinishesItsContentTimer() {
        val timer = PlaybackSleepTimer { 0L }
        timer.select(SleepTimerOption.EndOfTrack, 17L)
        assertTrue(timer.consumeEntryChange(null))
        assertFalse(timer.consumeEntryChange(null))
    }

    @Test fun aMissingCurrentItemCannotArmOrReplaceAnExistingTimer() {
        val timer = PlaybackSleepTimer { 0L }
        assertFalse(timer.select(SleepTimerOption.Minutes15, null))
        assertFalse(timer.select(SleepTimerOption.EndOfTrack, null))
        timer.select(SleepTimerOption.Minutes30, 1L)
        val existing = timer.state.value
        assertFalse(timer.select(SleepTimerOption.EndOfTrack, null))
        assertEquals(existing, timer.state.value)
        timer.cancel()
        assertEquals(SleepTimerOption.Off, timer.state.value.option)
    }

    @Test fun replacingContentModeWithDurationRemovesTheOccurrenceStop() {
        val timer = PlaybackSleepTimer { 10_000L }
        timer.select(SleepTimerOption.EndOfTrack, 17L)
        timer.select(SleepTimerOption.Minutes15, 17L)
        assertFalse(timer.consumeEntryChange(18L))
        assertFalse(timer.consumeTrackEnd(17L))
        assertEquals(SleepTimerOption.Minutes15, timer.state.value.option)
    }
}
