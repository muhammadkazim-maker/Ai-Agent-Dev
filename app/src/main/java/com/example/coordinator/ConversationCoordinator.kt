package com.example.coordinator

import com.example.BuildConfig
import com.example.audio.AudioCapture
import com.example.audio.AudioCaptureState
import com.example.audio.AudioHardwareDriver
import com.example.audio.AudioOwner
import com.example.audio.CaptureCallbackEvent
import com.example.audio.CaptureGenerationId
import com.example.audio.HardwareStartResult
import com.example.audio.MicPermissionState
import com.example.audio.PcmResampler
import com.example.contract.ActionId
import com.example.contract.ContractIdGenerator
import com.example.contract.ConversationState
import com.example.contract.CoordinatorCommand
import com.example.contract.SessionId
import com.example.contract.TranscriptEntry
import com.example.contract.TurnId
import com.example.contract.VerifiedOutcome
import com.example.live.GeminiLiveConnectionAdapter
import com.example.live.LiveConnectionPhase
import com.example.live.LiveConnectionState
import com.example.live.LiveFailureClassification
import com.example.live.LiveSocketCallbackEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Default deterministic hardware driver used when no Android Context driver is injected
 * (e.g., pure JVM unit tests). Does not fake microphone signal frames unless a test explicitly
 * injects frames via [AudioCapture.onHardwareCallback].
 */
class DefaultNoContextAudioDriver : AudioHardwareDriver {
    override fun hasMicrophoneDevice(): Boolean = true

    override fun startHardwareCapture(
        generationId: CaptureGenerationId,
        onEvent: (CaptureCallbackEvent) -> Unit
    ): HardwareStartResult = HardwareStartResult.Started(PcmResampler.TARGET_SAMPLE_RATE_HZ)

    override fun stopHardwareCapture() = Unit

    override fun playEphemeralPcm16kHz(
        generationId: CaptureGenerationId,
        samples16kHz: ShortArray,
        onCompleted: (CaptureCallbackEvent.EphemeralPlaybackCompleted) -> Unit
    ): Boolean {
        onCompleted(
            CaptureCallbackEvent.EphemeralPlaybackCompleted(
                generationId = generationId,
                playedSamplesCount = samples16kHz.size
            )
        )
        return true
    }

    override fun stopEphemeralPlayback() = Unit
}

/**
 * Immutable UI state snapshot emitted by the single [ConversationCoordinator].
 */
data class ConversationUiState(
    val sessionId: SessionId,
    val currentTurnId: TurnId,
    val lastActionId: ActionId,
    val state: ConversationState,
    val isMicrophoneActive: Boolean = false,
    val microphoneStatusLabel: String = "MIC: INACTIVE (LOCAL SELF-TEST READY; GEMINI PLAYBACK NOT_CLAIMED)",
    val modelConfigStatus: String = BuildConfig.MODEL_CONFIG_STATUS,
    val sourceBuildId: String = BuildConfig.SOURCE_BUILD_ID,
    val lastOutcome: VerifiedOutcome? = null,
    val transcript: List<TranscriptEntry> = emptyList(),
    val executedActionCount: Int = 0,
    val duplicateRejectedCount: Int = 0,
    val audioCaptureState: AudioCaptureState = AudioCaptureState(),
    val liveConnectionState: LiveConnectionState = LiveConnectionState()
)

/**
 * Single ConversationCoordinator for OmniVoice Rebuild (Phase 01, Phase 02 & Phase 03).
 *
 * Enforces PROJECT_CONTRACT.md invariants:
 * 1. Single coordinator authority for session, turn, action, audio owner, and Gemini Live adapter transitions.
 * 2. Strongly-typed SessionId, TurnId, ActionId, CaptureGenerationId, and LiveSessionGenerationId.
 * 3. Exactly-once effects: duplicate ActionIds are rejected deterministically without mutating state.
 * 4. Stop/Pause precedence: STOP > STOP_MIC_SELF_TEST > DISCONNECT_LIVE_SESSION > PAUSE > CONNECT_LIVE_SESSION > START_MIC_SELF_TEST > START.
 *    Large STOP immediately closes active Gemini Live WebSockets, releases audio capture/playback hardware, and wipes ephemeral buffers.
 * 5. Honest NOT_CONFIGURED state on Start: Phase 03 verifies the Gemini Live WebSocket handshake and diagnostics only,
 *    without claiming audio playback (`audioPlaybackClaimed = false`) or registering action tools (`tools = 0`).
 * 6. Excludes private data and secrets from logs and state payloads via [com.example.live.DiagnosticRedactor].
 */
class ConversationCoordinator(
    private val idGenerator: ContractIdGenerator = ContractIdGenerator(),
    val audioCapture: AudioCapture = AudioCapture(DefaultNoContextAudioDriver()),
    val liveAdapter: GeminiLiveConnectionAdapter = GeminiLiveConnectionAdapter(),
    private val clockProvider: () -> String = { "PHASE03-T+${System.currentTimeMillis() % 100000}ms" }
) {
    private val executedActionIds = LinkedHashSet<String>()
    private val maxTrackedActions = 512

    private val initialSessionId = idGenerator.nextSessionId()
    private val initialTurnId = idGenerator.nextTurnId()
    private val initialActionId = idGenerator.nextActionId("init")

    private val _uiState = MutableStateFlow(
        ConversationUiState(
            sessionId = initialSessionId,
            currentTurnId = initialTurnId,
            lastActionId = initialActionId,
            state = ConversationState.IDLE,
            isMicrophoneActive = false,
            audioCaptureState = audioCapture.state.value,
            liveConnectionState = liveAdapter.state.value,
            transcript = listOf(
                TranscriptEntry(
                    id = "${initialActionId.value}-log",
                    sessionId = initialSessionId,
                    turnId = initialTurnId,
                    actionId = initialActionId,
                    roleLabel = "COORDINATOR",
                    stateSnapshot = ConversationState.IDLE,
                    outcomeCode = "SHELL_INITIALIZED",
                    message = "OmniVoice Rebuild Phase 03 ready. Single ConversationCoordinator, single AudioCapture owner, and single GeminiLiveConnectionAdapter (${liveAdapter.configuredModelId}) active. Audio playback is not claimed in Phase 03; zero action tools registered.",
                    timestampLabel = "INIT"
                )
            )
        )
    )
    val uiState: StateFlow<ConversationUiState> = _uiState.asStateFlow()

    init {
        recordActionId(initialActionId)
    }

    fun nextActionId(verb: String): ActionId = idGenerator.nextActionId(verb)

    /**
     * Synchronizes the latest [AudioCaptureState] and [LiveConnectionState] into [ConversationUiState]
     * so the shell, microphone setup screen, and live diagnostics screen always reflect real state.
     */
    @Synchronized
    fun syncAudioState() {
        val snap = audioCapture.state.value
        val liveSnap = liveAdapter.state.value
        val micLabel = formatMicLabel(snap)
        _uiState.update { current ->
            current.copy(
                isMicrophoneActive = snap.isCapturing,
                microphoneStatusLabel = micLabel,
                audioCaptureState = snap,
                liveConnectionState = liveSnap
            )
        }
    }

    /**
     * Synchronizes the latest [LiveConnectionState] from [liveAdapter] into [ConversationUiState].
     */
    @Synchronized
    fun syncLiveState() {
        val liveSnap = liveAdapter.state.value
        _uiState.update { current ->
            current.copy(liveConnectionState = liveSnap)
        }
    }

    /**
     * Updates the Android RECORD_AUDIO permission state in the single [AudioCapture] owner.
     */
    @Synchronized
    fun onMicPermissionUpdated(permissionState: MicPermissionState) {
        audioCapture.updatePermissionState(permissionState)
        syncAudioState()
    }

    /**
     * Forwards a generation-tagged hardware callback through [AudioCapture] and updates state.
     * Returns true if accepted by the active generation, false if dropped as stale.
     */
    @Synchronized
    fun onAudioHardwareCallback(event: CaptureCallbackEvent): Boolean {
        val accepted = audioCapture.onHardwareCallback(event)
        syncAudioState()
        return accepted
    }

    /**
     * Forwards a generation-tagged socket callback through [GeminiLiveConnectionAdapter] and updates state.
     * Returns true if accepted by the active socket generation, false if dropped as stale.
     */
    @Synchronized
    fun onLiveSocketCallback(event: LiveSocketCallbackEvent): Boolean {
        val accepted = liveAdapter.onSocketCallback(event)
        syncLiveState()
        return accepted
    }

    /**
     * Starts lifecycle-safe connectivity monitoring (`ConnectivityManager.NetworkCallback`) while
     * the diagnostics screen or a Gemini Live session is active.
     */
    @Synchronized
    fun startLiveNetworkMonitoring() {
        liveAdapter.startNetworkMonitoring()
        syncLiveState()
    }

    /**
     * Unregisters connectivity monitoring safely when no longer needed.
     */
    @Synchronized
    fun stopLiveNetworkMonitoring() {
        liveAdapter.stopNetworkMonitoring()
        syncLiveState()
    }

    /**
     * Called when the Live Diagnostics screen is disposed (navigated away).
     * Cancels any scheduled retry backoff (`CONNECTING` / `RETRY_BACKOFF_WAITING`), and unregisters
     * the network callback if no active `SETUP_ACCEPTED` session is being monitored.
     */
    @Synchronized
    fun onLiveDiagnosticsDisposed(reason: String = "Live Diagnostics screen disposed") {
        liveAdapter.cancelScheduledRetries(reason)
        if (liveAdapter.state.value.phase != LiveConnectionPhase.SETUP_ACCEPTED) {
            liveAdapter.stopNetworkMonitoring()
        }
        syncLiveState()
    }

    /**
     * Initiates an authenticated Gemini Live WebSocket handshake with bounded backoff for transient
     * failures through the single [GeminiLiveConnectionAdapter], recording a typed [VerifiedOutcome.LiveConnectionOutcome]
     * in the coordinator transcript.
     */
    suspend fun connectLiveSessionWithHandshake(
        actionId: ActionId = idGenerator.nextActionId("connect_live")
    ): VerifiedOutcome.LiveConnectionOutcome {
        val sessionSnapshot = synchronized(this) {
            val current = _uiState.value
            if (executedActionIds.contains(actionId.value)) {
                return VerifiedOutcome.LiveConnectionOutcome(
                    actionId = actionId,
                    sessionId = current.sessionId,
                    turnId = current.currentTurnId,
                    resultingState = current.state,
                    livePhase = current.liveConnectionState.phase,
                    generationId = current.liveConnectionState.activeGeneration,
                    failureClassification = current.liveConnectionState.failureClassification,
                    code = "EXACTLY_ONCE_DUPLICATE_REJECTED",
                    detail = "Duplicate Live connect ActionId ${actionId.value} rejected."
                )
            }
            recordActionId(actionId)
            liveAdapter.startNetworkMonitoring()
            current.sessionId
        }

        val finalLiveState = liveAdapter.connectWithBoundedRetry(sessionSnapshot)

        return synchronized(this) {
            val current = _uiState.value
            val nextTurn = idGenerator.nextTurnId()
            val outcomeCode = when (finalLiveState.phase) {
                LiveConnectionPhase.SETUP_ACCEPTED -> "LIVE_SETUP_ACCEPTED"
                LiveConnectionPhase.NETWORK_UNAVAILABLE -> "LIVE_NETWORK_UNAVAILABLE"
                LiveConnectionPhase.NETWORK_RESTORED -> "LIVE_NETWORK_RESTORED"
                LiveConnectionPhase.FAILED -> "LIVE_FAILED_${finalLiveState.failureClassification.wireCode}"
                LiveConnectionPhase.DISCONNECTED -> "LIVE_DISCONNECTED"
                else -> "LIVE_${finalLiveState.phase.wireCode}"
            }
            val detailMsg = when (finalLiveState.phase) {
                LiveConnectionPhase.SETUP_ACCEPTED ->
                    "Gemini Live handshake verified (${finalLiveState.diagnostics.modelId} @ ${finalLiveState.diagnostics.sessionGenerationWireId}, attempts=${finalLiveState.attemptCount}, tools=0, audioPlayback=NOT_CLAIMED)."
                LiveConnectionPhase.NETWORK_UNAVAILABLE ->
                    "Gemini Live handshake blocked [NETWORK_UNAVAILABLE]: ${finalLiveState.diagnostics.redactedSummary}"
                LiveConnectionPhase.FAILED ->
                    "Gemini Live handshake failed [${finalLiveState.failureClassification.wireCode}] after ${finalLiveState.attemptCount} attempt(s) (retries=${finalLiveState.retryCount}): ${finalLiveState.diagnostics.redactedSummary}"
                else ->
                    finalLiveState.diagnostics.redactedSummary
            }
            val outcome = VerifiedOutcome.LiveConnectionOutcome(
                actionId = actionId,
                sessionId = current.sessionId,
                turnId = nextTurn,
                resultingState = current.state,
                livePhase = finalLiveState.phase,
                generationId = finalLiveState.activeGeneration ?: finalLiveState.lastCompletedGeneration,
                failureClassification = finalLiveState.failureClassification,
                code = outcomeCode,
                detail = detailMsg
            )
            applyVerifiedOutcome(
                sessionId = current.sessionId,
                turnId = nextTurn,
                actionId = actionId,
                newState = current.state,
                roleLabel = "LIVE_ADAPTER",
                outcome = outcome
            )
            outcome
        }
    }

    /**
     * Explicit lifecycle hook when navigating away from the Microphone Setup screen or when
     * the Activity stops/backgrounds (not a configuration change).
     * Immediately releases audio hardware, wipes ephemeral self-test audio, closes any active live socket,
     * and unregisters connectivity callbacks when disconnecting Live.
     */
    @Synchronized
    fun onLifecycleReleaseHardware(reason: String, disconnectLive: Boolean = false) {
        val hadActiveHardware = audioCapture.state.value.isCapturing ||
            audioCapture.state.value.isPlayingEphemeralCheck ||
            audioCapture.state.value.ephemeralRecordedSamples > 0
        val hadActiveLive = disconnectLive && (
            liveAdapter.state.value.phase == LiveConnectionPhase.CONNECTING ||
                liveAdapter.state.value.phase == LiveConnectionPhase.SETUP_ACCEPTED ||
                liveAdapter.state.value.phase == LiveConnectionPhase.RETRY_BACKOFF_WAITING
            )

        audioCapture.stopAndRelease(reason = reason, wipeEphemeral = true)
        if (disconnectLive) {
            liveAdapter.disconnectAndRelease(reason = reason)
            liveAdapter.stopNetworkMonitoring()
        }
        syncAudioState()
        if (hadActiveHardware || hadActiveLive) {
            val turn = idGenerator.nextTurnId()
            val act = idGenerator.nextActionId("lifecycle_release")
            val current = _uiState.value
            val entry = TranscriptEntry(
                id = "${act.value}-${turn.value}",
                sessionId = current.sessionId,
                turnId = turn,
                actionId = act,
                roleLabel = "LIFECYCLE_GUARD",
                stateSnapshot = current.state,
                outcomeCode = "HARDWARE_RELEASED_ON_LIFECYCLE",
                message = "Audio hardware released & ephemeral check buffer wiped: $reason.",
                timestampLabel = clockProvider()
            )
            _uiState.update { it.copy(transcript = it.transcript + entry) }
        }
    }

    /**
     * Dispatches a single [CoordinatorCommand] with an explicit [ActionId] to guarantee
     * exactly-once execution and typed [VerifiedOutcome].
     */
    @Synchronized
    fun dispatch(
        command: CoordinatorCommand,
        actionId: ActionId = idGenerator.nextActionId(command.name)
    ): VerifiedOutcome {
        val current = _uiState.value

        // Enforce Exactly-Once Effect Invariant
        if (executedActionIds.contains(actionId.value)) {
            val duplicateOutcome = VerifiedOutcome.DuplicateActionRejected(
                actionId = actionId,
                sessionId = current.sessionId,
                turnId = current.currentTurnId,
                resultingState = current.state,
                detail = "Action ${actionId.value} already executed; duplicate ignored per exactly-once contract."
            )
            _uiState.update { state ->
                state.copy(
                    lastOutcome = duplicateOutcome,
                    duplicateRejectedCount = state.duplicateRejectedCount + 1,
                    transcript = state.transcript + TranscriptEntry(
                        id = "${actionId.value}-dup-${state.duplicateRejectedCount + 1}",
                        sessionId = state.sessionId,
                        turnId = state.currentTurnId,
                        actionId = actionId,
                        roleLabel = "CONTRACT_GUARD",
                        stateSnapshot = state.state,
                        outcomeCode = duplicateOutcome.code,
                        message = duplicateOutcome.detail,
                        timestampLabel = clockProvider()
                    )
                )
            }
            return duplicateOutcome
        }

        recordActionId(actionId)
        val nextTurn = idGenerator.nextTurnId()

        return when (command) {
            CoordinatorCommand.STOP -> {
                // Large Stop MUST promptly release hardware, wipe ephemeral self-test data, and close live sockets
                audioCapture.stopAndRelease(
                    reason = "Immediate operator STOP command",
                    wipeEphemeral = true
                )
                liveAdapter.disconnectAndRelease(reason = "Immediate operator STOP command")
                val outcome = VerifiedOutcome.StateTransitionVerified(
                    actionId = actionId,
                    sessionId = current.sessionId,
                    turnId = nextTurn,
                    previousState = current.state,
                    resultingState = ConversationState.STOPPED,
                    detail = "STOP command executed with highest precedence (priority ${CoordinatorCommand.STOP.priority}). Session halted, live socket closed, audio hardware released, and ephemeral buffer wiped."
                )
                applyVerifiedOutcome(
                    sessionId = current.sessionId,
                    turnId = nextTurn,
                    actionId = actionId,
                    newState = ConversationState.STOPPED,
                    roleLabel = "OPERATOR_STOP",
                    outcome = outcome
                )
                outcome
            }

            CoordinatorCommand.PAUSE -> {
                if (current.state == ConversationState.STOPPED) {
                    val preempted = VerifiedOutcome.PreemptedByPrecedence(
                        actionId = actionId,
                        sessionId = current.sessionId,
                        turnId = nextTurn,
                        requestedCommand = CoordinatorCommand.PAUSE,
                        enforcingCommand = CoordinatorCommand.STOP,
                        resultingState = ConversationState.STOPPED,
                        detail = "PAUSE ignored because session is STOPPED (Stop precedence > Pause)."
                    )
                    applyVerifiedOutcome(
                        sessionId = current.sessionId,
                        turnId = nextTurn,
                        actionId = actionId,
                        newState = ConversationState.STOPPED,
                        roleLabel = "PRECEDENCE_GUARD",
                        outcome = preempted
                    )
                    preempted
                } else {
                    audioCapture.stopAndRelease(
                        reason = "Operator PAUSE command",
                        wipeEphemeral = false
                    )
                    liveAdapter.disconnectAndRelease(reason = "Operator PAUSE command")
                    val outcome = VerifiedOutcome.StateTransitionVerified(
                        actionId = actionId,
                        sessionId = current.sessionId,
                        turnId = nextTurn,
                        previousState = current.state,
                        resultingState = ConversationState.PAUSED,
                        detail = "PAUSE command executed (priority ${CoordinatorCommand.PAUSE.priority}). Active audio capture and live socket released; shell paused."
                    )
                    applyVerifiedOutcome(
                        sessionId = current.sessionId,
                        turnId = nextTurn,
                        actionId = actionId,
                        newState = ConversationState.PAUSED,
                        roleLabel = "OPERATOR_PAUSE",
                        outcome = outcome
                    )
                    outcome
                }
            }

            CoordinatorCommand.START -> {
                // Keep conversation shell honest: Phase 03 verifies the Gemini Live WebSocket handshake only;
                // audio playback and full voice turns are not claimed in Phase 03.
                val outcome = VerifiedOutcome.HonestNotConfiguredOutcome(
                    actionId = actionId,
                    sessionId = current.sessionId,
                    turnId = nextTurn,
                    resultingState = ConversationState.NOT_CONFIGURED,
                    detail = "Start requested from ${current.state.wireLabel}: Transitioned to honest NOT_CONFIGURED state. Full voice-turn audio playback is not claimed in Phase 03. Use Live Connection & Diagnostics to verify the Gemini Live WebSocket handshake (${liveAdapter.configuredModelId}), or Microphone Setup for local PCM check."
                )
                applyVerifiedOutcome(
                    sessionId = current.sessionId,
                    turnId = nextTurn,
                    actionId = actionId,
                    newState = ConversationState.NOT_CONFIGURED,
                    roleLabel = "OPERATOR_START",
                    outcome = outcome
                )
                outcome
            }

            CoordinatorCommand.DISCONNECT_LIVE_SESSION -> {
                val prevGen = liveAdapter.state.value.activeGeneration ?: liveAdapter.state.value.lastCompletedGeneration
                liveAdapter.disconnectAndRelease(reason = "Operator disconnected Gemini Live session")
                val liveSnap = liveAdapter.state.value
                val outcome = VerifiedOutcome.LiveConnectionOutcome(
                    actionId = actionId,
                    sessionId = current.sessionId,
                    turnId = nextTurn,
                    resultingState = current.state,
                    livePhase = liveSnap.phase,
                    generationId = prevGen,
                    failureClassification = LiveFailureClassification.NONE,
                    code = "LIVE_SESSION_DISCONNECTED",
                    detail = "Gemini Live session disconnected and socket released (${prevGen?.wireId ?: "NONE"})."
                )
                applyVerifiedOutcome(
                    sessionId = current.sessionId,
                    turnId = nextTurn,
                    actionId = actionId,
                    newState = current.state,
                    roleLabel = "LIVE_ADAPTER",
                    outcome = outcome
                )
                outcome
            }

            CoordinatorCommand.CONNECT_LIVE_SESSION -> {
                val liveSnap = liveAdapter.state.value
                val outcome = VerifiedOutcome.LiveConnectionOutcome(
                    actionId = actionId,
                    sessionId = current.sessionId,
                    turnId = nextTurn,
                    resultingState = current.state,
                    livePhase = liveSnap.phase,
                    generationId = liveSnap.activeGeneration,
                    failureClassification = liveSnap.failureClassification,
                    code = "LIVE_CONNECT_REQUESTED",
                    detail = "Gemini Live handshake verification requested for ${liveAdapter.configuredModelId}."
                )
                applyVerifiedOutcome(
                    sessionId = current.sessionId,
                    turnId = nextTurn,
                    actionId = actionId,
                    newState = current.state,
                    roleLabel = "LIVE_ADAPTER",
                    outcome = outcome
                )
                outcome
            }

            CoordinatorCommand.START_MIC_SELF_TEST -> {
                val genId = audioCapture.startCapture(
                    owner = AudioOwner.MIC_SELF_TEST,
                    sessionId = current.sessionId
                )
                val snap = audioCapture.state.value
                val outcomeCode = if (snap.isCapturing) {
                    "MIC_SELF_TEST_STARTED"
                } else {
                    "MIC_SELF_TEST_BLOCKED_${snap.anomaly.wireCode}"
                }
                val detailMsg = if (snap.isCapturing) {
                    "Local Microphone Self-Test started (${genId.wireId}, ${snap.hardwareSampleRateHz}Hz -> 16000Hz Mono PCM-16). Note: Self-test verifies local hardware only and is NOT an assistant answer."
                } else {
                    "Local Microphone Self-Test could not start (${genId.wireId}): ${snap.statusDetail}"
                }
                val outcome = VerifiedOutcome.AudioSelfTestOutcome(
                    actionId = actionId,
                    sessionId = current.sessionId,
                    turnId = nextTurn,
                    resultingState = current.state,
                    generationId = genId,
                    anomaly = snap.anomaly,
                    isCapturing = snap.isCapturing,
                    code = outcomeCode,
                    detail = detailMsg
                )
                applyVerifiedOutcome(
                    sessionId = current.sessionId,
                    turnId = nextTurn,
                    actionId = actionId,
                    newState = current.state,
                    roleLabel = "MIC_SELF_TEST",
                    outcome = outcome
                )
                outcome
            }

            CoordinatorCommand.STOP_MIC_SELF_TEST -> {
                val prevGen = audioCapture.state.value.activeGeneration
                val frames = audioCapture.state.value.capturedFrameCount
                val peakRms = audioCapture.state.value.peakRmsLinear
                audioCapture.stopAndRelease(
                    reason = "Self-test capture stopped and hardware released",
                    wipeEphemeral = false
                )
                val snap = audioCapture.state.value
                val outcome = VerifiedOutcome.AudioSelfTestOutcome(
                    actionId = actionId,
                    sessionId = current.sessionId,
                    turnId = nextTurn,
                    resultingState = current.state,
                    generationId = prevGen,
                    anomaly = snap.anomaly,
                    isCapturing = false,
                    code = "MIC_SELF_TEST_STOPPED_RELEASED",
                    detail = "Self-test stopped & AudioRecord released (${prevGen?.wireId ?: "none"}): $frames frames captured, peak RMS ${"%.3f".format(peakRms)}."
                )
                applyVerifiedOutcome(
                    sessionId = current.sessionId,
                    turnId = nextTurn,
                    actionId = actionId,
                    newState = current.state,
                    roleLabel = "MIC_SELF_TEST",
                    outcome = outcome
                )
                outcome
            }

            CoordinatorCommand.PLAY_EPHEMERAL_CHECK -> {
                val played = audioCapture.playAndDeleteEphemeralCheck(current.sessionId)
                val snap = audioCapture.state.value
                val outcome = VerifiedOutcome.AudioSelfTestOutcome(
                    actionId = actionId,
                    sessionId = current.sessionId,
                    turnId = nextTurn,
                    resultingState = current.state,
                    generationId = snap.activeGeneration ?: snap.lastCompletedGeneration,
                    anomaly = snap.anomaly,
                    isCapturing = false,
                    code = if (played) "EPHEMERAL_PLAYBACK_AND_DELETE" else "EPHEMERAL_PLAYBACK_EMPTY",
                    detail = if (played) {
                        "Ephemeral self-test recording played for intelligibility check and immediately wiped (Retention OFF, 0 bytes retained)."
                    } else {
                        "Ephemeral playback skipped: ${snap.statusDetail}"
                    }
                )
                applyVerifiedOutcome(
                    sessionId = current.sessionId,
                    turnId = nextTurn,
                    actionId = actionId,
                    newState = current.state,
                    roleLabel = "EPHEMERAL_CHECK",
                    outcome = outcome
                )
                outcome
            }

            CoordinatorCommand.RESET_IDLE -> {
                audioCapture.stopAndRelease(
                    reason = "Session reset to IDLE",
                    wipeEphemeral = true
                )
                liveAdapter.disconnectAndRelease(reason = "Session reset to IDLE")
                val freshSession = idGenerator.nextSessionId()
                val outcome = VerifiedOutcome.StateTransitionVerified(
                    actionId = actionId,
                    sessionId = freshSession,
                    turnId = nextTurn,
                    previousState = current.state,
                    resultingState = ConversationState.IDLE,
                    detail = "Session reset to IDLE with new session ID ${freshSession.value}. Audio hardware and live socket released."
                )
                applyVerifiedOutcome(
                    sessionId = freshSession,
                    turnId = nextTurn,
                    actionId = actionId,
                    newState = ConversationState.IDLE,
                    roleLabel = "SESSION_RESET",
                    outcome = outcome
                )
                outcome
            }
        }
    }

    /**
     * Evaluates a batch of concurrent commands and enforces strict Stop/Pause precedence
     * (STOP > STOP_MIC_SELF_TEST > DISCONNECT_LIVE_SESSION > PAUSE > CONNECT_LIVE_SESSION > START_MIC_SELF_TEST > START).
     */
    @Synchronized
    fun dispatchWithPrecedence(
        commands: List<CoordinatorCommand>,
        actionId: ActionId = idGenerator.nextActionId("batch")
    ): VerifiedOutcome {
        require(commands.isNotEmpty()) { "Command batch must not be empty" }
        val winningCommand = commands.maxByOrNull { it.priority } ?: CoordinatorCommand.STOP
        val losingCommands = commands.filter { it != winningCommand }

        if (losingCommands.isEmpty()) {
            return dispatch(winningCommand, actionId)
        }

        val current = _uiState.value
        if (executedActionIds.contains(actionId.value)) {
            return dispatch(winningCommand, actionId)
        }
        recordActionId(actionId)
        val nextTurn = idGenerator.nextTurnId()

        if (winningCommand == CoordinatorCommand.STOP || winningCommand == CoordinatorCommand.PAUSE) {
            audioCapture.stopAndRelease(
                reason = "Preempted by ${winningCommand.name} precedence",
                wipeEphemeral = (winningCommand == CoordinatorCommand.STOP)
            )
            liveAdapter.disconnectAndRelease(
                reason = "Preempted by ${winningCommand.name} precedence"
            )
        }

        val targetState = when (winningCommand) {
            CoordinatorCommand.STOP -> ConversationState.STOPPED
            CoordinatorCommand.PAUSE ->
                if (current.state == ConversationState.STOPPED) ConversationState.STOPPED
                else ConversationState.PAUSED
            CoordinatorCommand.START -> ConversationState.NOT_CONFIGURED
            CoordinatorCommand.RESET_IDLE -> ConversationState.IDLE
            else -> current.state
        }

        val preemptedList = losingCommands.joinToString(", ") { it.name }
        val outcome = VerifiedOutcome.PreemptedByPrecedence(
            actionId = actionId,
            sessionId = current.sessionId,
            turnId = nextTurn,
            requestedCommand = losingCommands.first(),
            enforcingCommand = winningCommand,
            resultingState = targetState,
            detail = "Concurrent commands [${commands.joinToString { it.name }}] resolved via Stop/Pause precedence: ${winningCommand.name} preempted [$preemptedList] -> ${targetState.wireLabel}."
        )

        applyVerifiedOutcome(
            sessionId = current.sessionId,
            turnId = nextTurn,
            actionId = actionId,
            newState = targetState,
            roleLabel = "PRECEDENCE_VERIFIER",
            outcome = outcome
        )
        return outcome
    }

    private fun formatMicLabel(snap: AudioCaptureState): String = when {
        snap.isCapturing -> "MIC: CAPTURING (${snap.activeGeneration?.wireId ?: "active"} • ${snap.capturedFrameCount} frames)"
        snap.isPlayingEphemeralCheck -> "MIC: PLAYING EPHEMERAL CHECK (${snap.activeGeneration?.wireId ?: "play"})"
        snap.permissionState == MicPermissionState.GRANTED -> "MIC: READY (PERMISSION GRANTED • IDLE)"
        snap.permissionState == MicPermissionState.PERMANENTLY_DENIED -> "MIC: BLOCKED (PERMANENTLY DENIED — OPEN SETTINGS)"
        snap.permissionState == MicPermissionState.REFUSED_CAN_RETRY -> "MIC: PERMISSION REFUSED (CAN RETRY)"
        else -> "MIC: INACTIVE (NOT_CONFIGURED IN PHASE 01)"
    }

    private fun applyVerifiedOutcome(
        sessionId: SessionId,
        turnId: TurnId,
        actionId: ActionId,
        newState: ConversationState,
        roleLabel: String,
        outcome: VerifiedOutcome
    ) {
        val snap = audioCapture.state.value
        val liveSnap = liveAdapter.state.value
        val micLabel = formatMicLabel(snap)

        val entry = TranscriptEntry(
            id = "${actionId.value}-${turnId.value}",
            sessionId = sessionId,
            turnId = turnId,
            actionId = actionId,
            roleLabel = roleLabel,
            stateSnapshot = newState,
            outcomeCode = outcome.code,
            message = outcome.detail,
            timestampLabel = clockProvider()
        )
        _uiState.update { state ->
            state.copy(
                sessionId = sessionId,
                currentTurnId = turnId,
                lastActionId = actionId,
                state = newState,
                isMicrophoneActive = snap.isCapturing,
                microphoneStatusLabel = micLabel,
                audioCaptureState = snap,
                liveConnectionState = liveSnap,
                lastOutcome = outcome,
                transcript = state.transcript + entry,
                executedActionCount = state.executedActionCount + 1
            )
        }
    }

    private fun recordActionId(actionId: ActionId) {
        if (executedActionIds.size >= maxTrackedActions) {
            val first = executedActionIds.iterator().next()
            executedActionIds.remove(first)
        }
        executedActionIds.add(actionId.value)
    }
}
