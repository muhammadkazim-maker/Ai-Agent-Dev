package com.example.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.io.InputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Result of evaluating a single aligned PCM window with a speech detector.
 */
data class NeuralVadInferenceResult(
    val isSpeechWindow: Boolean,
    val speechProbability: Float
)

/**
 * Abstraction for voice-activity inference used by [SpeechActivityGate].
 *
 * Production uses [SileroVadDetector] (Silero VAD v6.2.3 ONNX).
 * Legacy pure-JVM tests constructed without Android Context use [DefaultNoContextSyntheticSpeechDetector]
 * with [requiresLoadedModel] = false; whenever a real neural detector is provided ([requiresLoadedModel] = true)
 * and [isModelLoaded] is false, the coordinator must surface a recoverable error (`VAD_MODEL_LOAD_FAILED`)
 * and never fall back to RMS-only gating.
 */
interface SpeechNeuralDetector {
    val detectorName: String
    val detectorVersion: String
    val windowSizeSamples: Int
    val sampleRateHz: Int
    val speechThreshold: Float
    val isModelLoaded: Boolean
    val loadError: String?
    val requiresLoadedModel: Boolean

    fun resetState()

    fun inferWindow(
        samples16kHz: ShortArray,
        rmsLinear: Float = 0f
    ): NeuralVadInferenceResult

    fun close() = Unit
}

/**
 * Real on-device neural Silero VAD (snakers4/silero-vad v6.2.3, 16 kHz ONNX model).
 *
 * Exact upstream v6.2.3 tensor specification for 16,000 Hz mono PCM:
 * - Window size: 512 samples (32 ms)
 * - Context prepended from trailing end of previous window: 64 samples -> `input` shape `[1, 576]` float32 (`sample / 32768f`)
 * - Recurrent LSTM state tensor: `state` shape `[2, 1, 128]` float32
 * - Sample rate tensor: `sr` shape `[1]` int64 (`16000L`)
 * - Outputs: `output` `[1, 1]` speech probability `[0f..1f]`, updated `stateN` `[2, 1, 128]`
 *
 * State persistence & thread safety:
 * - Recurrent LSTM `state` and 64-sample `context` are preserved across consecutive 512-sample windows
 *   and reset only at session boundaries or capture discontinuities via [resetState].
 * - Calls are serialized under [inferenceLock] and offloaded from Main/AudioRecord threads by [ConversationCoordinator].
 */
class SileroVadDetector private constructor(
    private val ortEnv: OrtEnvironment?,
    private val ortSession: OrtSession?,
    override val speechThreshold: Float = DEFAULT_SPEECH_PROBABILITY_THRESHOLD,
    override val loadError: String? = null
) : SpeechNeuralDetector {

    override val detectorName: String = DETECTOR_NAME
    override val detectorVersion: String = DETECTOR_VERSION
    override val windowSizeSamples: Int = WINDOW_SAMPLES_16K
    override val sampleRateHz: Int = SAMPLE_RATE_HZ
    override val requiresLoadedModel: Boolean = true
    override val isModelLoaded: Boolean
        get() = ortEnv != null && ortSession != null && loadError == null

    private val inferenceLock = Any()
    private val contextBuffer = FloatArray(CONTEXT_SAMPLES_16K)
    private val inputBuffer = FloatArray(EFFECTIVE_INPUT_SAMPLES_16K)
    private val stateBuffer = FloatArray(STATE_TOTAL_FLOATS)
    private val srArray = longArrayOf(SAMPLE_RATE_HZ.toLong())

    @Volatile
    private var closed: Boolean = false

    @Volatile
    var runtimeError: String? = null
        private set

    override fun resetState() {
        synchronized(inferenceLock) {
            contextBuffer.fill(0f)
            inputBuffer.fill(0f)
            stateBuffer.fill(0f)
        }
    }

    override fun inferWindow(
        samples16kHz: ShortArray,
        rmsLinear: Float
    ): NeuralVadInferenceResult {
        val env = ortEnv
        val session = ortSession
        if (closed || env == null || session == null || loadError != null) {
            return NeuralVadInferenceResult(isSpeechWindow = false, speechProbability = 0f)
        }
        require(samples16kHz.size == WINDOW_SAMPLES_16K) {
            "SileroVadDetector v$DETECTOR_VERSION requires exactly $WINDOW_SAMPLES_16K samples at $SAMPLE_RATE_HZ Hz, got ${samples16kHz.size}"
        }

        return synchronized(inferenceLock) {
            if (closed) {
                return@synchronized NeuralVadInferenceResult(isSpeechWindow = false, speechProbability = 0f)
            }
            System.arraycopy(contextBuffer, 0, inputBuffer, 0, CONTEXT_SAMPLES_16K)
            for (i in 0 until WINDOW_SAMPLES_16K) {
                inputBuffer[CONTEXT_SAMPLES_16K + i] = samples16kHz[i] / 32768.0f
            }

            var inputTensor: OnnxTensor? = null
            var stateTensor: OnnxTensor? = null
            var srTensor: OnnxTensor? = null
            try {
                inputTensor = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(inputBuffer),
                    INPUT_SHAPE
                )
                stateTensor = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(stateBuffer),
                    STATE_SHAPE
                )
                srTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(srArray),
                    SR_SHAPE
                )

                val inputs = mapOf(
                    "input" to inputTensor,
                    "state" to stateTensor,
                    "sr" to srTensor
                )

                session.run(inputs).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val outputArr = result[0].value as Array<FloatArray>
                    val rawProb = outputArr[0][0].let { p ->
                        if (p.isNaN()) 0f else p.coerceIn(0f, 1f)
                    }

                    @Suppress("UNCHECKED_CAST")
                    val nextState = result[1].value as Array<Array<FloatArray>>
                    var writeIdx = 0
                    for (layer in 0 until STATE_DIM_0) {
                        val slice = nextState[layer][0]
                        System.arraycopy(slice, 0, stateBuffer, writeIdx, STATE_DIM_2)
                        writeIdx += STATE_DIM_2
                    }

                    // Preserve trailing 64 normalized samples as left context for the next 512-sample window.
                    System.arraycopy(
                        inputBuffer,
                        EFFECTIVE_INPUT_SAMPLES_16K - CONTEXT_SAMPLES_16K,
                        contextBuffer,
                        0,
                        CONTEXT_SAMPLES_16K
                    )

                    runtimeError = null
                    NeuralVadInferenceResult(
                        isSpeechWindow = rawProb >= speechThreshold,
                        speechProbability = rawProb
                    )
                }
            } catch (t: Throwable) {
                runtimeError = "${t::class.java.simpleName}: ${t.message ?: "inference error"}"
                contextBuffer.fill(0f)
                stateBuffer.fill(0f)
                NeuralVadInferenceResult(isSpeechWindow = false, speechProbability = 0f)
            } finally {
                try {
                    inputTensor?.close()
                } catch (_: Throwable) {
                }
                try {
                    stateTensor?.close()
                } catch (_: Throwable) {
                }
                try {
                    srTensor?.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    override fun close() {
        synchronized(inferenceLock) {
            if (closed) return
            closed = true
            try {
                ortSession?.close()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        const val DETECTOR_NAME: String = "Silero VAD"
        const val DETECTOR_VERSION: String = "6.2.3"
        const val MODEL_ASSET_NAME: String = "silero_vad.onnx"
        const val SAMPLE_RATE_HZ: Int = 16_000
        const val WINDOW_SAMPLES_16K: Int = 512
        const val CONTEXT_SAMPLES_16K: Int = 64
        const val EFFECTIVE_INPUT_SAMPLES_16K: Int = WINDOW_SAMPLES_16K + CONTEXT_SAMPLES_16K

        private const val STATE_DIM_0: Int = 2
        private const val STATE_DIM_1: Int = 1
        private const val STATE_DIM_2: Int = 128
        private const val STATE_TOTAL_FLOATS: Int = STATE_DIM_0 * STATE_DIM_1 * STATE_DIM_2

        private val INPUT_SHAPE = longArrayOf(1L, EFFECTIVE_INPUT_SAMPLES_16K.toLong())
        private val STATE_SHAPE = longArrayOf(STATE_DIM_0.toLong(), STATE_DIM_1.toLong(), STATE_DIM_2.toLong())
        private val SR_SHAPE = longArrayOf(1L)

        const val DEFAULT_SPEECH_PROBABILITY_THRESHOLD: Float = 0.50f

        fun fromAndroidContext(
            context: Context,
            assetName: String = MODEL_ASSET_NAME,
            speechThreshold: Float = DEFAULT_SPEECH_PROBABILITY_THRESHOLD
        ): SileroVadDetector {
            return try {
                val modelBytes = context.assets.open(assetName).use { it.readBytes() }
                fromModelBytes(modelBytes = modelBytes, speechThreshold = speechThreshold)
            } catch (t: Throwable) {
                SileroVadDetector(
                    ortEnv = null,
                    ortSession = null,
                    speechThreshold = speechThreshold,
                    loadError = "VAD_ASSET_OPEN_FAILED($assetName): ${t::class.java.simpleName}: ${t.message}"
                )
            }
        }

        fun fromResourceStream(
            streamProvider: () -> InputStream?,
            resourceLabel: String = MODEL_ASSET_NAME,
            speechThreshold: Float = DEFAULT_SPEECH_PROBABILITY_THRESHOLD
        ): SileroVadDetector {
            return try {
                val stream = streamProvider()
                    ?: return SileroVadDetector(
                        ortEnv = null,
                        ortSession = null,
                        speechThreshold = speechThreshold,
                        loadError = "VAD_RESOURCE_NOT_FOUND($resourceLabel)"
                    )
                val bytes = stream.use { it.readBytes() }
                fromModelBytes(modelBytes = bytes, speechThreshold = speechThreshold)
            } catch (t: Throwable) {
                SileroVadDetector(
                    ortEnv = null,
                    ortSession = null,
                    speechThreshold = speechThreshold,
                    loadError = "VAD_RESOURCE_LOAD_FAILED($resourceLabel): ${t::class.java.simpleName}: ${t.message}"
                )
            }
        }

        fun fromFile(
            file: File,
            speechThreshold: Float = DEFAULT_SPEECH_PROBABILITY_THRESHOLD
        ): SileroVadDetector {
            return try {
                if (!file.exists() || !file.isFile) {
                    return SileroVadDetector(
                        ortEnv = null,
                        ortSession = null,
                        speechThreshold = speechThreshold,
                        loadError = "VAD_FILE_NOT_FOUND(${file.absolutePath})"
                    )
                }
                fromModelBytes(modelBytes = file.readBytes(), speechThreshold = speechThreshold)
            } catch (t: Throwable) {
                SileroVadDetector(
                    ortEnv = null,
                    ortSession = null,
                    speechThreshold = speechThreshold,
                    loadError = "VAD_FILE_LOAD_FAILED(${file.name}): ${t::class.java.simpleName}: ${t.message}"
                )
            }
        }

        fun fromModelBytes(
            modelBytes: ByteArray,
            speechThreshold: Float = DEFAULT_SPEECH_PROBABILITY_THRESHOLD
        ): SileroVadDetector {
            if (modelBytes.isEmpty()) {
                return SileroVadDetector(
                    ortEnv = null,
                    ortSession = null,
                    speechThreshold = speechThreshold,
                    loadError = "VAD_MODEL_EMPTY_BYTES"
                )
            }
            return try {
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(1)
                    setInterOpNumThreads(1)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                val session = env.createSession(modelBytes, opts)
                SileroVadDetector(
                    ortEnv = env,
                    ortSession = session,
                    speechThreshold = speechThreshold,
                    loadError = null
                )
            } catch (t: Throwable) {
                SileroVadDetector(
                    ortEnv = null,
                    ortSession = null,
                    speechThreshold = speechThreshold,
                    loadError = "VAD_ORT_INIT_FAILED: ${t::class.java.simpleName}: ${t.message}"
                )
            }
        }

        fun failedForTesting(errorReason: String): SileroVadDetector {
            return SileroVadDetector(
                ortEnv = null,
                ortSession = null,
                speechThreshold = DEFAULT_SPEECH_PROBABILITY_THRESHOLD,
                loadError = errorReason
            )
        }
    }
}

/**
 * Lightweight pure-JVM detector used ONLY when [ConversationCoordinator] is constructed with default
 * arguments in legacy unit tests that have no Android Context and inject synthetic DC step arrays.
 * Production builds always inject [SileroVadDetector] with [requiresLoadedModel] = true.
 */
class DefaultNoContextSyntheticSpeechDetector(
    private val energyThresholdRms: Float = 0.012f,
    override val windowSizeSamples: Int = 320,
    override val sampleRateHz: Int = 16_000
) : SpeechNeuralDetector {
    override val detectorName: String = "JVM_SYNTHETIC_NO_CONTEXT"
    override val detectorVersion: String = "test-only"
    override val speechThreshold: Float = 0.50f
    override val isModelLoaded: Boolean = false
    override val loadError: String? = null
    override val requiresLoadedModel: Boolean = false

    override fun resetState() = Unit

    override fun inferWindow(
        samples16kHz: ShortArray,
        rmsLinear: Float
    ): NeuralVadInferenceResult {
        val computedRms = if (rmsLinear > 0f) {
            rmsLinear
        } else {
            var sumSq = 0.0
            for (s in samples16kHz) {
                val n = s / 32768.0
                sumSq += n * n
            }
            if (samples16kHz.isNotEmpty()) kotlin.math.sqrt(sumSq / samples16kHz.size).toFloat() else 0f
        }
        val active = computedRms >= energyThresholdRms
        val pseudoProb = if (active) 0.90f else 0.05f
        return NeuralVadInferenceResult(isSpeechWindow = active, speechProbability = pseudoProb)
    }
}
