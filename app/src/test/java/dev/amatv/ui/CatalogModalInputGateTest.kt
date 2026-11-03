package dev.amatv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogModalInputGateTest {
    @Test fun openingIntentImmediatelyOwnsUnderlyingKeys() {
        val gate = CatalogModalInputGate()
        assertEquals(CatalogModalInputGate.HostResult.Consume, gate.host(true, false, true, false))
        assertEquals(CatalogModalInputGate.HostResult.Consume, gate.host(true, false, false, true))
    }

    @Test fun pendingDialogBackDismissesOnlyMenuOnRelease() {
        val gate = CatalogModalInputGate()
        assertEquals(CatalogModalInputGate.HostResult.Consume, gate.host(true, true, true, false))
        assertEquals(CatalogModalInputGate.HostResult.Consume, gate.host(true, true, true, false))
        assertEquals(CatalogModalInputGate.HostResult.DismissMenu, gate.host(true, true, false, true))
        assertEquals(CatalogModalInputGate.HostResult.Pass, gate.host(false, false, true, false))
    }

    @Test fun closingDuringHeldBackDoesNotLeakReleaseToPage() {
        val gate = CatalogModalInputGate()
        gate.host(true, true, true, false)
        assertEquals(CatalogModalInputGate.HostResult.Consume, gate.host(false, true, false, true))
        assertEquals(CatalogModalInputGate.HostResult.Pass, gate.host(false, true, true, false))
    }

    @Test fun pressStartedBeforeDialogReadyCannotActivateAfterReady() {
        val gate = CatalogModalInputGate()
        assertFalse(gate.dialog(false, false, true, true, false))
        assertFalse(gate.dialog(true, false, true, true, false, repeat = 1))
        assertFalse(gate.dialog(true, false, true, false, true))
        assertTrue(gate.dialog(true, false, true, true, false))
        assertTrue(gate.dialog(true, false, true, false, true))
    }

    @Test fun backRemainsAvailableWhileMenuHasNotLaidOut() {
        val gate = CatalogModalInputGate()
        assertTrue(gate.dialog(false, true, false, true, false))
        assertTrue(gate.dialog(false, true, false, false, true))
    }

    @Test fun backReleaseTransferredToDialogDoesNotEatTheNextPageBack() {
        val gate = CatalogModalInputGate()
        gate.host(true, true, true, false)
        assertTrue(gate.dialog(false, true, false, false, true))
        assertEquals(CatalogModalInputGate.HostResult.Pass, gate.host(false, true, true, false))
    }

    @Test fun dialogOwnedBackSurvivesProgrammaticCloseUntilRootRelease() {
        val gate = CatalogModalInputGate()
        assertTrue(gate.dialog(true, true, false, true, false))
        assertEquals(CatalogModalInputGate.HostResult.Consume, gate.host(false, true, false, true))
        assertEquals(CatalogModalInputGate.HostResult.Pass, gate.host(false, true, true, false))
    }

    @Test fun nativeDialogDismissClearsPendingHostBack() {
        val gate = CatalogModalInputGate()
        gate.host(true, true, true, false)
        gate.dialogBackDismissed()
        assertEquals(CatalogModalInputGate.HostResult.Pass, gate.host(false, true, true, false))
    }

    @Test fun activationPressConsumedByHostCannotClickNewDialogOnRelease() {
        val gate = CatalogModalInputGate()
        assertEquals(CatalogModalInputGate.HostResult.Consume,
            gate.host(true, false, true, false, activation = true))
        assertFalse(gate.dialog(true, false, true, false, true))
        assertTrue(gate.dialog(true, false, true, true, false))
    }

    @Test fun openingOnHostDownCannotBecomeARepeatPressInReadyDialog() {
        val gate = CatalogModalInputGate()
        assertEquals(CatalogModalInputGate.HostResult.Pass,
            gate.host(false, false, true, false, activation = true))
        gate.menuOpened()
        assertFalse(gate.dialog(true, false, true, true, false, repeat = 1))
        assertFalse(gate.dialog(true, false, true, true, false, repeat = 1))
        assertFalse(gate.dialog(true, false, true, false, true))
        assertTrue(gate.dialog(true, false, true, true, false))
    }

    @Test fun normalHostClickAndNextFreshMenuPressRemainUsable() {
        val gate = CatalogModalInputGate()
        assertEquals(CatalogModalInputGate.HostResult.Pass,
            gate.host(false, false, true, false, activation = true))
        assertEquals(CatalogModalInputGate.HostResult.Pass,
            gate.host(false, false, false, true, activation = true))
        gate.menuOpened()
        assertTrue(gate.dialog(true, false, true, true, false))
        assertTrue(gate.dialog(true, false, true, false, true))
    }

    @Test fun dialogBlockedPressCannotClickUnderlyingPageIfDialogCloses() {
        val gate = CatalogModalInputGate()
        gate.dialog(false, false, true, true, false)
        assertEquals(CatalogModalInputGate.HostResult.Consume,
            gate.host(false, false, false, true, activation = true))
        assertEquals(CatalogModalInputGate.HostResult.Pass,
            gate.host(false, false, true, false, activation = true))
    }

    @Test fun disabledFocusedActionDoesNotBlockNavigationToAnotherRow() {
        val gate = CatalogModalInputGate()
        assertTrue(gate.dialog(true, false, false, true, false, activationReady = false))
        assertTrue(gate.dialog(true, false, false, false, true, activationReady = false))
        assertFalse(gate.dialog(true, false, true, true, false, activationReady = false))
        assertFalse(gate.dialog(true, false, true, false, true, activationReady = false))
        assertTrue(gate.dialog(true, true, false, false, true, activationReady = false))
    }

    @Test fun aPendingReadResolvingBetweenDownAndUpCannotTurnIntoAMutation() {
        val gate = CatalogModalInputGate()
        assertFalse(gate.dialog(true, false, true, true, false, activationReady = false))
        assertFalse(gate.dialog(true, false, true, true, false, repeat = 1, activationReady = true))
        assertFalse(gate.dialog(true, false, true, false, true, activationReady = true))
        assertTrue(gate.dialog(true, false, true, true, false, activationReady = true))
        assertTrue(gate.dialog(true, false, true, false, true, activationReady = true))
    }
}
