package dev.amatv.ui

/** One pane visit owns one deliberate initial focus, independent of background reads. */
internal class CatalogMenuFocusVisit {
    private var entered = false
    private var focusedId: String? = null
    private var wasActive = false

    /** Data-only recomposition is not a new Dialog/window handoff. */
    fun resumed(active: Boolean): Boolean {
        val resumed = active && !wasActive
        wasActive = active
        return resumed
    }

    fun entryTarget(rowIds: List<String>): String? = if (entered) null else rowIds.firstOrNull()

    fun entrySucceeded(id: String) { entered = true; focusedId = id }

    fun focused(id: String) { focusedId = id }

    fun restoreTarget(rowIds: List<String>): String? = focusedId?.takeIf { it in rowIds } ?: rowIds.firstOrNull()

    /** Disabled/pending actions still own their position; only removal needs repair. */
    fun replacementIndex(rowIds: List<String>, focusedId: String?, previousIndex: Int): Int? = when {
        focusedId == null || focusedId in rowIds || rowIds.isEmpty() -> null
        else -> previousIndex.coerceIn(rowIds.indices)
    }
}
