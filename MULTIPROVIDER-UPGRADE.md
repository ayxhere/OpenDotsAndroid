# OpenDots Android — Multi-Provider AI Upgrade

Built: 2026-10-15. Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (~299 MB).

## What was added

### 1. AI Providers (`Settings → AI Providers → Manage`)
- Supported: **OpenAI, Anthropic Claude, Google Gemini, Groq, OpenRouter**,
  plus **custom OpenAI-compatible** endpoints (configurable base URL, model, key).
  "ChatGPT" uses the official OpenAI API — a ChatGPT subscription does **not**
  include API credits; the app never assumes otherwise.
- Add multiple keys/providers, enable/disable, reorder failover priority (↑↓),
  edit, delete. Model list fetched live from each provider's API; manual model
  ID entry as fallback.
- **Test connection** per provider (or while adding): sends a real tiny
  completion through the backend. `ok:true` only comes from a real upstream 2xx —
  never fabricated.
- Keys stored in **Android Keystore-backed EncryptedSharedPreferences**,
  per-provider (`provider_key_<id>`). Never in code, logs, or the APK.

### 2. AI router with automatic failover (`AI Chat` tab, provider = "Auto")
- Tries providers in priority order; fails over on timeout, rate limit (429),
  5xx outage, or unknown model.
- **Never retries invalid credentials**: 401/403 and quota failures mark that
  provider bad for the session and move on; each provider tried at most once
  per message.
- Distinguishes: invalid key (stop, don't retry) vs rate limit (try next) vs
  quota/insufficient credits (try next provider, don't retry same key).
- Router settings: auto-failover on/off, **free-model-only fallback**,
  **max cost per run (USD)**, max attempts, per-attempt timeout.
- Paid→paid fallback is blocked when free-only mode is on or the cost cap
  would be breached. Spend shown as a chip; pricing table is approximate and
  clearly informational.
- Context preserved: the same message list goes to every attempt, strictly
  sequential (no duplicates). This path is plain completions — no tool calls,
  so duplicate tool execution is impossible by construction.
- Answer badge shows **provider · model · fallback**; total failure shows the
  real per-provider errors — never a fabricated reply. Cancel any time.

### 3. Voice chat (in AI Chat)
- Mic button → Android SpeechRecognizer STT with live transcription.
  Requires RECORD_AUDIO (runtime permission prompt).
- Speaker toggle → Android TTS reads answers aloud; Replay / Stop speech.
- Interrupt: starting the mic or Stop halts speech immediately.
- Honest fallback: STT → text → TTS. No provider realtime-audio is assumed;
  none of the proxied providers expose realtime audio through this backend.

### 4. Group chat (`AI Chat → Groups`)
- Create/rename/delete groups; add agents from configured providers (custom
  display names, optional per-agent model override).
- Modes: **@mention** (only mentioned agents reply), **round-robin** (each
  agent replies once), **moderated** (agents discuss + one moderator summary).
- Badges: name · provider · model, timestamps, streaming with typing indicators.
- Loop prevention: each agent max once per turn, no consecutive double-speak,
  hard turn cap, cost cap, stop button. Shared transcript for all agents;
  histories persist per group across restarts.

### 5. Security
- All AI traffic: app → `127.0.0.1:4310/api/ai/*` (OWNER_TOKEN Bearer, same as
  all `/api/*`) → provider. Backend never persists/logs keys; only provider,
  model, status, latency are logged.
- New backend endpoints (`03-ai-proxy.patch`): `POST /ai/chat` (streaming SSE,
  normalized events), `POST /ai/models`, `POST /ai/test`. Strict zod schemas,
  timeouts, client-disconnect aborts upstream.
- SSRF guard for custom base URLs: https only, no credentials in URL, no IP
  literals, no private/loopback hosts.

## Required permissions (new)
- `RECORD_AUDIO` — voice input only, requested at runtime when you tap the mic.

## Configuration
1. Install APK, let the backend start (Home shows Ready).
2. Settings → AI Providers → Manage → + Add provider → choose type, name,
   paste API key, Fetch models (or type model ID), Test connection, Save.
3. AI Chat tab → pick a provider or Auto → chat. Router settings via the
   "Router" button on the providers screen.

## Test results
- Backend smoke: **18/18 pass** (incl. 6 new: auth, validation, SSRF http +
  loopback blocks, models 400, bogus-key honest `ok:false`; persistence across
  restart verified).
- Unit tests: **35/35 pass** (`AiRouterTest` 16, `GroupChatRunnerTest` 9,
  `ModelsTest` 10): failover, invalid-key no-retry, quota, rate-limit,
  cancellation, context preservation, cost cap, free-only fallback, attempt
  bounds, loop prevention, turn/cost caps, mention/round-robin/moderated modes.

## Known limitations (honest)
- Not device-tested: no emulator/physical-device install in this environment.
  Embedded Node 24 + `node:sqlite` + proxy streaming on-device is
  host-verified only — install and check Home → backend Ready, then AI Chat.
- Real provider chat needs your own API keys; without them the app shows
  setup guidance / honest errors, never fake replies.
- Existing CopilotKit/Dot chat is untouched (still needs INTELLIGENCE_API_KEY).
  Approvals/tool flows remain on that path; the multi-provider path is
  completions-only (no tools).
- Cost estimates use an approximate built-in pricing table; unknown models
  count as $0 and flag the estimate unreliable.
- Group moderation summary is best-effort (skipped silently on failure).
- Voice: STT/TTS quality depends on the device's recognizer and TTS engine.
