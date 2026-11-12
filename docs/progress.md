# OmniVoice Rebuild — Phase Progress (`docs/progress.md`)

## Current Phase Status
- **Phase 01 (Conversation Shell & Contract Foundation)**: **COMPLETED & VERIFIED**
- **Phase 02 (Microphone Setup, Permission Flow & Single-Owner Local Capture Self-Test)**: **COMPLETED & VERIFIED**
- **Phase 03 (Secure Gemini Live Connection Adapter, Offline Detection & Honest Error Classification)**: **REPAIRED & AUTOMATED TESTS PASSING (31/31 Automated Tests Passing; Real-Device Offline Verification Pending User Test)**
- **Source / Build ID**: `ov-rebuild-p03-20261111`
- **Application ID**: `com.kazim.omnivoice.rebuild`
- **Version**: `0.3.0-phase03` (`versionCode = 3`)

---

## Phase 03 Targeted Offline Detection & Error Classification Repair Summary
1. **Lifecycle-Safe Connectivity Monitoring (`LiveNetworkMonitor.kt`)**:
   - Implemented `AndroidLiveNetworkMonitor` using `ConnectivityManager.NetworkCallback`.
   - Registers while `LiveDiagnosticsScreen` or an active Gemini Live session is open and unregisters safely when the screen is disposed (if no active session remains), when `STOP` / `Disconnect` is pressed, or when the Activity stops / ViewModel clears.
2. **Immediate Invalidation of Stale `SETUP_ACCEPTED` on Internet Loss (`GeminiLiveConnectionAdapter.kt`)**:
   - When validated internet (`NET_CAPABILITY_INTERNET` + `NET_CAPABILITY_VALIDATED`) is lost, immediately invalidates `SETUP_ACCEPTED` (`handshakeVerified = false`), advances the request sequence, closes/cancels the active Gemini Live connection and scheduled retries cleanly, and transitions to `NETWORK_UNAVAILABLE`.
3. **Pre-Handshake Network Validation Gate**:
   - Before every manual or automatic handshake attempt, verifies `networkMonitor.isInternetValidated()`. When false, returns `NETWORK_UNAVAILABLE` immediately without calling Firebase AI Logic or requesting an App Check token.
4. **Honest Cause-Chain Error Classification (`LiveExceptionClassifier`)**:
   - Inspects the full exception and cause chain first for DNS, timeout, socket, and I/O errors (`UnknownHostException`, `SocketTimeoutException`, `ConnectException`, `SocketException`, `EOFException`, `IOException`, `TimeoutCancellationException`), classifying them as `NETWORK_ERROR` even if wrapped inside a Firebase/App Check exception.
   - Classifies actual HTTP `401/403` or explicit App Check/authentication rejection while internet is validated as `BAD_CREDENTIALS_OR_APP_CHECK`.
5. **Controlled Recovery Policy**:
   - When validated internet returns, transitions to `NETWORK_RESTORED` (`READY_TO_RECONNECT`) and performs at most 1 controlled automatic reconnect per offline interruption without looping.
