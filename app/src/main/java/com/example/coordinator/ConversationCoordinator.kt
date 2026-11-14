package com.example.coordinator

import com.example.BuildConfig
import com.example.audio.AudioCapture
import com.example.audio.AudioCaptureState
import com.example.audio.AudioHardwareDriver
import com.example.audio.AudioOwner
import com.example.audio.CaptureCallbackEvent
import com.example.audio.CaptureGenerationId
import com.example.audio.HardwareStartResult
import com.example.audio.LiveAudioPlaybackAdapter
import com.example.audio.LiveAudioPlaybackState
import com.example.audio.MicPermissionState
import com.example.audio.PcmChunkWriteOutcome
import com.example.audio.PcmResampler
import com.example.audio.PlaybackGenerationId
import com.example.contract.ActionId
import com.example.contract.ContractIdGenerator
import com.example.contract.ConversationState
import com.example.contract.CoordinatorCommand
import com.example.contract.SessionId
import com.example.contract.TranscriptEntry
import com.example.contract.TurnId
import com.example.contract.VerifiedOutcome
import com.example.live.DiagnosticRedactor
import com.example.live.GeminiLiveConnectionAdapter
import com.example.live.LiveConfigConstants
import com.example.live.LiveConnectionPhase
import com.example.live.LiveConnectionState
import com.example.live.LiveFailureClassification
import com.example.live.LiveSocketCallbackEvent
import com.example.live.TurnGenerationId
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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
    val microphoneStatusLabel: String = "MIC: INACTIVE (PRESS START FOR GEMINI LIVE VOICE TURN)",
    val modelConfigStatus: String = BuildConfig.MODEL_CONFIG_STATUS,
    val sourceBuildId: String = BuildConfig.SOURCE_BUILD_ID,
    val lastOutcome: VerifiedOutcome? = null,
    val transcript: List<TranscriptEntry> = emptyList(),
    val executedActionCount: Int = 0,
    val duplicateRejectedCount: Int = 0,
    val audioCaptureState: AudioCaptureState = AudioCaptureState(),
    val liveConnectionState: LiveConnectionState = LiveConnectionState(),
    val playbackState: LiveAudioPlaybackState = LiveAudioPlaybackState(),
    val voiceTurnStateSequence: List<ConversationState> = emptyList(),
    val voiceTurnReadinessOrder: List<String> = emptyList(),
    val activeTurnGenerationId: TurnGenerationId? = null,
    val turnEventTrace: List<String> = emptyList(),
    val firstTurnLifecycleTrace: List<String> = emptyList(),
    val debouncedStartCount: Int = 0,
    val activeVoiceTurnErrorCode: String? = null,
    val activeVoiceTurnErrorDetail: String? = null,
    val voiceTurnFramesSent: Long = 0L,
    val voiceTurnChunksSent: Long = 0L,
    val voiceTurnBytesSent: Long = 0L,
    val voiceTurnPeakRmsLinear: Float = 0f,
    val interruptionRecoveryCount: Int = 0,
    val staleTurnCallbacksDropped: Int = 0,
    val currentTurnUserTranscript: String = "",
    val currentTurnModelTranscript: String = "",
    val authoritativeSystemInstruction: String = LiveConfigConstants.AUTHORITATIVE_SYSTEM_INSTRUCTION,
    val lastVoiceTurnSummary: String = "Ready. Press Start to begin a verified one-turn Gemini Live voice conversation."
)

/**
 * Single ConversationCoordinator for OmniVoice Rebuild (Phase 01 through Phase 04).
 *
 * Enforces PROJECT_CONTRACT.md & Phase 04 invariants:
 * 1. Single coordinator authority connecting the Phase 02 [AudioCapture] adapter, Phase 03
 *    [GeminiLiveConnectionAdapter], and Phase 04 [LiveAudioPlaybackAdapter].
 * 2. Pressing Start automatically initializes the Phase 04 one-turn voice coordinator without
 *    requiring any hidden settings screen.
 * 3. Never enters [ConversationState.NOT_CONFIGURED] when required adapters are available.
 *    If a real prerequisite is missing (microphone permission, microphone hardware, validated internet,
 *    or Firebase/Gemini handshake), transitions honestly to [ConversationState.ERROR] with the exact
 *    readable error code and detail.
 * 4. Enforces strict first-turn readiness order:
 *    `IDLE -> CONNECTING -> SETUP_ACCEPTED -> LISTENING -> PROCESSING -> SPEAKING -> READY`
 *    (or proceeds directly to `LISTENING` when the connection is already `SETUP_ACCEPTED`),
 *    attaching the server-event/audio collector before sending the first microphone frame and
 *    never starting `AudioRecord` or displaying `LISTENING` before `SETUP_ACCEPTED`.
 * 5. Streams 16kHz PCM microphone frames in FIFO order without discarding initial buffers, signals
 *    documented end-of-turn after real speech is captured, streams all response bytes through
 *    Android `AudioTrack`, and transitions to [ConversationState.READY] only after hardware
 *    playback has fully drained (`hardwarePlaybackHeadFrames >= totalFramesWrittenToHardware`).
 * 6. Ignores/debounces additional Start presses while `CONNECTING`, `LISTENING`, `PROCESSING`, or
 *    `SPEAKING` so duplicate turns cannot be created, and cleanly resets turn state after completion
 *    or failure so the next single Start press also works.
 * 7. Stop holds highest precedence: immediately cancels microphone capture, the current model turn,
 *    and queued playback, and invalidates generation IDs so a stopped response can never replay
 *    during the next turn.
 */
class ConversationCoordinator(
    private val idGenerator: ContractIdGenerator = ContractIdGenerator(),
    val audioCapture: AudioCapture = AudioCapture(DefaultNoContextAudioDriver()),
    val liveAdapter: GeminiLiveConnectionAdapter = GeminiLiveConnectionAdapter(),
    val playbackAdapter: LiveAudioPlaybackAdapter = LiveAudioPlaybackAdapter(),
    val isVoiceTurnConfigured: Boolean = true,
    val speechRmsThreshold: Float = 0.010f,
    val minSpeechChunksForAutoEnd: Int = 5,
    val silenceChunksForAutoEnd: Int = 18,
    val connectionTimeoutMs: Long = 20_000L,
    val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    private val clockProvider: () -> String = { "PHASE04-T+${System.currentTimeMillis() % 100000}ms" }
) {
    private sealed interface PrerequisiteCheck {
        data object Passed : PrerequisiteCheck
        data class NotConfigured(val detail: String) : PrerequisiteCheck
        data class MissingPrerequisite(val code: String, val detail: String) : PrerequisiteCheck
    }

    private val executedActionIds = LinkedHashSet<String>()
    private val maxTrackedActions = 512

    private val voiceTurnCounter = AtomicLong(0L)
    @Volatile
    private var activeVoiceTurnSeq: Long = 0L
    @Volatile
    private var activeTurnGenerationId: TurnGenerationId? = null
    @Volatile
    private var activeTurnPlaybackGen: PlaybackGenerationId? = null
    @Volatile
    private var activeTurnParentJob: CompletableJob? = null
    @Volatile
    private var activeTurnDrainJob: Job? = null
    private val activeTurnWriteJobs = mutableListOf<Job>()
    private val activeTurnMicSendJobs = mutableListOf<Job>()
    private val micSendMutex = Mutex()
    @Volatile
    private var activeTurnCompletionDeferred: CompletableDeferred<VerifiedOutcome>? = null
    @Volatile
    private var needsSessionRecreationBeforeNextTurn: Boolean = false

    private var turnSpeechChunks: Int = 0
    private var turnTrailingSilenceChunks: Int = 0
    private var autoEndTurnTriggeredForSeq: Long = 0L

    private val turnScope = CoroutineScope(SupervisorJob() + backgroundDispatcher)

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
            liveConnectionState = liveAdapter.state.value,
            playbackState = playbackAdapter.state.value,
            authoritativeSystemInstruction = liveAdapter.systemInstruction,
            transcript = listOf(
                TranscriptEntry(
                    id = "${initialActionId.value}-log",
                    sessionId = initialSessionId,
                    turnId = initialTurnId,
                    actionId = initialActionId,
                    roleLabel = "COORDINATOR",
                    stateSnapshot = ConversationState.IDLE,
                    outcomeCode = "SHELL_INITIALIZED",
                    message = "OmniVoice Rebuild Phase 04 ready. Single ConversationCoordinator connected to AudioCapture (16kHz PCM), GeminiLiveConnectionAdapter (${liveAdapter.configuredModelId}), and LiveAudioPlaybackAdapter (streaming PCM AudioTrack).",
                    timestampLabel = "INIT"
                )
            )
        )
    )
    val uiState: StateFlow<ConversationUiState> = _uiState.asStateFlow()

    init {
        recordActionId(initialActionId)
        liveAdapter.onStateChanged = { onLiveAdapterStateUpdated() }
        liveAdapter.onServerEventListener = { event -> onServerSocketEvent(event) }
        playbackAdapter.onStateChanged = { syncPlaybackState() }
    }

    fun nextActionId(verb: String): ActionId = idGenerator.nextActionId(verb)

    /**
     * Returns true if any per-turn background coroutine jobs are still active.
     * Used by lifecycle tests to verify zero leaked coroutines after turn completion or Stop.
     */
    @Synchronized
    fun hasActiveTurnJobs(): Boolean {
        return (activeTurnParentJob?.isActive == true) ||
            (activeTurnDrainJob?.isActive == true) ||
            activeTurnWriteJobs.any { it.isActive } ||
            activeTurnMicSendJobs.any { it.isActive }
    }

    /**
     * Synchronizes the latest [AudioCaptureState], [LiveConnectionState], and [LiveAudioPlaybackState]
     * into [ConversationUiState] using lock-free CAS updates so UI collectors never block on audio threads.
     */
    fun syncAudioState() {
        val snap = audioCapture.state.value
        val liveSnap = liveAdapter.state.value
        val playSnap = playbackAdapter.state.value
        val micLabel = formatMicLabel(snap)
        _uiState.update { current ->
            current.copy(
                isMicrophoneActive = snap.isCapturing,
                microphoneStatusLabel = micLabel,
                audioCaptureState = snap,
                liveConnectionState = liveSnap,
                playbackState = playSnap
            )
        }
    }

    /**
     * Synchronizes the latest [LiveConnectionState] from [liveAdapter] into [ConversationUiState] lock-free.
     */
    fun syncLiveState() {
        val liveSnap = liveAdapter.state.value
        _uiState.update { current ->
            current.copy(
                liveConnectionState = liveSnap,
                authoritativeSystemInstruction = liveSnap.systemInstruction
            )
        }
    }

    /**
     * Synchronizes the latest [LiveAudioPlaybackState] from [playbackAdapter] into [ConversationUiState] lock-free.
     */
    fun syncPlaybackState() {
        val playSnap = playbackAdapter.state.value
        _uiState.update { current ->
            current.copy(playbackState = playSnap)
        }
    }

    private fun onLiveAdapterStateUpdated() {
        val liveSnap = liveAdapter.state.value
        synchronized(this) {
            _uiState.update { current ->
                current.copy(liveConnectionState = liveSnap)
            }
            val currentState = _uiState.value.state
            val isVoiceTurnInFlight =
                currentState == ConversationState.CONNECTING ||
                    currentState == ConversationState.LISTENING ||
                    currentState == ConversationState.PROCESSING ||
                    currentState == ConversationState.SPEAKING

            if (isVoiceTurnInFlight && liveSnap.phase == LiveConnectionPhase.NETWORK_UNAVAILABLE) {
                failActiveVoiceTurnLocked(
                    failureCode = "NETWORK_UNAVAILABLE",
                    detail = "Validated internet lost during active voice turn (${currentState.wireLabel}). Microphone capture and audio playback cancelled."
                )
            }
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
     * Forwards a generation-tagged socket callback through [GeminiLiveConnectionAdapter] and updates state.
     * Returns true if accepted by the active socket generation, false if dropped as stale.
     */
    fun onLiveSocketCallback(event: LiveSocketCallbackEvent): Boolean {
        val accepted = liveAdapter.onSocketCallback(event)
        syncLiveState()
        return accepted
    }

    /**
     * Starts lifecycle-safe connectivity monitoring (`ConnectivityManager.NetworkCallback`) while
     * the diagnostics screen or a Gemini Live session is active.
     */
    @Synchronized
    fun startLiveNetworkMonitoring() {
        liveAdapter.startNetworkMonitoring()
        syncLiveState()
    }

    /**
     * Unregisters connectivity monitoring safely when no longer needed.
     */
    @Synchronized
    fun stopLiveNetworkMonitoring() {
        liveAdapter.stopNetworkMonitoring()
        syncLiveState()
    }

    /**
     * Called when the Live Diagnostics screen is disposed (navigated away).
     * Cancels any scheduled retry backoff (`CONNECTING` / `RETRY_BACKOFF_WAITING`), and unregisters
     * the network callback if no active `SETUP_ACCEPTED` session is being monitored.
     */
    @Synchronized
    fun onLiveDiagnosticsDisposed(reason: String = "Live Diagnostics screen disposed") {
        liveAdapter.cancelScheduledRetries(reason)
        if (liveAdapter.state.value.phase != LiveConnectionPhase.SETUP_ACCEPTED) {
            liveAdapter.stopNetworkMonitoring()
        }
        syncLiveState()
    }

    /**
     * Checks all Phase 04 prerequisites before starting or connecting a voice turn.
     * Never returns [PrerequisiteCheck.NotConfigured] unless [isVoiceTurnConfigured] is explicitly false.
     */
    private fun checkVoiceTurnPrerequisitesLocked(): PrerequisiteCheck {
        if (!isVoiceTurnConfigured) {
            return PrerequisiteCheck.NotConfigured(
                "Voice turn adapters are explicitly marked unconfigured in this coordinator instance."
            )
        }
        val permState = audioCapture.state.value.permissionState
        if (permState != MicPermissionState.GRANTED) {
            return PrerequisiteCheck.MissingPrerequisite(
                code = "MIC_PERMISSION_REQUIRED_${permState.wireLabel}",
                detail = "Microphone permission (RECORD_AUDIO) is required before starting a voice turn (current=${permState.wireLabel}). Grant microphone permission and press Start."
            )
        }
        if (!audioCapture.state.value.hasMicrophoneHardware) {
            return PrerequisiteCheck.MissingPrerequisite(
                code = "MISSING_MICROPHONE_DEVICE",
                detail = "No physical or virtual microphone input device detected on this device (MISSING_MICROPHONE_DEVICE)."
            )
        }
        if (!liveAdapter.networkMonitor.isInternetValidated()) {
            return PrerequisiteCheck.MissingPrerequisite(
                code = "NETWORK_UNAVAILABLE",
                detail = "No validated internet connection (NET_CAPABILITY_INTERNET + NET_CAPABILITY_VALIDATED required). Connect to Wi-Fi or mobile data and press Start."
            )
        }
        return PrerequisiteCheck.Passed
    }

    /**
     * Phase 04 One-Turn Voice Coordinator entry point triggered when the main Start button is pressed.
     *
     * Automatically:
     * 1. Ignores/debounces additional Start presses while a turn is already active in
     *    `CONNECTING`, `LISTENING`, `PROCESSING`, or `SPEAKING` so duplicate turns cannot be created.
     * 2. Verifies adapter availability (never shows `NOT_CONFIGURED` when adapters exist).
     * 3. Verifies `RECORD_AUDIO` permission, microphone hardware, and validated internet.
     * 4. Attaches the server-event/audio receiver collector before any connection or microphone frame.
     * 5. If the Gemini Live connection is not yet `SETUP_ACCEPTED`, transitions `IDLE -> CONNECTING`,
     *    waits asynchronously with bounded [connectionTimeoutMs] for `SETUP_ACCEPTED` and receiver
     *    collector readiness, and only then starts `AudioRecord` and transitions to `LISTENING`.
     *    If the connection is already `SETUP_ACCEPTED` and ready, proceeds directly to `LISTENING`.
     */
    suspend fun startVoiceTurn(
        actionId: ActionId = idGenerator.nextActionId("START")
    ): VerifiedOutcome {
        val turnSeq: Long
        val sessionId: SessionId
        val turnId: TurnId
        val alreadySetupAccepted: Boolean

        synchronized(this) {
            val current = _uiState.value
            if (executedActionIds.contains(actionId.value)) {
                val duplicateOutcome = VerifiedOutcome.DuplicateActionRejected(
                    actionId = actionId,
                    sessionId = current.sessionId,
                    turnId = current.currentTurnId,
                    resultingState = current.state,
                    detail = "Action ${actionId.value} already executed; duplicate ignored per exactly-once contract."
                )
                recordDuplicateOutcomeLocked(actionId, duplicateOutcome)
                return duplicateOutcome
            }

            // Requirement 7: Ignore/debounce additional Start presses while CONNECTING, LISTENING,
            // PROCESSING, or SPEAKING so duplicate turns cannot be created or cancel an in-flight turn.
            val isTurnCurrentlyActive =
                activeVoiceTurnSeq != 0L ||
                    current.state == ConversationState.CONNECTING ||
                    current.state == ConversationState.LISTENING ||
                    current.state == ConversationState.PROCESSING ||
                    current.state == ConversationState.SPEAKING

            if (isTurnCurrentlyActive) {
                recordActionId(actionId)
                val newDebouncedCount = current.debouncedStartCount + 1
                val debouncedDetail =
                    "START_DEBOUNCED: Additional Start press ignored while turn ${current.currentTurnId.value} is active in ${current.state.wireLabel} (debouncedCount=$newDebouncedCount)."
                val debouncedOutcome = VerifiedOutcome.DuplicateActionRejected(
                    actionId = actionId,
                    sessionId = current.sessionId,
                    turnId = current.currentTurnId,
                    resultingState = current.state,
                    detail = debouncedDetail
                )
                _uiState.update { state ->
                    state.copy(
                        debouncedStartCount = newDebouncedCount
                    )
                }
                logTurnLifecycleEventLocked("START_DEBOUNCED_WHILE_${current.state.wireLabel}")
                return debouncedOutcome
            }

            recordActionId(actionId)
            turnId = idGenerator.nextTurnId()
            sessionId = current.sessionId

            // Requirement 8: Cleanly reset any previous turn streams before initializing the new turn
            cancelActiveTurnStreamsLocked("Initializing new Phase 04 voice turn (${turnId.value})")

            when (val prereq = checkVoiceTurnPrerequisitesLocked()) {
                is PrerequisiteCheck.NotConfigured -> {
                    val outcome = VerifiedOutcome.HonestNotConfiguredOutcome(
                        actionId = actionId,
                        sessionId = sessionId,
                        turnId = turnId,
                        resultingState = ConversationState.NOT_CONFIGURED,
                        detail = prereq.detail
                    )
                    applyVerifiedOutcome(
                        sessionId = sessionId,
                        turnId = turnId,
                        actionId = actionId,
                        newState = ConversationState.NOT_CONFIGURED,
                        roleLabel = "OPERATOR_START",
                        outcome = outcome
                    )
                    return outcome
                }

                is PrerequisiteCheck.MissingPrerequisite -> {
                    if (prereq.code == "NETWORK_UNAVAILABLE") {
                        liveAdapter.onNetworkValidatedChanged(false)
                    }
                    val outcome = VerifiedOutcome.PrerequisiteFailureOutcome(
                        actionId = actionId,
                        sessionId = sessionId,
                        turnId = turnId,
                        resultingState = ConversationState.ERROR,
                        failureCode = prereq.code,
                        detail = prereq.detail
                    )
                    _uiState.update { state ->
                        state.copy(
                            activeVoiceTurnErrorCode = prereq.code,
                            activeVoiceTurnErrorDetail = prereq.detail,
                            lastVoiceTurnSummary = "ERROR [${prereq.code}]: ${prereq.detail}"
                        )
                    }
                    applyVerifiedOutcome(
                        sessionId = sessionId,
                        turnId = turnId,
                        actionId = actionId,
                        newState = ConversationState.ERROR,
                        roleLabel = "PREREQUISITE_GUARD",
                        outcome = outcome
                    )
                    return outcome
                }

                is PrerequisiteCheck.Passed -> {
                    // Ensure server-event/audio collector is attached BEFORE connecting or sending audio
                    liveAdapter.onStateChanged = { onLiveAdapterStateUpdated() }
                    liveAdapter.onServerEventListener = { event -> onServerSocketEvent(event) }

                    turnSeq = voiceTurnCounter.incrementAndGet()
                    val turnGen = TurnGenerationId(
                        turnSeq = turnSeq,
                        turnIdValue = turnId.value,
                        sessionId = sessionId
                    )
                    activeVoiceTurnSeq = turnSeq
                    activeTurnGenerationId = turnGen
                    turnSpeechChunks = 0
                    turnTrailingSilenceChunks = 0
                    autoEndTurnTriggeredForSeq = 0L
                    activeTurnParentJob = SupervisorJob()
                    activeTurnWriteJobs.clear()
                    activeTurnMicSendJobs.clear()
                    activeTurnCompletionDeferred = CompletableDeferred()
                    liveAdapter.startNetworkMonitoring()

                    alreadySetupAccepted =
                        !needsSessionRecreationBeforeNextTurn &&
                            liveAdapter.isSessionReadyForAudio()

                    val initialTrace = listOf(
                        "BUTTON_PRESS",
                        "RECEIVER_COLLECTOR_ATTACHED",
                        "LIVE_CONNECTION"
                    )
                    val initialReadiness = if (alreadySetupAccepted) {
                        listOf("SETUP_ACCEPTED")
                    } else {
                        listOf("IDLE", "CONNECTING")
                    }

                    val seqList = listOf(ConversationState.CONNECTING)
                    val connectingOutcome = VerifiedOutcome.StateTransitionVerified(
                        actionId = actionId,
                        sessionId = sessionId,
                        turnId = turnId,
                        previousState = current.state,
                        resultingState = ConversationState.CONNECTING,
                        detail = if (alreadySetupAccepted) {
                            "SETUP_ACCEPTED (${turnGen.wireId}): Gemini Live session already verified and collector attached; proceeding directly to LISTENING..."
                        } else {
                            "CONNECTING (${turnGen.wireId}): Verifying microphone & validated internet and connecting Gemini Live session (${liveAdapter.configuredModelId})..."
                        }
                    )
                    _uiState.update { state ->
                        state.copy(
                            voiceTurnStateSequence = seqList,
                            voiceTurnReadinessOrder = initialReadiness,
                            activeTurnGenerationId = turnGen,
                            turnEventTrace = emptyList(),
                            firstTurnLifecycleTrace = initialTrace,
                            activeVoiceTurnErrorCode = null,
                            activeVoiceTurnErrorDetail = null,
                            voiceTurnFramesSent = 0L,
                            voiceTurnChunksSent = 0L,
                            voiceTurnBytesSent = 0L,
                            voiceTurnPeakRmsLinear = 0f,
                            currentTurnUserTranscript = "",
                            currentTurnModelTranscript = "",
                            authoritativeSystemInstruction = liveAdapter.systemInstruction,
                            lastVoiceTurnSummary = connectingOutcome.detail
                        )
                    }
                    logTurnLifecycleEventLocked("BUTTON_PRESS -> RECEIVER_COLLECTOR_ATTACHED -> LIVE_CONNECTION (alreadyReady=$alreadySetupAccepted)")
                    if (!alreadySetupAccepted) {
                        applyVerifiedOutcome(
                            sessionId = sessionId,
                            turnId = turnId,
                            actionId = actionId,
                            newState = ConversationState.CONNECTING,
                            roleLabel = "VOICE_TURN_COORDINATOR",
                            outcome = connectingOutcome
                        )
                    }
                }
            }
        }

        // Requirement 2, 3, 4: If connection is not already ready, wait asynchronously (with bounded timeout)
        // for SETUP_ACCEPTED before starting AudioRecord or displaying LISTENING.
        val finalLiveState: LiveConnectionState? = if (alreadySetupAccepted) {
            liveAdapter.state.value
        } else {
            needsSessionRecreationBeforeNextTurn = false
            withTimeoutOrNull(connectionTimeoutMs) {
                liveAdapter.connectWithBoundedRetry(sessionId)
            }
        }

        return synchronized(this) {
            // Check if STOP, PAUSE, or RESET_IDLE preempted this turn while connecting
            if (activeVoiceTurnSeq != turnSeq) {
                val now = _uiState.value
                return@synchronized now.lastOutcome ?: VerifiedOutcome.StateTransitionVerified(
                    actionId = actionId,
                    sessionId = now.sessionId,
                    turnId = now.currentTurnId,
                    previousState = ConversationState.CONNECTING,
                    resultingState = now.state,
                    detail = "Voice turn $turnSeq preempted by ${now.state.wireLabel} during CONNECTING."
                )
            }

            // Requirement 4: Bounded connection timeout -> show an honest error if setup times out
            if (finalLiveState == null) {
                liveAdapter.cancelScheduledRetries("Connection setup timed out after ${connectionTimeoutMs}ms")
                val timeoutCode = "CONNECTION_SETUP_TIMEOUT"
                val timeoutDetail =
                    "Gemini Live connection setup timed out after ${connectionTimeoutMs}ms before SETUP_ACCEPTED. Verify internet quality and press Start to try again."
                return@synchronized failActiveVoiceTurnLocked(
                    failureCode = timeoutCode,
                    detail = timeoutDetail
                )
            }

            appendTurnEventLocked(if (alreadySetupAccepted) "SESSION_REUSED" else "SESSION_CREATED")

            val collectorReadyNow = liveAdapter.isSessionReadyForAudio()
            if (finalLiveState.phase != LiveConnectionPhase.SETUP_ACCEPTED ||
                !finalLiveState.handshakeVerified ||
                !collectorReadyNow
            ) {
                activeVoiceTurnSeq = 0L
                activeTurnGenerationId = null
                val failCode = if (finalLiveState.phase == LiveConnectionPhase.NETWORK_UNAVAILABLE) {
                    "NETWORK_UNAVAILABLE"
                } else if (finalLiveState.failureClassification != LiveFailureClassification.NONE) {
                    finalLiveState.failureClassification.wireCode
                } else if (!collectorReadyNow && finalLiveState.phase == LiveConnectionPhase.SETUP_ACCEPTED) {
                    "RECEIVER_COLLECTOR_NOT_ATTACHED"
                } else {
                    "LIVE_${finalLiveState.phase.wireCode}"
                }
                val failDetail =
                    "Gemini Live connection failed [$failCode]: ${DiagnosticRedactor.redact(finalLiveState.statusDetail)}"
                val failOutcome = VerifiedOutcome.PrerequisiteFailureOutcome(
                    actionId = idGenerator.nextActionId("live_fail"),
                    sessionId = sessionId,
                    turnId = turnId,
                    resultingState = ConversationState.ERROR,
                    failureCode = failCode,
                    detail = failDetail
                )
                _uiState.update { state ->
                    state.copy(
                        activeTurnGenerationId = null,
                        activeVoiceTurnErrorCode = failCode,
                        activeVoiceTurnErrorDetail = failDetail,
                        lastVoiceTurnSummary = failDetail
                    )
                }
                applyVerifiedOutcome(
                    sessionId = sessionId,
                    turnId = turnId,
                    actionId = failOutcome.actionId,
                    newState = ConversationState.ERROR,
                    roleLabel = "LIVE_ADAPTER",
                    outcome = failOutcome
                )
                activeTurnCompletionDeferred?.complete(failOutcome)
                return@synchronized failOutcome
            }

            // Record SETUP_ACCEPTED before AudioRecord start and before LISTENING state
            appendTurnEventLocked("SETUP_ACCEPTED")
            appendFirstTurnLifecycleLocked("SETUP_ACCEPTED")
            appendReadinessOrderLocked("SETUP_ACCEPTED")

            // Allocate streaming playback generation for this turn
            val playbackGen = playbackAdapter.startTurnPlayback(
                turnId = turnId,
                sessionId = sessionId
            )
            activeTurnPlaybackGen = playbackGen
            liveAdapter.setAudioPlaybackClaimed(true)

            // Requirement 2 & 5: Transition state to LISTENING and record AUDIO_RECORD_START inside the
            // synchronized lock right before starting AudioRecord so that even an immediate/synchronous
            // first microphone buffer from AudioRecord is accepted and never discarded!
            appendFirstTurnLifecycleLocked("AUDIO_RECORD_START")
            appendTurnEventLocked("USER_ACTIVITY_START")
            val updatedSequence = appendTurnStateLocked(ConversationState.LISTENING)
            val updatedReadiness = appendReadinessOrderLocked("LISTENING")
            _uiState.update { state ->
                state.copy(
                    state = ConversationState.LISTENING,
                    voiceTurnStateSequence = updatedSequence,
                    voiceTurnReadinessOrder = updatedReadiness
                )
            }

            // Start 16kHz PCM microphone capture bound to LIVE_VOICE_TURN owner AFTER SETUP_ACCEPTED
            val captureGen = audioCapture.startCapture(
                owner = AudioOwner.LIVE_VOICE_TURN,
                sessionId = sessionId
            ) { genId, samples16kHz, rmsLinear ->
                onVoiceTurnMicSamplesCaptured(
                    turnSeq = turnSeq,
                    captureGenId = genId,
                    samples16kHz = samples16kHz,
                    rmsLinear = rmsLinear
                )
            }

            val audioSnap = audioCapture.state.value
            if (!audioSnap.isCapturing) {
                activeVoiceTurnSeq = 0L
                activeTurnGenerationId = null
                activeTurnPlaybackGen = null
                playbackAdapter.stopAndFlush("Microphone capture failed to start")
                liveAdapter.setAudioPlaybackClaimed(false)
                val failCode = audioSnap.anomaly.wireCode
                val failDetail = "Microphone capture failed [$failCode]: ${audioSnap.statusDetail}"
                val failOutcome = VerifiedOutcome.PrerequisiteFailureOutcome(
                    actionId = idGenerator.nextActionId("mic_fail"),
                    sessionId = sessionId,
                    turnId = turnId,
                    resultingState = ConversationState.ERROR,
                    failureCode = failCode,
                    detail = failDetail
                )
                _uiState.update { state ->
                    state.copy(
                        activeTurnGenerationId = null,
                        activeVoiceTurnErrorCode = failCode,
                        activeVoiceTurnErrorDetail = failDetail,
                        lastVoiceTurnSummary = failDetail
                    )
                }
                applyVerifiedOutcome(
                    sessionId = sessionId,
                    turnId = turnId,
                    actionId = failOutcome.actionId,
                    newState = ConversationState.ERROR,
                    roleLabel = "AUDIO_CAPTURE",
                    outcome = failOutcome
                )
                activeTurnCompletionDeferred?.complete(failOutcome)
                return@synchronized failOutcome
            }

            val listeningDetail =
                "LISTENING (${captureGen.wireId} -> ${finalLiveState.activeGeneration?.wireId ?: "live"}): Speak one question clearly. Pause when finished or tap 'Send Spoken Question'."
            val listeningOutcome = VerifiedOutcome.StateTransitionVerified(
                actionId = idGenerator.nextActionId("listening"),
                sessionId = sessionId,
                turnId = turnId,
                previousState = ConversationState.CONNECTING,
                resultingState = ConversationState.LISTENING,
                detail = listeningDetail
            )
            _uiState.update { state ->
                state.copy(
                    voiceTurnStateSequence = updatedSequence,
                    voiceTurnReadinessOrder = updatedReadiness,
                    lastVoiceTurnSummary = listeningDetail
                )
            }
            applyVerifiedOutcome(
                sessionId = sessionId,
                turnId = turnId,
                actionId = listeningOutcome.actionId,
                newState = ConversationState.LISTENING,
                roleLabel = "VOICE_TURN_COORDINATOR",
                outcome = listeningOutcome
            )
            listeningOutcome
        }
    }

    /**
     * Streams captured 16kHz PCM microphone frames to the active Gemini Live session and monitors
     * speech + trailing silence for automatic end-of-turn signaling.
     *
     * Strictly enforces:
     * - Microphone frames are only captured and queued while the turn is in [ConversationState.LISTENING]
     *   and [AudioCapture] is actively capturing for [captureGenId].
     * - First microphone buffers are never discarded due to late initialization, mutable-buffer reuse,
     *   or premature queue cancellation when transitioning from `LISTENING` to `PROCESSING`.
     * - No microphone frames are ever captured or sent during [ConversationState.SPEAKING].
     * - Automatic end-of-turn only triggers after real speech (`rmsLinear >= speechRmsThreshold` for
     *   at least [minSpeechChunksForAutoEnd] chunks) followed by [silenceChunksForAutoEnd] trailing
     *   silence chunks — never ending the turn before real speech is captured.
     * - Serializes all frame sends in FIFO order via [micSendMutex] so trailing silence is never
     *   interleaved with speech frames.
     */
    private fun onVoiceTurnMicSamplesCaptured(
        turnSeq: Long,
        captureGenId: CaptureGenerationId,
        samples16kHz: ShortArray,
        rmsLinear: Float
    ) {
        if (samples16kHz.isEmpty()) return
        val pcmBytes = shortArrayToLittleEndianBytes(samples16kHz)
        var shouldTriggerAutoEnd = false

        val parentJob: CompletableJob
        val turnGen: TurnGenerationId
        synchronized(this) {
            if (activeVoiceTurnSeq != turnSeq || _uiState.value.state != ConversationState.LISTENING) {
                return
            }
            val currentTurnGen = activeTurnGenerationId ?: return
            val currentCaptureGen = audioCapture.state.value.activeGeneration
            if (currentCaptureGen == null || currentCaptureGen != captureGenId || !audioCapture.state.value.isCapturing) {
                return
            }
            parentJob = activeTurnParentJob ?: return
            turnGen = currentTurnGen

            appendTurnEventLocked("MIC_FRAMES_STREAMED")
            appendFirstTurnLifecycleLocked("AUDIO_SENT")

            val newFrames = _uiState.value.voiceTurnFramesSent + samples16kHz.size
            val newChunks = _uiState.value.voiceTurnChunksSent + 1L
            val newBytes = _uiState.value.voiceTurnBytesSent + pcmBytes.size
            val newPeakRms = max(_uiState.value.voiceTurnPeakRmsLinear, rmsLinear)
            val snap = audioCapture.state.value
            _uiState.update { state ->
                state.copy(
                    isMicrophoneActive = snap.isCapturing,
                    microphoneStatusLabel = formatMicLabel(snap),
                    audioCaptureState = snap,
                    voiceTurnFramesSent = newFrames,
                    voiceTurnChunksSent = newChunks,
                    voiceTurnBytesSent = newBytes,
                    voiceTurnPeakRmsLinear = newPeakRms
                )
            }

            if (rmsLinear >= speechRmsThreshold) {
                turnSpeechChunks += 1
                turnTrailingSilenceChunks = 0
            } else if (turnSpeechChunks >= minSpeechChunksForAutoEnd) {
                turnTrailingSilenceChunks += 1
                if (turnTrailingSilenceChunks >= silenceChunksForAutoEnd && autoEndTurnTriggeredForSeq != turnSeq) {
                    autoEndTurnTriggeredForSeq = turnSeq
                    shouldTriggerAutoEnd = true
                }
            }

            val sendJob = turnScope.launch(parentJob) {
                val sent = micSendMutex.withLock {
                    // Frame was captured during LISTENING for this exact turn. Allow it to flush
                    // while in LISTENING or while flushing into PROCESSING before signalEndOfSpokenTurn,
                    // but NEVER during SPEAKING or after turn cancellation!
                    val allowedToFlush = synchronized(this@ConversationCoordinator) {
                        activeVoiceTurnSeq == turnSeq &&
                            activeTurnGenerationId == turnGen &&
                            (_uiState.value.state == ConversationState.LISTENING ||
                                _uiState.value.state == ConversationState.PROCESSING)
                    }
                    if (!allowedToFlush) {
                        return@launch
                    }
                    liveAdapter.sendRealtimeAudioToActiveSession(
                        pcm16kHzBytes = pcmBytes,
                        mimeType = "audio/pcm;rate=16000"
                    )
                }
                if (!sent) {
                    synchronized(this@ConversationCoordinator) {
                        if (activeVoiceTurnSeq == turnSeq &&
                            activeTurnGenerationId == turnGen &&
                            (_uiState.value.state == ConversationState.LISTENING ||
                                _uiState.value.state == ConversationState.PROCESSING)
                        ) {
                            val code = if (!liveAdapter.networkMonitor.isInternetValidated()) {
                                "NETWORK_UNAVAILABLE"
                            } else {
                                "NETWORK_ERROR"
                            }
                            failActiveVoiceTurnLocked(
                                failureCode = code,
                                detail = "Failed to stream 16kHz microphone audio chunk to Gemini Live ($code)."
                            )
                        }
                    }
                    return@launch
                }
                if (shouldTriggerAutoEnd) {
                    finishSpokenTurnAndAwaitResponse(
                        reason = "Automatic end-of-turn silence detected after $turnSpeechChunks speech chunks"
                    )
                }
            }
            activeTurnMicSendJobs.removeAll { it.isCompleted }
            activeTurnMicSendJobs.add(sendJob)
        }
    }

    /**
     * Completes the spoken question capture (`LISTENING -> PROCESSING`), verifies that captured audio
     * is not silent, flushes all captured microphone buffers in order, sends the documented Gemini Live
     * end-of-turn signal, receives the real native-audio response (`PROCESSING -> SPEAKING`), streams
     * all audio chunks through Android audio hardware, and returns to `READY` only after hardware
     * playback has fully drained (`SPEAKING -> READY`).
     */
    suspend fun finishSpokenTurnAndAwaitResponse(
        reason: String = "Operator signaled end of spoken question",
        requireCapturedSpeech: Boolean = false
    ): VerifiedOutcome {
        val turnSeq: Long
        val turnGen: TurnGenerationId
        val sessionId: SessionId
        val turnId: TurnId
        val completionDeferred: CompletableDeferred<VerifiedOutcome>?
        val needsEndOfTurnSignal: Boolean
        val micJobsToFlush: List<Job>

        synchronized(this) {
            val current = _uiState.value
            turnSeq = activeVoiceTurnSeq
            val currentTurnGen = activeTurnGenerationId
            sessionId = current.sessionId
            turnId = current.currentTurnId
            completionDeferred = activeTurnCompletionDeferred

            if (turnSeq == 0L || currentTurnGen == null) {
                return current.lastOutcome ?: VerifiedOutcome.StateTransitionVerified(
                    actionId = current.lastActionId,
                    sessionId = sessionId,
                    turnId = turnId,
                    previousState = current.state,
                    resultingState = current.state,
                    detail = "No active voice turn to finish (state=${current.state.wireLabel})."
                )
            }
            turnGen = currentTurnGen

            if (current.state == ConversationState.LISTENING) {
                // Section D.4 & Requirement 6: If capture produced only silence or zero signal, do not
                // send an empty/silent turn to Gemini as if it were speech.
                val hasSilentCapturedFrames =
                    current.voiceTurnFramesSent > 0L && current.voiceTurnPeakRmsLinear < 0.008f
                val missingRequiredSpeech =
                    requireCapturedSpeech && (current.voiceTurnFramesSent <= 0L || current.voiceTurnPeakRmsLinear < 0.008f)

                if (hasSilentCapturedFrames || missingRequiredSpeech) {
                    stopTurnMicrophoneLocked(
                        reason = "Silent turn rejected ($reason)",
                        cancelQueuedMicSends = true
                    )
                    appendTurnEventLocked("SILENT_TURN_REJECTED")
                    return failActiveVoiceTurnLocked(
                        failureCode = "SILENT_AUDIO_CAPTURED",
                        detail = "Captured microphone audio had no speech signal (frames=${current.voiceTurnFramesSent}, peak RMS=${"%.3f".format(current.voiceTurnPeakRmsLinear)}). Empty/silent turn was not sent to Gemini. Speak clearly and try again."
                    )
                }

                // Stop hardware capture immediately so no new mic frames are read after LISTENING,
                // while preserving already-captured LISTENING buffers in activeTurnMicSendJobs so
                // they flush completely before signalEndOfSpokenTurn()!
                val currentJob = kotlin.coroutines.coroutineContext[Job]
                micJobsToFlush = activeTurnMicSendJobs.filter { it.isActive && it !== currentJob }
                stopTurnMicrophoneLocked(
                    reason = "Spoken question complete ($reason)",
                    cancelQueuedMicSends = false
                )

                needsEndOfTurnSignal = true
                appendTurnEventLocked("USER_ACTIVITY_END")
                appendFirstTurnLifecycleLocked("ACTIVITY_END_OF_TURN")
                val updatedSeq = appendTurnStateLocked(ConversationState.PROCESSING)
                val updatedReadiness = appendReadinessOrderLocked("PROCESSING")
                val procDetail =
                    "PROCESSING (${turnGen.wireId}): Spoken question captured (${current.voiceTurnFramesSent} frames, ${current.voiceTurnBytesSent} bytes, peak RMS ${"%.3f".format(current.voiceTurnPeakRmsLinear)}). Committing end-of-turn and awaiting Gemini native audio..."
                val procOutcome = VerifiedOutcome.StateTransitionVerified(
                    actionId = idGenerator.nextActionId("processing"),
                    sessionId = sessionId,
                    turnId = turnId,
                    previousState = ConversationState.LISTENING,
                    resultingState = ConversationState.PROCESSING,
                    detail = procDetail
                )
                _uiState.update { state ->
                    state.copy(
                        voiceTurnStateSequence = updatedSeq,
                        voiceTurnReadinessOrder = updatedReadiness,
                        lastVoiceTurnSummary = procDetail
                    )
                }
                applyVerifiedOutcome(
                    sessionId = sessionId,
                    turnId = turnId,
                    actionId = procOutcome.actionId,
                    newState = ConversationState.PROCESSING,
                    roleLabel = "VOICE_TURN_COORDINATOR",
                    outcome = procOutcome
                )
            } else {
                needsEndOfTurnSignal = false
                micJobsToFlush = emptyList()
            }
        }

        if (needsEndOfTurnSignal) {
            // Requirement 5: Await all already-captured LISTENING microphone frame sends before
            // sending the end-of-turn signal so the first utterance buffers are never discarded!
            for (job in micJobsToFlush) {
                job.join()
            }
            val stillActiveBeforeEndSignal = synchronized(this) {
                activeVoiceTurnSeq == turnSeq && activeTurnGenerationId == turnGen
            }
            if (stillActiveBeforeEndSignal) {
                val signaled = micSendMutex.withLock {
                    liveAdapter.signalEndOfSpokenTurn()
                }
                if (!signaled) {
                    synchronized(this) {
                        if (activeVoiceTurnSeq == turnSeq && activeTurnGenerationId == turnGen) {
                            val failCode = if (!liveAdapter.networkMonitor.isInternetValidated()) {
                                "NETWORK_UNAVAILABLE"
                            } else {
                                "NETWORK_ERROR"
                            }
                            return failActiveVoiceTurnLocked(
                                failureCode = failCode,
                                detail = "Failed to send end-of-turn signal to Gemini Live ($failCode)."
                            )
                        }
                    }
                }
            }
        }

        return completionDeferred?.await() ?: _uiState.value.lastOutcome!!
    }

    /**
     * Forwards a server socket callback bound to a specific [TurnGenerationId].
     * Stale callbacks from an older [TurnGenerationId] are deterministically dropped and counted
     * (`staleTurnCallbacksDropped`) so an older turn can never interrupt a new turn.
     */
    fun onTurnScopedServerEvent(
        turnGenerationId: TurnGenerationId,
        event: LiveSocketCallbackEvent
    ): Boolean {
        synchronized(this) {
            val currentTurnGen = activeTurnGenerationId
            if (currentTurnGen == null || currentTurnGen != turnGenerationId) {
                _uiState.update { state ->
                    state.copy(staleTurnCallbacksDropped = state.staleTurnCallbacksDropped + 1)
                }
                return false
            }
        }
        return onLiveSocketCallback(event)
    }

    private fun mergeTranscriptFragment(existing: String, incoming: String?): String {
        if (incoming.isNullOrBlank()) return existing
        val safe = DiagnosticRedactor.redact(incoming).trim()
        if (safe.isBlank() || safe == "NONE") return existing
        if (existing.isBlank()) return safe
        if (safe.startsWith(existing)) return safe
        if (existing.endsWith(safe)) return existing
        return "$existing $safe"
    }

    /**
     * Handles validated active-generation server events (`ServerAudioChunkReceived`, `ServerTurnCompleted`,
     * `ServerTranscriptionReceived`) forwarded from [GeminiLiveConnectionAdapter].
     */
    private fun onServerSocketEvent(event: LiveSocketCallbackEvent) {
        when (event) {
            is LiveSocketCallbackEvent.ServerTranscriptionReceived -> {
                synchronized(this) {
                    if (activeVoiceTurnSeq == 0L || activeTurnGenerationId == null) return
                    if (!event.outputTranscript.isNullOrBlank()) {
                        appendTurnEventLocked("SERVER_RESPONSE_START")
                        appendFirstTurnLifecycleLocked("SERVER_RESPONSE")
                    }
                    val current = _uiState.value
                    val updatedUser = mergeTranscriptFragment(current.currentTurnUserTranscript, event.inputTranscript)
                    val updatedModel = mergeTranscriptFragment(current.currentTurnModelTranscript, event.outputTranscript)
                    val summaryParts = buildList {
                        if (updatedUser.isNotBlank()) add("Heard: \"$updatedUser\"")
                        if (updatedModel.isNotBlank()) add("Gemini: \"$updatedModel\"")
                    }
                    _uiState.update { state ->
                        state.copy(
                            currentTurnUserTranscript = updatedUser,
                            currentTurnModelTranscript = updatedModel,
                            lastVoiceTurnSummary = if (summaryParts.isNotEmpty()) {
                                summaryParts.joinToString(" | ")
                            } else {
                                state.lastVoiceTurnSummary
                            }
                        )
                    }
                }
            }

            is LiveSocketCallbackEvent.ServerAudioChunkReceived -> {
                val turnSeq: Long
                val turnGen: TurnGenerationId
                val playbackGen: PlaybackGenerationId
                val parentJob: CompletableJob
                synchronized(this) {
                    turnSeq = activeVoiceTurnSeq
                    val currentTurnGen = activeTurnGenerationId
                    val currentPlaybackGen = activeTurnPlaybackGen
                    val currentParentJob = activeTurnParentJob
                    if (turnSeq == 0L || currentTurnGen == null || currentPlaybackGen == null || currentParentJob == null) {
                        return
                    }
                    turnGen = currentTurnGen
                    playbackGen = currentPlaybackGen
                    parentJob = currentParentJob

                    val currentState = _uiState.value.state
                    if (currentState == ConversationState.LISTENING) {
                        // Server VAD began responding while mic was still open: immediately stop mic capture
                        // and cancel queued mic send jobs before playback begins!
                        stopTurnMicrophoneLocked(
                            reason = "Server began native-audio response",
                            cancelQueuedMicSends = true
                        )
                        appendTurnEventLocked("USER_ACTIVITY_END")
                        appendFirstTurnLifecycleLocked("ACTIVITY_END_OF_TURN")
                        val updatedSeq = appendTurnStateLocked(ConversationState.PROCESSING)
                        val updatedReadiness = appendReadinessOrderLocked("PROCESSING")
                        _uiState.update { state ->
                            state.copy(
                                voiceTurnStateSequence = updatedSeq,
                                voiceTurnReadinessOrder = updatedReadiness
                            )
                        }
                    }

                    appendTurnEventLocked("SERVER_RESPONSE_START")
                    appendTurnEventLocked("AUDIO_CHUNKS_RECEIVED")
                    appendFirstTurnLifecycleLocked("SERVER_RESPONSE")

                    // Launch and register write job inside synchronized block so ServerTurnCompleted
                    // always awaits every preceding chunk write before checking hardware drain!
                    // Per Section E.7: Never claim SPEAKING until AudioTrack has accepted > 0 frames!
                    val writeJob = turnScope.launch(parentJob) {
                        val writeOutcome = playbackAdapter.verifyAndWritePcmChunk(
                            generationId = playbackGen,
                            rawMimeType = event.mimeType,
                            pcmBytes = event.pcmBytes
                        )
                        syncPlaybackState()

                        synchronized(this@ConversationCoordinator) {
                            if (activeVoiceTurnSeq != turnSeq || activeTurnGenerationId != turnGen) {
                                return@launch
                            }
                            if (writeOutcome is PcmChunkWriteOutcome.WrittenToHardware &&
                                writeOutcome.totalBytesAcceptedByHardware > 0L &&
                                playbackAdapter.state.value.totalFramesWrittenToHardware > 0L
                            ) {
                                appendTurnEventLocked("AUDIO_TRACK_WRITES")
                                appendFirstTurnLifecycleLocked("PLAYBACK")
                                if (_uiState.value.state == ConversationState.LISTENING ||
                                    _uiState.value.state == ConversationState.PROCESSING
                                ) {
                                    // Ensure zero mic send jobs can remain once SPEAKING begins
                                    stopTurnMicrophoneLocked(
                                        reason = "Playback started (SPEAKING)",
                                        cancelQueuedMicSends = true
                                    )
                                    val updatedSeq = appendTurnStateLocked(ConversationState.SPEAKING)
                                    val updatedReadiness = appendReadinessOrderLocked("SPEAKING")
                                    val speakDetail =
                                        "SPEAKING (${playbackGen.wireId}): Streaming Gemini native-audio PCM response through Android AudioTrack (${playbackAdapter.state.value.totalFramesWrittenToHardware} frames accepted)..."
                                    val speakOutcome = VerifiedOutcome.StateTransitionVerified(
                                        actionId = idGenerator.nextActionId("speaking"),
                                        sessionId = _uiState.value.sessionId,
                                        turnId = _uiState.value.currentTurnId,
                                        previousState = ConversationState.PROCESSING,
                                        resultingState = ConversationState.SPEAKING,
                                        detail = speakDetail
                                    )
                                    _uiState.update { state ->
                                        state.copy(
                                            voiceTurnStateSequence = updatedSeq,
                                            voiceTurnReadinessOrder = updatedReadiness,
                                            lastVoiceTurnSummary = if (state.currentTurnModelTranscript.isNotBlank()) {
                                                "Gemini: \"${state.currentTurnModelTranscript}\""
                                            } else {
                                                speakDetail
                                            }
                                        )
                                    }
                                    applyVerifiedOutcome(
                                        sessionId = _uiState.value.sessionId,
                                        turnId = _uiState.value.currentTurnId,
                                        actionId = speakOutcome.actionId,
                                        newState = ConversationState.SPEAKING,
                                        roleLabel = "AUDIO_PLAYBACK",
                                        outcome = speakOutcome
                                    )
                                }
                            } else {
                                val playSnap = playbackAdapter.state.value
                                val code = when (writeOutcome) {
                                    is PcmChunkWriteOutcome.RejectedMime -> "UNSUPPORTED_AUDIO_MIME"
                                    is PcmChunkWriteOutcome.FailedWrite -> "AUDIO_PLAYBACK_WRITE_FAILED"
                                    is PcmChunkWriteOutcome.DroppedStaleGeneration -> "STALE_PLAYBACK_GENERATION"
                                    else -> "ZERO_FRAMES_ACCEPTED_BY_AUDIOTRACK"
                                }
                                failActiveVoiceTurnLocked(
                                    failureCode = code,
                                    detail = "Native-audio playback rejected chunk [$code]: ${playSnap.lastError ?: playSnap.statusDetail}"
                                )
                            }
                        }
                    }
                    activeTurnWriteJobs.removeAll { it.isCompleted }
                    activeTurnWriteJobs.add(writeJob)
                }
            }

            is LiveSocketCallbackEvent.ServerTurnCompleted -> {
                val turnSeq: Long
                val turnGen: TurnGenerationId
                val playbackGen: PlaybackGenerationId
                val parentJob: CompletableJob
                val writeJobsSnapshot: List<Job>
                synchronized(this) {
                    turnSeq = activeVoiceTurnSeq
                    val currentTurnGen = activeTurnGenerationId
                    val currentPlaybackGen = activeTurnPlaybackGen
                    val currentParentJob = activeTurnParentJob
                    if (turnSeq == 0L || currentTurnGen == null || currentPlaybackGen == null || currentParentJob == null) {
                        return
                    }
                    turnGen = currentTurnGen
                    playbackGen = currentPlaybackGen
                    parentJob = currentParentJob
                    // Ensure microphone capture is completely stopped before draining response
                    if (_uiState.value.state == ConversationState.LISTENING || audioCapture.state.value.isCapturing) {
                        stopTurnMicrophoneLocked(
                            reason = "Server turn completed",
                            cancelQueuedMicSends = true
                        )
                    }
                    writeJobsSnapshot = activeTurnWriteJobs.toList()
                }

                // Await all queued audio buffer writes FIRST (even on interruption, do not discard
                // already-received audio chunks before they are written and drained to AudioTrack!).
                val drainJob = turnScope.launch(parentJob) {
                    for (job in writeJobsSnapshot) {
                        job.join()
                    }
                    val stillActiveBeforeDrain = synchronized(this@ConversationCoordinator) {
                        activeVoiceTurnSeq == turnSeq &&
                            activeTurnGenerationId == turnGen &&
                            activeTurnPlaybackGen == playbackGen
                    }
                    if (!stillActiveBeforeDrain) {
                        return@launch
                    }

                    val preDrainSnap = playbackAdapter.state.value
                    val drained = if (preDrainSnap.totalFramesWrittenToHardware > 0L) {
                        playbackAdapter.awaitHardwareDrainAfterServerTurnComplete(playbackGen)
                    } else {
                        false
                    }
                    syncPlaybackState()

                    if (event.interrupted) {
                        val sessionForRecovery: SessionId?
                        val shouldRecoverSession: Boolean
                        synchronized(this@ConversationCoordinator) {
                            if (activeVoiceTurnSeq != turnSeq || activeTurnGenerationId != turnGen) {
                                return@launch
                            }
                            if (drained && playbackAdapter.state.value.isPlaybackDrained) {
                                appendTurnEventLocked("PLAYBACK_DRAINED")
                            }
                            appendTurnEventLocked("SERVER_TURN_INTERRUPTED")
                            val current = _uiState.value
                            shouldRecoverSession = current.interruptionRecoveryCount < 1
                            sessionForRecovery = if (shouldRecoverSession) current.sessionId else null
                        }

                        // Perform at most ONE clean LiveSession recreation after an unexpected interruption
                        // (without recreating the Activity/app and without looping retries).
                        if (shouldRecoverSession && sessionForRecovery != null) {
                            liveAdapter.recreateSessionAfterInterruption(
                                sessionId = sessionForRecovery,
                                reason = "Single clean LiveSession recovery after SERVER_TURN_INTERRUPTED"
                            )
                        } else {
                            needsSessionRecreationBeforeNextTurn = true
                        }

                        synchronized(this@ConversationCoordinator) {
                            if (activeVoiceTurnSeq != turnSeq || activeTurnGenerationId != turnGen) {
                                return@launch
                            }
                            val playSnap = playbackAdapter.state.value
                            val newRecoveryCount = if (shouldRecoverSession) {
                                _uiState.value.interruptionRecoveryCount + 1
                            } else {
                                _uiState.value.interruptionRecoveryCount
                            }
                            _uiState.update { state ->
                                state.copy(interruptionRecoveryCount = newRecoveryCount)
                            }
                            val traceStr = _uiState.value.turnEventTrace.joinToString(" -> ")
                            val detailMsg =
                                "Gemini Live server turn was interrupted before completion [trace: $traceStr | audio recv=${playSnap.framesReceivedFromServer} frames, accepted=${playSnap.totalFramesWrittenToHardware} frames, played=${playSnap.hardwarePlaybackHeadFrames} frames (${playSnap.chunksReceived} chunks) | recovery=$newRecoveryCount/1]."
                            failActiveVoiceTurnLocked(
                                failureCode = "SERVER_TURN_INTERRUPTED",
                                detail = detailMsg
                            )
                        }
                        return@launch
                    }

                    synchronized(this@ConversationCoordinator) {
                        if (activeVoiceTurnSeq != turnSeq || activeTurnGenerationId != turnGen) {
                            return@launch
                        }
                        appendTurnEventLocked("SERVER_TURN_COMPLETE")
                        val playSnap = playbackAdapter.state.value
                        if (!drained || !playSnap.isPlaybackDrained || playSnap.totalFramesWrittenToHardware <= 0L) {
                            val failCode = when {
                                playSnap.chunksReceived > 0 && playSnap.totalFramesWrittenToHardware <= 0L ->
                                    "ZERO_FRAMES_ACCEPTED_BY_AUDIOTRACK"
                                !playSnap.lastError.isNullOrBlank() ->
                                    "AUDIO_PLAYBACK_DRAIN_FAILED"
                                else ->
                                    "HARDWARE_DRAIN_INCOMPLETE"
                            }
                            failActiveVoiceTurnLocked(
                                failureCode = failCode,
                                detail = "Audio playback did not drain cleanly [$failCode]: ${playSnap.lastError ?: playSnap.statusDetail}"
                            )
                            return@launch
                        }

                        appendTurnEventLocked("PLAYBACK_DRAINED")
                        appendTurnEventLocked("READY")
                        appendFirstTurnLifecycleLocked("PLAYBACK")

                        // Requirement 8: Complete voice turn, transition SPEAKING -> READY, and cleanly
                        // reset per-turn state so the next single Start press also works.
                        activeVoiceTurnSeq = 0L
                        activeTurnGenerationId = null
                        activeTurnPlaybackGen = null
                        activeTurnDrainJob = null
                        turnSpeechChunks = 0
                        turnTrailingSilenceChunks = 0
                        autoEndTurnTriggeredForSeq = 0L
                        activeTurnWriteJobs.clear()
                        activeTurnMicSendJobs.clear()
                        activeTurnParentJob?.cancel()
                        activeTurnParentJob = null
                        liveAdapter.setAudioPlaybackClaimed(false)

                        val updatedSeq = appendTurnStateLocked(ConversationState.READY)
                        val updatedReadiness = appendReadinessOrderLocked("READY")
                        val current = _uiState.value
                        val transcriptTail = buildList {
                            if (current.currentTurnUserTranscript.isNotBlank()) {
                                add("Heard: \"${current.currentTurnUserTranscript}\"")
                            }
                            if (current.currentTurnModelTranscript.isNotBlank()) {
                                add("Gemini: \"${current.currentTurnModelTranscript}\"")
                            }
                        }.joinToString(" | ")
                        val readySummary = buildString {
                            append("READY: Voice turn ${current.currentTurnId.value} completed & hardware playback fully drained (${playSnap.hardwarePlaybackHeadFrames}/${playSnap.totalFramesWrittenToHardware} frames at ${playSnap.verifiedSampleRateHz}Hz).")
                            if (transcriptTail.isNotBlank()) {
                                append(" ")
                                append(transcriptTail)
                            }
                        }
                        val readyOutcome = VerifiedOutcome.VoiceTurnOutcome(
                            actionId = idGenerator.nextActionId("turn_ready"),
                            sessionId = current.sessionId,
                            turnId = current.currentTurnId,
                            resultingState = ConversationState.READY,
                            stateSequence = updatedSeq,
                            micFramesSent = current.voiceTurnFramesSent,
                            playbackFramesDrained = playSnap.hardwarePlaybackHeadFrames,
                            isPlaybackDrained = true,
                            code = "VOICE_TURN_COMPLETED_DRAINED",
                            detail = readySummary
                        )
                        _uiState.update { state ->
                            state.copy(
                                activeTurnGenerationId = null,
                                voiceTurnStateSequence = updatedSeq,
                                voiceTurnReadinessOrder = updatedReadiness,
                                lastVoiceTurnSummary = readySummary
                            )
                        }
                        applyVerifiedOutcome(
                            sessionId = current.sessionId,
                            turnId = current.currentTurnId,
                            actionId = readyOutcome.actionId,
                            newState = ConversationState.READY,
                            roleLabel = "VOICE_TURN_COORDINATOR",
                            outcome = readyOutcome
                        )
                        activeTurnCompletionDeferred?.complete(readyOutcome)
                    }
                }
                synchronized(this) {
                    if (activeVoiceTurnSeq == turnSeq) {
                        activeTurnDrainJob = drainJob
                    }
                }
            }

            else -> Unit
        }
    }

    private fun logTurnLifecycleEventLocked(message: String) {
        try {
            android.util.Log.i("OmniVoiceTurn", DiagnosticRedactor.redact(message))
        } catch (_: Throwable) {
            // Safe in pure JVM unit tests where android.util.Log is not mocked
        }
    }

    private fun appendTurnEventLocked(step: String) {
        val existing = _uiState.value.turnEventTrace
        if (existing.lastOrNull() != step) {
            _uiState.update { state ->
                state.copy(turnEventTrace = existing + step)
            }
        }
    }

    private fun appendFirstTurnLifecycleLocked(step: String) {
        val existing = _uiState.value.firstTurnLifecycleTrace
        if (!existing.contains(step)) {
            val updated = existing + step
            _uiState.update { state ->
                state.copy(firstTurnLifecycleTrace = updated)
            }
            logTurnLifecycleEventLocked("FirstTurnOrder: ${updated.joinToString(" -> ")}")
        }
    }

    private fun appendReadinessOrderLocked(stage: String): List<String> {
        val existing = _uiState.value.voiceTurnReadinessOrder
        val updated = if (existing.lastOrNull() == stage) {
            existing
        } else {
            existing + stage
        }
        _uiState.update { state ->
            state.copy(voiceTurnReadinessOrder = updated)
        }
        return updated
    }

    private fun stopTurnMicrophoneLocked(
        reason: String,
        cancelQueuedMicSends: Boolean = true
    ) {
        if (cancelQueuedMicSends) {
            for (job in activeTurnMicSendJobs) {
                job.cancel()
            }
            activeTurnMicSendJobs.clear()
        }
        audioCapture.stopAndRelease(reason = reason, wipeEphemeral = true)
    }

    private fun appendTurnStateLocked(nextState: ConversationState): List<ConversationState> {
        val existing = _uiState.value.voiceTurnStateSequence
        return if (existing.lastOrNull() == nextState) {
            existing
        } else {
            existing + nextState
        }
    }

    private fun cancelActiveTurnStreamsLocked(reason: String) {
        activeVoiceTurnSeq = 0L
        activeTurnGenerationId = null
        activeTurnPlaybackGen = null
        turnSpeechChunks = 0
        turnTrailingSilenceChunks = 0
        autoEndTurnTriggeredForSeq = 0L
        activeTurnDrainJob?.cancel()
        activeTurnDrainJob = null
        for (job in activeTurnMicSendJobs) {
            job.cancel()
        }
        activeTurnMicSendJobs.clear()
        for (job in activeTurnWriteJobs) {
            job.cancel()
        }
        activeTurnWriteJobs.clear()
        activeTurnParentJob?.cancel()
        activeTurnParentJob = null
        audioCapture.stopAndRelease(reason = reason, wipeEphemeral = true)
        playbackAdapter.stopAndFlush(reason = reason)
        liveAdapter.setAudioPlaybackClaimed(false)
    }

    private fun failActiveVoiceTurnLocked(
        failureCode: String,
        detail: String
    ): VerifiedOutcome.PrerequisiteFailureOutcome {
        cancelActiveTurnStreamsLocked(reason = "Voice turn failed [$failureCode]")
        val current = _uiState.value
        val nextTurn = idGenerator.nextTurnId()
        val actId = idGenerator.nextActionId("turn_error")
        val updatedSeq = appendTurnStateLocked(ConversationState.ERROR)
        val updatedReadiness = appendReadinessOrderLocked("ERROR")
        val outcome = VerifiedOutcome.PrerequisiteFailureOutcome(
            actionId = actId,
            sessionId = current.sessionId,
            turnId = nextTurn,
            resultingState = ConversationState.ERROR,
            failureCode = failureCode,
            detail = detail
        )
        _uiState.update { state ->
            state.copy(
                activeTurnGenerationId = null,
                voiceTurnStateSequence = updatedSeq,
                voiceTurnReadinessOrder = updatedReadiness,
                activeVoiceTurnErrorCode = failureCode,
                activeVoiceTurnErrorDetail = detail,
                lastVoiceTurnSummary = "ERROR [$failureCode]: $detail"
            )
        }
        applyVerifiedOutcome(
            sessionId = current.sessionId,
            turnId = nextTurn,
            actionId = actId,
            newState = ConversationState.ERROR,
            roleLabel = "VOICE_TURN_ERROR",
            outcome = outcome
        )
        activeTurnCompletionDeferred?.complete(outcome)
        return outcome
    }

    /**
     * Initiates an authenticated Gemini Live WebSocket handshake with bounded backoff for transient
     * failures through the single [GeminiLiveConnectionAdapter], recording a typed [VerifiedOutcome.LiveConnectionOutcome]
     * in the coordinator transcript.
     */
    suspend fun connectLiveSessionWithHandshake(
        actionId: ActionId = idGenerator.nextActionId("connect_live")
    ): VerifiedOutcome.LiveConnectionOutcome {
        val sessionSnapshot = synchronized(this) {
            val current = _uiState.value
            if (executedActionIds.contains(actionId.value)) {
                return VerifiedOutcome.LiveConnectionOutcome(
                    actionId = actionId,
                    sessionId = current.sessionId,
                    turnId = current.currentTurnId,
                    resultingState = current.state,
                    livePhase = current.liveConnectionState.phase,
                    generationId = current.liveConnectionState.activeGeneration,
                    failureClassification = current.liveConnectionState.failureClassification,
                    code = "EXACTLY_ONCE_DUPLICATE_REJECTED",
                    detail = "Duplicate Live connect ActionId ${actionId.value} rejected."
                )
            }
            recordActionId(actionId)
            liveAdapter.startNetworkMonitoring()
            current.sessionId
        }

        val finalLiveState = liveAdapter.connectWithBoundedRetry(sessionSnapshot)

        return synchronized(this) {
            val current = _uiState.value
            val nextTurn = idGenerator.nextTurnId()
            val outcomeCode = when (finalLiveState.phase) {
                LiveConnectionPhase.SETUP_ACCEPTED -> "LIVE_SETUP_ACCEPTED"
                LiveConnectionPhase.NETWORK_UNAVAILABLE -> "LIVE_NETWORK_UNAVAILABLE"
                LiveConnectionPhase.NETWORK_RESTORED -> "LIVE_NETWORK_RESTORED"
                LiveConnectionPhase.FAILED -> "LIVE_FAILED_${finalLiveState.failureClassification.wireCode}"
                LiveConnectionPhase.DISCONNECTED -> "LIVE_DISCONNECTED"
                else -> "LIVE_${finalLiveState.phase.wireCode}"
            }
            val detailMsg = when (finalLiveState.phase) {
                LiveConnectionPhase.SETUP_ACCEPTED ->
                    "Gemini Live handshake verified (${finalLiveState.diagnostics.modelId} @ ${finalLiveState.diagnostics.sessionGenerationWireId}, attempts=${finalLiveState.attemptCount}, tools=0)."
                LiveConnectionPhase.NETWORK_UNAVAILABLE ->
                    "Gemini Live handshake blocked [NETWORK_UNAVAILABLE]: ${finalLiveState.diagnostics.redactedSummary}"
                LiveConnectionPhase.FAILED ->
                    "Gemini Live handshake failed [${finalLiveState.failureClassification.wireCode}] after ${finalLiveState.attemptCount} attempt(s) (retries=${finalLiveState.retryCount}): ${finalLiveState.diagnostics.redactedSummary}"
                else ->
                    finalLiveState.diagnostics.redactedSummary
            }
            val outcome = VerifiedOutcome.LiveConnectionOutcome(
                actionId = actionId,
                sessionId = current.sessionId,
                turnId = nextTurn,
                resultingState = current.state,
                livePhase = finalLiveState.phase,
                generationId = finalLiveState.activeGeneration ?: finalLiveState.lastCompletedGeneration,
                failureClassification = finalLiveState.failureClassification,
                code = outcomeCode,
                detail = detailMsg
            )
            applyVerifiedOutcome(
                sessionId = current.sessionId,
                turnId = nextTurn,
                actionId = actionId,
                newState = current.state,
                roleLabel = "LIVE_ADAPTER",
                outcome = outcome
            )
            outcome
        }
    }

    /**
     * Explicit lifecycle hook when navigating away from the Microphone Setup screen or when
     * the Activity stops/backgrounds (not a configuration change).
     * Immediately releases audio hardware, wipes ephemeral self-test audio, flushes active playback,
     * closes any active live socket, and unregisters connectivity callbacks when disconnecting Live.
     */
    @Synchronized
    fun onLifecycleReleaseHardware(reason: String, disconnectLive: Boolean = false) {
        val hadActiveHardware = audioCapture.state.value.isCapturing ||
            audioCapture.state.value.isPlayingEphemeralCheck ||
            audioCapture.state.value.ephemeralRecordedSamples > 0 ||
            playbackAdapter.state.value.isPlaying
        val hadActiveLive = disconnectLive && (
            liveAdapter.state.value.phase == LiveConnectionPhase.CONNECTING ||
                liveAdapter.state.value.phase == LiveConnectionPhase.SETUP_ACCEPTED ||
                liveAdapter.state.value.phase == LiveConnectionPhase.RETRY_BACKOFF_WAITING
            )

        if (disconnectLive) {
            cancelActiveTurnStreamsLocked(reason = reason)
            liveAdapter.disconnectAndRelease(reason = reason)
            liveAdapter.stopNetworkMonitoring()
        } else {
            audioCapture.stopAndRelease(reason = reason, wipeEphemeral = true)
        }
        syncAudioState()
        if (hadActiveHardware || hadActiveLive) {
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

    private fun recordDuplicateOutcomeLocked(
        actionId: ActionId,
        duplicateOutcome: VerifiedOutcome.DuplicateActionRejected
    ) {
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
            recordDuplicateOutcomeLocked(actionId, duplicateOutcome)
            return duplicateOutcome
        }

        recordActionId(actionId)
        val nextTurn = idGenerator.nextTurnId()

        return when (command) {
            CoordinatorCommand.STOP -> {
                // Large Stop MUST promptly cancel microphone capture, the current model turn, and queued playback,
                // invalidate playback/socket generations so stopped audio never replays, and close the live socket.
                val deferredToCancel = activeTurnCompletionDeferred
                activeTurnCompletionDeferred = null
                cancelActiveTurnStreamsLocked("Immediate operator STOP command")
                liveAdapter.disconnectAndRelease(reason = "Immediate operator STOP command")

                val outcome = VerifiedOutcome.StateTransitionVerified(
                    actionId = actionId,
                    sessionId = current.sessionId,
                    turnId = nextTurn,
                    previousState = current.state,
                    resultingState = ConversationState.STOPPED,
                    detail = "STOP command executed with highest precedence (priority ${CoordinatorCommand.STOP.priority}). Microphone capture, model turn, and queued playback cancelled; generation IDs invalidated."
                )
                _uiState.update { state ->
                    state.copy(
                        lastVoiceTurnSummary = "STOPPED: Microphone capture, model turn, and queued audio playback cancelled."
                    )
                }
                applyVerifiedOutcome(
                    sessionId = current.sessionId,
                    turnId = nextTurn,
                    actionId = actionId,
                    newState = ConversationState.STOPPED,
                    roleLabel = "OPERATOR_STOP",
                    outcome = outcome
                )
                deferredToCancel?.complete(outcome)
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
                    val deferredToCancel = activeTurnCompletionDeferred
                    activeTurnCompletionDeferred = null
                    activeVoiceTurnSeq = 0L
                    activeTurnPlaybackGen = null
                    activeTurnDrainJob?.cancel()
                    activeTurnDrainJob = null
                    audioCapture.stopAndRelease(
                        reason = "Operator PAUSE command",
                        wipeEphemeral = false
                    )
                    playbackAdapter.stopAndFlush(reason = "Operator PAUSE command")
                    liveAdapter.setAudioPlaybackClaimed(false)
                    liveAdapter.disconnectAndRelease(reason = "Operator PAUSE command")
                    val outcome = VerifiedOutcome.StateTransitionVerified(
                        actionId = actionId,
                        sessionId = current.sessionId,
                        turnId = nextTurn,
                        previousState = current.state,
                        resultingState = ConversationState.PAUSED,
                        detail = "PAUSE command executed (priority ${CoordinatorCommand.PAUSE.priority}). Active audio capture, playback, and live socket released; shell paused."
                    )
                    _uiState.update { state ->
                        state.copy(
                            lastVoiceTurnSummary = "PAUSED: Session paused; active capture and playback released."
                        )
                    }
                    applyVerifiedOutcome(
                        sessionId = current.sessionId,
                        turnId = nextTurn,
                        actionId = actionId,
                        newState = ConversationState.PAUSED,
                        roleLabel = "OPERATOR_PAUSE",
                        outcome = outcome
                    )
                    deferredToCancel?.complete(outcome)
                    outcome
                }
            }

            CoordinatorCommand.START -> {
                // Synchronous START command check: never shows NOT_CONFIGURED when adapters are configured.
                when (val prereq = checkVoiceTurnPrerequisitesLocked()) {
                    is PrerequisiteCheck.NotConfigured -> {
                        val outcome = VerifiedOutcome.HonestNotConfiguredOutcome(
                            actionId = actionId,
                            sessionId = current.sessionId,
                            turnId = nextTurn,
                            resultingState = ConversationState.NOT_CONFIGURED,
                            detail = prereq.detail
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

                    is PrerequisiteCheck.MissingPrerequisite -> {
                        if (prereq.code == "NETWORK_UNAVAILABLE") {
                            liveAdapter.onNetworkValidatedChanged(false)
                        }
                        val outcome = VerifiedOutcome.PrerequisiteFailureOutcome(
                            actionId = actionId,
                            sessionId = current.sessionId,
                            turnId = nextTurn,
                            resultingState = ConversationState.ERROR,
                            failureCode = prereq.code,
                            detail = prereq.detail
                        )
                        _uiState.update { state ->
                            state.copy(
                                activeVoiceTurnErrorCode = prereq.code,
                                activeVoiceTurnErrorDetail = prereq.detail,
                                lastVoiceTurnSummary = "ERROR [${prereq.code}]: ${prereq.detail}"
                            )
                        }
                        applyVerifiedOutcome(
                            sessionId = current.sessionId,
                            turnId = nextTurn,
                            actionId = actionId,
                            newState = ConversationState.ERROR,
                            roleLabel = "PREREQUISITE_GUARD",
                            outcome = outcome
                        )
                        outcome
                    }

                    is PrerequisiteCheck.Passed -> {
                        val seqList = listOf(ConversationState.CONNECTING)
                        val outcome = VerifiedOutcome.StateTransitionVerified(
                            actionId = actionId,
                            sessionId = current.sessionId,
                            turnId = nextTurn,
                            previousState = current.state,
                            resultingState = ConversationState.CONNECTING,
                            detail = "CONNECTING: Phase 04 one-turn voice coordinator initialized for ${liveAdapter.configuredModelId}."
                        )
                        _uiState.update { state ->
                            state.copy(
                                voiceTurnStateSequence = seqList,
                                activeVoiceTurnErrorCode = null,
                                activeVoiceTurnErrorDetail = null,
                                lastVoiceTurnSummary = outcome.detail
                            )
                        }
                        applyVerifiedOutcome(
                            sessionId = current.sessionId,
                            turnId = nextTurn,
                            actionId = actionId,
                            newState = ConversationState.CONNECTING,
                            roleLabel = "OPERATOR_START",
                            outcome = outcome
                        )
                        outcome
                    }
                }
            }

            CoordinatorCommand.END_SPOKEN_TURN -> {
                if (current.state == ConversationState.LISTENING && activeVoiceTurnSeq != 0L) {
                    val jobsToFlush = activeTurnMicSendJobs.filter { it.isActive }
                    stopTurnMicrophoneLocked(
                        reason = "Operator END_SPOKEN_TURN command",
                        cancelQueuedMicSends = false
                    )
                    appendTurnEventLocked("USER_ACTIVITY_END")
                    appendFirstTurnLifecycleLocked("ACTIVITY_END_OF_TURN")
                    val updatedSeq = appendTurnStateLocked(ConversationState.PROCESSING)
                    val updatedReadiness = appendReadinessOrderLocked("PROCESSING")
                    val outcome = VerifiedOutcome.StateTransitionVerified(
                        actionId = actionId,
                        sessionId = current.sessionId,
                        turnId = nextTurn,
                        previousState = ConversationState.LISTENING,
                        resultingState = ConversationState.PROCESSING,
                        detail = "PROCESSING: End of spoken turn signaled (${current.voiceTurnFramesSent} frames). Awaiting Gemini native audio..."
                    )
                    _uiState.update { state ->
                        state.copy(
                            voiceTurnStateSequence = updatedSeq,
                            voiceTurnReadinessOrder = updatedReadiness,
                            lastVoiceTurnSummary = outcome.detail
                        )
                    }
                    applyVerifiedOutcome(
                        sessionId = current.sessionId,
                        turnId = nextTurn,
                        actionId = actionId,
                        newState = ConversationState.PROCESSING,
                        roleLabel = "VOICE_TURN_COORDINATOR",
                        outcome = outcome
                    )
                    val parentJob = activeTurnParentJob
                    if (parentJob != null) {
                        turnScope.launch(parentJob) {
                            for (job in jobsToFlush) {
                                job.join()
                            }
                            micSendMutex.withLock {
                                liveAdapter.signalEndOfSpokenTurn()
                            }
                        }
                    } else {
                        turnScope.launch {
                            for (job in jobsToFlush) {
                                job.join()
                            }
                            micSendMutex.withLock {
                                liveAdapter.signalEndOfSpokenTurn()
                            }
                        }
                    }
                    outcome
                } else {
                    val outcome = VerifiedOutcome.StateTransitionVerified(
                        actionId = actionId,
                        sessionId = current.sessionId,
                        turnId = nextTurn,
                        previousState = current.state,
                        resultingState = current.state,
                        detail = "END_SPOKEN_TURN ignored because coordinator is in ${current.state.wireLabel}."
                    )
                    applyVerifiedOutcome(
                        sessionId = current.sessionId,
                        turnId = nextTurn,
                        actionId = actionId,
                        newState = current.state,
                        roleLabel = "VOICE_TURN_COORDINATOR",
                        outcome = outcome
                    )
                    outcome
                }
            }

            CoordinatorCommand.DISCONNECT_LIVE_SESSION -> {
                cancelActiveTurnStreamsLocked("Operator disconnected Gemini Live session")
                val prevGen = liveAdapter.state.value.activeGeneration ?: liveAdapter.state.value.lastCompletedGeneration
                liveAdapter.disconnectAndRelease(reason = "Operator disconnected Gemini Live session")
                val liveSnap = liveAdapter.state.value
                val outcome = VerifiedOutcome.LiveConnectionOutcome(
                    actionId = actionId,
                    sessionId = current.sessionId,
                    turnId = nextTurn,
                    resultingState = current.state,
                    livePhase = liveSnap.phase,
                    generationId = prevGen,
                    failureClassification = LiveFailureClassification.NONE,
                    code = "LIVE_SESSION_DISCONNECTED",
                    detail = "Gemini Live session disconnected and socket released (${prevGen?.wireId ?: "NONE"})."
                )
                applyVerifiedOutcome(
                    sessionId = current.sessionId,
                    turnId = nextTurn,
                    actionId = actionId,
                    newState = current.state,
                    roleLabel = "LIVE_ADAPTER",
                    outcome = outcome
                )
                outcome
            }

            CoordinatorCommand.CONNECT_LIVE_SESSION -> {
                val liveSnap = liveAdapter.state.value
                val outcome = VerifiedOutcome.LiveConnectionOutcome(
                    actionId = actionId,
                    sessionId = current.sessionId,
                    turnId = nextTurn,
                    resultingState = current.state,
                    livePhase = liveSnap.phase,
                    generationId = liveSnap.activeGeneration,
                    failureClassification = liveSnap.failureClassification,
                    code = "LIVE_CONNECT_REQUESTED",
                    detail = "Gemini Live handshake verification requested for ${liveAdapter.configuredModelId}."
                )
                applyVerifiedOutcome(
                    sessionId = current.sessionId,
                    turnId = nextTurn,
                    actionId = actionId,
                    newState = current.state,
                    roleLabel = "LIVE_ADAPTER",
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
                cancelActiveTurnStreamsLocked("Session reset to IDLE")
                liveAdapter.disconnectAndRelease(reason = "Session reset to IDLE")
                needsSessionRecreationBeforeNextTurn = false
                val freshSession = idGenerator.nextSessionId()
                val outcome = VerifiedOutcome.StateTransitionVerified(
                    actionId = actionId,
                    sessionId = freshSession,
                    turnId = nextTurn,
                    previousState = current.state,
                    resultingState = ConversationState.IDLE,
                    detail = "Session reset to IDLE with new session ID ${freshSession.value}. Audio hardware and live socket released."
                )
                _uiState.update { state ->
                    state.copy(
                        voiceTurnStateSequence = emptyList(),
                        voiceTurnReadinessOrder = emptyList(),
                        activeTurnGenerationId = null,
                        turnEventTrace = emptyList(),
                        firstTurnLifecycleTrace = emptyList(),
                        debouncedStartCount = 0,
                        activeVoiceTurnErrorCode = null,
                        activeVoiceTurnErrorDetail = null,
                        voiceTurnFramesSent = 0L,
                        voiceTurnChunksSent = 0L,
                        voiceTurnBytesSent = 0L,
                        voiceTurnPeakRmsLinear = 0f,
                        interruptionRecoveryCount = 0,
                        lastVoiceTurnSummary = "Ready. Press Start to begin a verified one-turn Gemini Live voice conversation."
                    )
                }
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
     * (STOP > STOP_MIC_SELF_TEST > DISCONNECT_LIVE_SESSION > PAUSE > END_SPOKEN_TURN > CONNECT_LIVE_SESSION > START_MIC_SELF_TEST > START).
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
            cancelActiveTurnStreamsLocked("Preempted by ${winningCommand.name} precedence")
            liveAdapter.disconnectAndRelease(
                reason = "Preempted by ${winningCommand.name} precedence"
            )
        }

        val targetState = when (winningCommand) {
            CoordinatorCommand.STOP -> ConversationState.STOPPED
            CoordinatorCommand.PAUSE ->
                if (current.state == ConversationState.STOPPED) ConversationState.STOPPED
                else ConversationState.PAUSED
            CoordinatorCommand.START ->
                if (!isVoiceTurnConfigured) ConversationState.NOT_CONFIGURED
                else ConversationState.CONNECTING
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

    private fun shortArrayToLittleEndianBytes(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        var byteIdx = 0
        for (sample in samples) {
            val v = sample.toInt()
            bytes[byteIdx++] = (v and 0xFF).toByte()
            bytes[byteIdx++] = ((v ushr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun formatMicLabel(snap: AudioCaptureState): String = when {
        snap.isCapturing && snap.owner == AudioOwner.LIVE_VOICE_TURN ->
            "MIC: LIVE STREAMING 16kHz PCM (${snap.activeGeneration?.wireId ?: "live"} • ${snap.capturedFrameCount} frames)"
        snap.isCapturing ->
            "MIC: CAPTURING (${snap.activeGeneration?.wireId ?: "active"} • ${snap.capturedFrameCount} frames)"
        snap.isPlayingEphemeralCheck ->
            "MIC: PLAYING EPHEMERAL CHECK (${snap.activeGeneration?.wireId ?: "play"})"
        snap.permissionState == MicPermissionState.GRANTED ->
            "MIC: READY (PERMISSION GRANTED • IDLE)"
        snap.permissionState == MicPermissionState.PERMANENTLY_DENIED ->
            "MIC: BLOCKED (PERMANENTLY DENIED — OPEN SETTINGS)"
        snap.permissionState == MicPermissionState.REFUSED_CAN_RETRY ->
            "MIC: PERMISSION REFUSED (CAN RETRY)"
        else ->
            "MIC: PERMISSION REQUIRED (GRANT ON START OR MIC SETUP)"
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
        val liveSnap = liveAdapter.state.value
        val playSnap = playbackAdapter.state.value
        val micLabel = formatMicLabel(snap)

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
                liveConnectionState = liveSnap,
                playbackState = playSnap,
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

    @Synchronized
    fun isVoiceTurnInProgress(): Boolean {
        val currentState = _uiState.value.state
        return activeVoiceTurnSeq != 0L ||
            currentState == ConversationState.CONNECTING ||
            currentState == ConversationState.LISTENING ||
            currentState == ConversationState.PROCESSING ||
            currentState == ConversationState.SPEAKING
    }
}
