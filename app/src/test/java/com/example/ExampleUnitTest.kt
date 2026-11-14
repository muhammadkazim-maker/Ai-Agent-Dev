package com.example

import com.example.audio.AudioAnomaly
import com.example.audio.AudioCapture
import com.example.audio.AudioHardwareDriver
import com.example.audio.CaptureCallbackEvent
import com.example.audio.CaptureGenerationId
import com.example.audio.DeterministicStreamingAudioTrackDriver
import com.example.audio.HardwareStartResult
import com.example.audio.LiveAudioPlaybackAdapter
import com.example.audio.MicPermissionState
import com.example.audio.PcmChunkWriteOutcome
import com.example.audio.PlaybackCallbackEvent
import com.example.audio.PlaybackGenerationId
import com.example.audio.StreamingAudioTrackDriver
import com.example.contract.ActionId
import com.example.contract.ConversationState
import com.example.contract.CoordinatorCommand
import com.example.contract.SessionId
import com.example.contract.VerifiedOutcome
import com.example.coordinator.ConversationCoordinator
import com.example.live.BoundedBackoffPolicy
import com.example.live.ControllableLiveNetworkMonitor
import com.example.live.DeterministicTestLiveTransportDriver
import com.example.live.DiagnosticRedactor
import com.example.live.GeminiLiveConnectionAdapter
import com.example.live.LiveConfigConstants
import com.example.live.LiveConnectionPhase
import com.example.live.LiveFailureClassification
import com.example.live.LiveHandshakeResult
import com.example.live.LiveSessionGenerationId
import com.example.live.LiveSocketCallbackEvent
import com.example.ui.shell.ConversationShellViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class ControllableTestAudioDriver(
    var hasMic: Boolean = true,
    var startResult: HardwareStartResult = HardwareStartResult.Started(48000),
    var onStartCallback: ((CaptureGenerationId, (CaptureCallbackEvent) -> Unit) -> Unit)? = null
) : AudioHardwareDriver {
    var startCaptureCalls = 0
    var stopCaptureCalls = 0
    var stopPlaybackCalls = 0
    var lastPlayedSamplesCount = 0

    override fun hasMicrophoneDevice(): Boolean = hasMic

    override fun startHardwareCapture(
        generationId: CaptureGenerationId,
        onEvent: (CaptureCallbackEvent) -> Unit
    ): HardwareStartResult {
        startCaptureCalls++
        onStartCallback?.invoke(generationId, onEvent)
        return startResult
    }

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
        assertFalse("Phase 01–03 shell must never fake an active microphone", state.isMicrophoneActive)
        assertTrue(state.sessionId.value.startsWith("ov01-sess-"))
        assertTrue(state.currentTurnId.value.startsWith("ov01-turn-"))
        assertTrue(state.lastActionId.value.startsWith("ov01-act-init-"))
        assertEquals(1, state.transcript.size)
    }

    @Test
    fun `start command never remains NOT_CONFIGURED when valid adapters exist and transitions to CONNECTING when prerequisites pass`() {
        val coordinator = ConversationCoordinator()
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        val outcome = coordinator.dispatch(CoordinatorCommand.START)
        val state = coordinator.uiState.value

        assertNotEquals("Start must never remain NOT_CONFIGURED when valid adapters exist", ConversationState.NOT_CONFIGURED, state.state)
        assertTrue(outcome is VerifiedOutcome.StateTransitionVerified)
        assertEquals(ConversationState.CONNECTING, state.state)

        // Only an explicitly unconfigured coordinator instance may return NOT_CONFIGURED
        val unconfiguredCoordinator = ConversationCoordinator(isVoiceTurnConfigured = false)
        val unconfiguredOutcome = unconfiguredCoordinator.dispatch(CoordinatorCommand.START)
        assertTrue(unconfiguredOutcome is VerifiedOutcome.HonestNotConfiguredOutcome)
        assertEquals(ConversationState.NOT_CONFIGURED, unconfiguredCoordinator.uiState.value.state)
    }

    @Test
    fun `stop and pause precedence enforces STOP over PAUSE and START`() {
        val coordinator = ConversationCoordinator()
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        coordinator.dispatch(CoordinatorCommand.START)
        assertEquals(ConversationState.CONNECTING, coordinator.uiState.value.state)

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
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        val fixedActionId = ActionId("ov01-act-custom-0099")

        val firstOutcome = coordinator.dispatch(CoordinatorCommand.START, fixedActionId)
        assertTrue(firstOutcome is VerifiedOutcome.StateTransitionVerified)
        val countAfterFirst = coordinator.uiState.value.executedActionCount

        val duplicateOutcome = coordinator.dispatch(CoordinatorCommand.STOP, fixedActionId)
        assertTrue(duplicateOutcome is VerifiedOutcome.DuplicateActionRejected)
        assertEquals(ConversationState.CONNECTING, coordinator.uiState.value.state)
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

    // --- Phase 03 Gemini Live Connection Adapter & Redacted Diagnostics Tests ---

    @Test
    fun `live adapter connects and transitions CONNECTING to SETUP_ACCEPTED with zero tools and no audio playback claimed`() = runBlocking {
        val observedPhases = mutableListOf<LiveConnectionPhase>()
        lateinit var adapter: GeminiLiveConnectionAdapter
        val driver = DeterministicTestLiveTransportDriver { genId, modelId, _, onSocketEvent ->
            // Observe that the adapter is in CONNECTING before handshake completes
            observedPhases.add(adapter.state.value.phase)
            assertEquals(LiveConfigConstants.SUPPORTED_LIVE_MODEL_ID, modelId)
            val note = "SETUP_ACCEPTED (${modelId} @ ${genId.wireId})"
            onSocketEvent(LiveSocketCallbackEvent.SetupAccepted(genId, note))
            LiveHandshakeResult.Accepted(genId, note)
        }
        adapter = GeminiLiveConnectionAdapter(
            driver = driver,
            delayRunner = { /* instant in unit test */ }
        )
        val coordinator = ConversationCoordinator(liveAdapter = adapter)

        val outcome = coordinator.connectLiveSessionWithHandshake()
        val liveState = coordinator.liveAdapter.state.value

        assertEquals(listOf(LiveConnectionPhase.CONNECTING), observedPhases)
        assertEquals(LiveConnectionPhase.SETUP_ACCEPTED, outcome.livePhase)
        assertEquals(LiveConnectionPhase.SETUP_ACCEPTED, liveState.phase)
        assertTrue(liveState.handshakeVerified)
        assertFalse("Phase 03 must NEVER claim audio playback", liveState.audioPlaybackClaimed)
        assertEquals("Phase 03 must register zero action tools", 0, liveState.registeredActionToolsCount)
        assertEquals(LiveConfigConstants.SUPPORTED_LIVE_MODEL_ID, liveState.diagnostics.modelId)
        assertEquals(
            LiveConfigConstants.ENDPOINT_FAMILY.wireCode,
            liveState.diagnostics.endpointFamily
        )
        assertEquals(
            LiveConfigConstants.AUTH_ROUTE.wireCode,
            liveState.diagnostics.authRoute
        )
    }

    @Test
    fun `live adapter bounded backoff retries transient network failures and stops after maxAttempts`() = runBlocking {
        val recordedBackoffDelays = mutableListOf<Long>()
        val requestedModels = mutableListOf<String>()
        val driver = DeterministicTestLiveTransportDriver { genId, modelId, attempt, _ ->
            requestedModels.add(modelId)
            LiveHandshakeResult.Failed(
                generationId = genId,
                classification = LiveFailureClassification.TRANSIENT_NETWORK_OR_SOCKET,
                rawDetail = "Simulated WebSocket EOF on attempt $attempt"
            )
        }
        val adapter = GeminiLiveConnectionAdapter(
            driver = driver,
            backoffPolicy = BoundedBackoffPolicy(
                maxAttempts = 3,
                initialDelayMs = 500L,
                multiplier = 2.0,
                maxDelayMs = 4000L
            ),
            delayRunner = { ms -> recordedBackoffDelays.add(ms) }
        )

        val finalState = adapter.connectWithBoundedRetry(SessionId("ov01-sess-001"))

        assertEquals(LiveConnectionPhase.FAILED, finalState.phase)
        assertEquals(LiveFailureClassification.TRANSIENT_NETWORK_OR_SOCKET, finalState.failureClassification)
        assertEquals(3, finalState.attemptCount)
        assertEquals(2, finalState.retryCount)
        assertEquals(listOf(500L, 1000L), recordedBackoffDelays)
        // Verify model never silently switched across all 3 attempts
        assertEquals(
            listOf(
                LiveConfigConstants.SUPPORTED_LIVE_MODEL_ID,
                LiveConfigConstants.SUPPORTED_LIVE_MODEL_ID,
                LiveConfigConstants.SUPPORTED_LIVE_MODEL_ID
            ),
            requestedModels
        )
        assertTrue(
            finalState.autoRetryStoppedReason?.contains("BOUNDED_BACKOFF_EXHAUSTED_AFTER_3_ATTEMPTS") == true
        )
    }

    @Test
    fun `live adapter immediately stops automatic retries on bad credentials, unsupported model, and exhausted quota without switching models`() = runBlocking {
        val nonRetryableCases = listOf(
            LiveFailureClassification.BAD_CREDENTIALS_OR_APP_CHECK,
            LiveFailureClassification.UNSUPPORTED_MODEL_OR_ENDPOINT,
            LiveFailureClassification.QUOTA_EXHAUSTED
        )

        for (failureClass in nonRetryableCases) {
            var attemptsInvoked = 0
            val backoffDelays = mutableListOf<Long>()
            val driver = DeterministicTestLiveTransportDriver { genId, modelId, _, _ ->
                attemptsInvoked++
                assertEquals(LiveConfigConstants.SUPPORTED_LIVE_MODEL_ID, modelId)
                LiveHandshakeResult.Failed(
                    generationId = genId,
                    classification = failureClass,
                    rawDetail = "Simulated non-retryable ${failureClass.wireCode}"
                )
            }
            val adapter = GeminiLiveConnectionAdapter(
                driver = driver,
                delayRunner = { ms -> backoffDelays.add(ms) }
            )

            val state = adapter.connectWithBoundedRetry(SessionId("ov01-sess-001"))

            assertEquals("Must stop after 1 attempt for ${failureClass.wireCode}", 1, attemptsInvoked)
            assertEquals("Must perform 0 retries for ${failureClass.wireCode}", 0, state.retryCount)
            assertTrue("Must not wait on backoff for ${failureClass.wireCode}", backoffDelays.isEmpty())
            assertEquals(LiveConnectionPhase.FAILED, state.phase)
            assertEquals(failureClass, state.failureClassification)
            assertEquals(
                "Model ID must never silently switch on ${failureClass.wireCode}",
                LiveConfigConstants.SUPPORTED_LIVE_MODEL_ID,
                state.configuredModelId
            )
            assertTrue(
                state.autoRetryStoppedReason?.contains("AUTO_RETRY_HALTED_${failureClass.wireCode}") == true
            )
        }
    }

    @Test
    fun `live adapter drops stale socket callbacks from superseded or closed session generations`() = runBlocking {
        val driver = DeterministicTestLiveTransportDriver()
        val adapter = GeminiLiveConnectionAdapter(
            driver = driver,
            delayRunner = {}
        )

        // Connect session 1 -> generation 1 active
        val state1 = adapter.connectWithBoundedRetry(SessionId("ov01-sess-001"))
        val gen1 = state1.activeGeneration!!
        assertEquals(LiveConnectionPhase.SETUP_ACCEPTED, state1.phase)

        // Connect session 2 -> generation 2 supersedes generation 1
        val state2 = adapter.connectWithBoundedRetry(SessionId("ov01-sess-001"))
        val gen2 = state2.activeGeneration!!
        assertNotEquals(gen1, gen2)

        // Inject a stale failure callback from generation 1 -> must be dropped and NOT fail generation 2!
        val staleAccepted = adapter.onSocketCallback(
            LiveSocketCallbackEvent.HandshakeFailed(
                generationId = gen1,
                classification = LiveFailureClassification.TRANSIENT_NETWORK_OR_SOCKET,
                rawErrorMessage = "Late error from old socket gen1"
            )
        )
        assertFalse("Callback from superseded socket generation must be rejected", staleAccepted)
        assertEquals(1, adapter.state.value.staleSocketCallbacksDropped)
        assertEquals(LiveConnectionPhase.SETUP_ACCEPTED, adapter.state.value.phase)

        // Disconnect active generation 2 -> late callback from gen2 must also be dropped
        adapter.disconnectAndRelease("Operator disconnected")
        assertEquals(LiveConnectionPhase.DISCONNECTED, adapter.state.value.phase)

        val lateFromClosedGen2 = adapter.onSocketCallback(
            LiveSocketCallbackEvent.SetupAccepted(
                generationId = gen2,
                serverNote = "Late setup frame after close"
            )
        )
        assertFalse(lateFromClosedGen2)
        assertEquals(2, adapter.state.value.staleSocketCallbacksDropped)
    }

    @Test
    fun `diagnostic redactor strips API keys, bearer tokens, and App Check headers from diagnostics`() {
        val rawWithSecrets =
            "WebSocket 403 failed with key=AIzaSyD1234567890abcdefghijklmnopqrstuv and Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.secret.sig and x-firebase-appcheck: secretAppCheckToken999"
        val redacted = DiagnosticRedactor.redact(rawWithSecrets)

        assertFalse(redacted.contains("AIzaSyD1234567890abcdefghijklmnopqrstuv"))
        assertFalse(redacted.contains("eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9"))
        assertFalse(redacted.contains("secretAppCheckToken999"))
        assertTrue(redacted.contains("[REDACTED_API_KEY]"))
        assertTrue(redacted.contains("[REDACTED_BEARER_TOKEN]"))
        assertTrue(redacted.contains("[REDACTED_APP_CHECK_TOKEN]"))
    }

    @Test
    fun `production default live adapter reports honest BLOCKED failure and halts retries when runtime credentials are unconfigured`() = runBlocking {
        // Default constructor uses real FirebaseAiLiveTransportDriver (no fake success)
        val productionAdapter = GeminiLiveConnectionAdapter()
        val state = productionAdapter.connectWithBoundedRetry(SessionId("ov01-sess-001"))

        assertEquals(LiveConnectionPhase.FAILED, state.phase)
        assertEquals(LiveFailureClassification.BAD_CREDENTIALS_OR_APP_CHECK, state.failureClassification)
        assertFalse("Production adapter must never fake SETUP_ACCEPTED when unconfigured", state.handshakeVerified)
        assertEquals("Must halt automatic retries after 1 attempt", 1, state.attemptCount)
        assertEquals(0, state.retryCount)
        assertTrue(state.diagnostics.redactedSummary.contains("BLOCKED_PREREQUISITE_UNCONFIGURED"))
    }

    // --- Phase 03 Offline Detection, Network Validation & Honest Error Classification Tests ---

    @Test
    fun `SETUP_ACCEPTED immediately becomes NETWORK_UNAVAILABLE and closes socket when validated internet is lost`() = runBlocking {
        val networkMonitor = com.example.live.ControllableLiveNetworkMonitor(initialValidated = true)
        val driver = DeterministicTestLiveTransportDriver()
        val adapter = GeminiLiveConnectionAdapter(
            driver = driver,
            networkMonitor = networkMonitor,
            delayRunner = {}
        )
        val coordinator = ConversationCoordinator(liveAdapter = adapter)
        coordinator.startLiveNetworkMonitoring()

        val outcome = coordinator.connectLiveSessionWithHandshake()
        assertEquals(LiveConnectionPhase.SETUP_ACCEPTED, outcome.livePhase)
        assertEquals(LiveConnectionPhase.SETUP_ACCEPTED, coordinator.liveAdapter.state.value.phase)
        assertTrue(coordinator.liveAdapter.state.value.handshakeVerified)

        // Simulate Wi-Fi and mobile data disabled (validated internet lost)
        networkMonitor.setInternetValidated(false)

        val offlineState = coordinator.liveAdapter.state.value
        val coordinatorLiveState = coordinator.uiState.value.liveConnectionState
        assertEquals(
            "Stale SETUP_ACCEPTED must immediately become NETWORK_UNAVAILABLE when internet is lost",
            LiveConnectionPhase.NETWORK_UNAVAILABLE,
            offlineState.phase
        )
        assertEquals(
            "Coordinator uiState.liveConnectionState must also immediately reflect NETWORK_UNAVAILABLE",
            LiveConnectionPhase.NETWORK_UNAVAILABLE,
            coordinatorLiveState.phase
        )
        assertEquals(LiveFailureClassification.NETWORK_UNAVAILABLE, offlineState.failureClassification)
        assertFalse("Must never report handshakeVerified while offline", offlineState.handshakeVerified)
        assertFalse("Coordinator uiState must never report handshakeVerified while offline", coordinatorLiveState.handshakeVerified)
        assertEquals(null, offlineState.activeGeneration)
        assertTrue("Active socket must be closed when internet is lost", driver.closedGenerations.isNotEmpty())
    }

    @Test
    fun `Verify while offline makes zero Firebase or Gemini requests and returns NETWORK_UNAVAILABLE`() = runBlocking {
        val networkMonitor = com.example.live.ControllableLiveNetworkMonitor(initialValidated = false)
        val driver = DeterministicTestLiveTransportDriver()
        val adapter = GeminiLiveConnectionAdapter(
            driver = driver,
            networkMonitor = networkMonitor,
            delayRunner = {}
        )
        val coordinator = ConversationCoordinator(liveAdapter = adapter)

        val outcome = coordinator.connectLiveSessionWithHandshake()
        val state = coordinator.liveAdapter.state.value

        assertEquals("Must make zero Firebase/Gemini requests while offline", 0, driver.invocationCount)
        assertEquals(LiveConnectionPhase.NETWORK_UNAVAILABLE, outcome.livePhase)
        assertEquals(LiveConnectionPhase.NETWORK_UNAVAILABLE, state.phase)
        assertEquals(
            "Offline Verify must report NETWORK_UNAVAILABLE, never BAD_CREDENTIALS_OR_APP_CHECK",
            LiveFailureClassification.NETWORK_UNAVAILABLE,
            state.failureClassification
        )
        assertFalse(state.handshakeVerified)
        assertEquals(0, state.attemptCount)
        assertEquals(0, state.retryCount)
    }

    @Test
    fun `network recovery shows NETWORK_RESTORED and performs at most one controlled reconnect`() = runBlocking {
        // 1. Verify NETWORK_RESTORED / READY_TO_RECONNECT state when autoReconnectOnNetworkRestore = false
        val manualMonitor = com.example.live.ControllableLiveNetworkMonitor(initialValidated = false)
        val manualDriver = DeterministicTestLiveTransportDriver()
        val manualAdapter = GeminiLiveConnectionAdapter(
            driver = manualDriver,
            networkMonitor = manualMonitor,
            autoReconnectOnNetworkRestore = false,
            delayRunner = {}
        )
        manualAdapter.startNetworkMonitoring()
        assertEquals(LiveConnectionPhase.NETWORK_UNAVAILABLE, manualAdapter.state.value.phase)

        manualMonitor.setInternetValidated(true)
        assertEquals(LiveConnectionPhase.NETWORK_RESTORED, manualAdapter.state.value.phase)
        assertTrue(manualAdapter.state.value.statusHeadline.contains("NETWORK_RESTORED / READY_TO_RECONNECT"))
        assertEquals(0, manualDriver.invocationCount)

        // 2. Verify automatic recovery performs at most ONE controlled reconnect and ignores duplicate callbacks
        val autoMonitor = com.example.live.ControllableLiveNetworkMonitor(initialValidated = true)
        val autoDriver = DeterministicTestLiveTransportDriver()
        val autoAdapter = GeminiLiveConnectionAdapter(
            driver = autoDriver,
            networkMonitor = autoMonitor,
            autoReconnectOnNetworkRestore = true,
            delayRunner = {}
        )
        autoAdapter.startNetworkMonitoring()

        // Connect initially while online (1st call)
        val initial = autoAdapter.connectWithBoundedRetry(SessionId("ov01-sess-001"))
        assertEquals(LiveConnectionPhase.SETUP_ACCEPTED, initial.phase)
        assertEquals(1, autoDriver.invocationCount)

        // Drop internet -> NETWORK_UNAVAILABLE
        autoMonitor.setInternetValidated(false)
        assertEquals(LiveConnectionPhase.NETWORK_UNAVAILABLE, autoAdapter.state.value.phase)

        // Restore internet -> performs 1 controlled reconnect (2nd call total)
        autoMonitor.setInternetValidated(true)
        assertEquals(LiveConnectionPhase.SETUP_ACCEPTED, autoAdapter.state.value.phase)
        assertEquals(1, autoAdapter.state.value.autoReconnectCount)
        assertEquals(2, autoDriver.invocationCount)

        // Duplicate network capability callbacks while online must NOT trigger additional reconnects
        autoMonitor.setInternetValidated(true)
        autoMonitor.setInternetValidated(true)
        assertEquals("Must perform at most 1 controlled reconnect on recovery", 2, autoDriver.invocationCount)
        assertEquals(1, autoAdapter.state.value.autoReconnectCount)

        // 3. Verify that if the single controlled reconnect on network recovery fails, it does NOT loop
        var failingReconnectCalls = 0
        val failingDriver = DeterministicTestLiveTransportDriver { genId, _, attempt, onSocketEvent ->
            failingReconnectCalls++
            if (attempt == 1) {
                onSocketEvent(LiveSocketCallbackEvent.SetupAccepted(genId, "Initial online setup"))
                LiveHandshakeResult.Accepted(genId, "Initial online setup")
            } else {
                LiveHandshakeResult.Failed(
                    generationId = genId,
                    classification = LiveFailureClassification.NETWORK_ERROR,
                    rawDetail = "Socket reset during single controlled auto-reconnect"
                )
            }
        }
        val netMonitor3 = com.example.live.ControllableLiveNetworkMonitor(initialValidated = true)
        val adapter3 = GeminiLiveConnectionAdapter(
            driver = failingDriver,
            networkMonitor = netMonitor3,
            autoReconnectOnNetworkRestore = true,
            delayRunner = {}
        )
        adapter3.startNetworkMonitoring()
        adapter3.connectWithBoundedRetry(SessionId("ov01-sess-003"))
        assertEquals(1, failingReconnectCalls)

        netMonitor3.setInternetValidated(false)
        netMonitor3.setInternetValidated(true)
        // Must have attempted only 1 single reconnect (total calls = 2, not 4!)
        assertEquals("Controlled auto-reconnect must attempt at most 1 reconnect without looping", 2, failingReconnectCalls)
    }

    @Test
    fun `old callbacks cannot restore stale SETUP_ACCEPTED after offline loss or newer connection generation`() = runBlocking {
        val networkMonitor = com.example.live.ControllableLiveNetworkMonitor(initialValidated = true)
        val driver = DeterministicTestLiveTransportDriver()
        val adapter = GeminiLiveConnectionAdapter(
            driver = driver,
            networkMonitor = networkMonitor,
            autoReconnectOnNetworkRestore = false,
            delayRunner = {}
        )
        adapter.startNetworkMonitoring()

        // Establish gen1
        val state1 = adapter.connectWithBoundedRetry(SessionId("ov01-sess-001"))
        val gen1 = state1.activeGeneration!!
        assertEquals(LiveConnectionPhase.SETUP_ACCEPTED, state1.phase)

        // Lose internet -> NETWORK_UNAVAILABLE
        networkMonitor.setInternetValidated(false)
        assertEquals(LiveConnectionPhase.NETWORK_UNAVAILABLE, adapter.state.value.phase)

        // Late SetupAccepted callback from gen1 arrives -> MUST be rejected and NEVER restore SETUP_ACCEPTED
        val acceptedWhileOffline = adapter.onSocketCallback(
            LiveSocketCallbackEvent.SetupAccepted(
                generationId = gen1,
                serverNote = "Stale setup accepted from gen1"
            )
        )
        assertFalse("Old callback must be rejected after internet loss", acceptedWhileOffline)
        assertEquals(LiveConnectionPhase.NETWORK_UNAVAILABLE, adapter.state.value.phase)
        assertFalse(adapter.state.value.handshakeVerified)
        assertEquals(1, adapter.state.value.staleSocketCallbacksDropped)

        // Restore internet and start gen2 that fails with NETWORK_ERROR; late callback from gen1 still cannot restore SETUP_ACCEPTED
        networkMonitor.setInternetValidated(true)
        driver.scriptedHandler = { genId, _, _, _ ->
            LiveHandshakeResult.Failed(
                generationId = genId,
                classification = LiveFailureClassification.NETWORK_ERROR,
                rawDetail = "Simulated timeout on gen2"
            )
        }
        adapter.connectWithBoundedRetry(SessionId("ov01-sess-001"))
        assertEquals(LiveConnectionPhase.FAILED, adapter.state.value.phase)

        val acceptedAfterGen2 = adapter.onSocketCallback(
            LiveSocketCallbackEvent.SetupAccepted(
                generationId = gen1,
                serverNote = "Late setup accepted from gen1 after gen2"
            )
        )
        assertFalse("Old callback from gen1 must never overwrite gen2 state", acceptedAfterGen2)
        assertEquals(LiveConnectionPhase.FAILED, adapter.state.value.phase)
        assertFalse(adapter.state.value.handshakeVerified)
    }

    @Test
    fun `real HTTP 401 and 403 while internet is validated remain BAD_CREDENTIALS_OR_APP_CHECK`() = runBlocking {
        val authExceptions = listOf(
            RuntimeException("HTTP 401 Unauthorized: API_KEY_INVALID"),
            RuntimeException("HTTP 403 Forbidden: Firebase App Check token rejected (PERMISSION_DENIED)"),
            IllegalStateException("UNAUTHENTICATED: App Check attestation failed with 403")
        )

        for (ex in authExceptions) {
            val classified = com.example.live.LiveExceptionClassifier.classify(
                throwable = ex,
                isInternetValidated = true
            )
            assertEquals(
                "Real 401/403/AppCheck rejection while online must be BAD_CREDENTIALS_OR_APP_CHECK",
                LiveFailureClassification.BAD_CREDENTIALS_OR_APP_CHECK,
                classified
            )
        }
    }

    @Test
    fun `network, DNS, timeout, and socket IO errors are classified as NETWORK_ERROR and never mislabeled as credentials errors`() {
        val networkExceptions = listOf(
            java.net.UnknownHostException("Unable to resolve host \"generativelanguage.googleapis.com\": No address associated with hostname"),
            java.nio.channels.UnresolvedAddressException(), // Ktor WebSocket DNS exception with null message
            java.net.SocketTimeoutException("failed to connect to generativelanguage.googleapis.com after 25000ms"),
            java.net.ConnectException("Failed to connect to /142.250.190.42:443"),
            java.net.SocketException("Software caused connection abort"),
            java.io.EOFException("Unexpected end of stream on WebSocket"),
            // Request ID containing -403- or -401- on a transport error must NOT be misclassified as HTTP 403/401
            RuntimeException("WebSocket transport broken on request id a1b2-403-9901-401-ff"),
            // Crucial regression case: Firebase App Check wrapper exception caused by underlying DNS/IO failure
            RuntimeException(
                "FirebaseAppCheck: Failed to exchange App Check debug token",
                java.net.UnknownHostException("Unable to resolve host \"firebaseappcheck.googleapis.com\"")
            ),
            RuntimeException(
                "FirebaseAppCheck: Failed to exchange App Check debug token",
                java.nio.channels.UnresolvedAddressException()
            ),
            RuntimeException(
                "App Check token request failed",
                java.io.IOException("Network is unreachable")
            )
        )

        for (ex in networkExceptions) {
            val classifiedOnline = com.example.live.LiveExceptionClassifier.classify(
                throwable = ex,
                isInternetValidated = true
            )
            assertEquals(
                "Exception ${ex::class.simpleName}(${ex.message}) must be classified as NETWORK_ERROR, not ${classifiedOnline.wireCode}",
                LiveFailureClassification.NETWORK_ERROR,
                classifiedOnline
            )

            val classifiedOffline = com.example.live.LiveExceptionClassifier.classify(
                throwable = ex,
                isInternetValidated = false
            )
            assertEquals(
                LiveFailureClassification.NETWORK_ERROR,
                classifiedOffline
            )
        }
    }

    // --- Phase 04 One-Turn Voice Coordinator, Native-Audio Playback & Stop Invalidation Tests ---

    @Test
    fun `phase 04 startVoiceTurn transitions through CONNECTING, LISTENING, PROCESSING, SPEAKING, and READY only after complete playback drain`() = runBlocking {
        val micDriver = ControllableTestAudioDriver(hasMic = true)
        val audioCapture = AudioCapture(driver = micDriver)
        val liveDriver = DeterministicTestLiveTransportDriver()
        val networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true)
        val liveAdapter = GeminiLiveConnectionAdapter(
            driver = liveDriver,
            networkMonitor = networkMonitor,
            delayRunner = {}
        )
        val outputDriver = DeterministicStreamingAudioTrackDriver()
        val playbackAdapter = LiveAudioPlaybackAdapter(
            driver = outputDriver,
            writeRetryDelayRunner = {},
            drainPollDelayRunner = {}
        )
        val coordinator = ConversationCoordinator(
            audioCapture = audioCapture,
            liveAdapter = liveAdapter,
            playbackAdapter = playbackAdapter
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        // 1. Press Start -> automatically connects Live session and enters LISTENING (never NOT_CONFIGURED)
        val startOutcome = coordinator.startVoiceTurn()
        assertTrue(startOutcome is VerifiedOutcome.StateTransitionVerified)
        assertEquals(ConversationState.LISTENING, coordinator.uiState.value.state)
        assertEquals(
            listOf(ConversationState.CONNECTING, ConversationState.LISTENING),
            coordinator.uiState.value.voiceTurnStateSequence
        )
        assertTrue("Microphone must be actively capturing during LISTENING", coordinator.uiState.value.isMicrophoneActive)
        assertEquals(LiveConnectionPhase.SETUP_ACCEPTED, coordinator.liveAdapter.state.value.phase)

        // 2. Capture spoken 16kHz PCM microphone frames and stream to Gemini Live
        val activeCaptureGen = coordinator.audioCapture.state.value.activeGeneration!!
        val spokenSamples = ShortArray(320) { 6000 } // 20ms at 16kHz
        val acceptedFrame = coordinator.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(
                generationId = activeCaptureGen,
                pcmSamples = spokenSamples,
                hardwareSampleRateHz = 16000
            )
        )
        assertTrue(acceptedFrame)
        assertEquals(320L, coordinator.uiState.value.voiceTurnFramesSent)
        assertEquals(640L, coordinator.uiState.value.voiceTurnBytesSent)
        assertEquals(1, liveDriver.sentAudioChunks.size)
        assertEquals(640, liveDriver.sentAudioChunks.first().size)

        // 3. Finish spoken question -> transitions LISTENING -> PROCESSING -> SPEAKING -> READY after hardware drain
        val turnOutcome = coordinator.finishSpokenTurnAndAwaitResponse()
        assertTrue(turnOutcome is VerifiedOutcome.VoiceTurnOutcome)
        val voiceOutcome = turnOutcome as VerifiedOutcome.VoiceTurnOutcome
        assertEquals(ConversationState.READY, voiceOutcome.resultingState)
        assertEquals(ConversationState.READY, coordinator.uiState.value.state)
        assertEquals(
            listOf(
                ConversationState.CONNECTING,
                ConversationState.LISTENING,
                ConversationState.PROCESSING,
                ConversationState.SPEAKING,
                ConversationState.READY
            ),
            voiceOutcome.stateSequence
        )
        assertFalse("Microphone must be released after LISTENING completes", coordinator.uiState.value.isMicrophoneActive)
        assertEquals(1, liveDriver.endOfTurnSignalCount)
        assertTrue("Playback must be completely drained before READY", voiceOutcome.isPlaybackDrained)
        assertTrue(coordinator.playbackAdapter.state.value.isPlaybackDrained)
        assertEquals(960L, voiceOutcome.playbackFramesDrained) // 2 chunks * 960 bytes = 1920 bytes = 960 16-bit frames
    }

    @Test
    fun `phase 04 partial audio writes and advancing hardware playback head complete without losing bytes or timing out`() = runBlocking {
        val outputDriver = DeterministicStreamingAudioTrackDriver(
            autoAdvancePlaybackHead = false
        )
        outputDriver.enqueueScriptedWriteSizes(200, 0, 300, 460) // Partial writes + 1 zero-write while head advances
        var pollStep = 0
        val playbackAdapter = LiveAudioPlaybackAdapter(
            driver = outputDriver,
            maxDrainPollIterations = 4,
            writeRetryDelayRunner = {
                outputDriver.manualPlaybackHeadFrames += 10L
            },
            drainPollDelayRunner = {
                // Simulate long response draining progressively in hardware across 10 polls (exceeding maxDrainPollIterations=4)
                pollStep += 1
                outputDriver.manualPlaybackHeadFrames = (pollStep * 50L).coerceAtMost(480L)
            }
        )
        val genId = playbackAdapter.startTurnPlayback(
            turnId = com.example.contract.TurnId("ov01-turn-0001"),
            sessionId = SessionId("ov01-sess-001")
        )

        // Write 960 bytes (480 frames at 24kHz) while head advances on zero write
        val chunkBytes = ByteArray(960) { idx -> (idx % 127).toByte() }
        val writeOutcome = playbackAdapter.verifyAndWritePcmChunk(
            generationId = genId,
            rawMimeType = "audio/pcm;rate=24000",
            pcmBytes = chunkBytes
        )
        assertTrue("Partial writes must loop until all 960 bytes are written without losing bytes", writeOutcome is PcmChunkWriteOutcome.WrittenToHardware)
        assertEquals(960L, playbackAdapter.state.value.bytesAcceptedByHardware)
        assertEquals(480L, playbackAdapter.state.value.totalFramesWrittenToHardware)
        assertTrue("Partial write count must be recorded", playbackAdapter.state.value.partialWriteCount >= 2)
        assertTrue(
            "Exact bytes written to hardware must match input chunk bytes",
            outputDriver.getAcceptedPcmBytes().contentEquals(chunkBytes)
        )

        // Now await hardware drain across 10 progressive polls (must not cut off because head is advancing!)
        val drained = playbackAdapter.awaitHardwareDrainAfterServerTurnComplete(genId)
        assertTrue("Advancing hardware playback head must never time out prematurely", drained)
        assertTrue(playbackAdapter.state.value.isPlaybackDrained)
        assertEquals(480L, playbackAdapter.state.value.hardwarePlaybackHeadFrames)
    }

    @Test
    fun `phase 04 Stop cancels microphone capture, model turn, and queued playback and prevents stopped response from replaying in next turn`() = runBlocking {
        val micDriver = ControllableTestAudioDriver(hasMic = true)
        val audioCapture = AudioCapture(driver = micDriver)
        var capturedTurn1LiveGen: LiveSessionGenerationId? = null
        val liveDriver = DeterministicTestLiveTransportDriver().apply {
            onEndOfTurnHandler = { genId, emitEvent ->
                capturedTurn1LiveGen = genId
                // Emit first chunk so coordinator enters SPEAKING, but do not complete turn yet
                emitEvent(
                    LiveSocketCallbackEvent.ServerAudioChunkReceived(
                        generationId = genId,
                        mimeType = "audio/pcm;rate=24000",
                        pcmBytes = ByteArray(960) { 0x11 }
                    )
                )
            }
        }
        val liveAdapter = GeminiLiveConnectionAdapter(
            driver = liveDriver,
            networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
            delayRunner = {}
        )
        val outputDriver = DeterministicStreamingAudioTrackDriver(autoAdvancePlaybackHead = false)
        val playbackAdapter = LiveAudioPlaybackAdapter(
            driver = outputDriver,
            writeRetryDelayRunner = {},
            drainPollDelayRunner = {},
            audioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )
        val coordinator = ConversationCoordinator(
            audioCapture = audioCapture,
            liveAdapter = liveAdapter,
            playbackAdapter = playbackAdapter
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        // Start turn 1 -> LISTENING
        coordinator.startVoiceTurn()
        assertEquals(ConversationState.LISTENING, coordinator.uiState.value.state)
        val turn1CapGen = coordinator.audioCapture.state.value.activeGeneration!!
        coordinator.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(turn1CapGen, ShortArray(320) { 6000 }, 16000)
        )

        // Transition to PROCESSING -> SPEAKING (chunk 1 arrives)
        coordinator.dispatch(CoordinatorCommand.END_SPOKEN_TURN)
        assertEquals(ConversationState.SPEAKING, coordinator.uiState.value.state)
        val turn1PlaybackGen = coordinator.playbackAdapter.state.value.activeGeneration!!
        assertEquals(960L, coordinator.playbackAdapter.state.value.bytesAcceptedByHardware)

        // Press STOP during SPEAKING -> cancels mic capture, model turn, and queued playback
        val flushCountBeforeStop = outputDriver.stopAndFlushCallCount
        val stopOutcome = coordinator.dispatch(CoordinatorCommand.STOP)
        assertEquals(ConversationState.STOPPED, stopOutcome.resultingState)
        assertEquals(ConversationState.STOPPED, coordinator.uiState.value.state)
        assertFalse(coordinator.audioCapture.state.value.isCapturing)
        assertFalse(coordinator.playbackAdapter.state.value.isPlaying)
        assertEquals(flushCountBeforeStop + 1, outputDriver.stopAndFlushCallCount)

        // Late audio chunk from stopped turn 1 arrives over socket -> rejected by both LiveAdapter and PlaybackAdapter
        val staleSocketAccepted = coordinator.onLiveSocketCallback(
            LiveSocketCallbackEvent.ServerAudioChunkReceived(
                generationId = capturedTurn1LiveGen!!,
                mimeType = "audio/pcm;rate=24000",
                pcmBytes = ByteArray(960) { 0x7F }
            )
        )
        assertFalse("Stopped turn's late socket audio callback must be rejected", staleSocketAccepted)

        val staleDirectPlaybackOutcome = coordinator.playbackAdapter.verifyAndWritePcmChunk(
            generationId = turn1PlaybackGen,
            rawMimeType = "audio/pcm;rate=24000",
            pcmBytes = ByteArray(960) { 0x7F }
        )
        assertTrue(
            "Stopped turn's playback generation must reject late audio writes",
            staleDirectPlaybackOutcome is PcmChunkWriteOutcome.DroppedStaleGeneration
        )

        // Now start Turn 2 with a clean response -> stopped Turn 1 audio never replays
        outputDriver.resetRecordedBytes()
        outputDriver.autoAdvancePlaybackHead = true
        liveDriver.onEndOfTurnHandler = { genId, emitEvent ->
            emitEvent(
                LiveSocketCallbackEvent.ServerAudioChunkReceived(
                    generationId = genId,
                    mimeType = "audio/pcm;rate=24000",
                    pcmBytes = ByteArray(480) { 0x05 }
                )
            )
            emitEvent(LiveSocketCallbackEvent.ServerTurnCompleted(generationId = genId))
        }

        coordinator.startVoiceTurn()
        assertEquals(ConversationState.LISTENING, coordinator.uiState.value.state)
        val turn2CapGen = coordinator.audioCapture.state.value.activeGeneration!!
        coordinator.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(turn2CapGen, ShortArray(320) { 6000 }, 16000)
        )
        val turn2Outcome = coordinator.finishSpokenTurnAndAwaitResponse()
        assertEquals(ConversationState.READY, turn2Outcome.resultingState)
        assertEquals(480L, coordinator.playbackAdapter.state.value.bytesAcceptedByHardware)
        assertEquals(240L, coordinator.playbackAdapter.state.value.hardwarePlaybackHeadFrames)
    }

    @Test
    fun `phase 04 configuration, network, microphone, and audio MIME failures are reported honestly`() = runBlocking {
        // 1. Missing microphone permission -> ERROR (MIC_PERMISSION_REQUIRED_NOT_REQUESTED)
        val coordNoPerm = ConversationCoordinator()
        val noPermOutcome = coordNoPerm.startVoiceTurn()
        assertTrue(noPermOutcome is VerifiedOutcome.PrerequisiteFailureOutcome)
        assertEquals(ConversationState.ERROR, coordNoPerm.uiState.value.state)
        assertEquals("MIC_PERMISSION_REQUIRED_NOT_REQUESTED", coordNoPerm.uiState.value.activeVoiceTurnErrorCode)

        // 2. Missing microphone hardware -> ERROR (MISSING_MICROPHONE_DEVICE)
        val coordNoMic = ConversationCoordinator(
            audioCapture = AudioCapture(ControllableTestAudioDriver(hasMic = false))
        )
        coordNoMic.onMicPermissionUpdated(MicPermissionState.GRANTED)
        val noMicOutcome = coordNoMic.startVoiceTurn()
        assertTrue(noMicOutcome is VerifiedOutcome.PrerequisiteFailureOutcome)
        assertEquals(ConversationState.ERROR, coordNoMic.uiState.value.state)
        assertEquals("MISSING_MICROPHONE_DEVICE", coordNoMic.uiState.value.activeVoiceTurnErrorCode)

        // 3. Offline network on Start -> ERROR (NETWORK_UNAVAILABLE), zero Firebase calls
        val offlineDriver = DeterministicTestLiveTransportDriver()
        val coordOffline = ConversationCoordinator(
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = offlineDriver,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = false),
                delayRunner = {}
            )
        )
        coordOffline.onMicPermissionUpdated(MicPermissionState.GRANTED)
        val offlineOutcome = coordOffline.startVoiceTurn()
        assertTrue(offlineOutcome is VerifiedOutcome.PrerequisiteFailureOutcome)
        assertEquals(ConversationState.ERROR, coordOffline.uiState.value.state)
        assertEquals("NETWORK_UNAVAILABLE", coordOffline.uiState.value.activeVoiceTurnErrorCode)
        assertEquals(0, offlineDriver.invocationCount)

        // 4. Bad credentials / App Check rejection during CONNECTING -> ERROR (BAD_CREDENTIALS_OR_APP_CHECK)
        val authFailDriver = DeterministicTestLiveTransportDriver { genId, _, _, _ ->
            LiveHandshakeResult.Failed(
                generationId = genId,
                classification = LiveFailureClassification.BAD_CREDENTIALS_OR_APP_CHECK,
                rawDetail = "HTTP 403 Forbidden: App Check token rejected"
            )
        }
        val coordAuthFail = ConversationCoordinator(
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = authFailDriver,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            )
        )
        coordAuthFail.onMicPermissionUpdated(MicPermissionState.GRANTED)
        val authFailOutcome = coordAuthFail.startVoiceTurn()
        assertTrue(authFailOutcome is VerifiedOutcome.PrerequisiteFailureOutcome)
        assertEquals(ConversationState.ERROR, coordAuthFail.uiState.value.state)
        assertEquals("BAD_CREDENTIALS_OR_APP_CHECK", coordAuthFail.uiState.value.activeVoiceTurnErrorCode)

        // 5. Unsupported server audio MIME -> ERROR (UNSUPPORTED_AUDIO_MIME)
        val badMimeDriver = DeterministicTestLiveTransportDriver().apply {
            onEndOfTurnHandler = { genId, emitEvent ->
                emitEvent(
                    LiveSocketCallbackEvent.ServerAudioChunkReceived(
                        generationId = genId,
                        mimeType = "audio/mp3",
                        pcmBytes = ByteArray(480)
                    )
                )
            }
        }
        val coordBadMime = ConversationCoordinator(
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = badMimeDriver,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            playbackAdapter = LiveAudioPlaybackAdapter(
                driver = DeterministicStreamingAudioTrackDriver(),
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {}
            )
        )
        coordBadMime.onMicPermissionUpdated(MicPermissionState.GRANTED)
        coordBadMime.startVoiceTurn()
        val badMimeCapGen = coordBadMime.audioCapture.state.value.activeGeneration!!
        coordBadMime.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(badMimeCapGen, ShortArray(320) { 6000 }, 16000)
        )
        val badMimeOutcome = coordBadMime.finishSpokenTurnAndAwaitResponse()
        assertEquals(ConversationState.ERROR, badMimeOutcome.resultingState)
        assertEquals("UNSUPPORTED_AUDIO_MIME", coordBadMime.uiState.value.activeVoiceTurnErrorCode)
    }

    @Test
    fun `phase 04 three consecutive voice turns and repeated Start Stop cycles complete without leaked jobs or duplicate owners`() = runBlocking {
        val micDriver = ControllableTestAudioDriver(hasMic = true)
        val audioCapture = AudioCapture(driver = micDriver)
        val outputDriver = DeterministicStreamingAudioTrackDriver(autoAdvancePlaybackHead = true)
        val turnPrompts = listOf(
            "What is the capital of Pakistan?" to "The capital of Pakistan is Islamabad.",
            "پاکستان کا دارالحکومت کیا ہے؟" to "پاکستان کا دارالحکومت اسلام آباد ہے۔",
            "Pakistan ka dar-ul-hukoomat kya hai?" to "Pakistan ka dar-ul-hukoomat Islamabad hai."
        )
        var turnIndex = 0
        val liveDriver = DeterministicTestLiveTransportDriver().apply {
            onEndOfTurnHandler = { genId, emitEvent ->
                val (userText, modelText) = turnPrompts[turnIndex % turnPrompts.size]
                turnIndex += 1
                emitEvent(
                    LiveSocketCallbackEvent.ServerTranscriptionReceived(
                        generationId = genId,
                        inputTranscript = userText,
                        outputTranscript = modelText
                    )
                )
                // Emit 4 chunks of 24kHz 16-bit mono PCM (4 * 960 bytes = 3840 bytes = 1920 frames)
                repeat(4) { chunkIdx ->
                    emitEvent(
                        LiveSocketCallbackEvent.ServerAudioChunkReceived(
                            generationId = genId,
                            mimeType = "audio/pcm;rate=24000;channels=1",
                            pcmBytes = ByteArray(960) { (chunkIdx + 1).toByte() }
                        )
                    )
                }
                emitEvent(LiveSocketCallbackEvent.ServerTurnCompleted(generationId = genId))
            }
        }
        val liveAdapter = GeminiLiveConnectionAdapter(
            driver = liveDriver,
            networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
            delayRunner = {},
            backgroundDispatcher = kotlinx.coroutines.Dispatchers.Default
        )
        val playbackAdapter = LiveAudioPlaybackAdapter(
            driver = outputDriver,
            writeRetryDelayRunner = {},
            drainPollDelayRunner = {}
        )
        val coordinator = ConversationCoordinator(
            audioCapture = audioCapture,
            liveAdapter = liveAdapter,
            playbackAdapter = playbackAdapter,
            backgroundDispatcher = kotlinx.coroutines.Dispatchers.Default
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        // Execute 3 full consecutive voice turns
        repeat(3) { idx ->
            val startOutcome = coordinator.startVoiceTurn()
            assertEquals("Turn ${idx + 1} must reach LISTENING", ConversationState.LISTENING, startOutcome.resultingState)
            assertTrue(coordinator.audioCapture.state.value.isCapturing)

            // Deliver 3 mic frames (60ms of speech)
            val captureGen = coordinator.audioCapture.state.value.activeGeneration!!
            repeat(3) {
                coordinator.onAudioHardwareCallback(
                    CaptureCallbackEvent.FrameCaptured(
                        generationId = captureGen,
                        pcmSamples = ShortArray(320) { 6000 },
                        hardwareSampleRateHz = 16000
                    )
                )
            }

            val finishOutcome = coordinator.finishSpokenTurnAndAwaitResponse(reason = "Turn ${idx + 1} complete")
            assertTrue(finishOutcome is VerifiedOutcome.VoiceTurnOutcome)
            val voiceOutcome = finishOutcome as VerifiedOutcome.VoiceTurnOutcome
            assertEquals("Turn ${idx + 1} must finish in READY", ConversationState.READY, voiceOutcome.resultingState)
            assertTrue("Turn ${idx + 1} must drain all 1920 frames", voiceOutcome.isPlaybackDrained)
            assertEquals(1920L, voiceOutcome.playbackFramesDrained)
            assertFalse("Turn ${idx + 1} must release mic capture", coordinator.audioCapture.state.value.isCapturing)
            assertFalse("Turn ${idx + 1} must leave zero active turn jobs", coordinator.hasActiveTurnJobs())
        }

        // Verify socket was reused across turns 2 and 3 (only 1 handshake needed while healthy)
        assertEquals(1, liveDriver.invocationCount)
        assertEquals(3, liveDriver.endOfTurnSignalCount)

        // Also run repeated Start -> Stop cycles to ensure zero leaked jobs or audio owners
        repeat(3) {
            coordinator.startVoiceTurn()
            assertEquals(ConversationState.LISTENING, coordinator.uiState.value.state)
            val stopOutcome = coordinator.dispatch(CoordinatorCommand.STOP)
            assertEquals(ConversationState.STOPPED, stopOutcome.resultingState)
            assertFalse(coordinator.audioCapture.state.value.isCapturing)
            assertFalse(coordinator.playbackAdapter.state.value.isPlaying)
            assertFalse("Stop must cancel all turn jobs cleanly", coordinator.hasActiveTurnJobs())
        }
    }

    @Test
    fun `phase 04 ServerTurnCompleted awaits all queued and partially written audio chunks before draining so long answers are never truncated`() = runBlocking {
        val outputDriver = DeterministicStreamingAudioTrackDriver(autoAdvancePlaybackHead = false)
        // Simulate partial AudioTrack writes across 5 chunks (5 * 960 = 4800 bytes = 2400 frames)
        outputDriver.enqueueScriptedWriteSizes(240, 240, 480, 320, 640, 480, 480)
        var drainPolls = 0
        val playbackAdapter = LiveAudioPlaybackAdapter(
            driver = outputDriver,
            writeRetryDelayRunner = {},
            drainPollDelayRunner = {
                drainPolls += 1
                outputDriver.manualPlaybackHeadFrames = (drainPolls * 400L).coerceAtMost(2400L)
            }
        )
        val liveDriver = DeterministicTestLiveTransportDriver().apply {
            onEndOfTurnHandler = { genId, emitEvent ->
                // Emit 5 audio chunks back-to-back followed immediately by ServerTurnCompleted
                repeat(5) { idx ->
                    emitEvent(
                        LiveSocketCallbackEvent.ServerAudioChunkReceived(
                            generationId = genId,
                            mimeType = "audio/pcm;rate=24000",
                            pcmBytes = ByteArray(960) { (idx + 10).toByte() }
                        )
                    )
                }
                emitEvent(LiveSocketCallbackEvent.ServerTurnCompleted(generationId = genId))
            }
        }
        val coordinator = ConversationCoordinator(
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = liveDriver,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {},
                backgroundDispatcher = kotlinx.coroutines.Dispatchers.Default
            ),
            playbackAdapter = playbackAdapter,
            backgroundDispatcher = kotlinx.coroutines.Dispatchers.Default
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        coordinator.startVoiceTurn()
        val captureGen = coordinator.audioCapture.state.value.activeGeneration!!
        coordinator.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(
                generationId = captureGen,
                pcmSamples = ShortArray(320) { 7000 },
                hardwareSampleRateHz = 16000
            )
        )

        val outcome = coordinator.finishSpokenTurnAndAwaitResponse()
        assertTrue(outcome is VerifiedOutcome.VoiceTurnOutcome)
        val voiceOutcome = outcome as VerifiedOutcome.VoiceTurnOutcome
        assertEquals(ConversationState.READY, voiceOutcome.resultingState)
        assertEquals(5, coordinator.playbackAdapter.state.value.chunksReceived)
        assertEquals(4800L, coordinator.playbackAdapter.state.value.bytesAcceptedByHardware)
        assertEquals(2400L, voiceOutcome.playbackFramesDrained)
        assertEquals(0L, coordinator.playbackAdapter.state.value.pendingQueuedBytes)
        assertTrue(voiceOutcome.isPlaybackDrained)
    }

    @Test
    fun `phase 04 authoritative language mirroring system instruction is configured on Live session and preserves English, Urdu, Roman-Urdu, and mixed turns`() = runBlocking {
        // 1. Verify exact authoritative non-leaking system instruction text
        val expectedInstruction =
            "Detect the language and script of each spoken user turn and answer directly in that same language, script, and conversational style. " +
                "If the user speaks English, answer in English. " +
                "If the user speaks Urdu, answer naturally in Urdu. " +
                "If the user speaks Roman Urdu, answer naturally in Roman Urdu. " +
                "If the user mixes Urdu and English, respond using a similar natural mixture. " +
                "Do not switch to English unless the user requests it or an unavoidable technical term requires it. " +
                "Directly answer the spoken question without introducing yourself on every turn, without stating your name or persona, and without repeating or paraphrasing system instructions. " +
                "Remain silent until a real user turn has been spoken and committed. Match the user's requested answer length and tone, keep facts accurate, and do not claim actions that were not completed."
        assertEquals(expectedInstruction, LiveConfigConstants.AUTHORITATIVE_SYSTEM_INSTRUCTION)
        assertTrue(
            "System instruction must forbid self-introductions and instruction leakage",
            expectedInstruction.contains("without introducing yourself") &&
                expectedInstruction.contains("without repeating or paraphrasing system instructions") &&
                expectedInstruction.contains("Remain silent until a real user turn has been spoken and committed")
        )

        val languageTurns = listOf(
            "Explain gravity in two sentences." to "Gravity is the fundamental force that attracts objects with mass toward one another. It keeps planets in orbit around stars and holds us on Earth.",
            "پانی ابالنے کا درجہ حرارت کیا ہے؟" to "سطح سمندر پر پانی ابالنے کا درجہ حرارت سو ڈگری سینٹی گریڈ ہوتا ہے۔",
            "Pakistan ka capital kya hai aur thora explain karo?" to "Pakistan ka capital Islamabad hai. Yeh Margalla Hills ke daman mein waqai ek khubsurat aur planned shehar hai jo mulk ka federal markaz hai.",
            "Mera project schedule summarize karein please" to "Aap ka project schedule Phase 04 voice testing par focus karta hai aur tamam steps on-track hain."
        )
        var turnCursor = 0
        val liveDriver = DeterministicTestLiveTransportDriver().apply {
            onEndOfTurnHandler = { genId, emitEvent ->
                val (spokenInput, spokenReply) = languageTurns[turnCursor++]
                emitEvent(
                    LiveSocketCallbackEvent.ServerTranscriptionReceived(
                        generationId = genId,
                        inputTranscript = spokenInput,
                        outputTranscript = spokenReply
                    )
                )
                emitEvent(
                    LiveSocketCallbackEvent.ServerAudioChunkReceived(
                        generationId = genId,
                        mimeType = "audio/pcm;rate=24000",
                        pcmBytes = ByteArray(960) { 0x22 }
                    )
                )
                emitEvent(LiveSocketCallbackEvent.ServerTurnCompleted(generationId = genId))
            }
        }
        val coordinator = ConversationCoordinator(
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = liveDriver,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            playbackAdapter = LiveAudioPlaybackAdapter(
                driver = DeterministicStreamingAudioTrackDriver(),
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {}
            )
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        languageTurns.forEach { (expectedUserTranscript, expectedModelTranscript) ->
            coordinator.startVoiceTurn()
            val captureGen = coordinator.audioCapture.state.value.activeGeneration!!
            coordinator.onAudioHardwareCallback(
                CaptureCallbackEvent.FrameCaptured(
                    generationId = captureGen,
                    pcmSamples = ShortArray(320) { 5000 },
                    hardwareSampleRateHz = 16000
                )
            )
            val finishOutcome = coordinator.finishSpokenTurnAndAwaitResponse()
            assertEquals(ConversationState.READY, finishOutcome.resultingState)
            assertEquals(expectedUserTranscript, coordinator.uiState.value.currentTurnUserTranscript)
            assertEquals(expectedModelTranscript, coordinator.uiState.value.currentTurnModelTranscript)
            assertFalse(
                "Response must never leak system persona introduction",
                coordinator.uiState.value.currentTurnModelTranscript.contains("Kazim’s natural voice assistant here")
            )
            assertTrue(
                "Transcript log must include mirrored response",
                coordinator.uiState.value.lastVoiceTurnSummary.contains(expectedModelTranscript)
            )
        }

        // Verify the authoritative system instruction was delivered only via Live session setup config
        assertEquals(expectedInstruction, liveDriver.lastReceivedSystemInstruction)
        assertTrue(
            liveDriver.receivedSystemInstructions.all { it == expectedInstruction }
        )
        assertEquals(expectedInstruction, coordinator.uiState.value.authoritativeSystemInstruction)
    }

    @Test
    fun `phase 04 session setup alone produces no spoken response and system instruction is never sent as user content`() = runBlocking {
        val liveDriver = DeterministicTestLiveTransportDriver()
        val outputDriver = DeterministicStreamingAudioTrackDriver()
        val coordinator = ConversationCoordinator(
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = liveDriver,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            playbackAdapter = LiveAudioPlaybackAdapter(
                driver = outputDriver,
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {}
            )
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        // 1. Connect Live session via Phase 03/04 handshake alone -> zero audio chunks sent, zero spoken responses
        val handshakeOutcome = coordinator.connectLiveSessionWithHandshake()
        assertEquals(LiveConnectionPhase.SETUP_ACCEPTED, handshakeOutcome.livePhase)
        assertEquals(0, liveDriver.sentAudioChunks.size)
        assertEquals(0, liveDriver.endOfTurnSignalCount)
        assertEquals(0, coordinator.playbackAdapter.state.value.chunksReceived)
        assertEquals("", coordinator.uiState.value.currentTurnModelTranscript)

        // 2. Start voice turn -> enters LISTENING, still zero prompt/instruction bytes sent as user content
        coordinator.startVoiceTurn()
        assertEquals(ConversationState.LISTENING, coordinator.uiState.value.state)
        assertEquals(0, liveDriver.sentAudioChunks.size)
        assertEquals(0, liveDriver.endOfTurnSignalCount)
        assertEquals(0, coordinator.playbackAdapter.state.value.chunksReceived)
    }

    @Test
    fun `phase 04 microphone stops before response playback, preserves non-zero RMS after LISTENING, and sends zero mic frames during SPEAKING`() = runBlocking {
        val micDriver = ControllableTestAudioDriver(hasMic = true)
        val audioCapture = AudioCapture(driver = micDriver)
        var micCountWhenFirstAudioChunkArrived = -1
        val liveDriver = DeterministicTestLiveTransportDriver().apply {
            onEndOfTurnHandler = { genId, emitEvent ->
                micCountWhenFirstAudioChunkArrived = sentAudioChunks.size
                emitEvent(
                    LiveSocketCallbackEvent.ServerAudioChunkReceived(
                        generationId = genId,
                        mimeType = "audio/pcm;rate=24000",
                        pcmBytes = ByteArray(960) { 0x15 }
                    )
                )
            }
        }
        val outputDriver = DeterministicStreamingAudioTrackDriver(autoAdvancePlaybackHead = false)
        val coordinator = ConversationCoordinator(
            audioCapture = audioCapture,
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = liveDriver,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            playbackAdapter = LiveAudioPlaybackAdapter(
                driver = outputDriver,
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {},
                audioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
            )
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        coordinator.startVoiceTurn()
        val captureGen = coordinator.audioCapture.state.value.activeGeneration!!

        // Stream 2 non-silent 16kHz PCM frames (amplitude 8000 -> RMS ~0.244)
        repeat(2) {
            coordinator.onAudioHardwareCallback(
                CaptureCallbackEvent.FrameCaptured(
                    generationId = captureGen,
                    pcmSamples = ShortArray(320) { 8000 },
                    hardwareSampleRateHz = 16000
                )
            )
        }
        assertEquals(640L, coordinator.uiState.value.voiceTurnFramesSent)
        assertTrue("Peak RMS must be > 0.20 during LISTENING", coordinator.uiState.value.voiceTurnPeakRmsLinear > 0.20f)

        // End spoken turn -> transitions to PROCESSING then SPEAKING as first server chunk is accepted by AudioTrack
        coordinator.dispatch(CoordinatorCommand.END_SPOKEN_TURN)
        assertEquals(ConversationState.SPEAKING, coordinator.uiState.value.state)
        assertFalse("Microphone must be stopped before SPEAKING", coordinator.audioCapture.state.value.isCapturing)

        // Verify RMS is NOT wiped to 0.000 after stopAndRelease!
        assertTrue(
            "AudioCapture currentRmsLinear must preserve captured RMS instead of resetting to 0.000",
            coordinator.audioCapture.state.value.currentRmsLinear > 0.20f
        )
        assertTrue(
            "Coordinator voiceTurnPeakRmsLinear must remain > 0.20 after LISTENING ends",
            coordinator.uiState.value.voiceTurnPeakRmsLinear > 0.20f
        )

        // Attempt to inject late/spurious microphone frames while in SPEAKING -> must be dropped and NEVER sent!
        val sentBeforeSpeakingMicAttempt = liveDriver.sentAudioChunks.size
        val acceptedDuringSpeaking = coordinator.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(
                generationId = captureGen,
                pcmSamples = ShortArray(320) { 9000 },
                hardwareSampleRateHz = 16000
            )
        )
        assertFalse("Mic frames during SPEAKING must be rejected", acceptedDuringSpeaking)
        assertEquals(
            "Zero microphone frames may be sent to Gemini Live during SPEAKING",
            sentBeforeSpeakingMicAttempt,
            liveDriver.sentAudioChunks.size
        )
        assertEquals(2, micCountWhenFirstAudioChunkArrived)
    }

    @Test
    fun `phase 04 seven received audio chunks totaling 23281 frames cannot result in zero AudioTrack writes without explicit error and never claim SPEAKING when 0 frames accepted`() = runBlocking {
        // Case A: AudioTrack rejects/fails write on 7 chunks (23,281 frames = 46,562 bytes) ->
        // Coordinator must NEVER claim SPEAKING and must fail explicitly with AUDIO_PLAYBACK_WRITE_FAILED
        val failingOutputDriver = DeterministicStreamingAudioTrackDriver(openTrackSucceeds = false)
        val liveDriverFail = DeterministicTestLiveTransportDriver().apply {
            onEndOfTurnHandler = { genId, emitEvent ->
                // 6 chunks of 3326 frames (6652 B) + 1 chunk of 3325 frames (6650 B) = 23,281 frames (46,562 B)
                repeat(6) {
                    emitEvent(
                        LiveSocketCallbackEvent.ServerAudioChunkReceived(
                            generationId = genId,
                            mimeType = "audio/pcm;rate=24000",
                            pcmBytes = ByteArray(6652) { 0x09 }
                        )
                    )
                }
                emitEvent(
                    LiveSocketCallbackEvent.ServerAudioChunkReceived(
                        generationId = genId,
                        mimeType = "audio/pcm;rate=24000",
                        pcmBytes = ByteArray(6650) { 0x09 }
                    )
                )
                emitEvent(LiveSocketCallbackEvent.ServerTurnCompleted(generationId = genId))
            }
        }
        val coordFail = ConversationCoordinator(
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = liveDriverFail,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            playbackAdapter = LiveAudioPlaybackAdapter(
                driver = failingOutputDriver,
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {}
            )
        )
        coordFail.onMicPermissionUpdated(MicPermissionState.GRANTED)
        coordFail.startVoiceTurn()
        val capGen1 = coordFail.audioCapture.state.value.activeGeneration!!
        coordFail.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(
                generationId = capGen1,
                pcmSamples = ShortArray(320) { 6000 },
                hardwareSampleRateHz = 16000
            )
        )
        val failOutcome = coordFail.finishSpokenTurnAndAwaitResponse()
        assertEquals(ConversationState.ERROR, failOutcome.resultingState)
        assertFalse(
            "Coordinator must NEVER claim SPEAKING when 0 frames were accepted by AudioTrack",
            coordFail.uiState.value.voiceTurnStateSequence.contains(ConversationState.SPEAKING)
        )
        assertEquals("AUDIO_PLAYBACK_WRITE_FAILED", coordFail.uiState.value.activeVoiceTurnErrorCode)

        // Case B: Working AudioTrack receives the exact 7 chunks (23,281 frames) ->
        // writes all 23,281 frames, calls finishStreamForDrain, and drains 23,281/23,281 frames to READY!
        val workingOutputDriver = DeterministicStreamingAudioTrackDriver(autoAdvancePlaybackHead = true)
        val coordOk = ConversationCoordinator(
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = liveDriverFail,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            playbackAdapter = LiveAudioPlaybackAdapter(
                driver = workingOutputDriver,
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {}
            )
        )
        coordOk.onMicPermissionUpdated(MicPermissionState.GRANTED)
        coordOk.startVoiceTurn()
        val capGen2 = coordOk.audioCapture.state.value.activeGeneration!!
        coordOk.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(
                generationId = capGen2,
                pcmSamples = ShortArray(320) { 6000 },
                hardwareSampleRateHz = 16000
            )
        )
        val okOutcome = coordOk.finishSpokenTurnAndAwaitResponse()
        assertTrue(okOutcome is VerifiedOutcome.VoiceTurnOutcome)
        val voiceOk = okOutcome as VerifiedOutcome.VoiceTurnOutcome
        assertEquals(ConversationState.READY, voiceOk.resultingState)
        assertEquals(7, coordOk.playbackAdapter.state.value.chunksReceived)
        assertEquals(23281L, coordOk.playbackAdapter.state.value.framesReceivedFromServer)
        assertEquals(23281L, coordOk.playbackAdapter.state.value.totalFramesWrittenToHardware)
        assertEquals(23281L, coordOk.playbackAdapter.state.value.hardwarePlaybackHeadFrames)
        assertEquals(23281L, voiceOk.playbackFramesDrained)
        assertEquals(1, workingOutputDriver.finishStreamForDrainCallCount)
    }

    @Test
    fun `phase 04 stale TurnGenerationId callbacks cannot interrupt a new turn and unexpected SERVER_TURN_INTERRUPTED drains received audio and performs single clean session recovery`() = runBlocking {
        val outputDriver = DeterministicStreamingAudioTrackDriver(autoAdvancePlaybackHead = true)
        var turnNumber = 0
        val liveDriver = DeterministicTestLiveTransportDriver().apply {
            onEndOfTurnHandler = { genId, emitEvent ->
                turnNumber += 1
                if (turnNumber == 1) {
                    // Turn 1: Server sends 7 audio chunks (23,281 frames) then signals interrupted=true
                    repeat(6) {
                        emitEvent(
                            LiveSocketCallbackEvent.ServerAudioChunkReceived(
                                generationId = genId,
                                mimeType = "audio/pcm;rate=24000",
                                pcmBytes = ByteArray(6652) { 0x07 }
                            )
                        )
                    }
                    emitEvent(
                        LiveSocketCallbackEvent.ServerAudioChunkReceived(
                            generationId = genId,
                            mimeType = "audio/pcm;rate=24000",
                            pcmBytes = ByteArray(6650) { 0x07 }
                        )
                    )
                    emitEvent(
                        LiveSocketCallbackEvent.ServerTurnCompleted(
                            generationId = genId,
                            interrupted = true
                        )
                    )
                } else {
                    // Turn 2: Clean completion on recovered session
                    emitEvent(
                        LiveSocketCallbackEvent.ServerTranscriptionReceived(
                            generationId = genId,
                            inputTranscript = "Second turn question",
                            outputTranscript = "Second turn complete answer"
                        )
                    )
                    emitEvent(
                        LiveSocketCallbackEvent.ServerAudioChunkReceived(
                            generationId = genId,
                            mimeType = "audio/pcm;rate=24000",
                            pcmBytes = ByteArray(1920) { 0x12 }
                        )
                    )
                    emitEvent(
                        LiveSocketCallbackEvent.ServerTurnCompleted(
                            generationId = genId,
                            interrupted = false
                        )
                    )
                }
            }
        }
        val coordinator = ConversationCoordinator(
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = liveDriver,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            playbackAdapter = LiveAudioPlaybackAdapter(
                driver = outputDriver,
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {}
            )
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        // Start Turn 1
        coordinator.startVoiceTurn()
        val turn1Gen = coordinator.uiState.value.activeTurnGenerationId!!
        val capGen1 = coordinator.audioCapture.state.value.activeGeneration!!
        coordinator.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(
                generationId = capGen1,
                pcmSamples = ShortArray(320) { 6500 },
                hardwareSampleRateHz = 16000
            )
        )
        val turn1Outcome = coordinator.finishSpokenTurnAndAwaitResponse()
        assertEquals(ConversationState.ERROR, turn1Outcome.resultingState)
        assertEquals("SERVER_TURN_INTERRUPTED", coordinator.uiState.value.activeVoiceTurnErrorCode)
        // Verify already-received 23,281 frames were written & drained before reporting interruption
        assertEquals(23281L, coordinator.playbackAdapter.state.value.framesReceivedFromServer)
        assertEquals(23281L, coordinator.playbackAdapter.state.value.totalFramesWrittenToHardware)
        // Verify redacted chronological event trace and single clean session recovery (1/1)
        val errorDetail = coordinator.uiState.value.activeVoiceTurnErrorDetail ?: ""
        assertTrue(errorDetail.contains("SESSION_CREATED -> SETUP_ACCEPTED -> USER_ACTIVITY_START -> MIC_FRAMES_STREAMED -> USER_ACTIVITY_END"))
        assertTrue(errorDetail.contains("AUDIO_CHUNKS_RECEIVED -> AUDIO_TRACK_WRITES"))
        assertTrue(errorDetail.contains("SERVER_TURN_INTERRUPTED"))
        assertEquals(1, coordinator.uiState.value.interruptionRecoveryCount)
        assertEquals("Session must be recreated exactly once after unexpected interruption", 2, liveDriver.invocationCount)

        // Start Turn 2 -> reuses the cleanly recovered session and ignores any late callback from Turn 1
        coordinator.startVoiceTurn()
        val turn2Gen = coordinator.uiState.value.activeTurnGenerationId!!
        assertNotEquals(turn1Gen, turn2Gen)

        // Inject a stale Turn 1 interruption callback during Turn 2 -> must be dropped!
        val staleAccepted = coordinator.onTurnScopedServerEvent(
            turnGenerationId = turn1Gen,
            event = LiveSocketCallbackEvent.ServerTurnCompleted(
                generationId = coordinator.liveAdapter.state.value.activeGeneration!!,
                interrupted = true
            )
        )
        assertFalse("Stale TurnGenerationId callback must be dropped and cannot interrupt Turn 2", staleAccepted)
        assertEquals(1, coordinator.uiState.value.staleTurnCallbacksDropped)
        assertEquals(ConversationState.LISTENING, coordinator.uiState.value.state)

        // Complete Turn 2 normally
        val capGen2 = coordinator.audioCapture.state.value.activeGeneration!!
        coordinator.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(
                generationId = capGen2,
                pcmSamples = ShortArray(320) { 6500 },
                hardwareSampleRateHz = 16000
            )
        )
        val turn2Outcome = coordinator.finishSpokenTurnAndAwaitResponse()
        assertEquals(ConversationState.READY, turn2Outcome.resultingState)
        assertEquals("Second turn complete answer", coordinator.uiState.value.currentTurnModelTranscript)
        assertEquals(
            listOf(
                "SESSION_REUSED",
                "SETUP_ACCEPTED",
                "USER_ACTIVITY_START",
                "MIC_FRAMES_STREAMED",
                "USER_ACTIVITY_END",
                "SERVER_RESPONSE_START",
                "AUDIO_CHUNKS_RECEIVED",
                "AUDIO_TRACK_WRITES",
                "SERVER_TURN_COMPLETE",
                "PLAYBACK_DRAINED",
                "READY"
            ),
            coordinator.uiState.value.turnEventTrace
        )
    }

    @Test
    fun `phase 04 silent microphone capture is rejected before committing empty turn to Gemini`() = runBlocking {
        val liveDriver = DeterministicTestLiveTransportDriver()
        val coordinator = ConversationCoordinator(
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = liveDriver,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            )
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        coordinator.startVoiceTurn()
        val capGen = coordinator.audioCapture.state.value.activeGeneration!!
        // Deliver silent PCM samples (all 0s -> RMS 0.000)
        coordinator.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(
                generationId = capGen,
                pcmSamples = ShortArray(320) { 0 },
                hardwareSampleRateHz = 16000
            )
        )

        val outcome = coordinator.finishSpokenTurnAndAwaitResponse()
        assertEquals(ConversationState.ERROR, outcome.resultingState)
        assertEquals("SILENT_AUDIO_CAPTURED", coordinator.uiState.value.activeVoiceTurnErrorCode)
        assertEquals("Empty/silent turn must never signal end-of-turn to Gemini", 0, liveDriver.endOfTurnSignalCount)
    }

    // --- Phase 04 First-Turn Silence Lifecycle Repair Tests ---

    @Test
    fun `phase 04 cold app launch first and only Start press completes spoken turn automatically and logs exact 9-step lifecycle trace`() = runBlocking {
        val micDriver = ControllableTestAudioDriver(hasMic = true)
        val audioCapture = AudioCapture(driver = micDriver)
        val outputDriver = DeterministicStreamingAudioTrackDriver(autoAdvancePlaybackHead = true)
        val liveDriver = DeterministicTestLiveTransportDriver().apply {
            onEndOfTurnHandler = { genId, emitEvent ->
                emitEvent(
                    LiveSocketCallbackEvent.ServerTranscriptionReceived(
                        generationId = genId,
                        inputTranscript = "Assalam o Alaikum, aj mausam kaisa hai?",
                        outputTranscript = "Walaikum Assalam! Aj mausam khushgawar hai."
                    )
                )
                emitEvent(
                    LiveSocketCallbackEvent.ServerAudioChunkReceived(
                        generationId = genId,
                        mimeType = "audio/pcm;rate=24000",
                        pcmBytes = ByteArray(1920) { 0x18 }
                    )
                )
                emitEvent(LiveSocketCallbackEvent.ServerTurnCompleted(generationId = genId))
            }
        }
        val coordinator = ConversationCoordinator(
            audioCapture = audioCapture,
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = liveDriver,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            playbackAdapter = LiveAudioPlaybackAdapter(
                driver = outputDriver,
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {}
            )
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        // Cold launch state is IDLE and Live phase is DISCONNECTED
        assertEquals(ConversationState.IDLE, coordinator.uiState.value.state)
        assertEquals(LiveConnectionPhase.DISCONNECTED, coordinator.liveAdapter.state.value.phase)

        // Single Start press on cold launch
        val startOutcome = coordinator.startVoiceTurn()
        assertEquals(ConversationState.LISTENING, startOutcome.resultingState)
        assertEquals(1, micDriver.startCaptureCalls)
        assertTrue(coordinator.liveAdapter.isSessionReadyForAudio())
        assertTrue(coordinator.liveAdapter.state.value.isReceiverCollectorAttached)

        val capGen = coordinator.audioCapture.state.value.activeGeneration!!
        // Speak normal phone-level utterance (RMS ~0.015, which is between 0.010 and 0.018) across 6 chunks
        repeat(6) {
            val accepted = coordinator.onAudioHardwareCallback(
                CaptureCallbackEvent.FrameCaptured(
                    generationId = capGen,
                    pcmSamples = ShortArray(320) { 500 }, // 500 / 32768 = ~0.0152 RMS
                    hardwareSampleRateHz = 16000
                )
            )
            assertTrue(accepted)
        }
        // Trailing silence (18 chunks) triggers automatic end-of-turn on the very first turn without needing a second Start press
        repeat(18) {
            coordinator.onAudioHardwareCallback(
                CaptureCallbackEvent.FrameCaptured(
                    generationId = capGen,
                    pcmSamples = ShortArray(320) { 0 },
                    hardwareSampleRateHz = 16000
                )
            )
        }

        // Wait briefly for the automatic end-of-turn coroutine to finish draining playback
        var waitAttempts = 0
        while (coordinator.uiState.value.state != ConversationState.READY && waitAttempts < 50) {
            kotlinx.coroutines.delay(10L)
            waitAttempts++
        }

        assertEquals("Cold launch first and only Start press must complete turn to READY", ConversationState.READY, coordinator.uiState.value.state)
        assertEquals("Walaikum Assalam! Aj mausam khushgawar hai.", coordinator.uiState.value.currentTurnModelTranscript)
        assertEquals(1, liveDriver.invocationCount)
        assertEquals(1, liveDriver.endOfTurnSignalCount)
        assertEquals(0, liveDriver.audioSentBeforeCollectorAttachedCount)
        assertEquals(0, liveDriver.audioSentBeforeSetupAcceptedCount)

        // Verify exact 9-step first-turn lifecycle trace required by specification
        assertEquals(
            listOf(
                "BUTTON_PRESS",
                "RECEIVER_COLLECTOR_ATTACHED",
                "LIVE_CONNECTION",
                "SETUP_ACCEPTED",
                "AUDIO_RECORD_START",
                "AUDIO_SENT",
                "ACTIVITY_END_OF_TURN",
                "SERVER_RESPONSE",
                "PLAYBACK"
            ),
            coordinator.uiState.value.firstTurnLifecycleTrace
        )

        // Verify required readiness order on cold launch: IDLE -> CONNECTING -> SETUP_ACCEPTED -> LISTENING -> PROCESSING -> SPEAKING -> READY
        assertEquals(
            listOf(
                "IDLE",
                "CONNECTING",
                "SETUP_ACCEPTED",
                "LISTENING",
                "PROCESSING",
                "SPEAKING",
                "READY"
            ),
            coordinator.uiState.value.voiceTurnReadinessOrder
        )
    }

    @Test
    fun `phase 04 slow connection setup waits for SETUP_ACCEPTED before AudioRecord starts or LISTENING displays and enforces bounded timeout`() = runBlocking {
        val handshakeGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val handshakeEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val micDriver = ControllableTestAudioDriver(hasMic = true)
        val slowLiveDriver = DeterministicTestLiveTransportDriver { genId, _, _, emitEvent ->
            handshakeEntered.complete(Unit)
            handshakeGate.await()
            emitEvent(
                LiveSocketCallbackEvent.SetupAccepted(
                    generationId = genId,
                    serverNote = "Delayed setup accepted"
                )
            )
            LiveHandshakeResult.Accepted(
                generationId = genId,
                serverNote = "Delayed setup accepted"
            )
        }
        val coordinator = ConversationCoordinator(
            audioCapture = AudioCapture(driver = micDriver),
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = slowLiveDriver,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            playbackAdapter = LiveAudioPlaybackAdapter(
                driver = DeterministicStreamingAudioTrackDriver(),
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {}
            )
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        val startDeferred = kotlinx.coroutines.CompletableDeferred<VerifiedOutcome>()
        Thread {
            runBlocking {
                startDeferred.complete(coordinator.startVoiceTurn())
            }
        }.start()

        // Wait until slow connection is in progress
        handshakeEntered.await()
        // While waiting for SETUP_ACCEPTED: state MUST be CONNECTING (never LISTENING) and AudioRecord MUST NOT have started
        assertEquals(ConversationState.CONNECTING, coordinator.uiState.value.state)
        assertFalse(coordinator.audioCapture.state.value.isCapturing)
        assertEquals("AudioRecord must NOT start before SETUP_ACCEPTED", 0, micDriver.startCaptureCalls)

        // Release handshake gate -> SETUP_ACCEPTED completes -> automatically transitions to LISTENING and starts AudioRecord
        handshakeGate.complete(Unit)
        val outcome = startDeferred.await()
        assertEquals(ConversationState.LISTENING, outcome.resultingState)
        assertEquals(ConversationState.LISTENING, coordinator.uiState.value.state)
        assertTrue(coordinator.audioCapture.state.value.isCapturing)
        assertEquals("AudioRecord must start immediately after SETUP_ACCEPTED", 1, micDriver.startCaptureCalls)
        assertEquals(
            listOf("IDLE", "CONNECTING", "SETUP_ACCEPTED", "LISTENING"),
            coordinator.uiState.value.voiceTurnReadinessOrder
        )
        coordinator.dispatch(CoordinatorCommand.STOP)

        // Now verify bounded connection timeout when setup hangs beyond connectionTimeoutMs
        val hangingDriver = DeterministicTestLiveTransportDriver { genId, _, _, _ ->
            kotlinx.coroutines.delay(500L)
            LiveHandshakeResult.Accepted(
                generationId = genId,
                serverNote = "Too late"
            )
        }
        val timeoutCoordinator = ConversationCoordinator(
            audioCapture = AudioCapture(driver = ControllableTestAudioDriver(hasMic = true)),
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = hangingDriver,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            connectionTimeoutMs = 60L
        )
        timeoutCoordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        val timeoutOutcome = timeoutCoordinator.startVoiceTurn()
        assertTrue(timeoutOutcome is VerifiedOutcome.PrerequisiteFailureOutcome)
        assertEquals(ConversationState.ERROR, timeoutCoordinator.uiState.value.state)
        assertEquals("CONNECTION_SETUP_TIMEOUT", timeoutCoordinator.uiState.value.activeVoiceTurnErrorCode)
        assertFalse("Turn lock must be cleanly reset after timeout", timeoutCoordinator.isVoiceTurnInProgress())
    }

    @Test
    fun `phase 04 receiver collector is attached before first audio frame and synchronous first mic buffer is never discarded`() = runBlocking {
        // Configure ControllableTestAudioDriver to emit the very first PCM frame synchronously inside startHardwareCapture()
        // before startCapture() even returns to the caller!
        val firstBufferSamples = ShortArray(320) { 7200 }
        val micDriver = ControllableTestAudioDriver(
            hasMic = true,
            startResult = HardwareStartResult.Started(16000),
            onStartCallback = { genId, onEvent ->
                onEvent(
                    CaptureCallbackEvent.FrameCaptured(
                        generationId = genId,
                        pcmSamples = firstBufferSamples,
                        hardwareSampleRateHz = 16000
                    )
                )
            }
        )
        val liveDriver = DeterministicTestLiveTransportDriver()
        val coordinator = ConversationCoordinator(
            audioCapture = AudioCapture(driver = micDriver),
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = liveDriver,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            playbackAdapter = LiveAudioPlaybackAdapter(
                driver = DeterministicStreamingAudioTrackDriver(),
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {}
            )
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        // Press Start once -> synchronous first microphone frame emitted during startHardwareCapture must be captured & sent!
        val startOutcome = coordinator.startVoiceTurn()
        assertEquals(ConversationState.LISTENING, startOutcome.resultingState)

        // Complete the turn and verify that the synchronous first frame was sent after collector attachment and never discarded
        val turnOutcome = coordinator.finishSpokenTurnAndAwaitResponse()
        assertEquals(ConversationState.READY, turnOutcome.resultingState)
        assertEquals("First synchronous microphone frame must never be discarded", 320L, coordinator.uiState.value.voiceTurnFramesSent)
        assertEquals(640L, coordinator.uiState.value.voiceTurnBytesSent)
        assertEquals(1, liveDriver.sentAudioChunks.size)
        assertEquals(0, liveDriver.audioSentBeforeCollectorAttachedCount)
        assertEquals(0, liveDriver.audioSentBeforeSetupAcceptedCount)

        val trace = coordinator.uiState.value.firstTurnLifecycleTrace
        val collectorIdx = trace.indexOf("RECEIVER_COLLECTOR_ATTACHED")
        val setupIdx = trace.indexOf("SETUP_ACCEPTED")
        val audioStartIdx = trace.indexOf("AUDIO_RECORD_START")
        val audioSentIdx = trace.indexOf("AUDIO_SENT")
        assertTrue("Collector must be attached before SETUP_ACCEPTED and AUDIO_SENT", collectorIdx in 0 until setupIdx)
        assertTrue("SETUP_ACCEPTED must occur before AUDIO_RECORD_START", setupIdx < audioStartIdx)
        assertTrue("AUDIO_RECORD_START must occur before AUDIO_SENT", audioStartIdx < audioSentIdx)
    }

    @Test
    fun `phase 04 rapid double-tap and repeated Start presses during CONNECTING LISTENING PROCESSING or SPEAKING create only one turn`() = runBlocking {
        val micDriver = ControllableTestAudioDriver(hasMic = true)
        val liveDriver = DeterministicTestLiveTransportDriver()
        val coordinator = ConversationCoordinator(
            audioCapture = AudioCapture(driver = micDriver),
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = liveDriver,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            playbackAdapter = LiveAudioPlaybackAdapter(
                driver = DeterministicStreamingAudioTrackDriver(),
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {}
            )
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        val viewModel = ConversationShellViewModel(
            coordinator = coordinator,
            workDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )

        // 1. First Start press starts Turn 1 and enters LISTENING
        viewModel.onStartClicked()
        assertEquals(ConversationState.LISTENING, coordinator.uiState.value.state)
        val turn1Id = coordinator.uiState.value.currentTurnId
        val turn1Gen = coordinator.uiState.value.activeTurnGenerationId
        assertEquals(1, micDriver.startCaptureCalls)
        assertEquals(0, coordinator.uiState.value.debouncedStartCount)

        // 2. Rapid double-tap / triple-tap on Start while LISTENING -> must be ignored/debounced without cancelling Turn 1
        viewModel.onStartClicked()
        viewModel.onStartClicked()
        val duplicateDirect = coordinator.startVoiceTurn()
        assertTrue(duplicateDirect is VerifiedOutcome.DuplicateActionRejected)
        assertEquals(ConversationState.LISTENING, coordinator.uiState.value.state)
        assertEquals("Turn ID must not change on duplicate Start press", turn1Id, coordinator.uiState.value.currentTurnId)
        assertEquals("TurnGenerationId must not change on duplicate Start press", turn1Gen, coordinator.uiState.value.activeTurnGenerationId)
        assertEquals("AudioRecord must only be started once", 1, micDriver.startCaptureCalls)
        assertEquals(3, coordinator.uiState.value.debouncedStartCount)

        // 3. Deliver speech and finish Turn 1 -> completes normally to READY
        val capGen = coordinator.audioCapture.state.value.activeGeneration!!
        coordinator.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(
                generationId = capGen,
                pcmSamples = ShortArray(320) { 6000 },
                hardwareSampleRateHz = 16000
            )
        )
        val finishOutcome = coordinator.finishSpokenTurnAndAwaitResponse()
        assertEquals(ConversationState.READY, finishOutcome.resultingState)
        assertEquals(1, liveDriver.invocationCount)
        assertEquals(1, liveDriver.endOfTurnSignalCount)
    }

    @Test
    fun `phase 04 five consecutive voice turns each require exactly one Start press and complete cleanly`() = runBlocking {
        val micDriver = ControllableTestAudioDriver(hasMic = true)
        val outputDriver = DeterministicStreamingAudioTrackDriver(autoAdvancePlaybackHead = true)
        var turnCounter = 0
        val liveDriver = DeterministicTestLiveTransportDriver().apply {
            onEndOfTurnHandler = { genId, emitEvent ->
                turnCounter += 1
                emitEvent(
                    LiveSocketCallbackEvent.ServerTranscriptionReceived(
                        generationId = genId,
                        inputTranscript = "Question $turnCounter",
                        outputTranscript = "Answer $turnCounter"
                    )
                )
                emitEvent(
                    LiveSocketCallbackEvent.ServerAudioChunkReceived(
                        generationId = genId,
                        mimeType = "audio/pcm;rate=24000",
                        pcmBytes = ByteArray(960) { turnCounter.toByte() }
                    )
                )
                emitEvent(LiveSocketCallbackEvent.ServerTurnCompleted(generationId = genId))
            }
        }
        val coordinator = ConversationCoordinator(
            audioCapture = AudioCapture(driver = micDriver),
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = liveDriver,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            playbackAdapter = LiveAudioPlaybackAdapter(
                driver = outputDriver,
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {}
            )
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        for (turnIdx in 1..5) {
            // Exactly ONE Start press per turn
            val startOutcome = coordinator.startVoiceTurn()
            assertEquals("Turn $turnIdx must enter LISTENING on a single Start press", ConversationState.LISTENING, startOutcome.resultingState)
            assertEquals(turnIdx, micDriver.startCaptureCalls)

            val capGen = coordinator.audioCapture.state.value.activeGeneration!!
            val accepted = coordinator.onAudioHardwareCallback(
                CaptureCallbackEvent.FrameCaptured(
                    generationId = capGen,
                    pcmSamples = ShortArray(320) { 6200 },
                    hardwareSampleRateHz = 16000
                )
            )
            assertTrue("Turn $turnIdx first audio buffer must be accepted", accepted)

            val finishOutcome = coordinator.finishSpokenTurnAndAwaitResponse(reason = "Turn $turnIdx finished")
            assertEquals("Turn $turnIdx must complete in READY", ConversationState.READY, finishOutcome.resultingState)
            assertEquals("Question $turnIdx", coordinator.uiState.value.currentTurnUserTranscript)
            assertEquals("Answer $turnIdx", coordinator.uiState.value.currentTurnModelTranscript)
            assertFalse("Turn $turnIdx must reset active turn lock after completion", coordinator.isVoiceTurnInProgress())
            assertFalse("Turn $turnIdx must leave zero background turn jobs", coordinator.hasActiveTurnJobs())

            if (turnIdx == 1) {
                assertEquals(
                    listOf("IDLE", "CONNECTING", "SETUP_ACCEPTED", "LISTENING", "PROCESSING", "SPEAKING", "READY"),
                    coordinator.uiState.value.voiceTurnReadinessOrder
                )
            } else {
                // Connection is already SETUP_ACCEPTED on Turns 2..5, so it proceeds directly to LISTENING
                assertEquals(
                    listOf("SETUP_ACCEPTED", "LISTENING", "PROCESSING", "SPEAKING", "READY"),
                    coordinator.uiState.value.voiceTurnReadinessOrder
                )
            }
        }

        assertEquals("All 5 turns must reuse the healthy Live session", 1, liveDriver.invocationCount)
        assertEquals("All 5 turns must send end-of-turn once", 5, liveDriver.endOfTurnSignalCount)
        assertEquals(0, liveDriver.audioSentBeforeCollectorAttachedCount)
        assertEquals(0, liveDriver.audioSentBeforeSetupAcceptedCount)
    }

    // ============================================================================================
    // PHASE 05 ACTIVE CONVERSATION COORDINATOR TESTS
    // (20 turns, pause races, overlapping completion callbacks, inactivity closure, lifecycle)
    // ============================================================================================

    @Test
    fun `phase 05 active conversation completes 20 continuous turns without stopping at 6 turns and bounds session transcript`() = runBlocking<Unit> {
        val micDriver = ControllableTestAudioDriver(hasMic = true)
        val outputDriver = DeterministicStreamingAudioTrackDriver(autoAdvancePlaybackHead = true)
        var turnCounter = 0
        val liveDriver = DeterministicTestLiveTransportDriver().apply {
            onEndOfTurnHandler = { genId, emitEvent ->
                turnCounter += 1
                emitEvent(
                    LiveSocketCallbackEvent.ServerTranscriptionReceived(
                        generationId = genId,
                        inputTranscript = "Active turn $turnCounter question",
                        outputTranscript = "Active turn $turnCounter spoken answer"
                    )
                )
                emitEvent(
                    LiveSocketCallbackEvent.ServerAudioChunkReceived(
                        generationId = genId,
                        mimeType = "audio/pcm;rate=24000",
                        pcmBytes = ByteArray(960) { (turnCounter % 127).toByte() }
                    )
                )
                emitEvent(LiveSocketCallbackEvent.ServerTurnCompleted(generationId = genId))
            }
        }
        val coordinator = ConversationCoordinator(
            audioCapture = AudioCapture(driver = micDriver),
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = liveDriver,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            playbackAdapter = LiveAudioPlaybackAdapter(
                driver = outputDriver,
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {}
            ),
            preferencesStore = com.example.coordinator.InMemoryConversationPreferencesStore(
                isActiveConversationEnabled = true
            )
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        // Verify explicit half-duplex policy (no spoken barge-in claimed while mic is disabled)
        assertFalse(coordinator.uiState.value.isSpokenBargeInClaimed)
        assertTrue(coordinator.uiState.value.duplexPolicyLabel.contains("HALF-DUPLEX"))

        // Start active conversation once before Turn 1
        val startOutcome = coordinator.startActiveConversation()
        assertEquals(ConversationState.LISTENING, startOutcome.resultingState)
        assertEquals(com.example.contract.ActiveConversationPhase.LISTENING, coordinator.uiState.value.activeConversationPhase)

        // Execute 20 consecutive turns without pressing Start again and without stopping at 6 turns!
        for (turnIdx in 1..20) {
            assertEquals(
                "Turn $turnIdx must already be in LISTENING (auto-restored on turns 2..20)",
                ConversationState.LISTENING,
                coordinator.uiState.value.state
            )
            assertEquals(
                com.example.contract.ActiveConversationPhase.LISTENING,
                coordinator.uiState.value.activeConversationPhase
            )
            assertTrue("Mic must be actively capturing at start of Turn $turnIdx", coordinator.audioCapture.state.value.isCapturing)

            val capGen = coordinator.audioCapture.state.value.activeGeneration!!
            val accepted = coordinator.onAudioHardwareCallback(
                CaptureCallbackEvent.FrameCaptured(
                    generationId = capGen,
                    pcmSamples = ShortArray(320) { 6400 },
                    hardwareSampleRateHz = 16000
                )
            )
            assertTrue("Turn $turnIdx mic frame must be accepted", accepted)

            val turnOutcome = coordinator.finishSpokenTurnAndAwaitResponse(
                reason = "Active conversation turn $turnIdx finished"
            )
            assertTrue(turnOutcome is VerifiedOutcome.VoiceTurnOutcome)
            assertEquals("Turn $turnIdx must increment completedTurnsInSession", turnIdx, coordinator.uiState.value.completedTurnsInSession)

            // Immediately after speech completion and drain, active conversation MUST auto-restore capture for Turn N+1
            assertEquals(
                "After Turn $turnIdx speech completion, coordinator must auto-restore LISTENING for Turn ${turnIdx + 1}",
                ConversationState.LISTENING,
                coordinator.uiState.value.state
            )
            assertEquals(
                com.example.contract.ActiveConversationPhase.LISTENING,
                coordinator.uiState.value.activeConversationPhase
            )
            assertTrue("Replacement capture must be active after Turn $turnIdx", coordinator.audioCapture.state.value.isCapturing)
            assertEquals("AudioRecord must be started once for initial turn + each auto-restored turn", turnIdx + 1, micDriver.startCaptureCalls)
        }

        // Verify actual resource release occurred before every replacement capture (1 initial + 20 auto-restores = 21)
        assertEquals(21, coordinator.uiState.value.resourceReleaseBeforeCaptureCount)
        assertEquals(20, coordinator.uiState.value.completedTurnsInSession)
        assertTrue(
            "Active conversation phase sequence must include PREPARING, LISTENING, THINKING, SPEAKING",
            coordinator.uiState.value.activeConversationPhaseSequence.containsAll(
                listOf(
                    com.example.contract.ActiveConversationPhase.PREPARING,
                    com.example.contract.ActiveConversationPhase.LISTENING,
                    com.example.contract.ActiveConversationPhase.THINKING,
                    com.example.contract.ActiveConversationPhase.SPEAKING
                )
            )
        )

        // Verify bounded session dialogue history (max 8 turns) and bounded transcript (max 24 entries)
        assertEquals(8, coordinator.uiState.value.sessionDialogueHistory.size)
        assertEquals(13, coordinator.uiState.value.sessionDialogueHistory.first().turnNumberInSession)
        assertEquals(20, coordinator.uiState.value.sessionDialogueHistory.last().turnNumberInSession)
        assertTrue(
            "Coordinator transcript must be bounded to maxTranscriptEntries (24)",
            coordinator.uiState.value.transcript.size <= coordinator.uiState.value.maxTranscriptEntries
        )

        coordinator.dispatch(CoordinatorCommand.STOP)
        assertEquals(ConversationState.STOPPED, coordinator.uiState.value.state)
    }

    @Test
    fun `phase 05 pause races during LISTENING and SPEAKING prevent auto-restore and Resume restores active conversation cleanly`() = runBlocking<Unit> {
        val micDriver = ControllableTestAudioDriver(hasMic = true)
        // Use manual playback head advancement so we can race PAUSE while SPEAKING is draining
        val manualDrainOutputDriver = DeterministicStreamingAudioTrackDriver(autoAdvancePlaybackHead = false)
        val liveDriver = DeterministicTestLiveTransportDriver()
        val coordinator = ConversationCoordinator(
            audioCapture = AudioCapture(driver = micDriver),
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = liveDriver,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            playbackAdapter = LiveAudioPlaybackAdapter(
                driver = manualDrainOutputDriver,
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {
                    // Advance playback head slowly during drain polling
                    manualDrainOutputDriver.manualPlaybackHeadFrames =
                        manualDrainOutputDriver.totalBytesAccepted / 2L
                }
            ),
            preferencesStore = com.example.coordinator.InMemoryConversationPreferencesStore(
                isActiveConversationEnabled = true
            )
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        // 1. Start active conversation -> LISTENING
        coordinator.startActiveConversation()
        assertEquals(ConversationState.LISTENING, coordinator.uiState.value.state)
        val turn1Gen = coordinator.uiState.value.activeTurnGenerationId!!
        val liveGen1 = coordinator.liveAdapter.state.value.activeGeneration!!

        // 2. Operator presses PAUSE during LISTENING -> immediately pauses and releases mic
        val pauseOutcome1 = coordinator.dispatch(CoordinatorCommand.PAUSE)
        assertEquals(ConversationState.PAUSED, pauseOutcome1.resultingState)
        assertEquals(com.example.contract.ActiveConversationPhase.PAUSED, coordinator.uiState.value.activeConversationPhase)
        assertTrue(coordinator.uiState.value.isDeliberatelyPaused)
        assertFalse(coordinator.audioCapture.state.value.isCapturing)

        // Late server completion from Turn 1 arriving while PAUSED must be dropped and NEVER auto-restore LISTENING!
        val staleAcceptedWhilePaused = coordinator.onSessionAndTurnScopedEvent(
            sessionId = coordinator.uiState.value.sessionId,
            turnGenerationId = turn1Gen,
            event = LiveSocketCallbackEvent.ServerTurnCompleted(
                generationId = liveGen1
            )
        )
        assertFalse("Completion callback arriving after PAUSE must be rejected", staleAcceptedWhilePaused)
        assertEquals(ConversationState.PAUSED, coordinator.uiState.value.state)
        assertFalse("Capture must NEVER auto-restore while deliberately PAUSED", coordinator.audioCapture.state.value.isCapturing)

        // 3. Operator presses RESUME -> clears pause flag, releases resources, and enters LISTENING
        val resumeOutcome = coordinator.resumeActiveConversation()
        assertEquals(ConversationState.LISTENING, resumeOutcome.resultingState)
        assertFalse(coordinator.uiState.value.isDeliberatelyPaused)
        assertTrue(coordinator.audioCapture.state.value.isCapturing)

        // 4. Race PAUSE against SPEAKING completion: emit audio chunk, pause immediately, then emit ServerTurnCompleted
        val turn2Gen = coordinator.uiState.value.activeTurnGenerationId!!
        val liveGen2 = coordinator.liveAdapter.state.value.activeGeneration!!
        coordinator.onSessionAndTurnScopedEvent(
            sessionId = coordinator.uiState.value.sessionId,
            turnGenerationId = turn2Gen,
            event = LiveSocketCallbackEvent.ServerAudioChunkReceived(
                generationId = liveGen2,
                mimeType = "audio/pcm;rate=24000",
                pcmBytes = ByteArray(1920) { 0x05 }
            )
        )
        // Pause while SPEAKING is active
        coordinator.dispatch(CoordinatorCommand.PAUSE)
        assertEquals(ConversationState.PAUSED, coordinator.uiState.value.state)

        // Late ServerTurnCompleted for turn2Gen must be dropped and cannot override PAUSED
        val lateTurn2Completion = coordinator.onSessionAndTurnScopedEvent(
            sessionId = coordinator.uiState.value.sessionId,
            turnGenerationId = turn2Gen,
            event = LiveSocketCallbackEvent.ServerTurnCompleted(generationId = liveGen2)
        )
        assertFalse(lateTurn2Completion)
        assertEquals(ConversationState.PAUSED, coordinator.uiState.value.state)
        assertFalse(coordinator.audioCapture.state.value.isCapturing)

        // 5. Verify STOP preempts RESUME
        coordinator.dispatch(CoordinatorCommand.STOP)
        assertEquals(ConversationState.STOPPED, coordinator.uiState.value.state)
        val resumeAfterStop = coordinator.resumeActiveConversation()
        assertTrue(resumeAfterStop is VerifiedOutcome.PreemptedByPrecedence)
        assertEquals(ConversationState.STOPPED, coordinator.uiState.value.state)
    }

    @Test
    fun `phase 05 overlapping completion callbacks and stale session or turn generations are deterministically dropped`() = runBlocking<Unit> {
        val micDriver = ControllableTestAudioDriver(hasMic = true)
        val outputDriver = DeterministicStreamingAudioTrackDriver(autoAdvancePlaybackHead = true)
        val liveDriver = DeterministicTestLiveTransportDriver().apply {
            onEndOfTurnHandler = { genId, emitEvent ->
                emitEvent(
                    LiveSocketCallbackEvent.ServerAudioChunkReceived(
                        generationId = genId,
                        mimeType = "audio/pcm;rate=24000",
                        pcmBytes = ByteArray(1920) { 0x11 }
                    )
                )
                // Emit THREE overlapping ServerTurnCompleted callbacks for the exact same turn generation!
                emitEvent(LiveSocketCallbackEvent.ServerTurnCompleted(generationId = genId))
                emitEvent(LiveSocketCallbackEvent.ServerTurnCompleted(generationId = genId))
                emitEvent(LiveSocketCallbackEvent.ServerTurnCompleted(generationId = genId))
            }
        }
        val coordinator = ConversationCoordinator(
            audioCapture = AudioCapture(driver = micDriver),
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = liveDriver,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            playbackAdapter = LiveAudioPlaybackAdapter(
                driver = outputDriver,
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {}
            ),
            preferencesStore = com.example.coordinator.InMemoryConversationPreferencesStore(
                isActiveConversationEnabled = true
            )
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        // Start Turn 1
        coordinator.startActiveConversation()
        val session1 = coordinator.uiState.value.sessionId
        val turn1Gen = coordinator.uiState.value.activeTurnGenerationId!!
        val capGen1 = coordinator.audioCapture.state.value.activeGeneration!!
        coordinator.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(
                generationId = capGen1,
                pcmSamples = ShortArray(320) { 7000 },
                hardwareSampleRateHz = 16000
            )
        )

        // Finish Turn 1 -> despite 3 overlapping ServerTurnCompleted events, Turn 1 completes ONCE
        // and auto-restores Turn 2 (never closing or completing Turn 2)!
        val outcome1 = coordinator.finishSpokenTurnAndAwaitResponse()
        assertTrue(outcome1 is VerifiedOutcome.VoiceTurnOutcome)
        assertEquals("Turn 1 must complete exactly once despite overlapping callbacks", 1, coordinator.uiState.value.completedTurnsInSession)
        assertEquals(
            "Turn 2 must now be active in LISTENING (err=${coordinator.uiState.value.activeVoiceTurnErrorCode}: ${coordinator.uiState.value.activeVoiceTurnErrorDetail})",
            ConversationState.LISTENING,
            coordinator.uiState.value.state
        )
        val turn2Gen = coordinator.uiState.value.activeTurnGenerationId!!
        assertNotEquals(turn1Gen, turn2Gen)
        assertTrue("Duplicate completion callbacks must be counted as dropped", coordinator.uiState.value.staleTurnCallbacksDropped >= 2)

        // Inject a late event carrying Turn 1's TurnGenerationId while Turn 2 is active -> must be dropped
        val droppedOldTurn = coordinator.onSessionAndTurnScopedEvent(
            sessionId = session1,
            turnGenerationId = turn1Gen,
            event = LiveSocketCallbackEvent.ServerTurnCompleted(
                generationId = coordinator.liveAdapter.state.value.activeGeneration!!
            )
        )
        assertFalse(droppedOldTurn)

        // Inject an event carrying a stale SessionId with Turn 2's turnSeq -> must be dropped
        val staleSessionId = com.example.contract.SessionId("session-0000-stale")
        val droppedOldSession = coordinator.onSessionAndTurnScopedEvent(
            sessionId = staleSessionId,
            turnGenerationId = turn2Gen,
            event = LiveSocketCallbackEvent.ServerTurnCompleted(
                generationId = coordinator.liveAdapter.state.value.activeGeneration!!
            )
        )
        assertFalse(droppedOldSession)
        assertEquals("Turn 2 must remain in LISTENING", ConversationState.LISTENING, coordinator.uiState.value.state)

        coordinator.dispatch(CoordinatorCommand.STOP)
    }

    @Test
    fun `phase 05 inactivity session closure and lifecycle restoration with auto-resume preference never overriding Pause or Stop`() = runBlocking<Unit> {
        val micDriver = ControllableTestAudioDriver(hasMic = true)
        val liveDriver = DeterministicTestLiveTransportDriver()
        val prefs = com.example.coordinator.InMemoryConversationPreferencesStore(
            isActiveConversationEnabled = true,
            autoResumeOnScreenReopen = true,
            inactivityTimeoutOption = com.example.contract.InactivityTimeoutOption.MINUTE_1
        )
        val coordinator = ConversationCoordinator(
            audioCapture = AudioCapture(driver = micDriver),
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = liveDriver,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            playbackAdapter = LiveAudioPlaybackAdapter(
                driver = DeterministicStreamingAudioTrackDriver(),
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {}
            ),
            preferencesStore = prefs
        )
        val viewModel = ConversationShellViewModel(
            coordinator = coordinator,
            workDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )

        // 1. Before first-use consent & configuration, reopening the visible screen MUST NOT auto-start
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        val preConsentAutoResume = coordinator.onConversationScreenVisible()
        assertNull("Screen reopen must not auto-start before first-use consent/Start", preConsentAutoResume)
        assertEquals(ConversationState.IDLE, coordinator.uiState.value.state)

        // 2. First-use Start establishes consent & enters LISTENING
        coordinator.startActiveConversation()
        assertTrue(coordinator.uiState.value.hasCompletedFirstUseConsentAndConfig)
        assertEquals(ConversationState.LISTENING, coordinator.uiState.value.state)

        // 3. Verify visible inactivity timeout setting changes and inactivity closure
        coordinator.setInactivityTimeoutOption(com.example.contract.InactivityTimeoutOption.SECONDS_30)
        assertEquals(30_000L, coordinator.uiState.value.inactivityTimeoutMs)
        val activeTurnGen = coordinator.uiState.value.activeTurnGenerationId!!
        val closedByInactivity = coordinator.onInactivityTimeoutExpired(
            sessionId = coordinator.uiState.value.sessionId,
            turnGen = activeTurnGen,
            timeoutMs = 30_000L
        )
        assertTrue("Inactivity expiration must close idle LISTENING session", closedByInactivity)
        assertEquals(ConversationState.IDLE, coordinator.uiState.value.state)
        assertEquals(1, coordinator.uiState.value.inactivityClosedCount)
        assertFalse("Mic hardware must be released on inactivity closure", coordinator.audioCapture.state.value.isCapturing)

        // 4. Reopening the visible conversation screen after inactivity closure (neither Paused nor Stopped)
        // WITH autoResumeOnScreenReopen=true and first-use consent completed -> automatically resumes LISTENING!
        val reopenOutcome = coordinator.onConversationScreenVisible()
        assertNotNull("Screen reopen must auto-resume when preference is ON and not paused/stopped", reopenOutcome)
        assertEquals(ConversationState.LISTENING, coordinator.uiState.value.state)

        // 5. Orientation change (isChangingConfigurations = true) preserves single coordinator and active LISTENING state
        viewModel.onActivityStopped(isChangingConfigurations = true)
        assertEquals(1, coordinator.uiState.value.configurationChangePreservedCount)
        assertEquals("Orientation change must not drop active LISTENING state", ConversationState.LISTENING, coordinator.uiState.value.state)
        assertTrue("Orientation change must not release active microphone capture", coordinator.audioCapture.state.value.isCapturing)

        // 6. Deliberate PAUSE -> reopening screen MUST NEVER override deliberate Pause!
        viewModel.onPauseClicked()
        assertEquals(ConversationState.PAUSED, coordinator.uiState.value.state)
        val blockedByPause = coordinator.onConversationScreenVisible()
        assertNull("Auto-resume on screen reopen must NEVER override deliberate PAUSE", blockedByPause)
        assertEquals(ConversationState.PAUSED, coordinator.uiState.value.state)
        assertEquals(1, coordinator.uiState.value.autoResumeBlockedByPauseOrStopCount)

        // 7. Deliberate STOP -> reopening screen MUST NEVER override deliberate Stop!
        viewModel.onStopClicked()
        assertEquals(ConversationState.STOPPED, coordinator.uiState.value.state)
        val blockedByStop = coordinator.onConversationScreenVisible()
        assertNull("Auto-resume on screen reopen must NEVER override deliberate STOP", blockedByStop)
        assertEquals(ConversationState.STOPPED, coordinator.uiState.value.state)
        assertEquals(2, coordinator.uiState.value.autoResumeBlockedByPauseOrStopCount)
    }

    // ============================================================================================
    // PHASE 05 REPAIR TESTS — HARDWARE_DRAIN_INCOMPLETE & STALE PLAYBACK TURN OWNERSHIP
    // ============================================================================================

    @Test
    fun `phase 05 repair received frames are never counted as AudioTrack accepted frames and 0 accepted fails with AUDIO_TRACK_WRITE_FAILED`() = runBlocking<Unit> {
        val failingDriver = DeterministicStreamingAudioTrackDriver(autoAdvancePlaybackHead = false).apply {
            // Two consecutive negative write errors (-3 ERROR_INVALID_OPERATION) exceed single recreation
            enqueueScriptedWriteSizes(-3, -3)
        }
        val adapter = LiveAudioPlaybackAdapter(
            driver = failingDriver,
            maxZeroWriteRetriesPerChunk = 5,
            writeRetryDelayRunner = {},
            drainPollDelayRunner = {}
        )
        val sessionId = com.example.contract.SessionId("ov01")
        val turnId = com.example.contract.TurnId("ov01-turn-0004")
        val gen = adapter.startTurnPlayback(turnId, sessionId)

        val chunk = ByteArray(1920) { 0x15 } // 960 frames @ 24kHz mono 16-bit
        val writeOutcome = adapter.verifyAndWritePcmChunk(gen, "audio/pcm;rate=24000", chunk)
        assertTrue(writeOutcome is PcmChunkWriteOutcome.FailedWrite)

        val snap = adapter.state.value
        // Gemini frames received MUST remain distinct from AudioTrack accepted frames (960 received vs 0 accepted)
        assertEquals(960L, snap.framesReceivedFromServer)
        assertEquals(0L, snap.totalFramesWrittenToHardware)
        assertEquals(0L, snap.bytesAcceptedByHardware)
        assertEquals("ERROR_INVALID_OPERATION(-3)", snap.lastWriteErrorName)
        assertEquals("Single track recreation attempted at most once", 1, snap.trackRecreationCount)
        assertTrue(snap.lastError?.startsWith("AUDIO_TRACK_WRITE_FAILED") == true)

        // Fresh turn with 0 accepted frames at awaitHardwareDrainAfterServerTurnComplete MUST fail with AUDIO_TRACK_WRITE_FAILED (never HARDWARE_DRAIN_INCOMPLETE)
        val zeroAcceptedTurn = adapter.startTurnPlayback(com.example.contract.TurnId("ov01-turn-0005"), sessionId)
        val drained = adapter.awaitHardwareDrainAfterServerTurnComplete(zeroAcceptedTurn)
        assertFalse(drained)
        assertTrue(
            "Zero accepted frames must fail with AUDIO_TRACK_WRITE_FAILED, never HARDWARE_DRAIN_INCOMPLETE",
            adapter.state.value.lastError?.startsWith("AUDIO_TRACK_WRITE_FAILED") == true
        )
        assertFalse(adapter.state.value.lastError?.startsWith("HARDWARE_DRAIN_INCOMPLETE") == true)
    }

    @Test
    fun `phase 05 repair partial and zero AudioTrack writes preserve every byte without dropping and never call stop before drain`() = runBlocking<Unit> {
        val driver = DeterministicStreamingAudioTrackDriver(autoAdvancePlaybackHead = true).apply {
            // Chunk of 2000 bytes (1000 frames): 0, 500, 0, 700, 800 -> total 2000 bytes accepted across retries
            enqueueScriptedWriteSizes(0, 500, 0, 700, 800)
        }
        val adapter = LiveAudioPlaybackAdapter(
            driver = driver,
            maxZeroWriteRetriesPerChunk = 10,
            writeRetryDelayRunner = {},
            drainPollDelayRunner = {}
        )
        val sessionId = com.example.contract.SessionId("ov01")
        val gen = adapter.startTurnPlayback(com.example.contract.TurnId("ov01-turn-0001"), sessionId)
        val payload = ByteArray(2000) { idx -> ((idx * 7) and 0xFF).toByte() }

        val outcome = adapter.verifyAndWritePcmChunk(gen, "audio/pcm;rate=24000", payload)
        assertTrue(outcome is PcmChunkWriteOutcome.WrittenToHardware)
        assertArrayEquals(payload, driver.getAcceptedPcmBytes())
        assertEquals(1000L, adapter.state.value.framesReceivedFromServer)
        assertEquals(1000L, adapter.state.value.totalFramesWrittenToHardware)
        assertEquals(2, adapter.state.value.partialWriteCount)
        assertEquals(2, adapter.state.value.zeroWriteRetryCount)

        val drained = adapter.awaitHardwareDrainAfterServerTurnComplete(gen)
        assertTrue("Drain must succeed against accepted frames target=1000", drained)
        assertTrue(adapter.state.value.isPlaybackDrained)
        assertEquals(1000L, adapter.state.value.hardwarePlaybackHeadFrames)
        assertEquals(
            "finishStreamForDrain must NEVER call AudioTrack.stop() before accepted audio finishes draining",
            0,
            driver.stopCalledBeforeDrainCount
        )
    }

    @Test
    fun `phase 05 repair negative write ERROR_DEAD_OBJECT triggers single clean track recreation and succeeds when replacement works`() = runBlocking<Unit> {
        val driver = DeterministicStreamingAudioTrackDriver(autoAdvancePlaybackHead = true).apply {
            // First write returns -6 (ERROR_DEAD_OBJECT), replacement track succeeds full chunk
            enqueueScriptedWriteSizes(-6)
        }
        val adapter = LiveAudioPlaybackAdapter(
            driver = driver,
            writeRetryDelayRunner = {},
            drainPollDelayRunner = {}
        )
        val gen = adapter.startTurnPlayback(
            turnId = com.example.contract.TurnId("ov01-turn-0002"),
            sessionId = com.example.contract.SessionId("ov01")
        )
        val payload = ByteArray(1920) { 0x22 }
        val outcome = adapter.verifyAndWritePcmChunk(gen, "audio/pcm;rate=24000", payload)
        assertTrue("Single track recreation after ERROR_DEAD_OBJECT(-6) should recover cleanly", outcome is PcmChunkWriteOutcome.WrittenToHardware)
        assertEquals(1, driver.trackRecreationCount)
        assertEquals(2, driver.trackCreationCount)
        assertEquals(960L, adapter.state.value.totalFramesWrittenToHardware)
        assertTrue(adapter.awaitHardwareDrainAfterServerTurnComplete(gen))
        assertEquals(0, driver.reusedReleasedTrackViolationCount)
    }

    @Test
    fun `phase 05 repair 32-bit AudioTrack playbackHeadPosition wraparound advances monotonically without stalling`() {
        val driver = DeterministicStreamingAudioTrackDriver(autoAdvancePlaybackHead = false)
        val gen = PlaybackGenerationId(
            generation = 1L,
            turnId = com.example.contract.TurnId("ov01-turn-0003"),
            sessionId = com.example.contract.SessionId("ov01")
        )
        assertTrue(driver.openStreamingTrack(gen, 24000, 1))
        driver.setRawPlaybackHead32ForWraparoundTest(0xFFFF_FF80L)
        val beforeWrap = driver.getPlaybackHeadFrames(gen)
        assertEquals(0xFFFF_FF80L, beforeWrap)

        // Wrap around past 2^32
        driver.setRawPlaybackHead32ForWraparoundTest(0x0000_0120L)
        val afterWrap = driver.getPlaybackHeadFrames(gen)
        assertEquals(0x1_0000_0120L, afterWrap)
        assertTrue("Wrapped head must increase monotonically across 32-bit boundary", afterWrap > beforeWrap)
    }

    @Test
    fun `phase 05 repair stale turn-0004 playback failure and callbacks during turn-0005 are dropped as STALE_CALLBACK_DROPPED without failing turn-0005`() = runBlocking<Unit> {
        val micDriver = ControllableTestAudioDriver(hasMic = true)
        val outputDriver = DeterministicStreamingAudioTrackDriver(autoAdvancePlaybackHead = true)
        var turnNum = 0
        val liveDriver = DeterministicTestLiveTransportDriver().apply {
            onEndOfTurnHandler = { genId, emitEvent ->
                turnNum += 1
                emitEvent(
                    LiveSocketCallbackEvent.ServerTranscriptionReceived(
                        generationId = genId,
                        inputTranscript = "Question $turnNum",
                        outputTranscript = "Answer $turnNum"
                    )
                )
                emitEvent(
                    LiveSocketCallbackEvent.ServerAudioChunkReceived(
                        generationId = genId,
                        mimeType = "audio/pcm;rate=24000",
                        pcmBytes = ByteArray(1920) { (turnNum % 120).toByte() }
                    )
                )
                emitEvent(LiveSocketCallbackEvent.ServerTurnCompleted(generationId = genId))
            }
        }
        val coordinator = ConversationCoordinator(
            audioCapture = AudioCapture(driver = micDriver),
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = liveDriver,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            playbackAdapter = LiveAudioPlaybackAdapter(
                driver = outputDriver,
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {}
            ),
            preferencesStore = com.example.coordinator.InMemoryConversationPreferencesStore(
                isActiveConversationEnabled = true
            )
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        coordinator.startActiveConversation()

        // Complete Turns 1..4 so Turn 5 is now active
        var turn4Id: com.example.contract.TurnId? = null
        lateinit var turn4TurnGen: com.example.live.TurnGenerationId
        lateinit var turn4PlayGen: PlaybackGenerationId
        for (i in 1..4) {
            if (i == 4) {
                turn4Id = coordinator.uiState.value.currentTurnId
                turn4TurnGen = coordinator.uiState.value.activeTurnGenerationId!!
                turn4PlayGen = coordinator.playbackAdapter.state.value.activeGeneration!!
            }
            val capGen = coordinator.audioCapture.state.value.activeGeneration!!
            coordinator.onAudioHardwareCallback(
                CaptureCallbackEvent.FrameCaptured(capGen, ShortArray(320) { 6500 }, 16000)
            )
            val res = coordinator.finishSpokenTurnAndAwaitResponse("Turn $i")
            assertEquals(ConversationState.READY, res.resultingState)
        }

        // Now Turn 5 is active in LISTENING!
        val turn5Id = coordinator.uiState.value.currentTurnId
        assertNotEquals(turn4Id, turn5Id)
        assertEquals(ConversationState.LISTENING, coordinator.uiState.value.state)

        val droppedBefore = coordinator.uiState.value.staleTurnCallbacksDropped
        // Inject stale ov01-turn-0004 HARDWARE_DRAIN_INCOMPLETE playback error while Turn 5 is active
        val stalePlaybackAccepted = coordinator.onTurnBoundPlaybackCallback(
            PlaybackCallbackEvent.PlaybackFailed(
                generationId = turn4PlayGen,
                reason = "HARDWARE_DRAIN_INCOMPLETE: head=0 / target=97201 frames",
                errorCode = "HARDWARE_DRAIN_INCOMPLETE"
            )
        )
        assertFalse("Stale turn-0004 PlaybackFailed must be dropped during turn-0005", stalePlaybackAccepted)

        // Inject stale ov01-turn-0004 socket completion while Turn 5 is active
        val staleSocketAccepted = coordinator.onSessionAndTurnScopedEvent(
            sessionId = coordinator.uiState.value.sessionId,
            turnGenerationId = turn4TurnGen,
            event = LiveSocketCallbackEvent.ServerTurnCompleted(
                generationId = coordinator.liveAdapter.state.value.activeGeneration!!
            )
        )
        assertFalse("Stale turn-0004 ServerTurnCompleted must be dropped during turn-0005", staleSocketAccepted)

        // Verify Turn 5 remained healthy in LISTENING with zero error shown!
        assertEquals(ConversationState.LISTENING, coordinator.uiState.value.state)
        assertEquals(turn5Id, coordinator.uiState.value.currentTurnId)
        assertNull(coordinator.uiState.value.activeVoiceTurnErrorCode)
        assertTrue(coordinator.uiState.value.staleTurnCallbacksDropped >= droppedBefore + 2)
        assertTrue(coordinator.audioCapture.state.value.isCapturing)

        // Verify Turn 5 completes normally
        val capGen5 = coordinator.audioCapture.state.value.activeGeneration!!
        coordinator.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(capGen5, ShortArray(320) { 6500 }, 16000)
        )
        val turn5Res = coordinator.finishSpokenTurnAndAwaitResponse("Turn 5")
        assertEquals(ConversationState.READY, turn5Res.resultingState)
        assertEquals(5, coordinator.uiState.value.completedTurnsInSession)
        assertEquals(ConversationState.LISTENING, coordinator.uiState.value.state)
        assertEquals(0, outputDriver.reusedReleasedTrackViolationCount)
        assertEquals(0, outputDriver.stopCalledBeforeDrainCount)

        coordinator.dispatch(CoordinatorCommand.STOP)
    }

    @Test
    fun `phase 05 repair next LISTENING capture begins only after hardware drain completes and released AudioTrack is never reused`() = runBlocking<Unit> {
        val micDriver = ControllableTestAudioDriver(hasMic = true)
        val manualDriver = DeterministicStreamingAudioTrackDriver(autoAdvancePlaybackHead = false)
        val liveDriver = DeterministicTestLiveTransportDriver()
        val drainPollEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseDrainGate = kotlinx.coroutines.CompletableDeferred<Unit>()

        val coordinator = ConversationCoordinator(
            audioCapture = AudioCapture(driver = micDriver),
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = liveDriver,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            playbackAdapter = LiveAudioPlaybackAdapter(
                driver = manualDriver,
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {
                    if (!drainPollEntered.isCompleted) {
                        drainPollEntered.complete(Unit)
                    }
                    releaseDrainGate.await()
                    manualDriver.manualPlaybackHeadFrames = manualDriver.totalBytesAccepted / 2L
                }
            ),
            preferencesStore = com.example.coordinator.InMemoryConversationPreferencesStore(
                isActiveConversationEnabled = true
            )
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        coordinator.startActiveConversation()

        val capGen1 = coordinator.audioCapture.state.value.activeGeneration!!
        coordinator.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(capGen1, ShortArray(320) { 6800 }, 16000)
        )

        val finishJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            coordinator.finishSpokenTurnAndAwaitResponse("Wait for hardware drain")
        }
        // Wait until adapter is actively polling hardware drain while SPEAKING
        drainPollEntered.await()
        assertEquals(ConversationState.SPEAKING, coordinator.uiState.value.state)
        assertFalse(
            "Next LISTENING capture MUST NOT start while SPEAKING hardware drain is incomplete",
            coordinator.audioCapture.state.value.isCapturing
        )
        assertEquals("Only initial capture should have started so far", 1, micDriver.startCaptureCalls)
        val turn1TrackIds = manualDriver.createdTrackInstanceIds.toList()
        assertEquals(1, turn1TrackIds.size)

        // Allow hardware playback head to reach target and complete drain
        releaseDrainGate.complete(Unit)
        finishJob.join()

        assertEquals(ConversationState.LISTENING, coordinator.uiState.value.state)
        assertTrue("Capture must restore cleanly ONLY AFTER hardware drain completes", coordinator.audioCapture.state.value.isCapturing)
        assertEquals(2, micDriver.startCaptureCalls)

        // Turn 1 track instance must be in releasedTrackInstanceIds and never reused on Turn 2
        assertTrue(manualDriver.releasedTrackInstanceIds.contains(turn1TrackIds.first()))
        val capGen2 = coordinator.audioCapture.state.value.activeGeneration!!
        coordinator.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(capGen2, ShortArray(320) { 6800 }, 16000)
        )
        val finish2 = coordinator.finishSpokenTurnAndAwaitResponse("Turn 2 creates fresh AudioTrack")
        assertEquals(ConversationState.READY, finish2.resultingState)
        assertEquals(2, manualDriver.createdTrackInstanceIds.size)
        assertNotEquals(manualDriver.createdTrackInstanceIds[0], manualDriver.createdTrackInstanceIds[1])
        assertEquals(0, manualDriver.reusedReleasedTrackViolationCount)
        assertEquals(0, manualDriver.stopCalledBeforeDrainCount)

        coordinator.dispatch(CoordinatorCommand.STOP)
    }

    @Test
    fun `phase 05 repair one Start supports 20 continuous turns over Dispatchers_IO explicitly checking turns 7, 10, 20 and off-main AudioTrack execution`() = runBlocking<Unit> {
        val micDriver = ControllableTestAudioDriver(hasMic = true)
        val callerThreadId = Thread.currentThread().id
        val trackThreads = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
        val baseOutputDriver = DeterministicStreamingAudioTrackDriver(autoAdvancePlaybackHead = true)
        val threadSpyDriver = object : StreamingAudioTrackDriver by baseOutputDriver {
            override fun openStreamingTrack(generationId: PlaybackGenerationId, sampleRateHz: Int, channelCount: Int): Boolean {
                trackThreads.add(Thread.currentThread().id)
                return baseOutputDriver.openStreamingTrack(generationId, sampleRateHz, channelCount)
            }
            override fun writePcmBytes(generationId: PlaybackGenerationId, pcmBytes: ByteArray, offset: Int, length: Int): Int {
                trackThreads.add(Thread.currentThread().id)
                return baseOutputDriver.writePcmBytes(generationId, pcmBytes, offset, length)
            }
            override fun finishStreamForDrain(generationId: PlaybackGenerationId) {
                trackThreads.add(Thread.currentThread().id)
                baseOutputDriver.finishStreamForDrain(generationId)
            }
            override fun getPlaybackHeadFrames(generationId: PlaybackGenerationId): Long {
                trackThreads.add(Thread.currentThread().id)
                return baseOutputDriver.getPlaybackHeadFrames(generationId)
            }
        }
        val liveDriver = DeterministicTestLiveTransportDriver()
        val coordinator = ConversationCoordinator(
            audioCapture = AudioCapture(driver = micDriver),
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = liveDriver,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                backgroundDispatcher = kotlinx.coroutines.Dispatchers.IO,
                delayRunner = {}
            ),
            // Uses production default audioDispatcher = Dispatchers.IO!
            playbackAdapter = LiveAudioPlaybackAdapter(
                driver = threadSpyDriver,
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {}
            ),
            preferencesStore = com.example.coordinator.InMemoryConversationPreferencesStore(
                isActiveConversationEnabled = true
            ),
            backgroundDispatcher = kotlinx.coroutines.Dispatchers.IO
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        // Single Start press for entire 20-turn continuous conversation
        val startOutcome = coordinator.startActiveConversation()
        assertEquals(ConversationState.LISTENING, startOutcome.resultingState)

        for (turnIdx in 1..20) {
            assertEquals(ConversationState.LISTENING, coordinator.uiState.value.state)
            assertTrue(coordinator.audioCapture.state.value.isCapturing)

            val capGen = coordinator.audioCapture.state.value.activeGeneration!!
            coordinator.onAudioHardwareCallback(
                CaptureCallbackEvent.FrameCaptured(capGen, ShortArray(320) { 6400 }, 16000)
            )
            val turnOutcome = coordinator.finishSpokenTurnAndAwaitResponse("Turn $turnIdx")
            assertEquals(ConversationState.READY, turnOutcome.resultingState)
            assertEquals(turnIdx, coordinator.uiState.value.completedTurnsInSession)

            if (turnIdx == 7 || turnIdx == 10 || turnIdx == 20) {
                assertNull("Turn $turnIdx must have zero active errors", coordinator.uiState.value.activeVoiceTurnErrorCode)
                assertTrue("Turn $turnIdx must restore LISTENING for next turn", coordinator.uiState.value.state == ConversationState.LISTENING)
                assertTrue("Turn $turnIdx replacement capture must be active", coordinator.audioCapture.state.value.isCapturing)
            }
        }

        // Verify all 20 AudioTrack instances were distinct and never executed on caller main-like thread
        assertEquals(20, baseOutputDriver.createdTrackInstanceIds.distinct().size)
        assertEquals(0, baseOutputDriver.reusedReleasedTrackViolationCount)
        assertEquals(0, baseOutputDriver.stopCalledBeforeDrainCount)
        assertFalse(
            "AudioTrack open/write/drain calls must run off caller thread on Dispatchers.IO",
            trackThreads.contains(callerThreadId)
        )

        // Pause prevents auto-restore and Resume creates exactly ONE replacement capture
        val startsBeforePause = micDriver.startCaptureCalls
        coordinator.dispatch(CoordinatorCommand.PAUSE)
        assertEquals(ConversationState.PAUSED, coordinator.uiState.value.state)
        assertFalse(coordinator.audioCapture.state.value.isCapturing)
        coordinator.resumeActiveConversation()
        assertEquals(ConversationState.LISTENING, coordinator.uiState.value.state)
        assertEquals("Resume must create exactly one active capture", startsBeforePause + 1, micDriver.startCaptureCalls)

        coordinator.dispatch(CoordinatorCommand.STOP)
        assertEquals(ConversationState.STOPPED, coordinator.uiState.value.state)
    }

    @Test
    fun `phase 05 repair failed turn preserves exact failing TurnId instead of bumping currentTurnId to next turn`() = runBlocking<Unit> {
        val micDriver = ControllableTestAudioDriver(hasMic = true)
        val failingDriver = DeterministicStreamingAudioTrackDriver(autoAdvancePlaybackHead = false).apply {
            enqueueScriptedWriteSizes(-3, -3)
        }
        val liveDriver = DeterministicTestLiveTransportDriver()
        val coordinator = ConversationCoordinator(
            audioCapture = AudioCapture(driver = micDriver),
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = liveDriver,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            playbackAdapter = LiveAudioPlaybackAdapter(
                driver = failingDriver,
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {}
            )
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        coordinator.startVoiceTurn()
        val activeTurnId = coordinator.uiState.value.currentTurnId
        val capGen = coordinator.audioCapture.state.value.activeGeneration!!
        coordinator.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(capGen, ShortArray(320) { 6500 }, 16000)
        )
        val failOutcome = coordinator.finishSpokenTurnAndAwaitResponse("Trigger negative write fail")
        assertEquals(ConversationState.ERROR, failOutcome.resultingState)
        assertEquals(
            "UI currentTurnId must match the exact failing turn ID instead of jumping ahead to turn+1",
            activeTurnId,
            coordinator.uiState.value.currentTurnId
        )
        assertEquals("AUDIO_TRACK_WRITE_FAILED", coordinator.uiState.value.activeVoiceTurnErrorCode)
        assertTrue(coordinator.uiState.value.activeVoiceTurnErrorDetail?.contains("ERROR_INVALID_OPERATION(-3)") == true)
    }

    @Test
    fun `phase 05 single Start creates one Live handshake and transitions SETUP_ACCEPTED to ACTIVE across 20 reused turns`() = runBlocking<Unit> {
        val micDriver = ControllableTestAudioDriver(hasMic = true)
        val liveDriver = DeterministicTestLiveTransportDriver()
        val outputDriver = DeterministicStreamingAudioTrackDriver(autoAdvancePlaybackHead = true)
        val coordinator = ConversationCoordinator(
            audioCapture = AudioCapture(driver = micDriver),
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = liveDriver,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            playbackAdapter = LiveAudioPlaybackAdapter(
                driver = outputDriver,
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {},
                audioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
            ),
            preferencesStore = com.example.coordinator.InMemoryConversationPreferencesStore(
                isActiveConversationEnabled = true
            )
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        val startOutcome = coordinator.startActiveConversation()
        assertEquals(ConversationState.LISTENING, startOutcome.resultingState)
        assertEquals("One Start must perform exactly one Live handshake", 1, liveDriver.invocationCount)
        assertEquals(LiveConnectionPhase.SETUP_ACCEPTED, coordinator.liveAdapter.state.value.phase)
        val firstGen = coordinator.liveAdapter.state.value.activeGeneration!!

        for (turnIdx in 1..20) {
            val capGen = coordinator.audioCapture.state.value.activeGeneration!!
            coordinator.onAudioHardwareCallback(
                CaptureCallbackEvent.FrameCaptured(capGen, ShortArray(320) { 6500 }, 16000)
            )
            assertEquals(
                "Sending realtime audio must transition session to ACTIVE",
                LiveConnectionPhase.ACTIVE,
                coordinator.liveAdapter.state.value.phase
            )
            val turnOutcome = coordinator.finishSpokenTurnAndAwaitResponse("Turn $turnIdx")
            assertEquals(ConversationState.READY, turnOutcome.resultingState)
            assertEquals(1, liveDriver.invocationCount)
            assertEquals(firstGen, coordinator.liveAdapter.state.value.activeGeneration)
            assertEquals(ConversationState.LISTENING, coordinator.uiState.value.state)
        }
        assertEquals("20 turns must reuse the single verified LiveSession generation", 1, liveDriver.invocationCount)
        assertEquals(0, coordinator.liveAdapter.state.value.retryCount)
        coordinator.dispatch(CoordinatorCommand.STOP)
    }

    @Test
    fun `phase 05 concurrent rapid Start and ensureConnectedSession calls deduplicate to one single-flight handshake`() = runBlocking<Unit> {
        val handshakeEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val allowHandshakeComplete = kotlinx.coroutines.CompletableDeferred<Unit>()
        val liveDriver = DeterministicTestLiveTransportDriver().apply {
            scriptedHandler = { genId, modelId, _, onSocketEvent ->
                handshakeEntered.complete(Unit)
                allowHandshakeComplete.await()
                val note = "SETUP_ACCEPTED ($modelId @ ${genId.wireId})"
                onSocketEvent(LiveSocketCallbackEvent.SetupAccepted(genId, note))
                LiveHandshakeResult.Accepted(genId, note)
            }
        }
        val adapter = GeminiLiveConnectionAdapter(
            driver = liveDriver,
            networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
            backgroundDispatcher = kotlinx.coroutines.Dispatchers.IO,
            delayRunner = {}
        )
        val sessionId = SessionId("ov01-sess-concurrent")

        val job1 = this.async(kotlinx.coroutines.Dispatchers.IO) {
            adapter.ensureConnectedSession(sessionId)
        }
        handshakeEntered.await()

        val job2 = this.async(kotlinx.coroutines.Dispatchers.IO) {
            adapter.ensureConnectedSession(sessionId)
        }
        val job3 = this.async(kotlinx.coroutines.Dispatchers.IO) {
            adapter.ensureConnectedSession(sessionId)
        }

        // Brief yield on IO while first handshake is suspended
        kotlinx.coroutines.delay(30L)
        assertEquals("Concurrent connects must never overlap handshakes", 1, liveDriver.invocationCount)

        allowHandshakeComplete.complete(Unit)
        val r1 = job1.await()
        val r2 = job2.await()
        val r3 = job3.await()

        assertEquals(LiveConnectionPhase.SETUP_ACCEPTED, r1.phase)
        assertEquals(r1.activeGeneration, r2.activeGeneration)
        assertEquals(r1.activeGeneration, r3.activeGeneration)
        assertEquals(1, liveDriver.invocationCount)
    }

    @Test
    fun `phase 05 bounded retry chain resets retryCount after SETUP_ACCEPTED and allows manual recovery after 3 exhausted attempts without restart`() = runBlocking<Unit> {
        val failFirstThreeThenSucceed = DeterministicTestLiveTransportDriver().apply {
            scriptedHandler = { genId, modelId, attempt, onSocketEvent ->
                if (attempt <= 3) {
                    LiveHandshakeResult.Failed(
                        generationId = genId,
                        classification = LiveFailureClassification.NETWORK_ERROR,
                        rawDetail = "ClosedReceiveChannelException: Channel was closed by the server (attempt=$attempt)"
                    )
                } else {
                    val note = "SETUP_ACCEPTED ($modelId @ ${genId.wireId})"
                    onSocketEvent(LiveSocketCallbackEvent.SetupAccepted(genId, note))
                    LiveHandshakeResult.Accepted(genId, note)
                }
            }
        }
        val micDriver = ControllableTestAudioDriver(hasMic = true)
        val outputDriver = DeterministicStreamingAudioTrackDriver(autoAdvancePlaybackHead = true)
        val coordinator = ConversationCoordinator(
            audioCapture = AudioCapture(driver = micDriver),
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = failFirstThreeThenSucceed,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                backoffPolicy = BoundedBackoffPolicy(maxAttempts = 3, initialDelayMs = 1L),
                delayRunner = {}
            ),
            playbackAdapter = LiveAudioPlaybackAdapter(
                driver = outputDriver,
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {},
                audioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
            )
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        // 1. First Start exhausts 3 bounded retries -> FAILED / ERROR, zero mic frames sent
        val failOutcome = coordinator.startVoiceTurn()
        assertEquals(ConversationState.ERROR, failOutcome.resultingState)
        assertEquals(LiveConnectionPhase.FAILED, coordinator.liveAdapter.state.value.phase)
        assertEquals("Max bounded attempts must be capped at 3", 3, failFirstThreeThenSucceed.invocationCount)
        assertEquals(2, coordinator.liveAdapter.state.value.retryCount)
        assertEquals("No mic capture until SETUP_ACCEPTED", 0, micDriver.startCaptureCalls)

        // 2. Manual Start without restarting app/coordinator -> clean RECONNECTING -> SETUP_ACCEPTED, resets retryCount = 0
        val recoverOutcome = coordinator.startVoiceTurn()
        assertEquals(ConversationState.LISTENING, recoverOutcome.resultingState)
        assertEquals(LiveConnectionPhase.SETUP_ACCEPTED, coordinator.liveAdapter.state.value.phase)
        assertEquals("SETUP_ACCEPTED must reset retryCount to 0", 0, coordinator.liveAdapter.state.value.retryCount)
        assertEquals(4, failFirstThreeThenSucceed.invocationCount)
        assertEquals(1, micDriver.startCaptureCalls)

        // 3. Complete spoken turn cleanly after manual recovery
        val capGen = coordinator.audioCapture.state.value.activeGeneration!!
        coordinator.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(capGen, ShortArray(320) { 6200 }, 16000)
        )
        val turnOutcome = coordinator.finishSpokenTurnAndAwaitResponse("Manual recovery turn")
        assertEquals(ConversationState.READY, turnOutcome.resultingState)
    }

    @Test
    fun `phase 05 SETUP_ACCEPTED after 2 transient failures resets retryCount to 0 and classifies server close codes`() = runBlocking<Unit> {
        val retryThenAcceptDriver = DeterministicTestLiveTransportDriver().apply {
            scriptedHandler = { genId, modelId, attempt, onSocketEvent ->
                if (attempt < 3) {
                    LiveHandshakeResult.Failed(
                        generationId = genId,
                        classification = LiveFailureClassification.NETWORK_ERROR,
                        rawDetail = "ServiceConnectionHandshakeFailedException (attempt=$attempt)"
                    )
                } else {
                    val note = "SETUP_ACCEPTED ($modelId @ ${genId.wireId})"
                    onSocketEvent(LiveSocketCallbackEvent.SetupAccepted(genId, note))
                    LiveHandshakeResult.Accepted(genId, note)
                }
            }
        }
        val adapter = GeminiLiveConnectionAdapter(
            driver = retryThenAcceptDriver,
            networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
            backoffPolicy = BoundedBackoffPolicy(maxAttempts = 3, initialDelayMs = 1L),
            delayRunner = {}
        )

        val state = adapter.ensureConnectedSession(SessionId("ov01-sess-reset"))
        assertEquals(LiveConnectionPhase.SETUP_ACCEPTED, state.phase)
        assertEquals(3, state.attemptCount)
        assertEquals("retryCount must reset to 0 after SETUP_ACCEPTED", 0, state.retryCount)

        // Close-reason classification verification
        assertEquals(
            LiveFailureClassification.BAD_CREDENTIALS_OR_APP_CHECK,
            com.example.live.LiveExceptionClassifier.classifyCloseReason("WebSocket HTTP 403 App Check token rejected", true)
        )
        assertEquals(
            LiveFailureClassification.QUOTA_EXHAUSTED,
            com.example.live.LiveExceptionClassifier.classifyCloseReason("Server close code 429 RESOURCE_EXHAUSTED", true)
        )
        assertEquals(
            LiveFailureClassification.UNSUPPORTED_MODEL_OR_ENDPOINT,
            com.example.live.LiveExceptionClassifier.classifyCloseReason("WebSocket close code 1008 model is not supported", true)
        )
        assertEquals(
            LiveFailureClassification.NETWORK_ERROR,
            com.example.live.LiveExceptionClassifier.classifyCloseReason("ClosedReceiveChannelException: Channel was closed by the server", true)
        )
        assertEquals(
            LiveFailureClassification.CANCELLED_BY_STOP_OR_PAUSE,
            com.example.live.LiveExceptionClassifier.classifyCloseReason("cancelled by operator_stop", true)
        )
    }

    @Test
    fun `phase 05 old generation callbacks never fail newer generation and are counted as stale`() = runBlocking<Unit> {
        val driver = DeterministicTestLiveTransportDriver()
        val adapter = GeminiLiveConnectionAdapter(
            driver = driver,
            networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
            delayRunner = {}
        )
        val s1 = adapter.ensureConnectedSession(SessionId("ov01-sess-001"))
        val gen1 = s1.activeGeneration!!

        // Force a genuine new session generation (like post-interruption recreation)
        val s2 = adapter.recreateSessionAfterInterruption(SessionId("ov01-sess-001"), "test")
        val gen2 = s2.activeGeneration!!
        assertNotEquals(gen1, gen2)

        // Old generation emits HandshakeFailed & SocketClosedRemotely -> must NOT fail gen2
        assertFalse(
            adapter.onSocketCallback(
                LiveSocketCallbackEvent.HandshakeFailed(
                    generationId = gen1,
                    classification = LiveFailureClassification.BAD_CREDENTIALS_OR_APP_CHECK,
                    rawErrorMessage = "HTTP 403 stale gen1"
                )
            )
        )
        assertFalse(
            adapter.onSocketCallback(
                LiveSocketCallbackEvent.SocketClosedRemotely(
                    generationId = gen1,
                    reason = "1008 policy violation from stale gen1"
                )
            )
        )
        assertEquals(LiveConnectionPhase.SETUP_ACCEPTED, adapter.state.value.phase)
        assertEquals(gen2, adapter.state.value.activeGeneration)
        assertEquals(2, adapter.state.value.staleSocketCallbacksDropped)
    }

    @Test
    fun `phase 05 turn 7 repair 20 sequential turns via one Start trace all 11 steps, send one activityEnd per turn, and leak zero resources`() = runBlocking<Unit> {
        val micDriver = ControllableTestAudioDriver(hasMic = true)
        val liveDriver = DeterministicTestLiveTransportDriver()
        val outputDriver = DeterministicStreamingAudioTrackDriver(autoAdvancePlaybackHead = true)
        val networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true)
        val coordinator = ConversationCoordinator(
            audioCapture = AudioCapture(driver = micDriver),
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = liveDriver,
                networkMonitor = networkMonitor,
                delayRunner = {},
                backgroundDispatcher = kotlinx.coroutines.Dispatchers.Default
            ),
            playbackAdapter = LiveAudioPlaybackAdapter(
                driver = outputDriver,
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {},
                audioDispatcher = kotlinx.coroutines.Dispatchers.IO
            ),
            preferencesStore = com.example.coordinator.InMemoryConversationPreferencesStore(
                isActiveConversationEnabled = true
            ),
            backgroundDispatcher = kotlinx.coroutines.Dispatchers.Default,
            firstResponseTimeoutMs = 5_000L
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        val startOutcome = coordinator.startActiveConversation()
        assertEquals(ConversationState.LISTENING, startOutcome.resultingState)
        val seenTurnGens = mutableSetOf<String>()

        for (turnIdx in 1..20) {
            assertEquals("Turn $turnIdx must start in LISTENING", ConversationState.LISTENING, coordinator.uiState.value.state)
            val turnGenWire = coordinator.uiState.value.activeTurnGenerationId?.wireId
            assertNotNull("Turn $turnIdx must allocate a fresh TurnGenerationId", turnGenWire)
            assertTrue("Turn $turnIdx TurnGenerationId must be unique", seenTurnGens.add(turnGenWire!!))

            val capGen = coordinator.audioCapture.state.value.activeGeneration!!
            coordinator.onAudioHardwareCallback(
                CaptureCallbackEvent.FrameCaptured(capGen, ShortArray(320) { 6400 }, 16000)
            )
            val outcome = coordinator.finishSpokenTurnAndAwaitResponse("Turn $turnIdx")
            assertEquals("Turn $turnIdx must complete cleanly in READY", ConversationState.READY, outcome.resultingState)
            assertEquals(turnIdx, liveDriver.startOfTurnSignalCount)
            assertEquals(turnIdx, liveDriver.endOfTurnSignalCount)
            assertEquals(1, liveDriver.invocationCount)

            val trace = coordinator.uiState.value.lastCompletedTurnTrace
            assertTrue("Turn $turnIdx must record 1_LISTENING_STARTED", trace.any { it.startsWith("1_LISTENING_STARTED") })
            assertTrue("Turn $turnIdx must record 2_FIRST_MIC_FRAME", trace.any { it.startsWith("2_FIRST_MIC_FRAME") })
            assertTrue("Turn $turnIdx must record 3_USER_SPEECH_DETECTED", trace.any { it.startsWith("3_USER_SPEECH_DETECTED") })
            assertTrue("Turn $turnIdx must record 2_LAST_MIC_FRAME", trace.any { it.startsWith("2_LAST_MIC_FRAME") })
            assertTrue("Turn $turnIdx must record 4_AUDIO_CAPTURE_STOPPED", trace.any { it.startsWith("4_AUDIO_CAPTURE_STOPPED") })
            assertTrue("Turn $turnIdx must record 5_ACTIVITY_END_SENT", trace.any { it.startsWith("5_ACTIVITY_END_SENT") })
            assertTrue("Turn $turnIdx must record 7_SERVER_RESPONSE_START", trace.any { it.startsWith("7_SERVER_RESPONSE_START") })
            assertTrue("Turn $turnIdx must record 8_FIRST_AUDIO_CHUNK", trace.any { it.startsWith("8_FIRST_AUDIO_CHUNK") })
            assertTrue("Turn $turnIdx must record 9_SERVER_TURN_COMPLETE", trace.any { it.startsWith("9_SERVER_TURN_COMPLETE") })
            assertTrue("Turn $turnIdx must record 10_AUDIOTRACK_FRAMES_DRAINED", trace.any { it.startsWith("10_AUDIOTRACK_FRAMES_DRAINED") })
            assertTrue("Turn $turnIdx must record 11_NEXT_LISTENING_STARTED", trace.any { it.startsWith("11_NEXT_LISTENING_STARTED") })

            if (turnIdx == 1 || turnIdx == 7 || turnIdx == 10 || turnIdx == 20) {
                val snap = coordinator.resourceSnapshot()
                assertEquals(1, snap.activeLiveSessionCount)
                assertTrue(snap.isReceiverCollectorAttached)
                assertFalse("Watchdog must be cancelled cleanly after Turn $turnIdx", snap.hasActiveFirstResponseWatchdog)
                assertFalse("AudioTrack must be closed/released after Turn $turnIdx drain", snap.isPlaybackTrackOpen)
                assertTrue("Mic must be restored cleanly for Turn ${turnIdx + 1}", snap.isMicrophoneCapturing)
                assertEquals(0, snap.firstResponseTimeoutCount)
                assertEquals("NONE", coordinator.uiState.value.lastCompletedTurnEarliestMissingStep)
                assertEquals("2_FIRST_MIC_FRAME", coordinator.uiState.value.activeTurnEarliestMissingStep)
            }
        }

        coordinator.dispatch(CoordinatorCommand.STOP)
        val afterStopSnap = coordinator.resourceSnapshot()
        assertEquals(0, afterStopSnap.activeTurnJobs)
        assertFalse(afterStopSnap.hasActiveInactivityTimer)
        assertFalse(afterStopSnap.hasActiveFirstResponseWatchdog)
        assertFalse(afterStopSnap.isMicrophoneCapturing)
        assertFalse(afterStopSnap.isPlaybackTrackOpen)
        assertEquals(0, afterStopSnap.activeLiveSessionCount)
    }

    @Test
    fun `phase 05 pre-PROCESSING validation blocks NO_AUDIO_CAPTURED, SILENT_AUDIO_CAPTURED, NO_AUDIO_FRAMES_SENT, END_OF_TURN_SEND_FAILED and duplicate activityEnd`() = runBlocking<Unit> {
        // 1. Zero audio frames captured -> NO_AUDIO_CAPTURED, never enters PROCESSING, 0 activityEnd
        run {
            val driver = DeterministicTestLiveTransportDriver()
            val coordinator = ConversationCoordinator(
                audioCapture = AudioCapture(ControllableTestAudioDriver(hasMic = true)),
                liveAdapter = GeminiLiveConnectionAdapter(driver, ControllableLiveNetworkMonitor(true), delayRunner = {}),
                playbackAdapter = LiveAudioPlaybackAdapter(
                    driver = DeterministicStreamingAudioTrackDriver(),
                    writeRetryDelayRunner = {},
                    drainPollDelayRunner = {}
                )
            )
            coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
            coordinator.startVoiceTurn()
            val outcome = coordinator.finishSpokenTurnAndAwaitResponse("silence-no-frames")
            assertEquals(ConversationState.ERROR, outcome.resultingState)
            assertEquals("NO_AUDIO_CAPTURED", coordinator.uiState.value.activeVoiceTurnErrorCode)
            assertEquals(0, driver.endOfTurnSignalCount)
            assertFalse(coordinator.uiState.value.voiceTurnStateSequence.contains(ConversationState.PROCESSING))
        }

        // 2. Only silent zeros captured -> SILENT_AUDIO_CAPTURED, never enters PROCESSING, 0 activityEnd
        run {
            val driver = DeterministicTestLiveTransportDriver()
            val coordinator = ConversationCoordinator(
                audioCapture = AudioCapture(ControllableTestAudioDriver(hasMic = true)),
                liveAdapter = GeminiLiveConnectionAdapter(driver, ControllableLiveNetworkMonitor(true), delayRunner = {}),
                playbackAdapter = LiveAudioPlaybackAdapter(
                    driver = DeterministicStreamingAudioTrackDriver(),
                    writeRetryDelayRunner = {},
                    drainPollDelayRunner = {}
                )
            )
            coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
            coordinator.startVoiceTurn()
            val capGen = coordinator.audioCapture.state.value.activeGeneration!!
            coordinator.onAudioHardwareCallback(
                CaptureCallbackEvent.FrameCaptured(capGen, ShortArray(320) { 0 }, 16000)
            )
            val outcome = coordinator.finishSpokenTurnAndAwaitResponse("silent-zeros")
            assertEquals(ConversationState.ERROR, outcome.resultingState)
            assertEquals("SILENT_AUDIO_CAPTURED", coordinator.uiState.value.activeVoiceTurnErrorCode)
            assertEquals(0, driver.endOfTurnSignalCount)
            assertFalse(coordinator.uiState.value.voiceTurnStateSequence.contains(ConversationState.PROCESSING))
        }

        // 3. Real speech captured, but sendAudioChunk rejects all sends -> NO_AUDIO_FRAMES_SENT, never enters PROCESSING
        run {
            val delegate = DeterministicTestLiveTransportDriver()
            val failingSendDriver = object : com.example.live.LiveTransportDriver by delegate {
                override suspend fun sendRealtimeAudioChunk(
                    generationId: LiveSessionGenerationId,
                    pcm16kHzBytes: ByteArray,
                    mimeType: String
                ): Boolean = false
            }
            val coordinator = ConversationCoordinator(
                audioCapture = AudioCapture(ControllableTestAudioDriver(hasMic = true)),
                liveAdapter = GeminiLiveConnectionAdapter(failingSendDriver, ControllableLiveNetworkMonitor(true), delayRunner = {}),
                playbackAdapter = LiveAudioPlaybackAdapter(
                    driver = DeterministicStreamingAudioTrackDriver(),
                    writeRetryDelayRunner = {},
                    drainPollDelayRunner = {}
                )
            )
            coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
            coordinator.startVoiceTurn()
            val capGen = coordinator.audioCapture.state.value.activeGeneration!!
            coordinator.onAudioHardwareCallback(
                CaptureCallbackEvent.FrameCaptured(capGen, ShortArray(320) { 6500 }, 16000)
            )
            val outcome = coordinator.finishSpokenTurnAndAwaitResponse("send-failed")
            assertEquals(ConversationState.ERROR, outcome.resultingState)
            val errCode = coordinator.uiState.value.activeVoiceTurnErrorCode
            assertTrue(
                "Expected NETWORK_ERROR or NO_AUDIO_FRAMES_SENT, got $errCode",
                errCode == "NETWORK_ERROR" || errCode == "NO_AUDIO_FRAMES_SENT"
            )
            assertEquals(0, delegate.endOfTurnSignalCount)
            assertFalse(coordinator.uiState.value.voiceTurnStateSequence.contains(ConversationState.PROCESSING))
        }

        // 3b. Async captured speech never confirmed sent when END_SPOKEN_TURN runs -> NO_AUDIO_FRAMES_SENT, never enters PROCESSING
        run {
            val delegate = DeterministicTestLiveTransportDriver()
            val blockedSendGate = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val failingDelayedSendDriver = object : com.example.live.LiveTransportDriver by delegate {
                override suspend fun sendRealtimeAudioChunk(
                    generationId: LiveSessionGenerationId,
                    pcm16kHzBytes: ByteArray,
                    mimeType: String
                ): Boolean = blockedSendGate.await()
            }
            val coordinator = ConversationCoordinator(
                audioCapture = AudioCapture(ControllableTestAudioDriver(hasMic = true)),
                liveAdapter = GeminiLiveConnectionAdapter(
                    failingDelayedSendDriver,
                    ControllableLiveNetworkMonitor(true),
                    delayRunner = {},
                    backgroundDispatcher = kotlinx.coroutines.Dispatchers.Default
                ),
                playbackAdapter = LiveAudioPlaybackAdapter(
                    driver = DeterministicStreamingAudioTrackDriver(),
                    writeRetryDelayRunner = {},
                    drainPollDelayRunner = {}
                ),
                backgroundDispatcher = kotlinx.coroutines.Dispatchers.Default
            )
            coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
            coordinator.startVoiceTurn()
            val capGen = coordinator.audioCapture.state.value.activeGeneration!!
            coordinator.onAudioHardwareCallback(
                CaptureCallbackEvent.FrameCaptured(capGen, ShortArray(320) { 6500 }, 16000)
            )
            val deferredOutcome = async { coordinator.finishSpokenTurnAndAwaitResponse("delayed-send-failed") }
            kotlinx.coroutines.delay(25L)
            blockedSendGate.complete(false)
            val outcome = deferredOutcome.await()
            assertEquals(ConversationState.ERROR, outcome.resultingState)
            assertEquals("NO_AUDIO_FRAMES_SENT", coordinator.uiState.value.activeVoiceTurnErrorCode)
            assertEquals(0, delegate.endOfTurnSignalCount)
            assertFalse(coordinator.uiState.value.voiceTurnStateSequence.contains(ConversationState.PROCESSING))
        }

        // 4. End-of-turn signal fails -> END_OF_TURN_SEND_FAILED, never enters PROCESSING
        run {
            val delegate = DeterministicTestLiveTransportDriver()
            var endAttempts = 0
            val failingEotDriver = object : com.example.live.LiveTransportDriver by delegate {
                override suspend fun sendEndOfSpokenTurn(generationId: LiveSessionGenerationId): Boolean {
                    endAttempts += 1
                    return false
                }
            }
            val coordinator = ConversationCoordinator(
                audioCapture = AudioCapture(ControllableTestAudioDriver(hasMic = true)),
                liveAdapter = GeminiLiveConnectionAdapter(failingEotDriver, ControllableLiveNetworkMonitor(true), delayRunner = {}),
                playbackAdapter = LiveAudioPlaybackAdapter(
                    driver = DeterministicStreamingAudioTrackDriver(),
                    writeRetryDelayRunner = {},
                    drainPollDelayRunner = {}
                )
            )
            coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
            coordinator.startVoiceTurn()
            val capGen = coordinator.audioCapture.state.value.activeGeneration!!
            coordinator.onAudioHardwareCallback(
                CaptureCallbackEvent.FrameCaptured(capGen, ShortArray(320) { 6500 }, 16000)
            )
            val outcome = coordinator.finishSpokenTurnAndAwaitResponse("eot-failed")
            assertEquals(ConversationState.ERROR, outcome.resultingState)
            assertEquals("END_OF_TURN_SEND_FAILED", coordinator.uiState.value.activeVoiceTurnErrorCode)
            assertEquals(1, endAttempts)
            assertFalse(coordinator.uiState.value.voiceTurnStateSequence.contains(ConversationState.PROCESSING))
        }

        // 5. Duplicate concurrent end-of-turn calls send at most 1 activityEnd
        run {
            val driver = DeterministicTestLiveTransportDriver()
            val coordinator = ConversationCoordinator(
                audioCapture = AudioCapture(ControllableTestAudioDriver(hasMic = true)),
                liveAdapter = GeminiLiveConnectionAdapter(driver, ControllableLiveNetworkMonitor(true), delayRunner = {}),
                playbackAdapter = LiveAudioPlaybackAdapter(
                    driver = DeterministicStreamingAudioTrackDriver(autoAdvancePlaybackHead = true),
                    writeRetryDelayRunner = {},
                    drainPollDelayRunner = {}
                )
            )
            coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
            coordinator.startVoiceTurn()
            val capGen = coordinator.audioCapture.state.value.activeGeneration!!
            coordinator.onAudioHardwareCallback(
                CaptureCallbackEvent.FrameCaptured(capGen, ShortArray(320) { 6500 }, 16000)
            )
            val o1 = async { coordinator.finishSpokenTurnAndAwaitResponse("first") }
            val o2 = async { coordinator.finishSpokenTurnAndAwaitResponse("duplicate") }
            o1.await()
            o2.await()
            assertEquals("Duplicate end-of-turn calls must emit exactly one activityEnd", 1, driver.endOfTurnSignalCount)
        }
    }

    @Test
    fun `phase 05 Turn 7 stuck in PROCESSING fires FIRST_RESPONSE_TIMEOUT, records missing step, performs single recovery, and resumes LISTENING for Turn 8`() = runBlocking<Unit> {
        val micDriver = ControllableTestAudioDriver(hasMic = true)
        var eotCounter = 0
        val liveDriver = DeterministicTestLiveTransportDriver().apply {
            onEndOfTurnHandler = { genId, emitEvent ->
                eotCounter += 1
                // Simulate physical-phone Turn 7 hang: turns 1..6 and 8 succeed, turn 7 never replies in PROCESSING
                if (eotCounter != 7) {
                    emitEvent(
                        LiveSocketCallbackEvent.ServerTranscriptionReceived(
                            generationId = genId,
                            inputTranscript = "Question $eotCounter",
                            outputTranscript = "Answer $eotCounter"
                        )
                    )
                    emitEvent(
                        LiveSocketCallbackEvent.ServerAudioChunkReceived(
                            generationId = genId,
                            mimeType = "audio/pcm;rate=24000",
                            pcmBytes = ByteArray(960) { 0x15 }
                        )
                    )
                    emitEvent(LiveSocketCallbackEvent.ServerTurnCompleted(generationId = genId))
                }
            }
        }
        val coordinator = ConversationCoordinator(
            audioCapture = AudioCapture(driver = micDriver),
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = liveDriver,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {},
                backgroundDispatcher = kotlinx.coroutines.Dispatchers.Default
            ),
            playbackAdapter = LiveAudioPlaybackAdapter(
                driver = DeterministicStreamingAudioTrackDriver(autoAdvancePlaybackHead = true),
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {},
                audioDispatcher = kotlinx.coroutines.Dispatchers.IO
            ),
            preferencesStore = com.example.coordinator.InMemoryConversationPreferencesStore(
                isActiveConversationEnabled = true
            ),
            backgroundDispatcher = kotlinx.coroutines.Dispatchers.Default,
            firstResponseTimeoutMs = 120L
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        assertEquals(ConversationState.LISTENING, coordinator.startActiveConversation().resultingState)

        // Turns 1..6 succeed on initial handshake
        for (turnIdx in 1..6) {
            val capGen = coordinator.audioCapture.state.value.activeGeneration!!
            coordinator.onAudioHardwareCallback(
                CaptureCallbackEvent.FrameCaptured(capGen, ShortArray(320) { 6400 }, 16000)
            )
            assertEquals(ConversationState.READY, coordinator.finishSpokenTurnAndAwaitResponse("Turn $turnIdx").resultingState)
        }
        assertEquals(1, liveDriver.invocationCount)

        // Turn 7: speaks & sends activityEnd -> server hangs in PROCESSING -> watchdog fires -> single clean recovery -> auto LISTENING
        val capGen7 = coordinator.audioCapture.state.value.activeGeneration!!
        coordinator.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(capGen7, ShortArray(320) { 6400 }, 16000)
        )
        val turn7Outcome = coordinator.finishSpokenTurnAndAwaitResponse("Turn 7 hang")
        assertTrue(turn7Outcome is VerifiedOutcome.PrerequisiteFailureOutcome)
        assertEquals("FIRST_RESPONSE_TIMEOUT", (turn7Outcome as VerifiedOutcome.PrerequisiteFailureOutcome).failureCode)
        assertEquals("7_SERVER_RESPONSE_START", coordinator.uiState.value.activeTurnEarliestMissingStep)
        assertEquals(1, coordinator.uiState.value.firstResponseTimeoutCount)
        assertEquals(1, coordinator.uiState.value.firstResponseRecoveryCount)
        assertEquals("Single clean recovery must open at most one replacement session", 2, liveDriver.invocationCount)
        assertEquals("Recovered active conversation must auto-return to LISTENING", ConversationState.LISTENING, coordinator.uiState.value.state)
        assertTrue(coordinator.audioCapture.state.value.isCapturing)

        // Turn 8 succeeds immediately without app restart or a second manual Start
        val capGen8 = coordinator.audioCapture.state.value.activeGeneration!!
        coordinator.onAudioHardwareCallback(
            CaptureCallbackEvent.FrameCaptured(capGen8, ShortArray(320) { 6400 }, 16000)
        )
        val turn8Outcome = coordinator.finishSpokenTurnAndAwaitResponse("Turn 8 after recovery")
        assertEquals(ConversationState.READY, turn8Outcome.resultingState)
        assertEquals(7, coordinator.uiState.value.completedTurnsInSession)
        assertEquals(ConversationState.LISTENING, coordinator.uiState.value.state)

        coordinator.dispatch(CoordinatorCommand.STOP)
    }

    @Test
    fun `phase 05 consecutive FIRST_RESPONSE_TIMEOUT stops cleanly in ERROR without infinite retries, and slow playback cancels watchdog`() = runBlocking<Unit> {
        // Part A: Consecutive timeout where even the recovered session never responds -> terminal ERROR (no infinite loop)
        run {
            val silentServerDriver = DeterministicTestLiveTransportDriver().apply {
                onEndOfTurnHandler = { _, _ -> /* Never respond */ }
            }
            val coordinator = ConversationCoordinator(
                audioCapture = AudioCapture(ControllableTestAudioDriver(hasMic = true)),
                liveAdapter = GeminiLiveConnectionAdapter(
                    driver = silentServerDriver,
                    networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                    delayRunner = {},
                    backgroundDispatcher = kotlinx.coroutines.Dispatchers.Default
                ),
                playbackAdapter = LiveAudioPlaybackAdapter(
                    driver = DeterministicStreamingAudioTrackDriver(autoAdvancePlaybackHead = true),
                    writeRetryDelayRunner = {},
                    drainPollDelayRunner = {},
                    audioDispatcher = kotlinx.coroutines.Dispatchers.IO
                ),
                preferencesStore = com.example.coordinator.InMemoryConversationPreferencesStore(
                    isActiveConversationEnabled = true
                ),
                backgroundDispatcher = kotlinx.coroutines.Dispatchers.Default,
                firstResponseTimeoutMs = 80L
            )
            coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
            coordinator.startActiveConversation()

            // First timeout -> 1 recovery attempt -> resumes LISTENING
            val cap1 = coordinator.audioCapture.state.value.activeGeneration!!
            coordinator.onAudioHardwareCallback(CaptureCallbackEvent.FrameCaptured(cap1, ShortArray(320) { 6400 }, 16000))
            coordinator.finishSpokenTurnAndAwaitResponse("Timeout 1")
            assertEquals(ConversationState.LISTENING, coordinator.uiState.value.state)
            assertEquals(2, silentServerDriver.invocationCount)

            // Consecutive second timeout -> stops cleanly in ERROR without attempting another recovery loop
            val cap2 = coordinator.audioCapture.state.value.activeGeneration!!
            coordinator.onAudioHardwareCallback(CaptureCallbackEvent.FrameCaptured(cap2, ShortArray(320) { 6400 }, 16000))
            val secondOutcome = coordinator.finishSpokenTurnAndAwaitResponse("Timeout 2")
            assertEquals(ConversationState.ERROR, secondOutcome.resultingState)
            assertEquals(ConversationState.ERROR, coordinator.uiState.value.state)
            assertEquals("FIRST_RESPONSE_TIMEOUT", coordinator.uiState.value.activeVoiceTurnErrorCode)
            assertEquals(2, coordinator.uiState.value.firstResponseTimeoutCount)
            assertEquals("Must not loop infinitely after consecutive timeout", 2, silentServerDriver.invocationCount)
        }

        // Part B: Watchdog is cancelled immediately on first server response event and never interrupts long valid playback
        run {
            val slowPlaybackDriver = DeterministicStreamingAudioTrackDriver(autoAdvancePlaybackHead = true).apply {
                enqueueScriptedWriteSizes(0, 960, 960)
            }
            val liveDriver = DeterministicTestLiveTransportDriver()
            val coordinator = ConversationCoordinator(
                audioCapture = AudioCapture(ControllableTestAudioDriver(hasMic = true)),
                liveAdapter = GeminiLiveConnectionAdapter(
                    driver = liveDriver,
                    networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                    delayRunner = {},
                    backgroundDispatcher = kotlinx.coroutines.Dispatchers.Default
                ),
                playbackAdapter = LiveAudioPlaybackAdapter(
                    driver = slowPlaybackDriver,
                    writeRetryDelayRunner = { kotlinx.coroutines.delay(180L) },
                    drainPollDelayRunner = {},
                    audioDispatcher = kotlinx.coroutines.Dispatchers.IO
                ),
                backgroundDispatcher = kotlinx.coroutines.Dispatchers.Default,
                firstResponseTimeoutMs = 80L
            )
            coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
            coordinator.startVoiceTurn()
            val cap = coordinator.audioCapture.state.value.activeGeneration!!
            coordinator.onAudioHardwareCallback(CaptureCallbackEvent.FrameCaptured(cap, ShortArray(320) { 6400 }, 16000))
            val outcome = coordinator.finishSpokenTurnAndAwaitResponse("Slow valid playback")
            assertEquals("Cancelled watchdog must never interrupt valid SPEAKING playback", ConversationState.READY, outcome.resultingState)
            assertEquals(0, coordinator.uiState.value.firstResponseTimeoutCount)
        }
    }

    @Test
    fun `phase 05 stale Turn 6 socket and playback callbacks arriving during Turn 7 are dropped safely`() = runBlocking<Unit> {
        val micDriver = ControllableTestAudioDriver(hasMic = true)
        val liveDriver = DeterministicTestLiveTransportDriver()
        val outputDriver = DeterministicStreamingAudioTrackDriver(autoAdvancePlaybackHead = true)
        val coordinator = ConversationCoordinator(
            audioCapture = AudioCapture(driver = micDriver),
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = liveDriver,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            playbackAdapter = LiveAudioPlaybackAdapter(
                driver = outputDriver,
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {}
            ),
            preferencesStore = com.example.coordinator.InMemoryConversationPreferencesStore(
                isActiveConversationEnabled = true
            )
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)
        coordinator.startActiveConversation()

        // Complete 6 turns and capture Turn 6 TurnGenerationId + PlaybackGenerationId
        var turn6TurnGen: com.example.live.TurnGenerationId? = null
        var turn6PlayGen: PlaybackGenerationId? = null
        for (turnIdx in 1..6) {
            if (turnIdx == 6) {
                turn6TurnGen = coordinator.uiState.value.activeTurnGenerationId
                turn6PlayGen = coordinator.playbackAdapter.state.value.activeGeneration
            }
            val capGen = coordinator.audioCapture.state.value.activeGeneration!!
            coordinator.onAudioHardwareCallback(CaptureCallbackEvent.FrameCaptured(capGen, ShortArray(320) { 6400 }, 16000))
            coordinator.finishSpokenTurnAndAwaitResponse("Turn $turnIdx")
        }

        // Now in Turn 7 LISTENING
        val turn7Gen = coordinator.uiState.value.activeTurnGenerationId!!
        val sessionId = coordinator.uiState.value.sessionId
        val activeLiveGen = coordinator.liveAdapter.state.value.activeGeneration!!
        assertNotEquals(turn6TurnGen, turn7Gen)

        val droppedBefore = coordinator.uiState.value.staleTurnCallbacksDropped
        val staleChunkAccepted = coordinator.onSessionAndTurnScopedEvent(
            sessionId = sessionId,
            turnGenerationId = turn6TurnGen!!,
            event = LiveSocketCallbackEvent.ServerAudioChunkReceived(
                generationId = activeLiveGen,
                mimeType = "audio/pcm;rate=24000",
                pcmBytes = ByteArray(480) { 0x42 }
            )
        )
        val staleCompleteAccepted = coordinator.onSessionAndTurnScopedEvent(
            sessionId = sessionId,
            turnGenerationId = turn6TurnGen,
            event = LiveSocketCallbackEvent.ServerTurnCompleted(generationId = activeLiveGen)
        )
        val stalePlayAccepted = coordinator.onTurnBoundPlaybackCallback(
            PlaybackCallbackEvent.PlaybackFailed(
                generationId = turn6PlayGen!!,
                reason = "stale turn 6 failure",
                errorCode = "HARDWARE_DRAIN_INCOMPLETE"
            )
        )

        assertFalse(staleChunkAccepted)
        assertFalse(staleCompleteAccepted)
        assertFalse(stalePlayAccepted)
        assertTrue(coordinator.uiState.value.staleTurnCallbacksDropped >= droppedBefore + 3)
        assertEquals(ConversationState.LISTENING, coordinator.uiState.value.state)
        assertEquals(turn7Gen, coordinator.uiState.value.activeTurnGenerationId)

        // Turn 7 still completes cleanly
        val capGen7 = coordinator.audioCapture.state.value.activeGeneration!!
        coordinator.onAudioHardwareCallback(CaptureCallbackEvent.FrameCaptured(capGen7, ShortArray(320) { 6400 }, 16000))
        val outcome7 = coordinator.finishSpokenTurnAndAwaitResponse("Turn 7")
        assertEquals(ConversationState.READY, outcome7.resultingState)
        assertEquals(7, coordinator.uiState.value.completedTurnsInSession)

        coordinator.dispatch(CoordinatorCommand.STOP)
    }
}



