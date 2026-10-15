# Android compatibility patches

Minimal, named modifications to upstream OpenDots (`v0.1.0`). Upstream source
is otherwise preserved byte-for-byte. Each patch is a unified diff applied
with `patch -p1` to a clean copy of the upstream tree during the backend
bundle build (`../scripts/build-backend-bundle.sh`).

1. **01-api-health.patch** — adds `GET /api/health`, a minimal *unauthenticated*
   health probe returning only `{ok: true, version}` (no sensitive data).
   The `/api/*` middleware bypasses the owner-token check for exactly this
   path; host/origin/`sec-fetch-site` checks still apply. Required by the
   Android runtime manager for startup/readiness gating.
2. **02-skip-spa-when-unbundled.patch** — serves the React SPA (`dist/client`)
   only when its build output is actually present. The APK replaces the web
   UI with the native UI and does not bundle `dist/client`; without this
   guard the catch-all static route would fail on every non-API request.
3. **03-ai-proxy.patch** — adds `src/server/ai-proxy.ts` plus its mount in
   `app.ts`: authenticated loopback-only multi-provider AI proxy used by the
   Android multi-provider feature (`POST /api/ai/chat`, `POST /api/ai/models`,
   `POST /api/ai/test`). Provider API keys are supplied per request and are
   never persisted, logged, or echoed; only provider id, model, HTTP status
   and latency are logged. Caller-supplied base URLs (custom providers only)
   must pass an SSRF allowlist (https, no IP literals, no private/loopback
   hosts). Upstream SSE/error shapes (OpenAI-compatible, Anthropic, Gemini)
   are normalized to a tiny stable event protocol for the Android client.
   Inherits the existing `/api/*` OWNER_TOKEN Bearer check; no new
   unauthenticated surface.

No dependencies are removed or replaced; no features are disabled.
