# OmniVoice Rebuild — Phase Progress (`docs/progress.md`)

## Current Phase Status
- **Phase 01 (Conversation Shell & Contract Foundation)**: **COMPLETED & VERIFIED**
- **Phase 02 (Microphone Setup, Permission Flow & Single-Owner Local Capture Self-Test)**: **COMPLETED & VERIFIED**
- **Phase 03 (Secure Gemini Live Connection Adapter, Offline Detection & Honest Error Classification)**: **COMPLETED & VERIFIED**
- **Phase 04 (One-Turn Voice Coordinator, First-Turn Lifecycle Readiness Gate, Non-Leaking System Instruction & Complete Audio Playback)**: **COMPLETED & VERIFIED**
- **Phase 05 (Foreground Active Conversation Coordinator, Serialized State Machine, Auto-Restore Capture, Inactivity Session Closure & Lifecycle Restoration)**: **COMPLETED & AUTOMATED TESTS PASSING (Physical-Phone Verification Status: `NOT_TESTED`)**
- **Source / Build ID**: `ov-rebuild-p03-20261111`
- **Application ID**: `com.kazim.omnivoice.rebuild`
- **Version**: `0.3.0-phase03` (`versionCode = 3`)

---

## Phase 05 Summary — Foreground Active Conversation Coordinator

### 1. Serialized Foreground Active Conversation State Machine
- Extended `ConversationCoordinator` to coordinate a foreground active conversation through one serialized state machine with explicit states (`ActiveConversationPhase`: `IDLE`, `PREPARING`, `LISTENING`, `THINKING`, `SPEAKING`, `PAUSED`, `STOPPED`, `ERROR`, mapped alongside `ConversationState`).
- Every socket/turn callback is validated against both `SessionId` and `TurnGenerationId` (`onSessionAndTurnScopedEvent` / `onTurnScopedServerEvent`), plus deduplicated against `handledCompletionTurnGenIds` and `inFlightCompletionTurnGenIds`. Stale or overlapping completion callbacks are deterministically dropped (`staleTurnCallbacksDropped`) and can never close, interrupt, or resume a newer turn.

### 2. Automatic Capture Restoration & Actual Resource Release
- After actual speech completion (`SPEAKING` hardware playback fully drained and verified), `ConversationCoordinator` records the turn in `sessionDialogueHistory` and, while `isActiveConversationEnabled` is true and neither Pause nor Stop was requested, automatically calls `autoRestoreCaptureForNextTurnLocked()` (`PREPARING -> LISTENING`).
- Before starting any initial or replacement capture, `releaseResourcesBeforeReplacementCaptureLocked()` explicitly stops/releases `AudioCapture` and `LiveAudioPlaybackAdapter` (`resourceReleaseBeforeCaptureCount`), ensuring clean hardware state across 20+ continuous turns without any arbitrary 6-turn cutoff.

### 3. First-Use Consent, Remembered Auto-Resume Preference & Inactivity Session Closure
- Persists user preferences via `ConversationPreferencesStore` (`SharedPreferencesConversationPreferencesStore` in Android runtime, `InMemoryConversationPreferencesStore` in JVM tests):
  - `isActiveConversationEnabled` (continuous multi-turn auto-restore after speech drain).
  - `autoResumeOnScreenReopen` (remembered preference to auto-resume when reopening the visible conversation screen after `hasCompletedFirstUseConsentAndConfig` and `RECORD_AUDIO` grant).
  - **Strict Guard**: `onConversationScreenVisible()` **never** overrides deliberate Pause/Mute (`isDeliberatelyPaused` / `PAUSED`) or deliberate Stop (`isDeliberatelyStopped` / `STOPPED`), incrementing `autoResumeBlockedByPauseOrStopCount`.
  - `inactivityTimeoutOption` (`30s`, `1m`, `2m`, `5m` visible selector chips): arms an inactivity timer during `LISTENING` that resets on speech frames and cleanly closes the idle session (`INACTIVITY_SESSION_CLOSED` -> `IDLE`) when silence exceeds the configured timeout.

### 4. Large Pause/Resume & Stop Controls, Half-Duplex Policy & Bounded Transcript
- Added large (`64.dp` min height) **Pause**, **Resume**, and **Stop** controls on the Command Deck along with visible chips for **Auto-Continue Turns**, **Auto-Resume on Reopen**, and **Inactivity Session Timeout (`30s`, `1m`, `2m`, `5m`)**.
- Kept the starting half-duplex policy explicit (`duplexPolicyLabel = "HALF-DUPLEX EXPLICIT (Mic disabled during THINKING & SPEAKING; spoken barge-in not claimed)"`, `isSpokenBargeInClaimed = false`).
- Bounded `sessionDialogueHistory` to `8` turns and `transcript` to `24` entries (`maxSessionDialogueTurns = 8`, `maxTranscriptEntries = 24`).
- Preserved the single ViewModel-scoped `ConversationCoordinator` across orientation changes (`onActivityStopped(isChangingConfigurations = true)` -> `onConfigurationChangePreserved()`).

### 5. Phase 05 Targeted Repair — `HARDWARE_DRAIN_INCOMPLETE` (`head=0`) & Stale Turn Ownership
- **AudioTrack Drain & Play-State Ownership (`LiveAudioPlaybackAdapter` / `AndroidStreamingAudioTrackDriver`)**:
  - Removed the premature `track.stop()` call from `finishStreamForDrain()` (which previously put `AudioTrack` into `PLAYSTATE_STOPPED` and reset `playbackHeadPosition` to `0` before drain polling began). For short sub-buffer clips, pads trailing zero silence frames without inflating `totalFramesWrittenToHardware`, keeping the track in `PLAYSTATE_PLAYING` until `playbackHeadPosition` reaches `totalFramesWrittenToHardware`.
  - Runs all `AudioTrack` construction, non-blocking writes, zero/partial write retries, single clean track recreation on negative error codes (`ERROR`, `ERROR_BAD_VALUE`, `ERROR_INVALID_OPERATION`, `ERROR_DEAD_OBJECT`), and 32-bit wraparound-aware drain polling on `audioDispatcher` (`Dispatchers.IO`), never on the main thread.
  - Strictly separates Gemini-received frames (`framesReceivedFromServer`), AudioTrack-accepted frames (`totalFramesWrittenToHardware`), and played head frames (`hardwarePlaybackHeadFrames`), failing immediately with `AUDIO_TRACK_WRITE_FAILED` when `totalFramesWrittenToHardware == 0`.
  - Computes dynamic bounded drain deadline `max(5000ms, acceptedFrames * 1000 / sampleRateHz + 5000ms)` and only releases the `AudioTrack` after accepted frames have drained (or explicit stop/failure), never reusing a released or stopped `AudioTrack`.
- **Turn Ownership & Off-Main Job Joining (`ConversationCoordinator`)**:
  - Enforced strict `(SessionId, TurnId, TurnGenerationId, PlaybackGenerationId)` verification on `writeJob`, `drainJob`, `failActiveVoiceTurnLocked`, `stopAndFlush`, and `onTurnBoundPlaybackCallback`. Stale `ov01-turn-0004` errors/callbacks arriving during `ov01-turn-0005` are logged as `STALE_CALLBACK_DROPPED` and never mutate the active turn.
  - Stopped `failActiveVoiceTurnLocked` from calling `idGenerator.nextTurnId()` (preserving the exact failing turn ID on error banners/logs).
  - Added off-main-thread cancellation and joining of prior turn playback/drain/mic jobs (`backgroundDispatcher`) before starting replacement capture, ensuring next `LISTENING` capture begins only after hardware drain completes.

### 6. Phase 05 Repair — Duplicate/Stale LiveSession Handshake & Single-Flight Ownership
- **Single-Flight LiveSession Owner (`GeminiLiveConnectionAdapter` + `FirebaseAiLiveTransportDriver`)**:
  - Added single-flight `connectMutex` + `inFlightConnectDeferred` and explicit connection state machine (`DISCONNECTED -> CONNECTING -> SETUP_ACCEPTED -> ACTIVE -> RECONNECTING / FAILED`).
  - Added `ensureConnectedSession(sessionId)` (`reuseIfHealthy = true`) for `ConversationCoordinator.startVoiceTurn()` so `Start` reuses healthy `SETUP_ACCEPTED`/`ACTIVE` sessions across 20+ sequential turns, deduplicates concurrent Start/lifecycle/reconnect triggers, and never cancels an active handshake.
  - Moved old session teardown off the main thread (`closeAndAwaitSocket` / `disconnectAndReleaseAsync`): before a genuine reconnect, invalidates the old generation, cancels and joins its receive collector off-main, and closes each `LiveSession` at most once (`closedSessions` IdentityHashMap).
  - Fixed Kotlin Flow `.onStart`/`.catch`/`.onCompletion` ordering and race in `FirebaseAiLiveTransportDriver`: `.onCompletion` no longer fires duplicate `SocketClosedRemotely` after `.catch`, local closes/cancels in `closingGenerations` log `STALE_CALLBACK_DROPPED` instead of triggering `FAILED`, and stale generation callbacks never fail a newer generation.
  - Reset `retryCount = 0` on `SetupAccepted`, enforced bounded retries up to 3 attempts, classified server close reasons (`401/403 -> BAD_CREDENTIALS_OR_APP_CHECK`, `429 -> QUOTA_EXHAUSTED`, `400/404/1002/1003/1008 -> UNSUPPORTED_MODEL_OR_ENDPOINT`, validated-network IO -> `NETWORK_ERROR`, stale/local cancel -> ignored), and allowed clean manual recovery after exhausted retries without restarting the app/Activity.
- **ViewModel & NetworkMonitor Race Fixes (`ConversationShellViewModel`, `AndroidLiveNetworkMonitor`)**:
  - Synchronized job creation/guards with `jobLock` so rapid `Start`, `Resume`, recomposition, and `ON_RESUME` never cancel/overlap active handshakes.
  - Initialized `AndroidLiveNetworkMonitor.latestCallbackValidated` before registering `registerDefaultNetworkCallback` to stop startup `false -> true` callback flicker from triggering a competing auto-reconnect.

### 7. Phase 05 Targeted Repair — Turn 7 Stuck in `PROCESSING` (Test `05.2`)
- **Turn-Scoped Isolation (`TurnRuntimeState`)**:
  - Replaced loose coordinator turn fields with a freshly allocated `TurnRuntimeState` container per turn holding its own `SupervisorJob`, `CompletableDeferred<VerifiedOutcome>`, `TurnGenerationId`, VAD counters, captured/confirmed-sent/activity flags, playback job tracking, and cancellable `firstResponseWatchdogJob`. Stale callbacks from prior turns (such as late Turn 6 events during Turn 7) are deterministically dropped as `STALE_CALLBACK_DROPPED`.
  - Retained one healthy `LiveSession` generation and persistent `receive()` collector across 20+ sequential turns without reconnecting per turn.
- **11-Step Per-Turn Trace & Explicit End-of-Turn Protocol**:
  - Records redacted timestamps and generation IDs across `1_LISTENING_STARTED` through `11_NEXT_LISTENING_STARTED` (`TurnTraceStep`), computing both `lastCompletedTurnEarliestMissingStep` (`NONE` when complete) and `activeTurnEarliestMissingStep` (`7_SERVER_RESPONSE_START` when stuck waiting for Gemini).
  - Configured `FirebaseAiLiveTransportDriver` with `ActivityDetectionConfig.disabled()` plus explicit `sendStartActivityRealtime()` once per spoken turn and **exactly one** `sendStopActivityRealtime()` (`activityEnd`) after mic capture stops and queued audio sends flush, replacing the previous 6x zero-byte trailing-silence loop.
- **Pre-`PROCESSING` Validation & `PROCESSING` Watchdog Recovery**:
  - Blocks `PROCESSING` and `activityEnd` unless non-silent speech was captured (`NO_AUDIO_CAPTURED` / `SILENT_AUDIO_CAPTURED`), audio frames were confirmed sent (`NO_AUDIO_FRAMES_SENT`), internet is validated (`NETWORK_UNAVAILABLE`), and `activityEnd` submission succeeded (`END_OF_TURN_SEND_FAILED`).
  - Arms a cancellable turn-scoped first-response watchdog (`firstResponseTimeoutMs`) only after `activityEnd` succeeds; cancels immediately on `ServerAudioChunkReceived`, non-blank output `ServerTranscriptionReceived`, or `ServerTurnCompleted` so long valid playback never times out. On timeout, logs `FIRST_RESPONSE_TIMEOUT` with earliest missing step, closes/invalidates the stuck turn, performs at most **one** clean Live session recovery (`recreateSessionAfterInterruption`), and resumes `LISTENING` in active conversation mode (or stops in `ERROR` on consecutive timeout with zero infinite loops).
- **Concurrency & Lock-Ordering Fix (`AudioCapture`, `ConversationCoordinator`)**:
  - Refactored `AudioCapture.onHardwareCallback` so `synchronized(this)` covers only state mutation while the coordinator listener is invoked strictly off-lock, eliminating the `AudioCapture <-> ConversationCoordinator` ABBA lock-ordering cycle. Added `resourceSnapshot()` verifying zero leaked jobs, timers, watchdogs, tracks, or mic captures across turns 1, 7, 10, and 20+.

---

## Remaining Physical-Device Checks (Phase 05 — `NOT_TESTED`)
1. **Live Handshake Reliability & Single-Flight Reuse (`ov01-turn-0001..0010+`)**: Cold-launch app on physical Android phone, tap Start once, verify Phase 05 reaches `SETUP_ACCEPTED -> ACTIVE` with a single WebSocket handshake (no `ClosedReceiveChannelException` / `ServiceConnectionHandshakeFailedException`), mic frames capture cleanly (`RMS > 0`, non-zero frames sent), and sequential turns reuse the healthy Live session without reconnecting per turn.
2. **Multi-Turn Turn 7+ Endurance & AudioTrack Drain (`05.2`)**: Run 10–20+ spoken turns via one Start on the physical phone; verify Turns 7, 10, and 20 complete without getting stuck in `PROCESSING`, `AUDIOTRACK` `played/accepted` frames advance smoothly (`head > 0`), and capture automatically restores to `LISTENING` after each spoken answer finishes playing through hardware.
3. **Pause / Resume, Stop & Inactivity Timeout**: Tap **Pause** during `LISTENING` and during `SPEAKING`, verify audio stops and does not auto-resume on screen reopen; tap **Resume** to continue with a single active capture; tap **Stop** to terminate the session; test `30s` inactivity closure to `IDLE`.


