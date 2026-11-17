# OmniVoice Rebuild — Project Contract (`PROJECT_CONTRACT.md`)

## 1. Identity & Coexistence
- **Application Name**: `OmniVoice Rebuild`
- **Root Project Name**: `omnivoice-rebuild`
- **Application ID**: `com.kazim.omnivoice.rebuild` (coexists cleanly alongside any legacy OmniVoice installation)
- **Namespace**: `com.example` (preserved for Android R-class & package stability)
- **Source / Build Identifier**: Derived per build in `BuildConfig.SOURCE_BUILD_ID` (`versionCode = 7`, `versionName = "0.7.0-phase07"`, reproducible `sha256` source fingerprint, with Git revision reported as `SOURCE_REVISION_UNAVAILABLE` when `.git/HEAD` or `SOURCE_REVISION` is absent)
- **Signing Identity**: Preserves `debugConfig` (`debug.keystore`) and `release` signing configurations in `app/build.gradle.kts` without modification.

## 2. Core Architectural & Safety Invariants
1. **Native Kotlin / Jetpack Compose Only**:
   - Built 100% in Kotlin with Jetpack Compose Material 3 (`1.4.0`). No web imitation, no WebView shell, and zero imported legacy engines or repair prompts.
2. **Single Conversation Coordinator (`com.example.coordinator.ConversationCoordinator`)**:
   - All operator and system commands flow through a single coordinator authority.
   - Every session, turn, action, audio capture loop, and live socket attempt is tagged with strongly-typed IDs: `SessionId`, `TurnId`, `ActionId`, `CaptureGenerationId`, and `LiveSessionGenerationId`.
   - Every command returns a sealed, auditable `VerifiedOutcome` (`StateTransitionVerified`, `HonestNotConfiguredOutcome`, `PreemptedByPrecedence`, `DuplicateActionRejected`, `AudioSelfTestOutcome`, `LiveConnectionOutcome`).
3. **Single Audio Owner (`com.example.audio.AudioCapture`)**:
   - Exactly one `AudioCapture` component manages microphone hardware with explicit `AudioOwner` (`NONE`, `MIC_SELF_TEST`) and monotonically increasing `CaptureGenerationId` (`cap-gen-0001-...`).
   - All hardware callbacks (`FrameCaptured`, `RecorderError`, `InterruptionDetected`, `EphemeralPlaybackCompleted`) must match the active `CaptureGenerationId`; stale callbacks from prior generations are deterministically dropped and counted.
   - No simultaneous `SpeechRecognizer` and `AudioRecord` loops exist anywhere in the project.
   - Uses 16,000 Hz, 16-bit mono PCM (`ENCODING_PCM_16BIT`, `CHANNEL_IN_MONO`) with deterministic linear-interpolation resampling (`PcmResampler`) when hardware reports 44.1 kHz or 48 kHz.
   - Ephemeral self-test recording/playback check defaults to retention OFF and immediately overwrites/deletes the buffer (`0 bytes`) after playback or on Stop/lifecycle exit.
4. **Single Gemini Live Connection Adapter (`com.example.live.GeminiLiveConnectionAdapter`) — Phase 03**:
   - **Documented Runtime Model & API Version**: Uses `gemini-3.1-flash-live-preview` over `v1beta` (`BidiGenerateContent` WebSocket via official Android Firebase AI Logic `Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(...)`). Never infers runtime model IDs from AI Studio's coding-model picker and never switches silently to guessed models on failure.
   - **Single Supported Managed Authentication Route**: Uses official Android **Firebase AI Logic** (`com.google.firebase:firebase-ai` in `firebase-bom:34.17.0`) with **Firebase App Check** (`DebugAppCheckProviderFactory` in debug builds, `RecaptchaAppCheckProviderFactory` in release builds), initialized synchronously via `.commit()` in `MainActivity.onCreate()` before any UI or Firebase calls.
   - **Zero Embedded Secrets**: No long-lived `GEMINI_API_KEY` or bearer token is embedded in the APK, `.env`, repository, or logs.
   - **Connection States & Failure Classification**: Exposes `DISCONNECTED`, `CONNECTING`, `SETUP_ACCEPTED`, `RETRY_BACKOFF_WAITING`, and `FAILED`, with classified failures (`BAD_CREDENTIALS_OR_APP_CHECK`, `UNSUPPORTED_MODEL_OR_ENDPOINT`, `QUOTA_EXHAUSTED`, `TRANSIENT_NETWORK_OR_SOCKET`, `CANCELLED_BY_STOP_OR_PAUSE`).
   - **Socket/Session Generation Binding**: Every socket callback (`SetupAccepted`, `HandshakeFailed`, `SocketClosedRemotely`) is bound to `LiveSessionGenerationId` (`live-gen-0001-...`); stale callbacks from superseded or closed sockets are deterministically dropped and counted.
   - **Bounded Backoff & Non-Retryable Halt**: Applies bounded exponential backoff (`maxAttempts = 3`, `500ms -> 1000ms`) strictly to `TRANSIENT_NETWORK_OR_SOCKET`. Immediately stops automatic retries on `BAD_CREDENTIALS_OR_APP_CHECK` (`401/403`), `UNSUPPORTED_MODEL_OR_ENDPOINT` (`400/404`), and `QUOTA_EXHAUSTED` (`429`).
   - **Phase 03 Honesty Scope**: Verifies WebSocket handshake (`LiveGenerativeModel.connect()`) and displays redacted diagnostics (`modelId`, `endpointFamily`, `authRoute`, `sessionGenerationWireId`, `failureCode`). Does **NOT** claim audio playback (`audioPlaybackClaimed = false`, never calls `startAudioConversation()`) and registers **zero action tools** (`tools = emptyList()`).
5. **Exactly-Once Effects**:
   - The coordinator tracks executed `ActionId`s and deterministically rejects duplicate dispatches with `VerifiedOutcome.DuplicateActionRejected`.
6. **Stop / Pause Precedence & Prompt Resource Release**:
   - `STOP` (priority 100) holds highest precedence over `STOP_MIC_SELF_TEST` (90), `DISCONNECT_LIVE_SESSION` (80), `PAUSE` (50), `CONNECT_LIVE_SESSION` (25), `START_MIC_SELF_TEST` (20), `PLAY_EPHEMERAL_CHECK` (15), `START` (10), and `RESET_IDLE` (5).
   - Large `STOP` immediately halts any active or connecting Gemini Live WebSocket session, releases `AudioRecord` / `AudioTrack` hardware, and wipes any ephemeral self-test buffer.
7. **Privacy, Redaction & Non-Destructive Evolution**:
   - Private user data, raw audio samples, API keys, and App Check tokens are excluded from logs and UI diagnostics via `DiagnosticRedactor`.
   - Future memory/storage migrations must be non-destructive.
   - No unsupported OS-control claims, and no unrequested publish, purchase, repository deletion, or security changes.

## 3. Toolchain & SDK Matrix
| Parameter | Value |
| :--- | :--- |
| **Android Gradle Plugin (AGP)** | `9.1.0` |
| **Kotlin Version** | `2.3.20` |
| **KSP Version** | `2.3.6` |
| **Compile SDK** | `36` (`minorApiLevel = 1`) |
| **Target SDK** | `36` |
| **Minimum SDK** | `24` |
| **Compose BOM** | `2026.03.01` (`material3 = 1.4.0`) |
| **Firebase BOM** | `34.17.0` (`firebase-ai`, `firebase-appcheck-recaptcha`, `firebase-appcheck-debug`) |
| **Model Configuration Status** | `LIVE_ADAPTER_CONFIGURED (gemini-3.1-flash-live-preview via Firebase AI Logic v1beta; TOOLS=0)` |
| **Source / Build ID** | Derived via `BuildConfig.SOURCE_BUILD_ID` (`0.7.0-phase07`, reproducible source fingerprint; `SOURCE_REVISION_UNAVAILABLE` when VCS revision is absent) |
