package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.ui.shell.ConversationShellRoute
import com.example.ui.shell.ConversationShellViewModel
import com.example.ui.theme.OmniVoiceRebuildTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `verify application id, app_name, and BuildConfig metadata`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("OmniVoice Rebuild", context.getString(R.string.app_name))
        assertEquals("com.kazim.omnivoice.rebuild", BuildConfig.APPLICATION_ID)
        assertEquals("ov-rebuild-p01-20261109", BuildConfig.SOURCE_BUILD_ID)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w840dp-h480dp-land")
    fun `conversation shell renders landscape layout and transitions through Start, Pause, Stop`() {
        val viewModel = ConversationShellViewModel()

        composeTestRule.setContent {
            OmniVoiceRebuildTheme {
                ConversationShellRoute(viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()

        // Landscape split container & initial IDLE state
        composeTestRule.onNodeWithTag("landscape_split_container").assertIsDisplayed()
        composeTestRule.onNodeWithTag("state_label_text", useUnmergedTree = true)
            .assertTextEquals("IDLE")
        composeTestRule.onNodeWithTag("mic_honest_indicator").assertIsDisplayed()

        // Click Start -> honest NOT_CONFIGURED state
        composeTestRule.onNodeWithTag("start_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("state_label_text", useUnmergedTree = true)
            .assertTextEquals("NOT_CONFIGURED")

        // Click Pause -> PAUSED state
        composeTestRule.onNodeWithTag("pause_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("state_label_text", useUnmergedTree = true)
            .assertTextEquals("PAUSED")

        // Click Stop -> STOPPED state
        composeTestRule.onNodeWithTag("stop_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("state_label_text", useUnmergedTree = true)
            .assertTextEquals("STOPPED")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-port")
    fun `conversation shell supports portrait fallback and accessibility font scaling`() {
        val viewModel = ConversationShellViewModel()

        composeTestRule.setContent {
            OmniVoiceRebuildTheme {
                ConversationShellRoute(viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()

        // Portrait fallback container is active
        composeTestRule.onNodeWithTag("portrait_fallback_container").assertIsDisplayed()

        // Open Settings placeholder dialog and switch font scale to 1.5x
        composeTestRule.onNodeWithTag("settings_placeholder_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_placeholder_dialog").assertIsDisplayed()

        composeTestRule.onNodeWithTag("font_scale_chip_1.5x")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        assertEquals(1.5f, viewModel.fontScaleOverride.value)

        composeTestRule.onNodeWithTag("settings_close_button").performClick()
        composeTestRule.waitForIdle()

        // Controls remain accessible at 1.5x font scale in portrait fallback
        composeTestRule.onNodeWithTag("start_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("pause_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("stop_button").assertIsDisplayed()
    }
}
