# PROJECT_CONTRACT.md — OmniVoice Rebuild

## 1. Project Identity & Build Metadata
- **Project Name**: `OmniVoice Rebuild`
- **Application ID**: `com.kazim.omnivoice.rebuild` (coexists cleanly alongside any legacy OmniVoice installation)
- **Namespace**: `com.example` (preserved for AI Studio Android template resource/build compatibility)
- **Source / Build Identifier**: `ov-rebuild-p02-20261110` (`BuildConfig.SOURCE_BUILD_ID`)
- **Current Phase**: `Phase 02 — Microphone Setup & Local AudioCapture` (`0.2.0-phase02`, `versionCode = 2`)
- **Toolchain**:
  - Android Gradle Plugin (AGP): `9.1.1`
  - Kotlin: `2.2.10` (Compose Compiler plugin integrated)
  - Gradle Distribution: `9.3.1`
  - Java Bytecode Target: `JavaVersion.VERSION_11`
  - Jetpack Compose BOM: `2024.09.00`
  - Testing: JUnit `4.13.2`, Robolectric `4.16.1`, Compose UI Test JUnit4
- **SDK Levels**:
  - `minSdk`: `24` (Android 7.0)
  - `targetSdk`: `36`
  - `compileSdk`: `36` (minorApiLevel `1`)
- **Model Configuration Status**: `NOT_CONFIGURED (Audio Self-Test Only; Gemini Deferred)` (`BuildConfig.MODEL_CONFIG_STATUS`)

---

## 2. Shared Architecture & Engineering Contract

All phases of **OmniVoice Rebuild** strictly adhere to the following non-negotiable invariants:

1. **Native Kotlin / Jetpack Compose Only**:
   - Built exclusively as a native Android application using Kotlin and Jetpack Compose. No web imitation wrappers or WebView shells.
2. **Clean-Room Rebuild (No Old Repository Import)**:
   - Zero previous engines, legacy repositories, or old repair prompts may be imported. Built from scratch in a clean package hierarchy (`com.example.contract`, `com.example.coordinator`, `com.example.audio`, `com.example.ui.shell`, `com.example.ui.mic`, `com.example.ui.theme`).
3. **Single Conversation Coordinator & Single Audio Owner**:
   - All conversation state transitions flow through a single authoritative `ConversationCoordinator`, which owns the single `AudioCapture` component (`AudioOwner`: `NONE`, `SHELL_CONVERSATION`, `MIC_SELF_TEST`).
   - Simultaneous `SpeechRecognizer` and `AudioRecord` loops are strictly prohibited.
   - Every capture or playback run is bound to a monotonic `CaptureGenerationId`. Any callback from a stale generation is deterministically dropped.
4. **Session / Turn / Action IDs**:
   - Every session, turn, and command execution carries strongly-typed `SessionId`, `TurnId`, and `ActionId` identifiers (`ov01-sess-*`, `ov01-turn-*`, `ov01-act-*`).
5. **Typed Verified Outcomes**:
   - Every command dispatched to `ConversationCoordinator` returns a sealed, auditable `VerifiedOutcome` (`StateTransitionVerified`, `HonestNotConfiguredOutcome`, `PreemptedByPrecedence`, `DuplicateActionRejected`, `AudioSelfTestOutcome`).
6. **Exactly-Once Effects**:
   - Action execution is idempotent by `ActionId`. Re-dispatching an already-processed `ActionId` is deterministically rejected (`DuplicateActionRejected`) with zero state mutation or duplicate side effects.
7. **Strict Stop / Pause Precedence & Prompt Hardware Release**:
   - `STOP` (priority 100) holds highest precedence over `STOP_MIC_SELF_TEST` (90), `PAUSE` (50), `START_MIC_SELF_TEST` (20), and `START` (10).
   - Large `STOP` immediately halts active `AudioRecord`/`AudioTrack` hardware and wipes any ephemeral self-test buffer.
   - Navigating away from Microphone Setup or backgrounding the Activity immediately releases audio hardware and wipes ephemeral audio.
8. **Honest Hardware & Model States**:
   - Pressing `Start` in the Conversation Shell transitions to an explicit, honest `NOT_CONFIGURED` state because Gemini/assistant models remain deferred in Phase 02.
   - Microphone Self-Test is a local hardware verification only and never masquerades as an assistant answer.
   - Because a moving RMS meter alone is not proof of intelligible sound, Phase 02 includes a user-invoked ephemeral recording/playback check with retention OFF and immediate deletion after playback or Stop.
9. **Explicit Permissions**:
   - Phase 02 declares `android.permission.RECORD_AUDIO` in `AndroidManifest.xml`, explains why microphone access is needed prior to requesting, handles refusal vs. permanent denial, and re-checks permission automatically on `ON_RESUME` after returning from System App Settings.
10. **Privacy & Secret Exclusion from Logs**:
    - Private user transcripts, audio samples, personal identifiers, API keys, and signing credentials are never written to logcat, diagnostic strings, or committed files. `.gitignore` excludes `.env`, `local.properties`, `*.jks`, `*.keystore`, `debug.keystore`, and build outputs.
11. **Non-Destructive Memory Migrations**:
    - When local or cloud persistence is introduced in later phases, schema migrations must be non-destructive and preserve existing user records.
12. **Current Documented API / Model Configuration & Bounded Retries**:
    - Future AI/network phases must use current documented SDK/model configurations with explicit bounded retry policies.
13. **Real-Device Proof for Audio & Actions**:
    - Audio capture, playback, and system actions require real-device verification checklists rather than assuming JVM/emulator parity.
14. **No Unsupported OS-Control Claims**:
    - The application never claims privileged or unsupported Android OS control capabilities.
15. **No Unrequested Publish, Purchase, Repository Deletion, or Security Changes**:
    - Signing configurations (`release` and `debugConfig`) in `app/build.gradle.kts` are preserved intact for future updates. No unrequested store publishing, billing, repo deletion, or security policy alterations are performed.

---

## 3. Repository & ZIP-to-Local-Git Setup (`omnivoice-rebuild`)

```bash
# Export the project ZIP from AI Studio and commit Phase 02 locally:
unzip omnivoice-rebuild-phase02.zip -d omnivoice-rebuild
cd omnivoice-rebuild
git add .
git commit -m "Phase 02: Microphone setup screen, single AudioCapture with generation IDs, 16kHz resampler, and zero-retention playback check"
git push origin main
```
