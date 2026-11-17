# OmniVoice Rebuild — Phase Progress (`docs/progress.md`)

## Current Phase Status
- **Phase 01 (Conversation Shell & Contract Foundation)**: **COMPLETED & VERIFIED**
- **Phase 02 (Microphone Setup, Permission Flow & Single-Owner Local Capture Self-Test)**: **COMPLETED & VERIFIED**
- **Phase 03 (Secure Gemini Live Connection Adapter, Offline Detection & Honest Error Classification)**: **COMPLETED & VERIFIED**
- **Phase 04 (One-Turn Voice Coordinator, First-Turn Lifecycle Readiness Gate, Non-Leaking System Instruction & Complete Audio Playback)**: **COMPLETED & VERIFIED**
- **Phase 05 (Foreground Active Conversation Coordinator, Serialized State Machine, Auto-Restore Capture, Inactivity Session Closure & Lifecycle Restoration)**: **COMPLETED & VERIFIED**
- **Phase 06 (Natural Urdu/English/Mixed-Language Conversation, Consistent `"Kore"` Voice, Silero VAD Silence/Noise Gate & Foreground Natural Speech)**: **COMPLETED & PHYSICAL-PHONE VERIFIED**
- **Phase 07A (Trustworthy Monotonic Timing Instrumentation & Redacted Diagnostics `COPY DIAG`)**: **IMPLEMENTED & UNIT-VERIFIED**
- **Phase 07B (Progress-Aware Stall Watchdogs, Bounded Recovery, Hardware Stall Detection & Live Session Resumption / GoAway Rotation)**: **READY FOR 20-MINUTE PHONE TEST**
- **Phase 07C (Honest Bounded 20-Turn Performance Summary & Redacted Reporting)**: **READY FOR 20-MINUTE PHONE TEST**
- **Source / Build ID**: Derived from current build and source tree (`ov-rebuild-p07-0.7.0-phase07-sha256-<12hex>`; `SOURCE_REVISION`: `SOURCE_REVISION_UNAVAILABLE` when Git metadata is absent; reproducible SHA-256 `SOURCE_FINGERPRINT` included)
- **Application ID**: `com.kazim.omnivoice.rebuild`
- **Version**: `0.7.0-phase07` (`versionCode = 7`)

---

## Phase 07B & 07C Summary (`READY FOR 20-MINUTE PHONE TEST`)

### 1. Progress-Aware Watchdogs, Hardware Stall Detection & Bounded Recovery (Phase 07B)
- Consolidated capture (`AudioCapture`), transport (`GeminiLiveConnectionAdapter`), and playback (`LiveAudioPlaybackAdapter`) ownership under one serialized `ConversationCoordinator` gated by monotonic clock timestamps and session/turn/capture/live/playback generation IDs.
- **Progress-Aware Server Response & Output Watchdogs**:
  - Armed only after a valid spoken utterance has been submitted (`finishSpokenTurnAndAwaitResponse`).
  - Separates initial audible output wait (`firstResponseTimeoutMs` / `firstAudioAfterResponseTimeoutMs` — output transcription alone without PCM audio does not satisfy audible output) from mid-response chunk progress (`serverOutputStallTimeoutMs`) and an overall response ceiling (`maxTurnResponseHardBoundMs`).
  - Executes at most **one** clean bounded recovery per consecutive timeout streak (`firstResponseRecoveryCount`) without infinite retries; a consecutive second timeout halts cleanly in `ConversationState.ERROR` (`FIRST_RESPONSE_TIMEOUT` / `SERVER_OUTPUT_STALL_TIMEOUT`).
- **Hardware Playback Stall Detection**:
  - `LiveAudioPlaybackAdapter` monitors hardware playback head progress against written frames while accepted audio remains pending (`HARDWARE_PLAYBACK_STALL`, `HARDWARE_DRAIN_INCOMPLETE`), emits `PlaybackCallbackEvent.PlaybackFailed` before clearing active generation state so `ConversationCoordinator` transitions deterministically to `ERROR`, and releases capture/playback cleanly.
- **Session Resumption, Context Window Compression & GoAway Rotation**:
  - Tracks `SessionResumptionUpdated`, `ContextRestorationStatus`, and `ServerGoAwayReceived` in `GeminiLiveConnectionAdapter` and `ConversationCoordinator`, rotating cleanly with scoped resumption handles during `LISTENING` or between turns (`prepareNextTurnSessionRecoveryLocked`), falling back cleanly to a fresh session if the server rejects a stale handle, and clearing scoped resumption handles on `STOP` and `RESET_IDLE`.

### 2. Honest Bounded Performance Summary & Redacted Diagnostics (Phase 07C)
- `Phase07PerformanceSummary` tracks turn outcomes (`Phase07TurnOutcomeRecord`) over a bounded 20-turn sliding window (`MAX_BOUNDED_TURN_RECORDS = 20`), computing honest `validSampleCount`, `missingSampleCount`, exact median, and nearest-rank p95 while keeping startup duration, playback duration, and speaking rate strictly separate from response latency.
- **Strict Sample Window & Synthetic Exclusion Policy**:
  - Reports `MEASUREMENT_PENDING` before turns execute and `INSUFFICIENT_SAMPLES` whenever fewer than 20 comparable valid real-device turns exist (`validRealSamples < 20`). Never reports `WITHIN_ROADMAP_TARGETS` for a partial sample window.
  - Explicitly labels synthetic/test measurements (`SYNTHETIC_TEST_HARNESS (EXCLUDED_FROM_REAL_DEVICE_SUMMARY)`) and excludes them from real-device performance summaries.
  - Missing timestamps remain `UNAVAILABLE` (`null`) and are never substituted with zero or fallback timestamps; hardware-write timing (`tFirstHardwareWriteMs`) is explicitly labeled as an `[ESTIMATE]`.
- Exposed via `LiveDiagnosticsScreen` chips (`live_diag_resumption_label`, `live_diag_stall_counts_label`, `live_diag_perf_summary_label`) and sections `[10. PHASE 07B SESSION MANAGEMENT & CONTEXT RESTORATION (HANDLES REDACTED)]` and `[11. PHASE 07C HONEST PERFORMANCE SUMMARY (BOUNDED max=20 TURNS)]` inside `COPY DIAG`, with zero transcripts, raw audio, tokens, or resumption handles leaked.

### 3. Phase 07 Stabilization Repairs & Verification Gaps Resolved
- **False Activation & Unsolicited Replies (`SpeechActivityGate.kt`)**:
  - Configured `SpeechActivityGate` (when `speechDetector.requiresLoadedModel == true`) with `minConsecutiveSpeechWindows = 8` (`256ms`, `speechConfirmDurationMs = 256L`) and `preRollTargetMs = 480L`, paired with acoustic cough/throat-clear burst/rebound discrimination (`isCoughOrThroatClearCandidateStreakLocked`) so varied/longer coughs, repeated throat-clears, typing, and table taps are rejected without increasing speech onset duration beyond 256 ms, while short and quiet valid utterances (`"haan"`, `"nahi"`, `"yes"`, `"no"`, `"stop"`) confirm and complete cleanly.
- **Audio Chunk Ordering, Stream-Drain Cushion & Voice Pinning (`ConversationCoordinator.kt`, `LiveAudioPlaybackAdapter.kt`, `GeminiLiveConnectionAdapter.kt`)**:
  - Serialized `ServerAudioChunkReceived` write jobs in `ConversationCoordinator` (`previousWriteJob?.join()`) so concurrent coroutines on multi-threaded `Dispatchers.IO` cannot enqueue `QueuedPcmChunk` entries out of arrival order.
  - Added an `80ms` trailing silence cushion in `AndroidStreamingAudioTrackDriver.finishStreamForDrain` to assist software `AudioTrack` head drain before track flush. Whether this software cushion prevents audible tail clipping on physical speaker/Bluetooth hardware, and whether any remaining distortion or pronunciation issues originate in client playback/buffering or server generation, remain **physical-device verification items**.
  - Preserved `LiveConfigConstants.DEFAULT_NATIVE_AUDIO_VOICE` (`"Kore"`) across initial connect, reconnect, and resume.
- **No-Response / Ignored-Noise Turn Recovery (`ConversationCoordinator.kt`)**:
  - When `ServerTurnCompleted(interrupted = false)` arrives with `0` audio chunks (`turn.serverAudioChunksReceivedCount == 0`), the coordinator completes the turn as `NO_RESPONSE_TURN_IGNORED` (`Phase07TurnOutcomeCategory.NO_SPEECH_REJECTED`) and automatically restores `LISTENING` instead of failing with `AUDIO_TRACK_WRITE_FAILED`.

---

## 20-Minute Physical-Phone Verification Checklist (`READY FOR 20-MINUTE PHONE TEST`)
1. Install debug APK (`com.kazim.omnivoice.rebuild`) on physical phone, tap `COPY DIAG` in `IDLE`, and confirm `[1.]` shows the derived Phase 07 `sourceBuildId` and `sourceFingerprint`, `sourceRevision: SOURCE_REVISION_UNAVAILABLE`, and `[11.]` reports `MEASUREMENT_PENDING` / `UNAVAILABLE`.
2. Tap **Start** once and test varied/longer coughs, repeated throat-clears, keyboard typing, table taps, and 10+ seconds of silence; verify `0` unsolicited replies (`"I am here, anything I can help with?"`) and confirm the app stays in `LISTENING`.
3. Test short and quiet valid utterances (`"haan"`, `"nahi"`, `"yes"`, `"no"`, `"stop"`) as well as natural English, Urdu/Roman-Urdu, and mixed questions without saying `"Jarvis"`; verify on physical hardware whether end-of-word syllables play completely without clipping and isolate whether any remaining audio distortion or pronunciation issues occur in client playback/buffering or server audio generation.
4. Confirm that after 1–19 real-device turns `COPY DIAG` reports `INSUFFICIENT_SAMPLES` (never `WITHIN_ROADMAP_TARGETS`), and only after 20+ comparable valid real-device turns assesses `WITHIN_ROADMAP_TARGETS` vs. `EXCEEDS_ROADMAP_TARGETS` (`[ESTIMATE]` labels preserved, zero transcripts/secrets/handles exposed).
5. Trigger one controlled offline outage (Airplane Mode during a turn) to verify bounded recovery/halting behavior and confirm subsequent **Start** recovers cleanly.


