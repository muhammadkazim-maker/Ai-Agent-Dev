# Capabilities Matrix — OmniVoice Rebuild (Phase 02)

## 1. Active Application & Audio Capabilities (Phase 01 + Phase 02)

| Capability | Phase 02 Status | Implementation Details |
| :--- | :--- | :--- |
| **Native Kotlin / Jetpack Compose UI** | **ACTIVE** | Compose Material 3 (`Compose BOM 2024.09.00`), Kotlin `2.2.10`, AGP `9.1.1`. |
| **Coexisting Application ID** | **ACTIVE** | `com.kazim.omnivoice.rebuild` (`minSdk 24`, `targetSdk 36`, `compileSdk 36.1`, `versionCode 2`). |
| **Source / Build Identifier** | **ACTIVE** | `BuildConfig.SOURCE_BUILD_ID = "ov-rebuild-p02-20261110"` |
| **Single Conversation Coordinator** | **ACTIVE** | `ConversationCoordinator` manages all shell and audio self-test state transitions. |
| **Single `AudioCapture` Component & Owner** | **ACTIVE** | `AudioCapture` enforces single `AudioOwner` (`NONE`, `SHELL_CONVERSATION`, `MIC_SELF_TEST`) and monotonic `CaptureGenerationId`. No `SpeechRecognizer` loop exists. |
| **Generation-Bound Callbacks** | **ACTIVE** | All frame, error, interruption, and playback callbacks carry `CaptureGenerationId`; stale callbacks are dropped (`staleCallbacksDropped`). |
| **Microphone Setup & Permission Flow** | **ACTIVE** | Clear rationale card, `RECORD_AUDIO` runtime request, `REFUSED_CAN_RETRY` vs. `PERMANENTLY_DENIED` handling, `Open System App Settings` intent, and automatic `ON_RESUME` re-check. |
| **16 kHz Mono PCM-16 & Resampler** | **ACTIVE** | Captures native `16,000 Hz` `PCM_16BIT` mono when supported, or resamples `24kHz`/`48kHz`/`44.1kHz` hardware frames to canonical `16,000 Hz` mono via `PcmResampler`. |
| **Live RMS & Frame Count Self-Test** | **ACTIVE** | Displays real-time linear RMS, dBFS, peak RMS, captured frame count, and sample count. |
| **Ephemeral Record/Playback Check (Retention OFF)** | **ACTIVE** | Buffers up to 5s of 16 kHz PCM during self-test, plays back via `AudioTrack` (`MODE_STATIC`) to confirm intelligible sound, and immediately zeroes/deletes the buffer upon playback or Stop. |
| **Hardware Anomaly Detection** | **ACTIVE** | Detects `MISSING_MICROPHONE_DEVICE`, `PERMISSION_MISSING`, `RECORDER_START_FAILURE`, `ANDROID_IMPOSED_SILENCE` (`isClientSilenced` / zero-frame streak), and `AUDIO_INTERRUPTED` (audio focus loss). |
| **Prompt Hardware Release & Lifecycle Rules** | **ACTIVE** | Large `Stop`, navigating back from Microphone Setup, and Activity `ON_STOP` (when not rotating) immediately release `AudioRecord`/`AudioTrack` and wipe ephemeral audio. |
| **Honest Shell Boundary** | **ACTIVE** | Main shell `Start` remains honestly `NOT_CONFIGURED` for Gemini; UI explicitly states that Microphone Self-Test is not an assistant answer. |

---

## 2. Intentionally Unconfigured Capabilities in Phase 02

| Capability | Phase 02 Status | Reason / Phase Boundary |
| :--- | :--- | :--- |
| **Gemini / AI Model Integration & Network Upload** | **NOT_CONFIGURED** | Deferred to Phase 03+. Zero audio frames are uploaded over the network in Phase 02. |
| **Accessibility Service / OS Control** | **NOT_CONFIGURED** | Explicitly excluded; no unsupported OS-control claims. |
| **Telecom / Call Routing** | **NOT_CONFIGURED** | Explicitly excluded in Phase 02. |
| **Persistent Audio / Memory Storage** | **NOT_CONFIGURED** | Retention is strictly OFF in Phase 02; ephemeral playback buffer is zeroed immediately after check. |

---

## 3. Environment Capabilities & Known Device-Only Limitations

- **Cloud Build & Local JVM/Robolectric Verification**: Full Gradle compilation (`compile_applet`) and local JVM + Robolectric Compose UI testing (`gradle :app:testDebugUnitTest`) are executed and verified in this workspace.
- **Device-Only Acoustic & Hardware Limitations**:
  - JVM/Robolectric tests deterministically verify permission state machines, `CaptureGenerationId` callback isolation, 48 kHz -> 16 kHz resampling math, RMS calculation, anomaly state transitions, and ephemeral buffer zeroing, but cannot physically transduce room acoustics or test OEM-specific hardware mute switches.
  - Real-device verification is required to confirm actual acoustic transducer clarity during the ephemeral playback check, Android 12+ privacy indicator behavior, and real phone-call/media audio-focus interruption on physical hardware.
