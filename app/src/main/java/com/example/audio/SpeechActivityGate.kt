package com.example.audio

import kotlin.math.max
import kotlin.math.min

/**
 * Explicit Phase 06B manual speech activity gate states.
 * Note: AutomaticActivityDetection is intentionally disabled in GeminiLiveConnectionAdapter,
 * so omniVoice owns these boundaries locally before sending activityStart or PCM audio.
 */
enum class SpeechGateState(val wireCode: String) {
    IDLE("IDLE"),
    POST_PLAYBACK_ECHO_GUARD("POST_PLAYBACK_ECHO_GUARD"),
    WAITING_FOR_SPEECH("WAITING_FOR_SPEECH"),
    SPEECH_ACTIVE("SPEECH_ACTIVE")
}

/**
 * Redacted, non-sensitive telemetry snapshot for the local [SpeechActivityGate].
 * Never stores or logs raw microphone PCM audio.
 */
data class SpeechGateTelemetry(
    val gateState: SpeechGateState = SpeechGateState.IDLE,
    val detectorName: String = SileroVadDetector.DETECTOR_NAME,
    val detectorVersion: String = SileroVadDetector.DETECTOR_VERSION,
    val isModelLoaded: Boolean = false,
    val modelLoadError: String? = null,
    val lastSpeechDecision: Boolean = false,
    val lastSpeechProbability: Float = 0f,
    val noiseFloorRms: Float = 0.002f,
    val activeSpeechStartThresholdRms: Float = 0.012f,
    val consecutiveSpeechDurationMs: Long = 0L,
    val candidateConfirmationCount: Int = 0,
    val preRollDurationMs: Long = 0L,
    val preRollBytes: Int = 0,
    val falseStartsRejected: Int = 0,
    val activityStartCount: Int = 0,
    val activityEndCount: Int = 0,
    val isPostPlaybackGuardActive: Boolean = false
)

sealed interface SpeechGateDecision {
    data object IgnoredDuringGuard : SpeechGateDecision
    data class DetectorUnavailable(val reason: String) : SpeechGateDecision
    data object WaitingSilent : SpeechGateDecision
    data class SpeechConfirmedStart(
        val preRollPcmChunks: List<ByteArray>,
        val preRollTotalBytes: Int,
        val preRollDurationMs: Long
    ) : SpeechGateDecision

    data class StreamActiveFrame(
        val pcmBytes: ByteArray,
        val shouldEndSpeechTurn: Boolean,
        val trailingSilenceDurationMs: Long
    ) : SpeechGateDecision
}

/**
 * Local neural VAD-driven speech-activity gate and 300 ms chronological pre-roll ring buffer (Phase 06B).
 *
 * Guarantees:
 * - Drives speech-vs-noise decisions from [SpeechNeuralDetector] ([SileroVadDetector] v6.2.3 in production),
 *   reframing incoming 16 kHz mono callbacks into aligned model windows (512 samples = 32 ms for Silero)
 *   while preserving leftover samples chronologically across callbacks.
 * - Retains RMS tracking strictly for redacted diagnostics (never confirms speech independently when
 *   [SpeechNeuralDetector.requiresLoadedModel] is true).
 * - Preserves recurrent neural detector state across consecutive windows; resets detector state only at
 *   session/turn boundaries, echo guard exits, or capture discontinuities.
 * - Never sends `activityStart` or silent/noise PCM while in `WAITING_FOR_SPEECH` or `POST_PLAYBACK_ECHO_GUARD`.
 * - Streams complete chronological 300 ms pre-roll once at speech onset, then never drops low-confidence
 *   frames mid-utterance during `SPEECH_ACTIVE`, ending turns after ~700 ms trailing silence.
 */
class SpeechActivityGate(
    private val speechDetector: SpeechNeuralDetector = DefaultNoContextSyntheticSpeechDetector(),
    private val sampleRateHz: Int = PcmResampler.TARGET_SAMPLE_RATE_HZ,
    private val preRollTargetMs: Long = 300L,
    private val speechConfirmDurationMs: Long = if (speechDetector.requiresLoadedModel) 160L else 120L,
    private val speechOnsetProbabilityThreshold: Float = 0.55f,
    private val speechContinuityProbabilityThreshold: Float = 0.35f,
    private val minConsecutiveSpeechWindows: Int = if (speechDetector.requiresLoadedModel) 5 else 3,
    private val trailingSilenceDurationMs: Long = 700L,
    private val postPlaybackEchoGuardMs: Long = 500L,
    private val baseMinThresholdRms: Float = 0.012f,
    private val noiseMultiplier: Float = 2.4f,
    private val noiseMarginRms: Float = 0.004f,
    initialNoiseFloorRms: Float = 0.002f,
    private val minNoiseFloorRms: Float = 0.0005f,
    private val maxNoiseFloorRms: Float = 0.15f,
    private val monotonicClockMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private data class PreRollFrame(
        val sampleCount: Int,
        val pcmBytes: ByteArray
    )

    private val windowSizeSamples: Int = speechDetector.windowSizeSamples.coerceAtLeast(1)
    private val reframerBuffer = ShortArray(windowSizeSamples)
    private var reframerFilled: Int = 0

    private val maxPreRollSamples: Int =
        ((sampleRateHz.toLong() * preRollTargetMs) / 1000L).toInt().coerceAtLeast(sampleRateHz / 10)
    private val preRollQueue = ArrayDeque<PreRollFrame>()
    private var preRollSampleCount: Int = 0
    private var preRollByteCount: Int = 0

    private var gateState: SpeechGateState = SpeechGateState.IDLE
    private var guardExpiresAtMs: Long = 0L

    private var noiseFloorRms: Float = initialNoiseFloorRms.coerceIn(minNoiseFloorRms, maxNoiseFloorRms)
    private var activeSpeechStartThresholdRms: Float = computeThreshold(noiseFloorRms)

    private var lastWaitingFrameEndMs: Long? = null
    private var lastActiveFrameEndMs: Long? = null
    private var speechStreakStartMs: Long? = null
    private var consecutiveSpeechWindows: Int = 0
    private var consecutiveVoiceSamples: Long = 0L
    private var consecutiveSpeechDurationMs: Long = 0L
    private var hadPositiveSpeechInWaiting: Boolean = false

    private var lastSpeechDecision: Boolean = false
    private var lastSpeechProbability: Float = 0f
    private var lastCompletedActiveDecisionWasSpeech: Boolean = true

    private var lastVoiceFrameMs: Long? = null
    private var trailingSilenceStartMs: Long? = null
    private var trailingSilenceSamples: Long = 0L
    private var trailingSilenceElapsedMs: Long = 0L
    private var endOfSpeechTriggered: Boolean = false

    private var falseStartsRejected: Int = 0
    private var sessionActivityStartCount: Int = 0
    private var sessionActivityEndCount: Int = 0

    @Synchronized
    fun beginWaitingForSpeech(resetSessionCounters: Boolean = false) {
        clearTurnTransientStateLocked(resetNoiseFloor = resetSessionCounters)
        gateState = SpeechGateState.WAITING_FOR_SPEECH
        guardExpiresAtMs = 0L
        if (resetSessionCounters) {
            falseStartsRejected = 0
            sessionActivityStartCount = 0
            sessionActivityEndCount = 0
        }
    }

    @Synchronized
    fun beginPostPlaybackEchoGuard() {
        clearTurnTransientStateLocked(resetNoiseFloor = false)
        val nowMs = monotonicClockMs()
        if (postPlaybackEchoGuardMs <= 0L) {
            gateState = SpeechGateState.WAITING_FOR_SPEECH
            guardExpiresAtMs = 0L
        } else {
            gateState = SpeechGateState.POST_PLAYBACK_ECHO_GUARD
            guardExpiresAtMs = nowMs + postPlaybackEchoGuardMs
        }
    }

    @Synchronized
    fun cancelAndResetToIdle(resetSessionCounters: Boolean = false) {
        clearTurnTransientStateLocked(resetNoiseFloor = resetSessionCounters)
        gateState = SpeechGateState.IDLE
        guardExpiresAtMs = 0L
        if (resetSessionCounters) {
            falseStartsRejected = 0
            sessionActivityStartCount = 0
            sessionActivityEndCount = 0
        }
    }

    @Synchronized
    fun refreshGuardIfExpired(): Boolean {
        if (gateState != SpeechGateState.POST_PLAYBACK_ECHO_GUARD) return false
        val nowMs = monotonicClockMs()
        if (nowMs >= guardExpiresAtMs) {
            gateState = SpeechGateState.WAITING_FOR_SPEECH
            guardExpiresAtMs = 0L
            resetReframerAndDetectorLocked()
            return true
        }
        return false
    }

    /**
     * Evaluates a captured 16 kHz mono PCM frame sequentially through the aligned neural VAD
     * reframer and gate state machine.
     */
    @Synchronized
    fun onFrameCaptured(
        samples16kHz: ShortArray,
        pcmBytes: ByteArray,
        rmsLinear: Float,
        capturedAtMs: Long? = null
    ): SpeechGateDecision {
        if (samples16kHz.isEmpty() || pcmBytes.isEmpty()) {
            return SpeechGateDecision.IgnoredDuringGuard
        }
        if (speechDetector.requiresLoadedModel && !speechDetector.isModelLoaded) {
            return SpeechGateDecision.DetectorUnavailable(
                speechDetector.loadError ?: "Silero VAD neural model is not loaded."
            )
        }

        val nowMs = capturedAtMs ?: monotonicClockMs()
        val sampleCount = samples16kHz.size
        val frameDurationMs = ((sampleCount.toLong() * 1000L) / sampleRateHz.toLong()).coerceAtLeast(1L)

        if (gateState == SpeechGateState.POST_PLAYBACK_ECHO_GUARD) {
            if (nowMs < guardExpiresAtMs) {
                return SpeechGateDecision.IgnoredDuringGuard
            }
            gateState = SpeechGateState.WAITING_FOR_SPEECH
            guardExpiresAtMs = 0L
            resetReframerAndDetectorLocked()
            lastWaitingFrameEndMs = nowMs - frameDurationMs
        }

        if (gateState == SpeechGateState.IDLE) {
            return SpeechGateDecision.IgnoredDuringGuard
        }

        if (gateState == SpeechGateState.SPEECH_ACTIVE) {
            val prevActiveEnd = lastActiveFrameEndMs
            if (prevActiveEnd != null && (nowMs - prevActiveEnd) > 250L) {
                resetReframerAndDetectorLocked()
            }
            lastActiveFrameEndMs = nowMs

            val windowResults = evaluateAlignedWindowsLocked(samples16kHz, rmsLinear)
            if (windowResults.isNotEmpty()) {
                var sawContinuitySpeech = false
                for (res in windowResults) {
                    lastSpeechProbability = res.speechProbability
                    val isSpeechCont = isContinuitySpeechWindow(res)
                    lastSpeechDecision = isSpeechCont
                    if (isSpeechCont) {
                        sawContinuitySpeech = true
                    }
                }
                lastCompletedActiveDecisionWasSpeech = sawContinuitySpeech
            }

            if (lastCompletedActiveDecisionWasSpeech) {
                trailingSilenceStartMs = null
                trailingSilenceSamples = 0L
                trailingSilenceElapsedMs = 0L
                lastVoiceFrameMs = nowMs
                return SpeechGateDecision.StreamActiveFrame(
                    pcmBytes = pcmBytes,
                    shouldEndSpeechTurn = false,
                    trailingSilenceDurationMs = 0L
                )
            } else {
                val startRef = trailingSilenceStartMs ?: (lastVoiceFrameMs ?: (nowMs - frameDurationMs))
                trailingSilenceStartMs = startRef
                trailingSilenceSamples += sampleCount.toLong()
                val sampleSilenceMs = (trailingSilenceSamples * 1000L) / sampleRateHz.toLong()
                val wallSilenceMs = (nowMs - startRef).coerceAtLeast(0L)
                trailingSilenceElapsedMs = max(sampleSilenceMs, wallSilenceMs)
                val shouldEnd = !endOfSpeechTriggered && trailingSilenceElapsedMs >= trailingSilenceDurationMs
                if (shouldEnd) {
                    endOfSpeechTriggered = true
                    sessionActivityEndCount += 1
                }
                return SpeechGateDecision.StreamActiveFrame(
                    pcmBytes = pcmBytes,
                    shouldEndSpeechTurn = shouldEnd,
                    trailingSilenceDurationMs = trailingSilenceElapsedMs
                )
            }
        }

        // WAITING_FOR_SPEECH: reset recurrent detector + partial window at capture discontinuity
        val prevWaitingEnd = lastWaitingFrameEndMs
        if (prevWaitingEnd != null && (nowMs - prevWaitingEnd) > 250L) {
            if (consecutiveSpeechWindows > 0 || consecutiveSpeechDurationMs > 0L) {
                falseStartsRejected += 1
            }
            resetCandidateStreakLocked()
            resetReframerAndDetectorLocked()
        }

        // Buffer chronologically into the 300 ms pre-roll ring buffer
        appendToPreRollLocked(sampleCount, pcmBytes)

        // Diagnostics-only adaptive ambient RMS tracking (never confirms speech when neural VAD is used)
        val alpha = if (rmsLinear < noiseFloorRms) 0.20f else 0.08f
        noiseFloorRms = (noiseFloorRms + alpha * (rmsLinear - noiseFloorRms))
            .coerceIn(minNoiseFloorRms, maxNoiseFloorRms)
        activeSpeechStartThresholdRms = computeThreshold(noiseFloorRms)

        val windowResults = evaluateAlignedWindowsLocked(samples16kHz, rmsLinear)
        lastWaitingFrameEndMs = nowMs

        if (windowResults.isEmpty()) {
            // Partial window waiting for remaining samples to reach detector's windowSizeSamples.
            return SpeechGateDecision.WaitingSilent
        }

        var confirmedThisCallback = false
        for (res in windowResults) {
            lastSpeechProbability = res.speechProbability
            val isOnsetPositive = isOnsetSpeechWindow(res)
            lastSpeechDecision = isOnsetPositive

            if (confirmedThisCallback) {
                if (isContinuitySpeechWindow(res)) {
                    lastVoiceFrameMs = nowMs
                    lastCompletedActiveDecisionWasSpeech = true
                    trailingSilenceStartMs = null
                    trailingSilenceElapsedMs = 0L
                }
                continue
            }

            if (isOnsetPositive) {
                hadPositiveSpeechInWaiting = true
                consecutiveSpeechWindows += 1
                consecutiveVoiceSamples += windowSizeSamples.toLong()
                val streakStart = speechStreakStartMs ?: (prevWaitingEnd ?: (nowMs - frameDurationMs))
                speechStreakStartMs = streakStart
                val sampleDurationMs = (consecutiveVoiceSamples * 1000L) / sampleRateHz.toLong()
                val wallElapsedMs = (nowMs - streakStart).coerceAtLeast(0L)
                consecutiveSpeechDurationMs = max(sampleDurationMs, wallElapsedMs)

                if (sampleDurationMs >= speechConfirmDurationMs &&
                    consecutiveSpeechWindows >= minConsecutiveSpeechWindows
                ) {
                    confirmedThisCallback = true
                }
            } else {
                if (consecutiveSpeechWindows > 0 || consecutiveSpeechDurationMs > 0L) {
                    falseStartsRejected += 1
                }
                resetCandidateStreakLocked()
            }
        }

        if (confirmedThisCallback) {
            val chunks = drainPreRollChunksLocked()
            val totalBytes = chunks.sumOf { it.size }
            val totalSamples = totalBytes / 2
            val preRollMs = (totalSamples.toLong() * 1000L) / sampleRateHz.toLong()

            gateState = SpeechGateState.SPEECH_ACTIVE
            sessionActivityStartCount += 1
            lastVoiceFrameMs = nowMs
            lastActiveFrameEndMs = nowMs
            lastCompletedActiveDecisionWasSpeech = true
            trailingSilenceStartMs = null
            trailingSilenceElapsedMs = 0L
            endOfSpeechTriggered = false

            return SpeechGateDecision.SpeechConfirmedStart(
                preRollPcmChunks = chunks,
                preRollTotalBytes = totalBytes,
                preRollDurationMs = preRollMs
            )
        }

        return SpeechGateDecision.WaitingSilent
    }

    /**
     * Legacy byte-oriented helper retained for direct unit tests; decodes 16-bit PCM little-endian
     * samples and forwards to [onFrameCaptured].
     */
    @Synchronized
    fun onFrameCaptured(
        sampleCount: Int,
        pcmBytes: ByteArray,
        rmsLinear: Float
    ): SpeechGateDecision {
        val count = min(sampleCount, pcmBytes.size / 2)
        val samples = ShortArray(count)
        var byteIdx = 0
        for (i in 0 until count) {
            val lo = pcmBytes[byteIdx].toInt() and 0xFF
            val hi = pcmBytes[byteIdx + 1].toInt() shl 8
            samples[i] = (hi or lo).toShort()
            byteIdx += 2
        }
        return onFrameCaptured(samples, pcmBytes, rmsLinear)
    }

    @Synchronized
    fun hasObservedWaitingSpeechCandidate(): Boolean =
        hadPositiveSpeechInWaiting || lastSpeechDecision

    /**
     * Used only when the operator explicitly invokes manual "Send Spoken Question" while
     * in [SpeechGateState.WAITING_FOR_SPEECH] with real speech captured in the pre-roll buffer
     * that has not yet crossed the automatic onset confirmation duration.
     */
    @Synchronized
    fun consumePreRollForManualSubmit(): List<ByteArray> {
        if (gateState != SpeechGateState.WAITING_FOR_SPEECH) {
            return emptyList()
        }
        val chunks = drainPreRollChunksLocked()
        gateState = SpeechGateState.SPEECH_ACTIVE
        sessionActivityStartCount += 1
        return chunks
    }

    @Synchronized
    fun recordManualEndOfSpeech() {
        if (!endOfSpeechTriggered) {
            endOfSpeechTriggered = true
            sessionActivityEndCount += 1
        }
        gateState = SpeechGateState.IDLE
    }

    @Synchronized
    fun remainingEchoGuardMs(): Long {
        if (gateState != SpeechGateState.POST_PLAYBACK_ECHO_GUARD) return 0L
        return (guardExpiresAtMs - monotonicClockMs()).coerceAtLeast(0L)
    }

    @Synchronized
    fun telemetrySnapshot(): SpeechGateTelemetry {
        val currentPreRollMs = (preRollSampleCount.toLong() * 1000L) / sampleRateHz.toLong()
        return SpeechGateTelemetry(
            gateState = gateState,
            detectorName = speechDetector.detectorName,
            detectorVersion = speechDetector.detectorVersion,
            isModelLoaded = speechDetector.isModelLoaded,
            modelLoadError = speechDetector.loadError,
            lastSpeechDecision = lastSpeechDecision,
            lastSpeechProbability = lastSpeechProbability,
            noiseFloorRms = noiseFloorRms,
            activeSpeechStartThresholdRms = activeSpeechStartThresholdRms,
            consecutiveSpeechDurationMs = consecutiveSpeechDurationMs,
            candidateConfirmationCount = consecutiveSpeechWindows,
            preRollDurationMs = currentPreRollMs,
            preRollBytes = preRollByteCount,
            falseStartsRejected = falseStartsRejected,
            activityStartCount = sessionActivityStartCount,
            activityEndCount = sessionActivityEndCount,
            isPostPlaybackGuardActive = gateState == SpeechGateState.POST_PLAYBACK_ECHO_GUARD
        )
    }

    private fun isOnsetSpeechWindow(res: NeuralVadInferenceResult): Boolean {
        return if (speechDetector.requiresLoadedModel) {
            res.speechProbability >= speechOnsetProbabilityThreshold
        } else {
            res.isSpeechWindow
        }
    }

    private fun isContinuitySpeechWindow(res: NeuralVadInferenceResult): Boolean {
        return if (speechDetector.requiresLoadedModel) {
            res.speechProbability >= speechContinuityProbabilityThreshold
        } else {
            res.isSpeechWindow
        }
    }

    private fun evaluateAlignedWindowsLocked(
        samples16kHz: ShortArray,
        rmsLinear: Float
    ): List<NeuralVadInferenceResult> {
        val results = mutableListOf<NeuralVadInferenceResult>()
        var srcOffset = 0
        while (srcOffset < samples16kHz.size) {
            val needed = windowSizeSamples - reframerFilled
            val available = samples16kHz.size - srcOffset
            val toCopy = min(needed, available)
            System.arraycopy(samples16kHz, srcOffset, reframerBuffer, reframerFilled, toCopy)
            reframerFilled += toCopy
            srcOffset += toCopy

            if (reframerFilled == windowSizeSamples) {
                val res = speechDetector.inferWindow(reframerBuffer, rmsLinear)
                results.add(res)
                reframerFilled = 0
            }
        }
        return results
    }

    private fun resetReframerAndDetectorLocked() {
        reframerFilled = 0
        reframerBuffer.fill(0)
        speechDetector.resetState()
    }

    private fun resetCandidateStreakLocked() {
        speechStreakStartMs = null
        consecutiveSpeechWindows = 0
        consecutiveVoiceSamples = 0L
        consecutiveSpeechDurationMs = 0L
        hadPositiveSpeechInWaiting = false
    }

    private fun computeThreshold(floorRms: Float): Float {
        return max(baseMinThresholdRms, floorRms * noiseMultiplier + noiseMarginRms)
    }

    private fun appendToPreRollLocked(sampleCount: Int, pcmBytes: ByteArray) {
        if (sampleCount >= maxPreRollSamples) {
            preRollQueue.clear()
            val keepBytes = maxPreRollSamples * 2
            val startIdx = (pcmBytes.size - keepBytes).coerceAtLeast(0)
            val trimmed = pcmBytes.copyOfRange(startIdx, pcmBytes.size)
            val trimmedSamples = trimmed.size / 2
            preRollQueue.addLast(PreRollFrame(trimmedSamples, trimmed))
            preRollSampleCount = trimmedSamples
            preRollByteCount = trimmed.size
            return
        }

        preRollQueue.addLast(PreRollFrame(sampleCount, pcmBytes))
        preRollSampleCount += sampleCount
        preRollByteCount += pcmBytes.size

        while (preRollSampleCount > maxPreRollSamples && preRollQueue.isNotEmpty()) {
            val oldest = preRollQueue.first()
            val excessSamples = preRollSampleCount - maxPreRollSamples
            if (oldest.sampleCount <= excessSamples) {
                preRollQueue.removeFirst()
                preRollSampleCount -= oldest.sampleCount
                preRollByteCount -= oldest.pcmBytes.size
            } else {
                preRollQueue.removeFirst()
                val dropBytes = excessSamples * 2
                val remainingBytes = oldest.pcmBytes.copyOfRange(dropBytes, oldest.pcmBytes.size)
                val remainingSamples = remainingBytes.size / 2
                preRollQueue.addFirst(PreRollFrame(remainingSamples, remainingBytes))
                preRollSampleCount -= excessSamples
                preRollByteCount -= dropBytes
                break
            }
        }
    }

    private fun drainPreRollChunksLocked(): List<ByteArray> {
        val list = preRollQueue.map { it.pcmBytes }
        preRollQueue.clear()
        preRollSampleCount = 0
        preRollByteCount = 0
        return list
    }

    private fun clearTurnTransientStateLocked(resetNoiseFloor: Boolean) {
        preRollQueue.clear()
        preRollSampleCount = 0
        preRollByteCount = 0
        lastWaitingFrameEndMs = null
        lastActiveFrameEndMs = null
        resetCandidateStreakLocked()
        resetReframerAndDetectorLocked()
        lastSpeechDecision = false
        lastSpeechProbability = 0f
        lastCompletedActiveDecisionWasSpeech = true
        lastVoiceFrameMs = null
        trailingSilenceStartMs = null
        trailingSilenceSamples = 0L
        trailingSilenceElapsedMs = 0L
        endOfSpeechTriggered = false
        if (resetNoiseFloor) {
            noiseFloorRms = 0.002f.coerceIn(minNoiseFloorRms, maxNoiseFloorRms)
            activeSpeechStartThresholdRms = computeThreshold(noiseFloorRms)
        }
    }
}
