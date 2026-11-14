package com.example.coordinator

import com.example.contract.SessionId
import com.example.contract.TurnId
import com.example.contract.VerifiedOutcome
import com.example.live.DiagnosticRedactor
import com.example.live.TurnGenerationId
import com.example.audio.PlaybackGenerationId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * Canonical 11-checkpoint per-turn lifecycle trace steps recorded with redacted timestamps
 * and generation IDs for diagnosing any stuck or delayed turn (Phase 05 test 05.2).
 */
enum class TurnTraceStep(val wireName: String) {
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
 * Isolated turn-scoped runtime container allocated fresh for every voice turn.
 * Never reuses completed/cancelled [CompletableDeferred], closed channels/jobs, old VAD counters,
 * old activity flags, or old playback counters across turns.
 */
internal class TurnRuntimeState(
    val turnSeq: Long,
    val sessionId: SessionId,
    val turnId: TurnId,
    val turnGen: TurnGenerationId,
    val createdAtLabel: String
) {
    val parentJob: CompletableJob = SupervisorJob()
    val completionDeferred: CompletableDeferred<VerifiedOutcome> = CompletableDeferred()
    val micSendJobs: MutableList<Job> = mutableListOf()
    val writeJobs: MutableList<Job> = mutableListOf()
    var drainJob: Job? = null
    var firstResponseWatchdogJob: Job? = null

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

    private val recordedSteps = LinkedHashSet<TurnTraceStep>()
    val structuredTraceEntries: MutableList<String> = mutableListOf()

    fun recordStep(
        step: TurnTraceStep,
        timestampLabel: String,
        liveGenWireId: String?,
        detail: String? = null
    ) {
        val shouldAppend = when (step) {
            TurnTraceStep.LAST_MIC_FRAME -> {
                // Keep updating latest mic frame checkpoint cleanly without spamming list
                recordedSteps.add(step)
                val prefix = "${step.wireName} @"
                val idx = structuredTraceEntries.indexOfFirst { it.startsWith(prefix) }
                val entry = formatStepEntry(step, timestampLabel, liveGenWireId, detail)
                if (idx >= 0) {
                    structuredTraceEntries[idx] = entry
                } else {
                    structuredTraceEntries.add(entry)
                }
                false
            }
            else -> recordedSteps.add(step)
        }
        if (shouldAppend) {
            structuredTraceEntries.add(formatStepEntry(step, timestampLabel, liveGenWireId, detail))
        }
    }

    private fun formatStepEntry(
        step: TurnTraceStep,
        timestampLabel: String,
        liveGenWireId: String?,
        detail: String?
    ): String {
        val safeTs = DiagnosticRedactor.redact(timestampLabel)
        val safeLive = DiagnosticRedactor.redact(liveGenWireId ?: "none")
        val suffix = detail?.takeIf { it.isNotBlank() }?.let { " (${DiagnosticRedactor.redact(it)})" } ?: ""
        return "${step.wireName} @ $safeTs [tgen=${turnGen.wireId}, live=$safeLive]$suffix"
    }

    fun hasStep(step: TurnTraceStep): Boolean = recordedSteps.contains(step)

    /**
     * Identifies the earliest missing checkpoint in the 11-step turn lifecycle up to drain.
     */
    fun earliestMissingStep(): String {
        val orderedRequired = listOf(
            TurnTraceStep.LISTENING_STARTED,
            TurnTraceStep.FIRST_MIC_FRAME,
            TurnTraceStep.USER_SPEECH_DETECTED,
            TurnTraceStep.LAST_MIC_FRAME,
            TurnTraceStep.AUDIO_CAPTURE_STOPPED,
            TurnTraceStep.ACTIVITY_END_SENT,
            TurnTraceStep.SERVER_RESPONSE_START,
            TurnTraceStep.FIRST_AUDIO_CHUNK,
            TurnTraceStep.SERVER_TURN_COMPLETE,
            TurnTraceStep.AUDIOTRACK_FRAMES_DRAINED
        )
        for (step in orderedRequired) {
            if (!recordedSteps.contains(step)) {
                return step.wireName
            }
        }
        return if (!recordedSteps.contains(TurnTraceStep.NEXT_LISTENING_STARTED)) {
            TurnTraceStep.NEXT_LISTENING_STARTED.wireName
        } else {
            "NONE"
        }
    }

    fun collectAllJobs(): List<Job> {
        val jobs = mutableListOf<Job>()
        firstResponseWatchdogJob?.let { jobs.add(it) }
        drainJob?.let { jobs.add(it) }
        jobs.addAll(micSendJobs)
        jobs.addAll(writeJobs)
        jobs.add(parentJob)
        return jobs
    }

    fun activeJobCount(): Int {
        var count = 0
        if (firstResponseWatchdogJob?.isActive == true) count += 1
        if (drainJob?.isActive == true) count += 1
        count += micSendJobs.count { it.isActive }
        count += writeJobs.count { it.isActive }
        if (parentJob.isActive && count > 0) {
            // parentJob itself is a SupervisorJob supervising children
            count += 1
        }
        return count
    }

    fun cancelWatchdog() {
        firstResponseWatchdogJob?.cancel()
        firstResponseWatchdogJob = null
    }

    fun dispose(cancelAllJobs: Boolean = true) {
        closed = true
        cancelWatchdog()
        if (cancelAllJobs) {
            drainJob?.cancel()
            drainJob = null
            for (job in micSendJobs) {
                job.cancel()
            }
            for (job in writeJobs) {
                job.cancel()
            }
            parentJob.cancel()
        } else {
            // Complete supervisor job cleanly once finished so no active supervisor leaks
            parentJob.complete()
        }
        micSendJobs.clear()
        writeJobs.clear()
    }
}

/**
 * Snapshot of active coordinator, timer, audio hardware, and Live session resources
 * used to verify zero leaks after Turns 1, 7, 10, and 20+.
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
