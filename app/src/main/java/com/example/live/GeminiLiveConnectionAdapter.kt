package com.example.live

import com.example.contract.SessionId
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.ActivityDetectionConfig
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InlineData
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.LiveServerContent
import com.google.firebase.ai.type.LiveServerGoAway
import com.google.firebase.ai.type.LiveServerSetupComplete
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.RealtimeInputConfig
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.TextPart
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.ClosedChannelException
import java.nio.channels.UnresolvedAddressException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * Typed outcome of a single Gemini Live socket handshake attempt.
 */
sealed interface LiveHandshakeResult {
    data class Accepted(
        val generationId: LiveSessionGenerationId,
        val serverNote: String
    ) : LiveHandshakeResult

    data class Failed(
        val generationId: LiveSessionGenerationId,
        val classification: LiveFailureClassification,
        val rawDetail: String
    ) : LiveHandshakeResult
}

/**
 * Transport driver interface for the single [GeminiLiveConnectionAdapter].
 * Allows deterministic unit/Robolectric verification of handshake, bounded backoff,
 * non-retryable failure classifications, offline gating, and stale socket callback rejection
 * while binding to the real [FirebaseAiLiveTransportDriver] in production.
 */
interface LiveTransportDriver {
    suspend fun connectAndHandshake(
        generationId: LiveSessionGenerationId,
        modelId: String,
        onSocketEvent: (LiveSocketCallbackEvent) -> Unit,
        systemInstruction: String = LiveConfigConstants.AUTHORITATIVE_SYSTEM_INSTRUCTION
    ): LiveHandshakeResult

    suspend fun sendStartOfSpokenTurn(
        generationId: LiveSessionGenerationId
    ): Boolean = true

    suspend fun sendRealtimeAudioChunk(
        generationId: LiveSessionGenerationId,
        pcm16kHzBytes: ByteArray,
        mimeType: String = "audio/pcm;rate=16000"
    ): Boolean = true

    suspend fun sendEndOfSpokenTurn(
        generationId: LiveSessionGenerationId
    ): Boolean = true

    fun isReceiverCollectorAttached(
        generationId: LiveSessionGenerationId
    ): Boolean = true

    fun closeSocket(generationId: LiveSessionGenerationId?)

    suspend fun closeAndAwaitSocket(generationId: LiveSessionGenerationId?) {
        closeSocket(generationId)
    }
}

/**
 * Classifies exceptions thrown during Gemini Live WebSocket setup or session negotiation
 * by inspecting the entire exception and cause chain.
 *
 * Enforces Phase 03 error classification rules:
 * - No internet, DNS failure, timeout, socket/IO interruption (anywhere in the cause chain,
 *   even when wrapped inside an App Check or Firebase exception) -> [LiveFailureClassification.NETWORK_ERROR]
 * - Actual HTTP 401/403 or explicit App Check/auth rejection while internet is validated -> [LiveFailureClassification.BAD_CREDENTIALS_OR_APP_CHECK] (no retry)
 * - HTTP 400/404 or unsupported model/endpoint -> [LiveFailureClassification.UNSUPPORTED_MODEL_OR_ENDPOINT] (no retry, no silent model switch)
 * - HTTP 429 / resource exhausted / quota -> [LiveFailureClassification.QUOTA_EXHAUSTED] (no retry)
 * - Cooperative coroutine cancellation -> [LiveFailureClassification.CANCELLED_BY_STOP_OR_PAUSE] (no retry)
 * - Never classifies every exception as authentication failure; never logs tokens or secrets.
 */
object LiveExceptionClassifier {

    fun classify(
        throwable: Throwable,
        isInternetValidated: Boolean = true
    ): LiveFailureClassification {
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val allNodes = ArrayList<Throwable>(12)
        val queue = ArrayDeque<Throwable>()
        queue.addLast(throwable)
        while (queue.isNotEmpty() && allNodes.size < 16) {
            val node = queue.removeFirst()
            if (!visited.add(node)) continue
            allNodes.add(node)
            node.cause?.let { queue.addLast(it) }
            node.suppressedExceptions.forEach { queue.addLast(it) }
        }

        // 1. Cooperative cancellation (excluding TimeoutCancellationException which is a network/handshake timeout)
        if (allNodes.any { it is CancellationException && it !is TimeoutCancellationException }) {
            return LiveFailureClassification.CANCELLED_BY_STOP_OR_PAUSE
        }

        // 2. If validated internet is not available, any transport/SDK error is a network error, never auth/credentials
        if (!isInternetValidated) {
            return LiveFailureClassification.NETWORK_ERROR
        }

        // 3. Hard transport/DNS/timeout/socket errors anywhere in the chain MUST remain NETWORK_ERROR
        // (even if wrapped inside Firebase/AppCheck exceptions like AppCheck -> UnknownHostException).
        if (allNodes.any { isDirectDnsTimeoutOrConnectFailure(it) }) {
            return LiveFailureClassification.NETWORK_ERROR
        }

        val combinedLower = allNodes.joinToString(" | ") { node ->
            "${node::class.simpleName ?: "Exception"}: ${node.message ?: ""}"
        }.lowercase()

        // 4. Explicit HTTP 401/403 or App Check / credential rejection while internet is validated
        if (hasAuthOrAppCheckIndicator(combinedLower)) {
            return LiveFailureClassification.BAD_CREDENTIALS_OR_APP_CHECK
        }

        // 5. Explicit HTTP 429 or quota exhaustion / WebSocket try-again-later (1013)
        if (hasQuotaIndicator(combinedLower)) {
            return LiveFailureClassification.QUOTA_EXHAUSTED
        }

        // 6. Explicit HTTP 400/404, unsupported model/endpoint, or WebSocket protocol error (1002/1003/1008)
        if (hasUnsupportedModelOrProtocolIndicator(combinedLower)) {
            return LiveFailureClassification.UNSUPPORTED_MODEL_OR_ENDPOINT
        }

        // 7. Remaining validated-network socket/channel/IO interruptions (e.g. ClosedReceiveChannelException, EOF, 502/503/504)
        if (allNodes.any { isNetworkOrIoThrowable(it) }) {
            return LiveFailureClassification.NETWORK_ERROR
        }

        // 8. Default unrecognized runtime transport failures to NETWORK_ERROR (never default to auth failure)
        return LiveFailureClassification.NETWORK_ERROR
    }

    fun classifyCloseReason(
        reason: String,
        isInternetValidated: Boolean = true
    ): LiveFailureClassification {
        if (!isInternetValidated) {
            return LiveFailureClassification.NETWORK_ERROR
        }
        val lower = reason.lowercase()
        return when {
            lower.contains("cancel") || lower.contains("operator_stop") || lower.contains("operator_pause") ->
                LiveFailureClassification.CANCELLED_BY_STOP_OR_PAUSE
            hasAuthOrAppCheckIndicator(lower) ->
                LiveFailureClassification.BAD_CREDENTIALS_OR_APP_CHECK
            hasQuotaIndicator(lower) ->
                LiveFailureClassification.QUOTA_EXHAUSTED
            hasUnsupportedModelOrProtocolIndicator(lower) ->
                LiveFailureClassification.UNSUPPORTED_MODEL_OR_ENDPOINT
            else ->
                LiveFailureClassification.NETWORK_ERROR
        }
    }

    private fun hasAuthOrAppCheckIndicator(text: String): Boolean {
        return hasHttpStatusCode(text, 401) ||
            hasHttpStatusCode(text, 403) ||
            text.contains("unauthenticated") ||
            text.contains("permission_denied") ||
            text.contains("permission denied") ||
            text.contains("api_key_invalid") ||
            text.contains("invalid api key") ||
            text.contains("bad_credentials") ||
            text.contains("firebaseapp is not initialized") ||
            text.contains("blocked_prerequisite_unconfigured") ||
            text.contains("needs_setup") ||
            text.contains("app check") ||
            text.contains("appcheck") ||
            text.contains("attestation")
    }

    private fun hasQuotaIndicator(text: String): Boolean {
        return hasHttpStatusCode(text, 429) ||
            hasWebSocketCloseCode(text, 1013) ||
            text.contains("resource_exhausted") ||
            text.contains("quota") ||
            text.contains("rate limit") ||
            text.contains("too many requests")
    }

    private fun hasUnsupportedModelOrProtocolIndicator(text: String): Boolean {
        return hasHttpStatusCode(text, 404) ||
            hasHttpStatusCode(text, 400) ||
            hasWebSocketCloseCode(text, 1002) ||
            hasWebSocketCloseCode(text, 1003) ||
            hasWebSocketCloseCode(text, 1008) ||
            text.contains("not_found") ||
            text.contains("not found") ||
            text.contains("unsupported model") ||
            text.contains("model is not supported") ||
            text.contains("is not found for api version") ||
            text.contains("invalid_argument") ||
            text.contains("protocol error") ||
            text.contains("unsupported protocol") ||
            text.contains("policy violation")
    }

    private fun isDirectDnsTimeoutOrConnectFailure(node: Throwable): Boolean {
        if (node is TimeoutCancellationException ||
            node is UnknownHostException ||
            node is UnresolvedAddressException ||
            node is SocketTimeoutException ||
            node is ConnectException ||
            node is NoRouteToHostException ||
            node is SSLException
        ) {
            return true
        }
        val classAndMsg = "${node::class.simpleName ?: ""} ${node.message ?: ""}".lowercase()
        return classAndMsg.contains("unknownhost") ||
            classAndMsg.contains("unresolvedaddress") ||
            classAndMsg.contains("unable to resolve host") ||
            classAndMsg.contains("no address associated with hostname") ||
            classAndMsg.contains("eai_nodata") ||
            classAndMsg.contains("enetunreach") ||
            classAndMsg.contains("ehostunreach") ||
            classAndMsg.contains("network is unreachable") ||
            classAndMsg.contains("httprequesttimeout") ||
            classAndMsg.contains("connecttimeout") ||
            classAndMsg.contains("sockettimeout") ||
            classAndMsg.contains("deadline exceeded") ||
            classAndMsg.contains("connection refused")
    }

    private fun isNetworkOrIoThrowable(node: Throwable): Boolean {
        if (isDirectDnsTimeoutOrConnectFailure(node) ||
            node is SocketException ||
            node is EOFException ||
            node is InterruptedIOException ||
            node is ClosedChannelException
        ) {
            return true
        }

        val classAndMsg = "${node::class.simpleName ?: ""} ${node.message ?: ""}".lowercase()

        if (classAndMsg.contains("network_error") ||
            classAndMsg.contains("network error") ||
            classAndMsg.contains("network_unavailable") ||
            classAndMsg.contains("no internet") ||
            classAndMsg.contains("offline") ||
            classAndMsg.contains("timed out") ||
            classAndMsg.contains("timeout") ||
            classAndMsg.contains("connection reset") ||
            classAndMsg.contains("connection abort") ||
            classAndMsg.contains("software caused connection abort") ||
            classAndMsg.contains("broken pipe") ||
            classAndMsg.contains("socket closed") ||
            classAndMsg.contains("closedreceivechannel") ||
            classAndMsg.contains("closedsendchannel") ||
            classAndMsg.contains("unexpected end of stream") ||
            classAndMsg.contains("websocket eof") ||
            hasWebSocketCloseCode(classAndMsg, 1006) ||
            hasWebSocketCloseCode(classAndMsg, 1011) ||
            hasHttpStatusCode(classAndMsg, 502) ||
            hasHttpStatusCode(classAndMsg, 503) ||
            hasHttpStatusCode(classAndMsg, 504)
        ) {
            return true
        }

        // Generic IOException is a network/IO failure unless it explicitly carries an HTTP 401/403/400/404/429 code
        if (node is IOException) {
            val hasExplicitNonNetworkHttp =
                hasHttpStatusCode(classAndMsg, 401) ||
                    hasHttpStatusCode(classAndMsg, 403) ||
                    hasHttpStatusCode(classAndMsg, 400) ||
                    hasHttpStatusCode(classAndMsg, 404) ||
                    hasHttpStatusCode(classAndMsg, 429)
            if (!hasExplicitNonNetworkHttp) {
                return true
            }
        }

        return false
    }

    private fun hasHttpStatusCode(text: String, code: Int): Boolean {
        val pattern = Regex(
            """(?i)(?:\bhttp(?:/\d(?:\.\d)?)?\s+$code\b|\b(?:status|code|error|response)(?:\s+code)?[\s:=]+$code\b|\bfailed\s+with\s+$code\b|\bwebsocket\s+$code\b|\b$code\s+(?:unauthorized|forbidden|bad\s+request|not\s+found|too\s+many\s+requests|bad\s+gateway|service\s+unavailable|gateway\s+timeout)\b|[\(\[]$code[\)\]])"""
        )
        return pattern.containsMatchIn(text)
    }

    private fun hasWebSocketCloseCode(text: String, code: Int): Boolean {
        val pattern = Regex(
            """(?i)(?:\bclose(?:\s+code|\s+reason)?[\s:=]+$code\b|\bwebsocket[\s:=]+$code\b|\bcode[\s:=]+$code\b|[\(\[]$code[\)\]])"""
        )
        return pattern.containsMatchIn(text)
    }

    /**
     * Formats a redacted summary of the exception and its cause chain without exposing tokens or secrets.
     */
    fun formatRedactedChain(throwable: Throwable): String {
        val rawChain = generateSequence(throwable) { it.cause }
            .take(6)
            .joinToString(" -> ") { node ->
                "${node::class.simpleName ?: "Exception"}: ${node.message ?: "no detail"}"
            }
        return DiagnosticRedactor.redact(rawChain)
    }
}

/**
 * Production [LiveTransportDriver] backed by official Android Firebase AI Logic
 * (`Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(...)`).
 *
 * Phase 03 Invariants:
 * - Verifies validated internet (`NET_CAPABILITY_INTERNET` + `NET_CAPABILITY_VALIDATED`) before
 *   calling Firebase AI Logic or requesting an App Check token.
 * - Uses managed credentials via Firebase AI Logic + Firebase App Check (zero long-lived API keys).
 * - Targets `gemini-3.1-flash-live-preview` over `v1beta` BidiGenerateContent WebSocket.
 * - Registers zero action tools (`tools = emptyList()`).
 * - Verifies WebSocket connect + setup handshake (`liveModel.connect()`) WITHOUT invoking
 *   `startAudioConversation()`, preserving the single `AudioCapture` owner and not claiming
 *   audio playback in Phase 03.
 */
@OptIn(PublicPreviewAPI::class)
class FirebaseAiLiveTransportDriver(
    private val networkMonitor: LiveNetworkMonitor? = null,
    private val handshakeTimeoutMs: Long = 25_000L,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : LiveTransportDriver {

    private sealed interface CollectorAttachOutcome {
        data object Attached : CollectorAttachOutcome
        data class Failed(val throwable: Throwable) : CollectorAttachOutcome
    }

    private val lock = Any()
    private val driverLifecycleMutex = Mutex()
    private var activeGenerationId: LiveSessionGenerationId? = null
    private var receiverCollectorAttachedGen: LiveSessionGenerationId? = null
    private var activeLiveSession: com.google.firebase.ai.type.LiveSession? = null
    private var activeReceiveJob: Job? = null
    private val closingGenerations = LinkedHashSet<LiveSessionGenerationId>()
    private val closedSessions = Collections.newSetFromMap(IdentityHashMap<com.google.firebase.ai.type.LiveSession, Boolean>())
    private val driverScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private fun logDriver(message: String) {
        try {
            android.util.Log.i("OmniVoiceLive", DiagnosticRedactor.redact(message))
        } catch (_: Throwable) {
        }
    }

    private suspend fun closeSessionOnce(session: com.google.firebase.ai.type.LiveSession?) {
        if (session == null) return
        val shouldClose = synchronized(lock) {
            closedSessions.add(session)
        }
        if (!shouldClose) return
        try {
            session.stopReceiving()
        } catch (_: Throwable) {
        }
        try {
            session.close()
        } catch (_: Throwable) {
        }
    }

    private fun markGenerationClosingLocked(genId: LiveSessionGenerationId?) {
        if (genId == null) return
        if (closingGenerations.size >= 64) {
            val first = closingGenerations.iterator().next()
            closingGenerations.remove(first)
        }
        closingGenerations.add(genId)
    }

    override fun isReceiverCollectorAttached(generationId: LiveSessionGenerationId): Boolean {
        return synchronized(lock) {
            activeGenerationId == generationId && receiverCollectorAttachedGen == generationId
        }
    }

    override suspend fun closeAndAwaitSocket(generationId: LiveSessionGenerationId?) {
        val sessionToClose: com.google.firebase.ai.type.LiveSession?
        val jobToCancel: Job?
        synchronized(lock) {
            if (generationId != null && activeGenerationId != null && activeGenerationId != generationId) {
                markGenerationClosingLocked(generationId)
                return
            }
            markGenerationClosingLocked(generationId ?: activeGenerationId)
            sessionToClose = activeLiveSession
            jobToCancel = activeReceiveJob
            activeGenerationId = null
            receiverCollectorAttachedGen = null
            activeLiveSession = null
            activeReceiveJob = null
        }
        if (jobToCancel == null && sessionToClose == null) {
            return
        }
        withContext(ioDispatcher + NonCancellable) {
            jobToCancel?.cancel()
            try {
                jobToCancel?.join()
            } catch (_: Throwable) {
            }
            closeSessionOnce(sessionToClose)
        }
    }

    override suspend fun connectAndHandshake(
        generationId: LiveSessionGenerationId,
        modelId: String,
        onSocketEvent: (LiveSocketCallbackEvent) -> Unit,
        systemInstruction: String
    ): LiveHandshakeResult {
        return driverLifecycleMutex.withLock {
            // Invalidate, cancel/join old collector, and close any prior LiveSession once off-main FIRST.
            closeAndAwaitSocket(null)

            // Requirement 4 & 5: If no validated network exists, do NOT call Firebase AI Logic or request App Check token.
            if (networkMonitor != null && !networkMonitor.isInternetValidated()) {
                return@withLock LiveHandshakeResult.Failed(
                    generationId = generationId,
                    classification = LiveFailureClassification.NETWORK_UNAVAILABLE,
                    rawDetail = "NETWORK_UNAVAILABLE: Active network lacks NET_CAPABILITY_INTERNET and NET_CAPABILITY_VALIDATED. Firebase AI Logic and App Check were not called."
                )
            }

            try {
                // Verify prerequisite before attempting network handshake: never fake success when
                // FirebaseApp is uninitialized in JVM tests or unconfigured builds.
                val initializedApps = try {
                    com.google.firebase.FirebaseApp.getApps(
                        com.google.firebase.FirebaseApp.getInstance().applicationContext
                    )
                } catch (_: Throwable) {
                    emptyList()
                }
                if (initializedApps.isEmpty()) {
                    return@withLock LiveHandshakeResult.Failed(
                        generationId = generationId,
                        classification = LiveFailureClassification.BAD_CREDENTIALS_OR_APP_CHECK,
                        rawDetail = "NEEDS_SETUP / CONFIG_ERROR (BLOCKED_PREREQUISITE_UNCONFIGURED): FirebaseApp is not initialized. See docs/token-broker-setup.md."
                    )
                }

                val liveConfig = liveGenerationConfig {
                    responseModality = ResponseModality.AUDIO
                    inputAudioTranscription = AudioTranscriptionConfig()
                    outputAudioTranscription = AudioTranscriptionConfig()
                    realtimeInputConfig = RealtimeInputConfig.builder()
                        .setActivityHandling(RealtimeInputConfig.ActivityHandling.NO_INTERRUPT)
                        .setAutomaticActivityDetection(ActivityDetectionConfig.disabled())
                        .build()
                }
                val sysInstructionContent = content {
                    text(systemInstruction)
                }
                val liveModel = Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
                    modelName = modelId,
                    generationConfig = liveConfig,
                    tools = emptyList(), // Phase 03/04/05: zero action tools registered
                    systemInstruction = sysInstructionContent
                )

                val session = withContext(ioDispatcher) {
                    withTimeout(handshakeTimeoutMs) {
                        liveModel.connect()
                    }
                }

                val collectorAttach = CompletableDeferred<CollectorAttachOutcome>()
                val immediateFailure = AtomicReference<Throwable?>(null)
                var handshakeReturnedAccepted = false

                val receiveJob: Job
                synchronized(lock) {
                    activeGenerationId = generationId
                    receiverCollectorAttachedGen = null
                    activeLiveSession = session
                    receiveJob = driverScope.launch {
                        session.receive()
                            .onStart {
                                val attached = synchronized(lock) {
                                    if (activeGenerationId == generationId && !closingGenerations.contains(generationId)) {
                                        receiverCollectorAttachedGen = generationId
                                        true
                                    } else {
                                        false
                                    }
                                }
                                if (attached) {
                                    collectorAttach.complete(CollectorAttachOutcome.Attached)
                                } else {
                                    collectorAttach.complete(
                                        CollectorAttachOutcome.Failed(CancellationException("STALE_CALLBACK_DROPPED: generation superseded before collector start"))
                                    )
                                }
                            }
                            .catch { err ->
                                immediateFailure.compareAndSet(null, err)
                                val isLocalOrStale = synchronized(lock) {
                                    if (activeGenerationId == generationId) {
                                        receiverCollectorAttachedGen = null
                                    }
                                    activeGenerationId != generationId || closingGenerations.contains(generationId)
                                } || (err is CancellationException && err !is TimeoutCancellationException)
                                collectorAttach.complete(CollectorAttachOutcome.Failed(err))
                                if (isLocalOrStale) {
                                    logDriver("STALE_CALLBACK_DROPPED: Ignored closed/cancelled receive channel for ${generationId.wireId}")
                                    return@catch
                                }
                                if (handshakeReturnedAccepted) {
                                    val validatedNow = networkMonitor?.isInternetValidated() ?: true
                                    val classification = LiveExceptionClassifier.classify(
                                        throwable = err,
                                        isInternetValidated = validatedNow
                                    )
                                    onSocketEvent(
                                        LiveSocketCallbackEvent.HandshakeFailed(
                                            generationId = generationId,
                                            classification = classification,
                                            rawErrorMessage = LiveExceptionClassifier.formatRedactedChain(err)
                                        )
                                    )
                                }
                            }
                            .onCompletion { cause ->
                                val isLocalOrStale = synchronized(lock) {
                                    if (activeGenerationId == generationId) {
                                        receiverCollectorAttachedGen = null
                                    }
                                    activeGenerationId != generationId || closingGenerations.contains(generationId)
                                }
                                if (cause == null && immediateFailure.get() == null && !isLocalOrStale && handshakeReturnedAccepted) {
                                    onSocketEvent(
                                        LiveSocketCallbackEvent.SocketClosedRemotely(
                                            generationId = generationId,
                                            reason = "Gemini Live receive stream completed/closed by server"
                                        )
                                    )
                                }
                            }
                            .collect { message ->
                                val stillActive = synchronized(lock) {
                                    activeGenerationId == generationId && !closingGenerations.contains(generationId)
                                }
                                if (!stillActive) {
                                    logDriver("STALE_CALLBACK_DROPPED: Dropped server frame for superseded ${generationId.wireId}")
                                    return@collect
                                }
                                when (message) {
                                    is LiveServerSetupComplete -> {
                                        onSocketEvent(
                                            LiveSocketCallbackEvent.SetupAccepted(
                                                generationId = generationId,
                                                serverNote = "LiveServerSetupComplete frame verified on ${generationId.wireId}"
                                            )
                                        )
                                    }
                                    is LiveServerGoAway -> {
                                        onSocketEvent(
                                            LiveSocketCallbackEvent.SocketClosedRemotely(
                                                generationId = generationId,
                                                reason = "LiveServerGoAway received from Gemini Live endpoint (timeLeft=${message.timeLeft})"
                                            )
                                        )
                                    }
                                    is LiveServerContent -> {
                                        val inTranscript = message.inputTranscription?.text?.takeIf { it.isNotBlank() }
                                        val textParts = message.content?.parts
                                            ?.filterIsInstance<TextPart>()
                                            ?.mapNotNull { it.text.takeIf { t -> t.isNotBlank() } }
                                            ?.joinToString(" ")
                                            ?.takeIf { it.isNotBlank() }
                                        val outTranscript = message.outputTranscription?.text?.takeIf { it.isNotBlank() } ?: textParts
                                        if (inTranscript != null || outTranscript != null) {
                                            onSocketEvent(
                                                LiveSocketCallbackEvent.ServerTranscriptionReceived(
                                                    generationId = generationId,
                                                    inputTranscript = inTranscript,
                                                    outputTranscript = outTranscript
                                                )
                                            )
                                        }

                                        val parts = message.content?.parts.orEmpty()
                                        for (part in parts) {
                                            if (part is InlineDataPart && part.inlineData.isNotEmpty()) {
                                                val lowerMime = part.mimeType.lowercase()
                                                if (lowerMime.startsWith("audio/")) {
                                                    onSocketEvent(
                                                        LiveSocketCallbackEvent.ServerAudioChunkReceived(
                                                            generationId = generationId,
                                                            mimeType = part.mimeType,
                                                            pcmBytes = part.inlineData
                                                        )
                                                    )
                                                }
                                            }
                                        }

                                        if (message.turnComplete || message.interrupted) {
                                            onSocketEvent(
                                                LiveSocketCallbackEvent.ServerTurnCompleted(
                                                    generationId = generationId,
                                                    interrupted = message.interrupted
                                                )
                                            )
                                        }
                                    }
                                    else -> Unit
                                }
                            }
                    }
                    activeReceiveJob = receiveJob
                }

                // Wait for collector attachment or immediate receive channel error
                val attachOutcome = withTimeout(handshakeTimeoutMs) {
                    collectorAttach.await()
                }
                yield()

                val earlyError = (attachOutcome as? CollectorAttachOutcome.Failed)?.throwable
                    ?: immediateFailure.get()
                if (earlyError != null) {
                    closeAndAwaitSocket(generationId)
                    val validatedNow = networkMonitor?.isInternetValidated() ?: true
                    val classification = LiveExceptionClassifier.classify(
                        throwable = earlyError,
                        isInternetValidated = validatedNow
                    )
                    return@withLock LiveHandshakeResult.Failed(
                        generationId = generationId,
                        classification = classification,
                        rawDetail = LiveExceptionClassifier.formatRedactedChain(earlyError)
                    )
                }

                val isStillCurrent = synchronized(lock) {
                    activeGenerationId == generationId &&
                        receiverCollectorAttachedGen == generationId &&
                        !closingGenerations.contains(generationId)
                }
                if (!isStillCurrent) {
                    closeAndAwaitSocket(generationId)
                    return@withLock LiveHandshakeResult.Failed(
                        generationId = generationId,
                        classification = LiveFailureClassification.CANCELLED_BY_STOP_OR_PAUSE,
                        rawDetail = "Superseded or cancelled before SETUP_ACCEPTED (${generationId.wireId})"
                    )
                }

                handshakeReturnedAccepted = true
                val acceptedNote =
                    "SETUP_ACCEPTED via Firebase AI Logic v1beta BidiGenerateContent (${modelId}, tools=0, collector=ATTACHED)"
                onSocketEvent(
                    LiveSocketCallbackEvent.SetupAccepted(
                        generationId = generationId,
                        serverNote = acceptedNote
                    )
                )
                LiveHandshakeResult.Accepted(
                    generationId = generationId,
                    serverNote = acceptedNote
                )
            } catch (t: Throwable) {
                closeAndAwaitSocket(generationId)
                val validatedNow = networkMonitor?.isInternetValidated() ?: true
                val classification = LiveExceptionClassifier.classify(
                    throwable = t,
                    isInternetValidated = validatedNow
                )
                val detail = LiveExceptionClassifier.formatRedactedChain(t)
                LiveHandshakeResult.Failed(
                    generationId = generationId,
                    classification = classification,
                    rawDetail = detail
                )
            }
        }
    }

    override suspend fun sendStartOfSpokenTurn(
        generationId: LiveSessionGenerationId
    ): Boolean {
        if (networkMonitor != null && !networkMonitor.isInternetValidated()) return false
        val session = synchronized(lock) {
            if (activeGenerationId == generationId && receiverCollectorAttachedGen == generationId) {
                activeLiveSession
            } else {
                null
            }
        } ?: return false
        return try {
            withContext(ioDispatcher) {
                session.sendStartActivityRealtime()
            }
            logDriver("USER_ACTIVITY_START (activityStart) sent on ${generationId.wireId}")
            true
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun sendRealtimeAudioChunk(
        generationId: LiveSessionGenerationId,
        pcm16kHzBytes: ByteArray,
        mimeType: String
    ): Boolean {
        if (pcm16kHzBytes.isEmpty()) return true
        if (networkMonitor != null && !networkMonitor.isInternetValidated()) return false
        val session = synchronized(lock) {
            if (activeGenerationId == generationId && receiverCollectorAttachedGen == generationId) {
                activeLiveSession
            } else {
                null
            }
        } ?: return false
        return try {
            withContext(ioDispatcher) {
                session.sendAudioRealtime(InlineData(pcm16kHzBytes, mimeType))
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun sendEndOfSpokenTurn(
        generationId: LiveSessionGenerationId
    ): Boolean {
        if (networkMonitor != null && !networkMonitor.isInternetValidated()) return false
        val session = synchronized(lock) {
            if (activeGenerationId == generationId && receiverCollectorAttachedGen == generationId) {
                activeLiveSession
            } else {
                null
            }
        } ?: return false
        return try {
            withContext(ioDispatcher) {
                session.sendStopActivityRealtime()
            }
            logDriver("USER_ACTIVITY_END (activityEnd) sent on ${generationId.wireId}")
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun closeSocket(generationId: LiveSessionGenerationId?) {
        val sessionToClose: com.google.firebase.ai.type.LiveSession?
        val jobToCancel: Job?
        synchronized(lock) {
            if (generationId != null && activeGenerationId != null && activeGenerationId != generationId) {
                markGenerationClosingLocked(generationId)
                return
            }
            markGenerationClosingLocked(generationId ?: activeGenerationId)
            sessionToClose = activeLiveSession
            jobToCancel = activeReceiveJob
            activeGenerationId = null
            receiverCollectorAttachedGen = null
            activeLiveSession = null
            activeReceiveJob = null
        }
        jobToCancel?.cancel()
        if (sessionToClose != null) {
            driverScope.launch {
                try {
                    jobToCancel?.join()
                } catch (_: Throwable) {
                }
                closeSessionOnce(sessionToClose)
            }
        }
    }
}

/**
 * Deterministic test driver used only when explicitly injected by unit/Robolectric tests
 * to verify adapter state transitions, bounded backoff, offline gating, and stale socket callback rejection.
 * Never used as the default in production (`FirebaseAiLiveTransportDriver` is the default).
 */
class DeterministicTestLiveTransportDriver(
    var scriptedHandler: suspend (
        generationId: LiveSessionGenerationId,
        modelId: String,
        attempt: Int,
        onSocketEvent: (LiveSocketCallbackEvent) -> Unit
    ) -> LiveHandshakeResult = { genId, modelId, _, onSocketEvent ->
        val note = "SETUP_ACCEPTED (${modelId} @ ${genId.wireId}, tools=0, audioPlayback=NOT_CLAIMED)"
        onSocketEvent(LiveSocketCallbackEvent.SetupAccepted(genId, note))
        LiveHandshakeResult.Accepted(genId, note)
    }
) : LiveTransportDriver {
    var onEndOfTurnHandler: (suspend (
        generationId: LiveSessionGenerationId,
        emitSocketEvent: (LiveSocketCallbackEvent) -> Unit
    ) -> Unit)? = { genId, emitSocketEvent ->
        val chunk1 = ByteArray(960) { idx -> if (idx % 2 == 0) 0x20 else 0x08 }
        val chunk2 = ByteArray(960) { idx -> if (idx % 2 == 0) 0x18 else 0x06 }
        emitSocketEvent(
            LiveSocketCallbackEvent.ServerAudioChunkReceived(
                generationId = genId,
                mimeType = "audio/pcm;rate=24000",
                pcmBytes = chunk1
            )
        )
        emitSocketEvent(
            LiveSocketCallbackEvent.ServerAudioChunkReceived(
                generationId = genId,
                mimeType = "audio/pcm;rate=24000",
                pcmBytes = chunk2
            )
        )
        emitSocketEvent(
            LiveSocketCallbackEvent.ServerTurnCompleted(
                generationId = genId,
                interrupted = false
            )
        )
    }
    private val attemptCounter = AtomicLong(0L)
    val invocationCount: Int
        get() = attemptCounter.get().toInt()

    var closedGenerations = mutableListOf<LiveSessionGenerationId?>()
        private set

    val sentAudioChunks = mutableListOf<ByteArray>()
    val receivedSystemInstructions = mutableListOf<String>()
    var lastReceivedSystemInstruction: String = LiveConfigConstants.AUTHORITATIVE_SYSTEM_INSTRUCTION
        private set
    val prependedFakeUserSpeechMessages = mutableListOf<String>()
    var startOfTurnSignalCount: Int = 0
        private set
    var endOfTurnSignalCount: Int = 0
        private set

    val collectorAttachedGenerations = mutableListOf<LiveSessionGenerationId>()
    var isCollectorAttachedForCurrentGen: Boolean = false
        private set
    var isSetupAcceptedForCurrentGen: Boolean = false
        private set
    var audioSentBeforeCollectorAttachedCount: Int = 0
        private set
    var audioSentBeforeSetupAcceptedCount: Int = 0
        private set

    private var activeCallback: ((LiveSocketCallbackEvent) -> Unit)? = null
    private var lastConnectedGenId: LiveSessionGenerationId? = null

    override fun isReceiverCollectorAttached(generationId: LiveSessionGenerationId): Boolean {
        return lastConnectedGenId == generationId && isCollectorAttachedForCurrentGen
    }

    override suspend fun connectAndHandshake(
        generationId: LiveSessionGenerationId,
        modelId: String,
        onSocketEvent: (LiveSocketCallbackEvent) -> Unit,
        systemInstruction: String
    ): LiveHandshakeResult {
        val attempt = attemptCounter.incrementAndGet().toInt()
        lastConnectedGenId = generationId
        activeCallback = onSocketEvent
        isCollectorAttachedForCurrentGen = true
        isSetupAcceptedForCurrentGen = false
        collectorAttachedGenerations.add(generationId)
        lastReceivedSystemInstruction = systemInstruction
        receivedSystemInstructions.add(systemInstruction)
        val wrappedCallback: (LiveSocketCallbackEvent) -> Unit = { event ->
            if (event is LiveSocketCallbackEvent.SetupAccepted && event.generationId == generationId) {
                isSetupAcceptedForCurrentGen = true
            }
            onSocketEvent(event)
        }
        activeCallback = wrappedCallback
        val result = scriptedHandler(generationId, modelId, attempt, wrappedCallback)
        if (result is LiveHandshakeResult.Accepted) {
            isSetupAcceptedForCurrentGen = true
        }
        return result
    }

    override suspend fun sendStartOfSpokenTurn(
        generationId: LiveSessionGenerationId
    ): Boolean {
        if (lastConnectedGenId != generationId || !isCollectorAttachedForCurrentGen || !isSetupAcceptedForCurrentGen) {
            return false
        }
        startOfTurnSignalCount += 1
        return true
    }

    override suspend fun sendRealtimeAudioChunk(
        generationId: LiveSessionGenerationId,
        pcm16kHzBytes: ByteArray,
        mimeType: String
    ): Boolean {
        if (!isCollectorAttachedForCurrentGen || lastConnectedGenId != generationId) {
            audioSentBeforeCollectorAttachedCount += 1
            return false
        }
        if (!isSetupAcceptedForCurrentGen) {
            audioSentBeforeSetupAcceptedCount += 1
            return false
        }
        sentAudioChunks.add(pcm16kHzBytes.copyOf())
        return true
    }

    override suspend fun sendEndOfSpokenTurn(
        generationId: LiveSessionGenerationId
    ): Boolean {
        if (lastConnectedGenId != generationId || !isCollectorAttachedForCurrentGen) return false
        endOfTurnSignalCount += 1
        val cb = activeCallback
        val handler = onEndOfTurnHandler
        if (cb != null && handler != null) {
            handler.invoke(generationId, cb)
        }
        return true
    }

    fun emitServerEventForTest(event: LiveSocketCallbackEvent) {
        activeCallback?.invoke(event)
    }

    override fun closeSocket(generationId: LiveSessionGenerationId?) {
        closedGenerations.add(generationId)
        if (generationId == null || generationId == lastConnectedGenId) {
            lastConnectedGenId = null
            isCollectorAttachedForCurrentGen = false
            isSetupAcceptedForCurrentGen = false
        }
    }
}

/**
 * Single Gemini Live Connection Adapter for OmniVoice Rebuild (Phase 03 & Phase 04).
 *
 * Enforces PROJECT_CONTRACT.md Phase 03, Offline Detection, and Phase 04 Language Mirroring invariants:
 * 1. Lifecycle-safe connectivity monitoring via [LiveNetworkMonitor] (`ConnectivityManager.NetworkCallback`).
 * 2. When validated internet access (`NET_CAPABILITY_INTERNET` + `NET_CAPABILITY_VALIDATED`) is lost:
 *    - Immediately invalidates stale `SETUP_ACCEPTED` state (`handshakeVerified = false`).
 *    - Closes/cancels any active Gemini Live connection and scheduled retries cleanly.
 *    - Transitions to `NETWORK_UNAVAILABLE`.
 * 3. Before every manual or automatic handshake, verifies `networkMonitor.isInternetValidated()`.
 *    If false, does NOT call Firebase AI Logic or App Check and returns `NETWORK_UNAVAILABLE`.
 * 4. Honest error classification via [LiveExceptionClassifier]:
 *    - DNS/timeout/socket/IO errors -> `NETWORK_ERROR` (eligible for bounded backoff up to 3 attempts while online).
 *    - Real HTTP 401/403 or App Check rejection while internet is validated -> `BAD_CREDENTIALS_OR_APP_CHECK` (no retry).
 * 5. Recovery policy:
 *    - When validated internet returns, transitions to `NETWORK_RESTORED` (`READY_TO_RECONNECT`) and performs
 *      at most 1 controlled automatic reconnect per offline interruption.
 * 6. Protects against stale callbacks with [LiveSessionGenerationId]; old requests never overwrite newer state.
 * 7. Sends [systemInstruction] (`LiveConfigConstants.AUTHORITATIVE_SYSTEM_INSTRUCTION`) through the official
 *    Gemini Live session/system-instruction mechanism on every handshake without prepending fake user speech.
 */
class GeminiLiveConnectionAdapter(
    private val driver: LiveTransportDriver? = null,
    val networkMonitor: LiveNetworkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
    val configuredModelId: String = LiveConfigConstants.SUPPORTED_LIVE_MODEL_ID,
    val systemInstruction: String = LiveConfigConstants.AUTHORITATIVE_SYSTEM_INSTRUCTION,
    val backoffPolicy: BoundedBackoffPolicy = BoundedBackoffPolicy(),
    val autoReconnectOnNetworkRestore: Boolean = true,
    val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    private val delayRunner: suspend (Long) -> Unit = { ms -> delay(ms) }
) {
    private val effectiveDriver: LiveTransportDriver =
        driver ?: FirebaseAiLiveTransportDriver(networkMonitor = networkMonitor)

    private val lock = Any()
    private val connectMutex = Mutex()
    private var inFlightConnectDeferred: CompletableDeferred<LiveConnectionState>? = null
    private var inFlightConnectIsAutoReconnectOnly: Boolean = false
    private val generationSeq = AtomicLong(0L)
    private val connectRequestSeq = AtomicLong(0L)
    private var activeGenerationId: LiveSessionGenerationId? = null
    private var activeConnectRequestId: Long = 0L
    private var activeConnectJob: Job? = null

    private var lastSessionId: SessionId = SessionId("ov01-sess-0001")
    private var lastKnownValidated: Boolean = networkMonitor.isInternetValidated()
    private var eligibleForSingleAutoReconnect: Boolean = false
    private var autoReconnectPerformedForOutage: Boolean = false
    private var operatorHalted: Boolean = false

    private val adapterScope = CoroutineScope(SupervisorJob() + backgroundDispatcher)

    private fun logAdapter(message: String) {
        try {
            android.util.Log.i("OmniVoiceLive", DiagnosticRedactor.redact(message))
        } catch (_: Throwable) {
        }
    }

    private val _state = MutableStateFlow(
        LiveConnectionState(
            configuredModelId = configuredModelId,
            systemInstruction = systemInstruction,
            isInternetValidated = lastKnownValidated,
            diagnostics = RedactedLiveDiagnostics(
                modelId = configuredModelId,
                systemInstruction = systemInstruction,
                isInternetValidated = lastKnownValidated,
                redactedSummary = "Idle. Ready to verify ${configuredModelId} handshake via Firebase AI Logic (${LiveConfigConstants.LIVE_API_VERSION})."
            )
        )
    )
    val state: StateFlow<LiveConnectionState> = _state.asStateFlow()

    /**
     * Optional synchronous listener invoked whenever [state] updates so [com.example.coordinator.ConversationCoordinator]
     * stays immediately synchronized even when [LiveNetworkMonitor] callbacks fire from background threads
     * or in standalone unit tests without a ViewModel collector.
     */
    @Volatile
    var onStateChanged: ((LiveConnectionState) -> Unit)? = null

    /**
     * Listener invoked synchronously for validated active-generation server content events
     * ([LiveSocketCallbackEvent.ServerAudioChunkReceived], [LiveSocketCallbackEvent.ServerTurnCompleted],
     * [LiveSocketCallbackEvent.ServerTranscriptionReceived]).
     * Stale callbacks from superseded or closed generations are rejected before reaching this listener.
     */
    @Volatile
    var onServerEventListener: ((LiveSocketCallbackEvent) -> Unit)? = null

    private fun updateStateAndNotify(transform: (LiveConnectionState) -> LiveConnectionState) {
        _state.update(transform)
        onStateChanged?.invoke(_state.value)
    }

    /**
     * Registers lifecycle-safe connectivity monitoring while the diagnostics screen or Live session is active.
     */
    fun startNetworkMonitoring() {
        networkMonitor.startMonitoring(::onNetworkValidatedChanged)
    }

    /**
     * Unregisters connectivity monitoring safely when no longer needed.
     */
    fun stopNetworkMonitoring() {
        networkMonitor.stopMonitoring()
    }

    /**
     * Handles real-time connectivity transitions from [LiveNetworkMonitor].
     *
     * - When `isValidated == false`:
     *   Immediately invalidates any stale `SETUP_ACCEPTED` or `ACTIVE` connection, cancels scheduled retries,
     *   closes the socket cleanly, and transitions to `NETWORK_UNAVAILABLE`.
     * - When `isValidated == true`:
     *   Transitions from offline state to `NETWORK_RESTORED` (`READY_TO_RECONNECT`), and performs at most
     *   one controlled automatic reconnect if an active/requested session was interrupted by the outage
     *   and no connect is already in flight.
     */
    fun onNetworkValidatedChanged(isValidated: Boolean) {
        if (!isValidated) {
            val prevGen: LiveSessionGenerationId?
            val jobToCancel: Job?
            synchronized(lock) {
                val prev = _state.value
                val hadSessionOrAttempt =
                    eligibleForSingleAutoReconnect ||
                        activeGenerationId != null ||
                        prev.phase == LiveConnectionPhase.SETUP_ACCEPTED ||
                        prev.phase == LiveConnectionPhase.ACTIVE ||
                        prev.phase == LiveConnectionPhase.CONNECTING ||
                        prev.phase == LiveConnectionPhase.RECONNECTING ||
                        prev.phase == LiveConnectionPhase.RETRY_BACKOFF_WAITING ||
                        prev.handshakeVerified

                if (hadSessionOrAttempt && !operatorHalted) {
                    eligibleForSingleAutoReconnect = true
                    autoReconnectPerformedForOutage = false
                }

                lastKnownValidated = false
                prevGen = activeGenerationId
                // Advance request sequence and clear activeGenerationId so any in-flight/old callback is rejected
                activeConnectRequestId = connectRequestSeq.incrementAndGet()
                activeGenerationId = null
                jobToCancel = activeConnectJob
                activeConnectJob = null

                val genLabel = prevGen?.wireId ?: prev.diagnostics.sessionGenerationWireId
                val offlineSummary =
                    "NETWORK_UNAVAILABLE: Validated internet lost (NET_CAPABILITY_INTERNET + NET_CAPABILITY_VALIDATED required). Stale session ($genLabel) invalidated and socket closed."

                updateStateAndNotify { current ->
                    current.copy(
                        phase = LiveConnectionPhase.NETWORK_UNAVAILABLE,
                        activeGeneration = null,
                        lastCompletedGeneration = prevGen ?: current.lastCompletedGeneration,
                        failureClassification = LiveFailureClassification.NETWORK_UNAVAILABLE,
                        isInternetValidated = false,
                        handshakeVerified = false,
                        audioPlaybackClaimed = false,
                        autoRetryStoppedReason = "RETRY_CANCELLED_OFFLINE (NETWORK_UNAVAILABLE)",
                        statusHeadline = "NETWORK_UNAVAILABLE — CONNECTION INVALIDATED",
                        statusDetail = offlineSummary,
                        diagnostics = current.diagnostics.copy(
                            sessionGenerationWireId = genLabel,
                            phaseWireCode = LiveConnectionPhase.NETWORK_UNAVAILABLE.wireCode,
                            failureCode = LiveFailureClassification.NETWORK_UNAVAILABLE.wireCode,
                            isInternetValidated = false,
                            handshakeVerified = false,
                            audioPlaybackClaimed = false,
                            redactedSummary = offlineSummary
                        )
                    )
                }
            }
            jobToCancel?.cancel()
            effectiveDriver.closeSocket(null)
            return
        }

        // Validated internet is now true
        var shouldTriggerSingleReconnect = false
        val reconnectSessionId: SessionId
        synchronized(lock) {
            val wasOffline =
                !lastKnownValidated ||
                    _state.value.phase == LiveConnectionPhase.NETWORK_UNAVAILABLE ||
                    !_state.value.isInternetValidated

            lastKnownValidated = true

            if (!wasOffline) {
                // Deduplicate repeated capability callbacks while already online
                if (!_state.value.isInternetValidated) {
                    updateStateAndNotify { current ->
                        current.copy(
                            isInternetValidated = true,
                            diagnostics = current.diagnostics.copy(isInternetValidated = true)
                        )
                    }
                }
                return
            }

            // Never clobber an active in-flight handshake or healthy session
            if (inFlightConnectDeferred?.isActive == true || isHealthyEstablishedSessionLocked()) {
                return
            }

            shouldTriggerSingleReconnect =
                autoReconnectOnNetworkRestore &&
                    eligibleForSingleAutoReconnect &&
                    !autoReconnectPerformedForOutage &&
                    !operatorHalted

            if (shouldTriggerSingleReconnect) {
                eligibleForSingleAutoReconnect = false
                autoReconnectPerformedForOutage = true
            }

            reconnectSessionId = lastSessionId
            val restoredHeadline = "NETWORK_RESTORED / READY_TO_RECONNECT"
            val restoredDetail = if (shouldTriggerSingleReconnect) {
                "NETWORK_RESTORED / READY_TO_RECONNECT: Validated internet restored; initiating 1 controlled automatic reconnect."
            } else {
                "NETWORK_RESTORED / READY_TO_RECONNECT: Validated internet restored. Press Verify Live Handshake to connect."
            }

            updateStateAndNotify { current ->
                current.copy(
                    phase = LiveConnectionPhase.NETWORK_RESTORED,
                    activeGeneration = null,
                    failureClassification = LiveFailureClassification.NONE,
                    isInternetValidated = true,
                    handshakeVerified = false,
                    statusHeadline = restoredHeadline,
                    statusDetail = restoredDetail,
                    diagnostics = current.diagnostics.copy(
                        phaseWireCode = LiveConnectionPhase.NETWORK_RESTORED.wireCode,
                        failureCode = LiveFailureClassification.NONE.wireCode,
                        isInternetValidated = true,
                        handshakeVerified = false,
                        redactedSummary = restoredDetail
                    )
                )
            }
        }

        if (shouldTriggerSingleReconnect) {
            val job = adapterScope.launch {
                executeHandshakeWithPolicy(
                    sessionId = reconnectSessionId,
                    maxAttemptsLimit = 1,
                    isControlledAutoReconnect = true,
                    reuseIfHealthy = true
                )
            }
            synchronized(lock) {
                if (activeConnectJob == null && job.isActive) {
                    activeConnectJob = job
                }
            }
        }
    }

    /**
     * Generates a fresh, monotonically increasing [LiveSessionGenerationId] bound to a specific
     * socket attempt and [SessionId].
     */
    private fun nextGenerationId(sessionId: SessionId, attempt: Int): LiveSessionGenerationId {
        val seq = generationSeq.incrementAndGet()
        return LiveSessionGenerationId(
            sequenceNumber = seq,
            sessionId = sessionId,
            wireId = "live-gen-${seq.toString().padStart(4, '0')}-${sessionId.value}-att$attempt"
        )
    }

    /**
     * Processes a socket/session callback event only if its [LiveSessionGenerationId] matches
     * the currently active socket generation AND validated internet is available.
     * Stale callbacks from superseded or closed sockets are deterministically rejected, logged as
     * `STALE_CALLBACK_DROPPED`, and counted.
     */
    fun onSocketCallback(event: LiveSocketCallbackEvent): Boolean {
        val closeGenAfterCallback: LiveSessionGenerationId?
        val acceptedWhileOnline: Boolean
        synchronized(lock) {
            val currentActive = activeGenerationId
            if (currentActive == null || currentActive != event.generationId) {
                logAdapter(
                    "STALE_CALLBACK_DROPPED: Dropped ${event::class.simpleName} for ${event.generationId.wireId} " +
                        "(activeGeneration=${currentActive?.wireId ?: "NONE"})"
                )
                updateStateAndNotify { prev ->
                    val newDropped = prev.staleSocketCallbacksDropped + 1
                    prev.copy(
                        staleSocketCallbacksDropped = newDropped,
                        diagnostics = prev.diagnostics.copy(
                            staleCallbacksDropped = newDropped
                        )
                    )
                }
                return false
            }

            // Never report SETUP_ACCEPTED while no validated internet is available!
            val validatedNow = networkMonitor.isInternetValidated()
            if (!validatedNow) {
                if (!operatorHalted) {
                    eligibleForSingleAutoReconnect = true
                    autoReconnectPerformedForOutage = false
                }
                lastKnownValidated = false
                activeGenerationId = null
                closeGenAfterCallback = currentActive
                acceptedWhileOnline = false
                val newDropped = _state.value.staleSocketCallbacksDropped + 1
                val offlineSummary =
                    "NETWORK_UNAVAILABLE: Dropped callback on ${currentActive.wireId} because validated internet is unavailable."
                updateStateAndNotify { prev ->
                    prev.copy(
                        phase = LiveConnectionPhase.NETWORK_UNAVAILABLE,
                        activeGeneration = null,
                        lastCompletedGeneration = currentActive,
                        failureClassification = LiveFailureClassification.NETWORK_UNAVAILABLE,
                        isInternetValidated = false,
                        handshakeVerified = false,
                        staleSocketCallbacksDropped = newDropped,
                        statusHeadline = "NETWORK_UNAVAILABLE — CONNECTION INVALIDATED",
                        statusDetail = offlineSummary,
                        diagnostics = prev.diagnostics.copy(
                            sessionGenerationWireId = currentActive.wireId,
                            phaseWireCode = LiveConnectionPhase.NETWORK_UNAVAILABLE.wireCode,
                            failureCode = LiveFailureClassification.NETWORK_UNAVAILABLE.wireCode,
                            isInternetValidated = false,
                            handshakeVerified = false,
                            staleCallbacksDropped = newDropped,
                            redactedSummary = offlineSummary
                        )
                    )
                }
            } else {
                acceptedWhileOnline = true
                when (event) {
                    is LiveSocketCallbackEvent.SetupAccepted -> {
                        closeGenAfterCallback = null
                        val safeNote = DiagnosticRedactor.redact(event.serverNote)
                        val collectorAttached =
                            effectiveDriver.isReceiverCollectorAttached(currentActive) &&
                                onServerEventListener != null
                        updateStateAndNotify { prev ->
                            val preservedPhase = if (
                                prev.phase == LiveConnectionPhase.ACTIVE &&
                                prev.activeGeneration == currentActive
                            ) {
                                LiveConnectionPhase.ACTIVE
                            } else {
                                LiveConnectionPhase.SETUP_ACCEPTED
                            }
                            prev.copy(
                                phase = preservedPhase,
                                activeGeneration = currentActive,
                                lastCompletedGeneration = currentActive,
                                failureClassification = LiveFailureClassification.NONE,
                                isInternetValidated = true,
                                retryCount = 0,
                                lastBackoffDelayMs = 0L,
                                autoRetryStoppedReason = null,
                                handshakeVerified = true,
                                isReceiverCollectorAttached = collectorAttached,
                                audioPlaybackClaimed = prev.audioPlaybackClaimed,
                                registeredActionToolsCount = 0,
                                statusHeadline = "LIVE HANDSHAKE VERIFIED (SETUP_ACCEPTED)",
                                statusDetail = safeNote,
                                diagnostics = prev.diagnostics.copy(
                                    modelId = configuredModelId,
                                    sessionGenerationWireId = currentActive.wireId,
                                    phaseWireCode = preservedPhase.wireCode,
                                    failureCode = LiveFailureClassification.NONE.wireCode,
                                    isInternetValidated = true,
                                    retryCount = 0,
                                    lastBackoffDelayMs = 0L,
                                    handshakeVerified = true,
                                    isReceiverCollectorAttached = collectorAttached,
                                    audioPlaybackClaimed = prev.audioPlaybackClaimed,
                                    registeredActionToolsCount = 0,
                                    redactedSummary = safeNote
                                )
                            )
                        }
                    }

                    is LiveSocketCallbackEvent.HandshakeFailed -> {
                        val wasEstablished =
                            _state.value.phase == LiveConnectionPhase.SETUP_ACCEPTED ||
                                _state.value.phase == LiveConnectionPhase.ACTIVE ||
                                _state.value.handshakeVerified
                        if (wasEstablished) {
                            activeGenerationId = null
                            closeGenAfterCallback = currentActive
                            if (!operatorHalted && event.classification.retryable) {
                                eligibleForSingleAutoReconnect = true
                                autoReconnectPerformedForOutage = false
                            }
                        } else {
                            closeGenAfterCallback = null
                        }
                        val safeMsg = DiagnosticRedactor.redact(event.rawErrorMessage)
                        val stopReason = if (!event.classification.retryable) {
                            "AUTO_RETRY_STOPPED_${event.classification.wireCode}"
                        } else {
                            null
                        }
                        updateStateAndNotify { prev ->
                            prev.copy(
                                phase = LiveConnectionPhase.FAILED,
                                activeGeneration = if (wasEstablished) null else prev.activeGeneration,
                                lastCompletedGeneration = currentActive,
                                failureClassification = event.classification,
                                handshakeVerified = false,
                                isReceiverCollectorAttached = false,
                                autoRetryStoppedReason = stopReason ?: prev.autoRetryStoppedReason,
                                statusHeadline = "LIVE SESSION FAILED: ${event.classification.wireCode}",
                                statusDetail = safeMsg,
                                diagnostics = prev.diagnostics.copy(
                                    modelId = configuredModelId,
                                    sessionGenerationWireId = currentActive.wireId,
                                    phaseWireCode = LiveConnectionPhase.FAILED.wireCode,
                                    failureCode = event.classification.wireCode,
                                    handshakeVerified = false,
                                    isReceiverCollectorAttached = false,
                                    redactedSummary = "Failure [${event.classification.wireCode}]: $safeMsg"
                                )
                            )
                        }
                    }

                    is LiveSocketCallbackEvent.SocketClosedRemotely -> {
                        val safeReason = DiagnosticRedactor.redact(event.reason)
                        val closeClassification = LiveExceptionClassifier.classifyCloseReason(
                            reason = safeReason,
                            isInternetValidated = validatedNow
                        )
                        activeGenerationId = null
                        closeGenAfterCallback = currentActive
                        if (closeClassification == LiveFailureClassification.CANCELLED_BY_STOP_OR_PAUSE) {
                            logAdapter("STALE_CALLBACK_DROPPED: Ignored local close/cancel callback for ${currentActive.wireId}")
                            updateStateAndNotify { prev ->
                                prev.copy(
                                    phase = LiveConnectionPhase.DISCONNECTED,
                                    activeGeneration = null,
                                    lastCompletedGeneration = currentActive,
                                    failureClassification = LiveFailureClassification.NONE,
                                    handshakeVerified = false,
                                    isReceiverCollectorAttached = false,
                                    statusHeadline = "LIVE SOCKET DISCONNECTED",
                                    statusDetail = safeReason,
                                    diagnostics = prev.diagnostics.copy(
                                        sessionGenerationWireId = currentActive.wireId,
                                        phaseWireCode = LiveConnectionPhase.DISCONNECTED.wireCode,
                                        failureCode = LiveFailureClassification.NONE.wireCode,
                                        handshakeVerified = false,
                                        isReceiverCollectorAttached = false,
                                        redactedSummary = "Socket closed (${currentActive.wireId}): $safeReason"
                                    )
                                )
                            }
                        } else {
                            if (!operatorHalted && closeClassification.retryable) {
                                eligibleForSingleAutoReconnect = true
                                autoReconnectPerformedForOutage = false
                            } else {
                                eligibleForSingleAutoReconnect = false
                            }
                            val stopReason = if (!closeClassification.retryable) {
                                "AUTO_RETRY_STOPPED_${closeClassification.wireCode}"
                            } else {
                                null
                            }
                            updateStateAndNotify { prev ->
                                prev.copy(
                                    phase = LiveConnectionPhase.FAILED,
                                    activeGeneration = null,
                                    lastCompletedGeneration = currentActive,
                                    failureClassification = closeClassification,
                                    handshakeVerified = false,
                                    isReceiverCollectorAttached = false,
                                    autoRetryStoppedReason = stopReason ?: prev.autoRetryStoppedReason,
                                    statusHeadline = "LIVE SOCKET CLOSED [${closeClassification.wireCode}]",
                                    statusDetail = safeReason,
                                    diagnostics = prev.diagnostics.copy(
                                        sessionGenerationWireId = currentActive.wireId,
                                        phaseWireCode = LiveConnectionPhase.FAILED.wireCode,
                                        failureCode = closeClassification.wireCode,
                                        handshakeVerified = false,
                                        isReceiverCollectorAttached = false,
                                        redactedSummary = "Remote socket closed (${currentActive.wireId}) [${closeClassification.wireCode}]: $safeReason"
                                    )
                                )
                            }
                        }
                    }

                    is LiveSocketCallbackEvent.ServerAudioChunkReceived,
                    is LiveSocketCallbackEvent.ServerTurnCompleted,
                    is LiveSocketCallbackEvent.ServerTranscriptionReceived -> {
                        closeGenAfterCallback = null
                    }
                }
            }
        }
        if (closeGenAfterCallback != null) {
            effectiveDriver.closeSocket(closeGenAfterCallback)
        }
        if (!acceptedWhileOnline) {
            return false
        }
        if (event is LiveSocketCallbackEvent.ServerAudioChunkReceived ||
            event is LiveSocketCallbackEvent.ServerTurnCompleted ||
            event is LiveSocketCallbackEvent.ServerTranscriptionReceived
        ) {
            onServerEventListener?.invoke(event)
        }
        return true
    }

    private fun isHealthyEstablishedSessionLocked(): Boolean {
        if (!networkMonitor.isInternetValidated()) return false
        val snap = _state.value
        val gen = activeGenerationId ?: return false
        return (snap.phase == LiveConnectionPhase.SETUP_ACCEPTED || snap.phase == LiveConnectionPhase.ACTIVE) &&
            snap.handshakeVerified &&
            snap.activeGeneration == gen &&
            effectiveDriver.isReceiverCollectorAttached(gen)
    }

    /**
     * Returns true only if the Gemini Live session is currently `SETUP_ACCEPTED` or `ACTIVE`, validated
     * internet is available, the coordinator's server-event collector (`onServerEventListener`)
     * is attached, and the transport driver's `session.receive()` collector is actively attached
     * for the active [LiveSessionGenerationId].
     */
    fun isSessionReadyForAudio(): Boolean {
        if (!networkMonitor.isInternetValidated()) return false
        return synchronized(lock) {
            isHealthyEstablishedSessionLocked() && onServerEventListener != null
        }
    }

    /**
     * Updates whether the Phase 04 audio playback adapter has claimed output for the active turn.
     */
    fun setAudioPlaybackClaimed(claimed: Boolean) {
        synchronized(lock) {
            updateStateAndNotify { prev ->
                prev.copy(
                    audioPlaybackClaimed = claimed,
                    diagnostics = prev.diagnostics.copy(
                        audioPlaybackClaimed = claimed
                    )
                )
            }
        }
    }

    /**
     * Transitions a verified `SETUP_ACCEPTED` session to [LiveConnectionPhase.ACTIVE] when realtime
     * audio streaming for a turn begins, without opening a new socket connection.
     */
    fun markSessionActive(): Boolean {
        return synchronized(lock) {
            val gen = activeGenerationId ?: return@synchronized false
            val snap = _state.value
            if (!snap.handshakeVerified || snap.activeGeneration != gen) return@synchronized false
            if (snap.phase == LiveConnectionPhase.ACTIVE) return@synchronized true
            if (snap.phase != LiveConnectionPhase.SETUP_ACCEPTED) return@synchronized false
            updateStateAndNotify { prev ->
                prev.copy(
                    phase = LiveConnectionPhase.ACTIVE,
                    statusHeadline = "LIVE SESSION ACTIVE (${gen.wireId})",
                    diagnostics = prev.diagnostics.copy(
                        phaseWireCode = LiveConnectionPhase.ACTIVE.wireCode
                    )
                )
            }
            true
        }
    }

    /**
     * Streams a 16kHz 16-bit mono PCM audio chunk to the currently verified active Gemini Live session.
     * Transitions `SETUP_ACCEPTED -> ACTIVE` when the first chunk streams.
     * Returns `false` if no verified session is active, if the receiver collector is not attached,
     * or if validated internet was lost.
     */
    suspend fun sendRealtimeAudioToActiveSession(
        pcm16kHzBytes: ByteArray,
        mimeType: String = "audio/pcm;rate=16000"
    ): Boolean {
        if (!networkMonitor.isInternetValidated()) {
            onNetworkValidatedChanged(false)
            return false
        }
        val activeGen = synchronized(lock) {
            val gen = activeGenerationId
            val snap = _state.value
            if (gen != null &&
                (snap.phase == LiveConnectionPhase.SETUP_ACCEPTED || snap.phase == LiveConnectionPhase.ACTIVE) &&
                snap.handshakeVerified &&
                onServerEventListener != null &&
                effectiveDriver.isReceiverCollectorAttached(gen)
            ) {
                if (snap.phase == LiveConnectionPhase.SETUP_ACCEPTED) {
                    updateStateAndNotify { prev ->
                        prev.copy(
                            phase = LiveConnectionPhase.ACTIVE,
                            statusHeadline = "LIVE SESSION ACTIVE (${gen.wireId})",
                            diagnostics = prev.diagnostics.copy(
                                phaseWireCode = LiveConnectionPhase.ACTIVE.wireCode
                            )
                        )
                    }
                }
                gen
            } else {
                null
            }
        } ?: return false
        return effectiveDriver.sendRealtimeAudioChunk(
            generationId = activeGen,
            pcm16kHzBytes = pcm16kHzBytes,
            mimeType = mimeType
        )
    }

    /**
     * Signals explicit start-of-spoken-turn (`activityStart`) to the currently verified active Gemini Live session.
     */
    suspend fun signalStartOfSpokenTurn(): Boolean {
        if (!networkMonitor.isInternetValidated()) {
            onNetworkValidatedChanged(false)
            return false
        }
        val activeGen = synchronized(lock) {
            val snap = _state.value
            val gen = activeGenerationId
            if (gen != null &&
                (snap.phase == LiveConnectionPhase.SETUP_ACCEPTED || snap.phase == LiveConnectionPhase.ACTIVE) &&
                snap.handshakeVerified &&
                onServerEventListener != null &&
                effectiveDriver.isReceiverCollectorAttached(gen)
            ) {
                gen
            } else {
                null
            }
        } ?: return false
        return effectiveDriver.sendStartOfSpokenTurn(activeGen)
    }

    /**
     * Signals end-of-spoken-turn to the currently verified active Gemini Live session.
     * Returns `false` if no verified session is active or if validated internet was lost.
     */
    suspend fun signalEndOfSpokenTurn(): Boolean {
        if (!networkMonitor.isInternetValidated()) {
            onNetworkValidatedChanged(false)
            return false
        }
        val activeGen = synchronized(lock) {
            val snap = _state.value
            if ((snap.phase == LiveConnectionPhase.SETUP_ACCEPTED || snap.phase == LiveConnectionPhase.ACTIVE) &&
                snap.handshakeVerified
            ) {
                activeGenerationId
            } else {
                null
            }
        } ?: return false
        return effectiveDriver.sendEndOfSpokenTurn(activeGen)
    }

    fun getActiveGenerationCountForDiagnostics(): Int {
        return synchronized(lock) {
            if (activeGenerationId != null) 1 else 0
        }
    }

    /**
     * Authoritative single-flight entry point for Start / voice turns:
     * - Reuses an already healthy `SETUP_ACCEPTED` or `ACTIVE` session across sequential turns without reconnecting.
     * - Joins any already in-flight handshake via single-flight `CompletableDeferred` without starting duplicate sockets.
     * - Connects once if disconnected or recovers cleanly after `FAILED` / exhausted retries without restarting the app.
     */
    suspend fun ensureConnectedSession(sessionId: SessionId): LiveConnectionState {
        return executeHandshakeWithPolicy(
            sessionId = sessionId,
            maxAttemptsLimit = backoffPolicy.maxAttempts,
            isControlledAutoReconnect = false,
            reuseIfHealthy = true
        )
    }

    /**
     * Initiates a real Gemini Live handshake with bounded exponential backoff (up to 3 attempts)
     * for transient network/socket errors while online.
     *
     * Set [reuseIfHealthy] to `true` (or call [ensureConnectedSession]) to reuse an existing
     * healthy `SETUP_ACCEPTED`/`ACTIVE` session instead of opening a replacement socket.
     * Overlapping concurrent calls are always single-flight deduplicated behind [connectMutex].
     */
    suspend fun connectWithBoundedRetry(
        sessionId: SessionId,
        reuseIfHealthy: Boolean = false
    ): LiveConnectionState {
        return executeHandshakeWithPolicy(
            sessionId = sessionId,
            maxAttemptsLimit = backoffPolicy.maxAttempts,
            isControlledAutoReconnect = false,
            reuseIfHealthy = reuseIfHealthy
        )
    }

    private suspend fun executeHandshakeWithPolicy(
        sessionId: SessionId,
        maxAttemptsLimit: Int,
        isControlledAutoReconnect: Boolean,
        reuseIfHealthy: Boolean = false
    ): LiveConnectionState {
        // Single-flight gate: either reuse healthy session or join an already in-flight connection
        val myDeferred: CompletableDeferred<LiveConnectionState>
        var existingToAwait: CompletableDeferred<LiveConnectionState>? = null
        var joinedWasAutoOnly = false

        synchronized(lock) {
            lastSessionId = sessionId
            operatorHalted = false

            if (reuseIfHealthy && isHealthyEstablishedSessionLocked()) {
                return _state.value
            }

            val currentInFlight = inFlightConnectDeferred
            if (currentInFlight != null && currentInFlight.isActive) {
                existingToAwait = currentInFlight
                joinedWasAutoOnly = inFlightConnectIsAutoReconnectOnly
                myDeferred = currentInFlight
            } else {
                val created = CompletableDeferred<LiveConnectionState>()
                inFlightConnectDeferred = created
                inFlightConnectIsAutoReconnectOnly = isControlledAutoReconnect
                myDeferred = created
            }
        }

        if (existingToAwait != null) {
            logAdapter("LIVE_HANDSHAKE_DEDUPLICATED: Joining existing single-flight handshake for ${sessionId.value}.")
            val joinedState = existingToAwait!!.await()
            val shouldFallbackRetryFromAuto =
                joinedWasAutoOnly &&
                    !isControlledAutoReconnect &&
                    (joinedState.phase == LiveConnectionPhase.FAILED || joinedState.phase == LiveConnectionPhase.DISCONNECTED) &&
                    networkMonitor.isInternetValidated()
            if (!shouldFallbackRetryFromAuto) {
                return joinedState
            }
            // Only if a single-attempt background auto-reconnect failed, fall through once for full manual recovery
            return executeHandshakeWithPolicy(
                sessionId = sessionId,
                maxAttemptsLimit = maxAttemptsLimit,
                isControlledAutoReconnect = false,
                reuseIfHealthy = reuseIfHealthy
            )
        }

        try {
            return connectMutex.withLock {
                if (reuseIfHealthy && synchronized(lock) { isHealthyEstablishedSessionLocked() }) {
                    return@withLock _state.value
                }

                // Pre-handshake check for NET_CAPABILITY_INTERNET + NET_CAPABILITY_VALIDATED
                if (!networkMonitor.isInternetValidated()) {
                    synchronized(lock) {
                        lastKnownValidated = false
                        eligibleForSingleAutoReconnect = true
                        autoReconnectPerformedForOutage = false
                        activeGenerationId = null
                        val offlineDetail =
                            "NETWORK_UNAVAILABLE: Active network lacks NET_CAPABILITY_INTERNET and NET_CAPABILITY_VALIDATED. Firebase AI Logic and App Check were not called."
                        updateStateAndNotify { prev ->
                            prev.copy(
                                phase = LiveConnectionPhase.NETWORK_UNAVAILABLE,
                                activeGeneration = null,
                                failureClassification = LiveFailureClassification.NETWORK_UNAVAILABLE,
                                isInternetValidated = false,
                                attemptCount = 0,
                                retryCount = 0,
                                handshakeVerified = false,
                                audioPlaybackClaimed = false,
                                autoRetryStoppedReason = "OFFLINE_PRECHECK_BLOCKED_NETWORK_UNAVAILABLE",
                                statusHeadline = "NETWORK_UNAVAILABLE — NO VALIDATED INTERNET",
                                statusDetail = offlineDetail,
                                diagnostics = prev.diagnostics.copy(
                                    modelId = configuredModelId,
                                    phaseWireCode = LiveConnectionPhase.NETWORK_UNAVAILABLE.wireCode,
                                    failureCode = LiveFailureClassification.NETWORK_UNAVAILABLE.wireCode,
                                    isInternetValidated = false,
                                    attemptCount = 0,
                                    retryCount = 0,
                                    handshakeVerified = false,
                                    audioPlaybackClaimed = false,
                                    redactedSummary = offlineDetail
                                )
                            )
                        }
                    }
                    withContext(backgroundDispatcher + NonCancellable) {
                        effectiveDriver.closeAndAwaitSocket(null)
                    }
                    return@withLock _state.value
                }

                // Before creating a fresh generation: invalidate old generation, cancel collector/retry job,
                // close old session once, and wait for cleanup off the main thread.
                val hadPriorSessionOrFailure: Boolean
                val requestId: Long
                val priorJobToCancel: Job?
                synchronized(lock) {
                    requestId = connectRequestSeq.incrementAndGet()
                    activeConnectRequestId = requestId
                    val prevGen = activeGenerationId
                    val prevPhase = _state.value.phase
                    hadPriorSessionOrFailure = prevGen != null ||
                        prevPhase == LiveConnectionPhase.SETUP_ACCEPTED ||
                        prevPhase == LiveConnectionPhase.ACTIVE ||
                        prevPhase == LiveConnectionPhase.RECONNECTING ||
                        prevPhase == LiveConnectionPhase.FAILED ||
                        prevPhase == LiveConnectionPhase.NETWORK_RESTORED ||
                        prevPhase == LiveConnectionPhase.RETRY_BACKOFF_WAITING
                    activeGenerationId = null
                    priorJobToCancel = if (!isControlledAutoReconnect) activeConnectJob else null
                    if (!isControlledAutoReconnect) {
                        activeConnectJob = null
                    }
                }
                priorJobToCancel?.cancel()
                if (hadPriorSessionOrFailure || priorJobToCancel != null) {
                    withContext(backgroundDispatcher + NonCancellable) {
                        effectiveDriver.closeAndAwaitSocket(null)
                    }
                } else {
                    effectiveDriver.closeAndAwaitSocket(null)
                }

                var attempt = 1
                var retryCount = 0
                val effectiveMaxAttempts = maxAttemptsLimit.coerceAtLeast(1)

                while (attempt <= effectiveMaxAttempts) {
                    if (!networkMonitor.isInternetValidated()) {
                        onNetworkValidatedChanged(false)
                        return@withLock _state.value
                    }

                    val connectingPhase = if (attempt > 1 || isControlledAutoReconnect || hadPriorSessionOrFailure) {
                        LiveConnectionPhase.RECONNECTING
                    } else {
                        LiveConnectionPhase.CONNECTING
                    }

                    val genId = synchronized(lock) {
                        if (activeConnectRequestId != requestId) {
                            return@withLock _state.value
                        }
                        val created = nextGenerationId(sessionId, attempt)
                        activeGenerationId = created
                        lastKnownValidated = true
                        val newAutoReconnectCount = if (isControlledAutoReconnect && attempt == 1) {
                            _state.value.autoReconnectCount + 1
                        } else {
                            _state.value.autoReconnectCount
                        }
                        val prefix = when {
                            isControlledAutoReconnect -> "NETWORK_RESTORED -> AUTO_RECONNECT"
                            connectingPhase == LiveConnectionPhase.RECONNECTING -> "RECONNECTING"
                            else -> "CONNECTING"
                        }
                        val connectingSummary =
                            "$prefix attempt $attempt/$effectiveMaxAttempts to $configuredModelId (${LiveConfigConstants.ENDPOINT_FAMILY.wireCode}) as ${created.wireId}."
                        updateStateAndNotify { prev ->
                            prev.copy(
                                phase = connectingPhase,
                                activeGeneration = created,
                                configuredModelId = configuredModelId,
                                failureClassification = LiveFailureClassification.NONE,
                                isInternetValidated = true,
                                attemptCount = attempt,
                                retryCount = retryCount,
                                autoReconnectCount = newAutoReconnectCount,
                                handshakeVerified = false,
                                audioPlaybackClaimed = false,
                                registeredActionToolsCount = 0,
                                autoRetryStoppedReason = null,
                                statusHeadline = "${connectingPhase.wireCode} TO GEMINI LIVE (ATTEMPT $attempt/$effectiveMaxAttempts)",
                                statusDetail = connectingSummary,
                                diagnostics = prev.diagnostics.copy(
                                    modelId = configuredModelId,
                                    sessionGenerationWireId = created.wireId,
                                    phaseWireCode = connectingPhase.wireCode,
                                    failureCode = LiveFailureClassification.NONE.wireCode,
                                    isInternetValidated = true,
                                    attemptCount = attempt,
                                    retryCount = retryCount,
                                    autoReconnectCount = newAutoReconnectCount,
                                    handshakeVerified = false,
                                    audioPlaybackClaimed = false,
                                    registeredActionToolsCount = 0,
                                    redactedSummary = connectingSummary
                                )
                            )
                        }
                        created
                    }

                    val result = try {
                        effectiveDriver.connectAndHandshake(
                            generationId = genId,
                            modelId = configuredModelId,
                            onSocketEvent = ::onSocketCallback,
                            systemInstruction = systemInstruction
                        )
                    } catch (ce: CancellationException) {
                        withContext(backgroundDispatcher + NonCancellable) {
                            effectiveDriver.closeAndAwaitSocket(genId)
                        }
                        throw ce
                    }

                    // Verify this generation wasn't preempted by STOP/PAUSE/disconnect, a newer request, or offline loss
                    val validatedAfterCall = networkMonitor.isInternetValidated()
                    val stillCurrent = synchronized(lock) {
                        activeConnectRequestId == requestId && activeGenerationId == genId
                    }
                    if (!stillCurrent) {
                        withContext(backgroundDispatcher + NonCancellable) {
                            effectiveDriver.closeAndAwaitSocket(genId)
                        }
                        return@withLock _state.value
                    }
                    if (!validatedAfterCall) {
                        withContext(backgroundDispatcher + NonCancellable) {
                            effectiveDriver.closeAndAwaitSocket(genId)
                        }
                        onNetworkValidatedChanged(false)
                        return@withLock _state.value
                    }

                    when (result) {
                        is LiveHandshakeResult.Accepted -> {
                            synchronized(lock) {
                                eligibleForSingleAutoReconnect = true
                                autoReconnectPerformedForOutage = false
                            }
                            onSocketCallback(
                                LiveSocketCallbackEvent.SetupAccepted(
                                    generationId = genId,
                                    serverNote = result.serverNote
                                )
                            )
                            return@withLock _state.value
                        }

                        is LiveHandshakeResult.Failed -> {
                            withContext(backgroundDispatcher + NonCancellable) {
                                effectiveDriver.closeAndAwaitSocket(genId)
                            }
                            val classification = result.classification
                            val redactedError = DiagnosticRedactor.redact(result.rawDetail)

                            if (classification == LiveFailureClassification.NETWORK_UNAVAILABLE ||
                                !networkMonitor.isInternetValidated()
                            ) {
                                onNetworkValidatedChanged(false)
                                return@withLock _state.value
                            }

                            // Non-retryable failure classes MUST stop automatic retries immediately!
                            if (!classification.retryable) {
                                val stopReason =
                                    "AUTO_RETRY_HALTED_${classification.wireCode} (Non-retryable on model $configuredModelId; silent model fallback prohibited)"
                                synchronized(lock) {
                                    if (activeConnectRequestId == requestId) {
                                        activeGenerationId = null
                                        updateStateAndNotify { prev ->
                                            prev.copy(
                                                phase = LiveConnectionPhase.FAILED,
                                                activeGeneration = null,
                                                lastCompletedGeneration = genId,
                                                failureClassification = classification,
                                                attemptCount = attempt,
                                                retryCount = retryCount,
                                                handshakeVerified = false,
                                                autoRetryStoppedReason = stopReason,
                                                statusHeadline = "FAILED (${classification.wireCode}) — AUTO-RETRY STOPPED",
                                                statusDetail = "${classification.guidance} | Detail: $redactedError",
                                                diagnostics = prev.diagnostics.copy(
                                                    modelId = configuredModelId,
                                                    sessionGenerationWireId = genId.wireId,
                                                    phaseWireCode = LiveConnectionPhase.FAILED.wireCode,
                                                    failureCode = classification.wireCode,
                                                    attemptCount = attempt,
                                                    retryCount = retryCount,
                                                    handshakeVerified = false,
                                                    redactedSummary = "$stopReason | $redactedError"
                                                )
                                            )
                                        }
                                    }
                                }
                                return@withLock _state.value
                            }

                            // Transient network/socket failure: check bounded backoff policy & effectiveMaxAttempts
                            val nextDelayMs = if (attempt >= effectiveMaxAttempts) {
                                null
                            } else {
                                backoffPolicy.nextDelayMs(attemptIndex = attempt - 1)
                            }

                            if (nextDelayMs == null) {
                                val exhaustedReason = if (isControlledAutoReconnect) {
                                    "CONTROLLED_AUTO_RECONNECT_COMPLETED_SINGLE_ATTEMPT (${classification.wireCode})"
                                } else {
                                    "BOUNDED_BACKOFF_EXHAUSTED_AFTER_${attempt}_ATTEMPTS (${classification.wireCode})"
                                }
                                synchronized(lock) {
                                    if (activeConnectRequestId == requestId) {
                                        activeGenerationId = null
                                        updateStateAndNotify { prev ->
                                            prev.copy(
                                                phase = LiveConnectionPhase.FAILED,
                                                activeGeneration = null,
                                                lastCompletedGeneration = genId,
                                                failureClassification = classification,
                                                attemptCount = attempt,
                                                retryCount = retryCount,
                                                handshakeVerified = false,
                                                autoRetryStoppedReason = exhaustedReason,
                                                statusHeadline = "FAILED AFTER $attempt BOUNDED ATTEMPTS (${classification.wireCode})",
                                                statusDetail = "$exhaustedReason | Last error: $redactedError",
                                                diagnostics = prev.diagnostics.copy(
                                                    modelId = configuredModelId,
                                                    sessionGenerationWireId = genId.wireId,
                                                    phaseWireCode = LiveConnectionPhase.FAILED.wireCode,
                                                    failureCode = classification.wireCode,
                                                    attemptCount = attempt,
                                                    retryCount = retryCount,
                                                    handshakeVerified = false,
                                                    redactedSummary = "$exhaustedReason | $redactedError"
                                                )
                                            )
                                        }
                                    }
                                }
                                return@withLock _state.value
                            }

                            // Wait bounded backoff before next attempt
                            retryCount += 1
                            synchronized(lock) {
                                if (activeConnectRequestId != requestId) {
                                    return@withLock _state.value
                                }
                                val backoffMsg =
                                    "Transient failure (${classification.wireCode}) on ${genId.wireId}. Waiting ${nextDelayMs}ms before retry ${attempt + 1}/$effectiveMaxAttempts. Detail: $redactedError"
                                updateStateAndNotify { prev ->
                                    prev.copy(
                                        phase = LiveConnectionPhase.RETRY_BACKOFF_WAITING,
                                        failureClassification = classification,
                                        attemptCount = attempt,
                                        retryCount = retryCount,
                                        lastBackoffDelayMs = nextDelayMs,
                                        handshakeVerified = false,
                                        statusHeadline = "TRANSIENT FAILURE — BACKOFF ${nextDelayMs}ms (RETRY $retryCount)",
                                        statusDetail = backoffMsg,
                                        diagnostics = prev.diagnostics.copy(
                                            modelId = configuredModelId,
                                            sessionGenerationWireId = genId.wireId,
                                            phaseWireCode = LiveConnectionPhase.RETRY_BACKOFF_WAITING.wireCode,
                                            failureCode = classification.wireCode,
                                            attemptCount = attempt,
                                            retryCount = retryCount,
                                            lastBackoffDelayMs = nextDelayMs,
                                            handshakeVerified = false,
                                            redactedSummary = backoffMsg
                                        )
                                    )
                                }
                            }

                            delayRunner(nextDelayMs)

                            // Check if disconnected, preempted by newer request, or went offline during backoff delay
                            if (!networkMonitor.isInternetValidated()) {
                                onNetworkValidatedChanged(false)
                                return@withLock _state.value
                            }
                            val stillWaiting = synchronized(lock) {
                                activeConnectRequestId == requestId && activeGenerationId == genId
                            }
                            if (!stillWaiting) {
                                return@withLock _state.value
                            }
                            attempt += 1
                        }
                    }
                }

                _state.value
            }
        } finally {
            val finalState = _state.value
            synchronized(lock) {
                if (inFlightConnectDeferred === myDeferred) {
                    inFlightConnectDeferred = null
                    inFlightConnectIsAutoReconnectOnly = false
                }
            }
            myDeferred.complete(finalState)
        }
    }

    /**
     * Cancels any scheduled retry backoff or in-flight connection attempt when the diagnostics
     * screen is disposed, without destroying an already-verified `SETUP_ACCEPTED` or `ACTIVE` session.
     */
    fun cancelScheduledRetries(reason: String) {
        val jobToCancel: Job?
        val genToClose: LiveSessionGenerationId?
        val safeReason = DiagnosticRedactor.redact(reason)
        synchronized(lock) {
            val currentPhase = _state.value.phase
            if (currentPhase == LiveConnectionPhase.RETRY_BACKOFF_WAITING ||
                currentPhase == LiveConnectionPhase.CONNECTING ||
                currentPhase == LiveConnectionPhase.RECONNECTING
            ) {
                activeConnectRequestId = connectRequestSeq.incrementAndGet()
                genToClose = activeGenerationId
                activeGenerationId = null
                jobToCancel = activeConnectJob
                activeConnectJob = null
                val genLabel = genToClose?.wireId ?: _state.value.diagnostics.sessionGenerationWireId
                updateStateAndNotify { prev ->
                    prev.copy(
                        phase = LiveConnectionPhase.DISCONNECTED,
                        activeGeneration = null,
                        lastCompletedGeneration = genToClose ?: prev.lastCompletedGeneration,
                        handshakeVerified = false,
                        autoRetryStoppedReason = "RETRY_CANCELLED ($safeReason)",
                        statusHeadline = "SCHEDULED RETRY CANCELLED",
                        statusDetail = "Cancelled ($genLabel): $safeReason",
                        diagnostics = prev.diagnostics.copy(
                            sessionGenerationWireId = genLabel,
                            phaseWireCode = LiveConnectionPhase.DISCONNECTED.wireCode,
                            handshakeVerified = false,
                            redactedSummary = "Cancelled ($genLabel): $safeReason"
                        )
                    )
                }
            } else {
                jobToCancel = null
                genToClose = null
            }
        }
        jobToCancel?.cancel()
        if (genToClose != null) {
            effectiveDriver.closeSocket(genToClose)
        }
    }

    /**
     * Immediately disconnects any active or connecting Gemini Live session, cancels scheduled
     * retries and auto-reconnect eligibility, invalidates the active [LiveSessionGenerationId]
     * so late callbacks are dropped as stale, and releases socket resources.
     */
    fun disconnectAndRelease(reason: String) {
        val prevGen: LiveSessionGenerationId?
        val jobToCancel: Job?
        val safeReason = DiagnosticRedactor.redact(reason)
        synchronized(lock) {
            operatorHalted = true
            eligibleForSingleAutoReconnect = false
            autoReconnectPerformedForOutage = false
            activeConnectRequestId = connectRequestSeq.incrementAndGet()
            prevGen = activeGenerationId
            activeGenerationId = null
            jobToCancel = activeConnectJob
            activeConnectJob = null
            updateStateAndNotify { prev ->
                val genLabel = prevGen?.wireId ?: prev.diagnostics.sessionGenerationWireId
                prev.copy(
                    phase = LiveConnectionPhase.DISCONNECTED,
                    activeGeneration = null,
                    lastCompletedGeneration = prevGen ?: prev.lastCompletedGeneration,
                    handshakeVerified = false,
                    audioPlaybackClaimed = false,
                    autoRetryStoppedReason = "DISCONNECTED ($safeReason)",
                    statusHeadline = "LIVE ADAPTER DISCONNECTED",
                    statusDetail = "Disconnected ($genLabel): $safeReason",
                    diagnostics = prev.diagnostics.copy(
                        sessionGenerationWireId = genLabel,
                        phaseWireCode = LiveConnectionPhase.DISCONNECTED.wireCode,
                        handshakeVerified = false,
                        audioPlaybackClaimed = false,
                        redactedSummary = "Disconnected ($genLabel): $safeReason"
                    )
                )
            }
        }
        jobToCancel?.cancel()
        effectiveDriver.closeSocket(prevGen)
    }

    /**
     * Suspends until the old receive collector is cancelled/joined and the underlying LiveSession
     * is closed once off the main thread.
     */
    suspend fun disconnectAndReleaseAsync(reason: String) {
        disconnectAndRelease(reason)
        withContext(backgroundDispatcher + NonCancellable) {
            effectiveDriver.closeAndAwaitSocket(null)
        }
    }

    /**
     * Recreates only the Gemini Live session (never the Activity/app) after an unexpected server
     * turn interruption so the next turn starts with a clean session state.
     */
    suspend fun recreateSessionAfterInterruption(
        sessionId: SessionId,
        reason: String
    ): LiveConnectionState {
        disconnectAndReleaseAsync("Session recreated after server turn interruption ($reason)")
        return connectWithBoundedRetry(sessionId, reuseIfHealthy = false)
    }
}
