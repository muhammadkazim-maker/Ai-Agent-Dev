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
import com.example.contract.SessionId
import com.example.contract.VerifiedOutcome
import com.example.coordinator.ConversationCoordinator
import com.example.live.BoundedBackoffPolicy
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
import kotlinx.coroutines.runBlocking
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
        assertFalse("Phase 01–03 shell must never fake an active microphone", state.isMicrophoneActive)
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
        assertFalse("Microphone must remain inactive on shell Start in Phase 03", state.isMicrophoneActive)
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
        assertEquals(
            "Stale SETUP_ACCEPTED must immediately become NETWORK_UNAVAILABLE when internet is lost",
            LiveConnectionPhase.NETWORK_UNAVAILABLE,
            offlineState.phase
        )
        assertEquals(LiveFailureClassification.NETWORK_UNAVAILABLE, offlineState.failureClassification)
        assertFalse("Must never report handshakeVerified while offline", offlineState.handshakeVerified)
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
            driver = autoMonitor.let { autoDriver },
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
        val failingDriver = DeterministicTestLiveTransportDriver { genId, modelId, attempt, onSocketEvent ->
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
            java.net.SocketTimeoutException("failed to connect to generativelanguage.googleapis.com after 25000ms"),
            java.net.ConnectException("Failed to connect to /142.250.190.42:443"),
            java.net.SocketException("Software caused connection abort"),
            java.io.EOFException("Unexpected end of stream on WebSocket"),
            // Crucial regression case: Firebase App Check wrapper exception caused by underlying DNS/IO failure
            RuntimeException(
                "FirebaseAppCheck: Failed to exchange App Check debug token",
                java.net.UnknownHostException("Unable to resolve host \"firebaseappcheck.googleapis.com\"")
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
                "Exception ${ex.message} must be classified as NETWORK_ERROR, not ${classifiedOnline.wireCode}",
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
}
