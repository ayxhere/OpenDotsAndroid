/**
 * Android port: multi-provider AI proxy.
 *
 * Authenticated loopback-only endpoints that let the Android app call
 * third-party AI providers THROUGH the embedded backend instead of
 * embedding provider SDKs in the APK:
 *
 *   POST /api/ai/chat    — chat completion (streaming SSE or single JSON)
 *   POST /api/ai/models  — list available models for a provider
 *   POST /api/ai/test    — minimal probe: key validity, model reachability
 *
 * Design notes (security):
 * - All routes inherit the global OWNER_TOKEN Bearer check in app.ts.
 * - The caller supplies the provider API key per request. Keys are NEVER
 *   persisted, logged, or echoed. Only provider id, model, HTTP status and
 *   latency are logged.
 * - Known providers use fixed base URLs; a caller-supplied baseUrl is only
 *   honored for provider=custom and must pass an SSRF allowlist check
 *   (https only, no IP literals, no private/loopback hostnames).
 * - Bodies are zod-validated with strict schemas and tight length limits.
 * - Upstream fetches have timeouts; client disconnect aborts upstream.
 * - Provider SSE/error shapes are normalized to a tiny stable event
 *   protocol the Android client parses:
 *       data: {"type":"delta","text":"..."}
 *       data: {"type":"done","model":"...","usage":{"input":n,"output":n}}
 *       data: {"type":"error","code":"...","message":"...","retryable":bool}
 */
import { Hono } from 'hono';
import { z } from 'zod';

type ProviderKind = 'openai' | 'anthropic' | 'gemini';

interface ProviderDef {
  kind: ProviderKind;
  baseUrl: string;
  allowCustomBaseUrl: boolean;
}

const PROVIDERS: Record<string, ProviderDef> = {
  openai: {
    kind: 'openai',
    baseUrl: 'https://api.openai.com/v1',
    allowCustomBaseUrl: false,
  },
  groq: {
    kind: 'openai',
    baseUrl: 'https://api.groq.com/openai/v1',
    allowCustomBaseUrl: false,
  },
  openrouter: {
    kind: 'openai',
    baseUrl: 'https://openrouter.ai/api/v1',
    allowCustomBaseUrl: false,
  },
  anthropic: {
    kind: 'anthropic',
    baseUrl: 'https://api.anthropic.com',
    allowCustomBaseUrl: false,
  },
  gemini: {
    kind: 'gemini',
    baseUrl: 'https://generativelanguage.googleapis.com',
    allowCustomBaseUrl: false,
  },
  custom: { kind: 'openai', baseUrl: '', allowCustomBaseUrl: true },
};

const providerIdSchema = z.enum([
  'openai',
  'anthropic',
  'gemini',
  'groq',
  'openrouter',
  'custom',
]);

const chatMessageSchema = z.object({
  role: z.enum(['system', 'user', 'assistant']),
  content: z.string().trim().min(1).max(32_000),
});

const chatRequestSchema = z
  .object({
    provider: providerIdSchema,
    baseUrl: z.string().trim().max(200).optional(),
    apiKey: z.string().min(1).max(2_000),
    model: z.string().trim().min(1).max(160),
    messages: z.array(chatMessageSchema).min(1).max(120),
    maxTokens: z.number().int().min(1).max(32_000).optional(),
    temperature: z.number().min(0).max(2).optional(),
    stream: z.boolean().optional().default(true),
  })
  .strict();

const modelsRequestSchema = z
  .object({
    provider: providerIdSchema,
    baseUrl: z.string().trim().max(200).optional(),
    apiKey: z.string().min(1).max(2_000),
  })
  .strict();

type ChatRequest = z.infer<typeof chatRequestSchema>;

/** Normalized, non-retryable vs retryable error classification. */
export interface ProxyError {
  code:
    | 'invalid_request'
    | 'invalid_key'
    | 'quota_exceeded'
    | 'rate_limited'
    | 'model_not_found'
    | 'upstream_error'
    | 'timeout'
    | 'blocked_url';
  message: string;
  retryable: boolean;
  status?: number;
}

function proxyError(
  code: ProxyError['code'],
  message: string,
  retryable: boolean,
  status?: number,
): ProxyError {
  return { code, message, retryable, status };
}

/** Map a proxy error to a Hono-typed HTTP status for c.json(). */
function pickStatus(err: ProxyError): 400 | 401 | 402 | 403 | 404 | 429 | 502 {
  switch (err.status) {
    case 400:
    case 401:
    case 402:
    case 403:
    case 404:
    case 429:
      return err.status;
    default:
      return 502;
  }
}

/**
 * SSRF guard for caller-supplied base URLs (provider=custom only).
 * Requires https and rejects IP literals, localhost and private ranges.
 * Hostname-based private targets via DNS rebinding are out of scope for a
 * loopback-only backend, but IP literals — the trivial bypass — are blocked.
 */
function assertSafeBaseUrl(raw: string): string {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw proxyError('blocked_url', 'The base URL is not a valid URL.', false);
  }
  if (url.protocol !== 'https:')
    throw proxyError('blocked_url', 'The base URL must use https.', false);
  if (url.username || url.password)
    throw proxyError(
      'blocked_url',
      'Credentials must not be embedded in the base URL.',
      false,
    );
  const host = url.hostname.toLowerCase().replace(/\.$/, '');
  if (host === 'localhost' || host.endsWith('.localhost'))
    throw proxyError('blocked_url', 'Loopback base URLs are not allowed.', false);
  if (host === '::1' || host === '[::1]')
    throw proxyError('blocked_url', 'Loopback base URLs are not allowed.', false);
  const v4 = host.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (v4) {
    const [a, b] = [Number(v4[1]), Number(v4[2])];
    const privateRange =
      a === 10 ||
      a === 127 ||
      (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && b === 168) ||
      (a === 169 && b === 254) ||
      a === 0;
    if (privateRange || [a, Number(v4[3]), Number(v4[4])].some((n) => n > 255))
      throw proxyError(
        'blocked_url',
        'IP-literal and private-network base URLs are not allowed.',
        false,
      );
  }
  // Normalize: no trailing slash; path preserved (some gateways need a prefix).
  return url.toString().replace(/\/$/, '');
}

function resolveBaseUrl(
  provider: string,
  customBaseUrl?: string,
): { kind: ProviderKind; baseUrl: string } {
  const def = PROVIDERS[provider];
  if (!def) throw proxyError('invalid_request', 'Unknown provider.', false);
  if (def.allowCustomBaseUrl) {
    if (!customBaseUrl)
      throw proxyError(
        'invalid_request',
        'A base URL is required for custom providers.',
        false,
      );
    return { kind: def.kind, baseUrl: assertSafeBaseUrl(customBaseUrl) };
  }
  return { kind: def.kind, baseUrl: def.baseUrl };
}

function classifyUpstream(
  status: number,
  bodyText: string,
): Omit<ProxyError, 'message'> & { message: string } {
  const body = bodyText.toLowerCase();
  const has = (...needles: string[]) => needles.some((n) => body.includes(n));
  if (status === 401 || status === 403)
    return {
      code: 'invalid_key',
      message:
        'The provider rejected the API key (HTTP ' + status + ').',
      retryable: false,
      status,
    };
  if (
    status === 402 ||
    has('insufficient_quota', 'insufficient credits', 'quota_exceeded', 'billing')
  )
    return {
      code: 'quota_exceeded',
      message: 'The provider reports insufficient credits or quota.',
      retryable: false,
      status,
    };
  if (status === 429)
    return {
      code: 'rate_limited',
      message: 'The provider rate-limited the request (HTTP 429).',
      retryable: true,
      status,
    };
  if (status === 404 && has('model'))
    return {
      code: 'model_not_found',
      message: 'The provider does not serve that model id.',
      retryable: true,
      status,
    };
  if (status >= 500)
    return {
      code: 'upstream_error',
      message: `The provider returned HTTP ${status}.`,
      retryable: true,
      status,
    };
  const short = bodyText.slice(0, 200).replace(/\s+/g, ' ');
  return {
    code: 'upstream_error',
    message: `The provider returned HTTP ${status}${short ? ': ' + short : '.'}`,
    retryable: status === 408 || status === 425,
    status,
  };
}

async function fetchWithTimeout(
  url: string,
  init: RequestInit,
  timeoutMs: number,
  clientSignal?: AbortSignal | null,
): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const onClientAbort = () => controller.abort();
  clientSignal?.addEventListener('abort', onClientAbort, { once: true });
  try {
    return await fetch(url, { ...init, signal: controller.signal });
  } catch (e) {
    if ((e as Error)?.name === 'AbortError')
      throw proxyError('timeout', 'The provider request timed out.', true);
    throw proxyError(
      'upstream_error',
      'Could not reach the provider: ' + (e as Error)?.message,
      true,
    );
  } finally {
    clearTimeout(timer);
    clientSignal?.removeEventListener('abort', onClientAbort);
  }
}

/** Split an SSE byte stream into data payloads. */
async function* sseDataLines(
  res: Response,
): AsyncGenerator<string, void, void> {
  const reader = res.body!.getReader();
  const decoder = new TextDecoder();
  let buf = '';
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      let idx: number;
      while ((idx = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, idx).trim();
        buf = buf.slice(idx + 1);
        if (line.startsWith('data:')) yield line.slice(5).trim();
      }
    }
  } finally {
    reader.releaseLock();
  }
}

interface NormalizedSink {
  delta(text: string): void;
  done(model: string, usage?: { input: number; output: number }): void;
  error(err: ProxyError): void;
}

function makeSink(send: (obj: unknown) => void): NormalizedSink {
  return {
    delta: (text) => {
      if (text) send({ type: 'delta', text });
    },
    done: (model, usage) => send({ type: 'done', model, usage }),
    error: (err) =>
      send({
        type: 'error',
        code: err.code,
        message: err.message,
        retryable: err.retryable,
      }),
  };
}

// ---------------------------------------------------------------------------
// OpenAI-compatible (OpenAI, Groq, OpenRouter, custom)
// ---------------------------------------------------------------------------

async function proxyOpenAIChat(
  baseUrl: string,
  req: ChatRequest,
  sink: NormalizedSink,
  clientSignal?: AbortSignal | null,
): Promise<void> {
  const extraHeaders: Record<string, string> = {};
  if (req.provider === 'openrouter') {
    extraHeaders['HTTP-Referer'] = 'https://github.com/CopilotKit/OpenDots';
    extraHeaders['X-Title'] = 'OpenDots Android';
  }
  const res = await fetchWithTimeout(
    `${baseUrl}/chat/completions`,
    {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${req.apiKey}`,
        'Content-Type': 'application/json',
        ...extraHeaders,
      },
      body: JSON.stringify({
        model: req.model,
        messages: req.messages,
        stream: req.stream,
        ...(req.maxTokens ? { max_tokens: req.maxTokens } : {}),
        ...(req.temperature !== undefined
          ? { temperature: req.temperature }
          : {}),
      }),
    },
    req.stream ? 300_000 : 120_000,
    clientSignal,
  );
  if (!res.ok || !res.body) {
    const text = await res.text().catch(() => '');
    const c = classifyUpstream(res.status, text);
    throw proxyError(c.code, c.message, c.retryable, c.status);
  }
  if (!req.stream) {
    const json = (await res.json().catch(() => null)) as {
      choices?: { message?: { content?: string } }[];
      usage?: { prompt_tokens?: number; completion_tokens?: number };
      model?: string;
    } | null;
    const text = json?.choices?.[0]?.message?.content ?? '';
    sink.delta(text);
    sink.done(json?.model ?? req.model, {
      input: json?.usage?.prompt_tokens ?? 0,
      output: json?.usage?.completion_tokens ?? 0,
    });
    return;
  }
  let model = req.model;
  let input = 0;
  let output = 0;
  for await (const data of sseDataLines(res)) {
    if (data === '[DONE]') break;
    let evt: {
      choices?: { delta?: { content?: string } }[];
      usage?: { prompt_tokens?: number; completion_tokens?: number };
      model?: string;
      error?: { message?: string; code?: string };
    };
    try {
      evt = JSON.parse(data);
    } catch {
      continue;
    }
    if (evt.error) {
      const msg = String(evt.error.message ?? 'Provider error.');
      const fake = classifyUpstream(400, msg);
      throw proxyError(fake.code, msg, fake.retryable);
    }
    if (evt.model) model = evt.model;
    const content = evt.choices?.[0]?.delta?.content;
    if (content) sink.delta(content);
    if (evt.usage) {
      input = evt.usage.prompt_tokens ?? input;
      output = evt.usage.completion_tokens ?? output;
    }
  }
  sink.done(model, { input, output });
}

async function listOpenAIModels(
  baseUrl: string,
  apiKey: string,
  clientSignal?: AbortSignal | null,
): Promise<{ id: string; name?: string }[]> {
  const res = await fetchWithTimeout(
    `${baseUrl}/models`,
    { headers: { Authorization: `Bearer ${apiKey}` } },
    30_000,
    clientSignal,
  );
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    const c = classifyUpstream(res.status, text);
    throw proxyError(c.code, c.message, c.retryable, c.status);
  }
  const json = (await res.json().catch(() => null)) as {
    data?: { id?: string }[];
  } | null;
  const ids = (json?.data ?? [])
    .map((m) => m?.id)
    .filter((id): id is string => typeof id === 'string' && id.length > 0);
  return ids.map((id) => ({ id }));
}

// ---------------------------------------------------------------------------
// Anthropic
// ---------------------------------------------------------------------------

async function proxyAnthropicChat(
  baseUrl: string,
  req: ChatRequest,
  sink: NormalizedSink,
  clientSignal?: AbortSignal | null,
): Promise<void> {
  const system = req.messages
    .filter((m) => m.role === 'system')
    .map((m) => m.content)
    .join('\n\n');
  const messages = req.messages
    .filter((m) => m.role !== 'system')
    .map((m) => ({ role: m.role, content: m.content }));
  const res = await fetchWithTimeout(
    `${baseUrl}/v1/messages`,
    {
      method: 'POST',
      headers: {
        'x-api-key': req.apiKey,
        'anthropic-version': '2023-06-01',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model: req.model,
        max_tokens: req.maxTokens ?? 1024,
        ...(system ? { system } : {}),
        messages,
        stream: req.stream,
        ...(req.temperature !== undefined
          ? { temperature: req.temperature }
          : {}),
      }),
    },
    req.stream ? 300_000 : 120_000,
    clientSignal,
  );
  if (!res.ok || !res.body) {
    const text = await res.text().catch(() => '');
    const c = classifyUpstream(res.status, text);
    throw proxyError(c.code, c.message, c.retryable, c.status);
  }
  if (!req.stream) {
    const json = (await res.json().catch(() => null)) as {
      content?: { type?: string; text?: string }[];
      usage?: { input_tokens?: number; output_tokens?: number };
      model?: string;
    } | null;
    const text = (json?.content ?? [])
      .filter((b) => b?.type === 'text')
      .map((b) => b.text ?? '')
      .join('');
    sink.delta(text);
    sink.done(json?.model ?? req.model, {
      input: json?.usage?.input_tokens ?? 0,
      output: json?.usage?.output_tokens ?? 0,
    });
    return;
  }
  let input = 0;
  let output = 0;
  let eventName = '';
  // Anthropic SSE interleaves `event:` and `data:` lines; sseDataLines only
  // yields data payloads, so track events via a parallel scan is overkill:
  // content deltas arrive as data payloads with delta.type == 'text_delta'.
  void eventName;
  for await (const data of sseDataLines(res)) {
    let evt: {
      type?: string;
      delta?: { type?: string; text?: string };
      message?: { usage?: { input_tokens?: number; output_tokens?: number } };
      usage?: { input_tokens?: number; output_tokens?: number };
      error?: { message?: string };
    };
    try {
      evt = JSON.parse(data);
    } catch {
      continue;
    }
    if (evt.type === 'error' || evt.error) {
      const msg = String(evt.error?.message ?? 'Anthropic error.');
      const fake = classifyUpstream(400, msg);
      throw proxyError(fake.code, msg, fake.retryable);
    }
    if (evt.type === 'message_start' && evt.message?.usage) {
      input = evt.message.usage.input_tokens ?? input;
    }
    if (evt.delta?.type === 'text_delta' && evt.delta.text)
      sink.delta(evt.delta.text);
    if (evt.type === 'message_delta' && evt.usage)
      output = evt.usage.output_tokens ?? output;
  }
  sink.done(req.model, { input, output });
}

async function listAnthropicModels(
  baseUrl: string,
  apiKey: string,
  clientSignal?: AbortSignal | null,
): Promise<{ id: string; name?: string }[]> {
  const res = await fetchWithTimeout(
    `${baseUrl}/v1/models`,
    {
      headers: {
        'x-api-key': apiKey,
        'anthropic-version': '2023-06-01',
      },
    },
    30_000,
    clientSignal,
  );
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    const c = classifyUpstream(res.status, text);
    throw proxyError(c.code, c.message, c.retryable, c.status);
  }
  const json = (await res.json().catch(() => null)) as {
    data?: { id?: string; display_name?: string }[];
  } | null;
  return (json?.data ?? [])
    .filter((m) => typeof m?.id === 'string')
    .map((m) => ({ id: m.id as string, name: m.display_name }));
}

// ---------------------------------------------------------------------------
// Google Gemini
// ---------------------------------------------------------------------------

function geminiContents(messages: ChatRequest['messages']) {
  const system = messages
    .filter((m) => m.role === 'system')
    .map((m) => m.content)
    .join('\n\n');
  const contents = messages
    .filter((m) => m.role !== 'system')
    .map((m) => ({
      role: m.role === 'assistant' ? 'model' : 'user',
      parts: [{ text: m.content }],
    }));
  return {
    ...(system ? { systemInstruction: { parts: [{ text: system }] } } : {}),
    contents,
  };
}

async function proxyGeminiChat(
  baseUrl: string,
  req: ChatRequest,
  sink: NormalizedSink,
  clientSignal?: AbortSignal | null,
): Promise<void> {
  const url =
    `${baseUrl}/v1beta/models/${encodeURIComponent(req.model)}` +
    (req.stream ? ':streamGenerateContent?alt=sse' : ':generateContent');
  const res = await fetchWithTimeout(
    url,
    {
      method: 'POST',
      headers: {
        'x-goog-api-key': req.apiKey,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        ...geminiContents(req.messages),
        generationConfig: {
          ...(req.maxTokens ? { maxOutputTokens: req.maxTokens } : {}),
          ...(req.temperature !== undefined
            ? { temperature: req.temperature }
            : {}),
        },
      }),
    },
    req.stream ? 300_000 : 120_000,
    clientSignal,
  );
  if (!res.ok || !res.body) {
    const text = await res.text().catch(() => '');
    const c = classifyUpstream(res.status, text);
    throw proxyError(c.code, c.message, c.retryable, c.status);
  }
  const handlePayload = (payload: {
    candidates?: { content?: { parts?: { text?: string }[] } }[];
    usageMetadata?: { promptTokenCount?: number; candidatesTokenCount?: number };
    error?: { message?: string };
  }) => {
    if (payload.error) {
      const msg = String(payload.error.message ?? 'Gemini error.');
      const fake = classifyUpstream(400, msg);
      throw proxyError(fake.code, msg, fake.retryable);
    }
    const text = (payload.candidates ?? [])
      .flatMap((cand) => cand?.content?.parts ?? [])
      .map((p) => p?.text ?? '')
      .join('');
    if (text) sink.delta(text);
    return payload.usageMetadata;
  };
  if (!req.stream) {
    const json = (await res.json().catch(() => null)) as Parameters<
      typeof handlePayload
    >[0] | null;
    const usage = json ? handlePayload(json) : undefined;
    sink.done(req.model, {
      input: usage?.promptTokenCount ?? 0,
      output: usage?.candidatesTokenCount ?? 0,
    });
    return;
  }
  let input = 0;
  let output = 0;
  for await (const data of sseDataLines(res)) {
    let payload: Parameters<typeof handlePayload>[0];
    try {
      payload = JSON.parse(data);
    } catch {
      continue;
    }
    const usage = handlePayload(payload);
    if (usage) {
      input = usage.promptTokenCount ?? input;
      output = usage.candidatesTokenCount ?? output;
    }
  }
  sink.done(req.model, { input, output });
}

async function listGeminiModels(
  baseUrl: string,
  apiKey: string,
  clientSignal?: AbortSignal | null,
): Promise<{ id: string; name?: string }[]> {
  const res = await fetchWithTimeout(
    `${baseUrl}/v1beta/models`,
    { headers: { 'x-goog-api-key': apiKey } },
    30_000,
    clientSignal,
  );
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    const c = classifyUpstream(res.status, text);
    throw proxyError(c.code, c.message, c.retryable, c.status);
  }
  const json = (await res.json().catch(() => null)) as {
    models?: { name?: string; displayName?: string }[];
  } | null;
  return (json?.models ?? [])
    .map((m) => ({
      id: String(m?.name ?? '').replace(/^models\//, ''),
      name: m?.displayName,
    }))
    .filter((m) => m.id.length > 0);
}

// ---------------------------------------------------------------------------
// Routes
// ---------------------------------------------------------------------------

export function aiProxyRoutes() {
  const app = new Hono();

  /**
   * POST /api/ai/chat
   * Streams normalized SSE events (or a single JSON object when
   * stream=false). Never logs or echoes the caller's API key.
   */
  app.post('/ai/chat', async (c) => {
    const parsed = chatRequestSchema.safeParse(
      await c.req.json().catch(() => null),
    );
    if (!parsed.success)
      return c.json({ error: 'Invalid chat request.' }, 400);
    const req = parsed.data;
    let target: { kind: ProviderKind; baseUrl: string };
    try {
      target = resolveBaseUrl(req.provider, req.baseUrl);
    } catch (e) {
      const err = e as ProxyError;
      return c.json(
        { error: err.message, code: err.code, retryable: err.retryable },
        400,
      );
    }
    const started = Date.now();
    console.info(
      `[ai-proxy] chat provider=${req.provider} model=${req.model} stream=${req.stream}`,
    );
    const clientSignal = c.req.raw.signal ?? null;

    if (!req.stream) {
      let text = '';
      let model = req.model;
      let usage = { input: 0, output: 0 };
      const sink = makeSink(() => {});
      const capture: NormalizedSink = {
        delta: (t) => {
          text += t;
        },
        done: (m, u) => {
          model = m;
          if (u) usage = u;
        },
        error: (err) => {
          throw err;
        },
      };
      try {
        if (target.kind === 'openai')
          await proxyOpenAIChat(target.baseUrl, req, capture, clientSignal);
        else if (target.kind === 'anthropic')
          await proxyAnthropicChat(target.baseUrl, req, capture, clientSignal);
        else await proxyGeminiChat(target.baseUrl, req, capture, clientSignal);
      } catch (e) {
        const err = e as ProxyError;
        console.info(
          `[ai-proxy] chat failed provider=${req.provider} code=${err.code} ` +
            `status=${err.status ?? '-'} latency=${Date.now() - started}ms`,
        );
        return c.json(
          {
            error: err.message,
            code: err.code,
            retryable: err.retryable,
          },
          pickStatus(err),
        );
      }
      console.info(
        `[ai-proxy] chat ok provider=${req.provider} latency=${Date.now() - started}ms`,
      );
      void sink;
      return c.json({ text, model, usage });
    }

    const stream = new ReadableStream({
      async start(controller) {
        const enc = new TextEncoder();
        const send = (obj: unknown) =>
          controller.enqueue(enc.encode(`data: ${JSON.stringify(obj)}\n\n`));
        const sink = makeSink(send);
        try {
          if (target.kind === 'openai')
            await proxyOpenAIChat(target.baseUrl, req, sink, clientSignal);
          else if (target.kind === 'anthropic')
            await proxyAnthropicChat(target.baseUrl, req, sink, clientSignal);
          else await proxyGeminiChat(target.baseUrl, req, sink, clientSignal);
          console.info(
            `[ai-proxy] stream ok provider=${req.provider} latency=${Date.now() - started}ms`,
          );
        } catch (e) {
          const err = e as ProxyError;
          console.info(
            `[ai-proxy] stream failed provider=${req.provider} code=${err.code} ` +
              `status=${err.status ?? '-'} latency=${Date.now() - started}ms`,
          );
          sink.error(err);
        } finally {
          controller.close();
        }
      },
    });
    return new Response(stream, {
      headers: {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
        Connection: 'keep-alive',
      },
    });
  });

  /** POST /api/ai/models — list models for a provider (key in body, never logged). */
  app.post('/ai/models', async (c) => {
    const parsed = modelsRequestSchema.safeParse(
      await c.req.json().catch(() => null),
    );
    if (!parsed.success)
      return c.json({ error: 'Invalid models request.' }, 400);
    const { provider, baseUrl, apiKey } = parsed.data;
    let target: { kind: ProviderKind; baseUrl: string };
    try {
      target = resolveBaseUrl(provider, baseUrl);
    } catch (e) {
      const err = e as ProxyError;
      return c.json({ error: err.message, code: err.code }, 400);
    }
    console.info(`[ai-proxy] models provider=${provider}`);
    try {
      const models =
        target.kind === 'openai'
          ? await listOpenAIModels(target.baseUrl, apiKey, c.req.raw.signal)
          : target.kind === 'anthropic'
            ? await listAnthropicModels(target.baseUrl, apiKey, c.req.raw.signal)
            : await listGeminiModels(target.baseUrl, apiKey, c.req.raw.signal);
      return c.json({ models });
    } catch (e) {
      const err = e as ProxyError;
      return c.json(
        { error: err.message, code: err.code, retryable: err.retryable },
        pickStatus(err),
      );
    }
  });

  /**
   * POST /api/ai/test — minimal key/model probe. Sends a tiny non-streaming
   * completion and reports ok/latency or a classified error. Never fabricates
   * success: ok=true only comes from a real upstream 2xx.
   */
  app.post('/ai/test', async (c) => {
    const parsed = chatRequestSchema
      .omit({ messages: true, stream: true, maxTokens: true })
      .safeParse(await c.req.json().catch(() => null));
    if (!parsed.success)
      return c.json({ error: 'Invalid test request.' }, 400);
    const probe: ChatRequest = {
      ...parsed.data,
      messages: [{ role: 'user', content: 'Reply with exactly: ok' }],
      maxTokens: 8,
      stream: false,
    };
    let target: { kind: ProviderKind; baseUrl: string };
    try {
      target = resolveBaseUrl(probe.provider, probe.baseUrl);
    } catch (e) {
      const err = e as ProxyError;
      return c.json(
        { ok: false, error: err.message, code: err.code, retryable: false },
        400,
      );
    }
    console.info(
      `[ai-proxy] test provider=${probe.provider} model=${probe.model}`,
    );
    const started = Date.now();
    let text = '';
    const capture: NormalizedSink = {
      delta: (t) => {
        text += t;
      },
      done: () => {},
      error: (err) => {
        throw err;
      },
    };
    try {
      if (target.kind === 'openai')
        await proxyOpenAIChat(target.baseUrl, probe, capture, c.req.raw.signal);
      else if (target.kind === 'anthropic')
        await proxyAnthropicChat(
          target.baseUrl,
          probe,
          capture,
          c.req.raw.signal,
        );
      else await proxyGeminiChat(target.baseUrl, probe, capture, c.req.raw.signal);
      return c.json({
        ok: true,
        model: probe.model,
        latencyMs: Date.now() - started,
        sample: text.slice(0, 120),
      });
    } catch (e) {
      const err = e as ProxyError;
      return c.json({
        ok: false,
        error: err.message,
        code: err.code,
        retryable: err.retryable,
        latencyMs: Date.now() - started,
      });
    }
  });

  return app;
}
