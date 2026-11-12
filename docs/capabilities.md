# OmniVoice Rebuild — Capabilities & Honest Limitations (`docs/capabilities.md`)

## 1. Active Capabilities in Phase 03 (`ov-rebuild-p03-20261111`)

| Capability Domain | Status in Phase 03 | Implementation Details |
| :--- | :--- | :--- |
| **Native Kotlin / Compose Shell** | **ACTIVE** | Landscape-first 2-column command deck (`>= 600.dp` or `width > height`) with vertical portrait fallback and accessibility font-scale tester (`1.0x`, `1.25x`, `1.5x`). |
| **Single ConversationCoordinator** | **ACTIVE** | Generates `SessionId`, `TurnId`, `ActionId`; enforces `STOP > STOP_MIC_SELF_TEST > DISCONNECT_LIVE_SESSION > PAUSE > CONNECT_LIVE_SESSION > START_MIC_SELF_TEST > START` precedence and exactly-once action execution. |
| **Microphone Permission Flow (`RECORD_AUDIO`)** | **ACTIVE** | Explains rationale before requesting `android.permission.RECORD_AUDIO`, distinguishes first-time refusal (`REFUSED_CAN_RETRY`) from permanent denial (`PERMANENTLY_DENIED`), provides direct System App Settings intent button, and re-checks permission on `Lifecycle.Event.ON_RESUME`. |
| **Single-Owner `AudioCapture` & Generation Guard** | **ACTIVE** | `com.example.audio.AudioCapture` owns `AudioRecord` / `AudioTrack` lifecycle with monotonic `CaptureGenerationId`. Rejects and counts stale callbacks from prior generations. |
| **16 kHz Mono PCM-16 Capture & Resampling** | **ACTIVE** | Requests 16,000 Hz `CHANNEL_IN_MONO` `ENCODING_PCM_16BIT`; automatically resamples 48 kHz / 44.1 kHz hardware streams to 16,000 Hz via `PcmResampler`. |
| **Ephemeral Recording / Playback Intelligibility Check** | **ACTIVE** | Captures up to 5 seconds in an ephemeral buffer, plays it back on user invocation to prove intelligible capture, and immediately wipes/deletes the buffer (`0 bytes`) after playback or Stop. |
| **Single `GeminiLiveConnectionAdapter` (Phase 03)** | **ACTIVE** | Targets `gemini-3.1-flash-live-preview` over `v1beta` (`BidiGenerateContent` WebSocket). Never switches silently to guessed models. |
| **Connectivity Monitoring & Offline Gate (`LiveNetworkMonitor`)** | **ACTIVE** | Monitors `NET_CAPABILITY_INTERNET` and `NET_CAPABILITY_VALIDATED` via `ConnectivityManager.NetworkCallback`. Invalidates `SETUP_ACCEPTED` to `NETWORK_UNAVAILABLE` on internet loss, blocks offline Firebase/App Check calls, and transitions to `NETWORK_RESTORED` (`READY_TO_RECONNECT`) with at most 1 controlled reconnect on recovery. |
| **Honest Cause-Chain Error Classification** | **ACTIVE** | Inspects the full exception and cause chain so DNS/timeout/socket/IO failures (even inside App Check token exchange) are classified as `NETWORK_ERROR`, while real HTTP 401/403 or App Check rejection while online is classified as `BAD_CREDENTIALS_OR_APP_CHECK`. |
| **Firebase App Check Debug & Production Play Integrity** | **ACTIVE** | `configureAppCheck(this, intent)` runs in `MainActivity.onCreate()` before `Firebase.ai` access. Debug builds install `DebugAppCheckProviderFactory.getInstance()`; release builds install `PlayIntegrityAppCheckProviderFactory.getInstance()`. |
| **Gemini Live Audio Playback & Voice Turns** | **NOT_CLAIMED (Phase 03)** | Phase 03 verifies the WebSocket setup handshake and diagnostics only. `audioPlaybackClaimed = false`; `startAudioConversation()` is never called. |
| **Action Tools Registration** | **ZERO_TOOLS (Phase 03)** | `tools = emptyList()` (`registeredActionToolsCount = 0`). |
| **Accessibility Service / Call Control** | **NOT_CONFIGURED (Phase 03)** | Intentionally omitted in Phase 03 per `PROJECT_CONTRACT.md`. |
