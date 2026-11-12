package com.example.live

import com.example.BuildConfig
import com.example.contract.SessionId
import kotlin.math.min
import kotlin.math.pow

enum class LiveEndpointFamily(val wireCode: String) {
    FIREBASE_AI_LOGIC_GOOGLE_AI_V1BETA_BIDI("FIREBASE_AI_LOGIC_GOOGLE_AI_V1BETA_BIDI")
}

enum class LiveAuthRoute(val wireCode: String) {
    FIREBASE_AI_LOGIC_MANAGED_APP_CHECK("FIREBASE_AI_LOGIC_MANAGED_APP_CHECK")
}

/**
 * Canonical Phase 03 Gemini Live configuration constants.
 * Note: Names shown in AI Studio's coding-model picker (e.g. models/gemini-4-argon)
 * are coding agent identifiers, NOT Gemini Live BidiGenerateContent runtime model IDs.
 */
object LiveConfigConstants {
    /** Official documented Gemini 3.x Live native-audio model ID for Firebase AI Logic (Gemini Developer API). */
    const val SUPPORTED_LIVE_MODEL_ID: String = BuildConfig.LIVE_MODEL_ID

    /** Documented fallback-eligible 2.5 native-audio model ID (only selectable explicitly; never switched silently). */
    const val SECONDARY_DOCUMENTED_LIVE_MODEL_ID: String = "gemini-2.5-flash-native-audio-preview-12-2025"

    const val LIVE_API_VERSION: String = "v1beta"
    val ENDPOINT_FAMILY: LiveEndpointFamily = LiveEndpointFamily.FIREBASE_AI_LOGIC_GOOGLE_AI_V1BETA_BIDI
    val AUTH_ROUTE: LiveAuthRoute = LiveAuthRoute.FIREBASE_AI_LOGIC_MANAGED_APP_CHECK
    const val REGISTERED_ACTION_TOOLS_COUNT: Int = 0
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
        userSummary = "Live handshake verified & BidiGenerateContent setup accepted. Audio playback deferred (Phase 03)."
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
    CANCELLED_BY_STOP_OR_PAUSE(
        wireCode = "CANCELLED_BY_STOP_OR_PAUSE",
        retryable = false,
        guidance = "Connection halted immediately by operator Stop/Pause or lifecycle release."
    )
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
 * Redacts sensitive tokens, API keys, bearer tokens, and App Check headers from any diagnostic
 * or exception string before storing in state or logs.
 */
object DiagnosticRedactor {
    private val apiKeyRegex = Regex("AIza[0-9A-Za-z\\-_]{20,}")
    private val bearerRegex = Regex("(?i)Bearer\\s+[A-Za-z0-9\\-._~+/]+=*")
    private val appCheckRegex = Regex("(?i)(x-firebase-appcheck\\s*:\\s*)([^\\s,;]+)")
    private val queryTokenRegex = Regex("(?i)(access_token|key|token|secret)=([^&\\s]+)")
    private val jwtRegex = Regex("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{3,}")

    fun redact(raw: String?): String {
        if (raw.isNullOrBlank()) return "NONE"
        return raw
            .replace(apiKeyRegex, "[REDACTED_API_KEY]")
            .replace(appCheckRegex, "$1[REDACTED_APP_CHECK_TOKEN]")
            .replace(bearerRegex, "Bearer [REDACTED_BEARER_TOKEN]")
            .replace(queryTokenRegex, "$1=[REDACTED_API_KEY]")
            .replace(jwtRegex, "[REDACTED_BEARER_TOKEN]")
            .take(320)
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
        val serverNote: String = "BidiGenerateContent setup accepted by server"
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
}

/**
 * Redacted, user-visible and test-verifiable configuration & session diagnostics for Phase 03.
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
    val audioPlaybackClaimed: Boolean = false,
    val registeredActionToolsCount: Int = LiveConfigConstants.REGISTERED_ACTION_TOOLS_COUNT,
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
    val failureClassification: LiveFailureClassification = LiveFailureClassification.NONE,
    val isInternetValidated: Boolean = true,
    val attemptCount: Int = 0,
    val retryCount: Int = 0,
    val autoReconnectCount: Int = 0,
    val lastBackoffDelayMs: Long = 0L,
    val staleSocketCallbacksDropped: Int = 0,
    val handshakeVerified: Boolean = false,
    val audioPlaybackClaimed: Boolean = false,
    val registeredActionToolsCount: BooleanOrIntCount = 0,
    val autoRetryStoppedReason: String? = null,
    val statusHeadline: String = "LIVE ADAPTER READY (DISCONNECTED)",
    val statusDetail: String = "Ready to verify ${LiveConfigConstants.SUPPORTED_LIVE_MODEL_ID} handshake via Firebase AI Logic (${LiveConfigConstants.LIVE_API_VERSION}).",
    val diagnostics: RedactedLiveDiagnostics = RedactedLiveDiagnostics()
)

private typealias BooleanOrIntCount = Int
