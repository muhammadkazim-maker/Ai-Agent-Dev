package com.example.audio

import com.example.contract.SessionId
import com.example.contract.TurnId

/**
 * Monotonic playback generation token bound to a specific [TurnId] and [SessionId].
 * Every callback or hardware drain notification emitted by [LiveAudioPlaybackAdapter] MUST
 * carry the [PlaybackGenerationId] it was created under. Late callbacks from cancelled or
 * superseded turns are deterministically dropped and counted.
 */
data class PlaybackGenerationId(
    val generation: Long,
    val turnId: TurnId,
    val sessionId: SessionId
) {
    val wireId: String
        get() = "play-gen-${generation.toString().padStart(4, '0')}-${turnId.value}"
}

/**
 * Typed result of verifying a server-returned audio MIME type and sample rate.
 * Enforces the Phase 04 requirement: "Verify returned MIME/sample rate rather than guessing."
 */
sealed interface PcmMimeVerificationResult {
    data class Verified(
        val canonicalMimeType: String,
        val sampleRateHz: Int,
        val channelCount: Int = 1,
        val encodingBits: Int = 16
    ) : PcmMimeVerificationResult

    data class Rejected(
        val rawMimeType: String?,
        val reason: String
    ) : PcmMimeVerificationResult
}

/**
 * Strict MIME and sample-rate verifier for Gemini Live native-audio PCM output.
 *
 * Rules:
 * - Accepts only explicit 16-bit PCM MIME types (`audio/pcm;rate=<Hz>` or `audio/l16;rate=<Hz>`)
 *   with an explicit, valid `rate=` parameter in the supported PCM range (`8000..48000` Hz).
 * - Parses optional `channels=` parameter (defaults to 1 mono; supports 1 or 2 channels).
 * - Never guesses a default sample rate when `rate=` is missing, malformed, or out of range.
 * - Rejects non-PCM formats (e.g., `audio/mp3`, `audio/ogg`, `application/octet-stream`) honestly.
 */
object PcmMimeVerifier {
    const val EXPECTED_GEMINI_INPUT_MIME = "audio/pcm;rate=16000"
    const val STANDARD_GEMINI_OUTPUT_MIME = "audio/pcm;rate=24000"
    const val STANDARD_GEMINI_OUTPUT_RATE_HZ = 24000

    private val SUPPORTED_PCM_RATES_HZ = setOf(8000, 16000, 22050, 24000, 32000, 44100, 48000)
    private val RATE_PARAM_REGEX = Regex("(?i)(?:^|;)\\s*rate\\s*=\\s*([^;\\s]+)\\s*(?:;|$)")
    private val CHANNELS_PARAM_REGEX = Regex("(?i)(?:^|;)\\s*channels?\\s*=\\s*([^;\\s]+)\\s*(?:;|$)")

    fun verifyAndParseSampleRate(rawMimeType: String?): PcmMimeVerificationResult {
        if (rawMimeType.isNullOrBlank()) {
            return PcmMimeVerificationResult.Rejected(
                rawMimeType = rawMimeType,
                reason = "UNVERIFIED_MIME_MISSING: Server audio chunk is missing a MIME type; refusing to guess sample rate."
            )
        }

        val trimmed = rawMimeType.trim()
        val baseType = trimmed.substringBefore(';').trim().lowercase()
        if (baseType != "audio/pcm" && baseType != "audio/l16") {
            return PcmMimeVerificationResult.Rejected(
                rawMimeType = rawMimeType,
                reason = "UNSUPPORTED_AUDIO_MIME ($trimmed): Expected audio/pcm;rate=<Hz>, not $baseType."
            )
        }

        val rateMatch = RATE_PARAM_REGEX.find(trimmed)
            ?: return PcmMimeVerificationResult.Rejected(
                rawMimeType = rawMimeType,
                reason = "UNVERIFIED_SAMPLE_RATE ($trimmed): Missing explicit rate=<Hz> parameter in MIME type; refusing to guess sample rate."
            )

        val parsedRate = rateMatch.groupValues[1].toIntOrNull()
        if (parsedRate == null || parsedRate !in SUPPORTED_PCM_RATES_HZ) {
            return PcmMimeVerificationResult.Rejected(
                rawMimeType = rawMimeType,
                reason = "UNSUPPORTED_SAMPLE_RATE ($trimmed): Sample rate '${rateMatch.groupValues[1]}' is not a supported PCM rate."
            )
        }

        val channelsMatch = CHANNELS_PARAM_REGEX.find(trimmed)
        val parsedChannels = if (channelsMatch != null) {
            val ch = channelsMatch.groupValues[1].toIntOrNull()
            if (ch == null || ch !in 1..2) {
                return PcmMimeVerificationResult.Rejected(
                    rawMimeType = rawMimeType,
                    reason = "UNSUPPORTED_CHANNEL_COUNT ($trimmed): Channel count '${channelsMatch.groupValues[1]}' is not supported."
                )
            }
            ch
        } else {
            1
        }

        val canonical = if (parsedChannels == 1) {
            "audio/pcm;rate=$parsedRate"
        } else {
            "audio/pcm;rate=$parsedRate;channels=$parsedChannels"
        }

        return PcmMimeVerificationResult.Verified(
            canonicalMimeType = canonical,
            sampleRateHz = parsedRate,
            channelCount = parsedChannels,
            encodingBits = 16
        )
    }
}

/**
 * Typed outcome of verifying and writing a single PCM chunk to the streaming playback adapter.
 */
sealed interface PcmChunkWriteOutcome {
    data class WrittenToHardware(
        val generationId: PlaybackGenerationId,
        val verifiedMimeType: String,
        val sampleRateHz: Int,
        val chunkBytesReceived: Int,
        val chunkBytesAccepted: Int,
        val totalBytesAcceptedByHardware: Long,
        val partialWritesInChunk: Int,
        val zeroWritesInChunk: Int
    ) : PcmChunkWriteOutcome

    data class RejectedMime(
        val generationId: PlaybackGenerationId,
        val rawMimeType: String?,
        val reason: String
    ) : PcmChunkWriteOutcome

    data class FailedWrite(
        val generationId: PlaybackGenerationId,
        val reason: String
    ) : PcmChunkWriteOutcome

    data class DroppedStaleGeneration(
        val generationId: PlaybackGenerationId,
        val activeGenerationId: PlaybackGenerationId?
    ) : PcmChunkWriteOutcome
}

/**
 * Generation-bound playback events emitted by [LiveAudioPlaybackAdapter].
 */
sealed interface PlaybackCallbackEvent {
    val generationId: PlaybackGenerationId

    data class AudioChunkReceived(
        override val generationId: PlaybackGenerationId,
        val verifiedMimeType: String,
        val sampleRateHz: Int,
        val chunkBytes: Int,
        val totalBytesReceived: Long
    ) : PlaybackCallbackEvent

    data class BytesAcceptedByHardware(
        override val generationId: PlaybackGenerationId,
        val chunkBytesAccepted: Int,
        val totalBytesAccepted: Long,
        val totalFramesWritten: Long,
        val partialWriteCount: Int,
        val zeroWriteRetryCount: Int
    ) : PlaybackCallbackEvent

    data class PlaybackDrained(
        override val generationId: PlaybackGenerationId,
        val totalBytesAccepted: Long,
        val totalFramesWritten: Long,
        val hardwarePlaybackHeadFrames: Long
    ) : PlaybackCallbackEvent

    data class PlaybackFailed(
        override val generationId: PlaybackGenerationId,
        val reason: String
    ) : PlaybackCallbackEvent
}

/**
 * Immutable state snapshot of the single [LiveAudioPlaybackAdapter].
 */
data class LiveAudioPlaybackState(
    val activeGeneration: PlaybackGenerationId? = null,
    val lastCompletedGeneration: PlaybackGenerationId? = null,
    val isTrackOpen: Boolean = false,
    val isPlaying: Boolean = false,
    val isDrainingHardware: Boolean = false,
    val isPlaybackDrained: Boolean = false,
    val serverTurnComplete: Boolean = false,
    val verifiedMimeType: String = "NONE",
    val verifiedSampleRateHz: Int = 0,
    val chunksReceived: Int = 0,
    val framesReceivedFromServer: Long = 0L,
    val audioBytesReceived: Long = 0L,
    val pendingQueuedBytes: Long = 0L,
    val bytesAcceptedByHardware: Long = 0L,
    val totalFramesWrittenToHardware: Long = 0L,
    val hardwarePlaybackHeadFrames: Long = 0L,
    val partialWriteCount: Int = 0,
    val zeroWriteRetryCount: Int = 0,
    val stalePlaybackCallbacksDropped: Int = 0,
    val hardwareReleaseCount: Int = 0,
    val lastError: String? = null,
    val statusDetail: String = "Streaming playback adapter idle."
)
