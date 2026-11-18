package com.example.live

import com.example.BuildConfig
import com.example.contract.SessionId
import com.example.retrieval.PublicRetrievalConstants
import kotlin.math.min
import kotlin.math.pow

enum class LiveEndpointFamily(val wireCode: String) {
    FIREBASE_AI_LOGIC_GOOGLE_AI_V1BETA_BIDI("FIREBASE_AI_LOGIC_GOOGLE_AI_V1BETA_BIDI")
}

enum class LiveAuthRoute(val wireCode: String) {
    FIREBASE_AI_LOGIC_MANAGED_APP_CHECK("FIREBASE_AI_LOGIC_MANAGED_APP_CHECK")
}

/**
 * Canonical Phase 03–06A Gemini Live configuration constants.
 * Note: Names shown in AI Studio's coding-model picker (e.g. models/gemini-4-argon)
 * are coding agent identifiers, NOT Gemini Live BidiGenerateContent runtime model IDs.
 */
object LiveConfigConstants {
    /** Official documented Gemini Live native-audio model ID for Firebase AI Logic (Gemini Developer API). */
    const val SUPPORTED_LIVE_MODEL_ID: String = BuildConfig.LIVE_MODEL_ID
    const val DEFAULT_NATIVE_AUDIO_VOICE: String = "Kore"
    const val LIVE_API_VERSION: String = "v1beta"
    val ENDPOINT_FAMILY: LiveEndpointFamily = LiveEndpointFamily.FIREBASE_AI_LOGIC_GOOGLE_AI_V1BETA_BIDI
    val AUTH_ROUTE: LiveAuthRoute = LiveAuthRoute.FIREBASE_AI_LOGIC_MANAGED_APP_CHECK
    const val REGISTERED_ACTION_TOOLS_COUNT: Int = 0
    const val REGISTERED_RETRIEVAL_TOOLS_COUNT: Int = PublicRetrievalConstants.REGISTERED_RETRIEVAL_TOOLS_COUNT
    const val PUBLIC_RETRIEVAL_TOOL_NAME: String = PublicRetrievalConstants.TOOL_NAME_SEARCH_PUBLIC_INFO
    const val RETRIEVAL_MODEL_ID: String = PublicRetrievalConstants.RETRIEVAL_MODEL_ID

    /**
     * Single authoritative non-leaking baseline Gemini Live session instruction (Phase 06A + 06B safety rule).
     */
    const val AUTHORITATIVE_SYSTEM_INSTRUCTION: String =
        "Listen to the speaker carefully. For every turn independently, respond unmistakably in the language of the speaker's current utterance. Fully English speech gets English. Urdu or Roman Urdu gets natural conversational Urdu or Roman Urdu. Meaningfully mixed Urdu-English speech gets a similar natural mixture. A request such as 'answer in English' applies only to that response unless the user explicitly says 'from now on', 'always', 'ab se', or 'hamesha'. Never let the language of a previous response control the next turn. Keep the same calm, friendly and direct personality and the same configured voice. Be concise unless the user requests detail. Do not repeatedly introduce yourself. Never claim an action was completed unless it was verified. Respond only after a verified intelligible user utterance. Silence, breathing, ambient noise, taps and unclear audio are not requests. Never ask whether the user needs help merely because the user is silent."
}

/**
 * Strongly-typed socket/session generation identity binding every WebSocket/LiveSession callback
 * to the exact connection attempt that created it.
 */
data class LiveSessionGenerationId(
    val sequenceNumber: Long,
    val sessionId: SessionId,
    val wireId: String
)

/**
 * Strongly-typed per-turn generation identity so callbacks from an older voice turn
 * can never interrupt or overwrite a newer voice turn.
 */
data class TurnGenerationId(
    val turnSeq: Long,
    val turnIdValue: String,
    val sessionId: SessionId
) {
    val wireId: String
        get() = "tgen-${turnSeq.toString().padStart(4, '0')}-$turnIdValue"
}

/**
 * Explicit connection lifecycle phases required for Phase 03.
 */
enum class LiveConnectionPhase(val wireCode: String, val userSummary: String) {
    DISCONNECTED(
        wireCode = "DISCONNECTED",
        userSummary = "Live WebSocket disconnected. Ready for handshake verification."
    ),
    CONNECTING(
        wireCode = "CONNECTING",
        userSummary = "Opening authenticated BidiGenerateContent WebSocket and sending setup frame..."
    ),
    SETUP_ACCEPTED(
        wireCode = "SETUP_ACCEPTED",
        userSummary = "Live handshake verified & BidiGenerateContent setup accepted."
    ),
    ACTIVE(
        wireCode = "ACTIVE",
        userSummary = "Gemini Live session active and streaming realtime conversation turns."
    ),
    RECONNECTING(
        wireCode = "RECONNECTING",
        userSummary = "Closing previous generation cleanly and reconnecting Gemini Live session..."
    ),
    RETRY_BACKOFF_WAITING(
        wireCode = "RETRY_BACKOFF_WAITING",
        userSummary = "Transient socket failure; waiting for bounded exponential backoff before retry..."
    ),
    NETWORK_UNAVAILABLE(
        wireCode = "NETWORK_UNAVAILABLE",
        userSummary = "No validated internet access (NET_CAPABILITY_INTERNET + NET_CAPABILITY_VALIDATED missing). Stale session invalidated."
    ),
    NETWORK_RESTORED(
        wireCode = "NETWORK_RESTORED",
        userSummary = "Validated internet restored (NETWORK_RESTORED / READY_TO_RECONNECT)."
    ),
    FAILED(
        wireCode = "FAILED",
        userSummary = "Live connection failed. Inspect classified failure code and redacted diagnostics."
    )
}

/**
 * Classified failure categories for Gemini Live connection attempts.
 * Enforces contract rule: bounded backoff ONLY for transient network/socket failures while online;
 * immediately stop automatic retries for offline (`NETWORK_UNAVAILABLE`), bad credentials (`401/403`),
 * unsupported models (`400/404`), and exhausted quota (`429`).
 */
enum class LiveFailureClassification(
    val wireCode: String,
    val retryable: Boolean,
    val guidance: String
) {
    NONE(
        wireCode = "NONE",
        retryable = false,
        guidance = "No failure."
    ),
    NETWORK_UNAVAILABLE(
        wireCode = "NETWORK_UNAVAILABLE",
        retryable = false,
        guidance = "No validated internet connection (NET_CAPABILITY_INTERNET + NET_CAPABILITY_VALIDATED required). Firebase AI Logic and App Check were not called."
    ),
    NETWORK_ERROR(
        wireCode = "NETWORK_ERROR",
        retryable = true,
        guidance = "Network, DNS, timeout, or WebSocket I/O interruption (NETWORK_ERROR). Bounded backoff retry eligible while online."
    ),
    BAD_CREDENTIALS_OR_APP_CHECK(
        wireCode = "BAD_CREDENTIALS_OR_APP_CHECK",
        retryable = false,
        guidance = "Authentication or Firebase App Check attestation rejected while internet is validated (HTTP 401/403). Automatic retries stopped."
    ),
    UNSUPPORTED_MODEL_OR_ENDPOINT(
        wireCode = "UNSUPPORTED_MODEL_OR_ENDPOINT",
        retryable = false,
        guidance = "Configured model ID or Bidi endpoint rejected (HTTP 400/404). No silent model switch; automatic retries stopped."
    ),
    QUOTA_EXHAUSTED(
        wireCode = "QUOTA_EXHAUSTED",
        retryable = false,
        guidance = "Project rate limit or quota exhausted (HTTP 429 / RESOURCE_EXHAUSTED). Automatic retries stopped."
    ),
    TRANSIENT_NETWORK_OR_SOCKET(
        wireCode = "TRANSIENT_NETWORK_OR_SOCKET",
        retryable = true,
        guidance = "Transient network/WebSocket error (HTTP 503 / timeout / socket reset). Bounded backoff retry eligible."
    ),
    SESSION_EXPIRED_OR_GO_AWAY(
        wireCode = "SESSION_EXPIRED_OR_GO_AWAY",
        retryable = true,
        guidance = "Server notified session going away or session lifetime expired (LiveServerGoAway / session expiry). Bounded reconnect eligible."
    ),
    LOCAL_CONFIGURATION_FAILURE(
        wireCode = "LOCAL_CONFIGURATION_FAILURE",
        retryable = false,
        guidance = "Local prerequisite or configuration missing/invalid (FirebaseApp uninitialized, VAD model missing, or invalid local setup). Automatic retries stopped."
    ),
    CANCELLED_BY_STOP_OR_PAUSE(
        wireCode = "CANCELLED_BY_STOP_OR_PAUSE",
        retryable = false,
        guidance = "Connection halted immediately by operator Stop/Pause or lifecycle release."
    )
}

/**
 * Redacted status of Gemini Live session resumption support (never exposes raw handle values).
 */
enum class SessionResumptionStatus(val wireCode: String) {
    NOT_CONFIGURED("NOT_CONFIGURED"),
    ENABLED_AWAITING_HANDLE("ENABLED_AWAITING_HANDLE"),
    HANDLE_AVAILABLE_RESUMABLE("HANDLE_AVAILABLE_RESUMABLE"),
    HANDLE_UNRESUMABLE("HANDLE_UNRESUMABLE"),
    RESUMED_WITH_SERVER_HANDLE("RESUMED_WITH_SERVER_HANDLE"),
    HANDLE_REJECTED_FELL_BACK_NEW_CONNECTION("HANDLE_REJECTED_FELL_BACK_NEW_CONNECTION"),
    CLEARED_ON_STOP_OR_RESET("CLEARED_ON_STOP_OR_RESET")
}

/**
 * Honest indicator of whether a connection/reconnection restored server session context via an
 * accepted resumption handle or established a fresh connection without resumed server context.
 */
enum class ContextRestorationStatus(val wireCode: String) {
    INITIAL_NEW_SESSION("INITIAL_NEW_SESSION"),
    RESUMED_WITH_SERVER_HANDLE("RESUMED_WITH_SERVER_HANDLE"),
    NEW_CONNECTION_WITHOUT_RESUMED_CONTEXT("NEW_CONNECTION_WITHOUT_RESUMED_CONTEXT")
}

/**
 * Bounded exponential backoff policy for transient Live WebSocket failures.
 */
data class BoundedBackoffPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 500L,
    val multiplier: Double = 2.0,
    val maxDelayMs: Long = 4_000L
) {
    /**
     * Computes deterministic bounded delay in milliseconds after 0-based [attemptIndex],
     * or returns `null` when `attemptIndex + 1 >= maxAttempts` (no further retries allowed).
     */
    fun nextDelayMs(attemptIndex: Int): Long? {
        if (attemptIndex + 1 >= maxAttempts) return null
        val factor = multiplier.pow(attemptIndex.coerceAtLeast(0).toDouble())
        return min(maxDelayMs, (initialDelayMs * factor).toLong()).coerceAtLeast(0L)
    }
}

/**
 * Redacts sensitive tokens, API keys, bearer tokens, App Check headers, resumption handles,
 * contact information, transcripts, raw audio payloads, and unfiltered exception messages
 * from any diagnostic string before storing in state, logs, or COPY DIAG snapshots.
 */
object DiagnosticRedactor {
    private val apiKeyRegex = Regex("AIza[0-9A-Za-z\\-_]{20,}")
    private val bearerRegex = Regex("(?i)Bearer\\s+[A-Za-z0-9\\-._~+/]+=*")
    private val appCheckRegex = Regex("(?i)(x-firebase-appcheck\\s*:\\s*)([^\\s,;]+)")
    private val appCheckParamRegex = Regex("(?i)(app[_-]?check[_-]?token|attestation[_-]?token)\\s*[:=]\\s*[\"']?([^\\s\"',;}]+)[\"']?")
    private val queryTokenRegex = Regex("(?i)(access_token|api_key|apikey|key|token|secret|password|credential)\\s*[:=]\\s*[\"']?([^&\\s\"',;}]+)[\"']?")
    private val jwtRegex = Regex("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{3,}")
    private val resumptionKeyValRegex = Regex("(?i)(resumption[_-]?handle|session[_-]?resumption[_-]?handle|session[_-]?handle|resume[_-]?token|resumption[_-]?token)\\s*[:=]\\s*[\"']?([^\\s\"',;}]+)[\"']?")
    private val resumptionTokenLiteralRegex = Regex("(?i)\\b(resumption_handle_[A-Za-z0-9_\\-.]+|resume_handle_[A-Za-z0-9_\\-.]+|sess_resume_[A-Za-z0-9_\\-.]+)\\b")
    private val emailRegex = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val phoneRegex = Regex("(?<!\\d)(?:\\+\\d{1,3}[\\s.-]?)?(?:\\(\\d{3}\\)|\\d{3})[\\s.-]\\d{3}[\\s.-]\\d{4}(?!\\d)|(?<!\\d)\\+\\d{1,3}[\\s.-]?\\d{2,4}[\\s.-]?\\d{3,4}[\\s.-]?\\d{3,4}(?!\\d)")
    private val spokenTranscriptLabelRegex = Regex("(?i)(Heard|Gemini|User|Assistant|inputTranscript|outputTranscript|transcript)\\s*[:=]\\s*\"[^\"]*\"")
    private val rawPcmOrBase64AudioRegex = Regex("(?i)(pcmBytes|rawAudio|audioBase64|inlineData)\\s*[:=]\\s*\\[?[^\\],;}]+\\]?")
    private val unfilteredExceptionRegex = Regex("([a-zA-Z0-9_]+\\.)+[A-Za-z0-9_$]*(Exception|Error|Throwable)\\s*:\\s*[^\\n\\r;|]+")
    private val stackTraceLineRegex = Regex("(?m)^\\s*at\\s+[A-Za-z0-9_$.]+\\([^)]*\\).*$")

    fun redact(raw: String?): String {
        if (raw.isNullOrBlank()) return "NONE"
        return raw
            .replace(apiKeyRegex, "[REDACTED_API_KEY]")
            .replace(appCheckRegex, "$1[REDACTED_APP_CHECK_TOKEN]")
            .replace(appCheckParamRegex, "$1=[REDACTED_APP_CHECK_TOKEN]")
            .replace(resumptionKeyValRegex, "$1=[REDACTED_RESUMPTION_HANDLE]")
            .replace(resumptionTokenLiteralRegex, "[REDACTED_RESUMPTION_HANDLE]")
            .replace(bearerRegex, "Bearer [REDACTED_BEARER_TOKEN]")
            .replace(queryTokenRegex, "$1=[REDACTED_API_KEY]")
            .replace(jwtRegex, "[REDACTED_BEARER_TOKEN]")
            .replace(emailRegex, "[REDACTED_CONTACT_EMAIL]")
            .replace(phoneRegex, "[REDACTED_CONTACT_PHONE]")
            .take(320)
    }

    /**
     * Strict diagnostic redaction for COPY DIAG snapshots and error history entries:
     * excludes transcripts, raw audio dumps, credentials, App Check tokens, resumption handles,
     * contact information, and unfiltered exception messages/stack traces.
     */
    fun redactForDiagnostics(raw: String?): String {
        if (raw.isNullOrBlank()) return "NONE"
        val base = redact(raw)
        return base
            .replace(spokenTranscriptLabelRegex, "$1=[REDACTED_TRANSCRIPT]")
            .replace(rawPcmOrBase64AudioRegex, "$1=[REDACTED_RAW_AUDIO]")
            .replace(unfilteredExceptionRegex, "[REDACTED_EXCEPTION_DETAIL]")
            .replace(stackTraceLineRegex, "[REDACTED_STACK_FRAME]")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "NONE" }
            .take(280)
    }
}

/**
 * Socket-bound callback events emitted by the underlying Live transport driver.
 * Every event MUST carry its [generationId] so stale callbacks from superseded sockets are ignored.
 */
sealed interface LiveSocketCallbackEvent {
    val generationId: LiveSessionGenerationId

    data class SetupAccepted(
        override val generationId: LiveSessionGenerationId,
        val serverNote: String = "BidiGenerateContent setup accepted by server",
        val resumedWithServerHandle: Boolean = false,
        val handleRejectedFallbackUsed: Boolean = false
    ) : LiveSocketCallbackEvent

    data class HandshakeFailed(
        override val generationId: LiveSessionGenerationId,
        val classification: LiveFailureClassification,
        val rawErrorMessage: String
    ) : LiveSocketCallbackEvent

    data class SocketClosedRemotely(
        override val generationId: LiveSessionGenerationId,
        val reason: String
    ) : LiveSocketCallbackEvent

    data class ServerGoAwayReceived(
        override val generationId: LiveSessionGenerationId,
        val timeLeftMs: Long?,
        val reason: String
    ) : LiveSocketCallbackEvent

    /**
     * Redacted notification that the server sent a [com.google.firebase.ai.type.LiveSessionResumptionUpdate].
     * Never carries the raw handle string outside the transport driver's private session store.
     */
    data class SessionResumptionUpdated(
        override val generationId: LiveSessionGenerationId,
        val resumable: Boolean,
        val hasScopedHandle: Boolean,
        val lastConsumedClientMessageIndex: Int? = null
    ) : LiveSocketCallbackEvent

    data class ServerAudioChunkReceived(
        override val generationId: LiveSessionGenerationId,
        val mimeType: String?,
        val pcmBytes: ByteArray
    ) : LiveSocketCallbackEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ServerAudioChunkReceived) return false
            return generationId == other.generationId &&
                mimeType == other.mimeType &&
                pcmBytes.contentEquals(other.pcmBytes)
        }

        override fun hashCode(): Int {
            var result = generationId.hashCode()
            result = 31 * result + (mimeType?.hashCode() ?: 0)
            result = 31 * result + pcmBytes.contentHashCode()
            return result
        }
    }

    data class ServerTurnCompleted(
        override val generationId: LiveSessionGenerationId,
        val interrupted: Boolean = false
    ) : LiveSocketCallbackEvent

    data class ServerTranscriptionReceived(
        override val generationId: LiveSessionGenerationId,
        val inputTranscript: String?,
        val outputTranscript: String?
    ) : LiveSocketCallbackEvent

    data class LiveToolCallRequest(
        val id: String?,
        val name: String,
        val queryArg: String
    )

    data class ServerToolCallReceived(
        override val generationId: LiveSessionGenerationId,
        val calls: List<LiveToolCallRequest>
    ) : LiveSocketCallbackEvent

    data class ServerToolCallCancelled(
        override val generationId: LiveSessionGenerationId,
        val functionIds: List<String>
    ) : LiveSocketCallbackEvent
}

/**
 * Redacted, user-visible and test-verifiable configuration & session diagnostics for Phase 03–07C.
 */
data class RedactedLiveDiagnostics(
    val modelId: String = LiveConfigConstants.SUPPORTED_LIVE_MODEL_ID,
    val apiVersion: String = LiveConfigConstants.LIVE_API_VERSION,
    val endpointFamily: String = LiveConfigConstants.ENDPOINT_FAMILY.wireCode,
    val authRoute: String = LiveConfigConstants.AUTH_ROUTE.wireCode,
    val sessionGenerationWireId: String = "NONE",
    val phaseWireCode: String = LiveConnectionPhase.DISCONNECTED.wireCode,
    val failureCode: String = LiveFailureClassification.NONE.wireCode,
    val isInternetValidated: Boolean = true,
    val attemptCount: Int = 0,
    val retryCount: Int = 0,
    val autoReconnectCount: Int = 0,
    val lastBackoffDelayMs: Long = 0L,
    val staleCallbacksDropped: Int = 0,
    val handshakeVerified: Boolean = false,
    val isReceiverCollectorAttached: Boolean = false,
    val audioPlaybackClaimed: Boolean = false,
    val registeredActionToolsCount: Int = LiveConfigConstants.REGISTERED_ACTION_TOOLS_COUNT,
    val registeredRetrievalToolsCount: Int = LiveConfigConstants.REGISTERED_RETRIEVAL_TOOLS_COUNT,
    val retrievalModelId: String = LiveConfigConstants.RETRIEVAL_MODEL_ID,
    val contextWindowCompressionEnabled: Boolean = true,
    val sessionResumptionEnabled: Boolean = true,
    val sessionResumptionStatus: String = SessionResumptionStatus.ENABLED_AWAITING_HANDLE.wireCode,
    val contextRestorationStatus: String = ContextRestorationStatus.INITIAL_NEW_SESSION.wireCode,
    val hasScopedResumptionHandle: Boolean = false,
    val sessionResumptionUpdatesReceived: Int = 0,
    val sessionResumptionSuccessCount: Int = 0,
    val sessionResumptionFallbackCount: Int = 0,
    val serverGoAwayCount: Int = 0,
    val lastServerGoAwayTimeLeftMs: Long? = null,
    val goAwayNoticePendingHandover: Boolean = false,
    val lastGoAwayGenerationWireId: String = "NONE",
    val actualSocketCloseCount: Int = 0,
    val lastActualSocketCloseGenerationWireId: String = "NONE",
    val lastActualSocketCloseClassification: String = "NONE",
    val lastActualSocketCloseReason: String = "NONE",
    val systemInstruction: String = LiveConfigConstants.AUTHORITATIVE_SYSTEM_INSTRUCTION,
    val redactedSummary: String = "Live adapter initialized. No credentials in APK/logs. Audio playback not claimed in Phase 03."
)

/**
 * Immutable state snapshot of the single [GeminiLiveConnectionAdapter].
 */
data class LiveConnectionState(
    val phase: LiveConnectionPhase = LiveConnectionPhase.DISCONNECTED,
    val activeGeneration: LiveSessionGenerationId? = null,
    val lastCompletedGeneration: LiveSessionGenerationId? = null,
    val configuredModelId: String = LiveConfigConstants.SUPPORTED_LIVE_MODEL_ID,
    val systemInstruction: String = LiveConfigConstants.AUTHORITATIVE_SYSTEM_INSTRUCTION,
    val failureClassification: LiveFailureClassification = LiveFailureClassification.NONE,
    val isInternetValidated: Boolean = true,
    val attemptCount: Int = 0,
    val retryCount: Int = 0,
    val autoReconnectCount: Int = 0,
    val lastBackoffDelayMs: Long = 0L,
    val staleSocketCallbacksDropped: Int = 0,
    val handshakeVerified: Boolean = false,
    val isReceiverCollectorAttached: Boolean = false,
    val audioPlaybackClaimed: Boolean = false,
    val registeredActionToolsCount: BooleanOrIntCount = 0,
    val registeredRetrievalToolsCount: BooleanOrIntCount = LiveConfigConstants.REGISTERED_RETRIEVAL_TOOLS_COUNT,
    val retrievalModelId: String = LiveConfigConstants.RETRIEVAL_MODEL_ID,
    val contextWindowCompressionEnabled: Boolean = true,
    val sessionResumptionEnabled: Boolean = true,
    val sessionResumptionStatus: SessionResumptionStatus = SessionResumptionStatus.ENABLED_AWAITING_HANDLE,
    val contextRestorationStatus: ContextRestorationStatus = ContextRestorationStatus.INITIAL_NEW_SESSION,
    val hasScopedResumptionHandle: Boolean = false,
    val sessionResumptionUpdatesReceived: Int = 0,
    val sessionResumptionSuccessCount: Int = 0,
    val sessionResumptionFallbackCount: Int = 0,
    val serverGoAwayCount: Int = 0,
    val lastServerGoAwayTimeLeftMs: Long? = null,
    val goAwayNoticePendingHandover: Boolean = false,
    val lastGoAwayGenerationWireId: String = "NONE",
    val actualSocketCloseCount: Int = 0,
    val lastActualSocketCloseGenerationWireId: String = "NONE",
    val lastActualSocketCloseClassification: String = "NONE",
    val lastActualSocketCloseReason: String = "NONE",
    val autoRetryStoppedReason: String? = null,
    val statusHeadline: String = "LIVE ADAPTER READY (DISCONNECTED)",
    val statusDetail: String = "Ready to verify ${LiveConfigConstants.SUPPORTED_LIVE_MODEL_ID} handshake via Firebase AI Logic (${LiveConfigConstants.LIVE_API_VERSION}).",
    val diagnostics: RedactedLiveDiagnostics = RedactedLiveDiagnostics()
)

private typealias BooleanOrIntCount = Int

