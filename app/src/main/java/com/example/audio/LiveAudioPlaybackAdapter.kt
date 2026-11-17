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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hardware streaming output abstraction for [LiveAudioPlaybackAdapter].
 */
interface StreamingAudioTrackDriver {
    fun openStreamingTrack(
        generationId: PlaybackGenerationId,
        sampleRateHz: Int,
        channelCount: Int = 1
    ): Boolean

    fun startStreamingTrackPlayback(generationId: PlaybackGenerationId): Boolean {
        return true
    }

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
    fun getUnderrunCount(generationId: PlaybackGenerationId): Int = 0
}

/**
 * Production Android implementation of [StreamingAudioTrackDriver] using [AudioTrack]
 * in [AudioTrack.MODE_STREAM] for 16-bit little-endian PCM output with FIFO ordering,
 * prebuffering, blocking writes, and underrun tracking.
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
    private var paddingFramesInTrack: Long = 0L
    private var paddingBytesInTrack: Long = 0L

    private val closedGenerations = LinkedHashSet<PlaybackGenerationId>()
    private val releasedTrackInstanceIds = LinkedHashSet<Long>()

    private var lastRawHead32: Long = 0L
    private var wraparoundOffset: Long = 0L
    private var lastMonotonicHead: Long = 0L

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

    override fun startStreamingTrackPlayback(generationId: PlaybackGenerationId): Boolean {
        synchronized(lock) {
            if (activeGenerationId != generationId) return false
            val track = activeTrack ?: return false
            if (track.state == AudioTrack.STATE_INITIALIZED && track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                try {
                    track.play()
                    lastPlayState = track.playState
                    lastDiagnostic = "AudioTrack started playing for ${generationId.wireId}"
                } catch (t: Throwable) {
                    lastDiagnostic = "AudioTrack.play() exception: ${t.message}"
                }
            }
            return track.playState == AudioTrack.PLAYSTATE_PLAYING
        }
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
                existing.state == AudioTrack.STATE_INITIALIZED
            ) {
                lastTrackState = existing.state
                lastPlayState = existing.playState
                return true
            }

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

            val bytesPerFrame = 2 * channelCount.coerceAtLeast(1)
            val bytesFor550Ms = (sampleRateHz * bytesPerFrame * 550) / 1000
            val bufferSizeInBytes = max(minBufBytes * 4, bytesFor550Ms)

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

                val newInstanceId = trackInstanceSeq.incrementAndGet()
                activeTrack = track
                activeGenerationId = generationId
                activeSampleRateHz = sampleRateHz
                activeChannelCount = channelCount
                activeBufferSizeInFrames = bufferSizeInBytes / bytesPerFrame
                activeTrackInstanceId = newInstanceId
                writtenFramesInTrack = 0L
                paddingFramesInTrack = 0L
                paddingBytesInTrack = 0L
                lastRawHead32 = 0L
                wraparoundOffset = 0L
                lastMonotonicHead = 0L
                lastWriteCode = 0
                lastDiagnostic = "Track #$newInstanceId initialized (buffered) (${sampleRateHz}Hz ch=$channelCount bufFrames=$activeBufferSizeInFrames)"
                true
            } catch (t: Throwable) {
                lastDiagnostic = "AudioTrack construction exception: ${t::class.java.simpleName}: ${t.message}"
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
                current.state != AudioTrack.STATE_INITIALIZED
            ) {
                lastTrackState = current?.state ?: AudioTrack.STATE_UNINITIALIZED
                lastPlayState = current?.playState ?: AudioTrack.PLAYSTATE_STOPPED
                lastWriteCode = AudioTrack.ERROR_INVALID_OPERATION
                lastDiagnostic = "Track not in STATE_INITIALIZED (state=$lastTrackState)"
                return AudioTrack.ERROR_INVALID_OPERATION
            }
            track = current
            channelCount = activeChannelCount.coerceAtLeast(1)
        }

        return try {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                track.write(pcmBytes, offset, length, AudioTrack.WRITE_BLOCKING)
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
        val trackToPad: AudioTrack?
        val silenceBytesCount: Int
        val channelCount: Int
        synchronized(lock) {
            if (activeGenerationId != generationId) return
            val track = activeTrack ?: return
            if (track.state != AudioTrack.STATE_INITIALIZED || writtenFramesInTrack <= 0L) {
                return
            }
            channelCount = activeChannelCount.coerceAtLeast(1)
            val bytesPerFrame = 2 * channelCount
            val sampleRate = activeSampleRateHz.coerceAtLeast(16_000)
            val tailCushionFrames = (sampleRate * 80) / 1000
            val deficitFrames = if (writtenFramesInTrack < activeBufferSizeInFrames.toLong()) {
                (activeBufferSizeInFrames.toLong() - writtenFramesInTrack).toInt()
            } else {
                0
            }
            val totalPadFrames = (deficitFrames + tailCushionFrames).coerceAtLeast(tailCushionFrames)
            trackToPad = track
            silenceBytesCount = totalPadFrames * bytesPerFrame
        }
        if (trackToPad != null && silenceBytesCount > 0) {
            try {
                val silence = ByteArray(silenceBytesCount)
                val writtenPad = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    trackToPad.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
                } else {
                    trackToPad.write(silence, 0, silence.size)
                }
                if (writtenPad > 0) {
                    synchronized(lock) {
                        if (activeGenerationId == generationId) {
                            val bytesPerFrame = 2 * channelCount
                            paddingBytesInTrack += writtenPad.toLong()
                            paddingFramesInTrack += (writtenPad / bytesPerFrame).toLong()
                        }
                    }
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
                (track.playState == AudioTrack.PLAYSTATE_PLAYING || track.playState == AudioTrack.PLAYSTATE_STOPPED)
        }
    }

    override fun getUnderrunCount(generationId: PlaybackGenerationId): Int {
        synchronized(lock) {
            if (activeGenerationId != generationId) return 0
            val track = activeTrack ?: return 0
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                track.underrunCount
            } else {
                0
            }
        }
    }

    override fun getTrackDiagnostics(generationId: PlaybackGenerationId): AudioTrackDiagnostics {
        synchronized(lock) {
            val track = if (activeGenerationId == generationId) activeTrack else null
            val underruns = if (track != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) track.underrunCount else 0
            return AudioTrackDiagnostics(
                trackState = track?.state ?: lastTrackState,
                playState = track?.playState ?: lastPlayState,
                sampleRateHz = activeSampleRateHz,
                channelCount = activeChannelCount,
                bufferSizeInFrames = activeBufferSizeInFrames,
                trackInstanceId = activeTrackInstanceId,
                lastWriteCode = lastWriteCode,
                underrunCount = underruns,
                paddingFramesWritten = paddingFramesInTrack,
                paddingBytesWritten = paddingBytesInTrack,
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
        paddingFramesInTrack = 0L
        paddingBytesInTrack = 0L

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
    private var currentGenerationBytesAccepted: Long = 0L
    var manualPlaybackHeadFrames: Long = 0L
    var simulatedRawHead32: Long? = null
    private var lastRawHead32: Long = 0L
    private var wraparoundOffset: Long = 0L
    private var lastMonotonicHead: Long = 0L
    var paddingFramesWritten: Long = 0L
        private set
    var paddingBytesWritten: Long = 0L
        private set

    var onWriteHook: ((PlaybackGenerationId, Int) -> Unit)? = null

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
    var startPlaybackCalledCount: Int = 0
        private set
    var isPlaying: Boolean = false
        private set

    var stopCalledBeforeDrainCount: Int = 0
    var reusedReleasedTrackViolationCount: Int = 0

    val createdTrackInstanceIds = mutableListOf<Long>()
    val trackInstanceIdsByGeneration = mutableMapOf<PlaybackGenerationId, MutableList<Long>>()
    val releasedTrackInstanceIds = mutableSetOf<Long>()
    val closedGenerations = mutableSetOf<PlaybackGenerationId>()

    fun resetRecordedBytes() {
        scriptedWriteSizes.clear()
        acceptedBytesStream.reset()
        totalBytesAccepted = 0L
        manualPlaybackHeadFrames = 0L
        paddingFramesWritten = 0L
        paddingBytesWritten = 0L
        writeInvocationCount = 0
        finishStreamForDrainCallCount = 0
    }

    fun setRawPlaybackHead32ForWraparoundTest(raw32: Long) {
        simulatedRawHead32 = raw32
    }

    fun enqueueScriptedWriteSizes(vararg sizes: Int) {
        for (size in sizes) {
            scriptedWriteSizes.addLast(size)
        }
    }

    fun getAcceptedPcmBytes(): ByteArray = acceptedBytesStream.toByteArray()

    override fun openStreamingTrack(
        generationId: PlaybackGenerationId,
        sampleRateHz: Int,
        channelCount: Int
    ): Boolean {
        if (closedGenerations.contains(generationId)) return false
        if (!openTrackSucceeds) return false
        val newInstanceId = trackInstanceSeq.incrementAndGet()
        val isNewGeneration = activeGenerationId != generationId
        activeGenerationId = generationId
        activeTrackInstanceId = newInstanceId
        openedSampleRateHz = sampleRateHz
        openedChannelCount = channelCount
        if (isNewGeneration) {
            currentGenerationBytesAccepted = 0L
            manualPlaybackHeadFrames = 0L
            paddingFramesWritten = 0L
            paddingBytesWritten = 0L
            lastRawHead32 = 0L
            wraparoundOffset = 0L
            lastMonotonicHead = 0L
        }
        isPlaying = false
        trackCreationCount += 1
        createdTrackInstanceIds.add(newInstanceId)
        trackInstanceIdsByGeneration.getOrPut(generationId) { mutableListOf() }.add(newInstanceId)
        return true
    }

    override fun startStreamingTrackPlayback(generationId: PlaybackGenerationId): Boolean {
        if (activeGenerationId == generationId && playSucceeds) {
            startPlaybackCalledCount += 1
            isPlaying = true
            return true
        }
        return false
    }

    override fun recreateStreamingTrack(
        generationId: PlaybackGenerationId,
        sampleRateHz: Int,
        channelCount: Int
    ): Boolean {
        if (closedGenerations.contains(generationId)) return false
        val oldInstance = activeTrackInstanceId
        if (oldInstance != 0L) {
            releasedTrackInstanceIds.add(oldInstance)
        }
        activeTrackInstanceId = 0L
        trackRecreationCount += 1
        return openStreamingTrack(generationId, sampleRateHz, channelCount)
    }

    override fun writePcmBytes(
        generationId: PlaybackGenerationId,
        pcmBytes: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        if (activeGenerationId != generationId || activeTrackInstanceId == 0L || activeTrackInstanceId in releasedTrackInstanceIds) {
            lastWriteCode = AudioTrack.ERROR_INVALID_OPERATION
            return AudioTrack.ERROR_INVALID_OPERATION
        }
        writeInvocationCount += 1
        onWriteHook?.invoke(generationId, length)
        if (activeGenerationId != generationId || activeTrackInstanceId == 0L || activeTrackInstanceId in releasedTrackInstanceIds) {
            lastWriteCode = AudioTrack.ERROR_INVALID_OPERATION
            return AudioTrack.ERROR_INVALID_OPERATION
        }

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
        currentGenerationBytesAccepted += actualToWrite
        val bytesPerFrame = (2 * openedChannelCount.coerceAtLeast(1)).toLong()
        if (autoAdvancePlaybackHead) {
            manualPlaybackHeadFrames = currentGenerationBytesAccepted / bytesPerFrame
        }
        return actualToWrite
    }

    override fun finishStreamForDrain(generationId: PlaybackGenerationId) {
        if (activeGenerationId == generationId) {
            finishStreamForDrainCallCount += 1
            val sampleRate = openedSampleRateHz.coerceAtLeast(16_000)
            val bytesPerFrame = (2 * openedChannelCount.coerceAtLeast(1)).toLong()
            val tailCushionFrames = ((sampleRate * 80) / 1000).toLong()
            paddingFramesWritten += tailCushionFrames
            paddingBytesWritten += tailCushionFrames * bytesPerFrame
        }
    }

    override fun getPlaybackHeadFrames(generationId: PlaybackGenerationId): Long {
        if (activeGenerationId != generationId) return 0L
        val raw32 = simulatedRawHead32 ?: if (autoAdvancePlaybackHead) {
            val bytesPerFrame = (2 * openedChannelCount.coerceAtLeast(1)).toLong()
            currentGenerationBytesAccepted / bytesPerFrame
        } else {
            manualPlaybackHeadFrames
        }
        if (raw32 < lastRawHead32 && (lastRawHead32 - raw32) > 0x8000_0000L) {
            wraparoundOffset += 0x1_0000_0000L
        }
        lastRawHead32 = raw32
        lastMonotonicHead = max(lastMonotonicHead, wraparoundOffset + raw32)
        return lastMonotonicHead
    }

    override fun isTrackActivelyPlaying(generationId: PlaybackGenerationId): Boolean {
        return activeGenerationId == generationId && activeTrackInstanceId != 0L && isPlaying
    }

    override fun getUnderrunCount(generationId: PlaybackGenerationId): Int = 0

    override fun getTrackDiagnostics(generationId: PlaybackGenerationId): AudioTrackDiagnostics {
        return AudioTrackDiagnostics(
            trackState = AudioTrack.STATE_INITIALIZED,
            playState = if (isPlaying) AudioTrack.PLAYSTATE_PLAYING else AudioTrack.PLAYSTATE_STOPPED,
            sampleRateHz = openedSampleRateHz,
            channelCount = openedChannelCount,
            bufferSizeInFrames = (openedSampleRateHz / 5).coerceAtLeast(1024),
            trackInstanceId = activeTrackInstanceId,
            lastWriteCode = lastWriteCode,
            underrunCount = 0,
            paddingFramesWritten = paddingFramesWritten,
            paddingBytesWritten = paddingBytesWritten,
            diagnostic = "Deterministic test driver track active"
        )
    }

    override fun stopAndFlushTrack(generationId: PlaybackGenerationId?) {
        if (activeTrackInstanceId != 0L) {
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
        isPlaying = false
    }
}

/**
 * Queued PCM chunk for strict FIFO ordering and prebuffering.
 */
data class QueuedPcmChunk(
    val sequenceNumber: Long,
    val mimeType: String?,
    val pcmBytes: ByteArray
)

/**
 * Single streaming PCM audio playback adapter for OmniVoice Rebuild (Phase 06A Repair).
 */
class LiveAudioPlaybackAdapter(
    val driver: StreamingAudioTrackDriver = AndroidStreamingAudioTrackDriver(),
    private val maxZeroWriteRetriesPerChunk: Int = 600,
    private val maxDrainPollIterations: Int = 600,
    private val writeRetryDelayRunner: suspend (Long) -> Unit = { ms -> delay(ms) },
    private val drainPollDelayRunner: suspend (Long) -> Unit = { ms -> delay(ms) },
    private val audioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    @Volatile var hardwarePlaybackStallTimeoutMs: Long = 5_000L,
    @Volatile var maxDrainHardBoundMs: Long = 60_000L
) {
    private val lock = Any()
    private val writeMutex = Mutex()
    private val generationSeq = AtomicLong(0L)

    private val pumpScope = CoroutineScope(SupervisorJob() + audioDispatcher)
    private val chunkQueues = mutableMapOf<PlaybackGenerationId, ArrayDeque<QueuedPcmChunk>>()
    private val pumpJobs = mutableMapOf<PlaybackGenerationId, Job>()
    private val wakeSignals = mutableMapOf<PlaybackGenerationId, CompletableDeferred<Unit>>()
    private val inputClosedGenerations = mutableSetOf<PlaybackGenerationId>()
    private val chunkSequenceCounters = mutableMapOf<PlaybackGenerationId, AtomicLong>()

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

    fun startTurnPlayback(turnId: TurnId, sessionId: SessionId): PlaybackGenerationId {
        val newGen: PlaybackGenerationId
        val prevGen: PlaybackGenerationId?
        val signalsToCancel: List<CompletableDeferred<Unit>>
        synchronized(lock) {
            prevGen = _state.value.activeGeneration
            recreatedGeneration = null

            pumpJobs.values.forEach { it.cancel() }
            pumpJobs.clear()
            signalsToCancel = wakeSignals.values.toList()
            wakeSignals.clear()
            chunkQueues.clear()
            inputClosedGenerations.clear()
            chunkSequenceCounters.clear()

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
                    paddingFramesWrittenToHardware = 0L,
                    paddingBytesWrittenToHardware = 0L,
                    lastHardwareProgressAtMs = 0L,
                    partialWriteCount = 0,
                    zeroWriteRetryCount = 0,
                    lastWriteResultCode = 0,
                    lastWriteErrorName = "NONE",
                    activeTrackInstanceId = 0L,
                    drainDeadlineMs = 0L,
                    underrunCount = 0,
                    queuedBytes = 0L,
                    maximumQueuedBytes = 0L,
                    prebufferTargetBytes = 0L,
                    playbackStartedAfterBufferedBytes = 0L,
                    expectedChunkSequence = 0L,
                    lastProcessedChunkSequence = 0L,
                    lastError = null,
                    statusDetail = "Playback generation ${newGen.wireId} initialized; awaiting verified PCM audio chunks."
                )
            }
        }
        signalsToCancel.forEach { it.cancel() }
        if (prevGen != null) {
            driver.stopAndFlushTrack(prevGen)
        }
        return newGen
    }

    /**
     * Synchronously verifies MIME/alignment and enqueues a PCM chunk into the generation's
     * strict FIFO queue without blocking the caller on `AudioTrack.write` or coroutine chains.
     */
    fun enqueueVerifiedPcmChunk(
        generationId: PlaybackGenerationId,
        rawMimeType: String?,
        pcmBytes: ByteArray,
        onEvent: (PlaybackCallbackEvent) -> Unit = {}
    ): PcmChunkWriteOutcome {
        synchronized(lock) {
            val currentGen = _state.value.activeGeneration
            if (currentGen == null || currentGen != generationId) {
                updateStateAndNotify {
                    it.copy(
                        stalePlaybackCallbacksDropped = it.stalePlaybackCallbacksDropped + 1,
                        statusDetail = "STALE_CALLBACK_DROPPED: Dropped chunk for ${generationId.wireId} (active=${currentGen?.wireId ?: "none"})."
                    )
                }
                return PcmChunkWriteOutcome.DroppedStaleGeneration(
                    generationId = generationId,
                    activeGenerationId = currentGen
                )
            }
        }

        val mimeVerification = PcmMimeVerifier.verifyAndParseSampleRate(rawMimeType)
        if (mimeVerification is PcmMimeVerificationResult.Rejected) {
            emitFailureAndFlushIfStillActive(
                generationId = generationId,
                reason = "Rejected unverified/unsupported MIME (${mimeVerification.reason})",
                errorCode = "UNSUPPORTED_AUDIO_MIME",
                onEvent = onEvent
            )
            return PcmChunkWriteOutcome.RejectedMime(
                generationId = generationId,
                rawMimeType = rawMimeType,
                reason = mimeVerification.reason
            )
        }
        val verified = mimeVerification as PcmMimeVerificationResult.Verified

        val bytesPerFrame = 2 * verified.channelCount.coerceAtLeast(1)
        val alignedByteCount = pcmBytes.size - (pcmBytes.size % bytesPerFrame)
        if (alignedByteCount <= 0) {
            val reason = "AUDIO_TRACK_WRITE_FAILED: Audio chunk has 0 aligned 16-bit PCM bytes (size=${pcmBytes.size})."
            emitFailureAndFlushIfStillActive(generationId, reason, "AUDIO_TRACK_WRITE_FAILED", onEvent)
            return PcmChunkWriteOutcome.FailedWrite(generationId, reason)
        }
        val chunkFrames = alignedByteCount.toLong() / bytesPerFrame.toLong()

        val seq = synchronized(lock) {
            val counter = chunkSequenceCounters.getOrPut(generationId) { AtomicLong(0L) }
            counter.incrementAndGet()
        }

        val totalReceivedAfterChunk: Long
        val newQueuedBytes: Long
        val newMaxQueuedBytes: Long
        val wakeSignal: CompletableDeferred<Unit>?
        synchronized(lock) {
            val currentGen = _state.value.activeGeneration
            if (currentGen != generationId) {
                updateStateAndNotify {
                    it.copy(stalePlaybackCallbacksDropped = it.stalePlaybackCallbacksDropped + 1)
                }
                return PcmChunkWriteOutcome.DroppedStaleGeneration(generationId, currentGen)
            }
            totalReceivedAfterChunk = _state.value.audioBytesReceived + alignedByteCount
            newQueuedBytes = _state.value.queuedBytes + alignedByteCount
            newMaxQueuedBytes = max(_state.value.maximumQueuedBytes, newQueuedBytes)

            val queue = chunkQueues.getOrPut(generationId) { ArrayDeque() }
            queue.addLast(QueuedPcmChunk(seq, rawMimeType, pcmBytes))
            wakeSignal = wakeSignals.remove(generationId)

            updateStateAndNotify { current ->
                current.copy(
                    verifiedMimeType = verified.canonicalMimeType,
                    verifiedSampleRateHz = verified.sampleRateHz,
                    chunksReceived = current.chunksReceived + 1,
                    framesReceivedFromServer = current.framesReceivedFromServer + chunkFrames,
                    audioBytesReceived = totalReceivedAfterChunk,
                    pendingQueuedBytes = newQueuedBytes,
                    queuedBytes = newQueuedBytes,
                    maximumQueuedBytes = newMaxQueuedBytes,
                    expectedChunkSequence = seq,
                    isPlaybackDrained = false,
                    statusDetail = "AUDIO_RECEIVED (${generationId.wireId}): Chunk #$seq ($alignedByteCount bytes / $chunkFrames frames @ ${verified.canonicalMimeType})."
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

        ensurePumpRunning(generationId, verified.sampleRateHz, verified.channelCount, onEvent)
        wakeSignal?.complete(Unit)

        // Check if track opening failed in pump
        val currentError = synchronized(lock) { _state.value.lastError }
        if (currentError != null && currentError.startsWith("AUDIO_TRACK_WRITE_FAILED")) {
            return PcmChunkWriteOutcome.FailedWrite(generationId, currentError)
        }

        return PcmChunkWriteOutcome.WrittenToHardware(
            generationId = generationId,
            verifiedMimeType = verified.canonicalMimeType,
            sampleRateHz = verified.sampleRateHz,
            chunkBytesReceived = alignedByteCount,
            chunkBytesAccepted = alignedByteCount,
            totalBytesAcceptedByHardware = alignedByteCount.toLong(),
            partialWritesInChunk = 0,
            zeroWritesInChunk = 0
        )
    }

    suspend fun verifyAndWritePcmChunk(
        generationId: PlaybackGenerationId,
        rawMimeType: String?,
        pcmBytes: ByteArray,
        onEvent: (PlaybackCallbackEvent) -> Unit = {}
    ): PcmChunkWriteOutcome = withContext(audioDispatcher) {
        enqueueVerifiedPcmChunk(
            generationId = generationId,
            rawMimeType = rawMimeType,
            pcmBytes = pcmBytes,
            onEvent = onEvent
        )
    }

    private fun ensurePumpRunning(
        generationId: PlaybackGenerationId,
        sampleRateHz: Int,
        channelCount: Int,
        onEvent: (PlaybackCallbackEvent) -> Unit
    ) {
        synchronized(lock) {
            if (pumpJobs.containsKey(generationId)) return
            val job = pumpScope.launch {
                runPlaybackPump(generationId, sampleRateHz, channelCount, onEvent)
            }
            pumpJobs[generationId] = job
        }
    }

    private suspend fun runPlaybackPump(
        generationId: PlaybackGenerationId,
        sampleRateHz: Int,
        channelCount: Int,
        onEvent: (PlaybackCallbackEvent) -> Unit
    ) {
        val bytesPerFrame = 2 * channelCount.coerceAtLeast(1)
        val prebufferTargetBytes = (sampleRateHz * bytesPerFrame * 220) / 1000 // ~220 ms prebuffer

        synchronized(lock) {
            updateStateAndNotify { it.copy(prebufferTargetBytes = prebufferTargetBytes.toLong()) }
        }

        val trackOpened = driver.openStreamingTrack(
            generationId = generationId,
            sampleRateHz = sampleRateHz,
            channelCount = channelCount
        )
        val openDiag = driver.getTrackDiagnostics(generationId)
        if (!trackOpened) {
            val reason = "AUDIO_TRACK_WRITE_FAILED: AudioTrack init failed for ${sampleRateHz}Hz (detail=${openDiag.diagnostic} / writeCode=${openDiag.lastWriteCode})."
            emitFailureAndFlushIfStillActive(generationId, reason, "AUDIO_TRACK_WRITE_FAILED", onEvent)
            return
        }

        synchronized(lock) {
            if (_state.value.activeGeneration == generationId) {
                updateStateAndNotify { current ->
                    current.copy(
                        isTrackOpen = true,
                        trackState = openDiag.trackState,
                        playState = openDiag.playState,
                        activeTrackInstanceId = openDiag.trackInstanceId,
                        trackCreationCount = current.trackCreationCount + 1
                    )
                }
            }
        }

        var playbackStarted = false
        var accumulatedBufferedBytes = 0L
        var lastObservedPumpHeadFrames = 0L
        var lastPumpHeadProgressMs = clockMs()

        fun checkPendingHardwareProgressOrStall(): Boolean {
            if (!playbackStarted) return true
            val currentHead = driver.getPlaybackHeadFrames(generationId)
            val nowMs = clockMs()
            val totalWrittenFrames = synchronized(lock) {
                if (_state.value.activeGeneration == generationId) {
                    val headAdvanced = currentHead > _state.value.hardwarePlaybackHeadFrames
                    updateStateAndNotify {
                        it.copy(
                            hardwarePlaybackHeadFrames = currentHead,
                            lastHardwareProgressAtMs = if (headAdvanced) nowMs else it.lastHardwareProgressAtMs
                        )
                    }
                    _state.value.totalFramesWrittenToHardware
                } else {
                    0L
                }
            }
            if (totalWrittenFrames <= currentHead) {
                lastObservedPumpHeadFrames = currentHead
                lastPumpHeadProgressMs = nowMs
                return true
            }
            if (currentHead > lastObservedPumpHeadFrames) {
                lastObservedPumpHeadFrames = currentHead
                lastPumpHeadProgressMs = nowMs
                return true
            }
            val stallLimitMs = hardwarePlaybackStallTimeoutMs.coerceAtLeast(100L)
            if ((nowMs - lastPumpHeadProgressMs) >= stallLimitMs) {
                val reason = "HARDWARE_PLAYBACK_STALL: Hardware playback head stalled at $currentHead / $totalWrittenFrames pending frames for >= $stallLimitMs ms on ${generationId.wireId}."
                emitFailureAndFlushIfStillActive(generationId, reason, "HARDWARE_PLAYBACK_STALL", onEvent)
                return false
            }
            return true
        }

        while (true) {
            val activeGen = synchronized(lock) { _state.value.activeGeneration }
            if (activeGen != generationId) break

            val chunk = synchronized(lock) {
                val queue = chunkQueues[generationId]
                if (queue == null || queue.isEmpty()) {
                    null
                } else {
                    queue.removeFirst()
                }
            }

            if (chunk == null) {
                val nextAction = synchronized(lock) {
                    val activeNow = _state.value.activeGeneration
                    if (activeNow != generationId) {
                        wakeSignals.remove(generationId)?.cancel()
                        -1
                    } else {
                        val queueEmpty = chunkQueues[generationId]?.isEmpty() ?: true
                        if (!queueEmpty) {
                            1
                        } else if (inputClosedGenerations.contains(generationId)) {
                            wakeSignals.remove(generationId)?.cancel()
                            2
                        } else {
                            0
                        }
                    }
                }
                if (nextAction == -1) break
                if (nextAction == 1) continue
                if (nextAction == 2) {
                    if (!playbackStarted) {
                        playbackStarted = true
                        driver.startStreamingTrackPlayback(generationId)
                        lastObservedPumpHeadFrames = driver.getPlaybackHeadFrames(generationId)
                        lastPumpHeadProgressMs = clockMs()
                        synchronized(lock) {
                            updateStateAndNotify {
                                it.copy(
                                    isPlaying = true,
                                    playbackStartedAfterBufferedBytes = accumulatedBufferedBytes,
                                    hardwarePlaybackHeadFrames = lastObservedPumpHeadFrames,
                                    lastHardwareProgressAtMs = lastPumpHeadProgressMs
                                )
                            }
                        }
                    }
                    break
                }
                if (!checkPendingHardwareProgressOrStall()) {
                    return
                }
                val waitSignal = synchronized(lock) {
                    val activeNow = _state.value.activeGeneration
                    val queueStillEmpty = chunkQueues[generationId]?.isEmpty() ?: true
                    val stillOpen = !inputClosedGenerations.contains(generationId)
                    if (activeNow == generationId && queueStillEmpty && stillOpen) {
                        CompletableDeferred<Unit>().also { wakeSignals[generationId] = it }
                    } else {
                        null
                    }
                }
                if (waitSignal != null) {
                    val hasPendingHardwareFrames = playbackStarted && synchronized(lock) {
                        _state.value.totalFramesWrittenToHardware > _state.value.hardwarePlaybackHeadFrames
                    }
                    if (hasPendingHardwareFrames) {
                        withTimeoutOrNull(10L) {
                            waitSignal.await()
                        }
                    } else {
                        waitSignal.await()
                    }
                    synchronized(lock) {
                        if (wakeSignals[generationId] === waitSignal) {
                            wakeSignals.remove(generationId)
                        }
                    }
                }
                continue
            }

            accumulatedBufferedBytes += chunk.pcmBytes.size

            if (!playbackStarted && accumulatedBufferedBytes >= prebufferTargetBytes) {
                playbackStarted = true
                driver.startStreamingTrackPlayback(generationId)
                lastObservedPumpHeadFrames = driver.getPlaybackHeadFrames(generationId)
                lastPumpHeadProgressMs = clockMs()
                synchronized(lock) {
                    updateStateAndNotify {
                        it.copy(
                            isPlaying = true,
                            playbackStartedAfterBufferedBytes = accumulatedBufferedBytes,
                            hardwarePlaybackHeadFrames = lastObservedPumpHeadFrames,
                            lastHardwareProgressAtMs = lastPumpHeadProgressMs
                        )
                    }
                }
            }

            var offset = 0
            var remaining = chunk.pcmBytes.size
            var consecutiveZeros = 0

            while (remaining > 0) {
                val genCheck = synchronized(lock) { _state.value.activeGeneration }
                if (genCheck != generationId) break

                val written = driver.writePcmBytes(generationId, chunk.pcmBytes, offset, remaining)
                if (written < 0) {
                    val mappedErr = AudioTrackErrorMapper.mapCode(written)
                    synchronized(lock) {
                        if (_state.value.activeGeneration == generationId) {
                            updateStateAndNotify { cur ->
                                cur.copy(
                                    lastWriteResultCode = written,
                                    lastWriteErrorName = mappedErr
                                )
                            }
                        }
                    }
                    val canRecreateOnce = synchronized(lock) {
                        _state.value.activeGeneration == generationId && recreatedGeneration != generationId
                    }
                    if (canRecreateOnce) {
                        recreatedGeneration = generationId
                        val recreated = driver.recreateStreamingTrack(
                            generationId = generationId,
                            sampleRateHz = sampleRateHz,
                            channelCount = channelCount
                        )
                        if (recreated) {
                            synchronized(lock) {
                                updateStateAndNotify { cur ->
                                    cur.copy(trackRecreationCount = cur.trackRecreationCount + 1)
                                }
                            }
                            continue
                        }
                    }
                    val reason = "AUDIO_TRACK_WRITE_FAILED: AudioTrack.write returned $mappedErr on ${generationId.wireId}."
                    emitFailureAndFlushIfStillActive(generationId, reason, "AUDIO_TRACK_WRITE_FAILED", onEvent)
                    return
                }

                if (written == 0) {
                    consecutiveZeros++
                    synchronized(lock) {
                        updateStateAndNotify { cur ->
                            cur.copy(zeroWriteRetryCount = cur.zeroWriteRetryCount + 1)
                        }
                    }
                    if (!checkPendingHardwareProgressOrStall()) {
                        return
                    }
                    if (consecutiveZeros > maxZeroWriteRetriesPerChunk) {
                        val reason = "AUDIO_TRACK_WRITE_FAILED: AudioTrack.write stalled at 0 bytes."
                        emitFailureAndFlushIfStillActive(generationId, reason, "AUDIO_TRACK_WRITE_FAILED", onEvent)
                        return
                    }
                    writeRetryDelayRunner(8L)
                    continue
                }

                consecutiveZeros = 0
                val isPartial = written < remaining
                offset += written
                remaining -= written
                val writeNowMs = clockMs()
                val currentHeadNow = driver.getPlaybackHeadFrames(generationId)

                synchronized(lock) {
                    if (_state.value.activeGeneration == generationId) {
                        val cur = _state.value
                        val newAccepted = cur.bytesAcceptedByHardware + written
                        val newFrames = newAccepted / bytesPerFrame.toLong()
                        val newQueued = (cur.queuedBytes - written).coerceAtLeast(0L)
                        val diag = driver.getTrackDiagnostics(generationId)
                        updateStateAndNotify {
                            it.copy(
                                bytesAcceptedByHardware = newAccepted,
                                totalFramesWrittenToHardware = newFrames,
                                hardwarePlaybackHeadFrames = max(it.hardwarePlaybackHeadFrames, currentHeadNow),
                                lastHardwareProgressAtMs = writeNowMs,
                                pendingQueuedBytes = newQueued,
                                queuedBytes = newQueued,
                                partialWriteCount = if (isPartial) cur.partialWriteCount + 1 else cur.partialWriteCount,
                                lastProcessedChunkSequence = chunk.sequenceNumber,
                                underrunCount = diag.underrunCount,
                                trackState = diag.trackState,
                                playState = diag.playState
                            )
                        }
                    }
                }
            }

            if (!checkPendingHardwareProgressOrStall()) {
                return
            }

            val snapshot = _state.value
            val acceptedEvent = PlaybackCallbackEvent.BytesAcceptedByHardware(
                generationId = generationId,
                chunkBytesAccepted = chunk.pcmBytes.size,
                totalBytesAccepted = snapshot.bytesAcceptedByHardware,
                totalFramesWritten = snapshot.totalFramesWrittenToHardware,
                partialWriteCount = snapshot.partialWriteCount,
                zeroWriteRetryCount = snapshot.zeroWriteRetryCount
            )
            onPlaybackCallback(acceptedEvent)
            onEvent(acceptedEvent)
        }
    }

    suspend fun awaitHardwareDrainAfterServerTurnComplete(
        generationId: PlaybackGenerationId,
        onEvent: (PlaybackCallbackEvent) -> Unit = {}
    ): Boolean = withContext(audioDispatcher) {
        val wakeSignal: CompletableDeferred<Unit>?
        synchronized(lock) {
            val current = _state.value
            if (current.activeGeneration == null || current.activeGeneration != generationId) {
                updateStateAndNotify {
                    it.copy(
                        stalePlaybackCallbacksDropped = it.stalePlaybackCallbacksDropped + 1,
                        statusDetail = "STALE_CALLBACK_DROPPED: Ignored drain await for ${generationId.wireId}."
                    )
                }
                return@withContext false
            }
            inputClosedGenerations.add(generationId)
            wakeSignal = wakeSignals.remove(generationId)
            updateStateAndNotify {
                it.copy(
                    serverTurnComplete = true,
                    statusDetail = "Server turnComplete received for ${generationId.wireId}; awaiting pump completion and hardware drain..."
                )
            }
        }
        wakeSignal?.complete(Unit)

        val job = synchronized(lock) { pumpJobs[generationId] }
        job?.join()

        writeMutex.withLock {
            val totalFramesTarget: Long
            val sampleRateHz: Int
            var lastObservedHead: Long
            synchronized(lock) {
                val current = _state.value
                if (current.activeGeneration == null || current.activeGeneration != generationId) {
                    return@withLock false
                }
                if (current.totalFramesWrittenToHardware <= 0L || current.bytesAcceptedByHardware <= 0L) {
                    val reason = "AUDIO_TRACK_WRITE_FAILED: 0 frames accepted by AudioTrack."
                    emitFailureAndFlushIfStillActive(generationId, reason, "AUDIO_TRACK_WRITE_FAILED", onEvent)
                    return@withLock false
                }
                totalFramesTarget = current.totalFramesWrittenToHardware
                sampleRateHz = current.verifiedSampleRateHz.coerceAtLeast(8000)
            }

            driver.finishStreamForDrain(generationId)
            val postPadDiag = driver.getTrackDiagnostics(generationId)

            val stallTimeoutMs = hardwarePlaybackStallTimeoutMs.coerceAtLeast(100L)
            val expectedPlaybackMs = (totalFramesTarget * 1000L) / sampleRateHz.toLong()
            val boundedDeadlineMs = max(stallTimeoutMs, expectedPlaybackMs + stallTimeoutMs)
                .coerceAtMost(maxDrainHardBoundMs.coerceAtLeast(stallTimeoutMs))
            val drainStartMs = clockMs()
            var lastProgressMs = drainStartMs

            synchronized(lock) {
                if (_state.value.activeGeneration != generationId) return@withLock false
                lastObservedHead = driver.getPlaybackHeadFrames(generationId)
                updateStateAndNotify {
                    it.copy(
                        serverTurnComplete = true,
                        isDrainingHardware = true,
                        isPlaybackDrained = false,
                        drainDeadlineMs = boundedDeadlineMs,
                        hardwarePlaybackHeadFrames = lastObservedHead,
                        paddingFramesWrittenToHardware = postPadDiag.paddingFramesWritten,
                        paddingBytesWrittenToHardware = postPadDiag.paddingBytesWritten,
                        lastHardwareProgressAtMs = drainStartMs,
                        statusDetail = "Draining hardware playback buffer (${generationId.wireId}): head=$lastObservedHead / target=$totalFramesTarget (padding=${postPadDiag.paddingFramesWritten} frames)."
                    )
                }
            }

            var stalledPolls = 0
            while (true) {
                if (synchronized(lock) { _state.value.activeGeneration != generationId }) {
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
                                paddingFramesWrittenToHardware = diagNow.paddingFramesWritten,
                                paddingBytesWrittenToHardware = diagNow.paddingBytesWritten,
                                lastHardwareProgressAtMs = lastProgressMs,
                                underrunCount = diagNow.underrunCount,
                                trackState = diagNow.trackState,
                                playState = diagNow.playState
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

                if ((nowMs - drainStartMs) >= boundedDeadlineMs || (nowMs - lastProgressMs) >= stallTimeoutMs || stalledPolls >= maxDrainPollIterations) {
                    val reason = "HARDWARE_DRAIN_INCOMPLETE: head=$headFrames / target=$totalFramesTarget."
                    emitFailureAndFlushIfStillActive(generationId, reason, "HARDWARE_DRAIN_INCOMPLETE", onEvent)
                    return@withLock false
                }

                drainPollDelayRunner(10L)
            }
            false
        }
    }

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
                is PlaybackCallbackEvent.AudioChunkReceived -> {}
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
                            statusDetail = "PLAYBACK_DRAINED (${activeGen.wireId}): ${event.totalBytesAccepted} bytes audibly drained."
                        )
                    }
                }
                is PlaybackCallbackEvent.PlaybackFailed -> {
                    shouldCloseDriverGen = activeGen
                    pumpJobs.remove(activeGen)
                    wakeSignals.remove(activeGen)?.cancel()
                    chunkQueues.clear()
                    inputClosedGenerations.clear()
                    chunkSequenceCounters.clear()
                    updateStateAndNotify { current ->
                        current.copy(
                            activeGeneration = null,
                            lastCompletedGeneration = activeGen,
                            isTrackOpen = false,
                            isPlaying = false,
                            isDrainingHardware = false,
                            isPlaybackDrained = false,
                            activeTrackInstanceId = 0L,
                            pendingQueuedBytes = 0L,
                            queuedBytes = 0L,
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

    private fun emitFailureAndFlushIfStillActive(
        generationId: PlaybackGenerationId,
        reason: String,
        errorCode: String,
        onEvent: (PlaybackCallbackEvent) -> Unit
    ) {
        onEvent(
            PlaybackCallbackEvent.PlaybackFailed(
                generationId = generationId,
                reason = reason,
                errorCode = errorCode
            )
        )
        val stillActive = synchronized(lock) { _state.value.activeGeneration == generationId }
        if (stillActive) {
            stopAndFlush(
                reason = reason,
                error = reason,
                expectedGenerationId = generationId
            )
        }
    }

    fun stopAndFlush(
        reason: String,
        error: String? = null,
        expectedGenerationId: PlaybackGenerationId? = null
    ) {
        val prevGen: PlaybackGenerationId?
        val signalsToCancel: List<CompletableDeferred<Unit>>
        synchronized(lock) {
            val activeGen = _state.value.activeGeneration
            if (expectedGenerationId != null && activeGen != expectedGenerationId) {
                updateStateAndNotify { current ->
                    current.copy(stalePlaybackCallbacksDropped = current.stalePlaybackCallbacksDropped + 1)
                }
                return
            }
            prevGen = activeGen
            pumpJobs.values.forEach { it.cancel() }
            pumpJobs.clear()
            signalsToCancel = wakeSignals.values.toList()
            wakeSignals.clear()
            chunkQueues.clear()
            inputClosedGenerations.clear()
            chunkSequenceCounters.clear()

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
                    queuedBytes = 0L,
                    lastError = error ?: current.lastError,
                    hardwareReleaseCount = if (wasActive) current.hardwareReleaseCount + 1 else current.hardwareReleaseCount,
                    statusDetail = "Playback stopped & flushed (${prevGen?.wireId ?: "none"}): $reason"
                )
            }
        }
        signalsToCancel.forEach { it.cancel() }
        driver.stopAndFlushTrack(prevGen ?: expectedGenerationId)
    }
}

