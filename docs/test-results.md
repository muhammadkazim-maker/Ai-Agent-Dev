# OmniVoice Rebuild — Test & Build Results (`docs/test-results.md`)

## Build & Verification Summary (Phase 07A + Phase 07B + Phase 07C)
- **Source / Build ID**: `ov-rebuild-p03-20261111`
- **Application ID**: `com.kazim.omnivoice.rebuild`
- **Compile & Assemble Check (`compile_applet`)**: **PASS**
- **Unit & Robolectric Test Suite (`gradle :app:testDebugUnitTest --no-daemon --console=plain`)**: **PASS (124/124 tests passing across all 6 test suites, 0 skipped, 0 failed, 0 errors)**
- **Real-Device 20-Minute Physical-Phone Test**: **NOT_TESTED_ON_HOST (No ADB/emulator in build container; ready for physical-phone execution)**

---

## Automated Test Suites Executed (124 / 124 PASS)

### 1. `ExampleUnitTest` (`app/src/test/java/com/example/ExampleUnitTest.kt` — 86/86 PASS)
- Covers Phases 01–06 contracts, `AudioCapture`, `GeminiLiveConnectionAdapter`, `LiveAudioPlaybackAdapter`, `ConversationCoordinator` serialized state machine, auto-restore, Pause/Resume/Stop precedence, inactivity timeouts, offline detection/classification, and Phase 06A/06B regression checks.

### 2. `ExampleRobolectricTest` (`app/src/test/java/com/example/ExampleRobolectricTest.kt` — 17/17 PASS)
- Covers `applicationId` / `BuildConfig`, App Check initialization (`DebugAppCheckProviderFactory` in debug), portrait/landscape/accessibility Compose layout contracts, `MicrophoneSetupScreen`, `LiveDiagnosticsScreen`, and active conversation UI stages.

### 3. `Phase06cWakeWordTest` (`app/src/test/java/com/example/Phase06cWakeWordTest.kt` — 4/4 PASS)
- Verifies silence creates no Gemini turn, natural foreground conversation activates without requiring `"Jarvis"`, Pause/Stop invalidates authorization immediately, and stale generation callbacks are rejected.

### 4. `SileroVadAcousticTest` (`app/src/test/java/com/example/SileroVadAcousticTest.kt` — 5/5 PASS)
- Verifies real Silero VAD ONNX acoustic filtering (typing/cough vs speech), chronological pre-roll reframing, off-main IO execution order, and honest `VAD_MODEL_LOAD_FAILED` handling.

### 5. `Phase07aDiagnosticsTest` (`app/src/test/java/com/example/Phase07aDiagnosticsTest.kt` — 5/5 PASS)
- `test1_all11EventTimestampsOrderedAndLatencyCalculationsSeparated`: **PASS**
- `test2_missingAndOutOfOrderTimestampsReturnNullAndUnavailable`: **PASS**
- `test3_staleGenerationCallbacksRejectedWithoutMutatingActiveTiming`: **PASS**
- `test4_redactionExcludesTranscriptsSecretsContactsAndExceptionsAndBoundsErrorHistory`: **PASS**
- `test5_copyDiagControlCopiesRedactedSnapshotOnlyWhenTapped`: **PASS**

### 6. `Phase07bcRecoveryAndPerformanceTest` (`app/src/test/java/com/example/Phase07bcRecoveryAndPerformanceTest.kt` — 7/7 PASS)
- `test1_progressAwareWatchdog_textOnlyNoAudioTriggersTimeout_andMidResponseProgressPreventsFalseTimeout`: **PASS**
- `test2_midResponseServerOutputStallAndBoundedRecovery_stopsWithoutInfiniteRetries`: **PASS**
- `test3_maxTurnResponseHardBoundPreventsUnboundedStreamingTurn`: **PASS**
- `test4_hardwarePlaybackStallDetectedInPumpAndSurfacedToCoordinator`: **PASS**
- `test5_sessionResumptionGoAwayRotationAndRejectedHandleFallback`: **PASS**
- `test6_phase07cHonestPerformanceSummary_pendingInitially_bounded20TurnWindow_andRedactedInCopyDiag`: **PASS**
- `test7_composeUiLiveDiagnosticsAndCopyDiagReflectPhase07bcHonestState`: **PASS**
