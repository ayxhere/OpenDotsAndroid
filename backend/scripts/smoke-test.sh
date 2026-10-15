#!/usr/bin/env bash
# Boots the built backend bundle with the pruned node_modules and exercises
# the real API: health, auth accept/reject, spaces/dots CRUD, honest 503
# without an Intelligence key, and SQLite persistence across a restart.
# Usage: smoke-test.sh <bundle_zip>
set -euo pipefail

ZIP="${1:?bundle zip}"
# /tmp is a small tmpfs on some machines; keep the large extraction on disk.
export TMPDIR="${TMPDIR:-$HOME/workspace/.smoke-tmp}"
mkdir -p "$TMPDIR"
WORK="$(mktemp -d)"
trap 'kill ${SERVER_PID:-} 2>/dev/null; rm -rf "$WORK"' EXIT

python3 -c "
import zipfile, sys
with zipfile.ZipFile(sys.argv[1]) as z:
    z.extractall(sys.argv[2])
print('extracted')
" "$ZIP" "$WORK/bundle"
cd "$WORK/bundle"

TOKEN="smoketest-token-0123456789abcdef0123456789abcdef"
PORT=4321
export HOST=127.0.0.1 PORT DATABASE_PATH="$WORK/smoke.sqlite" OWNER_TOKEN="$TOKEN"
export DO_NOT_TRACK=1 COPILOTKIT_TELEMETRY_DISABLED=1 NODE_ENV=production

node dist/server/server/index.js > "$WORK/server.log" 2>&1 &
SERVER_PID=$!

pass=0; fail=0
check() { # check <name> <command...>
  local name="$1"; shift
  if "$@" > /dev/null 2>&1; then echo "PASS: $name"; pass=$((pass+1));
  else echo "FAIL: $name"; fail=$((fail+1)); fi
}

echo "==> Waiting for /api/health"
for i in $(seq 1 60); do
  if curl -sf "http://127.0.0.1:$PORT/api/health" | grep -q '"ok":true'; then break; fi
  sleep 0.5
  if [ "$i" = 60 ]; then echo "server log:"; cat "$WORK/server.log"; echo "FATAL: no health"; exit 1; fi
done
echo "PASS: health 200 {ok:true}"

AUTH="Authorization: Bearer $TOKEN"
check "state without token -> 401" \
  bash -c "! curl -sf http://127.0.0.1:$PORT/api/state"
check "state with token -> 200" \
  curl -sf -H "$AUTH" http://127.0.0.1:$PORT/api/state
check "wrong token -> 401" \
  bash -c "! curl -sf -H 'Authorization: Bearer wrong' http://127.0.0.1:$PORT/api/state"

SPACE_ID="$(curl -sf -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"name":"Smoke Space"}' http://127.0.0.1:$PORT/api/spaces | node -p "JSON.parse(require('fs').readFileSync(0,'utf8')).id")"
check "create space" test -n "$SPACE_ID"

DOT_ID="$(curl -sf -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"name\":\"Smoke Dot\",\"instructions\":\"You are a smoke test.\",\"spaceId\":\"$SPACE_ID\",\"researchAllowed\":false,\"memoryAllowed\":false}" \
  http://127.0.0.1:$PORT/api/dots | node -p "JSON.parse(require('fs').readFileSync(0,'utf8')).id")"
check "create dot" test -n "$DOT_ID"

PAGE_JSON="$(curl -sf -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"title":"Smoke Page","content":"# hello"}' http://127.0.0.1:$PORT/api/spaces/$SPACE_ID/pages)"
PAGE_ID="$(echo "$PAGE_JSON" | node -p "JSON.parse(require('fs').readFileSync(0,'utf8')).id")"
check "create page" test -n "$PAGE_ID"
check "patch page with revision" \
  curl -sf -H "$AUTH" -H 'Content-Type: application/json' -X PATCH \
  -d '{"title":"Smoke Page","content":"# hello v2","expectedRevision":1}' \
  http://127.0.0.1:$PORT/api/spaces/$SPACE_ID/pages/$PAGE_ID
check "stale revision -> 409" \
  bash -c "curl -s -o /dev/null -w '%{http_code}' -H '$AUTH' -H 'Content-Type: application/json' -X PATCH \
    -d '{\"title\":\"x\",\"content\":\"y\",\"expectedRevision\":1}' \
    http://127.0.0.1:$PORT/api/spaces/$SPACE_ID/pages/$PAGE_ID | grep -q 409"

check "conversation without Intelligence key -> honest 503" \
  bash -c "curl -s -o /dev/null -w '%{http_code}' -H '$AUTH' -H 'Content-Type: application/json' \
    -d '{\"dotId\":\"$DOT_ID\"}' http://127.0.0.1:$PORT/api/conversations | grep -q 503"

check "ai chat without token -> 401" \
  bash -c "curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
    -d '{}' http://127.0.0.1:$PORT/api/ai/chat | grep -q 401"

check "ai chat invalid body -> 400" \
  bash -c "curl -s -H '$AUTH' -H 'Content-Type: application/json' \
    -d '{\"provider\":\"nope\"}' http://127.0.0.1:$PORT/api/ai/chat | grep -q '\"error\"'"

check "ai chat blocks http baseUrl (SSRF guard)" \
  bash -c "curl -s -H '$AUTH' -H 'Content-Type: application/json' -d \
    '{\"provider\":\"custom\",\"baseUrl\":\"http://example.com\",\"apiKey\":\"x\",\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}' \
    http://127.0.0.1:$PORT/api/ai/chat | grep -q 'blocked_url'"

check "ai chat blocks loopback baseUrl (SSRF guard)" \
  bash -c "curl -s -H '$AUTH' -H 'Content-Type: application/json' -d \
    '{\"provider\":\"custom\",\"baseUrl\":\"https://127.0.0.1:9999\",\"apiKey\":\"x\",\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}' \
    http://127.0.0.1:$PORT/api/ai/chat | grep -q 'blocked_url'"

check "ai models invalid provider -> 400" \
  bash -c "curl -s -H '$AUTH' -H 'Content-Type: application/json' \
    -d '{\"provider\":\"nope\",\"apiKey\":\"x\"}' http://127.0.0.1:$PORT/api/ai/models | grep -q '\"error\"'"

check "ai test with bogus key -> honest ok:false" \
  bash -c "curl -s -H '$AUTH' -H 'Content-Type: application/json' -d \
    '{\"provider\":\"openai\",\"apiKey\":\"sk-bogus-key-for-smoke-test\",\"model\":\"gpt-4o-mini\"}' \
    http://127.0.0.1:$PORT/api/ai/test | grep -q '\"ok\":false'"

check "workspace lists created objects" \
  bash -c "curl -sf -H '$AUTH' http://127.0.0.1:$PORT/api/workspace | grep -q '$SPACE_ID'"

check "database file created" test -f "$WORK/smoke.sqlite"

echo "==> Restart: verify persistence"
kill "$SERVER_PID"; wait "$SERVER_PID" 2>/dev/null || true
node dist/server/server/index.js > "$WORK/server2.log" 2>&1 &
SERVER_PID=$!
for i in $(seq 1 60); do
  curl -sf "http://127.0.0.1:$PORT/api/health" > /dev/null 2>&1 && break
  sleep 0.5
done
check "space survives restart" \
  bash -c "curl -sf -H '$AUTH' http://127.0.0.1:$PORT/api/workspace | grep -q '$SPACE_ID'"

echo "==> $pass passed, $fail failed"
[ "$fail" = 0 ]
