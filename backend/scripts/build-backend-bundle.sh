#!/usr/bin/env bash
# Builds the Android backend bundle from an OpenDots source tree.
# Usage: build-backend-bundle.sh <upstream_src_dir> <output_dir> [upstream_ref]
# Output: <output_dir>/opendots-backend.zip + opendots-backend-version.txt
# The zip contains: package.json, dist/server/**, pruned node_modules/**.
set -euo pipefail

SRC="${1:?upstream src dir}"
OUT="${2:?output dir}"
REF="${3:-unknown}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PATCHES="$HERE/../android-patches"

echo "==> Applying Android compatibility patches"
cd "$SRC"
for p in "$PATCHES"/*.patch; do
  echo "  - $(basename "$p")"
  patch -p1 --forward < "$p"
done

echo "==> Installing dependencies (npm ci)"
npm ci --no-audit --no-fund

echo "==> Type-checking server (tsc)"
npx tsc -p tsconfig.server.json

ENTRY="dist/server/server/index.js"
if [ ! -f "$ENTRY" ]; then
  echo "FATAL: expected server entry $ENTRY not built" >&2
  exit 1
fi
echo "  entry OK: $ENTRY"

echo "==> Removing client-only deps from the manifest (React client + browser service)"
# These are never imported by dist/server/**:
# - the React client (react, react-dom, tiptap, markdown renderers, icons,
#   @copilotkit/react-core): replaced by the native Android UI
# - playwright: only the standalone browser service (src/browser) uses it,
#   which is not run on Android (optional remote instead)
# `npm pkg delete` keeps package.json AND package-lock.json consistent, so
# the reinstall below yields a truly clean tree (no orphaned transitives).
npm pkg delete devDependencies \
  dependencies.react dependencies.react-dom \
  dependencies.lucide-react dependencies.react-markdown dependencies.remark-gfm \
  dependencies.playwright \
  dependencies.@copilotkit/react-core \
  dependencies.@tiptap/core dependencies.@tiptap/extension-placeholder \
  dependencies.@tiptap/extension-table dependencies.@tiptap/extension-task-item \
  dependencies.@tiptap/extension-task-list dependencies.@tiptap/markdown \
  dependencies.@tiptap/pm dependencies.@tiptap/react \
  dependencies.@tiptap/starter-kit dependencies.@tiptap/suggestion

echo "==> Reinstalling production-only dependencies (clean tree)"
rm -rf node_modules
npm ci --no-audit --no-fund

echo "==> Dropping test-runner leftovers (only imported by *.test.js in packages)"
# vitest/vite ride along as transitive deps of @copilotkit/channels but are
# only imported by test files shipped inside packages — never at runtime.
rm -rf node_modules/vitest node_modules/vite "node_modules/@vitest"
# Shipped test files inside packages are never imported at runtime.
find node_modules -type f -name "*.test.js" -delete || true
find node_modules -type f -name "*.test.d.ts" -delete || true
# Trim per-package docs to save APK size (code only).
find node_modules -type f \( -iname "readme*" -o -iname "license*" -o -iname "*.md" \) -delete || true

echo "==> Writing version stamp"
PKG_VERSION="$(node -p "require('./package.json').version")"
VERSION="opendots-${PKG_VERSION}-android1 ref=${REF} built=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
mkdir -p "$OUT"
echo "$VERSION" > "$OUT/opendots-backend-version.txt"
echo "  $VERSION"

echo "==> Creating backend zip"
rm -f "$OUT/opendots-backend.zip"
# .bin symlinks are build-time only; the server never executes them.
zip -qr "$OUT/opendots-backend.zip" package.json dist node_modules \
  -x "node_modules/.package-lock.json" "node_modules/.bin/*"
ls -lh "$OUT/opendots-backend.zip" "$OUT/opendots-backend-version.txt"
echo "BUILD OK"
