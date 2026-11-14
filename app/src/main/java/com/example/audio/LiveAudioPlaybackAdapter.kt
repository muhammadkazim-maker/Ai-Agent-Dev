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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Hardware streaming output abstraction for [LiveAudioPlaybackAdapter].
 * Allows production Android [AudioTrack] and deterministic unit/Robolectric tests to share
 * the exact same MIME verification, short/partial/zero/negative write loop, hardware playback
 * head drain verification (with 32-bit wraparound handling), single track recreation on write
 * error, and generation-bound callback filtering.
 */
interface StreamingAudioTrackDriver {
    fun openStreamingTrack(
        generationId: PlaybackGenerationId,
        sampleRateHz: Int,
        channelCount: Int = 1
    ): Boolean

    fun recreateStreamingTrack(
        generationId: PlaybackGenerationId,
        sampleRateHz: Int,
        channelCount: Int = 1
    ): Boolean {
        stopAndFlushTrack(generationId)
        return openStreamingTrack(generationId, sampleRateHz, channelCount)
    }

    fun writePcmBytes(
        generationId: PlaybackGenerationId,
        pcmBytes: ByteArray,
        offset: Int,
        length: Int
    ): Int

    fun finishStreamForDrain(generationId: PlaybackGenerationId) {}
    fun getPlaybackHeadFrames(generationId: PlaybackGenerationId): Long
    fun stopAndFlushTrack(generationId: PlaybackGenerationId?)
    fun getTrackDiagnostics(generationId: PlaybackGenerationId): AudioTrackDiagnostics = AudioTrackDiagnostics()
    fun isTrackActivelyPlaying(generationId: PlaybackGenerationId): Boolean = true
}

/**
 * Production Android implementation of [StreamingAudioTrackDriver] using [AudioTrack]
 * in [AudioTrack.MODE_STREAM] for 16-bit little-endian PCM output.
 *
 * Enforces:
 * - Single active [AudioTrack] owner per turn ([PlaybackGenerationId]).
 * - Never reuses a stopped or released [AudioTrack] or closed [PlaybackGenerationId].
 * - Verifies [AudioTrack.STATE_INITIALIZED] and [AudioTrack.PLAYSTATE_PLAYING] before writing.
 * - Never calls `pause()`, `stop()`, `flush()`, or `release()` in [finishStreamForDrain] before
 *   accepted audio has played (preventing `playbackHeadPosition` reset to `0`).
 * - Handles 32-bit unsigned `playbackHeadPosition` wraparound cleanly.
 */
class AndroidStreamingAudioTrackDriver : StreamingAudioTrackDriver {
    private val lock = Any()
    private val trackInstanceSeq = AtomicLong(0L)

    private var activeTrack: AudioTrack? = null
    private var activeGenerationId: PlaybackGenerationId? = null
    private var activeSampleRateHz: Int = 0
    private var activeChannelCount: Int = 1
    private var activeBufferSizeInFrames: Int = 0
    private var activeTrackInstanceId: Long = 0L
    private var writtenFramesInTrack: Long = 0L

    private val closedGenerations = LinkedHashSet<PlaybackGenerationId>()
    private val releasedTrackInstanceIds = LinkedHashSet<Long>()

    // 32-bit unsigned wraparound tracking for AudioTrack.playbackHeadPosition
    private var lastRawHead32: Long = 0L
    private var wraparoundOffset: Long = 0L
    private var lastMonotonicHead: Long = 0L

    // Real hardware diagnostics for honest error reporting
    private var lastTrackState: Int = AudioTrack.STATE_UNINITIALIZED
    private var lastPlayState: Int = AudioTrack.PLAYSTATE_STOPPED
    private var lastWriteCode: Int = 0
    private var lastDiagnostic: String = "NONE"

    override fun openStreamingTrack(
        generationId: PlaybackGenerationId,
        sampleRateHz: Int,
        channelCount: Int
    ): Boolean {
        return openTrackInternal(
            generationId = generationId,
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
            allowSameGenerationKeepAlive = true
        )
    }

    override fun recreateStreamingTrack(
        generationId: PlaybackGenerationId,
        sampleRateHz: Int,
        channelCount: Int
    ): Boolean {
        synchronized(lock) {
            if (closedGenerations.contains(generationId)) {
                lastDiagnostic = "Cannot recreate track for closed generation ${generationId.wireId}"
                return false
            }
            // Release ONLY the old track instance without marking generationId closed so one clean
            // replacement AudioTrack can be created for this same turn.
            releaseInternalLocked(markGenerationClosed = false)
        }
        return openTrackInternal(
            generationId = generationId,
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
            allowSameGenerationKeepAlive = false
        )
    }

    private fun openTrackInternal(
        generationId: PlaybackGenerationId,
        sampleRateHz: Int,
        channelCount: Int,
        allowSameGenerationKeepAlive: Boolean
    ): Boolean {
        synchronized(lock) {
            if (closedGenerations.contains(generationId)) {
                lastDiagnostic = "Refused to reuse closed playback generation ${generationId.wireId}"
                return false
            }

            val existing = activeTrack
            if (allowSameGenerationKeepAlive &&
                existing != null &&
                activeGenerationId == generationId &&
                activeTrackInstanceId !in releasedTrackInstanceIds &&
                activeSampleRateHz == sampleRateHz &&
                activeChannelCount == channelCount &&
                existing.state == AudioTrack.STATE_INITIALIZED &&
                existing.playState == AudioTrack.PLAYSTATE_PLAYING
            ) {
                lastTrackState = existing.state
                lastPlayState = existing.playState
                return true
            }

            // Never reuse a stopped/released AudioTrack or track from an older turn
            releaseInternalLocked(markGenerationClosed = activeGenerationId != generationId)

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
                lastTrackState = AudioTrack.STATE_UNINITIALIZED
                lastPlayState = AudioTrack.PLAYSTATE_STOPPED
                lastWriteCode = minBufBytes
                lastDiagnostic = "AudioTrack.getMinBufferSize failed (${AudioTrackErrorMapper.mapCode(minBufBytes)}) for ${sampleRateHz}Hz ch=$channelCount"
                return false
            }

            // Size streaming buffer cleanly (~200ms or 2x minBufBytes) from the actual negotiated format
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

                lastTrackState = track.state
                lastPlayState = track.playState
                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    lastDiagnostic = "AudioTrack failed STATE_INITIALIZED check (state=${track.state}) for ${sampleRateHz}Hz ch=$channelCount"
                    try {
                        track.release()
                    } catch (_: Throwable) {
                    }
                    return false
                }

                track.play()
                lastTrackState = track.state
                lastPlayState = track.playState
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    lastDiagnostic = "AudioTrack.play() did not reach PLAYSTATE_PLAYING (state=${track.state}, playState=${track.playState})"
                    try {
                        track.stop()
                    } catch (_: Throwable) {
                    }
                    try {
                        track.release()
                    } catch (_: Throwable) {
                    }
                    return false
                }

                val newInstanceId = trackInstanceSeq.incrementAndGet()
                activeTrack = track
                activeGenerationId = generationId
                activeSampleRateHz = sampleRateHz
                activeChannelCount = channelCount
                activeBufferSizeInFrames = bufferSizeInBytes / bytesPerFrame
                activeTrackInstanceId = newInstanceId
                writtenFramesInTrack = 0L
                lastRawHead32 = 0L
                wraparoundOffset = 0L
                lastMonotonicHead = 0L
                lastWriteCode = 0
                lastDiagnostic = "Track #$newInstanceId initialized & PLAYING (${sampleRateHz}Hz ch=$channelCount bufFrames=$activeBufferSizeInFrames)"
                true
            } catch (t: Throwable) {
                lastDiagnostic = "AudioTrack construction/play exception: ${t::class.java.simpleName}: ${t.message}"
                releaseInternalLocked(markGenerationClosed = false)
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
        val track: AudioTrack
        val channelCount: Int
        synchronized(lock) {
            if (activeGenerationId != generationId ||
                activeTrackInstanceId in releasedTrackInstanceIds
            ) {
                lastWriteCode = AudioTrack.ERROR_INVALID_OPERATION
                lastDiagnostic = "Stale or released track write rejected for ${generationId.wireId}"
                return AudioTrack.ERROR_INVALID_OPERATION
            }
            val current = activeTrack
            if (current == null ||
                current.state != AudioTrack.STATE_INITIALIZED ||
                current.playState != AudioTrack.PLAYSTATE_PLAYING
            ) {
                lastTrackState = current?.state ?: AudioTrack.STATE_UNINITIALIZED
                lastPlayState = current?.playState ?: AudioTrack.PLAYSTATE_STOPPED
                lastWriteCode = AudioTrack.ERROR_INVALID_OPERATION
                lastDiagnostic = "Track not in STATE_INITIALIZED+PLAYSTATE_PLAYING (state=$lastTrackState, playState=$lastPlayState)"
                return AudioTrack.ERROR_INVALID_OPERATION
            }
            track = current
            channelCount = activeChannelCount.coerceAtLeast(1)
        }

        return try {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                track.write(pcmBytes, offset, length, AudioTrack.WRITE_NON_BLOCKING)
            } else {
                track.write(pcmBytes, offset, length)
            }
            synchronized(lock) {
                lastWriteCode = result
                lastTrackState = track.state
                lastPlayState = track.playState
                if (result > 0 && activeGenerationId == generationId) {
                    val bytesPerFrame = 2 * channelCount
                    writtenFramesInTrack += (result / bytesPerFrame).toLong()
                } else if (result < 0) {
                    lastDiagnostic = "AudioTrack.write returned ${AudioTrackErrorMapper.mapCode(result)} (state=$lastTrackState, playState=$lastPlayState)"
                }
            }
            result
        } catch (t: Throwable) {
            synchronized(lock) {
                lastWriteCode = AudioTrack.ERROR_DEAD_OBJECT
                lastDiagnostic = "AudioTrack.write threw ${t::class.java.simpleName}: ${t.message}"
            }
            AudioTrack.ERROR_DEAD_OBJECT
        }
    }

    override fun finishStreamForDrain(generationId: PlaybackGenerationId) {
        // CRITICAL: Never call pause(), stop(), flush(), or release() before accepted audio has
        // played! On Android AudioTrack (MODE_STREAM), calling stop() transitions playState to
        // PLAYSTATE_STOPPED and resets getPlaybackHeadPosition() back to 0.
        // Instead, leave the track in PLAYSTATE_PLAYING while hardware drains, and only pad
        // silent zero bytes if total written frames is smaller than the stream buffer capacity so
        // short sub-buffer clips still begin hardware playback immediately without inflating
        // totalFramesWrittenToHardware.
        val trackToPad: AudioTrack?
        val silenceBytesCount: Int
        synchronized(lock) {
            if (activeGenerationId != generationId) return
            val track = activeTrack ?: return
            if (track.state != AudioTrack.STATE_INITIALIZED || track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                return
            }
            val bytesPerFrame = 2 * activeChannelCount.coerceAtLeast(1)
            if (writtenFramesInTrack in 1L until activeBufferSizeInFrames.toLong()) {
                val deficitFrames = (activeBufferSizeInFrames.toLong() - writtenFramesInTrack).toInt()
                trackToPad = track
                silenceBytesCount = deficitFrames * bytesPerFrame
            } else {
                trackToPad = null
                silenceBytesCount = 0
            }
        }
        if (trackToPad != null && silenceBytesCount > 0) {
            try {
                val silence = ByteArray(silenceBytesCount)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    trackToPad.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING)
                } else {
                    trackToPad.write(silence, 0, silence.size)
                }
            } catch (_: Throwable) {
            }
        }
    }

    override fun getPlaybackHeadFrames(generationId: PlaybackGenerationId): Long {
        val track = synchronized(lock) {
            if (activeGenerationId != generationId || activeTrackInstanceId in releasedTrackInstanceIds) {
                return lastMonotonicHead
            }
            activeTrack
        } ?: return 0L

        return try {
            val raw32 = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            synchronized(lock) {
                if (activeGenerationId != generationId) return lastMonotonicHead
                if (raw32 < lastRawHead32 && (lastRawHead32 - raw32) > 0x8000_0000L) {
                    wraparoundOffset += 0x1_0000_0000L
                }
                lastRawHead32 = raw32
                val cumulative = wraparoundOffset + raw32
                lastMonotonicHead = max(lastMonotonicHead, cumulative)
                lastTrackState = track.state
                lastPlayState = track.playState
                lastMonotonicHead
            }
        } catch (_: Throwable) {
            synchronized(lock) { lastMonotonicHead }
        }
    }

    override fun isTrackActivelyPlaying(generationId: PlaybackGenerationId): Boolean {
        synchronized(lock) {
            if (activeGenerationId != generationId) return false
            val track = activeTrack ?: return false
            return track.state == AudioTrack.STATE_INITIALIZED &&
                track.playState == AudioTrack.PLAYSTATE_PLAYING
        }
    }

    override fun getTrackDiagnostics(generationId: PlaybackGenerationId): AudioTrackDiagnostics {
        synchronized(lock) {
            val track = if (activeGenerationId == generationId) activeTrack else null
            return AudioTrackDiagnostics(
                trackState = track?.state ?: lastTrackState,
                playState = track?.playState ?: lastPlayState,
                sampleRateHz = activeSampleRateHz,
                channelCount = activeChannelCount,
                bufferSizeInFrames = activeBufferSizeInFrames,
                trackInstanceId = activeTrackInstanceId,
                lastWriteCode = lastWriteCode,
                diagnostic = lastDiagnostic
            )
        }
    }

    override fun stopAndFlushTrack(generationId: PlaybackGenerationId?) {
        synchronized(lock) {
            if (generationId != null && activeGenerationId != null && activeGenerationId != generationId) {
                closedGenerations.add(generationId)
                trimClosedGenerationsLocked()
                return
            }
            if (generationId != null) {
                closedGenerations.add(generationId)
                trimClosedGenerationsLocked()
            }
            releaseInternalLocked(markGenerationClosed = true)
        }
    }

    private fun trimClosedGenerationsLocked() {
        while (closedGenerations.size > 128) {
            val first = closedGenerations.iterator().next()
            closedGenerations.remove(first)
        }
        while (releasedTrackInstanceIds.size > 128) {
            val first = releasedTrackInstanceIds.iterator().next()
            releasedTrackInstanceIds.remove(first)
        }
    }

    private fun releaseInternalLocked(markGenerationClosed: Boolean) {
        val track = activeTrack
        val prevGen = activeGenerationId
        val prevInstanceId = activeTrackInstanceId
        if (markGenerationClosed && prevGen != null) {
            closedGenerations.add(prevGen)
        }
        if (prevInstanceId != 0L) {
            releasedTrackInstanceIds.add(prevInstanceId)
        }
        trimClosedGenerationsLocked()

        activeTrack = null
        activeGenerationId = null
        activeSampleRateHz = 0
        activeChannelCount = 1
        activeBufferSizeInFrames = 0
        activeTrackInstanceId = 0L
        writtenFramesInTrack = 0L

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
 * - Zero writes (`0`), short/partial writes (`1..length-1`), and negative AudioTrack error codes (`< 0`).
 * - Track initialization failure (`openTrackSucceeds = false`) and PLAYSTATE_PLAYING failure (`playSucceeds = false`).
 * - Controlled hardware playback head progression (`autoAdvancePlaybackHead = false`) and 32-bit
 *   unsigned wraparound simulation.
 * - Strict enforcement that a released AudioTrack instance or closed generation is never reused.
 */
class DeterministicStreamingAudioTrackDriver(
    var openTrackSucceeds: Boolean = true,
    var playSucceeds: Boolean = true,
    var autoAdvancePlaybackHead: Boolean = true
) : StreamingAudioTrackDriver {
    private val scriptedWriteSizes = ArrayDeque<Int>()
    private val acceptedBytesStream = java.io.ByteArrayOutputStream()
    private val trackInstanceSeq = AtomicLong(0L)

    var activeGenerationId: PlaybackGenerationId? = null
        private set
    var activeTrackInstanceId: Long = 0L
        private set
    var openedSampleRateHz: Int = 0
        private set
    var openedChannelCount: Int = 1
        private set
    var totalBytesAccepted: Long = 0L
        private set
    var manualPlaybackHeadFrames: Long = 0L
    var simulatedRawHead32: Long? = null
    private var lastRawHead32: Long = 0L
    private var wraparoundOffset: Long = 0L
    private var lastMonotonicHead: Long = 0L

    var trackCreationCount: Int = 0
        private set
    var trackRecreationCount: Int = 0
        private set
    var stopAndFlushCallCount: Int = 0
        private set
    var finishStreamForDrainCallCount: Int = 0
        private set
    var writeInvocationCount: Int = 0
        private set
    var lastWriteCode: Int = 0
        private set
    var stopCalledBeforeDrainCount: Int = 0
        private set
    var reusedReleasedTrackViolationCount: Int = 0
        private set

    val createdTrackInstanceIds = mutableListOf<Long>()
    val trackInstanceIdsByGeneration = mutableMapOf<PlaybackGenerationId, MutableList<Long>>()
    val releasedTrackInstanceIds = mutableSetOf<Long>()
    val closedGenerations = mutableSetOf<PlaybackGenerationId>()

    fun enqueueScriptedWriteSizes(vararg sizes: Int) {
        for (size in sizes) {
            scriptedWriteSizes.addLast(size)
        }
    }

    fun getAcceptedPcmBytes(): ByteArray = acceptedBytesStream.toByteArray()

    fun setRawPlaybackHead32ForWraparoundTest(raw32: Long) {
        simulatedRawHead32 = raw32 and 0xFFFFFFFFL
    }

    fun resetRecordedBytes() {
        scriptedWriteSizes.clear()
        acceptedBytesStream.reset()
        totalBytesAccepted = 0L
        manualPlaybackHeadFrames = 0L
        simulatedRawHead32 = null
        lastRawHead32 = 0L
        wraparoundOffset = 0L
        lastMonotonicHead = 0L
        writeInvocationCount = 0
        finishStreamForDrainCallCount = 0
    }

    override fun openStreamingTrack(
        generationId: PlaybackGenerationId,
        sampleRateHz: Int,
        channelCount: Int
    ): Boolean {
        return openInternal(
            generationId = generationId,
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
            allowSameGenerationKeepAlive = true
        )
    }

    override fun recreateStreamingTrack(
        generationId: PlaybackGenerationId,
        sampleRateHz: Int,
        channelCount: Int
    ): Boolean {
        if (closedGenerations.contains(generationId)) {
            reusedReleasedTrackViolationCount += 1
            return false
        }
        val oldInstance = activeTrackInstanceId
        if (oldInstance != 0L) {
            releasedTrackInstanceIds.add(oldInstance)
        }
        activeTrackInstanceId = 0L
        trackRecreationCount += 1
        return openInternal(
            generationId = generationId,
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
            allowSameGenerationKeepAlive = false
        )
    }

    private fun openInternal(
        generationId: PlaybackGenerationId,
        sampleRateHz: Int,
        channelCount: Int,
        allowSameGenerationKeepAlive: Boolean
    ): Boolean {
        if (closedGenerations.contains(generationId)) {
            reusedReleasedTrackViolationCount += 1
            return false
        }
        if (allowSameGenerationKeepAlive &&
            activeGenerationId == generationId &&
            activeTrackInstanceId != 0L &&
            activeTrackInstanceId !in releasedTrackInstanceIds
        ) {
            return true
        }
        if (activeTrackInstanceId != 0L) {
            releasedTrackInstanceIds.add(activeTrackInstanceId)
        }
        if (!openTrackSucceeds || !playSucceeds) {
            activeTrackInstanceId = 0L
            return false
        }
        if (activeGenerationId != generationId) {
            acceptedBytesStream.reset()
            totalBytesAccepted = 0L
            manualPlaybackHeadFrames = 0L
            simulatedRawHead32 = null
            lastRawHead32 = 0L
            wraparoundOffset = 0L
            lastMonotonicHead = 0L
            finishStreamForDrainCallCount = 0
        }
        val newInstanceId = trackInstanceSeq.incrementAndGet()
        activeGenerationId = generationId
        activeTrackInstanceId = newInstanceId
        openedSampleRateHz = sampleRateHz
        openedChannelCount = channelCount
        trackCreationCount += 1
        createdTrackInstanceIds.add(newInstanceId)
        trackInstanceIdsByGeneration.getOrPut(generationId) { mutableListOf() }.add(newInstanceId)
        return true
    }

    override fun writePcmBytes(
        generationId: PlaybackGenerationId,
        pcmBytes: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        if (activeGenerationId != generationId ||
            activeTrackInstanceId == 0L ||
            activeTrackInstanceId in releasedTrackInstanceIds
        ) {
            lastWriteCode = AudioTrack.ERROR_INVALID_OPERATION
            return AudioTrack.ERROR_INVALID_OPERATION
        }
        writeInvocationCount += 1

        val scripted = if (scriptedWriteSizes.isNotEmpty()) {
            scriptedWriteSizes.removeFirst()
        } else {
            length
        }

        lastWriteCode = scripted
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
        val raw = simulatedRawHead32
        if (raw != null) {
            if (raw < lastRawHead32 && (lastRawHead32 - raw) > 0x8000_0000L) {
                wraparoundOffset += 0x1_0000_0000L
            }
            lastRawHead32 = raw
            val cumulative = wraparoundOffset + raw
            lastMonotonicHead = max(lastMonotonicHead, cumulative)
            return lastMonotonicHead
        }
        val bytesPerFrame = (2 * openedChannelCount.coerceAtLeast(1)).toLong()
        return if (autoAdvancePlaybackHead) {
            totalBytesAccepted / bytesPerFrame
        } else {
            manualPlaybackHeadFrames
        }
    }

    override fun isTrackActivelyPlaying(generationId: PlaybackGenerationId): Boolean {
        return activeGenerationId == generationId &&
            activeTrackInstanceId != 0L &&
            activeTrackInstanceId !in releasedTrackInstanceIds &&
            openTrackSucceeds &&
            playSucceeds
    }

    override fun getTrackDiagnostics(generationId: PlaybackGenerationId): AudioTrackDiagnostics {
        val stateInt = if (!openTrackSucceeds) AudioTrack.STATE_UNINITIALIZED else AudioTrack.STATE_INITIALIZED
        val playInt = if (!playSucceeds || activeTrackInstanceId == 0L) AudioTrack.PLAYSTATE_STOPPED else AudioTrack.PLAYSTATE_PLAYING
        val diagText = when {
            !openTrackSucceeds -> "Deterministic test driver: openTrackSucceeds=false (STATE_UNINITIALIZED)"
            !playSucceeds -> "Deterministic test driver: playSucceeds=false (PLAYSTATE_STOPPED)"
            else -> "Deterministic track #$activeTrackInstanceId active"
        }
        return AudioTrackDiagnostics(
            trackState = stateInt,
            playState = playInt,
            sampleRateHz = openedSampleRateHz,
            channelCount = openedChannelCount,
            bufferSizeInFrames = (openedSampleRateHz / 5).coerceAtLeast(1024),
            trackInstanceId = activeTrackInstanceId,
            lastWriteCode = lastWriteCode,
            diagnostic = diagText
        )
    }

    override fun stopAndFlushTrack(generationId: PlaybackGenerationId?) {
        if (generationId != null && activeGenerationId != null && activeGenerationId != generationId) {
            closedGenerations.add(generationId)
            return
        }
        if (activeTrackInstanceId != 0L) {
            val bytesPerFrame = (2 * openedChannelCount.coerceAtLeast(1)).toLong()
            val totalFrames = totalBytesAccepted / bytesPerFrame
            val headNow = if (activeGenerationId != null) getPlaybackHeadFrames(activeGenerationId!!) else 0L
            if (totalFrames > 0L && headNow < totalFrames && finishStreamForDrainCallCount > 0) {
                stopCalledBeforeDrainCount += 1
            }
            stopAndFlushCallCount += 1
            releasedTrackInstanceIds.add(activeTrackInstanceId)
        }
        if (activeGenerationId != null) {
            closedGenerations.add(activeGenerationId!!)
        }
        if (generationId != null) {
            closedGenerations.add(generationId)
        }
        activeGenerationId = null
        activeTrackInstanceId = 0L
    }
}

/**
 * Single streaming PCM audio playback adapter for OmniVoice Rebuild (Phase 04/05).
 *
 * Enforces:
 * 1. Exact negotiated output format verification via [PcmMimeVerifier] (never guesses).
 * 2. All AudioTrack creation, `write()`, playback-head polling, and drain waiting run on
 *    [audioDispatcher] (never on the Android main thread).
 * 3. Distinct metrics for Gemini-received frames (`framesReceivedFromServer`) vs AudioTrack-accepted
 *    frames (`totalFramesWrittenToHardware`) vs played frames (`hardwarePlaybackHeadFrames`).
 * 4. Negative `AudioTrack.write` error inspection with real error-code mapping and at most ONE
 *    clean AudioTrack recreation per turn.
 * 5. Never waits for drain when `acceptedFrames == 0`; immediately fails with
 *    `AUDIO_TRACK_WRITE_FAILED` and real initialization/play/write evidence.
 * 6. Never calls `pause`, `stop`, `flush`, or `release` before accepted audio has played.
 * 7. Dynamic bounded drain deadline calculated from `(acceptedFrames / sampleRateHz)` + margin.
 * 8. Every event and `stopAndFlush` is strictly bound to [PlaybackGenerationId]; stale turn
 *    callbacks are dropped as `STALE_CALLBACK_DROPPED` and never mutate a newer turn.
 */
class LiveAudioPlaybackAdapter(
    val driver: StreamingAudioTrackDriver = AndroidStreamingAudioTrackDriver(),
    private val maxZeroWriteRetriesPerChunk: Int = 600,
    private val maxDrainPollIterations: Int = 600,
    private val writeRetryDelayRunner: suspend (Long) -> Unit = { ms -> delay(ms) },
    private val drainPollDelayRunner: suspend (Long) -> Unit = { ms -> delay(ms) },
    private val audioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private val writeMutex = Mutex()
    private val generationSeq = AtomicLong(0L)

    @Volatile
    private var recreatedGeneration: PlaybackGenerationId? = null

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
            recreatedGeneration = null
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
                    lastWriteResultCode = 0,
                    lastWriteErrorName = "NONE",
                    activeTrackInstanceId = 0L,
                    drainDeadlineMs = 0L,
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
     * and serializes writing all PCM bytes to the hardware track on [audioDispatcher] in FIFO order.
     */
    suspend fun verifyAndWritePcmChunk(
        generationId: PlaybackGenerationId,
        rawMimeType: String?,
        pcmBytes: ByteArray,
        onEvent: (PlaybackCallbackEvent) -> Unit = {}
    ): PcmChunkWriteOutcome = withContext(audioDispatcher) {
        // 1. Check generation validity before processing
        synchronized(lock) {
            val currentGen = _state.value.activeGeneration
            if (currentGen == null || currentGen != generationId) {
                updateStateAndNotify {
                    it.copy(
                        stalePlaybackCallbacksDropped = it.stalePlaybackCallbacksDropped + 1,
                        statusDetail = "STALE_CALLBACK_DROPPED: Dropped chunk for ${generationId.wireId} (active=${currentGen?.wireId ?: "none"})."
                    )
                }
                return@withContext PcmChunkWriteOutcome.DroppedStaleGeneration(
                    generationId = generationId,
                    activeGenerationId = currentGen
                )
            }
        }

        // 2. Verify MIME type, sample rate, and channel count (never guess)
        val mimeVerification = PcmMimeVerifier.verifyAndParseSampleRate(rawMimeType)
        if (mimeVerification is PcmMimeVerificationResult.Rejected) {
            stopAndFlush(
                reason = "Rejected unverified/unsupported MIME (${mimeVerification.reason})",
                error = mimeVerification.reason,
                expectedGenerationId = generationId
            )
            onEvent(
                PlaybackCallbackEvent.PlaybackFailed(
                    generationId = generationId,
                    reason = mimeVerification.reason,
                    errorCode = "UNSUPPORTED_AUDIO_MIME"
                )
            )
            return@withContext PcmChunkWriteOutcome.RejectedMime(
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
            val reason = "AUDIO_TRACK_WRITE_FAILED: Audio chunk has 0 aligned 16-bit PCM bytes (size=${pcmBytes.size})."
            stopAndFlush(reason, error = reason, expectedGenerationId = generationId)
            onEvent(PlaybackCallbackEvent.PlaybackFailed(generationId, reason, "AUDIO_TRACK_WRITE_FAILED"))
            return@withContext PcmChunkWriteOutcome.FailedWrite(generationId, reason)
        }
        val chunkFrames = alignedByteCount.toLong() / bytesPerFrame.toLong()

        // 3. Record AUDIO_RECEIVED separately from AudioTrack-accepted frames
        val totalReceivedAfterChunk: Long
        synchronized(lock) {
            val currentGen = _state.value.activeGeneration
            if (currentGen != generationId) {
                updateStateAndNotify {
                    it.copy(stalePlaybackCallbacksDropped = it.stalePlaybackCallbacksDropped + 1)
                }
                return@withContext PcmChunkWriteOutcome.DroppedStaleGeneration(generationId, currentGen)
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

        // 4. Serialize hardware AudioTrack writes in FIFO order via writeMutex on audioDispatcher
        writeMutex.withLock {
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
            val openDiag = driver.getTrackDiagnostics(generationId)
            if (!trackOpened || !driver.isTrackActivelyPlaying(generationId)) {
                val reason =
                    "PLAYBACK_TRACK_INIT_FAILED: AudioTrack init/play failed for ${verified.canonicalMimeType} (state=${openDiag.trackState}, playState=${openDiag.playState}, detail=${openDiag.diagnostic})."
                synchronized(lock) {
                    if (_state.value.activeGeneration == generationId) {
                        updateStateAndNotify {
                            it.copy(
                                trackState = openDiag.trackState,
                                playState = openDiag.playState,
                                lastWriteResultCode = openDiag.lastWriteCode,
                                lastWriteErrorName = AudioTrackErrorMapper.mapCode(openDiag.lastWriteCode)
                            )
                        }
                    }
                }
                stopAndFlush(reason, error = reason, expectedGenerationId = generationId)
                onEvent(PlaybackCallbackEvent.PlaybackFailed(generationId, reason, "AUDIO_PLAYBACK_WRITE_FAILED"))
                return@withLock PcmChunkWriteOutcome.FailedWrite(generationId, reason)
            }

            synchronized(lock) {
                if (_state.value.activeGeneration == generationId) {
                    updateStateAndNotify { current ->
                        val isNewInstance =
                            openDiag.trackInstanceId != 0L && current.activeTrackInstanceId != openDiag.trackInstanceId
                        current.copy(
                            isTrackOpen = true,
                            isPlaying = true,
                            trackState = openDiag.trackState,
                            playState = openDiag.playState,
                            activeTrackInstanceId = openDiag.trackInstanceId,
                            trackCreationCount = if (isNewInstance) current.trackCreationCount + 1 else current.trackCreationCount
                        )
                    }
                }
            }

            // 5. Write loop: handle short/partial writes, zero writes, and negative write errors
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
                    val mappedErr = AudioTrackErrorMapper.mapCode(written)
                    val errDiag = driver.getTrackDiagnostics(generationId)
                    val canRecreateOnce = synchronized(lock) {
                        _state.value.activeGeneration == generationId && recreatedGeneration != generationId
                    }
                    if (canRecreateOnce) {
                        recreatedGeneration = generationId
                        val recreated = driver.recreateStreamingTrack(
                            generationId = generationId,
                            sampleRateHz = verified.sampleRateHz,
                            channelCount = verified.channelCount
                        )
                        val newDiag = driver.getTrackDiagnostics(generationId)
                        synchronized(lock) {
                            if (_state.value.activeGeneration == generationId) {
                                updateStateAndNotify { current ->
                                    current.copy(
                                        trackRecreationCount = current.trackRecreationCount + 1,
                                        trackCreationCount = if (recreated) current.trackCreationCount + 1 else current.trackCreationCount,
                                        activeTrackInstanceId = newDiag.trackInstanceId,
                                        trackState = newDiag.trackState,
                                        playState = newDiag.playState,
                                        lastWriteResultCode = written,
                                        lastWriteErrorName = mappedErr,
                                        statusDetail = "Recreated AudioTrack once after $mappedErr on ${generationId.wireId} (recreated=$recreated)."
                                    )
                                }
                            }
                        }
                        if (recreated && driver.isTrackActivelyPlaying(generationId)) {
                            continue
                        }
                    }

                    val finalDiag = driver.getTrackDiagnostics(generationId)
                    val reason =
                        "AUDIO_TRACK_WRITE_FAILED: AudioTrack.write returned $mappedErr on ${generationId.wireId} (state=${finalDiag.trackState}, playState=${finalDiag.playState}, recreated=${recreatedGeneration == generationId}, detail=${errDiag.diagnostic})."
                    synchronized(lock) {
                        if (_state.value.activeGeneration == generationId) {
                            updateStateAndNotify { current ->
                                current.copy(
                                    trackState = finalDiag.trackState,
                                    playState = finalDiag.playState,
                                    lastWriteResultCode = written,
                                    lastWriteErrorName = mappedErr
                                )
                            }
                        }
                    }
                    stopAndFlush(reason, error = reason, expectedGenerationId = generationId)
                    onEvent(PlaybackCallbackEvent.PlaybackFailed(generationId, reason, "AUDIO_TRACK_WRITE_FAILED"))
                    return@withLock PcmChunkWriteOutcome.FailedWrite(generationId, reason)
                }

                if (written == 0) {
                    zeroWritesInChunk += 1
                    val currentHead = driver.getPlaybackHeadFrames(generationId)
                    if (currentHead > lastHeadDuringZeroWrite) {
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
                        val stallDiag = driver.getTrackDiagnostics(generationId)
                        val reason =
                            "AUDIO_TRACK_WRITE_FAILED: AudioTrack.write stalled at 0 bytes for $consecutiveStalledZeroWrites consecutive retries (state=${stallDiag.trackState}, playState=${stallDiag.playState})."
                        stopAndFlush(reason, error = reason, expectedGenerationId = generationId)
                        onEvent(PlaybackCallbackEvent.PlaybackFailed(generationId, reason, "AUDIO_TRACK_WRITE_FAILED"))
                        return@withLock PcmChunkWriteOutcome.FailedWrite(generationId, reason)
                    }
                    writeRetryDelayRunner(8L)
                    continue
                }

                // written > 0: count ONLY frames actually accepted by AudioTrack
                consecutiveStalledZeroWrites = 0
                val wasPartial = written < remaining
                if (wasPartial) {
                    partialWritesInChunk += 1
                }
                offset += written
                remaining -= written

                val headFrames = driver.getPlaybackHeadFrames(generationId)
                lastHeadDuringZeroWrite = headFrames
                val diagNow = driver.getTrackDiagnostics(generationId)
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
                                trackState = diagNow.trackState,
                                playState = diagNow.playState,
                                lastWriteResultCode = written,
                                lastWriteErrorName = "OK($written)",
                                pendingQueuedBytes = newPending,
                                bytesAcceptedByHardware = newAccepted,
                                totalFramesWrittenToHardware = newFramesWritten,
                                hardwarePlaybackHeadFrames = headFrames,
                                partialWriteCount = newPartials,
                                statusDetail = "BYTES_ACCEPTED_BY_HARDWARE (${generationId.wireId}): $newAccepted/${current.audioBytesReceived} bytes accepted (acceptedFrames=$newFramesWritten, recvFrames=${current.framesReceivedFromServer}, head=$headFrames, partialWrites=$newPartials, zeroRetries=${current.zeroWriteRetryCount})."
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
     * Awaits hardware playback drain after Gemini `turnComplete` on [audioDispatcher]:
     * - Never waits when `totalFramesWrittenToHardware <= 0L`; fails immediately with `AUDIO_TRACK_WRITE_FAILED`.
     * - Drain target is strictly `totalFramesWrittenToHardware` (accepted frames), never `framesReceivedFromServer`.
     * - Computes bounded drain deadline from accepted frames and actual sample rate plus margin.
     * - Never calls `pause`/`stop`/`flush`/`release` before accepted audio has drained.
     */
    suspend fun awaitHardwareDrainAfterServerTurnComplete(
        generationId: PlaybackGenerationId,
        onEvent: (PlaybackCallbackEvent) -> Unit = {}
    ): Boolean = withContext(audioDispatcher) {
        synchronized(lock) {
            val current = _state.value
            if (current.activeGeneration == null || current.activeGeneration != generationId) {
                updateStateAndNotify {
                    it.copy(
                        stalePlaybackCallbacksDropped = it.stalePlaybackCallbacksDropped + 1,
                        statusDetail = "STALE_CALLBACK_DROPPED: Ignored drain await for ${generationId.wireId} (active=${current.activeGeneration?.wireId ?: "none"})."
                    )
                }
                return@withContext false
            }
            updateStateAndNotify {
                it.copy(
                    serverTurnComplete = true,
                    statusDetail = "Server turnComplete received for ${generationId.wireId}; flushing any remaining queued PCM writes before hardware drain..."
                )
            }
        }

        // Wait for all in-flight/queued chunk writes for this generation to complete first!
        writeMutex.withLock {
            val totalFramesTarget: Long
            val sampleRateHz: Int
            var lastObservedHead: Long
            synchronized(lock) {
                val current = _state.value
                if (current.activeGeneration == null || current.activeGeneration != generationId) {
                    updateStateAndNotify {
                        it.copy(stalePlaybackCallbacksDropped = it.stalePlaybackCallbacksDropped + 1)
                    }
                    return@withLock false
                }
                if (current.totalFramesWrittenToHardware <= 0L || current.bytesAcceptedByHardware <= 0L) {
                    val diag = driver.getTrackDiagnostics(generationId)
                    val reason =
                        "AUDIO_TRACK_WRITE_FAILED: Received ${current.chunksReceived} chunks (${current.framesReceivedFromServer} frames) from Gemini, but 0 frames were accepted by AudioTrack (state=${diag.trackState}, playState=${diag.playState}, lastWrite=${current.lastWriteErrorName}, detail=${diag.diagnostic})."
                    stopAndFlush(reason, error = reason, expectedGenerationId = generationId)
                    onEvent(PlaybackCallbackEvent.PlaybackFailed(generationId, reason, "AUDIO_TRACK_WRITE_FAILED"))
                    return@withLock false
                }
                if (current.pendingQueuedBytes > 0L) {
                    val diag = driver.getTrackDiagnostics(generationId)
                    val reason =
                        "AUDIO_TRACK_WRITE_FAILED: Server reported turnComplete with pendingQueuedBytes=${current.pendingQueuedBytes}, acceptedFrames=${current.totalFramesWrittenToHardware} (state=${diag.trackState}, playState=${diag.playState})."
                    stopAndFlush(reason, error = reason, expectedGenerationId = generationId)
                    onEvent(PlaybackCallbackEvent.PlaybackFailed(generationId, reason, "AUDIO_TRACK_WRITE_FAILED"))
                    return@withLock false
                }
                totalFramesTarget = current.totalFramesWrittenToHardware
                sampleRateHz = current.verifiedSampleRateHz.coerceAtLeast(8000)
            }

            // Keep track in PLAYSTATE_PLAYING (only pads sub-buffer short clips without calling stop/release)
            driver.finishStreamForDrain(generationId)

            val expectedPlaybackMs = (totalFramesTarget * 1000L) / sampleRateHz.toLong()
            val boundedDeadlineMs = max(5_000L, expectedPlaybackMs + 5_000L)
            val stallWindowMs = 5_000L
            val drainStartMs = clockMs()
            var lastProgressMs = drainStartMs

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
                        drainDeadlineMs = boundedDeadlineMs,
                        hardwarePlaybackHeadFrames = lastObservedHead,
                        statusDetail = "Draining hardware playback buffer (${generationId.wireId}): head=$lastObservedHead / target=$totalFramesTarget accepted frames (deadline=${boundedDeadlineMs}ms @ ${sampleRateHz}Hz)."
                    )
                }
            }

            var stalledPolls = 0
            while (true) {
                val currentGen = synchronized(lock) { _state.value.activeGeneration }
                if (currentGen != generationId) {
                    return@withLock false
                }

                val headFrames = driver.getPlaybackHeadFrames(generationId)
                val nowMs = clockMs()
                if (headFrames > lastObservedHead) {
                    lastObservedHead = headFrames
                    stalledPolls = 0
                    lastProgressMs = nowMs
                } else {
                    stalledPolls += 1
                }

                val diagNow = driver.getTrackDiagnostics(generationId)
                synchronized(lock) {
                    if (_state.value.activeGeneration == generationId) {
                        updateStateAndNotify {
                            it.copy(
                                hardwarePlaybackHeadFrames = headFrames,
                                trackState = diagNow.trackState,
                                playState = diagNow.playState,
                                statusDetail = "Draining hardware playback buffer (${generationId.wireId}): head=$headFrames / target=$totalFramesTarget accepted frames."
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

                val elapsedTotalMs = nowMs - drainStartMs
                val stalledWallMs = nowMs - lastProgressMs
                val deadlineExceeded = elapsedTotalMs >= boundedDeadlineMs
                val wallClockStalled = elapsedTotalMs > 0L && stalledWallMs >= stallWindowMs
                val iterationStalled = stalledPolls >= maxDrainPollIterations

                if (deadlineExceeded || wallClockStalled || iterationStalled) {
                    val reason =
                        "HARDWARE_DRAIN_INCOMPLETE: head=$headFrames / target=$totalFramesTarget accepted frames on ${generationId.wireId} (state=${diagNow.trackState}, playState=${diagNow.playState}, elapsed=${elapsedTotalMs}ms, deadline=${boundedDeadlineMs}ms)."
                    stopAndFlush(reason, error = reason, expectedGenerationId = generationId)
                    onEvent(PlaybackCallbackEvent.PlaybackFailed(generationId, reason, "HARDWARE_DRAIN_INCOMPLETE"))
                    return@withLock false
                }

                drainPollDelayRunner(10L)
            }
            @Suppress("UNREACHABLE_CODE")
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
                    it.copy(
                        stalePlaybackCallbacksDropped = it.stalePlaybackCallbacksDropped + 1,
                        statusDetail = "STALE_CALLBACK_DROPPED: Dropped ${event::class.java.simpleName} for ${event.generationId.wireId} (active=${activeGen?.wireId ?: "none"})."
                    )
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
                            activeTrackInstanceId = 0L,
                            hardwarePlaybackHeadFrames = event.hardwarePlaybackHeadFrames,
                            hardwareReleaseCount = current.hardwareReleaseCount + 1,
                            statusDetail = "PLAYBACK_DRAINED (${activeGen.wireId}): ${event.totalBytesAccepted} bytes (${event.hardwarePlaybackHeadFrames}/${event.totalFramesWritten} accepted frames) audibly drained to speaker."
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
                            activeTrackInstanceId = 0L,
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
     *
     * When [expectedGenerationId] is non-null, a stale turn's flush call will NEVER clobber a
     * newer active playback generation.
     */
    fun stopAndFlush(
        reason: String,
        error: String? = null,
        expectedGenerationId: PlaybackGenerationId? = null
    ) {
        val prevGen: PlaybackGenerationId?
        synchronized(lock) {
            val activeGen = _state.value.activeGeneration
            if (expectedGenerationId != null && activeGen != expectedGenerationId) {
                updateStateAndNotify { current ->
                    current.copy(
                        stalePlaybackCallbacksDropped = current.stalePlaybackCallbacksDropped + 1
                    )
                }
                return
            }
            prevGen = activeGen
            val wasActive = prevGen != null || _state.value.isPlaying || _state.value.isDrainingHardware || _state.value.isTrackOpen
            updateStateAndNotify { current ->
                current.copy(
                    activeGeneration = null,
                    lastCompletedGeneration = prevGen ?: current.lastCompletedGeneration,
                    isTrackOpen = false,
                    isPlaying = false,
                    isDrainingHardware = false,
                    isPlaybackDrained = false,
                    activeTrackInstanceId = 0L,
                    pendingQueuedBytes = 0L,
                    lastError = error ?: current.lastError,
                    hardwareReleaseCount = if (wasActive) current.hardwareReleaseCount + 1 else current.hardwareReleaseCount,
                    statusDetail = "Playback stopped & flushed (${prevGen?.wireId ?: "none"}): $reason"
                )
            }
        }
        driver.stopAndFlushTrack(prevGen ?: expectedGenerationId)
    }
}
