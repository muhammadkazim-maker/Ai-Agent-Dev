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
 * Real, honest conversation states for Phase 01 through Phase 05.
 * When required Phase 02 (`AudioCapture`) and Phase 03 (`GeminiLiveConnectionAdapter`) adapters
 * are configured, `Start` initializes the voice pipeline (`CONNECTING/PREPARING -> LISTENING -> PROCESSING/THINKING -> SPEAKING -> READY`
 * or auto-restoring `LISTENING` in Phase 05 Active Conversation mode).
 * [NOT_CONFIGURED] is only shown if required adapters are explicitly absent.
 */
enum class ConversationState(
    val wireLabel: String,
    val badgeTitle: String,
    val summary: String
) {
    IDLE(
        wireLabel = "IDLE",
        badgeTitle = "IDLE",
        summary = "Coordinator initialized. Press Start to begin a verified Gemini Live native-audio conversation."
    ),
    PREPARING(
        wireLabel = "PREPARING",
        badgeTitle = "PREPARING",
        summary = "Preparing Gemini Live session, verifying microphone & validated internet, and releasing prior turn resources..."
    ),
    CONNECTING(
        wireLabel = "CONNECTING",
        badgeTitle = "CONNECTING",
        summary = "Verifying microphone & validated internet and connecting authenticated Gemini Live session..."
    ),
    LISTENING(
        wireLabel = "LISTENING",
        badgeTitle = "LISTENING",
        summary = "Capturing spoken turn at 16kHz PCM mono and streaming to Gemini Live (half-duplex active)..."
    ),
    THINKING(
        wireLabel = "THINKING",
        badgeTitle = "THINKING",
        summary = "Spoken turn committed (mic released per half-duplex policy). Thinking & awaiting Gemini native-audio response..."
    ),
    PROCESSING(
        wireLabel = "PROCESSING",
        badgeTitle = "PROCESSING",
        summary = "Spoken question sent with end-of-turn signal. Awaiting Gemini Live native-audio response..."
    ),
    SPEAKING(
        wireLabel = "SPEAKING",
        badgeTitle = "SPEAKING",
        summary = "Streaming Gemini native-audio PCM response through Android AudioTrack hardware until playback drains..."
    ),
    READY(
        wireLabel = "READY",
        badgeTitle = "READY",
        summary = "Voice turn completed and hardware playback fully drained. Ready for the next spoken turn."
    ),
    ERROR(
        wireLabel = "ERROR",
        badgeTitle = "ERROR",
        summary = "Prerequisite or runtime error encountered during voice turn. Inspect exact error classification below."
    ),
    NOT_CONFIGURED(
        wireLabel = "NOT_CONFIGURED",
        badgeTitle = "NOT_CONFIGURED",
        summary = "Honest state: Voice turn adapters are explicitly unconfigured in this instance."
    ),
    PAUSED(
        wireLabel = "PAUSED",
        badgeTitle = "PAUSED",
        summary = "Session paused by operator. Active audio capture, playback, and live socket released; Pause precedence active."
    ),
    STOPPED(
        wireLabel = "STOPPED",
        badgeTitle = "STOPPED",
        summary = "Session halted; microphone capture, model turn, and queued playback immediately cancelled. Stop holds highest precedence."
    );

    fun toActiveConversationPhase(): ActiveConversationPhase = when (this) {
        IDLE, READY, NOT_CONFIGURED -> ActiveConversationPhase.IDLE
        PREPARING, CONNECTING -> ActiveConversationPhase.PREPARING
        LISTENING -> ActiveConversationPhase.LISTENING
        THINKING, PROCESSING -> ActiveConversationPhase.THINKING
        SPEAKING -> ActiveConversationPhase.SPEAKING
        PAUSED -> ActiveConversationPhase.PAUSED
        STOPPED -> ActiveConversationPhase.STOPPED
        ERROR -> ActiveConversationPhase.ERROR
    }
}

/**
 * Serialized Phase 05 foreground active conversation state machine states:
 * Preparing, Listening, Thinking, Speaking, Paused, and Error (plus Idle and Stopped).
 */
enum class ActiveConversationPhase(
    val wireLabel: String,
    val displayLabel: String,
    val summary: String
) {
    IDLE(
        wireLabel = "IDLE",
        displayLabel = "Idle",
        summary = "Active conversation idle. Press Start to begin a foreground active conversation."
    ),
    PREPARING(
        wireLabel = "PREPARING",
        displayLabel = "Preparing",
        summary = "Preparing Gemini Live session, verifying microphone & validated internet, and releasing prior turn resources..."
    ),
    LISTENING(
        wireLabel = "LISTENING",
        displayLabel = "Listening",
        summary = "Listening in half-duplex active conversation (16kHz PCM mono capture active)..."
    ),
    THINKING(
        wireLabel = "THINKING",
        displayLabel = "Thinking",
        summary = "Spoken turn committed (mic released per half-duplex policy). Thinking & awaiting Gemini native-audio response..."
    ),
    SPEAKING(
        wireLabel = "SPEAKING",
        displayLabel = "Speaking",
        summary = "Speaking native-audio response via Android AudioTrack (half-duplex: mic disabled; auto-restores capture after playback drains)..."
    ),
    PAUSED(
        wireLabel = "PAUSED",
        displayLabel = "Paused",
        summary = "Deliberately paused/muted by operator. Active capture and playback released; auto-resume suppressed until operator presses Resume."
    ),
    STOPPED(
        wireLabel = "STOPPED",
        displayLabel = "Stopped",
        summary = "Deliberately stopped by operator. Capture, model turn, playback, and Live session halted; auto-resume suppressed."
    ),
    ERROR(
        wireLabel = "ERROR",
        displayLabel = "Error",
        summary = "Honest prerequisite or runtime error encountered. Inspect classified error code below."
    )
}

/**
 * Configurable inactivity-based session closure options for Phase 05 active conversation.
 * Replaces any arbitrary turn-count cutoff.
 */
enum class InactivityTimeoutOption(
    val durationMs: Long,
    val label: String,
    val shortChipLabel: String
) {
    SECONDS_30(30_000L, "30 seconds", "30s"),
    MINUTE_1(60_000L, "1 minute", "1m"),
    MINUTES_2(120_000L, "2 minutes", "2m"),
    MINUTES_5(300_000L, "5 minutes", "5m")
}

/**
 * Generation stamp carried by every serialized active-conversation event so stale events
 * from an older session or older turn can never close, interrupt, or resume a newer turn.
 */
data class ConversationGenerationStamp(
    val sessionId: SessionId,
    val liveGenerationId: LiveSessionGenerationId?,
    val turnGenerationId: com.example.live.TurnGenerationId
)

/**
 * Short bounded dialogue exchange entry within the active session for coherent follow-up.
 */
data class SessionDialogueTurn(
    val sessionId: SessionId,
    val turnId: TurnId,
    val turnGenerationWireId: String,
    val turnNumberInSession: Int,
    val userTranscript: String,
    val modelTranscript: String
)

/**
 * Operator or system commands dispatched through the single [com.example.coordinator.ConversationCoordinator].
 */
enum class CoordinatorCommand(val priority: Int) {
    /** Highest precedence (priority 100): immediately halts any state, cancels mic capture, model turn & queued playback, and closes live socket. */
    STOP(priority = 100),

    /** Stops an active local microphone self-test and releases hardware (priority 90). */
    STOP_MIC_SELF_TEST(priority = 90),

    /** Disconnects any active or connecting Gemini Live WebSocket session (priority 80). */
    DISCONNECT_LIVE_SESSION(priority = 80),

    /** Second precedence (priority 50): preempts Start/Resume/capture/live states unless Stopped, and releases active capture, playback & live socket. */
    PAUSE(priority = 50),

    /** Resumes a paused session after explicit operator request (priority 45; preempted by STOP and PAUSE). */
    RESUME(priority = 45),

    /** Signals end of spoken question during LISTENING so the turn transitions to PROCESSING -> SPEAKING -> READY (priority 30). */
    END_SPOKEN_TURN(priority = 30),

    /** Initiates or records a Gemini Live handshake verification attempt (priority 25). */
    CONNECT_LIVE_SESSION(priority = 25),

    /** Starts a local 16kHz PCM microphone self-test under MIC_SELF_TEST owner (priority 20). */
    START_MIC_SELF_TEST(priority = 20),

    /** Plays and immediately deletes the ephemeral self-test recording (priority 15). */
    PLAY_EPHEMERAL_CHECK(priority = 15),

    /** Normal precedence (priority 10): automatically initializes and runs a Gemini Live native-audio conversation turn. */
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

    data class PrerequisiteFailureOutcome(
        override val actionId: ActionId,
        override val sessionId: SessionId,
        override val turnId: TurnId,
        override val resultingState: ConversationState = ConversationState.ERROR,
        val failureCode: String,
        override val detail: String
    ) : VerifiedOutcome {
        override val code: String = failureCode
    }

    data class VoiceTurnOutcome(
        override val actionId: ActionId,
        override val sessionId: SessionId,
        override val turnId: TurnId,
        override val resultingState: ConversationState,
        val stateSequence: List<ConversationState>,
        val micFramesSent: Long,
        val playbackFramesDrained: Long,
        val isPlaybackDrained: Boolean,
        override val code: String,
        override val detail: String
    ) : VerifiedOutcome

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

