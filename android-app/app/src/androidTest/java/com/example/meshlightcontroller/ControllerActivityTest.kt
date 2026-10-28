package com.example.meshlightcontroller

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.meshlightcontroller.data.LightingRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ControllerActivityTest {
    @JvmField @Rule(order = 0) val hilt = HiltAndroidRule(this)
    @JvmField @Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @Inject lateinit var repository: LightingRepository

    @Test fun demoPowerUpdatesTheActiveDetailAndPreservesOtherProperties() {
        hilt.inject()
        compose.waitUntil(15_000) { !repository.state.value.loading }
        compose.runOnUiThread { repository.enterDemo() }
        compose.waitUntil(15_000) { repository.state.value.demo && !repository.state.value.loading }
        val original = repository.state.value.installation!!.nodes.first { it.name == "Floor lamp" }.aggregate!!.state
        val action = if (original.power) "Turn off" else "Turn on"
        val resultingAction = if (original.power) "Turn on" else "Turn off"
        compose.onNodeWithContentDescription("Open Floor lamp").performClick()
        compose.onNodeWithContentDescription(action).performClick()
        compose.waitUntil(5_000) {
            repository.state.value.installation!!.nodes.first { it.name == "Floor lamp" }.aggregate?.state?.power == !original.power
        }
        val actual = repository.state.value.installation!!.nodes.first { it.name == "Floor lamp" }.aggregate!!.state
        assertEquals(original.rgb, actual.rgb)
        assertEquals(original.intensity, actual.intensity)
        compose.onNodeWithContentDescription(resultingAction).assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription(resultingAction).assertExists()
    }
}
