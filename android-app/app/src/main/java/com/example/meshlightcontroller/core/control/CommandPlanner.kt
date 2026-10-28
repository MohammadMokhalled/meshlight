package com.example.meshlightcontroller.core.control

import com.example.meshlightcontroller.core.model.*

data class PlannedEdit(val nodeUuid: String, val target: Int, val patch: LightingPatch)

object CommandPlanner {
    fun zone(installation: Installation, references: List<LedRef>, patch: LightingPatch): List<PlannedEdit> {
        require(!patch.isEmpty)
        val nodes = installation.nodes.associateBy { it.uuid }
        return references.distinct().groupBy { it.nodeUuid }.flatMap { (uuid, refs) ->
            val node = requireNotNull(nodes[uuid]) { "A selected light no longer exists" }
            val capabilities = requireNotNull(node.capabilities)
            val count = capabilities.ledCount
            require(patch.rgb == null || capabilities.supportsRgb) { "Color is not supported" }
            require(patch.power == null || capabilities.supportsPower) { "Power is not supported" }
            require(patch.intensity == null || (capabilities.supportsIntensity && patch.intensity <= capabilities.maxIntensity)) { "Brightness is not supported" }
            require(if (refs.size == count) capabilities.supportsAll else capabilities.supportsIndexed) { "LED selection is not supported" }
            require(node.stage == SetupStage.READY && refs.all { it.index in 0 until count }) { "Invalid LED selection" }
            if (refs.size == count) listOf(PlannedEdit(uuid, LedTarget.All, patch))
            else refs.sortedBy { it.index }.map { PlannedEdit(uuid, it.index, patch) }
        }
    }

    fun preset(installation: Installation, edits: List<PresetEdit>): List<PlannedEdit> {
        require(edits.isNotEmpty() && edits.map { it.ref }.distinct().size == edits.size) { "Conflicting preset references" }
        return edits.groupBy { it.patch }.flatMap { (patch, values) -> zone(installation, values.map { it.ref }, patch) }
    }

    fun gradient(references: List<LedRef>, start: Rgb, end: Rgb): List<PresetEdit> {
        val distinct = references.distinct()
        return distinct.mapIndexed { index, ref ->
            val fraction = if (distinct.size <= 1) 0.0 else index.toDouble() / (distinct.size - 1)
            fun channel(a: Int, b: Int) = (a + (b - a) * fraction + 0.5).toInt()
            PresetEdit(ref, LightingPatch(rgb = Rgb(channel(start.red, end.red), channel(start.green, end.green), channel(start.blue, end.blue))))
        }
    }
}
