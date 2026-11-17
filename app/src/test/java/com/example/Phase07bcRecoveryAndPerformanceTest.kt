package com.example

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioCapture
import com.example.audio.AudioHardwareDriver
import com.example.audio.CaptureCallbackEvent
import com.example.audio.CaptureGenerationId
import com.example.audio.DeterministicStreamingAudioTrackDriver
import com.example.audio.HardwareStartResult
import com.example.audio.LiveAudioPlaybackAdapter
import com.example.audio.MicPermissionState
import com.example.contract.ConversationState
import com.example.contract.CoordinatorCommand
import com.example.coordinator.ConversationCoordinator
import com.example.coordinator.Phase07PerformanceSummary
import com.example.live.ContextRestorationStatus
import com.example.live.ControllableLiveNetworkMonitor
import com.example.live.DeterministicTestLiveTransportDriver
import com.example.live.GeminiLiveConnectionAdapter
import com.example.live.LiveSocketCallbackEvent
import com.example.live.SessionResumptionStatus
import com.example.ui.shell.ConversationShellRoute
import com.example.ui.shell.ConversationShellViewModel
import com.example.ui.theme.OmniVoiceRebuildTheme
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class Phase07bcTestAudioDriver : AudioHardwareDriver {
    override fun hasMicrophoneDevice(): Boolean = true

    override fun startHardwareCapture(
        generationId: CaptureGenerationId,
        onEvent: (CaptureCallbackEvent) -> Unit
    ): HardwareStartResult = HardwareStartResult.Started(16_000)

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
class Phase07bcRecoveryAndPerformanceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class Harness(
        firstResponseTimeoutMs: Long = 12_000L,
        firstAudioAfterResponseTimeoutMs: Long = firstResponseTimeoutMs,
        serverOutputStallTimeoutMs: Long = maxOf(firstResponseTimeoutMs, 10_000L),
        missingTurnCompleteTimeoutMs: Long = serverOutputStallTimeoutMs,
        maxTurnResponseHardBoundMs: Long = 90_000L,
        hardwarePlaybackStallTimeoutMs: Long = 5_000L,
        autoAdvancePlaybackHead: Boolean = true,
        playbackDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
        isRealDeviceMeasurementMode: Boolean = false
    ) {
        val fakeClockMs = AtomicLong(10_000L)
        val micDriver = Phase07bcTestAudioDriver()
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
            autoAdvancePlaybackHead = autoAdvancePlaybackHead
        )
        val playbackAdapter = LiveAudioPlaybackAdapter(
            driver = trackDriver,
            writeRetryDelayRunner = {},
            drainPollDelayRunner = {},
            audioDispatcher = playbackDispatcher,
            clockMs = { fakeClockMs.get() },
            hardwarePlaybackStallTimeoutMs = hardwarePlaybackStallTimeoutMs
        )
        val coordinator = ConversationCoordinator(
            audioCapture = audioCapture,
            liveAdapter = liveAdapter,
            playbackAdapter = playbackAdapter,
            firstResponseTimeoutMs = firstResponseTimeoutMs,
            firstAudioAfterResponseTimeoutMs = firstAudioAfterResponseTimeoutMs,
            serverOutputStallTimeoutMs = serverOutputStallTimeoutMs,
            missingTurnCompleteTimeoutMs = missingTurnCompleteTimeoutMs,
            maxTurnResponseHardBoundMs = maxTurnResponseHardBoundMs,
            hardwarePlaybackStallTimeoutMs = hardwarePlaybackStallTimeoutMs,
            monotonicClockMs = { fakeClockMs.get() },
            postPlaybackGuardDelayRunner = {},
            isRealDeviceMeasurementMode = isRealDeviceMeasurementMode
        )

        suspend fun speakAndTriggerAutoEnd(speechStartMs: Long): Long {
            val captureGen = audioCapture.state.value.activeGeneration!!
            val speechFrame = ShortArray(320) { 9_000 }
            val silenceFrame = ShortArray(320) { 0 }
            var ts = speechStartMs
            repeat(7) {
                fakeClockMs.set(ts)
                coordinator.onAudioHardwareCallback(
                    CaptureCallbackEvent.FrameCaptured(captureGen, speechFrame, 16_000)
                )
                ts += 20L
            }
            repeat(35) {
                fakeClockMs.set(ts)
                coordinator.onAudioHardwareCallback(
                    CaptureCallbackEvent.FrameCaptured(captureGen, silenceFrame, 16_000)
                )
                ts += 20L
            }
            val endMs = ts - 20L
            repeat(200) {
                val st = coordinator.uiState.value.state
                val tEnd = coordinator.uiState.value.activeTurnTimingSnapshot?.tSpeechEndMs
                if (st == ConversationState.PROCESSING && tEnd != null) {
                    return tEnd
                }
                delay(10L)
            }
            return endMs
        }
    }

    private suspend fun waitUntil(maxAttempts: Int = 200, stepDelayMs: Long = 10L, predicate: () -> Boolean) {
        repeat(maxAttempts) {
            if (predicate()) return
            delay(stepDelayMs)
        }
    }

    @Test
    fun test1_progressAwareWatchdog_textOnlyNoAudioTriggersTimeout_andMidResponseProgressPreventsFalseTimeout() = runBlocking {
        val h = Harness(
            firstResponseTimeoutMs = 120L,
            firstAudioAfterResponseTimeoutMs = 120L,
            serverOutputStallTimeoutMs = 180L,
            maxTurnResponseHardBoundMs = 2_000L
        )
        h.coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        h.coordinator.setActiveConversationEnabled(true)
        h.fakeClockMs.set(1_000L)
        h.coordinator.startVoiceTurn()
        assertEquals(ConversationState.LISTENING, h.coordinator.uiState.value.state)

        // Turn 1: User speaks -> PROCESSING. Server sends output text ONLY, never sends audio.
        h.speakAndTriggerAutoEnd(1_100L)
        assertEquals(ConversationState.PROCESSING, h.coordinator.uiState.value.state)
        val liveGen = h.liveAdapter.state.value.activeGeneration!!
        h.fakeClockMs.addAndGet(40L)
        h.coordinator.onLiveSocketCallback(
            LiveSocketCallbackEvent.ServerTranscriptionReceived(
                generationId = liveGen,
                inputTranscript = null,
                outputTranscript = "Text without any audio chunk"
            )
        )
        // Text-only does NOT satisfy audible output: watchdog must fire FIRST_RESPONSE_TIMEOUT and recover once.
        waitUntil { h.coordinator.uiState.value.firstResponseRecoveryCount == 1 && h.coordinator.uiState.value.state == ConversationState.LISTENING }
        assertEquals(1, h.coordinator.uiState.value.firstResponseTimeoutCount)
        assertEquals(1, h.coordinator.uiState.value.firstResponseRecoveryCount)
        assertEquals(1, h.coordinator.uiState.value.performanceSummary.timedOutTurns)
        assertEquals(ConversationState.LISTENING, h.coordinator.uiState.value.state)

        // Turn 2: Continuous mid-response audio chunks keep resetting serverOutputStallTimeoutMs (180ms)
        val afterEndMs = h.speakAndTriggerAutoEnd(h.fakeClockMs.get() + 100L)
        assertEquals(ConversationState.PROCESSING, h.coordinator.uiState.value.state)
        val liveGen2 = h.liveAdapter.state.value.activeGeneration!!
        val chunk = ByteArray(2_400) { 1 }

        // Emit 3 chunks spaced 100ms apart (total 300ms > serverOutputStallTimeoutMs=180ms, each gap < 180ms)
        h.fakeClockMs.set(afterEndMs + 40L)
        h.coordinator.onLiveSocketCallback(
            LiveSocketCallbackEvent.ServerAudioChunkReceived(liveGen2, "audio/pcm;rate=24000", chunk)
        )
        delay(90L)
        h.fakeClockMs.addAndGet(90L)
        h.coordinator.onLiveSocketCallback(
            LiveSocketCallbackEvent.ServerAudioChunkReceived(liveGen2, "audio/pcm;rate=24000", chunk)
        )
        delay(90L)
        h.fakeClockMs.addAndGet(90L)
        h.coordinator.onLiveSocketCallback(
            LiveSocketCallbackEvent.ServerAudioChunkReceived(liveGen2, "audio/pcm;rate=24000", chunk)
        )
        delay(90L)
        h.fakeClockMs.addAndGet(90L)
        h.coordinator.onLiveSocketCallback(
            LiveSocketCallbackEvent.ServerTurnCompleted(liveGen2, interrupted = false)
        )

        waitUntil {
            h.coordinator.uiState.value.completedTurnsInSession == 1 &&
                h.coordinator.uiState.value.state == ConversationState.LISTENING
        }
        assertEquals(1, h.coordinator.uiState.value.completedTurnsInSession)
        assertEquals(0, h.coordinator.uiState.value.serverOutputStallCount)
        assertEquals(1, h.coordinator.uiState.value.performanceSummary.completedTurns)
    }

    @Test
    fun test2_midResponseServerOutputStallAndBoundedRecovery_stopsWithoutInfiniteRetries() = runBlocking {
        val h = Harness(
            firstResponseTimeoutMs = 100L,
            firstAudioAfterResponseTimeoutMs = 100L,
            serverOutputStallTimeoutMs = 140L,
            maxTurnResponseHardBoundMs = 2_000L
        )
        h.coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        h.coordinator.setActiveConversationEnabled(true)
        h.fakeClockMs.set(2_000L)
        h.coordinator.startVoiceTurn()

        // Turn 1: Receives chunk #1 then stalls mid-response without ServerTurnCompleted -> single bounded recovery
        val endMs1 = h.speakAndTriggerAutoEnd(2_100L)
        val liveGen1 = h.liveAdapter.state.value.activeGeneration!!
        h.fakeClockMs.set(endMs1 + 30L)
        h.coordinator.onLiveSocketCallback(
            LiveSocketCallbackEvent.ServerAudioChunkReceived(liveGen1, "audio/pcm;rate=24000", ByteArray(2_400) { 1 })
        )
        waitUntil { h.coordinator.uiState.value.firstResponseRecoveryCount == 1 && h.coordinator.uiState.value.state == ConversationState.LISTENING }
        assertEquals(1, h.coordinator.uiState.value.serverOutputStallCount)
        assertEquals(1, h.coordinator.uiState.value.firstResponseTimeoutCount)
        assertEquals(1, h.coordinator.uiState.value.firstResponseRecoveryCount)

        // Turn 2: Stalls again consecutively -> must halt cleanly in ERROR with zero further automatic retries
        val endMs2 = h.speakAndTriggerAutoEnd(h.fakeClockMs.get() + 200L)
        val liveGen2 = h.liveAdapter.state.value.activeGeneration!!
        h.fakeClockMs.set(endMs2 + 30L)
        h.coordinator.onLiveSocketCallback(
            LiveSocketCallbackEvent.ServerAudioChunkReceived(liveGen2, "audio/pcm;rate=24000", ByteArray(2_400) { 1 })
        )
        waitUntil { h.coordinator.uiState.value.state == ConversationState.ERROR }
        assertEquals(ConversationState.ERROR, h.coordinator.uiState.value.state)
        assertEquals("SERVER_OUTPUT_STALL_TIMEOUT", h.coordinator.uiState.value.activeVoiceTurnErrorCode)
        assertEquals(2, h.coordinator.uiState.value.serverOutputStallCount)
        assertEquals(2, h.coordinator.uiState.value.firstResponseTimeoutCount)
        assertEquals(1, h.coordinator.uiState.value.firstResponseRecoveryCount)
        assertEquals(2, h.coordinator.uiState.value.performanceSummary.timedOutTurns)
        assertFalse(h.audioCapture.state.value.isCapturing)
        assertFalse(h.coordinator.hasActiveTurnJobs())
    }

    @Test
    fun test3_maxTurnResponseHardBoundPreventsUnboundedStreamingTurn() = runBlocking {
        val h = Harness(
            firstResponseTimeoutMs = 100L,
            firstAudioAfterResponseTimeoutMs = 100L,
            serverOutputStallTimeoutMs = 200L,
            maxTurnResponseHardBoundMs = 300L
        )
        h.coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        h.coordinator.setActiveConversationEnabled(true)
        h.fakeClockMs.set(5_000L)
        h.coordinator.startVoiceTurn()

        val endMs = h.speakAndTriggerAutoEnd(5_100L)
        val liveGen = h.liveAdapter.state.value.activeGeneration!!
        // Emit audio chunks every 80ms so stall timer keeps resetting, but exceed maxTurnResponseHardBoundMs = 300ms
        var offset = 40L
        while (offset <= 360L) {
            h.fakeClockMs.set(endMs + offset)
            h.coordinator.onLiveSocketCallback(
                LiveSocketCallbackEvent.ServerAudioChunkReceived(liveGen, "audio/pcm;rate=24000", ByteArray(1_200) { 1 })
            )
            delay(80L)
            offset += 80L
        }
        waitUntil { h.coordinator.uiState.value.firstResponseTimeoutCount >= 1 }
        assertEquals(1, h.coordinator.uiState.value.serverOutputStallCount)
        assertEquals(1, h.coordinator.uiState.value.firstResponseTimeoutCount)
    }

    @Test
    fun test4_hardwarePlaybackStallDetectedInPumpAndSurfacedToCoordinator() = runBlocking {
        val h = Harness(
            firstResponseTimeoutMs = 5_000L,
            serverOutputStallTimeoutMs = 5_000L,
            hardwarePlaybackStallTimeoutMs = 150L,
            autoAdvancePlaybackHead = false
        )
        h.coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        h.coordinator.setActiveConversationEnabled(true)
        h.fakeClockMs.set(10_000L)
        h.coordinator.startVoiceTurn()

        val endMs = h.speakAndTriggerAutoEnd(10_100L)
        val liveGen = h.liveAdapter.state.value.activeGeneration!!

        // Send >= 10,560 bytes prebuffer (220ms @ 24kHz) so AudioTrack starts while playback head remains stuck at 0
        h.fakeClockMs.set(endMs + 50L)
        h.coordinator.onLiveSocketCallback(
            LiveSocketCallbackEvent.ServerAudioChunkReceived(liveGen, "audio/pcm;rate=24000", ByteArray(11_520) { 1 })
        )
        waitUntil { h.coordinator.uiState.value.state == ConversationState.SPEAKING }
        assertEquals(ConversationState.SPEAKING, h.coordinator.uiState.value.state)

        // Advance monotonic clock past hardwarePlaybackStallTimeoutMs (150ms) without advancing head
        h.fakeClockMs.addAndGet(200L)
        waitUntil { h.coordinator.uiState.value.state == ConversationState.ERROR }

        val ui = h.coordinator.uiState.value
        assertEquals(ConversationState.ERROR, ui.state)
        assertEquals("HARDWARE_PLAYBACK_STALL", ui.activeVoiceTurnErrorCode)
        assertEquals(1, ui.hardwarePlaybackStallCount)
        assertEquals(1, ui.performanceSummary.timedOutTurns)
        assertFalse(h.audioCapture.state.value.isCapturing)
        assertFalse(h.playbackAdapter.state.value.isPlaying)

        val diagReport = h.coordinator.generateRedactedDiagnosticSnapshot()
        assertTrue(diagReport.contains("hardwarePlaybackStalls=1"))
        assertTrue(diagReport.contains("activeErrorCode: HARDWARE_PLAYBACK_STALL"))
    }

    @Test
    fun test5_sessionResumptionGoAwayRotationAndRejectedHandleFallback() = runBlocking {
        val h = Harness()
        h.coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        h.coordinator.setActiveConversationEnabled(true)
        h.fakeClockMs.set(20_000L)
        h.coordinator.startVoiceTurn()
        assertEquals(ConversationState.LISTENING, h.coordinator.uiState.value.state)

        val liveGen1 = h.liveAdapter.state.value.activeGeneration!!
        val secretHandle = "resumption_handle_SECRET_999888"
        h.liveDriver.simulateServerResumptionUpdateForTest(
            generationId = liveGen1,
            rawHandle = secretHandle,
            resumable = true,
            lastConsumedClientMessageIndex = 3
        )
        assertEquals(
            SessionResumptionStatus.HANDLE_AVAILABLE_RESUMABLE,
            h.coordinator.uiState.value.liveConnectionState.sessionResumptionStatus
        )
        assertTrue(h.coordinator.uiState.value.liveConnectionState.hasScopedResumptionHandle)

        val diagWithHandle = h.coordinator.generateRedactedDiagnosticSnapshot()
        assertTrue(diagWithHandle.contains("hasValidSessionResumptionHandle: true"))
        assertFalse(diagWithHandle.contains(secretHandle))

        // Server sends GoAway while user is silently waiting in LISTENING -> rotates cleanly using handle
        h.liveDriver.simulateServerGoAwayForTest(liveGen1, timeLeftMs = 4_500L)
        waitUntil {
            val gen = h.liveAdapter.state.value.activeGeneration
            gen != null && gen != liveGen1 && h.coordinator.uiState.value.state == ConversationState.LISTENING
        }
        val liveAfterResume = h.coordinator.uiState.value.liveConnectionState
        assertEquals(SessionResumptionStatus.RESUMED_WITH_SERVER_HANDLE, liveAfterResume.sessionResumptionStatus)
        assertEquals(ContextRestorationStatus.RESUMED_WITH_SERVER_HANDLE, liveAfterResume.contextRestorationStatus)
        assertEquals(1, liveAfterResume.sessionResumptionSuccessCount)
        assertEquals(1, liveAfterResume.serverGoAwayCount)
        assertEquals(4_500L, liveAfterResume.lastServerGoAwayTimeLeftMs)
        assertTrue(h.audioCapture.state.value.isCapturing)

        // Rejected resumption handle falls back cleanly to a fresh session without resumed context
        h.liveDriver.rejectResumptionHandleOnNextConnect = true
        val liveGen2 = h.liveAdapter.state.value.activeGeneration!!
        h.liveDriver.simulateServerResumptionUpdateForTest(
            generationId = liveGen2,
            rawHandle = "resumption_handle_EXPIRED_111",
            resumable = true
        )
        h.liveDriver.simulateServerGoAwayForTest(liveGen2, timeLeftMs = 2_000L)
        waitUntil {
            val gen = h.liveAdapter.state.value.activeGeneration
            gen != null && gen != liveGen2 && h.coordinator.uiState.value.state == ConversationState.LISTENING
        }
        val liveAfterFallback = h.coordinator.uiState.value.liveConnectionState
        assertEquals(
            SessionResumptionStatus.HANDLE_REJECTED_FELL_BACK_NEW_CONNECTION,
            liveAfterFallback.sessionResumptionStatus
        )
        assertEquals(
            ContextRestorationStatus.NEW_CONNECTION_WITHOUT_RESUMED_CONTEXT,
            liveAfterFallback.contextRestorationStatus
        )
        assertEquals(1, liveAfterFallback.sessionResumptionFallbackCount)
        assertEquals(2, liveAfterFallback.serverGoAwayCount)

        // STOP must clear scoped resumption handles cleanly
        h.coordinator.dispatch(CoordinatorCommand.STOP)
        assertEquals(ConversationState.STOPPED, h.coordinator.uiState.value.state)
        assertEquals(
            SessionResumptionStatus.CLEARED_ON_STOP_OR_RESET,
            h.coordinator.uiState.value.liveConnectionState.sessionResumptionStatus
        )
        assertFalse(h.coordinator.uiState.value.liveConnectionState.hasScopedResumptionHandle)
    }

    @Test
    fun test6_phase07cHonestPerformanceSummary_pendingInitially_bounded20TurnWindow_andRedactedInCopyDiag() = runBlocking {
        // Part A: Synthetic/test harness turns are explicitly labeled and excluded from real-device summaries
        val syntheticHarness = Harness(isRealDeviceMeasurementMode = false)
        syntheticHarness.coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        syntheticHarness.coordinator.setActiveConversationEnabled(true)
        syntheticHarness.fakeClockMs.set(1_000L)
        syntheticHarness.coordinator.startVoiceTurn()
        val synEndMs = syntheticHarness.speakAndTriggerAutoEnd(2_000L)
        val synLiveGen = syntheticHarness.liveAdapter.state.value.activeGeneration!!
        syntheticHarness.fakeClockMs.set(synEndMs + 300L)
        syntheticHarness.coordinator.onLiveSocketCallback(
            LiveSocketCallbackEvent.ServerAudioChunkReceived(
                generationId = synLiveGen,
                mimeType = "audio/pcm;rate=24000",
                pcmBytes = ByteArray(4_800) { 1 }
            )
        )
        waitUntil { syntheticHarness.coordinator.uiState.value.state == ConversationState.SPEAKING }
        syntheticHarness.fakeClockMs.set(synEndMs + 700L)
        syntheticHarness.coordinator.onLiveSocketCallback(
            LiveSocketCallbackEvent.ServerTurnCompleted(synLiveGen, interrupted = false)
        )
        waitUntil { syntheticHarness.coordinator.uiState.value.completedTurnsInSession == 1 }
        val synPerf = syntheticHarness.coordinator.uiState.value.performanceSummary
        assertEquals(1, synPerf.totalAttemptedTurns)
        assertEquals(0, synPerf.realDeviceWindowTurnCount)
        assertEquals(1, synPerf.syntheticTestTurnCountExcluded)
        assertEquals(0, synPerf.outputStartLatencyEstimateDistribution.validSampleCount)
        assertEquals("INSUFFICIENT_SAMPLES", synPerf.roadmapTargetStatus)
        val synReport = syntheticHarness.coordinator.generateRedactedDiagnosticSnapshot()
        assertTrue(synReport.contains("measurementOrigin: SYNTHETIC_TEST_HARNESS (EXCLUDED_FROM_REAL_DEVICE_SUMMARY)"))
        assertTrue(synReport.contains("SYNTHETIC_TEST_ONLY (excludedFromRealDeviceSummary=1)"))
        assertFalse(synReport.contains("WITHIN_ROADMAP_TARGETS"))

        // Part B: Real-device measurement window requires 20 comparable valid real-device turns before reporting WITHIN_ROADMAP_TARGETS
        val h = Harness(isRealDeviceMeasurementMode = true)
        h.coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        h.coordinator.setActiveConversationEnabled(true)

        // Initially pending / UNAVAILABLE until real turns execute
        val initialPerf = h.coordinator.uiState.value.performanceSummary
        assertEquals(0, initialPerf.totalAttemptedTurns)
        assertEquals(0, initialPerf.recordedWindowTurnCount)
        assertEquals("MEASUREMENT_PENDING", initialPerf.roadmapTargetStatus)
        assertNull(initialPerf.outputStartLatencyEstimateDistribution.medianValue)
        assertNull(initialPerf.outputStartLatencyEstimateDistribution.p95NearestRankValue)

        val initialReport = h.coordinator.generateRedactedDiagnosticSnapshot()
        assertTrue(initialReport.contains("[10. PHASE 07B SESSION MANAGEMENT & CONTEXT RESTORATION (HANDLES REDACTED)]"))
        assertTrue(initialReport.contains("[11. PHASE 07C HONEST PERFORMANCE SUMMARY (BOUNDED max=20 TURNS)]"))
        assertTrue(initialReport.contains("roadmapTargetAssessment: MEASUREMENT_PENDING"))
        assertTrue(initialReport.contains("speechEndToFirstHardwareWriteLatencyEstimate [ESTIMATE]: UNAVAILABLE"))

        // Execute 22 completed turns:
        // Turns 1..2 have slow 5,000ms latency (must be evicted from bounded 20-turn window).
        // Turns 3..22 (last 20 turns) have latencies 100, 200, ..., 2000 ms.
        // Turns 1..19 (partial window < 20) MUST report INSUFFICIENT_SAMPLES, never WITHIN_ROADMAP_TARGETS!
        h.fakeClockMs.set(1_000L)
        h.coordinator.startVoiceTurn()

        var clockCursor = 2_000L
        for (turnNum in 1..22) {
            val endMs = h.speakAndTriggerAutoEnd(clockCursor)
            val liveGen = h.liveAdapter.state.value.activeGeneration!!
            val targetOutputLatencyMs = if (turnNum <= 2) 5_000L else (turnNum - 2) * 100L

            h.fakeClockMs.set(endMs + targetOutputLatencyMs)
            h.coordinator.onLiveSocketCallback(
                LiveSocketCallbackEvent.ServerTranscriptionReceived(
                    generationId = liveGen,
                    inputTranscript = "SECRET_USER_Q_$turnNum",
                    outputTranscript = "SECRET_MODEL_REPLY_$turnNum word2 word3 word4"
                )
            )
            h.coordinator.onLiveSocketCallback(
                LiveSocketCallbackEvent.ServerAudioChunkReceived(
                    generationId = liveGen,
                    mimeType = "audio/pcm;rate=24000",
                    pcmBytes = ByteArray(4_800) { 1 }
                )
            )
            waitUntil { h.coordinator.uiState.value.state == ConversationState.SPEAKING }

            h.fakeClockMs.set(endMs + targetOutputLatencyMs + 500L)
            h.coordinator.onLiveSocketCallback(
                LiveSocketCallbackEvent.ServerTurnCompleted(
                    generationId = liveGen,
                    interrupted = false
                )
            )
            waitUntil {
                h.coordinator.uiState.value.completedTurnsInSession == turnNum &&
                    h.coordinator.uiState.value.state == ConversationState.LISTENING
            }
            if (turnNum < 20) {
                val partialPerf = h.coordinator.uiState.value.performanceSummary
                assertEquals(
                    "Partial sample window (turn $turnNum/20) must report INSUFFICIENT_SAMPLES",
                    "INSUFFICIENT_SAMPLES",
                    partialPerf.roadmapTargetStatus
                )
            }
            clockCursor = h.fakeClockMs.get() + 600L
        }

        val summary = h.coordinator.uiState.value.performanceSummary
        assertEquals(22, summary.totalAttemptedTurns)
        assertEquals(22, summary.completedTurns)
        assertEquals(0, summary.failedTurns)
        assertEquals(Phase07PerformanceSummary.MAX_BOUNDED_TURN_RECORDS, summary.recordedWindowTurnCount)
        assertEquals(20, summary.realDeviceWindowTurnCount)
        assertEquals(0, summary.syntheticTestTurnCountExcluded)

        // Window has turns 3..22: latencies 100, 200, ..., 2000 ms -> median=1050, p95(rank 19)=1900, min=100, max=2000
        val estDist = summary.outputStartLatencyEstimateDistribution
        assertTrue(estDist.isEstimate)
        assertEquals(20, estDist.validSampleCount)
        assertEquals(0, estDist.missingSampleCount)
        assertEquals(1_050L, estDist.medianValue)
        assertEquals(1_900L, estDist.p95NearestRankValue)
        assertEquals(100L, estDist.minValue)
        assertEquals(2_000L, estDist.maxValue)
        assertEquals("WITHIN_ROADMAP_TARGETS", summary.roadmapTargetStatus)

        // Playback duration (500ms) and speaking rate kept strictly separate from latency
        assertEquals(20, summary.playbackDurationDistributionSeparateFromLatency.validSampleCount)
        assertEquals(500L, summary.playbackDurationDistributionSeparateFromLatency.medianValue)
        assertEquals(20, summary.speakingRateWpmDistributionSeparateFromLatency.validSampleCount)

        val finalReport = h.coordinator.generateRedactedDiagnosticSnapshot()
        assertTrue(finalReport.contains("WITHIN_ROADMAP_TARGETS [FULL_20_TURN_WINDOW (n=20)]"))
        assertFalse(finalReport.contains("SECRET_USER_Q_"))
        assertFalse(finalReport.contains("SECRET_MODEL_REPLY_"))

        // RESET_IDLE must clear summary back to initial pending state
        h.coordinator.dispatch(CoordinatorCommand.RESET_IDLE)
        val resetPerf = h.coordinator.uiState.value.performanceSummary
        assertEquals(0, resetPerf.totalAttemptedTurns)
        assertEquals(0, resetPerf.recordedWindowTurnCount)
        assertEquals("MEASUREMENT_PENDING", resetPerf.roadmapTargetStatus)
        assertNull(resetPerf.outputStartLatencyEstimateDistribution.medianValue)
    }

    @Test
    fun test7_composeUiLiveDiagnosticsAndCopyDiagReflectPhase07bcHonestState() {
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
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("open_live_diagnostics_button")
            .performScrollTo()
            .assertExists()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("live_diag_resumption_label").assertExists()
        composeTestRule.onNodeWithTag("live_diag_stall_counts_label").assertExists()
        composeTestRule.onNodeWithTag("live_diag_perf_summary_label").assertExists()

        composeTestRule.onNodeWithTag("live_diag_copy_button")
            .assertExists()
            .performClick()
        composeTestRule.waitForIdle()

        assertTrue(clipboard.hasPrimaryClip())
        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue(copied.contains("=== OMNIVOICE REBUILD — PHASE 07A REDACTED DIAGNOSTIC SNAPSHOT ==="))
        assertTrue(copied.contains("[10. PHASE 07B SESSION MANAGEMENT & CONTEXT RESTORATION (HANDLES REDACTED)]"))
        assertTrue(copied.contains("[11. PHASE 07C HONEST PERFORMANCE SUMMARY (BOUNDED max=20 TURNS)]"))
        assertTrue(copied.contains("MEASUREMENT_PENDING"))
        assertNotNull(copied)
        assertNotEquals("", copied)
    }

    @Test
    fun test8_phase07StabilizationNoAudioTurnReturnsToListeningAndMultiChunkPlaybackPreservesOrder() = runBlocking<Unit> {
        val h = Harness()
        var turnIndex = 0
        h.liveDriver.onEndOfTurnHandler = { genId, emitEvent ->
            turnIndex += 1
            if (turnIndex == 1) {
                // Server obeys system instruction on unclear/noise turn: completes turn with 0 audio chunks
                h.fakeClockMs.addAndGet(90L)
                emitEvent(LiveSocketCallbackEvent.ServerTurnCompleted(genId, interrupted = false))
            } else {
                // Turn 2: Real multi-chunk native audio reply (12 chunks with sequential byte markers 1..12)
                h.fakeClockMs.addAndGet(110L)
                emitEvent(
                    LiveSocketCallbackEvent.ServerTranscriptionReceived(
                        generationId = genId,
                        inputTranscript = "Aaj mausam kaisa hai?",
                        outputTranscript = "Aaj mausam bohot khushgawar hai."
                    )
                )
                for (chunkIdx in 1..12) {
                    h.fakeClockMs.addAndGet(15L)
                    emitEvent(
                        LiveSocketCallbackEvent.ServerAudioChunkReceived(
                            generationId = genId,
                            mimeType = "audio/pcm;rate=24000",
                            pcmBytes = ByteArray(1920) { chunkIdx.toByte() }
                        )
                    )
                }
                h.fakeClockMs.addAndGet(20L)
                emitEvent(LiveSocketCallbackEvent.ServerTurnCompleted(genId, interrupted = false))
            }
        }

        h.coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        val startOut = h.coordinator.startActiveConversation()
        assertEquals(ConversationState.LISTENING, startOut.resultingState)

        // Turn 1: Server completes with 0 audio chunks -> must NOT fail with AUDIO_TRACK_WRITE_FAILED, must auto-restore LISTENING
        val capGen1 = h.coordinator.audioCapture.state.value.activeGeneration!!
        repeat(7) {
            h.fakeClockMs.addAndGet(20L)
            h.coordinator.onAudioHardwareCallback(
                CaptureCallbackEvent.FrameCaptured(capGen1, ShortArray(320) { 5200 }, 16000)
            )
        }
        val out1 = h.coordinator.finishSpokenTurnAndAwaitResponse("Turn 1 server no-audio turnComplete")
        assertTrue(out1 is com.example.contract.VerifiedOutcome.VoiceTurnOutcome)
        assertEquals("NO_RESPONSE_TURN_IGNORED", (out1 as com.example.contract.VerifiedOutcome.VoiceTurnOutcome).code)
        assertEquals(ConversationState.LISTENING, h.coordinator.uiState.value.state)
        assertNull(h.coordinator.uiState.value.activeVoiceTurnErrorCode)
        assertEquals(1, h.coordinator.uiState.value.performanceSummary.noSpeechRejectedTurns)

        // Turn 2: Real multi-chunk spoken turn immediately after without pressing Start again
        val capGen2 = h.coordinator.audioCapture.state.value.activeGeneration!!
        assertNotEquals(capGen1, capGen2)
        repeat(7) {
            h.fakeClockMs.addAndGet(20L)
            h.coordinator.onAudioHardwareCallback(
                CaptureCallbackEvent.FrameCaptured(capGen2, ShortArray(320) { 6400 }, 16000)
            )
        }
        val out2 = h.coordinator.finishSpokenTurnAndAwaitResponse("Turn 2 real multi-chunk speech")
        assertTrue(out2 is com.example.contract.VerifiedOutcome.VoiceTurnOutcome)
        assertEquals("VOICE_TURN_COMPLETED_DRAINED", (out2 as com.example.contract.VerifiedOutcome.VoiceTurnOutcome).code)
        assertEquals(ConversationState.LISTENING, h.coordinator.uiState.value.state)
        assertEquals(1, h.coordinator.uiState.value.completedTurnsInSession)

        // Verify all 12 chunks (12 * 1920 = 23,040 bytes) arrived in strict 1..12 sequence without reordering
        val acceptedBytes = h.trackDriver.getAcceptedPcmBytes()
        assertEquals(12 * 1920, acceptedBytes.size)
        for (chunkIdx in 1..12) {
            val expected = chunkIdx.toByte()
            val offset = (chunkIdx - 1) * 1920
            for (b in 0 until 1920) {
                assertEquals(expected, acceptedBytes[offset + b])
            }
        }

        val completedTiming = h.coordinator.uiState.value.lastCompletedTurnTimingSnapshot!!
        assertTrue(completedTiming.isSyntheticOrTestMeasurement)
        assertEquals(10_370L, completedTiming.tLastUserSpeechMs)
        assertEquals(10_370L, completedTiming.tSpeechEndMs)
        assertEquals(10_370L, completedTiming.tEndSubmittedMs)
        assertEquals(10_480L, completedTiming.tFirstResponseMs)
        assertEquals(10_495L, completedTiming.tFirstAudioReceivedMs)
        assertNotNull(completedTiming.tFirstHardwareWriteMs)
        assertNotNull(completedTiming.tPlaybackDrainedMs)
        assertTrue(completedTiming.tFirstHardwareWriteMs!! >= completedTiming.tFirstAudioReceivedMs!!)
        assertTrue(completedTiming.tPlaybackDrainedMs!! >= completedTiming.tFirstHardwareWriteMs!!)
        assertEquals(
            completedTiming.tFirstHardwareWriteMs!! - 10_370L,
            completedTiming.outputStartLatencyEstimateMs
        )
        assertEquals(
            completedTiming.tPlaybackDrainedMs!! - completedTiming.tFirstHardwareWriteMs!!,
            completedTiming.playbackDurationMs
        )

        val diag = h.coordinator.generateRedactedDiagnosticSnapshot()
        assertTrue(diag.contains("completed=1"))
        assertTrue(diag.contains("noSpeechRejected=1"))
        assertTrue(diag.contains("INSUFFICIENT_SAMPLES"))
        assertFalse(diag.contains("WITHIN_ROADMAP_TARGETS"))
        assertTrue(diag.contains("measurementOrigin: SYNTHETIC_TEST_HARNESS (EXCLUDED_FROM_REAL_DEVICE_SUMMARY)"))

        h.coordinator.dispatch(CoordinatorCommand.STOP)
    }

    @Test
    fun test9a_audioReceivedAndPlayed_thenMissingTurnComplete_recoversHonestlyAndHaltsOnRepeat() = runBlocking {
        val h = Harness(
            firstResponseTimeoutMs = 100L,
            firstAudioAfterResponseTimeoutMs = 100L,
            serverOutputStallTimeoutMs = 200L,
            missingTurnCompleteTimeoutMs = 140L,
            maxTurnResponseHardBoundMs = 2_000L,
            autoAdvancePlaybackHead = true
        )
        h.coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        h.coordinator.setActiveConversationEnabled(true)
        h.fakeClockMs.set(30_000L)
        h.coordinator.startVoiceTurn()

        // Turn 1: Server sends >= prebuffer audio (12,000 bytes = 6,000 frames @ 24kHz) and hardware plays all frames,
        // then ServerTurnCompleted never arrives.
        val endMs1 = h.speakAndTriggerAutoEnd(30_100L)
        val liveGen1 = h.liveAdapter.state.value.activeGeneration!!
        h.fakeClockMs.set(endMs1 + 40L)
        h.coordinator.onLiveSocketCallback(
            LiveSocketCallbackEvent.ServerAudioChunkReceived(
                generationId = liveGen1,
                mimeType = "audio/pcm;rate=24000",
                pcmBytes = ByteArray(12_000) { 0x11 }
            )
        )
        waitUntil { h.coordinator.uiState.value.state == ConversationState.SPEAKING }

        waitUntil {
            h.coordinator.uiState.value.missingTurnCompleteCount == 1 &&
                h.coordinator.uiState.value.state == ConversationState.LISTENING
        }
        val uiAfterRecovery = h.coordinator.uiState.value
        assertEquals(0, uiAfterRecovery.completedTurnsInSession)
        assertEquals(0, uiAfterRecovery.serverOutputStallCount)
        assertEquals(1, uiAfterRecovery.missingTurnCompleteCount)
        assertEquals(1, uiAfterRecovery.firstResponseRecoveryCount)
        assertNull(uiAfterRecovery.currentActiveDiagnosticError)
        assertTrue(uiAfterRecovery.previousDiagnosticErrors.any { it.errorCode == "MISSING_SERVER_TURN_COMPLETE" })
        assertTrue(uiAfterRecovery.lastVoiceTurnSummary.contains("MISSING_SERVER_TURN_COMPLETE"))

        // Turn 2: Second consecutive missing turnComplete halts cleanly in ERROR and disconnects Live
        val endMs2 = h.speakAndTriggerAutoEnd(h.fakeClockMs.get() + 200L)
        val liveGen2 = h.liveAdapter.state.value.activeGeneration!!
        h.fakeClockMs.set(endMs2 + 40L)
        h.coordinator.onLiveSocketCallback(
            LiveSocketCallbackEvent.ServerAudioChunkReceived(
                generationId = liveGen2,
                mimeType = "audio/pcm;rate=24000",
                pcmBytes = ByteArray(12_000) { 0x12 }
            )
        )
        waitUntil { h.coordinator.uiState.value.state == ConversationState.ERROR }
        val uiError = h.coordinator.uiState.value
        assertEquals("MISSING_SERVER_TURN_COMPLETE", uiError.activeVoiceTurnErrorCode)
        assertEquals(2, uiError.missingTurnCompleteCount)
        assertEquals(0, uiError.serverOutputStallCount)
        assertEquals(0, uiError.completedTurnsInSession)
        assertTrue(uiError.lastActionId.value.contains("missing_turn_complete"))
        assertEquals(
            com.example.live.LiveConnectionPhase.DISCONNECTED,
            uiError.liveConnectionState.phase
        )
        assertFalse(h.audioCapture.state.value.isCapturing)
        assertFalse(h.coordinator.hasActiveTurnJobs())
    }

    @Test
    fun test10b_delayedCompletionWhileQueuedPlaybackContinues_neverAbortsHealthyPlayback() = runBlocking<Unit> {
        val h = Harness(
            firstResponseTimeoutMs = 120L,
            firstAudioAfterResponseTimeoutMs = 120L,
            serverOutputStallTimeoutMs = 150L,
            missingTurnCompleteTimeoutMs = 150L,
            maxTurnResponseHardBoundMs = 2_000L,
            hardwarePlaybackStallTimeoutMs = 250L,
            autoAdvancePlaybackHead = false
        )
        h.coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        h.coordinator.setActiveConversationEnabled(true)
        h.fakeClockMs.set(40_000L)
        h.coordinator.startVoiceTurn()

        val endMs = h.speakAndTriggerAutoEnd(40_100L)
        val liveGen = h.liveAdapter.state.value.activeGeneration!!

        // Burst 20 chunks (20 * 1,920 B = 38,400 B = 19,200 frames @ 24kHz) without turnComplete yet
        h.fakeClockMs.set(endMs + 40L)
        repeat(20) { idx ->
            h.coordinator.onLiveSocketCallback(
                LiveSocketCallbackEvent.ServerAudioChunkReceived(
                    generationId = liveGen,
                    mimeType = "audio/pcm;rate=24000",
                    pcmBytes = ByteArray(1_920) { (idx + 1).toByte() }
                )
            )
        }
        waitUntil { h.coordinator.uiState.value.state == ConversationState.SPEAKING }
        assertEquals(19_200L, h.playbackAdapter.state.value.totalFramesWrittenToHardware)

        // Pause server chunks/completion for > serverOutputStallTimeoutMs (320ms > 150ms)
        // while hardware playback head keeps advancing steadily (local playback making progress).
        repeat(8) { step ->
            delay(40L)
            h.fakeClockMs.addAndGet(40L)
            h.trackDriver.manualPlaybackHeadFrames = (step + 1) * 1_800L
            assertEquals(ConversationState.SPEAKING, h.coordinator.uiState.value.state)
        }
        assertEquals(0, h.coordinator.uiState.value.serverOutputStallCount)
        assertEquals(0, h.coordinator.uiState.value.missingTurnCompleteCount)

        // Now delayed ServerTurnCompleted arrives, and remaining frames play out through drain.
        h.trackDriver.manualPlaybackHeadFrames = 19_200L
        h.fakeClockMs.addAndGet(20L)
        h.coordinator.onLiveSocketCallback(
            LiveSocketCallbackEvent.ServerTurnCompleted(liveGen, interrupted = false)
        )
        waitUntil {
            h.coordinator.uiState.value.completedTurnsInSession == 1 &&
                h.coordinator.uiState.value.state == ConversationState.LISTENING
        }
        val ui = h.coordinator.uiState.value
        assertEquals(1, ui.completedTurnsInSession)
        assertEquals(0, ui.serverOutputStallCount)
        assertEquals(0, ui.missingTurnCompleteCount)
        assertEquals(ConversationState.LISTENING, ui.state)

        // Verify completed turn snapshot reconciles frames with padding separated
        val timing = ui.lastCompletedTurnTimingSnapshot!!
        assertEquals(19_200L, timing.framesReceivedFromServer)
        assertEquals(19_200L, timing.framesAcceptedByHardware)
        assertEquals(19_200L, timing.framesPlayedByHardware)
        assertEquals(0L, timing.framesQueuedInAdapter)
        assertTrue(timing.paddingFramesWrittenToHardware > 0L)
        h.coordinator.dispatch(CoordinatorCommand.STOP)
    }

    @Test
    fun test11c_receiveLoopRemainsResponsiveDuringSlowHardwareWrites() = runBlocking<Unit> {
        val singleAudioDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val h = Harness(
            firstResponseTimeoutMs = 2_000L,
            serverOutputStallTimeoutMs = 2_000L,
            hardwarePlaybackStallTimeoutMs = 2_000L,
            autoAdvancePlaybackHead = true,
            playbackDispatcher = singleAudioDispatcher
        )
        h.trackDriver.onWriteHook = { _, _ ->
            Thread.sleep(60L)
        }
        try {
            h.coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
            h.coordinator.setActiveConversationEnabled(true)
            h.fakeClockMs.set(50_000L)
            h.coordinator.startVoiceTurn()

            h.speakAndTriggerAutoEnd(50_100L)
            val liveGen = h.liveAdapter.state.value.activeGeneration!!

            // Dispatch 8 audio chunks + transcript + ServerTurnCompleted from the receive thread while pump writes are sleeping 60ms each (480ms total write work).
            val tDispatchStart = System.nanoTime()
            for (idx in 1..8) {
                h.coordinator.onLiveSocketCallback(
                    LiveSocketCallbackEvent.ServerAudioChunkReceived(
                        generationId = liveGen,
                        mimeType = "audio/pcm;rate=24000",
                        pcmBytes = ByteArray(1_920) { idx.toByte() }
                    )
                )
            }
            h.coordinator.onLiveSocketCallback(
                LiveSocketCallbackEvent.ServerTranscriptionReceived(
                    generationId = liveGen,
                    inputTranscript = "Test",
                    outputTranscript = "Quick non-blocking reply."
                )
            )
            h.coordinator.onLiveSocketCallback(
                LiveSocketCallbackEvent.ServerTurnCompleted(liveGen, interrupted = false)
            )
            val dispatchElapsedMs = (System.nanoTime() - tDispatchStart) / 1_000_000L

            // Receive loop must remain non-blocking (< 200ms even though 8 * 60ms = 480ms of hardware writes happen asynchronously)
            assertTrue("Expected non-blocking receive loop (<200ms), actual=${dispatchElapsedMs}ms", dispatchElapsedMs < 200L)

            waitUntil(maxAttempts = 300) {
                h.coordinator.uiState.value.completedTurnsInSession == 1 &&
                    h.coordinator.uiState.value.state == ConversationState.LISTENING
            }
            assertEquals(1, h.coordinator.uiState.value.completedTurnsInSession)
            val accepted = h.trackDriver.getAcceptedPcmBytes()
            assertEquals(8 * 1_920, accepted.size)
            for (idx in 1..8) {
                assertEquals(idx.toByte(), accepted[(idx - 1) * 1_920])
            }
            h.coordinator.dispatch(CoordinatorCommand.STOP)
        } finally {
            (singleAudioDispatcher as? java.io.Closeable)?.close()
        }
    }

    @Test
    fun test12d_staleCompletionFromOlderTurnOrGenerationIsDroppedSafely() = runBlocking<Unit> {
        val h = Harness()
        h.coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        h.coordinator.setActiveConversationEnabled(true)
        h.fakeClockMs.set(60_000L)
        h.coordinator.startVoiceTurn()

        // Turn 1 completes cleanly
        h.speakAndTriggerAutoEnd(60_100L)
        val liveGen1 = h.liveAdapter.state.value.activeGeneration!!
        val turnGen1 = h.coordinator.uiState.value.activeTurnGenerationId!!
        h.coordinator.onLiveSocketCallback(
            LiveSocketCallbackEvent.ServerAudioChunkReceived(liveGen1, "audio/pcm;rate=24000", ByteArray(4_800) { 1 })
        )
        h.coordinator.onLiveSocketCallback(
            LiveSocketCallbackEvent.ServerTurnCompleted(liveGen1, interrupted = false)
        )
        waitUntil { h.coordinator.uiState.value.completedTurnsInSession == 1 && h.coordinator.uiState.value.state == ConversationState.LISTENING }

        // Turn 2 is now PROCESSING and waiting for its own response (advance past 500ms post-playback echo guard)
        h.speakAndTriggerAutoEnd(h.fakeClockMs.get() + 600L)
        assertEquals(ConversationState.PROCESSING, h.coordinator.uiState.value.state)
        val staleDroppedBefore = h.coordinator.uiState.value.staleTurnCallbacksDropped

        // Inject stale completion bound to older turnGen1
        val acceptedTurnScoped = h.coordinator.onSessionAndTurnScopedEvent(
            sessionId = h.coordinator.uiState.value.sessionId,
            turnGenerationId = turnGen1,
            event = LiveSocketCallbackEvent.ServerTurnCompleted(liveGen1, interrupted = false)
        )
        assertFalse(acceptedTurnScoped)
        assertEquals(ConversationState.PROCESSING, h.coordinator.uiState.value.state)
        assertEquals(1, h.coordinator.uiState.value.completedTurnsInSession)
        assertTrue(h.coordinator.uiState.value.staleTurnCallbacksDropped > staleDroppedBefore)

        // Complete Turn 2 normally
        h.coordinator.onLiveSocketCallback(
            LiveSocketCallbackEvent.ServerAudioChunkReceived(liveGen1, "audio/pcm;rate=24000", ByteArray(4_800) { 2 })
        )
        h.coordinator.onLiveSocketCallback(
            LiveSocketCallbackEvent.ServerTurnCompleted(liveGen1, interrupted = false)
        )
        waitUntil { h.coordinator.uiState.value.completedTurnsInSession == 2 && h.coordinator.uiState.value.state == ConversationState.LISTENING }
        assertEquals(2, h.coordinator.uiState.value.completedTurnsInSession)
        h.coordinator.dispatch(CoordinatorCommand.STOP)
    }

    @Test
    fun test13e_stopAndPauseDuringRecoveryPreemptAndNeverAutoRestoreListening() = runBlocking {
        // Part 1: STOP during PREPARING recovery
        val gate1 = kotlinx.coroutines.CompletableDeferred<Unit>()
        val h1 = Harness(
            firstResponseTimeoutMs = 90L,
            firstAudioAfterResponseTimeoutMs = 90L,
            serverOutputStallTimeoutMs = 90L,
            missingTurnCompleteTimeoutMs = 90L
        )
        h1.liveDriver.scriptedHandler = { genId, modelId, attempt, onSocketEvent ->
            if (attempt == 2) {
                gate1.await()
            }
            val note = "SETUP_ACCEPTED ($modelId @ ${genId.wireId})"
            onSocketEvent(LiveSocketCallbackEvent.SetupAccepted(genId, note))
            com.example.live.LiveHandshakeResult.Accepted(genId, note)
        }
        h1.coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        h1.coordinator.setActiveConversationEnabled(true)
        h1.coordinator.startVoiceTurn()
        h1.speakAndTriggerAutoEnd(70_100L)
        waitUntil { h1.coordinator.uiState.value.state == ConversationState.PREPARING }
        assertEquals(ConversationState.PREPARING, h1.coordinator.uiState.value.state)

        h1.coordinator.dispatch(CoordinatorCommand.STOP)
        gate1.complete(Unit)
        delay(50L)
        assertEquals(ConversationState.STOPPED, h1.coordinator.uiState.value.state)
        assertFalse(h1.audioCapture.state.value.isCapturing)
        assertFalse(h1.playbackAdapter.state.value.isPlaying)

        // Part 2: PAUSE during PREPARING recovery
        val gate2 = kotlinx.coroutines.CompletableDeferred<Unit>()
        val h2 = Harness(
            firstResponseTimeoutMs = 90L,
            firstAudioAfterResponseTimeoutMs = 90L,
            serverOutputStallTimeoutMs = 90L,
            missingTurnCompleteTimeoutMs = 90L
        )
        h2.liveDriver.scriptedHandler = { genId, modelId, attempt, onSocketEvent ->
            if (attempt == 2) {
                gate2.await()
            }
            val note = "SETUP_ACCEPTED ($modelId @ ${genId.wireId})"
            onSocketEvent(LiveSocketCallbackEvent.SetupAccepted(genId, note))
            com.example.live.LiveHandshakeResult.Accepted(genId, note)
        }
        h2.coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        h2.coordinator.setActiveConversationEnabled(true)
        h2.coordinator.startVoiceTurn()
        h2.speakAndTriggerAutoEnd(75_100L)
        waitUntil { h2.coordinator.uiState.value.state == ConversationState.PREPARING }

        h2.coordinator.dispatch(CoordinatorCommand.PAUSE)
        gate2.complete(Unit)
        delay(50L)
        assertEquals(ConversationState.PAUSED, h2.coordinator.uiState.value.state)
        assertFalse(h2.audioCapture.state.value.isCapturing)
        assertFalse(h2.playbackAdapter.state.value.isPlaying)
    }

    @Test
    fun test14f_nextQuestionAfterRecoveryHasFreshIdsAndNoDuplicateOldOutput() = runBlocking<Unit> {
        val h = Harness(
            firstResponseTimeoutMs = 100L,
            firstAudioAfterResponseTimeoutMs = 100L,
            serverOutputStallTimeoutMs = 180L,
            missingTurnCompleteTimeoutMs = 120L,
            autoAdvancePlaybackHead = true
        )
        h.coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        h.coordinator.setActiveConversationEnabled(true)
        h.fakeClockMs.set(80_000L)
        h.coordinator.startVoiceTurn()

        // Turn 1 receives 0x11 marker audio and hits MISSING_SERVER_TURN_COMPLETE -> bounded recovery
        val turnId1 = h.coordinator.uiState.value.currentTurnId
        val endMs1 = h.speakAndTriggerAutoEnd(80_100L)
        val endSignalsAfterTurn1 = h.liveDriver.endOfTurnSignalCount
        val liveGen1 = h.liveAdapter.state.value.activeGeneration!!
        h.fakeClockMs.set(endMs1 + 30L)
        h.coordinator.onLiveSocketCallback(
            LiveSocketCallbackEvent.ServerAudioChunkReceived(
                generationId = liveGen1,
                mimeType = "audio/pcm;rate=24000",
                pcmBytes = ByteArray(12_000) { 0x11 }
            )
        )
        waitUntil {
            h.coordinator.uiState.value.missingTurnCompleteCount == 1 &&
                h.coordinator.uiState.value.state == ConversationState.LISTENING
        }

        // Verify recovery did NOT blindly resubmit Turn 1's end-of-turn and rotated TurnId + LiveGenerationId
        assertEquals(endSignalsAfterTurn1, h.liveDriver.endOfTurnSignalCount)
        val turnId2 = h.coordinator.uiState.value.currentTurnId
        val liveGen2 = h.liveAdapter.state.value.activeGeneration!!
        assertNotEquals(turnId1, turnId2)
        assertNotEquals(liveGen1, liveGen2)

        // Old late chunk from liveGen1 must be dropped without tainting Turn 2
        val bytesBeforeTurn2 = h.trackDriver.getAcceptedPcmBytes().size
        assertEquals(12_000, bytesBeforeTurn2)
        val acceptedLate = h.coordinator.onLiveSocketCallback(
            LiveSocketCallbackEvent.ServerAudioChunkReceived(
                generationId = liveGen1,
                mimeType = "audio/pcm;rate=24000",
                pcmBytes = ByteArray(1_920) { 0x11 }
            )
        )
        assertFalse(acceptedLate)
        assertEquals(12_000, h.trackDriver.getAcceptedPcmBytes().size)

        // Turn 2 question completes cleanly with 0x22 audio only
        h.speakAndTriggerAutoEnd(h.fakeClockMs.get() + 200L)
        assertEquals(endSignalsAfterTurn1 + 1, h.liveDriver.endOfTurnSignalCount)
        h.coordinator.onLiveSocketCallback(
            LiveSocketCallbackEvent.ServerAudioChunkReceived(
                generationId = liveGen2,
                mimeType = "audio/pcm;rate=24000",
                pcmBytes = ByteArray(4_800) { 0x22 }
            )
        )
        h.coordinator.onLiveSocketCallback(
            LiveSocketCallbackEvent.ServerTurnCompleted(liveGen2, interrupted = false)
        )
        waitUntil {
            h.coordinator.uiState.value.completedTurnsInSession == 1 &&
                h.coordinator.uiState.value.state == ConversationState.LISTENING
        }

        val allAcceptedBytes = h.trackDriver.getAcceptedPcmBytes()
        val turn2AcceptedBytes = allAcceptedBytes.copyOfRange(bytesBeforeTurn2, allAcceptedBytes.size)
        assertEquals(4_800, turn2AcceptedBytes.size)
        assertTrue(turn2AcceptedBytes.all { it == 0x22.toByte() })
        assertEquals(1, h.coordinator.uiState.value.completedTurnsInSession)
        h.coordinator.dispatch(CoordinatorCommand.STOP)
    }
}
