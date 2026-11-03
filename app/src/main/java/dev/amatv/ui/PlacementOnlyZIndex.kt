package dev.amatv.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints

/** Same sibling draw order as zIndex, with no remeasurement for a pure z change. */
internal fun Modifier.placementOnlyZIndex(zIndex: Float): Modifier =
    this then PlacementOnlyZIndexElement(zIndex)

private data class PlacementOnlyZIndexElement(val zIndex: Float) :
    ModifierNodeElement<PlacementOnlyZIndexNode>() {
    override fun create() = PlacementOnlyZIndexNode(zIndex)
    override fun update(node: PlacementOnlyZIndexNode) { node.updateZIndex(zIndex) }
    override fun InspectorInfo.inspectableProperties() {
        name = "placementOnlyZIndex"
        properties["zIndex"] = zIndex
    }
}

private class PlacementOnlyZIndexNode(private var zIndex: Float) :
    LayoutModifierNode, Modifier.Node() {
    override val shouldAutoInvalidate: Boolean get() = false

    fun updateZIndex(value: Float) {
        if (value == zIndex) return
        zIndex = value
        if (isAttached) invalidatePlacement()
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.place(0, 0, zIndex = zIndex)
        }
    }
}
