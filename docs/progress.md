# Phase Progress Log — OmniVoice Rebuild

## Current Status: Phase 02 Completed (Stopped after Phase 02 per instructions)

| Phase | Scope | Status | Source / Build ID | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **Phase 01** | Fresh native Kotlin/Compose project (`com.kazim.omnivoice.rebuild`), single `ConversationCoordinator`, landscape-first shell + portrait fallback + accessibility font scaling, large Start/Pause/Stop controls, honest `NOT_CONFIGURED` state, Settings placeholder, contract & docs | **COMPLETED** | `ov-rebuild-p01-20261109` | Verified via `compile_applet` and 9 unit/Robolectric tests. |
| **Phase 02** | Microphone Setup screen with explicit `RECORD_AUDIO` rationale, refusal/permanent-denial handling & `ON_RESUME` Settings re-check; single `AudioCapture` owner with monotonic `CaptureGenerationId` & stale-callback rejection; 16 kHz Mono PCM-16 format & resampler; live RMS + captured frame count self-test; ephemeral record/playback intelligibility check with retention OFF & immediate deletion; anomaly detection (start failure, OS silence, missing mic, interruption); immediate hardware release on Large Stop & navigation/lifecycle | **COMPLETED** | `ov-rebuild-p02-20261110` | Verified via `compile_applet` and 15 unit/Robolectric tests (`gradle :app:testDebugUnitTest`). No Gemini upload or assistant answer simulation. |
| **Phase 03+** | Gemini live/model integration, bounded retries, non-destructive persistence | **DEFERRED (NOT STARTED)** | — | Intentionally halted at Phase 02 boundary per prompt instructions. |

---

## Phase 02 Deliverables Completed
1. **Explicit `RECORD_AUDIO` Permission Flow & Microphone Setup Screen (`MicrophoneSetupScreen.kt`)**:
   - Declared `<uses-permission android:name="android.permission.RECORD_AUDIO" />` in `AndroidManifest.xml`.
   - Displays a dedicated rationale card explaining why 16 kHz mono PCM microphone capture is required and confirming zero network upload and zero retention in Phase 02.
   - Requests `RECORD_AUDIO` via `ActivityResultContracts.RequestPermission()`, distinguishing `GRANTED`, `REFUSED_CAN_RETRY` (rationale shown), and `PERMANENTLY_DENIED` (provides direct `Open System App Settings` button).
   - Observes `Lifecycle.Event.ON_RESUME` to automatically re-check OS permission state upon returning from Android System Settings.
2. **Single `AudioCapture` Component with Owner & Capture Generation (`AudioCapture.kt`, `AudioCaptureModels.kt`)**:
   - Owned exclusively by `ConversationCoordinator` with explicit `AudioOwner` (`NONE`, `SHELL_CONVERSATION`, `MIC_SELF_TEST`) and monotonic `CaptureGenerationId` (`gen-0001-mic_self_test`, etc.).
   - Prohibits simultaneous `SpeechRecognizer` and `AudioRecord` loops.
   - Validates `event.generationId == activeGeneration` on every hardware callback, deterministically dropping stale callbacks and incrementing `staleCallbacksDropped`.
3. **16,000 Hz Mono PCM-16 Format, Resampling & Anomaly Detection**:
   - Negotiates `16,000 Hz` `CHANNEL_IN_MONO` `ENCODING_PCM_16BIT` via `AudioRecord`, with automatic fallback negotiation (`24,000 Hz`, `48,000 Hz`, `44,100 Hz`) and real-time linear-interpolation resampling (`PcmResampler.resampleMono16Bit`) to canonical `16,000 Hz` 16-bit mono PCM.
   - Detects and reports `MISSING_MICROPHONE_DEVICE`, `PERMISSION_MISSING`, `RECORDER_START_FAILURE`, `ANDROID_IMPOSED_SILENCE` (`isClientSilenced` on API 29+ or continuous zero-frame streaks), and `AUDIO_INTERRUPTED` (audio focus loss).
4. **Local Capture Self-Test & Ephemeral Recording/Playback Check (Retention OFF)**:
   - Displays live linear RMS, dBFS, peak RMS, captured frame count, sample count, and duration from visible UI.
   - Includes a user-invoked **Play & Delete Ephemeral Check** that plays back the temporary 16 kHz PCM capture via `AudioTrack` to verify intelligible sound and immediately zeroes/deletes the ephemeral buffer and any temporary cache file (`0 bytes remaining`).
5. **Prompt Hardware Release on Large Stop & Lifecycle Boundaries**:
   - Pressing Large `Stop` immediately stops `AudioRecord`/`AudioTrack`, releases hardware, wipes ephemeral audio, and transitions to `STOPPED`.
   - Navigating away from `MicrophoneSetupScreen` or backgrounding the Activity (`ON_STOP` when `!isChangingConfigurations`) immediately releases hardware and wipes ephemeral audio while preserving ViewModel state across rotation.
