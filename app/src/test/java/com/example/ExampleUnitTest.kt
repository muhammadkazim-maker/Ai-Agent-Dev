package com.example

import com.example.audio.AudioAnomaly
import com.example.audio.AudioCapture
import com.example.audio.AudioHardwareDriver
import com.example.audio.CaptureCallbackEvent
import com.example.audio.CaptureGenerationId
import com.example.audio.HardwareStartResult
import com.example.audio.MicPermissionState
import com.example.contract.ActionId
import com.example.contract.ConversationState
import com.example.contract.CoordinatorCommand
import com.example.contract.VerifiedOutcome
import com.example.coordinator.ConversationCoordinator
import com.example.ui.shell.ConversationShellViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class ControllableTestAudioDriver(
    var hasMic: Boolean = true,
    var startResult: HardwareStartResult = HardwareStartResult.Started(48000)
) : AudioHardwareDriver {
    var stopCaptureCalls = 0
    var stopPlaybackCalls = 0
    var lastPlayedSamplesCount = 0

    override fun hasMicrophoneDevice(): Boolean = hasMic

    override fun startHardwareCapture(
        generationId: CaptureGenerationId,
        onEvent: (CaptureCallbackEvent) -> Unit
    ): HardwareStartResult = startResult

    override fun stopHardwareCapture() {
        stopCaptureCalls++
    }

    override fun playEphemeralPcm16kHz(
        generationId: CaptureGenerationId,
        samples16kHz: ShortArray,
        onCompleted: (CaptureCallbackEvent.EphemeralPlaybackCompleted) -> Unit
    ): Boolean {
        lastPlayedSamplesCount = samples16kHz.size
        onCompleted(
            CaptureCallbackEvent.EphemeralPlaybackCompleted(
                generationId = generationId,
                playedSamplesCount = samples16kHz.size
            )
        )
        return true
    }

    override fun stopEphemeralPlayback() {
        stopPlaybackCalls++
    }
}

class ExampleUnitTest {

    // --- Phase 01 Preserved Contract Tests ---

    @Test
    fun `initial coordinator state is IDLE with inactive microphone and valid IDs`() {
        val coordinator = ConversationCoordinator()
        val state = coordinator.uiState.value

        assertEquals(ConversationState.IDLE, state.state)
        assertFalse("Phase 01/02 shell must never fake an active microphone", state.isMicrophoneActive)
        assertTrue(state.sessionId.value.startsWith("ov01-sess-"))
        assertTrue(state.currentTurnId.value.startsWith("ov01-turn-"))
        assertTrue(state.lastActionId.value.startsWith("ov01-act-init-"))
        assertEquals(1, state.transcript.size)
    }

    @Test
    fun `start command transitions honestly to NOT_CONFIGURED without activating mic`() {
        val coordinator = ConversationCoordinator()
        val outcome = coordinator.dispatch(CoordinatorCommand.START)
        val state = coordinator.uiState.value

        assertTrue(outcome is VerifiedOutcome.HonestNotConfiguredOutcome)
        assertEquals(ConversationState.NOT_CONFIGURED, state.state)
        assertFalse("Microphone must remain inactive on shell Start in Phase 02", state.isMicrophoneActive)
        assertEquals("HONEST_NOT_CONFIGURED", outcome.code)
    }

    @Test
    fun `stop and pause precedence enforces STOP over PAUSE and START`() {
        val coordinator = ConversationCoordinator()

        coordinator.dispatch(CoordinatorCommand.START)
        assertEquals(ConversationState.NOT_CONFIGURED, coordinator.uiState.value.state)

        val pauseOutcome = coordinator.dispatch(CoordinatorCommand.PAUSE)
        assertTrue(pauseOutcome is VerifiedOutcome.StateTransitionVerified)
        assertEquals(ConversationState.PAUSED, coordinator.uiState.value.state)

        val stopOutcome = coordinator.dispatch(CoordinatorCommand.STOP)
        assertTrue(stopOutcome is VerifiedOutcome.StateTransitionVerified)
        assertEquals(ConversationState.STOPPED, coordinator.uiState.value.state)

        val pauseAfterStop = coordinator.dispatch(CoordinatorCommand.PAUSE)
        assertTrue(pauseAfterStop is VerifiedOutcome.PreemptedByPrecedence)
        assertEquals(ConversationState.STOPPED, coordinator.uiState.value.state)
        assertEquals("PREEMPTED_BY_STOP", pauseAfterStop.code)
    }

    @Test
    fun `concurrent batch dispatch enforces STOP over PAUSE over START`() {
        val coordinator = ConversationCoordinator()

        val pauseOverStart = coordinator.dispatchWithPrecedence(
            listOf(CoordinatorCommand.START, CoordinatorCommand.PAUSE)
        )
        assertTrue(pauseOverStart is VerifiedOutcome.PreemptedByPrecedence)
        assertEquals(ConversationState.PAUSED, coordinator.uiState.value.state)

        val stopOverAll = coordinator.dispatchWithPrecedence(
            listOf(CoordinatorCommand.START, CoordinatorCommand.PAUSE, CoordinatorCommand.STOP)
        )
        assertTrue(stopOverAll is VerifiedOutcome.PreemptedByPrecedence)
        assertEquals(ConversationState.STOPPED, coordinator.uiState.value.state)
    }

    @Test
    fun `duplicate ActionId is rejected to guarantee exactly-once effects`() {
        val coordinator = ConversationCoordinator()
        val fixedActionId = ActionId("ov01-act-custom-0099")

        val firstOutcome = coordinator.dispatch(CoordinatorCommand.START, fixedActionId)
        assertTrue(firstOutcome is VerifiedOutcome.HonestNotConfiguredOutcome)
        val countAfterFirst = coordinator.uiState.value.executedActionCount

        val duplicateOutcome = coordinator.dispatch(CoordinatorCommand.STOP, fixedActionId)
        assertTrue(duplicateOutcome is VerifiedOutcome.DuplicateActionRejected)
        assertEquals(ConversationState.NOT_CONFIGURED, coordinator.uiState.value.state)
        assertEquals(countAfterFirst, coordinator.uiState.value.executedActionCount)
        assertEquals(1, coordinator.uiState.value.duplicateRejectedCount)
    }

    @Test
    fun `reset to idle allocates a new SessionId`() {
        val coordinator = ConversationCoordinator()
        val initialSession = coordinator.uiState.value.sessionId

        coordinator.dispatch(CoordinatorCommand.STOP)
        assertEquals(ConversationState.STOPPED, coordinator.uiState.value.state)

        coordinator.dispatch(CoordinatorCommand.RESET_IDLE)
        assertEquals(ConversationState.IDLE, coordinator.uiState.value.state)
        assertNotEquals(initialSession, coordinator.uiState.value.sessionId)
    }

    // --- Phase 02 Microphone Permission, AudioCapture, Resampling & Generation Tests ---

    @Test
    fun `permission refusal, permanent denial, and Settings return re-check are handled accurately`() {
        val driver = ControllableTestAudioDriver()
        val coordinator = ConversationCoordinator(audioCapture = AudioCapture(driver))
        val viewModel = ConversationShellViewModel(coordinator)

        // 1. User refuses permission once (rationale can be shown)
        viewModel.onPermissionRequestResult(granted = false, shouldShowRationale = true)
        assertEquals(
            MicPermissionState.REFUSED_CAN_RETRY,
            coordinator.audioCapture.state.value.permissionState
        )

        // Attempting to start self-test while refused blocks capture with PERMISSION_MISSING
        val blockedOutcome = viewModel.onStartMicSelfTestClicked() as VerifiedOutcome.AudioSelfTestOutcome
        assertFalse(blockedOutcome.isCapturing)
        assertEquals(AudioAnomaly.PERMISSION_MISSING, blockedOutcome.anomaly)

        // 2. User denies permanently (shouldShowRationale == false)
        viewModel.onPermissionRequestResult(granted = false, shouldShowRationale = false)
        assertEquals(
            MicPermissionState.PERMANENTLY_DENIED,
            coordinator.audioCapture.state.value.permissionState
        )

        // 3. User returns from System App Settings after granting RECORD_AUDIO
        viewModel.onReCheckPermissionFromOs(isGranted = true, shouldShowRationale = false)
        assertEquals(
            MicPermissionState.GRANTED,
            coordinator.audioCapture.state.value.permissionState
        )
        assertEquals(AudioAnomaly.NONE, coordinator.audioCapture.state.value.anomaly)
    }

    @Test
    fun `repeated start and stop increments capture generation and releases hardware promptly`() {
        val driver = ControllableTestAudioDriver()
        val coordinator = ConversationCoordinator(audioCapture = AudioCapture(driver))
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        // Start 1
        val start1 = coordinator.dispatch(CoordinatorCommand.START_MIC_SELF_TEST) as VerifiedOutcome.AudioSelfTestOutcome
        val gen1 = start1.generationId!!
        assertEquals(1L, gen1.generation)
        assertTrue(coordinator.audioCapture.state.value.isCapturing)

        // Stop 1
        coordinator.dispatch(CoordinatorCommand.STOP_MIC_SELF_TEST)
        assertFalse(coordinator.audioCapture.state.value.isCapturing)
        assertEquals(1, coordinator.audioCapture.state.value.hardwareReleaseCount)

        // Start 2 (new generation)
        val start2 = coordinator.dispatch(CoordinatorCommand.START_MIC_SELF_TEST) as VerifiedOutcome.AudioSelfTestOutcome
        val gen2 = start2.generationId!!
        assertEquals(2L, gen2.generation)
        assertNotEquals(gen1, gen2)
        assertTrue(coordinator.audioCapture.state.value.isCapturing)

        // Large STOP immediately releases hardware and transitions shell to STOPPED
        coordinator.dispatch(CoordinatorCommand.STOP)
        assertFalse(coordinator.audioCapture.state.value.isCapturing)
        assertEquals(ConversationState.STOPPED, coordinator.uiState.value.state)
        assertEquals(2, coordinator.audioCapture.state.value.hardwareReleaseCount)
    }

    @Test
    fun `stale callbacks from previous capture generation are rejected without mutating active state`() {
        val driver = ControllableTestAudioDriver()
        val coordinator = ConversationCoordinator(audioCapture = AudioCapture(driver))
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        // Start Gen 1
        val start1 = coordinator.dispatch(CoordinatorCommand.START_MIC_SELF_TEST) as VerifiedOutcome.AudioSelfTestOutcome
        val gen1 = start1.generationId!!

        // Start Gen 2 (preempts Gen 1)
        val start2 = coordinator.dispatch(CoordinatorCommand.START_MIC_SELF_TEST) as VerifiedOutcome.AudioSelfTestOutcome
        val gen2 = start2.generationId!!
        assertEquals(2L, gen2.generation)

        // Inject a stale frame callback belonging to Gen 1
        val loudFrame = ShortArray(960) { 12000 }
        val acceptedStale = coordinator.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(
                generationId = gen1,
                pcmSamples = loudFrame,
                hardwareSampleRateHz = 48000
            )
        )
        assertFalse("Stale callback from Gen 1 must be rejected", acceptedStale)
        assertEquals(1, coordinator.audioCapture.state.value.staleCallbacksDropped)
        assertEquals(0L, coordinator.audioCapture.state.value.capturedFrameCount)

        // Inject a valid frame callback belonging to active Gen 2 (48kHz -> resampled to 16kHz)
        val acceptedActive = coordinator.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(
                generationId = gen2,
                pcmSamples = loudFrame,
                hardwareSampleRateHz = 48000
            )
        )
        assertTrue("Active generation callback must be accepted", acceptedActive)
        val snap = coordinator.audioCapture.state.value
        assertEquals(1L, snap.capturedFrameCount)
        assertEquals(320L, snap.capturedSampleCount) // 960 @ 48kHz -> 320 @ 16kHz
        assertTrue(snap.resamplingActive)
        assertTrue(snap.currentRmsLinear > 0.3f)
    }

    @Test
    fun `ephemeral recording and playback check verifies audio and immediately deletes buffer`() {
        val driver = ControllableTestAudioDriver()
        val coordinator = ConversationCoordinator(audioCapture = AudioCapture(driver))
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        val start = coordinator.dispatch(CoordinatorCommand.START_MIC_SELF_TEST) as VerifiedOutcome.AudioSelfTestOutcome
        val gen = start.generationId!!

        // Feed two 20ms frames at 16kHz (640 samples total)
        val frame16k = ShortArray(320) { 8000 }
        coordinator.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(gen, frame16k, 16000)
        )
        coordinator.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(gen, frame16k, 16000)
        )

        assertEquals(640, coordinator.audioCapture.state.value.ephemeralRecordedSamples)

        // Stop self-test capture (retaining ephemeral clip only for immediate check)
        coordinator.dispatch(CoordinatorCommand.STOP_MIC_SELF_TEST)
        assertEquals(640, coordinator.audioCapture.state.value.ephemeralRecordedSamples)

        // Play & Delete Ephemeral Check
        coordinator.dispatch(CoordinatorCommand.PLAY_EPHEMERAL_CHECK)
        val afterPlayback = coordinator.audioCapture.state.value
        assertEquals(640, driver.lastPlayedSamplesCount)
        assertEquals("Ephemeral buffer must be wiped after playback", 0, afterPlayback.ephemeralRecordedSamples)
        assertEquals(1, afterPlayback.ephemeralPlaybackCount)
        assertTrue(afterPlayback.ephemeralLastWipedStatus.contains("0 bytes remaining"))
    }

    @Test
    fun `detects Android-imposed silence, missing device, recorder start failure, and interruption`() {
        val driver = ControllableTestAudioDriver()
        val coordinator = ConversationCoordinator(audioCapture = AudioCapture(driver))
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        // 1. Missing device
        driver.hasMic = false
        coordinator.dispatch(CoordinatorCommand.START_MIC_SELF_TEST)
        assertEquals(AudioAnomaly.MISSING_MICROPHONE_DEVICE, coordinator.audioCapture.state.value.anomaly)

        // 2. Recorder start failure
        driver.hasMic = true
        driver.startResult = HardwareStartResult.Failed(
            anomaly = AudioAnomaly.RECORDER_START_FAILURE,
            diagnostic = "Simulated AudioRecord start failure"
        )
        coordinator.dispatch(CoordinatorCommand.START_MIC_SELF_TEST)
        assertEquals(AudioAnomaly.RECORDER_START_FAILURE, coordinator.audioCapture.state.value.anomaly)

        // 3. Android-imposed silence & interruption
        driver.startResult = HardwareStartResult.Started(16000)
        val started = coordinator.dispatch(CoordinatorCommand.START_MIC_SELF_TEST) as VerifiedOutcome.AudioSelfTestOutcome
        val gen = started.generationId!!

        // OS reports client silenced
        coordinator.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(
                generationId = gen,
                pcmSamples = ShortArray(320) { 0 },
                hardwareSampleRateHz = 16000,
                osClientSilenced = true
            )
        )
        assertEquals(AudioAnomaly.ANDROID_IMPOSED_SILENCE, coordinator.audioCapture.state.value.anomaly)

        // Audio interruption stops capture and releases hardware
        coordinator.onAudioHardwareCallback(
            CaptureCallbackEvent.InterruptionDetected(
                generationId = gen,
                reason = "AudioFocus lost to phone call"
            )
        )
        assertFalse(coordinator.audioCapture.state.value.isCapturing)
        assertEquals(AudioAnomaly.AUDIO_INTERRUPTED, coordinator.audioCapture.state.value.anomaly)
    }
}
