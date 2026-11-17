package com.example

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioCapture
import com.example.audio.AudioHardwareDriver
import com.example.audio.CaptureCallbackEvent
import com.example.audio.CaptureGenerationId
import com.example.audio.DeterministicStreamingAudioTrackDriver
import com.example.audio.HardwareStartResult
import com.example.audio.LiveAudioPlaybackAdapter
import com.example.audio.MicPermissionState
import com.example.audio.PlaybackCallbackEvent
import com.example.audio.PlaybackGenerationId
import com.example.contract.ConversationState
import com.example.contract.CoordinatorCommand
import com.example.contract.SessionId
import com.example.contract.TurnId
import com.example.coordinator.ConversationCoordinator
import com.example.coordinator.safeMonotonicDurationMs
import com.example.live.ControllableLiveNetworkMonitor
import com.example.live.DeterministicTestLiveTransportDriver
import com.example.live.DiagnosticRedactor
import com.example.live.GeminiLiveConnectionAdapter
import com.example.live.LiveSessionGenerationId
import com.example.live.LiveSocketCallbackEvent
import com.example.live.TurnGenerationId
import com.example.ui.shell.ConversationShellRoute
import com.example.ui.shell.ConversationShellViewModel
import com.example.ui.theme.OmniVoiceRebuildTheme
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class Phase07aTestAudioDriver : AudioHardwareDriver {
    override fun hasMicrophoneDevice(): Boolean = true

    override fun startHardwareCapture(
        generationId: CaptureGenerationId,
        onEvent: (CaptureCallbackEvent) -> Unit
    ): HardwareStartResult = HardwareStartResult.Started(16000)

    override fun stopHardwareCapture() = Unit

    override fun playEphemeralPcm16kHz(
        generationId: CaptureGenerationId,
        samples16kHz: ShortArray,
        onCompleted: (CaptureCallbackEvent.EphemeralPlaybackCompleted) -> Unit
    ): Boolean {
        onCompleted(CaptureCallbackEvent.EphemeralPlaybackCompleted(generationId, samples16kHz.size))
        return true
    }

    override fun stopEphemeralPlayback() = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase07aDiagnosticsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class Harness {
        val fakeClockMs = AtomicLong(1_000L)
        val micDriver = Phase07aTestAudioDriver()
        val audioCapture = AudioCapture(driver = micDriver)
        val liveDriver = DeterministicTestLiveTransportDriver().apply {
            onEndOfTurnHandler = null
        }
        val networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true)
        val liveAdapter = GeminiLiveConnectionAdapter(
            driver = liveDriver,
            networkMonitor = networkMonitor,
            delayRunner = {}
        )
        val trackDriver = DeterministicStreamingAudioTrackDriver(
            openTrackSucceeds = true,
            playSucceeds = true,
            autoAdvancePlaybackHead = true
        )
        val playbackAdapter = LiveAudioPlaybackAdapter(
            driver = trackDriver,
            writeRetryDelayRunner = {},
            drainPollDelayRunner = {},
            audioDispatcher = Dispatchers.Unconfined
        )
        val coordinator = ConversationCoordinator(
            audioCapture = audioCapture,
            liveAdapter = liveAdapter,
            playbackAdapter = playbackAdapter,
            monotonicClockMs = { fakeClockMs.get() },
            postPlaybackGuardDelayRunner = {}
        )
    }

    @Test
    fun test1_all11EventTimestampsOrderedAndLatencyCalculationsSeparated() = runBlocking {
        val h = Harness()
        h.coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        h.coordinator.setActiveConversationEnabled(true)

        // 1) Conversation Start requested at 1000ms; SetupAccepted & MicReady during startVoiceTurn
        h.fakeClockMs.set(1_000L)
        h.coordinator.startVoiceTurn()
        assertEquals(ConversationState.LISTENING, h.coordinator.uiState.value.state)

        val startupSnap = h.coordinator.uiState.value.startupTimingSnapshot
        assertEquals(1_000L, startupSnap.tStartRequestedMs)
        assertEquals(1_000L, startupSnap.tSetupAcceptedMs)
        assertEquals(1_000L, startupSnap.tMicReadyMs)
        assertEquals(0L, startupSnap.connectionSetupDurationMs)
        assertEquals(0L, startupSnap.startupToMicReadyDurationMs)

        // Verify handshake SetupAccepted did NOT count as turn's first response
        val initialTurnTiming = h.coordinator.uiState.value.activeTurnTimingSnapshot
        assertNotNull(initialTurnTiming)
        assertNull(initialTurnTiming!!.tFirstResponseMs)

        // 4) User speech samples captured across 1100ms..1220ms (7 x 20ms frames = 140ms >= 120ms confirm)
        val captureGen = h.audioCapture.state.value.activeGeneration!!
        val speechFrame = ShortArray(320) { 9_000 }
        var frameClock = 1_100L
        repeat(7) {
            h.fakeClockMs.set(frameClock)
            h.coordinator.onAudioHardwareCallback(
                CaptureCallbackEvent.FrameCaptured(captureGen, speechFrame, 16_000)
            )
            frameClock += 20L
        }
        // Last speech frame was at 1220ms
        assertEquals(
            1_220L,
            h.coordinator.buildRedactedDiagnosticSnapshot().activeTurnTiming?.tLastUserSpeechMs
        )

        // 5) End of speech detected via trailing silence (34 x 20ms = 680ms < 700ms, then 35th frame at 1940ms -> 720ms >= 700ms)
        val silenceFrame = ShortArray(320) { 0 }
        repeat(34) {
            h.fakeClockMs.set(frameClock)
            h.coordinator.onAudioHardwareCallback(
                CaptureCallbackEvent.FrameCaptured(captureGen, silenceFrame, 16_000)
            )
            frameClock += 20L
        }
        // At 1940ms, 35th silence frame triggers auto-end (tSpeechEndMs = 1940ms) and submits end-of-turn (tEndSubmittedMs = 1940ms)
        h.fakeClockMs.set(1_940L)
        h.coordinator.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(captureGen, silenceFrame, 16_000)
        )
        assertEquals(ConversationState.PROCESSING, h.coordinator.uiState.value.state)

        val processingTiming = h.coordinator.uiState.value.activeTurnTimingSnapshot!!
        assertEquals(1_220L, processingTiming.tLastUserSpeechMs)
        assertEquals(1_940L, processingTiming.tSpeechEndMs)
        assertEquals(1_940L, processingTiming.tEndSubmittedMs)
        assertNull(processingTiming.tServerInputAckMs) // Server ack is distinct from client transport submission!
        assertEquals(720L, processingTiming.speechEndpointingDelayMs)
        assertEquals(0L, processingTiming.endOfTurnSubmissionDelayMs)

        val liveGen = h.liveAdapter.state.value.activeGeneration!!

        // Server input acknowledgment arrives at 2050ms (does NOT count as model output response)
        h.fakeClockMs.set(2_050L)
        h.coordinator.onLiveSocketCallback(
            LiveSocketCallbackEvent.ServerTranscriptionReceived(
                generationId = liveGen,
                inputTranscript = "Assalam o alaikum, how are you?",
                outputTranscript = null
            )
        )
        val afterInputAck = h.coordinator.uiState.value.activeTurnTimingSnapshot!!
        assertEquals(2_050L, afterInputAck.tServerInputAckMs)
        assertEquals(110L, afterInputAck.transportToServerAckDelayMs)
        assertNull(afterInputAck.tFirstResponseMs)

        // 7) First response event belonging to that turn arrives at 2200ms (output transcription)
        h.fakeClockMs.set(2_200L)
        h.coordinator.onLiveSocketCallback(
            LiveSocketCallbackEvent.ServerTranscriptionReceived(
                generationId = liveGen,
                inputTranscript = null,
                outputTranscript = "Walaikum assalam main bilkul theek hoon"
            )
        )
        val afterFirstResp = h.coordinator.uiState.value.activeTurnTimingSnapshot!!
        assertEquals(2_200L, afterFirstResp.tFirstResponseMs)
        assertEquals(260L, afterFirstResp.serverResponseWaitMs)
        assertNull(afterFirstResp.tFirstAudioReceivedMs)

        // 8) First audio chunk received at 2280ms & 9) First hardware write at 2280ms
        h.fakeClockMs.set(2_280L)
        val pcm24kChunk = ByteArray(4_800) { 1 } // 2400 frames = 100ms at 24kHz
        h.coordinator.onLiveSocketCallback(
            LiveSocketCallbackEvent.ServerAudioChunkReceived(
                generationId = liveGen,
                pcmBytes = pcm24kChunk,
                mimeType = "audio/pcm;rate=24000"
            )
        )
        var writeWaitTries = 0
        while (
            (h.coordinator.uiState.value.activeTurnTimingSnapshot?.tFirstHardwareWriteMs == null ||
                h.coordinator.uiState.value.state != ConversationState.SPEAKING) &&
            writeWaitTries < 40
        ) {
            kotlinx.coroutines.delay(5L)
            writeWaitTries++
        }
        assertEquals(ConversationState.SPEAKING, h.coordinator.uiState.value.state)
        val speakingTiming = h.coordinator.uiState.value.activeTurnTimingSnapshot!!
        assertEquals(2_200L, speakingTiming.tFirstResponseMs) // Preserves earliest turn response timestamp
        assertEquals(2_280L, speakingTiming.tFirstAudioReceivedMs)
        assertEquals(2_280L, speakingTiming.tFirstHardwareWriteMs)
        assertEquals(340L, speakingTiming.serverAudioWaitMs) // 2280 - 1940
        assertEquals(0L, speakingTiming.playbackBufferingDelayMs)
        assertEquals(340L, speakingTiming.outputStartLatencyEstimateMs) // 2280 - 1940 (speechEnd -> firstHardwareWrite ESTIMATE)

        // 10) Playback drain completed & 11) Next microphone capture ready at 2600ms
        h.fakeClockMs.set(2_600L)
        h.coordinator.onLiveSocketCallback(
            LiveSocketCallbackEvent.ServerTurnCompleted(
                generationId = liveGen,
                interrupted = false
            )
        )
        var drainWaitTries = 0
        while (
            (h.coordinator.uiState.value.completedTurnsInSession < 1 ||
                h.coordinator.uiState.value.state != ConversationState.LISTENING) &&
            drainWaitTries < 40
        ) {
            kotlinx.coroutines.delay(5L)
            drainWaitTries++
        }

        // Auto-restored to LISTENING for Turn 2
        assertEquals(ConversationState.LISTENING, h.coordinator.uiState.value.state)
        val completedTiming = h.coordinator.uiState.value.lastCompletedTurnTimingSnapshot!!
        assertEquals(2_600L, completedTiming.tPlaybackDrainedMs)
        assertEquals(2_600L, completedTiming.tNextMicReadyMs)
        assertEquals(320L, completedTiming.playbackDurationMs) // 2600 - 2280 (tracked separately from response latency)
        assertEquals(100L, completedTiming.audioContentDurationMs) // 2400 frames @ 24000Hz = 100ms
        assertNotNull(completedTiming.speakingRateWpmEstimate)
        assertEquals(0L, completedTiming.captureRestorationDelayMs)
    }

    @Test
    fun test2_missingAndOutOfOrderTimestampsReturnNullAndUnavailable() {
        assertNull(safeMonotonicDurationMs(null, 500L))
        assertNull(safeMonotonicDurationMs(500L, null))
        assertNull(safeMonotonicDurationMs(null, null))
        // Out-of-order timestamps (end < start) must be rejected as null
        assertNull(safeMonotonicDurationMs(500L, 499L))
        assertEquals(0L, safeMonotonicDurationMs(500L, 500L))
        assertEquals(125L, safeMonotonicDurationMs(500L, 625L))

        val h = Harness()
        val snap = h.coordinator.buildRedactedDiagnosticSnapshot()
        assertNull(snap.activeTurnTiming)
        assertNull(snap.lastCompletedTurnTiming)
        val formatted = snap.toFormattedReport()
        assertTrue(formatted.contains("tConversationStartRequested: UNAVAILABLE"))
        assertTrue(formatted.contains("connectionSetupDuration (startRequested -> setupAccepted): UNAVAILABLE"))
        assertTrue(formatted.contains("outputStartLatencyNote: speechEnd-to-firstHardwareWrite is an ESTIMATE"))
    }

    @Test
    fun test3_staleGenerationCallbacksRejectedWithoutMutatingActiveTiming() = runBlocking {
        val h = Harness()
        h.coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        h.fakeClockMs.set(5_000L)
        h.coordinator.startVoiceTurn()

        val activeTurnGen = h.coordinator.uiState.value.activeTurnGenerationId!!
        val staleTurnGen = TurnGenerationId(
            turnSeq = 999L,
            turnIdValue = "turn-stale-999",
            sessionId = h.coordinator.uiState.value.sessionId
        )
        val staleLiveGen = LiveSessionGenerationId(
            sequenceNumber = 999L,
            sessionId = h.coordinator.uiState.value.sessionId,
            wireId = "live-gen-0999"
        )
        val stalePlayGen = PlaybackGenerationId(
            generation = 999L,
            turnId = TurnId("turn-stale-999"),
            sessionId = h.coordinator.uiState.value.sessionId
        )

        h.fakeClockMs.set(6_000L)
        val acceptedServer = h.coordinator.onTurnScopedServerEvent(
            turnGenerationId = staleTurnGen,
            event = LiveSocketCallbackEvent.ServerAudioChunkReceived(
                generationId = staleLiveGen,
                pcmBytes = ByteArray(640) { 2 },
                mimeType = "audio/pcm;rate=24000"
            )
        )
        assertFalse(acceptedServer)

        val acceptedPlayback = h.coordinator.onTurnBoundPlaybackCallback(
            PlaybackCallbackEvent.BytesAcceptedByHardware(
                generationId = stalePlayGen,
                chunkBytesAccepted = 640,
                totalBytesAccepted = 640L,
                totalFramesWritten = 320L,
                partialWriteCount = 0,
                zeroWriteRetryCount = 0
            )
        )
        assertFalse(acceptedPlayback)

        val timing = h.coordinator.uiState.value.activeTurnTimingSnapshot!!
        assertEquals(activeTurnGen.wireId, timing.turnGenerationWireId)
        assertNull(timing.tFirstResponseMs)
        assertNull(timing.tFirstAudioReceivedMs)
        assertNull(timing.tFirstHardwareWriteMs)
        assertEquals(2, h.coordinator.uiState.value.staleTurnCallbacksDropped)
    }

    @Test
    fun test4_redactionExcludesTranscriptsSecretsContactsAndExceptionsAndBoundsErrorHistory() = runBlocking {
        val h = Harness()
        h.coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        h.fakeClockMs.set(10_000L)
        h.coordinator.startVoiceTurn()

        val liveGen = h.liveAdapter.state.value.activeGeneration!!
        val sensitiveUserSpeech = "SECRET_USER_SPOKEN_TRANSCRIPT_778899"
        val sensitiveModelSpeech = "SECRET_MODEL_SPOKEN_TRANSCRIPT_112233"

        h.coordinator.onLiveSocketCallback(
            LiveSocketCallbackEvent.ServerTranscriptionReceived(
                generationId = liveGen,
                inputTranscript = sensitiveUserSpeech,
                outputTranscript = sensitiveModelSpeech
            )
        )

        // Generate 12 errors to verify bounded history (1 active + 10 previous max)
        for (i in 1..12) {
            h.fakeClockMs.set(10_000L + i * 100L)
            if (h.coordinator.uiState.value.state != ConversationState.LISTENING) {
                h.coordinator.startVoiceTurn()
            }
            val leakyMessage = buildString {
                append("Error #$i ")
                append("apiKey=AIzaSyD1234567890abcdefghijklmnopqrstuv ")
                append("Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abc123def456 ")
                append("X-Firebase-AppCheck: secret-app-check-token-value-999 ")
                append("resumptionHandle=resume_secret_token_987654321 ")
                append("contact=user.secret@example.com phone=+1-415-555-2671 ")
                append("Heard: \"$sensitiveUserSpeech\" | Gemini: \"$sensitiveModelSpeech\" ")
                append("java.lang.IllegalStateException: Raw internal secret exception message at com.example.SecretClass.run(SecretClass.kt:42)")
            }
            val asyncTurn = async {
                h.coordinator.finishSpokenTurnAndAwaitResponse(reason = leakyMessage)
            }
            asyncTurn.await()
        }

        val snapshot = h.coordinator.buildRedactedDiagnosticSnapshot()
        assertNotNull(snapshot.currentActiveError)
        assertEquals(10, snapshot.previousErrorHistory.size)
        // Oldest entry in bounded history of 10 should be seq #2 (since #1 was evicted and #12 is active)
        assertEquals(2L, snapshot.previousErrorHistory.first().sequenceNumber)
        assertEquals(11L, snapshot.previousErrorHistory.last().sequenceNumber)
        assertEquals(12L, snapshot.currentActiveError!!.sequenceNumber)

        val report = h.coordinator.generateRedactedDiagnosticSnapshot()
        assertFalse(report.contains(sensitiveUserSpeech))
        assertFalse(report.contains(sensitiveModelSpeech))
        assertFalse(report.contains("AIzaSyD1234567890abcdefghijklmnopqrstuv"))
        assertFalse(report.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
        assertFalse(report.contains("secret-app-check-token-value-999"))
        assertFalse(report.contains("resume_secret_token_987654321"))
        assertFalse(report.contains("user.secret@example.com"))
        assertFalse(report.contains("415-555-2671"))
        assertFalse(report.contains("Raw internal secret exception message"))
        assertFalse(report.contains("SecretClass.kt:42"))

        // Verify required Phase 07A sections & fields are present
        assertTrue(report.contains("applicationId: com.kazim.omnivoice.rebuild"))
        assertTrue(report.contains("sourceRevision: SOURCE_REVISION_UNAVAILABLE"))
        assertTrue(report.contains("runtimeModelId: gemini-3.1-flash-live-preview"))
        assertTrue(report.contains("configuredVoice: Kore"))
        assertTrue(report.contains("[7. CURRENT ACTIVE ERROR]"))
        assertTrue(report.contains("[8. PREVIOUS ERROR HISTORY (SEPARATELY LABELLED, BOUNDED max=10)]"))
    }

    @Test
    fun test5_copyDiagControlCopiesRedactedSnapshotOnlyWhenTapped() {
        val h = Harness()
        val viewModel = ConversationShellViewModel(coordinator = h.coordinator)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.clearPrimaryClip()

        composeTestRule.setContent {
            OmniVoiceRebuildTheme {
                ConversationShellRoute(viewModel = viewModel)
            }
        }

        // Before tapping COPY DIAG, clipboard must remain empty
        assertFalse(clipboard.hasPrimaryClip())

        // Tap COPY DIAG
        composeTestRule.onNodeWithTag("copy_diag_button").assertExists().performClick()

        // After tapping COPY DIAG, clipboard contains the redacted Phase 07A diagnostic snapshot
        assertTrue(clipboard.hasPrimaryClip())
        val copiedText = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue(copiedText.contains("=== OMNIVOICE REBUILD — PHASE 07A REDACTED DIAGNOSTIC SNAPSHOT ==="))
        assertTrue(copiedText.contains("runtimeModelId: gemini-3.1-flash-live-preview"))
        assertTrue(copiedText.contains("configuredVoice: Kore"))
        assertEquals(h.coordinator.generateRedactedDiagnosticSnapshot(), copiedText)
    }
}
