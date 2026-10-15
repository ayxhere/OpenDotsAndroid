# OpenDots → Android — Phase 1: Compatibility Report & Implementation Plan

**Date:** 2026-10-15
**Source audited:** OpenDots `main` (v0.1.0, engines: `node >= 24`), read-only audit of 82 TS/TSX files + tests + env + compose configs.
**Port target:** Kotlin + Jetpack Compose UI, embedded Node.js (digidem `nodejs-mobile` full build @ Node 24.x, `libnode.so` via JNI), loopback HTTP `127.0.0.1:4310`, app-private SQLite.

---

## Executive verdict

**The port is feasible and unusually clean.** The entire OpenDots server is pure JavaScript with **zero native npm modules** — persistence is `node:sqlite` (Node built-in, no better-sqlite3/sqlite3), the HTTP stack is Hono on `@hono/node-server`, and there is no `child_process`/`worker_threads` usage in the server. The only hard version requirement (Node ≥ 24, `node:sqlite` present) is satisfied by the digidem nodejs-mobile **full** build (Node 24.x; the *lite* build drops `node:sqlite` and must never be used).

Key correction vs. naive assumptions: **chat streaming is NOT plain SSE.** OpenDots runs CopilotRuntime in **Intelligence mode**: `POST /api/copilotkit/agent/{dotId}/run` returns JSON join-credentials, and all AG-UI events flow over a **Phoenix-protocol WebSocket** to the CopilotKit Intelligence cloud gateway. A native Kotlin client must implement REST run/connect/stop + a small Phoenix channel client + AG-UI event assembly. `IntelligenceAgent` in `@copilotkit/core` (used by `src/server/headless.ts`) is the shipped proof that a non-browser client works.

**Honest non-negotiables:**
- Zero AI without `INTELLIGENCE_API_KEY` — every chat/thread/scheduled path returns 503 without it. There is no local-model fallback. The app must show a real setup screen.
- Chat history lives in Intelligence cloud, not local SQLite (local holds only thread bindings/metadata).
- The Docker/OpenBot computer fleet and the Playwright browser service **cannot run inside an APK**. Both remain as optional *remote* connections, exactly as the server's `configured=false` path already supports.

---

## 1. Compatibility matrix

### 1.1 Ready to run locally on Android (no server changes)

| Feature | Notes |
|---|---|
| Hono server on `127.0.0.1:4310`, full auth middleware (Bearer, host/origin checks, 1 MB body cap, JSON-only) | `app.ts:33-95`, `index.ts:17-18` |
| All SQLite persistence + migrations | `node:sqlite` `DatabaseSync`, WAL, guarded `ALTER TABLE ADD COLUMN`; `store.ts`, `workspace.ts` |
| Spaces / Dots / Conversations / Memories / Tasks CRUD | Full REST, ownership via `requireThread` + `dot_spaces` grants |
| Pages CRUD with revision-guarded autosave | `PATCH …/pages/:id` + `expectedRevision`; 409 on stale — client must reload/retry, never blind-overwrite |
| Both human-approval flows | Page review (`page_reviews` receipts) + connection action (`mcp_approvals` TTL 1h, `claimAction` idempotency, 409 duplicates) |
| MCP connections client | Pure HTTPS StreamableHTTP, Bearer never sent to UI |
| Background `Runner` (scheduled tasks) | In-process, SQLite leases; ticks only while Node process lives |
| `GET /api/state`, `GET /api/workspace` setup probes | Drives setup-guidance UI 1:1 (`missing[]`) |
| `DotAgent` + server-side tools (page, research, learned-skill) | Headless turns already non-DOM via `IntelligenceAgent` |
| Web search (Parallel MCP) | Plain HTTPS to `search.parallel.ai/mcp`; anonymous light use OK |

### 1.2 Requires compatibility modification

| Change | Why |
|---|---|
| **Add `GET /api/health`** (unauthenticated, returns `{ok, ready, setup}` only) | No health route exists today; Kotlin needs a startup/readiness gate (spec §7) |
| **`DATABASE_PATH` → absolute app-private path** (`context.filesDir`) | Default is relative `data/opendots.sqlite`; same for `./dist/client` static root |
| **MCP bearer tokens: plaintext in SQLite today** (`connection-store.ts:14`) → Keystore-backed EncryptedSharedPreferences (or encrypted column) | Spec §7 security bar |
| **Shutdown hook** — `shutdown.ts` uses SIGTERM/SIGINT which Android won't deliver | Kotlin must invoke an internal stop explicitly; never expose it on the API |
| **Telemetry off by default** | Posts to `telemetry.copilotkit.ai` unless `DO_NOT_TRACK=1` / `COPILOTKIT_TELEMETRY_DISABLED=true` |
| **Scheduler under Doze** | `Runner` dies with the process → foreground service + WorkManager-triggered starts; surface "scheduler paused while app closed" honestly |
| Skip `dist/client` in the APK | Kotlin replaces the React client; saves size |
| Chat streaming in Kotlin | REST run/connect/stop + Phoenix WS client + AG-UI event assembly; history via `GET /threads/{id}/messages` (skip WS replay for history) |
| HITL approvals in Kotlin | Native Approve/Decline cards; resume run with `resume[]`; **decline is local-only, never a server call** |
| Editor in Kotlin | Native markdown editor honoring the `inspectMarkdown` token allowlist (H1–H3, bold/italic/strike/code, lists incl. nested task lists, non-resizable tables, blockquote, code blocks, links, hr); raw source-mode fallback; 800 ms debounce autosave port with `saved|dirty|saving|error|conflict` states; drafts are in-memory today — **persist draft to disk** (spec §12 requires no draft loss) |
| Voice | Server `voice.ts` unchanged; Kotlin implements WebRTC via `org.webrtc` (SDP offer → `POST /voice/calls`) + `RECORD_AUDIO` runtime permission |

### 1.3 Requires a remote service or credentials

| Feature | Service / credential |
|---|---|
| All AI conversations | **CopilotKit Intelligence** — `INTELLIGENCE_API_KEY`/`CPK_INTELLIGENCE_API_KEY` (+ optional URL/WS overrides). **Hard gate: zero chat without it.** |
| Model execution | `OPENAI_API_KEY` + `OPENAI_MODEL` (any OpenAI-compatible `OPENAI_BASE_URL`) |
| Voice | OpenAI Realtime (`VOICE_API_KEY` + `VOICE_MODEL`, default voice `marin`) |
| Web research (default) | Parallel MCP (`search.parallel.ai`; optional `PARALLEL_API_KEY` for higher limits) |
| Read-only browser mode | Optional remote `BROWSER_URL` + `BROWSER_SECRET` (self-run Playwright service) |
| Slack channel | Intelligence-managed channel: `SLACK_CHANNEL_NAME/TEAM_ID/USER_IDS`, declared once via CopilotKit CLI; needs public HTTPS for local eval path (hosted Intelligence is the realistic phone setup) |
| MCP integrations | User's own MCP endpoints + bearer tokens (per-Dot settings) |

### 1.4 Requires an isolated external computer/browser service

| Feature | Reality |
|---|---|
| Dot computers (one container per Dot: browser/files/shell tools, HMAC per-Dot auth, human takeover) | Needs Docker Engine + Linux containers + Bun supervisor + `/var/run/docker.sock` — **impossible inside an APK**. Ship unconfigured (server already degrades to `computer.configured=false` + setup guidance); add optional `COMPUTER_SUPERVISOR_URL`/tokens settings for a user-run Docker host. No server code change. |
| Playwright read-only browser service (`:4311`) | Playwright publishes no Android browser builds; ~150 MB desktop Chromium can't be bundled. Same optional-remote treatment; default stays `WEB_SEARCH_PROVIDER=parallel`. |

### 1.5 Unsupported on Android (with alternative)

| Feature | Alternative |
|---|---|
| Full isolated desktop browser *in the app* | Marked unavailable; `computer_*` tools hidden unless a remote supervisor is configured; research via Parallel stays. **Workspace + document editor work fully without it (spec §9 satisfied).** |
| Inbound Slack/phone-call reception | Platform/NAT limits; outbound text only, channels marked unavailable. Use local Android notifications for approval requests instead. |
| Unrestricted background agent execution | Android Doze; WorkManager-driven scheduled starts reusing the existing task lease/status machine (`queued|running|paused|completed|failed|interrupted|cancelled`). |
| Exact-second scheduling while app is killed | Honest "paused while app closed" state in Settings. |
| Background WebRTC voice | Foreground-only voice, or defer to a later phase. |

---

## 2. Exact server-side changes required (small, named)

1. `src/server/index.ts` (or `app.ts`): add `GET /api/health` → `{ok:true, ready, version}` — no secrets, unauthenticated per spec §7.
2. Env injection from Kotlin: `DATABASE_PATH=<filesDir>/opendots.sqlite`, `PORT=4310`, `HOST=127.0.0.1`, `OWNER_TOKEN=<256-bit per-install token>` (via `WAGW`-style env or CLI — reuse the AyXWhatsApp `GatewayAuth.kt` pattern: SecureRandom → EncryptedSharedPreferences → env to Node).
3. `DO_NOT_TRACK=1` and `COPILOTKIT_TELEMETRY_DISABLED=true` unless user opts in.
4. No `dist/client` in the APK; `APP_ORIGIN` left at dev defaults is fine since the SPA is not served — or serve nothing and 404 `/*`.
5. Upstream code preserved otherwise: no dependency removal, no feature deletion.

---

## 3. Wire contract for the Kotlin UI (summary)

Base `http://127.0.0.1:4310/api`, `Authorization: Bearer <OWNER_TOKEN>`, JSON on non-GET, 1 MB cap.

- **Readiness:** `GET /api/health` (new); `GET /api/state` → `{settings, tasks, memories, mode, configured, setup.missing[]}`.
- **Workspace:** `GET /api/workspace` → `{spaces, dots, conversations, setup, calls}`; `POST /spaces {name, description?}`; `POST /dots {name, instructions(3–2000), researchAllowed, memoryAllowed, spaceId(required), …}`; `PUT /dots/:id`.
- **Pages:** `GET|POST /spaces/:sid/pages`; `GET|PATCH|DELETE /spaces/:sid/pages/:pid`; PATCH `{title, content, parentId, expectedRevision}` → **409** on stale (reload, keep local draft, never auto-merge).
- **Conversations:** `POST /conversations {dotId, title?}` → 201 thread (created in Intelligence, bound locally); `POST /conversations/:id/page {title}` (save chat as page); `GET …/page-context`; `GET …/capture`.
- **Chat (Intelligence mode):** `POST /api/copilotkit/agent/{dotId}/connect {threadId, runId, messages:[], tools:[], …}` → `{threadId, joinToken, realtime:{clientUrl, topic}}`; WS to `clientUrl?join_token=<joinToken>`, join `thread:<threadId>` with `{stream_mode:"connect", last_seen_event_id}` (replay) or `{stream_mode:"run", run_id}`; events as Phoenix channel event `ag_ui_event` with AG-UI payloads (`TEXT_MESSAGE_*`, `TOOL_CALL_*`, `RUN_FINISHED`, `RUN_ERROR`, …); cursor = `payload.metadata.cpki_event_id`. Turn: `POST …/agent/{dotId}/run {threadId, runId, messages:[{id,role,content}], tools:[…], …}` → join channel in run mode; stop via `POST …/stop/{threadId}` or channel push `stop_run`. History shortcut: `GET /api/copilotkit/threads/{threadId}/messages` (also `/events`, `/state`). `GET /api/copilotkit/info` → `{mode:"intelligence", …}`.
- **Approvals:** page review — `GET /conversations/:id/reviewed-page/:toolCallId` then `POST …/reviewed-page {title, content, spaceId, toolCallId}`; connection actions — `GET …/connection-approvals/:approvalId` (TTL 1 h) → `POST …/connection-actions {approvalId, toolCallId}` (idempotent, 409 on duplicate/different approval). **Decline = local-only.**
- **Voice:** `POST /voice/calls {threadId, sdp}` → `{id, sdp}`; `GET /voice/calls/:id`; `POST …/{active,compute,end}`.
- **Tasks/memories/settings:** `POST /tasks {prompt, intervalSeconds?, threadId?}`, `GET /tasks/:id`, `POST /tasks/:id/actions {run|pause|cancel}`, `PUT /tasks/:id/schedule`; `POST/PUT/DELETE /memories[/:id] {text}`; `PATCH /settings {name?, paused?, researchAllowed?, memoryAllowed?}`.

---

## 4. Implementation plan (adjusted to findings)

- **Phase 1 (done):** audit + this report. Blockers: Intelligence key (credential, not code), Node≥24 full build verification, health endpoint.
- **Phase 2:** Android skeleton (Kotlin/Compose, `minSdk 24`, AGP stable) + embedded `libnode.so` (digidem Node 24 full) PoC: JNI bridge, asset extraction (`node_modules` preinstalled reproducibly), single-instance guard, log surfacing, graceful stop. **Verify `process.versions` reports Node 24 and `node:sqlite` loads before any Kotlin networking code.**
- **Phase 3:** Secure API layer: per-install 256-bit token (Keystore/EncryptedSharedPreferences), loopback-only `:4310`, health + readiness checks, `GET /api/state` wiring, fail-closed init.
- **Phase 4:** SQLite via app-private `DATABASE_PATH`; migration verification; draft persistence for the editor; export/backup.
- **Phase 5:** Native Spaces/Pages/Dots screens over REST; pages editor with autosave state machine (incl. 409 conflict path); markdown source mode.
- **Phase 6:** Real AI chat — Kotlin Phoenix WS client + AG-UI event assembly, streaming text, tool-call display, retry/cancel, history via REST; setup screen when Intelligence key/model missing (drive from `setup.missing[]`).
- **Phase 7:** Tool execution + both approval flows with exact idempotency/TTL semantics; orphaned `running` receipts treated as failed, never retried into 409.
- **Phase 8:** Optional integrations — voice via `org.webrtc` (server unchanged); computer/browser/Slack as configured-remote settings; MCP connection management.
- **Phase 9:** Lifecycle hardening — foreground service + WorkManager, crash recovery, battery-respectful behavior, input validation/timeouts review.
- **Phase 10:** Tests (Node startup, auth ±, missing token, DB init/migrations, Dot/Space ACLs, pages autosave incl. failed-save recovery, chat, provider-missing, approval idempotency, restart recovery, timeouts/malformed responses, network isolation) + GitHub Actions workflow (Node 24 toolchain, `npm ci`, typecheck, lint, vitest, Android debug APK build, artifact upload). **No Android UI tests claimed unless run on emulator/device.**

**Not started / not promised:** any build is unverified until CI or a device build says otherwise (standing rule: pushed ≠ done).

---

## 5. Known limitations (will ship in the UI honestly)

1. AI requires the user's own CopilotKit Intelligence key + model key — shown as setup steps, never faked.
2. Chat history is cloud-backed (Intelligence); offline history needs a connection or a future local mirror.
3. Computers and the Playwright browser need external hosts; marked unavailable until configured remotely.
4. Scheduler runs only while the app's backend is alive (foreground service / WorkManager wake).
5. Voice needs OpenAI key + `RECORD_AUDIO` permission; optional and never breaks text chat.
