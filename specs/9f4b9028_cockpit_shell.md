# Plan: Task Wave 1 "cockpit-shell" of Milestone D (autonomous Factory Cockpit)

## Overview
Implement Task Wave 1 "cockpit-shell" of Milestone D on branch `sbx/coday-cockpit-shell-edda`.
This wave introduces the governed HTML shell `cockpit.html`, stylesheet `css/dockyard.css`, client services `api-client.mjs` and `sse-client.mjs`, client router `app.mjs`, server static route integration in `composition-root.mjs`, and a node-based test suite in `factory/tests/test-cockpit-shell.mjs`.

The implementation uses pure Vanilla ESM JS with zero dependencies, zero build step, and zero external JS framework. It coexists with the existing `index.html` monolith.

---

## Proposed Changes

### 1. Visual Identity & Stylesheet: `factory/dashboard/css/dockyard.css`
Create the Coday Dockyard deep-space theme stylesheet with exact tokens, native modal support, animations, reduced motion, and responsive breakpoints.

- Design tokens (`:root`):
  ```css
  :root {
    color-scheme: dark;
    --bg:#06080f; --panel:#0d1119; --panel-2:#131a26; --panel-3:#0a0e16;
    --border:#232c3d; --border-soft:#222b3d;
    --text:#f2f5fa; --dim:#aabdd5; --faint:#8b9cb6;
    --green:#4ade80; --red:#ff6f67; --blue:#6cb6ff; --amber:#e8b64a;
    --purple:#c89bff; --cyan:#5ad2dd; --violet:#94a3ff;
    --sans:'Play','Helvetica Neue',system-ui,sans-serif;
    --mono:ui-monospace,'SF Mono',Menlo,Monaco,'Roboto Mono',monospace;
    --surface:linear-gradient(180deg,#10141f 0%,#0b0f18 100%);
  }
  ```
- Body styling: `background: var(--bg)`, subtle radial gradients (`radial-gradient(circle at 20% 10%, rgba(200, 155, 255, 0.05) 0%, transparent 40%), radial-gradient(circle at 80% 80%, rgba(90, 210, 221, 0.05) 0%, transparent 40%)`), `background-attachment: fixed`, `font-family: var(--sans)`, `color: var(--text)`.
- Topbar: sticky top (`position: sticky; top: 0; z-index: 100`), `background: rgba(13, 17, 25, 0.85)`, `backdrop-filter: blur(12px) -webkit-backdrop-filter: blur(12px)`, border bottom `--border`.
- Brand / Logo: 3 vertical bars (amber, purple, cyan), brand text with gradient (`linear-gradient(90deg, var(--purple), var(--cyan))`, `-webkit-background-clip: text`, `-webkit-text-fill-color: transparent`).
- Live Indicator Dot: glowing dot with `@keyframes pulse` animation (opacity/scale keyframes).
- Status Chips:
  - Base chip class with tabular-nums and `font-family: var(--mono)`.
  - Variants: success (green border/text, box-shadow glow), fail (red border/text glow), running (blue border/text glow with `@keyframes spin`), wave (amber border/text glow).
- Panels: background `var(--surface)`, border `1px solid var(--border)`, `border-radius: 16px`, padding, uppercase letterspacing headers (`text-transform: uppercase; letter-spacing: 0.08em; color: var(--dim)`).
- Buttons:
  - Primary button: `background: linear-gradient(135deg, var(--purple), var(--violet))`, color `#06080f`, font-weight 600, glow shadow on hover.
  - Danger button: `background: var(--red)`, color `#06080f`, glow shadow on hover.
- Native `<dialog>` modal styling:
  - `#cockpit-dialog`: background `var(--panel)`, border `1px solid var(--border)`, `border-radius: 16px`, color `var(--text)`, padding.
  - `::backdrop`: `backdrop-filter: blur(8px)`, `-webkit-backdrop-filter: blur(8px)`, `background: rgba(6, 8, 15, 0.7)`.
- Monospace Data Rule: apply `font-family: var(--mono)` to all data elements (IDs, durations, tokens, hashes, status numbers, `.mono`, `code`, `pre`).
- Reduced motion: `@media (prefers-reduced-motion: reduce)` disabling all transitions, animations, pulse, spin.
- Responsive design: 3 breakpoints:
  - Mobile (`@media (max-width: 480px)`)
  - Tablet (`@media (max-width: 900px)`)
  - Desktop (default layout)

---

### 2. Standalone HTML Shell: `factory/dashboard/cockpit.html`
Create the governed standalone HTML shell.

- HTML structure:
  - `<!DOCTYPE html>` with `<html lang="en">`.
  - `<head>`: charset utf-8, viewport meta, `<title>Coday Factory Cockpit</title>`.
  - Google Fonts link for 'Play' with system font stack fallback in CSS (`--sans: 'Play', 'Helvetica Neue', system-ui, sans-serif;`).
  - Stylesheet link: `<link rel="stylesheet" href="/css/dockyard.css">`.
- Topbar:
  - Logo with 3 vertical bars (amber, purple, cyan CSS bars) and Coday Dockyard text with brand gradient.
  - Live indicator dot (live status container with dot and text).
  - Navigation links: `<nav class="cockpit-nav">`: `<a href="#/runs">Runs</a>`, `<a href="#/detail">Détail</a>`, `<a href="#/projection">Projection</a>`, `<a href="#/forge">Forge</a>`, `<a href="#/admin">Admin</a>`.
- Main view container: `<main id="cockpit-view-container"></main>`.
- View sections / placeholders inside main:
  - `<section id="view-runs" class="cockpit-view">...</section>`
  - `<section id="view-detail" class="cockpit-view">...</section>`
  - `<section id="view-projection" class="cockpit-view">...</section>`
  - `<section id="view-forge" class="cockpit-view">...</section>`
  - `<section id="view-admin" class="cockpit-view">...</section>`
- Native Modal: `<dialog id="cockpit-dialog"><div id="cockpit-dialog-content"></div></dialog>`.
- ES Module Script inclusion: `<script type="module" src="/js/app.mjs"></script>`.

---

### 3. REST API Client: `factory/dashboard/js/services/api-client.mjs`
Create lightweight REST API wrapper class / module.

- `ApiClient` class (or module functions / class instance):
  - Constructor takes `{ baseUrl = '', headers = {} }`.
  - Method `request(path, options = {})`:
    - Wraps native `fetch`.
    - Attribution headers: automatically inject `X-Factory-*` headers from options/state if provided (e.g. `X-Factory-Namespace-Id`, `X-Factory-Case-Id`, `X-Factory-Actor-Id`). Note: strict comment and design note that these are for *attribution only, NEVER authorization*.
    - Correlation ID propagation: checks `options.correlationId` or `options.headers['X-Correlation-Id']`, generates `crypto.randomUUID()` if missing, sets header `X-Correlation-Id`.
    - Handles request payload serialization (`JSON.stringify(body)` if object and missing Content-Type).
    - Checks `response.ok` (HTTP 200-299):
      - Parses response as JSON (or text if non-json).
      - Response unwrapping: if parsed object has shape `{ data: ... }` (where `data` is defined and object has no error property), unwraps and returns `parsed.data`. Otherwise returns raw parsed payload (objects, arrays, primitives).
    - Error normalization:
      - If HTTP status is not ok (401, 403, 404, 500, etc.) or response contains an error field:
      - Normalizes error response format:
        - Format A: `{ error: { code, message, ... } }` -> code = `error.code || status`, message = `error.message`.
        - Format B: `{ error: "some message" }` -> code = `HTTP_<STATUS>` or status code string, message = `error`.
        - Format C: Raw non-JSON or missing error body -> code = `HTTP_<STATUS>`, message = statusText or fallback.
      - Throws structured `ApiClientError` object or `Error` with properties `.code`, `.status`, `.message`, `.details`.
  - Exposed helper methods: `get(url, options)`, `post(url, body, options)`, `put(url, body, options)`, `delete(url, body, options)`.

---

### 4. SSE Client: `factory/dashboard/js/services/sse-client.mjs`
Create EventSource subscription manager with event bus and idempotent cleanup.

- `SseClient` class:
  - Constructor `(url, options = {})`.
  - Properties: `this.url`, `this.eventSource = null`, `this.listeners = new Map()`, `this.closed = false`, `this.reconnectTimer = null`.
  - Subscription methods:
    - `on(event, handler)`: adds handler set for named event.
    - `off(event, handler)`: removes handler.
    - `emit(event, data)`: dispatches event data to subscribed handlers.
  - Connection lifecycle:
    - `connect()`: instantiates `new EventSource(this.url)`.
    - Handles default `message` event: parses JSON payload, emits `'message'`, `data`.
    - Handles named events explicitly: binds event listeners for projection events:
      - `workflow-projection-updated`
      - `workflow-projection-removed`
      - `workflow-projection-restored`
      - `workflow-projection-purged`
      - And any custom registered event name. When fired, parses JSON data and calls `this.emit(eventName, parsedData)`.
    - Handles `error` event:
      - Cleanly handles reconnect / error without duplicate EventSource accumulation.
      - If `this.closed` is false, closes failed `EventSource` and schedules reconnect with proper timer cleanup.
  - Safe & Idempotent Cleanup (`close()`):
    - `close()` can be called multiple times safely.
    - Sets `this.closed = true`.
    - Clears reconnect timer if active (`clearTimeout(this.reconnectTimer)`).
    - Closes internal `EventSource` instance if open (`this.eventSource.close()`).
    - Clears all listeners from `this.listeners`.
    - Guarantees zero resource/memory leaks.

---

### 5. Client Router & SPA Controller: `factory/dashboard/js/app.mjs`
Create the hash-based SPA client router and view lifecycle manager.

- Hash-based routing without page reloads:
  - Listens to `hashchange` and `DOMContentLoaded` on `window`.
  - Route map for Wave 1 skeleton views:
    - `#/runs` -> Run List View
    - `#/detail` -> Run Detail View
    - `#/projection` -> Projection View
    - `#/forge` -> Forge View
    - `#/admin` -> Admin View
    - Default fallback (e.g., `#` or unknown) redirects to `#/runs`.
- View Mounting/Unmounting Lifecycle:
  - Maintains `currentView` instance / teardown handler (`activeTeardowns = []` or active view `unmount()` hook).
  - On every route change:
    1. Executes cleanup for previous view: cancels timers (`clearTimeout`, `setInterval`), calls `close()` on active `SseClient` instances, unsubscribes event listeners.
    2. Activates target view DOM section in `#cockpit-view-container` (hiding inactive sections, displaying active section).
    3. Updates topbar active link visual state (`.active` class on corresponding nav link).
    4. Renders placeholder content for the active skeleton view.
- Live Indicator Controller:
  - Controls topbar live status indicator (online dot + state text).
  - Updates status based on connectivity / active SSE state.
- Modal Helper:
  - Helper functions `showModal(content)` and `closeModal()` interacting with native `<dialog id="cockpit-dialog">`.

---

### 6. Server Integration: `factory/dashboard/composition-root.mjs` & `server.mjs`
Integrate static asset serving for `cockpit.html`, `css/`, and `js/` in `composition-root.mjs`.

- Route handlers inside `composition-root.mjs` (in `createHttpServer` request handler):
  - `GET /cockpit` and `GET /cockpit.html`:
    - Reads and serves `factory/dashboard/cockpit.html`.
    - Header: `Content-Type: text/html; charset=utf-8`, `X-Correlation-Id: res.correlationId`, CORS headers.
  - Static Asset Route matching `GET /css/*` and `GET /js/*`:
    - Sanitize and resolve path inside `factory/dashboard/css/` and `factory/dashboard/js/`. Prevent path traversal (`..`).
    - Check file existence using `existsSync` or `statSync`.
    - If found:
      - For `.css`: `Content-Type: text/css; charset=utf-8`.
      - For `.js` or `.mjs`: `Content-Type: application/javascript; charset=utf-8`.
      - Return file stream / content with 200 status.
    - If file not found in `/css/` or `/js/`:
      - Return 404 error response with `sendError(sendFn, 404, 'NOT_FOUND', 'Asset not found')`.
      - **CRITICAL**: NEVER fall back to serving HTML (`index.html` or `cockpit.html`) on `.mjs` or `.css` requests!
  - Existing routes:
    - `GET /` and `GET /index.html` continue serving `index.html` unchanged.
  - Auth / Policy Boundary:
    - Do NOT weaken existing B6 auth/trust boundary, TrustContext extraction, or loopback isolation policies.

---

### 7. Automated Tests: `factory/tests/test-cockpit-shell.mjs`
Create node-based test suite testing `api-client.mjs`, `sse-client.mjs`, and server HTTP endpoints.

- Test structure using node assertion module (`import assert from 'node:assert/strict'`):
- Test cases for `api-client.mjs`:
  1. `{ data: ... }` unwrapping: server returns `{ data: { items: [1, 2] } }`, client unwraps and returns `{ items: [1, 2] }`.
  2. Raw payload tolerance: server returns `{ raw: true }` or `[1, 2, 3]`, client returns payload as-is.
  3. Error format A (`{ error: { code: 'INVALID', message: 'bad input' } }`): client throws normalized error with `code === 'INVALID'` and `message === 'bad input'`.
  4. Error format B (`{ error: 'Something broke' }`): client throws normalized error with `message === 'Something broke'`.
  5. HTTP Status error handling (401, 403, 404, 500): verifies error throwing with expected status code.
  6. Correlation ID propagation: verifies `X-Correlation-Id` is sent in headers (provided or generated).
  7. Attribution headers: verifies `X-Factory-*` headers are included when options specify them.
- Test cases for `sse-client.mjs`:
  1. Event bus subscription and emission (`on`, `off`, `emit`).
  2. Named event dispatching: mock EventSource dispatching `workflow-projection-updated`, `workflow-projection-removed`, `workflow-projection-restored`, `workflow-projection-purged` triggers subscribed handlers.
  3. Safe and idempotent `close()`: calling `close()` multiple times does not throw, closes internal EventSource, clears timers and listeners.
  4. Zero leak verification: after `close()`, listeners map is empty and EventSource listener callbacks are detached.
- Test cases for Server HTTP Routes:
  1. Spin up composition root server on dynamic port (`PORT=0` or find free port).
  2. `GET /cockpit`: status 200, header `text/html; charset=utf-8`, body contains `<dialog id="cockpit-dialog">` and topbar.
  3. `GET /cockpit.html`: status 200, header `text/html; charset=utf-8`.
  4. `GET /css/dockyard.css`: status 200, header `text/css; charset=utf-8`, body contains `:root` design tokens (`--bg:#06080f`).
  5. `GET /js/app.mjs`: status 200, header `application/javascript; charset=utf-8`.
  6. `GET /js/services/api-client.mjs`: status 200, header `application/javascript; charset=utf-8`.
  7. `GET /js/services/sse-client.mjs`: status 200, header `application/javascript; charset=utf-8`.
  8. `GET /css/nonexistent.css`: status 404, `Content-Type` is JSON error, NOT HTML fallback.
  9. `GET /index.html` and `GET /`: status 200, original monolith dashboard returned unchanged.
  10. Teardown test server after test run.

---

## Verification Plan

### Automated Tests
Run the newly created test suite:
```bash
node factory/tests/test-cockpit-shell.mjs
```

Run existing composition root and factory tests to ensure no regressions:
```bash
node factory/tests/test-composition-root-source.mjs
node factory/tests/test-factory-api.mjs
```

Run full factory test suite:
```bash
node --test factory/tests/test-*.mjs
```

Run Nx quality checks:
```bash
pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2
pnpm nx affected -t lint --base="$(cat /work/data/baseline)"
```

### Manual Verification
1. Start factory dashboard server:
   ```bash
   PORT=3141 node factory/dashboard/server.mjs
   ```
2. Verify endpoints with curl:
   - `curl -i http://localhost:3141/cockpit` -> 200 OK, HTML
   - `curl -i http://localhost:3141/css/dockyard.css` -> 200 OK, Content-Type: text/css
   - `curl -i http://localhost:3141/js/app.mjs` -> 200 OK, Content-Type: application/javascript
   - `curl -i http://localhost:3141/index.html` -> 200 OK, monolith HTML
