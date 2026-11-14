# OmniVoice Rebuild — Phase Progress (`docs/progress.md`)

## Current Phase Status
- **Phase 01 (Conversation Shell & Contract Foundation)**: **COMPLETED & VERIFIED**
- **Phase 02 (Microphone Setup, Permission Flow & Single-Owner Local Capture Self-Test)**: **COMPLETED & VERIFIED**
- **Phase 03 (Secure Gemini Live Connection Adapter, Offline Detection & Honest Error Classification)**: **COMPLETED & VERIFIED**
- **Phase 04 (One-Turn Voice Coordinator, First-Turn Lifecycle Readiness Gate, Non-Leaking System Instruction & Complete Audio Playback)**: **TARGETED FIRST-TURN LIFECYCLE REPAIR COMPLETED & AUTOMATED TESTS PASSING (Physical-Phone Verification Status: `NOT_TESTED`)**
- **Source / Build ID**: `ov-rebuild-p03-20261111`
- **Application ID**: `com.kazim.omnivoice.rebuild`
- **Version**: `0.3.0-phase03` (`versionCode = 3`)

---

## Phase 04 Targeted Repair Summary — First Turn Is Silent, Second Start Works

### 1. Evidence-Based Root Causes Identified
1. **Unattached Receiver Collector Race on Cold Launch (`FirebaseAiLiveTransportDriver.connectAndHandshake`)**:
   - In `FirebaseAiLiveTransportDriver.connectAndHandshake()`, `driverScope.launch { session.receive().collect { ... } }` was launched asynchronously on `Dispatchers.IO`, while `connectAndHandshake()` immediately emitted `SetupAccepted` and returned `LiveHandshakeResult.Accepted` before `.collect` had subscribed to `session.receive()`.
   - On a cold launch (Turn 1), microphone frames began streaming before the server-event/audio collector was attached. On the second Start press (Turn 2), `reusingExistingSession == true` reused the already-established `LiveSession` whose receiver collector had already finished attaching during Turn 1.
2. **First Microphone Buffers Discarded at `CONNECTING -> LISTENING` and `LISTENING -> PROCESSING` Boundaries (`ConversationCoordinator.kt`)**:
   - In `startVoiceTurn()`, `audioCapture.startCapture(...)` was invoked while `_uiState.value.state` was still `ConversationState.CONNECTING` before `_uiState` transitioned to `LISTENING`, causing initial microphone buffers from the `AudioRecord` thread to be dropped by `if (_uiState.value.state != ConversationState.LISTENING) return`.
   - In `finishSpokenTurnAndAwaitResponse()`, `stopTurnMicrophoneLocked()` immediately cancelled `activeTurnMicSendJobs` and `sendJob` aborted whenever `_uiState.value.state != ConversationState.LISTENING`, discarding already-captured `LISTENING` speech frames waiting on `Dispatchers.IO` before `signalEndOfSpokenTurn()` ran.
3. **Automatic End-of-Turn Threshold & Paced Trailing Silence (`ConversationCoordinator.kt` & `FirebaseAiLiveTransportDriver.kt`)**:
   - `AudioSource.VOICE_RECOGNITION` disables automatic gain control (AGC) on physical phones, producing normal speech RMS around `0.010f..0.017f`. While that passed `AudioCapture`'s `0.008f` non-silent check, `ConversationCoordinator.speechRmsThreshold` was `0.018f`, preventing automatic end-of-turn from firing on Turn 1 unless the user pressed Start a second time or spoke unusually loudly.
   - `FirebaseAiLiveTransportDriver.sendEndOfSpokenTurn()` sent 4 silence chunks with zero inter-chunk pacing (`0ms` elapsed wall-clock time), which could fall short of server VAD's `700ms` silence window on the very first turn.
4. **Unprotected Start Button & Cold-Launch Permission Sync (`ConversationShellViewModel.kt`, `ConversationCoordinator.kt`, `MainActivity.kt`)**:
   - Pressing Start while `CONNECTING`, `LISTENING`, `PROCESSING`, or `SPEAKING` previously cancelled `activeVoiceTurnJob` instead of ignoring/debouncing the duplicate Start press.
   - `MainActivity` initialized `AudioCapture` with `MicPermissionState.NOT_REQUESTED` even when Android OS had already granted `RECORD_AUDIO`.

### 2. Implemented First-Turn Lifecycle Repairs
- **Collector Attached Before `SETUP_ACCEPTED` and Before Any Audio Frame**:
  - `FirebaseAiLiveTransportDriver.connectAndHandshake()` now awaits `collectorReady` (`CompletableDeferred<Unit>()` completed inside `session.receive().onStart { ... }`) with a bounded timeout **before** emitting `SetupAccepted` and **before** returning `LiveHandshakeResult.Accepted`.
  - `GeminiLiveConnectionAdapter` exposes `isSessionReadyForAudio()` and verifies `isReceiverCollectorAttached` and `onServerEventListener != null` before any audio frame is sent.
- **Strict Readiness Order & Asynchronous Wait with Bounded Timeout**:
  - `ConversationCoordinator.startVoiceTurn()` attaches listeners first, awaits `SETUP_ACCEPTED` and `isSessionReadyForAudio()` asynchronously with bounded `withTimeoutOrNull(connectionTimeoutMs)` (failing honestly with `CONNECTION_SETUP_TIMEOUT` on timeout), and transitions through:
    - Cold launch: `IDLE -> CONNECTING -> SETUP_ACCEPTED -> LISTENING -> PROCESSING -> SPEAKING -> READY`
    - Warm session: `SETUP_ACCEPTED -> LISTENING -> PROCESSING -> SPEAKING -> READY`
  - Structured lifecycle trace (`firstTurnLifecycleTrace`) records:
    `BUTTON_PRESS -> RECEIVER_COLLECTOR_ATTACHED -> LIVE_CONNECTION -> SETUP_ACCEPTED -> AUDIO_RECORD_START -> AUDIO_SENT -> ACTIVITY_END_OF_TURN -> SERVER_RESPONSE -> PLAYBACK`.
- **Zero Discarded Microphone Buffers & Flush-Before-End-of-Turn**:
  - `_uiState.value.state` transitions to `ConversationState.LISTENING` inside `synchronized(this)` right before `audioCapture.startCapture(...)` is called so even a synchronous first buffer is captured and sent.
  - Transitioning from `LISTENING` to `PROCESSING` stops hardware capture (`cancelQueuedMicSends = false`) without cancelling queued `LISTENING` send jobs, and awaits (`job.join()`) all captured `LISTENING` frames before calling `liveAdapter.signalEndOfSpokenTurn()`.
- **Calibrated Speech Detection, Paced Trailing Silence & Start Debouncing**:
  - Calibrated `speechRmsThreshold = 0.010f`, `minSpeechChunksForAutoEnd = 5`, and `silenceChunksForAutoEnd = 18`, and paced `sendEndOfSpokenTurn()` across 960ms of 16kHz PCM silence (6 x 160ms chunks with `delay(20L)`).
  - Debounced additional Start presses during `CONNECTING`, `LISTENING`, `PROCESSING`, or `SPEAKING` (`debouncedStartCount`) in both `ConversationShellViewModel.onStartClicked()` and `ConversationCoordinator.startVoiceTurn()`.

