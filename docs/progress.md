# OmniVoice Rebuild — Phase Progress (`docs/progress.md`)

## Current Phase Status
- **Phase 01 (Conversation Shell & Contract Foundation)**: **COMPLETED & VERIFIED**
- **Phase 02 (Microphone Setup, Permission Flow & Single-Owner Local Capture Self-Test)**: **COMPLETED & VERIFIED**
- **Phase 03 (Secure Gemini Live Connection Adapter, Offline Detection & Honest Error Classification)**: **COMPLETED & VERIFIED**
- **Phase 04 (One-Turn Voice Coordinator, First-Turn Lifecycle Readiness Gate, Non-Leaking System Instruction & Complete Audio Playback)**: **COMPLETED & VERIFIED**
- **Phase 05 (Foreground Active Conversation Coordinator, Serialized State Machine, Auto-Restore Capture, Inactivity Session Closure & Lifecycle Restoration)**: **COMPLETED & VERIFIED**
- **Phase 06 (Natural Urdu/English/Mixed-Language Conversation, Consistent `"Kore"` Voice, Silero VAD Silence/Noise Gate & Foreground Natural Speech)**: **COMPLETED & PHYSICAL-PHONE VERIFIED**
- **Phase 07A (Trustworthy Monotonic Timing Instrumentation & Redacted Diagnostics `COPY DIAG`)**: **COMPLETED & UNIT-VERIFIED**
- **Phase 07B (Progress-Aware Stall Watchdogs, Bounded Recovery, Hardware Stall Detection & Live Session Resumption / GoAway Rotation)**: **COMPLETED & STABILIZED** (unit-verified; ongoing physical endurance checks apply)
- **Phase 07C (Honest Bounded 20-Turn Performance Summary & Redacted Reporting)**: **COMPLETED & UNIT-VERIFIED**
- **Phase 08 (Bounded Public Information Retrieval for Current News & Changing Facts)**: **IMPLEMENTED & UNIT/ROBOLECTRIC-VERIFIED (PENDING PHYSICAL-DEVICE GROUNDING TEST)**
- **Source / Build ID**: Derived from current build and source tree (`ov-rebuild-p08-0.8.0-phase08-sha256-<12hex>`; `SOURCE_REVISION`: `SOURCE_REVISION_UNAVAILABLE` when Git metadata is absent; reproducible SHA-256 `SOURCE_FINGERPRINT` included)
- **Application ID**: `com.kazim.omnivoice.rebuild`
- **Version**: `0.8.0-phase08` (`versionCode = 8`)

---

## Phase 08 Summary (`IMPLEMENTED & UNIT/ROBOLECTRIC-VERIFIED`)

### 1. Endpoint Verification & Secured Retrieval Split (`firebase-ai:17.15.0`)
- Verified in `firebase-ai:17.15.0` that `LiveGenerativeModel` (`gemini-3.1-flash-live-preview` on v1beta `BidiGenerateContent`) streams audio & transcription inside `LiveServerContent` without exposing `GroundingMetadata`, `GroundingChunk`, or `SearchEntryPoint`.
- Configured the Live session with one bounded, read-only `FunctionDeclaration` (`search_public_information`) wired through `ConversationCoordinator` to a separate secured `PublicInformationRetrievalAdapter` (`Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel("gemini-3.8-flash", tools = listOf(Tool.googleSearch()))` guarded by Firebase App Check).
- Preserved one speech output owner (`LiveAudioPlaybackAdapter` with `"Kore"` native audio) and `registeredActionToolsCount = 0` (zero device action tools).

### 2. Selective Triggering, Untrusted Data Boundary & Freshness Enforcement
- **`RetrievalTriggerClassifier`**: Ensures greetings, small talk, acknowledgments, timeless general knowledge, device commands, and private-memory/secret probes skip web retrieval (`SKIPPED_NON_CURRENT_QUERY`) with zero blocking latency.
- **`UntrustedContentSanitizer`**: Enforces strict untrusted-data sanitization over retrieved findings, publication/event notes, uncertainty notes, source titles/snippets, Google Search suggestion HTML, and URLs. Blocks `http:`, `javascript:`, `file:`, `data:`, `localhost`, loopback, RFC1918 private hosts, and userinfo URLs; strips `<script>`/`<style>`, bidi/control characters, prompt-injection overrides, device-action syntax, and private-memory exfiltration directives.
- **`PublicRetrievalCache`**: Bounded 24-entry LRU cache enforcing explicit freshness TTLs (`BREAKING_LIVE_5M` = 5 min, `CHANGING_PUBLIC_15M` = 15 min) with strict rejection of expired entries (`expiredCacheRejected = true`). Failed, offline, or hostile-blocked retrievals never fabricate URLs or fall back silently to expired cache.
- **Honest Unverified Disclosure**: Live turns that answer a current-information utterance without calling `search_public_information` are explicitly tagged `UNVERIFIED_BACKGROUND_ONLY`.

### 3. UI Citations, Lock-Screen Readiness, Cancellation & Redacted Diagnostics
- Updated `ConversationShellScreen` (`voice_turn_live_transcript_box` and `TranscriptEntryCard`) with `PublicRetrievalMetadataBlock` displaying verification status badge, findings summary, fetch UTC timestamp + freshness policy + expired-cache/filtered-threat flags, publication/event timeframe, uncertainty note, clickable HTTPS source buttons (`LocalUriHandler`), sandboxed non-JS Google Search suggestions (`searchEntryPointRenderedContent` + `webSearchQueries`), while keeping `STOP` / `PAUSE` / `ServerToolCallCancelled` cleanly cancelling active retrieval jobs.
- Added `LockScreenSafePublicSummary` (`toLockScreenSafePublicSummaryOrNull()`) exposing strictly non-private verified public results only.
- Extended `RedactedDiagnosticSnapshot` (`COPY DIAG` section `[13. PHASE 08 BOUNDED PUBLIC RETRIEVAL TELEMETRY]`) and `LiveDiagnosticsScreen` (`live_diag_retrieval_label`) with redacted retrieval counts (never exposing queries, transcripts, or URLs).

---

## Remaining Physical-Device Verification Checklist (Phase 08)
1. Install debug APK (`com.kazim.omnivoice.rebuild`) on a physical Android device with Firebase App Check debug token registered (`gen-lang-client-0676583419`) and tap **Start**.
2. Ask one live current-news/weather question (e.g., today's Lahore weather or latest cricket match score) and verify `"Kore"` speaks a brief natural summary mentioning retrieved facts + timing/uncertainty while clickable HTTPS source cards and Google Search suggestions appear in the transcript and open properly in the browser.
3. Ask one ordinary greeting/small-talk turn (`"Hello, kaise ho?"`) and confirm zero search indicator appears and latency stays immediate.
4. Enable Airplane Mode mid-session and ask another current-news question; confirm the assistant honestly states current information could not be verified without inventing citations or reusing expired cache, and verify `COPY DIAG` section `[13.]` increments failure counters without leaking queries.
