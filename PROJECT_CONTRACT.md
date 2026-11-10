# PROJECT_CONTRACT.md — OmniVoice Rebuild

## 1. Project Identity & Build Metadata
- **Project Name**: `OmniVoice Rebuild`
- **Application ID**: `com.kazim.omnivoice.rebuild` (coexists cleanly alongside any legacy OmniVoice installation)
- **Namespace**: `com.example` (preserved for AI Studio Android template resource/build compatibility)
- **Source / Build Identifier**: `ov-rebuild-p01-20261109` (`BuildConfig.SOURCE_BUILD_ID`)
- **Current Phase**: `Phase 01 — Minimal Responsive Conversation Shell` (`0.1.0-phase01`, `versionCode = 1`)
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
- **Model Configuration Status**: `NOT_CONFIGURED (Deferred to Phase 02+)` (`BuildConfig.MODEL_CONFIG_STATUS`)

---

## 2. Shared Architecture & Engineering Contract

All phases of **OmniVoice Rebuild** strictly adhere to the following non-negotiable invariants:

1. **Native Kotlin / Jetpack Compose Only**:
   - Built exclusively as a native Android application using Kotlin and Jetpack Compose. No web imitation wrappers or WebView shells.
2. **Clean-Room Rebuild (No Old Repository Import)**:
   - Zero previous engines, legacy repositories, or old repair prompts may be imported. Built from scratch in a clean package hierarchy (`com.example.contract`, `com.example.coordinator`, `com.example.ui.shell`, `com.example.ui.theme`).
3. **Single Conversation Coordinator**:
   - All conversation state transitions flow through a single authoritative `ConversationCoordinator`. No competing state machines or uncoordinated side-channel state mutations.
4. **Session / Turn / Action IDs**:
   - Every session, turn, and command execution carries strongly-typed `SessionId`, `TurnId`, and `ActionId` identifiers (`ov01-sess-*`, `ov01-turn-*`, `ov01-act-*`).
5. **Typed Verified Outcomes**:
   - Every command dispatched to `ConversationCoordinator` returns a sealed, auditable `VerifiedOutcome` (`StateTransitionVerified`, `HonestNotConfiguredOutcome`, `PreemptedByPrecedence`, `DuplicateActionRejected`).
6. **Exactly-Once Effects**:
   - Action execution is idempotent by `ActionId`. Re-dispatching an already-processed `ActionId` is deterministically rejected (`DuplicateActionRejected`) with zero state mutation or duplicate side effects.
7. **Strict Stop / Pause Precedence**:
   - `STOP` (priority 100) holds highest precedence over `PAUSE` (priority 50) and `START` (priority 10).
   - `PAUSE` holds precedence over `START`.
   - Once `STOPPED`, `PAUSE` cannot override `STOPPED` unless explicitly reset or restarted.
8. **Honest Hardware & Model States (No Fake Microphone)**:
   - When audio capture or Gemini models are not configured (as in Phase 01), pressing `Start` transitions to an explicit, honest `NOT_CONFIGURED` state and keeps `isMicrophoneActive = false`. The UI never animates or simulates a fake working microphone.
9. **Explicit Permissions**:
   - Runtime and manifest permissions are requested only in the phase where the corresponding hardware/OS capability is genuinely wired. Phase 01 requests zero unnecessary permissions.
10. **Privacy & Secret Exclusion from Logs**:
    - Private user transcripts, personal identifiers, API keys, and signing credentials are never written to logcat, diagnostic strings, or committed files. `.gitignore` excludes `.env`, `local.properties`, `*.jks`, `*.keystore`, `debug.keystore`, and build outputs.
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

Because the cloud AI Studio container does not have direct outbound GitHub push credentials pre-attached in the agent shell, use either the **AI Studio "Push to GitHub" / "Export ZIP" menu** in the top-right UI or the following **ZIP-to-local-Git commands** to initialize the `omnivoice-rebuild` repository locally without claiming an automated remote sync occurred:

```bash
# 1. Export the project ZIP from AI Studio and extract it locally:
unzip omnivoice-rebuild-phase01.zip -d omnivoice-rebuild
cd omnivoice-rebuild

# 2. Initialize a fresh Git repository on main:
git init -b main

# 3. Verify that credentials (.env, *.keystore, *.jks, local.properties) and /build are ignored:
git status --ignored

# 4. Stage and commit Phase 01:
git add .
git commit -m "Phase 01: OmniVoice Rebuild native Compose shell and ConversationCoordinator"

# 5. Create and push to the new 'omnivoice-rebuild' repository (using GitHub CLI or remote URL):
gh repo create omnivoice-rebuild --private --source=. --remote=origin --push
# Or manually:
# git remote add origin https://github.com/<your-username>/omnivoice-rebuild.git
# git push -u origin main
```
