package com.example.live

import com.example.contract.SessionId
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.ActivityDetectionConfig
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.ContextWindowCompressionConfig
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InlineData
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.LiveServerContent
import com.google.firebase.ai.type.LiveServerGoAway
import com.google.firebase.ai.type.LiveServerSetupComplete
import com.google.firebase.ai.type.LiveSessionResumptionUpdate
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.RealtimeInputConfig
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SessionResumptionConfig
import com.google.firebase.ai.type.SlidingWindow
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.TextPart
import com.google.firebase.ai.type.Voice
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
        val serverNote: String,
        val resumedWithServerHandle: Boolean = false,
        val handleRejectedFallbackUsed: Boolean = false
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
        systemInstruction: String = LiveConfigConstants.AUTHORITATIVE_SYSTEM_INSTRUCTION,
        voiceName: String = LiveConfigConstants.DEFAULT_NATIVE_AUDIO_VOICE
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

    fun hasScopedResumptionHandle(sessionId: SessionId): Boolean = false

    fun clearScopedResumptionState(sessionId: SessionId? = null) {}

    fun closeSocket(generationId: LiveSessionGenerationId?)

    suspend fun closeAndAwaitSocket(generationId: LiveSessionGenerationId?) {
        closeSocket(generationId)
    }
}

/**
 * Classifies exceptions thrown during Gemini Live WebSocket setup or session negotiation
 * by inspecting the entire exception and cause chain.
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

        // 7. Remaining validated-network socket/channel/IO interruptions
        if (allNodes.any { isNetworkOrIoThrowable(it) }) {
            return LiveFailureClassification.NETWORK_ERROR
        }

        // 8. Default unrecognized runtime transport failures to NETWORK_ERROR
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
            lower.contains("session_expired_or_go_away") ||
                lower.contains("liveservergoaway") ||
                lower.contains("go_away") ||
                lower.contains("goaway") ||
                lower.contains("session expired") ||
                lower.contains("session_expired") ||
                lower.contains("timeleft=") ->
                LiveFailureClassification.SESSION_EXPIRED_OR_GO_AWAY
            lower.contains("local_configuration_failure") ||
                lower.contains("vad_model_load_failed") ->
                LiveFailureClassification.LOCAL_CONFIGURATION_FAILURE
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
    // Private session-scoped resumption handle store. Never exposed outside this driver.
    private val scopedResumptionHandles = LinkedHashMap<String, String>()
    private val scopedResumableStatus = LinkedHashMap<String, Boolean>()
    private val driverScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private fun logDriver(message: String) {
        try {
            android.util.Log.i("OmniVoiceLive", DiagnosticRedactor.redact(message))
        } catch (_: Throwable) {
        }
    }

    override fun hasScopedResumptionHandle(sessionId: SessionId): Boolean {
        return synchronized(lock) {
            val handle = scopedResumptionHandles[sessionId.value]
            val resumable = scopedResumableStatus[sessionId.value] ?: true
            !handle.isNullOrBlank() && resumable
        }
    }

    override fun clearScopedResumptionState(sessionId: SessionId?) {
        synchronized(lock) {
            if (sessionId == null) {
                scopedResumptionHandles.clear()
                scopedResumableStatus.clear()
            } else {
                scopedResumptionHandles.remove(sessionId.value)
                scopedResumableStatus.remove(sessionId.value)
            }
        }
    }

    private fun storeScopedResumptionUpdateLocked(
        sessionId: SessionId,
        newHandle: String?,
        resumable: Boolean?
    ): Boolean {
        while (scopedResumptionHandles.size >= 8 && !scopedResumptionHandles.containsKey(sessionId.value)) {
            val oldestKey = scopedResumptionHandles.keys.firstOrNull() ?: break
            scopedResumptionHandles.remove(oldestKey)
            scopedResumableStatus.remove(oldestKey)
        }
        val effectiveResumable = resumable ?: (!newHandle.isNullOrBlank())
        scopedResumableStatus[sessionId.value] = effectiveResumable
        if (!newHandle.isNullOrBlank() && effectiveResumable) {
            scopedResumptionHandles[sessionId.value] = newHandle
        } else if (resumable == false) {
            scopedResumptionHandles.remove(sessionId.value)
        }
        val currentHandle = scopedResumptionHandles[sessionId.value]
        return !currentHandle.isNullOrBlank() && effectiveResumable
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
        systemInstruction: String,
        voiceName: String
    ): LiveHandshakeResult {
        return driverLifecycleMutex.withLock {
            closeAndAwaitSocket(null)

            if (networkMonitor != null && !networkMonitor.isInternetValidated()) {
                return@withLock LiveHandshakeResult.Failed(
                    generationId = generationId,
                    classification = LiveFailureClassification.NETWORK_UNAVAILABLE,
                    rawDetail = "NETWORK_UNAVAILABLE: Active network lacks NET_CAPABILITY_INTERNET and NET_CAPABILITY_VALIDATED. Firebase AI Logic and App Check were not called."
                )
            }

            if (modelId.isBlank() || (!modelId.contains("live", ignoreCase = true) && !modelId.contains("native-audio", ignoreCase = true))) {
                return@withLock LiveHandshakeResult.Failed(
                    generationId = generationId,
                    classification = LiveFailureClassification.UNSUPPORTED_MODEL_OR_ENDPOINT,
                    rawDetail = "Unsupported or non-Live model identifier '$modelId' on ${LiveConfigConstants.ENDPOINT_FAMILY.wireCode}"
                )
            }

            try {
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
                    speechConfig = SpeechConfig(
                        voice = Voice(LiveConfigConstants.DEFAULT_NATIVE_AUDIO_VOICE)
                    )
                    inputAudioTranscription = AudioTranscriptionConfig()
                    outputAudioTranscription = AudioTranscriptionConfig()
                    contextWindowCompression = ContextWindowCompressionConfig(
                        slidingWindow = SlidingWindow()
                    )
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
                    tools = emptyList(),
                    systemInstruction = sysInstructionContent
                )

                val candidateHandle = synchronized(lock) {
                    val resumable = scopedResumableStatus[generationId.sessionId.value] ?: true
                    if (resumable) scopedResumptionHandles[generationId.sessionId.value] else null
                }

                var resumedWithHandle = false
                var handleRejectedFallbackUsed = false

                val session = withContext(ioDispatcher) {
                    if (!candidateHandle.isNullOrBlank()) {
                        try {
                            val connected = withTimeout(handshakeTimeoutMs) {
                                liveModel.connect(SessionResumptionConfig(handle = candidateHandle))
                            }
                            resumedWithHandle = true
                            connected
                        } catch (handleErr: Throwable) {
                            if (handleErr is CancellationException && handleErr !is TimeoutCancellationException) {
                                throw handleErr
                            }
                            val validatedNow = networkMonitor?.isInternetValidated() ?: true
                            if (!validatedNow) {
                                throw handleErr
                            }
                            // Clear rejected or expired handle and fall back to a clean connection without the handle
                            synchronized(lock) {
                                scopedResumptionHandles.remove(generationId.sessionId.value)
                                scopedResumableStatus.remove(generationId.sessionId.value)
                            }
                            resumedWithHandle = false
                            handleRejectedFallbackUsed = true
                            logDriver("SESSION_RESUMPTION_HANDLE_REJECTED: Cleared rejected/expired handle for ${generationId.sessionId.value} and falling back to clean connection.")
                            withTimeout(handshakeTimeoutMs) {
                                liveModel.connect(SessionResumptionConfig())
                            }
                        }
                    } else {
                        withTimeout(handshakeTimeoutMs) {
                            liveModel.connect(SessionResumptionConfig())
                        }
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
                                                serverNote = "LiveServerSetupComplete frame verified on ${generationId.wireId}",
                                                resumedWithServerHandle = resumedWithHandle,
                                                handleRejectedFallbackUsed = handleRejectedFallbackUsed
                                            )
                                        )
                                    }
                                    is LiveSessionResumptionUpdate -> {
                                        val hasHandleNow = synchronized(lock) {
                                            storeScopedResumptionUpdateLocked(
                                                sessionId = generationId.sessionId,
                                                newHandle = message.newHandle,
                                                resumable = message.resumable
                                            )
                                        }
                                        onSocketEvent(
                                            LiveSocketCallbackEvent.SessionResumptionUpdated(
                                                generationId = generationId,
                                                resumable = message.resumable ?: hasHandleNow,
                                                hasScopedHandle = hasHandleNow,
                                                lastConsumedClientMessageIndex = message.lastConsumedClientMessageIndex
                                            )
                                        )
                                    }
                                    is LiveServerGoAway -> {
                                        val goAwayMs = message.timeLeft?.inWholeMilliseconds
                                        val goAwayReason =
                                            "SESSION_EXPIRED_OR_GO_AWAY: LiveServerGoAway received from Gemini Live endpoint (timeLeftMs=${goAwayMs ?: "UNSPECIFIED"})"
                                        onSocketEvent(
                                            LiveSocketCallbackEvent.ServerGoAwayReceived(
                                                generationId = generationId,
                                                timeLeftMs = goAwayMs,
                                                reason = goAwayReason
                                            )
                                        )
                                        onSocketEvent(
                                            LiveSocketCallbackEvent.SocketClosedRemotely(
                                                generationId = generationId,
                                                reason = goAwayReason
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

                val attachOutcome = withTimeout(handshakeTimeoutMs) {
                    collectorAttach.await()
                }
                yield()

                val earlyError = (attachOutcome as? CollectorAttachOutcome.Failed)?.throwable
                    ?: immediateFailure.get()
                if (earlyError != null) {
                    closeAndAwaitSocket(generationId)
                    if (resumedWithHandle) {
                        synchronized(lock) {
                            scopedResumptionHandles.remove(generationId.sessionId.value)
                            scopedResumableStatus.remove(generationId.sessionId.value)
                        }
                    }
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
                val contextNote = when {
                    resumedWithHandle -> "context=RESUMED_WITH_SERVER_HANDLE"
                    handleRejectedFallbackUsed -> "context=NEW_CONNECTION_WITHOUT_RESUMED_CONTEXT(handleRejectedFallback)"
                    else -> "context=SESSION_RESUMPTION_ENABLED_COMPRESSION_SLIDING_WINDOW"
                }
                val acceptedNote =
                    "SETUP_ACCEPTED via Firebase AI Logic v1beta BidiGenerateContent ($modelId, voice=${LiveConfigConstants.DEFAULT_NATIVE_AUDIO_VOICE}, tools=0, collector=ATTACHED, $contextNote)"
                onSocketEvent(
                    LiveSocketCallbackEvent.SetupAccepted(
                        generationId = generationId,
                        serverNote = acceptedNote,
                        resumedWithServerHandle = resumedWithHandle,
                        handleRejectedFallbackUsed = handleRejectedFallbackUsed
                    )
                )
                LiveHandshakeResult.Accepted(
                    generationId = generationId,
                    serverNote = acceptedNote,
                    resumedWithServerHandle = resumedWithHandle,
                    handleRejectedFallbackUsed = handleRejectedFallbackUsed
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
        val note = "SETUP_ACCEPTED (${modelId} @ ${genId.wireId}, voice=${LiveConfigConstants.DEFAULT_NATIVE_AUDIO_VOICE}, tools=0, audioPlayback=NOT_CLAIMED)"
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
    val receivedVoiceNames = mutableListOf<String>()
    var lastReceivedVoiceName: String = LiveConfigConstants.DEFAULT_NATIVE_AUDIO_VOICE
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
    private val scopedResumptionHandles = mutableMapOf<String, String>()
    private val scopedResumableFlags = mutableMapOf<String, Boolean>()
    var rejectResumptionHandleOnNextConnect: Boolean = false
    var resumedConnectCount: Int = 0
        private set
    var rejectedHandleFallbackCount: Int = 0
        private set

    override fun hasScopedResumptionHandle(sessionId: SessionId): Boolean {
        val handle = scopedResumptionHandles[sessionId.value]
        val resumable = scopedResumableFlags[sessionId.value] ?: true
        return !handle.isNullOrBlank() && resumable
    }

    override fun clearScopedResumptionState(sessionId: SessionId?) {
        if (sessionId == null) {
            scopedResumptionHandles.clear()
            scopedResumableFlags.clear()
        } else {
            scopedResumptionHandles.remove(sessionId.value)
            scopedResumableFlags.remove(sessionId.value)
        }
    }

    /**
     * Simulates the server sending a [LiveSessionResumptionUpdate] while keeping the raw handle
     * strictly private inside the transport driver.
     */
    fun simulateServerResumptionUpdateForTest(
        generationId: LiveSessionGenerationId,
        rawHandle: String?,
        resumable: Boolean = true,
        lastConsumedClientMessageIndex: Int? = null
    ) {
        val sessionKey = generationId.sessionId.value
        scopedResumableFlags[sessionKey] = resumable
        if (!rawHandle.isNullOrBlank() && resumable) {
            scopedResumptionHandles[sessionKey] = rawHandle
        } else if (!resumable) {
            scopedResumptionHandles.remove(sessionKey)
        }
        val hasHandleNow = hasScopedResumptionHandle(generationId.sessionId)
        activeCallback?.invoke(
            LiveSocketCallbackEvent.SessionResumptionUpdated(
                generationId = generationId,
                resumable = resumable,
                hasScopedHandle = hasHandleNow,
                lastConsumedClientMessageIndex = lastConsumedClientMessageIndex
            )
        )
    }

    /**
     * Simulates the server sending a [LiveServerGoAway] notice on the active generation.
     */
    fun simulateServerGoAwayForTest(
        generationId: LiveSessionGenerationId,
        timeLeftMs: Long? = 5_000L
    ) {
        val reason = "SESSION_EXPIRED_OR_GO_AWAY: LiveServerGoAway received from Gemini Live endpoint (timeLeftMs=${timeLeftMs ?: "UNSPECIFIED"})"
        activeCallback?.invoke(
            LiveSocketCallbackEvent.ServerGoAwayReceived(
                generationId = generationId,
                timeLeftMs = timeLeftMs,
                reason = reason
            )
        )
        activeCallback?.invoke(
            LiveSocketCallbackEvent.SocketClosedRemotely(
                generationId = generationId,
                reason = reason
            )
        )
    }

    override fun isReceiverCollectorAttached(generationId: LiveSessionGenerationId): Boolean {
        return lastConnectedGenId == generationId && isCollectorAttachedForCurrentGen
    }

    override suspend fun connectAndHandshake(
        generationId: LiveSessionGenerationId,
        modelId: String,
        onSocketEvent: (LiveSocketCallbackEvent) -> Unit,
        systemInstruction: String,
        voiceName: String
    ): LiveHandshakeResult {
        val attempt = attemptCounter.incrementAndGet().toInt()
        lastConnectedGenId = generationId
        activeCallback = onSocketEvent
        isCollectorAttachedForCurrentGen = true
        isSetupAcceptedForCurrentGen = false
        collectorAttachedGenerations.add(generationId)
        lastReceivedSystemInstruction = systemInstruction
        receivedSystemInstructions.add(systemInstruction)
        lastReceivedVoiceName = voiceName
        receivedVoiceNames.add(voiceName)

        val hadScopedHandle = hasScopedResumptionHandle(generationId.sessionId)
        var resumedWithHandle = false
        var handleRejectedFallback = false
        if (hadScopedHandle) {
            if (rejectResumptionHandleOnNextConnect) {
                rejectResumptionHandleOnNextConnect = false
                clearScopedResumptionState(generationId.sessionId)
                handleRejectedFallback = true
                rejectedHandleFallbackCount += 1
            } else {
                resumedWithHandle = true
                resumedConnectCount += 1
            }
        }

        val wrappedCallback: (LiveSocketCallbackEvent) -> Unit = { event ->
            if (event is LiveSocketCallbackEvent.SetupAccepted && event.generationId == generationId) {
                isSetupAcceptedForCurrentGen = true
                onSocketEvent(
                    event.copy(
                        resumedWithServerHandle = event.resumedWithServerHandle || resumedWithHandle,
                        handleRejectedFallbackUsed = event.handleRejectedFallbackUsed || handleRejectedFallback
                    )
                )
            } else {
                onSocketEvent(event)
            }
        }
        activeCallback = wrappedCallback
        val result = scriptedHandler(generationId, modelId, attempt, wrappedCallback)
        if (result is LiveHandshakeResult.Accepted) {
            isSetupAcceptedForCurrentGen = true
            return result.copy(
                resumedWithServerHandle = result.resumedWithServerHandle || resumedWithHandle,
                handleRejectedFallbackUsed = result.handleRejectedFallbackUsed || handleRejectedFallback
            )
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
 * Single Gemini Live Connection Adapter for OmniVoice Rebuild (Phase 03–06A).
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
    private val establishedSessionIds = LinkedHashSet<String>()

    /**
     * Optional coordinator predicate checked before initiating an automatic network-restore reconnect.
     * Prevents competing reconnect loops when ConversationCoordinator is paused, stopped, or already
     * executing a bounded recovery.
     */
    @Volatile
    var canAutoReconnectOnNetworkRestore: () -> Boolean = { true }

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
            statusDetail = "Ready to verify $configuredModelId handshake via Firebase AI Logic (${LiveConfigConstants.LIVE_API_VERSION}).",
            diagnostics = RedactedLiveDiagnostics(
                modelId = configuredModelId,
                systemInstruction = systemInstruction,
                isInternetValidated = lastKnownValidated,
                redactedSummary = "Idle. Ready to verify $configuredModelId handshake via Firebase AI Logic (${LiveConfigConstants.LIVE_API_VERSION})."
            )
        )
    )
    val state: StateFlow<LiveConnectionState> = _state.asStateFlow()

    @Volatile
    var onStateChanged: ((LiveConnectionState) -> Unit)? = null

    @Volatile
    var onServerEventListener: ((LiveSocketCallbackEvent) -> Unit)? = null

    private fun updateStateAndNotify(transform: (LiveConnectionState) -> LiveConnectionState) {
        _state.update(transform)
        onStateChanged?.invoke(_state.value)
    }

    fun clearSessionResumptionState(sessionId: SessionId? = null) {
        effectiveDriver.clearScopedResumptionState(sessionId)
        synchronized(lock) {
            if (sessionId == null) {
                establishedSessionIds.clear()
            } else {
                establishedSessionIds.remove(sessionId.value)
            }
            val hasHandle = effectiveDriver.hasScopedResumptionHandle(lastSessionId)
            updateStateAndNotify { prev ->
                prev.copy(
                    sessionResumptionStatus = SessionResumptionStatus.CLEARED_ON_STOP_OR_RESET,
                    contextRestorationStatus = ContextRestorationStatus.INITIAL_NEW_SESSION,
                    hasScopedResumptionHandle = hasHandle,
                    diagnostics = prev.diagnostics.copy(
                        sessionResumptionStatus = SessionResumptionStatus.CLEARED_ON_STOP_OR_RESET.wireCode,
                        contextRestorationStatus = ContextRestorationStatus.INITIAL_NEW_SESSION.wireCode,
                        hasScopedResumptionHandle = hasHandle
                    )
                )
            }
        }
    }

    fun startNetworkMonitoring() {
        networkMonitor.startMonitoring(::onNetworkValidatedChanged)
    }

    fun stopNetworkMonitoring() {
        networkMonitor.stopMonitoring()
    }

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

        var shouldTriggerSingleReconnect = false
        val reconnectSessionId: SessionId
        val coordinatorAllowsAutoReconnect = try {
            canAutoReconnectOnNetworkRestore()
        } catch (_: Throwable) {
            true
        }
        synchronized(lock) {
            val wasOffline =
                !lastKnownValidated ||
                    _state.value.phase == LiveConnectionPhase.NETWORK_UNAVAILABLE ||
                    !_state.value.isInternetValidated

            lastKnownValidated = true

            if (!wasOffline) {
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

            if (inFlightConnectDeferred?.isActive == true || isHealthyEstablishedSessionLocked()) {
                return
            }

            if (operatorHalted || !coordinatorAllowsAutoReconnect) {
                eligibleForSingleAutoReconnect = false
                val haltedDetail =
                    "NETWORK_RESTORED: Validated internet restored while session is paused/stopped or managed by coordinator; automatic reconnect suppressed."
                updateStateAndNotify { current ->
                    current.copy(
                        isInternetValidated = true,
                        diagnostics = current.diagnostics.copy(
                            isInternetValidated = true,
                            redactedSummary = haltedDetail
                        )
                    )
                }
                return
            }

            shouldTriggerSingleReconnect =
                autoReconnectOnNetworkRestore &&
                    eligibleForSingleAutoReconnect &&
                    !autoReconnectPerformedForOutage &&
                    !operatorHalted &&
                    coordinatorAllowsAutoReconnect

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

    private fun nextGenerationId(sessionId: SessionId, attempt: Int): LiveSessionGenerationId {
        val seq = generationSeq.incrementAndGet()
        return LiveSessionGenerationId(
            sequenceNumber = seq,
            sessionId = sessionId,
            wireId = "live-gen-${seq.toString().padStart(4, '0')}-${sessionId.value}-att$attempt"
        )
    }

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
                        val sessionKey = currentActive.sessionId.value
                        val hadPreviousConnectionInSession =
                            establishedSessionIds.contains(sessionKey) &&
                                _state.value.lastCompletedGeneration?.let {
                                    it != currentActive && it.sessionId.value == sessionKey
                                } == true
                        while (establishedSessionIds.size >= 16 && !establishedSessionIds.contains(sessionKey)) {
                            val oldest = establishedSessionIds.iterator().next()
                            establishedSessionIds.remove(oldest)
                        }
                        establishedSessionIds.add(sessionKey)

                        val hasScopedHandleNow = effectiveDriver.hasScopedResumptionHandle(currentActive.sessionId)
                        val newContextStatus = when {
                            event.resumedWithServerHandle ->
                                ContextRestorationStatus.RESUMED_WITH_SERVER_HANDLE
                            event.handleRejectedFallbackUsed || hadPreviousConnectionInSession ->
                                ContextRestorationStatus.NEW_CONNECTION_WITHOUT_RESUMED_CONTEXT
                            else ->
                                ContextRestorationStatus.INITIAL_NEW_SESSION
                        }
                        val newResumptionStatus = when {
                            event.resumedWithServerHandle ->
                                SessionResumptionStatus.RESUMED_WITH_SERVER_HANDLE
                            event.handleRejectedFallbackUsed ->
                                SessionResumptionStatus.HANDLE_REJECTED_FELL_BACK_NEW_CONNECTION
                            hasScopedHandleNow ->
                                SessionResumptionStatus.HANDLE_AVAILABLE_RESUMABLE
                            else ->
                                SessionResumptionStatus.ENABLED_AWAITING_HANDLE
                        }

                        updateStateAndNotify { prev ->
                            val preservedPhase = if (
                                prev.phase == LiveConnectionPhase.ACTIVE &&
                                prev.activeGeneration == currentActive
                            ) {
                                LiveConnectionPhase.ACTIVE
                            } else {
                                LiveConnectionPhase.SETUP_ACCEPTED
                            }
                            val alreadyCountedThisGen = prev.handshakeVerified && prev.activeGeneration == currentActive
                            val newResumedCount = if (!alreadyCountedThisGen && event.resumedWithServerHandle) {
                                prev.sessionResumptionSuccessCount + 1
                            } else {
                                prev.sessionResumptionSuccessCount
                            }
                            val newFallbackCount = if (!alreadyCountedThisGen && event.handleRejectedFallbackUsed) {
                                prev.sessionResumptionFallbackCount + 1
                            } else {
                                prev.sessionResumptionFallbackCount
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
                                sessionResumptionStatus = newResumptionStatus,
                                contextRestorationStatus = newContextStatus,
                                hasScopedResumptionHandle = hasScopedHandleNow,
                                sessionResumptionSuccessCount = newResumedCount,
                                sessionResumptionFallbackCount = newFallbackCount,
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
                                    sessionResumptionStatus = newResumptionStatus.wireCode,
                                    contextRestorationStatus = newContextStatus.wireCode,
                                    hasScopedResumptionHandle = hasScopedHandleNow,
                                    sessionResumptionSuccessCount = newResumedCount,
                                    sessionResumptionFallbackCount = newFallbackCount,
                                    redactedSummary = safeNote
                                )
                            )
                        }
                    }

                    is LiveSocketCallbackEvent.SessionResumptionUpdated -> {
                        closeGenAfterCallback = null
                        val newStatus = if (event.resumable && event.hasScopedHandle) {
                            SessionResumptionStatus.HANDLE_AVAILABLE_RESUMABLE
                        } else if (!event.resumable) {
                            SessionResumptionStatus.HANDLE_UNRESUMABLE
                        } else {
                            SessionResumptionStatus.ENABLED_AWAITING_HANDLE
                        }
                        updateStateAndNotify { prev ->
                            val newUpdates = prev.sessionResumptionUpdatesReceived + 1
                            prev.copy(
                                sessionResumptionStatus = newStatus,
                                hasScopedResumptionHandle = event.hasScopedHandle,
                                sessionResumptionUpdatesReceived = newUpdates,
                                diagnostics = prev.diagnostics.copy(
                                    sessionResumptionStatus = newStatus.wireCode,
                                    hasScopedResumptionHandle = event.hasScopedHandle,
                                    sessionResumptionUpdatesReceived = newUpdates
                                )
                            )
                        }
                    }

                    is LiveSocketCallbackEvent.ServerGoAwayReceived -> {
                        closeGenAfterCallback = null
                        updateStateAndNotify { prev ->
                            val newGoAwayCount = prev.serverGoAwayCount + 1
                            prev.copy(
                                serverGoAwayCount = newGoAwayCount,
                                lastServerGoAwayTimeLeftMs = event.timeLeftMs,
                                diagnostics = prev.diagnostics.copy(
                                    serverGoAwayCount = newGoAwayCount,
                                    lastServerGoAwayTimeLeftMs = event.timeLeftMs
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
            event is LiveSocketCallbackEvent.ServerTranscriptionReceived ||
            event is LiveSocketCallbackEvent.ServerGoAwayReceived ||
            event is LiveSocketCallbackEvent.SessionResumptionUpdated
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

    fun isSessionReadyForAudio(): Boolean {
        if (!networkMonitor.isInternetValidated()) return false
        return synchronized(lock) {
            isHealthyEstablishedSessionLocked() && onServerEventListener != null
        }
    }

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

    suspend fun ensureConnectedSession(sessionId: SessionId): LiveConnectionState {
        return executeHandshakeWithPolicy(
            sessionId = sessionId,
            maxAttemptsLimit = backoffPolicy.maxAttempts,
            isControlledAutoReconnect = false,
            reuseIfHealthy = true
        )
    }

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
        val myDeferred: CompletableDeferred<LiveConnectionState>
        var existingToAwait: CompletableDeferred<LiveConnectionState>? = null
        var joinedWasAutoOnly = false

        synchronized(lock) {
            if (lastSessionId != sessionId) {
                effectiveDriver.clearScopedResumptionState(lastSessionId)
            }
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
                            systemInstruction = systemInstruction,
                            voiceName = LiveConfigConstants.DEFAULT_NATIVE_AUDIO_VOICE
                        )
                    } catch (ce: CancellationException) {
                        withContext(backgroundDispatcher + NonCancellable) {
                            effectiveDriver.closeAndAwaitSocket(genId)
                        }
                        throw ce
                    }

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
                                    serverNote = result.serverNote,
                                    resumedWithServerHandle = result.resumedWithServerHandle,
                                    handleRejectedFallbackUsed = result.handleRejectedFallbackUsed
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

    suspend fun disconnectAndReleaseAsync(reason: String) {
        disconnectAndRelease(reason)
        withContext(backgroundDispatcher + NonCancellable) {
            effectiveDriver.closeAndAwaitSocket(null)
        }
    }

    suspend fun recreateSessionAfterInterruption(
        sessionId: SessionId,
        reason: String
    ): LiveConnectionState {
        disconnectAndReleaseAsync("Session recreated after server turn interruption ($reason)")
        return connectWithBoundedRetry(sessionId, reuseIfHealthy = false)
    }
}

