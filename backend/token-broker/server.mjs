import http from "node:http";
import crypto from "node:crypto";

/**
 * OmniVoice Rebuild — Phase 03 Minimal Authenticated External Live Token Broker
 *
 * Security & Contract Invariants:
 * 1. NEVER deployed as a public unauthenticated endpoint: requires constant-time verified
 *    `Authorization: Bearer <BROKER_CLIENT_AUTH_SECRET>` (in addition to Cloud Run IAM).
 * 2. Permanent `GEMINI_API_KEY` remains strictly server-side (injected via Secret Manager)
 *    and is NEVER returned to the client or written to logs.
 * 3. Issues restricted, single-use (`uses: 1`), short-lived ephemeral Live tokens locked to
 *    the supported native-audio Live model (`models/gemini-3.1-flash-live-preview`).
 */

const PORT = Number(process.env.PORT || 8080);
const GEMINI_API_KEY = process.env.GEMINI_API_KEY || "";
const BROKER_CLIENT_AUTH_SECRET = process.env.BROKER_CLIENT_AUTH_SECRET || "";
const ALLOWED_LIVE_MODEL = process.env.ALLOWED_LIVE_MODEL || "models/gemini-3.1-flash-live-preview";

function timingSafeBearerMatch(authHeader, expectedSecret) {
  if (!authHeader || !expectedSecret || !authHeader.startsWith("Bearer ")) {
    return false;
  }
  const provided = Buffer.from(authHeader.slice("Bearer ".length).trim(), "utf8");
  const expected = Buffer.from(expectedSecret, "utf8");
  if (provided.length !== expected.length) {
    return false;
  }
  return crypto.timingSafeEqual(provided, expected);
}

const server = http.createServer(async (req, res) => {
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  res.setHeader("Cache-Control", "no-store");

  if (req.method === "GET" && req.url === "/healthz") {
    const configured = Boolean(GEMINI_API_KEY && BROKER_CLIENT_AUTH_SECRET);
    res.writeHead(configured ? 200 : 503);
    res.end(
      JSON.stringify({
        status: configured ? "READY" : "BLOCKED_SERVER_SECRETS_MISSING",
        allowedModel: ALLOWED_LIVE_MODEL
      })
    );
    return;
  }

  if (req.method !== "POST" || req.url !== "/v1/live-ephemeral-token") {
    res.writeHead(404);
    res.end(JSON.stringify({ error: "NOT_FOUND", code: "UNSUPPORTED_MODEL_OR_ENDPOINT" }));
    return;
  }

  if (!GEMINI_API_KEY || !BROKER_CLIENT_AUTH_SECRET) {
    res.writeHead(503);
    res.end(
      JSON.stringify({
        error: "Server-side GEMINI_API_KEY or BROKER_CLIENT_AUTH_SECRET is not configured.",
        code: "BAD_CREDENTIALS_OR_APP_CHECK"
      })
    );
    return;
  }

  // Enforce mandatory client authentication (never allow public unauthenticated token issuance)
  const authHeader = req.headers["authorization"] || "";
  if (!timingSafeBearerMatch(authHeader, BROKER_CLIENT_AUTH_SECRET)) {
    res.writeHead(401);
    res.end(
      JSON.stringify({
        error: "Unauthorized client request. Valid Bearer authentication required.",
        code: "BAD_CREDENTIALS_OR_APP_CHECK"
      })
    );
    return;
  }

  const now = Date.now();
  const newSessionExpireTime = new Date(now + 60 * 1000).toISOString(); // 60s to open WebSocket
  const expireTime = new Date(now + 10 * 60 * 1000).toISOString(); // 10m max session lifetime

  try {
    const upstreamResponse = await fetch(
      "https://generativelanguage.googleapis.com/v1alpha/auth_tokens",
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "x-goog-api-key": GEMINI_API_KEY
        },
        body: JSON.stringify({
          uses: 1,
          newSessionExpireTime,
          expireTime,
          bidiGenerateContentSetup: {
            model: ALLOWED_LIVE_MODEL,
            generationConfig: {
              responseModalities: ["AUDIO"]
            }
          }
        })
      }
    );

    if (!upstreamResponse.ok) {
      const status = upstreamResponse.status;
      const code =
        status === 401 || status === 403
          ? "BAD_CREDENTIALS_OR_APP_CHECK"
          : status === 400 || status === 404
            ? "UNSUPPORTED_MODEL_OR_ENDPOINT"
            : status === 429
              ? "QUOTA_EXHAUSTED"
              : "TRANSIENT_NETWORK_OR_SOCKET";
      res.writeHead(status);
      res.end(
        JSON.stringify({
          error: `Upstream auth_tokens creation failed with HTTP ${status}`,
          code
        })
      );
      return;
    }

    const tokenPayload = await upstreamResponse.json();
    res.writeHead(200);
    res.end(
      JSON.stringify({
        ephemeralToken: tokenPayload.name,
        modelId: ALLOWED_LIVE_MODEL,
        newSessionExpireTime,
        expireTime,
        uses: 1
      })
    );
  } catch (err) {
    res.writeHead(502);
    res.end(
      JSON.stringify({
        error: "Transient upstream network error while minting ephemeral Live token",
        code: "TRANSIENT_NETWORK_OR_SOCKET"
      })
    );
  }
});

server.listen(PORT, () => {
  console.log(
    JSON.stringify({
      service: "omnivoice-rebuild-live-token-broker",
      port: PORT,
      allowedModel: ALLOWED_LIVE_MODEL,
      secretsConfigured: Boolean(GEMINI_API_KEY && BROKER_CLIENT_AUTH_SECRET)
    })
  );
});
