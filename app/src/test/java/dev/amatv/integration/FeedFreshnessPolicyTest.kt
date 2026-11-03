package dev.amatv.integration

import org.junit.Assert.*
import org.junit.Test

class FeedFreshnessPolicyTest {
    @Test fun homeAndBrowseUseTheirOwnTtlBoundaries() {
        val wall = 1_000_000L
        assertFalse(FeedFreshnessPolicy.shouldRefresh(CatalogFeedKind.Home, true, true, wall - 59_999, nowEpochMs = wall, nowElapsedMs = 80_000))
        assertTrue(FeedFreshnessPolicy.shouldRefresh(CatalogFeedKind.Home, true, true, wall - 60_000, nowEpochMs = wall, nowElapsedMs = 80_000))
        assertFalse(FeedFreshnessPolicy.shouldRefresh(CatalogFeedKind.Browse, true, true, wall - 299_999, nowEpochMs = wall, nowElapsedMs = 80_000))
        assertTrue(FeedFreshnessPolicy.shouldRefresh(CatalogFeedKind.Browse, true, true, wall - 300_000, nowEpochMs = wall, nowElapsedMs = 80_000))
    }

    @Test fun hiddenOrInFlightFeedsNeverStartDuplicateRequestsEvenOnForce() {
        assertFalse(FeedFreshnessPolicy.shouldRefresh(CatalogFeedKind.Home, false, false, force = true, nowElapsedMs = 10))
        assertFalse(FeedFreshnessPolicy.shouldRefresh(CatalogFeedKind.Home, true, false, inFlight = true, force = true, nowElapsedMs = 10))
        assertNull(FeedFreshnessPolicy.nextCheckDelayMillis(CatalogFeedKind.Home, false, false, nowElapsedMs = 10))
        assertNull(FeedFreshnessPolicy.nextCheckDelayMillis(CatalogFeedKind.Home, true, false, inFlight = true, nowElapsedMs = 10))
    }

    @Test fun processLocalElapsedTimeWinsOverWallClockChanges() {
        assertFalse(FeedFreshnessPolicy.shouldRefresh(CatalogFeedKind.Home, true, true,
            snapshotWrittenAtEpochMs = 500_000, lastSuccessElapsedMs = 10_000,
            nowEpochMs = 9_000_000, nowElapsedMs = 20_000))
        assertTrue(FeedFreshnessPolicy.shouldRefresh(CatalogFeedKind.Home, true, true,
            snapshotWrittenAtEpochMs = 500_000, lastSuccessElapsedMs = 10_000,
            nowEpochMs = 1_000, nowElapsedMs = 70_000))
    }

    @Test fun futureOrUnknownWallTimeIsStaleButRecentAttemptsPreventAHotRetryLoop() {
        assertTrue(FeedFreshnessPolicy.shouldRefresh(CatalogFeedKind.Home, true, true,
            snapshotWrittenAtEpochMs = 500_000, nowEpochMs = 100_000, nowElapsedMs = 20_000))
        assertFalse(FeedFreshnessPolicy.shouldRefresh(CatalogFeedKind.Home, true, false,
            lastAttemptElapsedMs = 10_000, nowElapsedMs = 20_000))
        assertTrue(FeedFreshnessPolicy.shouldRefresh(CatalogFeedKind.Home, true, false,
            lastAttemptElapsedMs = 10_000, nowElapsedMs = 70_000))
        assertTrue(FeedFreshnessPolicy.shouldRefresh(CatalogFeedKind.Home, true, false,
            lastAttemptElapsedMs = 10_000, force = true, nowElapsedMs = 20_000))
    }

    @Test fun restoredSnapshotNeverTreatsAStalePreviousBootElapsedStampAsCurrent() {
        assertTrue(FeedFreshnessPolicy.shouldRefresh(CatalogFeedKind.Home, true, true,
            snapshotWrittenAtEpochMs = 10_000, lastSuccessElapsedMs = 500_000,
            lastAttemptElapsedMs = 500_000, nowEpochMs = 100_000, nowElapsedMs = 1_000))
    }

    @Test fun nextCheckWaitsForBothFreshnessAndAttemptBudgetWithoutPollingHiddenPages() {
        assertEquals(50_000L, FeedFreshnessPolicy.nextCheckDelayMillis(CatalogFeedKind.Home, true, true,
            lastSuccessElapsedMs = 10_000, nowElapsedMs = 20_000))
        assertEquals(59_000L, FeedFreshnessPolicy.nextCheckDelayMillis(CatalogFeedKind.Home, true, true,
            snapshotWrittenAtEpochMs = 1_000, lastAttemptElapsedMs = 19_000,
            nowEpochMs = 900_000, nowElapsedMs = 20_000))
        assertEquals(0L, FeedFreshnessPolicy.nextCheckDelayMillis(CatalogFeedKind.Home, true, false, nowElapsedMs = 20_000))
    }
}
