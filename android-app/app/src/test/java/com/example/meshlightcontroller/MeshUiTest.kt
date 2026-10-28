package com.example.meshlightcontroller

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.example.meshlightcontroller.core.model.*
import com.example.meshlightcontroller.ui.*
import com.example.meshlightcontroller.ui.theme.MeshLightControllerTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36],application=Application::class,qualifiers="w390dp-h844dp")
class MeshUiTest {
    @get:Rule val compose=createComposeRule()

    @Test fun welcomingDemoEntryIsExplicit() {
        val actions=mutableListOf<UiAction>()
        compose.setContent {MeshLightControllerTheme {MeshLightScreen(ControllerState(),onAction={actions+=it})}}
        compose.onNodeWithText("Explore a demo home").performScrollTo().performClick()
        assertEquals(listOf(UiAction.EnterDemo),actions)
        compose.onNodeWithText("Create your home").assertExists()
    }

    @Test fun powerControlChangesOnlyPower() {
        val actions=mutableListOf<UiAction>()
        val state=sampleHome()
        val node=state.installation!!.nodes.first()
        compose.setContent {MeshLightControllerTheme {Surface {LightDetailScreen(node,state,onAction={actions+=it},onNavigate={},onBack={})}}}
        compose.onNodeWithContentDescription("Turn off").performClick()
        val edit=actions.filterIsInstance<UiAction.EditLight>().single()
        assertEquals(setOf(LedTarget.All),edit.targets)
        assertEquals(LightingPatch(power=false),edit.patch)
    }

    @Test fun offlineChangesHaveExplicitApplyAndDiscard() {
        val state=sampleHome().copy(connection=ConnectionStatus.DISCONNECTED)
        val node=state.installation!!.nodes.first().copy(drafts=mapOf(LedTarget.All to LightingPatch(rgb=Rgb(1,2,3))))
        val actions=mutableListOf<UiAction>()
        compose.setContent {MeshLightControllerTheme {Surface {LightDetailScreen(node,state,onAction={actions+=it},onNavigate={},onBack={})}}}
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Apply changes"))
        compose.onNodeWithText("Apply changes").assertIsNotEnabled()
        compose.onNodeWithText("Discard preview").performClick()
        assertTrue(actions.any {it is UiAction.DiscardDrafts})
        assertFalse(actions.any {it is UiAction.ApplyDrafts})
    }

    @Test fun unknownValuesAreNotPresentedAsConfirmedBlackOrOff() {
        compose.setContent {MeshLightControllerTheme {Surface {LightingControls(null,onEdit={_,_->})}}}
        compose.onAllNodesWithText("Not read yet").assertCountEquals(3)
        compose.onNodeWithText("On").assertIsEnabled()
        compose.onNodeWithText("Off").assertIsEnabled()
    }

    @Test fun resetRequiresExplicitConfirmation() {
        val state=sampleHome()
        val actions=mutableListOf<UiAction>()
        compose.setContent {MeshLightControllerTheme {Surface {DeviceScreen(state.installation!!.nodes.first(),state,null,onAction={actions+=it},onBack={})}}}
        compose.onNodeWithText("Factory reset light").performScrollTo().performClick()
        assertTrue(actions.none {it is UiAction.Reset})
        compose.onNodeWithText("Factory reset this light?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(actions.none {it is UiAction.Reset})
    }

    @Test fun activeDetailSurvivesSavedInstanceRestoration() {
        val restoration=StateRestorationTester(compose)
        restoration.setContent {MeshLightControllerTheme {MeshLightScreen(sampleHome())}}
        compose.onNodeWithText("Floor lamp").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Power").assertExists()
        compose.onNodeWithText("Floor lamp").assertExists()
    }

    @Test fun activeNavigationDetailObservesUpdatedLightState() {
        val state=androidx.compose.runtime.mutableStateOf(sampleHome())
        compose.setContent {MeshLightControllerTheme {MeshLightScreen(state.value)}}
        compose.onNodeWithText("Floor lamp").performClick()
        compose.onNodeWithContentDescription("Turn off").assertExists()
        compose.runOnIdle {
            val home=state.value.installation!!
            state.value=state.value.copy(installation=home.copy(nodes=home.nodes.mapIndexed {index,node ->
                if(index==0) node.copy(aggregate=node.aggregate!!.copy(state=node.aggregate.state.copy(power=false))) else node
            }))
        }
        compose.onNodeWithContentDescription("Turn on").assertExists()
    }

    @Test fun editorSelectionSurvivesSwitchingTabs() {
        compose.setContent {MeshLightControllerTheme {MeshLightScreen(sampleHome())}}
        compose.onNodeWithText("Floor lamp").performClick()
        compose.onNodeWithContentDescription("Open LED editor").performClick()
        compose.onNodeWithText("Select LEDs").performClick()
        compose.onNodeWithContentDescription("LED 2, On").performClick()
        compose.onNodeWithText("2 selected").assertExists()
        compose.onNodeWithText("Groups").performClick()
        compose.onNodeWithText("Lights").performClick()
        compose.onNodeWithText("2 selected").assertExists()
    }

    @Test fun primaryCreationActionsHaveAccessibleNames() {
        compose.setContent {MeshLightControllerTheme {MeshLightScreen(sampleHome())}}
        compose.onNodeWithContentDescription("Add a light").assertHasClickAction()
        compose.onNodeWithText("Groups").performClick()
        compose.onNodeWithContentDescription("Create a room").assertHasClickAction()
        compose.onNodeWithText("LED zones").performClick()
        compose.onNodeWithContentDescription("Create an LED zone").assertHasClickAction()
        compose.onNodeWithText("Presets").performClick()
        compose.onNodeWithContentDescription("Create a preset").assertHasClickAction()
    }

    @Test fun bottomTabsKeepTheirOwnNavigationStacks() {
        compose.setContent {MeshLightControllerTheme {MeshLightScreen(sampleHome())}}
        compose.onNodeWithText("Floor lamp").performClick()
        compose.onNodeWithText("Groups").performClick()
        compose.onNodeWithText("Living room").assertExists()
        compose.onNodeWithText("Lights").performClick()
        compose.onNodeWithText("Power").assertExists()
        compose.onNodeWithText("Floor lamp").assertExists()
    }
}
