package com.example.meshlightcontroller

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
class SliderFeedbackTest {
    @get:Rule val compose=createComposeRule()

    @Test fun pendingDraftsOperationsAndConnectionChangesKeepSliderInPlace() {
        val state=mutableStateOf(sampleHome())
        compose.setContent {MeshLightControllerTheme {MeshLightScreen(state.value)}}
        compose.onNodeWithText("Floor lamp").performClick()
        val slider=compose.onNodeWithContentDescription("Hue")
        slider.performScrollTo()
        val before=slider.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val home=state.value.installation!!
            state.value=state.value.copy(
                pendingTargets=setOf("${home.nodes.first().uuid}/${LedTarget.All}"),
                installation=home.copy(nodes=home.nodes.mapIndexed {index,node ->
                    if(index==0) node.copy(drafts=mapOf(LedTarget.All to LightingPatch(rgb=Rgb(10,90,210)))) else node
                })
            )
        }
        assertEquals("Pending edits and drafts must not move the slider",before,slider.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle {
            state.value=state.value.copy(pendingTargets=emptySet(),operation=OperationProgress(OperationKind.APPLY))
        }
        assertEquals("Operation feedback must not resize the page",before,slider.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle {
            state.value=state.value.copy(operation=null,connection=ConnectionStatus.DISCONNECTED)
        }
        assertEquals("Offline feedback must not move the slider",before,slider.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle {
            val home=state.value.installation!!
            state.value=state.value.copy(connection=ConnectionStatus.CONNECTED,installation=home.copy(nodes=home.nodes.map {it.copy(drafts=emptyMap())}))
        }
        assertEquals("Confirmed edits must not move the slider",before,slider.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun draggingContinuesWhenSendingOrErrorsAppear() {
        val state=mutableStateOf(sampleHome())
        val edits=mutableListOf<UiAction.EditLight>()
        compose.setContent {MeshLightControllerTheme {MeshLightScreen(state.value,onAction={action ->
            if(action is UiAction.EditLight) {
                edits+=action
                val home=state.value.installation!!
                state.value=state.value.copy(pendingTargets=setOf("${action.uuid}/${LedTarget.All}"),
                    installation=home.copy(nodes=home.nodes.map {node ->
                        if(node.uuid==action.uuid) node.copy(drafts=mapOf(LedTarget.All to action.patch)) else node
                    }))
            }
        })}}
        compose.onNodeWithText("Floor lamp").performClick()
        val slider=compose.onNodeWithContentDescription("Hue")
        slider.performScrollTo()
        val before=slider.fetchSemanticsNode().boundsInRoot
        slider.performTouchInput {down(center);moveBy(Offset(40f,0f))}
        compose.onNodeWithContentDescription("Cancel pending edits").assertExists()
        assertEquals(before,slider.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle {state.value=state.value.copy(error="Light did not respond")}
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        assertEquals(before,slider.fetchSemanticsNode().boundsInRoot)
        slider.performTouchInput {moveBy(Offset(40f,0f));up()}
        assertTrue(edits.any {!it.final})
        assertTrue(edits.last().final)
    }

    @Test fun sendingBadgeCanCancelWithoutStartingAConnection() {
        val actions=mutableListOf<UiAction>()
        val state=sampleHome().copy(pendingTargets=setOf("pending"))
        compose.setContent {MeshLightControllerTheme {MeshLightScreen(state,onAction={actions+=it})}}
        compose.onNodeWithContentDescription("Cancel pending edits").performClick()
        assertEquals(listOf(UiAction.CancelOperation),actions)
    }

    @Test fun errorsLeaveControlsInteractiveAndCanBeDismissed() {
        val state=mutableStateOf(sampleHome())
        val actions=mutableListOf<UiAction>()
        compose.setContent {MeshLightControllerTheme {MeshLightScreen(state.value,onAction={
            actions+=it
            if(it==UiAction.DismissError) state.value=state.value.copy(error=null)
        })}}
        compose.onNodeWithText("Floor lamp").performClick()
        val slider=compose.onNodeWithContentDescription("Hue")
        slider.performScrollTo()
        val before=slider.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {state.value=state.value.copy(error="Light did not respond")}
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithText("Light did not respond").assertIsDisplayed()
        assertEquals(before,slider.fetchSemanticsNode().boundsInRoot)
        slider.performSemanticsAction(SemanticsActions.SetProgress) {it(180f)}
        assertTrue(actions.any {it is UiAction.EditLight && it.patch.rgb!=null})
        compose.onNodeWithContentDescription("Dismiss").performClick()
        compose.onNodeWithText("Light did not respond").assertDoesNotExist()
        assertTrue(UiAction.DismissError in actions)
    }

    @Test fun mixedAndUnknownColorFeedbackDoesNotMoveSliders() {
        val rgb=mutableStateOf<Rgb?>(null)
        val mixed=mutableStateOf(true)
        compose.setContent {MeshLightControllerTheme {Surface {ColorEditor(rgb.value,mixed=mixed.value,onChange={_,_->})}}}
        val labels=listOf("Hue","Saturation","Color lightness")
        val before=labels.map {compose.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot}
        compose.runOnIdle {rgb.value=Rgb(10,90,210);mixed.value=false}
        assertEquals(before,labels.map {compose.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot})
    }

    @Test fun roomVerificationFailuresKeepSliderInPlace() {
        val state=sampleHome()
        val room=mutableStateOf(state.installation!!.rooms.first())
        compose.setContent {MeshLightControllerTheme {Surface {RoomDetailScreen(room.value,state,onAction={},onNavigate={},onBack={})}}}
        val slider=compose.onNodeWithContentDescription("Hue")
        slider.performScrollTo()
        val before=slider.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {room.value=room.value.copy(controlFailures=mapOf("light" to "Light did not respond"))}
        assertEquals(before,slider.fetchSemanticsNode().boundsInRoot)
    }
}
