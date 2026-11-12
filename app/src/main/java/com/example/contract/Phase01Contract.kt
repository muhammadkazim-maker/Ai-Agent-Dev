package com.example.contract

import com.example.audio.AudioAnomaly
import com.example.audio.CaptureGenerationId
import com.example.live.LiveConnectionPhase
import com.example.live.LiveFailureClassification
import com.example.live.LiveSessionGenerationId
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
 * Real, honest conversation states for Phase 01, Phase 02, and Phase 03.
 * Start transitions to [NOT_CONFIGURED] because full voice-turn streaming and audio playback
 * are intentionally deferred in Phase 03 (Phase 03 verifies the Gemini Live WebSocket
 * handshake and diagnostics only, without claiming audio playback).
 */
enum class ConversationState(
    val wireLabel: String,
    val badgeTitle: String,
    val summary: String
) {
    IDLE(
        wireLabel = "IDLE",
        badgeTitle = "IDLE",
        summary = "Coordinator initialized. Awaiting operator command, Microphone Self-Test, or Gemini Live Handshake check."
    ),
    NOT_CONFIGURED(
        wireLabel = "NOT_CONFIGURED",
        badgeTitle = "NOT_CONFIGURED",
        summary = "Honest state: Voice-turn audio playback & tool execution are not claimed in Phase 03. Use Live Connection & Diagnostics to verify the Gemini Live WebSocket handshake, or Microphone Setup for local PCM check."
    ),
    PAUSED(
        wireLabel = "PAUSED",
        badgeTitle = "PAUSED",
        summary = "Session paused by operator. Active audio capture and live socket released; Pause precedence active."
    ),
    STOPPED(
        wireLabel = "STOPPED",
        badgeTitle = "STOPPED",
        summary = "Session halted; audio hardware and live socket immediately released. Stop holds highest precedence."
    )
}

/**
 * Operator or system commands dispatched through the single [com.example.coordinator.ConversationCoordinator].
 */
enum class CoordinatorCommand(val priority: Int) {
    /** Highest precedence (priority 100): immediately halts any state, closes live socket, releases hardware, and wipes ephemeral buffers. */
    STOP(priority = 100),

    /** Stops an active local microphone self-test and releases hardware (priority 90). */
    STOP_MIC_SELF_TEST(priority = 90),

    /** Disconnects any active or connecting Gemini Live WebSocket session (priority 80). */
    DISCONNECT_LIVE_SESSION(priority = 80),

    /** Second precedence (priority 50): preempts Start/capture/live states unless Stopped, and releases active capture & live socket. */
    PAUSE(priority = 50),

    /** Initiates or records a Gemini Live handshake verification attempt (priority 25). */
    CONNECT_LIVE_SESSION(priority = 25),

    /** Starts a local 16kHz PCM microphone self-test under MIC_SELF_TEST owner (priority 20). */
    START_MIC_SELF_TEST(priority = 20),

    /** Plays and immediately deletes the ephemeral self-test recording (priority 15). */
    PLAY_EPHEMERAL_CHECK(priority = 15),

    /** Normal precedence (priority 10): requests full voice conversation turn (transitions to NOT_CONFIGURED in Phase 01–03 because audio playback is not claimed yet). */
    START(priority = 10),

    /** Resets shell state back to IDLE with a fresh SessionId and releases any held audio hardware and live socket. */
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

    data class AudioSelfTestOutcome(
        override val actionId: ActionId,
        override val sessionId: SessionId,
        override val turnId: TurnId,
        override val resultingState: ConversationState,
        val generationId: CaptureGenerationId?,
        val anomaly: AudioAnomaly,
        val isCapturing: Boolean,
        override val code: String,
        override val detail: String
    ) : VerifiedOutcome

    data class LiveConnectionOutcome(
        override val actionId: ActionId,
        override val sessionId: SessionId,
        override val turnId: TurnId,
        override val resultingState: ConversationState,
        val livePhase: LiveConnectionPhase,
        val generationId: LiveSessionGenerationId?,
        val failureClassification: LiveFailureClassification,
        override val code: String,
        override val detail: String
    ) : VerifiedOutcome
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
