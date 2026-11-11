package com.example.coordinator

import com.example.BuildConfig
import com.example.audio.AudioAnomaly
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
    val microphoneStatusLabel: String = "MIC: INACTIVE (LOCAL SELF-TEST READY; GEMINI NOT_CONFIGURED)",
    val modelConfigStatus: String = BuildConfig.MODEL_CONFIG_STATUS,
    val sourceBuildId: String = BuildConfig.SOURCE_BUILD_ID,
    val lastOutcome: VerifiedOutcome? = null,
    val transcript: List<TranscriptEntry> = emptyList(),
    val executedActionCount: Int = 0,
    val duplicateRejectedCount: Int = 0,
    val audioCaptureState: AudioCaptureState = AudioCaptureState()
)

/**
 * Single ConversationCoordinator for OmniVoice Rebuild (Phase 01 & Phase 02).
 *
 * Enforces PROJECT_CONTRACT.md invariants:
 * 1. Single coordinator authority for session, turn, action, and audio owner transitions.
 * 2. Strongly-typed SessionId, TurnId, ActionId, and CaptureGenerationId.
 * 3. Exactly-once effects: duplicate ActionIds are rejected deterministically without mutating state.
 * 4. Stop/Pause precedence: STOP > STOP_MIC_SELF_TEST > PAUSE > START_MIC_SELF_TEST > START.
 *    Large STOP immediately releases audio capture/playback hardware and wipes ephemeral buffers.
 * 5. Honest NOT_CONFIGURED state on Start: microphone self-test is strictly local hardware
 *    verification and never pretends to be an assistant answer.
 * 6. Excludes private data and secrets from logs and state payloads.
 */
class ConversationCoordinator(
    private val idGenerator: ContractIdGenerator = ContractIdGenerator(),
    val audioCapture: AudioCapture = AudioCapture(DefaultNoContextAudioDriver()),
    private val clockProvider: () -> String = { "PHASE02-T+${System.currentTimeMillis() % 100000}ms" }
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
            transcript = listOf(
                TranscriptEntry(
                    id = "${initialActionId.value}-log",
                    sessionId = initialSessionId,
                    turnId = initialTurnId,
                    actionId = initialActionId,
                    roleLabel = "COORDINATOR",
                    stateSnapshot = ConversationState.IDLE,
                    outcomeCode = "SHELL_INITIALIZED",
                    message = "OmniVoice Rebuild Phase 02 ready. Single ConversationCoordinator & single AudioCapture owner active. Assistant/Gemini model remains NOT_CONFIGURED; Microphone Self-Test is local hardware verification only.",
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
     * Synchronizes the latest [AudioCaptureState] into [ConversationUiState] so the shell
     * and microphone setup screen always reflect real hardware state.
     */
    @Synchronized
    fun syncAudioState() {
        val snap = audioCapture.state.value
        val micLabel = when {
            snap.isCapturing -> "MIC: CAPTURING (${snap.activeGeneration?.wireId ?: "active"} • ${snap.capturedFrameCount} frames)"
            snap.isPlayingEphemeralCheck -> "MIC: PLAYING EPHEMERAL CHECK (${snap.activeGeneration?.wireId ?: "play"})"
            snap.permissionState == MicPermissionState.GRANTED -> "MIC: READY (PERMISSION GRANTED • IDLE)"
            snap.permissionState == MicPermissionState.PERMANENTLY_DENIED -> "MIC: BLOCKED (PERMANENTLY DENIED — OPEN SETTINGS)"
            snap.permissionState == MicPermissionState.REFUSED_CAN_RETRY -> "MIC: PERMISSION REFUSED (CAN RETRY)"
            else -> "MIC: INACTIVE (PERMISSION NOT_REQUESTED)"
        }
        _uiState.update { current ->
            current.copy(
                isMicrophoneActive = snap.isCapturing,
                microphoneStatusLabel = micLabel,
                audioCaptureState = snap
            )
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
     * Explicit lifecycle hook when navigating away from the Microphone Setup screen or when
     * the Activity stops/backgrounds (not a configuration change).
     * Immediately releases hardware and wipes ephemeral self-test audio.
     */
    @Synchronized
    fun onLifecycleReleaseHardware(reason: String) {
        val hadActiveHardware = audioCapture.state.value.isCapturing ||
            audioCapture.state.value.isPlayingEphemeralCheck ||
            audioCapture.state.value.ephemeralRecordedSamples > 0
        audioCapture.stopAndRelease(reason = reason, wipeEphemeral = true)
        syncAudioState()
        if (hadActiveHardware) {
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
                // Large Stop MUST promptly release hardware and wipe ephemeral self-test data
                audioCapture.stopAndRelease(
                    reason = "Immediate operator STOP command",
                    wipeEphemeral = true
                )
                val outcome = VerifiedOutcome.StateTransitionVerified(
                    actionId = actionId,
                    sessionId = current.sessionId,
                    turnId = nextTurn,
                    previousState = current.state,
                    resultingState = ConversationState.STOPPED,
                    detail = "STOP command executed with highest precedence (priority ${CoordinatorCommand.STOP.priority}). Session halted, audio hardware released, and ephemeral buffer wiped."
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
                    val outcome = VerifiedOutcome.StateTransitionVerified(
                        actionId = actionId,
                        sessionId = current.sessionId,
                        turnId = nextTurn,
                        previousState = current.state,
                        resultingState = ConversationState.PAUSED,
                        detail = "PAUSE command executed (priority ${CoordinatorCommand.PAUSE.priority}). Active audio capture released and shell paused."
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
                // Keep conversation shell honest: even when mic is configured, Gemini assistant is NOT_CONFIGURED in Phase 02.
                val outcome = VerifiedOutcome.HonestNotConfiguredOutcome(
                    actionId = actionId,
                    sessionId = current.sessionId,
                    turnId = nextTurn,
                    resultingState = ConversationState.NOT_CONFIGURED,
                    detail = "Start requested from ${current.state.wireLabel}: Transitioned to honest NOT_CONFIGURED state. Gemini assistant model is not wired in Phase 02 (Microphone Self-Test is local hardware verification only, not an assistant answer)."
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
                val freshSession = idGenerator.nextSessionId()
                val outcome = VerifiedOutcome.StateTransitionVerified(
                    actionId = actionId,
                    sessionId = freshSession,
                    turnId = nextTurn,
                    previousState = current.state,
                    resultingState = ConversationState.IDLE,
                    detail = "Session reset to IDLE with new session ID ${freshSession.value}. Audio hardware released."
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
     * (STOP > STOP_MIC_SELF_TEST > PAUSE > START_MIC_SELF_TEST > START).
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

    private fun applyVerifiedOutcome(
        sessionId: SessionId,
        turnId: TurnId,
        actionId: ActionId,
        newState: ConversationState,
        roleLabel: String,
        outcome: VerifiedOutcome
    ) {
        val snap = audioCapture.state.value
        val micLabel = when {
            snap.isCapturing -> "MIC: CAPTURING (${snap.activeGeneration?.wireId ?: "active"} • ${snap.capturedFrameCount} frames)"
            snap.isPlayingEphemeralCheck -> "MIC: PLAYING EPHEMERAL CHECK (${snap.activeGeneration?.wireId ?: "play"})"
            snap.permissionState == MicPermissionState.GRANTED -> "MIC: READY (PERMISSION GRANTED • IDLE)"
            snap.permissionState == MicPermissionState.PERMANENTLY_DENIED -> "MIC: BLOCKED (PERMANENTLY DENIED — OPEN SETTINGS)"
            snap.permissionState == MicPermissionState.REFUSED_CAN_RETRY -> "MIC: PERMISSION REFUSED (CAN RETRY)"
            else -> "MIC: INACTIVE (NOT_CONFIGURED IN PHASE 01)"
        }

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
