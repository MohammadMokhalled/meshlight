package com.example.meshlightcontroller

import com.example.meshlightcontroller.core.control.*
import com.example.meshlightcontroller.core.model.*
import org.junit.Assert.*
import org.junit.Test

class PlannerTest {
    private val node = NodeInfo("a", 2, "Strip", capabilities = Capabilities(1, 339, 63, 100), stage = SetupStage.READY)
    private val installation = Installation(networkId = "n", name = "Home", nodes = listOf(node))
    @Test fun onlyCompleteSelectionUsesAllTarget() {
        val patch = LightingPatch(intensity = 30)
        val partial = CommandPlanner.zone(installation, listOf(LedRef("a", 0), LedRef("a", 256), LedRef("a", 256)), patch)
        assertEquals(listOf(0, 256), partial.map { it.target })
        val complete = CommandPlanner.zone(installation, List(339) { LedRef("a", it) }, patch)
        assertEquals(listOf(PlannedEdit("a", LedTarget.All, patch)), complete)
    }
    @Test fun invalidOrConflictingReferencesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { CommandPlanner.zone(installation, listOf(LedRef("a", 339)), LightingPatch(power = true)) }
        val edit = PresetEdit(LedRef("a", 0), LightingPatch(power = true))
        assertThrows(IllegalArgumentException::class.java) { CommandPlanner.preset(installation, listOf(edit, edit)) }
    }
    @Test fun staticGradientHasExactEndpointsAndPreservesOtherProperties() {
        val pattern = CommandPlanner.gradient(List(3) { LedRef("a", it) }, Rgb(255, 0, 0), Rgb(0, 0, 255))
        assertEquals(listOf(Rgb(255, 0, 0), Rgb(128, 0, 128), Rgb(0, 0, 255)), pattern.map { it.patch.rgb })
        assertTrue(pattern.all { it.patch.power == null && it.patch.intensity == null })
    }
}
