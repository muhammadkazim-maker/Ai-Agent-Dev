package com.example.audio

import com.example.contract.SessionId
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Single owner identity for hardware audio capture.
 * Enforces that only one owner may hold the microphone at any time, and prohibits
 * simultaneous SpeechRecognizer + AudioRecord loops.
 */
enum class AudioOwner(val wireLabel: String) {
    NONE("NONE"),
    SHELL_CONVERSATION("SHELL_CONVERSATION"),
    MIC_SELF_TEST("MIC_SELF_TEST"),
    LIVE_VOICE_TURN("LIVE_VOICE_TURN")
}

/**
 * Monotonic capture generation token.
 * Every callback emitted by the audio driver or playback engine MUST carry the
 * [CaptureGenerationId] it was created under. Stale callbacks whose generation does not match
 * the currently active generation are deterministically dropped.
 */
data class CaptureGenerationId(
    val generation: Long,
    val owner: AudioOwner,
    val sessionId: SessionId
) {
    val wireId: String
        get() = "gen-${generation.toString().padStart(4, '0')}-${owner.name.lowercase()}"
}

/**
 * Explicit Android RECORD_AUDIO permission states, distinguishing initial unrequested state,
 * temporary refusal (rationale can be shown), and permanent denial (requires App Settings).
 */
enum class MicPermissionState(val wireLabel: String, val userSummary: String) {
    NOT_REQUESTED(
        wireLabel = "NOT_REQUESTED",
        userSummary = "Microphone permission has not been requested yet."
    ),
    GRANTED(
        wireLabel = "GRANTED",
        userSummary = "RECORD_AUDIO permission granted by Android OS."
    ),
    REFUSED_CAN_RETRY(
        wireLabel = "REFUSED_CAN_RETRY",
        userSummary = "Permission was refused. Review the explanation below and grant RECORD_AUDIO to run the self-test."
    ),
    PERMANENTLY_DENIED(
        wireLabel = "PERMANENTLY_DENIED",
        userSummary = "Microphone access is permanently denied by Android. Open System App Settings to enable Microphone permission; OmniVoice Rebuild will re-check automatically when you return."
    )
}

/**
 * Hardware and OS-level anomalies detected by [AudioCapture].
 */
enum class AudioAnomaly(val wireCode: String, val description: String) {
    NONE(
        wireCode = "OK",
        description = "No hardware or OS capture anomalies detected."
    ),
    MISSING_MICROPHONE_DEVICE(
        wireCode = "MISSING_DEVICE",
        description = "No microphone input hardware detected on this Android device."
    ),
    PERMISSION_MISSING(
        wireCode = "PERMISSION_DENIED",
        description = "RECORD_AUDIO permission is required before starting AudioRecord."
    ),
    RECORDER_START_FAILURE(
        wireCode = "RECORDER_START_FAILURE",
        description = "AudioRecord failed to initialize or failed to enter RECORDSTATE_RECORDING."
    ),
    ANDROID_IMPOSED_SILENCE(
        wireCode = "ANDROID_SILENCED",
        description = "Android OS is delivering continuous zero/silenced PCM frames (hardware privacy mute or concurrent app block)."
    ),
    AUDIO_INTERRUPTED(
        wireCode = "AUDIO_INTERRUPTED",
        description = "Audio capture interrupted by OS audio focus loss or input device disconnect."
    ),
    CONCURRENT_LOOP_REJECTED(
        wireCode = "CONCURRENT_LOOP_REJECTED",
        description = "Simultaneous SpeechRecognizer or competing AudioRecord loop is prohibited by contract."
    )
}

/**
 * Typed events delivered from the audio hardware loop, strictly bound to [generationId].
 */
sealed interface CaptureCallbackEvent {
    val generationId: CaptureGenerationId

    data class FrameCaptured(
        override val generationId: CaptureGenerationId,
        val pcmSamples: ShortArray,
        val hardwareSampleRateHz: Int,
        val osClientSilenced: Boolean = false
    ) : CaptureCallbackEvent

    data class RecorderError(
        override val generationId: CaptureGenerationId,
        val anomaly: AudioAnomaly,
        val diagnostic: String
    ) : CaptureCallbackEvent

    data class InterruptionDetected(
        override val generationId: CaptureGenerationId,
        val reason: String
    ) : CaptureCallbackEvent

    data class EphemeralPlaybackCompleted(
        override val generationId: CaptureGenerationId,
        val playedSamplesCount: Int
    ) : CaptureCallbackEvent
}

/**
 * Canonical audio format and deterministic linear-interpolation resampler to 16,000 Hz Mono PCM-16,
 * matching standard multimodal live voice provider requirements.
 */
object PcmResampler {
    const val TARGET_SAMPLE_RATE_HZ = 16000
    const val TARGET_CHANNELS = 1
    const val FRAME_DURATION_MS = 20
    const val SAMPLES_PER_20MS_FRAME_16KHZ = (TARGET_SAMPLE_RATE_HZ * FRAME_DURATION_MS) / 1000 // 320 samples

    val PREFERRED_HARDWARE_RATES_HZ = intArrayOf(16000, 24000, 48000, 44100)

    /**
     * Resamples 16-bit mono PCM from [inputRateHz] to [targetRateHz] (default 16,000 Hz).
     * When [inputRateHz] == [targetRateHz], returns a defensive copy without distortion.
     */
    fun resampleMono16Bit(
        input: ShortArray,
        inputRateHz: Int,
        targetRateHz: Int = TARGET_SAMPLE_RATE_HZ
    ): ShortArray {
        if (input.isEmpty() || inputRateHz <= 0 || targetRateHz <= 0) return ShortArray(0)
        if (inputRateHz == targetRateHz) return input.copyOf()

        val outputLength = max(1, ((input.size.toLong() * targetRateHz) / inputRateHz).toInt())
        val output = ShortArray(outputLength)
        val ratio = inputRateHz.toDouble() / targetRateHz.toDouble()

        for (i in 0 until outputLength) {
            val srcPos = i * ratio
            val index0 = srcPos.toInt().coerceIn(0, input.lastIndex)
            val index1 = (index0 + 1).coerceIn(0, input.lastIndex)
            val frac = srcPos - index0
            val interpolated = input[index0] * (1.0 - frac) + input[index1] * frac
            val clamped = interpolated.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output[i] = clamped.toShort()
        }
        return output
    }

    /**
     * Computes normalized linear RMS (0.0..1.0) and dBFS (-96.0..0.0) for a 16-bit PCM frame.
     */
    fun computeRmsMetrics(samples: ShortArray): Pair<Float, Float> {
        if (samples.isEmpty()) return 0f to -96f
        var sumSquares = 0.0
        for (sample in samples) {
            val normalized = sample.toDouble() / 32768.0
            sumSquares += normalized * normalized
        }
        val rmsLinear = sqrt(sumSquares / samples.size).toFloat().coerceIn(0f, 1f)
        val rmsDbfs = if (rmsLinear <= 1e-5f) {
            -96.0f
        } else {
            (20.0 * log10(rmsLinear.toDouble())).toFloat().coerceIn(-96.0f, 0.0f)
        }
        return rmsLinear to rmsDbfs
    }

    /**
     * Encodes 16-bit mono PCM samples into little-endian byte array (`audio/pcm;rate=16000`).
     */
    fun shortsToLittleEndianBytes(samples: ShortArray): ByteArray {
        if (samples.isEmpty()) return ByteArray(0)
        val bytes = ByteArray(samples.size * 2)
        var byteIndex = 0
        for (sample in samples) {
            val v = sample.toInt()
            bytes[byteIndex++] = (v and 0xFF).toByte()
            bytes[byteIndex++] = ((v ushr 8) and 0xFF).toByte()
        }
        return bytes
    }

    /**
     * Decodes little-endian 16-bit mono PCM bytes into [ShortArray].
     */
    fun littleEndianBytesToShorts(bytes: ByteArray): ShortArray {
        val sampleCount = bytes.size / 2
        if (sampleCount <= 0) return ShortArray(0)
        val samples = ShortArray(sampleCount)
        var byteIndex = 0
        for (i in 0 until sampleCount) {
            val low = bytes[byteIndex++].toInt() and 0xFF
            val high = bytes[byteIndex++].toInt()
            samples[i] = ((high shl 8) or low).toShort()
        }
        return samples
    }
}

/**
 * Immutable snapshot of the single [AudioCapture] component state.
 */
data class AudioCaptureState(
    val permissionState: MicPermissionState = MicPermissionState.NOT_REQUESTED,
    val hasMicrophoneHardware: Boolean = true,
    val owner: AudioOwner = AudioOwner.NONE,
    val activeGeneration: CaptureGenerationId? = null,
    val lastCompletedGeneration: CaptureGenerationId? = null,
    val isCapturing: Boolean = false,
    val isPlayingEphemeralCheck: Boolean = false,
    val hardwareSampleRateHz: Int = PcmResampler.TARGET_SAMPLE_RATE_HZ,
    val targetSampleRateHz: Int = PcmResampler.TARGET_SAMPLE_RATE_HZ,
    val resamplingActive: Boolean = false,
    val capturedFrameCount: Long = 0L,
    val capturedSampleCount: Long = 0L,
    val capturedDurationMs: Long = 0L,
    val nonSilentFrameCount: Long = 0L,
    val currentRmsLinear: Float = 0f,
    val peakRmsLinear: Float = 0f,
    val lastCaptureRmsLinear: Float = 0f,
    val lastCapturePeakRmsLinear: Float = 0f,
    val currentRmsDbfs: Float = -96.0f,
    val consecutiveSilentFrames: Int = 0,
    val anomaly: AudioAnomaly = AudioAnomaly.NONE,
    val statusDetail: String = "AudioCapture idle. Retention OFF; no audio uploaded.",
    val staleCallbacksDropped: Int = 0,
    val hardwareReleaseCount: Int = 0,
    val ephemeralCheckArmed: Boolean = true,
    val ephemeralRecordedSamples: Int = 0,
    val ephemeralDurationMs: Long = 0L,
    val ephemeralPlaybackCount: Int = 0,
    val ephemeralLastWipedStatus: String = "Retention OFF — Ephemeral buffer empty (0 bytes)"
)
