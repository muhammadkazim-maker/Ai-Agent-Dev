package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import com.example.contract.SessionId
import com.example.contract.TurnId
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Hardware streaming output abstraction for [LiveAudioPlaybackAdapter].
 * Allows production Android [AudioTrack] and deterministic unit/Robolectric tests to share
 * the exact same MIME verification, short/partial/zero write loop, hardware playback head drain
 * verification, and generation-bound callback filtering.
 */
interface StreamingAudioTrackDriver {
    fun openStreamingTrack(
        generationId: PlaybackGenerationId,
        sampleRateHz: Int,
        channelCount: Int = 1
    ): Boolean

    fun writePcmBytes(
        generationId: PlaybackGenerationId,
        pcmBytes: ByteArray,
        offset: Int,
        length: Int
    ): Int

    fun finishStreamForDrain(generationId: PlaybackGenerationId) {}
    fun getPlaybackHeadFrames(generationId: PlaybackGenerationId): Long
    fun stopAndFlushTrack(generationId: PlaybackGenerationId?)
}

/**
 * Production Android implementation of [StreamingAudioTrackDriver] using [AudioTrack]
 * in [AudioTrack.MODE_STREAM] for 16-bit little-endian PCM output.
 */
class AndroidStreamingAudioTrackDriver : StreamingAudioTrackDriver {
    private val lock = Any()
    private var activeTrack: AudioTrack? = null
    private var activeGenerationId: PlaybackGenerationId? = null
    private var activeSampleRateHz: Int = 0
    private var activeChannelCount: Int = 1

    override fun openStreamingTrack(
        generationId: PlaybackGenerationId,
        sampleRateHz: Int,
        channelCount: Int
    ): Boolean {
        synchronized(lock) {
            val existing = activeTrack
            if (existing != null &&
                activeGenerationId == generationId &&
                activeSampleRateHz == sampleRateHz &&
                activeChannelCount == channelCount &&
                existing.state == AudioTrack.STATE_INITIALIZED
            ) {
                if (existing.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    try {
                        existing.play()
                    } catch (_: Throwable) {
                        return false
                    }
                }
                return true
            }

            releaseInternalLocked()

            val channelMask = if (channelCount == 2) {
                AudioFormat.CHANNEL_OUT_STEREO
            } else {
                AudioFormat.CHANNEL_OUT_MONO
            }

            val minBufBytes = AudioTrack.getMinBufferSize(
                sampleRateHz,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufBytes <= 0 || minBufBytes == AudioTrack.ERROR || minBufBytes == AudioTrack.ERROR_BAD_VALUE) {
                return false
            }

            // Size streaming buffer to ~200ms (or 2x minBufBytes) so AudioFlinger starts playing
            // immediately on the first chunk instead of holding <1000ms responses in pre-roll at head=0.
            val bytesPerFrame = 2 * channelCount.coerceAtLeast(1)
            val bytesFor200Ms = (sampleRateHz * bytesPerFrame) / 5
            val bufferSizeInBytes = max(minBufBytes * 2, bytesFor200Ms)

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
                            .setSampleRate(sampleRateHz)
                            .setChannelMask(channelMask)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSizeInBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    track.release()
                    return false
                }

                track.play()
                activeTrack = track
                activeGenerationId = generationId
                activeSampleRateHz = sampleRateHz
                activeChannelCount = channelCount
                true
            } catch (_: Throwable) {
                releaseInternalLocked()
                false
            }
        }
    }

    override fun writePcmBytes(
        generationId: PlaybackGenerationId,
        pcmBytes: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        val track = synchronized(lock) {
            if (activeGenerationId != generationId) return AudioTrack.ERROR_INVALID_OPERATION
            activeTrack
        } ?: return AudioTrack.ERROR_INVALID_OPERATION

        return try {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                track.write(pcmBytes, offset, length, AudioTrack.WRITE_NON_BLOCKING)
            } else {
                track.write(pcmBytes, offset, length)
            }
        } catch (_: Throwable) {
            AudioTrack.ERROR_DEAD_OBJECT
        }
    }

    override fun finishStreamForDrain(generationId: PlaybackGenerationId) {
        synchronized(lock) {
            if (activeGenerationId != generationId) return
            val track = activeTrack ?: return
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    // In AudioTrack.MODE_STREAM, stop() marks end-of-stream and flushes all
                    // buffered frames to hardware playback even when total written frames are smaller
                    // than the initial pre-roll threshold.
                    track.stop()
                }
            } catch (_: Throwable) {
            }
        }
    }

    override fun getPlaybackHeadFrames(generationId: PlaybackGenerationId): Long {
        val track = synchronized(lock) {
            if (activeGenerationId != generationId) return 0L
            activeTrack
        } ?: return 0L

        return try {
            // Convert unsigned 32-bit playbackHeadPosition to Long frames
            track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        } catch (_: Throwable) {
            0L
        }
    }

    override fun stopAndFlushTrack(generationId: PlaybackGenerationId?) {
        synchronized(lock) {
            if (generationId != null && activeGenerationId != null && activeGenerationId != generationId) {
                return
            }
            releaseInternalLocked()
        }
    }

    private fun releaseInternalLocked() {
        val track = activeTrack
        activeTrack = null
        activeGenerationId = null
        activeSampleRateHz = 0
        activeChannelCount = 1
        if (track != null) {
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                }
            } catch (_: Throwable) {
            }
            try {
                track.flush()
            } catch (_: Throwable) {
            }
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
}

/**
 * Deterministic test driver for [LiveAudioPlaybackAdapter] used in unit and Robolectric tests.
 * Supports scripting:
 * - Zero writes (`0`) and short/partial writes (`1..length-1`) to prove no bytes are dropped.
 * - Controlled hardware playback head progression (`autoAdvancePlaybackHead = false`) to prove
 *   that queue emptiness and server completion alone do NOT mark `PLAYBACK_DRAINED` until the
 *   hardware playback head reaches `totalFramesWrittenToHardware`.
 */
class DeterministicStreamingAudioTrackDriver(
    var openTrackSucceeds: Boolean = true,
    var autoAdvancePlaybackHead: Boolean = true
) : StreamingAudioTrackDriver {
    private val scriptedWriteSizes = ArrayDeque<Int>()
    private val acceptedBytesStream = java.io.ByteArrayOutputStream()

    var activeGenerationId: PlaybackGenerationId? = null
        private set
    var openedSampleRateHz: Int = 0
        private set
    var openedChannelCount: Int = 1
        private set
    var totalBytesAccepted: Long = 0L
        private set
    var manualPlaybackHeadFrames: Long = 0L
    var stopAndFlushCallCount: Int = 0
        private set
    var finishStreamForDrainCallCount: Int = 0
        private set
    var writeInvocationCount: Int = 0
        private set

    fun enqueueScriptedWriteSizes(vararg sizes: Int) {
        for (size in sizes) {
            scriptedWriteSizes.addLast(size)
        }
    }

    fun getAcceptedPcmBytes(): ByteArray = acceptedBytesStream.toByteArray()

    fun resetRecordedBytes() {
        scriptedWriteSizes.clear()
        acceptedBytesStream.reset()
        totalBytesAccepted = 0L
        manualPlaybackHeadFrames = 0L
        writeInvocationCount = 0
        finishStreamForDrainCallCount = 0
    }

    override fun openStreamingTrack(
        generationId: PlaybackGenerationId,
        sampleRateHz: Int,
        channelCount: Int
    ): Boolean {
        if (!openTrackSucceeds) return false
        if (activeGenerationId != generationId) {
            acceptedBytesStream.reset()
            totalBytesAccepted = 0L
            manualPlaybackHeadFrames = 0L
            finishStreamForDrainCallCount = 0
        }
        activeGenerationId = generationId
        openedSampleRateHz = sampleRateHz
        openedChannelCount = channelCount
        return true
    }

    override fun writePcmBytes(
        generationId: PlaybackGenerationId,
        pcmBytes: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        if (activeGenerationId != generationId) return -1
        writeInvocationCount += 1

        val scripted = if (scriptedWriteSizes.isNotEmpty()) {
            scriptedWriteSizes.removeFirst()
        } else {
            length
        }

        if (scripted < 0) return scripted
        if (scripted == 0) return 0

        val actualToWrite = scripted.coerceAtMost(length)
        acceptedBytesStream.write(pcmBytes, offset, actualToWrite)
        totalBytesAccepted += actualToWrite
        val bytesPerFrame = (2 * openedChannelCount.coerceAtLeast(1)).toLong()
        if (autoAdvancePlaybackHead) {
            manualPlaybackHeadFrames = totalBytesAccepted / bytesPerFrame
        }
        return actualToWrite
    }

    override fun finishStreamForDrain(generationId: PlaybackGenerationId) {
        if (activeGenerationId == generationId) {
            finishStreamForDrainCallCount += 1
        }
    }

    override fun getPlaybackHeadFrames(generationId: PlaybackGenerationId): Long {
        if (activeGenerationId != generationId) return 0L
        val bytesPerFrame = (2 * openedChannelCount.coerceAtLeast(1)).toLong()
        return if (autoAdvancePlaybackHead) {
            totalBytesAccepted / bytesPerFrame
        } else {
            manualPlaybackHeadFrames
        }
    }

    override fun stopAndFlushTrack(generationId: PlaybackGenerationId?) {
        if (generationId != null && activeGenerationId != null && activeGenerationId != generationId) {
            return
        }
        stopAndFlushCallCount += 1
        activeGenerationId = null
    }
}

/**
 * Single streaming PCM audio playback adapter for OmniVoice Rebuild (Phase 04).
 *
 * Enforces Phase 04 playback invariants:
 * 1. Verifies server-returned MIME type, sample rate, and channel count via [PcmMimeVerifier] before
 *    opening [AudioTrack]; never guesses a sample rate when MIME metadata is missing or invalid.
 * 2. Serializes chunk writes via [writeMutex] and handles short/partial writes (`0 < written < remaining`)
 *    and zero writes (`written == 0`) in a deterministic loop without dropping any remaining bytes.
 * 3. Explicitly distinguishes `AUDIO_RECEIVED`, `BYTES_ACCEPTED_BY_HARDWARE`, and `PLAYBACK_DRAINED`.
 * 4. Enforces the Audible Completion Rule: queue emptiness (`pendingQueuedBytes == 0`) and
 *    server completion (`serverTurnComplete == true`) alone are NOT audible completion;
 *    [awaitHardwareDrainAfterServerTurnComplete] waits for all queued audio buffers to finish writing
 *    and then polls hardware until `hardwarePlaybackHeadFrames >= totalFramesWrittenToHardware`.
 * 5. Never cuts long responses with a fixed short timeout while the hardware playback head is advancing.
 * 6. Binds all callbacks and state mutations to [PlaybackGenerationId]; late callbacks from
 *    stopped or superseded turns are dropped and counted (`stalePlaybackCallbacksDropped`).
 */
class LiveAudioPlaybackAdapter(
    val driver: StreamingAudioTrackDriver = AndroidStreamingAudioTrackDriver(),
    private val maxZeroWriteRetriesPerChunk: Int = 600,
    private val maxDrainPollIterations: Int = 600,
    private val writeRetryDelayRunner: suspend (Long) -> Unit = { ms -> delay(ms) },
    private val drainPollDelayRunner: suspend (Long) -> Unit = { ms -> delay(ms) }
) {
    private val lock = Any()
    private val writeMutex = Mutex()
    private val generationSeq = AtomicLong(0L)

    private val _state = MutableStateFlow(LiveAudioPlaybackState())
    val state: StateFlow<LiveAudioPlaybackState> = _state.asStateFlow()

    @Volatile
    var onStateChanged: ((LiveAudioPlaybackState) -> Unit)? = null

    private fun updateStateAndNotify(transform: (LiveAudioPlaybackState) -> LiveAudioPlaybackState) {
        _state.update(transform)
        onStateChanged?.invoke(_state.value)
    }

    /**
     * Allocates a fresh [PlaybackGenerationId] for a single voice turn.
     * Any previously active playback track is flushed and released first.
     */
    fun startTurnPlayback(turnId: TurnId, sessionId: SessionId): PlaybackGenerationId {
        val newGen: PlaybackGenerationId
        val prevGen: PlaybackGenerationId?
        synchronized(lock) {
            prevGen = _state.value.activeGeneration
            newGen = PlaybackGenerationId(
                generation = generationSeq.incrementAndGet(),
                turnId = turnId,
                sessionId = sessionId
            )
            updateStateAndNotify { current ->
                current.copy(
                    activeGeneration = newGen,
                    lastCompletedGeneration = prevGen ?: current.lastCompletedGeneration,
                    isTrackOpen = false,
                    isPlaying = false,
                    isDrainingHardware = false,
                    isPlaybackDrained = false,
                    serverTurnComplete = false,
                    verifiedMimeType = "NONE",
                    verifiedSampleRateHz = 0,
                    chunksReceived = 0,
                    framesReceivedFromServer = 0L,
                    audioBytesReceived = 0L,
                    pendingQueuedBytes = 0L,
                    bytesAcceptedByHardware = 0L,
                    totalFramesWrittenToHardware = 0L,
                    hardwarePlaybackHeadFrames = 0L,
                    partialWriteCount = 0,
                    zeroWriteRetryCount = 0,
                    lastError = null,
                    statusDetail = "Playback generation ${newGen.wireId} initialized; awaiting verified PCM audio chunks."
                )
            }
        }
        if (prevGen != null) {
            driver.stopAndFlushTrack(prevGen)
        }
        return newGen
    }

    /**
     * Verifies the server chunk's MIME type and sample rate, enqueues its byte count immediately,
     * and serializes writing all PCM bytes to the hardware track in FIFO order, looping over any
     * zero (`0`) or partial (`1..remaining-1`) writes so that zero bytes are ever dropped.
     */
    suspend fun verifyAndWritePcmChunk(
        generationId: PlaybackGenerationId,
        rawMimeType: String?,
        pcmBytes: ByteArray,
        onEvent: (PlaybackCallbackEvent) -> Unit = {}
    ): PcmChunkWriteOutcome {
        // 1. Check generation validity before processing
        synchronized(lock) {
            val currentGen = _state.value.activeGeneration
            if (currentGen == null || currentGen != generationId) {
                updateStateAndNotify {
                    it.copy(stalePlaybackCallbacksDropped = it.stalePlaybackCallbacksDropped + 1)
                }
                return PcmChunkWriteOutcome.DroppedStaleGeneration(
                    generationId = generationId,
                    activeGenerationId = currentGen
                )
            }
        }

        // 2. Verify MIME type, sample rate, and channel count (never guess)
        val mimeVerification = PcmMimeVerifier.verifyAndParseSampleRate(rawMimeType)
        if (mimeVerification is PcmMimeVerificationResult.Rejected) {
            stopAndFlush("Rejected unverified/unsupported MIME (${mimeVerification.reason})", error = mimeVerification.reason)
            onEvent(
                PlaybackCallbackEvent.PlaybackFailed(
                    generationId = generationId,
                    reason = mimeVerification.reason
                )
            )
            return PcmChunkWriteOutcome.RejectedMime(
                generationId = generationId,
                rawMimeType = rawMimeType,
                reason = mimeVerification.reason
            )
        }
        val verified = mimeVerification as PcmMimeVerificationResult.Verified

        // Align 16-bit PCM byte length (2 bytes per sample * channelCount)
        val bytesPerFrame = 2 * verified.channelCount.coerceAtLeast(1)
        val alignedByteCount = pcmBytes.size - (pcmBytes.size % bytesPerFrame)
        if (alignedByteCount <= 0) {
            val reason = "INVALID_PCM_PAYLOAD: Audio chunk has 0 aligned 16-bit PCM bytes (size=${pcmBytes.size})."
            stopAndFlush(reason, error = reason)
            onEvent(PlaybackCallbackEvent.PlaybackFailed(generationId, reason))
            return PcmChunkWriteOutcome.FailedWrite(generationId, reason)
        }
        val chunkFrames = alignedByteCount.toLong() / bytesPerFrame.toLong()

        // 3. Record AUDIO_RECEIVED milestone immediately so pendingQueuedBytes reflects all arrived chunks
        val totalReceivedAfterChunk: Long
        synchronized(lock) {
            val currentGen = _state.value.activeGeneration
            if (currentGen != generationId) {
                updateStateAndNotify {
                    it.copy(stalePlaybackCallbacksDropped = it.stalePlaybackCallbacksDropped + 1)
                }
                return PcmChunkWriteOutcome.DroppedStaleGeneration(generationId, currentGen)
            }
            totalReceivedAfterChunk = _state.value.audioBytesReceived + alignedByteCount
            updateStateAndNotify { current ->
                current.copy(
                    verifiedMimeType = verified.canonicalMimeType,
                    verifiedSampleRateHz = verified.sampleRateHz,
                    chunksReceived = current.chunksReceived + 1,
                    framesReceivedFromServer = current.framesReceivedFromServer + chunkFrames,
                    audioBytesReceived = totalReceivedAfterChunk,
                    pendingQueuedBytes = current.pendingQueuedBytes + alignedByteCount,
                    isPlaybackDrained = false,
                    statusDetail = "AUDIO_RECEIVED (${generationId.wireId}): Chunk #${current.chunksReceived + 1} ($alignedByteCount bytes / $chunkFrames frames @ ${verified.canonicalMimeType})."
                )
            }
        }

        onEvent(
            PlaybackCallbackEvent.AudioChunkReceived(
                generationId = generationId,
                verifiedMimeType = verified.canonicalMimeType,
                sampleRateHz = verified.sampleRateHz,
                chunkBytes = alignedByteCount,
                totalBytesReceived = totalReceivedAfterChunk
            )
        )

        // 4. Serialize hardware AudioTrack writes in FIFO order via writeMutex
        return writeMutex.withLock {
            val activeAtLock = synchronized(lock) { _state.value.activeGeneration }
            if (activeAtLock != generationId) {
                synchronized(lock) {
                    updateStateAndNotify {
                        it.copy(stalePlaybackCallbacksDropped = it.stalePlaybackCallbacksDropped + 1)
                    }
                }
                return@withLock PcmChunkWriteOutcome.DroppedStaleGeneration(
                    generationId = generationId,
                    activeGenerationId = activeAtLock
                )
            }

            val trackOpened = driver.openStreamingTrack(
                generationId = generationId,
                sampleRateHz = verified.sampleRateHz,
                channelCount = verified.channelCount
            )
            if (!trackOpened) {
                val reason = "PLAYBACK_TRACK_INIT_FAILED: AudioTrack failed to initialize for ${verified.canonicalMimeType}."
                stopAndFlush(reason, error = reason)
                onEvent(PlaybackCallbackEvent.PlaybackFailed(generationId, reason))
                return@withLock PcmChunkWriteOutcome.FailedWrite(generationId, reason)
            }

            // 5. Write loop: handle short/partial writes and zero writes without dropping any remaining bytes.
            // Whenever the hardware playback head is actively advancing, reset stalled zero-write counter
            // so long responses that stream faster than 1x real-time are never cut off by buffer backpressure.
            var offset = 0
            var remaining = alignedByteCount
            var partialWritesInChunk = 0
            var zeroWritesInChunk = 0
            var consecutiveStalledZeroWrites = 0
            var lastHeadDuringZeroWrite = driver.getPlaybackHeadFrames(generationId)

            while (remaining > 0) {
                val stillActive = synchronized(lock) { _state.value.activeGeneration == generationId }
                if (!stillActive) {
                    synchronized(lock) {
                        updateStateAndNotify {
                            it.copy(stalePlaybackCallbacksDropped = it.stalePlaybackCallbacksDropped + 1)
                        }
                    }
                    return@withLock PcmChunkWriteOutcome.DroppedStaleGeneration(
                        generationId = generationId,
                        activeGenerationId = _state.value.activeGeneration
                    )
                }

                val written = driver.writePcmBytes(
                    generationId = generationId,
                    pcmBytes = pcmBytes,
                    offset = offset,
                    length = remaining
                )

                if (written < 0) {
                    val reason = "HARDWARE_WRITE_ERROR: AudioTrack.write returned error code $written."
                    stopAndFlush(reason, error = reason)
                    onEvent(PlaybackCallbackEvent.PlaybackFailed(generationId, reason))
                    return@withLock PcmChunkWriteOutcome.FailedWrite(generationId, reason)
                }

                if (written == 0) {
                    zeroWritesInChunk += 1
                    val currentHead = driver.getPlaybackHeadFrames(generationId)
                    if (currentHead > lastHeadDuringZeroWrite) {
                        // Hardware is actively draining the buffer at 1x real-time; not stalled!
                        lastHeadDuringZeroWrite = currentHead
                        consecutiveStalledZeroWrites = 0
                    } else {
                        consecutiveStalledZeroWrites += 1
                    }
                    synchronized(lock) {
                        if (_state.value.activeGeneration == generationId) {
                            updateStateAndNotify { current ->
                                current.copy(
                                    hardwarePlaybackHeadFrames = currentHead,
                                    zeroWriteRetryCount = current.zeroWriteRetryCount + 1,
                                    statusDetail = "Hardware buffer full on ${generationId.wireId} (0 bytes written, head=$currentHead); retrying without dropping remaining $remaining bytes..."
                                )
                            }
                        }
                    }
                    if (consecutiveStalledZeroWrites > maxZeroWriteRetriesPerChunk) {
                        val reason =
                            "HARDWARE_WRITE_STALLED: AudioTrack returned 0 bytes written for $consecutiveStalledZeroWrites consecutive stalled retries."
                        stopAndFlush(reason, error = reason)
                        onEvent(PlaybackCallbackEvent.PlaybackFailed(generationId, reason))
                        return@withLock PcmChunkWriteOutcome.FailedWrite(generationId, reason)
                    }
                    writeRetryDelayRunner(8L)
                    continue
                }

                // written > 0
                consecutiveStalledZeroWrites = 0
                val wasPartial = written < remaining
                if (wasPartial) {
                    partialWritesInChunk += 1
                }
                offset += written
                remaining -= written

                val headFrames = driver.getPlaybackHeadFrames(generationId)
                lastHeadDuringZeroWrite = headFrames
                synchronized(lock) {
                    if (_state.value.activeGeneration == generationId) {
                        updateStateAndNotify { current ->
                            val newAccepted = current.bytesAcceptedByHardware + written
                            val newFramesWritten = newAccepted / bytesPerFrame.toLong()
                            val newPending = (current.audioBytesReceived - newAccepted).coerceAtLeast(0L)
                            val newPartials = current.partialWriteCount + (if (wasPartial) 1 else 0)
                            current.copy(
                                isTrackOpen = true,
                                isPlaying = true,
                                pendingQueuedBytes = newPending,
                                bytesAcceptedByHardware = newAccepted,
                                totalFramesWrittenToHardware = newFramesWritten,
                                hardwarePlaybackHeadFrames = headFrames,
                                partialWriteCount = newPartials,
                                statusDetail = "BYTES_ACCEPTED_BY_HARDWARE (${generationId.wireId}): $newAccepted/${current.audioBytesReceived} bytes accepted (framesWritten=$newFramesWritten, headFrames=$headFrames, partialWrites=$newPartials, zeroRetries=${current.zeroWriteRetryCount})."
                            )
                        }
                    }
                }
            }

            val snapshotAfterChunk = _state.value
            val acceptedEvent = PlaybackCallbackEvent.BytesAcceptedByHardware(
                generationId = generationId,
                chunkBytesAccepted = alignedByteCount,
                totalBytesAccepted = snapshotAfterChunk.bytesAcceptedByHardware,
                totalFramesWritten = snapshotAfterChunk.totalFramesWrittenToHardware,
                partialWriteCount = snapshotAfterChunk.partialWriteCount,
                zeroWriteRetryCount = snapshotAfterChunk.zeroWriteRetryCount
            )
            onPlaybackCallback(acceptedEvent)
            onEvent(acceptedEvent)

            PcmChunkWriteOutcome.WrittenToHardware(
                generationId = generationId,
                verifiedMimeType = verified.canonicalMimeType,
                sampleRateHz = verified.sampleRateHz,
                chunkBytesReceived = alignedByteCount,
                chunkBytesAccepted = alignedByteCount,
                totalBytesAcceptedByHardware = snapshotAfterChunk.bytesAcceptedByHardware,
                partialWritesInChunk = partialWritesInChunk,
                zeroWritesInChunk = zeroWritesInChunk
            )
        }
    }

    /**
     * Called when the Gemini Live server signals `turnComplete = true`.
     *
     * Enforces the Audible Completion Rule:
     * 1. First acquires [writeMutex] so all previously queued [verifyAndWritePcmChunk] calls for
     *    this turn finish writing 100% of their bytes to [AudioTrack] before checking drain state.
     * 2. Queue emptiness (`pendingQueuedBytes == 0`) and server completion (`serverTurnComplete == true`)
     *    alone are NOT audible completion!
     * 3. Signals [StreamingAudioTrackDriver.finishStreamForDrain] so `AudioTrack` in `MODE_STREAM`
     *    drains even short responses that are smaller than the stream buffer threshold.
     * 4. Polls [StreamingAudioTrackDriver.getPlaybackHeadFrames] until the hardware playback head
     *    reaches `totalFramesWrittenToHardware`.
     * 5. Never cuts long responses with a fixed short timeout: as long as `headFrames` is advancing,
     *    resets the stall counter and continues waiting until playback has completely drained.
     */
    suspend fun awaitHardwareDrainAfterServerTurnComplete(
        generationId: PlaybackGenerationId,
        onEvent: (PlaybackCallbackEvent) -> Unit = {}
    ): Boolean {
        synchronized(lock) {
            val current = _state.value
            if (current.activeGeneration == null || current.activeGeneration != generationId) {
                updateStateAndNotify {
                    it.copy(stalePlaybackCallbacksDropped = it.stalePlaybackCallbacksDropped + 1)
                }
                return false
            }
            updateStateAndNotify {
                it.copy(
                    serverTurnComplete = true,
                    statusDetail = "Server turnComplete received for ${generationId.wireId}; flushing any remaining queued PCM writes before hardware drain..."
                )
            }
        }

        // Wait for all in-flight/queued chunk writes for this generation to complete first!
        return writeMutex.withLock {
            val totalFramesTarget: Long
            var lastObservedHead: Long
            synchronized(lock) {
                val current = _state.value
                if (current.activeGeneration == null || current.activeGeneration != generationId) {
                    updateStateAndNotify {
                        it.copy(stalePlaybackCallbacksDropped = it.stalePlaybackCallbacksDropped + 1)
                    }
                    return@withLock false
                }
                if (current.chunksReceived > 0 && current.totalFramesWrittenToHardware <= 0L) {
                    val reason =
                        "ZERO_FRAMES_ACCEPTED_BY_AUDIOTRACK: Received ${current.chunksReceived} audio chunks (${current.framesReceivedFromServer} frames) from server, but 0 frames were accepted by AudioTrack."
                    stopAndFlush(reason, error = reason)
                    onEvent(PlaybackCallbackEvent.PlaybackFailed(generationId, reason))
                    return@withLock false
                }
                if (current.bytesAcceptedByHardware <= 0L || current.pendingQueuedBytes > 0L) {
                    val reason =
                        "TURN_INCOMPLETE_AUDIO: Server reported turnComplete but bytesAcceptedByHardware=${current.bytesAcceptedByHardware}, pendingQueuedBytes=${current.pendingQueuedBytes}."
                    stopAndFlush(reason, error = reason)
                    onEvent(PlaybackCallbackEvent.PlaybackFailed(generationId, reason))
                    return@withLock false
                }
                totalFramesTarget = current.totalFramesWrittenToHardware
            }

            // Signal end-of-stream to AudioTrack so short responses below buffer pre-roll drain to speaker
            driver.finishStreamForDrain(generationId)

            synchronized(lock) {
                val current = _state.value
                if (current.activeGeneration == null || current.activeGeneration != generationId) {
                    return@withLock false
                }
                lastObservedHead = driver.getPlaybackHeadFrames(generationId)
                updateStateAndNotify {
                    it.copy(
                        serverTurnComplete = true,
                        isDrainingHardware = true,
                        isPlaybackDrained = false,
                        hardwarePlaybackHeadFrames = lastObservedHead,
                        statusDetail = "Server turnComplete & all chunks written; waiting for hardware playback head ($lastObservedHead/$totalFramesTarget frames) to drain..."
                    )
                }
            }

            var stalledPolls = 0
            while (stalledPolls < maxDrainPollIterations) {
                val currentGen = synchronized(lock) { _state.value.activeGeneration }
                if (currentGen != generationId) {
                    return@withLock false
                }

                val headFrames = driver.getPlaybackHeadFrames(generationId)
                if (headFrames > lastObservedHead) {
                    lastObservedHead = headFrames
                    stalledPolls = 0
                } else {
                    stalledPolls += 1
                }

                synchronized(lock) {
                    if (_state.value.activeGeneration == generationId) {
                        updateStateAndNotify {
                            it.copy(
                                hardwarePlaybackHeadFrames = headFrames,
                                statusDetail = "Draining hardware playback buffer (${generationId.wireId}): head=$headFrames / target=$totalFramesTarget frames."
                            )
                        }
                    }
                }

                if (headFrames >= totalFramesTarget) {
                    val drainedEvent = PlaybackCallbackEvent.PlaybackDrained(
                        generationId = generationId,
                        totalBytesAccepted = _state.value.bytesAcceptedByHardware,
                        totalFramesWritten = totalFramesTarget,
                        hardwarePlaybackHeadFrames = headFrames
                    )
                    val accepted = onPlaybackCallback(drainedEvent)
                    if (accepted) {
                        onEvent(drainedEvent)
                    }
                    return@withLock accepted
                }

                drainPollDelayRunner(10L)
            }

            // Hardware head has not reached target frames yet: remain in isDrainingHardware = true,
            // isPlaybackDrained = false (honest: queue emptiness + server completion is NOT audible completion!)
            false
        }
    }

    /**
     * Re-checks whether the active draining generation has now reached `totalFramesWrittenToHardware`
     * on the hardware playback head, completing drain when true.
     */
    fun checkAndCompleteHardwareDrainIfReady(
        generationId: PlaybackGenerationId,
        onEvent: (PlaybackCallbackEvent) -> Unit = {}
    ): Boolean {
        val drainedEvent: PlaybackCallbackEvent.PlaybackDrained?
        synchronized(lock) {
            val current = _state.value
            if (current.activeGeneration == null || current.activeGeneration != generationId) {
                updateStateAndNotify {
                    it.copy(stalePlaybackCallbacksDropped = it.stalePlaybackCallbacksDropped + 1)
                }
                return false
            }
            val headFrames = driver.getPlaybackHeadFrames(generationId)
            val targetFrames = current.totalFramesWrittenToHardware
            if (!current.serverTurnComplete ||
                current.pendingQueuedBytes > 0L ||
                targetFrames <= 0L ||
                headFrames < targetFrames
            ) {
                updateStateAndNotify {
                    it.copy(
                        hardwarePlaybackHeadFrames = headFrames,
                        isPlaybackDrained = false
                    )
                }
                return false
            }
            drainedEvent = PlaybackCallbackEvent.PlaybackDrained(
                generationId = generationId,
                totalBytesAccepted = current.bytesAcceptedByHardware,
                totalFramesWritten = targetFrames,
                hardwarePlaybackHeadFrames = headFrames
            )
        }
        val accepted = onPlaybackCallback(drainedEvent!!)
        if (accepted) {
            onEvent(drainedEvent)
        }
        return accepted
    }

    /**
     * Processes a generation-tagged playback callback event.
     * Drops and counts late callbacks from previous or cancelled playback generations.
     */
    fun onPlaybackCallback(event: PlaybackCallbackEvent): Boolean {
        var shouldCloseDriverGen: PlaybackGenerationId? = null
        synchronized(lock) {
            val activeGen = _state.value.activeGeneration
            if (activeGen == null || activeGen != event.generationId) {
                updateStateAndNotify {
                    it.copy(stalePlaybackCallbacksDropped = it.stalePlaybackCallbacksDropped + 1)
                }
                return false
            }

            when (event) {
                is PlaybackCallbackEvent.AudioChunkReceived -> {
                    // Already recorded in verifyAndWritePcmChunk
                }

                is PlaybackCallbackEvent.BytesAcceptedByHardware -> {
                    updateStateAndNotify { current ->
                        current.copy(
                            bytesAcceptedByHardware = event.totalBytesAccepted,
                            totalFramesWrittenToHardware = event.totalFramesWritten,
                            partialWriteCount = event.partialWriteCount,
                            zeroWriteRetryCount = event.zeroWriteRetryCount
                        )
                    }
                }

                is PlaybackCallbackEvent.PlaybackDrained -> {
                    shouldCloseDriverGen = activeGen
                    updateStateAndNotify { current ->
                        current.copy(
                            activeGeneration = null,
                            lastCompletedGeneration = activeGen,
                            isTrackOpen = false,
                            isPlaying = false,
                            isDrainingHardware = false,
                            isPlaybackDrained = true,
                            hardwarePlaybackHeadFrames = event.hardwarePlaybackHeadFrames,
                            hardwareReleaseCount = current.hardwareReleaseCount + 1,
                            statusDetail = "PLAYBACK_DRAINED (${activeGen.wireId}): ${event.totalBytesAccepted} bytes (${event.hardwarePlaybackHeadFrames}/${event.totalFramesWritten} frames) audibly drained to speaker."
                        )
                    }
                }

                is PlaybackCallbackEvent.PlaybackFailed -> {
                    shouldCloseDriverGen = activeGen
                    updateStateAndNotify { current ->
                        current.copy(
                            activeGeneration = null,
                            lastCompletedGeneration = activeGen,
                            isTrackOpen = false,
                            isPlaying = false,
                            isDrainingHardware = false,
                            isPlaybackDrained = false,
                            lastError = event.reason,
                            hardwareReleaseCount = current.hardwareReleaseCount + 1,
                            statusDetail = "PLAYBACK_FAILED (${activeGen.wireId}): ${event.reason}"
                        )
                    }
                }
            }
        }
        if (shouldCloseDriverGen != null) {
            driver.stopAndFlushTrack(shouldCloseDriverGen)
        }
        return true
    }

    /**
     * Immediately stops, flushes, and releases the active hardware [AudioTrack] and invalidates
     * the active [PlaybackGenerationId] so any in-flight or late callbacks are dropped.
     */
    fun stopAndFlush(reason: String, error: String? = null) {
        val prevGen: PlaybackGenerationId?
        synchronized(lock) {
            prevGen = _state.value.activeGeneration
            val wasActive = prevGen != null || _state.value.isPlaying || _state.value.isDrainingHardware || _state.value.isTrackOpen
            updateStateAndNotify { current ->
                current.copy(
                    activeGeneration = null,
                    lastCompletedGeneration = prevGen ?: current.lastCompletedGeneration,
                    isTrackOpen = false,
                    isPlaying = false,
                    isDrainingHardware = false,
                    isPlaybackDrained = false,
                    pendingQueuedBytes = 0L,
                    lastError = error ?: current.lastError,
                    hardwareReleaseCount = if (wasActive) current.hardwareReleaseCount + 1 else current.hardwareReleaseCount,
                    statusDetail = "Playback stopped & flushed (${prevGen?.wireId ?: "none"}): $reason"
                )
            }
        }
        driver.stopAndFlushTrack(prevGen)
    }
}
