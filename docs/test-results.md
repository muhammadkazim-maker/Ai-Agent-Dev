# Test & Build Verification Results — OmniVoice Rebuild (Phase 02)

## 1. Build Verification (`compile_applet`)
- **Command / Tool**: `compile_applet`
- **Source / Build Identifier**: `ov-rebuild-p02-20261110`
- **Application ID**: `com.kazim.omnivoice.rebuild`
- **Version**: `0.2.0-phase02` (`versionCode = 2`)
- **SDK Configuration**: `minSdk = 24`, `targetSdk = 36`, `compileSdk = 36` (minorApiLevel `1`)
- **Actual Result**: **BUILD SUCCEEDED** (`2026-11-10T00:26:04-08:00`)

---

## 2. Automated Unit & Robolectric UI Test Suite (`gradle :app:testDebugUnitTest`)
- **Command**: `gradle :app:testDebugUnitTest`
- **Total Tests**: **15 tests** (`11 JUnit4 unit tests` + `4 Robolectric Compose UI tests`)

### Suite A: `com.example.ExampleUnitTest` (11 JUnit4 Tests — Phase 01 + Phase 02)
1. `initial coordinator state is IDLE with inactive microphone and valid IDs` — **PASSED**
2. `start command transitions honestly to NOT_CONFIGURED without activating mic` — **PASSED**
3. `stop and pause precedence enforces STOP over PAUSE and START` — **PASSED**
4. `concurrent batch dispatch enforces STOP over PAUSE over START` — **PASSED**
5. `duplicate ActionId is rejected to guarantee exactly-once effects` — **PASSED**
6. `reset to idle allocates a new SessionId` — **PASSED**
7. `permission refusal, permanent denial, and Settings return re-check are handled accurately` — **PASSED**
   - Verifies `REFUSED_CAN_RETRY` when `shouldShowRationale == true`, `PERMISSION_MISSING` anomaly when starting capture without permission, `PERMANENTLY_DENIED` when `shouldShowRationale == false`, and automatic recovery to `GRANTED` upon returning from System App Settings (`onReCheckPermissionFromOs`).
8. `repeated start and stop increments capture generation and releases hardware promptly` — **PASSED**
   - Verifies monotonic `CaptureGenerationId` allocation (`gen-0001` -> `gen-0002`), prompt hardware release on `STOP_MIC_SELF_TEST`, and immediate hardware release + `STOPPED` transition on Large `STOP`.
9. `stale callbacks from previous capture generation are rejected without mutating active state` — **PASSED**
   - Verifies callbacks carrying a superseded `CaptureGenerationId` return `false`, increment `staleCallbacksDropped`, and leave active capture frame counts/RMS untouched; verifies active generation callbacks resample `48,000 Hz` (`960` samples) to `16,000 Hz` (`320` samples) and compute actual RMS.
10. `ephemeral recording and playback check verifies audio and immediately deletes buffer` — **PASSED**
    - Verifies 16 kHz PCM frames buffer while armed (Retention OFF), play back via `PLAY_EPHEMERAL_CHECK`, and immediately wipe `ephemeralRecordedSamples` to `0` (`0 bytes remaining`).
11. `detects Android-imposed silence, missing device, recorder start failure, and interruption` — **PASSED**
    - Verifies `MISSING_MICROPHONE_DEVICE`, `RECORDER_START_FAILURE`, `ANDROID_IMPOSED_SILENCE` (`osClientSilenced == true`), and `AUDIO_INTERRUPTED` (audio focus loss releasing hardware).

### Suite B: `com.example.ExampleRobolectricTest` (4 Robolectric + Compose UI Tests, `@Config(sdk = [36])`)
1. `verify application id, app_name, and BuildConfig metadata` — **PASSED**
   - Verifies `R.string.app_name == "OmniVoice Rebuild"`, `BuildConfig.APPLICATION_ID == "com.kazim.omnivoice.rebuild"`, and `BuildConfig.SOURCE_BUILD_ID == "ov-rebuild-p02-20261110"`.
2. `conversation shell renders landscape layout and transitions through Start, Pause, Stop` (`@Config(sdk = [36], qualifiers = "w840dp-h480dp-land")`) — **PASSED**
3. `conversation shell supports portrait fallback and accessibility font scaling` (`@Config(sdk = [36], qualifiers = "w411dp-h891dp-port")`) — **PASSED**
4. `microphone setup screen verifies rationale, permission refusal, self-test RMS, ephemeral playback, and navigation release` (`@Config(sdk = [36], qualifiers = "w840dp-h480dp-land")`) — **PASSED**
   - Opens `MicrophoneSetupScreen`, asserts rationale & honesty banners, verifies `REFUSED_CAN_RETRY` -> `PERMANENTLY_DENIED` -> `GRANTED` UI badges, starts self-test capture, verifies `FRAMES: 1` and RMS UI readout, triggers `Play & Delete Ephemeral Check` (`ephemeralRecordedSamples == 0`), and confirms navigating back immediately releases hardware (`isCapturing == false`).

---

## 3. Remaining Physical Device Checks (Phase 02 Hardware Proof Checklist)
Because JVM/Robolectric tests cannot physically transduce room acoustics, verify the following on a physical Android device:
1. **Runtime `RECORD_AUDIO` OS Dialog & Settings Return**:
   - Deny permission once -> confirm `REFUSED_CAN_RETRY` explanation appears.
   - Deny twice ("Don't ask again") -> confirm `PERMANENTLY_DENIED` state and tap **Open System App Settings**, grant Microphone access in Android Settings, press Back, and confirm the badge automatically updates to `GRANTED` on `ON_RESUME`.
2. **Real Microphone Acoustic Capture & Resampling**:
   - Tap **Start Self-Test**, speak into the microphone, and confirm `FRAMES`, `SAMPLES`, and `ACTUAL RMS` respond to real speech while Android's green microphone privacy indicator appears.
3. **Ephemeral Playback Intelligibility & Zero-Retention Deletion**:
   - Tap **Stop & Release** (confirm Android's green mic indicator turns off immediately), then tap **Play & Delete Ephemeral Check** to hear your recorded voice clearly and confirm `Buffered Samples` resets to `0 (0ms)` with `0 bytes remaining`.
4. **Interruption & Rotation / Background Lifecycle**:
   - Rotate between landscape and portrait during capture, then press Home or Back to leave Microphone Setup and confirm capture stops and hardware releases immediately.
