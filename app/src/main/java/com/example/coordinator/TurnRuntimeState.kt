package com.example.coordinator

import com.example.audio.PlaybackGenerationId
import com.example.contract.SessionId
import com.example.contract.TurnId
import com.example.contract.VerifiedOutcome
import com.example.live.TurnGenerationId
import java.util.EnumSet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * Redacted, monotonically ordered 11-checkpoint lifecycle steps for a single spoken turn in Phase 05.
 * Used to pinpoint the earliest missing event if a turn stalls in `LISTENING` or `PROCESSING`.
 */
enum class TurnTraceStep(val wireCode: String) {
    LISTENING_STARTED("1_LISTENING_STARTED"),
    FIRST_MIC_FRAME("2_FIRST_MIC_FRAME"),
    LAST_MIC_FRAME("2_LAST_MIC_FRAME"),
    USER_SPEECH_DETECTED("3_USER_SPEECH_DETECTED"),
    AUDIO_CAPTURE_STOPPED("4_AUDIO_CAPTURE_STOPPED"),
    ACTIVITY_END_SENT("5_ACTIVITY_END_SENT"),
    SERVER_INPUT_TRANSCRIPTION("6_SERVER_INPUT_TRANSCRIPTION"),
    SERVER_RESPONSE_START("7_SERVER_RESPONSE_START"),
    FIRST_AUDIO_CHUNK("8_FIRST_AUDIO_CHUNK"),
    SERVER_TURN_COMPLETE("9_SERVER_TURN_COMPLETE"),
    AUDIOTRACK_FRAMES_DRAINED("10_AUDIOTRACK_FRAMES_DRAINED"),
    NEXT_LISTENING_STARTED("11_NEXT_LISTENING_STARTED")
}

/**
 * Point-in-time resource snapshot of the [ConversationCoordinator] used to verify zero leaked
 * coroutines, timers, or hardware handles across multi-turn conversations and Stop/Pause transitions.
 */
data class CoordinatorResourceSnapshot(
    val activeTurnJobs: Int,
    val hasActiveInactivityTimer: Boolean,
    val hasActiveFirstResponseWatchdog: Boolean,
    val isMicrophoneCapturing: Boolean,
    val isPlaybackTrackOpen: Boolean,
    val activeLiveSessionCount: Int,
    val isReceiverCollectorAttached: Boolean,
    val staleTurnCallbacksDropped: Int,
    val firstResponseTimeoutCount: Int,
    val firstResponseRecoveryCount: Int
)

/**
 * Fresh, isolated, turn-scoped runtime state created anew for EVERY spoken turn in Phase 05.
 *
 * Strictly prevents any completed/cancelled `Deferred`, old VAD speech/silence flags, old completion signals,
 * old timers, or old playback counters from leaking from turn `N` into turn `N+1`.
 */
class TurnRuntimeState(
    val turnSeq: Long,
    val sessionId: SessionId,
    val turnId: TurnId,
    val turnGen: TurnGenerationId,
    val createdAtLabel: String,
    val parentJob: CompletableJob = SupervisorJob(),
    val completionDeferred: CompletableDeferred<VerifiedOutcome> = CompletableDeferred()
) {
    var playbackGen: PlaybackGenerationId? = null
    var speechChunks: Int = 0
    var trailingSilenceChunks: Int = 0
    var autoEndTriggered: Boolean = false

    var capturedFramesCount: Long = 0L
    var capturedBytesCount: Long = 0L
    var nonSilentSpeechFramesCount: Long = 0L
    var peakRmsLinear: Float = 0f

    var confirmedSentFramesCount: Long = 0L
    var confirmedSentChunksCount: Long = 0L
    var confirmedSentBytesCount: Long = 0L

    var activityStartAttempted: Boolean = false
    var activityStartSent: Boolean = false
    var activityStartFailed: Boolean = false

    var endOfTurnRequested: Boolean = false
    var endOfTurnSubmissionInFlight: Boolean = false
    var endOfTurnSubmitted: Boolean = false
    var endOfTurnFailed: Boolean = false

    var serverResponseStarted: Boolean = false
    var firstResponseWatchdogArmed: Boolean = false
    var firstResponseTimeoutFired: Boolean = false

    var completionHandled: Boolean = false
    var closed: Boolean = false

    var firstResponseWatchdogJob: Job? = null
    var drainJob: Job? = null
    val micSendJobs: MutableList<Job> = mutableListOf()
    val writeJobs: MutableList<Job> = mutableListOf()

    private val recordedSteps: EnumSet<TurnTraceStep> = EnumSet.noneOf(TurnTraceStep::class.java)
    val structuredTraceEntries: MutableList<String> = mutableListOf()

    @Synchronized
    fun recordStep(
        step: TurnTraceStep,
        timestampLabel: String,
        liveGenWireId: String? = null,
        detail: String? = null
    ) {
        recordedSteps.add(step)
        val livePart = liveGenWireId ?: "NONE"
        val detailSuffix = if (!detail.isNullOrBlank()) " [$detail]" else ""
        val entry = "${step.wireCode}@$timestampLabel(turnGen=${turnGen.wireId},liveGen=$livePart,playGen=${playbackGen?.wireId ?: "NONE"})$detailSuffix"
        if (step == TurnTraceStep.LAST_MIC_FRAME) {
            val existingIdx = structuredTraceEntries.indexOfFirst { it.startsWith(TurnTraceStep.LAST_MIC_FRAME.wireCode) }
            if (existingIdx >= 0) {
                structuredTraceEntries[existingIdx] = entry
                return
            }
        }
        if (structuredTraceEntries.none { it.startsWith("${step.wireCode}@") }) {
            structuredTraceEntries.add(entry)
        }
    }

    @Synchronized
    fun hasStep(step: TurnTraceStep): Boolean = recordedSteps.contains(step)

    @Synchronized
    fun earliestMissingStep(): String {
        if (!recordedSteps.contains(TurnTraceStep.LISTENING_STARTED)) return TurnTraceStep.LISTENING_STARTED.wireCode
        if (!recordedSteps.contains(TurnTraceStep.FIRST_MIC_FRAME)) return TurnTraceStep.FIRST_MIC_FRAME.wireCode
        if (!recordedSteps.contains(TurnTraceStep.USER_SPEECH_DETECTED)) return TurnTraceStep.USER_SPEECH_DETECTED.wireCode
        if (!recordedSteps.contains(TurnTraceStep.AUDIO_CAPTURE_STOPPED)) return TurnTraceStep.AUDIO_CAPTURE_STOPPED.wireCode
        if (!recordedSteps.contains(TurnTraceStep.ACTIVITY_END_SENT)) return TurnTraceStep.ACTIVITY_END_SENT.wireCode
        if (!recordedSteps.contains(TurnTraceStep.SERVER_RESPONSE_START) &&
            !recordedSteps.contains(TurnTraceStep.FIRST_AUDIO_CHUNK) &&
            !recordedSteps.contains(TurnTraceStep.SERVER_TURN_COMPLETE)
        ) {
            return TurnTraceStep.SERVER_RESPONSE_START.wireCode
        }
        if (!recordedSteps.contains(TurnTraceStep.FIRST_AUDIO_CHUNK) &&
            !recordedSteps.contains(TurnTraceStep.SERVER_TURN_COMPLETE)
        ) {
            return TurnTraceStep.FIRST_AUDIO_CHUNK.wireCode
        }
        if (!recordedSteps.contains(TurnTraceStep.SERVER_TURN_COMPLETE)) return TurnTraceStep.SERVER_TURN_COMPLETE.wireCode
        if (!recordedSteps.contains(TurnTraceStep.AUDIOTRACK_FRAMES_DRAINED)) return TurnTraceStep.AUDIOTRACK_FRAMES_DRAINED.wireCode
        return "NONE"
    }

    @Synchronized
    fun cancelWatchdog() {
        firstResponseWatchdogJob?.cancel()
        firstResponseWatchdogJob = null
    }

    @Synchronized
    fun collectAllJobs(): List<Job> {
        val all = mutableListOf<Job>()
        all.addAll(micSendJobs)
        all.addAll(writeJobs)
        firstResponseWatchdogJob?.let { all.add(it) }
        drainJob?.let { all.add(it) }
        return all
    }

    @Synchronized
    fun activeJobCount(): Int {
        var count = 0
        count += micSendJobs.count { it.isActive }
        count += writeJobs.count { it.isActive }
        if (firstResponseWatchdogJob?.isActive == true) count += 1
        if (drainJob?.isActive == true) count += 1
        return count
    }

    @Synchronized
    fun dispose(cancelAllJobs: Boolean) {
        closed = true
        firstResponseWatchdogJob?.cancel()
        firstResponseWatchdogJob = null
        if (cancelAllJobs) {
            for (job in micSendJobs) {
                job.cancel()
            }
            for (job in writeJobs) {
                job.cancel()
            }
            drainJob?.cancel()
            drainJob = null
            parentJob.cancel()
        } else {
            micSendJobs.removeAll { it.isCompleted }
            writeJobs.removeAll { it.isCompleted }
        }
    }
}
