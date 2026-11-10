# Capabilities Matrix — OmniVoice Rebuild (Phase 01)

## 1. Application & Build Capabilities

| Capability | Phase 01 Status | Implementation Details |
| :--- | :--- | :--- |
| **Native Kotlin / Jetpack Compose UI** | **ACTIVE** | Built with Compose Material 3 (`Compose BOM 2024.09.00`), Kotlin `2.2.10`, AGP `9.1.1`. |
| **Coexisting Application ID** | **ACTIVE** | `com.kazim.omnivoice.rebuild` (`minSdk 24`, `targetSdk 36`, `compileSdk 36.1`). |
| **Source / Build Identifier** | **ACTIVE** | `BuildConfig.SOURCE_BUILD_ID = "ov-rebuild-p01-20261109"` |
| **Single Conversation Coordinator** | **ACTIVE** | `ConversationCoordinator` manages all state transitions, transcript entries, and outcomes. |
| **Session / Turn / Action IDs** | **ACTIVE** | Typed `@JvmInline value class` identifiers (`SessionId`, `TurnId`, `ActionId`) via `ContractIdGenerator`. |
| **Typed Verified Outcomes** | **ACTIVE** | Sealed `VerifiedOutcome` (`StateTransitionVerified`, `HonestNotConfiguredOutcome`, `PreemptedByPrecedence`, `DuplicateActionRejected`). |
| **Exactly-Once Action Execution** | **ACTIVE** | Bounded `LinkedHashSet` of executed `ActionId`s prevents duplicate command execution. |
| **Stop / Pause Precedence** | **ACTIVE** | `STOP` (priority 100) overrides `PAUSE` (priority 50) and `START` (priority 10). |
| **Landscape-First Responsive Shell** | **ACTIVE** | 2-column split console (`landscape_split_container`) when `maxWidth >= 600.dp || maxWidth > maxHeight`. |
| **Portrait Fallback Layout** | **ACTIVE** | Vertical stacked layout (`portrait_fallback_container`) on compact portrait screens. |
| **Accessibility Font Scaling** | **ACTIVE** | `sp` typography, dynamic height cards (`heightIn`), `FlowRow` chips, and in-app Font Scale tester (`1.0x`–`1.5x`). |
| **Honest `NOT_CONFIGURED` State** | **ACTIVE** | Pressing `Start` transitions to `NOT_CONFIGURED`; `isMicrophoneActive` is strictly `false` with no fake mic animation. |
| **Settings Placeholder** | **ACTIVE** | Modal dialog displaying toolchain telemetry, contract counters, and accessibility font scale preview. |

---

## 2. Intentionally Unconfigured Capabilities in Phase 01

| Capability | Phase 01 Status | Reason / Phase Boundary |
| :--- | :--- | :--- |
| **Microphone Audio Capture (`RECORD_AUDIO`)** | **NOT_CONFIGURED** | Deferred to later phases per Phase 01 scope. No fake mic animation is shown. |
| **Gemini / AI Model Integration** | **NOT_CONFIGURED** | `BuildConfig.MODEL_CONFIG_STATUS = "NOT_CONFIGURED (Deferred to Phase 02+)"`. |
| **Accessibility Service / OS Control** | **NOT_CONFIGURED** | Explicitly excluded in Phase 01; no unsupported OS-control claims. |
| **Telecom / Call Routing** | **NOT_CONFIGURED** | Explicitly excluded in Phase 01. |
| **Local / Cloud Database Persistence** | **NOT_CONFIGURED** | Deferred until conversation memory phase with non-destructive migrations. |

---

## 3. Environment Capabilities & Known Limitations

- **Cloud Build & Local JVM/Robolectric Verification**: Full Gradle compilation (`compile_applet`) and local JVM + Robolectric Compose UI testing (`gradle :app:testDebugUnitTest`) are available and executed in this workspace.
- **No Direct Physical Device / ADB in Container**: The server-side build container does not have an attached physical Android device or `adb` daemon. Users can interact with the app in the AI Studio Streaming Android Emulator or export the APK/ZIP for physical device testing.
- **GitHub Sync**: The container does not auto-push to external GitHub accounts without user export; complete ZIP-to-local-Git instructions for creating the `omnivoice-rebuild` repository are provided in `PROJECT_CONTRACT.md`.
- **Gradle Wrapper Note**: `gradle/wrapper/gradle-wrapper.properties` is configured for `gradle-9.3.1-bin.zip`. In the AI Studio container, builds invoke the pre-installed `gradle` binary directly; when exporting to a local machine, running `gradle wrapper` (or opening in Android Studio) populates local wrapper scripts if needed.
