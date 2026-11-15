# OmniVoice Rebuild — Phase Progress (`docs/progress.md`)

## Current Phase Status
- **Phase 01 (Conversation Shell & Contract Foundation)**: **COMPLETED & VERIFIED**
- **Phase 02 (Microphone Setup, Permission Flow & Single-Owner Local Capture Self-Test)**: **COMPLETED & VERIFIED**
- **Phase 03 (Secure Gemini Live Connection Adapter, Offline Detection & Honest Error Classification)**: **COMPLETED & VERIFIED**
- **Phase 04 (One-Turn Voice Coordinator, First-Turn Lifecycle Readiness Gate, Non-Leaking System Instruction & Complete Audio Playback)**: **COMPLETED & VERIFIED**
- **Phase 05 (Foreground Active Conversation Coordinator, Serialized State Machine, Auto-Restore Capture, Inactivity Session Closure & Lifecycle Restoration)**: **COMPLETED & VERIFIED**
- **Phase 06A (Language & Voice Stabilization on Verified Phase 05 Checkpoint)**: **AUTOMATED TESTS PASSING — REAL-DEVICE GEMINI LANGUAGE VERIFICATION: `NOT RUN`**
- **Phase 06 Status**: **IN PROGRESS / PENDING PHYSICAL-PHONE VERIFICATION (Phase 06 is NOT claimed as passed yet)**
- **Source / Build ID**: `ov-rebuild-p03-20261111`
- **Application ID**: `com.kazim.omnivoice.rebuild`
- **Version**: `0.3.0-phase03` (`versionCode = 3`)

---

## Phase 06A Summary — Minimal Language & Voice Stabilization on Verified Phase 05 Baseline

### 1. Preserved Verified Phase 05 Baseline & Audio Pipeline
- Preserved `ConversationCoordinator.kt`, `TurnRuntimeState.kt`, `AudioCapture`, `LiveAudioPlaybackAdapter`, `ConversationPreferencesStore.kt`, and UI files (`ConversationShellViewModel.kt`, `ConversationShellScreen.kt`, `LiveDiagnosticsScreen.kt`) at the verified Phase 05 checkpoint.
- Zero `LanguagePolicyClassifier`, zero English/Roman-Urdu regex/word dictionaries, zero post-transcription language inference, zero per-transcript session rebuilds, and zero per-turn language persistence keys (`phase06_preferred_language`, `phase06_verbosity_preference`, `phase06_selected_voice` are not read or written).

### 2. Concise Authoritative System Instruction (`LiveConnectionModels.kt`)
- Configured `LiveConfigConstants.AUTHORITATIVE_SYSTEM_INSTRUCTION` as a single concise system instruction passed on initial connect and every reconnect:
  > `"Listen to the speaker carefully. For every turn independently, respond unmistakably in the language of the speaker's current utterance. Fully English speech gets English. Urdu or Roman Urdu gets natural conversational Urdu or Roman Urdu. Meaningfully mixed Urdu-English speech gets a similar natural mixture. A request such as 'answer in English' applies only to that response unless the user explicitly says 'from now on', 'always', 'ab se', or 'hamesha'. Never let the language of a previous response control the next turn. Keep the same calm, friendly and direct personality and the same configured voice. Be concise unless the user requests detail. Do not repeatedly introduce yourself. Never claim an action was completed unless it was verified."`

### 3. Pinned `"Kore"` Native-Audio Voice (`LiveConnectionModels.kt` & `GeminiLiveConnectionAdapter.kt`)
- Added `const val DEFAULT_NATIVE_AUDIO_VOICE: String = "Kore"` in `LiveConfigConstants`.
- Configured `speechConfig = SpeechConfig(voice = Voice(LiveConfigConstants.DEFAULT_NATIVE_AUDIO_VOICE))` inside `FirebaseAiLiveTransportDriver.connectAndHandshake` without setting `speechConfig.languageCode`.
- Every initial connection, reconnect, network restore, and Pause/Resume uses the same pinned `"Kore"` native-audio voice and concise `AUTHORITATIVE_SYSTEM_INSTRUCTION`.
- No voice selector UI is included in Phase 06A stabilization.

---

## Required Physical-Phone Test Checklist (Real-Device Gemini Language Verification: `NOT RUN`)
1. `"What is artificial intelligence?"` → English response.
2. `"Pakistan ka capital kya hai?"` → Urdu/Roman-Urdu response.
3. `"Explain RAM in one sentence."` → English response.
4. `"Mujhe Python ke benefits simple words mein batao."` → Urdu/Roman-Urdu response.
5. `"Machine learning aur data science ka difference simple English terms ke sath samjhao."` → natural mixed Urdu-English response.
6. `"Answer this in English: Pakistan ka founder kon tha?"` → English response for that turn only.
7. `"Japan ka capital kya hai?"` → Urdu/Roman-Urdu response automatically.
8. **Pause**, **Resume**, ask one more Urdu/Roman-Urdu question, then **Stop** cleanly.
