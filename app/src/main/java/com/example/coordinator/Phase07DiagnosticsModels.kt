package com.example.coordinator

import com.example.BuildConfig
import com.example.live.DiagnosticRedactor
import com.example.live.LiveConfigConstants
import kotlin.math.ceil

/**
 * Computes a non-negative duration in milliseconds between [startMs] and [endMs] on the single
 * monotonic clock. Returns `null` (unavailable) if either timestamp is missing (`null`) or if
 * [endMs] precedes [startMs] (out-of-order event rejection).
 */
fun safeMonotonicDurationMs(startMs: Long?, endMs: Long?): Long? {
    if (startMs == null || endMs == null) return null
    if (endMs < startMs) return null
    return endMs - startMs
}

/**
 * Formats a monotonic timestamp in milliseconds or `"UNAVAILABLE"` when missing.
 */
fun formatMonotonicTimestampMs(timestampMs: Long?): String =
    timestampMs?.let { "${it} ms" } ?: "UNAVAILABLE"

/**
 * Formats a derived duration in milliseconds or `"UNAVAILABLE"` when missing.
 */
fun formatDurationMs(durationMs: Long?): String =
    durationMs?.let { "${it} ms" } ?: "UNAVAILABLE"

/**
 * Phase 07A startup timing snapshot, tracked separately from per-turn response latency.
 */
data class Phase07StartupTimingSnapshot(
    val sessionId: String = "NONE",
    val initialTurnId: String = "NONE",
    val turnGenerationWireId: String = "NONE",
    val liveSessionGenerationWireId: String = "NONE",
    val captureGenerationWireId: String = "NONE",
    val tStartRequestedMs: Long? = null,
    val tSetupAcceptedMs: Long? = null,
    val tMicReadyMs: Long? = null,
    val isSyntheticOrTestMeasurement: Boolean = false
) {
    val connectionSetupDurationMs: Long?
        get() = safeMonotonicDurationMs(tStartRequestedMs, tSetupAcceptedMs)

    val startupToMicReadyDurationMs: Long?
        get() = safeMonotonicDurationMs(tStartRequestedMs, tMicReadyMs)

    val setupAcceptedToMicReadyDurationMs: Long?
        get() = safeMonotonicDurationMs(tSetupAcceptedMs, tMicReadyMs)
}

/**
 * Phase 07A per-turn timing snapshot bound to session and turn/capture/live/playback generation IDs.
 *
 * Separates:
 * - Startup timing (`tStartRequestedMs` -> `tSetupAcceptedMs` -> `tMicReadyMs`)
 * - Speech endpointing delay (`tLastUserSpeechMs` -> `tSpeechEndMs`)
 * - Client transport end-of-turn submission (`tEndSubmittedMs`, distinct from server acknowledgment `tServerInputAckMs`)
 * - Server response & audio wait (`tEndSubmittedMs` -> `tFirstResponseMs` / `tFirstAudioReceivedMs`; handshake events excluded)
 * - Playback buffering (`tFirstAudioReceivedMs` -> `tFirstHardwareWriteMs`)
 * - Output-start latency ESTIMATE (`tSpeechEndMs` -> `tFirstHardwareWriteMs`; explicitly labelled as an ESTIMATE, not physical acoustic audibility)
 * - Speaking rate and playback duration (`tFirstHardwareWriteMs` -> `tPlaybackDrainedMs`, kept separate from response latency)
 * - Capture-restoration delay (`tPlaybackDrainedMs` -> `tNextMicReadyMs`)
 */
data class Phase07TurnTimingSnapshot(
    val sessionId: String = "NONE",
    val turnId: String = "NONE",
    val turnGenerationWireId: String = "NONE",
    val liveSessionGenerationWireId: String = "NONE",
    val captureGenerationWireId: String = "NONE",
    val playbackGenerationWireId: String = "NONE",
    val tStartRequestedMs: Long? = null,
    val tSetupAcceptedMs: Long? = null,
    val tMicReadyMs: Long? = null,
    val tLastUserSpeechMs: Long? = null,
    val tSpeechEndMs: Long? = null,
    val tEndSubmittedMs: Long? = null,
    val tServerInputAckMs: Long? = null,
    val tFirstResponseMs: Long? = null,
    val tFirstAudioReceivedMs: Long? = null,
    val tFirstHardwareWriteMs: Long? = null,
    val tPlaybackDrainedMs: Long? = null,
    val tNextMicReadyMs: Long? = null,
    val framesReceivedFromServer: Long = 0L,
    val bytesReceivedFromServer: Long = 0L,
    val chunksReceivedFromServer: Int = 0,
    val framesQueuedInAdapter: Long = 0L,
    val bytesQueuedInAdapter: Long = 0L,
    val framesAcceptedByHardware: Long = 0L,
    val bytesAcceptedByHardware: Long = 0L,
    val framesPlayedByHardware: Long = 0L,
    val paddingFramesWrittenToHardware: Long = 0L,
    val paddingBytesWrittenToHardware: Long = 0L,
    val verifiedSampleRateHz: Int = 0,
    val redactedOutputWordCount: Int = 0,
    val isSyntheticOrTestMeasurement: Boolean = false
) {
    val startupDurationMs: Long?
        get() = safeMonotonicDurationMs(tStartRequestedMs, tMicReadyMs)

    val connectionSetupDurationMs: Long?
        get() = safeMonotonicDurationMs(tStartRequestedMs, tSetupAcceptedMs)

    val speechEndpointingDelayMs: Long?
        get() = safeMonotonicDurationMs(tLastUserSpeechMs, tSpeechEndMs)

    val endOfTurnSubmissionDelayMs: Long?
        get() = safeMonotonicDurationMs(tSpeechEndMs, tEndSubmittedMs)

    val transportToServerAckDelayMs: Long?
        get() = safeMonotonicDurationMs(tEndSubmittedMs, tServerInputAckMs)

    val serverResponseWaitMs: Long?
        get() = safeMonotonicDurationMs(tEndSubmittedMs, tFirstResponseMs)

    val serverAudioWaitMs: Long?
        get() = safeMonotonicDurationMs(tEndSubmittedMs, tFirstAudioReceivedMs)

    val playbackBufferingDelayMs: Long?
        get() = safeMonotonicDurationMs(tFirstAudioReceivedMs, tFirstHardwareWriteMs)

    /**
     * ESTIMATE of output-start latency (`tSpeechEndMs` -> `tFirstHardwareWriteMs`).
     * Measures elapsed monotonic time from local end-of-speech detection to the first positive
     * `AudioTrack.write` return; does NOT measure physical acoustic speaker audibility.
     */
    val outputStartLatencyEstimateMs: Long?
        get() = safeMonotonicDurationMs(tSpeechEndMs, tFirstHardwareWriteMs)

    /**
     * ESTIMATE from client transport end-of-turn submission to first positive `AudioTrack.write`.
     */
    val transportSubmitToFirstHardwareWriteEstimateMs: Long?
        get() = safeMonotonicDurationMs(tEndSubmittedMs, tFirstHardwareWriteMs)

    /**
     * Hardware playback duration (`tFirstHardwareWriteMs` -> `tPlaybackDrainedMs`),
     * tracked strictly separately from response latency.
     */
    val playbackDurationMs: Long?
        get() = safeMonotonicDurationMs(tFirstHardwareWriteMs, tPlaybackDrainedMs)

    /**
     * Audio content duration computed from accepted PCM frames and verified sample rate,
     * tracked strictly separately from response latency.
     */
    val audioContentDurationMs: Long?
        get() = if (verifiedSampleRateHz > 0 && framesAcceptedByHardware > 0L) {
            (framesAcceptedByHardware * 1000L) / verifiedSampleRateHz.toLong()
        } else {
            null
        }

    /**
     * Speaking rate estimate in words per minute (WPM) derived from non-sensitive output word count
     * and playback duration without retaining transcript text. Kept separate from response latency.
     */
    val speakingRateWpmEstimate: Int?
        get() {
            val durMs = playbackDurationMs ?: audioContentDurationMs
            return if (durMs != null && durMs > 0L && redactedOutputWordCount > 0) {
                ((redactedOutputWordCount.toLong() * 60_000L) / durMs).toInt()
            } else {
                null
            }
        }

    /**
     * Capture-restoration delay (`tPlaybackDrainedMs` -> `tNextMicReadyMs`).
     */
    val captureRestorationDelayMs: Long?
        get() = safeMonotonicDurationMs(tPlaybackDrainedMs, tNextMicReadyMs)
}

/**
 * Outcome category for each attempted spoken turn in Phase 07C performance summaries.
 */
enum class Phase07TurnOutcomeCategory(val wireCode: String) {
    COMPLETED("COMPLETED"),
    FAILED("FAILED"),
    INTERRUPTED("INTERRUPTED"),
    TIMED_OUT("TIMED_OUT"),
    NO_SPEECH_REJECTED("NO_SPEECH_REJECTED")
}

/**
 * Bounded per-turn outcome and timing record retained for the Phase 07C 20-turn window.
 * Never retains transcript text, audio bytes, or sensitive identifiers.
 */
data class Phase07TurnOutcomeRecord(
    val sessionId: String,
    val turnId: String,
    val turnGenerationWireId: String,
    val category: Phase07TurnOutcomeCategory,
    val outcomeCode: String,
    val timingSnapshot: Phase07TurnTimingSnapshot,
    val isSyntheticOrTestMeasurement: Boolean = timingSnapshot.isSyntheticOrTestMeasurement
)

/**
 * Honest statistical distribution computed from real valid samples (`n`) with explicit disclosure
 * of missing sample count (`missingSampleCount`), exact median, and nearest-rank p95 (`ceil(0.95 * n)`).
 * Returns `null` values (`UNAVAILABLE`) when `validSampleCount == 0`.
 */
data class Phase07MetricDistribution(
    val metricName: String,
    val unit: String = "ms",
    val isEstimate: Boolean = false,
    val validSampleCount: Int = 0,
    val missingSampleCount: Int = 0,
    val medianValue: Long? = null,
    val p95NearestRankValue: Long? = null,
    val minValue: Long? = null,
    val maxValue: Long? = null
) {
    fun toFormattedSummaryLine(): String {
        val estimateTag = if (isEstimate) " [ESTIMATE]" else ""
        if (validSampleCount == 0 || medianValue == null || p95NearestRankValue == null) {
            return "$metricName$estimateTag: UNAVAILABLE (validSamples=0, missingSamples=$missingSampleCount)"
        }
        return "$metricName$estimateTag: median=$medianValue $unit, p95(nearest-rank)=$p95NearestRankValue $unit " +
            "(validSamples=$validSampleCount, missingSamples=$missingSampleCount, min=$minValue $unit, max=$maxValue $unit)"
    }

    companion object {
        fun fromSamples(
            metricName: String,
            rawValues: List<Long?>,
            unit: String = "ms",
            isEstimate: Boolean = false
        ): Phase07MetricDistribution {
            val validSorted = rawValues.mapNotNull { value ->
                value?.takeIf { it >= 0L }
            }.sorted()
            val validCount = validSorted.size
            val missingCount = (rawValues.size - validCount).coerceAtLeast(0)
            if (validCount == 0) {
                return Phase07MetricDistribution(
                    metricName = metricName,
                    unit = unit,
                    isEstimate = isEstimate,
                    validSampleCount = 0,
                    missingSampleCount = missingCount,
                    medianValue = null,
                    p95NearestRankValue = null,
                    minValue = null,
                    maxValue = null
                )
            }
            val median = if (validCount % 2 == 1) {
                validSorted[validCount / 2]
            } else {
                val lower = validSorted[(validCount / 2) - 1]
                val upper = validSorted[validCount / 2]
                (lower + upper) / 2L
            }
            val nearestRankIndex = (ceil(0.95 * validCount.toDouble()).toInt() - 1)
                .coerceIn(0, validCount - 1)
            val p95 = validSorted[nearestRankIndex]
            return Phase07MetricDistribution(
                metricName = metricName,
                unit = unit,
                isEstimate = isEstimate,
                validSampleCount = validCount,
                missingSampleCount = missingCount,
                medianValue = median,
                p95NearestRankValue = p95,
                minValue = validSorted.first(),
                maxValue = validSorted.last()
            )
        }
    }
}

/**
 * Phase 07C honest performance summary over a bounded 20-turn window.
 *
 * Separates:
 * - Startup duration (`startupDurationDistribution`)
 * - Per-turn latency components (`speechEndpointingDelayDistribution`, `firstAudioWaitDistribution`,
 *   `playbackBufferingDistribution`, `nextCaptureDelayDistribution`, and `outputStartLatencyEstimateDistribution` [ESTIMATE])
 * - Playback duration and speaking rate (`playbackDurationDistributionSeparateFromLatency`,
 *   `speakingRateWpmDistributionSeparateFromLatency`), strictly excluded from response latency.
 * - Synthetic/test measurements are explicitly labeled and excluded from real-device performance summaries.
 * - `WITHIN_ROADMAP_TARGETS` is never reported for a partial sample window (`< 20` valid real-device turns).
 */
data class Phase07PerformanceSummary(
    val maxBoundedTurnWindow: Int = MAX_BOUNDED_TURN_RECORDS,
    val totalAttemptedTurns: Int = 0,
    val completedTurns: Int = 0,
    val failedTurns: Int = 0,
    val interruptedTurns: Int = 0,
    val timedOutTurns: Int = 0,
    val noSpeechRejectedTurns: Int = 0,
    val recordedWindowTurnCount: Int = 0,
    val realDeviceWindowTurnCount: Int = 0,
    val syntheticTestTurnCountExcluded: Int = 0,
    val measurementOriginSummary: String = "NO_MEASUREMENTS",
    val startupDurationDistribution: Phase07MetricDistribution = Phase07MetricDistribution(
        metricName = "startupToMicReadyDuration"
    ),
    val speechEndpointingDelayDistribution: Phase07MetricDistribution = Phase07MetricDistribution(
        metricName = "speechEndpointingDelay"
    ),
    val firstAudioWaitDistribution: Phase07MetricDistribution = Phase07MetricDistribution(
        metricName = "firstAudioWait"
    ),
    val playbackBufferingDistribution: Phase07MetricDistribution = Phase07MetricDistribution(
        metricName = "playbackBufferingDelay"
    ),
    val nextCaptureDelayDistribution: Phase07MetricDistribution = Phase07MetricDistribution(
        metricName = "nextCaptureRestorationDelay"
    ),
    val outputStartLatencyEstimateDistribution: Phase07MetricDistribution = Phase07MetricDistribution(
        metricName = "speechEndToFirstHardwareWriteLatencyEstimate",
        isEstimate = true
    ),
    val playbackDurationDistributionSeparateFromLatency: Phase07MetricDistribution = Phase07MetricDistribution(
        metricName = "playbackDuration_SEPARATE_FROM_LATENCY"
    ),
    val speakingRateWpmDistributionSeparateFromLatency: Phase07MetricDistribution = Phase07MetricDistribution(
        metricName = "speakingRateEstimate_SEPARATE_FROM_LATENCY",
        unit = "WPM"
    ),
    val roadmapTargetMedianThresholdMs: Long = ROADMAP_MEDIAN_TARGET_MS,
    val roadmapTargetP95ThresholdMs: Long = ROADMAP_P95_TARGET_MS,
    val roadmapTargetStatus: String = "MEASUREMENT_PENDING",
    val roadmapTargetDetail: String = "No valid speechEnd-to-firstHardwareWrite latency samples recorded yet.",
    val buildType: String = BuildConfig.BUILD_TYPE,
    val sourceRevision: String = BuildConfig.SOURCE_REVISION.takeIf { it.isNotBlank() && it != "null" }
        ?: "SOURCE_REVISION_UNAVAILABLE",
    val sourceFingerprint: String = BuildConfig.SOURCE_FINGERPRINT,
    val runtimeModelId: String = LiveConfigConstants.SUPPORTED_LIVE_MODEL_ID,
    val configuredVoice: String = LiveConfigConstants.DEFAULT_NATIVE_AUDIO_VOICE,
    val audioRouteLabel: String = "DEFAULT_SYSTEM_ROUTE",
    val contextWindowCompressionActive: Boolean = true,
    val sessionResumptionStatus: String = "INITIAL_NEW_SESSION",
    val contextRestorationStatus: String = "INITIAL_NEW_SESSION"
) {
    companion object {
        const val MAX_BOUNDED_TURN_RECORDS = 20
        const val ROADMAP_MEDIAN_TARGET_MS = 3_000L
        const val ROADMAP_P95_TARGET_MS = 6_000L

        fun fromTurnRecords(
            startupSnapshot: Phase07StartupTimingSnapshot,
            records: List<Phase07TurnOutcomeRecord>,
            totalAttemptedTurns: Int = records.size,
            completedTurns: Int = records.count { it.category == Phase07TurnOutcomeCategory.COMPLETED },
            failedTurns: Int = records.count { it.category == Phase07TurnOutcomeCategory.FAILED },
            interruptedTurns: Int = records.count { it.category == Phase07TurnOutcomeCategory.INTERRUPTED },
            timedOutTurns: Int = records.count { it.category == Phase07TurnOutcomeCategory.TIMED_OUT },
            noSpeechRejectedTurns: Int = records.count { it.category == Phase07TurnOutcomeCategory.NO_SPEECH_REJECTED },
            buildType: String = BuildConfig.BUILD_TYPE,
            sourceRevision: String = BuildConfig.SOURCE_REVISION.takeIf { it.isNotBlank() && it != "null" }
                ?: "SOURCE_REVISION_UNAVAILABLE",
            sourceFingerprint: String = BuildConfig.SOURCE_FINGERPRINT,
            runtimeModelId: String = LiveConfigConstants.SUPPORTED_LIVE_MODEL_ID,
            configuredVoice: String = LiveConfigConstants.DEFAULT_NATIVE_AUDIO_VOICE,
            audioRouteLabel: String = "DEFAULT_SYSTEM_ROUTE",
            contextWindowCompressionActive: Boolean = true,
            sessionResumptionStatus: String = "INITIAL_NEW_SESSION",
            contextRestorationStatus: String = "INITIAL_NEW_SESSION"
        ): Phase07PerformanceSummary {
            val bounded = records.takeLast(MAX_BOUNDED_TURN_RECORDS)
            val realDeviceRecords = bounded.filter {
                !it.isSyntheticOrTestMeasurement && !it.timingSnapshot.isSyntheticOrTestMeasurement
            }
            val syntheticExcludedCount = (bounded.size - realDeviceRecords.size).coerceAtLeast(0)
            val originSummary = when {
                bounded.isEmpty() && startupSnapshot.tStartRequestedMs == null -> "NO_MEASUREMENTS"
                realDeviceRecords.isEmpty() && syntheticExcludedCount > 0 ->
                    "SYNTHETIC_TEST_ONLY (excludedFromRealDeviceSummary=$syntheticExcludedCount)"
                realDeviceRecords.isNotEmpty() && syntheticExcludedCount > 0 ->
                    "MIXED_REAL_DEVICE_AND_SYNTHETIC (realDevice=${realDeviceRecords.size}, syntheticExcluded=$syntheticExcludedCount)"
                else -> "REAL_DEVICE (n=${realDeviceRecords.size})"
            }

            val startupSamples = if (!startupSnapshot.isSyntheticOrTestMeasurement && startupSnapshot.startupToMicReadyDurationMs != null) {
                listOf(startupSnapshot.startupToMicReadyDurationMs)
            } else if (realDeviceRecords.isNotEmpty()) {
                realDeviceRecords.map { it.timingSnapshot.startupDurationMs }.filterNotNull().take(1).ifEmpty { listOf(null) }
            } else {
                emptyList()
            }
            val startupDist = Phase07MetricDistribution.fromSamples(
                metricName = "startupToMicReadyDuration",
                rawValues = startupSamples
            )
            val endpointingDist = Phase07MetricDistribution.fromSamples(
                metricName = "speechEndpointingDelay",
                rawValues = realDeviceRecords.map { it.timingSnapshot.speechEndpointingDelayMs }
            )
            val firstAudioWaitDist = Phase07MetricDistribution.fromSamples(
                metricName = "firstAudioWait",
                rawValues = realDeviceRecords.map { it.timingSnapshot.serverAudioWaitMs }
            )
            val playbackBufferingDist = Phase07MetricDistribution.fromSamples(
                metricName = "playbackBufferingDelay",
                rawValues = realDeviceRecords.map { it.timingSnapshot.playbackBufferingDelayMs }
            )
            val nextCaptureDist = Phase07MetricDistribution.fromSamples(
                metricName = "nextCaptureRestorationDelay",
                rawValues = realDeviceRecords.map { it.timingSnapshot.captureRestorationDelayMs }
            )
            val outputStartEstimateDist = Phase07MetricDistribution.fromSamples(
                metricName = "speechEndToFirstHardwareWriteLatencyEstimate",
                rawValues = realDeviceRecords.map { it.timingSnapshot.outputStartLatencyEstimateMs },
                isEstimate = true
            )
            val playbackDurationDist = Phase07MetricDistribution.fromSamples(
                metricName = "playbackDuration_SEPARATE_FROM_LATENCY",
                rawValues = realDeviceRecords.map { it.timingSnapshot.playbackDurationMs }
            )
            val speakingRateDist = Phase07MetricDistribution.fromSamples(
                metricName = "speakingRateEstimate_SEPARATE_FROM_LATENCY",
                rawValues = realDeviceRecords.map { it.timingSnapshot.speakingRateWpmEstimate?.toLong() },
                unit = "WPM"
            )

            val medianEst = outputStartEstimateDist.medianValue
            val p95Est = outputStartEstimateDist.p95NearestRankValue
            val validRealSamples = outputStartEstimateDist.validSampleCount
            val (targetStatus, targetDetail) = when {
                totalAttemptedTurns == 0 && bounded.isEmpty() -> {
                    "MEASUREMENT_PENDING" to
                        "MEASUREMENT_PENDING (validRealDeviceSamples=0/$MAX_BOUNDED_TURN_RECORDS; target is median <= ${ROADMAP_MEDIAN_TARGET_MS} ms, p95 <= ${ROADMAP_P95_TARGET_MS} ms [ESTIMATE] across $MAX_BOUNDED_TURN_RECORDS comparable valid real-device turns)"
                }
                validRealSamples < MAX_BOUNDED_TURN_RECORDS || medianEst == null || p95Est == null -> {
                    "INSUFFICIENT_SAMPLES" to
                        "INSUFFICIENT_SAMPLES [PARTIAL_SAMPLE_WINDOW (validRealDeviceSamples=$validRealSamples/$MAX_BOUNDED_TURN_RECORDS, syntheticTestTurnsExcluded=$syntheticExcludedCount)]: requires $MAX_BOUNDED_TURN_RECORDS comparable valid real-device turns before evaluating roadmap latency targets (target median <= ${ROADMAP_MEDIAN_TARGET_MS} ms, p95 <= ${ROADMAP_P95_TARGET_MS} ms [ESTIMATE])"
                }
                medianEst <= ROADMAP_MEDIAN_TARGET_MS && p95Est <= ROADMAP_P95_TARGET_MS -> {
                    "WITHIN_ROADMAP_TARGETS" to
                        "WITHIN_ROADMAP_TARGETS [FULL_20_TURN_WINDOW (n=$validRealSamples)]: median=${medianEst} ms (<= ${ROADMAP_MEDIAN_TARGET_MS} ms), p95=${p95Est} ms (<= ${ROADMAP_P95_TARGET_MS} ms) [ESTIMATE]"
                }
                else -> {
                    "EXCEEDS_ROADMAP_TARGETS" to
                        "EXCEEDS_ROADMAP_TARGETS [FULL_20_TURN_WINDOW (n=$validRealSamples)]: median=${medianEst} ms (target <= ${ROADMAP_MEDIAN_TARGET_MS} ms), p95=${p95Est} ms (target <= ${ROADMAP_P95_TARGET_MS} ms) [ESTIMATE]"
                }
            }

            return Phase07PerformanceSummary(
                maxBoundedTurnWindow = MAX_BOUNDED_TURN_RECORDS,
                totalAttemptedTurns = totalAttemptedTurns,
                completedTurns = completedTurns,
                failedTurns = failedTurns,
                interruptedTurns = interruptedTurns,
                timedOutTurns = timedOutTurns,
                noSpeechRejectedTurns = noSpeechRejectedTurns,
                recordedWindowTurnCount = bounded.size,
                realDeviceWindowTurnCount = realDeviceRecords.size,
                syntheticTestTurnCountExcluded = syntheticExcludedCount,
                measurementOriginSummary = originSummary,
                startupDurationDistribution = startupDist,
                speechEndpointingDelayDistribution = endpointingDist,
                firstAudioWaitDistribution = firstAudioWaitDist,
                playbackBufferingDistribution = playbackBufferingDist,
                nextCaptureDelayDistribution = nextCaptureDist,
                outputStartLatencyEstimateDistribution = outputStartEstimateDist,
                playbackDurationDistributionSeparateFromLatency = playbackDurationDist,
                speakingRateWpmDistributionSeparateFromLatency = speakingRateDist,
                roadmapTargetMedianThresholdMs = ROADMAP_MEDIAN_TARGET_MS,
                roadmapTargetP95ThresholdMs = ROADMAP_P95_TARGET_MS,
                roadmapTargetStatus = targetStatus,
                roadmapTargetDetail = targetDetail,
                buildType = buildType,
                sourceRevision = sourceRevision,
                sourceFingerprint = sourceFingerprint,
                runtimeModelId = runtimeModelId,
                configuredVoice = configuredVoice,
                audioRouteLabel = audioRouteLabel,
                contextWindowCompressionActive = contextWindowCompressionActive,
                sessionResumptionStatus = sessionResumptionStatus,
                contextRestorationStatus = contextRestorationStatus
            )
        }
    }
}

/**
 * Redacted error entry used for the current active error and the bounded history of previous errors.
 * Never stores raw transcripts, raw audio, credentials, App Check tokens, resumption handles,
 * contact info, or unfiltered exception messages.
 */
data class RedactedDiagnosticErrorEntry(
    val sequenceNumber: Long,
    val timestampMonotonicMs: Long,
    val sessionId: String,
    val turnId: String,
    val generationWireId: String,
    val stage: String,
    val errorCode: String,
    val redactedSummary: String
)

/**
 * Dedicated redacted diagnostic representation for Phase 07 (`COPY DIAG`).
 * Strictly excludes transcripts, raw audio, credentials, App Check tokens, resumption handles,
 * contact information, and unfiltered exception messages.
 */
data class RedactedDiagnosticSnapshot(
    val capturedAtMonotonicMs: Long,
    val applicationId: String = BuildConfig.APPLICATION_ID,
    val versionName: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
    val buildType: String = BuildConfig.BUILD_TYPE,
    val sourceBuildId: String = BuildConfig.SOURCE_BUILD_ID,
    val sourceRevision: String = BuildConfig.SOURCE_REVISION.takeIf { it.isNotBlank() && it != "null" }
        ?: "SOURCE_REVISION_UNAVAILABLE",
    val sourceFingerprint: String = BuildConfig.SOURCE_FINGERPRINT,
    val sessionId: String,
    val currentTurnId: String,
    val activeTurnGenerationId: String,
    val liveSessionGenerationId: String,
    val captureGenerationId: String,
    val playbackGenerationId: String,
    val runtimeModelId: String = LiveConfigConstants.SUPPORTED_LIVE_MODEL_ID,
    val apiVersion: String = LiveConfigConstants.LIVE_API_VERSION,
    val endpointFamily: String = LiveConfigConstants.ENDPOINT_FAMILY.wireCode,
    val authRoute: String = LiveConfigConstants.AUTH_ROUTE.wireCode,
    val configuredVoice: String = LiveConfigConstants.DEFAULT_NATIVE_AUDIO_VOICE,
    val conversationState: String,
    val activeConversationPhase: String,
    val isActiveConversationEnabled: Boolean,
    val completedTurnsInSession: Int,
    val voiceTurnStateSequence: List<String>,
    val activeConversationPhaseSequence: List<String>,
    val voiceTurnReadinessOrder: List<String>,
    val structuredTurnTrace: List<String>,
    val lastCompletedTurnTrace: List<String>,
    val activeTurnEarliestMissingStep: String,
    val lastCompletedTurnEarliestMissingStep: String,
    val liveConnectionPhase: String,
    val handshakeVerified: Boolean,
    val isInternetValidated: Boolean,
    val isReceiverCollectorAttached: Boolean,
    val staleTurnCallbacksDropped: Int,
    val staleSocketCallbacksDropped: Int,
    val staleCaptureCallbacksDropped: Int,
    val stalePlaybackCallbacksDropped: Int,
    val startupTiming: Phase07StartupTimingSnapshot,
    val activeTurnTiming: Phase07TurnTimingSnapshot?,
    val lastCompletedTurnTiming: Phase07TurnTimingSnapshot?,
    val captureOwner: String,
    val isCapturing: Boolean,
    val micPermissionState: String,
    val hardwareSampleRateHz: Int,
    val targetSampleRateHz: Int,
    val resamplingActive: Boolean,
    val capturedFramesCount: Long,
    val capturedDurationMs: Long,
    val voiceTurnFramesSent: Long,
    val voiceTurnBytesSent: Long,
    val voiceTurnChunksSent: Long,
    val speechGateState: String,
    val vadDetectorName: String,
    val vadDetectorVersion: String,
    val vadModelLoaded: Boolean,
    val falseStartsRejectedCount: Int,
    val activityStartCount: Int,
    val activityEndCount: Int,
    val playbackIsTrackOpen: Boolean,
    val playbackIsPlaying: Boolean,
    val playbackIsDraining: Boolean,
    val playbackIsDrained: Boolean,
    val playbackVerifiedMimeType: String,
    val playbackVerifiedSampleRateHz: Int,
    val playbackFramesReceivedFromServer: Long,
    val playbackBytesReceivedFromServer: Long,
    val playbackChunksReceived: Int,
    val playbackFramesAcceptedByHardware: Long,
    val playbackBytesAcceptedByHardware: Long,
    val playbackFramesPlayedByHardwareHead: Long,
    val playbackQueuedBytes: Long,
    val playbackMaxQueuedBytes: Long,
    val playbackPrebufferTargetBytes: Long,
    val playbackStartedAfterBufferedBytes: Long,
    val playbackPartialWriteCount: Int,
    val playbackZeroWriteRetryCount: Int,
    val playbackUnderrunCount: Int,
    val playbackTrackRecreationCount: Int,
    val currentActiveError: RedactedDiagnosticErrorEntry?,
    val previousErrorHistory: List<RedactedDiagnosticErrorEntry>,
    val connectionTimeoutMs: Long,
    val firstResponseTimeoutMs: Long,
    val inactivityTimeoutOptionLabel: String,
    val inactivityTimeoutMs: Long,
    val speechConfirmDurationMs: Long,
    val trailingSilenceDurationMs: Long,
    val preRollDurationMs: Long,
    val postPlaybackEchoGuardMs: Long,
    val liveBackoffMaxAttempts: Int,
    val liveBackoffInitialDelayMs: Long,
    val liveBackoffMultiplier: Double,
    val liveBackoffMaxDelayMs: Long,
    val liveAttemptCount: Int,
    val liveRetryCount: Int,
    val liveAutoReconnectCount: Int,
    val liveLastBackoffDelayMs: Long,
    val firstResponseTimeoutCount: Int,
    val firstResponseRecoveryCount: Int,
    val interruptionRecoveryCount: Int,
    val firstAudioAfterResponseTimeoutMs: Long = firstResponseTimeoutMs,
    val serverOutputStallTimeoutMs: Long = firstResponseTimeoutMs.coerceAtLeast(10_000L),
    val missingTurnCompleteTimeoutMs: Long = serverOutputStallTimeoutMs,
    val maxTurnResponseHardBoundMs: Long = 90_000L,
    val hardwarePlaybackStallTimeoutMs: Long = 5_000L,
    val serverOutputStallCount: Int = 0,
    val missingTurnCompleteCount: Int = 0,
    val hardwarePlaybackStallCount: Int = 0,
    val playbackPaddingFramesWritten: Long = 0L,
    val playbackPaddingBytesWritten: Long = 0L,
    val sessionResumptionStatus: String = "INITIAL_NEW_SESSION",
    val contextRestorationStatus: String = "INITIAL_NEW_SESSION",
    val hasValidResumptionHandle: Boolean = false,
    val resumptionUpdateCount: Int = 0,
    val resumedConnectionCount: Int = 0,
    val rejectedResumptionHandleCount: Int = 0,
    val serverGoAwayCount: Int = 0,
    val lastServerGoAwayTimeLeftMs: Long = -1L,
    val goAwayPendingHandover: Boolean = false,
    val lastGoAwayGenerationId: String = "NONE",
    val actualSocketCloseCount: Int = 0,
    val lastActualSocketCloseGenerationId: String = "NONE",
    val lastActualSocketCloseClassification: String = "NONE",
    val lastActualSocketCloseReason: String = "NONE",
    val contextWindowCompressionActive: Boolean = true,
    val performanceSummary: Phase07PerformanceSummary = Phase07PerformanceSummary(
        startupDurationDistribution = Phase07MetricDistribution.fromSamples(
            metricName = "startupToMicReadyDuration",
            rawValues = if (!startupTiming.isSyntheticOrTestMeasurement) {
                listOfNotNull(startupTiming.startupToMicReadyDurationMs)
            } else {
                emptyList()
            }
        )
    )
) {
    fun toFormattedReport(): String = buildString {
        appendLine("=== OMNIVOICE REBUILD — PHASE 07A REDACTED DIAGNOSTIC SNAPSHOT ===")
        appendLine("snapshotCapturedAtMonotonicMs: $capturedAtMonotonicMs ms")
        appendLine()
        appendLine("[1. BUILD & VERSION IDENTIFICATION]")
        appendLine("applicationId: $applicationId")
        appendLine("versionName: $versionName")
        appendLine("versionCode: $versionCode")
        appendLine("buildType: $buildType")
        appendLine("sourceBuildId: $sourceBuildId")
        appendLine("sourceRevision: $sourceRevision")
        appendLine("sourceFingerprint: $sourceFingerprint")
        appendLine()
        appendLine("[2. SESSION, TURN & MODEL CONFIGURATION]")
        appendLine("sessionId: $sessionId")
        appendLine("currentTurnId: $currentTurnId")
        appendLine("activeTurnGenerationId: $activeTurnGenerationId")
        appendLine("liveSessionGenerationId: $liveSessionGenerationId")
        appendLine("captureGenerationId: $captureGenerationId")
        appendLine("playbackGenerationId: $playbackGenerationId")
        appendLine("runtimeModelId: $runtimeModelId")
        appendLine("apiVersion: $apiVersion")
        appendLine("endpointFamily: $endpointFamily")
        appendLine("authRoute: $authRoute")
        appendLine("configuredVoice: $configuredVoice")
        appendLine()
        appendLine("[3. STATE TRANSITIONS & CONNECTION STATUS]")
        appendLine("conversationState: $conversationState")
        appendLine("activeConversationPhase: $activeConversationPhase")
        appendLine("isActiveConversationEnabled: $isActiveConversationEnabled")
        appendLine("completedTurnsInSession: $completedTurnsInSession")
        appendLine("voiceTurnStateSequence: ${voiceTurnStateSequence.ifEmpty { listOf("NONE") }.joinToString(" -> ")}")
        appendLine("activeConversationPhaseSequence: ${activeConversationPhaseSequence.ifEmpty { listOf("NONE") }.joinToString(" -> ")}")
        appendLine("voiceTurnReadinessOrder: ${voiceTurnReadinessOrder.ifEmpty { listOf("NONE") }.joinToString(" -> ")}")
        appendLine("activeTurnEarliestMissingStep: $activeTurnEarliestMissingStep")
        appendLine("lastCompletedTurnEarliestMissingStep: $lastCompletedTurnEarliestMissingStep")
        appendLine("liveConnectionPhase: $liveConnectionPhase")
        appendLine("handshakeVerified: $handshakeVerified")
        appendLine("isInternetValidated: $isInternetValidated")
        appendLine("isReceiverCollectorAttached: $isReceiverCollectorAttached")
        appendLine("staleCallbacksDropped: turn=$staleTurnCallbacksDropped callbacks, socket=$staleSocketCallbacksDropped callbacks, capture=$staleCaptureCallbacksDropped callbacks, playback=$stalePlaybackCallbacksDropped callbacks")
        appendLine("structuredTurnTrace: ${structuredTurnTrace.ifEmpty { listOf("NONE") }.joinToString(" -> ") { DiagnosticRedactor.redactForDiagnostics(it) }}")
        appendLine("lastCompletedTurnTrace: ${lastCompletedTurnTrace.ifEmpty { listOf("NONE") }.joinToString(" -> ") { DiagnosticRedactor.redactForDiagnostics(it) }}")
        appendLine()
        appendLine("[4. STARTUP TIMING (TRACKED SEPARATELY FROM PER-TURN RESPONSE LATENCY)]")
        appendLine("startupSessionId: ${startupTiming.sessionId} (turn=${startupTiming.initialTurnId}, turnGen=${startupTiming.turnGenerationWireId}, liveGen=${startupTiming.liveSessionGenerationWireId}, capGen=${startupTiming.captureGenerationWireId})")
        appendLine("startupMeasurementOrigin: ${if (startupTiming.isSyntheticOrTestMeasurement) "SYNTHETIC_TEST_HARNESS (EXCLUDED_FROM_REAL_DEVICE_SUMMARY)" else "REAL_DEVICE_PIPELINE"}")
        appendLine("tConversationStartRequested: ${formatMonotonicTimestampMs(startupTiming.tStartRequestedMs)}")
        appendLine("tConnectionSetupAccepted: ${formatMonotonicTimestampMs(startupTiming.tSetupAcceptedMs)}")
        appendLine("tMicCaptureReady: ${formatMonotonicTimestampMs(startupTiming.tMicReadyMs)}")
        appendLine("connectionSetupDuration (startRequested -> setupAccepted): ${formatDurationMs(startupTiming.connectionSetupDurationMs)}")
        appendLine("startupToMicReadyDuration (startRequested -> micReady): ${formatDurationMs(startupTiming.startupToMicReadyDurationMs)}")
        appendLine("setupAcceptedToMicReadyDuration (setupAccepted -> micReady): ${formatDurationMs(startupTiming.setupAcceptedToMicReadyDurationMs)}")
        appendLine()
        appendLine("[5. PER-TURN TIMESTAMPS & DERIVED LATENCIES]")
        appendTurnTimingSection("ACTIVE_TURN_TIMING", activeTurnTiming)
        appendLine()
        appendTurnTimingSection("LAST_COMPLETED_TURN_TIMING", lastCompletedTurnTiming)
        appendLine()
        appendLine("[6. CAPTURE STATUS & PLAYBACK COUNTERS (WITH UNITS)]")
        appendLine("captureOwner: $captureOwner")
        appendLine("isCapturing: $isCapturing")
        appendLine("micPermissionState: $micPermissionState")
        val hwSampleRateLabel = if (hardwareSampleRateHz > 0) "$hardwareSampleRateHz Hz" else "UNAVAILABLE"
        val verifiedPlaybackRateLabel = if (playbackVerifiedSampleRateHz > 0) "$playbackVerifiedSampleRateHz Hz" else "UNAVAILABLE"
        val queuedFrames = (playbackFramesReceivedFromServer - playbackFramesAcceptedByHardware).coerceAtLeast(0L)
        val bufferedHardwareFrames = (playbackFramesAcceptedByHardware - playbackFramesPlayedByHardwareHead).coerceAtLeast(0L)
        appendLine("captureSampleRate: hardware=$hwSampleRateLabel, target=$targetSampleRateHz Hz (resamplingActive=$resamplingActive)")
        appendLine("captureRecorded: $capturedFramesCount frames ($capturedDurationMs ms)")
        appendLine("voiceTurnSentToTransport: $voiceTurnFramesSent frames ($voiceTurnBytesSent bytes across $voiceTurnChunksSent chunks)")
        appendLine("speechGate: state=$speechGateState, detector=$vadDetectorName v$vadDetectorVersion (loaded=$vadModelLoaded), falseStartsRejected=$falseStartsRejectedCount, activityStartCount=$activityStartCount, activityEndCount=$activityEndCount")
        appendLine("playbackTrackStatus: isTrackOpen=$playbackIsTrackOpen, isPlaying=$playbackIsPlaying, isDraining=$playbackIsDraining, isDrained=$playbackIsDrained, verifiedMime=$playbackVerifiedMimeType, verifiedSampleRate=$verifiedPlaybackRateLabel")
        appendLine("playbackReceivedFromServer: $playbackFramesReceivedFromServer frames ($playbackBytesReceivedFromServer bytes across $playbackChunksReceived chunks)")
        appendLine("playbackAcceptedByHardware: $playbackFramesAcceptedByHardware frames ($playbackBytesAcceptedByHardware bytes)")
        appendLine("playbackPlayedByHardwareHead: $playbackFramesPlayedByHardwareHead frames")
        appendLine("playbackPaddingWrittenSeparately: $playbackPaddingFramesWritten frames ($playbackPaddingBytesWritten bytes, excluded from content frames)")
        appendLine("playbackFrameReconciliation: received=$playbackFramesReceivedFromServer frames, queued=$queuedFrames frames ($playbackQueuedBytes bytes), accepted=$playbackFramesAcceptedByHardware frames, played=$playbackFramesPlayedByHardwareHead frames, bufferedInHardware=$bufferedHardwareFrames frames, padding=$playbackPaddingFramesWritten frames")
        appendLine("playbackBufferAndQueue: queued=$playbackQueuedBytes bytes, maxQueued=$playbackMaxQueuedBytes bytes, prebufferTarget=$playbackPrebufferTargetBytes bytes, startedAfterBuffered=$playbackStartedAfterBufferedBytes bytes")
        appendLine("playbackWriteCounters: partialWrites=$playbackPartialWriteCount writes, zeroWriteRetries=$playbackZeroWriteRetryCount retries, underruns=$playbackUnderrunCount underruns, trackRecreations=$playbackTrackRecreationCount recreations")
        appendLine()
        appendLine("[7. CURRENT ACTIVE ERROR]")
        if (currentActiveError == null) {
            appendLine("activeError: NONE")
        } else {
            appendLine("activeErrorCode: ${currentActiveError.errorCode}")
            appendLine("activeErrorStage: ${currentActiveError.stage}")
            appendLine("activeErrorSessionId: ${currentActiveError.sessionId}")
            appendLine("activeErrorTurnId: ${currentActiveError.turnId}")
            appendLine("activeErrorGenerationId: ${currentActiveError.generationWireId}")
            appendLine("activeErrorTimestampMonotonicMs: ${currentActiveError.timestampMonotonicMs} ms")
            appendLine("activeErrorRedactedSummary: ${DiagnosticRedactor.redactForDiagnostics(currentActiveError.redactedSummary)}")
        }
        appendLine()
        appendLine("[8. PREVIOUS ERROR HISTORY (SEPARATELY LABELLED, BOUNDED max=10)]")
        appendLine("previousErrorCount: ${previousErrorHistory.size}")
        if (previousErrorHistory.isEmpty()) {
            appendLine("previousErrors: NONE")
        } else {
            previousErrorHistory.forEachIndexed { idx, entry ->
                appendLine(
                    "previousError[${idx + 1}]: seq=#${entry.sequenceNumber} @ ${entry.timestampMonotonicMs} ms | " +
                        "session=${entry.sessionId} turn=${entry.turnId} gen=${entry.generationWireId} | " +
                        "stage=${entry.stage} code=${entry.errorCode} | " +
                        "summary=${DiagnosticRedactor.redactForDiagnostics(entry.redactedSummary)}"
                )
            }
        }
        appendLine()
        appendLine("[9. CURRENT TIMEOUT & RETRY SETTINGS]")
        appendLine("connectionTimeoutMs: $connectionTimeoutMs ms")
        appendLine("firstResponseTimeoutMs: $firstResponseTimeoutMs ms")
        appendLine("firstAudioAfterResponseTimeoutMs: $firstAudioAfterResponseTimeoutMs ms")
        appendLine("serverOutputStallTimeoutMs: $serverOutputStallTimeoutMs ms")
        appendLine("missingTurnCompleteTimeoutMs: $missingTurnCompleteTimeoutMs ms")
        appendLine("maxTurnResponseHardBoundMs: $maxTurnResponseHardBoundMs ms")
        appendLine("hardwarePlaybackStallTimeoutMs: $hardwarePlaybackStallTimeoutMs ms")
        appendLine("inactivityTimeout: $inactivityTimeoutOptionLabel ($inactivityTimeoutMs ms)")
        appendLine("speechConfirmDurationMs: $speechConfirmDurationMs ms")
        appendLine("trailingSilenceDurationMs: $trailingSilenceDurationMs ms")
        appendLine("preRollDurationMs: $preRollDurationMs ms")
        appendLine("postPlaybackEchoGuardMs: $postPlaybackEchoGuardMs ms")
        appendLine("liveBackoffPolicy: maxAttempts=$liveBackoffMaxAttempts attempts, initialDelay=$liveBackoffInitialDelayMs ms, multiplier=${liveBackoffMultiplier}x, maxDelay=$liveBackoffMaxDelayMs ms")
        appendLine("liveConnectionCounters: attempts=$liveAttemptCount, retries=$liveRetryCount, autoReconnects=$liveAutoReconnectCount, lastBackoffDelay=$liveLastBackoffDelayMs ms")
        appendLine("recoveryCounters: firstResponseTimeouts=$firstResponseTimeoutCount, serverOutputStalls=$serverOutputStallCount, missingTurnCompletes=$missingTurnCompleteCount, hardwarePlaybackStalls=$hardwarePlaybackStallCount, firstResponseRecoveries=$firstResponseRecoveryCount (max=1/turn), interruptionRecoveries=$interruptionRecoveryCount (max=1/turn)")
        appendLine()
        appendLine("[10. PHASE 07B SESSION MANAGEMENT & CONTEXT RESTORATION (HANDLES REDACTED)]")
        appendLine("contextWindowCompressionActive: $contextWindowCompressionActive (SlidingWindow)")
        appendLine("sessionResumptionStatus: $sessionResumptionStatus")
        appendLine("contextRestorationStatus: $contextRestorationStatus")
        appendLine("hasValidSessionResumptionHandle: $hasValidResumptionHandle (raw handle value private & never exported)")
        appendLine("sessionResumptionCounters: updatesReceived=$resumptionUpdateCount, resumedConnections=$resumedConnectionCount, rejectedHandleFallbacks=$rejectedResumptionHandleCount")
        appendLine("serverGoAwaySignals: count=$serverGoAwayCount, pendingHandover=$goAwayPendingHandover, lastGen=$lastGoAwayGenerationId, lastTimeLeftMs=${if (lastServerGoAwayTimeLeftMs >= 0L) "$lastServerGoAwayTimeLeftMs ms" else "NONE"}")
        appendLine("actualSocketClosures: count=$actualSocketCloseCount, lastGen=$lastActualSocketCloseGenerationId, lastClassification=$lastActualSocketCloseClassification, lastReason=${DiagnosticRedactor.redactForDiagnostics(lastActualSocketCloseReason)}")
        appendLine()
        appendLine("[11. PHASE 07C HONEST PERFORMANCE SUMMARY (BOUNDED max=${performanceSummary.maxBoundedTurnWindow} TURNS)]")
        appendLine("turnOutcomes: attempted=${performanceSummary.totalAttemptedTurns}, completed=${performanceSummary.completedTurns}, failed=${performanceSummary.failedTurns}, interrupted=${performanceSummary.interruptedTurns}, timedOut=${performanceSummary.timedOutTurns}, noSpeechRejected=${performanceSummary.noSpeechRejectedTurns}, windowRetained=${performanceSummary.recordedWindowTurnCount}")
        appendLine("measurementOriginSummary: ${performanceSummary.measurementOriginSummary} (realDeviceWindowTurns=${performanceSummary.realDeviceWindowTurnCount}, syntheticTestTurnsExcluded=${performanceSummary.syntheticTestTurnCountExcluded})")
        appendLine("audioRoute: ${performanceSummary.audioRouteLabel}")
        appendLine("roadmapTargetAssessment: ${performanceSummary.roadmapTargetStatus} (${performanceSummary.roadmapTargetDetail})")
        appendLine(performanceSummary.startupDurationDistribution.toFormattedSummaryLine())
        appendLine(performanceSummary.speechEndpointingDelayDistribution.toFormattedSummaryLine())
        appendLine(performanceSummary.firstAudioWaitDistribution.toFormattedSummaryLine())
        appendLine(performanceSummary.playbackBufferingDistribution.toFormattedSummaryLine())
        appendLine(performanceSummary.outputStartLatencyEstimateDistribution.toFormattedSummaryLine())
        appendLine(performanceSummary.nextCaptureDelayDistribution.toFormattedSummaryLine())
        appendLine(performanceSummary.playbackDurationDistributionSeparateFromLatency.toFormattedSummaryLine())
        appendLine(performanceSummary.speakingRateWpmDistributionSeparateFromLatency.toFormattedSummaryLine())
        appendLine()
        appendLine("[12. REDACTION & TIMING HONESTY NOTES]")
        appendLine("redactionPolicy: EXCLUDES transcript, raw audio, credentials, App Check tokens, resumption handles, contact info, and unfiltered exception messages")
        appendLine("outputStartLatencyNote: speechEnd-to-firstHardwareWrite is an ESTIMATE of output-start latency based on first positive AudioTrack.write; NOT physically measured speaker audibility")
        appendLine("transportSubmissionNote: tEndSubmittedMs marks client transport submission (sendStopActivityRealtime), distinct from server acknowledgment (tServerInputAckMs)")
    }.trimEnd()

    private fun StringBuilder.appendTurnTimingSection(
        sectionTitle: String,
        timing: Phase07TurnTimingSnapshot?
    ) {
        appendLine("--- $sectionTitle ---")
        if (timing == null) {
            appendLine("status: UNAVAILABLE")
            return
        }
        appendLine("turnIdentity: session=${timing.sessionId}, turn=${timing.turnId}, turnGen=${timing.turnGenerationWireId}, liveGen=${timing.liveSessionGenerationWireId}, capGen=${timing.captureGenerationWireId}, playGen=${timing.playbackGenerationWireId}")
        appendLine("measurementOrigin: ${if (timing.isSyntheticOrTestMeasurement) "SYNTHETIC_TEST_HARNESS (EXCLUDED_FROM_REAL_DEVICE_SUMMARY)" else "REAL_DEVICE_PIPELINE"}")
        appendLine("tStartRequestedMs: ${formatMonotonicTimestampMs(timing.tStartRequestedMs)}")
        appendLine("tSetupAcceptedMs: ${formatMonotonicTimestampMs(timing.tSetupAcceptedMs)}")
        appendLine("tMicReadyMs: ${formatMonotonicTimestampMs(timing.tMicReadyMs)}")
        appendLine("tLastUserSpeechMs: ${formatMonotonicTimestampMs(timing.tLastUserSpeechMs)}")
        appendLine("tSpeechEndMs: ${formatMonotonicTimestampMs(timing.tSpeechEndMs)}")
        appendLine("tEndSubmittedMs (CLIENT_TRANSPORT_SUBMISSION): ${formatMonotonicTimestampMs(timing.tEndSubmittedMs)}")
        appendLine("tServerInputAckMs (SERVER_ACKNOWLEDGMENT): ${formatMonotonicTimestampMs(timing.tServerInputAckMs)}")
        appendLine("tFirstResponseMs (NON_HANDSHAKE_TURN_RESPONSE): ${formatMonotonicTimestampMs(timing.tFirstResponseMs)}")
        appendLine("tFirstAudioReceivedMs: ${formatMonotonicTimestampMs(timing.tFirstAudioReceivedMs)}")
        appendLine("tFirstHardwareWriteMs [ESTIMATE]: ${formatMonotonicTimestampMs(timing.tFirstHardwareWriteMs)}")
        appendLine("tPlaybackDrainedMs: ${formatMonotonicTimestampMs(timing.tPlaybackDrainedMs)}")
        appendLine("tNextMicReadyMs: ${formatMonotonicTimestampMs(timing.tNextMicReadyMs)}")
        appendLine("speechEndpointingDelay (lastUserSpeech -> speechEnd): ${formatDurationMs(timing.speechEndpointingDelayMs)}")
        appendLine("endOfTurnTransportSubmitDelay (speechEnd -> endSubmittedToTransport): ${formatDurationMs(timing.endOfTurnSubmissionDelayMs)}")
        appendLine("transportToServerAckDelay (endSubmittedToTransport -> serverAck): ${formatDurationMs(timing.transportToServerAckDelayMs)}")
        appendLine("serverResponseWait (endSubmittedToTransport -> firstTurnResponse): ${formatDurationMs(timing.serverResponseWaitMs)}")
        appendLine("serverAudioWait (endSubmittedToTransport -> firstAudioReceived): ${formatDurationMs(timing.serverAudioWaitMs)}")
        appendLine("playbackBufferingDelay [ESTIMATE: firstAudioReceived -> firstHardwareWrite]: ${formatDurationMs(timing.playbackBufferingDelayMs)}")
        appendLine("outputStartLatencyEstimate [ESTIMATE: speechEnd -> firstHardwareWrite; NOT physical audibility]: ${formatDurationMs(timing.outputStartLatencyEstimateMs)}")
        appendLine("transportSubmitToFirstWriteEstimate [ESTIMATE: endSubmittedToTransport -> firstHardwareWrite]: ${formatDurationMs(timing.transportSubmitToFirstHardwareWriteEstimateMs)}")
        appendLine("playbackDuration_SEPARATE_FROM_LATENCY (firstHardwareWrite -> playbackDrained): ${formatDurationMs(timing.playbackDurationMs)}")
        val sampleRateLabel = if (timing.verifiedSampleRateHz > 0) "${timing.verifiedSampleRateHz} Hz" else "UNAVAILABLE"
        val framesLabel = if (timing.framesAcceptedByHardware > 0L && timing.verifiedSampleRateHz > 0) {
            " (${timing.framesAcceptedByHardware} frames @ $sampleRateLabel)"
        } else {
            ""
        }
        appendLine("audioContentDuration_SEPARATE_FROM_LATENCY$framesLabel: ${formatDurationMs(timing.audioContentDurationMs)}")
        appendLine("speakingRateEstimate_SEPARATE_FROM_LATENCY: ${timing.speakingRateWpmEstimate?.let { "$it WPM (from ${timing.redactedOutputWordCount} redacted words)" } ?: "UNAVAILABLE"}")
        appendLine("captureRestorationDelay (playbackDrained -> nextMicReady): ${formatDurationMs(timing.captureRestorationDelayMs)}")
    }
}
