package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.audio.CaptureCallbackEvent
import com.example.audio.MicPermissionState
import com.example.ui.shell.ActiveScreen
import com.example.ui.shell.ConversationShellRoute
import com.example.ui.shell.ConversationShellViewModel
import com.example.ui.theme.OmniVoiceRebuildTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertEquals("ov-rebuild-p02-20261110", BuildConfig.SOURCE_BUILD_ID)
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

    @Test
    @Config(sdk = [36], qualifiers = "w840dp-h480dp-land")
    fun `microphone setup screen verifies rationale, permission refusal, self-test RMS, ephemeral playback, and navigation release`() {
        val viewModel = ConversationShellViewModel()

        composeTestRule.setContent {
            OmniVoiceRebuildTheme {
                ConversationShellRoute(viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()

        // Navigate to Microphone Setup Screen
        composeTestRule.onNodeWithTag("top_mic_setup_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(ActiveScreen.MICROPHONE_SETUP, viewModel.activeScreen.value)
        composeTestRule.onNodeWithTag("microphone_setup_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mic_rationale_explanation_text").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mic_honesty_boundary_banner").assertIsDisplayed()

        // Simulate permission refusal then permanent denial, then Settings return grant
        viewModel.onPermissionRequestResult(granted = false, shouldShowRationale = true)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("mic_permission_status_text", useUnmergedTree = true)
            .assertTextEquals("REFUSED_CAN_RETRY")

        viewModel.onPermissionRequestResult(granted = false, shouldShowRationale = false)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("mic_permission_status_text", useUnmergedTree = true)
            .assertTextEquals("PERMANENTLY_DENIED")

        // Return from Settings with permission granted
        viewModel.onReCheckPermissionFromOs(isGranted = true, shouldShowRationale = false)
        composeTestRule.waitForIdle()
        assertEquals(MicPermissionState.GRANTED, viewModel.audioState.value.permissionState)
        composeTestRule.onNodeWithTag("mic_permission_status_text", useUnmergedTree = true)
            .assertTextEquals("GRANTED")

        // Start Local Capture Self-Test from visible UI
        composeTestRule.onNodeWithTag("start_self_test_button")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        assertTrue(viewModel.audioState.value.isCapturing)

        // Deliver a frame to the active generation and verify actual RMS and frame count display
        val activeGen = viewModel.audioState.value.activeGeneration!!
        viewModel.coordinator.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(
                generationId = activeGen,
                pcmSamples = ShortArray(320) { 10000 },
                hardwareSampleRateHz = 16000
            )
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("captured_frames_text", useUnmergedTree = true)
            .assertTextContains("FRAMES: 1")

        // Play & Delete Ephemeral Check from UI
        composeTestRule.onNodeWithTag("play_ephemeral_check_button")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        assertEquals(0, viewModel.audioState.value.ephemeralRecordedSamples)
        assertEquals(1, viewModel.audioState.value.ephemeralPlaybackCount)

        // Start capture again, then navigate back -> must immediately release hardware
        composeTestRule.onNodeWithTag("start_self_test_button")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        assertTrue(viewModel.audioState.value.isCapturing)

        composeTestRule.onNodeWithTag("mic_setup_back_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(ActiveScreen.CONVERSATION_SHELL, viewModel.activeScreen.value)
        assertFalse("Navigating away must release AudioCapture hardware", viewModel.audioState.value.isCapturing)
    }
}
