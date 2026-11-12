# OmniVoice Rebuild — Phase 03 Authentication & Token Broker Setup (`docs/token-broker-setup.md`)

## 1. Why Zero Long-Lived API Keys Are Embedded in the APK or Repository
Per `PROJECT_CONTRACT.md`:
- **Never embed a long-lived API key in the APK, repository, or logs.**
- **Never deploy a public unauthenticated token endpoint.**
- Note that `app/google-services.json` (which contains a Firebase client `current_key: "AIza..."` that the Google Services Gradle plugin would compile into `R.string.google_api_key`) is intentionally **excluded** from the repository and deleted from the workspace so that the compiled APK contains **zero** embedded `AIza...` keys.

---

## 2. Supported Authentication Routes in Phase 03
`GeminiLiveConnectionAdapter` supports two non-embedded credential routes without silent model fallback:

### Route A: Official Android Firebase AI Logic + App Check (Local Uncommitted `app/google-services.json`)
- Uses official Android `com.google.firebase:firebase-ai` (`Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel("gemini-3.1-flash-live-preview")`) with `DebugAppCheckProviderFactory` (debug) and `RecaptchaAppCheckProviderFactory` (release).
- Requires a locally provided, gitignored `app/google-services.json` on the developer workstation.
- When `app/google-services.json` is omitted (as in this clean repository so zero `AIza...` strings enter the APK), `FirebaseAiLiveTransportDriver` detects `FirebaseApp.getApps().isEmpty()` and honestly reports **BLOCKED** (`BAD_CREDENTIALS_OR_APP_CHECK`), halting automatic retries after 1 attempt.

### Route B: Minimal Authenticated External Token Broker (`backend/token-broker/`)
Because the Android workspace cannot host a backend process, the complete separate backend source is supplied in `backend/token-broker/`:
- `backend/token-broker/server.mjs`
- `backend/token-broker/package.json`
- `backend/token-broker/Dockerfile`

#### How It Works
1. The permanent `GEMINI_API_KEY` is stored **strictly server-side** in Google Cloud Secret Manager and injected into the Cloud Run container as an environment secret.
2. The endpoint (`POST /v1/live-ephemeral-token`) requires client authentication (`Authorization: Bearer <BROKER_CLIENT_AUTH_SECRET>` verified via `crypto.timingSafeEqual`, plus Cloud Run IAM `--no-allow-unauthenticated`). Unauthenticated requests are rejected with `401 Unauthorized`.
3. On an authenticated request, the broker calls `POST https://generativelanguage.googleapis.com/v1alpha/auth_tokens` server-side to mint a single-use (`uses: 1`), short-lived (60-second session start window) ephemeral token locked to `models/gemini-3.1-flash-live-preview` (`AUDIO` modality, zero tools).

#### Exact Deployment Instructions (Google Cloud Run + Secret Manager)
```bash
# 1. Store permanent Gemini API key and client auth secret in Secret Manager
gcloud secrets create omnivoice-gemini-api-key --replication-policy="automatic"
printf "%s" "$YOUR_SERVER_SIDE_GEMINI_API_KEY" | gcloud secrets versions add omnivoice-gemini-api-key --data-file=-

gcloud secrets create omnivoice-broker-client-secret --replication-policy="automatic"
printf "%s" "$YOUR_BROKER_CLIENT_SECRET" | gcloud secrets versions add omnivoice-broker-client-secret --data-file=-

# 2. Deploy the token broker from backend/token-broker (authenticated only)
gcloud run deploy omnivoice-live-token-broker \
  --source ./backend/token-broker \
  --region asia-east1 \
  --no-allow-unauthenticated \
  --set-env-vars="ALLOWED_LIVE_MODEL=models/gemini-3.1-flash-live-preview" \
  --set-secrets="GEMINI_API_KEY=omnivoice-gemini-api-key:latest,BROKER_CLIENT_AUTH_SECRET=omnivoice-broker-client-secret:latest"
```

---

## 3. Honest BLOCKED Status Until Endpoint or Local Managed Config Is Provided
Until either:
1. A deployed authenticated token broker URL + short-lived client credential is provided at runtime, **OR**
2. A local gitignored `app/google-services.json` with Firebase App Check attestation is placed on the build machine,

`GeminiLiveConnectionAdapter` honestly reports **BLOCKED** (`BAD_CREDENTIALS_OR_APP_CHECK`, `autoRetryStoppedReason = AUTO_RETRY_HALTED_BAD_CREDENTIALS_OR_APP_CHECK`), stops automatic retries after 1 attempt, never switches models, and never fakes `SETUP_ACCEPTED` in production.
