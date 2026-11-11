package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import com.example.contract.SessionId
import java.io.File
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Hardware driver abstraction for [AudioCapture] so real Android [AudioRecord] / [AudioTrack]
 * and deterministic unit/Robolectric tests share the exact same [AudioCapture] owner,
 * generation-validation, resampling, and ephemeral deletion logic.
 */
interface AudioHardwareDriver {
    fun hasMicrophoneDevice(): Boolean
    fun startHardwareCapture(
        generationId: CaptureGenerationId,
        onEvent: (CaptureCallbackEvent) -> Unit
    ): HardwareStartResult
    fun stopHardwareCapture()
    fun playEphemeralPcm16kHz(
        generationId: CaptureGenerationId,
        samples16kHz: ShortArray,
        onCompleted: (CaptureCallbackEvent.EphemeralPlaybackCompleted) -> Unit
    ): Boolean
    fun stopEphemeralPlayback()
}

sealed interface HardwareStartResult {
    data class Started(val hardwareSampleRateHz: Int) : HardwareStartResult
    data class Failed(val anomaly: AudioAnomaly, val diagnostic: String) : HardwareStartResult
}

/**
 * Production Android implementation of [AudioHardwareDriver] using [AudioRecord],
 * [AudioTrack], and [AudioManager] focus/device checks.
 * Never instantiates SpeechRecognizer.
 */
class AndroidAudioHardwareDriver(
    private val context: Context
) : AudioHardwareDriver {

    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    @Volatile
    private var activeRecorder: AudioRecord? = null

    @Volatile
    private var activeTrack: AudioTrack? = null

    private val captureLoopRunning = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusListener: AudioManager.OnAudioFocusChangeListener? = null

    override fun hasMicrophoneDevice(): Boolean {
        val pmHasMic = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        if (!pmHasMic) return false
        val am = audioManager ?: return true
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return inputs.isNotEmpty()
    }

    @SuppressLint("MissingPermission")
    override fun startHardwareCapture(
        generationId: CaptureGenerationId,
        onEvent: (CaptureCallbackEvent) -> Unit
    ): HardwareStartResult {
        stopHardwareCapture()

        if (!hasMicrophoneDevice()) {
            return HardwareStartResult.Failed(
                anomaly = AudioAnomaly.MISSING_MICROPHONE_DEVICE,
                diagnostic = "No microphone input device reported by PackageManager/AudioManager."
            )
        }

        // Negotiate sample rate (prefer 16,000 Hz, fallback to 24kHz / 48kHz / 44.1kHz with resampler)
        var chosenRate = -1
        var chosenMinBuffer = -1
        for (candidateRate in PcmResampler.PREFERRED_HARDWARE_RATES_HZ) {
            val minBuf = AudioRecord.getMinBufferSize(
                candidateRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf > 0 && minBuf != AudioRecord.ERROR_BAD_VALUE && minBuf != AudioRecord.ERROR) {
                chosenRate = candidateRate
                chosenMinBuffer = minBuf
                break
            }
        }

        if (chosenRate <= 0) {
            return HardwareStartResult.Failed(
                anomaly = AudioAnomaly.RECORDER_START_FAILURE,
                diagnostic = "AudioRecord.getMinBufferSize rejected all candidate sample rates."
            )
        }

        val frameSamplesAtHwRate = (chosenRate * PcmResampler.FRAME_DURATION_MS) / 1000
        val bufferSizeInBytes = max(chosenMinBuffer * 2, frameSamplesAtHwRate * 4)

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                chosenRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeInBytes
            )
        } catch (sec: SecurityException) {
            return HardwareStartResult.Failed(
                anomaly = AudioAnomaly.PERMISSION_MISSING,
                diagnostic = "SecurityException creating AudioRecord: RECORD_AUDIO not granted."
            )
        } catch (t: Throwable) {
            return HardwareStartResult.Failed(
                anomaly = AudioAnomaly.RECORDER_START_FAILURE,
                diagnostic = "Exception creating AudioRecord (${t.javaClass.simpleName})."
            )
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            try {
                recorder.release()
            } catch (_: Throwable) {
            }
            return HardwareStartResult.Failed(
                anomaly = AudioAnomaly.RECORDER_START_FAILURE,
                diagnostic = "AudioRecord state is STATE_UNINITIALIZED."
            )
        }

        // Request transient exclusive audio focus so interruptions are detected immediately
        requestAudioFocusForGeneration(generationId, onEvent)

        try {
            recorder.startRecording()
        } catch (t: Throwable) {
            try {
                recorder.release()
            } catch (_: Throwable) {
            }
            abandonAudioFocus()
            return HardwareStartResult.Failed(
                anomaly = AudioAnomaly.RECORDER_START_FAILURE,
                diagnostic = "AudioRecord.startRecording() threw ${t.javaClass.simpleName}."
            )
        }

        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            try {
                recorder.stop()
            } catch (_: Throwable) {
            }
            try {
                recorder.release()
            } catch (_: Throwable) {
            }
            abandonAudioFocus()
            return HardwareStartResult.Failed(
                anomaly = AudioAnomaly.RECORDER_START_FAILURE,
                diagnostic = "AudioRecord failed to enter RECORDSTATE_RECORDING."
            )
        }

        activeRecorder = recorder
        captureLoopRunning.set(true)

        captureThread = thread(
            start = true,
            name = "OmniVoice-AudioCapture-${generationId.wireId}"
        ) {
            val readBuffer = ShortArray(frameSamplesAtHwRate)
            try {
                while (captureLoopRunning.get()) {
                    val readCount = recorder.read(readBuffer, 0, readBuffer.size)
                    if (!captureLoopRunning.get()) break

                    if (readCount > 0) {
                        val slice = if (readCount == readBuffer.size) {
                            readBuffer.copyOf()
                        } else {
                            readBuffer.copyOfRange(0, readCount)
                        }
                        val osSilenced = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            try {
                                recorder.activeRecordingConfiguration?.isClientSilenced == true
                            } catch (_: Throwable) {
                                false
                            }
                        } else {
                            false
                        }
                        onEvent(
                            CaptureCallbackEvent.FrameCaptured(
                                generationId = generationId,
                                pcmSamples = slice,
                                hardwareSampleRateHz = chosenRate,
                                osClientSilenced = osSilenced
                            )
                        )
                    } else if (readCount < 0) {
                        onEvent(
                            CaptureCallbackEvent.RecorderError(
                                generationId = generationId,
                                anomaly = AudioAnomaly.RECORDER_START_FAILURE,
                                diagnostic = "AudioRecord.read returned error code $readCount."
                            )
                        )
                        break
                    }
                }
            } catch (t: Throwable) {
                if (captureLoopRunning.get()) {
                    onEvent(
                        CaptureCallbackEvent.RecorderError(
                            generationId = generationId,
                            anomaly = AudioAnomaly.AUDIO_INTERRUPTED,
                            diagnostic = "Capture loop interrupted (${t.javaClass.simpleName})."
                        )
                    )
                }
            }
        }

        return HardwareStartResult.Started(hardwareSampleRateHz = chosenRate)
    }

    override fun stopHardwareCapture() {
        captureLoopRunning.set(false)
        val recorder = activeRecorder
        activeRecorder = null
        if (recorder != null) {
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            } catch (_: Throwable) {
            }
            try {
                recorder.release()
            } catch (_: Throwable) {
            }
        }
        captureThread?.let { worker ->
            if (worker != Thread.currentThread()) {
                try {
                    worker.join(150L)
                } catch (_: InterruptedException) {
                }
            }
        }
        captureThread = null
        abandonAudioFocus()
    }

    override fun playEphemeralPcm16kHz(
        generationId: CaptureGenerationId,
        samples16kHz: ShortArray,
        onCompleted: (CaptureCallbackEvent.EphemeralPlaybackCompleted) -> Unit
    ): Boolean {
        stopEphemeralPlayback()
        if (samples16kHz.isEmpty()) return false

        val minBufBytes = AudioTrack.getMinBufferSize(
            PcmResampler.TARGET_SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val payloadBytes = samples16kHz.size * 2
        val trackBufferBytes = max(max(minBufBytes, 1024), payloadBytes)

        return try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(PcmResampler.TARGET_SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(trackBufferBytes)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            activeTrack = track
            val written = track.write(samples16kHz, 0, samples16kHz.size)
            if (written <= 0) {
                track.release()
                activeTrack = null
                return false
            }
            track.play()

            val durationMs = ((samples16kHz.size * 1000L) / PcmResampler.TARGET_SAMPLE_RATE_HZ).coerceIn(80L, 6000L)
            thread(start = true, name = "OmniVoice-EphemeralPlayback-${generationId.wireId}") {
                try {
                    Thread.sleep(durationMs)
                } catch (_: InterruptedException) {
                } finally {
                    stopEphemeralPlayback()
                    onCompleted(
                        CaptureCallbackEvent.EphemeralPlaybackCompleted(
                            generationId = generationId,
                            playedSamplesCount = written
                        )
                    )
                }
            }
            true
        } catch (_: Throwable) {
            stopEphemeralPlayback()
            false
        }
    }

    override fun stopEphemeralPlayback() {
        val track = activeTrack
        activeTrack = null
        if (track != null) {
            try {
                track.stop()
            } catch (_: Throwable) {
            }
            try {
                track.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun requestAudioFocusForGeneration(
        generationId: CaptureGenerationId,
        onEvent: (CaptureCallbackEvent) -> Unit
    ) {
        val am = audioManager ?: return
        val listener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
            ) {
                onEvent(
                    CaptureCallbackEvent.InterruptionDetected(
                        generationId = generationId,
                        reason = "Android AudioFocus lost (code=$focusChange)."
                    )
                )
            }
        }
        focusListener = listener
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(listener)
                .build()
            focusRequest = req
            try {
                am.requestAudioFocus(req)
            } catch (_: Throwable) {
            }
        } else {
            @Suppress("DEPRECATION")
            try {
                am.requestAudioFocus(
                    listener,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
            } catch (_: Throwable) {
            }
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { req ->
                try {
                    am.abandonAudioFocusRequest(req)
                } catch (_: Throwable) {
                }
            }
            focusRequest = null
        } else {
            focusListener?.let { l ->
                @Suppress("DEPRECATION")
                try {
                    am.abandonAudioFocus(l)
                } catch (_: Throwable) {
                }
            }
        }
        focusListener = null
    }
}

/**
 * Single authoritative [AudioCapture] component for OmniVoice Rebuild (Phase 02).
 *
 * Responsibilities:
 * - Enforces a single [AudioOwner] and monotonic [CaptureGenerationId].
 * - Validates that every callback belongs to the currently active [CaptureGenerationId],
 *   deterministically dropping stale callbacks.
 * - Prohibits simultaneous SpeechRecognizer and AudioRecord loops.
 * - Normalizes/resamples all incoming audio to 16,000 Hz 16-bit Mono PCM.
 * - Computes live RMS (linear and dBFS), captured frame count, and detects OS-imposed silence,
 *   missing microphone hardware, recorder start failures, and audio interruptions.
 * - Manages a bounded ephemeral self-test recording buffer (max 5 seconds @ 16 kHz) with
 *   retention OFF and immediate zeroing/file deletion after playback check or Stop.
 */
class AudioCapture(
    private val driver: AudioHardwareDriver,
    private val ephemeralCacheDir: File? = null
) {
    companion object {
        const val MAX_EPHEMERAL_SAMPLES_16KHZ = PcmResampler.TARGET_SAMPLE_RATE_HZ * 5 // 5 seconds max
        const val ANDROID_SILENCE_FRAME_THRESHOLD = 15 // ~300ms of continuous zero/silenced frames
        private const val EPHEMERAL_TEMP_FILENAME = "omnivoice_p02_ephemeral_check.pcm"
    }

    private val generationSeq = AtomicLong(0L)

    // Bounded in-memory ephemeral PCM buffer (zeroed on deletion/stop)
    private var ephemeralBuffer = ShortArray(MAX_EPHEMERAL_SAMPLES_16KHZ)
    private var ephemeralWriteIndex = 0

    private val _state = MutableStateFlow(
        AudioCaptureState(
            hasMicrophoneHardware = driver.hasMicrophoneDevice()
        )
    )
    val state: StateFlow<AudioCaptureState> = _state.asStateFlow()

    @Synchronized
    fun updatePermissionState(newPermissionState: MicPermissionState) {
        val hasMic = driver.hasMicrophoneDevice()
        _state.update { current ->
            val updatedAnomaly = when {
                !hasMic -> AudioAnomaly.MISSING_MICROPHONE_DEVICE
                newPermissionState == MicPermissionState.GRANTED &&
                    current.anomaly == AudioAnomaly.PERMISSION_MISSING -> AudioAnomaly.NONE
                else -> current.anomaly
            }
            current.copy(
                permissionState = newPermissionState,
                hasMicrophoneHardware = hasMic,
                anomaly = updatedAnomaly,
                statusDetail = "Mic permission: ${newPermissionState.wireLabel}. ${newPermissionState.userSummary}"
            )
        }
    }

    @Synchronized
    fun setEphemeralCheckArmed(armed: Boolean) {
        if (!armed) {
            wipeEphemeralAudio("Retention OFF — Ephemeral recording disarmed & wiped (0 bytes)")
        } else {
            _state.update { it.copy(ephemeralCheckArmed = true) }
        }
    }

    /**
     * Starts a new capture generation under [owner] and [sessionId].
     * Any previously running capture or ephemeral playback is stopped first.
     */
    @Synchronized
    fun startCapture(
        owner: AudioOwner,
        sessionId: SessionId
    ): CaptureGenerationId {
        require(owner != AudioOwner.NONE) { "AudioOwner must be explicit when starting capture." }

        // Release any prior capture/playback before allocating a new generation
        if (_state.value.isCapturing || _state.value.isPlayingEphemeralCheck) {
            stopAndRelease("Preempted by new capture generation for ${owner.wireLabel}")
        }

        val newGen = CaptureGenerationId(
            generation = generationSeq.incrementAndGet(),
            owner = owner,
            sessionId = sessionId
        )

        val currentPerm = _state.value.permissionState
        if (currentPerm != MicPermissionState.GRANTED) {
            _state.update {
                it.copy(
                    owner = AudioOwner.NONE,
                    activeGeneration = null,
                    lastCompletedGeneration = newGen,
                    isCapturing = false,
                    anomaly = AudioAnomaly.PERMISSION_MISSING,
                    statusDetail = "Cannot start capture (${newGen.wireId}): RECORD_AUDIO permission is ${currentPerm.wireLabel}."
                )
            }
            return newGen
        }

        if (!driver.hasMicrophoneDevice()) {
            _state.update {
                it.copy(
                    hasMicrophoneHardware = false,
                    owner = AudioOwner.NONE,
                    activeGeneration = null,
                    lastCompletedGeneration = newGen,
                    isCapturing = false,
                    anomaly = AudioAnomaly.MISSING_MICROPHONE_DEVICE,
                    statusDetail = "Cannot start capture (${newGen.wireId}): No microphone input device detected."
                )
            }
            return newGen
        }

        // Reset ephemeral write head for the fresh capture check
        Arrays.fill(ephemeralBuffer, 0.toShort())
        ephemeralWriteIndex = 0
        deleteEphemeralTempFile()

        // Activate generation before driver start so synchronous callbacks are recognized
        _state.update {
            it.copy(
                hasMicrophoneHardware = true,
                owner = owner,
                activeGeneration = newGen,
                isCapturing = true,
                isPlayingEphemeralCheck = false,
                capturedFrameCount = 0L,
                capturedSampleCount = 0L,
                capturedDurationMs = 0L,
                currentRmsLinear = 0f,
                peakRmsLinear = 0f,
                currentRmsDbfs = -96.0f,
                consecutiveSilentFrames = 0,
                anomaly = AudioAnomaly.NONE,
                ephemeralRecordedSamples = 0,
                ephemeralDurationMs = 0L,
                statusDetail = "Capture started (${newGen.wireId}). Target: 16kHz PCM-16 Mono. Retention OFF."
            )
        }

        when (val startResult = driver.startHardwareCapture(newGen, ::onHardwareCallback)) {
            is HardwareStartResult.Started -> {
                val hwRate = startResult.hardwareSampleRateHz
                val resampling = hwRate != PcmResampler.TARGET_SAMPLE_RATE_HZ
                _state.update {
                    it.copy(
                        hardwareSampleRateHz = hwRate,
                        targetSampleRateHz = PcmResampler.TARGET_SAMPLE_RATE_HZ,
                        resamplingActive = resampling,
                        statusDetail = if (resampling) {
                            "Capturing (${newGen.wireId}): Hardware ${hwRate}Hz -> Resampled to 16000Hz Mono PCM-16."
                        } else {
                            "Capturing (${newGen.wireId}): Native 16000Hz Mono PCM-16."
                        }
                    )
                }
            }
            is HardwareStartResult.Failed -> {
                driver.stopHardwareCapture()
                _state.update {
                    it.copy(
                        owner = AudioOwner.NONE,
                        activeGeneration = null,
                        lastCompletedGeneration = newGen,
                        isCapturing = false,
                        anomaly = startResult.anomaly,
                        hardwareReleaseCount = it.hardwareReleaseCount + 1,
                        statusDetail = "Recorder start failed (${newGen.wireId}): ${startResult.diagnostic}"
                    )
                }
            }
        }

        return newGen
    }

    /**
     * Processes a hardware callback event ONLY if its [CaptureGenerationId] matches the
     * currently active generation. Stale callbacks are counted and dropped.
     */
    @Synchronized
    fun onHardwareCallback(event: CaptureCallbackEvent): Boolean {
        val activeGen = _state.value.activeGeneration
        if (activeGen == null || event.generationId != activeGen) {
            _state.update {
                it.copy(
                    staleCallbacksDropped = it.staleCallbacksDropped + 1
                )
            }
            return false
        }

        when (event) {
            is CaptureCallbackEvent.FrameCaptured -> {
                val resampled16k = PcmResampler.resampleMono16Bit(
                    input = event.pcmSamples,
                    inputRateHz = event.hardwareSampleRateHz,
                    targetRateHz = PcmResampler.TARGET_SAMPLE_RATE_HZ
                )
                val (rmsLinear, rmsDbfs) = PcmResampler.computeRmsMetrics(resampled16k)
                val isFrameSilent = event.osClientSilenced || rmsLinear <= 1e-5f

                // Buffer into ephemeral self-test clip if armed (bounded to 5s, retention OFF)
                if (_state.value.ephemeralCheckArmed && resampled16k.isNotEmpty()) {
                    val spaceLeft = MAX_EPHEMERAL_SAMPLES_16KHZ - ephemeralWriteIndex
                    if (spaceLeft > 0) {
                        val toCopy = minOf(spaceLeft, resampled16k.size)
                        System.arraycopy(resampled16k, 0, ephemeralBuffer, ephemeralWriteIndex, toCopy)
                        ephemeralWriteIndex += toCopy
                    }
                }

                _state.update { current ->
                    val newFrames = current.capturedFrameCount + 1L
                    val newSamples = current.capturedSampleCount + resampled16k.size
                    val newDurationMs = (newSamples * 1000L) / PcmResampler.TARGET_SAMPLE_RATE_HZ
                    val newSilentStreak = if (isFrameSilent) current.consecutiveSilentFrames + 1 else 0
                    val silenceDetected = event.osClientSilenced ||
                        (newSilentStreak >= ANDROID_SILENCE_FRAME_THRESHOLD)
                    val newAnomaly = when {
                        silenceDetected -> AudioAnomaly.ANDROID_IMPOSED_SILENCE
                        current.anomaly == AudioAnomaly.ANDROID_IMPOSED_SILENCE && !isFrameSilent -> AudioAnomaly.NONE
                        else -> current.anomaly
                    }
                    val ephemeralMs = (ephemeralWriteIndex * 1000L) / PcmResampler.TARGET_SAMPLE_RATE_HZ

                    current.copy(
                        hardwareSampleRateHz = event.hardwareSampleRateHz,
                        resamplingActive = event.hardwareSampleRateHz != PcmResampler.TARGET_SAMPLE_RATE_HZ,
                        capturedFrameCount = newFrames,
                        capturedSampleCount = newSamples,
                        capturedDurationMs = newDurationMs,
                        currentRmsLinear = rmsLinear,
                        peakRmsLinear = max(current.peakRmsLinear, rmsLinear),
                        currentRmsDbfs = rmsDbfs,
                        consecutiveSilentFrames = newSilentStreak,
                        anomaly = newAnomaly,
                        ephemeralRecordedSamples = ephemeralWriteIndex,
                        ephemeralDurationMs = ephemeralMs,
                        ephemeralLastWipedStatus = if (ephemeralWriteIndex > 0) {
                            "Ephemeral buffer holding $ephemeralWriteIndex samples (${ephemeralMs}ms) for playback check — Retention OFF"
                        } else {
                            current.ephemeralLastWipedStatus
                        },
                        statusDetail = if (silenceDetected) {
                            "WARNING (${activeGen.wireId}): Android-imposed silence detected ($newSilentStreak silent frames)."
                        } else {
                            "Capturing (${activeGen.wireId}): Frame #$newFrames | RMS ${"%.3f".format(rmsLinear)} (${"%.1f".format(rmsDbfs)} dBFS)"
                        }
                    )
                }
            }

            is CaptureCallbackEvent.RecorderError -> {
                driver.stopHardwareCapture()
                wipeEphemeralAudio("Retention OFF — Ephemeral buffer wiped on recorder error (0 bytes)")
                _state.update {
                    it.copy(
                        owner = AudioOwner.NONE,
                        activeGeneration = null,
                        lastCompletedGeneration = activeGen,
                        isCapturing = false,
                        currentRmsLinear = 0f,
                        currentRmsDbfs = -96.0f,
                        anomaly = event.anomaly,
                        hardwareReleaseCount = it.hardwareReleaseCount + 1,
                        statusDetail = "Recorder error (${activeGen.wireId}): ${event.diagnostic}"
                    )
                }
            }

            is CaptureCallbackEvent.InterruptionDetected -> {
                driver.stopHardwareCapture()
                wipeEphemeralAudio("Retention OFF — Ephemeral buffer wiped on interruption (0 bytes)")
                _state.update {
                    it.copy(
                        owner = AudioOwner.NONE,
                        activeGeneration = null,
                        lastCompletedGeneration = activeGen,
                        isCapturing = false,
                        currentRmsLinear = 0f,
                        currentRmsDbfs = -96.0f,
                        anomaly = AudioAnomaly.AUDIO_INTERRUPTED,
                        hardwareReleaseCount = it.hardwareReleaseCount + 1,
                        statusDetail = "Capture interrupted (${activeGen.wireId}): ${event.reason}"
                    )
                }
            }

            is CaptureCallbackEvent.EphemeralPlaybackCompleted -> {
                driver.stopEphemeralPlayback()
                wipeEphemeralAudio(
                    "Retention OFF — Ephemeral clip played (${event.playedSamplesCount} samples) & immediately deleted (0 bytes remaining)"
                )
                _state.update {
                    it.copy(
                        owner = AudioOwner.NONE,
                        activeGeneration = null,
                        lastCompletedGeneration = activeGen,
                        isPlayingEphemeralCheck = false,
                        ephemeralPlaybackCount = it.ephemeralPlaybackCount + 1,
                        statusDetail = "Ephemeral playback verification completed (${activeGen.wireId}); temporary audio permanently deleted."
                    )
                }
            }
        }
        return true
    }

    /**
     * Stops active capture and releases AudioRecord hardware immediately.
     * If [wipeEphemeral] is false, preserves the just-recorded ephemeral clip solely so the user
     * can press "Play & Delete Ephemeral Check" right after stopping capture; if true (such as
     * on Large Stop or navigation away), immediately deletes the ephemeral clip as well.
     */
    @Synchronized
    fun stopAndRelease(
        reason: String = "Stopped by operator",
        wipeEphemeral: Boolean = false
    ) {
        val prevGen = _state.value.activeGeneration
        val wasCapturingOrPlaying = _state.value.isCapturing || _state.value.isPlayingEphemeralCheck

        driver.stopHardwareCapture()
        driver.stopEphemeralPlayback()

        if (wipeEphemeral) {
            wipeEphemeralAudio("Retention OFF — Ephemeral buffer wiped on Stop/Release ($reason, 0 bytes)")
        }

        _state.update { current ->
            current.copy(
                owner = AudioOwner.NONE,
                activeGeneration = null,
                lastCompletedGeneration = prevGen ?: current.lastCompletedGeneration,
                isCapturing = false,
                isPlayingEphemeralCheck = false,
                currentRmsLinear = 0f,
                currentRmsDbfs = -96.0f,
                hardwareReleaseCount = if (wasCapturingOrPlaying || prevGen != null) {
                    current.hardwareReleaseCount + 1
                } else {
                    current.hardwareReleaseCount
                },
                statusDetail = "Hardware released (${prevGen?.wireId ?: "no-active-gen"}): $reason."
            )
        }
    }

    /**
     * Plays the temporary self-test recording to verify intelligible sound and immediately
     * deletes/zeroes the ephemeral buffer and any temporary file upon completion.
     */
    @Synchronized
    fun playAndDeleteEphemeralCheck(sessionId: SessionId): Boolean {
        if (_state.value.isCapturing) {
            stopAndRelease(reason = "Stopped capture for ephemeral playback check", wipeEphemeral = false)
        }

        val sampleCount = ephemeralWriteIndex
        if (sampleCount <= 0) {
            _state.update {
                it.copy(
                    statusDetail = "No ephemeral audio recorded yet. Capture a short voice phrase first."
                )
            }
            return false
        }

        val clipCopy = ephemeralBuffer.copyOfRange(0, sampleCount)
        // Immediately wipe the primary ephemeral buffer before playback even finishes (zero retention)
        Arrays.fill(ephemeralBuffer, 0.toShort())
        ephemeralWriteIndex = 0
        deleteEphemeralTempFile()

        val playbackGen = CaptureGenerationId(
            generation = generationSeq.incrementAndGet(),
            owner = AudioOwner.MIC_SELF_TEST,
            sessionId = sessionId
        )

        _state.update {
            it.copy(
                owner = AudioOwner.MIC_SELF_TEST,
                activeGeneration = playbackGen,
                isCapturing = false,
                isPlayingEphemeralCheck = true,
                ephemeralRecordedSamples = 0,
                ephemeralDurationMs = 0L,
                ephemeralLastWipedStatus = "Playing $sampleCount samples then wiping temporary buffer...",
                statusDetail = "Playing ephemeral check (${playbackGen.wireId}, $sampleCount samples @ 16kHz)..."
            )
        }

        val started = driver.playEphemeralPcm16kHz(
            generationId = playbackGen,
            samples16kHz = clipCopy,
            onCompleted = { completedEvent ->
                Arrays.fill(clipCopy, 0.toShort())
                onHardwareCallback(completedEvent)
            }
        )

        if (!started) {
            Arrays.fill(clipCopy, 0.toShort())
            _state.update {
                it.copy(
                    owner = AudioOwner.NONE,
                    activeGeneration = null,
                    lastCompletedGeneration = playbackGen,
                    isPlayingEphemeralCheck = false,
                    ephemeralLastWipedStatus = "Retention OFF — Playback failed; ephemeral clip deleted (0 bytes)",
                    statusDetail = "AudioTrack playback failed; ephemeral buffer deleted."
                )
            }
            return false
        }

        return true
    }

    /**
     * Explicitly wipes the ephemeral buffer and deletes any temporary cache file.
     */
    @Synchronized
    fun wipeEphemeralAudio(statusMessage: String = "Retention OFF — Ephemeral clip deleted (0 bytes remaining)") {
        Arrays.fill(ephemeralBuffer, 0.toShort())
        ephemeralWriteIndex = 0
        deleteEphemeralTempFile()
        _state.update {
            it.copy(
                ephemeralRecordedSamples = 0,
                ephemeralDurationMs = 0L,
                ephemeralLastWipedStatus = statusMessage
            )
        }
    }

    private fun deleteEphemeralTempFile() {
        val dir = ephemeralCacheDir ?: return
        try {
            val file = File(dir, EPHEMERAL_TEMP_FILENAME)
            if (file.exists()) {
                file.delete()
            }
        } catch (_: Throwable) {
        }
    }
}
