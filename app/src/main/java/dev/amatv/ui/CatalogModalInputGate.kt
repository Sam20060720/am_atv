package dev.amatv.ui

/** Key ownership across the frame between a menu intent and its Dialog window. */
internal class CatalogModalInputGate {
    enum class HostResult { Pass, Consume, DismissMenu }

    private var backHeld = false
    private var activationBlocked = false
    private var dialogActivationHeld = false

    fun menuOpened() {
        // The opening button owns its release. This Dialog must see a new Down.
        activationBlocked = false
        dialogActivationHeld = false
    }

    fun host(menuOpen: Boolean, isBack: Boolean, down: Boolean, up: Boolean,
        activation: Boolean = false, repeat: Int = 0): HostResult {
        if (activation && down && repeat == 0 && !menuOpen) activationBlocked = false
        if (activation && activationBlocked) {
            if (up) activationBlocked = false
            return HostResult.Consume
        }
        if (menuOpen && activation && down) activationBlocked = true
        if (!menuOpen && !(isBack && backHeld)) return HostResult.Pass
        if (!isBack) return HostResult.Consume
        if (down) backHeld = true
        if (up) {
            backHeld = false
            return if (menuOpen) HostResult.DismissMenu else HostResult.Consume
        }
        return HostResult.Consume
    }

    // Native Dialog onDismissRequest has already handled the Back release.
    // Other dismissal paths retain held Back until the root receives Up.
    fun dialogBackDismissed() { backHeld = false }

    /** Only a fresh press owned by this ready Dialog may activate its row. */
    fun dialog(ready: Boolean, isBack: Boolean, activation: Boolean, down: Boolean, up: Boolean,
        repeat: Int = 0, activationReady: Boolean = ready): Boolean {
        if (isBack) {
            if (down) backHeld = true
            if (up) backHeld = false
            return true
        }
        if (activation) {
            if (down) {
                if (repeat == 0) {
                    dialogActivationHeld = true
                    activationBlocked = !ready || !activationReady
                } else if (!dialogActivationHeld) activationBlocked = true
            }
            if (up) {
                val allowed = ready && activationReady && dialogActivationHeld && !activationBlocked
                dialogActivationHeld = false
                activationBlocked = false
                return allowed
            }
            return ready && activationReady && dialogActivationHeld && !activationBlocked
        }
        return ready
    }
}
