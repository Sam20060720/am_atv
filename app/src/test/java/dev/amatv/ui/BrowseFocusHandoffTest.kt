package dev.amatv.ui

import androidx.compose.ui.focus.FocusRequester
import org.junit.Assert.*
import org.junit.Test

class BrowseFocusHandoffTest {
    @Test fun openingAPlaylistCannotBeAcknowledgedByItsStillFocusedHomeCard() {
        val home = ContentFocusEntry()
        home.targetFocused(FocusRequester(), true)
        val focus = BrowseFocusHandoff(home)
        assertTrue(focus.acknowledge(focus.request))

        val playlist = ContentFocusEntry()
        focus.enter(playlist)
        assertTrue(home.hasFocusedTarget) // Native source removal has not happened yet.
        assertFalse(focus.acknowledge(focus.request))
        assertTrue(focus.request.pending)

        playlist.targetFocused(FocusRequester(), true)
        assertTrue(focus.acknowledge(focus.request))
        assertFalse(focus.request.pending)
    }

    @Test fun navigationFallbackDoesNotCompleteALoadingPlaylistEntry() {
        val playlist = ContentFocusEntry()
        val navigation = ContentFocusEntry()
        val focus = BrowseFocusHandoff(playlist)
        navigation.targetFocused(FocusRequester(), true)

        assertFalse(focus.acknowledge(focus.request))
        assertSame(playlist, focus.request.owner)
        playlist.targetFocused(FocusRequester(), true)
        assertTrue(focus.acknowledge(focus.request))
    }

    @Test fun aRemoteMoveToNavigationInvalidatesALatePlaylistHandoff() {
        val playlist = ContentFocusEntry()
        val navigation = ContentFocusEntry()
        val focus = BrowseFocusHandoff(playlist)
        val pendingPlaylist = focus.request
        navigation.targetFocused(FocusRequester(), true)

        assertTrue(focus.claim(navigation))
        playlist.targetFocused(FocusRequester(), true)
        assertFalse(focus.acknowledge(pendingPlaylist))
        assertSame(navigation, focus.request.owner)
        assertFalse(focus.request.pending)
    }

    @Test fun anOldWindowAttemptCannotCompleteANewerReturnToTheSamePage() {
        val page = ContentFocusEntry()
        val focus = BrowseFocusHandoff(page)
        val old = focus.request
        focus.enter(page)
        page.targetFocused(FocusRequester(), true)

        assertFalse(focus.acknowledge(old))
        assertTrue(focus.request.pending)
        assertTrue(focus.acknowledge(focus.request))
    }

    @Test fun disposingAnOldControlCannotClearTheNewControlInTheSamePage() {
        val page = ContentFocusEntry()
        val removed = FocusRequester()
        val replacement = FocusRequester()
        page.targetFocused(removed, true)
        page.targetFocused(replacement, true)
        page.targetDetached(removed)
        assertTrue(page.hasFocusedTarget)
        page.targetDetached(replacement)
        assertFalse(page.hasFocusedTarget)
    }

    @Test fun removingAFocusedLoadingActionClearsOwnershipButRetainsThePageRequest() {
        val page = ContentFocusEntry()
        val action = FocusRequester()
        page.requester = action
        page.targetFocused(action, true)
        val focus = BrowseFocusHandoff(page)
        assertTrue(focus.acknowledge(focus.request))

        page.targetDetached(action)
        assertFalse(page.hasFocusedTarget)
        assertNull(page.requester)
        focus.enter(page)
        assertFalse(focus.acknowledge(focus.request))
        page.targetFocused(FocusRequester(), true)
        assertTrue(focus.acknowledge(focus.request))
    }

    @Test fun aDifferentVisitToTheSameResourceHasIndependentOwnership() {
        val firstVisit = ContentFocusEntry()
        val secondVisit = ContentFocusEntry()
        val focus = BrowseFocusHandoff(secondVisit)
        firstVisit.targetFocused(FocusRequester(), true)
        assertFalse(focus.acknowledge(focus.request))
        firstVisit.targetDetached(FocusRequester())
        assertTrue(firstVisit.hasFocusedTarget)
        assertFalse(secondVisit.hasFocusedTarget)
    }

    @Test fun anExactSourceReturnMustFinishBeforeAnIncidentalTargetCanSettleThePage() {
        val page = ContentFocusEntry()
        page.preferredReturnRequester = FocusRequester()
        page.targetFocused(FocusRequester(), true)
        val focus = BrowseFocusHandoff(page)
        assertFalse(focus.acknowledge(focus.request))
        page.preferredReturnRequester = null
        assertTrue(focus.acknowledge(focus.request))
    }

    @Test fun aRequesterReferenceAloneIsNotActualFocus() {
        val page = ContentFocusEntry()
        page.requester = FocusRequester()
        val focus = BrowseFocusHandoff(page)
        assertFalse(focus.acknowledge(focus.request))
        assertFalse(focus.claim(page))
    }
}
