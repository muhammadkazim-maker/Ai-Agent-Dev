package com.example.coordinator

import com.example.BuildConfig
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
 * Immutable UI state snapshot emitted by the single [ConversationCoordinator].
 */
data class ConversationUiState(
    val sessionId: SessionId,
    val currentTurnId: TurnId,
    val lastActionId: ActionId,
    val state: ConversationState,
    val isMicrophoneActive: Boolean = false,
    val microphoneStatusLabel: String = "MIC: INACTIVE (NOT_CONFIGURED IN PHASE 01)",
    val modelConfigStatus: String = BuildConfig.MODEL_CONFIG_STATUS,
    val sourceBuildId: String = BuildConfig.SOURCE_BUILD_ID,
    val lastOutcome: VerifiedOutcome? = null,
    val transcript: List<TranscriptEntry> = emptyList(),
    val executedActionCount: Int = 0,
    val duplicateRejectedCount: Int = 0
)

/**
 * Single ConversationCoordinator for OmniVoice Rebuild (Phase 01).
 *
 * EnforcesPROJECT_CONTRACT.md invariants:
 * 1. Single coordinator authority for session, turn, and action state transitions.
 * 2. Strongly-typed SessionId, TurnId, and ActionId on every transition and transcript entry.
 * 3. Exactly-once effects: duplicate ActionIds are rejected deterministically without mutating state.
 * 4. Stop/Pause precedence: STOP > PAUSE > START.
 * 5. Honest NOT_CONFIGURED state on Start in Phase 01; never fakes or animates a working microphone.
 * 6. Excludes private data and secrets from logs and state payloads.
 */
class ConversationCoordinator(
    private val idGenerator: ContractIdGenerator = ContractIdGenerator(),
    private val clockProvider: () -> String = { "PHASE01-T+${System.currentTimeMillis() % 100000}ms" }
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
            transcript = listOf(
                TranscriptEntry(
                    id = "${initialActionId.value}-log",
                    sessionId = initialSessionId,
                    turnId = initialTurnId,
                    actionId = initialActionId,
                    roleLabel = "COORDINATOR",
                    stateSnapshot = ConversationState.IDLE,
                    outcomeCode = "SHELL_INITIALIZED",
                    message = "OmniVoice Rebuild Phase 01 shell ready. Single ConversationCoordinator active. Audio, Gemini, accessibility, and call engines are NOT_CONFIGURED in Phase 01.",
                    timestampLabel = "INIT"
                )
            )
        )
    )
    val uiState: StateFlow<ConversationUiState> = _uiState.asStateFlow()

    init {
        recordActionId(initialActionId)
    }

    /**
     * Generates a fresh [ActionId] for a user or test command.
     */
    fun nextActionId(verb: String): ActionId = idGenerator.nextActionId(verb)

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
                val outcome = VerifiedOutcome.StateTransitionVerified(
                    actionId = actionId,
                    sessionId = current.sessionId,
                    turnId = nextTurn,
                    previousState = current.state,
                    resultingState = ConversationState.STOPPED,
                    detail = "STOP command executed with highest precedence (priority ${CoordinatorCommand.STOP.priority}). Session halted."
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
                // Stop/Pause Precedence: If already STOPPED, STOP retains precedence over PAUSE.
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
                    val outcome = VerifiedOutcome.StateTransitionVerified(
                        actionId = actionId,
                        sessionId = current.sessionId,
                        turnId = nextTurn,
                        previousState = current.state,
                        resultingState = ConversationState.PAUSED,
                        detail = "PAUSE command executed (priority ${CoordinatorCommand.PAUSE.priority}). Conversation shell paused."
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
                // In Phase 01, Start honestly reports NOT_CONFIGURED without faking a working mic.
                val outcome = VerifiedOutcome.HonestNotConfiguredOutcome(
                    actionId = actionId,
                    sessionId = current.sessionId,
                    turnId = nextTurn,
                    resultingState = ConversationState.NOT_CONFIGURED,
                    detail = "Start requested from ${current.state.wireLabel}: Transitioned to honest NOT_CONFIGURED state. Microphone capture and Gemini model are not wired in Phase 01."
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

            CoordinatorCommand.RESET_IDLE -> {
                val freshSession = idGenerator.nextSessionId()
                val outcome = VerifiedOutcome.StateTransitionVerified(
                    actionId = actionId,
                    sessionId = freshSession,
                    turnId = nextTurn,
                    previousState = current.state,
                    resultingState = ConversationState.IDLE,
                    detail = "Session reset to IDLE with new session ID ${freshSession.value}."
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
     * (STOP > PAUSE > START). Useful for verifying contract precedence under contention.
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

        val targetState = when (winningCommand) {
            CoordinatorCommand.STOP -> ConversationState.STOPPED
            CoordinatorCommand.PAUSE ->
                if (current.state == ConversationState.STOPPED) ConversationState.STOPPED
                else ConversationState.PAUSED
            CoordinatorCommand.START -> ConversationState.NOT_CONFIGURED
            CoordinatorCommand.RESET_IDLE -> ConversationState.IDLE
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
                isMicrophoneActive = false, // Strictly false in Phase 01; never fake mic activity
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
