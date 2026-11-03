package dev.amatv.ui

import androidx.annotation.StringRes
import dev.amatv.R
import dev.amatv.integration.CatalogFailureReason
import dev.amatv.integration.catalogFailureReason

/** Service meaning only. Raw HTTP bodies, resource IDs and exception messages never enter UI. */
internal data class CatalogFailurePresentation(
    @param:StringRes val message: Int,
    @param:StringRes val action: Int,
    val requiresSignIn: Boolean,
)

internal fun Throwable.catalogUiFailureReason(): CatalogFailureReason =
    catalogFailureReason() ?: CatalogFailureReason.Transient

internal fun catalogFailurePresentation(
    reason: CatalogFailureReason?,
    continuation: Boolean = false,
): CatalogFailurePresentation = when (reason ?: CatalogFailureReason.Transient) {
    CatalogFailureReason.Authorization -> CatalogFailurePresentation(
        if (continuation) R.string.recovery_more_sign_in_required else R.string.recovery_sign_in_required,
        R.string.recovery_sign_in, true,
    )
    CatalogFailureReason.Unavailable -> CatalogFailurePresentation(
        if (continuation) R.string.recovery_more_unavailable else R.string.recovery_content_unavailable,
        R.string.recovery_retry, false,
    )
    CatalogFailureReason.Transient -> CatalogFailurePresentation(
        if (continuation) R.string.recovery_more_connection_failed else R.string.recovery_connection_failed,
        R.string.recovery_retry, false,
    )
}

/** Authorization failures need an account action, not another identical catalog request. */
internal fun performCatalogRecovery(reason: CatalogFailureReason?, onRetry: () -> Unit, onSignIn: () -> Unit) {
    if (reason == CatalogFailureReason.Authorization) onSignIn() else onRetry()
}

@androidx.compose.runtime.Composable
internal fun catalogRecoveryLabel(reason: CatalogFailureReason?, continuation: Boolean = false): String {
    val presentation = catalogFailurePresentation(reason, continuation)
    return androidx.compose.ui.res.stringResource(presentation.message) + "\n" +
        androidx.compose.ui.res.stringResource(presentation.action)
}
