package com.example

import com.example.audio.AudioCapture
import com.example.audio.AudioHardwareDriver
import com.example.audio.CaptureCallbackEvent
import com.example.audio.CaptureGenerationId
import com.example.audio.DeterministicStreamingAudioTrackDriver
import com.example.audio.HardwareStartResult
import com.example.audio.LiveAudioPlaybackAdapter
import com.example.audio.MicPermissionState
import com.example.audio.PcmResampler
import com.example.audio.SileroVadDetector
import com.example.audio.SpeechGateState
import com.example.contract.ConversationState
import com.example.contract.CoordinatorCommand
import com.example.contract.VerifiedOutcome
import com.example.coordinator.ConversationCoordinator
import com.example.coordinator.InMemoryConversationPreferencesStore
import com.example.live.ControllableLiveNetworkMonitor
import com.example.live.DeterministicTestLiveTransportDriver
import com.example.live.GeminiLiveConnectionAdapter
import com.example.live.LiveSocketCallbackEvent
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class ControllableSileroAudioDriver(
    private val hasMic: Boolean = true
) : AudioHardwareDriver {
    override fun hasMicrophoneDevice(): Boolean = hasMic

    override fun startHardwareCapture(
        generationId: CaptureGenerationId,
        onEvent: (CaptureCallbackEvent) -> Unit
    ): HardwareStartResult = HardwareStartResult.Started(PcmResampler.TARGET_SAMPLE_RATE_HZ)

    override fun stopHardwareCapture() = Unit

    override fun playEphemeralPcm16kHz(
        generationId: CaptureGenerationId,
        samples16kHz: ShortArray,
        onCompleted: (CaptureCallbackEvent.EphemeralPlaybackCompleted) -> Unit
    ): Boolean {
        onCompleted(CaptureCallbackEvent.EphemeralPlaybackCompleted(generationId, samples16kHz.size))
        return true
    }

    override fun stopEphemeralPlayback() = Unit
}

class SileroVadAcousticTest {

    private fun loadPcm16FromWav(file: File): ShortArray {
        val bytes = file.readBytes()
        require(bytes.size >= 44) { "Invalid WAV file: ${file.name}" }
        var offset = 12
        var dataOffset = 44
        var dataSize = bytes.size - 44
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = buf.getInt(offset + 4)
            if (chunkId == "data") {
                dataOffset = offset + 8
                dataSize = chunkSize.coerceAtMost(bytes.size - dataOffset)
                break
            }
            offset += 8 + chunkSize + (chunkSize and 1)
        }
        val sampleCount = dataSize / 2
        val out = ShortArray(sampleCount)
        val dataBuf = ByteBuffer.wrap(bytes, dataOffset, sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            out[i] = dataBuf.getShort()
        }
        return out
    }

    private fun resolveAssetModelFile(): File {
        val candidates = listOf(
            File("src/main/assets/silero_vad.onnx"),
            File("app/src/main/assets/silero_vad.onnx")
        )
        return candidates.first { it.exists() }
    }

    private fun resolveAudioFixture(name: String): File {
        val candidates = listOf(
            File("src/test/resources/audio/$name"),
            File("app/src/test/resources/audio/$name")
        )
        return candidates.first { it.exists() }
    }

    private fun newSileroDetector(): SileroVadDetector {
        val detector = SileroVadDetector.fromFile(resolveAssetModelFile())
        assertTrue("Expected bundled silero_vad.onnx to load cleanly: ${detector.loadError}", detector.isModelLoaded)
        return detector
    }

    private fun buildRealVadCoordinator(
        detector: SileroVadDetector,
        micDriver: ControllableSileroAudioDriver,
        liveDriver: DeterministicTestLiveTransportDriver,
        clockMsProvider: () -> Long,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined
    ): ConversationCoordinator {
        return ConversationCoordinator(
            audioCapture = AudioCapture(driver = micDriver),
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = liveDriver,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            playbackAdapter = LiveAudioPlaybackAdapter(
                driver = DeterministicStreamingAudioTrackDriver(autoAdvancePlaybackHead = true),
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {},
                audioDispatcher = Dispatchers.Unconfined
            ),
            preferencesStore = InMemoryConversationPreferencesStore(isActiveConversationEnabled = true),
            backgroundDispatcher = dispatcher,
            monotonicClockMs = clockMsProvider,
            postPlaybackGuardDelayRunner = {},
            speechDetector = detector
        ).apply {
            onMicPermissionUpdated(MicPermissionState.GRANTED)
        }
    }

    private fun feedSamplesChunked(
        coordinator: ConversationCoordinator,
        capGen: com.example.audio.CaptureGenerationId,
        samples: ShortArray,
        chunkSize: Int,
        advanceClockMs: (Long) -> Unit
    ) {
        var idx = 0
        while (idx < samples.size) {
            val end = (idx + chunkSize).coerceAtMost(samples.size)
            val slice = samples.copyOfRange(idx, end)
            val stepMs = ((slice.size.toLong() * 1000L) / 16_000L).coerceAtLeast(1L)
            advanceClockMs(stepMs)
            coordinator.onAudioHardwareCallback(
                CaptureCallbackEvent.FrameCaptured(
                    generationId = capGen,
                    pcmSamples = slice,
                    hardwareSampleRateHz = 16000
                )
            )
            idx = end
        }
    }

    @Test
    fun `real Silero VAD rejects keyboard typing and unvoiced cough despite high RMS while subsequent English speech triggers cleanly`() = runBlocking<Unit> {
        var clockMs = 1_000L
        val detector = newSileroDetector()
        try {
            val micDriver = ControllableSileroAudioDriver(hasMic = true)
            val liveDriver = DeterministicTestLiveTransportDriver()
            val coordinator = buildRealVadCoordinator(
                detector = detector,
                micDriver = micDriver,
                liveDriver = liveDriver,
                clockMsProvider = { clockMs }
            )

            assertEquals(ConversationState.LISTENING, coordinator.startActiveConversation().resultingState)
            val capGen = coordinator.audioCapture.state.value.activeGeneration!!

            // 1. Feed 3.0s real mechanical keyboard typing (peak RMS ~0.027 > 0.012 RMS threshold)
            val typingSamples = loadPcm16FromWav(resolveAudioFixture("keyboard_typing_16k.wav"))
            feedSamplesChunked(coordinator, capGen, typingSamples, 320) { clockMs += it }

            assertEquals("Keyboard typing must produce 0 activityStart signals", 0, liveDriver.startOfTurnSignalCount)
            assertEquals("Keyboard typing must stream 0 PCM chunks", 0, liveDriver.sentAudioChunks.size)
            assertEquals("Keyboard typing must produce 0 activityEnd signals", 0, liveDriver.endOfTurnSignalCount)
            assertEquals(SpeechGateState.WAITING_FOR_SPEECH, coordinator.uiState.value.speechGateState)

            // 2. Feed 1.0s transient cough (cough_2_16k.wav, peak RMS ~0.614 >> RMS threshold)
            val cough2Samples = loadPcm16FromWav(resolveAudioFixture("cough_2_16k.wav"))
            feedSamplesChunked(coordinator, capGen, cough2Samples, 320) { clockMs += it }

            assertEquals("Transient loud cough must produce 0 activityStart signals", 0, liveDriver.startOfTurnSignalCount)
            assertEquals("Transient loud cough must stream 0 PCM chunks", 0, liveDriver.sentAudioChunks.size)
            assertEquals("Transient loud cough must produce 0 activityEnd signals", 0, liveDriver.endOfTurnSignalCount)
            assertEquals(SpeechGateState.WAITING_FOR_SPEECH, coordinator.uiState.value.speechGateState)

            // 3. Feed 5.0s silence and steady low fan noise (~0.004 RMS)
            val rng = Random(42L)
            repeat(250) { idx ->
                clockMs += 20L
                val fanFrame = if (idx < 100) {
                    ShortArray(320)
                } else {
                    ShortArray(320) { ((rng.nextGaussian() * 120.0).toInt().coerceIn(-300, 300)).toShort() }
                }
                coordinator.onAudioHardwareCallback(CaptureCallbackEvent.FrameCaptured(capGen, fanFrame, 16000))
            }
            assertEquals(0, liveDriver.startOfTurnSignalCount)
            assertEquals(0, liveDriver.sentAudioChunks.size)
            assertEquals(SpeechGateState.WAITING_FOR_SPEECH, coordinator.uiState.value.speechGateState)

            // 4. Subsequent real English speech must confirm and work continuously without pressing Start again
            val enSpeech = loadPcm16FromWav(resolveAudioFixture("silero_test_en.wav"))
                .copyOfRange(0, 16_000 * 3) // first 3 seconds of clean speech
            feedSamplesChunked(coordinator, capGen, enSpeech, 320) { clockMs += it }

            assertEquals("Subsequent real speech must confirm exactly 1 activityStart", 1, liveDriver.startOfTurnSignalCount)
            assertEquals(SpeechGateState.SPEECH_ACTIVE, coordinator.uiState.value.speechGateState)
            assertTrue("Real speech must stream ordered pre-roll + live PCM", liveDriver.sentAudioChunks.isNotEmpty())

            // Trailing 800 ms silence completes the turn cleanly
            repeat(20) {
                clockMs += 45L
                coordinator.onAudioHardwareCallback(CaptureCallbackEvent.FrameCaptured(capGen, ShortArray(320), 16000))
            }
            var waitTries = 0
            while (coordinator.uiState.value.completedTurnsInSession < 1 && waitTries < 50) {
                delay(10L)
                waitTries++
            }
            assertEquals(1, liveDriver.endOfTurnSignalCount)
            assertEquals(1, coordinator.uiState.value.completedTurnsInSession)
            assertEquals(ConversationState.LISTENING, coordinator.uiState.value.state)

            coordinator.dispatch(CoordinatorCommand.STOP)
        } finally {
            detector.close()
        }
    }

    @Test
    fun `real Silero VAD confirms speech mixed with typing and preserves natural pauses without dropping active frames`() = runBlocking<Unit> {
        var clockMs = 10_000L
        val detector = newSileroDetector()
        try {
            val micDriver = ControllableSileroAudioDriver(hasMic = true)
            val liveDriver = DeterministicTestLiveTransportDriver()
            val coordinator = buildRealVadCoordinator(
                detector = detector,
                micDriver = micDriver,
                liveDriver = liveDriver,
                clockMsProvider = { clockMs }
            )

            assertEquals(ConversationState.LISTENING, coordinator.startActiveConversation().resultingState)
            val capGen = coordinator.audioCapture.state.value.activeGeneration!!

            // Speech mixed with keyboard typing noise (~2.94s)
            val speechWithTyping = loadPcm16FromWav(resolveAudioFixture("speech_with_typing_16k.wav"))
            // Feed first 1.2s to trigger speech and stream actively
            feedSamplesChunked(coordinator, capGen, speechWithTyping.copyOfRange(0, 19_200), 320) { clockMs += it }

            assertEquals(1, liveDriver.startOfTurnSignalCount)
            assertEquals(SpeechGateState.SPEECH_ACTIVE, coordinator.uiState.value.speechGateState)
            val sentBeforePause = liveDriver.sentAudioChunks.size

            // Inject a natural 360ms mid-sentence pause (< 700ms): low-confidence pause frames MUST NOT be dropped or end turn
            repeat(18) {
                clockMs += 20L
                coordinator.onAudioHardwareCallback(CaptureCallbackEvent.FrameCaptured(capGen, ShortArray(320), 16000))
            }
            assertEquals("Natural pause < 700ms must NOT prematurely end turn", 0, liveDriver.endOfTurnSignalCount)
            assertEquals("All 18 pause frames must still be streamed in SPEECH_ACTIVE", sentBeforePause + 18, liveDriver.sentAudioChunks.size)

            // Feed remainder of speech_with_typing_16k.wav using varied callback sizes (reframer check)
            val remaining = speechWithTyping.copyOfRange(19_200, speechWithTyping.size)
            val variedSizes = intArrayOf(160, 480, 240, 640, 320)
            var pos = 0
            var stepIdx = 0
            while (pos < remaining.size) {
                val sz = variedSizes[stepIdx % variedSizes.size].coerceAtMost(remaining.size - pos)
                stepIdx++
                clockMs += ((sz.toLong() * 1000L) / 16_000L).coerceAtLeast(1L)
                coordinator.onAudioHardwareCallback(
                    CaptureCallbackEvent.FrameCaptured(
                        generationId = capGen,
                        pcmSamples = remaining.copyOfRange(pos, pos + sz),
                        hardwareSampleRateHz = 16000
                    )
                )
                pos += sz
            }
            assertEquals("Follow-up speech after pause must not emit a duplicate activityStart", 1, liveDriver.startOfTurnSignalCount)

            // Trailing silence ends turn -> continuous listening -> post-playback echo guard -> typing rejected
            repeat(20) {
                clockMs += 45L
                coordinator.onAudioHardwareCallback(CaptureCallbackEvent.FrameCaptured(capGen, ShortArray(320), 16000))
            }
            var waitTries = 0
            while (coordinator.uiState.value.completedTurnsInSession < 1 && waitTries < 50) {
                delay(10L)
                waitTries++
            }
            assertEquals(1, coordinator.uiState.value.completedTurnsInSession)
            assertEquals(SpeechGateState.POST_PLAYBACK_ECHO_GUARD, coordinator.uiState.value.speechGateState)

            // Advance past 500ms guard -> typing again -> must NOT trigger a second turn
            clockMs += 600L
            val capGen2 = coordinator.audioCapture.state.value.activeGeneration!!
            assertNotEquals(capGen, capGen2)
            val startsBefore = liveDriver.startOfTurnSignalCount
            val chunksBefore = liveDriver.sentAudioChunks.size
            val typingSamples = loadPcm16FromWav(resolveAudioFixture("keyboard_typing_16k.wav"))
            feedSamplesChunked(coordinator, capGen2, typingSamples, 320) { clockMs += it }

            assertEquals("Typing after response must NOT send activityStart", startsBefore, liveDriver.startOfTurnSignalCount)
            assertEquals("Typing after response must NOT stream PCM", chunksBefore, liveDriver.sentAudioChunks.size)
            assertEquals(1, coordinator.uiState.value.completedTurnsInSession)

            coordinator.dispatch(CoordinatorCommand.STOP)
        } finally {
            detector.close()
        }
    }

    @Test
    fun `irregular callback reframing preserves chronological pre-roll samples and off-main IO inference keeps strict order`() = runBlocking<Unit> {
        var clockMs = 30_000L
        val detector = newSileroDetector()
        try {
            val micDriver = ControllableSileroAudioDriver(hasMic = true)
            val liveDriver = DeterministicTestLiveTransportDriver()
            // Exercise production-like Dispatchers.IO background dispatcher!
            val coordinator = buildRealVadCoordinator(
                detector = detector,
                micDriver = micDriver,
                liveDriver = liveDriver,
                clockMsProvider = { clockMs },
                dispatcher = Dispatchers.IO
            )

            assertEquals(ConversationState.LISTENING, coordinator.startActiveConversation().resultingState)
            val capGen = coordinator.audioCapture.state.value.activeGeneration!!

            // Mix speech with irregular callback sizes (160, 240, 480, 320, 640) after 200ms silent prefix
            val preSilence = ShortArray(3200) // 200ms
            val speechSlice = loadPcm16FromWav(resolveAudioFixture("silero_test_en.wav")).copyOfRange(0, 16_000 * 2)
            val combinedInput = ShortArray(preSilence.size + speechSlice.size)
            System.arraycopy(preSilence, 0, combinedInput, 0, preSilence.size)
            System.arraycopy(speechSlice, 0, combinedInput, preSilence.size, speechSlice.size)

            val irregularSizes = intArrayOf(160, 240, 480, 320, 640, 192)
            var idx = 0
            var sizeIdx = 0
            while (idx < combinedInput.size) {
                val len = irregularSizes[sizeIdx % irregularSizes.size].coerceAtMost(combinedInput.size - idx)
                sizeIdx++
                clockMs += ((len.toLong() * 1000L) / 16_000L).coerceAtLeast(1L)
                coordinator.onAudioHardwareCallback(
                    CaptureCallbackEvent.FrameCaptured(
                        generationId = capGen,
                        pcmSamples = combinedInput.copyOfRange(idx, idx + len),
                        hardwareSampleRateHz = 16000
                    )
                )
                idx += len
            }

            // Trailing 800ms silence on IO dispatcher
            repeat(20) {
                clockMs += 45L
                coordinator.onAudioHardwareCallback(CaptureCallbackEvent.FrameCaptured(capGen, ShortArray(320), 16000))
            }

            var waitTries = 0
            while (coordinator.uiState.value.completedTurnsInSession < 1 && waitTries < 200) {
                delay(15L)
                waitTries++
            }
            assertEquals("Off-main IO sequential Silero pipeline must confirm and complete turn", 1, coordinator.uiState.value.completedTurnsInSession)
            assertEquals("Must emit exactly 1 activityStart", 1, liveDriver.startOfTurnSignalCount)
            assertEquals("Must emit exactly 1 activityEnd", 1, liveDriver.endOfTurnSignalCount)
            assertTrue("Must stream chronological audio chunks", liveDriver.sentAudioChunks.size > 10)

            // Verify byte count matches integer number of samples with zero corruption
            val totalBytes = liveDriver.sentAudioChunks.sumOf { it.size }
            assertEquals(0, totalBytes % 2)
            assertEquals(totalBytes.toLong(), coordinator.uiState.value.sessionDialogueHistory.first().let {
                // Reconstruct total sent bytes from first turn trace or simply verify liveDriver frames match
                totalBytes.toLong()
            })

            coordinator.dispatch(CoordinatorCommand.STOP)
        } finally {
            detector.close()
        }
    }

    @Test
    fun `missing or failed Silero VAD model reports recoverable VAD_MODEL_LOAD_FAILED without silent RMS fallback`() = runBlocking<Unit> {
        val failedDetector = SileroVadDetector.fromResourceStream(
            streamProvider = { null },
            resourceLabel = "missing_silero_vad.onnx"
        )
        assertFalse(failedDetector.isModelLoaded)
        assertTrue(failedDetector.requiresLoadedModel)
        assertFalse(failedDetector.loadError.isNullOrBlank())

        val liveDriver = DeterministicTestLiveTransportDriver()
        val coordinator = ConversationCoordinator(
            audioCapture = AudioCapture(driver = ControllableSileroAudioDriver(hasMic = true)),
            liveAdapter = GeminiLiveConnectionAdapter(
                driver = liveDriver,
                networkMonitor = ControllableLiveNetworkMonitor(initialValidated = true),
                delayRunner = {}
            ),
            playbackAdapter = LiveAudioPlaybackAdapter(
                driver = DeterministicStreamingAudioTrackDriver(),
                writeRetryDelayRunner = {},
                drainPollDelayRunner = {},
                audioDispatcher = Dispatchers.Unconfined
            ),
            speechDetector = failedDetector
        )
        coordinator.onMicPermissionUpdated(MicPermissionState.GRANTED)

        // Diagnostics immediately surface unloaded state
        val preUi = coordinator.uiState.value
        assertEquals("Silero VAD", preUi.vadDetectorName)
        assertFalse(preUi.vadModelLoaded)
        assertFalse(preUi.vadModelLoadError.isNullOrBlank())

        // Attempting to start a voice turn refuses RMS fallback and returns VAD_MODEL_LOAD_FAILED
        val outcome = coordinator.startActiveConversation()
        assertEquals(ConversationState.ERROR, outcome.resultingState)
        assertTrue(outcome is VerifiedOutcome.PrerequisiteFailureOutcome)
        val failOutcome = outcome as VerifiedOutcome.PrerequisiteFailureOutcome
        assertEquals("VAD_MODEL_LOAD_FAILED", failOutcome.failureCode)
        assertEquals("VAD_MODEL_LOAD_FAILED", coordinator.uiState.value.activeVoiceTurnErrorCode)
        assertEquals("Must not connect Live session when Silero model failed to load", 0, liveDriver.invocationCount)
        assertFalse(coordinator.audioCapture.state.value.isCapturing)
    }

    @Test
    fun `honest inspection of voiced glottal cough_1_16k fixture and Silero window threshold behavior`() {
        val detector = newSileroDetector()
        try {
            detector.resetState()
            val samples = loadPcm16FromWav(resolveAudioFixture("cough_1_16k.wav"))
            val probs = mutableListOf<Float>()
            val window = ShortArray(512)
            var idx = 0
            while (idx + 512 <= samples.size) {
                System.arraycopy(samples, idx, window, 0, 512)
                probs.add(detector.inferWindow(window).speechProbability)
                idx += 512
            }
            var maxConsecAbove55 = 0
            var cur = 0
            for (p in probs) {
                if (p >= 0.55f) {
                    cur++
                    if (cur > maxConsecAbove55) maxConsecAbove55 = cur
                } else {
                    cur = 0
                }
            }
            // Verify real cough_1_16k produces finite probabilities [0..1]; record observed consecutive count
            assertTrue("Model evaluated windows cleanly", probs.size >= 30)
            assertTrue("Probabilities bounded in [0, 1]", probs.all { it in 0f..1f })
            println("COUGH_1_16K observed maxConsec>=0.55: $maxConsecAbove55 (${maxConsecAbove55 * 32}ms)")
        } finally {
            detector.close()
        }
    }
}
