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
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

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
        val causeChain = generateSequence(throwable) { it.cause }.take(12).toList()

        // 1. Cooperative cancellation (excluding TimeoutCancellationException which is a network/handshake timeout)
        if (causeChain.any { it is CancellationException && it !is TimeoutCancellationException }) {
            return LiveFailureClassification.CANCELLED_BY_STOP_OR_PAUSE
        }

        // 2. If validated internet is not available, any transport/SDK error is a network error, never auth/credentials
        if (!isInternetValidated) {
            return LiveFailureClassification.NETWORK_ERROR
        }

        // 3. Inspect every node in the cause chain for network, DNS, timeout, or socket/IO failures FIRST
        // so that IOExceptions or UnresolvedAddressExceptions wrapped inside Firebase/AppCheck exceptions
        // are never mislabeled as BAD_CREDENTIALS_OR_APP_CHECK.
        if (causeChain.any { isNetworkOrIoThrowable(it) }) {
            return LiveFailureClassification.NETWORK_ERROR
        }

        val combinedLower = causeChain.joinToString(" | ") { node ->
            "${node::class.simpleName ?: "Exception"}: ${node.message ?: ""}"
        }.lowercase()

        // 4. Check for explicit HTTP 400/404 or unsupported model/endpoint
        if (hasHttpStatusCode(combinedLower, 404) ||
            hasHttpStatusCode(combinedLower, 400) ||
            combinedLower.contains("not_found") ||
            combinedLower.contains("not found") ||
            combinedLower.contains("unsupported model") ||
            combinedLower.contains("model is not supported") ||
            combinedLower.contains("is not found for api version") ||
            combinedLower.contains("invalid_argument")
        ) {
            return LiveFailureClassification.UNSUPPORTED_MODEL_OR_ENDPOINT
        }

        // 5. Check for explicit HTTP 429 or quota exhaustion
        if (hasHttpStatusCode(combinedLower, 429) ||
            combinedLower.contains("resource_exhausted") ||
            combinedLower.contains("quota") ||
            combinedLower.contains("rate limit") ||
            combinedLower.contains("too many requests")
        ) {
            return LiveFailureClassification.QUOTA_EXHAUSTED
        }

        // 6. Check for actual HTTP 401/403 or explicit App Check / credential rejection while internet is validated
        if (hasHttpStatusCode(combinedLower, 401) ||
            hasHttpStatusCode(combinedLower, 403) ||
            combinedLower.contains("unauthenticated") ||
            combinedLower.contains("permission_denied") ||
            combinedLower.contains("permission denied") ||
            combinedLower.contains("api_key_invalid") ||
            combinedLower.contains("invalid api key") ||
            combinedLower.contains("bad_credentials") ||
            combinedLower.contains("firebaseapp is not initialized") ||
            combinedLower.contains("blocked_prerequisite_unconfigured") ||
            combinedLower.contains("needs_setup") ||
            combinedLower.contains("app check") ||
            combinedLower.contains("appcheck") ||
            combinedLower.contains("attestation")
        ) {
            return LiveFailureClassification.BAD_CREDENTIALS_OR_APP_CHECK
        }

        // 7. Default unrecognized transport/runtime errors to NETWORK_ERROR (never default to auth failure)
        return LiveFailureClassification.NETWORK_ERROR
    }

    private fun isNetworkOrIoThrowable(node: Throwable): Boolean {
        if (node is TimeoutCancellationException ||
            node is UnknownHostException ||
            node is UnresolvedAddressException ||
            node is SocketTimeoutException ||
            node is ConnectException ||
            node is NoRouteToHostException ||
            node is SocketException ||
            node is SSLException ||
            node is EOFException ||
            node is InterruptedIOException ||
            node is ClosedChannelException
        ) {
            return true
        }

        val classAndMsg = "${node::class.simpleName ?: ""} ${node.message ?: ""}".lowercase()

        // Explicit network, DNS, Ktor channel, timeout, or socket indicators
        if (classAndMsg.contains("unknownhost") ||
            classAndMsg.contains("unresolvedaddress") ||
            classAndMsg.contains("unable to resolve host") ||
            classAndMsg.contains("no address associated with hostname") ||
            classAndMsg.contains("eai_nodata") ||
            classAndMsg.contains("enetunreach") ||
            classAndMsg.contains("ehostunreach") ||
            classAndMsg.contains("network is unreachable") ||
            classAndMsg.contains("network_error") ||
            classAndMsg.contains("network error") ||
            classAndMsg.contains("network_unavailable") ||
            classAndMsg.contains("no internet") ||
            classAndMsg.contains("offline") ||
            classAndMsg.contains("timed out") ||
            classAndMsg.contains("timeout") ||
            classAndMsg.contains("httprequesttimeout") ||
            classAndMsg.contains("connecttimeout") ||
            classAndMsg.contains("sockettimeout") ||
            classAndMsg.contains("deadline exceeded") ||
            classAndMsg.contains("connection reset") ||
            classAndMsg.contains("connection refused") ||
            classAndMsg.contains("connection abort") ||
            classAndMsg.contains("software caused connection abort") ||
            classAndMsg.contains("broken pipe") ||
            classAndMsg.contains("socket closed") ||
            classAndMsg.contains("closedreceivechannel") ||
            classAndMsg.contains("closedsendchannel") ||
            classAndMsg.contains("unexpected end of stream") ||
            classAndMsg.contains("websocket eof") ||
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
    private val handshakeTimeoutMs: Long = 25_000L
) : LiveTransportDriver {

    private val lock = Any()
    private var activeGenerationId: LiveSessionGenerationId? = null
    private var receiverCollectorAttachedGen: LiveSessionGenerationId? = null
    private var activeLiveSession: com.google.firebase.ai.type.LiveSession? = null
    private var activeReceiveJob: Job? = null
    private val driverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun isReceiverCollectorAttached(generationId: LiveSessionGenerationId): Boolean {
        return synchronized(lock) {
            activeGenerationId == generationId && receiverCollectorAttachedGen == generationId
        }
    }

    override suspend fun connectAndHandshake(
        generationId: LiveSessionGenerationId,
        modelId: String,
        onSocketEvent: (LiveSocketCallbackEvent) -> Unit,
        systemInstruction: String
    ): LiveHandshakeResult {
        closeSocket(null)

        // Requirement 4 & 5: If no validated network exists, do NOT call Firebase AI Logic or request App Check token.
        if (networkMonitor != null && !networkMonitor.isInternetValidated()) {
            return LiveHandshakeResult.Failed(
                generationId = generationId,
                classification = LiveFailureClassification.NETWORK_UNAVAILABLE,
                rawDetail = "NETWORK_UNAVAILABLE: Active network lacks NET_CAPABILITY_INTERNET and NET_CAPABILITY_VALIDATED. Firebase AI Logic and App Check were not called."
            )
        }

        return try {
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
                return LiveHandshakeResult.Failed(
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
                    .setAutomaticActivityDetection(
                        ActivityDetectionConfig.builder()
                            .setSilenceDurationMs(700)
                            .build()
                    )
                    .build()
            }
            val sysInstructionContent = content {
                text(systemInstruction)
            }
            val liveModel = Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
                modelName = modelId,
                generationConfig = liveConfig,
                tools = emptyList(), // Phase 03/04: zero action tools registered
                systemInstruction = sysInstructionContent
            )

            val session = withTimeout(handshakeTimeoutMs) {
                liveModel.connect()
            }

            val collectorReady = CompletableDeferred<Unit>()
            synchronized(lock) {
                activeGenerationId = generationId
                receiverCollectorAttachedGen = null
                activeLiveSession = session
                activeReceiveJob = driverScope.launch {
                    session.receive()
                        .onStart {
                            synchronized(lock) {
                                if (activeGenerationId == generationId) {
                                    receiverCollectorAttachedGen = generationId
                                }
                            }
                            collectorReady.complete(Unit)
                        }
                        .catch { err ->
                            collectorReady.complete(Unit)
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
                        .collect { message ->
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
                                            reason = "LiveServerGoAway received from Gemini Live endpoint"
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

                                    // Wait specifically for turnComplete (or interrupted) — never treat generationComplete alone as turnComplete
                                    // because final audio InlineDataPart frames can arrive after generationComplete until turnComplete = true.
                                    if (message.turnComplete || message.interrupted) {
                                        onSocketEvent(
                                            LiveSocketCallbackEvent.ServerTurnCompleted(
                                                generationId = generationId,
                                                interrupted = message.interrupted
                                            )
                                        )
                                    }
                                }
                                else -> {
                                    // Ignore unrecognized frame types; zero action tools are registered in Phase 04.
                                }
                            }
                        }
                }
            }

            // Guarantee the session.receive() collector is actively attached BEFORE SETUP_ACCEPTED
            // and BEFORE the first microphone frame can ever be transmitted on a cold launch!
            withTimeout(handshakeTimeoutMs) {
                collectorReady.await()
            }

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
            session.sendAudioRealtime(InlineData(pcm16kHzBytes, mimeType))
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
            // Use the single documented realtimeInput VAD mechanism: stream 960ms of paced 16kHz PCM
            // trailing silence (6 x 160ms chunks, exceeding the configured 700ms silenceDurationMs)
            // so server VAD deterministically commits the first spoken user turn without sending a
            // conflicting clientContent empty user turn.
            val silenceChunk160Ms = ByteArray(5120)
            repeat(6) { idx ->
                val stillActive = synchronized(lock) { activeGenerationId == generationId }
                if (!stillActive) return false
                session.sendAudioRealtime(InlineData(silenceChunk160Ms, "audio/pcm;rate=16000"))
                if (idx < 5) {
                    delay(20L)
                }
            }
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
                return
            }
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
                    sessionToClose.stopReceiving()
                } catch (_: Throwable) {
                }
                try {
                    sessionToClose.close()
                } catch (_: Throwable) {
                }
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
     *   Immediately invalidates any stale `SETUP_ACCEPTED` or active connection, cancels scheduled retries,
     *   closes the socket cleanly, and transitions to `NETWORK_UNAVAILABLE`.
     * - When `isValidated == true`:
     *   Transitions from offline state to `NETWORK_RESTORED` (`READY_TO_RECONNECT`), and performs at most
     *   one controlled automatic reconnect if an active/requested session was interrupted by the outage.
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
                        prev.phase == LiveConnectionPhase.CONNECTING ||
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
                    isControlledAutoReconnect = true
                )
            }
            synchronized(lock) {
                if (activeConnectJob == null) {
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
     * Stale callbacks from superseded or closed sockets are deterministically rejected and counted.
     */
    fun onSocketCallback(event: LiveSocketCallbackEvent): Boolean {
        val closeGenAfterCallback: LiveSessionGenerationId?
        val acceptedWhileOnline: Boolean
        synchronized(lock) {
            val currentActive = activeGenerationId
            if (currentActive == null || currentActive != event.generationId) {
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
                            prev.copy(
                                phase = LiveConnectionPhase.SETUP_ACCEPTED,
                                activeGeneration = currentActive,
                                lastCompletedGeneration = currentActive,
                                failureClassification = LiveFailureClassification.NONE,
                                isInternetValidated = true,
                                handshakeVerified = true,
                                isReceiverCollectorAttached = collectorAttached,
                                audioPlaybackClaimed = prev.audioPlaybackClaimed,
                                registeredActionToolsCount = 0,
                                statusHeadline = "LIVE HANDSHAKE VERIFIED (SETUP_ACCEPTED)",
                                statusDetail = safeNote,
                                diagnostics = prev.diagnostics.copy(
                                    modelId = configuredModelId,
                                    sessionGenerationWireId = currentActive.wireId,
                                    phaseWireCode = LiveConnectionPhase.SETUP_ACCEPTED.wireCode,
                                    failureCode = LiveFailureClassification.NONE.wireCode,
                                    isInternetValidated = true,
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
                                _state.value.handshakeVerified
                        if (wasEstablished) {
                            // If an active SETUP_ACCEPTED socket drops with a transport error milliseconds before
                            // NetworkCallback.onLost fires, invalidate activeGenerationId and preserve reconnect eligibility.
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
                        activeGenerationId = null
                        closeGenAfterCallback = currentActive
                        if (!operatorHalted) {
                            eligibleForSingleAutoReconnect = true
                            autoReconnectPerformedForOutage = false
                        }
                        updateStateAndNotify { prev ->
                            prev.copy(
                                phase = LiveConnectionPhase.DISCONNECTED,
                                activeGeneration = null,
                                lastCompletedGeneration = currentActive,
                                handshakeVerified = false,
                                isReceiverCollectorAttached = false,
                                statusHeadline = "LIVE SOCKET CLOSED REMOTELY",
                                statusDetail = safeReason,
                                diagnostics = prev.diagnostics.copy(
                                    sessionGenerationWireId = currentActive.wireId,
                                    phaseWireCode = LiveConnectionPhase.DISCONNECTED.wireCode,
                                    handshakeVerified = false,
                                    isReceiverCollectorAttached = false,
                                    redactedSummary = "Remote socket closed (${currentActive.wireId}): $safeReason"
                                )
                            )
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

    /**
     * Returns true only if the Gemini Live session is currently `SETUP_ACCEPTED`, validated
     * internet is available, the coordinator's server-event collector (`onServerEventListener`)
     * is attached, and the transport driver's `session.receive()` collector is actively attached
     * for the active [LiveSessionGenerationId].
     */
    fun isSessionReadyForAudio(): Boolean {
        if (!networkMonitor.isInternetValidated()) return false
        return synchronized(lock) {
            val snap = _state.value
            val gen = activeGenerationId
            gen != null &&
                snap.phase == LiveConnectionPhase.SETUP_ACCEPTED &&
                snap.handshakeVerified &&
                snap.activeGeneration == gen &&
                onServerEventListener != null &&
                effectiveDriver.isReceiverCollectorAttached(gen)
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
     * Streams a 16kHz 16-bit mono PCM audio chunk to the currently verified active Gemini Live session.
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
            if (gen != null &&
                _state.value.phase == LiveConnectionPhase.SETUP_ACCEPTED &&
                _state.value.handshakeVerified &&
                onServerEventListener != null &&
                effectiveDriver.isReceiverCollectorAttached(gen)
            ) {
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
     * Signals end-of-spoken-turn to the currently verified active Gemini Live session.
     * Returns `false` if no verified session is active or if validated internet was lost.
     */
    suspend fun signalEndOfSpokenTurn(): Boolean {
        if (!networkMonitor.isInternetValidated()) {
            onNetworkValidatedChanged(false)
            return false
        }
        val activeGen = synchronized(lock) {
            if (_state.value.phase == LiveConnectionPhase.SETUP_ACCEPTED && _state.value.handshakeVerified) {
                activeGenerationId
            } else {
                null
            }
        } ?: return false
        return effectiveDriver.sendEndOfSpokenTurn(activeGen)
    }

    /**
     * Initiates a real Gemini Live handshake with bounded exponential backoff (up to 3 attempts)
     * for transient network/socket errors while online.
     *
     * Invariants:
     * - Verifies `networkMonitor.isInternetValidated()` before every attempt. If false, does NOT
     *   call Firebase AI Logic or App Check and immediately returns `NETWORK_UNAVAILABLE`.
     * - Never switches silently to a guessed model on failure; strictly uses [configuredModelId].
     * - Immediately stops automatic retries on [LiveFailureClassification.BAD_CREDENTIALS_OR_APP_CHECK],
     *   [LiveFailureClassification.UNSUPPORTED_MODEL_OR_ENDPOINT], [LiveFailureClassification.QUOTA_EXHAUSTED],
     *   and [LiveFailureClassification.NETWORK_UNAVAILABLE].
     * - Retries [LiveFailureClassification.NETWORK_ERROR] / [LiveFailureClassification.TRANSIENT_NETWORK_OR_SOCKET]
     *   up to [BoundedBackoffPolicy.maxAttempts] (3 attempts) while online.
     */
    suspend fun connectWithBoundedRetry(sessionId: SessionId): LiveConnectionState {
        return executeHandshakeWithPolicy(
            sessionId = sessionId,
            maxAttemptsLimit = backoffPolicy.maxAttempts,
            isControlledAutoReconnect = false
        )
    }

    private suspend fun executeHandshakeWithPolicy(
        sessionId: SessionId,
        maxAttemptsLimit: Int,
        isControlledAutoReconnect: Boolean
    ): LiveConnectionState {
        val requestId: Long
        val priorJobToCancel: Job?
        synchronized(lock) {
            lastSessionId = sessionId
            operatorHalted = false
            requestId = connectRequestSeq.incrementAndGet()
            activeConnectRequestId = requestId
            priorJobToCancel = if (!isControlledAutoReconnect) activeConnectJob else null
            if (!isControlledAutoReconnect) {
                activeConnectJob = null
            }
        }
        priorJobToCancel?.cancel()

        // Requirement 4 & 5: Pre-handshake check for NET_CAPABILITY_INTERNET + NET_CAPABILITY_VALIDATED
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
            effectiveDriver.closeSocket(null)
            return _state.value
        }

        var attempt = 1
        var retryCount = 0
        val effectiveMaxAttempts = maxAttemptsLimit.coerceAtLeast(1)

        while (attempt <= effectiveMaxAttempts) {
            // Re-verify validated internet before every attempt in the loop
            if (!networkMonitor.isInternetValidated()) {
                onNetworkValidatedChanged(false)
                return _state.value
            }

            val genId = synchronized(lock) {
                if (activeConnectRequestId != requestId) {
                    return _state.value
                }
                val created = nextGenerationId(sessionId, attempt)
                activeGenerationId = created
                lastKnownValidated = true
                val newAutoReconnectCount = if (isControlledAutoReconnect && attempt == 1) {
                    _state.value.autoReconnectCount + 1
                } else {
                    _state.value.autoReconnectCount
                }
                val prefix = if (isControlledAutoReconnect) {
                    "NETWORK_RESTORED -> AUTO_RECONNECT"
                } else {
                    "CONNECTING"
                }
                val connectingSummary =
                    "$prefix attempt $attempt/$effectiveMaxAttempts to $configuredModelId (${LiveConfigConstants.ENDPOINT_FAMILY.wireCode}) as ${created.wireId}."
                updateStateAndNotify { prev ->
                    prev.copy(
                        phase = LiveConnectionPhase.CONNECTING,
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
                        statusHeadline = "CONNECTING TO GEMINI LIVE (ATTEMPT $attempt/$effectiveMaxAttempts)",
                        statusDetail = connectingSummary,
                        diagnostics = prev.diagnostics.copy(
                            modelId = configuredModelId,
                            sessionGenerationWireId = created.wireId,
                            phaseWireCode = LiveConnectionPhase.CONNECTING.wireCode,
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

            val result = effectiveDriver.connectAndHandshake(
                generationId = genId,
                modelId = configuredModelId,
                onSocketEvent = ::onSocketCallback,
                systemInstruction = systemInstruction
            )

            // Verify this generation wasn't preempted by STOP/PAUSE/disconnect, a newer request, or offline loss
            val validatedAfterCall = networkMonitor.isInternetValidated()
            val stillCurrent = synchronized(lock) {
                activeConnectRequestId == requestId && activeGenerationId == genId
            }
            if (!stillCurrent) {
                effectiveDriver.closeSocket(genId)
                return _state.value
            }
            if (!validatedAfterCall) {
                effectiveDriver.closeSocket(genId)
                onNetworkValidatedChanged(false)
                return _state.value
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
                    return _state.value
                }

                is LiveHandshakeResult.Failed -> {
                    effectiveDriver.closeSocket(genId)
                    val classification = result.classification
                    val redactedError = DiagnosticRedactor.redact(result.rawDetail)

                    if (classification == LiveFailureClassification.NETWORK_UNAVAILABLE ||
                        !networkMonitor.isInternetValidated()
                    ) {
                        onNetworkValidatedChanged(false)
                        return _state.value
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
                        return _state.value
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
                        return _state.value
                    }

                    // Wait bounded backoff before next attempt
                    retryCount += 1
                    synchronized(lock) {
                        if (activeConnectRequestId != requestId) {
                            return _state.value
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
                        return _state.value
                    }
                    val stillWaiting = synchronized(lock) {
                        activeConnectRequestId == requestId && activeGenerationId == genId
                    }
                    if (!stillWaiting) {
                        return _state.value
                    }
                    attempt += 1
                }
            }
        }

        return _state.value
    }

    /**
     * Cancels any scheduled retry backoff or in-flight connection attempt when the diagnostics
     * screen is disposed, without destroying an already-verified `SETUP_ACCEPTED` session.
     */
    fun cancelScheduledRetries(reason: String) {
        val jobToCancel: Job?
        val genToClose: LiveSessionGenerationId?
        val safeReason = DiagnosticRedactor.redact(reason)
        synchronized(lock) {
            val currentPhase = _state.value.phase
            if (currentPhase == LiveConnectionPhase.RETRY_BACKOFF_WAITING ||
                currentPhase == LiveConnectionPhase.CONNECTING
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
     * Recreates only the Gemini Live session (never the Activity/app) after an unexpected server
     * turn interruption so the next turn starts with a clean session state.
     */
    suspend fun recreateSessionAfterInterruption(
        sessionId: SessionId,
        reason: String
    ): LiveConnectionState {
        disconnectAndRelease("Session recreated after server turn interruption ($reason)")
        return connectWithBoundedRetry(sessionId)
    }
}
