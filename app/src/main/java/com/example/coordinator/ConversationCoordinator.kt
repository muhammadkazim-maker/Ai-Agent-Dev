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
import com.example.audio.PlaybackCallbackEvent
import com.example.audio.PlaybackGenerationId
import com.example.audio.DefaultNoContextSyntheticSpeechDetector
import com.example.audio.SileroVadDetector
import com.example.audio.SpeechActivityGate
import com.example.audio.SpeechGateDecision
import com.example.audio.SpeechGateState
import com.example.audio.SpeechNeuralDetector
import com.example.audio.WakeWordDetector
import com.example.audio.WakeAuthorization
import com.example.audio.WakeWordState
import com.example.audio.FakeWakeWordDetector
import com.example.contract.ActionId
import com.example.contract.ActiveConversationPhase
import com.example.contract.ContractIdGenerator
import com.example.contract.ConversationState
import com.example.contract.CoordinatorCommand
import com.example.contract.InactivityTimeoutOption
import com.example.contract.SessionDialogueTurn
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    val activeConversationPhase: ActiveConversationPhase = ActiveConversationPhase.IDLE,
    val activeConversationPhaseSequence: List<ActiveConversationPhase> = emptyList(),
    val isActiveConversationEnabled: Boolean = false,
    val completedTurnsInSession: Int = 0,
    val isDeliberatelyPaused: Boolean = false,
    val isDeliberatelyStopped: Boolean = false,
    val hasCompletedFirstUseConsentAndConfig: Boolean = false,
    val autoResumeOnScreenReopen: Boolean = false,
    val inactivityTimeoutOption: InactivityTimeoutOption = InactivityTimeoutOption.MINUTES_2,
    val inactivityTimeoutMs: Long = InactivityTimeoutOption.MINUTES_2.durationMs,
    val inactivityClosedCount: Int = 0,
    val resourceReleaseBeforeCaptureCount: Int = 0,
    val configurationChangePreservedCount: Int = 0,
    val autoResumeBlockedByPauseOrStopCount: Int = 0,
    val duplexPolicyLabel: String = "HALF-DUPLEX EXPLICIT (Mic disabled during THINKING & SPEAKING; spoken barge-in not claimed)",
    val isSpokenBargeInClaimed: Boolean = false,
    val sessionDialogueHistory: List<SessionDialogueTurn> = emptyList(),
    val maxSessionDialogueTurns: Int = 8,
    val maxTranscriptEntries: Int = 24,
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
    val structuredTurnTrace: List<String> = emptyList(),
    val lastCompletedTurnTrace: List<String> = emptyList(),
    val activeTurnEarliestMissingStep: String? = null,
    val lastCompletedTurnEarliestMissingStep: String? = null,
    val debouncedStartCount: Int = 0,
    val activeVoiceTurnErrorCode: String? = null,
    val activeVoiceTurnErrorDetail: String? = null,
    val voiceTurnFramesSent: Long = 0L,
    val voiceTurnChunksSent: Long = 0L,
    val voiceTurnBytesSent: Long = 0L,
    val voiceTurnPeakRmsLinear: Float = 0f,
    val interruptionRecoveryCount: Int = 0,
    val firstResponseTimeoutCount: Int = 0,
    val firstResponseRecoveryCount: Int = 0,
    val staleTurnCallbacksDropped: Int = 0,
    val currentTurnUserTranscript: String = "",
    val currentTurnModelTranscript: String = "",
    val authoritativeSystemInstruction: String = LiveConfigConstants.AUTHORITATIVE_SYSTEM_INSTRUCTION,
    val lastVoiceTurnSummary: String = "Ready. Press Start to begin a verified Gemini Live voice conversation.",
    val speechGateState: SpeechGateState = SpeechGateState.IDLE,
    val vadDetectorName: String = SileroVadDetector.DETECTOR_NAME,
    val vadDetectorVersion: String = SileroVadDetector.DETECTOR_VERSION,
    val vadModelLoaded: Boolean = false,
    val vadModelLoadError: String? = null,
    val vadSpeechDecision: Boolean = false,
    val vadSpeechProbability: Float = 0f,
    val vadCandidateConfirmationCount: Int = 0,
    val noiseFloorRms: Float = 0.002f,
    val activeSpeechStartThresholdRms: Float = 0.012f,
    val consecutiveSpeechDurationMs: Long = 0L,
    val preRollDurationMs: Long = 0L,
    val preRollBytes: Int = 0,
    val falseStartsRejectedCount: Int = 0,
    val activityStartCount: Int = 0,
    val activityEndCount: Int = 0,
    val isPostPlaybackGuardActive: Boolean = false,
    val wakeWordState: WakeWordState = WakeWordState.COMMAND_LISTENING,
    val wakeWordStatusLabel: String = "LISTENING (NATURAL SPEECH ACTIVE)",
    val wakeAuthorizationActive: Boolean = true,
    val lastWakeKeyword: String? = null,
    val startupTimingSnapshot: Phase07StartupTimingSnapshot = Phase07StartupTimingSnapshot(),
    val activeTurnTimingSnapshot: Phase07TurnTimingSnapshot? = null,
    val lastCompletedTurnTimingSnapshot: Phase07TurnTimingSnapshot? = null,
    val currentActiveDiagnosticError: RedactedDiagnosticErrorEntry? = null,
    val previousDiagnosticErrors: List<RedactedDiagnosticErrorEntry> = emptyList(),
    val serverOutputStallCount: Int = 0,
    val missingTurnCompleteCount: Int = 0,
    val hardwarePlaybackStallCount: Int = 0,
    val performanceSummary: Phase07PerformanceSummary = Phase07PerformanceSummary()
)

/**
 * Single ConversationCoordinator for OmniVoice Rebuild (Phase 01 through Phase 07).
 */
class ConversationCoordinator(
    private val idGenerator: ContractIdGenerator = ContractIdGenerator(),
    val audioCapture: AudioCapture = AudioCapture(DefaultNoContextAudioDriver()),
    val liveAdapter: GeminiLiveConnectionAdapter = GeminiLiveConnectionAdapter(),
    val playbackAdapter: LiveAudioPlaybackAdapter = LiveAudioPlaybackAdapter(),
    val preferencesStore: ConversationPreferencesStore = InMemoryConversationPreferencesStore(),
    val isVoiceTurnConfigured: Boolean = true,
    val speechRmsThreshold: Float = 0.010f,
    val minSpeechChunksForAutoEnd: Int = 5,
    val silenceChunksForAutoEnd: Int = 18,
    val connectionTimeoutMs: Long = 20_000L,
    val customInactivityTimeoutMs: Long? = null,
    val maxSessionDialogueTurns: Int = 8,
    val maxTranscriptEntries: Int = 24,
    val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    val firstResponseTimeoutMs: Long = 12_000L,
    val firstAudioAfterResponseTimeoutMs: Long = firstResponseTimeoutMs,
    val serverOutputStallTimeoutMs: Long = max(firstResponseTimeoutMs, 10_000L),
    val missingTurnCompleteTimeoutMs: Long = minOf(max(firstResponseTimeoutMs, 10_000L), 5_000L),
    val maxTurnResponseHardBoundMs: Long = 90_000L,
    val hardwarePlaybackStallTimeoutMs: Long = 5_000L,
    private val monotonicClockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    val preRollDurationMs: Long = 300L,
    val speechConfirmDurationMs: Long = 120L,
    val trailingSilenceDurationMs: Long = 700L,
    val postPlaybackEchoGuardMs: Long = 500L,
    private val postPlaybackGuardDelayRunner: suspend (Long) -> Unit = { ms -> delay(ms) },
    private val clockProvider: () -> String = { "PHASE05-T+${System.currentTimeMillis() % 100000}ms" },
    val speechDetector: SpeechNeuralDetector = DefaultNoContextSyntheticSpeechDetector(
        energyThresholdRms = max(0.012f, speechRmsThreshold)
    ),
    val wakeWordDetector: WakeWordDetector = FakeWakeWordDetector(),
    val isRealDeviceMeasurementMode: Boolean =
        speechDetector.requiresLoadedModel &&
            playbackAdapter.driver is com.example.audio.AndroidStreamingAudioTrackDriver
) {
    private sealed interface PrerequisiteCheck {
        data object Passed : PrerequisiteCheck
        data class NotConfigured(val detail: String) : PrerequisiteCheck
        data class MissingPrerequisite(val code: String, val detail: String) : PrerequisiteCheck
    }

    private val isSyntheticOrTestMeasurementMode: Boolean
        get() = !isRealDeviceMeasurementMode

    private val effectivePreRollDurationMs: Long =
        if (speechDetector.requiresLoadedModel && preRollDurationMs == 300L) 480L else preRollDurationMs

    private val effectiveSpeechConfirmDurationMs: Long =
        if (speechDetector.requiresLoadedModel && speechConfirmDurationMs == 120L) 256L else speechConfirmDurationMs

    val speechGate: SpeechActivityGate = SpeechActivityGate(
        speechDetector = speechDetector,
        sampleRateHz = PcmResampler.TARGET_SAMPLE_RATE_HZ,
        preRollTargetMs = effectivePreRollDurationMs,
        speechConfirmDurationMs = effectiveSpeechConfirmDurationMs,
        trailingSilenceDurationMs = trailingSilenceDurationMs,
        postPlaybackEchoGuardMs = postPlaybackEchoGuardMs,
        baseMinThresholdRms = max(0.012f, speechRmsThreshold),
        monotonicClockMs = monotonicClockMs
    )

    private val executedActionIds = LinkedHashSet<String>()
    private val maxTrackedActions = 512
    private val handledCompletionTurnGenIds = LinkedHashSet<String>()
    private val inFlightCompletionTurnGenIds = LinkedHashSet<String>()

    private val voiceTurnCounter = AtomicLong(0L)
    @Volatile
    private var activeTurn: TurnRuntimeState? = null
    @Volatile
    private var inactivityTimerJob: Job? = null
    @Volatile
    private var activeTimeoutRecoveryJob: Job? = null
    @Volatile
    private var activeTimeoutRecoveryDeferred: CompletableDeferred<VerifiedOutcome>? = null
    @Volatile
    private var activeNextTurnRecoveryJob: Job? = null
    @Volatile
    private var postPlaybackGuardJob: Job? = null
    private val micSendMutex = Mutex()
    @Volatile
    private var needsSessionRecreationBeforeNextTurn: Boolean = false
    @Volatile
    private var isDeliberatelyPaused: Boolean = false
    @Volatile
    private var isDeliberatelyStopped: Boolean = false
    @Volatile
    private var previousTurnEarliestMissingStep: String? = null
    private var consecutiveFirstResponseTimeouts: Int = 0

    @Volatile
    private var wakeWordState: WakeWordState = WakeWordState.COMMAND_LISTENING
    @Volatile
    private var activeWakeAuthorization: WakeAuthorization? = null

    private val maxErrorHistoryEntries = 10
    private val diagnosticErrorSequence = AtomicLong(0L)
    private val previousDiagnosticErrorsHistory = ArrayDeque<RedactedDiagnosticErrorEntry>()
    @Volatile
    private var currentActiveDiagnosticErrorEntry: RedactedDiagnosticErrorEntry? = null
    @Volatile
    private var lastCompletedTurnRuntime: TurnRuntimeState? = null
    @Volatile
    private var latestStartupTiming: Phase07StartupTimingSnapshot = Phase07StartupTimingSnapshot(
        isSyntheticOrTestMeasurement = isSyntheticOrTestMeasurementMode
    )

    private val recordedTurnOutcomes = ArrayDeque<Phase07TurnOutcomeRecord>()
    private var totalAttemptedTurnsCounter: Int = 0
    private var totalCompletedTurnsCounter: Int = 0
    private var totalFailedTurnsCounter: Int = 0
    private var totalInterruptedTurnsCounter: Int = 0
    private var totalTimedOutTurnsCounter: Int = 0
    private var totalNoSpeechRejectedTurnsCounter: Int = 0

    private fun ensureWakeAuthorizationValid(turn: TurnRuntimeState?): Boolean {
        return true
    }

    private fun recordDiagnosticErrorLocked(
        stage: String,
        errorCode: String,
        rawSummary: String,
        sessionId: SessionId = _uiState.value.sessionId,
        turnId: TurnId = _uiState.value.currentTurnId,
        generationWireId: String = activeTurn?.turnGen?.wireId
            ?: liveAdapter.state.value.activeGeneration?.wireId
            ?: "NONE"
    ) {
        val prevActive = currentActiveDiagnosticErrorEntry
        if (prevActive != null) {
            while (previousDiagnosticErrorsHistory.size >= maxErrorHistoryEntries) {
                previousDiagnosticErrorsHistory.removeFirst()
            }
            previousDiagnosticErrorsHistory.addLast(prevActive)
        }
        val newEntry = RedactedDiagnosticErrorEntry(
            sequenceNumber = diagnosticErrorSequence.incrementAndGet(),
            timestampMonotonicMs = monotonicClockMs(),
            sessionId = sessionId.value,
            turnId = turnId.value,
            generationWireId = generationWireId,
            stage = stage,
            errorCode = errorCode,
            redactedSummary = DiagnosticRedactor.redactForDiagnostics(rawSummary)
        )
        currentActiveDiagnosticErrorEntry = newEntry
        val historyList = previousDiagnosticErrorsHistory.toList()
        _uiState.update { state ->
            state.copy(
                currentActiveDiagnosticError = newEntry,
                previousDiagnosticErrors = historyList
            )
        }
    }

    private fun archiveActiveDiagnosticErrorLocked() {
        val prevActive = currentActiveDiagnosticErrorEntry ?: return
        while (previousDiagnosticErrorsHistory.size >= maxErrorHistoryEntries) {
            previousDiagnosticErrorsHistory.removeFirst()
        }
        previousDiagnosticErrorsHistory.addLast(prevActive)
        currentActiveDiagnosticErrorEntry = null
        val historyList = previousDiagnosticErrorsHistory.toList()
        _uiState.update { state ->
            state.copy(
                currentActiveDiagnosticError = null,
                previousDiagnosticErrors = historyList
            )
        }
    }

    private fun actionVerbForFailureCode(
        failureCode: String,
        isTimeoutEnd: Boolean = false
    ): String = when {
        failureCode == "FIRST_RESPONSE_TIMEOUT" ->
            if (isTimeoutEnd) "first_response_timeout_end" else "first_response_timeout"
        failureCode == "SERVER_OUTPUT_STALL_TIMEOUT" ->
            if (isTimeoutEnd) "server_output_stall_end" else "server_output_stall"
        failureCode == "MISSING_SERVER_TURN_COMPLETE" ->
            if (isTimeoutEnd) "missing_turn_complete_end" else "missing_turn_complete"
        failureCode == "HARDWARE_PLAYBACK_STALL" ||
            failureCode == "HARDWARE_DRAIN_INCOMPLETE" ||
            failureCode == "AUDIO_TRACK_WRITE_FAILED" ||
            failureCode == "AUDIO_PLAYBACK_WRITE_FAILED" ||
            failureCode == "AUDIO_PLAYBACK_DRAIN_FAILED" ->
            "playback_stall"
        failureCode == "NETWORK_UNAVAILABLE" ||
            failureCode.startsWith("LIVE_") ||
            failureCode == "TRANSIENT_NETWORK_OR_SOCKET" ||
            failureCode == "SESSION_EXPIRED_OR_GO_AWAY" ||
            failureCode == "NETWORK_ERROR" ->
            "transport_disconnected"
        else -> "turn_error"
    }

    private fun latchPlaybackSnapshotIntoTurnLocked(
        turn: TurnRuntimeState,
        playSnap: LiveAudioPlaybackState = playbackAdapter.state.value
    ) {
        val matchesTurnPlayback = turn.playbackGen != null && (
            playSnap.activeGeneration == turn.playbackGen ||
                (playSnap.activeGeneration == null && playSnap.lastCompletedGeneration == turn.playbackGen)
            )
        if (matchesTurnPlayback) {
            turn.latchedPlaybackFramesReceived = playSnap.framesReceivedFromServer
            turn.latchedPlaybackBytesReceived = playSnap.audioBytesReceived
            turn.latchedPlaybackChunksReceived = playSnap.chunksReceived
            turn.latchedPlaybackFramesQueued = playSnap.queuedFrames
            turn.latchedPlaybackBytesQueued = playSnap.queuedBytes
            turn.latchedPlaybackFramesAccepted = playSnap.totalFramesWrittenToHardware
            turn.latchedPlaybackBytesAccepted = playSnap.bytesAcceptedByHardware
            turn.latchedPlaybackFramesPlayed = playSnap.hardwarePlaybackHeadFrames
            turn.latchedPlaybackPaddingFrames = playSnap.paddingFramesWrittenToHardware
            turn.latchedPlaybackPaddingBytes = playSnap.paddingBytesWrittenToHardware
            turn.latchedPlaybackSampleRateHz = playSnap.verifiedSampleRateHz
        }
    }

    private fun buildTurnTimingSnapshotLocked(turn: TurnRuntimeState): Phase07TurnTimingSnapshot {
        latchPlaybackSnapshotIntoTurnLocked(turn, playbackAdapter.state.value)
        return turn.toTimingSnapshot(
            isSyntheticOrTestMeasurement = isSyntheticOrTestMeasurementMode
        )
    }

    private fun recordTurnOutcomeLocked(
        turn: TurnRuntimeState,
        category: Phase07TurnOutcomeCategory,
        outcomeCode: String
    ) {
        if (turn.outcomeRecordedInSummary) {
            refreshTurnOutcomeSnapshotLocked(turn)
            return
        }
        turn.outcomeRecordedInSummary = true
        totalAttemptedTurnsCounter += 1
        when (category) {
            Phase07TurnOutcomeCategory.COMPLETED -> totalCompletedTurnsCounter += 1
            Phase07TurnOutcomeCategory.FAILED -> totalFailedTurnsCounter += 1
            Phase07TurnOutcomeCategory.INTERRUPTED -> totalInterruptedTurnsCounter += 1
            Phase07TurnOutcomeCategory.TIMED_OUT -> totalTimedOutTurnsCounter += 1
            Phase07TurnOutcomeCategory.NO_SPEECH_REJECTED -> totalNoSpeechRejectedTurnsCounter += 1
        }
        val timingSnap = buildTurnTimingSnapshotLocked(turn)
        while (recordedTurnOutcomes.size >= Phase07PerformanceSummary.MAX_BOUNDED_TURN_RECORDS) {
            recordedTurnOutcomes.removeFirst()
        }
        recordedTurnOutcomes.addLast(
            Phase07TurnOutcomeRecord(
                sessionId = turn.sessionId.value,
                turnId = turn.turnId.value,
                turnGenerationWireId = turn.turnGen.wireId,
                category = category,
                outcomeCode = outcomeCode,
                timingSnapshot = timingSnap,
                isSyntheticOrTestMeasurement = isSyntheticOrTestMeasurementMode
            )
        )
    }

    private fun refreshTurnOutcomeSnapshotLocked(turn: TurnRuntimeState) {
        val updatedSnap = buildTurnTimingSnapshotLocked(turn)
        val idx = recordedTurnOutcomes.indexOfLast { it.turnGenerationWireId == turn.turnGen.wireId }
        if (idx >= 0) {
            val existing = recordedTurnOutcomes[idx]
            recordedTurnOutcomes[idx] = existing.copy(timingSnapshot = updatedSnap)
        }
    }

    private fun buildPerformanceSummaryLocked(): Phase07PerformanceSummary {
        val liveSnap = liveAdapter.state.value
        return Phase07PerformanceSummary.fromTurnRecords(
            startupSnapshot = latestStartupTiming,
            records = recordedTurnOutcomes.toList(),
            totalAttemptedTurns = totalAttemptedTurnsCounter,
            completedTurns = totalCompletedTurnsCounter,
            failedTurns = totalFailedTurnsCounter,
            interruptedTurns = totalInterruptedTurnsCounter,
            timedOutTurns = totalTimedOutTurnsCounter,
            noSpeechRejectedTurns = totalNoSpeechRejectedTurnsCounter,
            runtimeModelId = liveAdapter.configuredModelId,
            configuredVoice = LiveConfigConstants.DEFAULT_NATIVE_AUDIO_VOICE,
            contextWindowCompressionActive = liveSnap.contextWindowCompressionEnabled,
            sessionResumptionStatus = liveSnap.sessionResumptionStatus.wireCode,
            contextRestorationStatus = liveSnap.contextRestorationStatus.wireCode
        )
    }

    private fun syncTimingSnapshotsLocked() {
        val activeSnap = activeTurn?.let { buildTurnTimingSnapshotLocked(it) }
        val completedSnap = lastCompletedTurnRuntime?.let { buildTurnTimingSnapshotLocked(it) }
            ?: _uiState.value.lastCompletedTurnTimingSnapshot
        val startupSnap = latestStartupTiming
        val perfSummary = buildPerformanceSummaryLocked()
        _uiState.update { state ->
            state.copy(
                startupTimingSnapshot = startupSnap,
                activeTurnTimingSnapshot = activeSnap,
                lastCompletedTurnTimingSnapshot = completedSnap,
                performanceSummary = perfSummary
            )
        }
    }

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
            activeConversationPhase = ActiveConversationPhase.IDLE,
            isActiveConversationEnabled = preferencesStore.isActiveConversationEnabled,
            hasCompletedFirstUseConsentAndConfig = preferencesStore.hasCompletedFirstUseConsentAndConfig,
            autoResumeOnScreenReopen = preferencesStore.autoResumeOnScreenReopen,
            inactivityTimeoutOption = preferencesStore.inactivityTimeoutOption,
            inactivityTimeoutMs = customInactivityTimeoutMs ?: preferencesStore.inactivityTimeoutOption.durationMs,
            maxSessionDialogueTurns = maxSessionDialogueTurns,
            maxTranscriptEntries = maxTranscriptEntries,
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
                    message = "OmniVoice Rebuild Phase 05 ready. Single ConversationCoordinator connected to AudioCapture (16kHz PCM), GeminiLiveConnectionAdapter (${liveAdapter.configuredModelId}), and LiveAudioPlaybackAdapter (streaming PCM AudioTrack). Half-duplex policy explicit.",
                    timestampLabel = "INIT"
                )
            )
        )
    )
    val uiState: StateFlow<ConversationUiState> = _uiState.asStateFlow()

    init {
        recordActionId(initialActionId)
        playbackAdapter.hardwarePlaybackStallTimeoutMs = hardwarePlaybackStallTimeoutMs
        liveAdapter.canAutoReconnectOnNetworkRestore = {
            !isDeliberatelyPaused &&
                !isDeliberatelyStopped &&
                activeTimeoutRecoveryJob?.isActive != true &&
                activeNextTurnRecoveryJob?.isActive != true
        }
        liveAdapter.onStateChanged = { onLiveAdapterStateUpdated() }
        liveAdapter.onServerEventListener = { event -> onServerSocketEvent(event) }
        playbackAdapter.onStateChanged = { syncPlaybackState() }
        syncLiveState()
        syncSpeechGateTelemetryLocked()
    }

    fun nextActionId(verb: String): ActionId = idGenerator.nextActionId(verb)

    @Synchronized
    fun hasActiveTurnJobs(): Boolean {
        val turn = activeTurn
        val recoveryActive =
            activeTimeoutRecoveryJob?.isActive == true ||
                activeNextTurnRecoveryJob?.isActive == true ||
                postPlaybackGuardJob?.isActive == true
        if (turn == null) return recoveryActive
        return recoveryActive || turn.activeJobCount() > 0 || turn.parentJob.isActive
    }

    @Synchronized
    fun resourceSnapshot(): CoordinatorResourceSnapshot {
        val turn = activeTurn
        val liveSnap = liveAdapter.state.value
        val playSnap = playbackAdapter.state.value
        val audioSnap = audioCapture.state.value
        val ui = _uiState.value
        val recoveryJobsCount =
            (if (activeTimeoutRecoveryJob?.isActive == true) 1 else 0) +
                (if (activeNextTurnRecoveryJob?.isActive == true) 1 else 0) +
                (if (postPlaybackGuardJob?.isActive == true) 1 else 0)
        return CoordinatorResourceSnapshot(
            activeTurnJobs = (turn?.activeJobCount() ?: 0) + recoveryJobsCount,
            hasActiveInactivityTimer = inactivityTimerJob?.isActive == true,
            hasActiveFirstResponseWatchdog = turn?.firstResponseWatchdogJob?.isActive == true,
            isMicrophoneCapturing = audioSnap.isCapturing,
            isPlaybackTrackOpen = playSnap.isPlaying || playSnap.isDrainingHardware,
            activeLiveSessionCount = liveAdapter.getActiveGenerationCountForDiagnostics(),
            isReceiverCollectorAttached = liveSnap.isReceiverCollectorAttached,
            staleTurnCallbacksDropped = ui.staleTurnCallbacksDropped,
            firstResponseTimeoutCount = ui.firstResponseTimeoutCount,
            firstResponseRecoveryCount = ui.firstResponseRecoveryCount
        )
    }

    private fun syncSpeechGateTelemetryLocked() {
        val gateSnap = speechGate.telemetrySnapshot()
        _uiState.update { state ->
            state.copy(
                speechGateState = gateSnap.gateState,
                vadDetectorName = gateSnap.detectorName,
                vadDetectorVersion = gateSnap.detectorVersion,
                vadModelLoaded = gateSnap.isModelLoaded,
                vadModelLoadError = gateSnap.modelLoadError,
                vadSpeechDecision = gateSnap.lastSpeechDecision,
                vadSpeechProbability = gateSnap.lastSpeechProbability,
                vadCandidateConfirmationCount = gateSnap.candidateConfirmationCount,
                noiseFloorRms = gateSnap.noiseFloorRms,
                activeSpeechStartThresholdRms = gateSnap.activeSpeechStartThresholdRms,
                consecutiveSpeechDurationMs = gateSnap.consecutiveSpeechDurationMs,
                preRollDurationMs = gateSnap.preRollDurationMs,
                preRollBytes = gateSnap.preRollBytes,
                falseStartsRejectedCount = gateSnap.falseStartsRejected,
                activityStartCount = gateSnap.activityStartCount,
                activityEndCount = gateSnap.activityEndCount,
                isPostPlaybackGuardActive = gateSnap.isPostPlaybackGuardActive
            )
        }
    }

    private fun cancelPostPlaybackGuardJobLocked() {
        postPlaybackGuardJob?.cancel()
        postPlaybackGuardJob = null
    }

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

    fun syncLiveState() {
        val liveSnap = liveAdapter.state.value
        _uiState.update { current ->
            current.copy(
                liveConnectionState = liveSnap,
                authoritativeSystemInstruction = liveSnap.systemInstruction
            )
        }
    }

    fun syncPlaybackState() {
        val playSnap = playbackAdapter.state.value
        val expectedPlaybackGen = activeTurn?.playbackGen
        if (expectedPlaybackGen != null &&
            playSnap.activeGeneration != null &&
            playSnap.activeGeneration != expectedPlaybackGen
        ) {
            return
        }
        _uiState.update { current ->
            current.copy(playbackState = playSnap)
        }
    }

    private fun onLiveAdapterStateUpdated() {
        val liveSnap = liveAdapter.state.value
        synchronized(this) {
            _uiState.update { current ->
                current.copy(
                    liveConnectionState = liveSnap,
                    authoritativeSystemInstruction = liveSnap.systemInstruction
                )
            }
            val turn = activeTurn ?: return
            if (turn.closed) return
            val currentState = _uiState.value.state
            val isVoiceTurnInFlight =
                currentState == ConversationState.PREPARING ||
                    currentState == ConversationState.CONNECTING ||
                    currentState == ConversationState.LISTENING ||
                    currentState == ConversationState.PROCESSING ||
                    currentState == ConversationState.THINKING ||
                    currentState == ConversationState.SPEAKING

            if (isVoiceTurnInFlight && liveSnap.phase == LiveConnectionPhase.NETWORK_UNAVAILABLE) {
                failActiveVoiceTurnLocked(
                    failureCode = "NETWORK_UNAVAILABLE",
                    detail = "Validated internet lost during active voice turn (${currentState.wireLabel}). Microphone capture and audio playback cancelled.",
                    expectedSessionId = turn.sessionId,
                    expectedTurnSeq = turn.turnSeq,
                    expectedTurnGen = turn.turnGen
                )
            } else {
                val isMidTurnStreamingOrAudio =
                    currentState == ConversationState.LISTENING ||
                        currentState == ConversationState.PROCESSING ||
                        currentState == ConversationState.THINKING ||
                        currentState == ConversationState.SPEAKING
                val isCompletingInterruptionRecovery =
                    inFlightCompletionTurnGenIds.contains(turn.turnGen.wireId)
                if (isMidTurnStreamingOrAudio &&
                    !isCompletingInterruptionRecovery &&
                    (liveSnap.phase == LiveConnectionPhase.FAILED || liveSnap.phase == LiveConnectionPhase.DISCONNECTED)
                ) {
                    val isSilentWaitingInListening =
                        currentState == ConversationState.LISTENING &&
                            !turn.activityStartSent &&
                            !turn.endOfTurnSubmitted
                    val isGoAwayOrProactiveExpiry =
                        liveSnap.failureClassification == LiveFailureClassification.SESSION_EXPIRED_OR_GO_AWAY ||
                            needsSessionRecreationBeforeNextTurn
                    if (isSilentWaitingInListening &&
                        isGoAwayOrProactiveExpiry &&
                        _uiState.value.isActiveConversationEnabled &&
                        !isDeliberatelyPaused &&
                        !isDeliberatelyStopped
                    ) {
                        cancelInactivityTimerLocked()
                        cancelPostPlaybackGuardJobLocked()
                        speechGate.cancelAndResetToIdle(resetSessionCounters = false)
                        syncSpeechGateTelemetryLocked()
                        activeTurn = null
                        turn.dispose(cancelAllJobs = true)
                        audioCapture.stopAndRelease(
                            reason = "Session rotating after ServerGoAway / session expiry while waiting silently",
                            wipeEphemeral = true
                        )
                        playbackAdapter.stopAndFlush(reason = "Session rotating after ServerGoAway")
                        liveAdapter.setAudioPlaybackClaimed(false)
                        needsSessionRecreationBeforeNextTurn = false
                        val job = prepareNextTurnSessionRecoveryLocked(
                            sessionId = turn.sessionId,
                            previousTurnId = turn.turnId,
                            completedTurn = turn
                        )
                        job?.start()
                        return
                    }
                    val failCode = liveSnap.failureClassification
                        .takeIf { it != LiveFailureClassification.NONE }
                        ?.wireCode
                        ?: "LIVE_${liveSnap.phase.wireCode}"
                    failActiveVoiceTurnLocked(
                        failureCode = failCode,
                        detail = "Gemini Live session closed during active voice turn [${currentState.wireLabel}] ($failCode): ${DiagnosticRedactor.redact(liveSnap.statusDetail)}",
                        expectedSessionId = turn.sessionId,
                        expectedTurnSeq = turn.turnSeq,
                        expectedTurnGen = turn.turnGen
                    )
                }
            }
        }
    }

    @Synchronized
    fun onMicPermissionUpdated(permissionState: MicPermissionState) {
        audioCapture.updatePermissionState(permissionState)
        syncAudioState()
    }

    @Synchronized
    fun onAudioHardwareCallback(event: CaptureCallbackEvent): Boolean {
        val accepted = audioCapture.onHardwareCallback(event)
        syncAudioState()
        return accepted
    }

    fun onLiveSocketCallback(event: LiveSocketCallbackEvent): Boolean {
        val accepted = liveAdapter.onSocketCallback(event)
        if (accepted && event is LiveSocketCallbackEvent.SetupAccepted) {
            synchronized(this) {
                val setupAtMs = monotonicClockMs()
                val liveGenWire = event.generationId.wireId
                activeTurn?.let { turn ->
                    if (!turn.closed) {
                        if (turn.tSetupAcceptedMs == null) {
                            turn.tSetupAcceptedMs = setupAtMs
                        }
                        turn.liveGenWireId = liveGenWire
                    }
                }
                if (latestStartupTiming.tSetupAcceptedMs == null) {
                    latestStartupTiming = latestStartupTiming.copy(
                        liveSessionGenerationWireId = liveGenWire,
                        tSetupAcceptedMs = setupAtMs
                    )
                }
                syncTimingSnapshotsLocked()
            }
        }
        syncLiveState()
        return accepted
    }

    @Synchronized
    fun startLiveNetworkMonitoring() {
        liveAdapter.startNetworkMonitoring()
        syncLiveState()
    }

    @Synchronized
    fun stopLiveNetworkMonitoring() {
        liveAdapter.stopNetworkMonitoring()
        syncLiveState()
    }

    @Synchronized
    fun onLiveDiagnosticsDisposed(reason: String = "Live Diagnostics screen disposed") {
        liveAdapter.cancelScheduledRetries(reason)
        val livePhase = liveAdapter.state.value.phase
        if (livePhase != LiveConnectionPhase.SETUP_ACCEPTED && livePhase != LiveConnectionPhase.ACTIVE) {
            liveAdapter.stopNetworkMonitoring()
        }
        syncLiveState()
    }

    private fun checkVoiceTurnPrerequisitesLocked(): PrerequisiteCheck {
        if (!isVoiceTurnConfigured) {
            return PrerequisiteCheck.NotConfigured(
                "Voice turn adapters are explicitly marked unconfigured in this coordinator instance."
            )
        }
        if (speechDetector.requiresLoadedModel && !speechDetector.isModelLoaded) {
            return PrerequisiteCheck.MissingPrerequisite(
                code = "VAD_MODEL_LOAD_FAILED",
                detail = "Silero VAD neural detector failed to load (${speechDetector.loadError ?: "unknown model load failure"}). Refusing RMS-only fallback."
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

    @Synchronized
    fun setActiveConversationEnabled(enabled: Boolean) {
        preferencesStore.isActiveConversationEnabled = enabled
        if (!enabled) {
            activeNextTurnRecoveryJob?.cancel()
            activeNextTurnRecoveryJob = null
        }
        _uiState.update { state ->
            state.copy(isActiveConversationEnabled = enabled)
        }
    }

    @Synchronized
    fun setAutoResumeOnScreenReopen(enabled: Boolean) {
        preferencesStore.autoResumeOnScreenReopen = enabled
        _uiState.update { state ->
            state.copy(autoResumeOnScreenReopen = enabled)
        }
    }

    @Synchronized
    fun setInactivityTimeoutOption(option: InactivityTimeoutOption) {
        preferencesStore.inactivityTimeoutOption = option
        val effectiveMs = customInactivityTimeoutMs ?: option.durationMs
        _uiState.update { state ->
            state.copy(
                inactivityTimeoutOption = option,
                inactivityTimeoutMs = effectiveMs
            )
        }
        val turn = activeTurn
        if (_uiState.value.state == ConversationState.LISTENING && turn != null && !turn.closed) {
            armInactivityTimerLocked(turn.sessionId, turn.turnGen)
        }
    }

    @Synchronized
    fun setCustomInactivityTimeoutMs(timeoutMs: Long) {
        require(timeoutMs > 0L) { "inactivityTimeoutMs must be positive" }
        _uiState.update { state ->
            state.copy(inactivityTimeoutMs = timeoutMs)
        }
        val turn = activeTurn
        if (_uiState.value.state == ConversationState.LISTENING && turn != null && !turn.closed) {
            armInactivityTimerLocked(turn.sessionId, turn.turnGen)
        }
    }

    @Synchronized
    fun onConfigurationChangePreserved() {
        _uiState.update { state ->
            state.copy(
                configurationChangePreservedCount = state.configurationChangePreservedCount + 1
            )
        }
    }

    suspend fun startActiveConversation(
        actionId: ActionId = idGenerator.nextActionId("START_ACTIVE")
    ): VerifiedOutcome {
        return startVoiceTurn(
            actionId = actionId,
            enableActiveConversationOverride = true
        )
    }

    suspend fun resumeActiveConversation(
        actionId: ActionId = idGenerator.nextActionId("RESUME")
    ): VerifiedOutcome {
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
            if (current.state == ConversationState.STOPPED || isDeliberatelyStopped) {
                recordActionId(actionId)
                val nextTurn = idGenerator.nextTurnId()
                val preempted = VerifiedOutcome.PreemptedByPrecedence(
                    actionId = actionId,
                    sessionId = current.sessionId,
                    turnId = nextTurn,
                    requestedCommand = CoordinatorCommand.RESUME,
                    enforcingCommand = CoordinatorCommand.STOP,
                    resultingState = ConversationState.STOPPED,
                    detail = "RESUME ignored because session is STOPPED (Stop precedence > Resume). Press Start to begin a new session."
                )
                applyVerifiedOutcome(
                    sessionId = current.sessionId,
                    turnId = nextTurn,
                    actionId = actionId,
                    newState = ConversationState.STOPPED,
                    roleLabel = "PRECEDENCE_GUARD",
                    outcome = preempted
                )
                return preempted
            }
            isDeliberatelyPaused = false
            _uiState.update { state ->
                state.copy(isDeliberatelyPaused = false)
            }
        }
        return startVoiceTurn(actionId = actionId)
    }

    suspend fun onConversationScreenVisible(
        actionId: ActionId = idGenerator.nextActionId("SCREEN_REOPEN")
    ): VerifiedOutcome? {
        synchronized(this) {
            val current = _uiState.value
            if (!current.autoResumeOnScreenReopen || !current.hasCompletedFirstUseConsentAndConfig) {
                return null
            }
            if (isDeliberatelyPaused ||
                isDeliberatelyStopped ||
                current.state == ConversationState.PAUSED ||
                current.state == ConversationState.STOPPED
            ) {
                _uiState.update { state ->
                    state.copy(
                        autoResumeBlockedByPauseOrStopCount = state.autoResumeBlockedByPauseOrStopCount + 1
                    )
                }
                return null
            }
            if (audioCapture.state.value.permissionState != MicPermissionState.GRANTED) {
                return null
            }
            if (isVoiceTurnInProgress()) {
                return null
            }
        }
        return startVoiceTurn(actionId = actionId)
    }

    private fun collectActiveTurnJobsLocked(): List<Job> {
        val jobs = mutableListOf<Job>()
        activeTurn?.collectAllJobs()?.let { jobs.addAll(it) }
        activeTimeoutRecoveryJob?.let { jobs.add(it) }
        activeNextTurnRecoveryJob?.let { jobs.add(it) }
        postPlaybackGuardJob?.let { jobs.add(it) }
        return jobs
    }

    private fun releaseResourcesBeforeReplacementCaptureLocked(reason: String) {
        audioCapture.stopAndRelease(reason = reason, wipeEphemeral = true)
        playbackAdapter.stopAndFlush(reason = reason)
        liveAdapter.setAudioPlaybackClaimed(false)
        val snap = audioCapture.state.value
        val playSnap = playbackAdapter.state.value
        _uiState.update { state ->
            state.copy(
                isMicrophoneActive = snap.isCapturing,
                microphoneStatusLabel = formatMicLabel(snap),
                audioCaptureState = snap,
                playbackState = playSnap,
                resourceReleaseBeforeCaptureCount = state.resourceReleaseBeforeCaptureCount + 1
            )
        }
    }

    private fun cancelInactivityTimerLocked() {
        inactivityTimerJob?.cancel()
        inactivityTimerJob = null
    }

    private fun armInactivityTimerLocked(
        sessionId: SessionId,
        turnGen: TurnGenerationId
    ) {
        cancelInactivityTimerLocked()
        val timeoutMs = _uiState.value.inactivityTimeoutMs
        if (timeoutMs <= 0L) return
        inactivityTimerJob = turnScope.launch {
            delay(timeoutMs)
            onInactivityTimeoutExpired(sessionId = sessionId, turnGen = turnGen, timeoutMs = timeoutMs)
        }
    }

    @Synchronized
    fun onInactivityTimeoutExpired(
        sessionId: SessionId,
        turnGen: TurnGenerationId,
        timeoutMs: Long = _uiState.value.inactivityTimeoutMs
    ): Boolean {
        val current = _uiState.value
        val turn = activeTurn
        if (turn == null ||
            turn.closed ||
            current.sessionId != sessionId ||
            turn.turnGen != turnGen ||
            current.state != ConversationState.LISTENING
        ) {
            return false
        }
        val deferredToComplete = turn.completionDeferred
        cancelInactivityTimerLocked()
        cancelActiveTurnStreamsLocked("Inactivity session closure after ${timeoutMs}ms")
        liveAdapter.disconnectAndRelease(reason = "Inactivity session closure after ${timeoutMs}ms")

        val nextTurn = idGenerator.nextTurnId()
        val actId = idGenerator.nextActionId("inactivity_close")
        val updatedPhaseSeq = appendActiveConversationPhaseLocked(ActiveConversationPhase.IDLE)
        val detailMsg =
            "INACTIVITY_SESSION_CLOSED: Conversation closed after ${timeoutMs}ms of silence without speech. Press Start to resume."
        val outcome = VerifiedOutcome.StateTransitionVerified(
            actionId = actId,
            sessionId = sessionId,
            turnId = nextTurn,
            previousState = ConversationState.LISTENING,
            resultingState = ConversationState.IDLE,
            detail = detailMsg
        )
        _uiState.update { state ->
            state.copy(
                activeConversationPhase = ActiveConversationPhase.IDLE,
                activeConversationPhaseSequence = updatedPhaseSeq,
                activeTurnGenerationId = null,
                inactivityClosedCount = state.inactivityClosedCount + 1,
                lastVoiceTurnSummary = detailMsg
            )
        }
        applyVerifiedOutcome(
            sessionId = sessionId,
            turnId = nextTurn,
            actionId = actId,
            newState = ConversationState.IDLE,
            roleLabel = "INACTIVITY_GUARD",
            outcome = outcome
        )
        deferredToComplete.complete(outcome)
        return true
    }

    suspend fun startVoiceTurn(
        actionId: ActionId = idGenerator.nextActionId("START"),
        enableActiveConversationOverride: Boolean? = null
    ): VerifiedOutcome {
        val turnSeq: Long
        val sessionId: SessionId
        val turnId: TurnId
        val turnGen: TurnGenerationId
        val alreadySetupAccepted: Boolean
        var resetGateCounters = false

        val preCheckJobs = synchronized(this) {
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

            val isTurnCurrentlyActive =
                (activeTurn?.let { !it.closed } == true) ||
                    (activeTimeoutRecoveryJob?.isActive == true) ||
                    (activeNextTurnRecoveryJob?.isActive == true) ||
                    current.state == ConversationState.PREPARING ||
                    current.state == ConversationState.CONNECTING ||
                    current.state == ConversationState.LISTENING ||
                    current.state == ConversationState.THINKING ||
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
            collectActiveTurnJobsLocked()
        }
        if (preCheckJobs.any { it.isActive }) {
            withContext(backgroundDispatcher) {
                for (job in preCheckJobs) {
                    if (job.isActive) {
                        job.cancel()
                        job.join()
                    }
                }
            }
        }

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

            val isTurnCurrentlyActive =
                (activeTurn?.let { !it.closed } == true) ||
                    (activeTimeoutRecoveryJob?.isActive == true) ||
                    (activeNextTurnRecoveryJob?.isActive == true) ||
                    current.state == ConversationState.PREPARING ||
                    current.state == ConversationState.CONNECTING ||
                    current.state == ConversationState.LISTENING ||
                    current.state == ConversationState.THINKING ||
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
            resetGateCounters =
                current.state == ConversationState.IDLE ||
                    current.state == ConversationState.STOPPED ||
                    current.state == ConversationState.PAUSED ||
                    current.state == ConversationState.ERROR

            if (enableActiveConversationOverride != null) {
                preferencesStore.isActiveConversationEnabled = enableActiveConversationOverride
            }
            val effectiveActiveConv =
                enableActiveConversationOverride ?: _uiState.value.isActiveConversationEnabled

            cancelInactivityTimerLocked()
            cancelActiveTurnStreamsLocked("Initializing voice turn (${turnId.value})")
            releaseResourcesBeforeReplacementCaptureLocked("Actual resource release before starting turn ${turnId.value}")

            when (val prereq = checkVoiceTurnPrerequisitesLocked()) {
                is PrerequisiteCheck.NotConfigured -> {
                    recordDiagnosticErrorLocked(
                        stage = "PREREQUISITE_CHECK",
                        errorCode = "NOT_CONFIGURED",
                        rawSummary = prereq.detail,
                        sessionId = sessionId,
                        turnId = turnId
                    )
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
                    recordDiagnosticErrorLocked(
                        stage = "PREREQUISITE_CHECK",
                        errorCode = prereq.code,
                        rawSummary = prereq.detail,
                        sessionId = sessionId,
                        turnId = turnId
                    )
                    val updatedPhaseSeq = appendActiveConversationPhaseLocked(ActiveConversationPhase.ERROR)
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
                            activeConversationPhase = ActiveConversationPhase.ERROR,
                            activeConversationPhaseSequence = updatedPhaseSeq,
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
                    archiveActiveDiagnosticErrorLocked()
                    isDeliberatelyPaused = false
                    isDeliberatelyStopped = false
                    consecutiveFirstResponseTimeouts = 0
                    preferencesStore.hasCompletedFirstUseConsentAndConfig = true

                    liveAdapter.onStateChanged = { onLiveAdapterStateUpdated() }
                    liveAdapter.onServerEventListener = { event -> onServerSocketEvent(event) }

                    val startRequestedAtMs = monotonicClockMs()
                    turnSeq = voiceTurnCounter.incrementAndGet()
                    turnGen = TurnGenerationId(
                        turnSeq = turnSeq,
                        turnIdValue = turnId.value,
                        sessionId = sessionId
                    )
                    val freshTurn = TurnRuntimeState(
                        turnSeq = turnSeq,
                        sessionId = sessionId,
                        turnId = turnId,
                        turnGen = turnGen,
                        createdAtLabel = clockProvider()
                    )
                    freshTurn.tStartRequestedMs = startRequestedAtMs
                    activeTurn = freshTurn
                    latestStartupTiming = Phase07StartupTimingSnapshot(
                        sessionId = sessionId.value,
                        initialTurnId = turnId.value,
                        turnGenerationWireId = turnGen.wireId,
                        tStartRequestedMs = startRequestedAtMs,
                        isSyntheticOrTestMeasurement = isSyntheticOrTestMeasurementMode
                    )
                    syncTimingSnapshotsLocked()
                    liveAdapter.startNetworkMonitoring()

                    alreadySetupAccepted =
                        !needsSessionRecreationBeforeNextTurn &&
                            liveAdapter.isSessionReadyForAudio()
                    if (alreadySetupAccepted) {
                        val existingSetupMs = monotonicClockMs()
                        val existingLiveGen = liveAdapter.state.value.activeGeneration?.wireId ?: "NONE"
                        freshTurn.tSetupAcceptedMs = existingSetupMs
                        freshTurn.liveGenWireId = existingLiveGen
                        latestStartupTiming = latestStartupTiming.copy(
                            liveSessionGenerationWireId = existingLiveGen,
                            tSetupAcceptedMs = existingSetupMs
                        )
                        syncTimingSnapshotsLocked()
                    }

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
                    val updatedPhaseSeq = appendActiveConversationPhaseLocked(ActiveConversationPhase.PREPARING)
                    val connectingOutcome = VerifiedOutcome.StateTransitionVerified(
                        actionId = actionId,
                        sessionId = sessionId,
                        turnId = turnId,
                        previousState = current.state,
                        resultingState = ConversationState.CONNECTING,
                        detail = if (alreadySetupAccepted) {
                            "PREPARING / SETUP_ACCEPTED (${turnGen.wireId}): Gemini Live session already verified and collector attached; proceeding directly to LISTENING..."
                        } else {
                            "PREPARING / CONNECTING (${turnGen.wireId}): Verifying microphone & validated internet and connecting Gemini Live session (${liveAdapter.configuredModelId})..."
                        }
                    )
                    _uiState.update { state ->
                        state.copy(
                            currentTurnId = turnId,
                            activeConversationPhase = ActiveConversationPhase.PREPARING,
                            activeConversationPhaseSequence = updatedPhaseSeq,
                            isActiveConversationEnabled = effectiveActiveConv,
                            isDeliberatelyPaused = false,
                            isDeliberatelyStopped = false,
                            hasCompletedFirstUseConsentAndConfig = true,
                            voiceTurnStateSequence = seqList,
                            voiceTurnReadinessOrder = initialReadiness,
                            activeTurnGenerationId = turnGen,
                            turnEventTrace = emptyList(),
                            firstTurnLifecycleTrace = initialTrace,
                            structuredTurnTrace = emptyList(),
                            activeTurnEarliestMissingStep = freshTurn.earliestMissingStep(),
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

        val finalLiveState: LiveConnectionState? = if (alreadySetupAccepted) {
            liveAdapter.state.value
        } else {
            needsSessionRecreationBeforeNextTurn = false
            withContext(backgroundDispatcher) {
                withTimeoutOrNull(connectionTimeoutMs) {
                    liveAdapter.ensureConnectedSession(sessionId)
                }
            }
        }

        return synchronized(this) {
            val turn = activeTurn
            if (turn == null || turn.closed || turn.turnSeq != turnSeq || turn.turnGen != turnGen) {
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

            if (finalLiveState == null) {
                liveAdapter.cancelScheduledRetries("Connection setup timed out after ${connectionTimeoutMs}ms")
                val timeoutCode = "CONNECTION_SETUP_TIMEOUT"
                val timeoutDetail =
                    "Gemini Live connection setup timed out after ${connectionTimeoutMs}ms before SETUP_ACCEPTED. Verify internet quality and press Start to try again."
                return@synchronized failActiveVoiceTurnLocked(
                    failureCode = timeoutCode,
                    detail = timeoutDetail,
                    expectedSessionId = sessionId,
                    expectedTurnSeq = turnSeq,
                    expectedTurnGen = turnGen
                )!!
            }

            appendTurnEventLocked(if (alreadySetupAccepted) "SESSION_REUSED" else "SESSION_CREATED")

            val collectorReadyNow = liveAdapter.isSessionReadyForAudio()
            val isLivePhaseReady =
                finalLiveState.phase == LiveConnectionPhase.SETUP_ACCEPTED ||
                    finalLiveState.phase == LiveConnectionPhase.ACTIVE
            if (!isLivePhaseReady ||
                !finalLiveState.handshakeVerified ||
                !collectorReadyNow
            ) {
                turn.dispose(cancelAllJobs = true)
                activeTurn = null
                val failCode = if (finalLiveState.phase == LiveConnectionPhase.NETWORK_UNAVAILABLE) {
                    "NETWORK_UNAVAILABLE"
                } else if (finalLiveState.failureClassification != LiveFailureClassification.NONE) {
                    finalLiveState.failureClassification.wireCode
                } else if (!collectorReadyNow && isLivePhaseReady) {
                    "RECEIVER_COLLECTOR_NOT_ATTACHED"
                } else {
                    "LIVE_${finalLiveState.phase.wireCode}"
                }
                val failDetail =
                    "Gemini Live connection failed [$failCode]: ${DiagnosticRedactor.redact(finalLiveState.statusDetail)}"
                recordDiagnosticErrorLocked(
                    stage = "LIVE_CONNECTION_SETUP",
                    errorCode = failCode,
                    rawSummary = failDetail,
                    sessionId = sessionId,
                    turnId = turnId,
                    generationWireId = finalLiveState.activeGeneration?.wireId ?: turnGen.wireId
                )
                val updatedPhaseSeq = appendActiveConversationPhaseLocked(ActiveConversationPhase.ERROR)
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
                        activeConversationPhase = ActiveConversationPhase.ERROR,
                        activeConversationPhaseSequence = updatedPhaseSeq,
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
                turn.completionDeferred.complete(failOutcome)
                return@synchronized failOutcome
            }

            val setupAcceptedAtMs = turn.tSetupAcceptedMs ?: monotonicClockMs()
            val liveGenWire = finalLiveState.activeGeneration?.wireId ?: liveAdapter.state.value.activeGeneration?.wireId
            turn.tSetupAcceptedMs = setupAcceptedAtMs
            turn.liveGenWireId = liveGenWire ?: "NONE"
            latestStartupTiming = latestStartupTiming.copy(
                liveSessionGenerationWireId = liveGenWire ?: "NONE",
                tSetupAcceptedMs = setupAcceptedAtMs
            )

            appendTurnEventLocked("SETUP_ACCEPTED")
            appendFirstTurnLifecycleLocked("SETUP_ACCEPTED")
            appendReadinessOrderLocked("SETUP_ACCEPTED")

            val playbackGen = playbackAdapter.startTurnPlayback(
                turnId = turnId,
                sessionId = sessionId
            )
            turn.playbackGen = playbackGen
            liveAdapter.setAudioPlaybackClaimed(true)

            appendFirstTurnLifecycleLocked("AUDIO_RECORD_START")
            turn.recordStep(
                step = TurnTraceStep.LISTENING_STARTED,
                timestampLabel = clockProvider(),
                liveGenWireId = liveGenWire,
                detail = "turn=${turnId.value}"
            )
            val updatedSequence = appendTurnStateLocked(ConversationState.LISTENING)
            val updatedReadiness = appendReadinessOrderLocked("LISTENING")
            val updatedPhaseSeq = appendActiveConversationPhaseLocked(ActiveConversationPhase.LISTENING)
            _uiState.update { state ->
                state.copy(
                    state = ConversationState.LISTENING,
                    activeConversationPhase = ActiveConversationPhase.LISTENING,
                    activeConversationPhaseSequence = updatedPhaseSeq,
                    voiceTurnStateSequence = updatedSequence,
                    voiceTurnReadinessOrder = updatedReadiness,
                    structuredTurnTrace = turn.structuredTraceEntries.toList(),
                    activeTurnEarliestMissingStep = turn.earliestMissingStep()
                )
            }

            speechGate.beginWaitingForSpeech(resetSessionCounters = resetGateCounters)
            syncSpeechGateTelemetryLocked()

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

            wakeWordState = WakeWordState.COMMAND_LISTENING
            activeWakeAuthorization = WakeAuthorization(
                generationId = captureGen,
                expiresAtMs = monotonicClockMs() + 24L * 3600L * 1000L,
                keyword = "FOREGROUND_ACTIVE"
            )
            _uiState.update { state ->
                state.copy(
                    wakeWordState = WakeWordState.COMMAND_LISTENING,
                    wakeWordStatusLabel = "LISTENING (NATURAL SPEECH ACTIVE)",
                    wakeAuthorizationActive = true
                )
            }

            val audioSnap = audioCapture.state.value
            if (!audioSnap.isCapturing) {
                speechGate.cancelAndResetToIdle(resetSessionCounters = false)
                syncSpeechGateTelemetryLocked()
                turn.playbackGen = null
                turn.dispose(cancelAllJobs = true)
                activeTurn = null
                playbackAdapter.stopAndFlush("Microphone capture failed to start")
                liveAdapter.setAudioPlaybackClaimed(false)
                val failCode = audioSnap.anomaly.wireCode
                val failDetail = "Microphone capture failed [$failCode]: ${audioSnap.statusDetail}"
                recordDiagnosticErrorLocked(
                    stage = "AUDIO_CAPTURE_START",
                    errorCode = failCode,
                    rawSummary = failDetail,
                    sessionId = sessionId,
                    turnId = turnId,
                    generationWireId = captureGen.wireId
                )
                val errPhaseSeq = appendActiveConversationPhaseLocked(ActiveConversationPhase.ERROR)
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
                        activeConversationPhase = ActiveConversationPhase.ERROR,
                        activeConversationPhaseSequence = errPhaseSeq,
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
                turn.completionDeferred.complete(failOutcome)
                return@synchronized failOutcome
            }

            val micReadyAtMs = monotonicClockMs()
            turn.captureGenWireId = captureGen.wireId
            turn.tMicReadyMs = micReadyAtMs
            latestStartupTiming = latestStartupTiming.copy(
                captureGenerationWireId = captureGen.wireId,
                tMicReadyMs = micReadyAtMs
            )
            syncTimingSnapshotsLocked()

            armInactivityTimerLocked(sessionId, turn.turnGen)

            val listeningDetail =
                "LISTENING (${captureGen.wireId} -> ${liveGenWire ?: "live"}): Waiting silently for speech. Speak clearly when ready or tap 'Send Spoken Question'."
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

    private fun autoRestoreCaptureForNextTurnLocked(
        previousTurnId: TurnId,
        completedTurn: TurnRuntimeState? = null,
        allowRestoreAfterTimeoutRecovery: Boolean = false,
        recoveryReasonCode: String? = null
    ): VerifiedOutcome {
        val current = _uiState.value
        val sessionId = current.sessionId
        if (isDeliberatelyPaused || isDeliberatelyStopped || !current.isActiveConversationEnabled) {
            return failActiveVoiceTurnLocked(
                failureCode = "RESTORE_BLOCKED_BY_OPERATOR",
                detail = "Refused to auto-restore LISTENING while paused/stopped or active conversation disabled."
            )!!
        }
        if (!liveAdapter.isSessionReadyForAudio()) {
            return failActiveVoiceTurnLocked(
                failureCode = "SESSION_NOT_READY_FOR_CAPTURE",
                detail = "Refused to auto-restore LISTENING before a usable Gemini Live session was verified."
            )!!
        }
        val playSnapBeforeRestore = playbackAdapter.state.value
        if (playSnapBeforeRestore.isPlaying ||
            playSnapBeforeRestore.isDrainingHardware ||
            (!allowRestoreAfterTimeoutRecovery && !playSnapBeforeRestore.isPlaybackDrained)
        ) {
            return failActiveVoiceTurnLocked(
                failureCode = "AUDIO_PLAYBACK_DRAIN_FAILED",
                detail = "Refused to auto-restore LISTENING before playback drain completed (isPlaying=${playSnapBeforeRestore.isPlaying}, isDraining=${playSnapBeforeRestore.isDrainingHardware}, isDrained=${playSnapBeforeRestore.isPlaybackDrained})."
            )!!
        }

        val nextTurnId = idGenerator.nextTurnId()
        val liveGenWire = liveAdapter.state.value.activeGeneration?.wireId
        if (completedTurn != null) {
            completedTurn.recordStep(
                step = TurnTraceStep.NEXT_LISTENING_STARTED,
                timestampLabel = clockProvider(),
                liveGenWireId = liveGenWire,
                detail = "nextTurn=${nextTurnId.value}"
            )
            logTurnLifecycleEventLocked(
                "TurnComplete[${completedTurn.turnId.value}]: ${completedTurn.structuredTraceEntries.joinToString(" -> ")}"
            )
        }

        releaseResourcesBeforeReplacementCaptureLocked(
            "Actual resource release before auto-restoring capture after ${previousTurnId.value} -> ${nextTurnId.value}"
        )

        appendActiveConversationPhaseLocked(ActiveConversationPhase.PREPARING)
        val turnSeq = voiceTurnCounter.incrementAndGet()
        val turnGen = TurnGenerationId(
            turnSeq = turnSeq,
            turnIdValue = nextTurnId.value,
            sessionId = sessionId
        )
        val nextTurn = TurnRuntimeState(
            turnSeq = turnSeq,
            sessionId = sessionId,
            turnId = nextTurnId,
            turnGen = turnGen,
            createdAtLabel = clockProvider()
        )
        activeTurn = nextTurn

        val playbackGen = playbackAdapter.startTurnPlayback(
            turnId = nextTurnId,
            sessionId = sessionId
        )
        nextTurn.playbackGen = playbackGen
        liveAdapter.setAudioPlaybackClaimed(true)

        nextTurn.recordStep(
            step = TurnTraceStep.LISTENING_STARTED,
            timestampLabel = clockProvider(),
            liveGenWireId = liveGenWire,
            detail = "turn=${nextTurnId.value} (autoRestore)"
        )

        _uiState.update { state ->
            state.copy(
                currentTurnId = nextTurnId,
                activeTurnGenerationId = turnGen,
                turnEventTrace = listOf("AUTO_RESTORE_PREPARING", "SETUP_ACCEPTED"),
                lastCompletedTurnTrace = completedTurn?.structuredTraceEntries?.toList() ?: state.lastCompletedTurnTrace,
                lastCompletedTurnEarliestMissingStep = completedTurn?.earliestMissingStep() ?: state.lastCompletedTurnEarliestMissingStep,
                structuredTurnTrace = nextTurn.structuredTraceEntries.toList(),
                activeTurnEarliestMissingStep = nextTurn.earliestMissingStep(),
                activeVoiceTurnErrorCode = null,
                activeVoiceTurnErrorDetail = null,
                voiceTurnFramesSent = 0L,
                voiceTurnChunksSent = 0L,
                voiceTurnBytesSent = 0L,
                voiceTurnPeakRmsLinear = 0f,
                currentTurnUserTranscript = "",
                currentTurnModelTranscript = ""
            )
        }

        if (allowRestoreAfterTimeoutRecovery) {
            speechGate.beginWaitingForSpeech(resetSessionCounters = false)
        } else {
            speechGate.beginPostPlaybackEchoGuard()
        }
        syncSpeechGateTelemetryLocked()

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
            return failActiveVoiceTurnLocked(
                failureCode = audioSnap.anomaly.wireCode,
                detail = "Auto-restore capture failed [${audioSnap.anomaly.wireCode}]: ${audioSnap.statusDetail}",
                expectedSessionId = sessionId,
                expectedTurnSeq = turnSeq,
                expectedTurnGen = turnGen,
                expectedPlaybackGen = playbackGen
            )!!
        }

        val nextMicReadyAtMs = monotonicClockMs()
        nextTurn.captureGenWireId = captureGen.wireId
        nextTurn.liveGenWireId = liveGenWire ?: "NONE"
        nextTurn.tSetupAcceptedMs = completedTurn?.tSetupAcceptedMs ?: latestStartupTiming.tSetupAcceptedMs
        nextTurn.tMicReadyMs = nextMicReadyAtMs
        if (completedTurn != null && (completedTurn.completionHandled || completedTurn.tEndSubmittedMs != null)) {
            if (completedTurn.tNextMicReadyMs == null) {
                completedTurn.tNextMicReadyMs = nextMicReadyAtMs
            }
            lastCompletedTurnRuntime = completedTurn
            refreshTurnOutcomeSnapshotLocked(completedTurn)
        }
        syncTimingSnapshotsLocked()

        val updatedTurnSeq = appendTurnStateLocked(ConversationState.LISTENING)
        val updatedReadiness = appendReadinessOrderLocked("LISTENING")
        val listeningPhaseSeq = appendActiveConversationPhaseLocked(ActiveConversationPhase.LISTENING)

        if (!allowRestoreAfterTimeoutRecovery && postPlaybackEchoGuardMs > 0L) {
            cancelPostPlaybackGuardJobLocked()
            postPlaybackGuardJob = turnScope.launch(nextTurn.parentJob) {
                postPlaybackGuardDelayRunner(postPlaybackEchoGuardMs)
                synchronized(this@ConversationCoordinator) {
                    if (activeTurn === nextTurn &&
                        !nextTurn.closed &&
                        nextTurn.turnSeq == turnSeq &&
                        !isDeliberatelyPaused &&
                        !isDeliberatelyStopped &&
                        _uiState.value.state == ConversationState.LISTENING
                    ) {
                        if (speechGate.refreshGuardIfExpired()) {
                            syncSpeechGateTelemetryLocked()
                        }
                    }
                }
            }
        }

        armInactivityTimerLocked(sessionId, turnGen)

        val detail = if (allowRestoreAfterTimeoutRecovery) {
            val codeLabel = recoveryReasonCode ?: completedTurn?.watchdogFailureCode ?: "TURN_RECOVERY"
            if (codeLabel == "MISSING_SERVER_TURN_COMPLETE") {
                val playedFrames = completedTurn?.latchedPlaybackFramesPlayed ?: 0L
                "LISTENING (${captureGen.wireId} • Turn #${_uiState.value.completedTurnsInSession + 1}): Previous reply finished playing ($playedFrames frames) without server turnComplete ($codeLabel); recovered session cleanly and resumed listening."
            } else {
                "LISTENING (${captureGen.wireId} • Turn #${_uiState.value.completedTurnsInSession + 1}): Active conversation automatically restored after clean recovery from $codeLabel."
            }
        } else {
            "LISTENING (${captureGen.wireId} • Turn #${_uiState.value.completedTurnsInSession + 1}): Active conversation restored (post-playback echo guard -> silent waiting)."
        }
        val outcome = VerifiedOutcome.StateTransitionVerified(
            actionId = idGenerator.nextActionId("auto_restore_listening"),
            sessionId = sessionId,
            turnId = nextTurnId,
            previousState = if (allowRestoreAfterTimeoutRecovery || current.state == ConversationState.PREPARING) {
                ConversationState.PREPARING
            } else {
                ConversationState.READY
            },
            resultingState = ConversationState.LISTENING,
            detail = detail
        )
        _uiState.update { state ->
            state.copy(
                state = ConversationState.LISTENING,
                activeConversationPhase = ActiveConversationPhase.LISTENING,
                activeConversationPhaseSequence = listeningPhaseSeq,
                voiceTurnStateSequence = updatedTurnSeq,
                voiceTurnReadinessOrder = updatedReadiness,
                lastVoiceTurnSummary = detail
            )
        }
        applyVerifiedOutcome(
            sessionId = sessionId,
            turnId = nextTurnId,
            actionId = outcome.actionId,
            newState = ConversationState.LISTENING,
            roleLabel = "ACTIVE_CONVERSATION",
            outcome = outcome
        )
        return outcome
    }

    private fun onVoiceTurnMicSamplesCaptured(
        turnSeq: Long,
        captureGenId: CaptureGenerationId,
        samples16kHz: ShortArray,
        rmsLinear: Float
    ) {
        if (samples16kHz.isEmpty()) return
        val samplesCopy = samples16kHz.copyOf()
        val pcmBytes = shortArrayToLittleEndianBytes(samplesCopy)
        val capturedAtMs = monotonicClockMs()

        synchronized(this) {
            val turn = activeTurn ?: return
            if (turn.closed ||
                turn.turnSeq != turnSeq ||
                turn.endOfTurnRequested ||
                turn.endOfTurnSubmitted ||
                isDeliberatelyPaused ||
                isDeliberatelyStopped ||
                _uiState.value.state != ConversationState.LISTENING
            ) {
                return
            }
            val currentCaptureGen = audioCapture.state.value.activeGeneration
            if (currentCaptureGen == null || currentCaptureGen != captureGenId || !audioCapture.state.value.isCapturing) {
                return
            }
            val turnGen = turn.turnGen
            val previousSendJob = turn.micSendJobs.lastOrNull()

            // Natural speech conversation active without requiring wake word.

            // Enqueue neural VAD inference + ordered Live send off the Main and AudioRecord capture loops,
            // preserving strict chronological sample order via previousSendJob?.join().
            val sendJob = turnScope.launch(turn.parentJob) {
                previousSendJob?.join()

                val stillCapturingOrFlushing = synchronized(this@ConversationCoordinator) {
                    activeTurn === turn &&
                        !turn.closed &&
                        turn.turnSeq == turnSeq &&
                        !turn.endOfTurnSubmitted &&
                        !isDeliberatelyPaused &&
                        !isDeliberatelyStopped &&
                        _uiState.value.state == ConversationState.LISTENING
                }
                if (!stillCapturingOrFlushing) {
                    return@launch
                }

                val decision = speechGate.onFrameCaptured(
                    samples16kHz = samplesCopy,
                    pcmBytes = pcmBytes,
                    rmsLinear = rmsLinear,
                    capturedAtMs = capturedAtMs
                )

                var shouldTriggerAutoEnd = false
                var trailingSilenceMsForLog = 0L
                val liveGenWire: String?

                synchronized(this@ConversationCoordinator) {
                    if (activeTurn !== turn ||
                        turn.closed ||
                        turn.turnSeq != turnSeq ||
                        turn.endOfTurnSubmitted ||
                        isDeliberatelyPaused ||
                        isDeliberatelyStopped ||
                        _uiState.value.state != ConversationState.LISTENING
                    ) {
                        return@launch
                    }

                    syncSpeechGateTelemetryLocked()

                    if (decision is SpeechGateDecision.DetectorUnavailable) {
                        failActiveVoiceTurnLocked(
                            failureCode = "VAD_MODEL_LOAD_FAILED",
                            detail = "Silero VAD neural detector unavailable (${decision.reason}). Refusing RMS-only fallback.",
                            expectedSessionId = turnGen.sessionId,
                            expectedTurnSeq = turnSeq,
                            expectedTurnGen = turnGen
                        )
                        return@launch
                    }

                    if (decision is SpeechGateDecision.IgnoredDuringGuard) {
                        return@launch
                    }

                    liveGenWire = liveAdapter.state.value.activeGeneration?.wireId
                    turn.capturedFramesCount += samplesCopy.size.toLong()
                    turn.capturedBytesCount += pcmBytes.size.toLong()
                    turn.peakRmsLinear = max(turn.peakRmsLinear, rmsLinear)

                    val isSpeechObservedByVad =
                        decision is SpeechGateDecision.SpeechConfirmedStart ||
                            decision is SpeechGateDecision.StreamActiveFrame ||
                            speechGate.hasObservedWaitingSpeechCandidate()
                    if (isSpeechObservedByVad) {
                        turn.nonSilentSpeechFramesCount += samplesCopy.size.toLong()
                    }

                    if (!turn.hasStep(TurnTraceStep.FIRST_MIC_FRAME)) {
                        turn.recordStep(
                            step = TurnTraceStep.FIRST_MIC_FRAME,
                            timestampLabel = clockProvider(),
                            liveGenWireId = liveGenWire,
                            detail = "samples=${samplesCopy.size}, rms=${"%.3f".format(rmsLinear)}"
                        )
                    }
                    turn.recordStep(
                        step = TurnTraceStep.LAST_MIC_FRAME,
                        timestampLabel = clockProvider(),
                        liveGenWireId = liveGenWire,
                        detail = "capturedFrames=${turn.capturedFramesCount}, rms=${"%.3f".format(rmsLinear)}"
                    )

                    if (decision is SpeechGateDecision.WaitingSilent) {
                        val snap = audioCapture.state.value
                        val newPeakRms = max(_uiState.value.voiceTurnPeakRmsLinear, rmsLinear)
                        _uiState.update { state ->
                            state.copy(
                                isMicrophoneActive = snap.isCapturing,
                                microphoneStatusLabel = formatMicLabel(snap),
                                audioCaptureState = snap,
                                voiceTurnPeakRmsLinear = newPeakRms,
                                structuredTurnTrace = turn.structuredTraceEntries.toList(),
                                activeTurnEarliestMissingStep = turn.earliestMissingStep()
                            )
                        }
                        return@launch
                    }

                    if (decision is SpeechGateDecision.SpeechConfirmedStart) {
                        val gateSnap = speechGate.telemetrySnapshot()
                        turn.tLastUserSpeechMs = gateSnap.lastUserSpeechSampleAtMs
                        if (!turn.hasStep(TurnTraceStep.USER_SPEECH_DETECTED)) {
                            turn.recordStep(
                                step = TurnTraceStep.USER_SPEECH_DETECTED,
                                timestampLabel = clockProvider(),
                                liveGenWireId = liveGenWire,
                                detail = "vad=${gateSnap.detectorName}(p=${"%.2f".format(gateSnap.lastSpeechProbability)}), preRollBytes=${decision.preRollTotalBytes}"
                            )
                        }
                        syncTimingSnapshotsLocked()
                        armInactivityTimerLocked(turn.sessionId, turnGen)
                    } else if (decision is SpeechGateDecision.StreamActiveFrame) {
                        val gateSnap = speechGate.telemetrySnapshot()
                        gateSnap.lastUserSpeechSampleAtMs?.let { turn.tLastUserSpeechMs = it }
                        if (decision.trailingSilenceDurationMs == 0L) {
                            armInactivityTimerLocked(turn.sessionId, turnGen)
                        }
                    }

                    val snap = audioCapture.state.value
                    val newPeakRms = max(_uiState.value.voiceTurnPeakRmsLinear, rmsLinear)
                    _uiState.update { state ->
                        state.copy(
                            isMicrophoneActive = snap.isCapturing,
                            microphoneStatusLabel = formatMicLabel(snap),
                            audioCaptureState = snap,
                            voiceTurnPeakRmsLinear = newPeakRms,
                            structuredTurnTrace = turn.structuredTraceEntries.toList(),
                            activeTurnEarliestMissingStep = turn.earliestMissingStep()
                        )
                    }

                    shouldTriggerAutoEnd =
                        decision is SpeechGateDecision.StreamActiveFrame &&
                            decision.shouldEndSpeechTurn &&
                            !turn.autoEndTriggered
                    if (shouldTriggerAutoEnd) {
                        turn.autoEndTriggered = true
                        val gateSnap = speechGate.telemetrySnapshot()
                        turn.tLastUserSpeechMs = gateSnap.lastUserSpeechSampleAtMs ?: turn.tLastUserSpeechMs
                        turn.tSpeechEndMs = gateSnap.speechEndDetectedAtMs ?: capturedAtMs
                        syncTimingSnapshotsLocked()
                    }
                    trailingSilenceMsForLog =
                        (decision as? SpeechGateDecision.StreamActiveFrame)?.trailingSilenceDurationMs ?: 0L
                }

                var startFailed = false
                var allSent = true
                var sentFramesNow = 0L
                var sentChunksNow = 0L
                var sentBytesNow = 0L

                micSendMutex.withLock {
                    val allowedToFlush = synchronized(this@ConversationCoordinator) {
                        activeTurn === turn &&
                            !turn.closed &&
                            turn.turnSeq == turnSeq &&
                            !turn.endOfTurnSubmitted &&
                            !isDeliberatelyPaused &&
                            !isDeliberatelyStopped &&
                            (_uiState.value.state == ConversationState.LISTENING)
                    }
                    if (!allowedToFlush) {
                        return@launch
                    }

                    when (decision) {
                        is SpeechGateDecision.SpeechConfirmedStart -> {
                            if (!turn.activityStartAttempted) {
                                turn.activityStartAttempted = true
                                val startOk = liveAdapter.signalStartOfSpokenTurn()
                                turn.activityStartSent = startOk
                                if (!startOk) {
                                    turn.activityStartFailed = true
                                    startFailed = true
                                }
                            }
                            if (!startFailed) {
                                for (chunk in decision.preRollPcmChunks) {
                                    val chunkOk = liveAdapter.sendRealtimeAudioToActiveSession(
                                        pcm16kHzBytes = chunk,
                                        mimeType = "audio/pcm;rate=16000"
                                    )
                                    if (!chunkOk) {
                                        allSent = false
                                        break
                                    }
                                    sentFramesNow += (chunk.size / 2).toLong()
                                    sentChunksNow += 1L
                                    sentBytesNow += chunk.size.toLong()
                                }
                            }
                        }

                        is SpeechGateDecision.StreamActiveFrame -> {
                            if (!turn.activityStartSent) {
                                startFailed = true
                                allSent = false
                            } else {
                                val chunkOk = liveAdapter.sendRealtimeAudioToActiveSession(
                                    pcm16kHzBytes = decision.pcmBytes,
                                    mimeType = "audio/pcm;rate=16000"
                                )
                                if (!chunkOk) {
                                    allSent = false
                                } else {
                                    sentFramesNow += (decision.pcmBytes.size / 2).toLong()
                                    sentChunksNow += 1L
                                    sentBytesNow += decision.pcmBytes.size.toLong()
                                }
                            }
                        }

                        else -> Unit
                    }
                }

                if (startFailed || !allSent) {
                    synchronized(this@ConversationCoordinator) {
                        if (activeTurn === turn &&
                            !turn.closed &&
                            turn.turnSeq == turnSeq &&
                            (_uiState.value.state == ConversationState.LISTENING ||
                                _uiState.value.state == ConversationState.PROCESSING ||
                                _uiState.value.state == ConversationState.THINKING)
                        ) {
                            val code = if (!liveAdapter.networkMonitor.isInternetValidated()) {
                                "NETWORK_UNAVAILABLE"
                            } else if (turn.confirmedSentFramesCount <= 0L) {
                                "NO_AUDIO_FRAMES_SENT"
                            } else {
                                "NETWORK_ERROR"
                            }
                            failActiveVoiceTurnLocked(
                                failureCode = code,
                                detail = if (startFailed) {
                                    "Failed to signal start-of-turn activity to Gemini Live ($code)."
                                } else {
                                    "Failed to stream 16kHz microphone audio chunk to Gemini Live ($code)."
                                },
                                expectedSessionId = turnGen.sessionId,
                                expectedTurnSeq = turnSeq,
                                expectedTurnGen = turnGen
                            )
                        }
                    }
                    return@launch
                }

                synchronized(this@ConversationCoordinator) {
                    if (activeTurn === turn && !turn.closed && turn.turnSeq == turnSeq) {
                        turn.confirmedSentFramesCount += sentFramesNow
                        turn.confirmedSentChunksCount += sentChunksNow
                        turn.confirmedSentBytesCount += sentBytesNow
                        if (!_uiState.value.turnEventTrace.contains("USER_ACTIVITY_START")) {
                            appendTurnEventLocked("USER_ACTIVITY_START")
                        }
                        appendTurnEventLocked("MIC_FRAMES_STREAMED")
                        appendFirstTurnLifecycleLocked("AUDIO_SENT")
                        _uiState.update { state ->
                            state.copy(
                                voiceTurnFramesSent = turn.confirmedSentFramesCount,
                                voiceTurnChunksSent = turn.confirmedSentChunksCount,
                                voiceTurnBytesSent = turn.confirmedSentBytesCount,
                                structuredTurnTrace = turn.structuredTraceEntries.toList(),
                                activeTurnEarliestMissingStep = turn.earliestMissingStep()
                            )
                        }
                    }
                }

                if (shouldTriggerAutoEnd) {
                    finishSpokenTurnAndAwaitResponse(
                        reason = "Automatic end-of-turn monotonic trailing silence detected (${trailingSilenceMsForLog}ms)",
                        fromAutoEndJob = kotlin.coroutines.coroutineContext[Job]
                    )
                }
            }
            turn.micSendJobs.removeAll { it.isCompleted }
            turn.micSendJobs.add(sendJob)
        }
    }

    suspend fun finishSpokenTurnAndAwaitResponse(
        reason: String = "Operator signaled end of spoken question",
        requireCapturedSpeech: Boolean = true,
        fromAutoEndJob: Job? = null
    ): VerifiedOutcome {
        val turn: TurnRuntimeState
        val turnSeq: Long
        val turnGen: TurnGenerationId
        val sessionId: SessionId
        val turnId: TurnId
        val completionDeferred: CompletableDeferred<VerifiedOutcome>
        val needsEndOfTurnSignal: Boolean
        val micJobsToFlush: List<Job>

        synchronized(this) {
            val current = _uiState.value
            val currentTurn = activeTurn
            if (currentTurn == null || currentTurn.closed) {
                return current.lastOutcome ?: VerifiedOutcome.StateTransitionVerified(
                    actionId = current.lastActionId,
                    sessionId = current.sessionId,
                    turnId = current.currentTurnId,
                    previousState = current.state,
                    resultingState = current.state,
                    detail = "No active voice turn to finish (state=${current.state.wireLabel})."
                )
            }
            turn = currentTurn
            turnSeq = turn.turnSeq
            turnGen = turn.turnGen
            sessionId = turn.sessionId
            turnId = turn.turnId
            completionDeferred = turn.completionDeferred

            if (!ensureWakeAuthorizationValid(currentTurn)) {
                stopTurnMicrophoneLocked("Wake authorization required", true, currentTurn)
                return failActiveVoiceTurnLocked(
                    failureCode = "WAKE_WORD_REQUIRED",
                    detail = "Manual submit rejected: Wake word 'Jarvis' not detected. Say 'Jarvis' first to activate OmniVoice."
                )!!
            }

            if (current.state == ConversationState.LISTENING) {
                if (turn.endOfTurnRequested || turn.endOfTurnSubmissionInFlight || turn.endOfTurnSubmitted) {
                    needsEndOfTurnSignal = false
                    micJobsToFlush = emptyList()
                } else {
                    cancelInactivityTimerLocked()

                    val currentJob = kotlin.coroutines.coroutineContext[Job]
                    if (fromAutoEndJob != null) {
                        val triggerIdx = turn.micSendJobs.indexOfFirst { it === fromAutoEndJob }
                        if (triggerIdx >= 0 && triggerIdx + 1 < turn.micSendJobs.size) {
                            val tailJobs = turn.micSendJobs.subList(triggerIdx + 1, turn.micSendJobs.size).toList()
                            for (tailJob in tailJobs) {
                                tailJob.cancel()
                            }
                            turn.micSendJobs.removeAll(tailJobs.toSet())
                        }
                        micJobsToFlush = emptyList()
                    } else {
                        micJobsToFlush = turn.micSendJobs.filter { it.isActive && it !== currentJob }
                    }
                    if (turn.capturedFramesCount <= 0L && micJobsToFlush.isEmpty()) {
                        turn.endOfTurnRequested = true
                        stopTurnMicrophoneLocked(
                            reason = "No microphone audio captured ($reason)",
                            cancelQueuedMicSends = true,
                            turnForTrace = turn
                        )
                        return failActiveVoiceTurnLocked(
                            failureCode = "NO_AUDIO_CAPTURED",
                            detail = "No microphone audio frames were captured for ${turnId.value}. Refusing to enter PROCESSING. Speak clearly and try again.",
                            expectedSessionId = sessionId,
                            expectedTurnSeq = turnSeq,
                            expectedTurnGen = turnGen
                        )!!
                    }

                    turn.endOfTurnRequested = true
                    stopTurnMicrophoneLocked(
                        reason = "Spoken question complete ($reason)",
                        cancelQueuedMicSends = false,
                        turnForTrace = turn
                    )
                    needsEndOfTurnSignal = true
                }
            } else {
                needsEndOfTurnSignal = false
                micJobsToFlush = emptyList()
            }
        }

        if (needsEndOfTurnSignal) {
            withContext(backgroundDispatcher) {
                flushPreRollAndSubmitEndOfTurn(
                    turn = turn,
                    turnSeq = turnSeq,
                    turnGen = turnGen,
                    sessionId = sessionId,
                    turnId = turnId,
                    reason = reason,
                    requireCapturedSpeech = requireCapturedSpeech,
                    micJobsToFlush = micJobsToFlush
                )
            }
        }

        return completionDeferred.await()
    }

    private suspend fun flushPreRollAndSubmitEndOfTurn(
        turn: TurnRuntimeState,
        turnSeq: Long,
        turnGen: TurnGenerationId,
        sessionId: SessionId,
        turnId: TurnId,
        reason: String,
        requireCapturedSpeech: Boolean,
        micJobsToFlush: List<Job>
    ) {
        for (job in micJobsToFlush) {
            job.join()
        }

        val manualPreRollChunks: List<ByteArray>? = synchronized(this) {
            if (activeTurn !== turn ||
                turn.closed ||
                turn.turnSeq != turnSeq ||
                turn.turnGen != turnGen ||
                turn.endOfTurnSubmitted ||
                isDeliberatelyPaused ||
                isDeliberatelyStopped ||
                _uiState.value.state != ConversationState.LISTENING
            ) {
                return@synchronized null
            }
            if (turn.capturedFramesCount <= 0L) {
                failActiveVoiceTurnLocked(
                    failureCode = "NO_AUDIO_CAPTURED",
                    detail = "No microphone audio frames were captured for ${turnId.value}. Refusing to enter PROCESSING. Speak clearly and try again.",
                    expectedSessionId = sessionId,
                    expectedTurnSeq = turnSeq,
                    expectedTurnGen = turnGen
                )
                return@synchronized null
            }
            val hasRealSpeech =
                turn.nonSilentSpeechFramesCount > 0L && (speechDetector.requiresLoadedModel || turn.peakRmsLinear >= 0.008f)
            if (!hasRealSpeech && (requireCapturedSpeech || turn.capturedFramesCount > 0L)) {
                appendTurnEventLocked("SILENT_TURN_REJECTED")
                failActiveVoiceTurnLocked(
                    failureCode = "SILENT_AUDIO_CAPTURED",
                    detail = "Captured microphone audio had no speech signal (capturedFrames=${turn.capturedFramesCount}, peak RMS=${"%.3f".format(turn.peakRmsLinear)}). Refusing to enter PROCESSING.",
                    expectedSessionId = sessionId,
                    expectedTurnSeq = turnSeq,
                    expectedTurnGen = turnGen
                )
                return@synchronized null
            }
            val chunks =
                if (speechGate.telemetrySnapshot().gateState == SpeechGateState.WAITING_FOR_SPEECH) {
                    speechGate.consumePreRollForManualSubmit()
                } else {
                    emptyList()
                }
            if (chunks.isNotEmpty() && !turn.hasStep(TurnTraceStep.USER_SPEECH_DETECTED)) {
                turn.recordStep(
                    step = TurnTraceStep.USER_SPEECH_DETECTED,
                    timestampLabel = clockProvider(),
                    liveGenWireId = liveAdapter.state.value.activeGeneration?.wireId,
                    detail = "manualSubmitPreRoll=${chunks.sumOf { it.size }}B"
                )
            }
            syncSpeechGateTelemetryLocked()
            chunks
        }

        if (manualPreRollChunks == null) {
            return
        }

        if (manualPreRollChunks.isNotEmpty()) {
            var startFailed = false
            var sentFramesNow = 0L
            var sentChunksNow = 0L
            var sentBytesNow = 0L
            micSendMutex.withLock {
                val stillEligible = synchronized(this@ConversationCoordinator) {
                    activeTurn === turn &&
                        !turn.closed &&
                        turn.turnSeq == turnSeq &&
                        !turn.endOfTurnSubmitted &&
                        !isDeliberatelyPaused &&
                        !isDeliberatelyStopped
                }
                if (stillEligible) {
                    if (!turn.activityStartAttempted) {
                        turn.activityStartAttempted = true
                        val startOk = liveAdapter.signalStartOfSpokenTurn()
                        turn.activityStartSent = startOk
                        if (!startOk) {
                            turn.activityStartFailed = true
                            startFailed = true
                        }
                    }
                    if (!startFailed) {
                        for (chunk in manualPreRollChunks) {
                            val ok = liveAdapter.sendRealtimeAudioToActiveSession(
                                pcm16kHzBytes = chunk,
                                mimeType = "audio/pcm;rate=16000"
                            )
                            if (!ok) break
                            sentFramesNow += (chunk.size / 2).toLong()
                            sentChunksNow += 1L
                            sentBytesNow += chunk.size.toLong()
                        }
                    }
                }
            }
            if (sentFramesNow > 0L) {
                synchronized(this@ConversationCoordinator) {
                    if (activeTurn === turn && !turn.closed && turn.turnSeq == turnSeq) {
                        turn.confirmedSentFramesCount += sentFramesNow
                        turn.confirmedSentChunksCount += sentChunksNow
                        turn.confirmedSentBytesCount += sentBytesNow
                        if (!_uiState.value.turnEventTrace.contains("USER_ACTIVITY_START")) {
                            appendTurnEventLocked("USER_ACTIVITY_START")
                        }
                        appendTurnEventLocked("MIC_FRAMES_STREAMED")
                        appendFirstTurnLifecycleLocked("AUDIO_SENT")
                        _uiState.update { state ->
                            state.copy(
                                voiceTurnFramesSent = turn.confirmedSentFramesCount,
                                voiceTurnChunksSent = turn.confirmedSentChunksCount,
                                voiceTurnBytesSent = turn.confirmedSentBytesCount,
                                structuredTurnTrace = turn.structuredTraceEntries.toList(),
                                activeTurnEarliestMissingStep = turn.earliestMissingStep()
                            )
                        }
                    }
                }
            }
        }

        val readyToSendEndOfTurn = synchronized(this) {
            if (activeTurn !== turn ||
                turn.closed ||
                turn.turnSeq != turnSeq ||
                turn.turnGen != turnGen ||
                isDeliberatelyPaused ||
                isDeliberatelyStopped
            ) {
                return@synchronized false
            }
            if (turn.endOfTurnSubmitted) {
                return@synchronized false
            }
            if (turn.confirmedSentFramesCount <= 0L) {
                val failCode = if (!liveAdapter.networkMonitor.isInternetValidated()) {
                    "NETWORK_UNAVAILABLE"
                } else {
                    "NO_AUDIO_FRAMES_SENT"
                }
                failActiveVoiceTurnLocked(
                    failureCode = failCode,
                    detail = "Captured audio was not confirmed sent to Gemini Live (confirmedSentFrames=0, capturedFrames=${turn.capturedFramesCount}). Refusing to enter PROCESSING.",
                    expectedSessionId = sessionId,
                    expectedTurnSeq = turnSeq,
                    expectedTurnGen = turnGen
                )
                return@synchronized false
            }
            if (!liveAdapter.networkMonitor.isInternetValidated()) {
                failActiveVoiceTurnLocked(
                    failureCode = "NETWORK_UNAVAILABLE",
                    detail = "NETWORK_UNAVAILABLE: Validated internet lost before end-of-turn could be submitted.",
                    expectedSessionId = sessionId,
                    expectedTurnSeq = turnSeq,
                    expectedTurnGen = turnGen
                )
                return@synchronized false
            }
            turn.endOfTurnSubmissionInFlight = true
            true
        }

        if (!readyToSendEndOfTurn) {
            return
        }

        var submissionStartedAtMs: Long? = null
        val signaled = micSendMutex.withLock {
            val stillEligible = synchronized(this@ConversationCoordinator) {
                val eligible = activeTurn === turn &&
                    !turn.closed &&
                    turn.turnSeq == turnSeq &&
                    !turn.endOfTurnSubmitted &&
                    !isDeliberatelyPaused &&
                    !isDeliberatelyStopped
                if (eligible) {
                    val nowMs = monotonicClockMs()
                    submissionStartedAtMs = nowMs
                    if (!turn.autoEndTriggered) {
                        speechGate.recordManualEndOfSpeech(nowMs)
                        syncSpeechGateTelemetryLocked()
                    }
                    val gateSnapBeforeEnd = speechGate.telemetrySnapshot()
                    if (turn.tLastUserSpeechMs == null) {
                        turn.tLastUserSpeechMs = gateSnapBeforeEnd.lastUserSpeechSampleAtMs
                    }
                    if (turn.tSpeechEndMs == null) {
                        turn.tSpeechEndMs = gateSnapBeforeEnd.speechEndDetectedAtMs
                    }
                    turn.tEndSubmittedMs = nowMs
                }
                eligible
            }
            if (!stillEligible) {
                false
            } else {
                liveAdapter.signalEndOfSpokenTurn()
            }
        }

        synchronized(this) {
            turn.endOfTurnSubmissionInFlight = false
            if (activeTurn !== turn || turn.closed || turn.turnSeq != turnSeq || turn.turnGen != turnGen) {
                return@synchronized
            }
            if (!signaled) {
                turn.tEndSubmittedMs = null
                turn.endOfTurnFailed = true
                val failCode = if (!liveAdapter.networkMonitor.isInternetValidated()) {
                    "NETWORK_UNAVAILABLE"
                } else {
                    "END_OF_TURN_SEND_FAILED"
                }
                failActiveVoiceTurnLocked(
                    failureCode = failCode,
                    detail = "Failed to send end-of-turn signal to Gemini Live ($failCode). Refusing to remain in PROCESSING.",
                    expectedSessionId = sessionId,
                    expectedTurnSeq = turnSeq,
                    expectedTurnGen = turnGen
                )
                return@synchronized
            }

            turn.endOfTurnSubmitted = true
            if (turn.tEndSubmittedMs == null) {
                turn.tEndSubmittedMs = submissionStartedAtMs ?: monotonicClockMs()
            }
            val gateSnapAfterEnd = speechGate.telemetrySnapshot()
            if (turn.tLastUserSpeechMs == null) {
                turn.tLastUserSpeechMs = gateSnapAfterEnd.lastUserSpeechSampleAtMs
            }
            if (turn.tSpeechEndMs == null) {
                turn.tSpeechEndMs = gateSnapAfterEnd.speechEndDetectedAtMs
            }
            syncTimingSnapshotsLocked()
            promoteToProcessingLocked(
                turn = turn,
                sessionId = sessionId,
                turnId = turnId,
                turnGen = turnGen
            )
            if (!turn.hasStep(TurnTraceStep.SERVER_TURN_COMPLETE) &&
                (_uiState.value.state == ConversationState.PROCESSING || _uiState.value.state == ConversationState.SPEAKING)
            ) {
                armFirstResponseWatchdogLocked(turn)
            }
        }
    }

    private fun promoteToProcessingLocked(
        turn: TurnRuntimeState,
        sessionId: SessionId,
        turnId: TurnId,
        turnGen: TurnGenerationId
    ) {
        val current = _uiState.value
        if (current.state != ConversationState.LISTENING || activeTurn !== turn || turn.closed) {
            return
        }
        val liveGenWire = liveAdapter.state.value.activeGeneration?.wireId
        turn.recordStep(
            step = TurnTraceStep.ACTIVITY_END_SENT,
            timestampLabel = clockProvider(),
            liveGenWireId = liveGenWire,
            detail = "confirmedSentFrames=${turn.confirmedSentFramesCount}"
        )
        appendTurnEventLocked("USER_ACTIVITY_END")
        appendFirstTurnLifecycleLocked("ACTIVITY_END_OF_TURN")
        val updatedSeq = appendTurnStateLocked(ConversationState.PROCESSING)
        val updatedReadiness = appendReadinessOrderLocked("PROCESSING")
        val updatedPhaseSeq = appendActiveConversationPhaseLocked(ActiveConversationPhase.THINKING)
        val procDetail =
            "THINKING / PROCESSING (${turnGen.wireId}): Spoken question sent (${turn.confirmedSentFramesCount} frames, ${turn.confirmedSentBytesCount} bytes, peak RMS ${"%.3f".format(turn.peakRmsLinear)}). Half-duplex mic disabled; awaiting Gemini native audio..."
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
                activeConversationPhase = ActiveConversationPhase.THINKING,
                activeConversationPhaseSequence = updatedPhaseSeq,
                voiceTurnStateSequence = updatedSeq,
                voiceTurnReadinessOrder = updatedReadiness,
                structuredTurnTrace = turn.structuredTraceEntries.toList(),
                activeTurnEarliestMissingStep = turn.earliestMissingStep(),
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
    }

    private fun armFirstResponseWatchdogLocked(turn: TurnRuntimeState) {
        if (turn.closed || !turn.endOfTurnSubmitted || turn.tEndSubmittedMs == null || turn.firstResponseWatchdogArmed) return
        val timeoutMs = firstResponseTimeoutMs
        if (timeoutMs <= 0L) return
        turn.firstResponseWatchdogArmed = true
        val expectedSeq = turn.turnSeq
        val expectedTurnGen = turn.turnGen
        turn.firstResponseWatchdogJob = turnScope.launch(turn.parentJob) {
            var accumulatedWaitMs = 0L
            var accumulatedWaitSinceResponseMs = 0L
            var accumulatedWaitSinceLastAudioProgressMs = 0L
            var accumulatedWaitSinceLocalPlaybackIdleMs = 0L
            var lastSeenAudioChunkCount = 0
            var lastSeenResponseStarted = false
            var lastSeenBytesAccepted = 0L
            var lastSeenPlaybackHeadFrames = 0L

            while (true) {
                var waitStepMs = 10L
                var triggerTimeout = false
                var triggerTimeoutMs = timeoutMs
                var triggerFailureCode = "FIRST_RESPONSE_TIMEOUT"
                var triggerFailureReason = ""
                var shouldCountResponseWait = false
                var shouldCountServerAudioIdleWait = false
                var shouldCountPostPlaybackCompletionWait = false

                synchronized(this@ConversationCoordinator) {
                    if (activeTurn !== turn ||
                        turn.closed ||
                        turn.turnSeq != expectedSeq ||
                        turn.turnGen != expectedTurnGen ||
                        turn.hasStep(TurnTraceStep.SERVER_TURN_COMPLETE) ||
                        isDeliberatelyPaused ||
                        isDeliberatelyStopped
                    ) {
                        return@launch
                    }

                    val nowMs = monotonicClockMs()
                    val submittedAtMs = turn.tEndSubmittedMs ?: nowMs
                    val firstRespMs = turn.tFirstResponseMs
                    val firstAudioMs = turn.tFirstAudioReceivedMs
                    val lastProgMs = turn.tLastServerProgressMs
                    val audioChunks = turn.serverAudioChunksReceivedCount
                    val playSnap = playbackAdapter.state.value
                    val isTurnPlaybackActive =
                        turn.playbackGen != null && playSnap.activeGeneration == turn.playbackGen
                    val bytesAccepted = if (isTurnPlaybackActive) playSnap.bytesAcceptedByHardware else 0L
                    val framesAccepted = if (isTurnPlaybackActive) playSnap.totalFramesWrittenToHardware else 0L
                    val headFrames = if (isTurnPlaybackActive) playSnap.hardwarePlaybackHeadFrames else 0L
                    val queuedBytes = if (isTurnPlaybackActive) playSnap.queuedBytes else 0L
                    val isPlaying = isTurnPlaybackActive && playSnap.isPlaying

                    if (firstRespMs != null && !lastSeenResponseStarted) {
                        lastSeenResponseStarted = true
                        accumulatedWaitSinceResponseMs = 0L
                    }
                    if (audioChunks > lastSeenAudioChunkCount) {
                        lastSeenAudioChunkCount = audioChunks
                        accumulatedWaitSinceLastAudioProgressMs = 0L
                        turn.tAllReceivedAudioPlayedMs = null
                        accumulatedWaitSinceLocalPlaybackIdleMs = 0L
                    }
                    if (bytesAccepted > lastSeenBytesAccepted || headFrames > lastSeenPlaybackHeadFrames) {
                        lastSeenBytesAccepted = bytesAccepted
                        lastSeenPlaybackHeadFrames = headFrames
                        turn.tLastPlaybackProgressMs = maxOf(
                            turn.tLastPlaybackProgressMs ?: 0L,
                            nowMs,
                            playSnap.lastHardwareProgressAtMs
                        )
                        accumulatedWaitSinceLocalPlaybackIdleMs = 0L
                    }

                    val totalElapsedMs = max((nowMs - submittedAtMs).coerceAtLeast(0L), accumulatedWaitMs)

                    if (firstRespMs == null && firstAudioMs == null) {
                        if (totalElapsedMs >= timeoutMs) {
                            triggerTimeout = true
                            triggerTimeoutMs = timeoutMs
                            triggerFailureCode = "FIRST_RESPONSE_TIMEOUT"
                            triggerFailureReason = "No server response within ${timeoutMs}ms after end-of-turn submission"
                        } else {
                            waitStepMs = (timeoutMs - totalElapsedMs).coerceAtLeast(10L)
                        }
                    } else if (firstAudioMs == null) {
                        shouldCountResponseWait = true
                        val audioWaitLimitMs = firstAudioAfterResponseTimeoutMs.coerceAtLeast(timeoutMs)
                        val hardBoundMs = maxTurnResponseHardBoundMs.coerceAtLeast(audioWaitLimitMs)
                        val elapsedSinceRespMs = max(
                            (nowMs - (firstRespMs ?: submittedAtMs)).coerceAtLeast(0L),
                            accumulatedWaitSinceResponseMs
                        )
                        if (elapsedSinceRespMs >= audioWaitLimitMs || totalElapsedMs >= hardBoundMs) {
                            triggerTimeout = true
                            triggerTimeoutMs = if (totalElapsedMs >= hardBoundMs && elapsedSinceRespMs < audioWaitLimitMs) {
                                hardBoundMs
                            } else {
                                audioWaitLimitMs
                            }
                            triggerFailureCode = "FIRST_RESPONSE_TIMEOUT"
                            triggerFailureReason =
                                "Text-only server response received without any audio chunk within ${triggerTimeoutMs}ms (TEXT_ONLY_NO_AUDIO_RECEIVED)"
                        } else {
                            waitStepMs = minOf(
                                audioWaitLimitMs - elapsedSinceRespMs,
                                hardBoundMs - totalElapsedMs
                            ).coerceAtLeast(10L)
                        }
                    } else {
                        val stallLimitMs = serverOutputStallTimeoutMs.coerceAtLeast(100L)
                        val completionWaitLimitMs = missingTurnCompleteTimeoutMs.coerceAtLeast(100L)
                        val hardBoundMs = maxTurnResponseHardBoundMs.coerceAtLeast(
                            maxOf(stallLimitMs, completionWaitLimitMs)
                        )
                        val refProgMs = lastProgMs ?: firstAudioMs
                        val isActivelyPlayingLocalAudio =
                            isTurnPlaybackActive &&
                                isPlaying &&
                                framesAccepted > 0L &&
                                (queuedBytes > 0L || headFrames < framesAccepted)
                        val allReceivedAudioPlayed =
                            isTurnPlaybackActive &&
                                isPlaying &&
                                queuedBytes == 0L &&
                                framesAccepted > 0L &&
                                headFrames >= framesAccepted

                        if (isActivelyPlayingLocalAudio) {
                            turn.tAllReceivedAudioPlayedMs = null
                            accumulatedWaitSinceLocalPlaybackIdleMs = 0L
                            accumulatedWaitSinceLastAudioProgressMs = 0L
                            if (totalElapsedMs >= hardBoundMs) {
                                triggerTimeout = true
                                triggerTimeoutMs = hardBoundMs
                                triggerFailureCode = "SERVER_OUTPUT_STALL_TIMEOUT"
                                triggerFailureReason =
                                    "Server response exceeded hard upper bound of ${hardBoundMs}ms without turnComplete (audioChunks=$audioChunks, accepted=$framesAccepted frames, played=$headFrames frames)"
                            } else {
                                waitStepMs = minOf(
                                    100L,
                                    hardBoundMs - totalElapsedMs
                                ).coerceAtLeast(10L)
                            }
                        } else if (allReceivedAudioPlayed) {
                            shouldCountPostPlaybackCompletionWait = true
                            accumulatedWaitSinceLastAudioProgressMs = 0L
                            if (turn.tAllReceivedAudioPlayedMs == null) {
                                turn.tAllReceivedAudioPlayedMs = maxOf(
                                    nowMs,
                                    playSnap.lastHardwareProgressAtMs,
                                    refProgMs
                                )
                            }
                            val playedDoneAtMs = turn.tAllReceivedAudioPlayedMs!!
                            val idleAfterPlaybackMs = max(
                                (nowMs - playedDoneAtMs).coerceAtLeast(0L),
                                accumulatedWaitSinceLocalPlaybackIdleMs
                            )
                            if (totalElapsedMs >= hardBoundMs) {
                                triggerTimeout = true
                                triggerTimeoutMs = hardBoundMs
                                triggerFailureCode = "MISSING_SERVER_TURN_COMPLETE"
                                triggerFailureReason =
                                    "All $audioChunks audio chunk(s) ($headFrames/$framesAccepted frames) finished playing without server turnComplete before hard bound of ${hardBoundMs}ms"
                            } else if (idleAfterPlaybackMs >= completionWaitLimitMs) {
                                triggerTimeout = true
                                triggerTimeoutMs = completionWaitLimitMs
                                triggerFailureCode = "MISSING_SERVER_TURN_COMPLETE"
                                triggerFailureReason =
                                    "All $audioChunks audio chunk(s) ($headFrames/$framesAccepted frames) finished playing, but server turnComplete did not arrive within ${completionWaitLimitMs}ms"
                            } else {
                                waitStepMs = minOf(
                                    completionWaitLimitMs - idleAfterPlaybackMs,
                                    hardBoundMs - totalElapsedMs
                                ).coerceAtLeast(10L)
                            }
                        } else {
                            turn.tAllReceivedAudioPlayedMs = null
                            accumulatedWaitSinceLocalPlaybackIdleMs = 0L
                            shouldCountServerAudioIdleWait = true
                            val idleSinceProgressMs = max(
                                (nowMs - refProgMs).coerceAtLeast(0L),
                                accumulatedWaitSinceLastAudioProgressMs
                            )
                            if (totalElapsedMs >= hardBoundMs) {
                                triggerTimeout = true
                                triggerTimeoutMs = hardBoundMs
                                triggerFailureCode = "SERVER_OUTPUT_STALL_TIMEOUT"
                                triggerFailureReason =
                                    "Server response exceeded hard upper bound of ${hardBoundMs}ms without turnComplete (audioChunks=$audioChunks)"
                            } else if (idleSinceProgressMs >= stallLimitMs) {
                                triggerTimeout = true
                                triggerTimeoutMs = stallLimitMs
                                triggerFailureCode = "SERVER_OUTPUT_STALL_TIMEOUT"
                                triggerFailureReason =
                                    "Server audio output stalled for ${stallLimitMs}ms mid-response after $audioChunks audio chunk(s) without turnComplete (queued=${playSnap.queuedFrames} frames, accepted=$framesAccepted frames, played=$headFrames frames)"
                            } else {
                                waitStepMs = minOf(
                                    stallLimitMs - idleSinceProgressMs,
                                    hardBoundMs - totalElapsedMs
                                ).coerceAtLeast(10L)
                            }
                        }
                    }
                }

                if (triggerTimeout) {
                    turnScope.launch {
                        handleFirstResponseTimeout(
                            expectedTurnSeq = expectedSeq,
                            expectedTurnGen = expectedTurnGen,
                            timeoutMs = triggerTimeoutMs,
                            failureCode = triggerFailureCode,
                            failureDetailReason = triggerFailureReason
                        )
                    }
                    return@launch
                }

                delay(waitStepMs)
                accumulatedWaitMs += waitStepMs
                if (shouldCountResponseWait) {
                    accumulatedWaitSinceResponseMs += waitStepMs
                }
                if (shouldCountServerAudioIdleWait) {
                    accumulatedWaitSinceLastAudioProgressMs += waitStepMs
                }
                if (shouldCountPostPlaybackCompletionWait) {
                    accumulatedWaitSinceLocalPlaybackIdleMs += waitStepMs
                }
            }
        }
    }

    private suspend fun handleFirstResponseTimeout(
        expectedTurnSeq: Long,
        expectedTurnGen: TurnGenerationId,
        timeoutMs: Long,
        failureCode: String = "FIRST_RESPONSE_TIMEOUT",
        failureDetailReason: String = ""
    ) {
        val turn: TurnRuntimeState
        val missingStep: String
        val canAttemptSingleRecovery: Boolean
        val activeConvEnabled: Boolean
        val sessionId: SessionId
        val deferredToResolve: CompletableDeferred<VerifiedOutcome>
        val preCleanupFrameSummary: String
        val recoveryJob: Job? = kotlin.coroutines.coroutineContext[Job]

        synchronized(this) {
            val currentTurn = activeTurn
            val currentUi = _uiState.value
            val isStaleForFailureKind = if (failureCode == "FIRST_RESPONSE_TIMEOUT") {
                currentTurn?.tFirstAudioReceivedMs != null || currentUi.state != ConversationState.PROCESSING
            } else {
                currentUi.state != ConversationState.PROCESSING && currentUi.state != ConversationState.SPEAKING
            }
            if (currentTurn == null ||
                currentTurn.closed ||
                currentTurn.turnSeq != expectedTurnSeq ||
                currentTurn.turnGen != expectedTurnGen ||
                isStaleForFailureKind ||
                currentTurn.hasStep(TurnTraceStep.SERVER_TURN_COMPLETE) ||
                isDeliberatelyPaused ||
                isDeliberatelyStopped
            ) {
                logTurnLifecycleEventLocked(
                    "STALE_CALLBACK_DROPPED: Dropped stale watchdog timeout [$failureCode] for ${expectedTurnGen.wireId} " +
                        "(active=${currentTurn?.turnGen?.wireId ?: "none"}, state=${currentUi.state.wireLabel})."
                )
                _uiState.update { state ->
                    state.copy(staleTurnCallbacksDropped = state.staleTurnCallbacksDropped + 1)
                }
                return
            }

            turn = currentTurn
            val preCleanupPlaySnap = playbackAdapter.state.value
            latchPlaybackSnapshotIntoTurnLocked(turn, preCleanupPlaySnap)
            preCleanupFrameSummary =
                "recv=${preCleanupPlaySnap.framesReceivedFromServer}, " +
                    "queued=${preCleanupPlaySnap.queuedFrames}, " +
                    "accepted=${preCleanupPlaySnap.totalFramesWrittenToHardware}, " +
                    "played=${preCleanupPlaySnap.hardwarePlaybackHeadFrames}, " +
                    "padding=${preCleanupPlaySnap.paddingFramesWrittenToHardware}, " +
                    "chunks=${turn.serverAudioChunksReceivedCount}"

            turn.firstResponseTimeoutFired = true
            turn.watchdogFailureCode = failureCode
            turn.completionHandled = true
            missingStep = turn.earliestMissingStep()
            previousTurnEarliestMissingStep = missingStep
            consecutiveFirstResponseTimeouts += 1
            val newTimeoutCount = currentUi.firstResponseTimeoutCount + 1
            val newServerStallCount = if (failureCode == "SERVER_OUTPUT_STALL_TIMEOUT") {
                currentUi.serverOutputStallCount + 1
            } else {
                currentUi.serverOutputStallCount
            }
            val newMissingTurnCompleteCount = if (failureCode == "MISSING_SERVER_TURN_COMPLETE") {
                currentUi.missingTurnCompleteCount + 1
            } else {
                currentUi.missingTurnCompleteCount
            }
            activeConvEnabled = currentUi.isActiveConversationEnabled
            sessionId = turn.sessionId
            deferredToResolve = turn.completionDeferred
            val isOnline = liveAdapter.networkMonitor.isInternetValidated()
            canAttemptSingleRecovery =
                consecutiveFirstResponseTimeouts == 1 &&
                    !isDeliberatelyPaused &&
                    !isDeliberatelyStopped &&
                    isOnline

            logTurnLifecycleEventLocked(
                "$failureCode[${turn.turnId.value} @ ${turn.turnGen.wireId}]: missing=$missingStep " +
                    "consecutive=$consecutiveFirstResponseTimeouts canRecover=$canAttemptSingleRecovery " +
                    "frames=[$preCleanupFrameSummary] reason=$failureDetailReason " +
                    "trace=[${turn.structuredTraceEntries.joinToString(" -> ")}]"
            )

            cancelInactivityTimerLocked()
            activeTurn = null
            turn.dispose(cancelAllJobs = true)
            audioCapture.stopAndRelease(reason = "$failureCode on ${turn.turnId.value}", wipeEphemeral = true)
            playbackAdapter.stopAndFlush(reason = "$failureCode on ${turn.turnId.value}")
            liveAdapter.setAudioPlaybackClaimed(false)

            appendTurnEventLocked(failureCode)
            recordTurnOutcomeLocked(
                turn = turn,
                category = Phase07TurnOutcomeCategory.TIMED_OUT,
                outcomeCode = failureCode
            )

            if (!canAttemptSingleRecovery) {
                val failCode = if (!isOnline) "NETWORK_UNAVAILABLE" else failureCode
                val livePhaseNow = liveAdapter.state.value.phase
                if (livePhaseNow == LiveConnectionPhase.ACTIVE ||
                    livePhaseNow == LiveConnectionPhase.SETUP_ACCEPTED ||
                    livePhaseNow == LiveConnectionPhase.CONNECTING ||
                    livePhaseNow == LiveConnectionPhase.RECONNECTING
                ) {
                    liveAdapter.disconnectAndRelease(
                        reason = "Terminal $failCode on ${turn.turnId.value} ($preCleanupFrameSummary)"
                    )
                }
                val reasonSuffix = if (failureDetailReason.isNotBlank()) " [$failureDetailReason | $preCleanupFrameSummary]" else " [$preCleanupFrameSummary]"
                val detailMsg =
                    "$failCode after ${timeoutMs}ms waiting in ${currentUi.state.wireLabel} (earliestMissing=$missingStep, " +
                        "timeouts=$consecutiveFirstResponseTimeouts)$reasonSuffix. Stopped without infinite retries; press Start to try again."
                val actId = idGenerator.nextActionId(actionVerbForFailureCode(failCode, isTimeoutEnd = false))
                val updatedSeq = appendTurnStateLocked(ConversationState.ERROR)
                val updatedReadiness = appendReadinessOrderLocked("ERROR")
                val updatedPhaseSeq = appendActiveConversationPhaseLocked(ActiveConversationPhase.ERROR)
                val errOutcome = VerifiedOutcome.PrerequisiteFailureOutcome(
                    actionId = actId,
                    sessionId = sessionId,
                    turnId = turn.turnId,
                    resultingState = ConversationState.ERROR,
                    failureCode = failCode,
                    detail = detailMsg
                )
                lastCompletedTurnRuntime = turn
                recordDiagnosticErrorLocked(
                    stage = "${currentUi.state.wireLabel}_WATCHDOG",
                    errorCode = failCode,
                    rawSummary = detailMsg,
                    sessionId = sessionId,
                    turnId = turn.turnId,
                    generationWireId = turn.turnGen.wireId
                )
                syncTimingSnapshotsLocked()
                _uiState.update { state ->
                    state.copy(
                        currentTurnId = turn.turnId,
                        activeConversationPhase = ActiveConversationPhase.ERROR,
                        activeConversationPhaseSequence = updatedPhaseSeq,
                        activeTurnGenerationId = null,
                        voiceTurnStateSequence = updatedSeq,
                        voiceTurnReadinessOrder = updatedReadiness,
                        lastCompletedTurnTrace = turn.structuredTraceEntries.toList(),
                        lastCompletedTurnEarliestMissingStep = missingStep,
                        structuredTurnTrace = turn.structuredTraceEntries.toList(),
                        activeTurnEarliestMissingStep = missingStep,
                        firstResponseTimeoutCount = newTimeoutCount,
                        serverOutputStallCount = newServerStallCount,
                        missingTurnCompleteCount = newMissingTurnCompleteCount,
                        activeVoiceTurnErrorCode = failCode,
                        activeVoiceTurnErrorDetail = detailMsg,
                        lastVoiceTurnSummary = "ERROR [$failCode]: $detailMsg"
                    )
                }
                applyVerifiedOutcome(
                    sessionId = sessionId,
                    turnId = turn.turnId,
                    actionId = actId,
                    newState = ConversationState.ERROR,
                    roleLabel = "PROCESSING_WATCHDOG",
                    outcome = errOutcome
                )
                deferredToResolve.complete(errOutcome)
                return
            }

            activeTimeoutRecoveryJob = recoveryJob
            activeTimeoutRecoveryDeferred = deferredToResolve
            val preparingPhaseSeq = appendActiveConversationPhaseLocked(ActiveConversationPhase.PREPARING)
            val reasonSuffix = if (failureDetailReason.isNotBlank()) " [$failureDetailReason | $preCleanupFrameSummary]" else " [$preCleanupFrameSummary]"
            val recoveryStatus =
                "$failureCode after ${timeoutMs}ms (missing=$missingStep)$reasonSuffix. Performing 1 clean Gemini Live session recovery..."
            _uiState.update { state ->
                state.copy(
                    currentTurnId = turn.turnId,
                    state = ConversationState.PREPARING,
                    activeConversationPhase = ActiveConversationPhase.PREPARING,
                    activeConversationPhaseSequence = preparingPhaseSeq,
                    activeTurnGenerationId = null,
                    lastCompletedTurnTrace = turn.structuredTraceEntries.toList(),
                    structuredTurnTrace = turn.structuredTraceEntries.toList(),
                    activeTurnEarliestMissingStep = missingStep,
                    firstResponseTimeoutCount = newTimeoutCount,
                    serverOutputStallCount = newServerStallCount,
                    missingTurnCompleteCount = newMissingTurnCompleteCount,
                    activeVoiceTurnErrorCode = failureCode,
                    activeVoiceTurnErrorDetail = recoveryStatus,
                    lastVoiceTurnSummary = recoveryStatus
                )
            }
        }

        val recoveredLiveState = try {
            withContext(backgroundDispatcher) {
                liveAdapter.recreateSessionAfterInterruption(
                    sessionId = sessionId,
                    reason = "Single clean recovery after $failureCode (missing=$missingStep)"
                )
            }
        } catch (t: Throwable) {
            liveAdapter.state.value
        }

        synchronized(this) {
            if (activeTimeoutRecoveryJob === recoveryJob) {
                activeTimeoutRecoveryJob = null
            }
            if (activeTimeoutRecoveryDeferred === deferredToResolve) {
                activeTimeoutRecoveryDeferred = null
            }

            if (isDeliberatelyStopped || isDeliberatelyPaused ||
                _uiState.value.state == ConversationState.STOPPED ||
                _uiState.value.state == ConversationState.PAUSED
            ) {
                val preemptedOutcome = _uiState.value.lastOutcome ?: VerifiedOutcome.StateTransitionVerified(
                    actionId = idGenerator.nextActionId("timeout_preempted"),
                    sessionId = sessionId,
                    turnId = turn.turnId,
                    previousState = ConversationState.PREPARING,
                    resultingState = _uiState.value.state,
                    detail = "Timeout recovery superseded by operator Stop/Pause."
                )
                deferredToResolve.complete(preemptedOutcome)
                return
            }

            val isRecovered = liveAdapter.isSessionReadyForAudio() &&
                (recoveredLiveState.phase == LiveConnectionPhase.SETUP_ACCEPTED ||
                    recoveredLiveState.phase == LiveConnectionPhase.ACTIVE)

            if (isRecovered && activeConvEnabled) {
                val actId = idGenerator.nextActionId("timeout_recovered")
                val reasonSuffix = if (failureDetailReason.isNotBlank()) " [$failureDetailReason | $preCleanupFrameSummary]" else " [$preCleanupFrameSummary]"
                val recoveryOutcome = VerifiedOutcome.PrerequisiteFailureOutcome(
                    actionId = actId,
                    sessionId = sessionId,
                    turnId = turn.turnId,
                    resultingState = ConversationState.LISTENING,
                    failureCode = failureCode,
                    detail = "$failureCode after ${timeoutMs}ms (missing=$missingStep)$reasonSuffix. Clean Live recovery succeeded (${recoveredLiveState.activeGeneration?.wireId ?: "live"}); resuming LISTENING automatically."
                )
                recordDiagnosticErrorLocked(
                    stage = "RECOVERED_TURN_HISTORY",
                    errorCode = failureCode,
                    rawSummary = recoveryOutcome.detail,
                    sessionId = sessionId,
                    turnId = turn.turnId,
                    generationWireId = turn.turnGen.wireId
                )
                archiveActiveDiagnosticErrorLocked()
                _uiState.update { state ->
                    state.copy(
                        firstResponseRecoveryCount = state.firstResponseRecoveryCount + 1
                    )
                }
                autoRestoreCaptureForNextTurnLocked(
                    previousTurnId = turn.turnId,
                    completedTurn = turn,
                    allowRestoreAfterTimeoutRecovery = true,
                    recoveryReasonCode = failureCode
                )
                _uiState.update { state ->
                    state.copy(activeTurnEarliestMissingStep = missingStep)
                }
                deferredToResolve.complete(recoveryOutcome)
                return
            }

            val failCode = when {
                !liveAdapter.networkMonitor.isInternetValidated() ||
                    recoveredLiveState.phase == LiveConnectionPhase.NETWORK_UNAVAILABLE -> "NETWORK_UNAVAILABLE"
                !isRecovered && recoveredLiveState.failureClassification != LiveFailureClassification.NONE ->
                    recoveredLiveState.failureClassification.wireCode
                else -> failureCode
            }
            val postRecoveryLivePhase = liveAdapter.state.value.phase
            if (!isRecovered && (
                    postRecoveryLivePhase == LiveConnectionPhase.ACTIVE ||
                        postRecoveryLivePhase == LiveConnectionPhase.SETUP_ACCEPTED ||
                        postRecoveryLivePhase == LiveConnectionPhase.CONNECTING ||
                        postRecoveryLivePhase == LiveConnectionPhase.RECONNECTING
                    )
            ) {
                liveAdapter.disconnectAndRelease(
                    reason = "Recovery incomplete for $failCode on ${turn.turnId.value}"
                )
            }
            val reasonSuffix = if (failureDetailReason.isNotBlank()) " [$failureDetailReason | $preCleanupFrameSummary]" else " [$preCleanupFrameSummary]"
            val detailMsg = if (isRecovered) {
                "$failureCode after ${timeoutMs}ms (missing=$missingStep)$reasonSuffix. Clean Live recovery succeeded for next turn; press Start to speak again."
            } else {
                "$failCode: Timed out ($failureCode) after ${timeoutMs}ms (missing=$missingStep)$reasonSuffix and single Live recovery failed (${DiagnosticRedactor.redact(recoveredLiveState.statusDetail)})."
            }
            val actId = idGenerator.nextActionId(actionVerbForFailureCode(failCode, isTimeoutEnd = true))
            val updatedSeq = appendTurnStateLocked(ConversationState.ERROR)
            val updatedReadiness = appendReadinessOrderLocked("ERROR")
            val updatedPhaseSeq = appendActiveConversationPhaseLocked(ActiveConversationPhase.ERROR)
            val errOutcome = VerifiedOutcome.PrerequisiteFailureOutcome(
                actionId = actId,
                sessionId = sessionId,
                turnId = turn.turnId,
                resultingState = ConversationState.ERROR,
                failureCode = failCode,
                detail = detailMsg
            )
            lastCompletedTurnRuntime = turn
            recordDiagnosticErrorLocked(
                stage = "${_uiState.value.state.wireLabel}_WATCHDOG",
                errorCode = failCode,
                rawSummary = detailMsg,
                sessionId = sessionId,
                turnId = turn.turnId,
                generationWireId = turn.turnGen.wireId
            )
            syncTimingSnapshotsLocked()
            _uiState.update { state ->
                state.copy(
                    currentTurnId = turn.turnId,
                    activeConversationPhase = ActiveConversationPhase.ERROR,
                    activeConversationPhaseSequence = updatedPhaseSeq,
                    activeTurnGenerationId = null,
                    voiceTurnStateSequence = updatedSeq,
                    voiceTurnReadinessOrder = updatedReadiness,
                    lastCompletedTurnTrace = turn.structuredTraceEntries.toList(),
                    lastCompletedTurnEarliestMissingStep = missingStep,
                    structuredTurnTrace = turn.structuredTraceEntries.toList(),
                    activeTurnEarliestMissingStep = missingStep,
                    firstResponseRecoveryCount = if (isRecovered) state.firstResponseRecoveryCount + 1 else state.firstResponseRecoveryCount,
                    activeVoiceTurnErrorCode = failCode,
                    activeVoiceTurnErrorDetail = detailMsg,
                    lastVoiceTurnSummary = "ERROR [$failCode]: $detailMsg"
                )
            }
            applyVerifiedOutcome(
                sessionId = sessionId,
                turnId = turn.turnId,
                actionId = actId,
                newState = ConversationState.ERROR,
                roleLabel = "PROCESSING_WATCHDOG",
                outcome = errOutcome
            )
            deferredToResolve.complete(errOutcome)
        }
    }

    @Synchronized
    fun onTurnBoundPlaybackCallback(event: PlaybackCallbackEvent): Boolean {
        val current = _uiState.value
        val turn = activeTurn
        val activePlaybackGen = turn?.playbackGen
        val activeTurnGen = turn?.turnGen
        val matchesSession = event.generationId.sessionId == current.sessionId
        val matchesTurn = turn != null &&
            !turn.closed &&
            event.generationId.turnId == current.currentTurnId &&
            (activeTurnGen == null || activeTurnGen.turnIdValue == event.generationId.turnId.value)
        val matchesPlaybackGen = activePlaybackGen != null && activePlaybackGen == event.generationId
        if (!matchesSession || !matchesTurn || !matchesPlaybackGen || isDeliberatelyPaused || isDeliberatelyStopped) {
            logTurnLifecycleEventLocked(
                "STALE_CALLBACK_DROPPED: Dropped playback ${event::class.java.simpleName} for ${event.generationId.wireId} " +
                    "(activeTurn=${current.currentTurnId.value}, activePlayGen=${activePlaybackGen?.wireId ?: "none"})"
            )
            _uiState.update { state ->
                state.copy(staleTurnCallbacksDropped = state.staleTurnCallbacksDropped + 1)
            }
            playbackAdapter.onPlaybackCallback(event)
            return false
        }
        val accepted = playbackAdapter.onPlaybackCallback(event)
        syncPlaybackState()
        if (accepted && !turn.closed) {
            when (event) {
                is PlaybackCallbackEvent.BytesAcceptedByHardware -> {
                    if (event.totalBytesAccepted > 0L) {
                        val nowMs = monotonicClockMs()
                        turn.tLastPlaybackProgressMs = maxOf(turn.tLastPlaybackProgressMs ?: 0L, nowMs)
                        if (turn.tFirstHardwareWriteMs == null) {
                            turn.tFirstHardwareWriteMs = nowMs
                            syncTimingSnapshotsLocked()
                        }
                        transitionToSpeakingLocked(turn, activePlaybackGen)
                    }
                }
                is PlaybackCallbackEvent.PlaybackFailed -> {
                    val code = when {
                        event.errorCode == "UNSUPPORTED_AUDIO_MIME" -> "UNSUPPORTED_AUDIO_MIME"
                        event.reason.startsWith("PLAYBACK_TRACK_INIT_FAILED") -> "AUDIO_PLAYBACK_WRITE_FAILED"
                        event.errorCode.isNotBlank() -> event.errorCode
                        else -> "AUDIO_TRACK_WRITE_FAILED"
                    }
                    failActiveVoiceTurnLocked(
                        failureCode = code,
                        detail = "Native-audio playback failed [$code] on ${turn.turnGen.turnIdValue}: ${event.reason}",
                        expectedSessionId = turn.turnGen.sessionId,
                        expectedTurnSeq = turn.turnSeq,
                        expectedTurnGen = turn.turnGen,
                        expectedPlaybackGen = activePlaybackGen
                    )
                }
                else -> Unit
            }
        }
        return accepted
    }

    private fun transitionToSpeakingLocked(
        turn: TurnRuntimeState,
        playbackGen: PlaybackGenerationId
    ) {
        if (turn.closed || playbackAdapter.state.value.bytesAcceptedByHardware <= 0L) {
            return
        }
        val nowMs = monotonicClockMs()
        turn.tLastPlaybackProgressMs = maxOf(turn.tLastPlaybackProgressMs ?: 0L, nowMs)
        if (turn.tFirstHardwareWriteMs == null) {
            turn.tFirstHardwareWriteMs = nowMs
            syncTimingSnapshotsLocked()
        }
        appendTurnEventLocked("AUDIO_TRACK_WRITES")
        appendFirstTurnLifecycleLocked("PLAYBACK")
        val currentState = _uiState.value.state
        if (currentState == ConversationState.LISTENING ||
            currentState == ConversationState.PROCESSING ||
            currentState == ConversationState.THINKING
        ) {
            stopTurnMicrophoneLocked(
                reason = "Playback started (SPEAKING)",
                cancelQueuedMicSends = true,
                turnForTrace = turn
            )
            val updatedSeq = appendTurnStateLocked(ConversationState.SPEAKING)
            val updatedReadiness = appendReadinessOrderLocked("SPEAKING")
            val updatedPhaseSeq = appendActiveConversationPhaseLocked(ActiveConversationPhase.SPEAKING)
            val speakDetail =
                "SPEAKING (${playbackGen.wireId}): Streaming Gemini native-audio PCM response through Android AudioTrack (${playbackAdapter.state.value.totalFramesWrittenToHardware} frames accepted; half-duplex mic disabled)..."
            val speakOutcome = VerifiedOutcome.StateTransitionVerified(
                actionId = idGenerator.nextActionId("speaking"),
                sessionId = _uiState.value.sessionId,
                turnId = _uiState.value.currentTurnId,
                previousState = currentState,
                resultingState = ConversationState.SPEAKING,
                detail = speakDetail
            )
            _uiState.update { state ->
                state.copy(
                    activeConversationPhase = ActiveConversationPhase.SPEAKING,
                    activeConversationPhaseSequence = updatedPhaseSeq,
                    voiceTurnStateSequence = updatedSeq,
                    voiceTurnReadinessOrder = updatedReadiness,
                    structuredTurnTrace = turn.structuredTraceEntries.toList(),
                    activeTurnEarliestMissingStep = turn.earliestMissingStep(),
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
    }

    fun onSessionAndTurnScopedEvent(
        sessionId: SessionId,
        turnGenerationId: TurnGenerationId,
        event: LiveSocketCallbackEvent
    ): Boolean {
        synchronized(this) {
            val currentSessionId = _uiState.value.sessionId
            val currentTurnGen = activeTurn?.takeIf { !it.closed }?.turnGen
            val isAlreadyCompletedOrInFlightDuplicate =
                event is LiveSocketCallbackEvent.ServerTurnCompleted &&
                    (handledCompletionTurnGenIds.contains(turnGenerationId.wireId) ||
                        inFlightCompletionTurnGenIds.contains(turnGenerationId.wireId))

            if (currentSessionId != sessionId ||
                currentTurnGen == null ||
                currentTurnGen != turnGenerationId ||
                currentTurnGen.sessionId != sessionId ||
                isAlreadyCompletedOrInFlightDuplicate
            ) {
                logTurnLifecycleEventLocked(
                    "STALE_CALLBACK_DROPPED: Dropped ${event::class.simpleName} bound to ${turnGenerationId.wireId} " +
                        "(activeTurnGen=${currentTurnGen?.wireId ?: "none"})."
                )
                _uiState.update { state ->
                    state.copy(staleTurnCallbacksDropped = state.staleTurnCallbacksDropped + 1)
                }
                return false
            }
        }
        return onLiveSocketCallback(event)
    }

    fun onTurnScopedServerEvent(
        turnGenerationId: TurnGenerationId,
        event: LiveSocketCallbackEvent
    ): Boolean {
        return onSessionAndTurnScopedEvent(
            sessionId = turnGenerationId.sessionId,
            turnGenerationId = turnGenerationId,
            event = event
        )
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

    private fun onServerSocketEvent(event: LiveSocketCallbackEvent) {
        when (event) {
            is LiveSocketCallbackEvent.ServerTranscriptionReceived -> {
                synchronized(this) {
                    val turn = activeTurn
                    if (turn == null || turn.closed || isDeliberatelyPaused || isDeliberatelyStopped) {
                        logTurnLifecycleEventLocked("STALE_CALLBACK_DROPPED: Dropped ServerTranscriptionReceived with no active turn.")
                        _uiState.update { state ->
                            state.copy(staleTurnCallbacksDropped = state.staleTurnCallbacksDropped + 1)
                        }
                        return
                    }
                    val eventAtMs = monotonicClockMs()
                    val liveGenWire = liveAdapter.state.value.activeGeneration?.wireId
                    if (!event.inputTranscript.isNullOrBlank()) {
                        if (turn.tServerInputAckMs == null) {
                            turn.tServerInputAckMs = eventAtMs
                        }
                        turn.recordStep(
                            step = TurnTraceStep.SERVER_INPUT_TRANSCRIPTION,
                            timestampLabel = clockProvider(),
                            liveGenWireId = liveGenWire
                        )
                    }
                    if (!event.outputTranscript.isNullOrBlank()) {
                        if (turn.endOfTurnSubmissionInFlight && _uiState.value.state == ConversationState.LISTENING) {
                            turn.endOfTurnSubmitted = true
                            promoteToProcessingLocked(
                                turn = turn,
                                sessionId = turn.sessionId,
                                turnId = turn.turnId,
                                turnGen = turn.turnGen
                            )
                        }
                        if (turn.tFirstResponseMs == null) {
                            turn.tFirstResponseMs = eventAtMs
                        }
                        turn.tLastServerProgressMs = eventAtMs
                        turn.serverResponseStarted = true
                        if (!turn.hasStep(TurnTraceStep.SERVER_RESPONSE_START)) {
                            turn.recordStep(
                                step = TurnTraceStep.SERVER_RESPONSE_START,
                                timestampLabel = clockProvider(),
                                liveGenWireId = liveGenWire,
                                detail = "outputTranscription"
                            )
                        }
                        appendTurnEventLocked("SERVER_RESPONSE_START")
                        appendFirstTurnLifecycleLocked("SERVER_RESPONSE")
                    }
                    val current = _uiState.value
                    val updatedUser = mergeTranscriptFragment(current.currentTurnUserTranscript, event.inputTranscript)
                    val updatedModel = mergeTranscriptFragment(current.currentTurnModelTranscript, event.outputTranscript)
                    if (updatedModel.isNotBlank()) {
                        turn.redactedOutputWordCount = updatedModel.trim()
                            .split(Regex("\\s+"))
                            .count { it.isNotEmpty() }
                    }
                    syncTimingSnapshotsLocked()
                    val summaryParts = buildList {
                        if (updatedUser.isNotBlank()) add("Heard: \"$updatedUser\"")
                        if (updatedModel.isNotBlank()) add("Gemini: \"$updatedModel\"")
                    }
                    _uiState.update { state ->
                        state.copy(
                            currentTurnUserTranscript = updatedUser,
                            currentTurnModelTranscript = updatedModel,
                            structuredTurnTrace = turn.structuredTraceEntries.toList(),
                            activeTurnEarliestMissingStep = turn.earliestMissingStep(),
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
                val turn: TurnRuntimeState
                val turnSeq: Long
                val turnGen: TurnGenerationId
                val playbackGen: PlaybackGenerationId
                synchronized(this) {
                    val currentTurn = activeTurn
                    val currentPlaybackGen = currentTurn?.playbackGen
                    if (currentTurn == null ||
                        currentTurn.closed ||
                        currentPlaybackGen == null ||
                        isDeliberatelyPaused ||
                        isDeliberatelyStopped
                    ) {
                        logTurnLifecycleEventLocked("STALE_CALLBACK_DROPPED: Dropped ServerAudioChunkReceived for closed/inactive turn.")
                        _uiState.update { state ->
                            state.copy(staleTurnCallbacksDropped = state.staleTurnCallbacksDropped + 1)
                        }
                        return
                    }
                    turn = currentTurn
                    turnSeq = turn.turnSeq
                    turnGen = turn.turnGen
                    playbackGen = currentPlaybackGen

                    val audioChunkAtMs = monotonicClockMs()
                    val liveGenWire = liveAdapter.state.value.activeGeneration?.wireId
                    if (_uiState.value.state == ConversationState.LISTENING) {
                        cancelInactivityTimerLocked()
                        if (turn.endOfTurnSubmissionInFlight) {
                            turn.endOfTurnSubmitted = true
                        } else {
                            stopTurnMicrophoneLocked(
                                reason = "Server began native-audio response",
                                cancelQueuedMicSends = true,
                                turnForTrace = turn
                            )
                        }
                        promoteToProcessingLocked(
                            turn = turn,
                            sessionId = turn.sessionId,
                            turnId = turn.turnId,
                            turnGen = turn.turnGen
                        )
                    }

                    if (turn.tFirstResponseMs == null) {
                        turn.tFirstResponseMs = audioChunkAtMs
                    }
                    if (turn.tFirstAudioReceivedMs == null) {
                        turn.tFirstAudioReceivedMs = audioChunkAtMs
                    }
                    turn.tLastServerProgressMs = audioChunkAtMs
                    turn.tAllReceivedAudioPlayedMs = null
                    turn.serverAudioChunksReceivedCount += 1
                    turn.serverResponseStarted = true
                    if (!turn.hasStep(TurnTraceStep.SERVER_RESPONSE_START)) {
                        turn.recordStep(
                            step = TurnTraceStep.SERVER_RESPONSE_START,
                            timestampLabel = clockProvider(),
                            liveGenWireId = liveGenWire,
                            detail = "audioChunk"
                        )
                    }
                    if (!turn.hasStep(TurnTraceStep.FIRST_AUDIO_CHUNK)) {
                        turn.recordStep(
                            step = TurnTraceStep.FIRST_AUDIO_CHUNK,
                            timestampLabel = clockProvider(),
                            liveGenWireId = liveGenWire,
                            detail = "bytes=${event.pcmBytes.size}"
                        )
                    }
                    if (turn.endOfTurnSubmitted &&
                        !turn.firstResponseWatchdogArmed &&
                        !turn.hasStep(TurnTraceStep.SERVER_TURN_COMPLETE)
                    ) {
                        armFirstResponseWatchdogLocked(turn)
                    }
                    syncTimingSnapshotsLocked()
                    appendTurnEventLocked("SERVER_RESPONSE_START")
                    appendTurnEventLocked("AUDIO_CHUNKS_RECEIVED")
                    appendFirstTurnLifecycleLocked("SERVER_RESPONSE")
                    _uiState.update { state ->
                        state.copy(
                            structuredTurnTrace = turn.structuredTraceEntries.toList(),
                            activeTurnEarliestMissingStep = turn.earliestMissingStep()
                        )
                    }
                }

                // Synchronous non-blocking FIFO enqueue into LiveAudioPlaybackAdapter so the
                // receive collector never builds a fragile coroutine join chain or blocks on AudioTrack.write.
                val writeOutcome = playbackAdapter.enqueueVerifiedPcmChunk(
                    generationId = playbackGen,
                    rawMimeType = event.mimeType,
                    pcmBytes = event.pcmBytes,
                    onEvent = { pbEvent ->
                        onTurnBoundPlaybackCallback(pbEvent)
                    }
                )
                syncPlaybackState()

                synchronized(this) {
                    if (activeTurn !== turn ||
                        turn.closed ||
                        turn.turnSeq != turnSeq ||
                        turn.turnGen != turnGen ||
                        turn.playbackGen != playbackGen ||
                        _uiState.value.sessionId != turnGen.sessionId ||
                        isDeliberatelyPaused ||
                        isDeliberatelyStopped
                    ) {
                        logTurnLifecycleEventLocked(
                            "STALE_CALLBACK_DROPPED: Ignored late enqueue outcome for ${turnGen.wireId}/${playbackGen.wireId} " +
                                "(active=${activeTurn?.turnGen?.wireId ?: "none"}/${activeTurn?.playbackGen?.wireId ?: "none"})."
                        )
                        _uiState.update { state ->
                            state.copy(staleTurnCallbacksDropped = state.staleTurnCallbacksDropped + 1)
                        }
                        return
                    }
                    if (writeOutcome is PcmChunkWriteOutcome.DroppedStaleGeneration) {
                        logTurnLifecycleEventLocked(
                            "STALE_CALLBACK_DROPPED: Dropped stale playback enqueue outcome for ${playbackGen.wireId} " +
                                "(active=${writeOutcome.activeGenerationId?.wireId ?: "none"})."
                        )
                        _uiState.update { state ->
                            state.copy(staleTurnCallbacksDropped = state.staleTurnCallbacksDropped + 1)
                        }
                        return
                    }
                    when (writeOutcome) {
                        is PcmChunkWriteOutcome.WrittenToHardware -> {
                            syncTimingSnapshotsLocked()
                            if (playbackAdapter.state.value.bytesAcceptedByHardware > 0L) {
                                transitionToSpeakingLocked(turn, playbackGen)
                            }
                        }
                        is PcmChunkWriteOutcome.RejectedMime -> {
                            failActiveVoiceTurnLocked(
                                failureCode = "UNSUPPORTED_AUDIO_MIME",
                                detail = "Native-audio playback failed [UNSUPPORTED_AUDIO_MIME] on ${turnGen.turnIdValue}: ${writeOutcome.reason}",
                                expectedSessionId = turnGen.sessionId,
                                expectedTurnSeq = turnSeq,
                                expectedTurnGen = turnGen,
                                expectedPlaybackGen = playbackGen
                            )
                        }
                        is PcmChunkWriteOutcome.FailedWrite -> {
                            val code = if (writeOutcome.reason.startsWith("PLAYBACK_TRACK_INIT_FAILED")) {
                                "AUDIO_PLAYBACK_WRITE_FAILED"
                            } else {
                                "AUDIO_TRACK_WRITE_FAILED"
                            }
                            failActiveVoiceTurnLocked(
                                failureCode = code,
                                detail = "Native-audio playback failed [$code] on ${turnGen.turnIdValue}: ${writeOutcome.reason}",
                                expectedSessionId = turnGen.sessionId,
                                expectedTurnSeq = turnSeq,
                                expectedTurnGen = turnGen,
                                expectedPlaybackGen = playbackGen
                            )
                        }
                        is PcmChunkWriteOutcome.DroppedStaleGeneration -> Unit
                    }
                }
            }

            is LiveSocketCallbackEvent.ServerTurnCompleted -> {
                val turn: TurnRuntimeState
                val turnSeq: Long
                val turnGen: TurnGenerationId
                val playbackGen: PlaybackGenerationId
                val writeJobsSnapshot: List<Job>
                synchronized(this) {
                    val currentTurn = activeTurn
                    val currentPlaybackGen = currentTurn?.playbackGen
                    if (currentTurn == null ||
                        currentTurn.closed ||
                        currentPlaybackGen == null ||
                        _uiState.value.sessionId != currentTurn.turnGen.sessionId ||
                        isDeliberatelyPaused ||
                        isDeliberatelyStopped ||
                        (!currentTurn.endOfTurnSubmitted && !currentTurn.endOfTurnSubmissionInFlight && !currentTurn.serverResponseStarted) ||
                        handledCompletionTurnGenIds.contains(currentTurn.turnGen.wireId) ||
                        !inFlightCompletionTurnGenIds.add(currentTurn.turnGen.wireId)
                    ) {
                        logTurnLifecycleEventLocked("STALE_CALLBACK_DROPPED: Dropped ServerTurnCompleted for ${currentTurn?.turnGen?.wireId ?: "none"}.")
                        _uiState.update { state ->
                            state.copy(staleTurnCallbacksDropped = state.staleTurnCallbacksDropped + 1)
                        }
                        return
                    }
                    turn = currentTurn
                    turnSeq = turn.turnSeq
                    turnGen = turn.turnGen
                    playbackGen = currentPlaybackGen
                    val completedAtMs = monotonicClockMs()
                    if (turn.tFirstResponseMs == null) {
                        turn.tFirstResponseMs = completedAtMs
                    }
                    turn.serverResponseStarted = true
                    turn.cancelWatchdog()

                    val liveGenWire = liveAdapter.state.value.activeGeneration?.wireId
                    if (turn.endOfTurnSubmissionInFlight && _uiState.value.state == ConversationState.LISTENING) {
                        turn.endOfTurnSubmitted = true
                        promoteToProcessingLocked(
                            turn = turn,
                            sessionId = turn.sessionId,
                            turnId = turn.turnId,
                            turnGen = turn.turnGen
                        )
                    }
                    turn.recordStep(
                        step = TurnTraceStep.SERVER_TURN_COMPLETE,
                        timestampLabel = clockProvider(),
                        liveGenWireId = liveGenWire,
                        detail = "interrupted=${event.interrupted}"
                    )
                    syncTimingSnapshotsLocked()
                    cancelInactivityTimerLocked()
                    if (_uiState.value.state == ConversationState.LISTENING || audioCapture.state.value.isCapturing) {
                        stopTurnMicrophoneLocked(
                            reason = "Server turn completed",
                            cancelQueuedMicSends = true,
                            turnForTrace = turn
                        )
                    }
                    writeJobsSnapshot = turn.writeJobs.toList()
                }

                val drainJob = turnScope.launch(turn.parentJob) {
                    try {
                        for (job in writeJobsSnapshot) {
                            job.join()
                        }
                        val stillActiveBeforeDrain = synchronized(this@ConversationCoordinator) {
                            activeTurn === turn &&
                                !turn.closed &&
                                turn.turnSeq == turnSeq &&
                                turn.turnGen == turnGen &&
                                turn.playbackGen == playbackGen &&
                                _uiState.value.sessionId == turnGen.sessionId &&
                                !isDeliberatelyPaused &&
                                !isDeliberatelyStopped
                        }
                        if (!stillActiveBeforeDrain) {
                            synchronized(this@ConversationCoordinator) {
                                logTurnLifecycleEventLocked(
                                    "STALE_CALLBACK_DROPPED: Dropped drain await for ${turnGen.wireId}/${playbackGen.wireId} before drain start."
                                )
                                _uiState.update { state ->
                                    state.copy(staleTurnCallbacksDropped = state.staleTurnCallbacksDropped + 1)
                                }
                            }
                            return@launch
                        }

                        val preDrainSnap = playbackAdapter.state.value
                        val isSilentNoAudioTurnComplete =
                            !event.interrupted &&
                                synchronized(this@ConversationCoordinator) { turn.serverAudioChunksReceivedCount == 0 } &&
                                preDrainSnap.chunksReceived == 0 &&
                                preDrainSnap.framesReceivedFromServer == 0L &&
                                preDrainSnap.lastError == null

                        val drained = if (isSilentNoAudioTurnComplete) {
                            playbackAdapter.stopAndFlush(
                                reason = "Server completed turn with 0 audio chunks (no-response/noise ignored)",
                                expectedGenerationId = playbackGen
                            )
                            false
                        } else if (preDrainSnap.activeGeneration == playbackGen) {
                            playbackAdapter.awaitHardwareDrainAfterServerTurnComplete(playbackGen)
                        } else {
                            false
                        }
                        syncPlaybackState()

                        if (event.interrupted) {
                            val sessionForRecovery: SessionId?
                            val shouldRecoverSession: Boolean
                            synchronized(this@ConversationCoordinator) {
                                if (activeTurn !== turn ||
                                    turn.closed ||
                                    turn.turnSeq != turnSeq ||
                                    turn.turnGen != turnGen ||
                                    turn.playbackGen != playbackGen ||
                                    _uiState.value.sessionId != turnGen.sessionId ||
                                    isDeliberatelyPaused ||
                                    isDeliberatelyStopped
                                ) {
                                    logTurnLifecycleEventLocked("STALE_CALLBACK_DROPPED: Dropped late interruption callback for ${turnGen.wireId}.")
                                    _uiState.update { state ->
                                        state.copy(staleTurnCallbacksDropped = state.staleTurnCallbacksDropped + 1)
                                    }
                                    return@launch
                                }
                                recordHandledCompletionTurnGenLocked(turnGen.wireId)
                                if (drained && playbackAdapter.state.value.isPlaybackDrained) {
                                    appendTurnEventLocked("PLAYBACK_DRAINED")
                                }
                                appendTurnEventLocked("SERVER_TURN_INTERRUPTED")
                                val current = _uiState.value
                                shouldRecoverSession = current.interruptionRecoveryCount < 1
                                sessionForRecovery = if (shouldRecoverSession) current.sessionId else null
                            }

                            if (shouldRecoverSession && sessionForRecovery != null) {
                                liveAdapter.recreateSessionAfterInterruption(
                                    sessionId = sessionForRecovery,
                                    reason = "Single clean LiveSession recovery after SERVER_TURN_INTERRUPTED"
                                )
                            } else {
                                needsSessionRecreationBeforeNextTurn = true
                            }

                            synchronized(this@ConversationCoordinator) {
                                if (activeTurn !== turn ||
                                    turn.closed ||
                                    turn.turnSeq != turnSeq ||
                                    turn.turnGen != turnGen ||
                                    turn.playbackGen != playbackGen
                                ) {
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
                                    detail = detailMsg,
                                    expectedSessionId = turnGen.sessionId,
                                    expectedTurnSeq = turnSeq,
                                    expectedTurnGen = turnGen,
                                    expectedPlaybackGen = playbackGen
                                )
                            }
                            return@launch
                        }

                        var readyOutcome: VerifiedOutcome.VoiceTurnOutcome? = null
                        var recoveryJobToStart: Job? = null

                        synchronized(this@ConversationCoordinator) {
                            if (activeTurn !== turn ||
                                turn.closed ||
                                turn.turnSeq != turnSeq ||
                                turn.turnGen != turnGen ||
                                turn.playbackGen != playbackGen ||
                                _uiState.value.sessionId != turnGen.sessionId ||
                                isDeliberatelyPaused ||
                                isDeliberatelyStopped ||
                                _uiState.value.state == ConversationState.PAUSED ||
                                _uiState.value.state == ConversationState.STOPPED ||
                                handledCompletionTurnGenIds.contains(turnGen.wireId)
                            ) {
                                logTurnLifecycleEventLocked(
                                    "STALE_CALLBACK_DROPPED: Dropped late completion for ${turnGen.wireId}/${playbackGen.wireId} " +
                                        "(active=${activeTurn?.turnGen?.wireId ?: "none"}/${activeTurn?.playbackGen?.wireId ?: "none"})."
                                )
                                _uiState.update { state ->
                                    state.copy(staleTurnCallbacksDropped = state.staleTurnCallbacksDropped + 1)
                                }
                                return@launch
                            }
                            recordHandledCompletionTurnGenLocked(turnGen.wireId)
                            appendTurnEventLocked("SERVER_TURN_COMPLETE")
                            val playSnap = playbackAdapter.state.value
                            if (isSilentNoAudioTurnComplete) {
                                appendTurnEventLocked("NO_AUDIO_RESPONSE_IGNORED")
                                appendTurnEventLocked("READY")
                                consecutiveFirstResponseTimeouts = 0
                                turn.completionHandled = true
                                turn.drainJob = null
                                lastCompletedTurnRuntime = turn
                                activeTurn = null
                                liveAdapter.setAudioPlaybackClaimed(false)
                                recordTurnOutcomeLocked(
                                    turn = turn,
                                    category = Phase07TurnOutcomeCategory.NO_SPEECH_REJECTED,
                                    outcomeCode = "NO_RESPONSE_TURN_IGNORED"
                                )
                                syncTimingSnapshotsLocked()

                                val current = _uiState.value
                                val updatedSeq = appendTurnStateLocked(ConversationState.READY)
                                val updatedReadiness = appendReadinessOrderLocked("READY")
                                val noAudioSummary =
                                    "READY: Server completed ${current.currentTurnId.value} with 0 audio chunks (unintelligible/noise ignored); returning to listening."
                                readyOutcome = VerifiedOutcome.VoiceTurnOutcome(
                                    actionId = idGenerator.nextActionId("turn_no_response_ignored"),
                                    sessionId = current.sessionId,
                                    turnId = current.currentTurnId,
                                    resultingState = ConversationState.READY,
                                    stateSequence = updatedSeq,
                                    micFramesSent = current.voiceTurnFramesSent,
                                    playbackFramesDrained = 0L,
                                    isPlaybackDrained = false,
                                    code = "NO_RESPONSE_TURN_IGNORED",
                                    detail = noAudioSummary
                                )
                                val wantsAutoRestore =
                                    _uiState.value.isActiveConversationEnabled &&
                                        !isDeliberatelyPaused &&
                                        !isDeliberatelyStopped
                                val sessionReadyNow =
                                    liveAdapter.isSessionReadyForAudio() && !needsSessionRecreationBeforeNextTurn

                                _uiState.update { state ->
                                    state.copy(
                                        activeConversationPhase = ActiveConversationPhase.IDLE,
                                        activeTurnGenerationId = null,
                                        voiceTurnStateSequence = updatedSeq,
                                        voiceTurnReadinessOrder = updatedReadiness,
                                        lastCompletedTurnTrace = turn.structuredTraceEntries.toList(),
                                        lastCompletedTurnEarliestMissingStep = turn.earliestMissingStep(),
                                        structuredTurnTrace = turn.structuredTraceEntries.toList(),
                                        activeTurnEarliestMissingStep = turn.earliestMissingStep(),
                                        lastVoiceTurnSummary = noAudioSummary
                                    )
                                }
                                applyVerifiedOutcome(
                                    sessionId = current.sessionId,
                                    turnId = current.currentTurnId,
                                    actionId = readyOutcome!!.actionId,
                                    newState = ConversationState.READY,
                                    roleLabel = "VOICE_TURN_COORDINATOR",
                                    outcome = readyOutcome!!
                                )

                                if (wantsAutoRestore && sessionReadyNow) {
                                    autoRestoreCaptureForNextTurnLocked(
                                        previousTurnId = current.currentTurnId,
                                        completedTurn = turn,
                                        allowRestoreAfterTimeoutRecovery = true
                                    )
                                } else if (wantsAutoRestore) {
                                    recoveryJobToStart = prepareNextTurnSessionRecoveryLocked(
                                        sessionId = current.sessionId,
                                        previousTurnId = current.currentTurnId,
                                        completedTurn = turn
                                    )
                                } else {
                                    logTurnLifecycleEventLocked(
                                        "TurnNoAudioComplete[${turn.turnId.value}]: ${turn.structuredTraceEntries.joinToString(" -> ")}"
                                    )
                                }
                                return@synchronized
                            }
                            if (!drained || !playSnap.isPlaybackDrained || playSnap.totalFramesWrittenToHardware <= 0L) {
                                val failCode = when {
                                    playSnap.totalFramesWrittenToHardware <= 0L ->
                                        "AUDIO_TRACK_WRITE_FAILED"
                                    playSnap.lastError?.startsWith("AUDIO_TRACK_WRITE_FAILED") == true ->
                                        "AUDIO_TRACK_WRITE_FAILED"
                                    playSnap.lastError?.startsWith("PLAYBACK_TRACK_INIT_FAILED") == true ->
                                        "AUDIO_PLAYBACK_WRITE_FAILED"
                                    playSnap.lastError?.startsWith("HARDWARE_DRAIN_INCOMPLETE") == true ->
                                        "HARDWARE_DRAIN_INCOMPLETE"
                                    !playSnap.lastError.isNullOrBlank() ->
                                        "HARDWARE_DRAIN_INCOMPLETE"
                                    else ->
                                        "HARDWARE_DRAIN_INCOMPLETE"
                                }
                                failActiveVoiceTurnLocked(
                                    failureCode = failCode,
                                    detail = "Audio playback failed [$failCode] on ${turnGen.turnIdValue}: ${playSnap.lastError ?: playSnap.statusDetail}",
                                    expectedSessionId = turnGen.sessionId,
                                    expectedTurnSeq = turnSeq,
                                    expectedTurnGen = turnGen,
                                    expectedPlaybackGen = playbackGen
                                )
                                return@launch
                            }

                            val drainedAtMs = monotonicClockMs()
                            if (turn.tPlaybackDrainedMs == null) {
                                turn.tPlaybackDrainedMs = drainedAtMs
                            }
                            val liveGenWire = liveAdapter.state.value.activeGeneration?.wireId
                            turn.recordStep(
                                step = TurnTraceStep.AUDIOTRACK_FRAMES_DRAINED,
                                timestampLabel = clockProvider(),
                                liveGenWireId = liveGenWire,
                                detail = "head=${playSnap.hardwarePlaybackHeadFrames}/${playSnap.totalFramesWrittenToHardware}"
                            )
                            appendTurnEventLocked("PLAYBACK_DRAINED")
                            appendTurnEventLocked("READY")
                            appendFirstTurnLifecycleLocked("PLAYBACK")

                            consecutiveFirstResponseTimeouts = 0

                            turn.completionHandled = true
                            turn.drainJob = null
                            lastCompletedTurnRuntime = turn
                            activeTurn = null
                            liveAdapter.setAudioPlaybackClaimed(false)
                            recordTurnOutcomeLocked(
                                turn = turn,
                                category = Phase07TurnOutcomeCategory.COMPLETED,
                                outcomeCode = "VOICE_TURN_COMPLETED_DRAINED"
                            )
                            syncTimingSnapshotsLocked()

                            val current = _uiState.value
                            val updatedSeq = appendTurnStateLocked(ConversationState.READY)
                            val updatedReadiness = appendReadinessOrderLocked("READY")
                            val newCompletedCount = current.completedTurnsInSession + 1
                            val dialogueTurn = SessionDialogueTurn(
                                sessionId = current.sessionId,
                                turnId = current.currentTurnId,
                                turnGenerationWireId = turnGen.wireId,
                                turnNumberInSession = newCompletedCount,
                                userTranscript = current.currentTurnUserTranscript.ifBlank {
                                    "[Spoken audio ${current.voiceTurnFramesSent} frames]"
                                },
                                modelTranscript = current.currentTurnModelTranscript.ifBlank {
                                    "[Gemini audio ${playSnap.hardwarePlaybackHeadFrames} frames]"
                                }
                            )
                            val boundedDialogueHistory =
                                (current.sessionDialogueHistory + dialogueTurn).takeLast(maxSessionDialogueTurns)

                            val transcriptTail = buildList {
                                if (current.currentTurnUserTranscript.isNotBlank()) {
                                    add("Heard: \"${current.currentTurnUserTranscript}\"")
                                }
                                if (current.currentTurnModelTranscript.isNotBlank()) {
                                    add("Gemini: \"${current.currentTurnModelTranscript}\"")
                                }
                            }.joinToString(" | ")
                            val readySummary = buildString {
                                append("READY: Voice turn ${current.currentTurnId.value} (#$newCompletedCount) completed & hardware playback fully drained (${playSnap.hardwarePlaybackHeadFrames}/${playSnap.totalFramesWrittenToHardware} accepted frames at ${playSnap.verifiedSampleRateHz}Hz).")
                                if (transcriptTail.isNotBlank()) {
                                    append(" ")
                                    append(transcriptTail)
                                }
                            }
                            readyOutcome = VerifiedOutcome.VoiceTurnOutcome(
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
                            val wantsAutoRestore =
                                _uiState.value.isActiveConversationEnabled &&
                                    !isDeliberatelyPaused &&
                                    !isDeliberatelyStopped
                            val sessionReadyNow =
                                liveAdapter.isSessionReadyForAudio() && !needsSessionRecreationBeforeNextTurn

                            _uiState.update { state ->
                                state.copy(
                                    activeConversationPhase = ActiveConversationPhase.IDLE,
                                    activeTurnGenerationId = null,
                                    completedTurnsInSession = newCompletedCount,
                                    sessionDialogueHistory = boundedDialogueHistory,
                                    voiceTurnStateSequence = updatedSeq,
                                    voiceTurnReadinessOrder = updatedReadiness,
                                    lastCompletedTurnTrace = turn.structuredTraceEntries.toList(),
                                    lastCompletedTurnEarliestMissingStep = turn.earliestMissingStep(),
                                    structuredTurnTrace = turn.structuredTraceEntries.toList(),
                                    activeTurnEarliestMissingStep = turn.earliestMissingStep(),
                                    lastVoiceTurnSummary = readySummary
                                )
                            }
                            applyVerifiedOutcome(
                                sessionId = current.sessionId,
                                turnId = current.currentTurnId,
                                actionId = readyOutcome!!.actionId,
                                newState = ConversationState.READY,
                                roleLabel = "VOICE_TURN_COORDINATOR",
                                outcome = readyOutcome!!
                            )

                            if (wantsAutoRestore && sessionReadyNow) {
                                autoRestoreCaptureForNextTurnLocked(
                                    previousTurnId = current.currentTurnId,
                                    completedTurn = turn
                                )
                            } else if (wantsAutoRestore) {
                                recoveryJobToStart = prepareNextTurnSessionRecoveryLocked(
                                    sessionId = current.sessionId,
                                    previousTurnId = current.currentTurnId,
                                    completedTurn = turn
                                )
                            } else {
                                logTurnLifecycleEventLocked(
                                    "TurnComplete[${turn.turnId.value}]: ${turn.structuredTraceEntries.joinToString(" -> ")}"
                                )
                            }
                        }

                        turn.dispose(cancelAllJobs = false)
                        if (recoveryJobToStart != null) {
                            recoveryJobToStart!!.start()
                            try {
                                recoveryJobToStart!!.join()
                            } catch (_: CancellationException) {
                            }
                        }
                        readyOutcome?.let { turn.completionDeferred.complete(it) }
                    } finally {
                        synchronized(this@ConversationCoordinator) {
                            inFlightCompletionTurnGenIds.remove(turnGen.wireId)
                            turn.drainJob = null
                            turn.parentJob.complete()
                        }
                    }
                }
                synchronized(this) {
                    if (activeTurn === turn && !turn.closed && turn.turnSeq == turnSeq) {
                        turn.drainJob = drainJob
                    }
                }
            }

            is LiveSocketCallbackEvent.SessionResumptionUpdated -> {
                synchronized(this) {
                    syncLiveState()
                    syncTimingSnapshotsLocked()
                }
            }

            is LiveSocketCallbackEvent.ServerGoAwayReceived -> {
                synchronized(this) {
                    needsSessionRecreationBeforeNextTurn = true
                    logTurnLifecycleEventLocked(
                        "SERVER_GO_AWAY_RECEIVED: timeLeftMs=${event.timeLeftMs} gen=${event.generationId.wireId}"
                    )
                    syncLiveState()
                    syncTimingSnapshotsLocked()
                }
            }

            else -> Unit
        }
    }

    private fun recordHandledCompletionTurnGenLocked(wireId: String) {
        if (handledCompletionTurnGenIds.size >= 128) {
            val first = handledCompletionTurnGenIds.iterator().next()
            handledCompletionTurnGenIds.remove(first)
        }
        handledCompletionTurnGenIds.add(wireId)
    }

    private fun logTurnLifecycleEventLocked(message: String) {
        try {
            android.util.Log.i("OmniVoiceTurn", DiagnosticRedactor.redact(message))
        } catch (_: Throwable) {
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

    private fun appendActiveConversationPhaseLocked(phase: ActiveConversationPhase): List<ActiveConversationPhase> {
        val existing = _uiState.value.activeConversationPhaseSequence
        val updated = if (existing.lastOrNull() == phase) {
            existing
        } else {
            (existing + phase).takeLast(64)
        }
        _uiState.update { state ->
            state.copy(
                activeConversationPhase = phase,
                activeConversationPhaseSequence = updated
            )
        }
        return updated
    }

    private fun stopTurnMicrophoneLocked(
        reason: String,
        cancelQueuedMicSends: Boolean = true,
        turnForTrace: TurnRuntimeState? = activeTurn
    ) {
        val turn = turnForTrace
        if (cancelQueuedMicSends && turn != null) {
            for (job in turn.micSendJobs) {
                job.cancel()
            }
            turn.micSendJobs.clear()
        }
        audioCapture.stopAndRelease(reason = reason, wipeEphemeral = true)
        if (turn != null && !turn.closed && !turn.hasStep(TurnTraceStep.AUDIO_CAPTURE_STOPPED)) {
            val liveGenWire = liveAdapter.state.value.activeGeneration?.wireId
            turn.recordStep(
                step = TurnTraceStep.AUDIO_CAPTURE_STOPPED,
                timestampLabel = clockProvider(),
                liveGenWireId = liveGenWire,
                detail = reason
            )
            _uiState.update { state ->
                state.copy(
                    structuredTurnTrace = turn.structuredTraceEntries.toList(),
                    activeTurnEarliestMissingStep = turn.earliestMissingStep()
                )
            }
        }
    }

    private fun appendTurnStateLocked(nextState: ConversationState): List<ConversationState> {
        val existing = _uiState.value.voiceTurnStateSequence
        return if (existing.lastOrNull() == nextState) {
            existing
        } else {
            existing + nextState
        }
    }

    private fun prepareNextTurnSessionRecoveryLocked(
        sessionId: SessionId,
        previousTurnId: TurnId,
        completedTurn: TurnRuntimeState
    ): Job? {
        val existingJob = activeNextTurnRecoveryJob
        if (existingJob != null && !existingJob.isCompleted && !existingJob.isCancelled) {
            logTurnLifecycleEventLocked(
                "SINGLE_FLIGHT_NEXT_TURN_RECOVERY: Recovery job already active for ${sessionId.value}; ignoring duplicate completion."
            )
            return null
        }

        val preparingSeq = appendTurnStateLocked(ConversationState.PREPARING)
        val preparingReadiness = appendReadinessOrderLocked("PREPARING")
        val preparingPhaseSeq = appendActiveConversationPhaseLocked(ActiveConversationPhase.PREPARING)
        val preparingDetail =
            "PREPARING: Gemini Live session temporarily unavailable after ${previousTurnId.value}; recovering session before restoring LISTENING..."
        val preparingOutcome = VerifiedOutcome.StateTransitionVerified(
            actionId = idGenerator.nextActionId("next_turn_preparing"),
            sessionId = sessionId,
            turnId = previousTurnId,
            previousState = ConversationState.READY,
            resultingState = ConversationState.PREPARING,
            detail = preparingDetail
        )
        _uiState.update { state ->
            state.copy(
                state = ConversationState.PREPARING,
                activeConversationPhase = ActiveConversationPhase.PREPARING,
                activeConversationPhaseSequence = preparingPhaseSeq,
                voiceTurnStateSequence = preparingSeq,
                voiceTurnReadinessOrder = preparingReadiness,
                lastVoiceTurnSummary = preparingDetail
            )
        }
        applyVerifiedOutcome(
            sessionId = sessionId,
            turnId = previousTurnId,
            actionId = preparingOutcome.actionId,
            newState = ConversationState.PREPARING,
            roleLabel = "ACTIVE_CONVERSATION",
            outcome = preparingOutcome
        )

        var createdJob: Job? = null
        val job = turnScope.launch(start = CoroutineStart.LAZY) {
            val needsExplicitRecreate = synchronized(this@ConversationCoordinator) {
                val flag = needsSessionRecreationBeforeNextTurn
                needsSessionRecreationBeforeNextTurn = false
                flag
            }
            val recoveredState = try {
                withContext(backgroundDispatcher) {
                    withTimeoutOrNull(connectionTimeoutMs) {
                        if (needsExplicitRecreate) {
                            liveAdapter.recreateSessionAfterInterruption(
                                sessionId = sessionId,
                                reason = "Proactive session rotation before next turn (ServerGoAway or interruption)"
                            )
                        } else {
                            liveAdapter.ensureConnectedSession(sessionId)
                        }
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                liveAdapter.state.value
            }

            synchronized(this@ConversationCoordinator) {
                if (activeNextTurnRecoveryJob === createdJob) {
                    activeNextTurnRecoveryJob = null
                }
                val currentAfter = _uiState.value
                val sameSession = currentAfter.sessionId == sessionId
                val validState =
                    currentAfter.state == ConversationState.READY ||
                        currentAfter.state == ConversationState.PREPARING
                val activeConvStillEnabled = currentAfter.isActiveConversationEnabled
                val notPausedOrStopped =
                    !isDeliberatelyPaused &&
                        !isDeliberatelyStopped &&
                        currentAfter.state != ConversationState.PAUSED &&
                        currentAfter.state != ConversationState.STOPPED
                val noNewerTurn = activeTurn == null || activeTurn?.closed == true

                if (!sameSession || !validState || !activeConvStillEnabled || !notPausedOrStopped || !noNewerTurn) {
                    logTurnLifecycleEventLocked(
                        "NEXT_TURN_RECOVERY_SKIPPED: Post-recovery invariant changed (sameSession=$sameSession, state=${currentAfter.state.wireLabel}, activeConv=$activeConvStillEnabled, notPausedOrStopped=$notPausedOrStopped, noNewerTurn=$noNewerTurn)."
                    )
                    return@launch
                }

                if (recoveredState != null && liveAdapter.isSessionReadyForAudio()) {
                    autoRestoreCaptureForNextTurnLocked(
                        previousTurnId = previousTurnId,
                        completedTurn = completedTurn,
                        allowRestoreAfterTimeoutRecovery = completedTurn.tPlaybackDrainedMs == null
                    )
                } else {
                    val rawDiagnostic =
                        recoveredState?.diagnostics?.redactedSummary?.takeIf { it.isNotBlank() }
                            ?: recoveredState?.statusDetail?.takeIf { it.isNotBlank() }
                            ?: liveAdapter.state.value.diagnostics.redactedSummary.takeIf { it.isNotBlank() }
                            ?: liveAdapter.state.value.statusDetail
                    val safeDiagnostic = DiagnosticRedactor.redact(rawDiagnostic)
                    val failureCode = "NEXT_TURN_SESSION_RECOVERY_FAILED"
                    val failureDetail =
                        "$failureCode: Could not restore Gemini Live session for next turn after ${previousTurnId.value} ($safeDiagnostic). Press Start to reconnect."
                    val errorSeq = appendTurnStateLocked(ConversationState.ERROR)
                    val errorReadiness = appendReadinessOrderLocked("ERROR")
                    val errorPhaseSeq = appendActiveConversationPhaseLocked(ActiveConversationPhase.ERROR)
                    val errorOutcome = VerifiedOutcome.PrerequisiteFailureOutcome(
                        actionId = idGenerator.nextActionId("next_turn_recovery_failed"),
                        sessionId = sessionId,
                        turnId = previousTurnId,
                        resultingState = ConversationState.ERROR,
                        failureCode = failureCode,
                        detail = failureDetail
                    )
                    recordDiagnosticErrorLocked(
                        stage = "NEXT_TURN_RECOVERY",
                        errorCode = failureCode,
                        rawSummary = failureDetail,
                        sessionId = sessionId,
                        turnId = previousTurnId,
                        generationWireId = completedTurn.turnGen.wireId
                    )
                    _uiState.update { state ->
                        state.copy(
                            state = ConversationState.ERROR,
                            activeConversationPhase = ActiveConversationPhase.ERROR,
                            activeConversationPhaseSequence = errorPhaseSeq,
                            activeTurnGenerationId = null,
                            voiceTurnStateSequence = errorSeq,
                            voiceTurnReadinessOrder = errorReadiness,
                            activeVoiceTurnErrorCode = failureCode,
                            activeVoiceTurnErrorDetail = failureDetail,
                            lastVoiceTurnSummary = "ERROR [$failureCode]: $failureDetail"
                        )
                    }
                    applyVerifiedOutcome(
                        sessionId = sessionId,
                        turnId = previousTurnId,
                        actionId = errorOutcome.actionId,
                        newState = ConversationState.ERROR,
                        roleLabel = "ACTIVE_CONVERSATION",
                        outcome = errorOutcome
                    )
                }
            }
        }
        createdJob = job
        activeNextTurnRecoveryJob = job
        return job
    }

    private fun cancelActiveTurnStreamsLocked(reason: String) {
        cancelInactivityTimerLocked()
        cancelPostPlaybackGuardJobLocked()
        speechGate.cancelAndResetToIdle(resetSessionCounters = false)
        syncSpeechGateTelemetryLocked()
        activeTimeoutRecoveryJob?.cancel()
        activeTimeoutRecoveryJob = null
        activeNextTurnRecoveryJob?.cancel()
        activeNextTurnRecoveryJob = null
        val prevTurn = activeTurn
        activeTurn = null
        prevTurn?.dispose(cancelAllJobs = true)
        audioCapture.stopAndRelease(reason = reason, wipeEphemeral = true)
        playbackAdapter.stopAndFlush(reason = reason)
        liveAdapter.setAudioPlaybackClaimed(false)
    }

    private fun failActiveVoiceTurnLocked(
        failureCode: String,
        detail: String,
        expectedSessionId: SessionId? = null,
        expectedTurnSeq: Long? = null,
        expectedTurnGen: TurnGenerationId? = null,
        expectedPlaybackGen: PlaybackGenerationId? = null
    ): VerifiedOutcome.PrerequisiteFailureOutcome? {
        val current = _uiState.value
        val turn = activeTurn
        val staleSession = expectedSessionId != null && current.sessionId != expectedSessionId
        val staleSeq = expectedTurnSeq != null && turn?.turnSeq != expectedTurnSeq
        val staleTurnGen = expectedTurnGen != null && turn?.turnGen != expectedTurnGen
        val stalePlaybackGen = expectedPlaybackGen != null && turn?.playbackGen != expectedPlaybackGen
        if (staleSession || staleSeq || staleTurnGen || stalePlaybackGen) {
            logTurnLifecycleEventLocked(
                "STALE_CALLBACK_DROPPED: Dropped failure [$failureCode] for ${expectedTurnGen?.wireId ?: expectedPlaybackGen?.wireId ?: "turn"} " +
                    "(currentTurn=${current.currentTurnId.value}, activeTurnGen=${turn?.turnGen?.wireId ?: "none"})."
            )
            _uiState.update { state ->
                state.copy(staleTurnCallbacksDropped = state.staleTurnCallbacksDropped + 1)
            }
            return null
        }

        val failingTurnId = expectedTurnGen?.let { TurnId(it.turnIdValue) } ?: turn?.turnId ?: current.currentTurnId
        val deferredToComplete = turn?.completionDeferred
        val savedTrace = turn?.structuredTraceEntries?.toList() ?: _uiState.value.structuredTurnTrace
        val missingStep = turn?.earliestMissingStep() ?: _uiState.value.activeTurnEarliestMissingStep
        previousTurnEarliestMissingStep = missingStep
        val isHardwareStall = failureCode == "HARDWARE_PLAYBACK_STALL" || failureCode == "HARDWARE_DRAIN_INCOMPLETE"
        if (turn != null) {
            latchPlaybackSnapshotIntoTurnLocked(turn, playbackAdapter.state.value)
            lastCompletedTurnRuntime = turn
            val category = when {
                failureCode == "SERVER_TURN_INTERRUPTED" -> Phase07TurnOutcomeCategory.INTERRUPTED
                failureCode.contains("TIMEOUT") || failureCode.contains("STALL") -> Phase07TurnOutcomeCategory.TIMED_OUT
                else -> Phase07TurnOutcomeCategory.FAILED
            }
            recordTurnOutcomeLocked(
                turn = turn,
                category = category,
                outcomeCode = failureCode
            )
        }
        recordDiagnosticErrorLocked(
            stage = current.state.wireLabel,
            errorCode = failureCode,
            rawSummary = detail,
            sessionId = current.sessionId,
            turnId = failingTurnId,
            generationWireId = expectedTurnGen?.wireId ?: turn?.turnGen?.wireId ?: "NONE"
        )
        cancelActiveTurnStreamsLocked(reason = "Voice turn failed [$failureCode]")
        syncTimingSnapshotsLocked()
        val actId = idGenerator.nextActionId(actionVerbForFailureCode(failureCode))
        val updatedSeq = appendTurnStateLocked(ConversationState.ERROR)
        val updatedReadiness = appendReadinessOrderLocked("ERROR")
        val updatedPhaseSeq = appendActiveConversationPhaseLocked(ActiveConversationPhase.ERROR)
        val outcome = VerifiedOutcome.PrerequisiteFailureOutcome(
            actionId = actId,
            sessionId = current.sessionId,
            turnId = failingTurnId,
            resultingState = ConversationState.ERROR,
            failureCode = failureCode,
            detail = detail
        )
        _uiState.update { state ->
            state.copy(
                currentTurnId = failingTurnId,
                activeConversationPhase = ActiveConversationPhase.ERROR,
                activeConversationPhaseSequence = updatedPhaseSeq,
                activeTurnGenerationId = null,
                voiceTurnStateSequence = updatedSeq,
                voiceTurnReadinessOrder = updatedReadiness,
                structuredTurnTrace = savedTrace,
                lastCompletedTurnTrace = if (savedTrace.isNotEmpty()) savedTrace else state.lastCompletedTurnTrace,
                lastCompletedTurnEarliestMissingStep = missingStep,
                activeTurnEarliestMissingStep = missingStep,
                hardwarePlaybackStallCount = if (isHardwareStall) state.hardwarePlaybackStallCount + 1 else state.hardwarePlaybackStallCount,
                activeVoiceTurnErrorCode = failureCode,
                activeVoiceTurnErrorDetail = detail,
                lastVoiceTurnSummary = "ERROR [$failureCode]: $detail"
            )
        }
        applyVerifiedOutcome(
            sessionId = current.sessionId,
            turnId = failingTurnId,
            actionId = actId,
            newState = ConversationState.ERROR,
            roleLabel = "VOICE_TURN_ERROR",
            outcome = outcome
        )
        deferredToComplete?.complete(outcome)
        return outcome
    }

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
                LiveConnectionPhase.SETUP_ACCEPTED,
                LiveConnectionPhase.ACTIVE -> "LIVE_SETUP_ACCEPTED"
                LiveConnectionPhase.NETWORK_UNAVAILABLE -> "LIVE_NETWORK_UNAVAILABLE"
                LiveConnectionPhase.NETWORK_RESTORED -> "LIVE_NETWORK_RESTORED"
                LiveConnectionPhase.FAILED -> "LIVE_FAILED_${finalLiveState.failureClassification.wireCode}"
                LiveConnectionPhase.DISCONNECTED -> "LIVE_DISCONNECTED"
                else -> "LIVE_${finalLiveState.phase.wireCode}"
            }
            val detailMsg = when (finalLiveState.phase) {
                LiveConnectionPhase.SETUP_ACCEPTED,
                LiveConnectionPhase.ACTIVE ->
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

    @Synchronized
    fun onLifecycleReleaseHardware(reason: String, disconnectLive: Boolean = false) {
        val hadActiveHardware = audioCapture.state.value.isCapturing ||
            audioCapture.state.value.isPlayingEphemeralCheck ||
            audioCapture.state.value.ephemeralRecordedSamples > 0 ||
            playbackAdapter.state.value.isPlaying
        val hadActiveLive = disconnectLive && (
            liveAdapter.state.value.phase == LiveConnectionPhase.CONNECTING ||
                liveAdapter.state.value.phase == LiveConnectionPhase.RECONNECTING ||
                liveAdapter.state.value.phase == LiveConnectionPhase.SETUP_ACCEPTED ||
                liveAdapter.state.value.phase == LiveConnectionPhase.ACTIVE ||
                liveAdapter.state.value.phase == LiveConnectionPhase.RETRY_BACKOFF_WAITING
            )

        if (disconnectLive) {
            consecutiveFirstResponseTimeouts = 0
            val deferredToCancel = activeTurn?.completionDeferred
            val recoveryDeferredToCancel = activeTimeoutRecoveryDeferred
            activeTimeoutRecoveryDeferred = null
            cancelActiveTurnStreamsLocked(reason = reason)
            liveAdapter.disconnectAndRelease(reason = reason)
            liveAdapter.stopNetworkMonitoring()
            val current = _uiState.value
            val targetState = when {
                isDeliberatelyStopped || current.state == ConversationState.STOPPED -> ConversationState.STOPPED
                isDeliberatelyPaused || current.state == ConversationState.PAUSED -> ConversationState.PAUSED
                else -> ConversationState.IDLE
            }
            _uiState.update { state ->
                state.copy(
                    state = targetState,
                    activeConversationPhase = targetState.toActiveConversationPhase(),
                    activeTurnGenerationId = null
                )
            }
            val releaseOutcome = VerifiedOutcome.StateTransitionVerified(
                actionId = idGenerator.nextActionId("lifecycle_stop"),
                sessionId = current.sessionId,
                turnId = current.currentTurnId,
                previousState = current.state,
                resultingState = targetState,
                detail = "Hardware released on lifecycle: $reason"
            )
            deferredToCancel?.complete(releaseOutcome)
            recoveryDeferredToCancel?.complete(releaseOutcome)
        } else {
            cancelPostPlaybackGuardJobLocked()
            speechGate.cancelAndResetToIdle(resetSessionCounters = false)
            syncSpeechGateTelemetryLocked()
            activeNextTurnRecoveryJob?.cancel()
            activeNextTurnRecoveryJob = null
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
            _uiState.update { state ->
                state.copy(transcript = (state.transcript + entry).takeLast(maxTranscriptEntries))
            }
        }
    }

    private fun recordDuplicateOutcomeLocked(
        actionId: ActionId,
        duplicateOutcome: VerifiedOutcome.DuplicateActionRejected
    ) {
        _uiState.update { state ->
            val newEntry = TranscriptEntry(
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
            state.copy(
                lastOutcome = duplicateOutcome,
                duplicateRejectedCount = state.duplicateRejectedCount + 1,
                transcript = (state.transcript + newEntry).takeLast(maxTranscriptEntries)
            )
        }
    }

    @Synchronized
    fun dispatch(
        command: CoordinatorCommand,
        actionId: ActionId = idGenerator.nextActionId(command.name)
    ): VerifiedOutcome {
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

        recordActionId(actionId)
        val nextTurn = idGenerator.nextTurnId()

        return when (command) {
            CoordinatorCommand.STOP -> {
                isDeliberatelyStopped = true
                isDeliberatelyPaused = false
                consecutiveFirstResponseTimeouts = 0
                val deferredToCancel = activeTurn?.completionDeferred
                val recoveryDeferredToCancel = activeTimeoutRecoveryDeferred
                activeTimeoutRecoveryDeferred = null
                cancelActiveTurnStreamsLocked("Immediate operator STOP command")
                liveAdapter.clearSessionResumptionState(current.sessionId)
                liveAdapter.disconnectAndRelease(reason = "Immediate operator STOP command")
                val updatedPhaseSeq = appendActiveConversationPhaseLocked(ActiveConversationPhase.STOPPED)

                val outcome = VerifiedOutcome.StateTransitionVerified(
                    actionId = actionId,
                    sessionId = current.sessionId,
                    turnId = nextTurn,
                    previousState = current.state,
                    resultingState = ConversationState.STOPPED,
                    detail = "STOP command executed with highest precedence (priority ${CoordinatorCommand.STOP.priority}). Microphone capture, model turn, and queued playback cancelled; generation IDs invalidated."
                )
                wakeWordState = WakeWordState.STANDBY
                activeWakeAuthorization = null
                wakeWordDetector.resetState()
                _uiState.update { state ->
                    state.copy(
                        activeConversationPhase = ActiveConversationPhase.STOPPED,
                        activeConversationPhaseSequence = updatedPhaseSeq,
                        activeTurnGenerationId = null,
                        isDeliberatelyStopped = true,
                        isDeliberatelyPaused = false,
                        wakeWordState = WakeWordState.STANDBY,
                        wakeWordStatusLabel = "STANDBY — SAY “JARVIS”",
                        wakeAuthorizationActive = false,
                        lastWakeKeyword = null,
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
                recoveryDeferredToCancel?.complete(outcome)
                outcome
            }

            CoordinatorCommand.PAUSE -> {
                if (current.state == ConversationState.STOPPED || isDeliberatelyStopped) {
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
                    isDeliberatelyPaused = true
                    val deferredToCancel = activeTurn?.completionDeferred
                    val recoveryDeferredToCancel = activeTimeoutRecoveryDeferred
                    activeTimeoutRecoveryDeferred = null
                    cancelActiveTurnStreamsLocked("Operator PAUSE command")
                    liveAdapter.disconnectAndRelease(reason = "Operator PAUSE command")
                    val updatedPhaseSeq = appendActiveConversationPhaseLocked(ActiveConversationPhase.PAUSED)
                    val outcome = VerifiedOutcome.StateTransitionVerified(
                        actionId = actionId,
                        sessionId = current.sessionId,
                        turnId = nextTurn,
                        previousState = current.state,
                        resultingState = ConversationState.PAUSED,
                        detail = "PAUSE command executed (priority ${CoordinatorCommand.PAUSE.priority}). Active audio capture, playback, and live socket released; shell paused."
                    )
                    wakeWordState = WakeWordState.STANDBY
                    activeWakeAuthorization = null
                    wakeWordDetector.resetState()
                    _uiState.update { state ->
                        state.copy(
                            activeConversationPhase = ActiveConversationPhase.PAUSED,
                            activeConversationPhaseSequence = updatedPhaseSeq,
                            activeTurnGenerationId = null,
                            isDeliberatelyPaused = true,
                            wakeWordState = WakeWordState.STANDBY,
                            wakeWordStatusLabel = "STANDBY — SAY “JARVIS”",
                            wakeAuthorizationActive = false,
                            lastWakeKeyword = null,
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
                    recoveryDeferredToCancel?.complete(outcome)
                    outcome
                }
            }

            CoordinatorCommand.RESUME -> {
                if (current.state == ConversationState.STOPPED || isDeliberatelyStopped) {
                    val preempted = VerifiedOutcome.PreemptedByPrecedence(
                        actionId = actionId,
                        sessionId = current.sessionId,
                        turnId = nextTurn,
                        requestedCommand = CoordinatorCommand.RESUME,
                        enforcingCommand = CoordinatorCommand.STOP,
                        resultingState = ConversationState.STOPPED,
                        detail = "RESUME ignored because session is STOPPED (Stop precedence > Resume)."
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
                    isDeliberatelyPaused = false
                    releaseResourcesBeforeReplacementCaptureLocked("Operator RESUME command resource release")
                    val updatedPhaseSeq = appendActiveConversationPhaseLocked(ActiveConversationPhase.PREPARING)
                    val outcome = VerifiedOutcome.StateTransitionVerified(
                        actionId = actionId,
                        sessionId = current.sessionId,
                        turnId = nextTurn,
                        previousState = current.state,
                        resultingState = ConversationState.CONNECTING,
                        detail = "RESUME command executed (priority ${CoordinatorCommand.RESUME.priority}). Resources released; preparing active conversation..."
                    )
                    _uiState.update { state ->
                        state.copy(
                            activeConversationPhase = ActiveConversationPhase.PREPARING,
                            activeConversationPhaseSequence = updatedPhaseSeq,
                            isDeliberatelyPaused = false,
                            lastVoiceTurnSummary = outcome.detail
                        )
                    }
                    applyVerifiedOutcome(
                        sessionId = current.sessionId,
                        turnId = nextTurn,
                        actionId = actionId,
                        newState = ConversationState.CONNECTING,
                        roleLabel = "OPERATOR_RESUME",
                        outcome = outcome
                    )
                    outcome
                }
            }

            CoordinatorCommand.START -> {
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
                        recordDiagnosticErrorLocked(
                            stage = "PREREQUISITE_CHECK",
                            errorCode = prereq.code,
                            rawSummary = prereq.detail,
                            sessionId = current.sessionId,
                            turnId = nextTurn
                        )
                        val updatedPhaseSeq = appendActiveConversationPhaseLocked(ActiveConversationPhase.ERROR)
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
                                activeConversationPhase = ActiveConversationPhase.ERROR,
                                activeConversationPhaseSequence = updatedPhaseSeq,
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
                        archiveActiveDiagnosticErrorLocked()
                        isDeliberatelyPaused = false
                        isDeliberatelyStopped = false
                        preferencesStore.hasCompletedFirstUseConsentAndConfig = true
                        val seqList = listOf(ConversationState.CONNECTING)
                        val updatedPhaseSeq = appendActiveConversationPhaseLocked(ActiveConversationPhase.PREPARING)
                        val outcome = VerifiedOutcome.StateTransitionVerified(
                            actionId = actionId,
                            sessionId = current.sessionId,
                            turnId = nextTurn,
                            previousState = current.state,
                            resultingState = ConversationState.CONNECTING,
                            detail = "PREPARING / CONNECTING: Voice coordinator initialized for ${liveAdapter.configuredModelId}."
                        )
                        _uiState.update { state ->
                            state.copy(
                                activeConversationPhase = ActiveConversationPhase.PREPARING,
                                activeConversationPhaseSequence = updatedPhaseSeq,
                                isDeliberatelyPaused = false,
                                isDeliberatelyStopped = false,
                                hasCompletedFirstUseConsentAndConfig = true,
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
                val turn = activeTurn
                if (current.state == ConversationState.LISTENING && turn != null && !turn.closed && !turn.endOfTurnRequested) {
                    val jobsToFlush = turn.micSendJobs.filter { it.isActive }
                    if (turn.capturedFramesCount <= 0L && jobsToFlush.isEmpty()) {
                        turn.endOfTurnRequested = true
                        stopTurnMicrophoneLocked(
                            reason = "No microphone audio captured (Operator END_SPOKEN_TURN)",
                            cancelQueuedMicSends = true,
                            turnForTrace = turn
                        )
                        failActiveVoiceTurnLocked(
                            failureCode = "NO_AUDIO_CAPTURED",
                            detail = "No microphone audio frames were captured for ${turn.turnId.value}. Refusing to enter PROCESSING. Speak clearly and try again.",
                            expectedSessionId = turn.sessionId,
                            expectedTurnSeq = turn.turnSeq,
                            expectedTurnGen = turn.turnGen
                        )!!
                    } else {
                        turn.endOfTurnRequested = true
                        cancelInactivityTimerLocked()
                        val turnSeq = turn.turnSeq
                        val turnGen = turn.turnGen
                        stopTurnMicrophoneLocked(
                            reason = "Operator END_SPOKEN_TURN command",
                            cancelQueuedMicSends = false,
                            turnForTrace = turn
                        )
                        val outcome = VerifiedOutcome.StateTransitionVerified(
                            actionId = actionId,
                            sessionId = current.sessionId,
                            turnId = turn.turnId,
                            previousState = ConversationState.LISTENING,
                            resultingState = ConversationState.LISTENING,
                            detail = "END_SPOKEN_TURN accepted (${turn.turnGen.wireId}); flushing captured audio before submitting end-of-turn and entering PROCESSING."
                        )
                        applyVerifiedOutcome(
                            sessionId = current.sessionId,
                            turnId = turn.turnId,
                            actionId = actionId,
                            newState = ConversationState.LISTENING,
                            roleLabel = "VOICE_TURN_COORDINATOR",
                            outcome = outcome
                        )
                        turnScope.launch(turn.parentJob) {
                            flushPreRollAndSubmitEndOfTurn(
                                turn = turn,
                                turnSeq = turnSeq,
                                turnGen = turnGen,
                                sessionId = turn.sessionId,
                                turnId = turn.turnId,
                                reason = "Operator END_SPOKEN_TURN",
                                requireCapturedSpeech = true,
                                micJobsToFlush = jobsToFlush
                            )
                        }
                        _uiState.value.lastOutcome ?: outcome
                    }
                } else {
                    val outcome = VerifiedOutcome.StateTransitionVerified(
                        actionId = actionId,
                        sessionId = current.sessionId,
                        turnId = nextTurn,
                        previousState = current.state,
                        resultingState = current.state,
                        detail = "END_SPOKEN_TURN ignored because coordinator is in ${current.state.wireLabel} (endOfTurnRequested=${turn?.endOfTurnRequested ?: false})."
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
                archiveActiveDiagnosticErrorLocked()
                isDeliberatelyPaused = false
                isDeliberatelyStopped = false
                consecutiveFirstResponseTimeouts = 0
                previousTurnEarliestMissingStep = null
                val deferredToCancel = activeTurn?.completionDeferred
                val recoveryDeferredToCancel = activeTimeoutRecoveryDeferred
                activeTimeoutRecoveryDeferred = null
                cancelActiveTurnStreamsLocked("Session reset to IDLE")
                speechGate.cancelAndResetToIdle(resetSessionCounters = true)
                syncSpeechGateTelemetryLocked()
                liveAdapter.clearSessionResumptionState()
                liveAdapter.disconnectAndRelease(reason = "Session reset to IDLE")
                needsSessionRecreationBeforeNextTurn = false
                recordedTurnOutcomes.clear()
                totalAttemptedTurnsCounter = 0
                totalCompletedTurnsCounter = 0
                totalFailedTurnsCounter = 0
                totalInterruptedTurnsCounter = 0
                totalTimedOutTurnsCounter = 0
                totalNoSpeechRejectedTurnsCounter = 0
                lastCompletedTurnRuntime = null
                latestStartupTiming = Phase07StartupTimingSnapshot(
                    isSyntheticOrTestMeasurement = isSyntheticOrTestMeasurementMode
                )
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
                        activeConversationPhase = ActiveConversationPhase.IDLE,
                        activeConversationPhaseSequence = listOf(ActiveConversationPhase.IDLE),
                        completedTurnsInSession = 0,
                        isDeliberatelyPaused = false,
                        isDeliberatelyStopped = false,
                        sessionDialogueHistory = emptyList(),
                        voiceTurnStateSequence = emptyList(),
                        voiceTurnReadinessOrder = emptyList(),
                        activeTurnGenerationId = null,
                        turnEventTrace = emptyList(),
                        firstTurnLifecycleTrace = emptyList(),
                        structuredTurnTrace = emptyList(),
                        lastCompletedTurnTrace = emptyList(),
                        lastCompletedTurnEarliestMissingStep = null,
                        activeTurnEarliestMissingStep = null,
                        firstResponseTimeoutCount = 0,
                        firstResponseRecoveryCount = 0,
                        serverOutputStallCount = 0,
                        missingTurnCompleteCount = 0,
                        hardwarePlaybackStallCount = 0,
                        debouncedStartCount = 0,
                        activeVoiceTurnErrorCode = null,
                        activeVoiceTurnErrorDetail = null,
                        voiceTurnFramesSent = 0L,
                        voiceTurnChunksSent = 0L,
                        voiceTurnBytesSent = 0L,
                        voiceTurnPeakRmsLinear = 0f,
                        interruptionRecoveryCount = 0,
                        startupTimingSnapshot = Phase07StartupTimingSnapshot(),
                        activeTurnTimingSnapshot = null,
                        lastCompletedTurnTimingSnapshot = null,
                        performanceSummary = Phase07PerformanceSummary(),
                        lastVoiceTurnSummary = "Ready. Press Start to begin a verified Gemini Live voice conversation."
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
                deferredToCancel?.complete(outcome)
                recoveryDeferredToCancel?.complete(outcome)
                outcome
            }
        }
    }

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
            if (winningCommand == CoordinatorCommand.STOP) {
                isDeliberatelyStopped = true
                isDeliberatelyPaused = false
                consecutiveFirstResponseTimeouts = 0
                liveAdapter.clearSessionResumptionState(current.sessionId)
            } else if (current.state != ConversationState.STOPPED && !isDeliberatelyStopped) {
                isDeliberatelyPaused = true
            }
            val deferredToCancel = activeTurn?.completionDeferred
            val recoveryDeferredToCancel = activeTimeoutRecoveryDeferred
            activeTimeoutRecoveryDeferred = null
            cancelActiveTurnStreamsLocked("Preempted by ${winningCommand.name} precedence")
            liveAdapter.disconnectAndRelease(
                reason = "Preempted by ${winningCommand.name} precedence"
            )
            val preemptOutcome = VerifiedOutcome.StateTransitionVerified(
                actionId = actionId,
                sessionId = current.sessionId,
                turnId = nextTurn,
                previousState = current.state,
                resultingState = if (winningCommand == CoordinatorCommand.STOP) ConversationState.STOPPED else ConversationState.PAUSED,
                detail = "Preempted by ${winningCommand.name} precedence"
            )
            deferredToCancel?.complete(preemptOutcome)
            recoveryDeferredToCancel?.complete(preemptOutcome)
        }

        val targetState = when (winningCommand) {
            CoordinatorCommand.STOP -> ConversationState.STOPPED
            CoordinatorCommand.PAUSE ->
                if (current.state == ConversationState.STOPPED || isDeliberatelyStopped) ConversationState.STOPPED
                else ConversationState.PAUSED
            CoordinatorCommand.RESUME ->
                if (current.state == ConversationState.STOPPED || isDeliberatelyStopped) ConversationState.STOPPED
                else ConversationState.CONNECTING
            CoordinatorCommand.START ->
                if (!isVoiceTurnConfigured) ConversationState.NOT_CONFIGURED
                else ConversationState.CONNECTING
            CoordinatorCommand.RESET_IDLE -> ConversationState.IDLE
            else -> current.state
        }

        val updatedPhaseSeq = appendActiveConversationPhaseLocked(targetState.toActiveConversationPhase())
        _uiState.update { state ->
            state.copy(
                activeConversationPhase = targetState.toActiveConversationPhase(),
                activeConversationPhaseSequence = updatedPhaseSeq,
                isDeliberatelyPaused = isDeliberatelyPaused,
                isDeliberatelyStopped = isDeliberatelyStopped
            )
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
            "MIC: READY (PERMISSION GRANTED • HALF-DUPLEX IDLE)"
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
                authoritativeSystemInstruction = liveSnap.systemInstruction,
                lastOutcome = outcome,
                transcript = (state.transcript + entry).takeLast(maxTranscriptEntries),
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
        val turn = activeTurn
        return (turn != null && !turn.closed) ||
            activeTimeoutRecoveryJob?.isActive == true ||
            activeNextTurnRecoveryJob?.isActive == true ||
            currentState == ConversationState.PREPARING ||
            currentState == ConversationState.CONNECTING ||
            currentState == ConversationState.LISTENING ||
            currentState == ConversationState.THINKING ||
            currentState == ConversationState.PROCESSING ||
            currentState == ConversationState.SPEAKING
    }

    /**
     * Constructs a dedicated [RedactedDiagnosticSnapshot] on demand when `COPY DIAG` is tapped.
     * Excludes transcripts, raw audio, credentials, App Check tokens, resumption handles,
     * contact information, and unfiltered exception messages.
     */
    @Synchronized
    fun buildRedactedDiagnosticSnapshot(): RedactedDiagnosticSnapshot {
        syncTimingSnapshotsLocked()
        val ui = _uiState.value
        val audioSnap = audioCapture.state.value
        val liveSnap = liveAdapter.state.value
        val playSnap = playbackAdapter.state.value
        val gateSnap = speechGate.telemetrySnapshot()
        val turn = activeTurn
        val completedTurn = lastCompletedTurnRuntime

        return RedactedDiagnosticSnapshot(
            capturedAtMonotonicMs = monotonicClockMs(),
            sessionId = ui.sessionId.value,
            currentTurnId = ui.currentTurnId.value,
            activeTurnGenerationId = turn?.turnGen?.wireId ?: ui.activeTurnGenerationId?.wireId ?: "NONE",
            liveSessionGenerationId = liveSnap.activeGeneration?.wireId
                ?: liveSnap.lastCompletedGeneration?.wireId
                ?: "NONE",
            captureGenerationId = audioSnap.activeGeneration?.wireId
                ?: audioSnap.lastCompletedGeneration?.wireId
                ?: "NONE",
            playbackGenerationId = playSnap.activeGeneration?.wireId
                ?: playSnap.lastCompletedGeneration?.wireId
                ?: "NONE",
            runtimeModelId = liveAdapter.configuredModelId,
            apiVersion = liveSnap.diagnostics.apiVersion,
            endpointFamily = liveSnap.diagnostics.endpointFamily,
            authRoute = liveSnap.diagnostics.authRoute,
            configuredVoice = LiveConfigConstants.DEFAULT_NATIVE_AUDIO_VOICE,
            conversationState = ui.state.wireLabel,
            activeConversationPhase = ui.activeConversationPhase.wireLabel,
            isActiveConversationEnabled = ui.isActiveConversationEnabled,
            completedTurnsInSession = ui.completedTurnsInSession,
            voiceTurnStateSequence = ui.voiceTurnStateSequence.map { it.wireLabel },
            activeConversationPhaseSequence = ui.activeConversationPhaseSequence.map { it.wireLabel },
            voiceTurnReadinessOrder = ui.voiceTurnReadinessOrder,
            structuredTurnTrace = ui.structuredTurnTrace.map { DiagnosticRedactor.redactForDiagnostics(it) },
            lastCompletedTurnTrace = ui.lastCompletedTurnTrace.map { DiagnosticRedactor.redactForDiagnostics(it) },
            activeTurnEarliestMissingStep = ui.activeTurnEarliestMissingStep ?: "NONE",
            lastCompletedTurnEarliestMissingStep = ui.lastCompletedTurnEarliestMissingStep ?: "NONE",
            liveConnectionPhase = liveSnap.phase.wireCode,
            handshakeVerified = liveSnap.handshakeVerified,
            isInternetValidated = liveSnap.isInternetValidated,
            isReceiverCollectorAttached = liveSnap.isReceiverCollectorAttached,
            staleTurnCallbacksDropped = ui.staleTurnCallbacksDropped,
            staleSocketCallbacksDropped = liveSnap.staleSocketCallbacksDropped,
            staleCaptureCallbacksDropped = audioSnap.staleCallbacksDropped,
            stalePlaybackCallbacksDropped = playSnap.stalePlaybackCallbacksDropped,
            startupTiming = latestStartupTiming,
            activeTurnTiming = turn?.let { buildTurnTimingSnapshotLocked(it) },
            lastCompletedTurnTiming = completedTurn?.let { buildTurnTimingSnapshotLocked(it) }
                ?: ui.lastCompletedTurnTimingSnapshot,
            captureOwner = audioSnap.owner.name,
            isCapturing = audioSnap.isCapturing,
            micPermissionState = audioSnap.permissionState.wireLabel,
            hardwareSampleRateHz = audioSnap.hardwareSampleRateHz,
            targetSampleRateHz = audioSnap.targetSampleRateHz,
            resamplingActive = audioSnap.resamplingActive,
            capturedFramesCount = audioSnap.capturedFrameCount,
            capturedDurationMs = audioSnap.capturedDurationMs,
            voiceTurnFramesSent = ui.voiceTurnFramesSent,
            voiceTurnBytesSent = ui.voiceTurnBytesSent,
            voiceTurnChunksSent = ui.voiceTurnChunksSent,
            speechGateState = gateSnap.gateState.name,
            vadDetectorName = gateSnap.detectorName,
            vadDetectorVersion = gateSnap.detectorVersion,
            vadModelLoaded = gateSnap.isModelLoaded,
            falseStartsRejectedCount = gateSnap.falseStartsRejected,
            activityStartCount = gateSnap.activityStartCount,
            activityEndCount = gateSnap.activityEndCount,
            playbackIsTrackOpen = playSnap.isTrackOpen || playSnap.isPlaying || playSnap.isDrainingHardware,
            playbackIsPlaying = playSnap.isPlaying,
            playbackIsDraining = playSnap.isDrainingHardware,
            playbackIsDrained = playSnap.isPlaybackDrained,
            playbackVerifiedMimeType = playSnap.verifiedMimeType.ifBlank { "NONE" },
            playbackVerifiedSampleRateHz = playSnap.verifiedSampleRateHz,
            playbackFramesReceivedFromServer = playSnap.framesReceivedFromServer,
            playbackBytesReceivedFromServer = playSnap.audioBytesReceived,
            playbackChunksReceived = playSnap.chunksReceived,
            playbackFramesAcceptedByHardware = playSnap.totalFramesWrittenToHardware,
            playbackBytesAcceptedByHardware = playSnap.bytesAcceptedByHardware,
            playbackFramesPlayedByHardwareHead = playSnap.hardwarePlaybackHeadFrames,
            playbackQueuedBytes = playSnap.queuedBytes,
            playbackMaxQueuedBytes = playSnap.maximumQueuedBytes,
            playbackPrebufferTargetBytes = playSnap.prebufferTargetBytes,
            playbackStartedAfterBufferedBytes = playSnap.playbackStartedAfterBufferedBytes,
            playbackPartialWriteCount = playSnap.partialWriteCount,
            playbackZeroWriteRetryCount = playSnap.zeroWriteRetryCount,
            playbackUnderrunCount = playSnap.underrunCount,
            playbackTrackRecreationCount = playSnap.trackRecreationCount,
            currentActiveError = currentActiveDiagnosticErrorEntry,
            previousErrorHistory = previousDiagnosticErrorsHistory.toList(),
            connectionTimeoutMs = connectionTimeoutMs,
            firstResponseTimeoutMs = firstResponseTimeoutMs,
            inactivityTimeoutOptionLabel = ui.inactivityTimeoutOption.label,
            inactivityTimeoutMs = ui.inactivityTimeoutMs,
            speechConfirmDurationMs = effectiveSpeechConfirmDurationMs,
            trailingSilenceDurationMs = trailingSilenceDurationMs,
            preRollDurationMs = preRollDurationMs,
            postPlaybackEchoGuardMs = postPlaybackEchoGuardMs,
            liveBackoffMaxAttempts = liveAdapter.backoffPolicy.maxAttempts,
            liveBackoffInitialDelayMs = liveAdapter.backoffPolicy.initialDelayMs,
            liveBackoffMultiplier = liveAdapter.backoffPolicy.multiplier,
            liveBackoffMaxDelayMs = liveAdapter.backoffPolicy.maxDelayMs,
            liveAttemptCount = liveSnap.attemptCount,
            liveRetryCount = liveSnap.retryCount,
            liveAutoReconnectCount = liveSnap.autoReconnectCount,
            liveLastBackoffDelayMs = liveSnap.lastBackoffDelayMs,
            firstResponseTimeoutCount = ui.firstResponseTimeoutCount,
            firstResponseRecoveryCount = ui.firstResponseRecoveryCount,
            interruptionRecoveryCount = ui.interruptionRecoveryCount,
            firstAudioAfterResponseTimeoutMs = firstAudioAfterResponseTimeoutMs,
            serverOutputStallTimeoutMs = serverOutputStallTimeoutMs,
            missingTurnCompleteTimeoutMs = missingTurnCompleteTimeoutMs,
            maxTurnResponseHardBoundMs = maxTurnResponseHardBoundMs,
            hardwarePlaybackStallTimeoutMs = hardwarePlaybackStallTimeoutMs,
            serverOutputStallCount = ui.serverOutputStallCount,
            missingTurnCompleteCount = ui.missingTurnCompleteCount,
            hardwarePlaybackStallCount = ui.hardwarePlaybackStallCount,
            playbackPaddingFramesWritten = playSnap.paddingFramesWrittenToHardware,
            playbackPaddingBytesWritten = playSnap.paddingBytesWrittenToHardware,
            sessionResumptionStatus = liveSnap.sessionResumptionStatus.wireCode,
            contextRestorationStatus = liveSnap.contextRestorationStatus.wireCode,
            hasValidResumptionHandle = liveSnap.hasScopedResumptionHandle,
            resumptionUpdateCount = liveSnap.sessionResumptionUpdatesReceived,
            resumedConnectionCount = liveSnap.sessionResumptionSuccessCount,
            rejectedResumptionHandleCount = liveSnap.sessionResumptionFallbackCount,
            serverGoAwayCount = liveSnap.serverGoAwayCount,
            lastServerGoAwayTimeLeftMs = liveSnap.lastServerGoAwayTimeLeftMs ?: -1L,
            contextWindowCompressionActive = liveSnap.contextWindowCompressionEnabled,
            performanceSummary = ui.performanceSummary
        )
    }

    /**
     * Generates the formatted, redacted Phase 07A `COPY DIAG` text snapshot.
     */
    @Synchronized
    fun generateRedactedDiagnosticSnapshot(): String =
        buildRedactedDiagnosticSnapshot().toFormattedReport()
}
