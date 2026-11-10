package com.example.contract

import java.util.concurrent.atomic.AtomicLong

/**
 * Strongly-typed Session Identifier required by PROJECT_CONTRACT.md.
 * Never embeds private user content or credentials.
 */
@JvmInline
value class SessionId(val value: String) {
    override fun toString(): String = value
}

/**
 * Strongly-typed Turn Identifier within a conversation session.
 */
@JvmInline
value class TurnId(val value: String) {
    override fun toString(): String = value
}

/**
 * Strongly-typed Action Identifier used to guarantee exactly-once effects
 * in [com.example.coordinator.ConversationCoordinator].
 */
@JvmInline
value class ActionId(val value: String) {
    override fun toString(): String = value
}

/**
 * Deterministic, thread-safe ID generator for Session, Turn, and Action IDs.
 */
class ContractIdGenerator(
    private val prefix: String = "ov01"
) {
    private val sessionSeq = AtomicLong(1L)
    private val turnSeq = AtomicLong(0L)
    private val actionSeq = AtomicLong(0L)

    fun nextSessionId(): SessionId =
        SessionId("$prefix-sess-${sessionSeq.getAndIncrement().toString().padStart(3, '0')}")

    fun nextTurnId(): TurnId =
        TurnId("$prefix-turn-${turnSeq.incrementAndGet().toString().padStart(4, '0')}")

    fun currentTurnIdOrInit(): TurnId {
        val curr = turnSeq.get()
        return if (curr == 0L) TurnId("$prefix-turn-0000")
        else TurnId("$prefix-turn-${curr.toString().padStart(4, '0')}")
    }

    fun nextActionId(verb: String): ActionId {
        val safeVerb = verb.lowercase().replace(Regex("[^a-z0-9_]"), "")
        return ActionId("$prefix-act-$safeVerb-${actionSeq.incrementAndGet().toString().padStart(4, '0')}")
    }
}

/**
 * Real, honest conversation states for Phase 01.
 * Start transitions to [NOT_CONFIGURED] because Gemini/audio/call engines are intentionally
 * omitted in Phase 01. Never simulates or animates a fake active microphone.
 */
enum class ConversationState(
    val wireLabel: String,
    val badgeTitle: String,
    val summary: String
) {
    IDLE(
        wireLabel = "IDLE",
        badgeTitle = "IDLE",
        summary = "Coordinator initialized. Awaiting operator command."
    ),
    NOT_CONFIGURED(
        wireLabel = "NOT_CONFIGURED",
        badgeTitle = "NOT_CONFIGURED",
        summary = "Honest Phase 01 state: Audio capture & Gemini model are not configured in Phase 01."
    ),
    PAUSED(
        wireLabel = "PAUSED",
        badgeTitle = "PAUSED",
        summary = "Session paused by operator. Pause precedence active."
    ),
    STOPPED(
        wireLabel = "STOPPED",
        badgeTitle = "STOPPED",
        summary = "Session halted immediately. Stop holds highest precedence."
    )
}

/**
 * Operator or system commands dispatched to the single ConversationCoordinator.
 */
enum class CoordinatorCommand(val priority: Int) {
    /** Highest precedence (priority 100): immediately halts any state or pending start/pause. */
    STOP(priority = 100),

    /** Second precedence (priority 50): preempts Start/active states unless Stopped. */
    PAUSE(priority = 50),

    /** Normal precedence (priority 10): requests session start (transitions to NOT_CONFIGURED in Phase 01). */
    START(priority = 10),

    /** Resets shell state back to IDLE with a fresh SessionId. */
    RESET_IDLE(priority = 5)
}

/**
 * Typed verified outcomes returned by the single ConversationCoordinator.
 * Ensures every command produces an auditable, typed result.
 */
sealed interface VerifiedOutcome {
    val actionId: ActionId
    val sessionId: SessionId
    val turnId: TurnId
    val resultingState: ConversationState
    val code: String
    val detail: String

    data class StateTransitionVerified(
        override val actionId: ActionId,
        override val sessionId: SessionId,
        override val turnId: TurnId,
        val previousState: ConversationState,
        override val resultingState: ConversationState,
        override val detail: String
    ) : VerifiedOutcome {
        override val code: String = "VERIFIED_TRANSITION"
    }

    data class HonestNotConfiguredOutcome(
        override val actionId: ActionId,
        override val sessionId: SessionId,
        override val turnId: TurnId,
        override val resultingState: ConversationState = ConversationState.NOT_CONFIGURED,
        override val detail: String
    ) : VerifiedOutcome {
        override val code: String = "HONEST_NOT_CONFIGURED"
    }

    data class PreemptedByPrecedence(
        override val actionId: ActionId,
        override val sessionId: SessionId,
        override val turnId: TurnId,
        val requestedCommand: CoordinatorCommand,
        val enforcingCommand: CoordinatorCommand,
        override val resultingState: ConversationState,
        override val detail: String
    ) : VerifiedOutcome {
        override val code: String = "PREEMPTED_BY_${enforcingCommand.name}"
    }

    data class DuplicateActionRejected(
        override val actionId: ActionId,
        override val sessionId: SessionId,
        override val turnId: TurnId,
        override val resultingState: ConversationState,
        override val detail: String
    ) : VerifiedOutcome {
        override val code: String = "EXACTLY_ONCE_DUPLICATE_REJECTED"
    }
}

/**
 * Transcript item rendered in the conversation shell transcript pane.
 */
data class TranscriptEntry(
    val id: String,
    val sessionId: SessionId,
    val turnId: TurnId,
    val actionId: ActionId,
    val roleLabel: String,
    val stateSnapshot: ConversationState,
    val outcomeCode: String,
    val message: String,
    val timestampLabel: String
)
