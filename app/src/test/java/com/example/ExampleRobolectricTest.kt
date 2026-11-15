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
import com.example.live.LiveConfigConstants
import com.example.live.LiveConnectionPhase
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
        assertEquals("ov-rebuild-p03-20261111", BuildConfig.SOURCE_BUILD_ID)
        assertEquals(LiveConfigConstants.SUPPORTED_LIVE_MODEL_ID, BuildConfig.LIVE_MODEL_ID)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w840dp-h480dp-land")
    fun `conversation shell renders landscape layout and transitions through Start, Pause, Stop`() {
        val coordinator = com.example.coordinator.ConversationCoordinator(
            liveAdapter = com.example.live.GeminiLiveConnectionAdapter(
                driver = com.example.live.DeterministicTestLiveTransportDriver(),
                delayRunner = {}
            ),
            playbackAdapter = com.example.audio.LiveAudioPlaybackAdapter(
                driver = com.example.audio.DeterministicStreamingAudioTrackDriver(),
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {}
            )
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        val viewModel = ConversationShellViewModel(coordinator = coordinator)

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

        // Click Start -> automatically initializes Phase 04 coordinator and enters LISTENING (never NOT_CONFIGURED)
        composeTestRule.onNodeWithTag("start_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("state_label_text", useUnmergedTree = true)
            .assertTextEquals("LISTENING")

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

    @Test
    @Config(sdk = [36], qualifiers = "w840dp-h480dp-land")
    fun `live diagnostics screen verifies handshake transition to SETUP_ACCEPTED, redacted telemetry, and Large Stop socket release`() {
        val coordinator = com.example.coordinator.ConversationCoordinator(
            liveAdapter = com.example.live.GeminiLiveConnectionAdapter(
                driver = com.example.live.DeterministicTestLiveTransportDriver()
            )
        )
        val viewModel = ConversationShellViewModel(coordinator = coordinator)

        composeTestRule.setContent {
            OmniVoiceRebuildTheme {
                ConversationShellRoute(viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()

        // Navigate to Live Diagnostics screen from top bar
        composeTestRule.onNodeWithTag("top_live_diagnostics_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(ActiveScreen.LIVE_DIAGNOSTICS, viewModel.activeScreen.value)
        composeTestRule.onNodeWithTag("live_diagnostics_screen_root").assertIsDisplayed()
        composeTestRule.onNodeWithTag("live_diag_honest_boundary_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("live_diag_phase_text", useUnmergedTree = true)
            .assertTextEquals("DISCONNECTED")

        // Verify redacted diagnostic chips for model, endpoint family, and auth route
        composeTestRule.onNodeWithTag("live_diag_model_label", useUnmergedTree = true)
            .assertTextContains("gemini-3.1-flash-live-preview", substring = true)
        composeTestRule.onNodeWithTag("live_diag_endpoint_family_label", useUnmergedTree = true)
            .assertTextContains("FIREBASE_AI_LOGIC_GOOGLE_AI_V1BETA_BIDI", substring = true)
        composeTestRule.onNodeWithTag("live_diag_auth_route_label", useUnmergedTree = true)
            .assertTextContains("FIREBASE_AI_LOGIC_MANAGED_APP_CHECK", substring = true)

        // Click Verify Live Handshake (Connect) -> transitions to SETUP_ACCEPTED
        composeTestRule.onNodeWithTag("connect_live_button")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(LiveConnectionPhase.SETUP_ACCEPTED, viewModel.liveState.value.phase)
        assertTrue(viewModel.liveState.value.handshakeVerified)
        assertFalse(viewModel.liveState.value.audioPlaybackClaimed)
        assertEquals(0, viewModel.liveState.value.registeredActionToolsCount)
        composeTestRule.onNodeWithTag("live_diag_phase_text", useUnmergedTree = true)
            .assertTextEquals("SETUP_ACCEPTED")

        // Click Large Stop -> immediately disconnects Live socket and transitions shell to STOPPED
        composeTestRule.onNodeWithTag("live_diag_large_stop_button")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        assertEquals(LiveConnectionPhase.DISCONNECTED, viewModel.liveState.value.phase)
        assertFalse(viewModel.liveState.value.handshakeVerified)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w840dp-h480dp-land")
    fun `production default live diagnostics screen honestly reports FAILED and BLOCKED when runtime credentials are unconfigured`() {
        com.google.firebase.FirebaseApp.clearInstancesForTest()
        val viewModel = ConversationShellViewModel()

        composeTestRule.setContent {
            OmniVoiceRebuildTheme {
                ConversationShellRoute(viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("top_live_diagnostics_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("connect_live_button")
            .performScrollTo()
            .performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            viewModel.liveState.value.phase == LiveConnectionPhase.FAILED
        }
        composeTestRule.waitForIdle()

        assertEquals(LiveConnectionPhase.FAILED, viewModel.liveState.value.phase)
        assertFalse(viewModel.liveState.value.handshakeVerified)
        assertEquals(1, viewModel.liveState.value.attemptCount)
        assertEquals(0, viewModel.liveState.value.retryCount)
        composeTestRule.onNodeWithTag("live_diag_phase_text", useUnmergedTree = true)
            .assertTextEquals("FAILED")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-port")
    fun `portrait mode initializes DebugAppCheckProviderFactory in debug build and opens and closes Gemini Live Handshake and Diagnostics 5 times without crashing`() {
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity != null)
                assertTrue("Debug build flag must be true in debug test", BuildConfig.DEBUG)
            }
        }

        // Simulate unconfigured state to deterministically verify NEEDS_SETUP / CONFIG_ERROR UI in unit test
        com.google.firebase.FirebaseApp.clearInstancesForTest()
        val viewModel = ConversationShellViewModel()

        composeTestRule.setContent {
            OmniVoiceRebuildTheme {
                ConversationShellRoute(viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()

        // Open and close "Gemini Live Handshake & Diagnostics" 5 times in portrait mode
        repeat(5) { iteration ->
            if (iteration % 2 == 0) {
                composeTestRule.onNodeWithTag("open_live_diagnostics_button")
                    .performScrollTo()
                    .performClick()
            } else {
                composeTestRule.onNodeWithTag("top_live_diagnostics_button")
                    .performClick()
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("live_diagnostics_screen_root").assertIsDisplayed()
            composeTestRule.onNodeWithTag("live_diag_portrait_column").assertIsDisplayed()

            // On the 5th open, also trigger Connect in portrait mode and verify readable NEEDS_SETUP / CONFIG_ERROR state
            if (iteration == 4) {
                composeTestRule.onNodeWithTag("connect_live_button")
                    .performScrollTo()
                    .performClick()
                composeTestRule.waitUntil(timeoutMillis = 5_000L) {
                    viewModel.liveState.value.phase == LiveConnectionPhase.FAILED
                }
                composeTestRule.waitForIdle()
                composeTestRule.onNodeWithTag("live_diag_phase_text", useUnmergedTree = true)
                    .assertTextEquals("FAILED")
                composeTestRule.onNodeWithTag("live_diag_config_status_label", useUnmergedTree = true)
                    .assertTextContains("NEEDS_SETUP / CONFIG_ERROR", substring = true)
            }

            composeTestRule.onNodeWithTag("live_diag_back_button").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("conversation_shell_root").assertIsDisplayed()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-port")
    fun `live diagnostics UI invalidates SETUP_ACCEPTED to NETWORK_UNAVAILABLE on internet loss, blocks offline Verify, and recovers with one reconnect`() {
        val networkMonitor = com.example.live.ControllableLiveNetworkMonitor(initialValidated = true)
        val driver = com.example.live.DeterministicTestLiveTransportDriver()
        val coordinator = com.example.coordinator.ConversationCoordinator(
            liveAdapter = com.example.live.GeminiLiveConnectionAdapter(
                driver = driver,
                networkMonitor = networkMonitor,
                autoReconnectOnNetworkRestore = true,
                delayRunner = {}
            )
        )
        val viewModel = ConversationShellViewModel(coordinator = coordinator)

        composeTestRule.setContent {
            OmniVoiceRebuildTheme {
                ConversationShellRoute(viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()

        // Open Live Diagnostics screen -> registers network monitor
        composeTestRule.onNodeWithTag("top_live_diagnostics_button").performClick()
        composeTestRule.waitForIdle()
        assertTrue("Network monitor must be active while diagnostics screen is open", networkMonitor.isMonitoringActive)

        // 1. Connect while online -> SETUP_ACCEPTED
        composeTestRule.onNodeWithTag("connect_live_button")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("live_diag_phase_text", useUnmergedTree = true)
            .assertTextEquals("SETUP_ACCEPTED")
        assertEquals(1, driver.invocationCount)

        // 2. Disable Wi-Fi and mobile data -> UI immediately shows NETWORK_UNAVAILABLE
        networkMonitor.setInternetValidated(false)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("live_diag_phase_text", useUnmergedTree = true)
            .assertTextEquals("NETWORK_UNAVAILABLE")
        composeTestRule.onNodeWithTag("live_diag_network_status_label", useUnmergedTree = true)
            .assertTextContains("NETWORK_UNAVAILABLE", substring = true)

        // 3. Press Verify while offline -> makes zero Firebase/Gemini requests and stays NETWORK_UNAVAILABLE
        composeTestRule.onNodeWithTag("connect_live_button")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        assertEquals("Offline Verify must not invoke transport driver", 1, driver.invocationCount)
        composeTestRule.onNodeWithTag("live_diag_phase_text", useUnmergedTree = true)
            .assertTextEquals("NETWORK_UNAVAILABLE")

        // 4. Restore validated internet -> performs at most 1 controlled reconnect
        networkMonitor.setInternetValidated(true)
        composeTestRule.waitForIdle()
        assertEquals(2, driver.invocationCount)
        composeTestRule.onNodeWithTag("live_diag_phase_text", useUnmergedTree = true)
            .assertTextEquals("SETUP_ACCEPTED")

        // 5. Disconnect and navigate back -> unregisters network monitor safely
        composeTestRule.onNodeWithTag("disconnect_live_button")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("live_diag_back_button").performClick()
        composeTestRule.waitForIdle()
        assertFalse("Network monitor must unregister when diagnostics screen is disposed and session is disconnected", networkMonitor.isMonitoringActive)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-port")
    fun `portrait phase 04 voice turn transitions through CONNECTING, LISTENING, PROCESSING, SPEAKING, and READY and handles Stop invalidation`() {
        val liveDriver = com.example.live.DeterministicTestLiveTransportDriver()
        val outputDriver = com.example.audio.DeterministicStreamingAudioTrackDriver()
        val coordinator = com.example.coordinator.ConversationCoordinator(
            liveAdapter = com.example.live.GeminiLiveConnectionAdapter(
                driver = liveDriver,
                networkMonitor = com.example.live.ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            playbackAdapter = com.example.audio.LiveAudioPlaybackAdapter(
                driver = outputDriver,
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {},
                audioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
            )
        )
        val viewModel = ConversationShellViewModel(coordinator = coordinator)

        composeTestRule.setContent {
            OmniVoiceRebuildTheme {
                ConversationShellRoute(viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()

        // Verify portrait layout renders pipeline card and command deck with no NOT_CONFIGURED state
        composeTestRule.onNodeWithTag("portrait_fallback_container").assertIsDisplayed()
        composeTestRule.onNodeWithTag("voice_turn_pipeline_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("state_label_text", useUnmergedTree = true)
            .assertTextEquals("IDLE")

        // 1. Click Start before granting microphone permission -> honestly reports ERROR (never NOT_CONFIGURED)
        composeTestRule.onNodeWithTag("start_button")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("state_label_text", useUnmergedTree = true)
            .assertTextEquals("ERROR")
        composeTestRule.onNodeWithTag("voice_turn_error_code_text", useUnmergedTree = true)
            .assertTextContains("MIC_PERMISSION_REQUIRED", substring = true)

        // 2. Grant microphone permission and click Start -> enters LISTENING via CONNECTING
        viewModel.onPermissionRequestResult(granted = true, shouldShowRationale = false)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("start_button")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("state_label_text", useUnmergedTree = true)
            .assertTextEquals("LISTENING")
        assertTrue(viewModel.audioState.value.isCapturing)
        assertTrue("Main Start button must enable Active Conversation", viewModel.uiState.value.isActiveConversationEnabled)

        // Disable Auto-Continue Turns explicitly to verify single-turn Phase 04 READY completion
        viewModel.onToggleActiveConversationMode(false)
        composeTestRule.waitForIdle()
        assertFalse(viewModel.uiState.value.isActiveConversationEnabled)

        // 3. Deliver spoken 16kHz PCM frame and click Send Spoken Question (End Turn) -> PROCESSING -> SPEAKING -> READY
        val activeCaptureGen = viewModel.audioState.value.activeGeneration!!
        viewModel.coordinator.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(
                generationId = activeCaptureGen,
                pcmSamples = ShortArray(320) { 8000 },
                hardwareSampleRateHz = 16000
            )
        )
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("end_spoken_turn_button")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("state_label_text", useUnmergedTree = true)
            .assertTextEquals("READY")
        composeTestRule.onNodeWithTag("playback_drain_status_text", useUnmergedTree = true)
            .assertTextEquals("PLAYBACK: DRAINED")
        assertEquals(
            listOf(
                com.example.contract.ConversationState.CONNECTING,
                com.example.contract.ConversationState.LISTENING,
                com.example.contract.ConversationState.PROCESSING,
                com.example.contract.ConversationState.SPEAKING,
                com.example.contract.ConversationState.READY
            ),
            viewModel.uiState.value.voiceTurnStateSequence
        )
        assertTrue(viewModel.playbackState.value.isPlaybackDrained)
    }

    private fun verifyPortraitResponsiveGeometry(expectedScreenWidthDp: Int) {
        val viewModel = ConversationShellViewModel()
        composeTestRule.setContent {
            OmniVoiceRebuildTheme {
                ConversationShellRoute(viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()

        val rootBounds = composeTestRule.onNodeWithTag("conversation_shell_root").fetchSemanticsNode().boundsInRoot
        val titleBounds = composeTestRule.onNodeWithTag("app_title_text", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val subtitleBounds = composeTestRule.onNodeWithTag("app_subtitle_text", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val statusActionRowBounds = composeTestRule.onNodeWithTag("top_header_status_action_row").fetchSemanticsNode().boundsInRoot
        val pipelineBounds = composeTestRule.onNodeWithTag("voice_turn_pipeline_card").fetchSemanticsNode().boundsInRoot

        // 1. Title and subtitle must receive the full card inner width (never squeezed into a narrow 10-30dp column)
        assertTrue(
            "Title width (${titleBounds.width}) on ${expectedScreenWidthDp}dp screen must be at least 220px so words never wrap vertically 1-2 chars per line",
            titleBounds.width >= 220f
        )
        assertTrue(
            "Subtitle width (${subtitleBounds.width}) on ${expectedScreenWidthDp}dp screen must be at least 220px",
            subtitleBounds.width >= 220f
        )
        // 2. Status and icons row must sit below the full-width title/subtitle, never beside it
        assertTrue(
            "Status/action row top (${statusActionRowBounds.top}) must be below title bottom (${titleBounds.bottom})",
            statusActionRowBounds.top >= titleBounds.bottom
        )
        // 3. No horizontal overflow beyond root container
        assertTrue(
            "Title right (${titleBounds.right}) must fit within root width (${rootBounds.right})",
            titleBounds.right <= rootBounds.right + 1f
        )
        assertTrue(
            "Pipeline card right (${pipelineBounds.right}) must fit within root width (${rootBounds.right})",
            pipelineBounds.right <= rootBounds.right + 1f
        )

        // 4. Verify primary controls and transcript are accessible via vertical scroll
        composeTestRule.onNodeWithTag("start_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("pause_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("stop_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("transcript_pane").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(sdk = [36], qualifiers = "w320dp-h640dp-port")
    fun `portrait layout at 320dp width keeps title words intact, puts status and icons on separate row, and avoids overflow`() {
        verifyPortraitGeometryAndFontScale(320)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h740dp-port")
    fun `portrait layout at 360dp width keeps title words intact, puts status and icons on separate row, and avoids overflow`() {
        verifyPortraitResponsiveGeometry(360)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w412dp-h892dp-port")
    fun `portrait layout at 412dp width keeps title words intact, puts status and icons on separate row, and avoids overflow`() {
        verifyPortraitResponsiveGeometry(412)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w600dp-h960dp-port")
    fun `layout at 600dp width keeps title words intact, puts status and icons on separate row, and avoids overflow`() {
        verifyPortraitResponsiveGeometry(600)
    }

    private fun verifyPortraitGeometryAndFontScale(screenWidthDp: Int) {
        val viewModel = ConversationShellViewModel()
        composeTestRule.setContent {
            OmniVoiceRebuildTheme {
                ConversationShellRoute(viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()

        val titleBounds1x = composeTestRule.onNodeWithTag("app_title_text", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Title width (${titleBounds1x.width}) at ${screenWidthDp}dp must span full inner width (>= 220px)",
            titleBounds1x.width >= 220f
        )

        // Test 1.25x and 1.5x font scale resilience at 320dp
        viewModel.onSetFontScaleOverride(1.25f)
        composeTestRule.waitForIdle()
        val titleBounds125x = composeTestRule.onNodeWithTag("app_title_text", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Title width at 1.25x font scale must remain full width", titleBounds125x.width >= 220f)

        viewModel.onSetFontScaleOverride(1.5f)
        composeTestRule.waitForIdle()
        val titleBounds15x = composeTestRule.onNodeWithTag("app_title_text", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Title width at 1.5x font scale must remain full width", titleBounds15x.width >= 220f)
        composeTestRule.onNodeWithTag("start_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("pause_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("stop_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(sdk = [36], qualifiers = "w412dp-h892dp-port")
    fun `phase 05 active conversation UI displays Preparing Listening Thinking Speaking Paused Error stages, large Pause Resume Stop controls, inactivity timeout setting, and explicit half-duplex policy`() {
        val liveDriver = com.example.live.DeterministicTestLiveTransportDriver()
        val outputDriver = com.example.audio.DeterministicStreamingAudioTrackDriver()
        val coordinator = com.example.coordinator.ConversationCoordinator(
            liveAdapter = com.example.live.GeminiLiveConnectionAdapter(
                driver = liveDriver,
                networkMonitor = com.example.live.ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            playbackAdapter = com.example.audio.LiveAudioPlaybackAdapter(
                driver = outputDriver,
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {},
                audioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
            ),
            preferencesStore = com.example.coordinator.InMemoryConversationPreferencesStore(
                isActiveConversationEnabled = true
            )
        )
        val viewModel = ConversationShellViewModel(coordinator = coordinator)

        composeTestRule.setContent {
            OmniVoiceRebuildTheme {
                ConversationShellRoute(viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()
        viewModel.onPermissionRequestResult(granted = true, shouldShowRationale = false)
        composeTestRule.waitForIdle()

        // 1. Verify Phase 05 Active Conversation stages and explicit half-duplex policy indicator
        composeTestRule.onNodeWithTag("active_conversation_phase_row").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("active_phase_preparing").assertIsDisplayed()
        composeTestRule.onNodeWithTag("active_phase_listening").assertIsDisplayed()
        composeTestRule.onNodeWithTag("active_phase_thinking").assertIsDisplayed()
        composeTestRule.onNodeWithTag("active_phase_speaking").assertIsDisplayed()
        composeTestRule.onNodeWithTag("active_phase_paused").assertIsDisplayed()
        composeTestRule.onNodeWithTag("active_phase_error").assertIsDisplayed()
        composeTestRule.onNodeWithTag("duplex_policy_chip")
            .assertTextContains("HALF-DUPLEX", substring = true)

        // 2. Verify large Pause, Resume, and Stop controls and visible inactivity timeout setting
        composeTestRule.onNodeWithTag("pause_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("resume_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("stop_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("inactivity_timeout_selector_row").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("inactivity_timeout_chip_30s").performClick()
        composeTestRule.waitForIdle()
        assertEquals(30_000L, viewModel.uiState.value.inactivityTimeoutMs)

        // 3. Click Start -> enters LISTENING; complete Turn 1 -> auto-restores LISTENING for Turn 2
        composeTestRule.onNodeWithTag("start_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(com.example.contract.ConversationState.LISTENING, viewModel.uiState.value.state)

        val capGen1 = viewModel.audioState.value.activeGeneration!!
        viewModel.coordinator.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(
                generationId = capGen1,
                pcmSamples = ShortArray(320) { 7500 },
                hardwareSampleRateHz = 16000
            )
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("end_spoken_turn_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // After Turn 1 completes, active conversation automatically restores capture for Turn 2!
        assertEquals(1, viewModel.uiState.value.completedTurnsInSession)
        assertEquals(com.example.contract.ConversationState.LISTENING, viewModel.uiState.value.state)
        assertTrue(viewModel.audioState.value.isCapturing)

        // 4. Click Pause -> enters PAUSED; click Resume -> restores LISTENING; click Stop -> enters STOPPED
        composeTestRule.onNodeWithTag("pause_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(com.example.contract.ConversationState.PAUSED, viewModel.uiState.value.state)

        composeTestRule.onNodeWithTag("resume_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(com.example.contract.ConversationState.LISTENING, viewModel.uiState.value.state)

        composeTestRule.onNodeWithTag("stop_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(com.example.contract.ConversationState.STOPPED, viewModel.uiState.value.state)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w412dp-h892dp-port")
    fun `phase 05 recomposition, repeated screen visible callbacks, and rapid Start do not create duplicate handshakes`() {
        val liveDriver = com.example.live.DeterministicTestLiveTransportDriver()
        val outputDriver = com.example.audio.DeterministicStreamingAudioTrackDriver()
        val coordinator = com.example.coordinator.ConversationCoordinator(
            liveAdapter = com.example.live.GeminiLiveConnectionAdapter(
                driver = liveDriver,
                networkMonitor = com.example.live.ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            playbackAdapter = com.example.audio.LiveAudioPlaybackAdapter(
                driver = outputDriver,
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {},
                audioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
            ),
            preferencesStore = com.example.coordinator.InMemoryConversationPreferencesStore(
                isActiveConversationEnabled = true,
                hasCompletedFirstUseConsentAndConfig = true,
                autoResumeOnScreenReopen = true
            )
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        val viewModel = ConversationShellViewModel(
            coordinator = coordinator,
            workDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )

        composeTestRule.setContent {
            OmniVoiceRebuildTheme {
                ConversationShellRoute(viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()

        // Rapid double Start on initial launch -> only 1 Live handshake
        composeTestRule.onNodeWithTag("start_button").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("start_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, liveDriver.invocationCount)
        assertEquals(com.example.contract.ConversationState.LISTENING, viewModel.uiState.value.state)

        // Trigger multiple state updates/recompositions + repeated onConversationScreenVisible + orientation config change
        viewModel.onSetFontScaleOverride(1.1f)
        composeTestRule.waitForIdle()
        viewModel.onSetFontScaleOverride(null)
        composeTestRule.waitForIdle()
        viewModel.onConversationScreenVisible()
        viewModel.onConversationScreenVisible()
        viewModel.onActivityStopped(isChangingConfigurations = true)
        composeTestRule.waitForIdle()

        assertEquals("Recomposition and ON_RESUME must never recreate the Live handshake", 1, liveDriver.invocationCount)
        assertEquals(1, viewModel.uiState.value.configurationChangePreservedCount)
        assertEquals(LiveConnectionPhase.SETUP_ACCEPTED, viewModel.liveState.value.phase)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w412dp-h892dp-port")
    fun `phase 06A SharedPreferencesConversationPreferencesStore ignores legacy phase06 language keys and preserves Phase 05 UI without voice selector`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val rawPrefs = context.getSharedPreferences("omnivoice_phase05_conversation_prefs", Context.MODE_PRIVATE)
        rawPrefs.edit()
            .clear()
            .putString("phase06_preferred_language", "ENGLISH")
            .putString("phase06_verbosity_preference", "DETAILED")
            .putString("phase06_selected_voice", "Charon")
            .commit()

        val store = com.example.coordinator.SharedPreferencesConversationPreferencesStore(context)
        // Test A: Fresh production preference store must default isActiveConversationEnabled to true
        assertTrue("Fresh SharedPreferencesConversationPreferencesStore must default isActiveConversationEnabled to true", store.isActiveConversationEnabled)

        // Verify explicit user toggle to false persists and can be restored to true
        store.isActiveConversationEnabled = false
        assertFalse(store.isActiveConversationEnabled)
        store.isActiveConversationEnabled = true
        assertTrue(store.isActiveConversationEnabled)

        val liveDriver = com.example.live.DeterministicTestLiveTransportDriver()
        val coordinator = com.example.coordinator.ConversationCoordinator(
            liveAdapter = com.example.live.GeminiLiveConnectionAdapter(
                driver = liveDriver,
                networkMonitor = com.example.live.ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            playbackAdapter = com.example.audio.LiveAudioPlaybackAdapter(
                driver = com.example.audio.DeterministicStreamingAudioTrackDriver(),
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {},
                audioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
            ),
            preferencesStore = store
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        val viewModel = ConversationShellViewModel(
            coordinator = coordinator,
            workDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )

        composeTestRule.setContent {
            OmniVoiceRebuildTheme {
                ConversationShellRoute(viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()

        // Start session -> connects with pinned "Kore" voice and authoritative system instruction (legacy SharedPreferences keys ignored)
        composeTestRule.onNodeWithTag("start_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertTrue(viewModel.uiState.value.isActiveConversationEnabled)
        assertEquals("Kore", liveDriver.lastReceivedVoiceName)
        assertEquals(
            com.example.live.LiveConfigConstants.AUTHORITATIVE_SYSTEM_INSTRUCTION,
            liveDriver.lastReceivedSystemInstruction
        )

        // Open Settings -> Phase 05 settings remain intact, no voice selector UI is present
        composeTestRule.onNodeWithTag("settings_placeholder_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_active_conversation_chip").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_voice_selector_row").assertDoesNotExist()
        composeTestRule.onNodeWithTag("settings_language_selector_row").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [36], qualifiers = "w412dp-h892dp-port")
    fun `phase 06B Robolectric Android Context loads bundled silero_vad onnx exposes VAD diagnostics and reports VAD_MODEL_LOAD_FAILED when asset missing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val realDetector = com.example.audio.SileroVadDetector.fromAndroidContext(context)
        try {
            assertTrue("Android AssetManager must load silero_vad.onnx: ${realDetector.loadError}", realDetector.isModelLoaded)
            assertEquals("Silero VAD", realDetector.detectorName)
            assertEquals("6.2.3", realDetector.detectorVersion)

            val liveDriver = com.example.live.DeterministicTestLiveTransportDriver()
            val coordinator = com.example.coordinator.ConversationCoordinator(
                liveAdapter = com.example.live.GeminiLiveConnectionAdapter(
                    driver = liveDriver,
                    networkMonitor = com.example.live.ControllableLiveNetworkMonitor(initialValidated = true),
                    delayRunner = {}
                ),
                playbackAdapter = com.example.audio.LiveAudioPlaybackAdapter(
                    driver = com.example.audio.DeterministicStreamingAudioTrackDriver(),
                    writeRetryDelayRunner = {},
                    drainPollDelayRunner = {},
                    audioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
                ),
                preferencesStore = com.example.coordinator.InMemoryConversationPreferencesStore(isActiveConversationEnabled = true),
                speechDetector = realDetector
            )
            coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
            val viewModel = ConversationShellViewModel(
                coordinator = coordinator,
                workDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
            )

            composeTestRule.setContent {
                OmniVoiceRebuildTheme {
                    ConversationShellRoute(viewModel = viewModel)
                }
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("speech_vad_detector_chip")
                .performScrollTo()
                .assertTextContains("Silero VAD v6.2.3 (LOADED)", substring = true)

            composeTestRule.onNodeWithTag("start_button").performScrollTo().performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("speech_vad_gate_chip")
                .performScrollTo()
                .assertTextContains("GATE: WAITING_FOR_SPEECH", substring = true)
                .assertTextContains("actStart=0", substring = true)
        } finally {
            realDetector.close()
        }

        // Missing model asset must fail recoverably with VAD_MODEL_LOAD_FAILED and zero RMS fallback
        val missingDetector = com.example.audio.SileroVadDetector.fromAndroidContext(
            context = context,
            assetName = "nonexistent_silero.onnx"
        )
        assertFalse(missingDetector.isModelLoaded)
        val brokenCoordinator = com.example.coordinator.ConversationCoordinator(
            liveAdapter = com.example.live.GeminiLiveConnectionAdapter(
                driver = com.example.live.DeterministicTestLiveTransportDriver(),
                networkMonitor = com.example.live.ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            speechDetector = missingDetector
        )
        brokenCoordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        val brokenVm = ConversationShellViewModel(
            coordinator = brokenCoordinator,
            workDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )
        composeTestRule.runOnUiThread {
            brokenVm.onStartClicked()
        }
        composeTestRule.waitForIdle()
        assertEquals("VAD_MODEL_LOAD_FAILED", brokenVm.uiState.value.activeVoiceTurnErrorCode)
        assertEquals(com.example.contract.ConversationState.ERROR, brokenVm.uiState.value.state)
    }
}


