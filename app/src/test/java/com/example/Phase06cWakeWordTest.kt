package com.example

import com.example.audio.*
import com.example.contract.*
import com.example.coordinator.ConversationCoordinator
import com.example.live.ControllableLiveNetworkMonitor
import com.example.live.DeterministicTestLiveTransportDriver
import com.example.live.GeminiLiveConnectionAdapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

private class Phase06ControllableTestAudioDriver(
    var hasMic: Boolean = true,
    var startResult: HardwareStartResult = HardwareStartResult.Started(16000),
    var onStartCallback: ((CaptureGenerationId, (CaptureCallbackEvent) -> Unit) -> Unit)? = null
) : AudioHardwareDriver {
    override fun hasMicrophoneDevice(): Boolean = hasMic

    override fun startHardwareCapture(
        generationId: CaptureGenerationId,
        onEvent: (CaptureCallbackEvent) -> Unit
    ): HardwareStartResult {
        onStartCallback?.invoke(generationId, onEvent)
        return startResult
    }

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

class Phase06cWakeWordTest {

    private fun createTestCoordinator(
        wakeWordDetector: WakeWordDetector = FakeWakeWordDetector()
    ): ConversationCoordinator {
        val micDriver = Phase06ControllableTestAudioDriver(hasMic = true)
        val audioCapture = AudioCapture(driver = micDriver)
        val liveDriver = DeterministicTestLiveTransportDriver()
        val networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true)
        val liveAdapter = GeminiLiveConnectionAdapter(
            driver = liveDriver,
            networkMonitor = networkMonitor,
            delayRunner = {}
        )
        return ConversationCoordinator(
            audioCapture = audioCapture,
            liveAdapter = liveAdapter,
            wakeWordDetector = wakeWordDetector
        )
    }

    @Test
    fun test1_silenceInForegroundCreatesNoGeminiTurn() = runBlocking {
        val fakeKws = FakeWakeWordDetector(triggerOnNextChunk = false)
        val coordinator = createTestCoordinator(wakeWordDetector = fakeKws)
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        coordinator.startVoiceTurn()
        assertEquals(ConversationState.LISTENING, coordinator.uiState.value.state)
        assertTrue(coordinator.uiState.value.wakeAuthorizationActive)

        val captureGen = coordinator.audioCapture.state.value.activeGeneration!!
        val silence = ShortArray(320) { 0 }
        repeat(10) {
            coordinator.onAudioHardwareCallback(CaptureCallbackEvent.FrameCaptured(captureGen, silence, 16000))
        }

        assertEquals(0L, coordinator.uiState.value.voiceTurnFramesSent)
    }

    @Test
    fun test2_naturalConversationWithoutJarvisActivatesTurn() = runBlocking {
        val fakeKws = FakeWakeWordDetector(triggerOnNextChunk = false)
        val coordinator = createTestCoordinator(wakeWordDetector = fakeKws)
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        coordinator.startVoiceTurn()
        assertEquals(ConversationState.LISTENING, coordinator.uiState.value.state)

        val captureGen = coordinator.audioCapture.state.value.activeGeneration!!
        val speechChunk = ShortArray(320) { 8000 }
        coordinator.onAudioHardwareCallback(CaptureCallbackEvent.FrameCaptured(captureGen, speechChunk, 16000))

        assertTrue(coordinator.uiState.value.wakeAuthorizationActive)
    }

    @Test
    fun test3_pauseStopInvalidatesAuthorizationImmediately() = runBlocking {
        val fakeKws = FakeWakeWordDetector(triggerOnNextChunk = false)
        val coordinator = createTestCoordinator(wakeWordDetector = fakeKws)
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        coordinator.startVoiceTurn()
        assertTrue(coordinator.uiState.value.wakeAuthorizationActive)

        coordinator.dispatch(CoordinatorCommand.PAUSE)
        assertFalse(coordinator.uiState.value.wakeAuthorizationActive)
        assertEquals(ConversationState.PAUSED, coordinator.uiState.value.state)
    }

    @Test
    fun test4_staleGenerationCallbacksRejected() = runBlocking {
        val fakeKws = FakeWakeWordDetector(triggerOnNextChunk = false)
        val coordinator = createTestCoordinator(wakeWordDetector = fakeKws)
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        coordinator.startVoiceTurn()
        val gen1 = coordinator.audioCapture.state.value.activeGeneration!!
        coordinator.dispatch(CoordinatorCommand.STOP)
        coordinator.startVoiceTurn()
        val gen2 = coordinator.audioCapture.state.value.activeGeneration!!
        assertNotEquals(gen1, gen2)

        val accepted = coordinator.onAudioHardwareCallback(CaptureCallbackEvent.FrameCaptured(gen1, ShortArray(320), 16000))
        assertFalse(accepted)
    }
}
